package io.github.yinwenyuan28.ailite.llm;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重试策略：默认值、退避、可重试判定、监听器与异常分类。
 */
public class RetryPolicyTest {

    @Test
    public void defaultsAreConservative() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertThat(policy.getMaxAttempts()).isEqualTo(3);
        assertThat(policy.getInitialBackoffMs()).isEqualTo(200L);
        assertThat(policy.getMultiplier()).isEqualTo(2.0D);
        assertThat(policy.getMaxBackoffMs()).isEqualTo(2000L);
        assertThat(policy.isJitter()).isTrue();
        assertThat(policy.getTotalDeadlineMs()).isNull();
    }

    @Test
    public void builderRejectsInvalidValues() {
        assertThatThrownBy(() -> RetryPolicy.builder().maxAttempts(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().initialBackoffMs(-1L).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().multiplier(0.5D).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().maxBackoffMs(-1L).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().totalDeadlineMs(-1L).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void backoffGrowsAndCapsWithoutJitter() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoffMs(200L)
                .multiplier(2.0D)
                .maxBackoffMs(2000L)
                .jitter(false)
                .build();

        assertThat(policy.delayMs(1)).isEqualTo(200L);
        assertThat(policy.delayMs(2)).isEqualTo(400L);
        assertThat(policy.delayMs(3)).isEqualTo(800L);
        assertThat(policy.delayMs(4)).isEqualTo(1600L);
        assertThat(policy.delayMs(5)).isEqualTo(2000L);
        assertThat(policy.delayMs(100)).isEqualTo(2000L);
    }

    @Test
    public void zeroInitialBackoffStaysZero() {
        RetryPolicy policy = RetryPolicy.builder()
                .initialBackoffMs(0L).maxBackoffMs(0L).jitter(true).build();

        assertThat(policy.delayMs(1)).isZero();
        assertThat(policy.delayMs(10)).isZero();
    }

    @Test
    public void defaultRetryableOnlyCoversTransientErrors() {
        assertThat(RetryPolicy.defaultRetryable(transportError())).isTrue();
        assertThat(RetryPolicy.defaultRetryable(httpError(408))).isTrue();
        assertThat(RetryPolicy.defaultRetryable(httpError(429))).isTrue();
        assertThat(RetryPolicy.defaultRetryable(httpError(500))).isTrue();
        assertThat(RetryPolicy.defaultRetryable(httpError(502))).isTrue();
        assertThat(RetryPolicy.defaultRetryable(httpError(503))).isTrue();
        assertThat(RetryPolicy.defaultRetryable(httpError(504))).isTrue();

        assertThat(RetryPolicy.defaultRetryable(httpError(400))).isFalse();
        assertThat(RetryPolicy.defaultRetryable(httpError(401))).isFalse();
        assertThat(RetryPolicy.defaultRetryable(httpError(404))).isFalse();
        assertThat(RetryPolicy.defaultRetryable(httpError(422))).isFalse();
        assertThat(RetryPolicy.defaultRetryable(
                new LlmCallException("parse", null, LlmCallException.Kind.PARSE))).isFalse();
        assertThat(RetryPolicy.defaultRetryable(
                new LlmCallException("cancel", null, LlmCallException.Kind.CANCELLED))).isFalse();
        assertThat(RetryPolicy.defaultRetryable(
                new LlmCallException("config", null, LlmCallException.Kind.CONFIG))).isFalse();
        assertThat(RetryPolicy.defaultRetryable(null)).isFalse();
    }

    @Test
    public void unknownKindWithIoCauseIsRetryableForThirdPartyTransports() {
        assertThat(RetryPolicy.defaultRetryable(
                new LlmCallException("boom", new IOException("reset")))).isTrue();
        assertThat(RetryPolicy.defaultRetryable(
                new LlmCallException("boom", new IllegalStateException("bug")))).isFalse();
    }

    @Test
    public void customPredicateOverridesDefault() {
        RetryPolicy policy = RetryPolicy.builder()
                .retryable(new java.util.function.Predicate<LlmCallException>() {
                    @Override
                    public boolean test(LlmCallException error) {
                        return true;
                    }
                }).build();

        assertThat(policy.shouldRetry(httpError(400))).isTrue();
        assertThat(policy.shouldRetry(null)).isFalse();
    }

    @Test
    public void listenerIsNotifiedAndExceptionsAreIgnored() {
        final AtomicInteger calls = new AtomicInteger();
        final long[] delay = new long[1];
        RetryPolicy policy = RetryPolicy.builder().listener(new RetryPolicy.Listener() {
            @Override
            public void onRetry(int failedAttempt, long delayMs, LlmCallException error) {
                calls.incrementAndGet();
                delay[0] = delayMs;
            }
        }).build();

        policy.notifyRetry(1, 25L, transportError());

        assertThat(calls.get()).isEqualTo(1);
        assertThat(delay[0]).isEqualTo(25L);

        RetryPolicy exploding = RetryPolicy.builder().listener(new RetryPolicy.Listener() {
            @Override
            public void onRetry(int failedAttempt, long delayMs, LlmCallException error) {
                throw new IllegalStateException("observer bug");
            }
        }).build();
        exploding.notifyRetry(1, 1L, transportError());
    }

    @Test
    public void exceptionCarriesStructuredKindAndStatus() {
        LlmCallException oldStyle = new LlmCallException("old");
        assertThat(oldStyle.getKind()).isEqualTo(LlmCallException.Kind.UNKNOWN);
        assertThat(oldStyle.getStatusCode()).isNull();

        LlmCallException http = httpError(503);
        assertThat(http.getKind()).isEqualTo(LlmCallException.Kind.HTTP);
        assertThat(http.getStatusCode()).isEqualTo(Integer.valueOf(503));
    }

    private static LlmCallException transportError() {
        return new LlmCallException("boom", new IOException("io"),
                LlmCallException.Kind.TRANSPORT);
    }

    private static LlmCallException httpError(int status) {
        return new LlmCallException("http " + status, null,
                LlmCallException.Kind.HTTP, Integer.valueOf(status));
    }
}
