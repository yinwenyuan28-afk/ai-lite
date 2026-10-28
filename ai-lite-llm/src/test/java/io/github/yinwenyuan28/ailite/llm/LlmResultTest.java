package io.github.yinwenyuan28.ailite.llm;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结果里的正文和 thinking 是独立字段，rawContent 只用于兼容旧拼接形态。
 */
public class LlmResultTest {

    @Test
    public void contentAndThinkingAreSeparate() {
        LlmResult result = new LlmResult("answer", "think", LlmStopReason.STOP,
                LlmUsage.zero(), null, null, null, 0L, 1, null);

        assertThat(result.getContent()).isEqualTo("answer");
        assertThat(result.getThinkingText()).isEqualTo("think");
        assertThat(result.getRawContent()).isEqualTo("think\n\nanswer");
    }

    @Test
    public void emptyThinkingKeepsContentOnly() {
        LlmResult result = new LlmResult("answer", null, LlmStopReason.STOP,
                LlmUsage.zero(), null, null, null, 0L, 1, null);

        assertThat(result.getRawContent()).isEqualTo("answer");
    }
}
