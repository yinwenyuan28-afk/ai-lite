package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;

/**
 * Retry-After 尊重（限流熔断计划 G3）：头值解析健壮性（只认正整数秒）、
 * 同步/异步重试退避采用头值、clamp 上限与 deadline 收口、无头值退化指数退避。
 */
public class RetryAfterTest {

    private static final List<LlmMessage> MESSAGES = Collections.singletonList(LlmMessage.user("hi"));

    // ------------------------------------------------------------------
    // 头值解析（LlmCallException.retryAfterMillis，5 个执行器共用的口径）
    // ------------------------------------------------------------------

    @Test
    public void parsesPositiveIntegerSeconds() {
        assertThat(LlmCallException.retryAfterMillis("3")).isEqualTo(Long.valueOf(3000L));
        assertThat(LlmCallException.retryAfterMillis(" 2 ")).isEqualTo(Long.valueOf(2000L));
        assertThat(LlmCallException.retryAfterMillis("1")).isEqualTo(Long.valueOf(1000L));
    }

    @Test
    public void ignoresDateFormAndInvalidValues() {
        // HTTP 日期形式、非数字、0、负数、空串一律忽略（返回 null）。
        assertThat(LlmCallException.retryAfterMillis("Wed, 21 Oct 2026 07:28:00 GMT")).isNull();
        assertThat(LlmCallException.retryAfterMillis("soon")).isNull();
        assertThat(LlmCallException.retryAfterMillis("0")).isNull();
        assertThat(LlmCallException.retryAfterMillis("-5")).isNull();
        assertThat(LlmCallException.retryAfterMillis("")).isNull();
        assertThat(LlmCallException.retryAfterMillis(null)).isNull();
    }

    @Test
    public void httpErrorFactoryOnlyParses429And503() {
        LlmCallException rateLimited = LlmCallException.httpError("r", 429, "7");
        assertThat(rateLimited.getRetryAfterMs()).isEqualTo(Long.valueOf(7000L));
        assertThat(rateLimited.getKind()).isEqualTo(LlmCallException.Kind.HTTP);

        LlmCallException unavailable = LlmCallException.httpError("u", 503, "9");
        assertThat(unavailable.getRetryAfterMs()).isEqualTo(Long.valueOf(9000L));

        // 500 即使带头值也不解析（语义上不是限流/维护指令）。
        LlmCallException serverError = LlmCallException.httpError("s", 500, "7");
        assertThat(serverError.getRetryAfterMs()).isNull();

        // 无头值恒为 null。
        LlmCallException noHeader = LlmCallException.httpError("n", 429, null);
        assertThat(noHeader.getRetryAfterMs()).isNull();
    }

    // ------------------------------------------------------------------
    // 同步 oneWithRetry 采用头值退避
    // ------------------------------------------------------------------

    @Test
    public void syncRetryUsesRetryAfterHeaderOverExponentialBackoff() {
        // 策略起退避 2000ms，头值 1s：实际等待应在 1s 附近而非 2s。
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(2).initialBackoffMs(2000L).multiplier(2.0D)
                .maxBackoffMs(10_000L).jitter(false).build();
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(new FailOnceTransport(HTTP_429_WITH_RETRY_AFTER)),
                new NoopLlmCallStore(), new DirectExecutorProvider(), new SequentialToolExecutor());

        long started = System.currentTimeMillis();
        LlmResult result = gateway.oneWithRetry(LlmCall.profile("biz", "main", MESSAGES), policy);
        long elapsed = System.currentTimeMillis() - started;

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(elapsed).isBetween(900L, 1900L);
    }

    @Test
    public void syncRetryClampsHeaderToMaxBackoff() {
        // 头值 60s 超过 maxBackoffMs=1s：退避按 maxBackoff。
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(2).initialBackoffMs(10_000L).multiplier(2.0D)
                .maxBackoffMs(1000L).jitter(false).build();
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(new FailOnceTransport(HTTP_429_WITH_LONG_RETRY_AFTER)),
                new NoopLlmCallStore(), new DirectExecutorProvider(), new SequentialToolExecutor());

        long started = System.currentTimeMillis();
        LlmResult result = gateway.oneWithRetry(LlmCall.profile("biz", "main", MESSAGES), policy);
        long elapsed = System.currentTimeMillis() - started;

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(elapsed).isBetween(900L, 2500L);
    }

    @Test
    public void syncRetryFallsBackToExponentialWithoutHeader() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(2).initialBackoffMs(300L).multiplier(2.0D)
                .maxBackoffMs(2000L).jitter(false).build();
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(new FailOnceTransport(HTTP_429_NO_HEADER)),
                new NoopLlmCallStore(), new DirectExecutorProvider(), new SequentialToolExecutor());

        long started = System.currentTimeMillis();
        LlmResult result = gateway.oneWithRetry(LlmCall.profile("biz", "main", MESSAGES), policy);
        long elapsed = System.currentTimeMillis() - started;

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(elapsed).isBetween(250L, 1500L);
    }

    // ------------------------------------------------------------------
    // 异步 oneWithRetryAsync 采用头值退避
    // ------------------------------------------------------------------

    @Test
    public void asyncRetryUsesRetryAfterHeader() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            RetryPolicy policy = RetryPolicy.builder()
                    .maxAttempts(2).initialBackoffMs(2000L).multiplier(2.0D)
                    .maxBackoffMs(10_000L).jitter(false).build();
            LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                    Collections.singletonList(new FailOnceAsyncTransport(HTTP_429_WITH_RETRY_AFTER)),
                    new NoopLlmCallStore(), new DirectExecutorProvider(),
                    new SequentialToolExecutor(), scheduler);

            long started = System.currentTimeMillis();
            LlmResult result = gateway.oneWithRetryAsync(
                    LlmCall.profile("biz", "main", MESSAGES), policy).get(10L, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - started;

            assertThat(result.getContent()).isEqualTo("ok");
            assertThat(elapsed).isBetween(900L, 1900L);
        } finally {
            scheduler.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 测试传输：第一次返回 429，第二次成功
    // ------------------------------------------------------------------

    private static final LlmCallException HTTP_429_WITH_RETRY_AFTER =
            LlmCallException.httpError("rate limited", 429, "1");
    private static final LlmCallException HTTP_429_WITH_LONG_RETRY_AFTER =
            LlmCallException.httpError("rate limited", 429, "60");
    private static final LlmCallException HTTP_429_NO_HEADER =
            LlmCallException.httpError("rate limited", 429, null);

    /** 同步假传输：第一次抛给定异常，之后返回成功。 */
    private static final class FailOnceTransport implements LlmTransport {

        private final LlmCallException first;
        private boolean failed;

        private FailOnceTransport(LlmCallException first) {
            this.first = first;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            if (!failed) {
                failed = true;
                throw first;
            }
            return new LlmCompletion("ok", null, LlmStopReason.STOP, LlmUsage.zero(), null);
        }
    }

    /** 异步假传输：第一次异常完成，之后成功完成。 */
    private static final class FailOnceAsyncTransport implements LlmTransport {

        private final LlmCallException first;
        private volatile boolean failed;

        private FailOnceAsyncTransport(LlmCallException first) {
            this.first = first;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            throw new UnsupportedOperationException("async only");
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            CompletableFuture<LlmCompletion> future = new CompletableFuture<LlmCompletion>();
            if (!failed) {
                failed = true;
                future.completeExceptionally(first);
            } else {
                future.complete(new LlmCompletion("ok", null, LlmStopReason.STOP,
                        LlmUsage.zero(), null));
            }
            return future;
        }
    }
}
