package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workflow 夯实用例：预校验、取消、失败定位、不可变结果与兼容性。
 */
public class WorkflowHardeningTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    private final ExecutorService threads = Executors.newCachedThreadPool();

    @After
    public void tearDown() {
        threads.shutdownNow();
    }

    // ---------------------------------------------------------------- 预校验

    @Test
    public void nullListIsRejected() {
        Workflow workflow = workflow(new CapturingTransport());

        assertThatThrownBy(() -> workflow.run(null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("步骤不能为空");
    }

    @Test
    public void emptyListReturnsImmutableEmptyListWithoutRequests() {
        CapturingTransport transport = new CapturingTransport();
        Workflow workflow = workflow(transport);

        List<LlmResult> results = workflow.run(Collections.emptyList());

        assertThat(results).isEmpty();
        assertThat(transport.requests).isEmpty();
        assertThatThrownBy(() -> results.add(result("x", LlmStopReason.STOP)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void nullElementIsRejectedBeforeAnyRequest() {
        CapturingTransport transport = new CapturingTransport();
        Workflow workflow = workflow(transport);

        assertThatThrownBy(() -> workflow.run(Arrays.asList(
                Workflow.oneStep(llmCall()), null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("步骤");

        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void nonStepIsRejectedBeforeAnyRequest() {
        CapturingTransport transport = new CapturingTransport();
        Workflow workflow = workflow(transport);

        assertThatThrownBy(() -> workflow.run(Arrays.asList(
                Workflow.oneStep(llmCall()), "not a step")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("步骤");

        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void agentLoopAtEndIsRejectedBeforeAnyRequest() {
        CapturingTransport transport = new CapturingTransport();
        LlmGateway gateway = gateway(transport);
        Workflow workflow = new Workflow(gateway);
        AgentLoop loop = new AgentLoop(gateway);

        assertThatThrownBy(() -> workflow.run(Arrays.asList(
                Workflow.oneStep(llmCall()), loop)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("Agent loop");

        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void structureErrorWinsOverPreCancellation() {
        Workflow workflow = workflow(new CapturingTransport());
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();

        assertThatThrownBy(() -> workflow.run(Arrays.asList(
                Workflow.oneStep(llmCall()), null), cancel))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("步骤");
    }

    @Test
    public void executeNullUsesStepSpecificMessage() {
        Workflow workflow = workflow(new CapturingTransport());

        assertThatThrownBy(() -> workflow.execute(null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("步骤不能为空");
    }

    @Test
    public void stepFactoriesRejectNullCall() {
        assertThatThrownBy(() -> Workflow.oneStep(null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("调用不能为空");
        assertThatThrownBy(() -> Workflow.toolLoopStep(null, Collections.<LlmTool>emptyList(), 1))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("调用不能为空");
    }

    // ---------------------------------------------------------------- 取消

    @Test
    public void preCancelledRunStopsBeforeFirstStep() {
        CapturingTransport transport = new CapturingTransport();
        Workflow workflow = workflow(transport);
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();

        WorkflowCancelledException ex = cancelled(() -> workflow.run(
                Arrays.asList(Workflow.oneStep(llmCall()), Workflow.oneStep(llmCall())), cancel));

        assertThat(ex.getStepIndex()).isZero();
        assertThat(ex.getCompletedResults()).isEmpty();
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void cancelBetweenStepsStopsNextStep() {
        CapturingTransport transport = new CapturingTransport();
        Workflow workflow = workflow(transport);
        final LlmCancelHandle cancel = new LlmCancelHandle();

        SyncStep first = new SyncStep() {
            @Override
            public LlmResult execute(LlmGateway gateway) {
                cancel.cancel();
                return result("first", LlmStopReason.STOP);
            }
        };
        SyncStep second = new SyncStep() {
            @Override
            public LlmResult execute(LlmGateway gateway) {
                throw new AssertionError("第二步不应执行");
            }
        };

        WorkflowCancelledException ex =
                cancelled(() -> workflow.run(Arrays.asList(first, second), cancel));

        assertThat(ex.getStepIndex()).isEqualTo(1);
        assertThat(ex.getCompletedResults()).extracting("content").containsExactly("first");
    }

    @Test
    public void inFlightCancelAbortsTransportAndStopsRun() throws Exception {
        AbortAwareTransport transport = new AbortAwareTransport();
        final Workflow workflow = workflow(transport);
        final LlmCancelHandle cancel = new LlmCancelHandle();

        Future<List<LlmResult>> future = threads.submit(new Callable<List<LlmResult>>() {
            @Override
            public List<LlmResult> call() {
                return workflow.run(Collections.singletonList(Workflow.oneStep(llmCall())), cancel);
            }
        });
        assertThat(transport.started.await(5, TimeUnit.SECONDS)).isTrue();
        cancel.cancel();

        WorkflowCancelledException ex = cancelledFuture(future);
        assertThat(ex.getStepIndex()).isZero();
        assertThat(ex.getCompletedResults()).hasSize(1);
        assertThat(ex.getCompletedResults().get(0).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.aborted.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void abortedResultStopsRunEvenWithoutHandle() {
        Workflow workflow = workflow(new CapturingTransport());

        WorkflowCancelledException ex = cancelled(() -> workflow.run(Arrays.asList(
                step(result("aborted", LlmStopReason.ABORTED)),
                step(result("never", LlmStopReason.STOP)))));

        assertThat(ex.getStepIndex()).isZero();
        assertThat(ex.getCompletedResults()).hasSize(1);
        assertThat(ex.getCompletedResults().get(0).getStopReason())
                .isEqualTo(LlmStopReason.ABORTED);
    }

    @Test
    public void failureWhileCancelledIsClassifiedAsCancellation() {
        Workflow workflow = workflow(new CapturingTransport());
        final LlmCancelHandle cancel = new LlmCancelHandle();

        SyncStep first = step(result("first", LlmStopReason.STOP));
        SyncStep second = new SyncStep() {
            @Override
            public LlmResult execute(LlmGateway gateway) {
                cancel.cancel();
                throw new IllegalStateException("boom");
            }
        };

        WorkflowCancelledException ex =
                cancelled(() -> workflow.run(Arrays.asList(first, second), cancel));

        assertThat(ex.getStepIndex()).isEqualTo(1);
        assertThat(ex.getCompletedResults()).hasSize(1);
        assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void otherStopReasonsContinueExecution() {
        Workflow workflow = workflow(new CapturingTransport());

        List<LlmResult> results = workflow.run(Arrays.asList(
                step(result("a", LlmStopReason.MAX_ROUNDS)),
                step(result("b", LlmStopReason.LENGTH)),
                step(result("c", LlmStopReason.STOP))));

        assertThat(results).extracting("content").containsExactly("a", "b", "c");
    }

    @Test
    public void executePassesCancelHandleToBuiltInStep() throws Exception {
        AbortAwareTransport transport = new AbortAwareTransport();
        final Workflow workflow = workflow(transport);
        final LlmCancelHandle cancel = new LlmCancelHandle();

        Future<LlmResult> future = threads.submit(new Callable<LlmResult>() {
            @Override
            public LlmResult call() {
                return workflow.execute(Workflow.oneStep(llmCall()), cancel);
            }
        });
        assertThat(transport.started.await(5, TimeUnit.SECONDS)).isTrue();
        cancel.cancel();

        LlmResult result = future.get(5, TimeUnit.SECONDS);
        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.aborted.await(5, TimeUnit.SECONDS)).isTrue();
    }

    // ---------------------------------------------------------------- 失败定位

    @Test
    public void firstStepFailureCarriesIndexAndCause() {
        Workflow workflow = workflow(new CapturingTransport());
        final IllegalStateException cause = new IllegalStateException("prompt=SECRET");

        WorkflowStepException ex = failed(() -> workflow.run(Arrays.asList(
                failing(cause),
                step(result("never", LlmStopReason.STOP)))));

        assertThat(ex.getStepIndex()).isZero();
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getCompletedResults()).isEmpty();
        assertThat(ex.getMessage()).doesNotContain("SECRET");
    }

    @Test
    public void secondStepFailureCarriesCompletedResults() {
        Workflow workflow = workflow(new CapturingTransport());

        WorkflowStepException ex = failed(() -> workflow.run(Arrays.asList(
                step(result("first", LlmStopReason.STOP)),
                failing(new IllegalArgumentException("boom")))));

        assertThat(ex.getStepIndex()).isEqualTo(1);
        assertThat(ex.getCompletedResults()).extracting("content").containsExactly("first");
    }

    @Test
    public void nullStepResultIsTreatedAsFailure() {
        Workflow workflow = workflow(new CapturingTransport());

        WorkflowStepException ex = failed(() -> workflow.run(Arrays.asList(
                step(null))));

        assertThat(ex.getStepIndex()).isZero();
        assertThat(ex.getCause().getMessage()).contains("空结果");
    }

    @Test
    public void errorIsNotWrapped() {
        Workflow workflow = workflow(new CapturingTransport());

        assertThatThrownBy(() -> workflow.run(Collections.singletonList(
                new SyncStep() {
                    @Override
                    public LlmResult execute(LlmGateway gateway) {
                        throw new BoomError();
                    }
                })))
                .isInstanceOf(BoomError.class);
    }

    @Test
    public void completedResultsSnapshotsAreImmutable() {
        Workflow workflow = workflow(new CapturingTransport());

        WorkflowStepException ex = failed(() -> workflow.run(Arrays.asList(
                step(result("first", LlmStopReason.STOP)),
                failing(new IllegalStateException("boom")))));

        assertThatThrownBy(() -> ex.getCompletedResults()
                .add(result("x", LlmStopReason.STOP)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------- 兼容与并发

    @Test
    public void legacyCustomStepStillWorks() {
        Workflow workflow = workflow(new CapturingTransport());
        SyncStep legacy = new SyncStep() {
            @Override
            public LlmResult execute(LlmGateway gateway) {
                return result("legacy", LlmStopReason.STOP);
            }
        };

        List<LlmResult> results = workflow.run(Collections.singletonList(legacy));

        assertThat(results).extracting("content").containsExactly("legacy");
    }

    @Test
    public void concurrentRunsDoNotShareState() throws Exception {
        final Workflow workflow = workflow(new CapturingTransport());
        final CountDownLatch start = new CountDownLatch(1);

        Callable<LlmResult> taskA = labelled(workflow, start, "A");
        Callable<LlmResult> taskB = labelled(workflow, start, "B");
        Future<LlmResult> a = threads.submit(taskA);
        Future<LlmResult> b = threads.submit(taskB);
        start.countDown();

        assertThat(a.get(5, TimeUnit.SECONDS).getContent()).isEqualTo("A");
        assertThat(b.get(5, TimeUnit.SECONDS).getContent()).isEqualTo("B");
    }

    @Test
    public void stepFactoryDefaultCancelIsUsedWhenRunHasNoHandle() {
        CapturingTransport transport = new CapturingTransport();
        Workflow workflow = workflow(transport);
        LlmCancelHandle stepCancel = new LlmCancelHandle();
        stepCancel.cancel();

        WorkflowCancelledException ex = cancelled(() -> workflow.run(
                Collections.singletonList(Workflow.oneStep(llmCall(), stepCancel))));

        assertThat(ex.getStepIndex()).isZero();
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void oneStepSnapshotsCallTimeouts() {
        CapturingTransport transport = new CapturingTransport();
        Workflow workflow = workflow(transport);
        LlmCall call = llmCall().timeouts(111, 222);
        SyncStep step = Workflow.oneStep(call);
        call.timeouts(999, 999);

        workflow.run(Collections.singletonList(step));

        assertThat(transport.requests).hasSize(1);
        assertThat(transport.requests.get(0).effectiveConnectTimeoutMs()).isEqualTo(111);
        assertThat(transport.requests.get(0).effectiveReadTimeoutMs()).isEqualTo(222);
    }

    // ---------------------------------------------------------------- 工具

    private static Callable<LlmResult> labelled(final Workflow workflow,
                                                 final CountDownLatch start,
                                                 final String label) {
        return new Callable<LlmResult>() {
            @Override
            public LlmResult call() throws Exception {
                start.await(5, TimeUnit.SECONDS);
                return workflow.run(Collections.singletonList(
                        step(result(label, LlmStopReason.STOP)))).get(0);
            }
        };
    }

    private static Workflow workflow(LlmTransport transport) {
        return new Workflow(gateway(transport));
    }

    private static LlmGateway gateway(LlmTransport transport) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmCall llmCall() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    private static LlmResult result(String content, LlmStopReason stop) {
        return new LlmResult(content, null, stop, LlmUsage.zero(), "test", "m",
                LlmThinkingLevel.OFF, 0L, 1, null);
    }

    private static SyncStep step(final LlmResult result) {
        return new SyncStep() {
            @Override
            public LlmResult execute(LlmGateway gateway) {
                return result;
            }
        };
    }

    private static SyncStep failing(final RuntimeException error) {
        return new SyncStep() {
            @Override
            public LlmResult execute(LlmGateway gateway) {
                throw error;
            }
        };
    }

    private static WorkflowCancelledException cancelled(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected cancellation");
        } catch (WorkflowCancelledException ex) {
            return ex;
        }
    }

    private static WorkflowCancelledException cancelledFuture(Future<List<LlmResult>> future)
            throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("expected cancellation");
        } catch (java.util.concurrent.ExecutionException ex) {
            assertThat(ex.getCause()).isInstanceOf(WorkflowCancelledException.class);
            return (WorkflowCancelledException) ex.getCause();
        }
    }

    private static WorkflowStepException failed(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected failure");
        } catch (WorkflowStepException ex) {
            return ex;
        }
    }

    /**
     * 成功路径的捕获传输：记录请求并返回 STOP。
     */
    private static final class CapturingTransport implements LlmTransport {

        final List<LlmRequest> requests = new CopyOnWriteArrayList<LlmRequest>();

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            return new LlmCompletion("ok", null, LlmStopReason.STOP, LlmUsage.zero(), null);
        }
    }

    /**
     * 在途取消可见的传输：complete 阻塞到 abort 或超时。
     */
    private static final class AbortAwareTransport implements LlmTransport {

        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch aborted = new CountDownLatch(1);

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            started.countDown();
            final LlmCancelHandle cancel = request.getCancel();
            if (cancel == null) {
                throw new LlmCallException("测试需要取消句柄");
            }
            final CountDownLatch release = new CountDownLatch(1);
            cancel.bindAbort(new Runnable() {
                @Override
                public void run() {
                    aborted.countDown();
                    release.countDown();
                }
            });
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new LlmCallException("测试等待取消超时");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new LlmCallException("测试被中断", ex);
            }
            throw new LlmCallException("调用已取消", null, LlmCallException.Kind.CANCELLED);
        }
    }

    private static final class BoomError extends Error {
    }
}
