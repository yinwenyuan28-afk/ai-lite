package io.github.yinwenyuan28.ailite.llm;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmTexts 的拼接、折叠与校验行为。
 */
public class LlmTextsTest {

    @Test
    public void spliceJoinsReasoningAndContentWithBlankLine() {
        assertThat(LlmTexts.splice("think", "answer")).isEqualTo("think\n\nanswer");
    }

    @Test
    public void spliceReturnsTheOnlySideWhenOtherIsBlank() {
        assertThat(LlmTexts.splice("think", " ")).isEqualTo("think");
        assertThat(LlmTexts.splice(null, "answer")).isEqualTo("answer");
        assertThat(LlmTexts.splice(null, null)).isEmpty();
    }

    @Test
    public void foldMergesSystemMessagesAndConvertsOtherToUser() {
        List<LlmMessage> messages = Arrays.asList(
                LlmMessage.system("a"),
                LlmMessage.user("question"),
                LlmMessage.system("b"),
                LlmMessage.other("legacy"));

        List<LlmMessage> folded = LlmTexts.fold(messages);

        assertThat(folded).hasSize(3);
        assertThat(folded.get(0).getRole()).isEqualTo(LlmRole.SYSTEM);
        assertThat(folded.get(0).getText()).isEqualTo("a\n\nb");
        assertThat(folded.get(1).getRole()).isEqualTo(LlmRole.USER);
        assertThat(folded.get(2).getRole()).isEqualTo(LlmRole.USER);
        assertThat(folded.get(2).getText()).isEqualTo("legacy");
    }

    @Test
    public void validateRejectsEmptyMessages() {
        assertThatThrownBy(() -> LlmTexts.validate(Collections.<LlmMessage>emptyList(), true))
                .isInstanceOf(LlmCallException.class)
                .hasMessage("消息不能为空");
    }

    @Test
    public void validateRejectsToolResultWithoutCallId() {
        List<LlmMessage> messages = Collections.singletonList(LlmMessage.toolResult(" ", "ok"));
        assertThatThrownBy(() -> LlmTexts.validate(messages, true))
                .isInstanceOf(LlmCallException.class)
                .hasMessage("工具结果缺少 toolCallId");
    }

    @Test
    public void validateRejectsImagesWhenModelDoesNotAcceptThem() {
        List<LlmImage> images = Collections.singletonList(new LlmImage("image/png", "AAAA"));
        List<LlmMessage> messages = Collections.singletonList(LlmMessage.user("look", images));

        assertThatThrownBy(() -> LlmTexts.validate(messages, false))
                .isInstanceOf(LlmCallException.class)
                .hasMessage("模型不接受图片");
    }

    @Test
    public void validateRejectsNullImageElement() {
        List<LlmImage> images = Collections.singletonList(null);
        List<LlmMessage> messages = Collections.singletonList(LlmMessage.user("look", images));

        assertThatThrownBy(() -> LlmTexts.validate(messages, true))
                .isInstanceOf(LlmCallException.class)
                .hasMessage("图片不能为空");
    }

    @Test
    public void validateRejectsBlankMime() {
        List<LlmImage> images = Collections.singletonList(new LlmImage("  ", "AAAA"));
        List<LlmMessage> messages = Collections.singletonList(LlmMessage.user("look", images));

        assertThatThrownBy(() -> LlmTexts.validate(messages, true))
                .isInstanceOf(LlmCallException.class)
                .hasMessage("图片 MIME 不能为空");
    }

    @Test
    public void validateRejectsNonImageMime() {
        List<LlmImage> images = Collections.singletonList(new LlmImage("text/plain", "AAAA"));
        List<LlmMessage> messages = Collections.singletonList(LlmMessage.user("look", images));

        assertThatThrownBy(() -> LlmTexts.validate(messages, true))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("图片 MIME 必须以 image/ 开头");
    }

    @Test
    public void validateRejectsBlankData() {
        List<LlmImage> images = Collections.singletonList(new LlmImage("image/png", " "));
        List<LlmMessage> messages = Collections.singletonList(LlmMessage.user("look", images));

        assertThatThrownBy(() -> LlmTexts.validate(messages, true))
                .isInstanceOf(LlmCallException.class)
                .hasMessage("图片数据不能为空");
    }

    @Test
    public void validateRejectsNonBase64DataUrl() {
        List<LlmImage> images = Collections.singletonList(new LlmImage("image/svg+xml",
                "data:image/svg+xml;charset=utf-8,<svg/>"));
        List<LlmMessage> messages = Collections.singletonList(LlmMessage.user("look", images));

        assertThatThrownBy(() -> LlmTexts.validate(messages, true))
                .isInstanceOf(LlmCallException.class)
                .hasMessage("仅支持 base64 data URL");
    }

    @Test
    public void validateAcceptsUppercaseImageMime() {
        List<LlmImage> images = Collections.singletonList(new LlmImage("IMAGE/PNG", "AAAA"));
        List<LlmMessage> messages = Collections.singletonList(LlmMessage.user("look", images));

        LlmTexts.validate(messages, true);
    }

    @Test
    public void validateAcceptsMultipleImagesAndImageOnlyMessage() {
        List<LlmMessage> messages = Arrays.asList(
                LlmMessage.user("two images", Arrays.asList(
                        new LlmImage("image/png", "AAAA"),
                        new LlmImage("image/jpeg", "BBBB"))),
                LlmMessage.user("", Collections.singletonList(
                        new LlmImage("image/webp", "CCCC"))));

        LlmTexts.validate(messages, true);
    }

    @Test
    public void validateAcceptsWellFormedConversation() {
        List<LlmMessage> messages = Arrays.asList(
                LlmMessage.system("rules"),
                LlmMessage.user("hi"),
                LlmMessage.assistant("", Collections.singletonList(new LlmToolCall("c1", "now", "{}"))),
                LlmMessage.toolResult("c1", "12:00"));

        LlmTexts.validate(messages, true);
    }
}
