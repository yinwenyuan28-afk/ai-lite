package io.github.yinwenyuan28.ailite.catalog;

/**
 * 供应商连接信息的运行时覆盖。空字段表示沿用配置文件。
 *
 * <p>覆盖只改「怎么连」，不改「供应商叫什么」：供应商 id 和名单仍由配置文件决定。
 * 协议也放在这里，因为选错协议的表现就是请求打到错误路径，属于连接问题而不是结构问题。</p>
 */
public final class ProviderOverlay {

    private final String providerId;
    private final String baseUrl;
    private final String apiKey;
    private final Integer connectTimeoutMs;
    private final Integer readTimeoutMs;
    private final String api;

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
        this.providerId = providerId;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.api = api;
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
}
