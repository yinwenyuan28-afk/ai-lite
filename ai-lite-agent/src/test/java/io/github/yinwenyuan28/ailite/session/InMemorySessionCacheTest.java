package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import org.junit.Test;

import java.util.Collections;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内存会话缓存：TTL、淘汰、删除和防御性拷贝。
 */
public class InMemorySessionCacheTest {

    @Test
    public void putAndGetRoundTrip() {
        InMemorySessionCache cache = new InMemorySessionCache(10);
        AgentSession session = session("u", "s", 3L);
        session.setMessages(Collections.singletonList(AgentSessionMessage.user("hi")));

        cache.put(session, 60L);

        AgentSession loaded = cache.get("u", "s").get();
        assertThat(loaded.getVersion()).isEqualTo(3L);
        assertThat(loaded.getMessages()).hasSize(1);
        assertThat(loaded.getMessages().get(0).getText()).isEqualTo("hi");
    }

    @Test
    public void zeroTtlMeansNoExpiry() throws Exception {
        InMemorySessionCache cache = new InMemorySessionCache(10);
        cache.put(session("u", "s", 0L), 0L);

        Thread.sleep(50L);

        assertThat(cache.get("u", "s")).isPresent();
    }

    @Test
    public void positiveTtlExpires() throws Exception {
        InMemorySessionCache cache = new InMemorySessionCache(10);
        cache.put(session("u", "s", 0L), 1L);

        Thread.sleep(1100L);

        assertThat(cache.get("u", "s")).isEmpty();
    }

    @Test
    public void deleteRemovesEntry() {
        InMemorySessionCache cache = new InMemorySessionCache(10);
        cache.put(session("u", "s", 0L), 60L);

        cache.delete("u", "s");

        assertThat(cache.get("u", "s")).isEmpty();
    }

    @Test
    public void getReturnsDefensiveCopy() {
        InMemorySessionCache cache = new InMemorySessionCache(10);
        cache.put(session("u", "s", 0L), 60L);

        AgentSession first = cache.get("u", "s").get();
        first.getMessages().add(AgentSessionMessage.user("injected"));

        assertThat(cache.get("u", "s").get().getMessages()).isEmpty();
    }

    @Test
    public void maxEntriesEvictsOldest() throws Exception {
        InMemorySessionCache cache = new InMemorySessionCache(2);
        cache.put(session("u", "old", 0L), 60L);
        Thread.sleep(5L);
        cache.put(session("u", "middle", 0L), 60L);
        Thread.sleep(5L);
        cache.put(session("u", "new", 0L), 60L);

        assertThat(cache.get("u", "new")).isPresent();
        assertThat(cache.get("u", "old")).isEmpty();
    }

    @Test
    public void blankKeysAreRejected() {
        InMemorySessionCache cache = new InMemorySessionCache(10);

        assertThatThrownBy(() -> cache.get("", "s"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> cache.delete("u", " "))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("不能为空");
    }

    private static AgentSession session(String userId, String sessionId, long version) {
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setSessionId(sessionId);
        session.setVersion(version);
        session.setUpdatedAt(new Date());
        return session;
    }
}
