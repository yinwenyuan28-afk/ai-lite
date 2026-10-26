package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
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
 * oneWithRetry：只重试瞬时错误、每次尝试独立记录、取消与总时限优先。
 */
public class LlmGatewayRetryTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    private final ExecutorService caller = Executors.newCachedThreadPool();

    @After
    public void tearDown() {
        caller.shutdownNow();
    }

    @Test
    public void firstAttemptSucceedsWithoutRetry() {
        ScriptedTransport transport = new ScriptedTransport().enqueue(stop("ok"));

        LlmResult result = gateway(transport).oneWithRetry(call(), fastPolicy(3));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void retriesTransportFailureThenSucceeds() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError())
                .enqueue(stop("ok"));

        LlmResult result = gateway(transport).oneWithRetry(call(), fastPolicy(3));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    public void retriesHttp503ThenSucceeds() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(httpError(503))
                .enqueue(stop("ok"));

        LlmResult result = gateway(transport).oneWithRetry(call(), fastPolicy(3));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    public void doesNotRetryHttp400() {
        ScriptedTransport transport = new ScriptedTransport().fail(httpError(400));

        assertThatThrownBy(() -> gateway(transport).oneWithRetry(call(), fastPolicy(3)))
                .isInstanceOf(LlmCallException.class)
                .satisfies(ex -> {
                    LlmCallException call = (LlmCallException) ex;
                    assertThat(call.getKind()).isEqualTo(LlmCallException.Kind.HTTP);
                    assertThat(call.getStatusCode()).isEqualTo(Integer.valueOf(400));
                });
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void doesNotRetryParseFailure() {
        ScriptedTransport transport = new ScriptedTransport().fail(
                new LlmCallException("bad json", null, LlmCallException.Kind.PARSE));

        assertThatThrownBy(() -> gateway(transport).oneWithRetry(call(), fastPolicy(3)))
                .isInstanceOf(LlmCallException.class);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void doesNotRetryConfigFailure() {
        ScriptedTransport transport = new ScriptedTransport();

        assertThatThrownBy(() -> gateway(transport).oneWithRetry(
                LlmCall.profile("other", "main", MESSAGES), fastPolicy(3)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("业务 id 未登记");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void maxAttemptsOneMeansNoRetry() {
        ScriptedTransport transport = new ScriptedTransport().fail(transportError());

        assertThatThrownBy(() -> gateway(transport).oneWithRetry(call(), fastPolicy(1)))
                .isInstanceOf(LlmCallException.class);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void exhaustedAttemptsKeepLastErrorAndSuppressedFailures() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError())
                .fail(transportError())
                .fail(transportError());

        assertThatThrownBy(() -> gateway(transport).oneWithRetry(call(), fastPolicy(3)))
                .isInstanceOf(LlmCallException.class)
                .satisfies(ex -> assertThat(ex.getSuppressed()).hasSize(2));
        assertThat(transport.requests).hasSize(3);
    }

    @Test
    public void cancelBeforeStartReturnsAbortedWithoutRequest() {
        ScriptedTransport transport = new ScriptedTransport();
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();

        LlmResult result = gateway(transport).oneWithRetry(call(), fastPolicy(3), cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void cancelDuringBackoffStopsFurtherAttempts() throws Exception {
        final ScriptedTransport transport = new ScriptedTransport().fail(transportError());
        final LlmCancelHandle cancel = new LlmCancelHandle();
        final LlmGateway gateway = gateway(transport);
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoffMs(1000L)
                .maxBackoffMs(1000L)
                .jitter(false)
                .build();

        Future<LlmResult> future = caller.submit(new java.util.concurrent.Callable<LlmResult>() {
            @Override
            public LlmResult call() {
                return gateway.oneWithRetry(LlmGatewayRetryTest.call(), policy, cancel);
            }
        });
        assertThat(transport.firstRequest.await(5, TimeUnit.SECONDS)).isTrue();
        cancel.cancel();

        LlmResult result = future.get(2, TimeUnit.SECONDS);
        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void totalDeadlineStopsBeforeAnotherAttempt() {
        ScriptedTransport transport = new ScriptedTransport().fail(transportError());
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5)
                .initialBackoffMs(1000L)
                .maxBackoffMs(1000L)
                .jitter(false)
                .totalDeadlineMs(30L)
                .build();

        assertThatThrownBy(() -> gateway(transport).oneWithRetry(call(), policy))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("重试超过总时限");
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void everyAttemptWritesItsOwnCallRecord() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError())
                .enqueue(stop("ok"));
        RecordingStore store = new RecordingStore();

        gateway(transport, store).oneWithRetry(call(), fastPolicy(3));

        assertThat(store.records).hasSize(2);
        assertThat(store.records.get(0).getStopReason())
                .isEqualTo(LlmStopReason.ERROR.wire());
        assertThat(store.records.get(1).getStopReason())
                .isEqualTo(LlmStopReason.STOP.wire());
    }

    @Test
    public void listenerObservesEachRetry() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError())
                .enqueue(stop("ok"));
        final AtomicInteger retries = new AtomicInteger();
        final long[] delay = new long[1];
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoffMs(1L)
                .maxBackoffMs(1L)
                .jitter(false)
                .listener(new RetryPolicy.Listener() {
                    @Override
                    public void onRetry(int failedAttempt, long delayMs, LlmCallException error) {
                        retries.incrementAndGet();
                        delay[0] = delayMs;
                    }
                }).build();

        gateway(transport).oneWithRetry(call(), policy);

        assertThat(retries.get()).isEqualTo(1);
        assertThat(delay[0]).isEqualTo(1L);
    }

    @Test
    public void unknownFailureWithIoCauseIsRetried() {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(new LlmCallException("boom", new IOException("reset")))
                .enqueue(stop("ok"));

        LlmResult result = gateway(transport).oneWithRetry(call(), fastPolicy(3));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    public void unknownFailureWithoutIoCauseIsNotRetried() {
        ScriptedTransport transport = new ScriptedTransport().fail(
                new LlmCallException("boom", new IllegalStateException("bug")));

        assertThatThrownBy(() -> gateway(transport).oneWithRetry(call(), fastPolicy(3)))
                .isInstanceOf(LlmCallException.class);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void plainOneStillAttemptsOnce() {
        ScriptedTransport transport = new ScriptedTransport().fail(transportError());

        assertThatThrownBy(() -> gateway(transport).one(call()))
                .isInstanceOf(LlmCallException.class);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void nullPolicyIsRejected() {
        assertThatThrownBy(() -> gateway(new ScriptedTransport()).oneWithRetry(call(), null))
                .isInstanceOf(LlmCallException.class)
                .satisfies(ex -> assertThat(((LlmCallException) ex).getKind())
                        .isEqualTo(LlmCallException.Kind.CONFIG));
    }

    private static LlmGateway gateway(LlmTransport transport) {
        return gateway(transport, new NoopLlmCallStore());
    }

    private static LlmGateway gateway(LlmTransport transport, LlmCallStore store) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), store,
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmCall call() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    private static RetryPolicy fastPolicy(int attempts) {
        return RetryPolicy.builder()
                .maxAttempts(attempts)
                .initialBackoffMs(1L)
                .maxBackoffMs(1L)
                .jitter(false)
                .build();
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
     * 按顺序返回成功结果或抛出指定异常，并记录每次请求。
     */
    private static final class ScriptedTransport implements LlmTransport {

        private final Deque<Object> outcomes = new ArrayDeque<Object>();
        final List<LlmRequest> requests =
                Collections.synchronizedList(new ArrayList<LlmRequest>());
        final CountDownLatch firstRequest = new CountDownLatch(1);

        ScriptedTransport enqueue(LlmCompletion completion) {
            synchronized (outcomes) {
                outcomes.addLast(completion);
            }
            return this;
        }

        ScriptedTransport fail(LlmCallException failure) {
            synchronized (outcomes) {
                outcomes.addLast(failure);
            }
            return this;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            firstRequest.countDown();
            Object outcome;
            synchronized (outcomes) {
                outcome = outcomes.isEmpty() ? stop("") : outcomes.removeFirst();
            }
            if (outcome instanceof LlmCallException) {
                throw (LlmCallException) outcome;
            }
            return (LlmCompletion) outcome;
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
