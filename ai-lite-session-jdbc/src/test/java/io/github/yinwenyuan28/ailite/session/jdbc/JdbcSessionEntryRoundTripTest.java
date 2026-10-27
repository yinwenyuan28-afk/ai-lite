package io.github.yinwenyuan28.ailite.session.jdbc;

import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.session.AgentSession;
import io.github.yinwenyuan28.ailite.session.AgentSessionEntry;
import io.github.yinwenyuan28.ailite.session.AgentSessionMessage;
import io.github.yinwenyuan28.ailite.session.CompactionEntry;
import io.github.yinwenyuan28.ailite.session.UsageEntry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RT2：真实 JDBC（H2）往返门禁。
 *
 * <p>核心模块的门禁测试只覆盖内存存储与编解码；这里补上"真的写进数据库再读回来"
 * 这一环。{@code MESSAGES_JSON} 是 CLOB，读写路径与内存完全不同，
 * 必须单独验证压缩条目与其余条目类型的字段完整性。</p>
 */
public class JdbcSessionEntryRoundTripTest {

    private JdbcAgentSessionStore store;

    @Before
    public void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:entry_rt_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE AI_LITE_SESSION ("
                + "USER_ID VARCHAR(128) NOT NULL, SESSION_ID VARCHAR(191) NOT NULL,"
                + "BIZ_ID VARCHAR(128), VERSION BIGINT NOT NULL, MESSAGES_JSON CLOB,"
                + "TITLE VARCHAR(255), MESSAGE_COUNT INT DEFAULT 0,"
                + "TOTAL_TOKENS BIGINT DEFAULT 0,"
                + "COMPACTION_LEASE_UNTIL BIGINT,"
                + "UPDATED_AT TIMESTAMP, PRIMARY KEY (USER_ID, SESSION_ID))");
        store = new JdbcAgentSessionStore(jdbc);
    }

    /**
     * RT2：压缩条目的四个关键字段必须完整往返。
     */
    @Test
    public void compactionEntrySurvivesJdbcRoundTripWithAllFields() {
        store.save(sessionWithCompaction());
        AgentSession loaded = store.find("u", "s").get();

        assertThat(ids(loaded)).containsExactly("m1", "m2", "c1", "m3", "m4");

        CompactionEntry compaction = compactionOf(loaded);
        assertThat(compaction.getSummary()).isEqualTo("用户问了天气；已改用 24 小时制。");
        assertThat(compaction.getFirstKeptEntryId()).isEqualTo("m3");
        assertThat(compaction.getTokensBefore()).isEqualTo(28000);
        assertThat(compaction.getSummaryModel()).isEqualTo("deepseek/deepseek-chat");
        assertThat(compaction.getSummaryTokens()).isEqualTo(180);
    }

    /**
     * RT2 补充：更新路径（第二次 save）同样不能丢压缩条目。
     */
    @Test
    public void compactionEntrySurvivesJdbcUpdatePath() {
        store.save(sessionWithCompaction());
        AgentSession loaded = store.find("u", "s").get();
        List<AgentSessionMessage> messages = loaded.getMessages();
        messages.add(AgentSessionMessage.user("再来一轮"));
        loaded.setMessages(messages);

        store.save(loaded);
        AgentSession updated = store.find("u", "s").get();

        assertThat(compactionOf(updated).getSummary()).isEqualTo("用户问了天气；已改用 24 小时制。");
        // 前五条顺序不变（含 c1），末尾多出本轮新增的一条 user。
        assertThat(ids(updated)).startsWith("m1", "m2", "c1", "m3", "m4").hasSize(6);
        assertThat(updated.getMessages()).hasSize(5);
    }

    /**
     * RT2 补充：usage 条目与消息混排时也要完整往返，且不进入消息视图。
     */
    @Test
    public void usageEntrySurvivesJdbcRoundTrip() {
        AgentSession session = new AgentSession();
        session.setUserId("u");
        session.setSessionId("s");
        session.setBizId("biz");
        List<AgentSessionEntry> entries = new ArrayList<AgentSessionEntry>();
        entries.add(AgentSessionMessage.of("m1", LlmRole.USER, "hi", null, null, null));
        entries.add(new UsageEntry("g1", UsageEntry.KIND_CHAT, "test", "m", 10, 5, 15));
        session.setEntries(entries);

        store.save(session);
        AgentSession loaded = store.find("u", "s").get();

        assertThat(ids(loaded)).containsExactly("m1", "g1");
        assertThat(loaded.getMessages()).hasSize(1);
        assertThat(loaded.nonMessageEntryCount()).isEqualTo(1);
    }

    private static AgentSession sessionWithCompaction() {
        AgentSession session = new AgentSession();
        session.setUserId("u");
        session.setSessionId("s");
        session.setBizId("biz");
        List<AgentSessionEntry> entries = new ArrayList<AgentSessionEntry>();
        entries.add(AgentSessionMessage.of("m1", LlmRole.SYSTEM, "你是助手", null, null, null));
        entries.add(AgentSessionMessage.of("m2", LlmRole.SYSTEM, "输出中文", null, null, null));
        entries.add(new CompactionEntry("c1", "用户问了天气；已改用 24 小时制。", "m3", 28000,
                "deepseek/deepseek-chat", 180));
        entries.add(AgentSessionMessage.of("m3", LlmRole.USER, "明天天气", null, null, null));
        entries.add(AgentSessionMessage.of("m4", LlmRole.ASSISTANT, "晴", null, null, null));
        session.setEntries(entries);
        return session;
    }

    private static List<String> ids(AgentSession session) {
        List<String> ids = new ArrayList<String>();
        for (AgentSessionEntry entry : session.getEntries()) {
            ids.add(entry.getId());
        }
        return ids;
    }

    private static CompactionEntry compactionOf(AgentSession session) {
        for (AgentSessionEntry entry : session.getEntries()) {
            if (entry instanceof CompactionEntry) {
                return (CompactionEntry) entry;
            }
        }
        throw new AssertionError("会话里没有压缩条目");
    }
}
