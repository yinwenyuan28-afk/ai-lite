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

    @Test
    public void stripsBomAtStreamStart() {
        Utf8LineBuffer buffer = new Utf8LineBuffer(1024);
        byte[] body = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                bytes("data: a\n"));
        assertThat(buffer.accept(body)).containsExactly("data: a");
    }

    @Test
    public void stripsBomSplitAcrossChunks() {
        Utf8LineBuffer buffer = new Utf8LineBuffer(1024);
        List<String> lines = new ArrayList<String>();
        lines.addAll(buffer.accept(new byte[]{(byte) 0xEF}));
        lines.addAll(buffer.accept(new byte[]{(byte) 0xBB}));
        lines.addAll(buffer.accept(concat(new byte[]{(byte) 0xBF}, bytes("data: a\n"))));
        assertThat(lines).containsExactly("data: a");
    }

    @Test
    public void keepsLeadingBytesThatAreNotBom() {
        // 0xEF 0xBB 开头但不是完整 BOM：这些字节属于正文，不能被吞掉。
        // 该序列不是合法 UTF-8，解码后是替换字符，所以只断言「行里有正文」。
        Utf8LineBuffer buffer = new Utf8LineBuffer(1024);
        byte[] body = concat(new byte[]{(byte) 0xEF, (byte) 0xBB}, bytes("a\n"));
        List<String> lines = buffer.accept(body);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).endsWith("a");
        assertThat(lines.get(0).length()).isGreaterThan(1);
    }

    @Test
    public void flushesBomPrefixWhenStreamEndsMidBom() {
        // 流只发了 0xEF 就结束：那是正文，不能丢（同样解码为替换字符）。
        Utf8LineBuffer buffer = new Utf8LineBuffer(1024);
        assertThat(buffer.accept(new byte[]{(byte) 0xEF})).isEmpty();
        assertThat(buffer.flush()).hasSize(1);
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] all = new byte[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    private static List<String> accept(String text) {
        Utf8LineBuffer buffer = new Utf8LineBuffer(1024);
        return buffer.accept(bytes(text));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }
}
