package io.github.yinwenyuan28.ailite.http;

/**
 * 非流式 HTTP 响应。
 */
public final class HttpResult {

    private final int status;
    private final String body;

    public HttpResult(int status, String body) {
        this.status = status;
        this.body = body == null ? "" : body;
    }

    public int getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }
}
