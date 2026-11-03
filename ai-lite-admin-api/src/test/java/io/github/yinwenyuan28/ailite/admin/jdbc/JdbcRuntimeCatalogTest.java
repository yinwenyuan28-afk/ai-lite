package io.github.yinwenyuan28.ailite.admin.jdbc;

import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

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
