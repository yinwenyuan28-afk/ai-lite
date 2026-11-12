package io.github.yinwenyuan28.ailite.internal.http;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 把任意切分的字节流还原成 UTF-8 文本行。
 *
 * <p>异步 HTTP 客户端只保证“一段字节”，不保证按行、也不保证不切断多字节字符。
 * 这里先按字节寻找 {@code \n}/{@code \r}/{@code \r\n}，只对完整的一行做 UTF-8
 * 解码；跨 chunk 的多字节字符和 CRLF 都能正确处理。</p>
 *
 * <p>文件开头若有 UTF-8 BOM（{@code EF BB BF}），会被剥掉。SSE 解析靠
 * {@code startsWith("data:")} 判断事件行，一个 {@code \uFEFF} 前缀就能让整个流
 * 的首个事件被静默丢掉；供应商按「UTF-8 with BOM」导出时这不是假想问题。BOM 可能
 * 跨 chunk 到达，因此用「逐字节匹配前缀」的方式识别，而不是先攒够 3 字节。</p>
 *
 * <p>每个流一个实例，不做并发保护；回调线程串行调用即可。单行超过上限时抛
 * {@link LlmCallException}，避免恶意或损坏响应把内存打满。</p>
 */
public final class Utf8LineBuffer {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** UTF-8 BOM 的三个字节。 */
    private static final int[] BOM = {0xEF, 0xBB, 0xBF};

    private final int maxLineBytes;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    /** 上一个字节是 CR；紧随其后的 LF 属于同一个 CRLF，不再产生空行。 */
    private boolean skipNextLf;
    /** 已匹配到的 BOM 前缀长度；0 表示还没开始或已判定不是 BOM。 */
    private int bomMatched;
    /** true 表示仍处在「可能是 BOM」的开头阶段。 */
    private boolean atStreamStart = true;

    /**
     * @param maxLineBytes 单行字节上限
     */
    public Utf8LineBuffer(int maxLineBytes) {
        this.maxLineBytes = maxLineBytes;
    }

    /**
     * 消费一段字节，返回其中已经完整的行。没有完整行时返回空列表。
     *
     * @param bytes 原始字节
     * @return 完整行，按出现顺序
     */
    public List<String> accept(byte[] bytes) {
        List<String> lines = new ArrayList<String>();
        if (bytes == null) {
            return lines;
        }
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            if (atStreamStart) {
                if (value == BOM[bomMatched]) {
                    bomMatched++;
                    if (bomMatched == BOM.length) {
                        // 三个字节全是 BOM，整体丢弃，正文从这里开始。
                        atStreamStart = false;
                        bomMatched = 0;
                    }
                    continue;
                }
                // 前缀不匹配：已匹配的那几个字节属于正文，回灌后按普通字节继续。
                atStreamStart = false;
                for (int j = 0; j < bomMatched; j++) {
                    append((byte) BOM[j], lines);
                }
                bomMatched = 0;
            }
            append((byte) value, lines);
        }
        return lines;
    }

    /**
     * 流结束时取出最后一行（末尾没有换行符的情况）。
     *
     * @return 最后一行；没有残留内容时返回 null
     */
    public String flush() {
        skipNextLf = false;
        if (atStreamStart && bomMatched > 0) {
            // 流在 BOM 前缀中途结束：这几个字节是正文，不能丢。
            for (int j = 0; j < bomMatched; j++) {
                line.write(BOM[j]);
            }
            bomMatched = 0;
            atStreamStart = false;
        }
        if (line.size() == 0) {
            return null;
        }
        return decode();
    }

    /**
     * 把一个字节按行切分规则消费掉。
     */
    private void append(byte raw, List<String> lines) {
        int value = raw & 0xFF;
        if (skipNextLf) {
            skipNextLf = false;
            if (value == '\n') {
                return;
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

    private String decode() {
        String value = new String(line.toByteArray(), UTF_8);
        line.reset();
        return value;
    }
}
