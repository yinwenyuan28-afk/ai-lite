package io.github.yinwenyuan28.ailite.store.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JDBC 调用记录存储配置。
 *
 * <p>{@code tableName} 刻意不给默认值：调用记录写入失败会被
 * {@code AsyncLlmCallLogger} 静默吞掉（只留一行 warn），用户可能几周后
 * 才发现账单表一直是空的。强制显式配置是让用户确认"我知道我在写这张表"
 * 的唯一机会，同时确保他已经执行过 {@code ai-lite-llm-call.sql} 建表。</p>
 */
@ConfigurationProperties(prefix = "ai-lite.store.jdbc")
public class AiLiteStoreJdbcProperties {

    /**
     * 是否启用 JDBC 调用记录。默认 true：引入本模块即生效。
     */
    private boolean enabled = true;

    /**
     * 调用记录表名。无默认值，必须显式配置；只允许字母、数字和下划线。
     */
    private String tableName;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
}
