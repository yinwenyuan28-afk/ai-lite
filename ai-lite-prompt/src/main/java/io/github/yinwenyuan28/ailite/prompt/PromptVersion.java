package io.github.yinwenyuan28.ailite.prompt;

import java.util.Date;

/**
 * 一个提示词版本。versionNo 在同一 key 内递增，versionId 全局唯一。
 */
public final class PromptVersion {

    private String versionId;
    private String promptKey;
    private int versionNo;
    private PromptStatus status = PromptStatus.DRAFT;
    private String systemTemplate;
    private String userTemplate;
    private String outputSchema;
    private String contentHash;
    private String createdBy;
    private Date createTime;

    public PromptVersion() {
    }

    public PromptVersion copy() {
        PromptVersion copy = new PromptVersion();
        copy.versionId = versionId;
        copy.promptKey = promptKey;
        copy.versionNo = versionNo;
        copy.status = status;
        copy.systemTemplate = systemTemplate;
        copy.userTemplate = userTemplate;
        copy.outputSchema = outputSchema;
        copy.contentHash = contentHash;
        copy.createdBy = createdBy;
        copy.createTime = createTime == null ? null : new Date(createTime.getTime());
        return copy;
    }

    public PromptTemplate toTemplate() {
        return new PromptTemplate(systemTemplate, userTemplate, outputSchema);
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getPromptKey() {
        return promptKey;
    }

    public void setPromptKey(String promptKey) {
        this.promptKey = promptKey;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(int versionNo) {
        this.versionNo = versionNo;
    }

    public PromptStatus getStatus() {
        return status;
    }

    public void setStatus(PromptStatus status) {
        this.status = status;
    }

    public String getSystemTemplate() {
        return systemTemplate;
    }

    public void setSystemTemplate(String systemTemplate) {
        this.systemTemplate = systemTemplate;
    }

    public String getUserTemplate() {
        return userTemplate;
    }

    public void setUserTemplate(String userTemplate) {
        this.userTemplate = userTemplate;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public void setOutputSchema(String outputSchema) {
        this.outputSchema = outputSchema;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
