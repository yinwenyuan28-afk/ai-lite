package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.AsyncToolExecutor;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.llm.ToolExecutor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentLoop 异步路径的泵结构、终态对齐、取消收敛与 A 系列能力。
 *
 * <p>锁定异步计划的契约：蹦床不爆栈、异常路径 afterRun 恰好一次、终态优先级与同步一致
 * （parity 用例逐条镜像同步加固测试）、检查点②无幽灵轮、合成 tool_result 无 dangling、
 * 非合作 transport 由取消监听收敛、整轮超时、并行工具与异步工具执行器。</p>
 */
public class AgentLoopAsyncHardeningTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    // ---------------------------------------------------------------- 蹦床与不挂起

    @Test
    public void instantRoundsTrampolineNeverOverflowsStack() throws Exception {
        // 完成型 future + 连续 CONTINUE：旧实现同步递归会 StackOverflow，泵必须在 5 秒内收敛。
        AsyncScriptTransport transport = new AsyncScriptTransport(true);
        CapturingLifecycle always = new CapturingLifecycle();
        always.alwaysContinue = true;

        long started = System.currentTimeMillis();
        LlmResult result = loop(transport).runAsync(call(), null,
                Collections.singletonList(always), Integer.valueOf(10000), null)
                .get(5L, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.MAX_ROUNDS);
        assertThat(result.getRounds()).isEqualTo(10000);
        assertThat(System.currentTimeMillis() - started).isLessThan(5000L);
    }

    @Test
    public void errorFromTransportThreadConvergesFuture() throws Exception {
        // 传输线程上抛 Error（AssertionError）：future 必须异常完成，afterRun 恰好一次且能看到 error。
        AsyncScriptTransport transport = new AsyncScriptTransport(false);
        transport.failWith(new AssertionError("transport boom"));
        CapturingLifecycle recorder = new CapturingLifecycle();

        CompletableFuture<LlmResult> future = loop(transport).runAsync(call(), null,
                Collections.singletonList(recorder), Integer.valueOf(4), null);

        // 传输在 ForkJoinPool 上异步抛 Error：future 必须收敛；网关会把传输故障
        // 包成 LlmCallException("模型调用失败")，AssertionError 在 cause 链上。
        assertThatThrownBy(() -> future.get(2L, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        Throwable error = recorder.errorAtAfterRun;
        while (error != null && !(error instanceof AssertionError)) {
            error = error.getCause();
        }
        assertThat(error).as("cause 链上应能找到 AssertionError").isNotNull();
        assertThat(recorder.afterRun).isEqualTo(1);
    }

    @Test
    public void hookFailureRunsAfterRunExactlyOnceWithVisibleError() throws Exception {
        AsyncScriptTransport transport = new AsyncScriptTransport(false)
                .enqueue(completion("x", LlmStopReason.STOP, null));
        CapturingLifecycle broken = new CapturingLifecycle();
        broken.failInAfterModel = new IllegalStateException("hook down");

        CompletableFuture<LlmResult> future = loop(transport).runAsync(call(), null,
                Collections.singletonList(broken), Integer.valueOf(4), null);

        assertThatThrownBy(() -> future.get(2L, TimeUnit.SECONDS))
                .hasMessageContaining("hook down");
        assertThat(broken.afterRun).isEqualTo(1);
        assertThat(broken.resultAtAfterRun).isNull();
        assertThat(broken.errorAtAfterRun).isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- 取消与收敛

    @Test
    public void futureCancelRunsAfterRunOnceAndAbortsTransport() throws Exception {
        CooperativeNeverTransport transport = new CooperativeNeverTransport();
        CapturingLifecycle recorder = new CapturingLifecycle();

        CompletableFuture<LlmResult> future = loop(transport).runAsync(call(), null,
                Collections.singletonList(recorder), Integer.valueOf(4), null);
        awaitRequests(transport.started, 1);
        assertThat(future.cancel(true)).isTrue();

        assertThat(future.isCancelled()).isTrue();
        assertThat(recorder.afterRun).isEqualTo(1);
        // 两条收敛路径（取消方补跑 afterRun / 泵以 ABORTED 收敛）由 CAS 二选一，恰好一次。
        assertThat(recorder.errorAtAfterRun instanceof CancellationException
                || (recorder.resultAtAfterRun != null
                        && recorder.resultAtAfterRun.getStopReason() == LlmStopReason.ABORTED))
                .as("afterRun 要么看到 CancellationException，要么看到 ABORTED 结果")
                .isTrue();
        assertThat(transport.aborted.get()).isTrue();
    }

    @Test
    public void nonCooperativeTransportCancelConvergesWithinOneSecond() throws Exception {
        // 不 bindAbort、future 永不完成：只有取消监听器能救（AL-H05）。
        NeverTransport transport = new NeverTransport();
        LlmCancelHandle cancel = new LlmCancelHandle();

        CompletableFuture<LlmResult> future = loop(transport).runAsync(call(), null,
                null, Integer.valueOf(4), cancel);
        awaitRequests(transport.started, 1);
        long started = System.currentTimeMillis();
        cancel.cancel();

        LlmResult result = future.get(1L, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(System.currentTimeMillis() - started).isLessThan(1000L);
    }

    @Test
    public void preCancelledHandleReturnsAbortedZeroRequests() throws Exception {
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();
        AsyncScriptTransport transport = new AsyncScriptTransport(false);
        CapturingLifecycle recorder = new CapturingLifecycle();

        LlmResult result = loop(transport).runAsync(call(), null,
                Collections.singletonList(recorder), Integer.valueOf(4), cancel)
                .get(2L, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(result.getRounds()).isEqualTo(0);
        assertThat(transport.requests).isEmpty();
        assertThat(recorder.afterRun).isEqualTo(1);
        assertThat(recorder.afterModel).isEqualTo(0);
    }

    @Test
    public void beforeModelCancelProducesNoGhostRound() throws Exception {
        // 检查点②：beforeModel 内取消不请求、不计轮、不调 afterModel/afterTurn（AL-D07）。
        AsyncScriptTransport transport = new AsyncScriptTransport(false);
        CapturingLifecycle canceller = new CapturingLifecycle();
        canceller.cancelInBeforeModel = true;

        LlmResult result = loop(transport).runAsync(call(), null,
                Collections.singletonList(canceller), Integer.valueOf(4), null)
                .get(2L, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(result.getRounds()).isEqualTo(0);
        assertThat(transport.requests).isEmpty();
        assertThat(canceller.afterModel).isEqualTo(0);
        assertThat(canceller.turn).isEqualTo(0);
        assertThat(canceller.afterRun).isEqualTo(1);
    }

    @Test
    public void mutatingHooksListDuringRunDoesNotAffectLoop() throws Exception {
        // AL-D04：入参列表做防御性拷贝，run 进行中的修改不串扰。
        NeverTransport transport = new NeverTransport();
        List<AgentLifecycle> hooks = new ArrayList<AgentLifecycle>();
        CapturingLifecycle inside = new CapturingLifecycle();
        hooks.add(inside);

        CompletableFuture<LlmResult> future = loop(transport).runAsync(call(), null,
                hooks, Integer.valueOf(4), null);
        awaitRequests(transport.started, 1);
        CapturingLifecycle outside = new CapturingLifecycle();
        hooks.add(outside);
        future.cancel(true);

        assertThat(inside.afterRun).isEqualTo(1);
        assertThat(outside.beforeRun).isEqualTo(0);
        assertThat(outside.afterRun).isEqualTo(0);
    }

    // ---------------------------------------------------------------- 终态对齐（parity）

    @Test
    public void stopWithToolCallsExecutesToolsAndContinuesAsync() throws Exception {
        // AL-D06：STOP+toolCalls 在异步侧同样执行工具并继续（镜像同步版同名用例）。
        AsyncScriptTransport transport = new AsyncScriptTransport(false)
                .enqueue(completion("", LlmStopReason.STOP, toolCalls("c1")))
                .enqueue(stop("done"));
        int[] executions = new int[1];

        LlmResult result = loop(transport).runAsync(call(), tools("echo", executions),
                null, Integer.valueOf(4), null).get(2L, TimeUnit.SECONDS);

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(result.getRounds()).isEqualTo(2);
        assertThat(executions[0]).isEqualTo(1);
    }

    @Test
    public void lastRoundToolUseWithStopDecisionReturnsStopAsync() throws Exception {
        // AL-D01①：最后轮 TOOL_USE + hook STOP → STOP（旧异步先判 MAX_ROUNDS，错位）。
        AsyncScriptTransport transport = new AsyncScriptTransport(false)
                .enqueue(completion("", LlmStopReason.TOOL_USE, toolCalls("c1")));
        CapturingLifecycle stopper = new CapturingLifecycle();
        stopper.turnDecision = TurnDecision.stop();

        LlmResult result = loop(transport).runAsync(call(), tools("echo", new int[1]),
                Collections.singletonList(stopper), Integer.valueOf(1), null)
                .get(2L, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.STOP);
    }

    @Test
    public void continueExhaustedRoundsReturnsMaxRoundsAsync() throws Exception {
        // AL-D01②：CONTINUE+非工具轮耗尽 → MAX_ROUNDS（旧异步落模型 stopReason）。
        AsyncScriptTransport transport = new AsyncScriptTransport(false)
                .enqueue(stop("a")).enqueue(stop("b")).enqueue(stop("c"));
        CapturingLifecycle always = new CapturingLifecycle();
        always.alwaysContinue = true;

        LlmResult result = loop(transport).runAsync(call(), null,
                Collections.singletonList(always), Integer.valueOf(3), null)
                .get(2L, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.MAX_ROUNDS);
        assertThat(result.getRounds()).isEqualTo(3);
        assertThat(result.getContent()).isEqualTo("c");
    }

    @Test
    public void continueNonToolRoundAppendsAssistantAsync() throws Exception {
        // AL-D02：非工具轮 CONTINUE 必须先写回 assistant，否则下一轮重复同一请求。
        AsyncScriptTransport transport = new AsyncScriptTransport(false)
                .enqueue(stop("one")).enqueue(stop("two"));
        CapturingLifecycle recorder = new CapturingLifecycle();
        recorder.continueOnTurn = 1;

        LlmResult result = loop(transport).runAsync(call(), null,
                Collections.singletonList(recorder), Integer.valueOf(4), null)
                .get(2L, TimeUnit.SECONDS);

        assertThat(result.getRounds()).isEqualTo(2);
        LlmMessage second = transport.requests.get(1).getMessages().get(1);
        assertThat(second.getRole()).isEqualTo(LlmRole.ASSISTANT);
        assertThat(second.getText()).isEqualTo("one");
    }

    @Test
    public void abortedWithToolCallsSynthesizesSkippedAsync() throws Exception {
        // AL-D05：取消/ABORTED 带工具调用时补合成 tool_result，上下文不留 dangling。
        AsyncScriptTransport transport = new AsyncScriptTransport(false)
                .enqueue(completion("", LlmStopReason.ABORTED, toolCalls("c1", "c2")));
        CapturingLifecycle recorder = new CapturingLifecycle();
        int[] executions = new int[1];

        LlmResult result = loop(transport).runAsync(call(), tools("echo", executions),
                Collections.singletonList(recorder), Integer.valueOf(4), null)
                .get(2L, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(executions[0]).isEqualTo(0);
        assertThat(toolResultTexts(recorder.ctxAtAfterRun.getMessages())).containsExactly(
                "未执行：调用已取消", "未执行：调用已取消");
    }

    @Test
    public void lastRoundToolCallsSynthesizeMaxRoundsAsync() throws Exception {
        AsyncScriptTransport transport = new AsyncScriptTransport(false)
                .enqueue(completion("", LlmStopReason.TOOL_USE, toolCalls("c1", "c2")));
        CapturingLifecycle recorder = new CapturingLifecycle();

        LlmResult result = loop(transport).runAsync(call(), tools("echo", new int[1]),
                Collections.singletonList(recorder), Integer.valueOf(1), null)
                .get(2L, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.MAX_ROUNDS);
        assertThat(toolResultTexts(recorder.ctxAtAfterRun.getMessages())).containsExactly(
                "未执行：达到最大轮数", "未执行：达到最大轮数");
    }

    @Test
    public void errorWithToolCallsAndContinueAppendsAssistantOnceAsync() throws Exception {
        // AL-D08 异步版：ERROR+toolCalls+CONTINUE 时 assistant 只追加一次，无 dangling。
        AsyncScriptTransport transport = new AsyncScriptTransport(false)
                .enqueue(completion("", LlmStopReason.ERROR, toolCalls("c1")))
                .enqueue(stop("done"));
        CapturingLifecycle recorder = new CapturingLifecycle();
        recorder.continueOnTurn = 1;

        LlmResult result = loop(transport).runAsync(call(), tools("echo", new int[1]),
                Collections.singletonList(recorder), Integer.valueOf(4), null)
                .get(2L, TimeUnit.SECONDS);

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(result.getRounds()).isEqualTo(2);
        List<LlmMessage> messages = recorder.ctxAtAfterRun.getMessages();
        assertThat(assistantCount(messages)).isEqualTo(1);
        assertThat(toolResultTexts(messages)).containsExactly("未执行：本轮不执行工具");
    }

    // ---------------------------------------------------------------- 整轮超时（AL-A3）

    @Test
    public void wholeRunTimeoutConvergesAborted() throws Exception {
        NeverTransport transport = new NeverTransport();

        long started = System.currentTimeMillis();
        LlmResult result = loop(transport).runAsync(call(), null, null,
                Integer.valueOf(4), null, Long.valueOf(300L)).get(2L, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(System.currentTimeMillis() - started).isLessThan(2000L);
    }

    @Test
    public void wholeRunTimeoutDoesNotAffectEarlyCompletion() throws Exception {
        AsyncScriptTransport transport = new AsyncScriptTransport(true).enqueue(stop("fast"));

        LlmResult result = loop(transport).runAsync(call(), null, null,
                Integer.valueOf(4), null, Long.valueOf(5000L)).get(2L, TimeUnit.SECONDS);

        assertThat(result.getContent()).isEqualTo("fast");
        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.STOP);
    }

    // ---------------------------------------------------------------- 并行工具（AL-A1）

    @Test
    public void parallelToolsRunConcurrentlyAndWriteBackInOrder() throws Exception {
        // 两个工具互相等待对方启动：串行会超时，并行才能通过；结果按模型原始顺序写回。
        AsyncScriptTransport transport = new AsyncScriptTransport(false)
                .enqueue(completion("", LlmStopReason.TOOL_USE,
                        Arrays.asList(call("c1", "pa"), call("c2", "pb"))))
                .enqueue(stop("done"));
        final CountDownLatch bothStarted = new CountDownLatch(2);
        final boolean[] overlapped = new boolean[] {true, true};
        LlmTool pa = tool("pa", () -> {
            bothStarted.countDown();
            return bothStarted.await(2L, TimeUnit.SECONDS) ? "A" : "A-serial";
        });
        LlmTool pb = tool("pb", () -> {
            bothStarted.countDown();
            return bothStarted.await(2L, TimeUnit.SECONDS) ? "B" : "B-serial";
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AgentLoop parallel = new AgentLoop(gateway(transport), new DirectExecutorProvider(),
                    new SequentialToolExecutor(), AgentLoop.ToolRunMode.PARALLEL, pool);
            CapturingLifecycle recorder = new CapturingLifecycle();

            LlmResult result = parallel.run(call(), Arrays.asList(pa, pb),
                    Collections.singletonList(recorder), Integer.valueOf(4), null);

            assertThat(result.getContent()).isEqualTo("done");
            List<String> texts = toolResultTexts(recorder.ctxAtAfterRun.getMessages());
            assertThat(texts).containsExactly("A", "B");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void parallelSingleAllowedToolRunsOnCallerThread() throws Exception {
        // 两个调用 deny 一个：并行分支只剩 1 个允许项，直接在调用方线程执行，不跳线程。
        AsyncScriptTransport transport = new AsyncScriptTransport(true)
                .enqueue(completion("", LlmStopReason.TOOL_USE,
                        Arrays.asList(call("c1", "pa"), call("c2", "pb"))))
                .enqueue(stop("done"));
        final String[] threadName = new String[1];
        LlmTool pa = tool("pa", () -> {
            threadName[0] = Thread.currentThread().getName();
            return "A";
        });
        CapturingLifecycle denier = new CapturingLifecycle() {
            @Override
            public ToolDecision beforeTool(AgentLoopContext ctx, AgentToolCall call) {
                return "pb".equals(call.getName())
                        ? ToolDecision.deny("已拒绝 pb") : ToolDecision.allow();
            }
        };

        new AgentLoop(gateway(transport), new DirectExecutorProvider(),
                new SequentialToolExecutor(), AgentLoop.ToolRunMode.PARALLEL)
                .run(call(), Arrays.asList(pa, tool("pb", () -> "B")),
                        Collections.singletonList(denier), Integer.valueOf(4), null);

        assertThat(threadName[0]).isEqualTo(Thread.currentThread().getName());
    }

    @Test
    public void parallelExecutorFaultFailsRunAndSkipsAfterTool() throws Exception {
        AsyncScriptTransport transport = new AsyncScriptTransport(true)
                .enqueue(completion("", LlmStopReason.TOOL_USE,
                        Arrays.asList(call("c1", "pa"), call("c2", "pb"))));
        ToolExecutor faulty = new ToolExecutor() {
            @Override
            public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
                throw new IllegalStateException("executor down");
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AgentLoop parallel = new AgentLoop(gateway(transport), new DirectExecutorProvider(),
                    faulty, AgentLoop.ToolRunMode.PARALLEL, pool);
            CapturingLifecycle recorder = new CapturingLifecycle();

            assertThatThrownBy(() -> parallel.run(call(),
                    Arrays.asList(tool("pa", () -> "A"), tool("pb", () -> "B")),
                    Collections.singletonList(recorder), Integer.valueOf(4), null))
                    .isInstanceOf(LlmCallException.class)
                    .hasMessageContaining("工具执行器故障");
            assertThat(recorder.afterTool).isEqualTo(0);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 异步工具执行器（AL-A2）

    @Test
    public void asyncToolExecutorChainCompletesWithoutBlocking() throws Exception {
        AsyncScriptTransport transport = new AsyncScriptTransport(false)
                .enqueue(completion("", LlmStopReason.TOOL_USE, toolCalls("c1")))
                .enqueue(stop("done"));
        final AtomicBoolean asyncUsed = new AtomicBoolean(false);
        AsyncToolExecutor async = new AsyncToolExecutor() {
            @Override
            public CompletableFuture<String> executeOneAsync(List<LlmTool> tools,
                    LlmToolCall call, int roundNo) {
                asyncUsed.set(true);
                return CompletableFuture.completedFuture("ok-async");
            }

            @Override
            public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
                return Arrays.asList("ok-sync");
            }
        };
        CapturingLifecycle recorder = new CapturingLifecycle();

        LlmResult result = new AgentLoop(gateway(transport), new DirectExecutorProvider(), async)
                .runAsync(call(), tools("echo", new int[1]),
                        Collections.singletonList(recorder), Integer.valueOf(4), null)
                .get(2L, TimeUnit.SECONDS);

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(asyncUsed.get()).isTrue();
        assertThat(toolResultTexts(recorder.ctxAtAfterRun.getMessages()))
                .containsExactly("ok-async");
    }

    @Test
    public void asyncToolExecutorFaultFailsRun() throws Exception {
        AsyncScriptTransport transport = new AsyncScriptTransport(false)
                .enqueue(completion("", LlmStopReason.TOOL_USE, toolCalls("c1")));
        AsyncToolExecutor faulty = new AsyncToolExecutor() {
            @Override
            public CompletableFuture<String> executeOneAsync(List<LlmTool> tools,
                    LlmToolCall call, int roundNo) {
                throw new LlmCallException("工具执行器故障");
            }

            @Override
            public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
                return Collections.emptyList();
            }
        };

        CompletableFuture<LlmResult> future = new AgentLoop(gateway(transport),
                new DirectExecutorProvider(), faulty)
                .runAsync(call(), tools("echo", new int[1]), null, Integer.valueOf(4), null);

        assertThatThrownBy(() -> future.get(2L, TimeUnit.SECONDS))
                .hasMessageContaining("工具执行器故障");
    }

    // ---------------------------------------------------------------- 辅助

    private static AgentLoop loop(LlmTransport transport) {
        return new AgentLoop(gateway(transport));
    }

    private static LlmGateway gateway(LlmTransport transport) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmCall call() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    private static LlmCompletion stop(String content) {
        return new LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.zero(), null);
    }

    private static LlmCompletion completion(String content, LlmStopReason reason,
                                            List<LlmToolCall> toolCalls) {
        return new LlmCompletion(content, null, reason, LlmUsage.zero(), toolCalls);
    }

    private static List<LlmToolCall> toolCalls(String... ids) {
        List<LlmToolCall> calls = new ArrayList<LlmToolCall>();
        for (String id : ids) {
            calls.add(call(id, "echo"));
        }
        return calls;
    }

    private static LlmToolCall call(String id, String name) {
        return new LlmToolCall(id, name, "{}");
    }

    private static List<LlmTool> tools(String name, final int[] executions) {
        return Collections.singletonList(tool(name, () -> {
            executions[0]++;
            return "ok";
        }));
    }

    private interface ToolBody {
        String run() throws Exception;
    }

    private static LlmTool tool(String name, final ToolBody body) {
        return new LlmTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Map<String, Object> parameters() {
                return Collections.<String, Object>singletonMap("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                try {
                    return body.run();
                } catch (RuntimeException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
    }

    private static int assistantCount(List<LlmMessage> messages) {
        int count = 0;
        for (LlmMessage message : messages) {
            if (message.getRole() == LlmRole.ASSISTANT) {
                count++;
            }
        }
        return count;
    }

    private static List<String> toolResultTexts(List<LlmMessage> messages) {
        List<String> texts = new ArrayList<String>();
        for (LlmMessage message : messages) {
            if (message.getRole() == LlmRole.TOOL_RESULT) {
                texts.add(message.getText());
            }
        }
        return texts;
    }

    private static void awaitRequests(AtomicInteger started, int expected)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (started.get() < expected && System.nanoTime() < end) {
            Thread.sleep(5L);
        }
        assertThat(started.get()).isEqualTo(expected);
    }

    /**
     * 记录钩子调用与 afterRun 观察值的生命周期。
     */
    private static class CapturingLifecycle implements AgentLifecycle {

        int beforeRun;
        int afterRun;
        int afterModel;
        int beforeTool;
        int afterTool;
        int turn;
        int continueOnTurn = -1;
        boolean alwaysContinue;
        boolean cancelInBeforeModel;
        IllegalStateException failInAfterModel;
        ToolDecision toolDecision = ToolDecision.allow();
        TurnDecision turnDecision = TurnDecision.modelDecides();
        LlmResult resultAtAfterRun;
        Throwable errorAtAfterRun;
        AgentLoopContext ctxAtAfterRun;

        @Override
        public void beforeRun(AgentLoopContext ctx) {
            beforeRun++;
            ctxAtAfterRun = ctx;
        }

        @Override
        public void beforeModel(AgentLoopContext ctx) {
            if (cancelInBeforeModel) {
                ctx.getCancel().cancel();
            }
        }

        @Override
        public void afterModel(AgentLoopContext ctx, AgentModelOutput output) {
            afterModel++;
            if (failInAfterModel != null) {
                throw failInAfterModel;
            }
        }

        @Override
        public ToolDecision beforeTool(AgentLoopContext ctx, AgentToolCall call) {
            beforeTool++;
            return toolDecision;
        }

        @Override
        public String afterTool(AgentLoopContext ctx, AgentToolCall call, String resultText) {
            afterTool++;
            return resultText;
        }

        @Override
        public TurnDecision afterTurn(AgentLoopContext ctx, LlmResult roundResult) {
            turn++;
            if (alwaysContinue) {
                return TurnDecision.continueRound();
            }
            if (continueOnTurn > 0 && turn == continueOnTurn) {
                return TurnDecision.continueRound();
            }
            return turnDecision;
        }

        @Override
        public void afterRun(AgentLoopContext ctx, LlmResult lastResult, Throwable error) {
            afterRun++;
            resultAtAfterRun = lastResult;
            errorAtAfterRun = error;
            ctxAtAfterRun = ctx;
        }
    }

    /**
     * 按脚本返回的异步传输：默认在 ForkJoinPool 上完成（真实异步路径），
     * instant=true 时返回同步已完成的 future（蹦床/单线程场景）。
     * 脚本耗尽后返回 STOP("")；failWith 注入的 Error 会异步抛出。
     */
    private static final class AsyncScriptTransport implements LlmTransport {

        final List<LlmRequest> requests = new CopyOnWriteArrayList<LlmRequest>();
        private final List<LlmCompletion> script = new CopyOnWriteArrayList<LlmCompletion>();
        private final boolean instant;
        private volatile Error failure;

        AsyncScriptTransport(boolean instant) {
            this.instant = instant;
        }

        AsyncScriptTransport enqueue(LlmCompletion completion) {
            script.add(completion);
            return this;
        }

        void failWith(Error error) {
            this.failure = error;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            if (!script.isEmpty()) {
                return script.remove(0);
            }
            return new LlmCompletion("", null, LlmStopReason.STOP, LlmUsage.zero(), null);
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            if (failure != null) {
                final Error error = failure;
                return CompletableFuture.supplyAsync(() -> {
                    throw error;
                });
            }
            if (instant) {
                return CompletableFuture.completedFuture(complete(request));
            }
            return CompletableFuture.supplyAsync(() -> complete(request));
        }
    }

    /** 永不完成、不配合 abort 的传输：非合作 transport 反例（AL-H05）。 */
    private static final class NeverTransport implements LlmTransport {

        final AtomicInteger started = new AtomicInteger();

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            started.incrementAndGet();
            return new CompletableFuture<LlmCompletion>();
        }
    }

    /** 永不主动完成但配合 bindAbort 的传输：验证 future.cancel 桥到底层。 */
    private static final class CooperativeNeverTransport implements LlmTransport {

        final AtomicInteger started = new AtomicInteger();
        final AtomicBoolean aborted = new AtomicBoolean(false);

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
            started.incrementAndGet();
            request.getCancel().bindAbort(() -> aborted.set(true));
            return new CompletableFuture<LlmCompletion>();
        }
    }
}
