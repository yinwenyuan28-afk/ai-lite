package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.catalog.RuntimeCatalog;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 目录的 JDBC 实现。读路径带短缓存，写入后立刻清掉本机缓存。
 *
 * <p>同一供应商下同一模型 id 不建唯一索引。插入前先查；已经存在则不覆盖。
 * 若并发写入出现两行，读取时取更新时间较新的一行。</p>
 */
public final class JdbcRuntimeCatalog implements RuntimeCatalog {

    static final String MISSING_TABLE_MESSAGE = "目录表不可用，请执行 ai-lite-catalog.sql 建表: ";

    private final JdbcTemplate jdbc;
    private final String providerTable;
    private final String modelTable;
    private final String bizTable;
    private final long cacheMillis;
    private volatile Snapshot snapshot;
    private volatile long expiresAt;

    /**
     * @param jdbc           JDBC
     * @param providerTable  覆盖表
     * @param modelTable     模型表
     * @param bizTable       业务绑定表
     * @param cacheSeconds   缓存秒数；小于等于 0 表示不缓存
     */
    public JdbcRuntimeCatalog(JdbcTemplate jdbc, String providerTable, String modelTable,
                              String bizTable, int cacheSeconds) {
        if (jdbc == null) {
            throw new IllegalArgumentException("JdbcTemplate 不能为空");
        }
        this.jdbc = jdbc;
        this.providerTable = safeTable(providerTable);
        this.modelTable = safeTable(modelTable);
        this.bizTable = safeTable(bizTable);
        this.cacheMillis = cacheSeconds <= 0 ? 0L : cacheSeconds * 1000L;
        requireTables(jdbc, this.providerTable, this.modelTable, this.bizTable);
    }

    /**
     * 三张目录表必须已经存在。
     *
     * @param jdbc          JDBC
     * @param providerTable 覆盖表
     * @param modelTable    模型表
     * @param bizTable      业务绑定表
     */
    public static void requireTables(JdbcTemplate jdbc, String providerTable, String modelTable,
                                     String bizTable) {
        probe(jdbc, safeTable(providerTable));
        probe(jdbc, safeTable(modelTable));
        probe(jdbc, safeTable(bizTable));
    }

    /**
     * @param jdbc  JDBC
     * @param table 表名
     */
    public static void requireTable(JdbcTemplate jdbc, String table, String script) {
        try {
            jdbc.queryForList("SELECT 1 FROM " + safeTable(table) + " WHERE 1 = 0");
        } catch (RuntimeException ex) {
            throw new IllegalStateException("表不可用，请执行 " + script + " 建表: " + table, ex);
        }
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public ProviderOverlay findProvider(String providerId) {
        return load().providers.get(providerId);
    }

    @Override
    public CatalogModel findModel(String providerId, String modelId) {
        return load().models.get(key(providerId, modelId));
    }

    @Override
    public BizBinding findBinding(String bizId) {
        return load().bindings.get(bizId);
    }

    @Override
    public void invalidate() {
        snapshot = null;
        expiresAt = 0L;
    }

    /**
     * 整行写入覆盖。空字段会写进库，调用方负责决定哪些字段要保留。
     *
     * @param overlay 覆盖
     */
    public void saveOverlay(ProviderOverlay overlay) {
        if (overlay == null || overlay.getProviderId() == null) {
            throw new IllegalArgumentException("供应商不能为空");
        }
        jdbc.update("DELETE FROM " + providerTable + " WHERE PROVIDER_ID = ?", overlay.getProviderId());
        jdbc.update("INSERT INTO " + providerTable
                        + " (PROVIDER_ID, BASE_URL, API_KEY, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, UPDATE_TIME)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                overlay.getProviderId(), overlay.getBaseUrl(), overlay.getApiKey(),
                overlay.getConnectTimeoutMs(), overlay.getReadTimeoutMs(), now());
        invalidate();
    }

    /**
     * @param providerId 供应商 id
     */
    public void clearOverlay(String providerId) {
        jdbc.update("DELETE FROM " + providerTable + " WHERE PROVIDER_ID = ?", providerId);
        invalidate();
    }

    /**
     * 列出某个供应商下的全部模型行。同一对键有多行时都返回，不折叠。
     *
     * @param providerId 供应商 id
     * @return 按模型 id、更新时间排序的行
     */
    public List<CatalogModel> listModels(String providerId) {
        final List<CatalogModel> rows = new ArrayList<CatalogModel>();
        jdbc.query("SELECT PROVIDER_ID, MODEL_ID, READY, REASONING, INPUT_MODES, CONTEXT_WINDOW,"
                        + " MAX_TOKENS, THINKING_FORMAT, DISPLAY_NAME, UPDATE_TIME FROM " + modelTable
                        + " WHERE PROVIDER_ID = ? ORDER BY MODEL_ID, UPDATE_TIME",
                new org.springframework.jdbc.core.RowCallbackHandler() {
                    @Override
                    public void processRow(ResultSet rs) throws SQLException {
                        rows.add(toModel(rs));
                    }
                }, providerId);
        return rows;
    }

    /**
     * 增量插入模型。已存在则不改能力参数和就绪状态。
     *
     * @param providerId 供应商 id
     * @param modelId    模型 id
     * @return true 表示新插入
     */
    public boolean insertModelIfAbsent(String providerId, String modelId) {
        if (findModel(providerId, modelId) != null) {
            return false;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + modelTable
                        + " WHERE PROVIDER_ID = ? AND MODEL_ID = ?",
                Integer.class, providerId, modelId);
        if (count != null && count.intValue() > 0) {
            return false;
        }
        jdbc.update("INSERT INTO " + modelTable
                        + " (MODEL_ROW_ID, PROVIDER_ID, MODEL_ID, READY, DISPLAY_NAME, UPDATE_TIME)"
                        + " VALUES (?, ?, ?, 0, ?, ?)",
                UUID.randomUUID().toString(), providerId, modelId, modelId, now());
        invalidate();
        return true;
    }

    /**
     * 删除某个模型的全部行。仍被业务绑定时拒绝。
     *
     * @param providerId 供应商 id
     * @param modelId    模型 id
     */
    public void deleteModel(String providerId, String modelId) {
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM " + bizTable
                + " WHERE PROVIDER_ID = ? AND MODEL_ID = ?", Integer.class, providerId, modelId);
        if (used != null && used.intValue() > 0) {
            throw new IllegalStateException("仍有业务在使用这个模型");
        }
        jdbc.update("DELETE FROM " + modelTable + " WHERE PROVIDER_ID = ? AND MODEL_ID = ?",
                providerId, modelId);
        invalidate();
    }

