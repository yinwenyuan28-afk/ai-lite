package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.guard.ProviderGuard;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关并发闸门接入测试（限流熔断计划 G1）：按轮获取/释放（D4）、同步有界等待、
 * 异步排队不阻塞提交线程、排队容量/超时、排队中取消退出、工具执行期间许可让出、
 * 不合作传输（future 永不完成）的许可回收。
 */
public class LlmGatewayGuardTest {

    private static final List<LlmMessage> MESSAGES = Collections.singletonList(LlmMessage.user("hi"));

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ExecutorService carrier = Executors.newCachedThreadPool();

    @After
    public void tearDown() {
        scheduler.shutdownNow();
        carrier.shutdownNow();
    }

    /**
     * 手动闸门传输：complete 阻塞到对应 gate 放行；completeAsync 返回待手动完成的
     * future。两种形态都记录请求数，用于断言"零出站"。
     */
    static final class GatedTransport implements LlmTransport {

        final List<LlmRequest> requests = Collections.synchronizedList(new ArrayList<LlmRequest>());
        final List<CountDownLatch> syncGates =
                Collections.synchronizedList(new ArrayList<CountDownLatch>());
        final List<CompletableFuture<LlmCompletion>> pending =
                Collections.synchronizedList(new ArrayList<CompletableFuture<LlmCompletion>>());
        final List<LlmCompletion> syncResponses =
                Collections.synchronizedList(new ArrayList<LlmCompletion>());

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            CountDownLatch gate = new CountDownLatch(1);
            syncGates.add(gate);
            try {
                gate.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new LlmCallException("测试闸门被中断", ex, LlmCallException.Kind.TRANSPORT);
            }
            return syncResponses.isEmpty()
                    ? new LlmCompletion("", null, LlmStopReason.STOP, LlmUsage.zero(), null)
                    : syncResponses.remove(0);
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            requests.add(request);
            CompletableFuture<LlmCompletion> future = new CompletableFuture<LlmCompletion>();
            pending.add(future);
            return future;
        }

        /** 手动完成最早的一个异步 future。 */
        void completeFirst(LlmCompletion completion) {
            nextPending().complete(completion);
        }

        void failFirst(Throwable error) {
            nextPending().completeExceptionally(error);
        }

