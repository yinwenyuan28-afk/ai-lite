package io.github.yinwenyuan28.ailite.llm;

/**
 * 一轮模型请求的停止原因。
 *
 * <p>{@link #wire()} 是稳定值，用于调用记录和外部展示，不要直接依赖枚举名。</p>
 */
public enum LlmStopReason {
    STOP("stop"),
    LENGTH("length"),
    TOOL_USE("toolUse"),
    ERROR("error"),
    ABORTED("aborted"),
    MAX_ROUNDS("maxRounds"),
    CONTEXT_LENGTH_EXCEEDED("contextLengthExceeded");

    private final String wire;

    LlmStopReason(String wire) {
        this.wire = wire;
    }

    /**
     * 返回写入调用记录和接口展示的稳定字符串。
     *
     * @return wire 值
     */
    public String wire() {
        return wire;
    }
}
