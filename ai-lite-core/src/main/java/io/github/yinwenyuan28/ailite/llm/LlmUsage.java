package io.github.yinwenyuan28.ailite.llm;

/**
 * token 用量。各字段保证非负；total 缺失时用输入加输出补齐。
 */
public final class LlmUsage {

    private final int inputTokens;
    private final int outputTokens;
    private final int totalTokens;

    private LlmUsage(int inputTokens, int outputTokens, int totalTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
    }

    /**
     * 零用量，用于失败或取消场景。
     *
     * @return 全零用量
     */
    public static LlmUsage zero() {
        return new LlmUsage(0, 0, 0);
    }

    /**
     * 构造用量，自动修正负数和缺失的 total。
     *
     * @param inputTokens  输入 token
     * @param outputTokens 输出 token
     * @param totalTokens  总 token；小于等于 0 时按输入加输出计算
     * @return 用量对象
     */
    public static LlmUsage of(int inputTokens, int outputTokens, int totalTokens) {
        int input = Math.max(0, inputTokens);
        int output = Math.max(0, outputTokens);
        int total = totalTokens > 0 ? totalTokens : input + output;
        return new LlmUsage(input, output, total);
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LlmUsage)) {
            return false;
        }
        LlmUsage that = (LlmUsage) other;
        return inputTokens == that.inputTokens
                && outputTokens == that.outputTokens
                && totalTokens == that.totalTokens;
    }

    @Override
    public int hashCode() {
        int result = inputTokens;
        result = 31 * result + outputTokens;
        result = 31 * result + totalTokens;
        return result;
    }

    @Override
    public String toString() {
        return "LlmUsage{input=" + inputTokens + ", output=" + outputTokens + ", total=" + totalTokens + '}';
    }
}
