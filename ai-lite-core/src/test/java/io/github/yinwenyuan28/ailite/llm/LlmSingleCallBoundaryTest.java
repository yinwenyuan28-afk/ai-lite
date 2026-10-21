package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单次调用路径的边界收口：适配器违约时必须变成可观察的失败，而不是挂起。
 */
public class LlmSingleCallBoundaryTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @After
    public void tearDown() {
        worker.shutdownNow();
    }

    @Test
    public void nullFutureFailsInsteadOfHanging() {
        CompletableFuture<LlmResult> future = gateway(new Transport() {
            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                return null;
            }
        }).oneAsync(call());

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("空 future");
    }

    @Test
    public void nullCompletionFailsInsteadOfHanging() {
        CompletableFuture<LlmResult> future = gateway(new Transport() {
            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                return CompletableFuture.completedFuture(null);
            }
        }).oneAsync(call());

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("空结果");
    }

    @Test
    public void syncNullCompletionFailsWithLlmCallException() {
        LlmGateway gateway = gateway(new Transport() {
            @Override
            public LlmCompletion complete(LlmRequest request) {
                return null;
            }
        });

        assertThatThrownBy(() -> gateway.one(call()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("空结果");
    }

    @Test
    public void transportThrowingFromCompleteAsyncFailsTheFutureNotTheCaller() {
        CompletableFuture<LlmResult> future = gateway(new Transport() {
            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                throw new IllegalStateException("adapter exploded");
            }
        }).oneAsync(call());

        assertThat(future.isDone()).isTrue();
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型调用失败");
    }

    @Test
    public void asyncDeltaFailureCompletesExceptionallyInsteadOfHanging() throws Exception {
        CompletableFuture<LlmResult> future = gateway(new Transport() {
            @Override
            public CompletableFuture<LlmCompletion> completeAsync(final LlmRequest request) {
                return CompletableFuture.supplyAsync(() -> {
                    request.getOnDelta().accept("chunk");
                    return stop("done");
                }, worker);
            }
        }).oneByStreamAsync(call(), new Consumer<String>() {
            @Override
            public void accept(String delta) {
                throw new IllegalStateException("consumer exploded");
            }
        }, null);

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型调用失败");
    }

    @Test
    public void nullStopAndUsageAreNormalizedBeforeReachingTheResult() throws Exception {
        CompletableFuture<LlmResult> future = gateway(new Transport() {
            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                return CompletableFuture.completedFuture(
                        new LlmCompletion(null, null, null, null, null));
            }
        }).oneAsync(call());

        LlmResult result = future.get(5, TimeUnit.SECONDS);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.STOP);
        assertThat(result.getUsage().getTotalTokens()).isZero();
    }

    @Test
    public void cancelRacingWithCompletionYieldsExactlyOneOutcome() throws Exception {
        for (int i = 0; i < 50; i++) {
            final LlmCancelHandle handle = new LlmCancelHandle();
            Transport transport = new Transport() {
                @Override
                public CompletableFuture<LlmCompletion> completeAsync(final LlmRequest request) {
                    return CompletableFuture.supplyAsync(() -> stop("done"), worker);
                }
            };

            CompletableFuture<LlmResult> future = gateway(transport).oneAsync(call(), handle);
            handle.cancel();
            LlmResult result = future.get(5, TimeUnit.SECONDS);

            assertThat(result.getStopReason())
                    .isIn(LlmStopReason.STOP, LlmStopReason.ABORTED);
        }
    }

    private static LlmGateway gateway(LlmTransport transport) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmCall call() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    private static LlmCompletion stop(String content) {
        return new LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.zero(), null);
    }

    /**
     * 默认异步实现只是把 complete 放到公共池，测试里按需覆盖。
     */
    private abstract static class Transport implements LlmTransport {
        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            throw new UnsupportedOperationException("async only");
        }
    }
}
