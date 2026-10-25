package io.github.yinwenyuan28.ailite.llm;

/**
 * 调用失败时抛出的统一异常。消息里不包含提示词、图片和密钥。
 *
 * <p>{@link Kind} 和 {@link #getStatusCode()} 是给重试策略用的结构化信息：
 * 传输层负责标注瞬时错误，调用方不再解析错误消息字符串。旧构造器保持兼容，
 * 默认 {@link Kind#UNKNOWN}。</p>
 */
public class LlmCallException extends RuntimeException {

    /**
     * 失败类别。只有 {@link #TRANSPORT} 和部分 {@link #HTTP} 可重试。
     */
    public enum Kind {
        CONFIG,
        TRANSPORT,
        HTTP,
        PARSE,
        CANCELLED,
        /** 回调队列已满等本框架过载保护；不可重试。 */
        OVERLOAD,
        UNKNOWN
    }

    private final Kind kind;
    private final Integer statusCode;

    public LlmCallException(String message) {
        this(message, null, Kind.UNKNOWN, null);
    }

    public LlmCallException(String message, Throwable cause) {
        this(message, cause, Kind.UNKNOWN, null);
    }

    /**
     * @param message 安全摘要，不包含提示词、图片和密钥
     * @param cause   原始异常，可为 null
     * @param kind    失败类别；null 按 {@link Kind#UNKNOWN} 处理
     */
    public LlmCallException(String message, Throwable cause, Kind kind) {
        this(message, cause, kind, null);
    }

    /**
     * @param message    安全摘要
     * @param cause      原始异常，可为 null
     * @param kind       失败类别；null 按 {@link Kind#UNKNOWN} 处理
     * @param statusCode HTTP 状态码，非 HTTP 失败为 null
     */
    public LlmCallException(String message, Throwable cause, Kind kind, Integer statusCode) {
        super(message, cause);
        this.kind = kind == null ? Kind.UNKNOWN : kind;
        this.statusCode = statusCode;
    }

    /**
     * @return 失败类别，永不为 null
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * @return HTTP 状态码；非 HTTP 失败为 null
     */
    public Integer getStatusCode() {
        return statusCode;
    }
}
