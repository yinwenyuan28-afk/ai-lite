package io.github.yinwenyuan28.ailite.llm;

/**
 * 模型返回的一次工具调用。{@code argumentsJson} 保留模型给出的原始 JSON 文本。
 */
public final class LlmToolCall {

    private final String id;
    private final String name;
    private final String argumentsJson;

    /**
     * @param id            供应商给出的调用 id，用于回传工具结果
     * @param name          工具名
     * @param argumentsJson 原始参数 JSON 文本
     */
    public LlmToolCall(String id, String name, String argumentsJson) {
        this.id = id;
        this.name = name;
        this.argumentsJson = argumentsJson;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }
}
