package io.github.yinwenyuan28.ailite.catalog;

/**
 * 业务 id 到供应商、模型和思考档的绑定。
 */
public final class BizBinding {

    private final String bizId;
    private final String providerId;
    private final String modelId;
    private final String thinkingLevel;

    /**
     * @param bizId         业务 id
     * @param providerId    供应商 id
     * @param modelId       模型 id
     * @param thinkingLevel 思考档
     */
    public BizBinding(String bizId, String providerId, String modelId, String thinkingLevel) {
        this.bizId = bizId;
        this.providerId = providerId;
        this.modelId = modelId;
        this.thinkingLevel = thinkingLevel;
    }

    public String getBizId() {
        return bizId;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getModelId() {
        return modelId;
    }

    public String getThinkingLevel() {
        return thinkingLevel;
    }
}
