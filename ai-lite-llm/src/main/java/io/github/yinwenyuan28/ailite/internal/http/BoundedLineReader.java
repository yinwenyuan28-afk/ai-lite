package io.github.yinwenyuan28.ailite.internal.http;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * 有上限的 UTF-8 逐行读取，供阻塞式传输共用。
 *
 * <p>{@code BufferedReader.readLine()} 与 OkHttp 的 {@code readUtf8Line()} 都没有
 * 长度限制：损坏或恶意的响应只要有一条不换行的超长「行」，就能把堆撑爆。这里按
 * 字节累计，超过上限抛 {@link LlmCallException}（PARSE），让上层按响应损坏处理。</p>
 *
 * <p>行切分规则与 {@link Utf8LineBuffer} 保持一致：{@code \n}、{@code \r}、
 * {@code \r\n} 都算行结束，单独出现的 {@code \r} 后面若紧跟 {@code \n} 不产出空行。
 * 首行开头的 UTF-8 BOM 会被剥掉——SSE 的 {@code startsWith("data:")} 判断经不起
 * 一个 {@code \uFEFF} 前缀。</p>
 *
 * <p>每个流一个实例，不做并发保护；调用方在当前线程串行读取即可。</p>
 */
public final class BoundedLineReader implements Closeable {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** UTF-8 BOM 的三个字节。 */
    private static final int BOM_0 = 0xEF;
    private static final int BOM_1 = 0xBB;
    private static final int BOM_2 = 0xBF;

    private final InputStream input;
    private final int maxBytes;
    /** 复用的读缓冲，避免每行都新建数组。 */
    private final byte[] buffer = new byte[8192];
    /** 当前行已经读到的字节。 */
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    private int position;
    private int limit;
    private boolean eof;
    /** 上一个字节是 CR；紧随其后的 LF 属于同一个 CRLF。 */
    private boolean skipNextLf;
    private boolean firstLine = true;

    /**
     * @param input    UTF-8 字节流
     * @param maxBytes 单行字节上限，必须为正
     */
    public BoundedLineReader(InputStream input, int maxBytes) {
        if (input == null) {
            throw new IllegalArgumentException("输入流不能为空");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("单行上限必须为正");
        }
        this.input = input;
        this.maxBytes = maxBytes;
    }

    /**
     * 读下一行。
     *
     * @return 一行文本（不含换行符）；流结束返回 null
     * @throws IOException      底层读取失败
     * @throws LlmCallException 单行超过上限
     */
    public String next() throws IOException {
        for (;;) {
            if (position >= limit && !fill()) {
                if (line.size() == 0) {
                    return null;
                }
                return decode();
            }
            int value = buffer[position++] & 0xFF;
            if (skipNextLf) {
                skipNextLf = false;
                if (value == '\n') {
                    continue;
                }
            }
            if (value == '\n') {
                return decode();
            }
            if (value == '\r') {
                skipNextLf = true;
                return decode();
            }
            line.write(value);
            if (line.size() > maxBytes) {
                throw new LlmCallException("SSE 单行超过 " + maxBytes + " 字节", null,
                        LlmCallException.Kind.PARSE);
            }
        }
    }

    /**
     * @return true 表示读到了新字节；false 表示流已结束
     */
    private boolean fill() throws IOException {
        if (eof) {
            return false;
        }
        int read = input.read(buffer, 0, buffer.length);
        if (read < 0) {
            eof = true;
            return false;
        }
        position = 0;
        limit = read;
        return true;
    }

    /**
     * 把当前行解码成字符串。首行的 UTF-8 BOM 在这里剥掉。
     */
    private String decode() {
        byte[] bytes = line.toByteArray();
        line.reset();
        int offset = 0;
        if (firstLine) {
            firstLine = false;
            if (bytes.length >= 3
                    && (bytes[0] & 0xFF) == BOM_0
                    && (bytes[1] & 0xFF) == BOM_1
                    && (bytes[2] & 0xFF) == BOM_2) {
                offset = 3;
            }
        }
        return new String(bytes, offset, bytes.length - offset, UTF_8);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
