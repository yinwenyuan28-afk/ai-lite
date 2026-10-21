package io.github.yinwenyuan28.ailite.llm;

/**
 * 用户消息里的图片。{@code data} 是 base64；可以带 {@code data:...;base64,} 前缀，
 * 构造时会自动去掉，传输层看到的始终是裸 base64。
 */
public final class LlmImage {

    private static final String DATA_PREFIX = "data:";
    private static final String BASE64_SUFFIX = ";base64";

    private final String mime;
    private final String data;

    /**
     * @param mime 图片 MIME 类型，例如 image/png；首尾空白会被去掉
     * @param data base64 数据，带不带 data URL 前缀都可以
     */
    public LlmImage(String mime, String data) {
        this.mime = mime == null ? null : mime.trim();
        this.data = stripDataUrl(data);
    }

    public String getMime() {
        return mime;
    }

    public String getData() {
        return data;
    }

    /**
     * 去掉 data URL 前缀，并清理裸 base64 的首尾空白。
     * 只处理形如 {@code data:<mime>;base64,<data>} 的输入；非 base64 的 data URL
     * 原样保留，由 {@link LlmTexts#validate} 在发请求前拒绝。
     */
    private static String stripDataUrl(String data) {
        if (data == null) {
            return null;
        }
        String trimmed = data.trim();
        if (!trimmed.regionMatches(true, 0, DATA_PREFIX, 0, DATA_PREFIX.length())) {
            return trimmed;
        }
        int comma = trimmed.indexOf(',');
        if (comma < 0) {
            return trimmed;
        }
        String header = trimmed.substring(DATA_PREFIX.length(), comma).toLowerCase();
        if (!header.endsWith(BASE64_SUFFIX)) {
            return trimmed;
        }
        return trimmed.substring(comma + 1).trim();
    }
}
