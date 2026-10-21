package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步续聊：加载、循环、落盘的 future 链路与取消语义。
 */
public class AgentSessionLoopAsyncTest {

    @Test
    public void resumeAsyncPersistsAssistantReply() throws Exception {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("answer", null, LlmStopReason.STOP,
                        LlmUsage.of(1, 2, 3), null));
        AgentSessionLoop sessionLoop = sessionLoop(transport);
        sessionLoop.ensureSession("u", "s", "biz");

        AgentSessionLoop.ResumeResult result = sessionLoop.resumeAsync("u", "s",
                LlmCall.profile("biz", "main", Collections.singletonList(LlmMessage.user("hi"))),
                null, null, Integer.valueOf(4), null,
                Collections.singletonList(AgentSessionMessage.user("hi")))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getResult().getContent()).isEqualTo("answer");
        assertThat(result.getSession().getMessages()).hasSize(2);
        assertThat(result.getSession().getMessages().get(0).getText()).isEqualTo("hi");
        assertThat(result.getSession().getMessages().get(1).getText()).isEqualTo("answer");
    }

    @Test
    public void cancelledAsyncResumeDoesNotPersist() throws Exception {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport();
        AgentSessionLoop sessionLoop = sessionLoop(transport);
        sessionLoop.ensureSession("u", "s", "biz");
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();

        AgentSessionLoop.ResumeResult result = sessionLoop.resumeAsync("u", "s",
                LlmCall.profile("biz", "main", Collections.singletonList(LlmMessage.user("hi"))),
                null, null, Integer.valueOf(4), cancel,
                Collections.singletonList(AgentSessionMessage.user("hi")))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getResult().getStopReason()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(result.getSession().getMessages()).isEmpty();
        assertThat(transport.requests()).isEmpty();
    }

    @Test
    public void missingSessionCompletesExceptionally() {
        AgentSessionLoop sessionLoop = sessionLoop(new GatewayTestSupport.FakeTransport());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                sessionLoop.resumeAsync("u", "missing",
                        LlmCall.profile("biz", "main", Collections.singletonList(LlmMessage.user("hi"))),
                        null, null, Integer.valueOf(4), null, null)
                        .get(5, TimeUnit.SECONDS)))
                .hasMessageContaining("会话不存在");
    }

    private static AgentSessionLoop sessionLoop(LlmTransport transport) {
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
        return new AgentSessionLoop(new AgentLoop(gateway),
                new InMemoryAgentSessionStore(10, 60));
    }
}
