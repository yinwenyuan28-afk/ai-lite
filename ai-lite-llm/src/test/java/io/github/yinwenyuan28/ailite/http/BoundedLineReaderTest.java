package io.github.yinwenyuan28.ailite.http;

import io.github.yinwenyuan28.ailite.internal.http.BoundedLineReader;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阻塞式有界逐行读取：行切分、跨读取边界、单行上限、BOM。
 */
public class BoundedLineReaderTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @Test
    public void readsLinesInOrder() throws IOException {
        assertThat(readAll("a\nb\nc", 1024)).containsExactly("a", "b", "c");
    }

    @Test
    public void handlesLfCrLfAndLoneCr() throws IOException {
        assertThat(readAll("a\nb\r\nc\rd", 1024)).containsExactly("a", "b", "c", "d");
    }

    @Test
    public void emitsEmptyLines() throws IOException {
        assertThat(readAll("a\n\nb\n", 1024)).containsExactly("a", "", "b");
    }

    @Test
    public void readsAcrossInternalBufferBoundary() throws IOException {
        // 内部缓冲是 8192 字节；构造一条跨边界的行，确认不会被截断。
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 9000; i++) {
            text.append('x');
        }
        String longLine = text.toString();
        assertThat(readAll(longLine + "\ntail", 20000)).containsExactly(longLine, "tail");
    }

    @Test
    public void rejectsLineOverLimit() {
        BoundedLineReader reader = reader("12345\n", 4);
        assertThatThrownBy(() -> readAll(reader))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("单行");
    }

    @Test
    public void stripsBomAtStreamStart() throws IOException {
        byte[] body = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                bytes("data: a\n"));
        assertThat(readAll(reader(body, 1024))).containsExactly("data: a");
    }

    @Test
    public void keepsLeadingBytesThatAreNotBom() throws IOException {
        // 0xEF 0xBB 开头但不是完整 BOM：字节属于正文，不能被吞掉。
        // 该序列不是合法 UTF-8，解码后是替换字符，所以只断言「行里有正文」。
        byte[] body = concat(new byte[]{(byte) 0xEF, (byte) 0xBB}, bytes("a\n"));
        List<String> lines = readAll(reader(body, 1024));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).endsWith("a");
        assertThat(lines.get(0).length()).isGreaterThan(1);
    }

    @Test
    public void preservedUtf8AcrossBufferBoundary() throws IOException {
        assertThat(readAll("data: 你好🙂\n", 1024)).containsExactly("data: 你好🙂");
    }

    @Test
    public void emptyStreamReturnsNothing() throws IOException {
        assertThat(readAll("", 1024)).isEmpty();
    }

    @Test
    public void trailingCarriageReturnDoesNotEmitExtraLine() throws IOException {
        assertThat(readAll("a\r", 1024)).containsExactly("a");
    }

    private static List<String> readAll(String text, int maxBytes) throws IOException {
        return readAll(reader(text, maxBytes));
    }

    private static List<String> readAll(BoundedLineReader reader) throws IOException {
        List<String> lines = new ArrayList<String>();
        try {
            String line;
            while ((line = reader.next()) != null) {
                lines.add(line);
            }
        } finally {
            reader.close();
        }
        return lines;
    }

    private static BoundedLineReader reader(String text, int maxBytes) {
        return reader(bytes(text), maxBytes);
    }

    private static BoundedLineReader reader(byte[] body, int maxBytes) {
        return new BoundedLineReader(new ByteArrayInputStream(body), maxBytes);
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] all = new byte[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }
}
