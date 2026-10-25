package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.AsyncToolExecutor;
import io.github.yinwenyuan28.ailite.llm.CallbackPolicy;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.SerialCallbackExecutor;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.llm.ToolExecutor;
import io.github.yinwenyuan28.ailite.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 带上下文和生命周期的完整工具循环。每一轮只调网关的 round。
 *
 * <p>这一版是阻塞的：一轮还没返回时不看取消；返回之后如果已取消，就不再发下一轮。</p>
 */
public final class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final LlmGateway gateway;
    private final ExecutorProvider executor;
    private final ToolExecutor toolExecutor;
    private final ToolRunMode toolRunMode;
    private final Executor toolPool;
    private final CallbackPolicy callbackPolicy;

    /**
     * 工具执行模式（AL-A1）：默认串行；PARALLEL 对 run 与 runAsync 都生效，
     * 结果仍按模型原始顺序写回。
     */
    public enum ToolRunMode {

        /** 逐个按模型返回顺序执行（默认）。 */
        SEQUENTIAL,

        /** 本批调用并发执行，afterTool 与写回仍按原始顺序串行。 */
        PARALLEL
    }

    /**
     * 复用网关的 I/O 执行器和工具执行器。
     *
     * @param gateway 模型网关
     */
    public AgentLoop(LlmGateway gateway) {
        this(gateway, null, null);
    }

    /**
     * @param gateway  模型网关
     * @param executor I/O 执行器，null 表示复用网关的
     */
    public AgentLoop(LlmGateway gateway, ExecutorProvider executor) {
        this(gateway, executor, null);
    }

    /**
     * @param gateway      模型网关
     * @param executor     I/O 执行器，null 表示复用网关的
     * @param toolExecutor 工具执行器，null 表示复用网关的
     */
    public AgentLoop(LlmGateway gateway, ExecutorProvider executor, ToolExecutor toolExecutor) {
        this(gateway, executor, toolExecutor, ToolRunMode.SEQUENTIAL, null);
    }

    /**
     * 指定工具执行模式。
     *
     * @param toolRunMode 工具执行模式，null 表示串行
     */
    public AgentLoop(LlmGateway gateway, ExecutorProvider executor, ToolExecutor toolExecutor,
                     ToolRunMode toolRunMode) {
        this(gateway, executor, toolExecutor, toolRunMode, null);
    }

    /**
     * 指定工具执行模式与并行执行池。
     *
     * @param toolRunMode 工具执行模式，null 表示串行
     * @param toolPool    并行工具执行池，null 表示复用载体池
     */
    public AgentLoop(LlmGateway gateway, ExecutorProvider executor, ToolExecutor toolExecutor,
                     ToolRunMode toolRunMode, Executor toolPool) {
        this(gateway, executor, toolExecutor, toolRunMode, toolPool, null);
    }

    /**
     * 指定工具执行模式、并行执行池与回调执行策略。
     *
     * @param callbackPolicy 回调策略（回调策略 Phase 3）；io 模式下 runAsync 的泵与
     *                       用户钩子经独立回调池上的 per-run 串行执行器执行，
     *                       null 表示 inline（现状行为）
     */
    public AgentLoop(LlmGateway gateway, ExecutorProvider executor, ToolExecutor toolExecutor,
                     ToolRunMode toolRunMode, Executor toolPool, CallbackPolicy callbackPolicy) {
        if (gateway == null) {
            throw new LlmCallException("网关不能为空");
        }
        this.gateway = gateway;
        this.executor = executor == null ? gateway.getExecutor() : executor;
        this.toolExecutor = toolExecutor == null ? gateway.getToolExecutor() : toolExecutor;
        this.toolRunMode = toolRunMode == null ? ToolRunMode.SEQUENTIAL : toolRunMode;
        this.toolPool = toolPool;
        this.callbackPolicy = callbackPolicy == null ? CallbackPolicy.inline() : callbackPolicy;
    }

    /**
     * @return 本循环使用的 I/O 执行器
     */
    public ExecutorProvider getExecutor() {
        return executor;
    }

    /**
     * 跑一次 Agent 循环。
     *
     * <p>终态优先级：取消 &gt; STOP &gt; MAX_ROUNDS &gt; 模型自身 stopReason。继续条件由
     * “本轮是否还有 toolCalls”决定；`ABORTED` / `ERROR` 即使带 toolCalls 也不执行工具。</p>
     *
     * <p>{@link AgentLifecycle#afterRun(AgentLoopContext, LlmResult)} 是观察钩子：只要
     * {@code beforeRun} 成功进入，正常、取消、异常三条路径都恰好调用一次，异常不会覆盖结果。</p>
     *
     * @param call       调用参数
     * @param tools      工具列表
     * @param lifecycles 生命周期，按顺序执行，可为 null
     * @param maxRounds  最大轮数，null 表示 8
     * @param cancel     取消句柄，可为 null
     * @return 最后一轮结果
     */
    public LlmResult run(LlmCall call, List<LlmTool> tools, List<AgentLifecycle> lifecycles,
                         Integer maxRounds, LlmCancelHandle cancel) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        int limit = maxRounds == null ? LlmGateway.DEFAULT_MAX_ROUNDS : maxRounds.intValue();
        if (limit <= 0) {
            throw new LlmCallException("最大轮数必须大于 0");
        }
        // 钩子列表做防御性拷贝，避免调用方在 run 期间修改影响本轮。
        final List<AgentLifecycle> hooks = lifecycles == null
                ? Collections.<AgentLifecycle>emptyList()
                : new ArrayList<AgentLifecycle>(lifecycles);
        final AgentLoopContext ctx = AgentLoopContext.copy(call.getBizId(), call.getMessages(),
                tools, cancel);

        // beforeRun 失败代表 run 未开始：不打 afterRun，直接抛出。
        eachBeforeRun(hooks, ctx);

        LlmResult outcome = null;
        Throwable failure = null;
        try {
            outcome = runRounds(call, hooks, ctx, limit);
            return outcome;
        } catch (RuntimeException ex) {
            failure = ex;
            throw ex;
        } catch (Error ex) {
            failure = ex;
            throw ex;
        } finally {
            // afterRun 是观察钩子：异常只记录，绝不覆盖结果或原异常。
            eachAfterRun(hooks, ctx, outcome, failure);
        }
    }

    /**
     * 同步轮次推进。所有终止分支只返回结果，由 {@link #run} 的 finally 统一收口 afterRun。
     */
    private LlmResult runRounds(LlmCall call, List<AgentLifecycle> hooks, AgentLoopContext ctx,
                                int limit) {
        int completed = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        int totalTokens = 0;
        long latencyMs = 0L;
        LlmResult last = null;

        while (true) {
            // 检查点 1：每轮开始前。
            if (ctx.isCancelled()) {
                return aborted(last, inputTokens, outputTokens, totalTokens, latencyMs, completed);
            }
            eachBeforeModel(hooks, ctx);
            // 检查点 2：beforeModel 之后、发请求之前；不计轮次、不调用 afterModel。
            if (ctx.isCancelled()) {
                return aborted(last, inputTokens, outputTokens, totalTokens, latencyMs, completed);
            }

            LlmResult round = gateway.round(call.withMessages(ctx.getMessages()),
                    ctx.getTools(), ctx.getCancel());
            completed++;
            inputTokens += round.getUsage().getInputTokens();
            outputTokens += round.getUsage().getOutputTokens();
            totalTokens += round.getUsage().getTotalTokens();
            latencyMs += round.getLatencyMs();

            AgentModelOutput output = new AgentModelOutput(round);
            eachAfterModel(hooks, ctx, output);
            LlmResult shown = withContent(round, output.getContent());

            boolean hasToolCalls = shown.getToolCalls() != null
                    && !shown.getToolCalls().isEmpty();
            boolean lastRound = completed >= limit;
            boolean runnable = hasToolCalls
                    && shown.getStopReason() != LlmStopReason.ERROR;
            boolean appended = false;

            // 工具阶段决策：取消/ABORTED、执行、合成、直通；副作用在这里统一写回。
            switch (decideToolPhase(ctx.isCancelled(), shown.getStopReason(),
                    hasToolCalls, lastRound)) {
                case ABORTED:
                    // 检查点 3：模型返回后、工具启动前。取消优先于一切。
                    if (hasToolCalls) {
                        appendAssistant(ctx, shown);
                        appendSkipped(ctx, shown.getToolCalls(), "未执行：调用已取消");
                    }
                    return accumulate(shown, LlmStopReason.ABORTED,
                            inputTokens, outputTokens, totalTokens, latencyMs, completed);
                case EXECUTE_TOOLS:
                    appendAssistant(ctx, shown);
                    runTools(ctx, hooks, shown, completed);
                    appended = true;
                    break;
                case SYNTHESIZE_MAX_ROUNDS:
                    // 最后一轮仍要工具：不执行，补合成结果避免 dangling。
                    appendAssistant(ctx, shown);
                    appendSkipped(ctx, shown.getToolCalls(), "未执行：达到最大轮数");
                    appended = true;
                    break;
                case SYNTHESIZE_NOT_RUN:
                    // ERROR 带 toolCalls：不执行，补合成结果避免 dangling。
                    appendAssistant(ctx, shown);
                    appendSkipped(ctx, shown.getToolCalls(), "未执行：本轮不执行工具");
                    appended = true;
                    break;
                default:
                    break;
            }

            boolean stop = false;
            boolean again = false;
            for (AgentLifecycle hook : hooks) {
                if (hook == null) {
                    continue;
                }
                TurnDecision decision = hook.afterTurn(ctx, shown);
                TurnDecision.Kind kind = decision == null
                        ? TurnDecision.Kind.MODEL_DECIDES : decision.kind();
                if (kind == TurnDecision.Kind.STOP) {
                    stop = true;
                } else if (kind == TurnDecision.Kind.CONTINUE) {
                    again = true;
                }
            }
            last = shown;

            // 收尾决策：取消 > STOP > MAX_ROUNDS > 模型自身 stopReason。
            switch (decideTerminal(ctx.isCancelled(), stop, lastRound, hasToolCalls,
                    again, runnable)) {
                case ABORTED:
                    // 检查点 4：工具批次结束后（runTools 内也可能已取消）。
                    return accumulate(shown, LlmStopReason.ABORTED,
                            inputTokens, outputTokens, totalTokens, latencyMs, completed);
                case STOP:
                    return accumulate(shown, LlmStopReason.STOP,
                            inputTokens, outputTokens, totalTokens, latencyMs, completed);
                case MAX_ROUNDS:
                    // 最后一轮仍有 toolCalls，或 hook 要求继续但轮数耗尽。
                    return accumulate(shown, LlmStopReason.MAX_ROUNDS,
                            inputTokens, outputTokens, totalTokens, latencyMs, completed);
                case NEXT_ROUND:
                    // 非工具轮被要求继续：必须先把 assistant 正文写回上下文，否则下一轮
                    // 重复同一请求。AL-D08：本轮已追加过（ERROR/最后轮合成路径）不得重复追加。
                    if (again && !appended) {
                        appendAssistant(ctx, shown);
                    }
                    continue;
                default:
                    return accumulate(shown, shown.getStopReason(),
                            inputTokens, outputTokens, totalTokens, latencyMs, completed);
            }
        }
    }

    /**
     * 模型返回后的工具阶段决策。
     */
    enum ToolPhase {

        /** 已取消或模型 ABORTED：不执行工具，调用方补合成后以 ABORTED 收敛。 */
        ABORTED,

        /** 执行本批工具（非最后轮、stopReason 非终态异常）。 */
        EXECUTE_TOOLS,

        /** 最后一轮仍要工具：不执行，合成“未执行：达到最大轮数”。 */
        SYNTHESIZE_MAX_ROUNDS,

        /** ERROR 带 toolCalls：不执行，合成“未执行：本轮不执行工具”。 */
        SYNTHESIZE_NOT_RUN,

        /** 无 toolCalls：直通收尾判定。 */
        PASS
    }

    /**
     * afterTurn 聚合后的收尾决策，优先级 取消 &gt; STOP &gt; MAX_ROUNDS &gt; 模型。
     */
    enum Terminal {

        /** 取消收敛。 */
        ABORTED,

        /** 钩子要求停止。 */
        STOP,

        /** 轮数耗尽（最后轮仍有 toolCalls，或 CONTINUE 耗尽）。 */
        MAX_ROUNDS,

        /** 继续下一轮。 */
        NEXT_ROUND,

        /** 按模型自身 stopReason 收敛。 */
        MODEL
    }

    /**
     * 工具阶段决策（纯函数，同步/异步共用）。
     *
     * @param cancelled    上下文是否已取消
     * @param stopReason   本轮模型 stopReason
     * @param hasToolCalls 本轮是否有工具调用
     * @param lastRound    本轮是否为最后一轮
     * @return 工具阶段；追加 assistant、合成 tool_result 等副作用由调用方执行
     */
    static ToolPhase decideToolPhase(boolean cancelled, LlmStopReason stopReason,
                                     boolean hasToolCalls, boolean lastRound) {
        if (cancelled || stopReason == LlmStopReason.ABORTED) {
            return ToolPhase.ABORTED;
        }
        boolean runnable = hasToolCalls && stopReason != LlmStopReason.ERROR;
        if (runnable && !lastRound) {
            return ToolPhase.EXECUTE_TOOLS;
        }
        if (hasToolCalls) {
            return lastRound ? ToolPhase.SYNTHESIZE_MAX_ROUNDS : ToolPhase.SYNTHESIZE_NOT_RUN;
        }
        return ToolPhase.PASS;
    }

    /**
     * 收尾决策（纯函数，同步/异步共用）。
     *
     * @param cancelled    上下文是否已取消
     * @param stop         是否有 afterTurn 钩子要求停止
     * @param lastRound    本轮是否为最后一轮
     * @param hasToolCalls 本轮是否有工具调用
     * @param again        是否有 afterTurn 钩子要求继续
     * @param runnable     本轮是否满足工具执行条件（hasToolCalls 且 stopReason 非 ERROR）
     * @return 终态或下一轮
     */
    static Terminal decideTerminal(boolean cancelled, boolean stop, boolean lastRound,
                                   boolean hasToolCalls, boolean again, boolean runnable) {
        if (cancelled) {
            return Terminal.ABORTED;
        }
        if (stop) {
            return Terminal.STOP;
        }
        if (lastRound) {
            return (hasToolCalls || again) ? Terminal.MAX_ROUNDS : Terminal.MODEL;
        }
        if (again || runnable) {
            return Terminal.NEXT_ROUND;
        }
        return Terminal.MODEL;
    }

    /**
     * 非阻塞跑一次 Agent 循环。
     *
     * <p>模型调用走 {@code LlmGateway.roundAsync}；决策与上下文写回集中在泵里串行推进，
     * 完成回调只登记结果。终态、异常、取消都恰好调用一次 {@code afterRun}，
     * future 不会因递归、Error 逃逸或非合作传输而永久挂起。</p>
     *
     * @param call       调用参数
     * @param tools      工具列表
     * @param lifecycles 生命周期，按顺序执行，可为 null
     * @param maxRounds  最大轮数，null 表示 8
     * @param cancel     取消句柄，可为 null
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> runAsync(LlmCall call, List<LlmTool> tools,
                                                 List<AgentLifecycle> lifecycles,
                                                 Integer maxRounds, LlmCancelHandle cancel) {
        return runAsync(call, tools, lifecycles, maxRounds, cancel, null);
    }

    /**
     * 带整轮超时的非阻塞 Agent 循环（AL-A3）。
     *
     * <p>超时覆盖整个 run（模型轮次 + 工具执行）：到点触发取消句柄，循环按取消路径
     * 收敛为 ABORTED。调用方自带句柄时，超时会消费掉该句柄（one-shot）；
     * {@code timeoutMs} 为 null 或小于等于 0 表示不设整轮预算。</p>
     *
     * @param timeoutMs 整轮预算毫秒数；null 或 &lt;=0 表示不限制
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> runAsync(LlmCall call, List<LlmTool> tools,
                                                 List<AgentLifecycle> lifecycles,
                                                 Integer maxRounds, LlmCancelHandle cancel,
                                                 Long timeoutMs) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        int limit = maxRounds == null ? LlmGateway.DEFAULT_MAX_ROUNDS : maxRounds.intValue();
        if (limit <= 0) {
            throw new LlmCallException("最大轮数必须大于 0");
        }
        // 钩子列表做防御性拷贝，避免调用方在 run 期间修改影响本轮（AL-D04）。
        final List<AgentLifecycle> hooks = lifecycles == null
                ? Collections.<AgentLifecycle>emptyList()
                : new ArrayList<AgentLifecycle>(lifecycles);
        // future.cancel 也要能中断正在进行的模型请求，即使调用方没有显式传句柄。
        final LlmCancelHandle effectiveCancel = cancel == null ? new LlmCancelHandle() : cancel;
        AgentLoopContext ctx = AgentLoopContext.copy(call.getBizId(), call.getMessages(),
                tools, effectiveCancel);

        // beforeRun 失败代表 run 未开始：不打 afterRun；异步入口统一用 future 表达异常。
        try {
            eachBeforeRun(hooks, ctx);
        } catch (RuntimeException ex) {
            CompletableFuture<LlmResult> failed = new CompletableFuture<LlmResult>();
            failed.completeExceptionally(ex);
            return failed;
        }
        AsyncRun run = new AsyncRun(call, hooks, ctx, limit);
        CompletableFuture<LlmResult> future = run.start();
        scheduleRunTimeout(future, effectiveCancel, timeoutMs);
        return future;
    }

    /**
     * 整轮超时（AL-A3）：到点触发取消句柄，收敛交给循环自身的取消路径；
     * run 提前结束时撤销调度任务，避免泄漏。调度复用网关的重试调度器。
     */
    private void scheduleRunTimeout(CompletableFuture<LlmResult> future,
                                    LlmCancelHandle cancel, Long timeoutMs) {
        if (timeoutMs == null || timeoutMs.longValue() <= 0L) {
            return;
        }
        final ScheduledFuture<?> task = gateway.retryScheduler().schedule(new Runnable() {
            @Override
            public void run() {
                // 极小竞态窗口内 run 刚完成：句柄会被消费，对已结束的 run 无影响。
                if (!future.isDone()) {
                    cancel.cancel();
                }
            }
        }, timeoutMs.longValue(), TimeUnit.MILLISECONDS);
        future.whenComplete((value, error) -> task.cancel(false));
    }

    /**
     * 异步循环状态机：泵结构（参照 Workflow 的 AsyncWorkflowRun）。
     *
     * <p>线程纪律：模型轮/工具批次的完成回调只写结果槽并唤醒泵；全部决策与上下文
     * 写回集中在 pump() 里单线程串行推进，drain 层 catch (Throwable) 兜底，保证
     * result 永不因同步递归、Error 逃逸或非合作传输而挂起（AL-H04）。</p>
     *
     * <p>并发纪律：结果槽与等待标志由 lock 保护；finished CAS 保证 afterRun 恰好一次，
     * 覆盖成功、异常与 future.cancel 三条收敛路径（AL-D03）。pump 落在触发推进的
     * 线程上（首轮=调用方线程，后续=传输/载体线程），钩子线程归属与旧版一致。</p>
     */
    private final class AsyncRun {

        private final LlmCall call;
        private final List<AgentLifecycle> hooks;
        private final AgentLoopContext ctx;
        private final int limit;
        private final CompletableFuture<LlmResult> result = new CompletableFuture<LlmResult>();
        private final Object lock = new Object();
        /** afterRun 恰好一次的闸门，也是 run 终止的统一标志。 */
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicInteger wip = new AtomicInteger();
        /** 回调策略 io 模式的 per-run 串行执行器；inline 为 null。 */
        private final SerialCallbackExecutor callbackBarrier;

        /** 结果槽：外部回调唯一写入点，只有 pump 解释。 */
        private AsyncOutcome pending;
        private boolean waitingModel;
        private boolean waitingTools;

        // 本轮状态（pump 线程独占读写）
        private LlmResult currentShown;
        private boolean currentRunnable;
        private boolean assistantAppended;

        private int completed;
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;
        private long latencyMs;
        private LlmResult last;

        private AsyncRun(LlmCall call, List<AgentLifecycle> hooks, AgentLoopContext ctx, int limit) {
            this.call = call;
            this.hooks = hooks;
            this.ctx = ctx;
            this.limit = limit;
            // 回调策略 io 模式：per-run 串行执行器承接泵推进；inline 为 null（就地 drain）。
            this.callbackBarrier = callbackPolicy.newSerialExecutor();
        }

        private CompletableFuture<LlmResult> start() {
            // future.cancel 桥：取消方线程上补跑 afterRun（契约 §5.3，AL-D03）。
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) {
                    ctx.getCancel().cancel();
                    convergeCancelledByFuture();
                }
            });
            advance();
            return result;
        }

        /**
         * 回调唤醒入口：wip 抢占 + drain，重入只登记次数不递归（AL-H04 蹦床）。
         * 回调策略 io 模式（Phase 3a）：drain 任务投递到 per-run 串行执行器——投递只
         * 发生在 wip 0→1 转换，队列中至多一个 drain 任务；inline 就地执行。
         */
        private void advance() {
            if (wip.getAndIncrement() != 0) {
                return;
            }
            if (callbackBarrier == null) {
                drainLoop();
                return;
            }
            if (!callbackBarrier.submit(new Runnable() {
                @Override
                public void run() {
                    drainLoop();
                }
            })) {
                // M-1：投递被拒（队列满/已关闭）必须在提交线程收敛，否则唤醒丢失、run 挂起。
                convergeFailure(new LlmCallException("回调队列已满", null,
                        LlmCallException.Kind.OVERLOAD));
                ctx.getCancel().cancel();
            }
        }

        /** drain 循环：消化 wip 计数并推进泵；Error 一并收敛，future 永不悬挂。 */
        private void drainLoop() {
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
                // 钩子/网关异常统一在这里收敛：afterRun 恰好一次后异常完成。
                convergeFailure(ex);
            }
        }

        private void pump() {
            synchronized (lock) {
                for (;;) {
                    if (finished.get()) {
                        return;
                    }
                    if (pending != null) {
                        AsyncOutcome outcome = pending;
                        pending = null;
                        if (outcome.toolsDone) {
                            if (outcome.error != null) {
                                convergeFailure(outcome.error);
                                return;
                            }
                            if (!finishTurnLocked()) {
                                return;
                            }
                            continue;
                        }
                        if (outcome.error != null) {
                            convergeFailure(outcome.error);
                            return;
                        }
                        if (!processRoundLocked(outcome.round)) {
                            return;
                        }
                        // 合成/直通路径：模型轮消费完直接进入本轮收尾判定，
                        // 不能回到循环顶开新一轮（否则终态判定和轮数上限全部失效）。
                        if (!finishTurnLocked()) {
                            return;
                        }
                        continue;
                    }
                    // 检查点 1：每轮开始前（含首轮前取消，rounds=0）。
                    if (ctx.isCancelled()) {
                        convergeComplete(abortedResultLocked());
                        return;
                    }
                    if (waitingModel || waitingTools) {
                        return;
                    }
                    startRoundLocked();
                }
            }
        }

        /**
         * 消费模型轮结果：累计 usage、afterModel、检查点 3、工具阶段决策。
         *
         * @return true 表示直接进入本轮收尾判定（合成/直通路径）；
         *         false 表示已终态或已提交工具批次等待回调
         */
        private boolean processRoundLocked(LlmResult round) {
            if (round == null) {
                throw new LlmCallException("模型轮返回了空结果");
            }
            completed++;
            inputTokens += round.getUsage().getInputTokens();
            outputTokens += round.getUsage().getOutputTokens();
            totalTokens += round.getUsage().getTotalTokens();
            latencyMs += round.getLatencyMs();

            AgentModelOutput output = new AgentModelOutput(round);
            eachAfterModel(hooks, ctx, output);
            LlmResult shown = withContent(round, output.getContent());
            currentShown = shown;

            boolean hasToolCalls = shown.getToolCalls() != null
                    && !shown.getToolCalls().isEmpty();
            boolean lastRound = completed >= limit;
            currentRunnable = hasToolCalls && shown.getStopReason() != LlmStopReason.ERROR;
            assistantAppended = false;

            switch (decideToolPhase(ctx.isCancelled(), shown.getStopReason(), hasToolCalls,
                    lastRound)) {
                case ABORTED:
                    // 检查点 3：模型返回后、工具启动前；取消/ABORTED 补合成避免 dangling。
                    if (hasToolCalls) {
                        appendAssistant(ctx, shown);
                        appendSkipped(ctx, shown.getToolCalls(), "未执行：调用已取消");
                    }
                    convergeComplete(accumulate(shown, LlmStopReason.ABORTED,
                            inputTokens, outputTokens, totalTokens, latencyMs, completed));
                    return false;
                case EXECUTE_TOOLS:
                    appendAssistant(ctx, shown);
                    assistantAppended = true;
                    submitToolsLocked(shown);
                    return false;
                case SYNTHESIZE_MAX_ROUNDS:
                    appendAssistant(ctx, shown);
                    assistantAppended = true;
                    appendSkipped(ctx, shown.getToolCalls(), "未执行：达到最大轮数");
                    return true;
                case SYNTHESIZE_NOT_RUN:
                    appendAssistant(ctx, shown);
                    assistantAppended = true;
                    appendSkipped(ctx, shown.getToolCalls(), "未执行：本轮不执行工具");
                    return true;
                default:
                    return true;
            }
        }

        /**
         * 开新一轮：beforeModel、检查点 2（取消不请求、不计轮、不调 afterModel）、发模型请求。
         */
        private void startRoundLocked() {
            eachBeforeModel(hooks, ctx);
            if (ctx.isCancelled()) {
                convergeComplete(abortedResultLocked());
                return;
            }
            CompletableFuture<LlmResult> future = gateway.roundAsync(
                    call.withMessages(ctx.getMessages()), ctx.getTools(), ctx.getCancel());
            if (future == null) {
                throw new LlmCallException("roundAsync 返回了空 future");
            }
            waitingModel = true;
            future.whenComplete((value, error) -> onRoundDone(value, error));
        }

        /** 模型轮完成回调：只写结果槽并唤醒泵。 */
        private void onRoundDone(LlmResult value, Throwable error) {
            synchronized (lock) {
                if (finished.get() || !waitingModel) {
                    return;
                }
                waitingModel = false;
                pending = new AsyncOutcome(value, error, false);
            }
            advance();
        }

        /** 提交工具批次：完成回调只写结果槽。 */
        private void submitToolsLocked(LlmResult shown) {
            final int roundNo = completed;
            waitingTools = true;
            CompletableFuture<Void> toolsFuture;
            try {
                toolsFuture = submitToolBatch(ctx, hooks, shown, roundNo, callbackBarrier);
            } catch (RuntimeException ex) {
                waitingTools = false;
                throw ex;
            }
            toolsFuture.whenComplete((ignored, toolError) -> onToolsDone(toolError));
        }

        /** 工具批次完成回调：只写结果槽并唤醒泵。 */
        private void onToolsDone(Throwable error) {
            synchronized (lock) {
                if (finished.get() || !waitingTools) {
                    return;
                }
                waitingTools = false;
                pending = new AsyncOutcome(null, error, true);
            }
            advance();
        }

        /**
         * 本轮收尾：afterTurn 聚合、检查点 5、终态判定。
         *
         * @return true 表示继续下一轮（由 pump 循环消费，不递归）；false 表示已终态
         */
        private boolean finishTurnLocked() {
            LlmResult shown = currentShown;
            boolean stop = false;
            boolean again = false;
            for (AgentLifecycle hook : hooks) {
                if (hook == null) {
                    continue;
                }
                TurnDecision decision = hook.afterTurn(ctx, shown);
                TurnDecision.Kind kind = decision == null
                        ? TurnDecision.Kind.MODEL_DECIDES : decision.kind();
                if (kind == TurnDecision.Kind.STOP) {
                    stop = true;
                } else if (kind == TurnDecision.Kind.CONTINUE) {
                    again = true;
                }
            }
            last = shown;
            boolean lastRound = completed >= limit;
            boolean hasToolCalls = shown.getToolCalls() != null
                    && !shown.getToolCalls().isEmpty();
            switch (decideTerminal(ctx.isCancelled(), stop, lastRound, hasToolCalls,
                    again, currentRunnable)) {
                case ABORTED:
                    // 检查点 5：工具批次结束后（runTools 内也可能已取消）。
                    convergeComplete(accumulate(shown, LlmStopReason.ABORTED,
                            inputTokens, outputTokens, totalTokens, latencyMs, completed));
                    return false;
                case STOP:
                    convergeComplete(accumulate(shown, LlmStopReason.STOP,
                            inputTokens, outputTokens, totalTokens, latencyMs, completed));
                    return false;
                case MAX_ROUNDS:
                    convergeComplete(accumulate(shown, LlmStopReason.MAX_ROUNDS,
                            inputTokens, outputTokens, totalTokens, latencyMs, completed));
                    return false;
                case NEXT_ROUND:
                    // AL-D02/D08：非工具轮被要求继续时先补 assistant；已追加过的不重复。
                    if (again && !assistantAppended) {
                        appendAssistant(ctx, shown);
                    }
                    return true;
                default:
                    convergeComplete(accumulate(shown, shown.getStopReason(),
                            inputTokens, outputTokens, totalTokens, latencyMs, completed));
                    return false;
            }
        }

        /** 成功终态收敛：恰好一次 afterRun 后完成 future；回调通道随之关闭。 */
        private void convergeComplete(LlmResult value) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            eachAfterRun(hooks, ctx, value, null);
            if (callbackBarrier != null) {
                callbackBarrier.close();
            }
            result.complete(value);
        }

        /** 异常终态收敛：恰好一次 afterRun 后异常完成 future；回调通道随之关闭。 */
        private void convergeFailure(Throwable error) {
            Throwable cause = unwrap(error);
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            eachAfterRun(hooks, ctx, null, cause);
            if (callbackBarrier != null) {
                callbackBarrier.close();
            }
            result.completeExceptionally(cause);
        }

        /**
         * future.cancel 路径收敛：取消方线程上恰好一次 afterRun（D-2 文档化例外），
         * result 保持 cancelled；回调通道随之关闭。
         */
        private void convergeCancelledByFuture() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            eachAfterRun(hooks, ctx, null, new CancellationException("run 被调用方取消"));
            if (callbackBarrier != null) {
                callbackBarrier.close();
            }
        }

        private LlmResult abortedResultLocked() {
            return aborted(last, inputTokens, outputTokens, totalTokens, latencyMs, completed);
        }
    }

    /**
     * 异步循环的结果槽：模型轮结果、批次完成标记或异常。回调线程只构造，不解释。
     */
    private static final class AsyncOutcome {

        final LlmResult round;
        final Throwable error;
        final boolean toolsDone;

        AsyncOutcome(LlmResult round, Throwable error, boolean toolsDone) {
            this.round = round;
            this.error = error;
            this.toolsDone = toolsDone;
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 执行一轮工具调用。beforeTool 可以改写参数或拒绝，实际执行交给 ToolExecutor，
     * 同时保留 AgentLoop 自己的逐条生命周期顺序。
     *
     * <p>每个工具执行前都会检查取消；一旦取消，该工具及后续工具都不执行，并为它们补
     * 一条“未执行”的合成 tool_result，保证上下文不出现 dangling call。</p>
     *
     * <p>{@link ToolExecutor} 的实现必须自行捕获单个工具的业务异常；这里 catch 到的
     * 异常按执行器级故障处理，直接终止 run。</p>
     */
    private void runTools(AgentLoopContext ctx, List<AgentLifecycle> hooks, LlmResult shown,
                          int roundNo) {
        List<LlmToolCall> calls = shown.getToolCalls();
        if (toolRunMode == ToolRunMode.PARALLEL && calls.size() > 1) {
            runToolsParallel(ctx, hooks, calls, roundNo);
            return;
        }
        for (int i = 0; i < calls.size(); i++) {
            LlmToolCall call = calls.get(i);
            if (ctx.isCancelled()) {
                appendSkipped(ctx, calls.subList(i, calls.size()), "未执行：调用已取消");
                return;
            }
            AgentToolCall mutable = new AgentToolCall(call.getId(), call.getName(),
                    call.getArgumentsJson());
            String denial = null;
            for (AgentLifecycle hook : hooks) {
                if (hook == null) {
                    continue;
                }
                ToolDecision decision = hook.beforeTool(ctx, mutable);
                if (decision == null) {
                    decision = ToolDecision.allow();
                }
                if (!decision.isAllowed()) {
                    denial = Strings.isBlank(decision.getReason())
                            ? "已拒绝这次工具调用" : decision.getReason();
                    break;
                }
                if (decision.getArgumentsJson() != null) {
                    mutable.setArgumentsJson(decision.getArgumentsJson());
                }
            }
            String text = denial != null ? denial : execute(ctx.getTools(), mutable, roundNo);
            if (denial == null) {
                for (AgentLifecycle hook : hooks) {
                    if (hook == null) {
                        continue;
                    }
                    String changed = hook.afterTool(ctx, mutable, text);
                    if (changed != null) {
                        text = changed;
                    }
                }
            }
            ctx.getMessages().add(LlmMessage.toolResult(call.getId(), text));
        }
    }

    /**
     * 并行批次预检结果（同步与异步入口共用）：beforeTool 全量串行预检的产物。
     */
    private static final class ParallelPrecheck {

        final boolean[] denied;
        final String[] denials;
        final List<AgentToolCall> mutable;
        /** 预检中发现取消：拒绝文本与 skipped 已写回，本批无需执行。 */
        boolean cancelledHandled;

        ParallelPrecheck(int size) {
            this.denied = new boolean[size];
            this.denials = new String[size];
            this.mutable = new ArrayList<AgentToolCall>(size);
        }
    }

    /**
     * 并行批次第 1 段：串行预检。钩子有序、deny-wins、参数改写都在并发前确定；
     * 取消时已拒绝的写拒绝文本、未处理的合成 skipped（M-6：停止依赖逐工具检查）。
     */
    private ParallelPrecheck precheckParallel(AgentLoopContext ctx, List<AgentLifecycle> hooks,
                                              List<LlmToolCall> calls) {
        final int size = calls.size();
        final ParallelPrecheck pre = new ParallelPrecheck(size);
        for (int i = 0; i < size; i++) {
            LlmToolCall call = calls.get(i);
            pre.mutable.add(new AgentToolCall(call.getId(), call.getName(),
                    call.getArgumentsJson()));
            if (ctx.isCancelled()) {
                for (int j = 0; j < i; j++) {
                    if (pre.denied[j]) {
                        ctx.getMessages().add(
                                LlmMessage.toolResult(calls.get(j).getId(), pre.denials[j]));
                    }
                }
                appendSkipped(ctx, calls.subList(i, size), "未执行：调用已取消");
                pre.cancelledHandled = true;
                return pre;
            }
            String denial = null;
            for (AgentLifecycle hook : hooks) {
                if (hook == null) {
                    continue;
                }
                ToolDecision decision = hook.beforeTool(ctx, pre.mutable.get(i));
                if (decision == null) {
                    decision = ToolDecision.allow();
                }
                if (!decision.isAllowed()) {
                    denial = Strings.isBlank(decision.getReason())
                            ? "已拒绝这次工具调用" : decision.getReason();
                    break;
                }
                if (decision.getArgumentsJson() != null) {
                    pre.mutable.get(i).setArgumentsJson(decision.getArgumentsJson());
                }
            }
            if (denial != null) {
                pre.denied[i] = true;
                pre.denials[i] = denial;
            }
        }
        return pre;
    }

    /**
     * 并行批次第 3 段：按模型原始顺序串行写回——denied 用拒绝文本；否则先过
     * afterTool 链再生成 tool_result。顺序与完成顺序无关。
     */
    private void writeBackParallel(AgentLoopContext ctx, List<AgentLifecycle> hooks,
                                   List<LlmToolCall> calls, ParallelPrecheck pre,
                                   String[] texts) {
        final int size = calls.size();
        for (int i = 0; i < size; i++) {
            String text;
            if (pre.denied[i]) {
                text = pre.denials[i];
            } else {
                text = texts[i] == null ? "" : texts[i];
                for (AgentLifecycle hook : hooks) {
                    if (hook == null) {
                        continue;
                    }
                    String changed = hook.afterTool(ctx, pre.mutable.get(i), text);
                    if (changed != null) {
                        text = changed;
                    }
                }
            }
            ctx.getMessages().add(LlmMessage.toolResult(calls.get(i).getId(), text));
        }
    }

    /**
     * 并行执行本批工具（AL-A1，同步入口）：beforeTool 全量串行预检 → 允许的调用
     * 并发执行（单个工具不切线程）→ 汇合后按模型原始顺序写回。执行器故障即抛出
     * 终止 run：已提交调用的结果丢弃、已完成工具的 afterTool 不再调用。
     */
    private void runToolsParallel(AgentLoopContext ctx, List<AgentLifecycle> hooks,
                                  List<LlmToolCall> calls, int roundNo) {
        final int size = calls.size();
        final ParallelPrecheck pre = precheckParallel(ctx, hooks, calls);
        if (pre.cancelledHandled) {
            return;
        }

        // 2. 并发执行未被拒绝的调用；只有一个时直接在当前线程执行，避免无谓跳线程。
        final String[] texts = new String[size];
        final List<CompletableFuture<String>> futures = new ArrayList<CompletableFuture<String>>();
        int allowed = 0;
        int onlyAllowed = -1;
        for (int i = 0; i < size; i++) {
            if (!pre.denied[i]) {
                allowed++;
                onlyAllowed = i;
            }
        }
        if (allowed == 1) {
            texts[onlyAllowed] = execute(ctx.getTools(), pre.mutable.get(onlyAllowed), roundNo);
        } else if (allowed > 1) {
            for (int i = 0; i < size; i++) {
                if (pre.denied[i]) {
                    futures.add(null);
                    continue;
                }
                final AgentToolCall call = pre.mutable.get(i);
                futures.add(CompletableFuture.supplyAsync(
                        () -> execute(ctx.getTools(), call, roundNo), toolPool()));
            }
            joinToolFutures(futures);
            for (int i = 0; i < size; i++) {
                if (!pre.denied[i]) {
                    CompletableFuture<String> future = futures.get(i);
                    String text = future.getNow(null);
                    texts[i] = text == null ? "" : text;
                }
            }
        }

        writeBackParallel(ctx, hooks, calls, pre, texts);
    }

    /**
     * 等待并行批次汇合：任一失败立即失败（fail-fast），全部成功才返回。
     */
    private static void joinToolFutures(List<CompletableFuture<String>> futures) {
        final CompletableFuture<Void> converged = new CompletableFuture<Void>();
        final List<CompletableFuture<String>> inFlight = new ArrayList<CompletableFuture<String>>();
        for (CompletableFuture<String> future : futures) {
            if (future == null) {
                continue;
            }
            inFlight.add(future);
            future.whenComplete((text, error) -> {
                if (error != null) {
                    converged.completeExceptionally(error);
                }
            });
        }
        CompletableFuture.allOf(inFlight.toArray(new CompletableFuture[0]))
                .whenComplete((ignored, error) -> {
                    // allOf 的失败必须传播：无条件 complete(null) 会在单 future 的
                    // 失败回调尚未触发时把故障吞掉（join 正常返回、写回空文本）。
                    if (error != null) {
                        converged.completeExceptionally(error);
                    } else {
                        converged.complete(null);
                    }
                });
        try {
            converged.join();
        } catch (CompletionException ex) {
            // 解包任意层 CompletionException 嵌套：多 future 失败竞争时
            // allOf 与单 future 的失败回调谁先到达不确定，嵌套深度不定。
            Throwable cause = ex;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new LlmCallException("工具执行器故障", cause);
        }
    }

    /** 并行/异步工具共用的执行池：未显式指定时复用载体池。 */
    private Executor toolPool() {
        return toolPool == null ? executor.io() : toolPool;
    }

    /**
     * 回调策略下的"钩子执行器"（Phase 3b）：io 模式把任务投递到 per-run 串行执行器
     * （submit 失败按 OVERLOAD 异常完成链段）；inline 复用载体池（语义与现状等价）。
     */
    private Executor callbackExec(final SerialCallbackExecutor barrier) {
        if (barrier == null) {
            return executor.io();
        }
        return new Executor() {
            @Override
            public void execute(Runnable command) {
                if (!barrier.submit(command)) {
                    throw new LlmCallException("回调队列已满", null,
                            LlmCallException.Kind.OVERLOAD);
                }
            }
        };
    }

    /**
     * 提交工具批次（异步入口，Phase 3b 泛化）：串行走统一异步链（beforeTool/afterTool/
     * 写回在回调执行器、工具本体在 io 池或 {@link AsyncToolExecutor} future）；
     * 并行走三段异步版。执行器故障沿链传播终止 run。
     */
    private CompletableFuture<Void> submitToolBatch(final AgentLoopContext ctx,
                                                    final List<AgentLifecycle> hooks,
                                                    final LlmResult shown, final int roundNo,
                                                    final SerialCallbackExecutor barrier) {
        if (toolRunMode == ToolRunMode.PARALLEL) {
            return runToolsParallelAsync(ctx, hooks, shown, roundNo, barrier);
        }
        return runToolsAsyncChain(ctx, hooks, shown, roundNo, barrier);
    }

    /**
     * 异步工具链（AL-A2 + Phase 3b 泛化）：逐工具串行组合——取消检查、beforeTool 在
     * 回调执行器；工具本体按来源分流（{@link AsyncToolExecutor} 的 future 直接使用
     * 不再包 io；普通执行器 supplyAsync 到 io 池）；afterTool 与写回回到回调执行器。
     * 执行器故障沿链传播终止 run。
     */
    private CompletableFuture<Void> runToolsAsyncChain(final AgentLoopContext ctx,
                                                       final List<AgentLifecycle> hooks,
                                                       final LlmResult shown, final int roundNo,
                                                       final SerialCallbackExecutor barrier) {
        final boolean asyncExecutor = toolExecutor instanceof AsyncToolExecutor;
        final List<LlmToolCall> calls = shown.getToolCalls();
        final boolean[] skipRest = new boolean[1];
        final Executor cbExec = callbackExec(barrier);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < calls.size(); i++) {
            final int index = i;
            chain = chain.thenComposeAsync(ignored -> {
                if (skipRest[0]) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                LlmToolCall call = calls.get(index);
                if (ctx.isCancelled()) {
                    appendSkipped(ctx, calls.subList(index, calls.size()), "未执行：调用已取消");
                    skipRest[0] = true;
                    return CompletableFuture.<Void>completedFuture(null);
                }
                AgentToolCall mutable = new AgentToolCall(call.getId(), call.getName(),
                        call.getArgumentsJson());
                String denial = null;
                for (AgentLifecycle hook : hooks) {
                    if (hook == null) {
                        continue;
                    }
                    ToolDecision decision = hook.beforeTool(ctx, mutable);
                    if (decision == null) {
                        decision = ToolDecision.allow();
                    }
                    if (!decision.isAllowed()) {
                        denial = Strings.isBlank(decision.getReason())
                                ? "已拒绝这次工具调用" : decision.getReason();
                        break;
                    }
                    if (decision.getArgumentsJson() != null) {
                        mutable.setArgumentsJson(decision.getArgumentsJson());
                    }
                }
                if (denial != null) {
                    ctx.getMessages().add(LlmMessage.toolResult(call.getId(), denial));
                    return CompletableFuture.<Void>completedFuture(null);
                }
                final CompletableFuture<String> one;
                if (asyncExecutor) {
                    CompletableFuture<String> produced;
                    try {
                        produced = ((AsyncToolExecutor) toolExecutor).executeOneAsync(
                                ctx.getTools(), new LlmToolCall(mutable.getId(), mutable.getName(),
                                        mutable.getArgumentsJson()), roundNo);
                    } catch (RuntimeException ex) {
                        // 按 ToolExecutor SPI 契约，业务异常应由实现自行兜住；走到这里说明是执行器故障。
                        log.warn("工具执行器故障 round={} callId={} tool={} cause={}",
                                Integer.valueOf(roundNo), mutable.getId(), mutable.getName(),
                                ex.toString());
                        throw new LlmCallException("工具执行器故障", ex);
                    }
                    if (produced == null) {
                        throw new LlmCallException("工具执行器返回了空 future");
                    }
                    one = produced;
                } else {
                    final AgentToolCall callForExecute = mutable;
                    one = CompletableFuture.supplyAsync(
                            () -> execute(ctx.getTools(), callForExecute, roundNo), executor.io());
                }
                return one.thenAcceptAsync(text -> {
                    String resultText = text == null ? "" : text;
                    for (AgentLifecycle hook : hooks) {
                        if (hook == null) {
                            continue;
                        }
                        String changed = hook.afterTool(ctx, mutable, resultText);
                        if (changed != null) {
                            resultText = changed;
                        }
                    }
                    ctx.getMessages().add(LlmMessage.toolResult(call.getId(), resultText));
                }, cbExec);
            }, cbExec);
        }
        return chain;
    }

    /**
     * 并行工具异步版（AL-A1 + Phase 3b）：预检在回调执行器 → 允许的调用并发执行
     * 在 toolPool（M-4：异步入口不做"单允许就地执行"捷径，避免工具本体落在
     * 传输/回调线程）→ 汇合去阻塞化（M-3：fail-fast 非阻塞组合，不在回调线程
     * join）→ 写回在回调执行器。
     */
    private CompletableFuture<Void> runToolsParallelAsync(final AgentLoopContext ctx,
                                                          final List<AgentLifecycle> hooks,
                                                          final LlmResult shown,
                                                          final int roundNo,
                                                          final SerialCallbackExecutor barrier) {
        final List<LlmToolCall> calls = shown.getToolCalls();
        final Executor cbExec = callbackExec(barrier);
        final ParallelPrecheck[] preHolder = new ParallelPrecheck[1];
        final String[][] textsHolder = new String[1][];
        return CompletableFuture.runAsync(new Runnable() {
            @Override
            public void run() {
                preHolder[0] = precheckParallel(ctx, hooks, calls);
            }
        }, cbExec).thenCompose(new java.util.function.Function<Object, CompletableFuture<Void>>() {
            @Override
            public CompletableFuture<Void> apply(Object ignored) {
                ParallelPrecheck pre = preHolder[0];
                if (pre.cancelledHandled) {
                    return CompletableFuture.completedFuture(null);
                }
                final int size = calls.size();
                final String[] texts = new String[size];
                textsHolder[0] = texts;
                final List<CompletableFuture<String>> futures =
                        new ArrayList<CompletableFuture<String>>();
                boolean anyAllowed = false;
                for (int i = 0; i < size; i++) {
                    if (pre.denied[i]) {
                        continue;
                    }
                    anyAllowed = true;
                    final AgentToolCall call = pre.mutable.get(i);
                    futures.add(CompletableFuture.supplyAsync(
                            () -> execute(ctx.getTools(), call, roundNo), toolPool()));
                }
                if (!anyAllowed) {
                    return CompletableFuture.completedFuture(null);
                }
                return convergeFailFast(futures).thenApply(new java.util.function.Function<Void, Void>() {
                    @Override
                    public Void apply(Void ignored) {
                        for (int i = 0, f = 0; i < size; i++) {
                            if (pre.denied[i]) {
                                continue;
                            }
                            String text = futures.get(f++).getNow(null);
                            texts[i] = text == null ? "" : text;
                        }
                        return null;
                    }
                });
            }
        }).thenRun(new Runnable() {
            @Override
            public void run() {
                if (!preHolder[0].cancelledHandled) {
                    writeBackParallel(ctx, hooks, calls, preHolder[0], textsHolder[0]);
                }
            }
        });
    }

    /**
     * 并行批次汇合（M-3 非阻塞版）：任一失败立即异常完成（fail-fast，不等其余），
     * 全部成功才正常完成；不在调用线程阻塞等待。
     */
    private static CompletableFuture<Void> convergeFailFast(
            List<CompletableFuture<String>> futures) {
        final CompletableFuture<Void> converged = new CompletableFuture<Void>();
        final List<CompletableFuture<String>> inFlight =
                new ArrayList<CompletableFuture<String>>();
        for (CompletableFuture<String> future : futures) {
            if (future == null) {
                continue;
            }
            inFlight.add(future);
            future.whenComplete(new java.util.function.BiConsumer<String, Throwable>() {
                @Override
                public void accept(String text, Throwable error) {
                    if (error != null) {
                        converged.completeExceptionally(error);
                    }
                }
            });
        }
        CompletableFuture.allOf(inFlight.toArray(new CompletableFuture[0]))
                .whenComplete(new java.util.function.BiConsumer<Void, Throwable>() {
                    @Override
                    public void accept(Void ignored, Throwable error) {
                        if (error != null) {
                            converged.completeExceptionally(error);
                        } else {
                            converged.complete(null);
                        }
                    }
                });
        return converged;
    }

    /**
     * 单次工具执行走 ToolExecutor。逐条调用是为了保留 beforeTool/afterTool 的顺序；
     * 批量并行仍由 gateway.toolLoop 的 ToolExecutor.executeAsync 负责。
     */
    private String execute(List<LlmTool> tools, AgentToolCall call, int roundNo) {
        LlmToolCall target = new LlmToolCall(call.getId(), call.getName(), call.getArgumentsJson());
        try {
            List<String> results = toolExecutor.execute(tools, Collections.singletonList(target));
            if (results == null || results.isEmpty()) {
                return "";
            }
            String text = results.get(0);
            return text == null ? "" : text;
        } catch (RuntimeException ex) {
            // 按 ToolExecutor SPI 契约，业务异常应由实现自行兜住；走到这里说明是执行器故障。
            log.warn("工具执行器故障 round={} callId={} tool={} cause={}",
                    Integer.valueOf(roundNo), call.getId(), call.getName(), ex.toString());
            throw new LlmCallException("工具执行器故障", ex);
        }
    }

    /**
     * 把本轮的 assistant 消息写回上下文。
     */
    private static void appendAssistant(AgentLoopContext ctx, LlmResult shown) {
        ctx.getMessages().add(LlmMessage.assistant(shown.getContent(),
                shown.getThinkingText(), shown.getThinkingSignature(), shown.getToolCalls()));
    }

    /**
     * 为没有执行的工具补合成 tool_result，避免上层复用上下文时出现 dangling call。
     */
    private static void appendSkipped(AgentLoopContext ctx, List<LlmToolCall> calls,
                                      String reason) {
        for (LlmToolCall call : calls) {
            if (call != null) {
                ctx.getMessages().add(LlmMessage.toolResult(call.getId(), reason));
            }
        }
    }

    private static void eachBeforeRun(List<AgentLifecycle> hooks, AgentLoopContext ctx) {
        for (AgentLifecycle hook : hooks) {
            if (hook != null) {
                hook.beforeRun(ctx);
            }
        }
    }

    private static void eachBeforeModel(List<AgentLifecycle> hooks, AgentLoopContext ctx) {
        for (AgentLifecycle hook : hooks) {
            if (hook != null) {
                hook.beforeModel(ctx);
            }
        }
    }

    private static void eachAfterModel(List<AgentLifecycle> hooks, AgentLoopContext ctx,
                                       AgentModelOutput output) {
        for (AgentLifecycle hook : hooks) {
            if (hook != null) {
                hook.afterModel(ctx, output);
            }
        }
    }

    private static void eachAfterRun(List<AgentLifecycle> hooks, AgentLoopContext ctx,
                                     LlmResult result, Throwable error) {
        for (AgentLifecycle hook : hooks) {
            if (hook == null) {
                continue;
            }
            try {
                hook.afterRun(ctx, result, error);
            } catch (Throwable ex) {
                // 观察钩子：异常只记录，不覆盖已经确定的结果，也不阻止后续钩子。
                log.warn("afterRun 钩子异常，已忽略: {}", ex.toString());
            }
        }
    }

    private static LlmResult aborted(LlmResult last, int inputTokens, int outputTokens,
                                     int totalTokens, long latencyMs, int rounds) {
        if (last == null) {
            return new LlmResult("", null, null, LlmStopReason.ABORTED, LlmUsage.zero(),
                    null, null, null, latencyMs, rounds, null);
        }
        return accumulate(last, LlmStopReason.ABORTED, inputTokens, outputTokens, totalTokens,
                latencyMs, rounds);
    }

    private static LlmResult accumulate(LlmResult shown, LlmStopReason stop, int inputTokens,
                                        int outputTokens, int totalTokens, long latencyMs, int rounds) {
        return new LlmResult(shown.getContent(), shown.getThinkingText(),
                shown.getThinkingSignature(), stop,
                LlmUsage.of(inputTokens, outputTokens, totalTokens),
                shown.getProvider(), shown.getModelId(), shown.getThinkingLevel(),
                latencyMs, rounds, shown.getToolCalls());
    }

    private static LlmResult withContent(LlmResult source, String content) {
        return new LlmResult(content, source.getThinkingText(), source.getThinkingSignature(),
                source.getStopReason(),
                source.getUsage(), source.getProvider(), source.getModelId(),
                source.getThinkingLevel(), source.getLatencyMs(), source.getRounds(),
                source.getToolCalls());
    }
}
