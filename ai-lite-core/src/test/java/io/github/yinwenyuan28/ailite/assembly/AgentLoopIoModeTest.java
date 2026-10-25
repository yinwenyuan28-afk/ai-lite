package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.CallbackPolicy;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回调策略 mode=io 下的 AgentLoop：泵与钩子落在 ai-lite-callback-* 线程、
 * 工具本体落在载体线程、trampoline 在执行器模式下仍不爆栈、取消可用。
 */
public class AgentLoopIoModeTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    @Test
    public void hooksRunOnCallbackThreadsAndToolsOnIoThreads() throws Exception {
        ExecutorService callbackPool = namedPool(2);
        ExecutorService ioPool = Executors.newFixedThreadPool(2,
                namedFactory("ai-lite-io-"));
        try {
            ScriptedTransport transport = new ScriptedTransport(true)
                    .enqueue(completion("", LlmStopReason.TOOL_USE, toolCalls("c1")))
                    .enqueue(stop("done"));
            CapturingLifecycle recorder = new CapturingLifecycle();

            // 载体池用非 direct 的命名池：direct（内联 io）下工具本体会内联在回调线程，
            // 属回调策略计划 D-3 文档化的组合，由 starter WARN 提示。
            AgentLoop loop = new AgentLoop(gateway(transport), namedProvider(ioPool),
                    null, null, null, CallbackPolicy.io(callbackPool, 64));
            LlmResult result = loop.runAsync(call(),
                    Collections.<LlmTool>singletonList(tool(recorder)),
                    Collections.<AgentLifecycle>singletonList(recorder),
                    Integer.valueOf(4), null).get(3L, TimeUnit.SECONDS);

            assertThat(result.getContent()).isEqualTo("done");
            assertThat(recorder.beforeModelThread).startsWith("ai-lite-callback-");
            assertThat(recorder.afterModelThread).startsWith("ai-lite-callback-");
            assertThat(recorder.beforeToolThread).startsWith("ai-lite-callback-");
            assertThat(recorder.afterToolThread).startsWith("ai-lite-callback-");
            assertThat(recorder.afterTurnThread).startsWith("ai-lite-callback-");
            assertThat(recorder.afterRunThread).startsWith("ai-lite-callback-");
            // 工具本体走载体池（DirectExecutorProvider 是 inline——泛化链 inline 语义下
            // io() 即载体池；io 回调模式只改变钩子线程，工具本体仍在 io()）。
            assertThat(recorder.toolThread).startsWith("ai-lite-io-");
        } finally {
            callbackPool.shutdownNow();
            ioPool.shutdownNow();
        }
    }

    @Test
    public void trampolineStillWorksUnderIoMode() throws Exception {
        ExecutorService callbackPool = namedPool(2);
        try {
            ScriptedTransport transport = new ScriptedTransport(true);
            CapturingLifecycle always = new CapturingLifecycle();
            always.alwaysContinue = true;

            long started = System.currentTimeMillis();
            LlmResult result = new AgentLoop(gateway(transport), new DirectExecutorProvider(),
                    null, null, null, CallbackPolicy.io(callbackPool, 1024))
                    .runAsync(call(), null,
                            Collections.<AgentLifecycle>singletonList(always),
                            Integer.valueOf(5000), null).get(10L, TimeUnit.SECONDS);

            assertThat(result.getStopReason()).isEqualTo(LlmStopReason.MAX_ROUNDS);
            assertThat(result.getRounds()).isEqualTo(5000);
            assertThat(System.currentTimeMillis() - started).isLessThan(9000L);
        } finally {
            callbackPool.shutdownNow();
        }
    }

    @Test
    public void cancelStillWorksUnderIoMode() throws Exception {
        // io 模式下取消路径：泵在检查点收敛为 ABORTED，回调通道关闭，afterRun 恰好一次。
        ExecutorService callbackPool = namedPool(1);
        try {
            ScriptedTransport transport = new ScriptedTransport(true);
            CapturingLifecycle canceller = new CapturingLifecycle();
            canceller.cancelInBeforeModel = true;

            LlmResult result = new AgentLoop(gateway(transport), new DirectExecutorProvider(),
                    null, null, null, CallbackPolicy.io(callbackPool, 64))
                    .runAsync(call(), null,
                            Collections.<AgentLifecycle>singletonList(canceller),
                            Integer.valueOf(4), null).get(3L, TimeUnit.SECONDS);

            assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
            assertThat(result.getRounds()).isEqualTo(0);
            assertThat(transport.requests).isEmpty();
            assertThat(canceller.afterRun).isEqualTo(1);
        } finally {
            callbackPool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- 辅助

    /** 与 Starter 同名的回调池线程工厂，供线程名断言使用。 */
    private static ExecutorService namedPool(int size) {
        return Executors.newFixedThreadPool(size, namedFactory("ai-lite-callback-"));
    }

    private static java.util.concurrent.ThreadFactory namedFactory(final String prefix) {
        final AtomicInteger seq = new AtomicInteger();
        return new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, prefix + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    /** 命名载体池的 ExecutorProvider（gateway() 里的 gateway 构造不受影响）。 */
    private static io.github.yinwenyuan28.ailite.executor.ExecutorProvider namedProvider(
            final ExecutorService pool) {
        return new io.github.yinwenyuan28.ailite.executor.ExecutorProvider() {
            @Override
            public java.util.concurrent.Executor io() {
                return pool;
            }

            @Override
            public void close() {
                pool.shutdownNow();
            }
        };
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
            calls.add(new LlmToolCall(id, "echo", "{}"));
        }
        return calls;
    }

    /** 记录工具执行线程到 recorder。 */
    private static LlmTool tool(final CapturingLifecycle recorder) {
        return new LlmTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "echo";
            }

            @Override
            public Map<String, Object> parameters() {
                return Collections.<String, Object>singletonMap("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                recorder.toolThread = Thread.currentThread().getName();
                return "ok";
            }
        };
    }

    /**
     * 按脚本返回结果（instant=true 时同步完成 future）并记录请求。
     */
    private static final class ScriptedTransport implements LlmTransport {

        final List<LlmRequest> requests = new CopyOnWriteArrayList<LlmRequest>();
        private final List<LlmCompletion> script = new CopyOnWriteArrayList<LlmCompletion>();
        private final boolean instant;

        ScriptedTransport(boolean instant) {
            this.instant = instant;
        }

        ScriptedTransport enqueue(LlmCompletion completion) {
            script.add(completion);
            return this;
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
            if (instant) {
                return CompletableFuture.completedFuture(complete(request));
            }
            return CompletableFuture.supplyAsync(() -> complete(request));
        }
    }

    /** 记录各钩子执行线程名与 afterRun 计数。 */
    private static class CapturingLifecycle implements AgentLifecycle {

        String beforeModelThread;
        String afterModelThread;
        String beforeToolThread;
        String afterToolThread;
        String afterTurnThread;
        String afterRunThread;
        String toolThread;
        int afterRun;
        boolean alwaysContinue;
        boolean cancelInBeforeModel;

        @Override
        public void beforeModel(AgentLoopContext ctx) {
            beforeModelThread = Thread.currentThread().getName();
            if (cancelInBeforeModel) {
                ctx.getCancel().cancel();
            }
        }

        @Override
        public void afterModel(AgentLoopContext ctx, AgentModelOutput output) {
            afterModelThread = Thread.currentThread().getName();
        }

        @Override
        public ToolDecision beforeTool(AgentLoopContext ctx, AgentToolCall call) {
            beforeToolThread = Thread.currentThread().getName();
            return ToolDecision.allow();
        }

        @Override
        public String afterTool(AgentLoopContext ctx, AgentToolCall call, String resultText) {
            afterToolThread = Thread.currentThread().getName();
            return resultText;
        }

        @Override
        public TurnDecision afterTurn(AgentLoopContext ctx, LlmResult roundResult) {
            afterTurnThread = Thread.currentThread().getName();
            return alwaysContinue ? TurnDecision.continueRound() : TurnDecision.modelDecides();
        }

        @Override
        public void afterRun(AgentLoopContext ctx, LlmResult lastResult, Throwable error) {
            afterRunThread = Thread.currentThread().getName();
            afterRun++;
        }
    }
}
