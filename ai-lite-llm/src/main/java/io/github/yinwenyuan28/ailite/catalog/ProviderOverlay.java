package io.github.yinwenyuan28.ailite.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 供应商连接信息的运行时覆盖。空字段表示沿用配置文件。
 *
 * <p>覆盖只改「怎么连」，不改「供应商叫什么」：供应商 id 和名单仍由配置文件决定。
 * 协议也放在这里，因为选错协议的表现就是请求打到错误路径，属于连接问题而不是结构问题。</p>
 *
 * <p>{@code headers} 与 {@code extraBody} 虽然不影响「连到哪」，但同属「调用时带什么」
 * 这一类运行时配置，和地址、密钥放一起保存最自然。它们为 {@code null} 表示数据库没配，
 * 沿用配置文件；空 Map 表示明确清空配置文件里的那份。</p>
 */
public final class ProviderOverlay {

    private final String providerId;
    private final String baseUrl;
    private final String apiKey;
    private final Integer connectTimeoutMs;
    private final Integer readTimeoutMs;
    private final String api;
    private final Map<String, String> headers;
    private final Map<String, Object> extraBody;

    /**
     * 不带协议的构造器，保留给既有调用方。
     *
     * @param providerId       供应商 id
     * @param baseUrl          覆盖地址，可空
     * @param apiKey           覆盖密钥，可空
     * @param connectTimeoutMs 连接超时，可空
     * @param readTimeoutMs    读取超时，可空
     */
    public ProviderOverlay(String providerId, String baseUrl, String apiKey,
                           Integer connectTimeoutMs, Integer readTimeoutMs) {
        this(providerId, baseUrl, apiKey, connectTimeoutMs, readTimeoutMs, null);
    }

    /**
     * @param providerId       供应商 id
     * @param baseUrl          覆盖地址，可空
     * @param apiKey           覆盖密钥，可空
     * @param connectTimeoutMs 连接超时，可空
     * @param readTimeoutMs    读取超时，可空
     * @param api              协议覆盖，可空；空表示沿用配置文件里的 api
     */
    public ProviderOverlay(String providerId, String baseUrl, String apiKey,
                           Integer connectTimeoutMs, Integer readTimeoutMs, String api) {
        this(providerId, baseUrl, apiKey, connectTimeoutMs, readTimeoutMs, api, null, null);
    }

    /**
     * @param providerId       供应商 id
     * @param baseUrl          覆盖地址，可空
     * @param apiKey           覆盖密钥，可空
     * @param connectTimeoutMs 连接超时，可空
     * @param readTimeoutMs    读取超时，可空
     * @param api              协议覆盖，可空；空表示沿用配置文件里的 api
     * @param headers          供应商级请求头；null 表示沿用配置文件
     * @param extraBody        供应商级附加请求体；null 表示沿用配置文件
     */
    public ProviderOverlay(String providerId, String baseUrl, String apiKey,
                           Integer connectTimeoutMs, Integer readTimeoutMs, String api,
                           Map<String, String> headers, Map<String, Object> extraBody) {
        this.providerId = providerId;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.api = api;
        this.headers = headers == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers));
        this.extraBody = extraBody == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(extraBody));
    }

    public String getProviderId() {
        return providerId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public Integer getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public Integer getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public String getApi() {
        return api;
    }

    /**
     * @return 供应商级请求头；null 表示数据库没配，应沿用配置文件
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * @return 供应商级附加请求体；null 表示数据库没配，应沿用配置文件
     */
    public Map<String, Object> getExtraBody() {
        return extraBody;
    }
}
