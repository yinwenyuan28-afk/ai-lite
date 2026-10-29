package io.github.yinwenyuan28.ailite.admin.call;

import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;
import io.github.yinwenyuan28.ailite.llm.CallLogTables;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
     * @param modelId  模型 id，空表示不按模型过滤
     * @param success  成功过滤；null 表示不过滤
     * @param limit    条数，调用方已经限制在 1 到 200
     * @param offset   偏移，从 0 起
     * @return items / total / limit / offset
     */
    public static Map<String, Object> page(JdbcTemplate jdbc, String table,
                                           String bizId, String provider, String modelId,
                                           Boolean success, int limit, int offset) {
        return page(jdbc, table, null, bizId, provider, modelId, success, limit, offset);
    }

    /**
     * @param jdbc      JDBC
     * @param table     摘要表名；空则拒绝
     * @param bodyTable 正文表名；空表示按摘要表名推导（见 {@link CallLogTables}）
     * @param bizId     业务 id，空表示不按业务过滤
     * @param provider  供应商 id，空表示不按供应商过滤
     * @param modelId   模型 id，空表示不按模型过滤
     * @param success   成功过滤；null 表示不过滤
     * @param limit     条数，调用方已经限制在 1 到 200
     * @param offset    偏移，从 0 起
     * @return items / total / limit / offset
     */
    public static Map<String, Object> page(JdbcTemplate jdbc, String table, String bodyTable,
                                           String bizId, String provider, String modelId,
                                           Boolean success, int limit, int offset) {
        if (table == null || table.trim().isEmpty()) {
            throw new IllegalArgumentException(MISSING_TABLE);
        }
        String safe = safeTable(table);
        JdbcRuntimeCatalog.requireTable(jdbc, safe, "ai-lite-llm-call.sql");
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<Object>();
        appendFilters(where, args, bizId, provider, modelId, success);

        Number total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + safe + where, Number.class, args.toArray());
        long count = total == null ? 0L : total.longValue();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT CALL_ID, BIZ_ID, PROVIDER, MODEL_ID, API, PROFILE, THINKING_LEVEL,");
        sql.append(" ROUND_NO, INPUT_TOKENS, OUTPUT_TOKENS, TOTAL_TOKENS, LATENCY_MS,");
        sql.append(" STOP_REASON, SUCCESS, ERROR_BRIEF, CREATE_TIME FROM ").append(safe);
        sql.append(where);
        sql.append(" ORDER BY CREATE_TIME DESC LIMIT ? OFFSET ?");
        List<Object> pageArgs = new ArrayList<Object>(args);
        pageArgs.add(Integer.valueOf(limit));
        pageArgs.add(Integer.valueOf(Math.max(0, offset)));
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), pageArgs.toArray());
        attachBodies(jdbc, rows, CallLogTables.resolveBodyTable(safe, bodyTable));

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("items", rows);
        data.put("total", Long.valueOf(count));
        data.put("limit", Integer.valueOf(limit));
        data.put("offset", Integer.valueOf(Math.max(0, offset)));
        return data;
    }

    /**
     * @param jdbc   JDBC
     * @param table  表名
     * @param callId 调用 id
     * @return 单条摘要，找不到返回 null
     */
    public static Map<String, Object> find(JdbcTemplate jdbc, String table, String callId) {
        return find(jdbc, table, null, callId);
    }

    /**
     * @param jdbc      JDBC
     * @param table     摘要表名
     * @param bodyTable 正文表名；空表示按摘要表名推导
     * @param callId    调用 id
     * @return 单条摘要，找不到返回 null
     */
    public static Map<String, Object> find(JdbcTemplate jdbc, String table, String bodyTable,
                                           String callId) {
        if (table == null || table.trim().isEmpty()) {
            throw new IllegalArgumentException(MISSING_TABLE);
        }
        if (callId == null || callId.trim().isEmpty()) {
            throw new IllegalArgumentException("请指定调用 id");
        }
        String safe = safeTable(table);
        JdbcRuntimeCatalog.requireTable(jdbc, safe, "ai-lite-llm-call.sql");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT CALL_ID, BIZ_ID, PROVIDER, MODEL_ID, API, PROFILE, THINKING_LEVEL,"
                        + " ROUND_NO, INPUT_TOKENS, OUTPUT_TOKENS, TOTAL_TOKENS, LATENCY_MS,"
                        + " STOP_REASON, SUCCESS, ERROR_BRIEF, CREATE_TIME FROM " + safe
                        + " WHERE CALL_ID = ?",
                callId.trim());
        if (rows.isEmpty()) {
            return null;
        }
        attachBodies(jdbc, rows, CallLogTables.resolveBodyTable(safe, bodyTable));
        return rows.get(0);
    }

    private static void appendFilters(StringBuilder where, List<Object> args,
                                      String bizId, String provider, String modelId,
                                      Boolean success) {
        if (bizId != null && !bizId.trim().isEmpty()) {
            where.append(" AND BIZ_ID = ?");
            args.add(bizId.trim());
        }
        if (provider != null && !provider.trim().isEmpty()) {
            where.append(" AND PROVIDER = ?");
            args.add(provider.trim());
        }
        if (modelId != null && !modelId.trim().isEmpty()) {
            where.append(" AND MODEL_ID = ?");
            args.add(modelId.trim());
        }
        if (success != null) {
            where.append(" AND SUCCESS = ?");
            args.add(Integer.valueOf(success.booleanValue() ? 1 : 0));
        }
    }

    /**
     * 把正文合并进摘要行。
     *
     * <p>先探一次表（正文表是可选功能，没建就静默跳过），再用一条
     * {@code IN} 查询把整页正文一次取回。以前是每行探一次表、再查一次，
     * 翻 200 条就是 400 次往返，管理页翻页明显发卡。</p>
     *
     * @param jdbc      JDBC
     * @param rows      摘要行
     * @param bodyTable 正文表名
     */
    private static void attachBodies(JdbcTemplate jdbc, List<Map<String, Object>> rows,
                                     String bodyTable) {
        if (rows.isEmpty() || bodyTable == null || !hasTable(jdbc, bodyTable)) {
            return;
        }
        Map<String, Map<String, Object>> bodies = new HashMap<String, Map<String, Object>>();
        List<Object> ids = new ArrayList<Object>(rows.size());
        StringBuilder placeholders = new StringBuilder();
        for (Map<String, Object> row : rows) {
            Object id = row.get("CALL_ID");
            if (id == null) {
                continue;
            }
            if (placeholders.length() > 0) {
                placeholders.append(", ");
            }
            placeholders.append('?');
            ids.add(id);
        }
        if (ids.isEmpty()) {
            return;
        }
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT CALL_ID, INPUT_TEXT, OUTPUT_TEXT FROM " + bodyTable
                        + " WHERE CALL_ID IN (" + placeholders + ")", ids.toArray());
        for (Map<String, Object> body : found) {
            Object id = body.get("CALL_ID");
            if (id != null) {
                bodies.put(String.valueOf(id), body);
            }
        }
        for (Map<String, Object> row : rows) {
            Object id = row.get("CALL_ID");
            Map<String, Object> body = id == null ? null : bodies.get(String.valueOf(id));
            if (body != null) {
                row.put("INPUT_TEXT", body.get("INPUT_TEXT"));
                row.put("OUTPUT_TEXT", body.get("OUTPUT_TEXT"));
            }
        }
    }

    /**
     * @param jdbc  JDBC
     * @param table 表名（已经过 safeTable 校验）
     * @return 表是否存在
     */
    private static boolean hasTable(JdbcTemplate jdbc, String table) {
        try {
            jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1 = 0");
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
