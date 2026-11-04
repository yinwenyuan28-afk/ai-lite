package io.github.yinwenyuan28.ailite.admin.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.catalog.RuntimeCatalog;
import io.github.yinwenyuan28.ailite.json.AiLiteJson;
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
        // 放在构造器里而不是自动装配里：直接 new 这个类的调用方也要能读旧库。
        ensureProviderApiColumn(jdbc, this.providerTable);
        ensureExtraColumns(jdbc, this.providerTable, this.modelTable);
    }

    /**
     * 0.1.15 起新增的七列。旧库补列后全为 NULL，解析时回落到配置文件，行为与升级前一致。
     */
    private static final String[] OVERLAY_JSON_COLUMNS = {"PROVIDER_HEADERS", "PROVIDER_EXTRA_BODY"};

    private static final String[] MODEL_JSON_COLUMNS = {"THINKING_LEVEL_MAP", "SAMPLER_PARAMS",
            "SAMPLER_PARAMS_BY_LEVEL", "MODEL_HEADERS", "EXTRA_BODY"};

    /**
     * 补齐 0.1.15 新增的七列。七列都是 JSON 文本。
     *
     * <p>用 {@code CLOB} 还是 {@code TEXT} 取决于数据库：SQLite/PostgreSQL/Oracle/达梦接受
     * {@code CLOB}，MySQL 只接受 {@code TEXT}。这里先试 {@code CLOB} 再试 {@code TEXT}，
     * 都失败才报错并给出手工 SQL。</p>
     *
     * @param jdbc          JDBC
     * @param providerTable 覆盖表
     * @param modelTable    模型表
     */
    public static void ensureExtraColumns(JdbcTemplate jdbc, String providerTable, String modelTable) {
        String providers = safeTable(providerTable);
        String models = safeTable(modelTable);
        for (String column : OVERLAY_JSON_COLUMNS) {
            ensureJsonColumn(jdbc, providers, column);
        }
        for (String column : MODEL_JSON_COLUMNS) {
            ensureJsonColumn(jdbc, models, column);
        }
    }

    /**
     * 补一列 JSON 文本。已存在则直接返回。
     *
     * @param jdbc   JDBC
     * @param table  表名
     * @param column 列名
     */
    private static void ensureJsonColumn(JdbcTemplate jdbc, String table, String column) {
        if (hasColumn(jdbc, table, column)) {
            return;
        }
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " CLOB");
            return;
        } catch (RuntimeException clobFailed) {
            // 已被别的实例加上时这里也会抛，先重查再决定要不要换方言。
            if (hasColumn(jdbc, table, column)) {
                return;
            }
            try {
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " TEXT");
                return;
            } catch (RuntimeException textFailed) {
                if (!hasColumn(jdbc, table, column)) {
                    throw new IllegalStateException(
                            "表 " + table + " 缺少 " + column + " 列，且自动补齐失败。请手工执行其中之一:\n"
                                    + "  ALTER TABLE " + table + " ADD COLUMN " + column + " CLOB\n"
                                    + "  ALTER TABLE " + table + " ADD COLUMN " + column + " TEXT",
                            textFailed);
                }
            }
        }
    }


    /**
     * 补齐旧库缺少的列。
     *
     * <p>0.1.14 之前 {@code AI_LITE_PROVIDER_OVERLAY} 没有 {@code API} 列，协议只能写在
     * 配置文件里。现在协议可以由管理页覆盖，旧库需要补这一列，否则读取覆盖时整表查询
     * 会失败。已有的行该列为 NULL，表示沿用配置文件，行为与升级前一致。</p>
     *
     * @param jdbc          JDBC
     * @param providerTable 覆盖表
     */
    public static void ensureProviderApiColumn(JdbcTemplate jdbc, String providerTable) {
        String table = safeTable(providerTable);
        if (hasColumn(jdbc, table, "API")) {
            return;
        }
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN API VARCHAR(64)");
        } catch (RuntimeException ex) {
            // 已被别的实例加上，或数据库不允许在线改表：重查一次，仍然没有才报错。
            if (!hasColumn(jdbc, table, "API")) {
                throw new IllegalStateException(
                        "覆盖表 " + table + " 缺少 API 列，且自动补齐失败，请手工执行: ALTER TABLE "
                                + table + " ADD COLUMN API VARCHAR(64)", ex);
            }
        }
    }

    private static boolean hasColumn(JdbcTemplate jdbc, String table, String column) {
        try {
            jdbc.queryForList("SELECT " + column + " FROM " + table + " WHERE 1 = 0");
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
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
                        + " (PROVIDER_ID, API, BASE_URL, API_KEY, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,"
                        + " PROVIDER_HEADERS, PROVIDER_EXTRA_BODY, UPDATE_TIME)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                overlay.getProviderId(), overlay.getApi(), overlay.getBaseUrl(), overlay.getApiKey(),
                overlay.getConnectTimeoutMs(), overlay.getReadTimeoutMs(),
                writeJson(overlay.getHeaders()), writeJson(overlay.getExtraBody()), now());
        invalidate();
    }

    /**
     * 序列化 Map 为 JSON 文本。null 保持 null（表示「沿用配置文件」），
     * 空 Map 序列化为 {@code {}}（表示「明确清空」），两者不能混。
     *
     * @param value 待序列化对象
     * @return JSON 文本；入参为 null 时返回 null
     */
    private static String writeJson(Object value) {
        return value == null ? null : AiLiteJson.toJson(value);
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
                        + " MAX_TOKENS, THINKING_FORMAT, DISPLAY_NAME, UPDATE_TIME,"
                        + " THINKING_LEVEL_MAP, SAMPLER_PARAMS, SAMPLER_PARAMS_BY_LEVEL,"
                        + " MODEL_HEADERS, EXTRA_BODY FROM " + modelTable
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
     * 保存能力参数。**不改就绪状态**——就绪由 {@link #setReady} 单独控制。
     *
     * <p>早先这里会顺手把 {@code READY} 置 1，导致"填了参数就等于启用"。但能力参数
     * 与"这个模型能不能被业务选中"是两件事：参数可能只是照抄了默认值还没验证，
     * 或者模型供应商那边还没开通。启用应当是一个明确的、可回退的决定。</p>
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
        saveCapabilities(providerId, modelId, reasoning, new ArrayList<String>(), contextWindow,
                maxTokens, thinkingFormat, null);
    }

    /**
     * 保存能力参数，同时可更新显示名和入参类型。
     *
     * <p>入参类型决定 {@code ResolvedModel.acceptsImage}：只有包含 {@code image} 时
     * 调用方才能把图片消息发给这个模型。早先这里写死成 {@code text}，导致支持图片的
     * 模型在管理页上无论怎么配都不会被当成多模态。</p>
     *
     * @param providerId     供应商 id
     * @param modelId        模型 id
     * @param reasoning      是否支持推理
     * @param inputModes     入参类型，如 text、image；空表示只收文本
     * @param contextWindow  上下文窗口
     * @param maxTokens      输出上限
     * @param thinkingFormat 思考格式
     * @param displayName    显示名；null 表示不改，空字符串表示回落到模型 id
     */
    public void saveCapabilities(String providerId, String modelId, boolean reasoning,
                                 List<String> inputModes, int contextWindow, int maxTokens,
                                 String thinkingFormat, String displayName) {
        saveCapabilities(providerId, modelId, reasoning, inputModes, contextWindow, maxTokens,
                thinkingFormat, displayName, null, null, null, null, null);
    }

    /**
     * 保存能力参数与模型级请求参数。
     *
     * <p>五个请求参数用「null 表示不改」的约定拼动态 SET：请求里没出现的列不进 SQL，
     * 出现的空 Map 写成 {@code {}}（明确清空），出现的非空 Map 写成 JSON。这样不需要
     * 先读后写，也就没有读改写之间的竞态。</p>
     *
     * @param providerId           供应商 id
     * @param modelId              模型 id
     * @param reasoning            是否支持推理
     * @param inputModes           入参类型
     * @param contextWindow        上下文窗口
     * @param maxTokens            输出上限
     * @param thinkingFormat       思考格式
     * @param displayName          显示名；null 表示不改
     * @param thinkingLevelMap     思考档映射；null 表示不改
     * @param samplerParams        默认采样参数；null 表示不改
     * @param samplerParamsByLevel 分档采样参数；null 表示不改
     * @param modelHeaders         模型级请求头；null 表示不改
     * @param extraBody            模型级附加请求体；null 表示不改
     */
    public void saveCapabilities(String providerId, String modelId, boolean reasoning,
                                 List<String> inputModes, int contextWindow, int maxTokens,
                                 String thinkingFormat, String displayName,
                                 Map<String, String> thinkingLevelMap,
                                 Map<String, Object> samplerParams,
                                 Map<String, Map<String, Object>> samplerParamsByLevel,
                                 Map<String, String> modelHeaders,
                                 Map<String, Object> extraBody) {
        List<String> modes = inputModes == null || inputModes.isEmpty()
                ? Collections.singletonList("text") : inputModes;
        StringBuilder modesText = new StringBuilder();
        for (String mode : modes) {
            if (mode == null || mode.trim().isEmpty()) {
                continue;
            }
            if (modesText.length() > 0) {
                modesText.append(',');
            }
            modesText.append(mode.trim());
        }
        String modesValue = modesText.length() == 0 ? "text" : modesText.toString();

        StringBuilder sql = new StringBuilder("UPDATE ").append(modelTable)
                .append(" SET REASONING = ?, INPUT_MODES = ?, CONTEXT_WINDOW = ?,")
                .append(" MAX_TOKENS = ?, THINKING_FORMAT = ?");
        List<Object> args = new ArrayList<Object>();
        args.add(reasoning ? Integer.valueOf(1) : Integer.valueOf(0));
        args.add(modesValue);
        args.add(Integer.valueOf(contextWindow));
        args.add(Integer.valueOf(maxTokens));
        args.add(thinkingFormat);
        if (displayName != null) {
            sql.append(", DISPLAY_NAME = ?");
            args.add(displayName.trim().isEmpty() ? modelId : displayName.trim());
        }
        appendJsonSet(sql, args, "THINKING_LEVEL_MAP", thinkingLevelMap);
        appendJsonSet(sql, args, "SAMPLER_PARAMS", samplerParams);
        appendJsonSet(sql, args, "SAMPLER_PARAMS_BY_LEVEL", samplerParamsByLevel);
        appendJsonSet(sql, args, "MODEL_HEADERS", modelHeaders);
        appendJsonSet(sql, args, "EXTRA_BODY", extraBody);
        sql.append(", UPDATE_TIME = ? WHERE PROVIDER_ID = ? AND MODEL_ID = ?");
        args.add(now());
        args.add(providerId);
        args.add(modelId);

        int updated = jdbc.update(sql.toString(), args.toArray());
        if (updated == 0) {
            throw new IllegalStateException("模型不存在: " + modelId);
        }
        invalidate();
    }

    /**
     * 往 SET 子句追加一个 JSON 列。value 为 null 表示不改这一列。
     *
     * @param sql   正在拼的 SQL
     * @param args  参数列表
     * @param column 列名
     * @param value  值；null 表示跳过
     */
    private static void appendJsonSet(StringBuilder sql, List<Object> args, String column, Object value) {
        if (value == null) {
            return;
        }
        sql.append(", ").append(column).append(" = ?");
        args.add(AiLiteJson.toJson(value));
    }

    /**
     * 设置模型是否就绪（即能否被业务选中）。
     *
     * <p>这是唯一能改变 {@code READY} 的动作。置为未就绪不会解除已有的业务绑定：
     * 绑定继续存在，但下一次调用会在解析阶段报"模型未就绪"。
     * 这是刻意的——静默解绑会让业务在无人察觉的情况下失去模型。</p>
     *
     * @param providerId 供应商 id
     * @param modelId    模型 id
     * @param ready      true 表示可被业务选中
     * @throws IllegalStateException 模型不存在时抛出
     */
    public void setReady(String providerId, String modelId, boolean ready) {
        if (providerId == null || providerId.trim().isEmpty()
                || modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("供应商 id 与模型 id 不能为空");
        }
        int updated = jdbc.update("UPDATE " + modelTable
                        + " SET READY = ?, UPDATE_TIME = ? WHERE PROVIDER_ID = ? AND MODEL_ID = ?",
                ready ? Integer.valueOf(1) : Integer.valueOf(0), now(),
                providerId.trim(), modelId.trim());
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
            throw new IllegalStateException("模型未就绪，不能绑定业务: " + binding.getModelId());
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
        jdbc.query("SELECT PROVIDER_ID, API, BASE_URL, API_KEY, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,"
                + " PROVIDER_HEADERS, PROVIDER_EXTRA_BODY FROM "
                + providerTable, new org.springframework.jdbc.core.RowCallbackHandler() {
            @Override
            public void processRow(ResultSet rs) throws SQLException {
                providers.put(rs.getString("PROVIDER_ID"), new ProviderOverlay(
                        rs.getString("PROVIDER_ID"), rs.getString("BASE_URL"), rs.getString("API_KEY"),
                        integer(rs, "CONNECT_TIMEOUT_MS"), integer(rs, "READ_TIMEOUT_MS"),
                        rs.getString("API"),
                        readStringMap(rs, "PROVIDER_HEADERS",
                                "供应商 " + rs.getString("PROVIDER_ID")),
                        readObjectMap(rs, "PROVIDER_EXTRA_BODY",
                                "供应商 " + rs.getString("PROVIDER_ID"))));
            }
        });
        Map<String, CatalogModel> models = new LinkedHashMap<String, CatalogModel>();
        jdbc.query("SELECT PROVIDER_ID, MODEL_ID, READY, REASONING, INPUT_MODES, CONTEXT_WINDOW,"
                + " MAX_TOKENS, THINKING_FORMAT, DISPLAY_NAME, UPDATE_TIME,"
                + " THINKING_LEVEL_MAP, SAMPLER_PARAMS, SAMPLER_PARAMS_BY_LEVEL,"
                + " MODEL_HEADERS, EXTRA_BODY FROM " + modelTable, new org.springframework.jdbc.core.RowCallbackHandler() {
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
                updated == null ? 0L : updated.getTime(),
                readStringMap(rs, "THINKING_LEVEL_MAP", "模型 " + rs.getString("MODEL_ID")),
                readObjectMap(rs, "SAMPLER_PARAMS", "模型 " + rs.getString("MODEL_ID")),
                readNestedMap(rs, "SAMPLER_PARAMS_BY_LEVEL", "模型 " + rs.getString("MODEL_ID")),
                readStringMap(rs, "MODEL_HEADERS", "模型 " + rs.getString("MODEL_ID")),
                readObjectMap(rs, "EXTRA_BODY", "模型 " + rs.getString("MODEL_ID")));
    }

    /**
     * 读一个字符串 Map 列。NULL 表示「没配」，必须原样返回 null。
     *
     * <p>JSON 损坏时抛异常而不是当空处理：静默当成空会让用户的配置悄悄失效，
     * 排查时完全看不出原因。消息里带表名与模型 id，便于直接定位那一行。</p>
     *
     * @param rs    结果集
     * @param column 列名
     * @param where  出错位置描述
     * @return 解析结果；列为 NULL 时返回 null
     */
    private static Map<String, String> readStringMap(ResultSet rs, String column, String where)
            throws SQLException {
        String raw = rs.getString(column);
        if (raw == null) {
            return null;
        }
        if (raw.trim().isEmpty()) {
            return new LinkedHashMap<String, String>();
        }
        try {
            return new LinkedHashMap<String, String>(
                    AiLiteJson.mapper().readValue(raw, new TypeReference<LinkedHashMap<String, String>>() {
                    }));
        } catch (Exception ex) {
            throw new IllegalStateException(where + " 的 " + column + " 不是合法 JSON: " + ex.getMessage(), ex);
        }
    }

    /**
     * 读一个对象 Map 列。NULL 表示「没配」。
     *
     * @param rs     结果集
     * @param column 列名
     * @param where  出错位置描述
     * @return 解析结果；列为 NULL 时返回 null
     */
    private static Map<String, Object> readObjectMap(ResultSet rs, String column, String where)
            throws SQLException {
        String raw = rs.getString(column);
        if (raw == null) {
            return null;
        }
        if (raw.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return new LinkedHashMap<String, Object>(
                    AiLiteJson.mapper().readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
                    }));
        } catch (Exception ex) {
            throw new IllegalStateException(where + " 的 " + column + " 不是合法 JSON: " + ex.getMessage(), ex);
        }
    }

    /**
     * 读一个是「档位 → 对象」的嵌套列。NULL 表示「没配」。
     *
     * @param rs     结果集
     * @param column 列名
     * @param where  出错位置描述
     * @return 解析结果；列为 NULL 时返回 null
     */
    private static Map<String, Map<String, Object>> readNestedMap(ResultSet rs, String column,
                                                                  String where) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null) {
            return null;
        }
        if (raw.trim().isEmpty()) {
            return new LinkedHashMap<String, Map<String, Object>>();
        }
        try {
            return new LinkedHashMap<String, Map<String, Object>>(
                    AiLiteJson.mapper().readValue(raw,
                            new TypeReference<LinkedHashMap<String, Map<String, Object>>>() {
                            }));
        } catch (Exception ex) {
            throw new IllegalStateException(where + " 的 " + column + " 不是合法 JSON: " + ex.getMessage(), ex);
        }
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