    /**
     * 补齐能力参数并标为可选。
     *
     * @param providerId     供应商 id
     * @param modelId        模型 id
     * @param reasoning      是否支持推理
     * @param contextWindow  上下文窗口
     * @param maxTokens      输出上限
     * @param thinkingFormat 思考格式
     */
    public void saveCapabilities(String providerId, String modelId, boolean reasoning,
                                 int contextWindow, int maxTokens, String thinkingFormat) {
        int updated = jdbc.update("UPDATE " + modelTable
                        + " SET READY = 1, REASONING = ?, INPUT_MODES = ?, CONTEXT_WINDOW = ?,"
                        + " MAX_TOKENS = ?, THINKING_FORMAT = ?, UPDATE_TIME = ?"
                        + " WHERE PROVIDER_ID = ? AND MODEL_ID = ?",
                reasoning ? Integer.valueOf(1) : Integer.valueOf(0), "text",
                Integer.valueOf(contextWindow), Integer.valueOf(maxTokens), thinkingFormat, now(),
                providerId, modelId);
        if (updated == 0) {
            throw new IllegalStateException("模型不存在: " + modelId);
        }
        invalidate();
    }

    /**
     * 保存业务绑定。模型未就绪时拒绝。
     *
     * @param binding 绑定
     */
    public void saveBinding(BizBinding binding) {
        CatalogModel model = findModel(binding.getProviderId(), binding.getModelId());
        if (model == null || !model.isReady()) {
            throw new IllegalStateException("模型尚未补齐能力参数: " + binding.getModelId());
        }
        jdbc.update("DELETE FROM " + bizTable + " WHERE BIZ_ID = ?", binding.getBizId());
        jdbc.update("INSERT INTO " + bizTable
                        + " (BIZ_ID, PROVIDER_ID, MODEL_ID, THINKING_LEVEL, UPDATE_TIME) VALUES (?, ?, ?, ?, ?)",
                binding.getBizId(), binding.getProviderId(), binding.getModelId(),
                binding.getThinkingLevel(), now());
        invalidate();
    }

    /**
     * 清除业务模型绑定。业务 id 仍保留在配置白名单里。
     *
     * @param bizId 业务 id
     */
    public void clearBinding(String bizId) {
        if (bizId == null || bizId.trim().isEmpty()) {
            throw new IllegalArgumentException("业务 id 不能为空");
        }
        jdbc.update("DELETE FROM " + bizTable + " WHERE BIZ_ID = ?", bizId.trim());
        invalidate();
    }

