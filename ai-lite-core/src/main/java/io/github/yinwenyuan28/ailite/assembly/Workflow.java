package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.RetryPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 单次调用和简单工具循环的顺序执行器。步骤不能是 Agent loop。
 *
 * <p>同步路径使用 {@link SyncStep}，异步路径使用 {@link AsyncStep}；两种类型不互相继承，
 * 调用方必须显式声明步骤是同步还是异步。Workflow 只组合、不做决策：不规划、不分支、
 * 不自动重试、不并行。</p>
 *
 * <p>本类无状态，可并发使用；但并发的 {@code run} / {@code runAsync} 必须各自持有
 * 取消句柄。</p>
 */
public final class Workflow {

    private final LlmGateway gateway;

    public Workflow(LlmGateway gateway) {
        if (gateway == null) {
            throw new LlmCallException("网关不能为空");
        }
        this.gateway = gateway;
    }

    public LlmResult one(LlmCall call) {
        return gateway.one(call);
    }

    /**
     * 直通单次调用，并把取消句柄透传给网关。
     *
     * @param call   调用参数
     * @param cancel 取消句柄，可为 null
     * @return 网关结果
     */
    public LlmResult one(LlmCall call, LlmCancelHandle cancel) {
        return gateway.one(call, cancel);
    }

    public LlmResult oneByStream(LlmCall call, Consumer<String> onDelta, LlmCancelHandle cancel) {
        return gateway.oneByStream(call, onDelta, cancel);
    }

    public LlmResult toolLoop(LlmCall call, List<LlmTool> tools, Integer maxRounds) {
        return gateway.toolLoop(call, tools, maxRounds, null);
    }

    /**
     * 直通工具循环，并把取消句柄透传给网关。
     *
     * @param call      调用参数
     * @param tools     工具列表
     * @param maxRounds 最大轮数
     * @param cancel    取消句柄，可为 null
     * @return 网关结果
     */
    public LlmResult toolLoop(LlmCall call, List<LlmTool> tools, Integer maxRounds,
                              LlmCancelHandle cancel) {
        return gateway.toolLoop(call, tools, maxRounds, cancel);
    }

    public LlmResult toolLoop(LlmCall call, List<LlmTool> tools) {
        return toolLoop(call, tools, null);
    }

    public LlmResult toolLoopByStream(LlmCall call, List<LlmTool> tools, Integer maxRounds,
                                      Consumer<String> onDelta, LlmCancelHandle cancel) {
        return gateway.toolLoopByStream(call, tools, maxRounds, onDelta, cancel);
    }

    // ---------------------------------------------------------------- 同步执行

    /**
     * 按写好的顺序执行同步步骤。放进 Agent loop 时直接拒绝，不发请求。
     *
     * @param steps 步骤列表
     * @return 每步结果，不可变列表
     */
    public List<LlmResult> run(List<?> steps) {
        return run(steps, null);
    }

