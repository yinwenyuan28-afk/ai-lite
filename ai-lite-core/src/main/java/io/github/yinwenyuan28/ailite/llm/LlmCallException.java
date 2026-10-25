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
        /** 出口熔断开启，guard 快速失败；不可重试（retry 空转只会拖长 OPEN）。 */
        CIRCUIT_OPEN,
        UNKNOWN
    }

    private final Kind kind;
    private final Integer statusCode;
    private final Long retryAfterMs;

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
        this(message, cause, kind, statusCode, null);
    }

    /**
     * @param message     安全摘要
     * @param cause       原始异常，可为 null
     * @param kind        失败类别；null 按 {@link Kind#UNKNOWN} 处理
     * @param statusCode  HTTP 状态码，非 HTTP 失败为 null
     * @param retryAfterMs 服务端 {@code Retry-After}（秒形式换算的毫秒）；
     *                    仅 429/503 带有效头值时非 null，供重试退避采用
     */
    public LlmCallException(String message, Throwable cause, Kind kind, Integer statusCode,
                            Long retryAfterMs) {
        super(message, cause);
        this.kind = kind == null ? Kind.UNKNOWN : kind;
        this.statusCode = statusCode;
        this.retryAfterMs = retryAfterMs;
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

    /**
     * @return 服务端 {@code Retry-After} 换算的毫秒数；无有效头值为 null
     */
    public Long getRetryAfterMs() {
        return retryAfterMs;
    }

    /**
     * HTTP 错误的统一工厂（限流熔断计划 §3.3）：5 个传输执行器的非 2xx 路径共用，
     * 保证 Retry-After 口径一致——仅 429/503 解析，仅秒数形式。
     *
     * @param message          安全摘要
     * @param statusCode       HTTP 状态码
     * @param retryAfterHeader 响应头 {@code Retry-After} 原始值，可为 null
     * @return 带结构化分类（与退避指令）的异常
     */
    public static LlmCallException httpError(String message, int statusCode, String retryAfterHeader) {
        Long retryAfterMs = null;
        if (statusCode == 429 || statusCode == 503) {
            retryAfterMs = retryAfterMillis(retryAfterHeader);
        }
        return new LlmCallException(message, null, Kind.HTTP, Integer.valueOf(statusCode), retryAfterMs);
    }

    /**
     * 解析 {@code Retry-After} 头：仅接受正整数秒；HTTP 日期形式与非数字/≤0 一律忽略。
     *
     * @param headerValue 响应头原始值，可为 null
     * @return 毫秒数；无有效头值为 null
     */
    public static Long retryAfterMillis(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String text = headerValue.trim();
        if (text.isEmpty()) {
            return null;
        }
        long seconds;
        try {
            seconds = Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (seconds <= 0L) {
            return null;
        }
        return Long.valueOf(seconds * 1000L);
    }
}
