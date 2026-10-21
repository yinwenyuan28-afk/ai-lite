package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import org.junit.Test;

import java.util.Collections;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内存会话存储的版本控制、消息级 CRUD、TTL 和防御性拷贝。
 */
public class InMemoryAgentSessionStoreTest {

    @Test
    public void saveCreatesAndBumpsVersion() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s", 0L));

        Optional<AgentSession> found = store.find("u", "s");
        assertThat(found).isPresent();
        assertThat(found.get().getVersion()).isEqualTo(1L);
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    @Test
    public void staleVersionIsRejected() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s", 0L));

        assertThatThrownBy(() -> store.save(session("u", "s", 0L)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("版本冲突");
        assertThatThrownBy(() -> store.save(session("u", "s", 5L)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("版本冲突");
    }

    @Test
    public void appendFindReplaceDeleteMessage() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s", 0L));
        AgentSessionMessage message = AgentSessionMessage.of("m1", LlmRole.USER, "hello", null, null, null);

        store.appendMessage("u", "s", message);

        assertThat(store.findMessage("u", "s", "m1")).isPresent();
        assertThat(store.find("u", "s").get().getVersion()).isEqualTo(2L);

        store.replaceMessage("u", "s", AgentSessionMessage.of("m1", LlmRole.USER, "changed", null, null, null));
        assertThat(store.findMessage("u", "s", "m1").get().getText()).isEqualTo("changed");

        store.deleteMessage("u", "s", "m1");
        assertThat(store.findMessage("u", "s", "m1")).isEmpty();
        assertThat(store.find("u", "s").get().getMessages()).isEmpty();
    }

    @Test
    public void toolCallMessageRoundTripsThroughStore() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s", 0L));
        AgentSessionMessage message = AgentSessionMessage.of("m1", LlmRole.ASSISTANT, "text",
                "thinking", Collections.singletonList(new LlmToolCall("c1", "echo", "{}")), null);

        store.appendMessage("u", "s", message);
        AgentSessionMessage found = store.findMessage("u", "s", "m1").get();

        assertThat(found.getThinkingText()).isEqualTo("thinking");
        assertThat(found.getToolCalls()).hasSize(1);
        assertThat(found.getToolCalls().get(0).getName()).isEqualTo("echo");
    }

    @Test
    public void appendRejectsDuplicateId() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s", 0L));
        store.appendMessage("u", "s", AgentSessionMessage.of("m1", LlmRole.USER, "a", null, null, null));

        assertThatThrownBy(() -> store.appendMessage("u", "s",
                AgentSessionMessage.of("m1", LlmRole.USER, "b", null, null, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("id 重复");
    }

    @Test
    public void messageOperationsOnMissingSessionFail() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);

        assertThatThrownBy(() -> store.appendMessage("u", "missing",
                AgentSessionMessage.of("m1", LlmRole.USER, "a", null, null, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("会话不存在");
        assertThatThrownBy(() -> store.replaceMessage("u", "missing",
                AgentSessionMessage.of("m1", LlmRole.USER, "a", null, null, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("会话不存在");
        assertThatThrownBy(() -> store.deleteMessage("u", "missing", "m1"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("会话不存在");
        assertThat(store.findMessage("u", "missing", "m1")).isEmpty();
    }

    @Test
    public void replaceAndDeleteRequireExistingMessage() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s", 0L));

        assertThatThrownBy(() -> store.replaceMessage("u", "s",
                AgentSessionMessage.of("missing", LlmRole.USER, "a", null, null, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("消息不存在");
        assertThatThrownBy(() -> store.deleteMessage("u", "s", "missing"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("消息不存在");
    }

    @Test
    public void blankKeysAndMessageIdsAreRejected() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);

        assertThatThrownBy(() -> store.find(" ", "s"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> store.appendMessage("u", "s", null))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("缺少 id");
        assertThatThrownBy(() -> store.findMessage("u", "s", " "))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("messageId");
    }

    @Test
    public void saveRejectsDuplicateMessageIds() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        AgentSession session = session("u", "s", 0L);
        session.setMessages(java.util.Arrays.asList(
                AgentSessionMessage.of("m1", LlmRole.USER, "a", null, null, null),
                AgentSessionMessage.of("m1", LlmRole.USER, "b", null, null, null)));

        assertThatThrownBy(() -> store.save(session))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("id 重复");
    }

    @Test
    public void expiredSessionIsInvisible() throws Exception {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 1);
        store.save(session("u", "s", 0L));
        assertThat(store.exists("u", "s")).isTrue();

        Thread.sleep(1100L);

        assertThat(store.find("u", "s")).isEmpty();
        assertThat(store.exists("u", "s")).isFalse();
    }

    @Test
    public void findReturnsDefensiveCopy() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s", 0L));

        AgentSession first = store.find("u", "s").get();
        first.getMessages().add(AgentSessionMessage.user("injected"));

        assertThat(store.find("u", "s").get().getMessages()).isEmpty();
    }

    @Test
    public void maxEntriesEvictsOldestSession() throws Exception {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(2, 60);
        store.save(session("u", "old", 0L));
        Thread.sleep(5L);
        store.save(session("u", "middle", 0L));
        Thread.sleep(5L);
        store.save(session("u", "new", 0L));

        assertThat(store.exists("u", "new")).isTrue();
        assertThat(store.exists("u", "old")).isFalse();
    }

    private static AgentSession session(String userId, String sessionId, long version) {
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setSessionId(sessionId);
        session.setBizId("biz");
        session.setVersion(version);
        session.setUpdatedAt(new Date());
        return session;
    }
}
