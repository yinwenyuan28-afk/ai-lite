package io.github.yinwenyuan28.ailite.session.jdbc;

import io.github.yinwenyuan28.ailite.session.AgentSession;
import io.github.yinwenyuan28.ailite.session.AgentSessionMessage;
import io.github.yinwenyuan28.ailite.session.AgentSessionSummary;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDBC 会话列举与压缩租约。
 */
public class JdbcSessionListingAndLeaseTest {

    private JdbcTemplate jdbc;
    private JdbcAgentSessionStore store;

    @Before
    public void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:listing_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE AI_LITE_SESSION ("
                + "USER_ID VARCHAR(128) NOT NULL, SESSION_ID VARCHAR(191) NOT NULL,"
                + "BIZ_ID VARCHAR(128), VERSION BIGINT NOT NULL, MESSAGES_JSON CLOB,"
                + "TITLE VARCHAR(255), MESSAGE_COUNT INT DEFAULT 0,"
                + "TOTAL_TOKENS BIGINT DEFAULT 0, COMPACTION_LEASE_UNTIL BIGINT,"
                + "UPDATED_AT TIMESTAMP, PRIMARY KEY (USER_ID, SESSION_ID))");
        store = new JdbcAgentSessionStore(jdbc);
    }

    @Test
    public void listByUserReturnsOwnSessionsOrderedByUpdatedAt() {
        save("u1", "s1", "标题一", 100L);
        save("u1", "s2", "标题二", 200L);
        save("u2", "s3", "别人的", 300L);

        List<AgentSessionSummary> listed = store.listByUser("u1", 0, 10);

        assertThat(listed).hasSize(2);
        assertThat(listed).extracting("sessionId").containsExactly("s2", "s1");
        assertThat(listed.get(0).getTitle()).isEqualTo("标题二");
        assertThat(listed.get(0).getTotalTokens()).isEqualTo(200L);
    }

    @Test
    public void listByUserPaginatesWithStableOrder() {
        // 三条同一时间：靠 SESSION_ID DESC 兜底排序，否则翻页会重复或漏行。
        for (int i = 1; i <= 3; i++) {
            save("u", "s" + i, "标题" + i, 100L);
        }

        List<AgentSessionSummary> first = store.listByUser("u", 0, 2);
        List<AgentSessionSummary> second = store.listByUser("u", 2, 2);

        assertThat(first).extracting("sessionId").containsExactly("s3", "s2");
        assertThat(second).extracting("sessionId").containsExactly("s1");
    }

    @Test
    public void listByUserBeyondRangeReturnsEmpty() {
        save("u", "s1", "标题", 100L);

        assertThat(store.listByUser("u", 5, 10)).isEmpty();
    }

    @Test
    public void messageCountAndTokensArePersisted() {
        save("u", "s1", "标题", 123L);

        AgentSession loaded = store.find("u", "s1").get();
        assertThat(loaded.getTitle()).isEqualTo("标题");
        assertThat(loaded.getTotalTokens()).isEqualTo(123L);
        assertThat(loaded.getMessages()).hasSize(1);
    }

    // ------------------------------------------------------------- 租约

    @Test
    public void leaseIsExclusiveAndReleasable() {
        save("u", "s1", "标题", 100L);

        assertThat(store.tryAcquireCompaction("u", "s1", 60000L)).isTrue();
        assertThat(store.tryAcquireCompaction("u", "s1", 60000L)).isFalse();

        store.releaseCompaction("u", "s1");
        assertThat(store.tryAcquireCompaction("u", "s1", 60000L)).isTrue();
    }

    @Test
    public void expiredLeaseCanBeTakenOver() throws Exception {
        save("u", "s1", "标题", 100L);
        assertThat(store.tryAcquireCompaction("u", "s1", 1L)).isTrue();

        long deadline = System.currentTimeMillis() + 2000L;
        boolean acquired = false;
        while (System.currentTimeMillis() < deadline) {
            if (store.tryAcquireCompaction("u", "s1", 60000L)) {
                acquired = true;
                break;
            }
            Thread.sleep(5L);
        }
        assertThat(acquired).isTrue();
    }

    private void save(String userId, String sessionId, String title, long totalTokens) {
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setSessionId(sessionId);
        session.setBizId("biz");
        session.setTitle(title);
        session.setTotalTokens(totalTokens);
        session.setVersion(0L);
        session.setMessages(Collections.singletonList(AgentSessionMessage.user("hi")));
        store.save(session);
        // 让 UPDATED_AT 与 TOTAL_TOKENS 语义对齐，便于断言排序。
        jdbc.update("UPDATE AI_LITE_SESSION SET UPDATED_AT = ? WHERE USER_ID = ? AND SESSION_ID = ?",
                new java.sql.Timestamp(totalTokens), userId, sessionId);
    }
}
