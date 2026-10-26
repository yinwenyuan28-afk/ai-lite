package io.github.yinwenyuan28.ailite.llm;

import org.junit.Test;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;

/**
 * 网关的业务 id 校验、单次调用、工具循环和 round 语义。
 */
public class LlmGatewayTest {

    private static final List<LlmMessage> MESSAGES =
            Collections.singletonList(LlmMessage.user("hi"));

    @Test
    public void rejectsUnregisteredBizIdBeforeSending() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
        LlmGateway gateway = gateway(transport);

        assertThatThrownBy(() -> gateway.one(LlmCall.profile("other", "main", MESSAGES)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("业务 id 未登记");
        assertThat(transport.requests()).isEmpty();
    }

    @Test
    public void oneReturnsContentAndUsage() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("answer", null, LlmStopReason.STOP,
                        LlmUsage.of(3, 2, 5), null));
        LlmGateway gateway = gateway(transport);

        LlmResult result = gateway.one(LlmCall.profile("biz", "main", MESSAGES));

        assertThat(result.getContent()).isEqualTo("answer");
        assertThat(result.getUsage().getTotalTokens()).isEqualTo(5);
        assertThat(result.getRounds()).isEqualTo(1);
        assertThat(result.getThinkingLevel()).isEqualTo(LlmThinkingLevel.HIGH);
    }

    @Test
    public void roundDoesNotExecuteTools() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                        Collections.singletonList(new LlmToolCall("c1", "echo", "{}"))));
        LlmGateway gateway = gateway(transport);
        final int[] executions = new int[1];

        LlmResult result = gateway.round(LlmCall.profile("biz", "main", MESSAGES),
                Collections.singletonList(tool(executions)));

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(executions[0]).isZero();
    }

    @Test
    public void toolLoopExecutesToolAndFeedsResultBack() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                        Collections.singletonList(new LlmToolCall("c1", "echo", "{\"q\":\"x\"}"))))
                .enqueue(new LlmCompletion("done", null, LlmStopReason.STOP, LlmUsage.zero(), null));
        LlmGateway gateway = gateway(transport);
        final int[] executions = new int[1];

        LlmResult result = gateway.toolLoop(LlmCall.profile("biz", "main", MESSAGES),
                Collections.singletonList(tool(executions)), Integer.valueOf(4));

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(result.getRounds()).isEqualTo(2);
        assertThat(executions[0]).isEqualTo(1);
        List<LlmMessage> second = transport.requests().get(1).getMessages();
        assertThat(second).isNotEmpty();
        LlmMessage toolResult = second.get(second.size() - 1);
        assertThat(toolResult.getRole()).isEqualTo(LlmRole.TOOL_RESULT);
        assertThat(toolResult.getToolCallId()).isEqualTo("c1");
        assertThat(toolResult.getText()).isEqualTo("ok:x");
    }

    @Test
    public void toolLoopStopsAtMaxRounds() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                        Collections.singletonList(new LlmToolCall("c1", "echo", "{}"))))
                .enqueue(new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                        Collections.singletonList(new LlmToolCall("c2", "echo", "{}"))));
        LlmGateway gateway = gateway(transport);

        LlmResult result = gateway.toolLoop(LlmCall.profile("biz", "main", MESSAGES),
                Collections.singletonList(tool(new int[1])), Integer.valueOf(2));

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.MAX_ROUNDS);
        assertThat(result.getRounds()).isEqualTo(2);
    }

    @Test
    public void cancelledCallReturnsAborted() {
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
        LlmGateway gateway = gateway(transport);

        LlmResult result = gateway.oneByStream(LlmCall.profile("biz", "main", MESSAGES), null, cancel);

        assertThat(result.getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(transport.requests()).isEmpty();
    }

    @Test
    public void missingApiKeyFailsAtResolveTime() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
        LlmGateway gateway = gateway(transport, GatewayTestSupport.resolver(null));

        assertThatThrownBy(() -> gateway.one(LlmCall.profile("biz", "main", MESSAGES)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型密钥未配置");
    }

    private static LlmGateway gateway(GatewayTestSupport.FakeTransport transport) {
        return gateway(transport, GatewayTestSupport.resolver("key"));
    }

    private static LlmGateway gateway(GatewayTestSupport.FakeTransport transport,
                                      io.github.yinwenyuan28.ailite.config.ModelResolver resolver) {
        return new LlmGateway(resolver, Collections.<LlmTransport>singletonList(transport),
                new NoopLlmCallStore(), new DirectExecutorProvider(),
                new SequentialToolExecutor());
    }

    private static LlmTool tool(final int[] executions) {
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
            public java.util.Map<String, Object> parameters() {
                return Collections.<String, Object>singletonMap("type", "object");
            }

            @Override
            public String execute(java.util.Map<String, Object> arguments) {
                executions[0]++;
                return "ok:" + arguments.get("q");
            }
        };
    }
}
