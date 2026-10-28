package io.github.yinwenyuan28.ailite.assembly;

/**
 * beforeTool 的决定。拒绝后本次工具不执行，拒绝原因作为工具结果进入下一轮。
 */
public final class ToolDecision {

    private final boolean allowed;
    private final String argumentsJson;
    private final String reason;

    private ToolDecision(boolean allowed, String argumentsJson, String reason) {
        this.allowed = allowed;
        this.argumentsJson = argumentsJson;
        this.reason = reason;
    }

    public static ToolDecision allow() {
        return new ToolDecision(true, null, null);
    }

    /**
     * 放行，并用这份参数替换模型给出的参数。
     *
     * @param argumentsJson 新的参数 JSON
     * @return 决策
     */
    public static ToolDecision allow(String argumentsJson) {
        return new ToolDecision(true, argumentsJson, null);
    }

    public static ToolDecision deny(String reason) {
        return new ToolDecision(false, null, reason);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public String getReason() {
        return reason;
    }
}
