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
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentLoop 的 7 个生命周期时机、拒绝/停止共识、工具执行和异步版本。
 */
public class AgentLoopTest {

    private static final List<LlmMessage> MESSAGES = Collections.singletonList(LlmMessage.user("hi"));

    @Test
    public void hooksRunInOrderAndToolExecutes() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(toolUse("c1", "echo", "{\"q\":\"x\"}"))
                .enqueue(stop("done"));
        AgentLoop loop = new AgentLoop(gateway(transport));
        final int[] executions = new int[1];
        RecordingLifecycle first = new RecordingLifecycle("a");
        RecordingLifecycle second = new RecordingLifecycle("b");

        LlmResult result = loop.run(call(), Collections.singletonList(tool("echo", executions, null)),
                Arrays.asList(first, second), Integer.valueOf(4), null);

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(result.getRounds()).isEqualTo(2);
        assertThat(executions[0]).isEqualTo(1);
        assertThat(first.events).containsExactly(
                "a.beforeRun", "a.beforeModel", "a.afterModel", "a.beforeTool", "a.afterTool",
                "a.afterTurn", "a.beforeModel", "a.afterModel", "a.afterTurn", "a.afterRun");
        assertThat(second.events).containsExactly(
                "b.beforeRun", "b.beforeModel", "b.afterModel", "b.beforeTool", "b.afterTool",
                "b.afterTurn", "b.beforeModel", "b.afterModel", "b.afterTurn", "b.afterRun");
    }

    @Test
    public void denialWinsAndSkipsTool() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(toolUse("c1", "echo", "{}"))
                .enqueue(stop("done"));
        AgentLoop loop = new AgentLoop(gateway(transport));
        final int[] executions = new int[1];
        RecordingLifecycle denier = new RecordingLifecycle("a");
        denier.toolDecision = ToolDecision.deny("blocked");
        RecordingLifecycle later = new RecordingLifecycle("b");

        loop.run(call(), Collections.singletonList(tool("echo", executions, null)),
                Arrays.asList(denier, later), Integer.valueOf(4), null);

        assertThat(executions[0]).isZero();
        assertThat(later.events).doesNotContain("b.beforeTool");
        List<LlmMessage> second = transport.requests().get(1).getMessages();
        LlmMessage result = second.get(second.size() - 1);
        assertThat(result.getRole()).isEqualTo(LlmRole.TOOL_RESULT);
        assertThat(result.getText()).isEqualTo("blocked");
    }

    @Test
    public void allowCanRewriteArguments() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(toolUse("c1", "echo", "{\"q\":\"original\"}"))
                .enqueue(stop("done"));
        AgentLoop loop = new AgentLoop(gateway(transport));
        final Map<String, Object>[] received = new Map[1];
        RecordingLifecycle rewriter = new RecordingLifecycle("a");
        rewriter.toolDecision = ToolDecision.allow("{\"q\":\"rewritten\"}");

        loop.run(call(), Collections.singletonList(tool("echo", new int[1], received)),
                Collections.singletonList(rewriter), Integer.valueOf(4), null);

        assertThat(received[0].get("q")).isEqualTo("rewritten");
    }

    @Test
    public void afterModelCanRewriteAssistantContent() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(toolUse("c1", "echo", "{}"))
                .enqueue(stop("done"));
        AgentLoop loop = new AgentLoop(gateway(transport));
        RecordingLifecycle rewriter = new RecordingLifecycle("a");
        rewriter.rewrittenContent = "改写后的正文";

        loop.run(call(), Collections.singletonList(tool("echo", new int[1], null)),
                Collections.singletonList(rewriter), Integer.valueOf(4), null);

        List<LlmMessage> second = transport.requests().get(1).getMessages();
        LlmMessage assistant = null;
        for (LlmMessage message : second) {
            if (message.getRole() == LlmRole.ASSISTANT) {
                assistant = message;
            }
        }
        assertThat(assistant).isNotNull();
        assertThat(assistant.getText()).isEqualTo("改写后的正文");
    }

    @Test
    public void afterToolCanRewriteResult() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(toolUse("c1", "echo", "{}"))
                .enqueue(stop("done"));
        AgentLoop loop = new AgentLoop(gateway(transport));
        RecordingLifecycle rewriter = new RecordingLifecycle("a");
        rewriter.toolResult = "filtered";

        loop.run(call(), Collections.singletonList(tool("echo", new int[1], null)),
                Collections.singletonList(rewriter), Integer.valueOf(4), null);

        List<LlmMessage> second = transport.requests().get(1).getMessages();
        assertThat(second.get(second.size() - 1).getText()).isEqualTo("filtered");
    }

    @Test
    public void stopWinsOverContinue() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(stop("first"));
        AgentLoop loop = new AgentLoop(gateway(transport));
        RecordingLifecycle stopper = new RecordingLifecycle("a");
        stopper.turnDecision = TurnDecision.stop();
        RecordingLifecycle continuer = new RecordingLifecycle("b");
        continuer.turnDecision = TurnDecision.continueRound();

        LlmResult result = loop.run(call(), null, Arrays.asList(stopper, continuer),
                Integer.valueOf(4), null);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.STOP);
        assertThat(result.getRounds()).isEqualTo(1);
        assertThat(transport.requests()).hasSize(1);
    }

    @Test
    public void continueForcesAnotherRound() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(stop("first"))
                .enqueue(stop("second"));
        AgentLoop loop = new AgentLoop(gateway(transport));
        RecordingLifecycle continuer = new RecordingLifecycle("a");
        continuer.continueOnTurn = 1;

        LlmResult result = loop.run(call(), null, Collections.singletonList(continuer),
                Integer.valueOf(4), null);

        assertThat(result.getContent()).isEqualTo("second");
        assertThat(result.getRounds()).isEqualTo(2);
    }

    @Test
    public void toolUseOnLastRoundBecomesMaxRounds() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(toolUse("c1", "echo", "{}"));
        AgentLoop loop = new AgentLoop(gateway(transport));

        LlmResult result = loop.run(call(), Collections.singletonList(tool("echo", new int[1], null)),
                null, Integer.valueOf(1), null);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.MAX_ROUNDS);
        assertThat(result.getRounds()).isEqualTo(1);
    }

    @Test
    public void cancelledBeforeRunReturnsAbortedAndStillCallsAfterRun() {
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
        AgentLoop loop = new AgentLoop(gateway(transport));
        RecordingLifecycle recorder = new RecordingLifecycle("a");

        LlmResult result = loop.run(call(), null, Collections.singletonList(recorder),
                Integer.valueOf(4), cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.requests()).isEmpty();
        assertThat(recorder.events).containsExactly("a.beforeRun", "a.afterRun");
    }

    @Test
    public void cancellationAfterRoundStopsWithoutSavingNextRound() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(toolUse("c1", "echo", "{}"))
                .enqueue(stop("never"));
        AgentLoop loop = new AgentLoop(gateway(transport));
        final LlmCancelHandle cancel = new LlmCancelHandle();
        RecordingLifecycle canceller = new RecordingLifecycle("a") {
            @Override
            public void afterModel(AgentLoopContext ctx, AgentModelOutput output) {
                super.afterModel(ctx, output);
                cancel.cancel();
            }
        };

        LlmResult result = loop.run(call(), Collections.singletonList(tool("echo", new int[1], null)),
                Collections.singletonList(canceller), Integer.valueOf(4), cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(result.getRounds()).isEqualTo(1);
        assertThat(transport.requests()).hasSize(1);
    }

    @Test
    public void beforeRunCanReplaceToolList() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(toolUse("c1", "other", "{}"))
                .enqueue(stop("done"));
        AgentLoop loop = new AgentLoop(gateway(transport));
        final int[] original = new int[1];
        final int[] replacement = new int[1];
        RecordingLifecycle replacer = new RecordingLifecycle("a") {
            @Override
            public void beforeRun(AgentLoopContext ctx) {
                super.beforeRun(ctx);
                ctx.setTools(Collections.singletonList(tool("other", replacement, null)));
            }
        };

        loop.run(call(), Collections.singletonList(tool("echo", original, null)),
                Collections.singletonList(replacer), Integer.valueOf(4), null);

        assertThat(original[0]).isZero();
        assertThat(replacement[0]).isEqualTo(1);
    }

    @Test
    public void unknownToolAndBadArgumentsBecomeToolText() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(toolUse("c1", "missing", "{}"))
                .enqueue(toolUse("c2", "echo", "not-json"))
                .enqueue(stop("done"));
        AgentLoop loop = new AgentLoop(gateway(transport));

        loop.run(call(), Collections.singletonList(tool("echo", new int[1], null)),
                null, Integer.valueOf(4), null);

        List<LlmMessage> second = transport.requests().get(1).getMessages();
        assertThat(second.get(second.size() - 1).getText()).contains("未找到工具");
        List<LlmMessage> third = transport.requests().get(2).getMessages();
        assertThat(third.get(third.size() - 1).getText()).contains("不是合法 JSON");
    }

    @Test
    public void hookExceptionPropagatesInSyncRun() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(stop("done"));
        AgentLoop loop = new AgentLoop(gateway(transport));
        RecordingLifecycle broken = new RecordingLifecycle("a") {
            @Override
            public void afterModel(AgentLoopContext ctx, AgentModelOutput output) {
                throw new IllegalStateException("hook down");
            }
        };

        assertThatThrownBy(() -> loop.run(call(), null, Collections.singletonList(broken),
                Integer.valueOf(4), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hook down");
    }

    @Test
    public void asyncRunMatchesSyncSemantics() throws Exception {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(toolUse("c1", "echo", "{\"q\":\"x\"}"))
                .enqueue(stop("done"));
        AgentLoop loop = new AgentLoop(gateway(transport));
        final int[] executions = new int[1];
        RecordingLifecycle recorder = new RecordingLifecycle("a");

        LlmResult result = loop.runAsync(call(), Collections.singletonList(tool("echo", executions, null)),
                Collections.singletonList(recorder), Integer.valueOf(4), null)
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(result.getRounds()).isEqualTo(2);
        assertThat(executions[0]).isEqualTo(1);
        assertThat(recorder.events).containsExactly(
                "a.beforeRun", "a.beforeModel", "a.afterModel", "a.beforeTool", "a.afterTool",
                "a.afterTurn", "a.beforeModel", "a.afterModel", "a.afterTurn", "a.afterRun");
    }

    @Test
    public void cancellingAsyncFutureAbortsInFlightTransport() {
        final AtomicBoolean aborted = new AtomicBoolean();
        LlmTransport never = new LlmTransport() {
            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                throw new UnsupportedOperationException("async only");
            }

            @Override
            public java.util.concurrent.CompletableFuture<LlmCompletion> completeAsync(
                    LlmRequest request) {
                request.getCancel().bindAbort(new Runnable() {
                    @Override
                    public void run() {
                        aborted.set(true);
                    }
                });
                return new java.util.concurrent.CompletableFuture<LlmCompletion>();
            }
        };
        AgentLoop loop = new AgentLoop(gateway(never));

        CompletableFuture<LlmResult> future = loop.runAsync(call(), null, null,
                Integer.valueOf(4), null);
        future.cancel(true);

        assertThat(aborted).isTrue();
    }

    @Test
    public void asyncHookExceptionCompletesExceptionally() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(stop("done"));
        AgentLoop loop = new AgentLoop(gateway(transport));
        RecordingLifecycle broken = new RecordingLifecycle("a") {
            @Override
            public void afterModel(AgentLoopContext ctx, AgentModelOutput output) {
                throw new IllegalStateException("hook down");
            }
        };

        CompletableFuture<LlmResult> future = loop.runAsync(call(), null,
                Collections.singletonList(broken), Integer.valueOf(4), null);

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    public void asyncCancellationReturnsAborted() throws Exception {
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
        AgentLoop loop = new AgentLoop(gateway(transport));
        RecordingLifecycle recorder = new RecordingLifecycle("a");

        LlmResult result = loop.runAsync(call(), null, Collections.singletonList(recorder),
                Integer.valueOf(4), cancel).get(5, TimeUnit.SECONDS);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.requests()).isEmpty();
        assertThat(recorder.events).containsExactly("a.beforeRun", "a.afterRun");
    }

    @Test
    public void invalidMaxRoundsFailsFast() {
        AgentLoop loop = new AgentLoop(gateway(new GatewayTestSupport.FakeTransport()));

        assertThatThrownBy(() -> loop.run(call(), null, null, Integer.valueOf(0), null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("最大轮数");
        assertThatThrownBy(() -> loop.runAsync(call(), null, null, Integer.valueOf(0), null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("最大轮数");
    }

    private static LlmCall call() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    private static LlmGateway gateway(LlmTransport transport) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmCompletion stop(String content) {
        return new LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.zero(), null);
    }

    private static LlmCompletion toolUse(String id, String name, String arguments) {
        return new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                Collections.singletonList(new LlmToolCall(id, name, arguments)));
    }

    private static LlmTool tool(final String name, final int[] executions,
                                final Map<String, Object>[] received) {
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
            @SuppressWarnings("unchecked")
            public String execute(Map<String, Object> arguments) {
                executions[0]++;
                if (received != null) {
                    received[0] = arguments;
                }
                return "ok:" + arguments.get("q");
            }
        };
    }

    /**
     * 可配置的生命周期记录器。
     */
    static class RecordingLifecycle implements AgentLifecycle {

        final String name;
        final List<String> events = new ArrayList<String>();
        ToolDecision toolDecision = ToolDecision.allow();
        String rewrittenContent;
        String toolResult;
        TurnDecision turnDecision = TurnDecision.modelDecides();
        int continueOnTurn = -1;
        int turn;

        RecordingLifecycle(String name) {
            this.name = name;
        }

        @Override
        public void beforeRun(AgentLoopContext ctx) {
            events.add(name + ".beforeRun");
        }

        @Override
        public void beforeModel(AgentLoopContext ctx) {
            events.add(name + ".beforeModel");
        }

        @Override
        public void afterModel(AgentLoopContext ctx, AgentModelOutput output) {
            events.add(name + ".afterModel");
            if (rewrittenContent != null) {
                output.setContent(rewrittenContent);
            }
        }

        @Override
        public ToolDecision beforeTool(AgentLoopContext ctx, AgentToolCall call) {
            events.add(name + ".beforeTool");
            return toolDecision;
        }

        @Override
        public String afterTool(AgentLoopContext ctx, AgentToolCall call, String resultText) {
            events.add(name + ".afterTool");
            return toolResult == null ? resultText : toolResult;
        }

        @Override
        public TurnDecision afterTurn(AgentLoopContext ctx, LlmResult roundResult) {
            events.add(name + ".afterTurn");
            turn++;
            if (continueOnTurn > 0 && turn == continueOnTurn) {
                return TurnDecision.continueRound();
            }
            return turnDecision;
        }

        @Override
        public void afterRun(AgentLoopContext ctx, LlmResult lastResult) {
            events.add(name + ".afterRun");
        }
    }
}
