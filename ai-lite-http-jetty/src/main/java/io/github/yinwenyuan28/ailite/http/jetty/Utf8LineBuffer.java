package io.github.yinwenyuan28.ailite.http.jetty;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 把任意切分的字节流还原成 UTF-8 文本行。
 *
 * <p>Jetty 的 {@code onContent} 回调只保证“一段字节”，不保证按行、也不保证
 * 不切断多字节字符。这里先按字节寻找 {@code \n}/{@code \r}/{@code \r\n}，
 * 只对完整的一行做 UTF-8 解码；跨 chunk 的多字节字符和 CRLF 都能正确处理。</p>
 *
 * <p>每个流一个实例，不做并发保护；回调线程串行调用即可。</p>
 */
final class Utf8LineBuffer {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final int maxLineBytes;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    /** 上一个字节是 CR；紧随其后的 LF 属于同一个 CRLF，不再产生空行。 */
    private boolean skipNextLf;

    Utf8LineBuffer(int maxLineBytes) {
        this.maxLineBytes = maxLineBytes;
    }

    /**
     * 消费一段字节，返回其中已经完整的行。没有完整行时返回空列表。
     *
     * @param bytes 原始字节
     * @return 完整行，按出现顺序
     */
    List<String> accept(byte[] bytes) {
        List<String> lines = new ArrayList<String>();
        if (bytes == null) {
            return lines;
        }
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            if (skipNextLf) {
                skipNextLf = false;
                if (value == '\n') {
                    continue;
                }
            }
            if (value == '\n') {
                lines.add(decode());
            } else if (value == '\r') {
                // CR 本身已经是行结束；下一个 LF 只负责被吞掉。
                lines.add(decode());
                skipNextLf = true;
            } else {
                line.write(value);
                if (line.size() > maxLineBytes) {
                    throw new LlmCallException("SSE 单行超过 " + maxLineBytes + " 字节", null,
                            LlmCallException.Kind.PARSE);
                }
            }
        }
        return lines;
    }

    /**
     * 流结束时取出最后一行（末尾没有换行符的情况）。
     *
     * @return 最后一行；没有残留内容时返回 null
     */
    String flush() {
        skipNextLf = false;
        if (line.size() == 0) {
            return null;
        }
        return decode();
    }

    private String decode() {
        String value = new String(line.toByteArray(), UTF_8);
        line.reset();
        return value;
    }
}
