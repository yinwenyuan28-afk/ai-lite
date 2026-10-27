package io.github.yinwenyuan28.ailite.session.jdbc;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.session.AgentSession;
import io.github.yinwenyuan28.ailite.session.AgentSessionCodec;
import io.github.yinwenyuan28.ailite.session.AgentSessionStore;
import io.github.yinwenyuan28.ailite.session.AgentSessionSummary;
import io.github.yinwenyuan28.ailite.internal.session.AgentSessionStoreSupport;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
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

    /** 调用方未指定租约时长时的默认值。 */
    private static final long DEFAULT_COMPACTION_LEASE_MS = 120000L;

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
                "SELECT USER_ID, SESSION_ID, BIZ_ID, VERSION, MESSAGES_JSON, TITLE,"
                        + " MESSAGE_COUNT, TOTAL_TOKENS, UPDATED_AT FROM "
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
                "UPDATE " + table + " SET BIZ_ID = ?, VERSION = ?, MESSAGES_JSON = ?, TITLE = ?,"
                        + " MESSAGE_COUNT = ?, TOTAL_TOKENS = ?, UPDATED_AT = ?"
                        + " WHERE USER_ID = ? AND SESSION_ID = ? AND VERSION = ?",
                session.getBizId(), Long.valueOf(current.longValue() + 1L),
                AgentSessionCodec.encodeEntries(session.getEntries()),
                session.getTitle(), Integer.valueOf(session.getMessages().size()),
                Long.valueOf(session.getTotalTokens()),
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
                        + " (USER_ID, SESSION_ID, BIZ_ID, VERSION, MESSAGES_JSON, TITLE,"
                        + " MESSAGE_COUNT, TOTAL_TOKENS, UPDATED_AT)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                session.getUserId(), session.getSessionId(), session.getBizId(),
                Long.valueOf(1L), AgentSessionCodec.encodeEntries(session.getEntries()),
                session.getTitle(), Integer.valueOf(session.getMessages().size()),
                Long.valueOf(session.getTotalTokens()),
                new Timestamp(System.currentTimeMillis()));
    }

    /**
     * 按用户分页列举会话摘要。
     *
     * <p>只查派生列，不读 {@code MESSAGES_JSON}：列表页不需要消息体，
     * 而单会话的消息 JSON 可能达到 MB 级。</p>
     *
     * <p>排序用 {@code UPDATED_AT DESC, SESSION_ID DESC}：同一批次写入的会话
     * 更新时间常常相同，只有时间一个键会让翻页丢行或重复。</p>
     *
     * @param userId 用户 id
     * @param offset 跳过条数
     * @param limit  最多返回条数，小于等于 0 表示不限制
     * @return 摘要列表
     */
    @Override
    public List<AgentSessionSummary> listByUser(String userId, int offset, int limit) {
        AgentSessionStoreSupport.requireKeys(userId, "list");
        int from = Math.max(0, offset);
        // 用 BETWEEN 统一两种分页形态，避免不同数据库的 LIMIT/OFFSET 语法差异。
        final int upper = limit <= 0 ? Integer.MAX_VALUE : from + limit;
        StringBuilder sql = new StringBuilder()
                .append("SELECT USER_ID, SESSION_ID, BIZ_ID, TITLE, MESSAGE_COUNT, TOTAL_TOKENS,")
                .append(" UPDATED_AT FROM ").append(table)
                .append(" WHERE USER_ID = ? ORDER BY UPDATED_AT DESC, SESSION_ID DESC");
        List<AgentSessionSummary> all = jdbc.query(sql.toString(),
                new Object[]{userId}, SUMMARY_MAPPER);
        List<AgentSessionSummary> page = new ArrayList<AgentSessionSummary>();
        for (int i = from; i < all.size() && i < upper; i++) {
            page.add(all.get(i));
        }
        return page;
    }

    @Override
    public boolean supportsListing() {
        return true;
    }

    /**
     * 压缩租约：条件更新抢占，只有 {@code LEASE_UNTIL} 为空或已过期的行才会被更新。
     *
     * <p>用单条 {@code UPDATE ... WHERE} 完成抢占，避免"先查后写"的竞态。
     * 过期租约允许抢占：节点崩溃不能让压缩永久卡死。</p>
     *
     * @param userId      用户 id
     * @param sessionId   会话 id
     * @param leaseMillis 租约时长
     * @return 是否抢到
     */
    @Override
    public boolean tryAcquireCompaction(String userId, String sessionId, long leaseMillis) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        long now = System.currentTimeMillis();
        long ttl = leaseMillis <= 0 ? DEFAULT_COMPACTION_LEASE_MS : leaseMillis;
        int updated = jdbc.update(
                "UPDATE " + table + " SET COMPACTION_LEASE_UNTIL = ?"
                        + " WHERE USER_ID = ? AND SESSION_ID = ?"
                        + " AND (COMPACTION_LEASE_UNTIL IS NULL OR COMPACTION_LEASE_UNTIL <= ?)",
                Long.valueOf(now + ttl), userId, sessionId, Long.valueOf(now));
        return updated == 1;
    }

    @Override
    public void releaseCompaction(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        jdbc.update("UPDATE " + table + " SET COMPACTION_LEASE_UNTIL = NULL"
                + " WHERE USER_ID = ? AND SESSION_ID = ?", userId, sessionId);
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
            session.setTitle(rs.getString("TITLE"));
            session.setTotalTokens(rs.getLong("TOTAL_TOKENS"));
            // 必须读全量条目：用 decodeMessages 会把压缩摘要、用量记录等静默丢掉。
            session.setEntries(AgentSessionCodec.decodeEntries(rs.getString("MESSAGES_JSON")));
            Timestamp updatedAt = rs.getTimestamp("UPDATED_AT");
            session.setUpdatedAt(updatedAt == null ? null : new Date(updatedAt.getTime()));
            return session;
        }
    };

    private static final RowMapper<AgentSessionSummary> SUMMARY_MAPPER =
            new RowMapper<AgentSessionSummary>() {
                @Override
                public AgentSessionSummary mapRow(ResultSet rs, int rowNum) throws SQLException {
                    Timestamp updatedAt = rs.getTimestamp("UPDATED_AT");
                    return new AgentSessionSummary(rs.getString("USER_ID"),
                            rs.getString("SESSION_ID"), rs.getString("BIZ_ID"),
                            rs.getString("TITLE"), rs.getInt("MESSAGE_COUNT"),
                            rs.getLong("TOTAL_TOKENS"),
                            updatedAt == null ? null : new Date(updatedAt.getTime()));
                }
            };
}
