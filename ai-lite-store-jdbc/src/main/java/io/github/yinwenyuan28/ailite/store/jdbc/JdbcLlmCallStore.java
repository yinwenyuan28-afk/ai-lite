package io.github.yinwenyuan28.ailite.store.jdbc;

import io.github.yinwenyuan28.ailite.llm.LlmCallRecord;
import io.github.yinwenyuan28.ailite.llm.LlmCallStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

/**
 * JDBC 调用记录存储。摘要写入调用表。输入输出只在记录对象带了正文时写入
 * {@code AI_LITE_LLM_CALL_BODY}，并且会去掉密钥。
 *
 * <p>表结构见 {@code src/main/resources/ai-lite-llm-call.sql}。</p>
 */
public final class JdbcLlmCallStore implements LlmCallStore {

    private final JdbcTemplate jdbc;
    private final String table;

    public JdbcLlmCallStore(JdbcTemplate jdbc) {
        this(jdbc, "AI_LITE_LLM_CALL");
    }

    public JdbcLlmCallStore(JdbcTemplate jdbc, String table) {
        if (jdbc == null) {
            throw new IllegalArgumentException("JdbcTemplate 不能为空");
        }
        this.jdbc = jdbc;
        this.table = safeTable(table);
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
        jdbc.update("INSERT INTO AI_LITE_LLM_CALL_BODY (CALL_ID, INPUT_TEXT, OUTPUT_TEXT)"
                        + " VALUES (?, ?, ?)",
                record.getCallId(), emptyToNull(record.getInputBody()), emptyToNull(record.getOutputBody()));
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
