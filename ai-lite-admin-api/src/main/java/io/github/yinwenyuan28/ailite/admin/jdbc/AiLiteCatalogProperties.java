package io.github.yinwenyuan28.ailite.admin.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运行时目录的表名和缓存。
 */
@ConfigurationProperties(prefix = "ai-lite.catalog")
public class AiLiteCatalogProperties {

    private int cacheSeconds = 5;
    private String providerTable = "AI_LITE_PROVIDER_OVERLAY";
    private String modelTable = "AI_LITE_MODEL";
    private String bizTable = "AI_LITE_BIZ_BINDING";

    public int getCacheSeconds() {
        return cacheSeconds;
    }

    public void setCacheSeconds(int cacheSeconds) {
        this.cacheSeconds = cacheSeconds;
    }

    public String getProviderTable() {
        return providerTable;
    }

    public void setProviderTable(String providerTable) {
        this.providerTable = providerTable;
    }

    public String getModelTable() {
        return modelTable;
    }

    public void setModelTable(String modelTable) {
        this.modelTable = modelTable;
    }

    public String getBizTable() {
        return bizTable;
    }

    public void setBizTable(String bizTable) {
        this.bizTable = bizTable;
    }
}
