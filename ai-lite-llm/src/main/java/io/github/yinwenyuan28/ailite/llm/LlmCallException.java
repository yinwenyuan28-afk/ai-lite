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
        /**
         * 上下文超出模型窗口且无法通过压缩解决；不可重试。
         *
         * <p>由编排层（{@code AgentSessionLoop}）在发送前估算得出，或压缩失败后抛出。
         * 不做静默截断/丢历史：静默修改对话历史比报错更难排查。</p>
         */
        CONTEXT_OVERFLOW,
        UNKNOWN
    }

    private final Kind kind;
    private final Integer statusCode;
    private final Long retryAfterMs;

    /**
     * {@code Retry-After} 换算上限：24 小时。
     *
     * <p>既挡住 {@code seconds * 1000} 的溢出，也挡住「供应商写 999999999 秒」这种
     * 病态长退避——按上限等待即可，不必把异常值原样传下去。</p>
     */
    public static final long MAX_RETRY_AFTER_MS = 24L * 60L * 60L * 1000L;

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
     * 上下文超限的统一工厂。错误消息里带上估算值与窗口上限，
     * 便于调用方定位"是历史太长还是单条太大"。
     *
     * @param estimatedTokens 估算的输入 token 数
     * @param limitTokens     允许的上限（contextWindow - reserveTokens）
     * @param detail          附加说明，例如"压缩已关闭"或"压缩后仍超限"，可为 null
     * @return 带 {@link Kind#CONTEXT_OVERFLOW} 分类的异常
     */
    public static LlmCallException contextOverflow(int estimatedTokens, int limitTokens, String detail) {
        StringBuilder message = new StringBuilder("上下文超出模型窗口：估算 ")
                .append(estimatedTokens).append(" tokens，上限 ").append(limitTokens).append(" tokens");
        if (detail != null && !detail.trim().isEmpty()) {
            message.append("（").append(detail).append("）");
        }
        return new LlmCallException(message.toString(), null, Kind.CONTEXT_OVERFLOW);
    }

    /**
     * 解析 {@code Retry-After} 头：仅接受正整数秒；HTTP 日期形式与非数字/≤0 一律忽略。
     *
     * <p>结果钳在 {@link #MAX_RETRY_AFTER_MS}（24 小时）以内。{@code seconds * 1000}
     * 没有检查会溢出：{@code Retry-After: 9223372036854775807} 回绕成 -1000，再被
     * {@code RetrySupport.backoffMs} 的 {@code Math.max(0L, delay)} 钳成 0，本该等很久
     * 的 429 变成立刻重打，持续打爆供应商限流。超过上限时按上限处理而不是返回 null：
     * 返回 null 表示「服务端没给指令」会退回秒级指数退避，与供应商「很久以后再来」
     * 的意图相反。</p>
     *
     * @param headerValue 响应头原始值，可为 null
     * @return 毫秒数，范围 (0, 24h]；无有效头值为 null
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
        long millis;
        try {
            millis = Math.multiplyExact(seconds, 1000L);
        } catch (ArithmeticException ex) {
            return Long.valueOf(MAX_RETRY_AFTER_MS);
        }
        return Long.valueOf(Math.min(millis, MAX_RETRY_AFTER_MS));
    }
}
