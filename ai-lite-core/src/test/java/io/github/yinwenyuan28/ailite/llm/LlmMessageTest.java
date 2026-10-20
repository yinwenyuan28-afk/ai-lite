package io.github.yinwenyuan28.ailite.llm;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息工厂与不可变性。
 */
public class LlmMessageTest {

    @Test
    public void messageCopiesToolCallList() {
        List<LlmToolCall> calls = new ArrayList<LlmToolCall>();
        calls.add(new LlmToolCall("c1", "now", "{}"));

        LlmMessage message = LlmMessage.assistant("", calls);
        calls.add(new LlmToolCall("c2", "now", "{}"));

        assertThat(message.getToolCalls()).hasSize(1);
    }

    @Test
    public void messageCopiesImageList() {
        List<LlmImage> images = new ArrayList<LlmImage>();
        images.add(new LlmImage("image/png", "AAAA"));

        LlmMessage message = LlmMessage.user("look", images);
        images.clear();

        assertThat(message.getImages()).hasSize(1);
    }

    @Test
    public void nullTextBecomesEmptyString() {
        assertThat(LlmMessage.user(null).getText()).isEmpty();
        assertThat(LlmMessage.assistant(null, null).getToolCalls()).isEmpty();
    }

    @Test
    public void toolResultKeepsCallId() {
        LlmMessage message = LlmMessage.toolResult("c1", "ok");

        assertThat(message.getRole()).isEqualTo(LlmRole.TOOL_RESULT);
        assertThat(message.getToolCallId()).isEqualTo("c1");
    }

    @Test
    public void emptyFactoryListsAreSafe() {
        assertThat(LlmMessage.user("x", Collections.<LlmImage>emptyList()).getImages()).isEmpty();
        assertThat(LlmMessage.assistant("x", Collections.<LlmToolCall>emptyList()).getToolCalls()).isEmpty();
    }
}
