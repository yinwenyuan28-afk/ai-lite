package io.github.yinwenyuan28.ailite.llm;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * 单次模型调用的重试策略。不可变对象，通过 {@link #builder()} 构造。
 *
 * <p>它只决定“要不要重试、等多久”，不负责执行调用，也不感知工具或编排层。
 * 默认策略只重试瞬时错误：传输失败、HTTP 408/429/500/502/503/504，以及
 * {@link LlmCallException.Kind#UNKNOWN} 但根因是 {@link IOException} 的第三方传输异常。</p>
 */
public final class RetryPolicy {

    /**
     * 每次即将重试时的观察回调。回调抛出的 RuntimeException 会被吞掉。
     */
    public interface Listener {

        /**
         * @param failedAttempt 已经失败的尝试次数，从 1 开始
         * @param delayMs       本次退避毫秒数
         * @param error         导致重试的异常
         */
        void onRetry(int failedAttempt, long delayMs, LlmCallException error);
    }

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final double multiplier;
    private final long maxBackoffMs;
    private final boolean jitter;
    private final Long totalDeadlineMs;
    private final Predicate<LlmCallException> retryable;
    private final Listener listener;

    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialBackoffMs = builder.initialBackoffMs;
        this.multiplier = builder.multiplier;
        this.maxBackoffMs = builder.maxBackoffMs;
        this.jitter = builder.jitter;
        this.totalDeadlineMs = builder.totalDeadlineMs;
        this.retryable = builder.retryable;
        this.listener = builder.listener;
    }

    /**
     * @return 默认策略：3 次尝试、200ms 起、2 倍退避、封顶 2s、带抖动、无总时限
     */
    public static RetryPolicy defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public boolean isJitter() {
        return jitter;
    }

    /**
     * @return 总时限毫秒数；null 表示不限制
     */
    public Long getTotalDeadlineMs() {
        return totalDeadlineMs;
    }

    /**
     * @param error 失败异常
     * @return 是否允许重试
     */
    public boolean shouldRetry(LlmCallException error) {
        return error != null && retryable.test(error);
    }

    /**
     * 计算第 N 次失败后的退避时间。
     *
     * @param failedAttempt 已失败次数，从 1 开始
     * @return 退避毫秒数
     */
    public long delayMs(int failedAttempt) {
        int attempt = Math.max(1, failedAttempt);
        double raw = initialBackoffMs * Math.pow(multiplier, attempt - 1);
        long capped = Math.min(maxBackoffMs, (long) raw);
        if (capped <= 0L) {
            return 0L;
        }
        if (jitter) {
            double factor = 0.8D + ThreadLocalRandom.current().nextDouble() * 0.4D;
            capped = Math.max(0L, Math.round(capped * factor));
            capped = Math.min(maxBackoffMs, capped);
        }
        return capped;
    }

    /**
     * 通知监听器；监听器异常不影响重试。
     */
    public void notifyRetry(int failedAttempt, long delayMs, LlmCallException error) {
        if (listener == null) {
            return;
        }
        try {
            listener.onRetry(failedAttempt, delayMs, error);
        } catch (RuntimeException ignored) {
            // 观察者失败不能改变重试行为。
        }
    }

    /**
     * 默认可重试判定。
     *
     * @param error 失败异常
     * @return 是否属于瞬时错误
     */
    public static boolean defaultRetryable(LlmCallException error) {
        if (error == null) {
            return false;
        }
        switch (error.getKind()) {
            case TRANSPORT:
                return true;
            case HTTP:
                return isRetryableStatus(error.getStatusCode());
            case UNKNOWN:
                // 兼容第三方 transport：没有结构化分类，但根因是可识别的 IO 异常。
                return hasIoCause(error);
            default:
                return false;
        }
    }

    private static boolean isRetryableStatus(Integer statusCode) {
        if (statusCode == null) {
            return false;
        }
        switch (statusCode.intValue()) {
            case 408:
            case 429:
            case 500:
            case 502:
            case 503:
            case 504:
                return true;
            default:
                return false;
        }
    }

    private static boolean hasIoCause(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 可变 builder，只用于构造 {@link RetryPolicy}。
     */
    public static final class Builder {

        private int maxAttempts = 3;
        private long initialBackoffMs = 200L;
        private double multiplier = 2.0D;
        private long maxBackoffMs = 2000L;
        private boolean jitter = true;
        private Long totalDeadlineMs;
        private Predicate<LlmCallException> retryable = new Predicate<LlmCallException>() {
            @Override
            public boolean test(LlmCallException error) {
                return defaultRetryable(error);
            }
        };
        private Listener listener;

        private Builder() {
        }

        /**
         * @param maxAttempts 总尝试次数（含第一次），必须大于 0
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
            return this;
        }

        public Builder multiplier(double multiplier) {
            this.multiplier = multiplier;
            return this;
        }

        public Builder maxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
            return this;
        }

        public Builder jitter(boolean jitter) {
            this.jitter = jitter;
            return this;
        }

        /**
         * @param totalDeadlineMs 覆盖全部尝试和退避的总时限；null 表示不限制
         */
        public Builder totalDeadlineMs(Long totalDeadlineMs) {
            this.totalDeadlineMs = totalDeadlineMs;
            return this;
        }

        /**
         * @param retryable 自定义可重试判定；null 表示使用默认判定
         */
        public Builder retryable(Predicate<LlmCallException> retryable) {
            this.retryable = retryable == null
                    ? new Predicate<LlmCallException>() {
                        @Override
                        public boolean test(LlmCallException error) {
                            return defaultRetryable(error);
                        }
                    }
                    : retryable;
            return this;
        }

        public Builder listener(Listener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * @return 不可变策略
         * @throws IllegalArgumentException 参数非法时抛出
         */
        public RetryPolicy build() {
            if (maxAttempts <= 0) {
                throw new IllegalArgumentException("maxAttempts 必须大于 0");
            }
            if (initialBackoffMs < 0L) {
                throw new IllegalArgumentException("initialBackoffMs 不能小于 0");
            }
            if (multiplier < 1.0D) {
                throw new IllegalArgumentException("multiplier 不能小于 1");
            }
            if (maxBackoffMs < 0L) {
                throw new IllegalArgumentException("maxBackoffMs 不能小于 0");
            }
            if (totalDeadlineMs != null && totalDeadlineMs.longValue() < 0L) {
                throw new IllegalArgumentException("totalDeadlineMs 不能小于 0");
            }
            return new RetryPolicy(this);
        }
    }
}
