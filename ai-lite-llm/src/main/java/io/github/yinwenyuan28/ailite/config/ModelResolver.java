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
        return resolveExplicit(provider, modelId, thinkingLevel, false);
    }

    /**
     * 显式解析供应商和模型，可选择是否采纳目录里的模型行。
     *
     * <p>{@code useCatalog} 为 true 时，如果目录里有这个模型行，就用它的能力参数与
     * 请求参数（并套用供应商覆盖），等同于业务调用会拿到的结果；目录里没有则退回配置文件。
     * 管理页的「测试调用」用它，才能测出与业务调用一致的行为。</p>
     *
     * <p>与 {@link #resolveBiz} 的差别：这里不要求业务绑定，也不检查就绪状态——
     * 测试调用的目的正是「在绑定之前验证这个模型能不能用」。</p>
     *
     * @param provider      供应商 id
     * @param modelId       模型 id
     * @param thinkingLevel 思考档，可空
     * @param useCatalog    是否采纳目录里的模型行
     * @return 解析结果
     */
    public ResolvedModel resolveExplicit(String provider, String modelId, String thinkingLevel,
                                         boolean useCatalog) {
        String providerId = Strings.trimToNull(provider);
        if (providerId == null) {
            throw new LlmCallException("供应商不能为空", null, LlmCallException.Kind.CONFIG);
        }
        AiLiteConfig.Provider p = providers.get(providerId);
        if (p == null) {
            throw new LlmCallException("未知供应商: " + providerId, null, LlmCallException.Kind.CONFIG);
        }
        if (useCatalog && catalog.isActive()) {
            String id = Strings.trimToNull(modelId);
            CatalogModel row = id == null ? null : catalog.findModel(providerId, id);
            if (row != null) {
                AiLiteConfig.Provider effective = applyOverlay(p, catalog.findProvider(providerId));
                return resolve(providerId, effective, toConfigModel(row, findModel(p, id)),
                        thinkingLevel);
            }
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
            throw new LlmCallException("模型未就绪: " + binding.getModelId(), null, LlmCallException.Kind.CONFIG);
        }
        AiLiteConfig.Provider effective = applyOverlay(source, catalog.findProvider(providerId));
        return resolve(providerId, effective, toConfigModel(row, findModel(source, binding.getModelId().trim())),
                binding.getThinkingLevel());
    }

    /**
     * 复制供应商并套上非空覆盖。配置了密钥池时不改单把密钥，调用和探测都走池。
     *
     * <p>请求头与附加请求体是「整份替换」：覆盖表里存了就用覆盖表那份，不逐键合并。
     * 这与地址、密钥的覆盖语义一致——覆盖是换掉，不是叠加。真正的合并发生在
     * 供应商级与模型级之间，见 {@link #resolve}。</p>
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
        if (Strings.isNotBlank(overlay.getApi())) {
            copy.setApi(overlay.getApi().trim());
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
        // null 表示数据库没配，保留上面从配置文件复制来的那份；空 Map 才是明确清空。
        if (overlay.getHeaders() != null) {
            copy.setHeaders(new LinkedHashMap<String, String>(overlay.getHeaders()));
        }
        if (overlay.getExtraBody() != null) {
            copy.setExtraBody(new LinkedHashMap<String, Object>(overlay.getExtraBody()));
        }
        return copy;
    }

    /**
     * 把目录行转成解析用的模型。
     *
     * <p>解析顺序统一为「数据库 → 配置文件同名模型 → 内置默认」。数据库列是 NULL
     * 表示这一项没填，要沿用配置文件；空 Map 表示明确清空。</p>
     *
     * <p><b>为什么能力参数也要回落</b>：管理页建行时能力参数全是 NULL（见
     * {@code insertModelIfAbsent}），只要没填就启用，就会得到「已启用、参数全空」的
     * 模型。早先这里直接把 NULL 落成内置默认值，结果是上下文窗口 0（预算与自动压缩
     * 整条跳过）、入参只剩 text（图片被拒），而页面显示一切正常。回落之后，
     * 配置文件里写过的能力参数仍然生效。</p>
     *
     * @param row  目录行
     * @param yaml 配置文件里的同名模型；null 表示配置文件里没有这个模型
     * @return 解析用的模型
     */
    private static AiLiteConfig.Model toConfigModel(CatalogModel row, AiLiteConfig.Model yaml) {
        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId(row.getModelId());
        model.setName(row.getDisplayName());
        // 推理开关：数据库没填时用配置文件；两边都没有才用内置默认（true）。
        model.setReasoning(row.getReasoningFlag() != null
                ? row.getReasoningFlag().booleanValue()
                : (yaml != null ? yaml.isReasoning() : new AiLiteConfig.Model().isReasoning()));
        if (row.getInputModes() == null || row.getInputModes().isEmpty()) {
            model.setInput(yaml != null && yaml.getInput() != null && !yaml.getInput().isEmpty()
                    ? new ArrayList<String>(yaml.getInput()) : singletonText());
        } else {
            model.setInput(new ArrayList<String>(row.getInputModes()));
        }
        model.setContextWindow(row.getContextWindow() != null
                ? row.getContextWindow()
                : (yaml != null ? yaml.getContextWindow() : null));
        model.setMaxTokens(row.getMaxTokens() != null
                ? row.getMaxTokens()
                : (yaml != null ? yaml.getMaxTokens() : Integer.valueOf(8192)));
        model.setThinkingFormat(!Strings.isBlank(row.getThinkingFormat())
                ? row.getThinkingFormat().trim()
                : (yaml != null && !Strings.isBlank(yaml.getThinkingFormat())
                        ? yaml.getThinkingFormat().trim() : "reasoning-effort"));
        model.setThinkingLevelMap(copyOr(row.getThinkingLevelMap(),
                yaml == null ? null : yaml.getThinkingLevelMap()));
        model.setSamplerParams(copyOr(row.getSamplerParams(),
                yaml == null ? null : yaml.getSamplerParams()));
        model.setSamplerParamsByThinkingLevel(copyByLevel(row.getSamplerParamsByLevel(),
                yaml == null ? null : yaml.getSamplerParamsByThinkingLevel()));
        model.setHeaders(copyOr(row.getModelHeaders(),
                yaml == null ? null : yaml.getHeaders()));
        model.setExtraBody(copyOr(row.getExtraBody(),
                yaml == null ? null : yaml.getExtraBody()));
        return model;
    }

    /**
     * @return 只含 text 的入参类型列表，避免每个调用点各写一遍
     */
    private static List<String> singletonText() {
        List<String> text = new ArrayList<String>();
        text.add("text");
        return text;
    }

    /**
     * 取数据库值；数据库没配时回落配置文件；两边都没有则给空 Map。
     *
     * <p>返回空 Map 而不是 null，是因为下游 {@code resolve} 直接在上面做
     * {@code putAll}，null 会抛空指针。</p>
     *
     * @param fromDb   数据库值，null 表示没配
     * @param fromYaml 配置文件值，null 表示没有
     * @param <V>      值类型
     * @return 可写的副本
     */
    private static <V> Map<String, V> copyOr(Map<String, V> fromDb, Map<String, V> fromYaml) {
        if (fromDb != null) {
            return new LinkedHashMap<String, V>(fromDb);
        }
        if (fromYaml != null) {
            return new LinkedHashMap<String, V>(fromYaml);
        }
        return new LinkedHashMap<String, V>();
    }

    /**
     * 嵌套 Map 版本的回落，规则同 {@link #copyOr}。
     *
     * @param fromDb   数据库值，null 表示没配
     * @param fromYaml 配置文件值，null 表示没有
     * @return 可写的副本
     */
    private static Map<String, Map<String, Object>> copyByLevel(
            Map<String, Map<String, Object>> fromDb, Map<String, Map<String, Object>> fromYaml) {
        if (fromDb != null) {
            return new LinkedHashMap<String, Map<String, Object>>(fromDb);
        }
        if (fromYaml != null) {
            return new LinkedHashMap<String, Map<String, Object>>(fromYaml);
        }
        return new LinkedHashMap<String, Map<String, Object>>();
    }

    private ResolvedModel resolve(String providerId, AiLiteConfig.Provider p, AiLiteConfig.Model m,
                                  String requestedLevel) {
        LlmThinkingLevel requested = Strings.isBlank(requestedLevel)
                ? LlmThinkingLevel.OFF
                : LlmThinkingLevel.parse(requestedLevel);
        LlmThinkingLevel effective = LlmThinking.clamp(requested, m.isReasoning(), m.getThinkingLevelMap());

        // 供应商级在前、模型级在后；同名（忽略大小写）时模型级覆盖。见 putHeaderIgnoreCase。
        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : p.getHeaders().entrySet()) {
            putHeaderIgnoreCase(headers, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : m.getHeaders().entrySet()) {
            putHeaderIgnoreCase(headers, entry.getKey(), entry.getValue());
        }

        // 供应商级在前、模型级在后，递归合并：嵌套对象按子键合并，不是整块替换。
        // 逐块替换会让「模型级只想补一个小项」把供应商级的同父子键全冲掉。
        Map<String, Object> extraBody = new LinkedHashMap<String, Object>();
        deepMerge(extraBody, p.getExtraBody());
        deepMerge(extraBody, m.getExtraBody());

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

    /**
     * 递归合并 Map：嵌套对象按子键合并，其余按键覆盖。
     *
     * <p>与传输层 {@code TransportSupport.merge} 的语义一致，保证「两级之间」与
     * 「配置与单次调用之间」的合并规则相同。此前这里用的是 {@code putAll}（整块替换），
     * 于是模型级写一个同父键的小项，会把供应商级该父键下的子键全部冲掉。</p>
     *
     * <p>合并时把嵌套 Map 复制一份再往里写，避免修改 source 里的对象——
     * source 直接来自配置对象，改它会污染全局配置。</p>
     *
     * @param target 目标
     * @param source 来源；null 或空时不动
     */
    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            Object incoming = entry.getValue();
            Object existing = target.get(key);
            if (incoming instanceof Map && existing instanceof Map) {
                deepMerge((Map<String, Object>) existing, (Map<String, Object>) incoming);
            } else if (incoming instanceof Map) {
                Map<String, Object> copy = new LinkedHashMap<String, Object>();
                deepMerge(copy, (Map<String, Object>) incoming);
                target.put(key, copy);
            } else {
                target.put(key, incoming);
            }
        }
    }

    /**
     * 头名忽略大小写地放入。已存在同名键时先移除旧的，实现「后来的覆盖先前的」。
     *
     * <p>HTTP 头名不区分大小写，但 {@code LinkedHashMap} 区分。直接用 {@code putAll}
     * 合并两级时，{@code X-Foo} 与 {@code x-foo} 会变成两个头一起发出去：服务器可能
     * 取第一个（覆盖没生效），也可能因重复头直接报错。</p>
     *
     * <p>保留后写入那个的原始大小写，也就是「最后配置的那个」看起来是什么样就发什么样。</p>
     *
     * @param target 目标头
     * @param name   头名
     * @param value  头值
     */
    private static void putHeaderIgnoreCase(Map<String, String> target, String name, String value) {
        String existing = null;
        for (String key : target.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                existing = key;
                break;
            }
        }
        if (existing != null) {
            target.remove(existing);
        }
        target.put(name, value);
    }

    /**
     * 由库管理的请求头。它们不允许通过 {@code headers} 覆盖。
     *
     * <p>刻意拒绝而不是允许：写错一个大小写就会静默顶掉真实密钥（{@code containsHeader}
     * 是大小写无关的判断，认出自定义了 Authorization 就不再补 {@code Bearer}），
     * 既不报错也不留痕迹。启动失败比运行时发错凭据好查得多。</p>
     */
    private static final String[] RESERVED_HEADERS = {"Authorization", "Content-Type"};

    /**
     * 检查自定义请求头里没有库管理的保留头。
     *
     * @param where  出错位置，用于拼文案（如「供应商 demo 的 headers」）
     * @param custom 自定义头
     * @throws LlmCallException 出现保留头时抛出
     */
    private static void rejectReservedHeaders(String where, Map<String, String> custom) {
        if (custom == null || custom.isEmpty()) {
            return;
        }
        for (String reserved : RESERVED_HEADERS) {
            for (String name : custom.keySet()) {
                if (name != null && name.equalsIgnoreCase(reserved)) {
                    String hint = "Authorization".equals(reserved)
                            ? "请用 api-key / api-keys 配置密钥" : "Content-Type 由请求编码固定";
                    throw new LlmCallException(where + " 不能配置 " + reserved + "，" + hint,
                            null, LlmCallException.Kind.CONFIG);
                }
            }
        }
    }

    private static AiLiteConfig.Model findModel(AiLiteConfig.Provider provider, String modelId) {        String id = Strings.trimToNull(modelId);
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
        KeyRotation rotation = rotationFor(providerId, keys);
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
        KeyRotation rotation = rotationFor(providerId, keys);
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

    /**
     * 取（或建）该 provider 的轮换状态；配置条数或内容变化时重建（热更新兜底）。
     *
     * <p>用一次 {@code compute} 完成「判定 + 重建 + 装入」，而不是
     * get / putIfAbsent / put 三段：热更新改变 {@code api-keys} 时，多个线程可能
     * 同时判定「需要重建」，最后写入者会覆盖别人刚建好的实例，先返回者则在没人
     * 持有的孤儿实例上写冷却与游标，429 之后冷却就丢了（F-29）。</p>
     *
     * <p>同时按内容（而不只是条数）判定：条数不变但 key 顺序或取值变了时，旧索引
     * 的冷却会落到新 key 上，同样要重建。</p>
     */
    private KeyRotation rotationFor(String providerId, List<String> keys) {
        return rotations.compute(providerId, (id, existing) ->
                existing != null && existing.matches(keys) ? existing : new KeyRotation(keys));
    }

    /** 多 key 轮换状态：游标 + 每 key 冷却到期时间戳（0 = 未冷却）。 */
    private static final class KeyRotation {
        private final int size;
        /** 建这个实例时的 key 快照；内容或顺序变化即视为需要重建。 */
        private final List<String> keys;
        private final AtomicInteger cursor = new AtomicInteger(0);
        private final AtomicLong[] cooldownUntil;

        private KeyRotation(List<String> keys) {
            this.keys = new ArrayList<String>(keys);
            this.size = Math.max(1, this.keys.size());
            this.cooldownUntil = new AtomicLong[this.size];
            for (int i = 0; i < this.size; i++) {
                this.cooldownUntil[i] = new AtomicLong(0L);
            }
        }

        /**
         * @param current 当前配置里的 key 列表
         * @return true 表示条数与内容都未变，可以复用本实例
         */
        private boolean matches(List<String> current) {
            return current != null && keys.equals(current);
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
            rejectReservedHeaders("供应商 " + id + " 的 headers", p.getHeaders());
            if (p.getModels() != null) {
                for (AiLiteConfig.Model m : p.getModels()) {
                    if (m == null || Strings.isBlank(m.getId())) {
                        throw new LlmCallException("供应商 " + id + " 存在缺少 id 的模型", null, LlmCallException.Kind.CONFIG);
                    }
                    rejectReservedHeaders(
                            "供应商 " + id + " 模型 " + m.getId() + " 的 headers", m.getHeaders());
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
