package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 目录表的覆盖、增量同步、重复行和缓存。
 */
public class JdbcRuntimeCatalogTest {

    private JdbcTemplate jdbc;
    private JdbcRuntimeCatalog catalog;

    @Before
    public void setUp() {
        jdbc = SqliteFiles.open("catalog");
        createTables(jdbc);
        catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY", "AI_LITE_MODEL",
                "AI_LITE_BIZ_BINDING", 60);
    }

    @Test
    public void missingTableFailsAndNamesTheScript() {
        JdbcTemplate empty = SqliteFiles.open("empty");

        assertThatThrownBy(() -> new JdbcRuntimeCatalog(empty, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ai-lite-catalog.sql");
    }

    @Test
    public void overlayRoundTripAndClear() {
        catalog.saveOverlay(new ProviderOverlay("demo", "http://override/v1", "secret",
                Integer.valueOf(2000), Integer.valueOf(3000)));

        ProviderOverlay loaded = catalog.findProvider("demo");
        assertThat(loaded.getBaseUrl()).isEqualTo("http://override/v1");
        assertThat(loaded.getApiKey()).isEqualTo("secret");

        catalog.clearOverlay("demo");
        assertThat(catalog.findProvider("demo")).isNull();
    }

    /**
     * 协议覆盖要能写库读回；不写协议时该列为 null，表示沿用配置文件。
     */
    @Test
    public void overlayRoundTripsProtocol() {
        catalog.saveOverlay(new ProviderOverlay("demo", "http://override/v1", "secret",
                null, null, "openai-responses"));
        assertThat(catalog.findProvider("demo").getApi()).isEqualTo("openai-responses");

        catalog.saveOverlay(new ProviderOverlay("demo", "http://override/v2", "secret",
                null, null, null));
        assertThat(catalog.findProvider("demo").getApi()).isNull();
    }

    /**
     * 旧库的覆盖表没有 API 列：补列后能读写，已有行的协议保持空（沿用配置文件）。
     */
    @Test
    public void ensuresApiColumnOnLegacyTable() {
        JdbcTemplate legacy = SqliteFiles.open("legacy-overlay");
        legacy.execute("CREATE TABLE AI_LITE_PROVIDER_OVERLAY ("
                + "PROVIDER_ID VARCHAR(64) PRIMARY KEY, BASE_URL VARCHAR(500), API_KEY VARCHAR(500),"
                + "CONNECT_TIMEOUT_MS INT, READ_TIMEOUT_MS INT, UPDATE_TIME TIMESTAMP)");
        legacy.execute("CREATE TABLE AI_LITE_MODEL ("
                + "MODEL_ROW_ID VARCHAR(64) PRIMARY KEY, PROVIDER_ID VARCHAR(64) NOT NULL,"
                + "MODEL_ID VARCHAR(128) NOT NULL, READY INT NOT NULL, REASONING INT, INPUT_MODES VARCHAR(64),"
                + "CONTEXT_WINDOW INT, MAX_TOKENS INT, THINKING_FORMAT VARCHAR(64),"
                + "DISPLAY_NAME VARCHAR(128), UPDATE_TIME TIMESTAMP)");
        legacy.execute("CREATE TABLE AI_LITE_BIZ_BINDING ("
                + "BIZ_ID VARCHAR(64) PRIMARY KEY, PROVIDER_ID VARCHAR(64) NOT NULL,"
                + "MODEL_ID VARCHAR(128) NOT NULL, THINKING_LEVEL VARCHAR(32) NOT NULL, UPDATE_TIME TIMESTAMP)");
        legacy.update("INSERT INTO AI_LITE_PROVIDER_OVERLAY (PROVIDER_ID, BASE_URL, API_KEY)"
                + " VALUES ('demo', 'http://old/v1', 'k')");

        JdbcRuntimeCatalog.ensureProviderApiColumn(legacy, "AI_LITE_PROVIDER_OVERLAY");

        JdbcRuntimeCatalog migrated = new JdbcRuntimeCatalog(legacy, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0);
        assertThat(migrated.findProvider("demo").getApi()).isNull();
        migrated.saveOverlay(new ProviderOverlay("demo", "http://old/v1", "k", null, null,
                "anthropic-messages"));
        assertThat(migrated.findProvider("demo").getApi()).isEqualTo("anthropic-messages");

        // 再补一次是幂等的，不会因为列已存在而失败。
        JdbcRuntimeCatalog.ensureProviderApiColumn(legacy, "AI_LITE_PROVIDER_OVERLAY");
    }

    /* ---------------- 0.1.15 新增的七列 ---------------- */

    /**
     * 旧表（没有七列）构造时会自动补列，已有行的新列为 NULL。
     *
     * <p>升级路径：补列后全 NULL，解析时回落配置文件，行为与升级前一致。</p>
     */
    @Test
    public void ensuresExtraColumnsOnLegacyTable() {
        JdbcTemplate legacy = SqliteFiles.open("legacy-extra");
        legacy.execute("CREATE TABLE AI_LITE_PROVIDER_OVERLAY ("
                + "PROVIDER_ID VARCHAR(64) PRIMARY KEY, BASE_URL VARCHAR(500), API_KEY VARCHAR(500),"
                + " CONNECT_TIMEOUT_MS INT, READ_TIMEOUT_MS INT, UPDATE_TIME TIMESTAMP)");
        legacy.execute("CREATE TABLE AI_LITE_MODEL ("
                + "MODEL_ROW_ID VARCHAR(64) PRIMARY KEY, PROVIDER_ID VARCHAR(64) NOT NULL,"
                + " MODEL_ID VARCHAR(128) NOT NULL, READY INT NOT NULL, REASONING INT,"
                + " INPUT_MODES VARCHAR(64), CONTEXT_WINDOW INT, MAX_TOKENS INT,"
                + " THINKING_FORMAT VARCHAR(64), DISPLAY_NAME VARCHAR(128), UPDATE_TIME TIMESTAMP)");
        legacy.execute("CREATE TABLE AI_LITE_BIZ_BINDING ("
                + "BIZ_ID VARCHAR(64) PRIMARY KEY, PROVIDER_ID VARCHAR(64) NOT NULL,"
                + " MODEL_ID VARCHAR(128) NOT NULL, THINKING_LEVEL VARCHAR(32) NOT NULL, UPDATE_TIME TIMESTAMP)");
        legacy.update("INSERT INTO AI_LITE_MODEL (MODEL_ROW_ID, PROVIDER_ID, MODEL_ID, READY,"
                + " DISPLAY_NAME) VALUES ('r1', 'demo', 'qwen', 1, 'qwen')");

        JdbcRuntimeCatalog migrated = new JdbcRuntimeCatalog(legacy, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0);

        CatalogModel model = migrated.findModel("demo", "qwen");
        assertThat(model).isNotNull();
        // 新列为 NULL：五个请求参数返回 null，表示沿用配置文件。
        assertThat(model.getThinkingLevelMap()).isNull();
        assertThat(model.getSamplerParams()).isNull();
        assertThat(model.getSamplerParamsByLevel()).isNull();
        assertThat(model.getModelHeaders()).isNull();
        assertThat(model.getExtraBody()).isNull();
    }

    /**
     * 模型级五个请求参数能写库读回，且 NULL 与空 Map 可区分。
     */
    @Test
    public void modelRequestParamsRoundTrip() {
        catalog.insertModelIfAbsent("demo", "qwen");

        Map<String, String> levelMap = new LinkedHashMap<String, String>();
        levelMap.put("low", "low");
        levelMap.put("off", null);
        Map<String, Object> sampler = new LinkedHashMap<String, Object>();
        sampler.put("temperature", Double.valueOf(0.2));
        Map<String, Map<String, Object>> byLevel = new LinkedHashMap<String, Map<String, Object>>();
        byLevel.put("high", Collections.<String, Object>singletonMap("top_p", Double.valueOf(0.95)));
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Model", "v2");
        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("vendor_field", "x");

        catalog.saveCapabilities("demo", "qwen", true, Collections.singletonList("text"),
                32768, 4096, "deepseek", "qwen", levelMap, sampler, byLevel, headers, extra);

        CatalogModel model = catalog.findModel("demo", "qwen");
        assertThat(model.getThinkingLevelMap()).containsEntry("low", "low").containsKey("off");
        assertThat(model.getThinkingLevelMap().get("off")).isNull();
        assertThat(model.getSamplerParams()).containsEntry("temperature", Double.valueOf(0.2));
        assertThat(model.getSamplerParamsByLevel().get("high"))
                .containsEntry("top_p", Double.valueOf(0.95));
        assertThat(model.getModelHeaders()).containsEntry("X-Model", "v2");
        assertThat(model.getExtraBody()).containsEntry("vendor_field", "x");
    }

    /**
     * 传空 Map 表示明确清空，读到的是空 Map 而不是 null。
     */
    @Test
    public void emptyMapIsStoredAsExplicitEmpty() {
        catalog.insertModelIfAbsent("demo", "qwen");
        catalog.saveCapabilities("demo", "qwen", true, Collections.singletonList("text"),
                32768, 4096, "deepseek", "qwen",
                new LinkedHashMap<String, String>(), new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Map<String, Object>>(),
                new LinkedHashMap<String, String>(), new LinkedHashMap<String, Object>());

        CatalogModel model = catalog.findModel("demo", "qwen");
        assertThat(model.getThinkingLevelMap()).isNotNull().isEmpty();
        assertThat(model.getSamplerParams()).isNotNull().isEmpty();
        assertThat(model.getExtraBody()).isNotNull().isEmpty();
    }

    /**
     * 不传（null）表示不改：先写入值，再用 null 保存一次，值应保留。
     */
    @Test
    public void nullMeansLeaveUnchanged() {
        catalog.insertModelIfAbsent("demo", "qwen");
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Model", "v2");
        catalog.saveCapabilities("demo", "qwen", true, Collections.singletonList("text"),
                32768, 4096, "deepseek", "qwen",
                null, null, null, headers, null);

        catalog.saveCapabilities("demo", "qwen", true, Collections.singletonList("text"),
                32768, 4096, "deepseek", "qwen", null, null, null, null, null);

        assertThat(catalog.findModel("demo", "qwen").getModelHeaders())
                .containsEntry("X-Model", "v2");
    }

    /**
     * 供应商级两个请求参数能写库读回；不设置时为 null。
     */
    @Test
    public void overlayRequestParamsRoundTrip() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Request-Source", "my-app");
        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("chat_template_kwargs", Collections.<String, Object>singletonMap(
                "enable_thinking", Boolean.TRUE));

        catalog.saveOverlay(new ProviderOverlay("demo", "http://override/v1", "k", null, null,
                null, headers, extra));

        ProviderOverlay loaded = catalog.findProvider("demo");
        assertThat(loaded.getHeaders()).containsEntry("X-Request-Source", "my-app");
        assertThat(loaded.getExtraBody()).containsKey("chat_template_kwargs");

        // 不传这两项时为 null，表示沿用配置文件。
        catalog.saveOverlay(new ProviderOverlay("demo", "http://override/v1", "k", null, null));
        ProviderOverlay cleared = catalog.findProvider("demo");
        assertThat(cleared.getHeaders()).isNull();
        assertThat(cleared.getExtraBody()).isNull();
    }

    /**
     * JSON 损坏时读取要明确报错，不能静默当空。
     */
    @Test
    public void corruptJsonFailsLoudly() {
        catalog.insertModelIfAbsent("demo", "qwen");
        jdbc.update("UPDATE AI_LITE_MODEL SET MODEL_HEADERS = ? WHERE MODEL_ID = ?",
                "{not-json", "qwen");
        catalog.invalidate();

        assertThatThrownBy(() -> catalog.findModel("demo", "qwen"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODEL_HEADERS")
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    public void syncDoesNotOverwriteReadyModel() {
        assertThat(catalog.insertModelIfAbsent("demo", "qwen")).isTrue();
        catalog.saveCapabilities("demo", "qwen", true, 32768, 4096, "chat-template");
        catalog.setReady("demo", "qwen", true);

        assertThat(catalog.insertModelIfAbsent("demo", "qwen")).isFalse();

        CatalogModel model = catalog.findModel("demo", "qwen");
        assertThat(model.isReady()).isTrue();
        assertThat(model.isReasoning()).isTrue();
        assertThat(model.getContextWindow()).isEqualTo(Integer.valueOf(32768));
        assertThat(model.getThinkingFormat()).isEqualTo("chat-template");
    }

    @Test
    public void bindingRejectsModelThatIsNotReady() {
        catalog.insertModelIfAbsent("demo", "qwen");

        assertThatThrownBy(() -> catalog.saveBinding(new BizBinding("demo-chat", "demo", "qwen", "off")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型未就绪");
    }

    /**
     * 就绪与能力参数解耦：填完参数不算就绪，必须显式 setReady。
     */
    @Test
    public void capabilitiesDoNotImplyReady() {
        catalog.insertModelIfAbsent("demo", "qwen");
        catalog.saveCapabilities("demo", "qwen", true, 32768, 4096, "chat-template");

        assertThat(catalog.findModel("demo", "qwen").isReady()).isFalse();
        assertThatThrownBy(() -> catalog.saveBinding(new BizBinding("demo-chat", "demo", "qwen", "off")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型未就绪");

        catalog.setReady("demo", "qwen", true);
        assertThat(catalog.findModel("demo", "qwen").isReady()).isTrue();
        catalog.saveBinding(new BizBinding("demo-chat", "demo", "qwen", "off"));
        assertThat(catalog.findBinding("demo-chat").getModelId()).isEqualTo("qwen");
    }

    /**
     * 停用不回退能力参数，也不解除绑定。
     */
    @Test
    public void disableKeepsCapabilitiesAndBinding() {
        catalog.insertModelIfAbsent("demo", "qwen");
        catalog.saveCapabilities("demo", "qwen", true, 32768, 4096, "chat-template");
        catalog.setReady("demo", "qwen", true);
        catalog.saveBinding(new BizBinding("demo-chat", "demo", "qwen", "off"));

        catalog.setReady("demo", "qwen", false);

        CatalogModel model = catalog.findModel("demo", "qwen");
        assertThat(model.isReady()).isFalse();
        // 能力参数保留。
        assertThat(model.getContextWindow()).isEqualTo(Integer.valueOf(32768));
        assertThat(model.getThinkingFormat()).isEqualTo("chat-template");
        // 绑定保留：静默解绑会让业务在无人察觉时失去模型。
        assertThat(catalog.findBinding("demo-chat")).isNotNull();
    }

    @Test
    public void setReadyOnMissingModelFails() {
        assertThatThrownBy(() -> catalog.setReady("demo", "nope", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型不存在");
        assertThatThrownBy(() -> catalog.setReady("demo", " ", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void newerDuplicateRowWins() {
        jdbc.update("INSERT INTO AI_LITE_MODEL (MODEL_ROW_ID, PROVIDER_ID, MODEL_ID, READY, UPDATE_TIME)"
                + " VALUES ('old', 'demo', 'qwen', 0, ?)", new Timestamp(1L));
        jdbc.update("INSERT INTO AI_LITE_MODEL (MODEL_ROW_ID, PROVIDER_ID, MODEL_ID, READY, UPDATE_TIME)"
                + " VALUES ('new', 'demo', 'qwen', 1, ?)", new Timestamp(2_000L));
        catalog.invalidate();

        assertThat(catalog.findModel("demo", "qwen").isReady()).isTrue();
    }

    @Test
    public void cacheHidesRawWritesUntilInvalidate() {
        catalog.insertModelIfAbsent("demo", "qwen");
        assertThat(catalog.findModel("demo", "qwen").isReady()).isFalse();

        jdbc.update("UPDATE AI_LITE_MODEL SET READY = 1 WHERE MODEL_ID = 'qwen'");
        assertThat(catalog.findModel("demo", "qwen").isReady()).isFalse();

        catalog.invalidate();
        assertThat(catalog.findModel("demo", "qwen").isReady()).isTrue();
    }

    public static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE AI_LITE_PROVIDER_OVERLAY ("
                + "PROVIDER_ID VARCHAR(64) PRIMARY KEY, BASE_URL VARCHAR(500), API_KEY VARCHAR(500),"
                + "CONNECT_TIMEOUT_MS INT, READ_TIMEOUT_MS INT, UPDATE_TIME TIMESTAMP)");
        jdbc.execute("CREATE TABLE AI_LITE_MODEL ("
                + "MODEL_ROW_ID VARCHAR(64) PRIMARY KEY, PROVIDER_ID VARCHAR(64) NOT NULL,"
                + "MODEL_ID VARCHAR(128) NOT NULL, READY INT NOT NULL, REASONING INT, INPUT_MODES VARCHAR(64),"
                + "CONTEXT_WINDOW INT, MAX_TOKENS INT, THINKING_FORMAT VARCHAR(64),"
                + "DISPLAY_NAME VARCHAR(128), UPDATE_TIME TIMESTAMP)");
        jdbc.execute("CREATE TABLE AI_LITE_BIZ_BINDING ("
                + "BIZ_ID VARCHAR(64) PRIMARY KEY, PROVIDER_ID VARCHAR(64) NOT NULL,"
                + "MODEL_ID VARCHAR(128) NOT NULL, THINKING_LEVEL VARCHAR(32) NOT NULL, UPDATE_TIME TIMESTAMP)");
    }
}
