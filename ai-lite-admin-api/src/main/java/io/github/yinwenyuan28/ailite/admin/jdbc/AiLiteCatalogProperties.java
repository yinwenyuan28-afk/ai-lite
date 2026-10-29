package io.github.yinwenyuan28.ailite.admin.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运行时目录的表名和缓存。
 *
 * <p>四张表全部可通过配置改名，用于与既有库的表命名规范对齐（例如统一业务前缀）。
 * 表名只允许字母、数字和下划线，见 {@link JdbcRuntimeCatalog#safeTable}。</p>
 */
@ConfigurationProperties(prefix = "ai-lite.catalog")
public class AiLiteCatalogProperties {

    private int cacheSeconds = 5;
    private String providerTable = "AI_LITE_PROVIDER_OVERLAY";
    private String modelTable = "AI_LITE_MODEL";
    private String bizTable = "AI_LITE_BIZ_BINDING";
    private String settingTable = "AI_LITE_ADMIN_SETTING";

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

    /**
     * @return 管理端运行时开关表名（目前只存 call-body 档位）
     */
    public String getSettingTable() {
        return settingTable;
    }

    public void setSettingTable(String settingTable) {
        this.settingTable = settingTable;
    }
}
