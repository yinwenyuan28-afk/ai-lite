package io.github.yinwenyuan28.ailite.admin.call;

import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调用记录只读查询。只选摘要列，不读提示词、响应正文和密钥。
 */
public final class CallLogQuery {

    public static final String MISSING_TABLE =
            "未配置调用记录表名，请设置 ai-lite.store.jdbc.table-name 并执行 ai-lite-llm-call.sql";

    private CallLogQuery() {
    }

    /**
     * @param jdbc     JDBC
     * @param table    表名；空则拒绝
     * @param bizId    业务 id，空表示不按业务过滤
     * @param provider 供应商 id，空表示不按供应商过滤
     * @param limit    条数，调用方已经限制在 1 到 200
     * @return 按创建时间倒序的摘要
     */
    public static List<Map<String, Object>> list(JdbcTemplate jdbc, String table,
                                                 String bizId, String provider, int limit) {
        if (table == null || table.trim().isEmpty()) {
            throw new IllegalArgumentException(MISSING_TABLE);
        }
        String safe = safeTable(table);
        JdbcRuntimeCatalog.requireTable(jdbc, safe, "ai-lite-llm-call.sql");
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT CALL_ID, BIZ_ID, PROVIDER, MODEL_ID, API, PROFILE, THINKING_LEVEL,");
        sql.append(" ROUND_NO, INPUT_TOKENS, OUTPUT_TOKENS, TOTAL_TOKENS, LATENCY_MS,");
        sql.append(" STOP_REASON, SUCCESS, ERROR_BRIEF, CREATE_TIME FROM ").append(safe);
        sql.append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<Object>();
        if (bizId != null && !bizId.trim().isEmpty()) {
            sql.append(" AND BIZ_ID = ?");
            args.add(bizId.trim());
        }
        if (provider != null && !provider.trim().isEmpty()) {
            sql.append(" AND PROVIDER = ?");
            args.add(provider.trim());
        }
        sql.append(" ORDER BY CREATE_TIME DESC LIMIT ?");
        args.add(Integer.valueOf(limit));
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        if (!hasBodyTable(jdbc)) {
            return rows;
        }
        for (Map<String, Object> row : rows) {
            Object callId = row.get("CALL_ID");
            List<Map<String, Object>> bodies = jdbc.queryForList(
                    "SELECT INPUT_TEXT, OUTPUT_TEXT FROM AI_LITE_LLM_CALL_BODY WHERE CALL_ID = ?",
                    callId);
            if (!bodies.isEmpty()) {
                row.put("INPUT_TEXT", bodies.get(0).get("INPUT_TEXT"));
                row.put("OUTPUT_TEXT", bodies.get(0).get("OUTPUT_TEXT"));
            }
        }
        return rows;
    }

    private static boolean hasBodyTable(JdbcTemplate jdbc) {
        try {
            jdbc.queryForList("SELECT 1 FROM AI_LITE_LLM_CALL_BODY WHERE 1 = 0");
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    static String safeTable(String table) {
        String value = table == null ? "" : table.trim();
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        return value;
    }
}
