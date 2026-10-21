package io.github.yinwenyuan28.ailite.session.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JDBC 会话存储配置。
 */
@ConfigurationProperties(prefix = "ai-lite.session.jdbc")
public class AiLiteSessionJdbcProperties {

    /** 会话表名。只允许字母、数字和下划线。 */
    private String tableName = "AI_LITE_SESSION";

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
}