    private Snapshot load() {
        long now = System.currentTimeMillis();
        Snapshot current = snapshot;
        if (cacheMillis > 0L && current != null && now < expiresAt) {
            return current;
        }
        synchronized (this) {
            if (cacheMillis > 0L && snapshot != null && System.currentTimeMillis() < expiresAt) {
                return snapshot;
            }
            Snapshot loaded = read();
            snapshot = loaded;
            expiresAt = cacheMillis <= 0L ? 0L : System.currentTimeMillis() + cacheMillis;
            return loaded;
        }
    }

    private Snapshot read() {
        Map<String, ProviderOverlay> providers = new LinkedHashMap<String, ProviderOverlay>();
        jdbc.query("SELECT PROVIDER_ID, BASE_URL, API_KEY, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS FROM "
                + providerTable, new org.springframework.jdbc.core.RowCallbackHandler() {
            @Override
            public void processRow(ResultSet rs) throws SQLException {
                providers.put(rs.getString("PROVIDER_ID"), new ProviderOverlay(
                        rs.getString("PROVIDER_ID"), rs.getString("BASE_URL"), rs.getString("API_KEY"),
                        integer(rs, "CONNECT_TIMEOUT_MS"), integer(rs, "READ_TIMEOUT_MS")));
            }
        });
        Map<String, CatalogModel> models = new LinkedHashMap<String, CatalogModel>();
        jdbc.query("SELECT PROVIDER_ID, MODEL_ID, READY, REASONING, INPUT_MODES, CONTEXT_WINDOW,"
                + " MAX_TOKENS, THINKING_FORMAT, DISPLAY_NAME, UPDATE_TIME FROM " + modelTable, new org.springframework.jdbc.core.RowCallbackHandler() {
            @Override
            public void processRow(ResultSet rs) throws SQLException {
                CatalogModel row = toModel(rs);
                String id = key(row.getProviderId(), row.getModelId());
                CatalogModel previous = models.get(id);
                if (previous == null || row.getUpdatedAtMillis() >= previous.getUpdatedAtMillis()) {
                    models.put(id, row);
                }
            }
        });
        Map<String, BizBinding> bindings = new LinkedHashMap<String, BizBinding>();
        jdbc.query("SELECT BIZ_ID, PROVIDER_ID, MODEL_ID, THINKING_LEVEL FROM " + bizTable,
                new org.springframework.jdbc.core.RowCallbackHandler() {
                    @Override
                    public void processRow(ResultSet rs) throws SQLException {
                        bindings.put(rs.getString("BIZ_ID"), new BizBinding(
                                rs.getString("BIZ_ID"), rs.getString("PROVIDER_ID"),
                                rs.getString("MODEL_ID"), rs.getString("THINKING_LEVEL")));
                    }
                });
        return new Snapshot(providers, models, bindings);
    }

    private static CatalogModel toModel(ResultSet rs) throws SQLException {
        Timestamp updated = rs.getTimestamp("UPDATE_TIME");
        return new CatalogModel(
                rs.getString("PROVIDER_ID"),
                rs.getString("MODEL_ID"),
                rs.getInt("READY") == 1,
                rs.getInt("REASONING") == 1,
                splitModes(rs.getString("INPUT_MODES")),
                integer(rs, "CONTEXT_WINDOW"),
                integer(rs, "MAX_TOKENS"),
                rs.getString("THINKING_FORMAT"),
                rs.getString("DISPLAY_NAME"),
                updated == null ? 0L : updated.getTime());
    }

    private static List<String> splitModes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> modes = new ArrayList<String>();
        for (String part : raw.split(",")) {
            if (!part.trim().isEmpty()) {
                modes.add(part.trim());
            }
        }
        return modes;
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : Integer.valueOf(value);
    }

    private static String key(String providerId, String modelId) {
        return providerId + "\n" + modelId;
    }

    private static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    private static void probe(JdbcTemplate jdbc, String table) {
        try {
            jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1 = 0");
        } catch (RuntimeException ex) {
            throw new IllegalStateException(MISSING_TABLE_MESSAGE + table, ex);
        }
    }

    static String safeTable(String table) {
        String value = table == null ? "" : table.trim();
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        return value;
    }

    private static final class Snapshot {
        private final Map<String, ProviderOverlay> providers;
        private final Map<String, CatalogModel> models;
        private final Map<String, BizBinding> bindings;

        private Snapshot(Map<String, ProviderOverlay> providers, Map<String, CatalogModel> models,
                         Map<String, BizBinding> bindings) {
            this.providers = providers;
            this.models = models;
            this.bindings = bindings;
        }
    }
}
