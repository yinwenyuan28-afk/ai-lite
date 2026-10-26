package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;

/**
 * 单次模型调用入口的 parity：同步 one 与异步 oneAsync 在结果、请求、校验、
 * 异常、日志和线程六个维度上必须一致。
 */
public class LlmSingleCallEntryParityTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    private final ExecutorService worker = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "parity-async-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    @After
    public void tearDown() {
        worker.shutdownNow();
    }

    @Test
    public void resultFieldsMatchBetweenSyncAndAsync() throws Exception {
        LlmCompletion completion = new LlmCompletion("answer", "thinking", "sig",
                LlmStopReason.LENGTH, LlmUsage.of(3, 2, 5), null);

        LlmResult sync = gateway(new ParityTransport().enqueue(completion))
                .one(call());
        LlmResult async = gateway(new ParityTransport().enqueue(completion))
                .oneAsync(call()).get(5, TimeUnit.SECONDS);

        assertThat(sync.getContent()).isEqualTo(async.getContent());
        assertThat(sync.getThinkingText()).isEqualTo(async.getThinkingText());
        assertThat(sync.getThinkingSignature()).isEqualTo(async.getThinkingSignature());
        assertThat(sync.getStopReason()).isEqualTo(async.getStopReason());
        assertThat(sync.getUsage().getInputTokens()).isEqualTo(async.getUsage().getInputTokens());
        assertThat(sync.getUsage().getOutputTokens()).isEqualTo(async.getUsage().getOutputTokens());
        assertThat(sync.getUsage().getTotalTokens()).isEqualTo(async.getUsage().getTotalTokens());
        assertThat(sync.getRounds()).isEqualTo(async.getRounds());
        assertThat(sync.getProvider()).isEqualTo(async.getProvider());
        assertThat(sync.getModelId()).isEqualTo(async.getModelId());
        assertThat(sync.getThinkingLevel()).isEqualTo(async.getThinkingLevel());
        assertThat(sync.getToolCalls()).isEqualTo(async.getToolCalls());
    }

    @Test
    public void requestShapeMatchesBetweenSyncAndAsync() throws Exception {
        ParityTransport syncTransport = new ParityTransport().enqueue(stop("ok"));
        ParityTransport asyncTransport = new ParityTransport().enqueue(stop("ok"));

        gateway(syncTransport).one(call());
        gateway(asyncTransport).oneAsync(call()).get(5, TimeUnit.SECONDS);

        assertRequestShape(syncTransport.requests.get(0), asyncTransport.requests.get(0));
        assertThat(syncTransport.requests).hasSize(1);
        assertThat(asyncTransport.requests).hasSize(1);
    }

    @Test
    public void explicitCancelHandleIsPassedThroughUnchanged() throws Exception {
        LlmCancelHandle handle = new LlmCancelHandle();
        ParityTransport syncTransport = new ParityTransport().enqueue(stop("ok"));
        ParityTransport asyncTransport = new ParityTransport().enqueue(stop("ok"));

        gateway(syncTransport).one(call(), handle);
        gateway(asyncTransport).oneAsync(call(), handle).get(5, TimeUnit.SECONDS);

        assertThat(syncTransport.requests.get(0).getCancel()).isSameAs(handle);
        assertThat(asyncTransport.requests.get(0).getCancel()).isSameAs(handle);
    }

    @Test
    public void nullCallIsRejectedBeforeRequestOnBothPaths() {
        ParityTransport transport = new ParityTransport();
        LlmGateway gateway = gateway(transport);

        assertThatThrownBy(() -> gateway.one(null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("调用不能为空");
        assertThatThrownBy(() -> gateway.oneAsync(null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("调用不能为空");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void blankBizIdIsRejectedBeforeRequestOnBothPaths() {
        ParityTransport transport = new ParityTransport();
        LlmGateway gateway = gateway(transport);

        assertThatThrownBy(() -> gateway.one(LlmCall.profile("  ", "main", MESSAGES)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("业务 id 不能为空");
        assertThatThrownBy(() -> gateway.oneAsync(LlmCall.profile("  ", "main", MESSAGES)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("业务 id 不能为空");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void unregisteredBizIdIsRejectedBeforeRequestOnBothPaths() {
        ParityTransport transport = new ParityTransport();
        LlmGateway gateway = gateway(transport);

        assertThatThrownBy(() -> gateway.one(LlmCall.profile("other", "main", MESSAGES)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("业务 id 未登记");
        assertThatThrownBy(() -> gateway.oneAsync(LlmCall.profile("other", "main", MESSAGES)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("业务 id 未登记");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void unknownModelIsRejectedBeforeRequestOnBothPaths() {
        ParityTransport transport = new ParityTransport();
        LlmGateway gateway = gateway(transport);
        LlmCall missingModel = LlmCall.explicit("biz", "test", "missing", null, MESSAGES);

        assertThatThrownBy(() -> gateway.one(missingModel))
                .isInstanceOf(LlmCallException.class);
        assertThatThrownBy(() -> gateway.oneAsync(missingModel))
                .isInstanceOf(LlmCallException.class);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void unsupportedApiIsRejectedBeforeRequestOnBothPaths() {
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.<LlmTransport>singletonList(new GhoseApiTransport()),
                new NoopLlmCallStore(), new DirectExecutorProvider(),
                new SequentialToolExecutor());

        assertThatThrownBy(() -> gateway.one(call()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("不支持的协议: fake");
        assertThatThrownBy(() -> gateway.oneAsync(call()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("不支持的协议: fake");
    }

    @Test
    public void llmCallExceptionKeepsTypeAndMessageOnBothPaths() throws Exception {
        LlmGateway syncGateway = gateway(new ParityTransport().failWith(
                new LlmCallException("boom")));
        LlmGateway asyncGateway = gateway(new ParityTransport().failWith(
                new LlmCallException("boom")));

        assertThatThrownBy(() -> syncGateway.one(call()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("boom");

        CompletableFuture<LlmResult> future = asyncGateway.oneAsync(call());
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("boom");
    }

    @Test
    public void runtimeExceptionIsWrappedTheSameWayOnBothPaths() throws Exception {
        LlmGateway syncGateway = gateway(new ParityTransport().failWith(
                new IllegalStateException("kaboom")));
        LlmGateway asyncGateway = gateway(new ParityTransport().failWith(
                new IllegalStateException("kaboom")));

        assertThatThrownBy(() -> syncGateway.one(call()))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型调用失败")
                .hasCauseInstanceOf(IllegalStateException.class);

        CompletableFuture<LlmResult> future = asyncGateway.oneAsync(call());
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型调用失败");
    }

    @Test
    public void nestedCompletionExceptionIsUnwrappedToRootCause() throws Exception {
        final CompletableFuture<LlmCompletion> failed = new CompletableFuture<LlmCompletion>();
        failed.completeExceptionally(new CompletionException(
                new CompletionException(new LlmCallException("deep-cause"))));
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
                return failed;
            }
        };

        CompletableFuture<LlmResult> future = gateway(transport).oneAsync(call());

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("deep-cause");
    }

    @Test
    public void bothPathsWriteOneEquivalentCallRecord() throws Exception {
        RecordingStore syncStore = new RecordingStore();
        RecordingStore asyncStore = new RecordingStore();

        gateway(new ParityTransport().enqueue(stop("ok")), syncStore).one(call());
        gateway(new ParityTransport().enqueue(stop("ok")), asyncStore)
                .oneAsync(call()).get(5, TimeUnit.SECONDS);

        assertThat(syncStore.records).hasSize(1);
        assertThat(asyncStore.records).hasSize(1);
        LlmCallRecord syncRow = syncStore.records.get(0);
        LlmCallRecord asyncRow = asyncStore.records.get(0);
        assertThat(syncRow.getStopReason()).isEqualTo(asyncRow.getStopReason());
        assertThat(syncRow.getSuccess()).isEqualTo(asyncRow.getSuccess());
        assertThat(syncRow.getRoundNo()).isEqualTo(asyncRow.getRoundNo());
        assertThat(syncRow.getTotalTokens()).isEqualTo(asyncRow.getTotalTokens());
        assertThat(syncRow.getBizId()).isEqualTo(asyncRow.getBizId());
        assertThat(syncRow.getProvider()).isEqualTo(asyncRow.getProvider());
        assertThat(syncRow.getModelId()).isEqualTo(asyncRow.getModelId());
        assertThat(syncRow.getApi()).isEqualTo(asyncRow.getApi());
    }

    @Test
    public void syncEntryRunsOnCallerThread() {
        ParityTransport transport = new ParityTransport().enqueue(stop("ok"));
        final Thread caller = Thread.currentThread();

        gateway(transport).one(call());

        assertThat(transport.completeThreads.get(0)).isSameAs(caller);
    }

    @Test
    public void asyncEntryReturnsBeforeTransportCompletes() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ParityTransport transport = new ParityTransport().enqueue(stop("ok"));
        transport.gate = release;

        CompletableFuture<LlmResult> future = gateway(transport).oneAsync(call());

        assertThat(future.isDone()).isFalse();
        release.countDown();
        LlmResult result = future.get(5, TimeUnit.SECONDS);
        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.completeThreads.get(0).getName()).startsWith("parity-async-");
    }

    private static void assertRequestShape(LlmRequest sync, LlmRequest async) {
        assertThat(sync.getMessages()).hasSameSizeAs(async.getMessages());
        for (int i = 0; i < sync.getMessages().size(); i++) {
            assertThat(sync.getMessages().get(i).getRole())
                    .isEqualTo(async.getMessages().get(i).getRole());
            assertThat(sync.getMessages().get(i).getText())
                    .isEqualTo(async.getMessages().get(i).getText());
            assertThat(sync.getMessages().get(i).getImages())
                    .hasSameSizeAs(async.getMessages().get(i).getImages());
            for (int j = 0; j < sync.getMessages().get(i).getImages().size(); j++) {
                LlmImage syncImage = sync.getMessages().get(i).getImages().get(j);
                LlmImage asyncImage = async.getMessages().get(i).getImages().get(j);
                assertThat(syncImage.getMime()).isEqualTo(asyncImage.getMime());
                assertThat(syncImage.getData()).isEqualTo(asyncImage.getData());
            }
        }
        assertThat(sync.isStream()).isEqualTo(async.isStream());
        assertThat(sync.getTools()).isNull();
        assertThat(async.getTools()).isNull();
        assertThat(sync.getOnDelta()).isNull();
        assertThat(async.getOnDelta()).isNull();
        assertThat(sync.effectiveConnectTimeoutMs())
                .isEqualTo(async.effectiveConnectTimeoutMs());
        assertThat(sync.effectiveReadTimeoutMs())
                .isEqualTo(async.effectiveReadTimeoutMs());
    }

    private LlmGateway gateway(LlmTransport transport) {
        return gateway(transport, new NoopLlmCallStore());
    }

    private LlmGateway gateway(LlmTransport transport, LlmCallStore store) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), store,
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmCall call() {
        return LlmCall.profile("biz", "main", MESSAGES).timeouts(1111, 2222);
    }

    private static LlmCompletion stop(String content) {
        return new LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.of(3, 2, 5), null);
    }

    /**
     * 同时实现同步和异步路径的假传输。异步完成放在独立线程池，
     * 这样“调用后立即返回”才是可断言的。
     */
    private final class ParityTransport implements LlmTransport {

        private final Deque<LlmCompletion> responses = new ArrayDeque<LlmCompletion>();
        private final Deque<RuntimeException> failures = new ArrayDeque<RuntimeException>();
        final List<LlmRequest> requests =
                Collections.synchronizedList(new ArrayList<LlmRequest>());
        final List<Thread> completeThreads =
                Collections.synchronizedList(new ArrayList<Thread>());
        volatile CountDownLatch gate;

        ParityTransport enqueue(LlmCompletion completion) {
            responses.addLast(completion);
            return this;
        }

        ParityTransport failWith(RuntimeException failure) {
            failures.addLast(failure);
            return this;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            completeThreads.add(Thread.currentThread());
            return poll();
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(final LlmRequest request) {
            requests.add(request);
            final CompletableFuture<LlmCompletion> future = new CompletableFuture<LlmCompletion>();
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    awaitGate();
                    completeThreads.add(Thread.currentThread());
                    if (future.isDone()) {
                        return;
                    }
                    try {
                        future.complete(poll());
                    } catch (RuntimeException ex) {
                        future.completeExceptionally(ex);
                    }
                }
            });
            return future;
        }

        private void awaitGate() {
            CountDownLatch latch = gate;
            if (latch == null) {
                return;
            }
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private LlmCompletion poll() {
            synchronized (responses) {
                RuntimeException failure = failures.isEmpty() ? null : failures.removeFirst();
                if (failure != null) {
                    throw failure;
                }
                return responses.isEmpty() ? stop("") : responses.removeFirst();
            }
        }
    }

    /**
     * 协议名不在网关注册表里，用于校验“不支持协议”。
     */
    private static final class GhoseApiTransport implements LlmTransport {
        @Override
        public String api() {
            return "ghost";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            throw new AssertionError("不应发请求");
        }
    }

    /**
     * 直接在主线程收集调用记录。
     */
    private static final class RecordingStore implements LlmCallStore {
        final List<LlmCallRecord> records =
                Collections.synchronizedList(new ArrayList<LlmCallRecord>());

        @Override
        public void insert(LlmCallRecord record) {
            records.add(record);
        }
    }
}
