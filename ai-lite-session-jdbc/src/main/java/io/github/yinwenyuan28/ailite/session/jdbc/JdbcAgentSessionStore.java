package io.github.yinwenyuan28.ailite.session.jdbc;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.session.AgentSession;
import io.github.yinwenyuan28.ailite.session.AgentSessionCodec;
import io.github.yinwenyuan28.ailite.session.AgentSessionStore;
import io.github.yinwenyuan28.ailite.internal.session.AgentSessionStoreSupport;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * JDBC 会话存储。只依赖 JdbcTemplate，不绑定 MyBatis 或 JPA。
 *
 * <p>表结构见 {@code src/main/resources/ai-lite-session.sql}。保存使用
 * version 条件更新，避免并发写静默覆盖。</p>
 */
public final class JdbcAgentSessionStore implements AgentSessionStore {

    private final JdbcTemplate jdbc;
    private final String table;

    public JdbcAgentSessionStore(JdbcTemplate jdbc) {
        this(jdbc, "AI_LITE_SESSION");
    }

    public JdbcAgentSessionStore(JdbcTemplate jdbc, String table) {
        if (jdbc == null) {
            throw new IllegalArgumentException("JdbcTemplate 不能为空");
        }
        this.jdbc = jdbc;
        this.table = safeTable(table);
    }

    @Override
    public Optional<AgentSession> find(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        List<AgentSession> rows = jdbc.query(
                "SELECT USER_ID, SESSION_ID, BIZ_ID, VERSION, MESSAGES_JSON, UPDATED_AT FROM "
                        + table + " WHERE USER_ID = ? AND SESSION_ID = ?",
                new Object[]{userId, sessionId}, ROW_MAPPER);
        return rows.isEmpty() ? Optional.<AgentSession>empty() : Optional.of(rows.get(0));
    }

    @Override
    public void save(AgentSession session) {
        AgentSessionStoreSupport.requireSession(session);
        Long current = currentVersion(session.getUserId(), session.getSessionId());
        if (current == null) {
            if (session.getVersion() != 0L) {
                throw new LlmCallException("会话版本冲突");
            }
            insert(session);
            return;
        }
        if (current.longValue() != session.getVersion()) {
            throw new LlmCallException("会话版本冲突");
        }
        int updated = jdbc.update(
                "UPDATE " + table + " SET BIZ_ID = ?, VERSION = ?, MESSAGES_JSON = ?, UPDATED_AT = ?"
                        + " WHERE USER_ID = ? AND SESSION_ID = ? AND VERSION = ?",
                session.getBizId(), Long.valueOf(current.longValue() + 1L),
                AgentSessionCodec.encodeMessages(session.getMessages()),
                new Timestamp(System.currentTimeMillis()),
                session.getUserId(), session.getSessionId(), Long.valueOf(current.longValue()));
        if (updated != 1) {
            throw new LlmCallException("会话版本冲突");
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        jdbc.update("DELETE FROM " + table + " WHERE USER_ID = ? AND SESSION_ID = ?",
                userId, sessionId);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM " + table + " WHERE USER_ID = ? AND SESSION_ID = ?",
                new Object[]{userId, sessionId}, Integer.class);
        return count != null && count.intValue() > 0;
    }

    private void insert(AgentSession session) {
        jdbc.update("INSERT INTO " + table
                        + " (USER_ID, SESSION_ID, BIZ_ID, VERSION, MESSAGES_JSON, UPDATED_AT)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                session.getUserId(), session.getSessionId(), session.getBizId(),
                Long.valueOf(1L), AgentSessionCodec.encodeMessages(session.getMessages()),
                new Timestamp(System.currentTimeMillis()));
    }

    private Long currentVersion(String userId, String sessionId) {
        try {
            return jdbc.queryForObject(
                    "SELECT VERSION FROM " + table + " WHERE USER_ID = ? AND SESSION_ID = ?",
                    new Object[]{userId, sessionId}, Long.class);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private static String safeTable(String table) {
        String value = table == null ? "" : table.trim();
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        return value;
    }

    private static final RowMapper<AgentSession> ROW_MAPPER = new RowMapper<AgentSession>() {
        @Override
        public AgentSession mapRow(ResultSet rs, int rowNum) throws SQLException {
            AgentSession session = new AgentSession();
            session.setUserId(rs.getString("USER_ID"));
            session.setSessionId(rs.getString("SESSION_ID"));
            session.setBizId(rs.getString("BIZ_ID"));
            session.setVersion(rs.getLong("VERSION"));
            session.setMessages(AgentSessionCodec.decodeMessages(rs.getString("MESSAGES_JSON")));
            Timestamp updatedAt = rs.getTimestamp("UPDATED_AT");
            session.setUpdatedAt(updatedAt == null ? null : new Date(updatedAt.getTime()));
            return session;
        }
    };
}
