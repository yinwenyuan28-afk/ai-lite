package io.github.yinwenyuan28.ailite.prompt.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 提示词三张表的表名配置。
 *
 * <p>与 {@code ai-lite.store.jdbc.table-name}、{@code ai-lite.session.jdbc.table-name}
 * 保持同一命名约定：模块名 + {@code .jdbc} + 表用途。默认值就是 {@code ai-lite-prompt.sql}
 * 里的表名，因此不配置时行为与旧版本完全一致。</p>
 *
 * <p>三张表是一个整体：发布时分三步写（退役旧版本、发布目标版本、更新生效指针），
 * 拆开配置只对"表已经按自己规范建好"的场景有意义。只改其中一两张会得到一个
 * 语义不完整的状态，所以这里三个键同进同退。</p>
 *
 * <p>表名只允许字母、数字和下划线，由 {@link JdbcPromptStore} 构造时校验；
 * 非法名字直接抛 {@link IllegalArgumentException}，不会拼进 SQL。</p>
 */
@ConfigurationProperties(prefix = "ai-lite.prompt.jdbc")
public class AiLitePromptJdbcProperties {

    /** 版本与模板正文表。 */
    private String versionTable = "AI_LITE_PROMPT_VERSION";
    /** 每个 key 的生效指针表。 */
    private String activeTable = "AI_LITE_PROMPT_ACTIVE";
    /** 操作审计表。 */
    private String auditTable = "AI_LITE_PROMPT_AUDIT";

    public String getVersionTable() {
        return versionTable;
    }

    public void setVersionTable(String versionTable) {
        this.versionTable = versionTable;
    }

    public String getActiveTable() {
        return activeTable;
    }

    public void setActiveTable(String activeTable) {
        this.activeTable = activeTable;
    }

    public String getAuditTable() {
        return auditTable;
    }

    public void setAuditTable(String auditTable) {
        this.auditTable = auditTable;
    }
}
