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

    /**
     * 调用正文档位存储正文的表名。
     *
     * <p>不配置时按 {@code tableName + "_BODY"} 推导：主表用默认值时得到的正是
     * {@code AI_LITE_LLM_CALL_BODY}，与旧版本行为一致；主表改名后正文表自动跟随，
     * 不会再出现"主表叫 MY_CALL、正文表还叫 AI_LITE_LLM_CALL_BODY"的半改名状态。</p>
     *
     * <p>摘要写入与正文写入是两条独立语句，正文表不存在时摘要照样落库，异常只被
     * 异步日志吞掉并留一行 warn——用户会以为记录功能正常，只是正文永远是空的。
     * 因此这里跟随主表推导，比让用户配两个键更安全。</p>
     */
    private String bodyTableName;

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

    /**
     * @return 正文表名；未配置时由 {@link #getTableName()} 推导为 {@code <主表>_BODY}
     */
    public String getBodyTableName() {
        return bodyTableName;
    }

    public void setBodyTableName(String bodyTableName) {
        this.bodyTableName = bodyTableName;
    }

    /**
     * 正文表名，未显式配置时跟随主表推导。
     *
     * @return 实际使用的正文表名；主表名也没配时返回 null，交由调用方报错
     */
    public String resolveBodyTableName() {
        if (bodyTableName != null && !bodyTableName.trim().isEmpty()) {
            return bodyTableName.trim();
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            return null;
        }
        return tableName.trim() + "_BODY";
    }
}
