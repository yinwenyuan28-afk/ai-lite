package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.yinwenyuan28.ailite.internal.llm.RetryScheduler;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;

/**
 * oneWithRetryAsync：异步重试的主路径、deadline、取消、调度与记录。
 *
 * <p>测试替身在 {@code completeAsync} 入口记录线程名：重试启动线程的断言必须放在
 * transport 入口，而不是 {@code whenComplete}，后者运行在完成 future 的线程上。</p>
 */
public class LlmGatewayAsyncRetryTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    private ScheduledThreadPoolExecutor scheduler;
    private RecordingStore store;
    private LlmGateway gateway;

    @Before
    public void setUp() {
        scheduler = RetryScheduler.create();
        store = new RecordingStore();
    }

    @After
    public void tearDown() {
        scheduler.shutdownNow();
    }

    // ---------------------------------------------------------------- A 基本结果

    @Test
    public void a01FirstAttemptSucceeds() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().succeed("ok");
        gateway = gateway(transport);

        LlmResult result = await(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.attempts.get()).isEqualTo(1);
        assertThat(store.records).hasSize(1);
        awaitQueueEmpty();
    }

    @Test
    public void a02RetriesTransportFailureThenSucceeds() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError()).succeed("ok");
        gateway = gateway(transport);

        LlmResult result = await(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.attempts.get()).isEqualTo(2);
        assertThat(transport.threads).hasSize(2);
        assertThat(transport.threads.get(0)).doesNotStartWith(RetryScheduler.THREAD_PREFIX);
        assertThat(transport.threads.get(1)).startsWith(RetryScheduler.THREAD_PREFIX);
        assertThat(store.records).hasSize(2);
    }

    @Test
    public void a03MaxAttemptsOneMeansNoRetry() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(httpError(500));
        gateway = gateway(transport);

        LlmCallException failure = failure(gateway.oneWithRetryAsync(call(), policy(1)));

        assertThat(failure.getKind()).isEqualTo(LlmCallException.Kind.HTTP);
        assertThat(transport.attempts.get()).isEqualTo(1);
        assertThat(store.records).hasSize(1);
        awaitQueueEmpty();
    }

    @Test
    public void a04Http400IsNotRetried() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(httpError(400));
        gateway = gateway(transport);

        LlmCallException failure = failure(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(failure.getKind()).isEqualTo(LlmCallException.Kind.HTTP);
        assertThat(failure.getStatusCode()).isEqualTo(Integer.valueOf(400));
        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void a05Http401And404AreNotRetried() throws Exception {
        for (int status : new int[]{401, 404}) {
            store = new RecordingStore();
            ScriptedTransport transport = new ScriptedTransport().fail(httpError(status));
            gateway = gateway(transport);

            LlmCallException failure = failure(gateway.oneWithRetryAsync(call(), policy(3)));

            assertThat(failure.getStatusCode()).isEqualTo(Integer.valueOf(status));
            assertThat(transport.attempts.get()).isEqualTo(1);
        }
    }

    @Test
    public void a06ParseFailureIsNotRetried() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(
                new LlmCallException("bad json", null, LlmCallException.Kind.PARSE));
        gateway = gateway(transport);

        failure(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void a07ConfigFailureIsNotRetried() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(
                new LlmCallException("bad config", null, LlmCallException.Kind.CONFIG));
        gateway = gateway(transport);

        failure(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void a08AbortedCompletionIsTerminal() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .complete(new LlmCompletion("", null, LlmStopReason.ABORTED, LlmUsage.zero(), null))
                .succeed("never");
        gateway = gateway(transport);

        LlmResult result = await(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void a09CancelledErrorIsNotRetried() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(
                new LlmCallException("cancelled", null, LlmCallException.Kind.CANCELLED));
        gateway = gateway(transport);

        LlmCallException failure = failure(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(failure.getKind()).isEqualTo(LlmCallException.Kind.CANCELLED);
        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void a10ExhaustedAttemptsKeepLastErrorAndSuppressed() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(httpError(500)).fail(httpError(503)).fail(httpError(502));
        gateway = gateway(transport);

        LlmCallException failure = failure(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(transport.attempts.get()).isEqualTo(3);
        assertThat(failure.getStatusCode()).isEqualTo(Integer.valueOf(502));
        assertThat(failure.getSuppressed()).hasSize(2);
        assertThat(store.records).hasSize(3);
    }

    @Test
    public void a11EveryAttemptWritesItsOwnRecord() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError()).fail(transportError()).succeed("ok");
        gateway = gateway(transport);

        await(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(store.records).hasSize(3);
        assertThat(store.records.get(0).getStopReason())
                .isEqualTo(LlmStopReason.ERROR.wire());
        assertThat(store.records.get(2).getStopReason())
                .isEqualTo(LlmStopReason.STOP.wire());
    }

    @Test
    public void a12CustomRetryableTrueRetries() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(httpError(500)).succeed("ok");
        gateway = gateway(transport);
        RetryPolicy custom = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(0L).maxBackoffMs(0L).jitter(false)
                .retryable(error -> true).build();

        LlmResult result = await(gateway.oneWithRetryAsync(call(), custom));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.attempts.get()).isEqualTo(2);
    }

    @Test
    public void a13CustomRetryableFalseStopsImmediately() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(httpError(500));
        gateway = gateway(transport);
        RetryPolicy custom = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(0L).maxBackoffMs(0L).jitter(false)
                .retryable(error -> false).build();

        failure(gateway.oneWithRetryAsync(call(), custom));

        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void a14NullPolicyThrowsSynchronously() {
        gateway = gateway(new ScriptedTransport());

        assertThatThrownBy(() -> gateway.oneWithRetryAsync(call(), null))
                .isInstanceOf(LlmCallException.class)
                .satisfies(ex -> assertThat(((LlmCallException) ex).getKind())
                        .isEqualTo(LlmCallException.Kind.CONFIG));
    }

    @Test
    public void a15FirstAttemptTransportThrowBecomesFailedFuture() throws Exception {
        final LlmCallException config = new LlmCallException(
                "boom", null, LlmCallException.Kind.CONFIG);
        ScriptedTransport transport = new ScriptedTransport().throwSync(config);
        gateway = gateway(transport);

        LlmCallException failure = failure(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(failure.getKind()).isEqualTo(LlmCallException.Kind.CONFIG);
        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- B deadline

    @Test
    public void b01NoDeadlineRetriesToMaxAttempts() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(transportError());
        gateway = gateway(transport);

        failure(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(transport.attempts.get()).isEqualTo(3);
    }

    @Test
    public void b02ZeroDeadlineMeansImmediateExpiry() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().succeed("never");
        gateway = gateway(transport);
        RetryPolicy deadline = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(10L).maxBackoffMs(10L)
                .jitter(false).totalDeadlineMs(0L).build();

        LlmCallException failure = failure(gateway.oneWithRetryAsync(call(), deadline));

        assertThat(failure.getMessage()).contains("重试超过总时限");
        assertThat(transport.attempts.get()).isEqualTo(0);
        assertThat(store.records).isEmpty();
    }

    @Test
    public void b03RemainingTimeTooShortStopsBeforeNextAttempt() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(transportError());
        gateway = gateway(transport);
        RetryPolicy deadline = RetryPolicy.builder()
                .maxAttempts(5).initialBackoffMs(5000L).maxBackoffMs(5000L)
                .jitter(false).totalDeadlineMs(50L).build();

        LlmCallException failure = failure(gateway.oneWithRetryAsync(call(), deadline));

        assertThat(failure.getMessage()).contains("重试超过总时限");
        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void b04BackoffIsClampedToRemainingDeadline() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(transportError());
        gateway = gateway(transport);
        RetryPolicy deadline = RetryPolicy.builder()
                .maxAttempts(5).initialBackoffMs(5000L).maxBackoffMs(5000L)
                .jitter(false).totalDeadlineMs(300L).build();

        CompletableFuture<LlmResult> future = gateway.oneWithRetryAsync(call(), deadline);
        awaitQueueSize(1);
        ScheduledFuture<?> queued = (ScheduledFuture<?>) scheduler.getQueue().peek();
        assertThat(queued.getDelay(TimeUnit.MILLISECONDS)).isBetween(0L, 301L);

        LlmCallException failure = failure(future);
        assertThat(failure.getMessage()).contains("重试超过总时限");
        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void b05DeadlineDoesNotInterruptInFlightAttempt() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().pending();
        gateway = gateway(transport);
        RetryPolicy deadline = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(10L).maxBackoffMs(10L)
                .jitter(false).totalDeadlineMs(50L).build();

        CompletableFuture<LlmResult> future = gateway.oneWithRetryAsync(call(), deadline);
        awaitAttempts(transport, 1);
        Thread.sleep(100L);
        transport.completePending(0, stop("late"));

        LlmResult result = await(future);
        assertThat(result.getContent()).isEqualTo("late");
        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void b06DeadlineFailureKeepsLastClassification() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(httpError(503));
        gateway = gateway(transport);
        RetryPolicy deadline = RetryPolicy.builder()
                .maxAttempts(5).initialBackoffMs(5000L).maxBackoffMs(5000L)
                .jitter(false).totalDeadlineMs(50L).build();

        LlmCallException failure = failure(gateway.oneWithRetryAsync(call(), deadline));

        assertThat(failure.getKind()).isEqualTo(LlmCallException.Kind.HTTP);
        assertThat(failure.getStatusCode()).isEqualTo(Integer.valueOf(503));
    }

    @Test
    public void b07CancelWinsOverZeroDeadline() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().succeed("never");
        gateway = gateway(transport);
        LlmCancelHandle handle = new LlmCancelHandle();
        handle.cancel();
        RetryPolicy deadline = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(10L).maxBackoffMs(10L)
                .jitter(false).totalDeadlineMs(0L).build();

        LlmResult result = await(gateway.oneWithRetryAsync(call(), deadline, handle));

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.attempts.get()).isEqualTo(0);
        assertThat(store.records).hasSize(1);
    }

    // ---------------------------------------------------------------- C 取消

    @Test
    public void c01PreCancelledHandleReturnsAborted() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().succeed("never");
        gateway = gateway(transport);
        LlmCancelHandle handle = new LlmCancelHandle();
        handle.cancel();

        LlmResult result = await(gateway.oneWithRetryAsync(call(), policy(3), handle));

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.attempts.get()).isEqualTo(0);
        assertThat(store.records).hasSize(1);
        awaitQueueEmpty();
    }

    @Test
    public void c02CancelDuringAttemptAbortsWithoutSecondRequest() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().pending();
        gateway = gateway(transport);
        LlmCancelHandle handle = new LlmCancelHandle();

        CompletableFuture<LlmResult> future = gateway.oneWithRetryAsync(call(), policy(3), handle);
        awaitAttempts(transport, 1);
        handle.cancel();

        LlmResult result = await(future);
        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.attempts.get()).isEqualTo(1);
        assertThat(store.records).hasSize(1);
        awaitQueueEmpty();
    }

    @Test
    public void c03CancelDuringBackoffStopsScheduledRetry() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(transportError()).succeed("never");
        gateway = gateway(transport);
        LlmCancelHandle handle = new LlmCancelHandle();
        RetryPolicy slow = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(2000L).maxBackoffMs(2000L)
                .jitter(false).build();

        CompletableFuture<LlmResult> future = gateway.oneWithRetryAsync(call(), slow, handle);
        awaitQueueSize(1);
        handle.cancel();

        LlmResult result = await(future);
        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.attempts.get()).isEqualTo(1);
        assertThat(store.records).hasSize(1);
        awaitQueueEmpty();
    }

    @Test
    public void c04FutureCancelDuringAttemptCancelsUnderlying() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().pending();
        gateway = gateway(transport);

        CompletableFuture<LlmResult> future = gateway.oneWithRetryAsync(call(), policy(3));
        awaitAttempts(transport, 1);
        assertThat(future.cancel(true)).isTrue();

        assertThat(future.isCancelled()).isTrue();
        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .isInstanceOf(CancellationException.class);
        Thread.sleep(100L);
        assertThat(transport.attempts.get()).isEqualTo(1);
        awaitQueueEmpty();
    }

    @Test
    public void c05FutureCancelDuringBackoffClearsQueue() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(transportError()).succeed("never");
        gateway = gateway(transport);
        RetryPolicy slow = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(2000L).maxBackoffMs(2000L)
                .jitter(false).build();

        CompletableFuture<LlmResult> future = gateway.oneWithRetryAsync(call(), slow);
        awaitQueueSize(1);
        assertThat(future.cancel(true)).isTrue();

        assertThat(future.isCancelled()).isTrue();
        awaitQueueEmpty();
        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void c06RepeatedCancelIsIdempotent() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().pending();
        gateway = gateway(transport);
        LlmCancelHandle handle = new LlmCancelHandle();

        CompletableFuture<LlmResult> future = gateway.oneWithRetryAsync(call(), policy(3), handle);
        awaitAttempts(transport, 1);
        handle.cancel();
        handle.cancel();

        LlmResult result = await(future);
        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(store.records).hasSize(1);
    }

    @Test
    public void c07LateCompletionDoesNotOverrideAborted() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().pending();
        gateway = gateway(transport);
        LlmCancelHandle handle = new LlmCancelHandle();

        CompletableFuture<LlmResult> future = gateway.oneWithRetryAsync(call(), policy(3), handle);
        awaitAttempts(transport, 1);
        transport.detachCancellation(0);
        handle.cancel();
        transport.completePending(0, stop("late"));

        LlmResult result = await(future);
        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void c08CancelAfterSuccessHasNoEffect() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().succeed("ok");
        gateway = gateway(transport);
        LlmCancelHandle handle = new LlmCancelHandle();

        LlmResult result = await(gateway.oneWithRetryAsync(call(), policy(3), handle));
        handle.cancel();

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.STOP);
        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void c09CancelRacingRetryTaskConvergesOnce() throws Exception {
        for (int i = 0; i < 50; i++) {
            store = new RecordingStore();
            ScriptedTransport transport = new ScriptedTransport()
                    .fail(transportError()).pending();
            gateway = gateway(transport);
            LlmCancelHandle handle = new LlmCancelHandle();
            RetryPolicy zero = RetryPolicy.builder()
                    .maxAttempts(3).initialBackoffMs(0L).maxBackoffMs(0L)
                    .jitter(false).build();

            CompletableFuture<LlmResult> future =
                    gateway.oneWithRetryAsync(call(), zero, handle);
            awaitAttempts(transport, 1);
            handle.cancel();

            LlmResult result = await(future);
            assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
            assertThat(transport.attempts.get()).isBetween(1, 2);
            assertThat(store.records.size()).isBetween(1, 2);
            awaitQueueEmpty();
        }
    }

    @Test
    public void c10CancelAtRetryBoundaryNeverStartsThirdAttempt() throws Exception {
        for (int i = 0; i < 30; i++) {
            store = new RecordingStore();
            ScriptedTransport transport = new ScriptedTransport()
                    .fail(transportError()).pending();
            gateway = gateway(transport);
            LlmCancelHandle handle = new LlmCancelHandle();
            RetryPolicy zero = RetryPolicy.builder()
                    .maxAttempts(3).initialBackoffMs(0L).maxBackoffMs(0L)
                    .jitter(false).build();

            CompletableFuture<LlmResult> future =
                    gateway.oneWithRetryAsync(call(), zero, handle);
            awaitAttempts(transport, 1);
            // 让取消与重启任务同时竞争；无论谁赢，都不能出现第三次尝试。
            handle.cancel();

            await(future);
            assertThat(transport.attempts.get()).isLessThanOrEqualTo(2);
            awaitQueueEmpty();
        }
    }

    // ---------------------------------------------------------------- D 调度

    @Test
    public void d01ZeroDelayDoesNotRecurse() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(transportError());
        gateway = gateway(transport);
        RetryPolicy zero = RetryPolicy.builder()
                .maxAttempts(1000).initialBackoffMs(0L).maxBackoffMs(0L)
                .jitter(false).build();

        failure(gateway.oneWithRetryAsync(call(), zero));

        assertThat(transport.attempts.get()).isEqualTo(1000);
        for (int i = 1; i < transport.threads.size(); i++) {
            assertThat(transport.threads.get(i)).startsWith(RetryScheduler.THREAD_PREFIX);
        }
    }

    @Test
    public void d02QueueIsEmptyAfterEveryTerminalState() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().succeed("ok");
        gateway = gateway(transport);
        await(gateway.oneWithRetryAsync(call(), policy(3)));
        awaitQueueEmpty();

        store = new RecordingStore();
        transport = new ScriptedTransport().fail(httpError(400));
        gateway = gateway(transport);
        failure(gateway.oneWithRetryAsync(call(), policy(3)));
        awaitQueueEmpty();
    }

    @Test
    public void d03RejectedSchedulerFailsFutureInsteadOfHanging() throws Exception {
        ScriptedTransport transport = new ScriptedTransport().fail(transportError());
        gateway = gateway(transport);
        scheduler.shutdownNow();

        LlmCallException failure = failure(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(failure.getMessage()).contains("重试调度被拒绝");
        assertThat(transport.attempts.get()).isEqualTo(1);
    }

    @Test
    public void d04SecondAttemptFailureDoesNotHang() throws Exception {
        LlmCallException config = new LlmCallException(
                "second", null, LlmCallException.Kind.CONFIG);
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError()).throwSync(config);
        gateway = gateway(transport);

        LlmCallException failure = failure(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(failure.getKind()).isEqualTo(LlmCallException.Kind.CONFIG);
        assertThat(transport.attempts.get()).isEqualTo(2);
        awaitQueueEmpty();
    }

    @Test
    public void d05RetryThreadsUseDedicatedPrefix() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError()).succeed("ok");
        gateway = gateway(transport);

        await(gateway.oneWithRetryAsync(call(), policy(3)));

        assertThat(transport.threads.get(1)).startsWith(RetryScheduler.THREAD_PREFIX);
    }

    @Test
    public void d06TransportNeverRunsOnIoExecutor() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError()).succeed("ok");
        gateway = gateway(transport);

        await(gateway.oneWithRetryAsync(call(), policy(3)));

        for (String thread : transport.threads) {
            assertThat(thread).doesNotContain("io-");
        }
    }

    @Test
    public void d07ListenerRuntimeExceptionDoesNotBreakRetry() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError()).succeed("ok");
        gateway = gateway(transport);
        RetryPolicy throwing = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(0L).maxBackoffMs(0L).jitter(false)
                .listener(new RetryPolicy.Listener() {
                    @Override
                    public void onRetry(int failedAttempt, long delayMs, LlmCallException error) {
                        throw new IllegalStateException("listener bug");
                    }
                }).build();

        LlmResult result = await(gateway.oneWithRetryAsync(call(), throwing));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.attempts.get()).isEqualTo(2);
    }

    @Test
    public void d08ListenerErrorDoesNotKillSchedulerWorker() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError()).succeed("ok");
        gateway = gateway(transport);
        RetryPolicy throwing = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(0L).maxBackoffMs(0L).jitter(false)
                .listener(new RetryPolicy.Listener() {
                    @Override
                    public void onRetry(int failedAttempt, long delayMs, LlmCallException error) {
                        throw new AssertionError("listener error");
                    }
                }).build();

        LlmResult result = await(gateway.oneWithRetryAsync(call(), throwing));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.attempts.get()).isEqualTo(2);
    }

    @Test
    public void d09ListenerSeesTheScheduledDelay() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError()).succeed("ok");
        gateway = gateway(transport);
        final List<Long> observed = new CopyOnWriteArrayList<Long>();
        RetryPolicy fixed = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(25L).maxBackoffMs(25L).jitter(false)
                .listener(new RetryPolicy.Listener() {
                    @Override
                    public void onRetry(int failedAttempt, long delayMs, LlmCallException error) {
                        observed.add(Long.valueOf(delayMs));
                    }
                }).build();

        await(gateway.oneWithRetryAsync(call(), fixed));

        assertThat(observed).containsExactly(Long.valueOf(25L));
    }

    @Test
    public void d10JitterStaysWithinPolicyRange() throws Exception {
        ScriptedTransport transport = new ScriptedTransport()
                .fail(transportError()).succeed("ok");
        gateway = gateway(transport);
        final List<Long> observed = new CopyOnWriteArrayList<Long>();
        RetryPolicy jitter = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(1000L).maxBackoffMs(1000L).jitter(true)
                .listener(new RetryPolicy.Listener() {
                    @Override
                    public void onRetry(int failedAttempt, long delayMs, LlmCallException error) {
                        observed.add(Long.valueOf(delayMs));
                    }
                }).build();

        await(gateway.oneWithRetryAsync(call(), jitter));

        assertThat(observed).hasSize(1);
        assertThat(observed.get(0).longValue()).isBetween(800L, 1000L);
    }

    // ---------------------------------------------------------------- 工具

    private LlmGateway gateway(LlmTransport transport) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), store,
                new DirectExecutorProvider(), new SequentialToolExecutor(), scheduler);
    }

    private static LlmCall call() {
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

    private static LlmResult await(CompletableFuture<LlmResult> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static LlmCallException failure(CompletableFuture<LlmResult> future) throws Exception {
        try {
            LlmResult result = future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected failure, got " + result.getStopReason());
        } catch (ExecutionException ex) {
            assertThat(ex.getCause()).isInstanceOf(LlmCallException.class);
            return (LlmCallException) ex.getCause();
        }
    }

    private void awaitQueueEmpty() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!scheduler.getQueue().isEmpty() && System.nanoTime() < end) {
            Thread.sleep(5L);
        }
        assertThat(scheduler.getQueue()).isEmpty();
    }

    private void awaitQueueSize(int expected) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (scheduler.getQueue().size() < expected && System.nanoTime() < end) {
            Thread.sleep(5L);
        }
        assertThat(scheduler.getQueue().size()).isEqualTo(expected);
    }

    /**
     * 等到至少发起 {@code expected} 次尝试。
     *
     * <p>零延迟重试下，断言“恰好等于”会和调度线程竞态：第 2 次尝试可能在断言前已经启动。
     * 这里的语义只是“已经过了这个点”，精确次数由调用方用区间断言。</p>
     */
    private static void awaitAttempts(ScriptedTransport transport, int expected)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (transport.attempts.get() < expected && System.nanoTime() < end) {
            Thread.sleep(5L);
        }
        assertThat(transport.attempts.get()).isGreaterThanOrEqualTo(expected);
    }

    /**
     * 按脚本返回成功、失败、pending 或同步抛出，并记录每次尝试的线程与请求。
     */
    private static final class ScriptedTransport implements LlmTransport {

        final AtomicInteger attempts = new AtomicInteger();
        final List<String> threads = new CopyOnWriteArrayList<String>();
        final List<LlmRequest> requests = new CopyOnWriteArrayList<LlmRequest>();
        final List<CompletableFuture<LlmCompletion>> pending =
                new CopyOnWriteArrayList<CompletableFuture<LlmCompletion>>();
        private final List<Outcome> outcomes = new CopyOnWriteArrayList<Outcome>();

        ScriptedTransport succeed(String content) {
            outcomes.add(Outcome.complete(stop(content)));
            return this;
        }

        ScriptedTransport complete(LlmCompletion completion) {
            outcomes.add(Outcome.complete(completion));
            return this;
        }

        ScriptedTransport fail(LlmCallException error) {
            outcomes.add(Outcome.fail(error));
            return this;
        }

        ScriptedTransport pending() {
            outcomes.add(Outcome.pending());
            return this;
        }

        ScriptedTransport throwSync(RuntimeException error) {
            outcomes.add(Outcome.throwSync(error));
            return this;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            Outcome outcome = next(request);
            if (outcome.syncThrow != null) {
                throw outcome.syncThrow;
            }
            if (outcome.error != null) {
                throw outcome.error;
            }
            if (outcome.pending) {
                throw new IllegalStateException("pending 只支持异步路径");
            }
            return outcome.completion;
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            final Outcome outcome = next(request);
            if (outcome.syncThrow != null) {
                throw outcome.syncThrow;
            }
            if (outcome.error != null) {
                CompletableFuture<LlmCompletion> failed = new CompletableFuture<LlmCompletion>();
                failed.completeExceptionally(outcome.error);
                return failed;
            }
            if (!outcome.pending) {
                return CompletableFuture.completedFuture(outcome.completion);
            }
            final CompletableFuture<LlmCompletion> future = new CompletableFuture<LlmCompletion>();
            pending.add(future);
            final LlmCancelHandle cancel = request.getCancel();
            if (cancel != null) {
                cancel.bindAbort(new Runnable() {
                    @Override
                    public void run() {
                        future.complete(aborted());
                    }
                });
                if (cancel.isCancelled()) {
                    future.complete(aborted());
                }
            }
            return future;
        }

        void completePending(int index, LlmCompletion completion) {
            pending.get(index).complete(completion);
        }

        /**
         * 解绑取消动作，模拟“传输层不响应 abort”的极端情况。
         */
        void detachCancellation(int index) {
            LlmRequest request = requests.get(index);
            if (request.getCancel() != null) {
                request.getCancel().bindAbort(null);
            }
        }

        private Outcome next(LlmRequest request) {
            int index = attempts.getAndIncrement();
            threads.add(Thread.currentThread().getName());
            requests.add(request);
            int scripted = Math.min(index, outcomes.size() - 1);
            return outcomes.get(scripted);
        }

        private static LlmCompletion aborted() {
            return new LlmCompletion("", null, LlmStopReason.ABORTED, LlmUsage.zero(), null);
        }
    }

    private static final class Outcome {
        final LlmCompletion completion;
        final LlmCallException error;
        final RuntimeException syncThrow;
        final boolean pending;

        private Outcome(LlmCompletion completion, LlmCallException error,
                        RuntimeException syncThrow, boolean pending) {
            this.completion = completion;
            this.error = error;
            this.syncThrow = syncThrow;
            this.pending = pending;
        }

        static Outcome complete(LlmCompletion completion) {
            return new Outcome(completion, null, null, false);
        }

        static Outcome fail(LlmCallException error) {
            return new Outcome(null, error, null, false);
        }

        static Outcome throwSync(RuntimeException error) {
            return new Outcome(null, null, error, false);
        }

        static Outcome pending() {
            return new Outcome(null, null, null, true);
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
