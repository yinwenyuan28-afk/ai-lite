package io.github.yinwenyuan28.ailite.session.jdbc;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.session.AgentSession;
import io.github.yinwenyuan28.ailite.session.AgentSessionMessage;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JDBC 会话存储：插入、版本递增、冲突、删除。
 */
public class JdbcAgentSessionStoreTest {

    private JdbcAgentSessionStore store;

    @Before
    public void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:session_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE AI_LITE_SESSION ("
                + "USER_ID VARCHAR(128) NOT NULL, SESSION_ID VARCHAR(191) NOT NULL,"
                + "BIZ_ID VARCHAR(128), VERSION BIGINT NOT NULL, MESSAGES_JSON CLOB,"
                + "UPDATED_AT TIMESTAMP, PRIMARY KEY (USER_ID, SESSION_ID))");
        store = new JdbcAgentSessionStore(jdbc);
    }

    @Test
    public void saveInsertsAndIncrementsVersion() {
        AgentSession session = session(0L);
        store.save(session);

        AgentSession loaded = store.find("u", "s").get();
        assertThat(loaded.getVersion()).isEqualTo(1L);
        assertThat(loaded.getMessages()).hasSize(1);
        assertThat(loaded.getMessages().get(0).getText()).isEqualTo("hi");
    }

    @Test
    public void saveUpdatesWhenVersionMatches() {
        store.save(session(0L));
        AgentSession loaded = store.find("u", "s").get();
        loaded.setMessages(Collections.singletonList(AgentSessionMessage.user("again")));

        store.save(loaded);
        AgentSession updated = store.find("u", "s").get();

        assertThat(updated.getVersion()).isEqualTo(2L);
        assertThat(updated.getMessages().get(0).getText()).isEqualTo("again");
    }

    @Test
    public void staleVersionIsRejected() {
        AgentSession original = session(0L);
        store.save(original);

        assertThatThrownBy(() -> store.save(original))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("版本冲突");
    }

    @Test
    public void deleteAndExists() {
        store.save(session(0L));
        assertThat(store.exists("u", "s")).isTrue();

        store.delete("u", "s");
        assertThat(store.exists("u", "s")).isFalse();
        assertThat(store.find("u", "s").isPresent()).isFalse();
    }

    private static AgentSession session(long version) {
        AgentSession session = new AgentSession();
        session.setUserId("u");
        session.setSessionId("s");
        session.setBizId("biz");
        session.setVersion(version);
        session.setMessages(Collections.singletonList(AgentSessionMessage.user("hi")));
        return session;
    }
}
