package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 异步网关：非阻塞完成、工具循环、取消、异常和流式回调。
 */
public class LlmGatewayAsyncTest {

    private static final List<LlmMessage> MESSAGES = Collections.singletonList(LlmMessage.user("hi"));

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @After
    public void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    public void oneAsyncReturnsContent() throws Exception {
        AsyncFakeTransport transport = new AsyncFakeTransport(scheduler, 10L)
                .enqueue(completion("answer", LlmStopReason.STOP, null));
        LlmGateway gateway = gateway(transport);

        LlmResult result = gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getContent()).isEqualTo("answer");
        assertThat(result.getRounds()).isEqualTo(1);
        assertThat(result.getUsage().getTotalTokens()).isEqualTo(5);
    }

    @Test
    public void validationStillFailsSynchronously() {
        AsyncFakeTransport transport = new AsyncFakeTransport(scheduler, 10L);
        LlmGateway gateway = gateway(transport);

        assertThatThrownBy(() -> gateway.oneAsync(LlmCall.profile("other", "main", MESSAGES)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("业务 id 未登记");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void thousandsOfCallsDoNotNeedThousandsOfThreads() throws Exception {
        int calls = 2000;
        AsyncFakeTransport transport = new AsyncFakeTransport(scheduler, 20L);
        for (int i = 0; i < calls; i++) {
            transport.enqueue(completion("ok", LlmStopReason.STOP, null));
        }
        LlmGateway gateway = gateway(transport);

        List<CompletableFuture<LlmResult>> futures = new ArrayList<CompletableFuture<LlmResult>>();
        for (int i = 0; i < calls; i++) {
            futures.add(gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES)));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]))
                .get(30, TimeUnit.SECONDS);

        assertThat(transport.requests).hasSize(calls);
        assertThat(transport.completed.get()).isEqualTo(calls);
        assertThat(Thread.activeCount()).isLessThan(200);
    }

    @Test
    public void toolLoopAsyncFeedsResultBack() throws Exception {
        AsyncFakeTransport transport = new AsyncFakeTransport(scheduler, 5L)
                .enqueue(completion("", LlmStopReason.TOOL_USE,
                        Collections.singletonList(new LlmToolCall("c1", "echo", "{\"q\":\"x\"}"))))
                .enqueue(completion("done", LlmStopReason.STOP, null));
        LlmGateway gateway = gateway(transport);
        final int[] executions = new int[1];

        LlmResult result = gateway.toolLoopAsync(LlmCall.profile("biz", "main", MESSAGES),
                Collections.singletonList(tool(executions)), Integer.valueOf(4))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(result.getRounds()).isEqualTo(2);
        assertThat(executions[0]).isEqualTo(1);
        List<LlmMessage> second = transport.requests.get(1).getMessages();
        LlmMessage toolResult = second.get(second.size() - 1);
        assertThat(toolResult.getRole()).isEqualTo(LlmRole.TOOL_RESULT);
        assertThat(toolResult.getText()).isEqualTo("ok:x");
    }

    @Test
    public void toolLoopAsyncStopsAtMaxRounds() throws Exception {
        AsyncFakeTransport transport = new AsyncFakeTransport(scheduler, 5L)
                .enqueue(completion("", LlmStopReason.TOOL_USE,
                        Collections.singletonList(new LlmToolCall("c1", "echo", "{}"))))
                .enqueue(completion("", LlmStopReason.TOOL_USE,
                        Collections.singletonList(new LlmToolCall("c2", "echo", "{}"))));
        LlmGateway gateway = gateway(transport);

        LlmResult result = gateway.toolLoopAsync(LlmCall.profile("biz", "main", MESSAGES),
                Collections.singletonList(tool(new int[1])), Integer.valueOf(2))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.MAX_ROUNDS);
        assertThat(result.getRounds()).isEqualTo(2);
    }

    @Test
    public void roundAsyncDoesNotExecuteTools() throws Exception {
        AsyncFakeTransport transport = new AsyncFakeTransport(scheduler, 5L)
                .enqueue(completion("", LlmStopReason.TOOL_USE,
                        Collections.singletonList(new LlmToolCall("c1", "echo", "{}"))));
        LlmGateway gateway = gateway(transport);
        final int[] executions = new int[1];

        LlmResult result = gateway.roundAsync(LlmCall.profile("biz", "main", MESSAGES),
                Collections.singletonList(tool(executions))).get(5, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(executions[0]).isZero();
    }

    @Test
    public void transportFailureCompletesExceptionally() {
        AsyncFakeTransport transport = new AsyncFakeTransport(scheduler, 5L)
                .failNext(new LlmCallException("boom"));
        LlmGateway gateway = gateway(transport);

        CompletableFuture<LlmResult> future = gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES));

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("boom");
    }

    @Test
    public void cancelledBeforeStartReturnsAborted() throws Exception {
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();
        AsyncFakeTransport transport = new AsyncFakeTransport(scheduler, 5L);
        LlmGateway gateway = gateway(transport);

        LlmResult result = gateway.oneByStreamAsync(LlmCall.profile("biz", "main", MESSAGES),
                null, cancel).get(5, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void cancellingFutureWithoutExplicitHandleAbortsTransport() {
        final AtomicBoolean aborted = new AtomicBoolean();
        LlmTransport transport = new LlmTransport() {
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
                request.getCancel().bindAbort(new Runnable() {
                    @Override
                    public void run() {
                        aborted.set(true);
                    }
                });
                return new CompletableFuture<LlmCompletion>();
            }
        };
        LlmGateway gateway = gateway(transport);

        CompletableFuture<LlmResult> future =
                gateway.oneAsync(LlmCall.profile("biz", "main", MESSAGES));
        future.cancel(true);

        assertThat(aborted).isTrue();
    }

    @Test
    public void cancellingReturnedFutureCancelsHandle() {
        LlmCancelHandle cancel = new LlmCancelHandle();
        AsyncFakeTransport transport = new AsyncFakeTransport(scheduler, 60_000L);
        LlmGateway gateway = gateway(transport);

        CompletableFuture<LlmResult> future = gateway.oneByStreamAsync(
                LlmCall.profile("biz", "main", MESSAGES), null, cancel);
        future.cancel(true);

        assertThat(cancel.isCancelled()).isTrue();
    }

    @Test
    public void streamingDeltasArriveBeforeCompletion() throws Exception {
        final List<String> deltas = Collections.synchronizedList(new ArrayList<String>());
        AsyncFakeTransport transport = new AsyncFakeTransport(scheduler, 5L)
                .enqueue(completion("hello", LlmStopReason.STOP, null));
        LlmGateway gateway = gateway(transport);

        LlmResult result = gateway.oneByStreamAsync(LlmCall.profile("biz", "main", MESSAGES),
                new java.util.function.Consumer<String>() {
                    @Override
                    public void accept(String text) {
                        deltas.add(text);
                    }
                }, null).get(5, TimeUnit.SECONDS);

        assertThat(result.getContent()).isEqualTo("hello");
        assertThat(deltas).containsExactly("hello");
    }

    private static LlmGateway gateway(LlmTransport transport) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmCompletion completion(String content, LlmStopReason stop, List<LlmToolCall> calls) {
        return new LlmCompletion(content, null, stop, LlmUsage.of(3, 2, 5), calls);
    }

    private static LlmTool tool(final int[] executions) {
        return new LlmTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "echo";
            }

            @Override
            public java.util.Map<String, Object> parameters() {
                return Collections.<String, Object>singletonMap("type", "object");
            }

            @Override
            public String execute(java.util.Map<String, Object> arguments) {
                executions[0]++;
                return "ok:" + arguments.get("q");
            }
        };
    }

    /**
     * 用调度器模拟异步 I/O，不占调用线程。
     */
    static final class AsyncFakeTransport implements LlmTransport {

        private final ScheduledExecutorService scheduler;
        private final long delayMs;
        private final LinkedList<LlmCompletion> responses = new LinkedList<LlmCompletion>();
        private final LinkedList<RuntimeException> failures = new LinkedList<RuntimeException>();
        final List<LlmRequest> requests = Collections.synchronizedList(new ArrayList<LlmRequest>());
        final AtomicInteger completed = new AtomicInteger();

        AsyncFakeTransport(ScheduledExecutorService scheduler, long delayMs) {
            this.scheduler = scheduler;
            this.delayMs = delayMs;
        }

        AsyncFakeTransport enqueue(LlmCompletion completion) {
            responses.add(completion);
            return this;
        }

        AsyncFakeTransport failNext(RuntimeException failure) {
            failures.add(failure);
            return this;
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
        public CompletableFuture<LlmCompletion> completeAsync(final LlmRequest request) {
            requests.add(request);
            final CompletableFuture<LlmCompletion> future = new CompletableFuture<LlmCompletion>();
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    if (future.isDone()) {
                        return;
                    }
                    LlmCompletion completion;
                    RuntimeException failure;
                    synchronized (responses) {
                        failure = failures.isEmpty() ? null : failures.removeFirst();
                        completion = failure == null && !responses.isEmpty()
                                ? responses.removeFirst() : null;
                    }
                    if (failure != null) {
                        future.completeExceptionally(failure);
                        return;
                    }
                    if (completion == null) {
                        future.complete(new LlmCompletion("", null, LlmStopReason.STOP,
                                LlmUsage.zero(), null));
                        return;
                    }
                    if (request.getOnDelta() != null && !completion.getContent().isEmpty()) {
                        request.getOnDelta().accept(completion.getContent());
                    }
                    completed.incrementAndGet();
                    future.complete(completion);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
            return future;
        }
    }
}
