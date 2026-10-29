package io.github.yinwenyuan28.ailite.store.jdbc;

import io.github.yinwenyuan28.ailite.llm.CallLogTables;
import io.github.yinwenyuan28.ailite.llm.LlmCallRecord;
import io.github.yinwenyuan28.ailite.llm.LlmCallStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

/**
 * JDBC 调用记录存储。摘要写入调用表。输入输出只在记录对象带了正文时写入
 * 正文表，并且会去掉密钥。
 *
 * <p>正文表名默认跟随摘要表名推导（{@code <摘要表>_BODY}），也可显式指定。
 * 见 {@link CallLogTables}：两张表分开改名的历史遗留会让写入静默失败。</p>
 *
 * <p>表结构见 {@code src/main/resources/ai-lite-llm-call.sql}。</p>
 */
public final class JdbcLlmCallStore implements LlmCallStore {

    private final JdbcTemplate jdbc;
    private final String table;
    private final String bodyTable;

    public JdbcLlmCallStore(JdbcTemplate jdbc) {
        this(jdbc, CallLogTables.DEFAULT_TABLE, null);
    }

    public JdbcLlmCallStore(JdbcTemplate jdbc, String table) {
        this(jdbc, table, null);
    }

    /**
     * @param jdbc      JdbcTemplate
     * @param table     摘要表名
     * @param bodyTable 正文表名；null 或空表示按 {@code table + "_BODY"} 推导
     */
    public JdbcLlmCallStore(JdbcTemplate jdbc, String table, String bodyTable) {
        if (jdbc == null) {
            throw new IllegalArgumentException("JdbcTemplate 不能为空");
        }
        this.jdbc = jdbc;
        this.table = safeTable(table);
        this.bodyTable = safeTable(CallLogTables.resolveBodyTable(this.table, bodyTable));
    }

    @Override
    public void insert(LlmCallRecord record) {
        if (record == null) {
            return;
        }
        jdbc.update("INSERT INTO " + table
                        + " (CALL_ID, BIZ_ID, PROVIDER, MODEL_ID, API, PROFILE, THINKING_LEVEL,"
                        + " ROUND_NO, INPUT_TOKENS, OUTPUT_TOKENS, TOTAL_TOKENS, LATENCY_MS,"
                        + " STOP_REASON, SUCCESS, ERROR_BRIEF, CREATE_TIME)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.getCallId(), record.getBizId(), record.getProvider(), record.getModelId(),
                record.getApi(), record.getProfile(), record.getThinkingLevel(), record.getRoundNo(),
                record.getInputTokens(), record.getOutputTokens(), record.getTotalTokens(),
                record.getLatencyMs(), record.getStopReason(), record.getSuccess(),
                record.getErrorBrief(), new Timestamp(record.getCreateTime() == null
                        ? System.currentTimeMillis() : record.getCreateTime().getTime()));
        if (record.getInputBody() == null && record.getOutputBody() == null) {
            return;
        }
        jdbc.update("INSERT INTO " + bodyTable + " (CALL_ID, INPUT_TEXT, OUTPUT_TEXT)"
                        + " VALUES (?, ?, ?)",
                record.getCallId(), emptyToNull(record.getInputBody()), emptyToNull(record.getOutputBody()));
    }

    /**
     * 摘要表名。
     *
     * @return 摘要表名
     */
    public String getTable() {
        return table;
    }

    /**
     * 正文表名。
     *
     * <p>供管理端查询正文时用同一张表——读侧不能自己拼 {@code "_BODY"}，
     * 否则显式配置了 {@code body-table-name} 时读写会指向不同的表。</p>
     *
     * @return 正文表名
     */
    public String getBodyTable() {
        return bodyTable;
    }

    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    private static String safeTable(String table) {
        String value = table == null ? "" : table.trim();
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        return value;
    }
}
