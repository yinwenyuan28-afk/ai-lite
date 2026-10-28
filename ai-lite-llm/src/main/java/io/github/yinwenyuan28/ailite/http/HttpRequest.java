package io.github.yinwenyuan28.ailite.http;

import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 HTTP 请求。方法固定为 POST，足够覆盖 LLM 协议。
 */
public final class HttpRequest {

    private final String url;
    private final Map<String, String> headers;
    private final String body;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final LlmCancelHandle cancel;

    public HttpRequest(String url, Map<String, String> headers, String body,
                       int connectTimeoutMs, int readTimeoutMs, LlmCancelHandle cancel) {
        this.url = url;
        this.headers = headers == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers));
        this.body = body == null ? "" : body;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.cancel = cancel;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public LlmCancelHandle getCancel() {
        return cancel;
    }
}
