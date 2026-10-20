package io.github.yinwenyuan28.ailite.config;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmThinking;
import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
import io.github.yinwenyuan28.ailite.util.Strings;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 把 YAML 配置解析成一次调用需要的 {@link ResolvedModel}。
 *
 * <p>校验尽量前移：供应商、模型、profile 的引用关系在构造时就检查，
 * 密钥和业务 id 在调用时再检查，避免启动阶段依赖外部环境变量。</p>
 */
public final class ModelResolver {

    private final AiLiteConfig.Llm config;
    private final boolean configured;
    private final Set<String> bizIds;
    private final Map<String, AiLiteConfig.Provider> providers;
    private final Map<String, AiLiteConfig.Profile> profiles;

    /**
     * @param config 模型配置节
     * @throws LlmCallException 配置结构非法时抛出
     */
    public ModelResolver(AiLiteConfig.Llm config) {
        this.config = config == null ? new AiLiteConfig.Llm() : config;
        this.providers = this.config.getProviders();
        this.profiles = this.config.getProfiles();
        this.configured = providers != null && !providers.isEmpty();
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
            throw new LlmCallException("供应商不能为空");
        }
        AiLiteConfig.Provider p = providers.get(providerId);
        if (p == null) {
            throw new LlmCallException("未知供应商: " + providerId);
        }
        AiLiteConfig.Model m = findModel(p, modelId);
        if (m == null) {
            throw new LlmCallException("未知模型: " + modelId);
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
            throw new LlmCallException("配置档不能为空");
        }
        AiLiteConfig.Profile profile = profiles.get(name);
        if (profile == null) {
            throw new LlmCallException("未知配置档: " + name);
        }
        AiLiteConfig.Provider p = providers.get(Strings.trimToNull(profile.getProvider()));
        if (p == null) {
            throw new LlmCallException("配置档 " + name + " 指向未知供应商: " + profile.getProvider());
        }
        AiLiteConfig.Model m = findModel(p, profile.getModel());
        if (m == null) {
            throw new LlmCallException("配置档 " + name + " 指向未知模型: " + profile.getModel());
        }
        return resolve(Strings.trimToNull(profile.getProvider()), p, m, profile.getThinkingLevel());
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
                requireApiKey(providerId, p), headers, m.getId(), contextWindow, maxTokens,
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
            throw new LlmCallException("模型密钥未配置: " + providerId);
        }
        return key;
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
                throw new LlmCallException("供应商配置不能为空: " + id);
            }
            if (Strings.isBlank(p.getApi())) {
                throw new LlmCallException("供应商 " + id + " 缺少 api");
            }
            if (Strings.isBlank(p.getBaseUrl())) {
                throw new LlmCallException("供应商 " + id + " 缺少 base-url");
            }
            if (Strings.isBlank(trimTrailingSlash(p.getBaseUrl()))) {
                throw new LlmCallException("供应商 " + id + " 的 base-url 无效");
            }
            if (p.getModels() == null || p.getModels().isEmpty()) {
                throw new LlmCallException("供应商 " + id + " 没有配置模型");
            }
            for (AiLiteConfig.Model m : p.getModels()) {
                if (m == null || Strings.isBlank(m.getId())) {
                    throw new LlmCallException("供应商 " + id + " 存在缺少 id 的模型");
                }
            }
        }
        for (Map.Entry<String, AiLiteConfig.Profile> entry : profiles.entrySet()) {
            AiLiteConfig.Profile profile = entry.getValue();
            if (profile == null) {
                throw new LlmCallException("配置档不能为空: " + entry.getKey());
            }
            if (Strings.isBlank(profile.getProvider())) {
                throw new LlmCallException("配置档 " + entry.getKey() + " 缺少 provider");
            }
            if (Strings.isBlank(profile.getModel())) {
                throw new LlmCallException("配置档 " + entry.getKey() + " 缺少 model");
            }
            AiLiteConfig.Provider p = providers.get(profile.getProvider().trim());
            if (p == null) {
                throw new LlmCallException("配置档 " + entry.getKey() + " 指向未知供应商");
            }
            if (findModel(p, profile.getModel()) == null) {
                throw new LlmCallException("配置档 " + entry.getKey() + " 指向未知模型");
            }
        }
    }
}
