package io.github.yinwenyuan28.ailite.llm;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调用参数的工厂、超时与消息替换。
 */
public class LlmCallTest {

    private final List<LlmMessage> messages = Collections.singletonList(LlmMessage.user("hi"));

    @Test
    public void explicitKeepsProviderModelAndLevel() {
        LlmCall call = LlmCall.explicit("biz", "vllm", "qwen", "high", messages);

        assertThat(call.getBizId()).isEqualTo("biz");
        assertThat(call.getProvider()).isEqualTo("vllm");
        assertThat(call.getModelId()).isEqualTo("qwen");
        assertThat(call.getThinkingLevel()).isEqualTo("high");
        assertThat(call.getProfile()).isNull();
    }

    @Test
    public void profileKeepsOnlyProfileName() {
        LlmCall call = LlmCall.profile("biz", "data-clean", messages);

        assertThat(call.getProfile()).isEqualTo("data-clean");
        assertThat(call.getProvider()).isNull();
        assertThat(call.getModelId()).isNull();
    }

    @Test
    public void timeoutsAreAppliedToThisCall() {
        LlmCall call = LlmCall.profile("biz", "p", messages).timeouts(1000, 2000);

        assertThat(call.getConnectTimeoutMs()).isEqualTo(1000);
        assertThat(call.getReadTimeoutMs()).isEqualTo(2000);
    }

    @Test
    public void withMessagesKeepsModelSelectionAndTimeouts() {
        LlmCall original = LlmCall.explicit("biz", "vllm", "qwen", "off", messages).timeouts(1000, 2000);
        List<LlmMessage> next = Collections.singletonList(LlmMessage.user("again"));

        LlmCall copy = original.withMessages(next);

        assertThat(copy.getMessages()).isSameAs(next);
        assertThat(copy.getProvider()).isEqualTo("vllm");
        assertThat(copy.getModelId()).isEqualTo("qwen");
        assertThat(copy.getConnectTimeoutMs()).isEqualTo(1000);
        assertThat(copy.getReadTimeoutMs()).isEqualTo(2000);
    }
}
