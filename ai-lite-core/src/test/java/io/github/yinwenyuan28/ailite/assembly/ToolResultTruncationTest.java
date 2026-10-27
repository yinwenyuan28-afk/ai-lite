package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.internal.assembly.ToolResultTruncator;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具结果截断：覆盖串行 / 并行 / 异步三条写回路径与边界条件。
 *
 * <p>核心不变式：落盘内容（以及 {@code afterTool} 钩子看到的内容）必须是截断后的文本。
 * 为此截断必须先于钩子执行——{@code SessionRecorder} 本身就是 {@code afterTool} 钩子。</p>
 */
public class ToolResultTruncationTest {

    private static final String BIG = repeat("x", 5000);
    private static final int LIMIT = 100;

    // --------------------------------------------------------------- 单元层

    @Test
    public void textUnderLimitIsUnchanged() {
        assertThat(ToolResultTruncator.truncate("short", LIMIT)).isEqualTo("short");
    }

    @Test
    public void textExactlyAtLimitIsUnchanged() {
        String exact = repeat("a", LIMIT);

        assertThat(ToolResultTruncator.truncate(exact, LIMIT)).isEqualTo(exact);
    }

    @Test
    public void textOneOverLimitIsTruncatedWithMarker() {
        String over = repeat("a", LIMIT + 1);
        String truncated = ToolResultTruncator.truncate(over, LIMIT);

        assertThat(truncated).startsWith(repeat("a", LIMIT));
        assertThat(truncated).contains("输出过长已截断");
        assertThat(truncated).contains("原始 " + (LIMIT + 1));
    }

    @Test
    public void nullBecomesEmptyString() {
        assertThat(ToolResultTruncator.truncate(null, LIMIT)).isEmpty();
    }

    @Test
    public void zeroLimitMeansUnlimited() {
        assertThat(ToolResultTruncator.truncate(BIG, 0)).isEqualTo(BIG);
    }

    @Test
    public void resolveLimitPrefersOverrideThenConfig() {
        assertThat(ToolResultTruncator.resolveLimit(Integer.valueOf(50), true, 100))
                .isEqualTo(50);
        assertThat(ToolResultTruncator.resolveLimit(Integer.valueOf(0), true, 100))
                .isZero();
        assertThat(ToolResultTruncator.resolveLimit(null, true, 100)).isEqualTo(100);
        assertThat(ToolResultTruncator.resolveLimit(null, false, 100)).isZero();
    }

    // ------------------------------------------------------- 串行写回路径

    @Test
    public void sequentialPathWritesTruncatedText() {
        Recorder recorder = new Recorder();
        Harness harness = Harness.create(LIMIT, AgentLoop.ToolRunMode.SEQUENTIAL, false);
        harness.enqueueToolUse("echo");
        harness.enqueueStop("done");

        harness.loop.run(call(), harness.tools(), Collections.singletonList(recorder),
                Integer.valueOf(4), null);

        String written = harness.lastToolResultText();
        assertThat(written).startsWith(repeat("x", LIMIT));
        assertThat(written).contains("输出过长已截断");
        // 钩子看到的也必须是截断文本，否则落盘会是原文。
        assertThat(recorder.lastAfterToolText).contains("输出过长已截断");
    }

    // ------------------------------------------------------- 并行写回路径

    @Test
    public void parallelPathWritesTruncatedText() {
        Recorder recorder = new Recorder();
        Harness harness = Harness.create(LIMIT, AgentLoop.ToolRunMode.PARALLEL, false);
        harness.enqueueTwoTools();
        harness.enqueueStop("done");

        harness.loop.run(call(), harness.tools(), Collections.singletonList(recorder),
                Integer.valueOf(4), null);

        List<LlmMessage> results = harness.toolResults();
        assertThat(results).hasSize(2);
        for (LlmMessage message : results) {
            assertThat(message.getText()).contains("输出过长已截断");
        }
    }

    // ------------------------------------------------------- 异步写回路径

    @Test
    public void asyncPathWritesTruncatedText() throws Exception {
        Harness harness = Harness.create(LIMIT, AgentLoop.ToolRunMode.SEQUENTIAL, false);
        harness.enqueueToolUse("echo");
        harness.enqueueStop("done");

        harness.loop.runAsync(call(), harness.tools(), null, Integer.valueOf(4), null)
                .get(5, TimeUnit.SECONDS);

        assertThat(harness.lastToolResultText()).contains("输出过长已截断");
    }

    // --------------------------------------------------------- 单轮覆盖

