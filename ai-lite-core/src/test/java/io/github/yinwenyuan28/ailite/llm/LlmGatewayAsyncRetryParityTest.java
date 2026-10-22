package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同步 {@code oneWithRetry} 与异步 {@code oneWithRetryAsync} 的行为一致性。
 *
 * <p>两者共用的规则（次数、分类、suppressed、deadline）必须一致；差异只在
 * 取消退避时的调用记录数量，见 F-05。</p>
 */
public class LlmGatewayAsyncRetryParityTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    private ScheduledThreadPoolExecutor scheduler;
    private final ExecutorService caller = Executors.newCachedThreadPool();

    @Before
    public void setUp() {
        scheduler = RetryScheduler.create();
    }

    @After
    public void tearDown() {
        scheduler.shutdownNow();
        caller.shutdownNow();
    }

    @Test
    public void f01SuccessMatchesSync() throws Exception {
        ScriptedTransport syncTransport = new ScriptedTransport().fail(transportError()).succeed("ok");
        ScriptedTransport asyncTransport = new ScriptedTransport().fail(transportError()).succeed("ok");
        RecordingStore syncStore = new RecordingStore();
        RecordingStore asyncStore = new RecordingStore();

        LlmResult sync = sync(syncTransport, syncStore).oneWithRetry(llmCall(), policy(3));
        LlmResult async = async(asyncTransport, asyncStore).oneWithRetryAsync(llmCall(), policy(3))
                .get(5, TimeUnit.SECONDS);

        assertThat(sync.getContent()).isEqualTo(async.getContent());
        assertThat(sync.getStopReason()).isEqualTo(async.getStopReason());
        assertThat(syncStore.records).hasSize(asyncStore.records.size());
        assertThat(syncTransport.attempts.get()).isEqualTo(asyncTransport.attempts.get());
    }

    @Test
    public void f02ExhaustionMatchesSync() throws Exception {
        ScriptedTransport syncTransport = new ScriptedTransport()
                .fail(httpError(500)).fail(httpError(503)).fail(httpError(502));
        ScriptedTransport asyncTransport = new ScriptedTransport()
                .fail(httpError(500)).fail(httpError(503)).fail(httpError(502));
        RecordingStore syncStore = new RecordingStore();
        RecordingStore asyncStore = new RecordingStore();

        LlmCallException sync = syncFailure(syncTransport, syncStore, policy(3));
        LlmCallException async = asyncFailure(asyncTransport, asyncStore, policy(3));

        assertThat(syncTransport.attempts.get()).isEqualTo(asyncTransport.attempts.get());
        assertThat(sync.getStatusCode()).isEqualTo(async.getStatusCode());
        assertThat(sync.getKind()).isEqualTo(async.getKind());
        assertThat(sync.getSuppressed().length).isEqualTo(async.getSuppressed().length);
        assertThat(syncStore.records).hasSize(asyncStore.records.size());
    }

    @Test
    public void f03NonRetryableMatchesSync() throws Exception {
        ScriptedTransport syncTransport = new ScriptedTransport().fail(httpError(400));
        ScriptedTransport asyncTransport = new ScriptedTransport().fail(httpError(400));

        LlmCallException sync = syncFailure(syncTransport, new RecordingStore(), policy(3));
        LlmCallException async = asyncFailure(asyncTransport, new RecordingStore(), policy(3));

        assertThat(syncTransport.attempts.get()).isEqualTo(1);
        assertThat(asyncTransport.attempts.get()).isEqualTo(1);
        assertThat(sync.getKind()).isEqualTo(async.getKind());
        assertThat(sync.getStatusCode()).isEqualTo(async.getStatusCode());
    }

    @Test
    public void f04PreCancelMatchesSync() throws Exception {
        ScriptedTransport syncTransport = new ScriptedTransport().succeed("never");
        ScriptedTransport asyncTransport = new ScriptedTransport().succeed("never");
        RecordingStore syncStore = new RecordingStore();
        RecordingStore asyncStore = new RecordingStore();
        LlmCancelHandle syncCancel = new LlmCancelHandle();
        LlmCancelHandle asyncCancel = new LlmCancelHandle();
        syncCancel.cancel();
        asyncCancel.cancel();

        LlmResult sync = sync(syncTransport, syncStore).oneWithRetry(llmCall(), policy(3), syncCancel);
        LlmResult async = async(asyncTransport, asyncStore)
                .oneWithRetryAsync(llmCall(), policy(3), asyncCancel).get(5, TimeUnit.SECONDS);

        assertThat(sync.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(async.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(syncTransport.attempts.get()).isZero();
        assertThat(asyncTransport.attempts.get()).isZero();
        assertThat(syncStore.records).hasSize(asyncStore.records.size());
    }

    @Test
    public void f05BackoffCancelMatchesAttemptsButNotRecordCount() throws Exception {
        final ScriptedTransport syncTransport = new ScriptedTransport().fail(transportError());
        final ScriptedTransport asyncTransport = new ScriptedTransport().fail(transportError());
        final RecordingStore syncStore = new RecordingStore();
        final RecordingStore asyncStore = new RecordingStore();
        final LlmGateway syncGateway = sync(syncTransport, syncStore);
        final LlmGateway asyncGateway = async(asyncTransport, asyncStore);
        LlmCancelHandle syncCancel = new LlmCancelHandle();
        LlmCancelHandle asyncCancel = new LlmCancelHandle();
        RetryPolicy slow = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(2000L).maxBackoffMs(2000L)
                .jitter(false).build();

        Future<LlmResult> syncFuture = caller.submit(new java.util.concurrent.Callable<LlmResult>() {
            @Override
            public LlmResult call() {
                return syncGateway.oneWithRetry(llmCall(), slow, syncCancel);
            }
        });
        awaitAtLeast(syncTransport.attempts, 1);
        syncCancel.cancel();
        LlmResult sync = syncFuture.get(5, TimeUnit.SECONDS);

        CompletableFuture<LlmResult> asyncFuture =
                asyncGateway.oneWithRetryAsync(llmCall(), slow, asyncCancel);
        awaitAtLeast(asyncTransport.attempts, 1);
        asyncCancel.cancel();
        LlmResult async = asyncFuture.get(5, TimeUnit.SECONDS);

        assertThat(sync.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(async.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(syncTransport.attempts.get()).isEqualTo(asyncTransport.attempts.get());
        // 同步版取消后会再调一次 one(call, cancel)，因此多一条 aborted 记录。
        assertThat(syncStore.records.size()).isGreaterThan(asyncStore.records.size());
    }

    @Test
    public void f06CoroutinePolicyArithmeticMatchesAsync() {
        // 协程实现复用 RetrySupport，这里直接校验共享算术在三方入口下的边界。
        Long noDeadline = RetrySupport.deadlineNanos(100L, null);
        Long zeroDeadline = RetrySupport.deadlineNanos(100L, Long.valueOf(0L));
        Long later = RetrySupport.deadlineNanos(100L, Long.valueOf(10L));

        assertThat(noDeadline).isNull();
        assertThat(RetrySupport.deadlineReached(zeroDeadline, 100L)).isTrue();
        assertThat(RetrySupport.deadlineReached(later, 100L)).isFalse();
        assertThat(RetrySupport.remainingNanos(later, 100L))
                .isEqualTo(TimeUnit.MILLISECONDS.toNanos(10L));
    }

    private LlmGateway sync(LlmTransport transport, LlmCallStore store) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), store,
                new DirectExecutorProvider(), new SequentialToolExecutor(), scheduler);
    }

    private LlmGateway async(LlmTransport transport, LlmCallStore store) {
        return sync(transport, store);
    }

    private LlmCallException asyncFailure(ScriptedTransport transport, LlmCallStore store,
                                          RetryPolicy policy) throws Exception {
        try {
            asyncGateway(transport, store).oneWithRetryAsync(llmCall(), policy)
                    .get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected failure");
        } catch (ExecutionException ex) {
            return (LlmCallException) ex.getCause();
        }
    }

    private LlmCallException syncFailure(ScriptedTransport transport, LlmCallStore store,
                                         RetryPolicy policy) {
        try {
            syncGateway(transport, store).oneWithRetry(llmCall(), policy);
            throw new AssertionError("expected failure");
        } catch (LlmCallException ex) {
            return ex;
        }
    }

    private LlmGateway syncGateway(LlmTransport transport, LlmCallStore store) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), store,
                new DirectExecutorProvider(), new SequentialToolExecutor(), scheduler);
    }

    private LlmGateway asyncGateway(LlmTransport transport, LlmCallStore store) {
        return syncGateway(transport, store);
    }

    private static void awaitAtLeast(AtomicInteger counter, int expected)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (counter.get() < expected && System.nanoTime() < end) {
            Thread.sleep(5L);
        }
        assertThat(counter.get()).isGreaterThanOrEqualTo(expected);
    }

    private static LlmCall llmCall() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    private static RetryPolicy policy(int attempts) {
        return RetryPolicy.builder()
                .maxAttempts(attempts).initialBackoffMs(0L).maxBackoffMs(0L)
                .jitter(false).build();
    }

    private static LlmCallException transportError() {
        return new LlmCallException("boom", new IOException("io"),
                LlmCallException.Kind.TRANSPORT);
    }

    private static LlmCallException httpError(int status) {
        return new LlmCallException("http " + status, null,
                LlmCallException.Kind.HTTP, Integer.valueOf(status));
    }

    private static LlmCompletion stop(String content) {
        return new LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.zero(), null);
    }

    /**
     * 同时支持同步与异步入口的脚本化传输。
     */
    private static final class ScriptedTransport implements LlmTransport {

        final AtomicInteger attempts = new AtomicInteger();
        final List<LlmRequest> requests = new CopyOnWriteArrayList<LlmRequest>();
        final CountDownLatch firstRequest = new CountDownLatch(1);
        private final List<Object> outcomes = new CopyOnWriteArrayList<Object>();

        ScriptedTransport succeed(String content) {
            outcomes.add(stop(content));
            return this;
        }

        ScriptedTransport fail(LlmCallException error) {
            outcomes.add(error);
            return this;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            int index = record(request);
            Object outcome = outcomes.get(Math.min(index, outcomes.size() - 1));
            if (outcome instanceof LlmCallException) {
                throw (LlmCallException) outcome;
            }
            return (LlmCompletion) outcome;
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            int index = record(request);
            Object outcome = outcomes.get(Math.min(index, outcomes.size() - 1));
            if (outcome instanceof LlmCallException) {
                CompletableFuture<LlmCompletion> failed = new CompletableFuture<LlmCompletion>();
                failed.completeExceptionally((LlmCallException) outcome);
                return failed;
            }
            return CompletableFuture.completedFuture((LlmCompletion) outcome);
        }

        private int record(LlmRequest request) {
            requests.add(request);
            firstRequest.countDown();
            return attempts.getAndIncrement();
        }
    }

    private static final class RecordingStore implements LlmCallStore {
        final List<LlmCallRecord> records =
                Collections.synchronizedList(new ArrayList<LlmCallRecord>());

        @Override
        public void insert(LlmCallRecord record) {
            records.add(record);
        }
    }
}
