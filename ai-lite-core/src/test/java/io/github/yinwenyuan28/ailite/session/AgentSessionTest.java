package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.GatewayTestSupport;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话编解码、内存存储和续聊循环。
 */
public class AgentSessionTest {

    @Test
    public void codecKeepsLegacyFieldNamesAndTimestamp() {
        String json = "{\"userId\":\"u\",\"sessionId\":\"s\",\"bizId\":\"b\",\"version\":3,"
                + "\"updatedAt\":1760940000000,\"messages\":["
                + "{\"id\":\"m1\",\"role\":\"ASSISTANT\",\"text\":\"answer\",\"thinkingText\":\"think\","
                + "\"toolCalls\":[{\"id\":\"c1\",\"name\":\"now\",\"argumentsJson\":\"{}\"}]},"
                + "{\"id\":\"m2\",\"role\":\"TOOL_RESULT\",\"text\":\"12:00\",\"toolCallId\":\"c1\"}]}";

        AgentSession session = AgentSessionCodec.decodeSession(json);

        assertThat(session.getUserId()).isEqualTo("u");
        assertThat(session.getVersion()).isEqualTo(3L);
        assertThat(session.getUpdatedAt().getTime()).isEqualTo(1760940000000L);
        assertThat(session.getMessages()).hasSize(2);
        assertThat(session.getMessages().get(0).getThinkingText()).isEqualTo("think");
        assertThat(session.getMessages().get(0).getToolCalls().get(0).getName()).isEqualTo("now");
        assertThat(session.getMessages().get(1).getRole()).isEqualTo(LlmRole.TOOL_RESULT);

        String encoded = AgentSessionCodec.encodeSession(session);
        assertThat(encoded).contains("\"updatedAt\":1760940000000");
        assertThat(encoded).contains("\"argumentsJson\":\"{}\"");
    }

    @Test
    public void codecKeepsThinkingSignature() {
        AgentSession session = session("u", "s", 0L);
        session.setMessages(Collections.singletonList(
                AgentSessionMessage.assistant("answer", "think", "sig", null)));

        String json = AgentSessionCodec.encodeSession(session);

        assertThat(json).contains("\"thinkingSignature\":\"sig\"");
        AgentSession decoded = AgentSessionCodec.decodeSession(json);
        assertThat(decoded.getMessages().get(0).getThinkingText()).isEqualTo("think");
        assertThat(decoded.getMessages().get(0).getThinkingSignature()).isEqualTo("sig");
        assertThat(decoded.getMessages().get(0).getText()).isEqualTo("answer");
    }

    @Test
    public void sessionToLlmKeepsThinkingSeparateWhenEnabled() {
        AgentSession session = session("u", "s", 0L);
        session.setMessages(Collections.singletonList(
                AgentSessionMessage.assistant("answer", "think", "sig", null)));

        LlmMessage withThinking = session.toLlmMessages(true).get(0);
        assertThat(withThinking.getText()).isEqualTo("answer");
        assertThat(withThinking.getThinkingText()).isEqualTo("think");
        assertThat(withThinking.getThinkingSignature()).isEqualTo("sig");

        LlmMessage withoutThinking = session.toLlmMessages().get(0);
        assertThat(withoutThinking.getText()).isEqualTo("answer");
        assertThat(withoutThinking.getThinkingText()).isNull();
    }

    @Test
    public void inMemoryStoreIncrementsVersionAndRejectsStaleWrite() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        AgentSession session = session("u", "s", 0L);
        session.setMessages(Collections.<AgentSessionMessage>emptyList());

        store.save(session);
        AgentSession loaded = store.find("u", "s").get();
        assertThat(loaded.getVersion()).isEqualTo(1L);

        AgentSession stale = session("u", "s", 0L);
        assertThatThrownBy(() -> store.save(stale))
                .isInstanceOf(io.github.yinwenyuan28.ailite.llm.LlmCallException.class)
                .hasMessageContaining("版本冲突");

        store.delete("u", "s");
        assertThat(store.exists("u", "s")).isFalse();
    }

    @Test
    public void sessionLoopPersistsUserAndAssistantTurns() {
        GatewayTestSupport.FakeTransport transport = new GatewayTestSupport.FakeTransport()
                .enqueue(new LlmCompletion("first", null, LlmStopReason.STOP, LlmUsage.zero(), null))
                .enqueue(new LlmCompletion("second", null, LlmStopReason.STOP, LlmUsage.zero(), null));
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(transport), new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        AgentSessionLoop loop = new AgentSessionLoop(new AgentLoop(gateway), store);

        AgentSession created = loop.ensureSession("u", "s", "biz");
        assertThat(created.getVersion()).isEqualTo(1L);

        AgentSessionLoop.ResumeResult first = loop.resume("u", "s",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<LlmTool>emptyList(), null, Integer.valueOf(4), null,
                Collections.singletonList(AgentSessionMessage.user("hello")));
        assertThat(first.getResult().getContent()).isEqualTo("first");
        assertThat(first.getSession().getVersion()).isEqualTo(2L);
        assertThat(first.getSession().getMessages()).hasSize(2);

        AgentSessionLoop.ResumeResult second = loop.resume("u", "s",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<LlmTool>emptyList(), null, Integer.valueOf(4), null,
                Collections.singletonList(AgentSessionMessage.user("again")));
        assertThat(second.getResult().getContent()).isEqualTo("second");
        assertThat(second.getSession().getVersion()).isEqualTo(3L);
        assertThat(second.getSession().getMessages()).hasSize(4);

        List<LlmMessage> sent = transport.requests().get(1).getMessages();
        assertThat(sent).extracting("role").contains(LlmRole.USER, LlmRole.ASSISTANT);
    }

    @Test
    public void resumeWithoutSessionFails() {
        LlmGateway gateway = new LlmGateway(GatewayTestSupport.resolver("key"),
                Collections.singletonList(new GatewayTestSupport.FakeTransport()),
                new NoopLlmCallStore(), new DirectExecutorProvider(), new SequentialToolExecutor());
        AgentSessionLoop loop = new AgentSessionLoop(new AgentLoop(gateway),
                new InMemoryAgentSessionStore(10, 60));

        assertThatThrownBy(() -> loop.resume("u", "missing",
                LlmCall.profile("biz", "main", Collections.<LlmMessage>emptyList()),
                Collections.<LlmTool>emptyList(), null, Integer.valueOf(1), null,
                Collections.singletonList(AgentSessionMessage.user("hi"))))
                .isInstanceOf(io.github.yinwenyuan28.ailite.llm.LlmCallException.class)
                .hasMessageContaining("会话不存在");
    }

    private static AgentSession session(String userId, String sessionId, long version) {
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setSessionId(sessionId);
        session.setBizId("biz");
        session.setVersion(version);
        return session;
    }
}