    @Test
    public void perRoundOverrideTakesPrecedenceOverGlobal() {
        Harness harness = Harness.create(100, AgentLoop.ToolRunMode.SEQUENTIAL, false);
        harness.enqueueToolUse("echo");
        harness.enqueueStop("done");

        // 单轮覆盖为 0 = 不限，即使全局是 100。
        harness.loop.run(call(), harness.tools(), null, Integer.valueOf(4), null,
                Integer.valueOf(0));

        assertThat(harness.lastToolResultText()).isEqualTo(BIG);
    }

    @Test
    public void globalDefaultAppliesWhenNoOverride() {
        Harness harness = Harness.create(LIMIT, AgentLoop.ToolRunMode.SEQUENTIAL, false);
        harness.enqueueToolUse("echo");
        harness.enqueueStop("done");

        harness.loop.run(call(), harness.tools(), null, Integer.valueOf(4), null);

        assertThat(harness.lastToolResultText()).contains("输出过长已截断");
    }

    // ------------------------------------------------- 拒绝文本不受截断影响

    @Test
    public void denialTextIsNotTruncated() {
        Harness harness = Harness.create(LIMIT, AgentLoop.ToolRunMode.SEQUENTIAL, false);
        harness.enqueueToolUse("echo");
        harness.enqueueStop("done");
        AgentLifecycle denier = new AgentLifecycle() {
            @Override
            public ToolDecision beforeTool(AgentLoopContext ctx, AgentToolCall call) {
                return ToolDecision.deny("业务规则不允许");
            }
        };

        harness.loop.run(call(), harness.tools(), Collections.singletonList(denier),
                Integer.valueOf(4), null);

        assertThat(harness.lastToolResultText()).isEqualTo("业务规则不允许");
    }

    // ------------------------------------------------------------ 夹具

    private static String repeat(String unit, int times) {
        StringBuilder builder = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(unit);
        }
        return builder.toString();
    }

    private static LlmCall call() {
        return LlmCall.profile("biz", "main",
                Collections.singletonList(LlmMessage.user("hi")));
    }

    /**
     * 记录 afterTool 实际看到的文本，用于验证"截断先于钩子"。
     */
    private static final class Recorder implements AgentLifecycle {

        private String lastAfterToolText;

        @Override
        public String afterTool(AgentLoopContext ctx, AgentToolCall call, String resultText) {
            lastAfterToolText = resultText;
            return resultText;
        }
    }

    private static final class Harness {

        private final GatewayTestSupport.FakeTransport transport;
        private final AgentLoop loop;
        private final List<LlmTool> tools;

        private Harness(GatewayTestSupport.FakeTransport transport, AgentLoop loop,
                        List<LlmTool> tools) {
            this.transport = transport;
            this.loop = loop;
            this.tools = tools;
        }

        static Harness create(int maxChars, AgentLoop.ToolRunMode mode, boolean asyncTools) {
            GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
            LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                    Collections.singletonList(transport), new NoopLlmCallStore(),
                    new DirectExecutorProvider(), new SequentialToolExecutor());
            AgentLoop loop = new AgentLoop(gateway, new DirectExecutorProvider(), null, mode,
                    null, null, maxChars);
            return new Harness(transport, loop, Collections.singletonList(bigEcho()));
        }

        void enqueueToolUse(String name) {
            transport.enqueue(new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                    Collections.singletonList(new LlmToolCall("c1", name, "{}"))));
        }

        void enqueueTwoTools() {
            transport.enqueue(new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                    java.util.Arrays.asList(new LlmToolCall("c1", "echo", "{}"),
                            new LlmToolCall("c2", "echo", "{}"))));
        }

        void enqueueStop(String content) {
            transport.enqueue(new LlmCompletion(content, null, LlmStopReason.STOP,
                    LlmUsage.zero(), null));
        }

        List<LlmTool> tools() {
            return tools;
        }

        /** 取最后一轮请求里最末一条 tool_result 的文本。 */
        String lastToolResultText() {
            List<LlmMessage> results = toolResults();
            return results.isEmpty() ? null : results.get(results.size() - 1).getText();
        }

        /** 取最后一轮请求里的全部 tool_result。 */
        List<LlmMessage> toolResults() {
            List<io.github.yinwenyuan28.ailite.llm.LlmRequest> requests = transport.requests();
            if (requests.isEmpty()) {
                return Collections.emptyList();
            }
            List<LlmMessage> all = requests.get(requests.size() - 1).getMessages();
            java.util.List<LlmMessage> results =
                    new java.util.ArrayList<LlmMessage>();
            for (LlmMessage message : all) {
                if (message.getRole() == LlmRole.TOOL_RESULT) {
                    results.add(message);
                }
            }
            return results;
        }

        private static LlmTool bigEcho() {
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
                    return BIG;
                }
            };
        }
    }
}
