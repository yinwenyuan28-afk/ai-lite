package io.github.yinwenyuan28.ailite.catalog;

/**
 * 供应商连接信息的运行时覆盖。空字段表示沿用配置文件。
 */
public final class ProviderOverlay {

    private final String providerId;
    private final String baseUrl;
    private final String apiKey;
    private final Integer connectTimeoutMs;
    private final Integer readTimeoutMs;

    /**
     * @param providerId       供应商 id
     * @param baseUrl          覆盖地址，可空
     * @param apiKey           覆盖密钥，可空
     * @param connectTimeoutMs 连接超时，可空
     * @param readTimeoutMs    读取超时，可空
     */
    public ProviderOverlay(String providerId, String baseUrl, String apiKey,
                           Integer connectTimeoutMs, Integer readTimeoutMs) {
        this.providerId = providerId;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
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
}
