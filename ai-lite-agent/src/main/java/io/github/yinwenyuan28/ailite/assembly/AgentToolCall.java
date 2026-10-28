package io.github.yinwenyuan28.ailite.assembly;

/**
 * 一次待执行的工具调用。beforeTool 可以改参数。
 */
public final class AgentToolCall {

    private final String id;
    private final String name;
    private String argumentsJson;

    AgentToolCall(String id, String name, String argumentsJson) {
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

    public void setArgumentsJson(String argumentsJson) {
        this.argumentsJson = argumentsJson;
    }
}
