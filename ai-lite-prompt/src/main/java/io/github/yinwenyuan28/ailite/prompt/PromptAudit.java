package io.github.yinwenyuan28.ailite.prompt;

import java.util.Date;

/**
 * 提示词操作审计。只记录动作和版本，不记录模板正文。
 */
public final class PromptAudit {

    private String auditId;
    private String promptKey;
    private String action;
    private String versionId;
    private String actor;
    private String detail;
    private Date createTime;

    public PromptAudit() {
    }

    public PromptAudit copy() {
        PromptAudit copy = new PromptAudit();
        copy.auditId = auditId;
        copy.promptKey = promptKey;
        copy.action = action;
        copy.versionId = versionId;
        copy.actor = actor;
        copy.detail = detail;
        copy.createTime = createTime == null ? null : new Date(createTime.getTime());
        return copy;
    }

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }

    public String getPromptKey() {
        return promptKey;
    }

    public void setPromptKey(String promptKey) {
        this.promptKey = promptKey;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
