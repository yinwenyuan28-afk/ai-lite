package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;

/**
 * 单次模型调用入口的取消契约：L1（在途 HTTP 中止）与 L2（取消后不再推进）。
 */
public class LlmSingleCallCancellationTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    private final ExecutorService caller = Executors.newCachedThreadPool();

    @After
    public void tearDown() {
        caller.shutdownNow();
    }

    @Test
    public void syncOneReturnsAbortedWhenCancelledBeforeStart() {
        CancellableTransport transport = new CancellableTransport();
        LlmCancelHandle handle = new LlmCancelHandle();
        handle.cancel();

        LlmResult result = gateway(transport).one(call(), handle);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.requests).isEmpty();
        assertThat(transport.aborts.get()).isZero();
    }

    @Test
    public void asyncOneReturnsAbortedWhenCancelledBeforeStart() throws Exception {
        CancellableTransport transport = new CancellableTransport();
        LlmCancelHandle handle = new LlmCancelHandle();
        handle.cancel();

        LlmResult result = gateway(transport).oneAsync(call(), handle)
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void syncOneAbortsInFlightRequest() throws Exception {
        final CancellableTransport transport = new CancellableTransport();
        final LlmCancelHandle handle = new LlmCancelHandle();
        final LlmGateway gateway = gateway(transport);

        Future<LlmResult> future = caller.submit(() -> gateway.one(call(), handle));
        assertThat(transport.awaitRequest()).isTrue();

        handle.cancel();
        LlmResult result = future.get(5, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.aborts.get()).isEqualTo(1);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void asyncOneAbortsInFlightRequest() throws Exception {
        CancellableTransport transport = new CancellableTransport();
        LlmCancelHandle handle = new LlmCancelHandle();

        CompletableFuture<LlmResult> future = gateway(transport).oneAsync(call(), handle);
        assertThat(transport.awaitRequest()).isTrue();

        handle.cancel();
        LlmResult result = future.get(5, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(handle.isCancelled()).isTrue();
        assertThat(transport.aborts.get()).isEqualTo(1);
    }

    @Test
    public void streamingEntryAbortsInFlightRequest() throws Exception {
        final CancellableTransport transport = new CancellableTransport();
        final LlmCancelHandle handle = new LlmCancelHandle();
        final LlmGateway gateway = gateway(transport);

        Future<LlmResult> future = caller.submit(
                () -> gateway.oneByStream(call(), null, handle));
        assertThat(transport.awaitRequest()).isTrue();

        handle.cancel();

        assertThat(future.get(5, TimeUnit.SECONDS).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.aborts.get()).isEqualTo(1);
    }

    @Test
    public void cancelBeforeBindAbortsImmediately() {
        LlmCancelHandle handle = new LlmCancelHandle();
        final AtomicInteger aborts = new AtomicInteger();

        handle.cancel();
        handle.bindAbort(new Runnable() {
            @Override
            public void run() {
                aborts.incrementAndGet();
            }
        });

        assertThat(aborts.get()).isEqualTo(1);
    }

    @Test
    public void repeatedCancelRunsAbortOnlyOnce() {
        LlmCancelHandle handle = new LlmCancelHandle();
        final AtomicInteger aborts = new AtomicInteger();
        handle.bindAbort(new Runnable() {
            @Override
            public void run() {
                aborts.incrementAndGet();
            }
        });

        handle.cancel();
        handle.cancel();
        handle.cancel();

        assertThat(aborts.get()).isEqualTo(1);
    }

    @Test
    public void bindingAgainAfterCancelStillRunsAbortOnce() {
        LlmCancelHandle handle = new LlmCancelHandle();
        final AtomicInteger aborts = new AtomicInteger();

        handle.bindAbort(new Runnable() {
            @Override
            public void run() {
                aborts.incrementAndGet();
            }
        });
        handle.cancel();
        handle.bindAbort(null);
        handle.bindAbort(new Runnable() {
            @Override
            public void run() {
                aborts.incrementAndGet();
            }
        });

        assertThat(aborts.get()).isEqualTo(1);
    }

    @Test
    public void futureCancelMarksFutureCancelledAndAbortsTransport() throws Exception {
        CancellableTransport transport = new CancellableTransport();
        LlmGateway gateway = gateway(transport);

        CompletableFuture<LlmResult> future = gateway.oneAsync(call());
        assertThat(transport.awaitRequest()).isTrue();

        future.cancel(true);

        assertThat(future.isCancelled()).isTrue();
        assertThat(transport.aborts.get()).isEqualTo(1);
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    public void cancelAfterCompletionDoesNotChangeResultOrSendAnotherRequest() throws Exception {
        CancellableTransport transport = new CancellableTransport();
        transport.release();
        LlmCancelHandle handle = new LlmCancelHandle();
        LlmGateway gateway = gateway(transport);

        LlmResult result = gateway.one(call(), handle);
        handle.cancel();
        Thread.sleep(100L);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.STOP);
        assertThat(transport.requests).hasSize(1);
        assertThat(transport.aborts.get()).isEqualTo(1);
    }

    @Test
    public void cancelDoesNotRetryTheRequest() throws Exception {
        CancellableTransport transport = new CancellableTransport();
        LlmCancelHandle handle = new LlmCancelHandle();

        CompletableFuture<LlmResult> future = gateway(transport).oneAsync(call(), handle);
        assertThat(transport.awaitRequest()).isTrue();
        handle.cancel();
        future.get(5, TimeUnit.SECONDS);
        Thread.sleep(150L);

        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void cancellingOneCallDoesNotAffectAnother() throws Exception {
        CancellableTransport transport = new CancellableTransport();
        LlmGateway gateway = gateway(transport);
        LlmCancelHandle first = new LlmCancelHandle();
        LlmCancelHandle second = new LlmCancelHandle();

        CompletableFuture<LlmResult> firstFuture = gateway.oneAsync(call(), first);
        CompletableFuture<LlmResult> secondFuture = gateway.oneAsync(call(), second);
        assertThat(transport.awaitRequests(2)).isTrue();

        first.cancel();
        transport.release();

        assertThat(firstFuture.get(5, TimeUnit.SECONDS).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED);
        assertThat(secondFuture.get(5, TimeUnit.SECONDS).getStopReason())
                .isEqualTo(LlmStopReason.STOP);
        assertThat(transport.aborts.get()).isEqualTo(1);
    }

    @Test
    public void syncOneReturnsAbortedEvenWhenTransportIgnoresAbort() throws Exception {
        final CountDownLatch seen = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        LlmTransport transport = new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                seen.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new LlmCompletion("done", null, LlmStopReason.STOP,
                        LlmUsage.zero(), null);
            }
        };
        final LlmCancelHandle handle = new LlmCancelHandle();

        Future<LlmResult> future = caller.submit(() -> gateway(transport).one(call(), handle));
        assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
        handle.cancel();
        release.countDown();

        assertThat(future.get(5, TimeUnit.SECONDS).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED);
    }

    @Test
    public void asyncOneReturnsAbortedEvenWhenTransportIgnoresAbort() throws Exception {
        final CountDownLatch seen = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
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
                seen.countDown();
                final CompletableFuture<LlmCompletion> future =
                        new CompletableFuture<LlmCompletion>();
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        future.complete(new LlmCompletion("done", null, LlmStopReason.STOP,
                                LlmUsage.zero(), null));
                    }
                }, "non-cooperative-transport");
                worker.setDaemon(true);
                worker.start();
                return future;
            }
        };
        LlmCancelHandle handle = new LlmCancelHandle();

        CompletableFuture<LlmResult> future = gateway(transport).oneAsync(call(), handle);
        assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
        handle.cancel();
        release.countDown();

        assertThat(future.get(5, TimeUnit.SECONDS).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED);
    }

    @Test
    public void futureCancelWritesAbortedCallRecord() throws Exception {
        CancellableTransport transport = new CancellableTransport();
        RecordingStore store = new RecordingStore();
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), store,
                new DirectExecutorProvider(), new SequentialToolExecutor());

        CompletableFuture<LlmResult> future = gateway.oneAsync(call());
        assertThat(transport.awaitRequest()).isTrue();
        future.cancel(true);

        long deadline = System.currentTimeMillis() + 2000L;
        while (store.records.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(store.records).hasSize(1);
        assertThat(store.records.get(0).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED.wire());
        assertThat(store.records.get(0).getSuccess()).isEqualTo(Integer.valueOf(0));
    }

    @Test
    public void syncAndAsyncCancellationProduceTheSameTerminalResult() throws Exception {
        CancellableTransport syncTransport = new CancellableTransport();
        LlmCancelHandle syncHandle = new LlmCancelHandle();
        syncHandle.cancel();
        LlmResult sync = gateway(syncTransport).one(call(), syncHandle);

        CancellableTransport asyncTransport = new CancellableTransport();
        LlmCancelHandle asyncHandle = new LlmCancelHandle();
        asyncHandle.cancel();
        LlmResult async = gateway(asyncTransport).oneAsync(call(), asyncHandle)
                .get(5, TimeUnit.SECONDS);

        assertThat(sync.getStopReason()).isEqualTo(async.getStopReason());
        assertThat(sync.getContent()).isEqualTo(async.getContent());
        assertThat(sync.getRounds()).isEqualTo(async.getRounds());
        assertThat(sync.getUsage().getTotalTokens())
                .isEqualTo(async.getUsage().getTotalTokens());
        assertThat(syncTransport.requests).isEmpty();
        assertThat(asyncTransport.requests).isEmpty();
    }

    private static LlmGateway gateway(LlmTransport transport) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmCall call() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    /**
     * 直接收集调用记录的存储，用于断言取消路径也会落一条 aborted。
     */
    private static final class RecordingStore implements LlmCallStore {
        final List<LlmCallRecord> records =
                Collections.synchronizedList(new ArrayList<LlmCallRecord>());

        @Override
        public void insert(LlmCallRecord record) {
            records.add(record);
        }
    }

    /**
     * 模拟真实传输：请求阻塞等待；取消时通过 bindAbort 打断等待并结束本轮。
     * 同步与异步路径共用同一套 abort 语义。
     */
    private static final class CancellableTransport implements LlmTransport {

        final List<LlmRequest> requests =
                Collections.synchronizedList(new ArrayList<LlmRequest>());
        final AtomicInteger aborts = new AtomicInteger();
        private final CountDownLatch requestSeen = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void release() {
            released.countDown();
        }

        boolean awaitRequest() throws InterruptedException {
            return requestSeen.await(5, TimeUnit.SECONDS);
        }

        boolean awaitRequests(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000L;
            while (requests.size() < count && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            return requests.size() >= count;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            enter(request);
            request.getCancel().bindAbort(new Runnable() {
                @Override
                public void run() {
                    aborts.incrementAndGet();
                    released.countDown();
                }
            });
            awaitRelease();
            if (request.isCancelled()) {
                throw new LlmCallException("调用已取消");
            }
            return stop();
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(final LlmRequest request) {
            enter(request);
            final CompletableFuture<LlmCompletion> future = new CompletableFuture<LlmCompletion>();
            request.getCancel().bindAbort(new Runnable() {
                @Override
                public void run() {
                    aborts.incrementAndGet();
                    future.completeExceptionally(new LlmCallException("调用已取消"));
                    released.countDown();
                }
            });
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    awaitRelease();
                    if (!future.isDone()) {
                        if (request.isCancelled()) {
                            future.completeExceptionally(new LlmCallException("调用已取消"));
                        } else {
                            future.complete(stop());
                        }
                    }
                }
            }, "cancel-test-transport");
            worker.setDaemon(true);
            worker.start();
            return future;
        }

        private void enter(LlmRequest request) {
            requests.add(request);
            requestSeen.countDown();
        }

        private void awaitRelease() {
            try {
                released.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private static LlmCompletion stop() {
            return new LlmCompletion("done", null, LlmStopReason.STOP, LlmUsage.zero(), null);
        }
    }
}
