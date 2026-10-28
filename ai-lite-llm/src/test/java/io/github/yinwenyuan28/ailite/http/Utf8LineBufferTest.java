package io.github.yinwenyuan28.ailite.http;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import org.junit.Test;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.yinwenyuan28.ailite.internal.http.Utf8LineBuffer;

/**
 * 字节流切行：CRLF、跨 chunk 的多字节字符、空行和末尾无换行。
 */
public class Utf8LineBufferTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @Test
    public void splitsLf() {
        assertThat(accept("a\nb\n")).containsExactly("a", "b");
    }

    @Test
    public void splitsCrLfAcrossChunks() {
        Utf8LineBuffer buffer = new Utf8LineBuffer(1024);
        List<String> lines = new ArrayList<String>();
        lines.addAll(buffer.accept(bytes("a\r")));
        lines.addAll(buffer.accept(bytes("\nb\r")));
        lines.addAll(buffer.accept(bytes("\n")));
        assertThat(lines).containsExactly("a", "b");
    }

    @Test
    public void splitsLoneCr() {
        assertThat(accept("a\rb\r")).containsExactly("a", "b");
    }

    @Test
    public void emitsEmptyLines() {
        assertThat(accept("a\n\nb\n")).containsExactly("a", "", "b");
        assertThat(accept("\n")).containsExactly("");
    }

    @Test
    public void keepsUtf8WhenEveryByteIsSeparateChunk() {
        byte[] body = bytes("data: 你好🙂\n");
        Utf8LineBuffer buffer = new Utf8LineBuffer(1024);
        List<String> lines = new ArrayList<String>();
        for (byte value : body) {
            lines.addAll(buffer.accept(new byte[]{value}));
        }
        assertThat(lines).containsExactly("data: 你好🙂");
    }

    @Test
    public void flushesTailWithoutNewline() {
        Utf8LineBuffer buffer = new Utf8LineBuffer(1024);
        assertThat(buffer.accept(bytes("last"))).isEmpty();
        assertThat(buffer.flush()).isEqualTo("last");
        assertThat(buffer.flush()).isNull();
    }

    @Test
    public void doesNotEmitExtraLineForTrailingCarriageReturn() {
        Utf8LineBuffer buffer = new Utf8LineBuffer(1024);
        assertThat(buffer.accept(bytes("a\r"))).containsExactly("a");
        assertThat(buffer.flush()).isNull();
    }

    @Test
    public void rejectsLineOverLimit() {
        Utf8LineBuffer buffer = new Utf8LineBuffer(4);
        assertThatThrownBy(() -> buffer.accept(bytes("12345\n")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("单行");
    }

    private static List<String> accept(String text) {
        Utf8LineBuffer buffer = new Utf8LineBuffer(1024);
        return buffer.accept(bytes(text));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }
}
