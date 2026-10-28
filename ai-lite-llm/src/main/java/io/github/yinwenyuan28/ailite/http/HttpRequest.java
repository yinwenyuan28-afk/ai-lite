package io.github.yinwenyuan28.ailite.http;

import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 HTTP 请求。
 *
 * <p>模型协议本身只需要 POST，因此 {@link #HttpRequest(String, Map, String, int, int,
 * LlmCancelHandle)} 默认方法就是 POST——旧调用方不需要改动。管理台的模型列表探测
 * 需要 {@code GET {baseUrl}/models}，才补了带 {@code method} 的构造器：让它同样走
 * 配置启用的传输，而不是另起一个客户端。</p>
 */
public final class HttpRequest {

    /** 默认方法。模型协议全部是 POST。 */
    public static final String POST = "POST";

    /** 只读探测用。 */
    public static final String GET = "GET";

    private final String method;
    private final String url;
    private final Map<String, String> headers;
    private final String body;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final LlmCancelHandle cancel;

    /**
     * 以 POST 发起请求。
     *
     * @param url              绝对地址
     * @param headers          请求头，可为 null
     * @param body             请求体，可为 null
     * @param connectTimeoutMs 连接超时，非正值由传输实现取默认
     * @param readTimeoutMs    读取/空闲超时，非正值由传输实现取默认
     * @param cancel           取消句柄，可为 null
     */
    public HttpRequest(String url, Map<String, String> headers, String body,
                       int connectTimeoutMs, int readTimeoutMs, LlmCancelHandle cancel) {
        this(POST, url, headers, body, connectTimeoutMs, readTimeoutMs, cancel);
    }

    /**
     * @param method           请求方法，null 或空按 POST 处理
     * @param url              绝对地址
     * @param headers          请求头，可为 null
     * @param body             请求体；GET 时忽略
     * @param connectTimeoutMs 连接超时
     * @param readTimeoutMs    读取/空闲超时
     * @param cancel           取消句柄
     */
    public HttpRequest(String method, String url, Map<String, String> headers, String body,
                       int connectTimeoutMs, int readTimeoutMs, LlmCancelHandle cancel) {
        this.method = method == null || method.trim().isEmpty()
                ? POST : method.trim().toUpperCase();
        this.url = url;
        this.headers = headers == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers));
        this.body = body == null ? "" : body;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.cancel = cancel;
    }

    /**
     * @return 请求方法，大写，默认 {@link #POST}
     */
    public String getMethod() {
        return method;
    }

    /**
     * @return true 表示 GET；GET 不带请求体
     */
    public boolean isGet() {
        return GET.equals(method);
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