    /**
     * 按写好的顺序执行同步步骤，并支持整体取消。
     *
     * <p>执行前先做不可变快照和整表结构校验：任何非法元素都会在第一步请求前失败，
     * 且结构错误优先于预取消。传入 null 句柄等价于不可取消。</p>
     *
     * @param steps  步骤列表
     * @param cancel 取消句柄，可为 null
     * @return 每步结果，不可变列表
     * @throws WorkflowCancelledException 取消，或某步返回 {@code ABORTED}
     * @throws WorkflowStepException      某步抛出异常或返回 null
     */
    public List<LlmResult> run(List<?> steps, LlmCancelHandle cancel) {
        if (steps == null) {
            throw new LlmCallException("Workflow 步骤不能为空");
        }
        List<Object> snapshot = new ArrayList<Object>(steps.size());
        for (Object step : steps) {
            snapshot.add(step);
        }
        validateSyncSteps(snapshot);
        if (snapshot.isEmpty()) {
            // 空列表不检查取消：没有步骤可取消，也没有请求会发出。
            return Collections.emptyList();
        }
        if (cancel != null && cancel.isCancelled()) {
            throw new WorkflowCancelledException(0, Collections.<LlmResult>emptyList());
        }

        List<LlmResult> results = new ArrayList<LlmResult>();
        for (int i = 0; i < snapshot.size(); i++) {
            if (cancel != null && cancel.isCancelled()) {
                throw new WorkflowCancelledException(i, immutable(results));
            }
            LlmResult result;
            try {
                result = execute(snapshot.get(i), cancel);
            } catch (WorkflowCancelledException ex) {
                throw ex;
            } catch (WorkflowStepException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new WorkflowCancelledException(i, immutable(results), ex);
                }
                throw new WorkflowStepException(i, immutable(results), ex);
            }
            if (result == null) {
                throw new WorkflowStepException(i, immutable(results),
                        new LlmCallException("步骤返回了空结果"));
            }
            results.add(result);
            if (result.getStopReason() == LlmStopReason.ABORTED) {
                // ABORTED 具有权威性：即使没有句柄，也视为取消并停止后续步骤。
                throw new WorkflowCancelledException(i, immutable(results));
            }
        }
        return immutable(results);
    }

    /**
     * 执行单个同步步骤。
     *
     * @param step 步骤对象
     * @return 步骤结果
     */
    public LlmResult execute(Object step) {
        return execute(step, null);
    }

    /**
     * 执行单个同步步骤，并把取消句柄透传给内置步骤。
     *
     * @param step   步骤对象
     * @param cancel 取消句柄，可为 null
     * @return 步骤结果
     */
    public LlmResult execute(Object step, LlmCancelHandle cancel) {
        if (step == null) {
            throw new LlmCallException("步骤不能为空");
        }
        validateSyncStep(step);
        return ((SyncStep) step).execute(gateway, cancel);
    }

    // ---------------------------------------------------------------- 异步执行

    /**
     * 按写好的顺序执行异步步骤。
     *
     * <p>严格顺序执行；等待模型时不占调用线程。返回的 future 可能由步骤完成的
     * HTTP 回调线程完成。`AsyncStep` 必须快速返回 future，不能阻塞。</p>
     *
     * @param steps 异步步骤列表
     * @return 每步结果的 future，结果为不可变列表
     */
    public CompletableFuture<List<LlmResult>> runAsync(List<? extends AsyncStep> steps) {
        return runAsync(steps, null);
    }

    /**
     * 按写好的顺序执行异步步骤，并支持整体取消。
     *
     * <p>非法输入同步抛出；预取消、步骤失败、取消通过 future 表达。每一步使用独立
     * 的取消句柄；调用方句柄只作为“取消整个 run”的转发器。</p>
     *
     * @param steps  异步步骤列表
     * @param cancel 调用方取消句柄，可为 null
     * @return 每步结果的 future，结果为不可变列表
     */
    public CompletableFuture<List<LlmResult>> runAsync(List<? extends AsyncStep> steps,
                                                       LlmCancelHandle cancel) {
        if (steps == null) {
            throw new LlmCallException("Workflow 步骤不能为空");
        }
        List<AsyncStep> snapshot = new ArrayList<AsyncStep>(steps.size());
        List<?> raw = steps;
        for (Object element : raw) {
            if (element == null) {
                throw new LlmCallException("Workflow 步骤不能为空");
            }
            if (!(element instanceof AsyncStep)) {
                throw new LlmCallException("Workflow 异步步骤必须实现 AsyncStep；"
                        + "同步步骤请使用 run() 或 Workflow.adapt(...)");
            }
            snapshot.add((AsyncStep) element);
        }
        if (snapshot.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.<LlmResult>emptyList());
        }
        if (cancel != null && cancel.isCancelled()) {
            return failedFuture(new WorkflowCancelledException(
                    0, Collections.<LlmResult>emptyList()));
        }
        return new AsyncWorkflowRun(snapshot, cancel).start();
    }

    /**
     * 执行单个异步步骤。
     *
     * @param step 异步步骤
     * @return 步骤结果的 future
     */
    public CompletableFuture<LlmResult> executeAsync(AsyncStep step) {
        return executeAsync(step, null);
    }

    /**
     * 执行单个异步步骤，并支持整体取消。
     *
     * @param step   异步步骤
     * @param cancel 取消句柄，可为 null
     * @return 步骤结果的 future
     */
    public CompletableFuture<LlmResult> executeAsync(AsyncStep step, LlmCancelHandle cancel) {
        if (step == null) {
            throw new LlmCallException("步骤不能为空");
        }
        CompletableFuture<List<LlmResult>> run =
                runAsync(Collections.singletonList(step), cancel);
        final CompletableFuture<LlmResult> single = new CompletableFuture<LlmResult>();
        run.whenComplete((results, error) -> {
            if (error != null) {
                single.completeExceptionally(error);
            } else {
                single.complete(results.get(0));
            }
        });
        // 取消单步 future 时，把取消传播到整个 run，避免底层请求继续执行。
        single.whenComplete((value, error) -> {
            if (single.isCancelled()) {
                run.cancel(true);
            }
        });
        return single;
    }

    // ---------------------------------------------------------------- 同步工厂

    /**
     * 单次调用步骤。创建时对 {@link LlmCall} 做快照，避免调用方后续修改超时。
     *
     * @param call 调用参数
     * @return 同步步骤
     */
    public static SyncStep oneStep(LlmCall call) {
        return oneStep(call, null);
    }

    /**
     * 单次调用步骤，并携带一个默认取消句柄。
     *
     * <p>通过 {@link #run(List, LlmCancelHandle)} / {@link #execute(Object, LlmCancelHandle)}
     * 执行时，若外层传入了句柄则以外层为准；否则使用这里捕获的句柄。</p>
     *
     * @param call   调用参数
     * @param cancel 默认取消句柄，可为 null
     * @return 同步步骤
     */
    public static SyncStep oneStep(LlmCall call, LlmCancelHandle cancel) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        final LlmCall snapshot = call.copy();
        final LlmCancelHandle defaultCancel = cancel;
        return new SyncStep() {
            @Override
            public LlmResult execute(LlmGateway gateway) {
                return gateway.one(snapshot, defaultCancel);
            }

            @Override
            public LlmResult execute(LlmGateway gateway, LlmCancelHandle runCancel) {
                return gateway.one(snapshot,
                        runCancel != null ? runCancel : defaultCancel);
            }
        };
    }

    public static SyncStep toolLoopStep(LlmCall call, List<LlmTool> tools, Integer maxRounds) {
        return toolLoopStep(call, tools, maxRounds, null);
    }

    /**
     * 工具循环步骤。创建时对调用参数和工具列表做浅拷贝。
     *
     * @param call      调用参数
     * @param tools     工具列表
     * @param maxRounds 最大轮数
     * @param cancel    默认取消句柄，可为 null
     * @return 同步步骤
     */
    public static SyncStep toolLoopStep(LlmCall call, List<LlmTool> tools, Integer maxRounds,
                                        LlmCancelHandle cancel) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        final LlmCall callSnapshot = call.copy();
        final List<LlmTool> toolsSnapshot = tools == null ? null
                : Collections.unmodifiableList(new ArrayList<LlmTool>(tools));
        final LlmCancelHandle defaultCancel = cancel;
        return new SyncStep() {
            @Override
            public LlmResult execute(LlmGateway gateway) {
                return gateway.toolLoop(callSnapshot, toolsSnapshot, maxRounds, defaultCancel);
            }

            @Override
            public LlmResult execute(LlmGateway gateway, LlmCancelHandle runCancel) {
                return gateway.toolLoop(callSnapshot, toolsSnapshot, maxRounds,
                        runCancel != null ? runCancel : defaultCancel);
            }
        };
    }

    // ---------------------------------------------------------------- 异步工厂

    /**
     * 单次调用异步步骤。
     *
     * @param call 调用参数
     * @return 异步步骤
     */
    public static AsyncStep oneStepAsync(LlmCall call) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        final LlmCall snapshot = call.copy();
        return new AsyncStep() {
            @Override
            public CompletableFuture<LlmResult> executeAsync(LlmGateway gateway,
                                                            LlmCancelHandle cancel) {
                return gateway.oneAsync(snapshot, cancel);
            }
        };
    }

    /**
     * 工具循环异步步骤。
     *
     * @param call      调用参数
     * @param tools     工具列表
     * @param maxRounds 最大轮数
     * @return 异步步骤
     */
    public static AsyncStep toolLoopStepAsync(LlmCall call, List<LlmTool> tools,
                                              Integer maxRounds) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        final LlmCall callSnapshot = call.copy();
        final List<LlmTool> toolsSnapshot = tools == null ? null
                : Collections.unmodifiableList(new ArrayList<LlmTool>(tools));
        return new AsyncStep() {
            @Override
            public CompletableFuture<LlmResult> executeAsync(LlmGateway gateway,
                                                            LlmCancelHandle cancel) {
                return gateway.toolLoopAsync(callSnapshot, toolsSnapshot, maxRounds, cancel);
            }
        };
    }

    /**
     * 带显式重试的单次调用异步步骤。重试策略只作用于这一步。
     *
     * <p>请求数为 {@code 1..policy.maxAttempts}；每次尝试独立写调用记录，重试会重复计费。</p>
     *
     * @param call   调用参数
     * @param policy 重试策略
     * @return 异步步骤
     */
    public static AsyncStep oneStepWithRetryAsync(LlmCall call, RetryPolicy policy) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        if (policy == null) {
            throw new LlmCallException("重试策略不能为空", null,
                    LlmCallException.Kind.CONFIG);
        }
        final LlmCall snapshot = call.copy();
        final RetryPolicy retry = policy;
        return new AsyncStep() {
            @Override
            public CompletableFuture<LlmResult> executeAsync(LlmGateway gateway,
                                                            LlmCancelHandle cancel) {
                return gateway.oneWithRetryAsync(snapshot, retry, cancel);
            }
        };
    }

    /**
     * 把同步步骤显式适配为异步步骤。
     *
     * <p>适配器使用 {@code gateway.getExecutor().io()} 执行同步步骤，**会阻塞该线程**
     * 直到步骤返回。`DirectExecutorProvider` 下会阻塞当前推进线程；`CallerRunsPolicy`
     * 下可能阻塞 HTTP 回调线程。高并发路径应直接实现 {@link AsyncStep}。</p>
     *
     * @param step 同步步骤
     * @return 异步步骤
     */
    public static AsyncStep adapt(final SyncStep step) {
        if (step == null) {
            throw new LlmCallException("步骤不能为空");
        }
        return new AsyncStep() {
            @Override
            public CompletableFuture<LlmResult> executeAsync(final LlmGateway gateway,
                                                            final LlmCancelHandle cancel) {
                if (cancel != null && cancel.isCancelled()) {
                    return failedFuture(new WorkflowCancelledException(
                            0, Collections.<LlmResult>emptyList()));
                }
                try {
                    return CompletableFuture.supplyAsync(() -> {
                        if (cancel != null && cancel.isCancelled()) {
                            throw new WorkflowCancelledException(
                                    0, Collections.<LlmResult>emptyList());
                        }
                        return step.execute(gateway, cancel);
                    }, gateway.getExecutor().io());
                } catch (RejectedExecutionException ex) {
                    return failedFuture(new LlmCallException("同步步骤适配被拒绝", ex,
                            LlmCallException.Kind.TRANSPORT));
                }
            }
        };
    }

    // ---------------------------------------------------------------- 异步引擎

    /**
     * 一次 runAsync 的状态机。只有 pump() 推进 index/completed；
     * 完成回调只写 pendingOutcome。
     */
    private final class AsyncWorkflowRun {

        private final List<AsyncStep> steps;
        private final LlmCancelHandle callerCancel;
        private final CompletableFuture<List<LlmResult>> result =
                new CompletableFuture<List<LlmResult>>();
        private final Object lock = new Object();
        private final List<LlmResult> completed = new ArrayList<LlmResult>();
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final AtomicInteger wip = new AtomicInteger();

        private int index;
        private CompletableFuture<LlmResult> currentStepFuture;
        private LlmCancelHandle currentStepCancel;
        private StepOutcome pendingOutcome;
        private boolean cancelRequested;
        private boolean futureCancelled;

        private AsyncWorkflowRun(List<AsyncStep> steps, LlmCancelHandle callerCancel) {
            this.steps = steps;
            this.callerCancel = callerCancel;
        }

        private CompletableFuture<List<LlmResult>> start() {
            if (callerCancel != null) {
                // 调用方句柄只做 forwarder；每步的 abort 槽位由 fresh 句柄独占。
                callerCancel.bindAbort(this::cancelFromCaller);
            }
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) {
                    cancelFromFuture();
                }
            });
            advance();
            return result;
        }

        private void advance() {
            if (wip.getAndIncrement() != 0) {
                return;
            }
            int missed = 1;
            try {
                for (;;) {
                    pump();
                    missed = wip.addAndGet(-missed);
                    if (missed == 0) {
                        break;
                    }
                }
            } catch (Throwable ex) {
                // pump 已捕获常规异常；这里是最后兜底，保证 future 不永久挂起。
                convergeUnexpected(ex);
            }
        }

        private void pump() {
            synchronized (lock) {
                for (;;) {
                    if (terminal.get() || futureCancelled) {
                        return;
                    }
                    if (pendingOutcome != null) {
                        StepOutcome outcome = pendingOutcome;
                        pendingOutcome = null;
                        currentStepFuture = null;
                        currentStepCancel = null;
                        if (outcome.cancelled) {
                            convergeCancelledLocked(outcome.error);
                            return;
                        }
                        if (outcome.error != null) {
                            handleStepErrorLocked(outcome.error);
                            return;
                        }
                        if (outcome.value == null) {
                            convergeStepFailureLocked(
                                    new LlmCallException("步骤返回了空结果"));
                            return;
                        }
                        completed.add(outcome.value);
                        if (outcome.value.getStopReason() == LlmStopReason.ABORTED) {
                            // 与同步版一致：ABORTED 先进入 completed，再用当前步索引收敛。
                            convergeCancelledLocked(null);
                            return;
                        }
                        index++;
                        continue;
                    }
                    if (cancelRequested) {
                        // 先消费已完成步骤的结果，再以“下一步”的索引收敛；
                        // 这样步骤间取消不会丢掉已经完成的步骤。
                        convergeCancelledLocked(null);
                        return;
                    }
                    if (index >= steps.size()) {
                        convergeSuccessLocked();
                        return;
                    }
                    if (currentStepFuture != null) {
                        return;
                    }
                    startNextLocked();
                }
            }
        }

        private void startNextLocked() {
            final int currentIndex = index;
            final AsyncStep step = steps.get(currentIndex);
            final LlmCancelHandle stepCancel = new LlmCancelHandle();
            currentStepCancel = stepCancel;
            CompletableFuture<LlmResult> future;
            try {
                future = step.executeAsync(gateway, stepCancel);
            } catch (Throwable ex) {
                pendingOutcome = new StepOutcome(currentIndex, null, ex, false);
                return;
            }
            if (future == null) {
                pendingOutcome = new StepOutcome(currentIndex, null,
                        new LlmCallException("步骤返回了空 future"), false);
                return;
            }
            currentStepFuture = future;
            future.whenComplete((value, error) -> {
                boolean cancelled = future.isCancelled();
                onStepDone(currentIndex, value, error, cancelled);
            });
        }

        private void onStepDone(int stepIndex, LlmResult value, Throwable error,
                                boolean cancelled) {
            synchronized (lock) {
                if (terminal.get() || futureCancelled) {
                    return;
                }
                if (stepIndex != index) {
                    return;
                }
                pendingOutcome = new StepOutcome(stepIndex, value, error, cancelled);
            }
            advance();
        }

        private void handleStepErrorLocked(Throwable error) {
            Throwable cause = unwrap(error);
            if (cause instanceof WorkflowCancelledException) {
                convergeCancelledLocked(cause);
                return;
            }
            if (cause instanceof WorkflowStepException) {
                convergeStepFailureLocked(cause);
                return;
            }
            if (cause instanceof Error) {
                convergeUnexpectedLocked(cause);
                return;
            }
            convergeStepFailureLocked(cause);
        }

        private void convergeSuccessLocked() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            unbindCaller();
            result.complete(immutable(completed));
        }

        private void convergeCancelledLocked(Throwable cause) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            unbindCaller();
            result.completeExceptionally(
                    new WorkflowCancelledException(index, immutable(completed), cause));
        }

        private void convergeStepFailureLocked(Throwable cause) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            unbindCaller();
            result.completeExceptionally(
                    new WorkflowStepException(index, immutable(completed), cause));
        }

        private void convergeUnexpectedLocked(Throwable error) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            unbindCaller();
            result.completeExceptionally(error);
        }

        private void convergeUnexpected(Throwable error) {
            synchronized (lock) {
                convergeUnexpectedLocked(error);
            }
        }

        private void cancelFromCaller() {
            LlmCancelHandle stepCancel;
            CompletableFuture<LlmResult> stepFuture;
            boolean convergeNow;
            synchronized (lock) {
                if (terminal.get() || futureCancelled || cancelRequested) {
                    return;
                }
                cancelRequested = true;
                stepCancel = currentStepCancel;
                stepFuture = currentStepFuture;
                convergeNow = stepCancel == null && stepFuture == null
                        && pendingOutcome == null;
            }
            if (stepCancel != null) {
                stepCancel.cancel();
            }
            if (stepFuture != null) {
                stepFuture.cancel(true);
            }
            if (convergeNow) {
                synchronized (lock) {
                    convergeCancelledLocked(null);
                }
            } else {
                advance();
            }
        }

        private void cancelFromFuture() {
            LlmCancelHandle stepCancel;
            CompletableFuture<LlmResult> stepFuture;
            synchronized (lock) {
                if (terminal.get()) {
                    return;
                }
                futureCancelled = true;
                cancelRequested = true;
                terminal.set(true);
                stepCancel = currentStepCancel;
                stepFuture = currentStepFuture;
                currentStepCancel = null;
                currentStepFuture = null;
                unbindCaller();
            }
            if (stepCancel != null) {
                stepCancel.cancel();
            }
            if (stepFuture != null) {
                stepFuture.cancel(true);
            }
        }

        private void unbindCaller() {
            if (callerCancel != null) {
                callerCancel.bindAbort(null);
            }
        }
    }

    private static final class StepOutcome {

        private final int stepIndex;
        private final LlmResult value;
        private final Throwable error;
        private final boolean cancelled;

        private StepOutcome(int stepIndex, LlmResult value, Throwable error,
                            boolean cancelled) {
            this.stepIndex = stepIndex;
            this.value = value;
            this.error = error;
            this.cancelled = cancelled;
        }
    }

    // ---------------------------------------------------------------- 工具

    private static void validateSyncSteps(List<?> steps) {
        for (Object step : steps) {
            validateSyncStep(step);
        }
    }

    private static void validateSyncStep(Object step) {
        if (step instanceof AgentLoop) {
            throw new LlmCallException("Workflow 不能放入 Agent loop");
        }
        if (!(step instanceof SyncStep)) {
            throw new LlmCallException("Workflow 步骤只能是单次调用或简单工具循环");
        }
    }

    private static List<LlmResult> immutable(List<LlmResult> results) {
        if (results.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<LlmResult>(results));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(error);
        return future;
    }
}
