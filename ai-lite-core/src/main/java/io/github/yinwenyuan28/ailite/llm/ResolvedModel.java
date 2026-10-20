package io.github.yinwenyuan28.ailite.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析完成的一次模型选择。供应商、协议、端点、密钥、模型能力都已确定。
 *
 * <p>这个对象只在一次调用内使用，不缓存可变状态。</p>
 */
public final class ResolvedModel {

    private final String provider;
    private final String api;
    private final String baseUrl;
    private final String apiKey;
    private final Map<String, String> headers;
    private final String modelId;
    private final int contextWindow;
    private final int maxTokens;
    private final boolean acceptsImage;
    private final boolean reasoning;
    private final LlmThinkingLevel thinkingLevel;
    private final Map<String, String> thinkingLevelMap;
    private final String thinkingFormat;
    private final Map<String, Object> samplerParams;
    private final Map<String, Object> extraBody;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public ResolvedModel(String provider, String api, String baseUrl, String apiKey,
                         Map<String, String> headers, String modelId, int contextWindow, int maxTokens,
                         boolean acceptsImage, boolean reasoning, LlmThinkingLevel thinkingLevel,
                         Map<String, String> thinkingLevelMap, String thinkingFormat,
                         Map<String, Object> samplerParams, Map<String, Object> extraBody,
                         int connectTimeoutMs, int readTimeoutMs) {
        this.provider = provider;
        this.api = api;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.headers = immutable(headers);
        this.modelId = modelId;
        this.contextWindow = contextWindow;
        this.maxTokens = maxTokens;
        this.acceptsImage = acceptsImage;
        this.reasoning = reasoning;
        this.thinkingLevel = thinkingLevel;
        this.thinkingLevelMap = immutable(thinkingLevelMap);
        this.thinkingFormat = thinkingFormat;
        this.samplerParams = immutable(samplerParams);
        this.extraBody = immutable(extraBody);
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> source) {
        return source == null
                ? Collections.<K, V>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<K, V>(source));
    }

    public String getProvider() {
        return provider;
    }

    public String getApi() {
        return api;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getModelId() {
        return modelId;
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public boolean isAcceptsImage() {
        return acceptsImage;
    }

    public boolean isReasoning() {
        return reasoning;
    }

    public LlmThinkingLevel getThinkingLevel() {
        return thinkingLevel;
    }

    public Map<String, String> getThinkingLevelMap() {
        return thinkingLevelMap;
    }

    public String getThinkingFormat() {
        return thinkingFormat;
    }

    public Map<String, Object> getSamplerParams() {
        return samplerParams;
    }

    public Map<String, Object> getExtraBody() {
        return extraBody;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }
}
