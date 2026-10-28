package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCallRecord;
import io.github.yinwenyuan28.ailite.llm.LlmCallStore;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.RetryPolicy;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workflow 异步执行器：顺序、蹦床、取消、异常、重试步骤与适配器。
 */
public class WorkflowAsyncTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    // ---------------------------------------------------------------- 主路径

    @Test
    public void emptyListCompletesWithImmutableEmptyList() throws Exception {
        Workflow workflow = workflow(emptyGateway());

        List<LlmResult> results = await(workflow.runAsync(Collections.<AsyncStep>emptyList()));

        assertThat(results).isEmpty();
        assertThatThrownBy(() -> results.add(result("x", LlmStopReason.STOP)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void stepsRunSequentially() throws Exception {
        CompletableFuture<LlmResult> first = new CompletableFuture<LlmResult>();
        final AtomicInteger secondCalls = new AtomicInteger();
        AsyncStep firstStep = (gateway, cancel) -> first;
        AsyncStep secondStep = (gateway, cancel) -> {
            secondCalls.incrementAndGet();
            return CompletableFuture.completedFuture(result("second", LlmStopReason.STOP));
        };
        Workflow workflow = workflow(emptyGateway());

        CompletableFuture<List<LlmResult>> run =
                workflow.runAsync(Arrays.asList(firstStep, secondStep));

        assertThat(secondCalls.get()).isZero();
        first.complete(result("first", LlmStopReason.STOP));
        List<LlmResult> results = await(run);

        assertThat(results).extracting("content").containsExactly("first", "second");
        assertThat(secondCalls.get()).isEqualTo(1);
    }

    @Test
    public void syncCompletedStepsDoNotOverflowStack() throws Exception {
        List<AsyncStep> steps = new ArrayList<AsyncStep>();
        for (int i = 0; i < 10_000; i++) {
            steps.add((gateway, cancel) ->
                    CompletableFuture.completedFuture(result("s", LlmStopReason.STOP)));
        }
        Workflow workflow = workflow(emptyGateway());

        List<LlmResult> results = await(workflow.runAsync(steps));

        assertThat(results).hasSize(10_000);
    }

    @Test
    public void abortedResultStopsRunAndIsIncludedInCompletedResults() {
        final AtomicInteger secondCalls = new AtomicInteger();
        AsyncStep first = (gateway, cancel) ->
                CompletableFuture.completedFuture(result("aborted", LlmStopReason.ABORTED));
        AsyncStep second = (gateway, cancel) -> {
            secondCalls.incrementAndGet();
            return CompletableFuture.completedFuture(result("never", LlmStopReason.STOP));
        };
        Workflow workflow = workflow(emptyGateway());

        WorkflowCancelledException ex = cancelled(
                workflow.runAsync(Arrays.asList(first, second)));

        assertThat(ex.getStepIndex()).isZero();
        assertThat(ex.getCompletedResults()).hasSize(1);
        assertThat(ex.getCompletedResults().get(0).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED);
        assertThat(secondCalls.get()).isZero();
    }

    @Test
    public void otherStopReasonsContinue() throws Exception {
        Workflow workflow = workflow(emptyGateway());
        List<AsyncStep> steps = Arrays.asList(
                (gateway, cancel) -> CompletableFuture.completedFuture(
                        result("a", LlmStopReason.MAX_ROUNDS)),
                (gateway, cancel) -> CompletableFuture.completedFuture(
                        result("b", LlmStopReason.LENGTH)),
                (gateway, cancel) -> CompletableFuture.completedFuture(
                        result("c", LlmStopReason.STOP)));

        List<LlmResult> results = await(workflow.runAsync(steps));

        assertThat(results).extracting("content").containsExactly("a", "b", "c");
    }

    // ---------------------------------------------------------------- 预校验与失败

    @Test
    public void invalidInputsFailSynchronously() {
        Workflow workflow = workflow(emptyGateway());

        assertThatThrownBy(() -> workflow.runAsync(null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("步骤不能为空");
        assertThatThrownBy(() -> workflow.runAsync(
                Arrays.asList((AsyncStep) null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("步骤不能为空");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void rawListWithSyncStepIsRejected() {
        Workflow workflow = workflow(emptyGateway());
        List raw = new ArrayList();
        raw.add(Workflow.oneStep(llmCall()));

        try {
            workflow.runAsync(raw);
            throw new AssertionError("expected rejection");
        } catch (LlmCallException ex) {
            assertThat(ex.getMessage()).contains("AsyncStep");
        }
    }

    @Test
    public void stepFailureCarriesIndexCauseAndCompletedResults() {
        Workflow workflow = workflow(emptyGateway());
        IllegalStateException cause = new IllegalStateException("boom");
        List<AsyncStep> steps = Arrays.asList(
                (gateway, cancel) -> CompletableFuture.completedFuture(
                        result("first", LlmStopReason.STOP)),
                (gateway, cancel) -> {
                    throw cause;
                });

        WorkflowStepException ex = failed(workflow.runAsync(steps));

        assertThat(ex.getStepIndex()).isEqualTo(1);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getCompletedResults()).extracting("content")
                .containsExactly("first");
        assertThatThrownBy(() -> ex.getCompletedResults()
                .add(result("x", LlmStopReason.STOP)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void nullFutureAndNullResultFailWithoutHanging() {
        Workflow workflow = workflow(emptyGateway());

        WorkflowStepException nullFuture = failed(workflow.runAsync(
                Collections.singletonList((gateway, cancel) -> null)));
        assertThat(nullFuture.getCause().getMessage()).contains("空 future");

        WorkflowStepException nullResult = failed(workflow.runAsync(
                Collections.singletonList((gateway, cancel) ->
                        CompletableFuture.<LlmResult>completedFuture(null))));
        assertThat(nullResult.getCause().getMessage()).contains("空结果");
    }

    @Test
    public void failedFutureWithCompletionExceptionIsUnwrapped() {
        Workflow workflow = workflow(emptyGateway());
        final IllegalStateException cause = new IllegalStateException("inner");
        AsyncStep step = (gateway, cancel) -> {
            CompletableFuture<LlmResult> future = new CompletableFuture<LlmResult>();
            future.completeExceptionally(new java.util.concurrent.CompletionException(cause));
            return future;
        };

        WorkflowStepException ex = failed(workflow.runAsync(Collections.singletonList(step)));

        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    public void errorIsNotWrapped() {
        Workflow workflow = workflow(emptyGateway());
        AsyncStep step = (gateway, cancel) -> {
            throw new BoomError();
        };

        Throwable cause = failure(workflow.runAsync(Collections.singletonList(step)));

        assertThat(cause).isInstanceOf(BoomError.class);
    }

    @Test
    public void errorCompletedFutureIsNotWrapped() {
        Workflow workflow = workflow(emptyGateway());
        CompletableFuture<LlmResult> failed = new CompletableFuture<LlmResult>();
        failed.completeExceptionally(new BoomError());
        AsyncStep step = (gateway, cancel) -> failed;

        Throwable cause = failure(workflow.runAsync(Collections.singletonList(step)));

        assertThat(cause).isInstanceOf(BoomError.class);
    }

    @Test
    public void cancelCompletionRaceHasSingleTerminal() throws Exception {
        for (int i = 0; i < 100; i++) {
            CompletableFuture<LlmResult> pending = new CompletableFuture<LlmResult>();
            Workflow workflow = workflow(emptyGateway());
            LlmCancelHandle caller = new LlmCancelHandle();
            CompletableFuture<List<LlmResult>> run = workflow.runAsync(
                    Collections.singletonList((gateway, cancel) -> pending), caller);

            Thread canceller = new Thread(caller::cancel, "race-cancel");
            Thread completer = new Thread(
                    () -> pending.complete(result("ok", LlmStopReason.STOP)),
                    "race-complete");
            canceller.start();
            completer.start();
            canceller.join(1000L);
            completer.join(1000L);

            try {
                assertThat(await(run)).extracting("content").containsExactly("ok");
            } catch (ExecutionException ex) {
                assertThat(ex.getCause()).isInstanceOf(WorkflowCancelledException.class);
            }
        }
    }

    @Test
    public void alreadyCancelledStepFutureIsTreatedAsCancellation() {
        Workflow workflow = workflow(emptyGateway());
        CompletableFuture<LlmResult> cancelled = new CompletableFuture<LlmResult>();
        cancelled.cancel(true);
        AsyncStep step = (gateway, cancel) -> cancelled;

        WorkflowCancelledException ex = cancelled(
                workflow.runAsync(Collections.singletonList(step)));

        assertThat(ex.getStepIndex()).isZero();
        assertThat(ex.getCause()).isInstanceOf(CancellationException.class);
    }

    // ---------------------------------------------------------------- 取消

    @Test
    public void preCancelledHandleReturnsFailedFuture() {
        Workflow workflow = workflow(emptyGateway());
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();

        WorkflowCancelledException ex = cancelled(workflow.runAsync(
                Collections.singletonList((gateway, handle) ->
                        CompletableFuture.completedFuture(result("never", LlmStopReason.STOP))),
                cancel));

        assertThat(ex.getStepIndex()).isZero();
        assertThat(ex.getCompletedResults()).isEmpty();
    }

    @Test
    public void handleCancelInFlightCancelsStepFutureAndRun() {
        CompletableFuture<LlmResult> pending = new CompletableFuture<LlmResult>();
        Workflow workflow = workflow(emptyGateway());
        LlmCancelHandle caller = new LlmCancelHandle();

        CompletableFuture<List<LlmResult>> run = workflow.runAsync(
                Collections.singletonList((gateway, cancel) -> pending), caller);
        caller.cancel();

        WorkflowCancelledException ex = cancelled(run);
        assertThat(ex.getStepIndex()).isZero();
        assertThat(pending.isCancelled()).isTrue();
    }

    @Test
    public void futureCancelCancelsCurrentStepAndMarkFutureCancelled() {
        CompletableFuture<LlmResult> pending = new CompletableFuture<LlmResult>();
        Workflow workflow = workflow(emptyGateway());

        CompletableFuture<List<LlmResult>> run = workflow.runAsync(
                Collections.singletonList((gateway, cancel) -> pending));
        assertThat(run.cancel(true)).isTrue();

        assertThat(run.isCancelled()).isTrue();
        assertThat(pending.isCancelled()).isTrue();
        assertThatThrownBy(() -> run.get(1, TimeUnit.SECONDS))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    public void lateCompletionAfterCancelDoesNotOverrideTerminal() {
        UncooperativeFuture pending = new UncooperativeFuture();
        Workflow workflow = workflow(emptyGateway());
        LlmCancelHandle caller = new LlmCancelHandle();

        CompletableFuture<List<LlmResult>> run = workflow.runAsync(
                Collections.singletonList((gateway, cancel) -> pending), caller);
        caller.cancel();

        WorkflowCancelledException ex = cancelled(run);
        assertThat(ex.getStepIndex()).isZero();

        assertThat(pending.complete(result("late", LlmStopReason.STOP))).isTrue();
        assertThat(cancelled(run).getCompletedResults()).isEmpty();
    }

    @Test
    public void cancelBetweenStepsStopsNextStep() {
        final AtomicInteger secondCalls = new AtomicInteger();
        final LlmCancelHandle caller = new LlmCancelHandle();
        AsyncStep first = (gateway, cancel) -> {
            caller.cancel();
            return CompletableFuture.completedFuture(result("first", LlmStopReason.STOP));
        };
        AsyncStep second = (gateway, cancel) -> {
            secondCalls.incrementAndGet();
            return CompletableFuture.completedFuture(result("second", LlmStopReason.STOP));
        };
        Workflow workflow = workflow(emptyGateway());

        WorkflowCancelledException ex = cancelled(
                workflow.runAsync(Arrays.asList(first, second), caller));

        assertThat(ex.getStepIndex()).isEqualTo(1);
        assertThat(ex.getCompletedResults()).extracting("content")
                .containsExactly("first");
        assertThat(secondCalls.get()).isZero();
    }

    @Test
    public void successfulRunDoesNotCancelCallerHandle() throws Exception {
        Workflow workflow = workflow(emptyGateway());
        LlmCancelHandle caller = new LlmCancelHandle();

        await(workflow.runAsync(Collections.singletonList(
                (gateway, cancel) -> CompletableFuture.completedFuture(
                        result("ok", LlmStopReason.STOP))), caller));

        assertThat(caller.isCancelled()).isFalse();
    }

    @Test
    public void cancelDoesNotAffectConcurrentRun() throws Exception {
        CompletableFuture<LlmResult> pending = new CompletableFuture<LlmResult>();
        Workflow workflow = workflow(emptyGateway());
        LlmCancelHandle cancelledHandle = new LlmCancelHandle();

        CompletableFuture<List<LlmResult>> cancelledRun = workflow.runAsync(
                Collections.singletonList((gateway, cancel) -> pending), cancelledHandle);
        CompletableFuture<List<LlmResult>> successfulRun = workflow.runAsync(
                Collections.singletonList((gateway, cancel) -> CompletableFuture.completedFuture(
                        result("ok", LlmStopReason.STOP))));

        cancelledHandle.cancel();

        assertThat(cancelled(cancelledRun).getStepIndex()).isZero();
        assertThat(await(successfulRun)).extracting("content").containsExactly("ok");
    }

    @Test
    public void lateUnbindOfPreviousStepDoesNotClearNextStepAbort() throws Exception {
        LateUnbindTransport transport = new LateUnbindTransport();
        Workflow workflow = workflow(gateway(transport));
        LlmCancelHandle caller = new LlmCancelHandle();

        CompletableFuture<List<LlmResult>> run = workflow.runAsync(Arrays.asList(
                Workflow.oneStepAsync(llmCall()),
                Workflow.oneStepAsync(llmCall())), caller);

        assertThat(transport.secondBound.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(transport.firstUnbound.await(5, TimeUnit.SECONDS)).isTrue();

        caller.cancel();

        assertThat(transport.aborted.await(5, TimeUnit.SECONDS)).isTrue();
        WorkflowCancelledException ex = cancelled(run);
        // 第一步成功、第二步被取消并返回 ABORTED；两者都进入已完成结果，与同步版一致。
        assertThat(ex.getCompletedResults()).hasSize(2);
        assertThat(ex.getCompletedResults().get(1).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED);
    }

    // ---------------------------------------------------------------- 单步与适配

    @Test
    public void executeAsyncReturnsSingleResult() throws Exception {
        Workflow workflow = workflow(emptyGateway());

        LlmResult result = await(workflow.executeAsync(
                (gateway, cancel) -> CompletableFuture.completedFuture(
                        result("single", LlmStopReason.STOP))));

        assertThat(result.getContent()).isEqualTo("single");
    }

    @Test
    public void executeAsyncRejectsNullStep() {
        Workflow workflow = workflow(emptyGateway());

        assertThatThrownBy(() -> workflow.executeAsync(null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("步骤不能为空");
    }

    @Test
    public void executeAsyncFailureCarriesStepZero() {
        Workflow workflow = workflow(emptyGateway());

        WorkflowStepException ex = failed(workflow.executeAsync(
                (gateway, cancel) -> {
                    throw new IllegalStateException("boom");
                }));

        assertThat(ex.getStepIndex()).isZero();
    }

    @Test
    public void executeAsyncFutureCancelPropagatesToStep() {
        CompletableFuture<LlmResult> pending = new CompletableFuture<LlmResult>();
        Workflow workflow = workflow(emptyGateway());

        CompletableFuture<LlmResult> single = workflow.executeAsync(
                (gateway, cancel) -> pending);
        assertThat(single.cancel(true)).isTrue();

        assertThat(single.isCancelled()).isTrue();
        assertThat(pending.isCancelled()).isTrue();
    }

    @Test
    public void adaptRunsSyncStep() throws Exception {
        Workflow workflow = workflow(emptyGateway());
        SyncStep sync = gateway -> result("adapted", LlmStopReason.STOP);

        LlmResult result = await(workflow.executeAsync(Workflow.adapt(sync)));

        assertThat(result.getContent()).isEqualTo("adapted");
    }

    @Test
    public void adaptRejectionBecomesStepFailure() {
        ExecutorProvider rejecting = new ExecutorProvider() {
            @Override
            public Executor io() {
                return command -> {
                    throw new RejectedExecutionException("no capacity");
                };
            }

            @Override
            public void close() {
            }
        };
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.<LlmTransport>emptyList(), new NoopLlmCallStore(),
                rejecting, new SequentialToolExecutor());
        Workflow workflow = new Workflow(gateway);
        SyncStep sync = g -> result("never", LlmStopReason.STOP);

        WorkflowStepException ex = failed(workflow.executeAsync(Workflow.adapt(sync)));

        assertThat(ex.getCause()).isInstanceOf(LlmCallException.class);
        assertThat(ex.getCause().getMessage()).contains("同步步骤适配被拒绝");
    }

    // ---------------------------------------------------------------- 重试步骤

    @Test
    public void retryStepUsesOneWithRetryAsync() throws Exception {
        ScriptedAsyncTransport transport = new ScriptedAsyncTransport()
                .fail(transportError()).succeed("ok");
        RecordingStore store = new RecordingStore();
        Workflow workflow = workflow(gateway(transport, store));

        LlmResult result = await(workflow.executeAsync(
                Workflow.oneStepWithRetryAsync(llmCall(), policy(2))));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(transport.requests).hasSize(2);
        assertThat(store.records).hasSize(2);
    }

    @Test
    public void retryStepCancelDuringBackoffStopsNextAttempt() throws Exception {
        ScriptedAsyncTransport transport = new ScriptedAsyncTransport()
                .fail(transportError());
        Workflow workflow = workflow(gateway(transport, new RecordingStore()));
        LlmCancelHandle caller = new LlmCancelHandle();
        RetryPolicy slow = RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(2000L).maxBackoffMs(2000L)
                .jitter(false).build();

        CompletableFuture<LlmResult> run = workflow.executeAsync(
                Workflow.oneStepWithRetryAsync(llmCall(), slow), caller);
        assertThat(transport.firstRequest.await(5, TimeUnit.SECONDS)).isTrue();
        caller.cancel();

        Throwable cause = failure(run);
        assertThat(cause).isInstanceOf(WorkflowCancelledException.class);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void retryStepRejectsNullPolicyAtFactoryTime() {
        assertThatThrownBy(() -> Workflow.oneStepWithRetryAsync(llmCall(), null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("重试策略不能为空");
    }

    // ---------------------------------------------------------------- 工具

    private static Workflow workflow(LlmGateway gateway) {
        return new Workflow(gateway);
    }

    private static LlmGateway emptyGateway() {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.<LlmTransport>emptyList(), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmGateway gateway(LlmTransport transport) {
        return gateway(transport, new RecordingStore());
    }

    private static LlmGateway gateway(LlmTransport transport, LlmCallStore store) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), store,
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmCall llmCall() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    private static LlmResult result(String content, LlmStopReason stop) {
        return new LlmResult(content, null, stop, LlmUsage.zero(), "test", "m",
                LlmThinkingLevel.OFF, 0L, 1, null);
    }

    private static RetryPolicy policy(int attempts) {
        return RetryPolicy.builder()
                .maxAttempts(attempts).initialBackoffMs(0L).maxBackoffMs(0L)
                .jitter(false).build();
    }

    private static LlmCompletion stop(String content) {
        return new LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.zero(), null);
    }

    private static LlmCallException transportError() {
        return new LlmCallException("boom", new IOException("io"),
                LlmCallException.Kind.TRANSPORT);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static Throwable failure(CompletableFuture<?> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected failure");
        } catch (ExecutionException ex) {
            return ex.getCause();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static WorkflowCancelledException cancelled(CompletableFuture<?> future) {
        Throwable cause = failure(future);
        assertThat(cause).isInstanceOf(WorkflowCancelledException.class);
        return (WorkflowCancelledException) cause;
    }

    private static WorkflowStepException failed(CompletableFuture<?> future) {
        Throwable cause = failure(future);
        assertThat(cause).isInstanceOf(WorkflowStepException.class);
        return (WorkflowStepException) cause;
    }

    private static final class UncooperativeFuture extends CompletableFuture<LlmResult> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }

    private static final class BoomError extends Error {
    }

    // ---------------------------------------------------------------- 传输与存储

    /**
     * 模拟 OkHttp 的“先完成 future、稍后再 unbind”顺序：第一次请求的晚到 unbind
     * 不能清掉第二次请求的 abort 绑定。
     */
    private static final class LateUnbindTransport implements LlmTransport {

        final CountDownLatch secondBound = new CountDownLatch(1);
        final CountDownLatch firstUnbound = new CountDownLatch(1);
        final CountDownLatch aborted = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();

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
            final int number = requests.incrementAndGet();
            final CompletableFuture<LlmCompletion> future =
                    new CompletableFuture<LlmCompletion>();
            final LlmCancelHandle cancel = request.getCancel();
            if (number == 1) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        future.complete(stop("one"));
                        try {
                            secondBound.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        cancel.bindAbort(null);
                        firstUnbound.countDown();
                    }
                }, "late-unbind").start();
                return future;
            }
            cancel.bindAbort(new Runnable() {
                @Override
                public void run() {
                    aborted.countDown();
                    future.complete(new LlmCompletion("", null, LlmStopReason.ABORTED,
                            LlmUsage.zero(), null));
                }
            });
            secondBound.countDown();
            return future;
        }
    }

    private static final class ScriptedAsyncTransport implements LlmTransport {

        final List<LlmRequest> requests = new CopyOnWriteArrayList<LlmRequest>();
        final CountDownLatch firstRequest = new CountDownLatch(1);
        private final Deque<Object> outcomes = new ArrayDeque<Object>();

        ScriptedAsyncTransport succeed(String content) {
            outcomes.addLast(stop(content));
            return this;
        }

        ScriptedAsyncTransport fail(LlmCallException error) {
            outcomes.addLast(error);
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
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            requests.add(request);
            firstRequest.countDown();
            Object outcome = outcomes.isEmpty() ? stop("") : outcomes.removeFirst();
            CompletableFuture<LlmCompletion> future = new CompletableFuture<LlmCompletion>();
            if (outcome instanceof LlmCallException) {
                future.completeExceptionally((LlmCallException) outcome);
            } else {
                future.complete((LlmCompletion) outcome);
            }
            return future;
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
