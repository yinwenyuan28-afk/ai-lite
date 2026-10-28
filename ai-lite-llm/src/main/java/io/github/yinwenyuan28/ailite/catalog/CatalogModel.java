package io.github.yinwenyuan28.ailite.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 目录中的一个模型。能力参数在未就绪时可以为空，这时不能被业务选中。
 */
public final class CatalogModel {

    private final String providerId;
    private final String modelId;
    private final boolean ready;
    private final boolean reasoning;
    private final List<String> inputModes;
    private final Integer contextWindow;
    private final Integer maxTokens;
    private final String thinkingFormat;
    private final String displayName;
    private final long updatedAtMillis;

    /**
     * @param providerId      供应商 id
     * @param modelId         模型 id
     * @param ready           能力参数已补齐
     * @param reasoning       是否支持推理
     * @param inputModes      入参类型，可空
     * @param contextWindow   上下文窗口，可空
     * @param maxTokens       输出上限，可空
     * @param thinkingFormat  思考格式，可空
     * @param displayName     显示名，可空
     * @param updatedAtMillis 更新时间，毫秒；同一对键多行时取较大值
     */
    public CatalogModel(String providerId, String modelId, boolean ready, boolean reasoning,
                        List<String> inputModes, Integer contextWindow, Integer maxTokens,
                        String thinkingFormat, String displayName, long updatedAtMillis) {
        this.providerId = providerId;
        this.modelId = modelId;
        this.ready = ready;
        this.reasoning = reasoning;
        this.inputModes = inputModes == null
                ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(inputModes));
        this.contextWindow = contextWindow;
        this.maxTokens = maxTokens;
        this.thinkingFormat = thinkingFormat;
        this.displayName = displayName;
        this.updatedAtMillis = updatedAtMillis;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getModelId() {
        return modelId;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isReasoning() {
        return reasoning;
    }

    public List<String> getInputModes() {
        return inputModes;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public String getThinkingFormat() {
        return thinkingFormat;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }
}
