package io.github.yinwenyuan28.ailite.config;

import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import io.github.yinwenyuan28.ailite.catalog.NoopRuntimeCatalog;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.catalog.RuntimeCatalog;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmThinking;
import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
import io.github.yinwenyuan28.ailite.util.Strings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 YAML 配置解析成一次调用需要的 {@link ResolvedModel}。
 *
 * <p>校验尽量前移：供应商、模型、profile 的引用关系在构造时就检查，
 * 密钥和业务 id 在调用时再检查，避免启动阶段依赖外部环境变量。</p>
 *
 * <p>多 key 轮换（限流熔断计划 §3.4）：配置了 {@code api-keys} 的供应商在
 * {@link #markKeyFailure}（429/401 失败标记）时推进游标并冷却当前 key，
 * resolve 只读游标——无失败不轮换。轮换状态用原子类型，resolve 并发调用安全；
 * 冷却惰性到期判定（时间戳比较），无定时器。key 值只参与值匹配，永不进日志。</p>
 */
public final class ModelResolver {

    /** 默认 key 冷却时长（未显式传入时）。 */
    private static final long DEFAULT_KEY_COOLDOWN_MS = 60_000L;

    private final AiLiteConfig.Llm config;
    private final boolean configured;
    private final Set<String> bizIds;
    private final Map<String, AiLiteConfig.Provider> providers;
    private final Map<String, AiLiteConfig.Profile> profiles;
    private final long keyCooldownMs;
    private final RuntimeCatalog catalog;
    /** providerId → 轮换状态；只在配置了 api-keys 的 provider 上创建。 */
    private final ConcurrentHashMap<String, KeyRotation> rotations = new ConcurrentHashMap<String, KeyRotation>();

    /**
     * @param config 模型配置节
     * @throws LlmCallException 配置结构非法时抛出
     */
    public ModelResolver(AiLiteConfig.Llm config) {
        this(config, DEFAULT_KEY_COOLDOWN_MS);
    }

    /**
     * @param config         模型配置节
     * @param keyCooldownMs  429/401 失败标记后单把 key 的冷却毫秒数
     * @throws LlmCallException 配置结构非法时抛出
     */
    public ModelResolver(AiLiteConfig.Llm config, long keyCooldownMs) {
        this(config, keyCooldownMs, null);
    }

    /**
     * @param config         模型配置节
     * @param keyCooldownMs  429/401 失败标记后单把 key 的冷却毫秒数
     * @param catalog        运行时目录；null 表示不启用
     * @throws LlmCallException 配置结构非法时抛出
     */
    public ModelResolver(AiLiteConfig.Llm config, long keyCooldownMs, RuntimeCatalog catalog) {
        this.config = config == null ? new AiLiteConfig.Llm() : config;
        this.providers = this.config.getProviders();
        this.profiles = this.config.getProfiles();
        this.configured = providers != null && !providers.isEmpty();
        this.keyCooldownMs = Math.max(0L, keyCooldownMs);
        this.catalog = catalog == null ? NoopRuntimeCatalog.INSTANCE : catalog;
        this.bizIds = new HashSet<String>();
        if (this.config.getBizIds() != null) {
            for (String id : this.config.getBizIds()) {
                if (Strings.isNotBlank(id)) {
                    bizIds.add(id.trim());
                }
            }
        }
        validateStructure();
    }

    /**
     * @return 目录是否参与业务解析
     */
    public boolean isCatalogActive() {
        return catalog.isActive();
    }

    /**
     * @return 是否至少配置了一个供应商
     */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * @param bizId 业务 id
     * @return 该业务是否已登记
     */
    public boolean allows(String bizId) {
        return bizId != null && bizIds.contains(bizId.trim());
    }

    /**
     * @return 默认 profile 名称，可能为 null
     */
    public String getDefaultProfile() {
        return Strings.trimToNull(config.getDefaultProfile());
    }

    /**
     * 显式解析供应商和模型。
     *
     * @param provider      供应商 id
     * @param modelId       模型 id
     * @param thinkingLevel 思考档，可空
     * @return 解析结果
     */
    public ResolvedModel resolveExplicit(String provider, String modelId, String thinkingLevel) {
        String providerId = Strings.trimToNull(provider);
        if (providerId == null) {
            throw new LlmCallException("供应商不能为空", null, LlmCallException.Kind.CONFIG);
        }
        AiLiteConfig.Provider p = providers.get(providerId);
        if (p == null) {
            throw new LlmCallException("未知供应商: " + providerId, null, LlmCallException.Kind.CONFIG);
        }
        AiLiteConfig.Model m = findModel(p, modelId);
        if (m == null) {
            throw new LlmCallException("未知模型: " + modelId, null, LlmCallException.Kind.CONFIG);
        }
        return resolve(providerId, p, m, thinkingLevel);
    }

    /**
     * 按预置 profile 解析。
     *
     * @param profileName profile 名称
     * @return 解析结果
     */
    public ResolvedModel resolveProfile(String profileName) {
        String name = Strings.trimToNull(profileName);
        if (name == null) {
            throw new LlmCallException("配置档不能为空", null, LlmCallException.Kind.CONFIG);
        }
        AiLiteConfig.Profile profile = profiles.get(name);
        if (profile == null) {
            throw new LlmCallException("未知配置档: " + name, null, LlmCallException.Kind.CONFIG);
        }
        AiLiteConfig.Provider p = providers.get(Strings.trimToNull(profile.getProvider()));
        if (p == null) {
            throw new LlmCallException("配置档 " + name + " 指向未知供应商: " + profile.getProvider(), null, LlmCallException.Kind.CONFIG);
        }
        AiLiteConfig.Model m = findModel(p, profile.getModel());
        if (m == null) {
            throw new LlmCallException("配置档 " + name + " 指向未知模型: " + profile.getModel(), null, LlmCallException.Kind.CONFIG);
        }
        return resolve(Strings.trimToNull(profile.getProvider()), p, m, profile.getThinkingLevel());
    }

    /**
     * 按业务 id 从运行时目录解析。没有绑定、模型未就绪或目录未启用时失败，不回退配置档。
     *
     * @param bizId 业务 id
     * @return 解析结果
     */
    public ResolvedModel resolveBiz(String bizId) {
        if (!catalog.isActive()) {
            throw new LlmCallException("运行时目录未启用", null, LlmCallException.Kind.CONFIG);
        }
        String id = Strings.trimToNull(bizId);
        BizBinding binding = id == null ? null : catalog.findBinding(id);
        if (binding == null || Strings.isBlank(binding.getProviderId()) || Strings.isBlank(binding.getModelId())) {
            throw new LlmCallException("业务未配置模型: " + bizId, null, LlmCallException.Kind.CONFIG);
        }
        String providerId = binding.getProviderId().trim();
        AiLiteConfig.Provider source = providers.get(providerId);
        if (source == null) {
            throw new LlmCallException("未知供应商: " + providerId, null, LlmCallException.Kind.CONFIG);
        }
        CatalogModel row = catalog.findModel(providerId, binding.getModelId().trim());
        if (row == null || !row.isReady()) {
            throw new LlmCallException("模型尚未补齐能力参数: " + binding.getModelId(), null, LlmCallException.Kind.CONFIG);
        }
        AiLiteConfig.Provider effective = applyOverlay(source, catalog.findProvider(providerId));
        return resolve(providerId, effective, toConfigModel(row), binding.getThinkingLevel());
    }

    /**
     * 复制供应商并套上非空覆盖。配置了密钥池时不改单把密钥，调用和探测都走池。
     */
    private static AiLiteConfig.Provider applyOverlay(AiLiteConfig.Provider source, ProviderOverlay overlay) {
        AiLiteConfig.Provider copy = new AiLiteConfig.Provider();
        copy.setApi(source.getApi());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setApiKey(source.getApiKey());
        copy.setApiKeyEnv(source.getApiKeyEnv());
        copy.setApiKeys(source.getApiKeys() == null
                ? new ArrayList<String>() : new ArrayList<String>(source.getApiKeys()));
        copy.setHeaders(new LinkedHashMap<String, String>(source.getHeaders()));
        copy.setExtraBody(new LinkedHashMap<String, Object>(source.getExtraBody()));
        copy.setConnectTimeoutMs(source.getConnectTimeoutMs());
        copy.setReadTimeoutMs(source.getReadTimeoutMs());
        copy.setModels(source.getModels());
        copy.setGuard(source.getGuard());
        if (overlay == null) {
            return copy;
        }
        if (Strings.isNotBlank(overlay.getBaseUrl())) {
            copy.setBaseUrl(overlay.getBaseUrl().trim());
        }
        boolean pool = copy.getApiKeys() != null && !copy.getApiKeys().isEmpty();
        if (!pool && Strings.isNotBlank(overlay.getApiKey())) {
            copy.setApiKey(overlay.getApiKey().trim());
        }
        if (overlay.getConnectTimeoutMs() != null) {
            copy.setConnectTimeoutMs(overlay.getConnectTimeoutMs());
        }
        if (overlay.getReadTimeoutMs() != null) {
            copy.setReadTimeoutMs(overlay.getReadTimeoutMs());
        }
        return copy;
    }

    private static AiLiteConfig.Model toConfigModel(CatalogModel row) {
        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId(row.getModelId());
        model.setName(row.getDisplayName());
        model.setReasoning(row.isReasoning());
        if (row.getInputModes() == null || row.getInputModes().isEmpty()) {
            List<String> text = new ArrayList<String>();
            text.add("text");
            model.setInput(text);
        } else {
            model.setInput(new ArrayList<String>(row.getInputModes()));
        }
        model.setContextWindow(row.getContextWindow());
        model.setMaxTokens(row.getMaxTokens() == null ? Integer.valueOf(8192) : row.getMaxTokens());
        model.setThinkingFormat(Strings.isBlank(row.getThinkingFormat())
                ? "reasoning-effort" : row.getThinkingFormat().trim());
        return model;
    }

    private ResolvedModel resolve(String providerId, AiLiteConfig.Provider p, AiLiteConfig.Model m,
                                  String requestedLevel) {
        LlmThinkingLevel requested = Strings.isBlank(requestedLevel)
                ? LlmThinkingLevel.OFF
                : LlmThinkingLevel.parse(requestedLevel);
        LlmThinkingLevel effective = LlmThinking.clamp(requested, m.isReasoning(), m.getThinkingLevelMap());

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.putAll(p.getHeaders());
        headers.putAll(m.getHeaders());

        Map<String, Object> extraBody = new LinkedHashMap<String, Object>();
        extraBody.putAll(p.getExtraBody());
        extraBody.putAll(m.getExtraBody());

        Map<String, Object> sampler = new LinkedHashMap<String, Object>();
        sampler.putAll(m.getSamplerParams());
        Map<String, Object> byLevel = m.getSamplerParamsByThinkingLevel().get(effective.wire());
        if (byLevel != null) {
            sampler.putAll(byLevel);
        }

        int maxTokens = m.getMaxTokens() == null || m.getMaxTokens().intValue() <= 0
                ? 8192 : m.getMaxTokens().intValue();
        int contextWindow = m.getContextWindow() == null ? 0 : m.getContextWindow().intValue();
        int connect = p.getConnectTimeoutMs() == null ? 10000 : p.getConnectTimeoutMs().intValue();
        int read = p.getReadTimeoutMs() == null ? 120000 : p.getReadTimeoutMs().intValue();
        boolean acceptsImage = m.getInput() != null && m.getInput().contains("image");

        return new ResolvedModel(providerId, p.getApi(), trimTrailingSlash(p.getBaseUrl()),
                apiKeyFor(providerId, p), headers, m.getId(), contextWindow, maxTokens,
                acceptsImage, m.isReasoning(), effective, m.getThinkingLevelMap(),
                Strings.isBlank(m.getThinkingFormat()) ? "none" : m.getThinkingFormat().trim(),
                sampler, extraBody, connect, read);
    }

    private static AiLiteConfig.Model findModel(AiLiteConfig.Provider provider, String modelId) {
        String id = Strings.trimToNull(modelId);
        if (id == null || provider.getModels() == null) {
            return null;
        }
        for (AiLiteConfig.Model model : provider.getModels()) {
            if (model != null && id.equals(Strings.trimToNull(model.getId()))) {
                return model;
            }
        }
        return null;
    }

    private static String requireApiKey(String providerId, AiLiteConfig.Provider p) {
        String key = Strings.trimToNull(p.getApiKey());
        if (key == null && Strings.isNotBlank(p.getApiKeyEnv())) {
            key = Strings.trimToNull(System.getenv(p.getApiKeyEnv().trim()));
        }
        key = resolvePlaceholder(key);
        if (key == null) {
            throw new LlmCallException("模型密钥未配置: " + providerId, null, LlmCallException.Kind.CONFIG);
        }
        return key;
    }

    /**
     * 取一次调用使用的密钥：配置了 api-keys 时从轮换池取（优先于单 key），
     * 否则走单 key 路径。
     */
    private String apiKeyFor(String providerId, AiLiteConfig.Provider p) {
        List<String> keys = resolveKeys(p);
        if (keys.isEmpty()) {
            return requireApiKey(providerId, p);
        }
        return pickKey(providerId, keys);
    }

    /**
     * 解析 api-keys 列表：每个元素支持与单 key 相同的占位符；解析为 null 的
     * 条目（env 缺失且无默认值）跳过。全部无效时抛出——配置了池却无可用 key
     * 属于配置错误，不能静默退化。
     */
    private List<String> resolveKeys(AiLiteConfig.Provider p) {
        List<String> raw = p.getApiKeys();
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<String>(0);
        }
        List<String> resolved = new ArrayList<String>(raw.size());
        for (String item : raw) {
            String key = resolvePlaceholder(item);
            if (key != null) {
                resolved.add(key);
            }
        }
        if (resolved.isEmpty()) {
            throw new LlmCallException("模型密钥未配置: api-keys 全部无效", null, LlmCallException.Kind.CONFIG);
        }
        return resolved;
    }

    /**
     * 从游标起找第一个未冷却的 key（惰性到期判定）；全部冷却时等价单 key，
     * 回游标位（失败标记已保证游标指向第一个）。resolve 不推进游标。
     */
    private String pickKey(String providerId, List<String> keys) {
        KeyRotation rotation = rotationFor(providerId, keys.size());
        long now = System.currentTimeMillis();
        int start = Math.min(rotation.cursor.get(), keys.size() - 1);
        for (int i = 0; i < keys.size(); i++) {
            int idx = (start + i) % keys.size();
            if (rotation.cooldownUntil[idx].get() <= now) {
                return keys.get(idx);
            }
        }
        return keys.get(start);
    }

    /**
     * 429/401 失败标记（限流熔断计划 §3.4）：按 key 值匹配索引标记冷却，
     * 游标切到其后第一个未冷却 key；全部冷却则回第一个（等价单 key）。
     * 值不匹配（env 已变）不乱标；游标只在失败标记时推进。
     *
     * @param providerId 供应商 id
     * @param apiKeyValue 本次调用实际使用的 key 值（来自 ResolvedModel）
     */
    public void markKeyFailure(String providerId, String apiKeyValue) {
        if (providerId == null || apiKeyValue == null) {
            return;
        }
        AiLiteConfig.Provider p = providers.get(providerId);
        if (p == null) {
            return;
        }
        List<String> keys;
        try {
            keys = resolveKeys(p);
        } catch (LlmCallException ignored) {
            // 池在此刻全部失效：无从标记，保持现状。
            return;
        }
        if (keys.isEmpty()) {
            return;
        }
        KeyRotation rotation = rotationFor(providerId, keys.size());
        int matched = -1;
        for (int i = 0; i < keys.size(); i++) {
            if (apiKeyValue.equals(keys.get(i))) {
                matched = i;
                break;
            }
        }
        if (matched < 0) {
            return;
        }
        rotation.cooldownUntil[matched].set(System.currentTimeMillis() + keyCooldownMs);
        long now = System.currentTimeMillis();
        for (int i = 1; i <= keys.size(); i++) {
            int idx = (matched + i) % keys.size();
            if (i == keys.size()) {
                // 全部冷却：回第一个（等价单 key）。
                rotation.cursor.set(0);
                break;
            }
            if (rotation.cooldownUntil[idx].get() <= now) {
                rotation.cursor.set(idx);
                break;
            }
        }
    }

    /** 取（或建）该 provider 的轮换状态；配置条数变化时重建（热更新兜底）。 */
    private KeyRotation rotationFor(String providerId, int size) {
        KeyRotation existing = rotations.get(providerId);
        if (existing != null && existing.size == size) {
            return existing;
        }
        KeyRotation created = new KeyRotation(size);
        KeyRotation winner = rotations.putIfAbsent(providerId, created);
        if (winner != null && winner.size == size) {
            return winner;
        }
        rotations.put(providerId, created);
        return created;
    }

    /** 多 key 轮换状态：游标 + 每 key 冷却到期时间戳（0 = 未冷却）。 */
    private static final class KeyRotation {
        private final int size;
        private final AtomicInteger cursor = new AtomicInteger(0);
        private final AtomicLong[] cooldownUntil;

        private KeyRotation(int size) {
            this.size = Math.max(1, size);
            this.cooldownUntil = new AtomicLong[this.size];
            for (int i = 0; i < this.size; i++) {
                this.cooldownUntil[i] = new AtomicLong(0L);
            }
        }
    }

    /**
     * 支持字面量、{@code ${ENV}} 和 {@code $ENV}。不执行命令，避免配置即执行代码。
     */
    private static String resolvePlaceholder(String value) {
        String text = Strings.trimToNull(value);
        if (text == null) {
            return null;
        }
        if (text.startsWith("${") && text.endsWith("}") && text.length() > 3) {
            return resolveEnv(text.substring(2, text.length() - 1).trim());
        }
        if (text.startsWith("$") && text.length() > 1) {
            return resolveEnv(text.substring(1).trim());
        }
        return text;
    }

    /**
     * 支持 {@code ENV} 和 {@code ENV:default}；默认值只在环境变量缺失时使用。
     */
    private static String resolveEnv(String expression) {
        int colon = expression.indexOf(':');
        String name = colon >= 0 ? expression.substring(0, colon).trim() : expression;
        String fallback = colon >= 0 ? expression.substring(colon + 1) : null;
        String value = Strings.trimToNull(System.getenv(name));
        return value != null ? value : Strings.trimToNull(fallback);
    }

    private static String trimTrailingSlash(String url) {
        String value = Strings.trimToNull(url);
        if (value == null) {
            return null;
        }
        while (value.endsWith("/") && !value.endsWith("://")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private void validateStructure() {
        for (Map.Entry<String, AiLiteConfig.Provider> entry : providers.entrySet()) {
            String id = entry.getKey();
            AiLiteConfig.Provider p = entry.getValue();
            if (p == null) {
                throw new LlmCallException("供应商配置不能为空: " + id, null, LlmCallException.Kind.CONFIG);
            }
            if (Strings.isBlank(p.getApi())) {
                throw new LlmCallException("供应商 " + id + " 缺少 api", null, LlmCallException.Kind.CONFIG);
            }
            if (Strings.isBlank(p.getBaseUrl())) {
                throw new LlmCallException("供应商 " + id + " 缺少 base-url", null, LlmCallException.Kind.CONFIG);
            }
            if (Strings.isBlank(trimTrailingSlash(p.getBaseUrl()))) {
                throw new LlmCallException("供应商 " + id + " 的 base-url 无效", null, LlmCallException.Kind.CONFIG);
            }
            if (!catalog.isActive() && (p.getModels() == null || p.getModels().isEmpty())) {
                throw new LlmCallException("供应商 " + id + " 没有配置模型", null, LlmCallException.Kind.CONFIG);
            }
            if (p.getModels() != null) {
                for (AiLiteConfig.Model m : p.getModels()) {
                    if (m == null || Strings.isBlank(m.getId())) {
                        throw new LlmCallException("供应商 " + id + " 存在缺少 id 的模型", null, LlmCallException.Kind.CONFIG);
                    }
                }
            }
        }
        for (Map.Entry<String, AiLiteConfig.Profile> entry : profiles.entrySet()) {
            AiLiteConfig.Profile profile = entry.getValue();
            if (profile == null) {
                throw new LlmCallException("配置档不能为空: " + entry.getKey(), null, LlmCallException.Kind.CONFIG);
            }
            if (Strings.isBlank(profile.getProvider())) {
                throw new LlmCallException("配置档 " + entry.getKey() + " 缺少 provider", null, LlmCallException.Kind.CONFIG);
            }
            if (Strings.isBlank(profile.getModel())) {
                throw new LlmCallException("配置档 " + entry.getKey() + " 缺少 model", null, LlmCallException.Kind.CONFIG);
            }
            AiLiteConfig.Provider p = providers.get(profile.getProvider().trim());
            if (p == null) {
                throw new LlmCallException("配置档 " + entry.getKey() + " 指向未知供应商", null, LlmCallException.Kind.CONFIG);
            }
            if (findModel(p, profile.getModel()) == null) {
                throw new LlmCallException("配置档 " + entry.getKey() + " 指向未知模型", null, LlmCallException.Kind.CONFIG);
            }
        }
    }
}
