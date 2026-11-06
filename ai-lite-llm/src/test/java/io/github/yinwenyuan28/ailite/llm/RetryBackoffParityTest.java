package io.github.yinwenyuan28.ailite.llm;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三个重试入口的退避口径必须完全一致（F-13）。
 *
 * <p>同步（{@code oneWithRetry}）、异步（{@code RetryRun}）、协程
 * （{@code LlmCoroutines.oneWithRetry}）此前各自算退避：Java 两处处理了
 * {@code Retry-After}，协程那处漏了，429 下按指数退避猛打。现在三者共用
 * {@code RetrySupport.backoffMs}，本类把「同参同值」这条约束锁住。</p>
 */
public class RetryBackoffParityTest {

    /** 只验证纯粹的函数口径，不涉及任何线程与等待。 */
    private static RetryPolicy policy(long maxBackoffMs) {
        return RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoffMs(1000L)
                .multiplier(2.0D)
                .maxBackoffMs(maxBackoffMs)
                .jitter(false)
                .build();
    }

    private static LlmCallException rateLimited(Long retryAfterMs) {
        return new LlmCallException("rate limited", null, LlmCallException.Kind.HTTP,
                Integer.valueOf(429), retryAfterMs);
    }

    /** 无头值：走策略的指数退避。 */
    @Test
    public void withoutHeaderFallsBackToExponential() {
        RetryPolicy policy = policy(60_000L);
        // 第 1 次失败：1000ms；第 2 次：2000ms；第 3 次：4000ms。
        assertThat(io.github.yinwenyuan28.ailite.internal.llm.RetrySupport
                .backoffMs(policy, rateLimited(null), 1)).isEqualTo(1000L);
        assertThat(io.github.yinwenyuan28.ailite.internal.llm.RetrySupport
                .backoffMs(policy, rateLimited(null), 2)).isEqualTo(2000L);
        assertThat(io.github.yinwenyuan28.ailite.internal.llm.RetrySupport
                .backoffMs(policy, rateLimited(null), 3)).isEqualTo(4000L);
    }

    /** 有头值：头值优先于指数退避。 */
    @Test
    public void headerOverridesExponential() {
        RetryPolicy policy = policy(60_000L);
        assertThat(io.github.yinwenyuan28.ailite.internal.llm.RetrySupport
                .backoffMs(policy, rateLimited(Long.valueOf(300L)), 1)).isEqualTo(300L);
        assertThat(io.github.yinwenyuan28.ailite.internal.llm.RetrySupport
                .backoffMs(policy, rateLimited(Long.valueOf(7000L)), 1)).isEqualTo(7000L);
    }

    /** 头值超过上限：压到 maxBackoffMs，避免一个离谱的头值把调用挂死。 */
    @Test
    public void headerIsCappedByMaxBackoff() {
        RetryPolicy policy = policy(500L);
        assertThat(io.github.yinwenyuan28.ailite.internal.llm.RetrySupport
                .backoffMs(policy, rateLimited(Long.valueOf(60_000L)), 1)).isEqualTo(500L);
    }

    /** null 失败信息（异常路径）按无头值处理，不能 NPE。 */
    @Test
    public void nullFailureUsesExponential() {
        RetryPolicy policy = policy(60_000L);
        assertThat(io.github.yinwenyuan28.ailite.internal.llm.RetrySupport
                .backoffMs(policy, null, 1)).isEqualTo(1000L);
    }

    /**
     * 三个入口在同一个失败、同一份策略下，监听器收到的退避值必须一致。
     *
     * <p>只跑同步和异步两条真实链路（协程侧在同模块的
     * {@code LlmCoroutinesRetryTest#retryAfterIsHonoured} 里用同样的断言覆盖，
     * 避免在这里引入 kotlin 依赖）。</p>
     */
    @Test
    public void syncAndAsyncReportSameDelay() throws Exception {
        RetryPolicy syncPolicy = recording(policy(60_000L), syncDelays);
        RetryPolicy asyncPolicy = recording(policy(60_000L), asyncDelays);


        LlmCall call = LlmCall.profile("biz", "main",
                java.util.Collections.singletonList(LlmMessage.user("hi")));
        // 各用一份新网关与传输，避免第一次的失败被对方消费掉。
        newGateway().oneWithRetry(call, syncPolicy);
        newGateway().oneWithRetryAsync(call, asyncPolicy)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(syncDelays).isNotEmpty();
        assertThat(asyncDelays).isNotEmpty();
        assertThat(asyncDelays.get(0))
                .describedAs("同步与异步必须报出同一个退避值")
                .isEqualTo(syncDelays.get(0));
        assertThat(syncDelays.get(0))
                .describedAs("两边都该采用 Retry-After 的 250ms")
                .isEqualTo(250L);
    }

    private static LlmGateway newGateway() {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                java.util.Collections.singletonList(new FailOnceThenSucceedTransport()),
                new io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore(),
                new io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider(),
                new io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor());
    }

    private final List<Long> syncDelays = new ArrayList<Long>();
    private final List<Long> asyncDelays = new ArrayList<Long>();

    private static RetryPolicy recording(RetryPolicy base, final List<Long> sink) {
        return RetryPolicy.builder()
                .maxAttempts(base.getMaxAttempts())
                .initialBackoffMs(1L)
                .multiplier(base.getMultiplier())
                .maxBackoffMs(base.getMaxBackoffMs())
                .jitter(false)
                .listener(new RetryPolicy.Listener() {
                    @Override
                    public void onRetry(int failedAttempt, long delayMs, LlmCallException error) {
                        sink.add(Long.valueOf(delayMs));
                    }
                })
                .build();
    }

    /**
     * 第一次抛 429（带 Retry-After），第二次成功。
     *
     * <p>同步与异步各用一份实例，避免第一次的失败被对方消费掉。</p>
     */
    private static final class FailOnceThenSucceedTransport implements LlmTransport {

        private final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        private static final long RETRY_AFTER_MS = 250L;

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            if (calls.getAndIncrement() == 0) {
                throw new LlmCallException("rate limited", null,
                        LlmCallException.Kind.HTTP, Integer.valueOf(429),
                        Long.valueOf(RETRY_AFTER_MS));
            }
            return new LlmCompletion("ok", null, LlmStopReason.STOP, LlmUsage.zero(), null);
        }

        @Override
        public java.util.concurrent.CompletableFuture<LlmCompletion> completeAsync(
                LlmRequest request) {
            if (calls.getAndIncrement() == 0) {
                java.util.concurrent.CompletableFuture<LlmCompletion> failed =
                        new java.util.concurrent.CompletableFuture<LlmCompletion>();
                failed.completeExceptionally(new LlmCallException("rate limited", null,
                        LlmCallException.Kind.HTTP, Integer.valueOf(429),
                        Long.valueOf(RETRY_AFTER_MS)));
                return failed;
            }
            return java.util.concurrent.CompletableFuture.completedFuture(complete(request));
        }
    }
}
