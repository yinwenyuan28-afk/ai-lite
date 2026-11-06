package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 终态之后的收尾（F-04 / F-10）。
 *
 * <p>F-04：result 已经完成，之后到达的 delta 还在往用户的回调里冒。
 * 屏障的职责只是把<b>终态之前</b>已入队的 delta 冲刷完，终态之后的不该再进。</p>
 *
 * <p>F-10：{@code scheduleRetry} 的 {@code schedule()} 调用落在 {@code synchronized}
 * 之外，取消可能正好落在「已排定、尚未登记」的窗口里，于是撤的是空引用，
 * 定时任务留在延迟队列里继续跑。</p>
 */
public class TerminalTailRegressionTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));
    private static final long TIMEOUT_SECONDS = 5L;

    private ExecutorService callbackPool;
    private ScheduledExecutorService scheduler;

    @After
    public void tearDown() {
        if (callbackPool != null) {
            callbackPool.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private LlmGateway gateway(LlmTransport transport) {
        scheduler = Executors.newScheduledThreadPool(2);
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor(), scheduler);
    }

    private static LlmCompletion completion(String content, LlmStopReason stop) {
        return new LlmCompletion(content, null, stop, LlmUsage.zero(), null);
    }

    // ------------------------------------------------------------------ F-04

    /**
     * 回调队列溢出导致 OVERLOAD 收敛后，排队中的 delta 不能再冒出来。
     *
     * <p>这条打的是 F-04 的真正缺口：{@code failOverload()} 直接
     * {@code result.completeExceptionally}，<b>不经过完成屏障、也不 close</b>。
     * 于是 result 已经异常完成，队列里那些还没执行的 delta 任务仍会被 worker
     * 取出来跑，用户在一个「已经失败」的调用上继续收到增量。</p>
     *
     * <p>构造：容量 2 的回调队列，第一个 delta 占住 worker（用 latch 卡死），
     * 再推两个把队列塞满，第四个触发 {@code failOverload()}。</p>
     */
    @Test
    public void overloadDropsAlreadyQueuedDeltas() throws Exception {
        callbackPool = Executors.newFixedThreadPool(2);
        final AtomicInteger deltas = new AtomicInteger();
        final CountDownLatch firstCallbackEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstCallback = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> deltaSink =
                new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>>();
        final CompletableFuture<LlmCompletion> manual = new CompletableFuture<LlmCompletion>();

        LlmTransport controllable = new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                throw new UnsupportedOperationException("async 用例不应走同步入口");
            }

            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                deltaSink.set(request.getOnDelta());
                return manual;
            }
        };
        LlmGateway gateway = gateway(controllable);
        gateway.setCallbackPolicy(CallbackPolicy.io(callbackPool, 2));

        CompletableFuture<LlmResult> future = gateway.oneByStreamAsync(
                LlmCall.profile("biz", "main", MESSAGES),
                new java.util.function.Consumer<String>() {
                    @Override
                    public void accept(String text) {
                        deltas.incrementAndGet();
                        try {
                            firstCallbackEntered.countDown();
                            // 卡住 worker，让后面的 delta 只能排队。
                            releaseFirstCallback.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }, null);
        try {
            deltaSink.get().accept("running");
            assertThat(firstCallbackEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            // 队列容量 2：这两个能进队，第三个触发溢出 → failOverload。
            deltaSink.get().accept("queued-1");
            deltaSink.get().accept("queued-2");
            deltaSink.get().accept("overflow");

            Throwable cause = null;
            try {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException ex) {
                cause = ex.getCause();
            }
            assertThat(cause).isInstanceOf(LlmCallException.class);
            assertThat(((LlmCallException) cause).getKind())
                    .isEqualTo(LlmCallException.Kind.OVERLOAD);
            assertThat(deltas.get()).as("溢出发生时只应有 1 个 delta 真正回调").isEqualTo(1);

            // 放行 worker：队列里那两个 delta 若没被清掉，就会在终态之后跑出来。
            releaseFirstCallback.countDown();
            Thread.sleep(500L);
            assertThat(deltas.get())
                    .as("OVERLOAD 终态之后，排队中的 delta 不应再回调给用户")
                    .isEqualTo(1);
        } finally {
            // 断言失败时也要放行，否则回调池线程会卡到用例超时。
            releaseFirstCallback.countDown();
        }
    }

    /**
     * 正常终态之前已入队的 delta 必须照旧送达——修复不能把合法的补偿掉。
     */
    @Test
    public void deltasQueuedBeforeTerminalStillArrive() throws Exception {
        callbackPool = Executors.newFixedThreadPool(2);
        final AtomicInteger deltas = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> deltaSink =
                new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>>();
        final CompletableFuture<LlmCompletion> manual = new CompletableFuture<LlmCompletion>();

        LlmTransport controllable = new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                throw new UnsupportedOperationException("async 用例不应走同步入口");
            }

            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                deltaSink.set(request.getOnDelta());
                return manual;
            }
        };
        LlmGateway gateway = gateway(controllable);
        gateway.setCallbackPolicy(CallbackPolicy.io(callbackPool, 64));

        CompletableFuture<LlmResult> future = gateway.oneByStreamAsync(
                LlmCall.profile("biz", "main", MESSAGES),
                new java.util.function.Consumer<String>() {
                    @Override
                    public void accept(String text) {
                        deltas.incrementAndGet();
                    }
                }, null);

        for (int i = 0; i < 3; i++) {
            deltaSink.get().accept("d" + i);
        }
        manual.complete(completion("ok", LlmStopReason.STOP));
        future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(deltas.get())
                .as("完成屏障应先把已入队的 delta 冲刷完再完成 result")
                .isEqualTo(3);
    }

    /**
     * 取消终态确定之后到达的 delta 同样要丢弃。
     *
     * <p>取消路径（{@code cancelledByListener}）走的是 {@code completeBarrier}，
     * 屏障会在完成 result 前关闭；关闭后到达的 delta 不能进队列。</p>
     */
    @Test
    public void deltaArrivingAfterCancelIsDropped() throws Exception {
        callbackPool = Executors.newFixedThreadPool(2);
        final AtomicInteger deltas = new AtomicInteger();
        final CompletableFuture<LlmCompletion> manual = new CompletableFuture<LlmCompletion>();
        final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>> deltaSink =
                new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<String>>();
        final LlmCancelHandle cancel = new LlmCancelHandle();

        LlmTransport controllable = new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                throw new UnsupportedOperationException("async 用例不应走同步入口");
            }

            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                deltaSink.set(request.getOnDelta());
                return manual;
            }
        };
        LlmGateway gateway = gateway(controllable);
        gateway.setCallbackPolicy(CallbackPolicy.io(callbackPool, 64));

        CompletableFuture<LlmResult> future = gateway.oneByStreamAsync(
                LlmCall.profile("biz", "main", MESSAGES),
                new java.util.function.Consumer<String>() {
                    @Override
                    public void accept(String text) {
                        deltas.incrementAndGet();
                    }
                }, cancel);

        deltaSink.get().accept("before");
        cancel.cancel();

        LlmResult result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        int afterCancel = deltas.get();

        for (int i = 0; i < 5; i++) {
            deltaSink.get().accept("after" + i);
        }
        Thread.sleep(300L);
        assertThat(deltas.get())
                .as("取消终态之后到达的 delta 不应再回调给用户")
                .isEqualTo(afterCancel);
    }

    // ------------------------------------------------------------------ F-10

    /**
     * 取消之后，那一代退避任务必须从延迟队列撤掉，不能滞留。
     *
     * <p>退避设成 60s，让任务在取消的那一刻确实还躺在延迟队列里——
     * 若撤销漏了，这条断言会直接看到非空队列。</p>
     *
     * <p>注：{@code scheduleRetry} 的 {@code schedule()} 仍在锁外执行，但返回后
     * 立刻在锁内复查 {@code terminal / cancelRequested}，漏掉的话
     * {@code claimTerminalLocked} 还会再撤一次，两条路都覆盖了这个竞态。
     * 本用例是这条行为的守护，不是缺陷复现。</p>
     */
    @Test
    public void cancelRemovesStaleScheduledTask() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();

        LlmTransport alwaysFail = new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                attempts.incrementAndGet();
                throw new LlmCallException("transient", new java.io.IOException("io"),
                        LlmCallException.Kind.TRANSPORT);
            }

            @Override
            public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
                return CompletableFuture.supplyAsync(() -> complete(request));
            }
        };

        final ScheduledThreadPoolExecutor racy = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "racy-retry-1");
            t.setDaemon(true);
            return t;
        });
        racy.setRemoveOnCancelPolicy(true);

        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(alwaysFail), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor(), racy);

        final LlmCancelHandle cancel = new LlmCancelHandle();
        CompletableFuture<LlmResult> future = gateway.oneWithRetryAsync(
                LlmCall.profile("biz", "main", MESSAGES),
                RetryPolicy.builder()
                        // 60s 退避：任务留在延迟队列里，滞留可观测。
                        .maxAttempts(10).initialBackoffMs(60000L).maxBackoffMs(60000L)
                        .jitter(false).build(),
                cancel);

        // 等第一次尝试失败并排定退避：此刻延迟队列里有 1 个任务。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (racy.getQueue().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(racy.getQueue()).as("退避任务应已排定").hasSize(1);

        cancel.cancel();
        LlmResult result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);

        int attemptsAtCancel = attempts.get();
        assertThat(racy.getQueue())
                .as("取消后延迟队列应已清空（滞留任务说明撤销漏了这一代）")
                .isEmpty();
        Thread.sleep(200L);
        assertThat(attempts.get())
                .as("取消之后不应再发起新的尝试")
                .isEqualTo(attemptsAtCancel);
    }
}