        private CompletableFuture<LlmCompletion> nextPending() {
            synchronized (pending) {
                return pending.remove(0);
            }
        }
    }

    private ProviderGuard guard(int concurrency, long waitMs, int queueCapacity) {
        return new ProviderGuard("test", concurrency, waitMs, queueCapacity, null,
                System::currentTimeMillis,
                (task, delayMs) -> scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS),
                null);
    }

    private LlmGateway gateway(LlmTransport transport, ProviderGuard guard) {
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor(), scheduler);
        gateway.setGuards(Collections.singletonMap("test", guard));
        return gateway;
    }

    private static LlmCall call() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    private static LlmCompletion completion(String content, LlmStopReason stop) {
        return completion(content, stop, null);
    }

    private static LlmCompletion completion(String content, LlmStopReason stop, List<LlmToolCall> calls) {
        return new LlmCompletion(content, null, stop, LlmUsage.of(3, 2, 5), calls);
    }

    @Test
    public void syncThirdCallerWaitsThenSucceeds() throws Exception {
        GatedTransport transport = new GatedTransport();
        LlmGateway gateway = gateway(transport, guard(2, 5000, 10));

        final AtomicReference<LlmResult> first = new AtomicReference<LlmResult>();
        final AtomicReference<LlmResult> second = new AtomicReference<LlmResult>();
        final AtomicReference<LlmResult> third = new AtomicReference<LlmResult>();
        final CountDownLatch thirdDone = new CountDownLatch(1);
        carrier.submit(() -> first.set(gateway.one(call())));
        carrier.submit(() -> second.set(gateway.one(call())));
        carrier.submit(() -> {
            third.set(gateway.one(call()));
            thirdDone.countDown();
        });
        awaitRequests(transport, 2);
        // 并发=2 已占满：第三个调用在闸门上等待，不产生请求。
        Thread.sleep(150L);
        assertThat(transport.requests).hasSize(2);
        assertThat(thirdDone.getCount()).isEqualTo(1L);

        transport.syncGates.get(0).countDown();
        // 第一个调用归还许可，第三个进入传输层。
        awaitRequests(transport, 3);
        transport.syncGates.get(1).countDown();
        transport.syncGates.get(2).countDown();
        assertThat(thirdDone.await(5L, TimeUnit.SECONDS)).isTrue();
        assertThat(third.get().getStopReason()).isEqualTo(LlmStopReason.STOP);
        assertThat(first.get().getStopReason()).isEqualTo(LlmStopReason.STOP);
    }

    @Test
    public void syncWaitTimeoutRejectsWithOverloadWithoutRequest() throws Exception {
        GatedTransport transport = new GatedTransport();
        LlmGateway gateway = gateway(transport, guard(1, 120, 10));
        carrier.submit(() -> {
            try {
                gateway.one(call());
            } catch (RuntimeException ignored) {
                // 测试只关心主线程上的第二个调用。
            }
        });
        awaitRequests(transport, 1);

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> gateway.one(call()))
                .isInstanceOfSatisfying(LlmCallException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.OVERLOAD);
                    assertThat(ex.getMessage()).contains("并发等待超时");
                });
        assertThat(System.currentTimeMillis() - started).isBetween(80L, 5000L);
        assertThat(transport.requests).hasSize(1);
        transport.syncGates.get(0).countDown();
    }

    @Test
    public void asyncQueueingDoesNotBlockSubmitThread() throws Exception {
        GatedTransport transport = new GatedTransport();
        LlmGateway gateway = gateway(transport, guard(1, 5000, 10));

        CompletableFuture<LlmResult> first = gateway.oneAsync(call());
        awaitRequests(transport, 1);
        long submitStarted = System.currentTimeMillis();
        CompletableFuture<LlmResult> second = gateway.oneAsync(call());
        long submitMs = System.currentTimeMillis() - submitStarted;
        // 提交线程不被阻塞：第二个调用只是排队，还没有传输请求。
        assertThat(submitMs).isLessThan(1000L);
        assertThat(transport.requests).hasSize(1);

        transport.completeFirst(completion("first", LlmStopReason.STOP));
        assertThat(first.get(5L, TimeUnit.SECONDS).getContent()).isEqualTo("first");
        // 许可交接后第二个调用的请求在载体线程上发出。
        awaitRequests(transport, 2);
        transport.completeFirst(completion("second", LlmStopReason.STOP));
        assertThat(second.get(5L, TimeUnit.SECONDS).getContent()).isEqualTo("second");
    }

    @Test
    public void asyncQueueFullCompletesWithOverload() throws Exception {
        GatedTransport transport = new GatedTransport();
        LlmGateway gateway = gateway(transport, guard(1, 5000, 1));

        CompletableFuture<LlmResult> first = gateway.oneAsync(call());
        awaitRequests(transport, 1);
        gateway.oneAsync(call());
        CompletableFuture<LlmResult> third = gateway.oneAsync(call());

        Throwable cause = assertFailure(third);
        assertThat(cause).isInstanceOfSatisfying(LlmCallException.class, ex ->
                assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.OVERLOAD));
        assertThat(transport.requests).hasSize(1);
        transport.completeFirst(completion("first", LlmStopReason.STOP));
        assertThat(first.get(5L, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    public void asyncQueueTimeoutCompletesWithOverload() throws Exception {
        GatedTransport transport = new GatedTransport();
        LlmGateway gateway = gateway(transport, guard(1, 150, 10));

        CompletableFuture<LlmResult> first = gateway.oneAsync(call());
        awaitRequests(transport, 1);
        CompletableFuture<LlmResult> second = gateway.oneAsync(call());

        Throwable cause = assertFailure(second);
        assertThat(cause).isInstanceOfSatisfying(LlmCallException.class, ex -> {
            assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.OVERLOAD);
            assertThat(ex.getMessage()).contains("并发排队超时");
        });
        // 排队超时没有发出过请求。
        assertThat(transport.requests).hasSize(1);
        transport.completeFirst(completion("first", LlmStopReason.STOP));
        assertThat(first.get(5L, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    public void cancelledQueuedCallSkipsHandoverAndReturnsAborted() throws Exception {
        GatedTransport transport = new GatedTransport();
        LlmGateway gateway = gateway(transport, guard(1, 10_000, 10));

        CompletableFuture<LlmResult> first = gateway.oneAsync(call());
        awaitRequests(transport, 1);
        LlmCancelHandle cancel = new LlmCancelHandle();
        CompletableFuture<LlmResult> second = gateway.oneAsync(call(), cancel);
        Thread.sleep(100L);
        assertThat(transport.requests).hasSize(1);

        cancel.cancel();
        assertThat(second.get(5L, TimeUnit.SECONDS).getStopReason()).isEqualTo(LlmStopReason.ABORTED);

        // 释放第一个调用的许可：死 waiter 被跳过，不再发出第二个调用的请求。
        transport.completeFirst(completion("first", LlmStopReason.STOP));
        assertThat(first.get(5L, TimeUnit.SECONDS)).isNotNull();
        Thread.sleep(150L);
        assertThat(transport.requests).hasSize(1);

        // 许可已归还：新调用快速拿到。
        CompletableFuture<LlmResult> third = gateway.oneAsync(call());
        awaitRequests(transport, 2);
        transport.completeFirst(completion("third", LlmStopReason.STOP));
        assertThat(third.get(5L, TimeUnit.SECONDS).getContent()).isEqualTo("third");
    }

    /**
     * D4 粒度：许可按单次模型调用计，工具执行期间不占许可——concurrency=1 的
     * toolLoop 在工具阻塞时，第二个调用者可以立即进入。
     */
    @Test
    public void permitIsReleasedWhileToolsExecute() throws Exception {
        GatedTransport transport = new GatedTransport();
        final CountDownLatch toolStarted = new CountDownLatch(1);
        final CountDownLatch toolRelease = new CountDownLatch(1);
        ToolExecutor blockingTool = new ToolExecutor() {
            @Override
            public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
                toolStarted.countDown();
                try {
                    toolRelease.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                List<String> results = new ArrayList<String>();
                for (LlmToolCall ignored : calls) {
                    results.add("tool-ok");
                }
                return results;
            }
        };
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), blockingTool, scheduler);
        gateway.setGuards(Collections.singletonMap("test", guard(1, 1000, 10)));

        CompletableFuture<LlmResult> loop = gateway.toolLoopAsync(call(),
                Collections.singletonList(tool()), 4);
        awaitRequests(transport, 1);
        transport.completeFirst(completion("", LlmStopReason.TOOL_USE,
                Collections.singletonList(new LlmToolCall("c1", "echo", "{\"q\":\"x\"}"))));
        assertThat(toolStarted.await(5L, TimeUnit.SECONDS)).isTrue();

        // 工具阻塞中（无模型请求在途）：第二个调用者应能立刻拿到许可发出请求。
        CompletableFuture<LlmResult> second = gateway.oneAsync(call());
        awaitRequests(transport, 2);
        transport.completeFirst(completion("second", LlmStopReason.STOP));
        assertThat(second.get(5L, TimeUnit.SECONDS).getContent()).isEqualTo("second");

        toolRelease.countDown();
        // 工具完成后循环第 2 轮重新走一轮获取→发出第 3 个请求。
        awaitRequests(transport, 3);
        transport.completeFirst(completion("done", LlmStopReason.STOP));
        assertThat(loop.get(5L, TimeUnit.SECONDS).getContent()).isEqualTo("done");
    }

    /**
     * 不合作传输兜底（AL-H05 场景）：取消收敛了 result，但传输 future 永不完成，
     * 许可必须由收敛路径归还，后续调用不被永久卡死。
     */
    @Test
    public void nonCooperativeTransportCancellationReturnsPermit() throws Exception {
        GatedTransport transport = new GatedTransport();
        LlmGateway gateway = gateway(transport, guard(1, 5000, 10));

        LlmCancelHandle cancel = new LlmCancelHandle();
        CompletableFuture<LlmResult> first = gateway.oneAsync(call(), cancel);
        awaitRequests(transport, 1);
        cancel.cancel();
        assertThat(first.get(5L, TimeUnit.SECONDS).getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        // pending future 永不完成（模拟不合作传输）。

        CompletableFuture<LlmResult> second = gateway.oneAsync(call());
        awaitRequests(transport, 2);
        // 死 future 仍占着 pending 队头：完成第二个调用自己的 future。
        synchronized (transport.pending) {
            transport.pending.get(1).complete(completion("second", LlmStopReason.STOP));
        }
        assertThat(second.get(5L, TimeUnit.SECONDS).getContent()).isEqualTo("second");
    }

    private static LlmTool tool() {
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
            public Map<String, Object> parameters() {
                return Collections.<String, Object>emptyMap();
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return "tool-ok";
            }
        };
    }

    private static void awaitRequests(GatedTransport transport, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (transport.requests.size() >= expected) {
                return;
            }
            Thread.sleep(20L);
        }
    }

    private static Throwable assertFailure(CompletableFuture<?> future) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(future.isDone()).isTrue();
        try {
            future.get();
            throw new AssertionError("应当异常完成");
        } catch (ExecutionException ex) {
            return ex.getCause();
        }
    }
}
