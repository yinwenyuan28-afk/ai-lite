package io.github.yinwenyuan28.ailite.prompt.jdbc;

import io.github.yinwenyuan28.ailite.prompt.PromptAudit;
import io.github.yinwenyuan28.ailite.prompt.PromptException;
import io.github.yinwenyuan28.ailite.prompt.PromptStatus;
import io.github.yinwenyuan28.ailite.prompt.PromptStore;
import io.github.yinwenyuan28.ailite.prompt.PromptVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * JDBC 提示词存储。表结构见 {@code ai-lite-prompt.sql}。
 *
 * <p>{@link #activate} 在一个事务里退役旧版本、发布目标版本并更新生效指针；
 * 传入 {@link PlatformTransactionManager} 时使用事务模板，否则依赖连接自身的自动提交。
 * 多节点并发创建草稿时，请保留 (PROMPT_KEY, VERSION_NO) 唯一索引作为最后防线。</p>
 */
public final class JdbcPromptStore implements PromptStore {

    private final JdbcTemplate jdbc;
    private final String versionTable;
    private final String activeTable;
    private final String auditTable;
    private final TransactionTemplate transactions;

    public JdbcPromptStore(JdbcTemplate jdbc) {
        this(jdbc, "AI_LITE_PROMPT_VERSION", "AI_LITE_PROMPT_ACTIVE", "AI_LITE_PROMPT_AUDIT", null);
    }

    public JdbcPromptStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, "AI_LITE_PROMPT_VERSION", "AI_LITE_PROMPT_ACTIVE", "AI_LITE_PROMPT_AUDIT",
                transactionManager);
    }

    /**
     * @param jdbc             JdbcTemplate
     * @param versionTable     版本表名
     * @param activeTable      生效指针表名
     * @param auditTable       审计表名
     * @param txManager        事务管理器，可为 null
     */
    public JdbcPromptStore(JdbcTemplate jdbc, String versionTable, String activeTable,
                           String auditTable, PlatformTransactionManager txManager) {
        if (jdbc == null) {
            throw new IllegalArgumentException("JdbcTemplate 不能为空");
        }
        this.jdbc = jdbc;
        this.versionTable = safeTable(versionTable);
        this.activeTable = safeTable(activeTable);
        this.auditTable = safeTable(auditTable);
        this.transactions = txManager == null ? null : new TransactionTemplate(txManager);
    }

    @Override
    public Optional<PromptVersion> findActive(String promptKey) {
        List<PromptVersion> rows = jdbc.query(
                "SELECT v.VERSION_ID, v.PROMPT_KEY, v.VERSION_NO, v.STATUS, v.SYSTEM_TEMPLATE,"
                        + " v.USER_TEMPLATE, v.OUTPUT_SCHEMA, v.CONTENT_HASH, v.CREATED_BY, v.CREATE_TIME"
                        + " FROM " + versionTable + " v JOIN " + activeTable + " a"
                        + " ON a.VERSION_ID = v.VERSION_ID WHERE a.PROMPT_KEY = ?",
                new Object[]{promptKey}, VERSION_MAPPER);
        return rows.isEmpty() ? Optional.<PromptVersion>empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<PromptVersion> findVersion(String versionId) {
        if (versionId == null) {
            return Optional.empty();
        }
        List<PromptVersion> rows = jdbc.query(
                "SELECT VERSION_ID, PROMPT_KEY, VERSION_NO, STATUS, SYSTEM_TEMPLATE, USER_TEMPLATE,"
                        + " OUTPUT_SCHEMA, CONTENT_HASH, CREATED_BY, CREATE_TIME FROM " + versionTable
                        + " WHERE VERSION_ID = ?",
                new Object[]{versionId}, VERSION_MAPPER);
        return rows.isEmpty() ? Optional.<PromptVersion>empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<PromptVersion> versions(String promptKey, final int limit) {
        return jdbc.query(new PreparedStatementCreator() {
            @Override
            public PreparedStatement createPreparedStatement(Connection connection) throws SQLException {
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT VERSION_ID, PROMPT_KEY, VERSION_NO, STATUS, SYSTEM_TEMPLATE,"
                                + " USER_TEMPLATE, OUTPUT_SCHEMA, CONTENT_HASH, CREATED_BY, CREATE_TIME"
                                + " FROM " + versionTable + " WHERE PROMPT_KEY = ?"
                                + " ORDER BY VERSION_NO DESC");
                applyLimit(statement, limit);
                statement.setString(1, promptKey);
                return statement;
            }
        }, VERSION_MAPPER);
    }

    @Override
    public List<PromptAudit> audits(String promptKey, final int limit) {
        return jdbc.query(new PreparedStatementCreator() {
            @Override
            public PreparedStatement createPreparedStatement(Connection connection) throws SQLException {
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT AUDIT_ID, PROMPT_KEY, ACTION, VERSION_ID, ACTOR, DETAIL, CREATE_TIME"
                                + " FROM " + auditTable + " WHERE PROMPT_KEY = ?"
                                + " ORDER BY CREATE_TIME ASC");
                applyLimit(statement, limit);
                statement.setString(1, promptKey);
                return statement;
            }
        }, AUDIT_MAPPER);
    }

    @Override
    public int nextVersionNo(String promptKey) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(VERSION_NO), 0) + 1 FROM " + versionTable + " WHERE PROMPT_KEY = ?",
                new Object[]{promptKey}, Integer.class);
        return value == null ? 1 : value.intValue();
    }

    @Override
    public void saveVersion(final PromptVersion version) {
        if (version == null || version.getVersionId() == null) {
            throw new PromptException("提示词版本缺少 id");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM " + versionTable + " WHERE VERSION_ID = ?",
                new Object[]{version.getVersionId()}, Integer.class);
        if (count != null && count.intValue() > 0) {
            jdbc.update("UPDATE " + versionTable + " SET PROMPT_KEY = ?, VERSION_NO = ?, STATUS = ?,"
                            + " SYSTEM_TEMPLATE = ?, USER_TEMPLATE = ?, OUTPUT_SCHEMA = ?,"
                            + " CONTENT_HASH = ?, CREATED_BY = ?, CREATE_TIME = ? WHERE VERSION_ID = ?",
                    version.getPromptKey(), Integer.valueOf(version.getVersionNo()),
                    wire(version.getStatus()), version.getSystemTemplate(), version.getUserTemplate(),
                    version.getOutputSchema(), version.getContentHash(), version.getCreatedBy(),
                    toTimestamp(version.getCreateTime()), version.getVersionId());
            return;
        }
        jdbc.update("INSERT INTO " + versionTable
                        + " (VERSION_ID, PROMPT_KEY, VERSION_NO, STATUS, SYSTEM_TEMPLATE, USER_TEMPLATE,"
                        + " OUTPUT_SCHEMA, CONTENT_HASH, CREATED_BY, CREATE_TIME)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                version.getVersionId(), version.getPromptKey(),
                Integer.valueOf(version.getVersionNo()), wire(version.getStatus()),
                version.getSystemTemplate(), version.getUserTemplate(), version.getOutputSchema(),
                version.getContentHash(), version.getCreatedBy(), toTimestamp(version.getCreateTime()));
    }

    @Override
    public void activate(final String promptKey, final String versionId, final String actor,
                         final Date publishTime) {
        inTransaction(new Runnable() {
            @Override
            public void run() {
                jdbc.update("UPDATE " + versionTable + " SET STATUS = ? WHERE PROMPT_KEY = ?"
                                + " AND STATUS = ? AND VERSION_ID <> ?",
                        wire(PromptStatus.RETIRED), promptKey, wire(PromptStatus.PUBLISHED), versionId);
                int updated = jdbc.update("UPDATE " + versionTable + " SET STATUS = ?"
                                + " WHERE VERSION_ID = ? AND PROMPT_KEY = ?",
                        wire(PromptStatus.PUBLISHED), versionId, promptKey);
                if (updated != 1) {
                    throw new PromptException("提示词版本不存在");
                }
                int pointer = jdbc.update("UPDATE " + activeTable
                                + " SET VERSION_ID = ?, REVISION = REVISION + 1, PUBLISH_TIME = ?, ACTOR = ?"
                                + " WHERE PROMPT_KEY = ?",
                        versionId, toTimestamp(publishTime), actor, promptKey);
                if (pointer == 0) {
                    jdbc.update("INSERT INTO " + activeTable
                                    + " (PROMPT_KEY, VERSION_ID, REVISION, PUBLISH_TIME, ACTOR)"
                                    + " VALUES (?, ?, 1, ?, ?)",
                            promptKey, versionId, toTimestamp(publishTime), actor);
                }
            }
        });
    }

    @Override
    public void audit(final PromptAudit audit) {
        if (audit == null) {
            return;
        }
        jdbc.update("INSERT INTO " + auditTable
                        + " (AUDIT_ID, PROMPT_KEY, ACTION, VERSION_ID, ACTOR, DETAIL, CREATE_TIME)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                audit.getAuditId(), audit.getPromptKey(), audit.getAction(), audit.getVersionId(),
                audit.getActor(), audit.getDetail(), toTimestamp(audit.getCreateTime()));
    }

    private void inTransaction(final Runnable runnable) {
        if (transactions == null) {
            runnable.run();
            return;
        }
        transactions.execute(status -> {
            runnable.run();
            return null;
        });
    }

    private static void applyLimit(PreparedStatement statement, int limit) throws SQLException {
        if (limit > 0) {
            statement.setMaxRows(limit);
        }
    }

    private static String wire(PromptStatus status) {
        return status == null ? PromptStatus.DRAFT.wire() : status.wire();
    }

    private static Timestamp toTimestamp(Date date) {
        return date == null ? null : new Timestamp(date.getTime());
    }

    private static String safeTable(String table) {
        String value = table == null ? "" : table.trim();
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        return value;
    }

    private static final RowMapper<PromptVersion> VERSION_MAPPER = new RowMapper<PromptVersion>() {
        @Override
        public PromptVersion mapRow(ResultSet rs, int rowNum) throws SQLException {
            PromptVersion version = new PromptVersion();
            version.setVersionId(rs.getString("VERSION_ID"));
            version.setPromptKey(rs.getString("PROMPT_KEY"));
            version.setVersionNo(rs.getInt("VERSION_NO"));
            PromptStatus status = PromptStatus.parse(rs.getString("STATUS"));
            version.setStatus(status == null ? PromptStatus.DRAFT : status);
            version.setSystemTemplate(rs.getString("SYSTEM_TEMPLATE"));
            version.setUserTemplate(rs.getString("USER_TEMPLATE"));
            version.setOutputSchema(rs.getString("OUTPUT_SCHEMA"));
            version.setContentHash(rs.getString("CONTENT_HASH"));
            version.setCreatedBy(rs.getString("CREATED_BY"));
            Timestamp createTime = rs.getTimestamp("CREATE_TIME");
            version.setCreateTime(createTime == null ? null : new Date(createTime.getTime()));
            return version;
        }
    };

    private static final RowMapper<PromptAudit> AUDIT_MAPPER = new RowMapper<PromptAudit>() {
        @Override
        public PromptAudit mapRow(ResultSet rs, int rowNum) throws SQLException {
            PromptAudit audit = new PromptAudit();
            audit.setAuditId(rs.getString("AUDIT_ID"));
            audit.setPromptKey(rs.getString("PROMPT_KEY"));
            audit.setAction(rs.getString("ACTION"));
            audit.setVersionId(rs.getString("VERSION_ID"));
            audit.setActor(rs.getString("ACTOR"));
            audit.setDetail(rs.getString("DETAIL"));
            Timestamp createTime = rs.getTimestamp("CREATE_TIME");
            audit.setCreateTime(createTime == null ? null : new Date(createTime.getTime()));
            return audit;
        }
    };
}
