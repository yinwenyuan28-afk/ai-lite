package io.github.yinwenyuan28.ailite.llm;

/**
 * 用户消息里的图片。{@code data} 是不带 data URL 前缀的 base64。
 */
public final class LlmImage {

    private final String mime;
    private final String data;

    /**
     * @param mime 图片 MIME 类型，例如 image/png
     * @param data 不带前缀的 base64 数据
     */
    public LlmImage(String mime, String data) {
        this.mime = mime;
        this.data = data;
    }

    public String getMime() {
        return mime;
    }

    public String getData() {
        return data;
    }
}
