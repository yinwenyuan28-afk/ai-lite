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
import io.github.yinwenyuan28.ailite.llm.ToolExecutor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentLoop 同步路径的终态、取消、钩子与工具语义。
 *
 * <p>这些用例锁定审查发现的契约：ABORTED/ERROR 不执行工具、取消中途补齐合成
 * tool_result、afterRun 恰好一次且不覆盖结果、执行器故障终止。</p>
 */
public class AgentLoopSyncHardeningTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    // ---------------------------------------------------------------- 终态矩阵

    @Test
    public void stopWithToolCallsExecutesToolsAndContinues() {
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(completion("thinking", LlmStopReason.STOP, toolCalls("c1")))
                .enqueue(stop("done"));
        AgentLoop loop = loop(transport);
        int[] executions = new int[1];

        LlmResult result = loop.run(call(), tools(executions), null, Integer.valueOf(4), null);

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(result.getRounds()).isEqualTo(2);
        assertThat(executions[0]).isEqualTo(1);
    }

    @Test
    public void abortedWithToolCallsDoesNotExecuteTools() {
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(completion("", LlmStopReason.ABORTED, toolCalls("c1")));
        AgentLoop loop = loop(transport);
        int[] executions = new int[1];

        LlmResult result = loop.run(call(), tools(executions), null, Integer.valueOf(4), null);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(executions[0]).isZero();
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void errorWithToolCallsDoesNotExecuteTools() {
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(completion("", LlmStopReason.ERROR, toolCalls("c1")));
        AgentLoop loop = loop(transport);
        int[] executions = new int[1];

        LlmResult result = loop.run(call(), tools(executions), null, Integer.valueOf(4), null);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ERROR);
        assertThat(executions[0]).isZero();
    }

    @Test
    public void errorWithToolCallsAndContinueAppendsAssistantOnce() {
        // AL-D08：ERROR+toolCalls 的合成路径已追加 assistant，CONTINUE 续行时不得重复追加，
        // 否则上下文出现第二条无 tool_result 的 tool_use（dangling）。
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(completion("", LlmStopReason.ERROR, toolCalls("c1")))
                .enqueue(stop("done"));
        AgentLoop loop = loop(transport);
        int[] executions = new int[1];
        CapturingLifecycle recorder = new CapturingLifecycle();
        recorder.continueOnTurn = 1;

        LlmResult result = loop.run(call(), tools(executions),
                Collections.singletonList(recorder), Integer.valueOf(4), null);

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(result.getRounds()).isEqualTo(2);
        List<LlmMessage> messages = recorder.ctxAtAfterRun.getMessages();
        int assistants = 0;
        for (LlmMessage message : messages) {
            if (message.getRole() == LlmRole.ASSISTANT) {
                assistants++;
            }
        }
        assertThat(assistants).isEqualTo(1);
        assertThat(toolResultTexts(messages)).containsExactly("未执行：本轮不执行工具");
    }

    @Test
    public void lastRoundToolCallsSynthesizeSkippedResults() {
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(completion("", LlmStopReason.TOOL_USE, toolCalls("c1", "c2")));
        AgentLoop loop = loop(transport);
        int[] executions = new int[1];
        CapturingLifecycle recorder = new CapturingLifecycle();

        LlmResult result = loop.run(call(), tools(executions),
                Collections.singletonList(recorder), Integer.valueOf(1), null);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.MAX_ROUNDS);
        assertThat(result.getToolCalls()).hasSize(2);
        assertThat(executions[0]).isZero();

        List<LlmMessage> messages = recorder.ctxAtAfterRun.getMessages();
        assertThat(toolResultTexts(messages)).containsExactly(
                "未执行：达到最大轮数", "未执行：达到最大轮数");
    }

    @Test
    public void stopDecisionWinsOverMaxRoundsOnLastRound() {
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(completion("", LlmStopReason.TOOL_USE, toolCalls("c1")));
        AgentLoop loop = loop(transport);
        CapturingLifecycle stopper = new CapturingLifecycle();
        stopper.turnDecision = TurnDecision.stop();

        LlmResult result = loop.run(call(), tools(new int[1]),
                Collections.singletonList(stopper), Integer.valueOf(1), null);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.STOP);
        assertThat(result.getRounds()).isEqualTo(1);
    }

    @Test
    public void continueOnNonToolRoundAppendsAssistantMessage() {
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(stop("first")).enqueue(stop("second"));
        AgentLoop loop = loop(transport);
        CapturingLifecycle continuer = new CapturingLifecycle();
        continuer.continueOnTurn = 1;

        LlmResult result = loop.run(call(), null, Collections.singletonList(continuer),
                Integer.valueOf(4), null);

        assertThat(result.getContent()).isEqualTo("second");
        assertThat(result.getRounds()).isEqualTo(2);

        List<LlmMessage> second = transport.requests.get(1).getMessages();
        LlmMessage assistant = lastAssistant(second);
        assertThat(assistant).isNotNull();
        assertThat(assistant.getText()).isEqualTo("first");
    }

    @Test
    public void continueExhaustingRoundsReturnsMaxRounds() {
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(stop("a")).enqueue(stop("b"));
        AgentLoop loop = loop(transport);
        CapturingLifecycle continuer = new CapturingLifecycle();
        continuer.alwaysContinue = true;

        LlmResult result = loop.run(call(), null, Collections.singletonList(continuer),
                Integer.valueOf(2), null);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.MAX_ROUNDS);
        assertThat(result.getRounds()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 取消

    @Test
    public void cancelAfterModelSkipsToolsAndSynthesizesResults() {
        final ScriptedTransport transport = new ScriptedTransport()
                .enqueue(completion("", LlmStopReason.TOOL_USE, toolCalls("c1")));
        AgentLoop loop = loop(transport);
        final LlmCancelHandle cancel = new LlmCancelHandle();
        int[] executions = new int[1];
        CapturingLifecycle canceller = new CapturingLifecycle() {
            @Override
            public void afterModel(AgentLoopContext ctx, AgentModelOutput output) {
                cancel.cancel();
            }
        };

        LlmResult result = loop.run(call(), tools(executions),
                Collections.singletonList(canceller), Integer.valueOf(4), cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(executions[0]).isZero();
        assertThat(toolResultTexts(canceller.ctxAtAfterRun.getMessages()))
                .containsExactly("未执行：调用已取消");
    }

    @Test
    public void cancelMidBatchSkipsRemainingToolsAndSynthesizes() {
        final LlmCancelHandle cancel = new LlmCancelHandle();
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(completion("", LlmStopReason.TOOL_USE, toolCalls("c1", "c2")))
                .enqueue(stop("never"));
        AgentLoop loop = loop(transport);
        final int[] executions = new int[1];
        CapturingLifecycle canceller = new CapturingLifecycle() {
            @Override
            public String afterTool(AgentLoopContext ctx, AgentToolCall toolCall, String text) {
                cancel.cancel();
                return text;
            }
        };

        LlmResult result = loop.run(call(), tools(executions),
                Collections.singletonList(canceller), Integer.valueOf(4), cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(executions[0]).isEqualTo(1);
        assertThat(toolResultTexts(canceller.ctxAtAfterRun.getMessages()))
                .containsExactly("ok:null", "未执行：调用已取消");
    }

    @Test
    public void preCancelledRunSendsNoRequestAndStillCallsHooks() {
        ScriptedTransport transport = new ScriptedTransport();
        AgentLoop loop = loop(transport);
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();
        CapturingLifecycle recorder = new CapturingLifecycle();

        LlmResult result = loop.run(call(), null, Collections.singletonList(recorder),
                Integer.valueOf(4), cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(result.getRounds()).isZero();
        assertThat(transport.requests).isEmpty();
        assertThat(recorder.beforeRun).isEqualTo(1);
        assertThat(recorder.afterRun).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 钩子

    @Test
    public void afterRunRunsExactlyOnceWhenHookThrows() {
        ScriptedTransport transport = new ScriptedTransport().enqueue(stop("done"));
        AgentLoop loop = loop(transport);
        CapturingLifecycle broken = new CapturingLifecycle() {
            @Override
            public void afterModel(AgentLoopContext ctx, AgentModelOutput output) {
                throw new IllegalStateException("hook down");
            }
        };
        CapturingLifecycle observer = new CapturingLifecycle();

        assertThatThrownBy(() -> loop.run(call(), null,
                Arrays.asList(broken, observer), Integer.valueOf(4), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hook down");

        assertThat(broken.afterRun).isEqualTo(1);
        assertThat(observer.afterRun).isEqualTo(1);
        assertThat(observer.errorAtAfterRun).isInstanceOf(IllegalStateException.class);
        assertThat(observer.resultAtAfterRun).isNull();
    }

    @Test
    public void afterRunExceptionDoesNotOverrideSuccess() {
        ScriptedTransport transport = new ScriptedTransport().enqueue(stop("done"));
        AgentLoop loop = loop(transport);
        CapturingLifecycle thrower = new CapturingLifecycle() {
            @Override
            public void afterRun(AgentLoopContext ctx, LlmResult lastResult) {
                throw new IllegalStateException("afterRun boom");
            }
        };
        CapturingLifecycle observer = new CapturingLifecycle();

        LlmResult result = loop.run(call(), null, Arrays.asList(thrower, observer),
                Integer.valueOf(4), null);

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(observer.afterRun).isEqualTo(1);
    }

    @Test
    public void beforeRunFailureDoesNotCallAfterRun() {
        ScriptedTransport transport = new ScriptedTransport();
        AgentLoop loop = loop(transport);
        CapturingLifecycle broken = new CapturingLifecycle() {
            @Override
            public void beforeRun(AgentLoopContext ctx) {
                throw new IllegalStateException("beforeRun down");
            }
        };

        assertThatThrownBy(() -> loop.run(call(), null, Collections.singletonList(broken),
                Integer.valueOf(4), null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(broken.afterRun).isZero();
        assertThat(transport.requests).isEmpty();
    }

    @Test
    public void eachHookRunsInOrderAndDenySkipsAfterTool() {
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(completion("", LlmStopReason.TOOL_USE, toolCalls("c1")))
                .enqueue(stop("done"));
        AgentLoop loop = loop(transport);
        int[] executions = new int[1];
        CapturingLifecycle denier = new CapturingLifecycle();
        denier.toolDecision = ToolDecision.deny("blocked");

        LlmResult result = loop.run(call(), tools(executions),
                Collections.singletonList(denier), Integer.valueOf(4), null);

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(executions[0]).isZero();
        assertThat(denier.beforeTool).isEqualTo(1);
        assertThat(denier.afterTool).isZero();
    }

    // ---------------------------------------------------------------- 工具

    @Test
    public void toolExecutorFailureTerminatesRun() {
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(completion("", LlmStopReason.TOOL_USE, toolCalls("c1")));
        ToolExecutor broken = new ToolExecutor() {
            @Override
            public List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls) {
                throw new IllegalStateException("executor down");
            }
        };
        AgentLoop loop = new AgentLoop(gateway(transport), new DirectExecutorProvider(), broken);

        assertThatThrownBy(() -> loop.run(call(), tools(new int[1]), null,
                Integer.valueOf(4), null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("工具执行器故障")
                .satisfies(ex -> assertThat(ex.getCause())
                        .isInstanceOf(IllegalStateException.class));

        assertThat(transport.requests).hasSize(1);
    }

    @Test
    public void toolBusinessErrorIsFedBackByExecutor() {
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(completion("", LlmStopReason.TOOL_USE, toolCalls("c1")))
                .enqueue(stop("done"));
        LlmTool failing = new LlmTool() {
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
                return Collections.emptyMap();
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                throw new IllegalStateException("business down");
            }
        };
        AgentLoop loop = loop(transport);

        LlmResult result = loop.run(call(), Collections.singletonList(failing), null,
                Integer.valueOf(4), null);

        assertThat(result.getContent()).isEqualTo("done");
        List<LlmMessage> second = transport.requests.get(1).getMessages();
        assertThat(second.get(second.size() - 1).getText()).isEqualTo("工具执行失败");
    }

    // ---------------------------------------------------------------- 无状态

    @Test
    public void concurrentRunsDoNotShareState() throws Exception {
        final AgentLoop loop = loop(new ScriptedTransport().enqueue(stop("ok")));
        final CountDownLatch start = new CountDownLatch(1);
        final List<LlmResult> results = new CopyOnWriteArrayList<LlmResult>();

        List<Thread> threads = new ArrayList<Thread>();
        for (int i = 0; i < 8; i++) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        results.add(loop.run(call(), null, null, Integer.valueOf(2), null));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "agent-run-" + i);
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(results).hasSize(8);
        for (LlmResult result : results) {
            assertThat(result.getContent()).isEqualTo("ok");
        }
    }

    @Test
    public void hookListMutationDoesNotAffectRun() {
        ScriptedTransport transport = new ScriptedTransport()
                .enqueue(stop("first")).enqueue(stop("second"));
        AgentLoop loop = loop(transport);
        CapturingLifecycle continuer = new CapturingLifecycle();
        continuer.continueOnTurn = 1;
        final List<AgentLifecycle> hooks = new ArrayList<AgentLifecycle>();
        hooks.add(new CapturingLifecycle() {
            @Override
            public void beforeRun(AgentLoopContext ctx) {
                hooks.clear();
            }
        });
        hooks.add(continuer);

        LlmResult result = loop.run(call(), null, hooks, Integer.valueOf(4), null);

        assertThat(result.getRounds()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 工具

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
            calls.add(new LlmToolCall(id, "echo", "{}"));
        }
        return calls;
    }

    private static List<LlmTool> tools(final int[] executions) {
        return Collections.singletonList(new LlmTool() {
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
                executions[0]++;
                return "ok:" + arguments.get("q");
            }
        });
    }

    private static LlmMessage lastAssistant(List<LlmMessage> messages) {
        LlmMessage found = null;
        for (LlmMessage message : messages) {
            if (message.getRole() == LlmRole.ASSISTANT) {
                found = message;
            }
        }
        return found;
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

    /**
     * 记录钩子调用次数与 afterRun 观察到的结果/异常。
     */
    private static class CapturingLifecycle implements AgentLifecycle {

        int beforeRun;
        int afterRun;
        int beforeTool;
        int afterTool;
        int turn;
        int continueOnTurn = -1;
        boolean alwaysContinue;
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
        public void afterRun(AgentLoopContext ctx, LlmResult lastResult) {
            afterRun++;
            resultAtAfterRun = lastResult;
            ctxAtAfterRun = ctx;
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
     * 按脚本返回结果并记录请求。
     */
    private static final class ScriptedTransport implements LlmTransport {

        final List<LlmRequest> requests = new CopyOnWriteArrayList<LlmRequest>();
        private final List<LlmCompletion> outcomes = new CopyOnWriteArrayList<LlmCompletion>();

        ScriptedTransport enqueue(LlmCompletion completion) {
            outcomes.add(completion);
            return this;
        }

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(LlmRequest request) {
            requests.add(request);
            int index = Math.min(requests.size() - 1, outcomes.size() - 1);
            return outcomes.get(index);
        }
    }
}
