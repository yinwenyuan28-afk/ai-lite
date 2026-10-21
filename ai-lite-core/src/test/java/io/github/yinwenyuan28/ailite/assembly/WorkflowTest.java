package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workflow 的步骤执行、顺序保持和 Agent loop 拒绝规则。
 */
public class WorkflowTest {

    private static final List<LlmMessage> MESSAGES = Collections.singletonList(LlmMessage.user("hi"));

    @Test
    public void oneDelegatesToGateway() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(stop("answer"));
        Workflow workflow = new Workflow(gateway(transport));

        LlmResult result = workflow.one(call());

        assertThat(result.getContent()).isEqualTo("answer");
        assertThat(transport.requests()).hasSize(1);
    }

    @Test
    public void runExecutesStepsInOrder() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(stop("first"))
                .enqueue(stop("second"));
        Workflow workflow = new Workflow(gateway(transport));

        List<LlmResult> results = workflow.run(Arrays.asList(
                Workflow.oneStep(call()),
                Workflow.oneStep(call())));

        assertThat(results).extracting("content").containsExactly("first", "second");
        assertThat(transport.requests()).hasSize(2);
    }

    @Test
    public void toolLoopStepExecutesTools() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
                        Collections.singletonList(new LlmToolCall("c1", "echo", "{\"q\":\"x\"}"))))
                .enqueue(stop("done"));
        Workflow workflow = new Workflow(gateway(transport));
        final int[] executions = new int[1];

        LlmResult result = workflow.execute(Workflow.toolLoopStep(call(),
                Collections.singletonList(tool(executions)), Integer.valueOf(4)));

        assertThat(result.getContent()).isEqualTo("done");
        assertThat(executions[0]).isEqualTo(1);
    }

    @Test
    public void agentLoopCannotBeAWorkflowStep() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
        LlmGateway gateway = gateway(transport);
        Workflow workflow = new Workflow(gateway);
        AgentLoop loop = new AgentLoop(gateway);

        assertThatThrownBy(() -> workflow.execute(loop))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("Agent loop");
        assertThatThrownBy(() -> workflow.run(Collections.singletonList(loop)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("Agent loop");
        assertThat(transport.requests()).isEmpty();
    }

    @Test
    public void unknownStepIsRejected() {
        Workflow workflow = new Workflow(gateway(new GatewayTestSupport.FakeTransport()));

        assertThatThrownBy(() -> workflow.execute("not a step"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("步骤");
    }

    @Test
    public void nullStepsAreRejected() {
        Workflow workflow = new Workflow(gateway(new GatewayTestSupport.FakeTransport()));

        assertThatThrownBy(() -> workflow.run(null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("步骤不能为空");
    }

    @Test
    public void nullGatewayIsRejected() {
        assertThatThrownBy(() -> new Workflow(null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("网关不能为空");
        assertThatThrownBy(() -> new AgentLoop(null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("网关不能为空");
    }

    private static LlmCall call() {
        return LlmCall.profile("biz", "main", MESSAGES);
    }

    private static LlmGateway gateway(GatewayTestSupport.FakeTransport transport) {
        return new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.<io.github.yinwenyuan28.ailite.llm.LlmTransport>singletonList(transport),
                new NoopLlmCallStore(), new DirectExecutorProvider(), new SequentialToolExecutor());
    }

    private static LlmCompletion stop(String content) {
        return new LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.zero(), null);
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
            public Map<String, Object> parameters() {
                return Collections.<String, Object>singletonMap("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                executions[0]++;
                return "ok:" + arguments.get("q");
            }
        };
    }
}
