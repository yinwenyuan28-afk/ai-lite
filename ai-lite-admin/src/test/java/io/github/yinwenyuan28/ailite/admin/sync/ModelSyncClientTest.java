package io.github.yinwenyuan28.ailite.admin.sync;

import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;
import io.github.yinwenyuan28.ailite.admin.jdbc.SqliteFiles;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真请求打网新 wangxin 供应商。失败摘要和 Anthropic 拒绝不发请求。
 */
public class ModelSyncClientTest {

    private static final String WANGXIN_BASE =
            "https://tq.insigma-dev.com/doapi/insigma-ddd-ops/open/access/ai/v1";

    private JdbcRuntimeCatalog catalog;
    private String apiKey;

    @Before
    public void setUp() throws Exception {
        JdbcTemplate jdbc = SqliteFiles.open("sync");
        io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalogTest.createTables(jdbc);
        catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY", "AI_LITE_MODEL",
                "AI_LITE_BIZ_BINDING", 0);
        apiKey = readWangxinKey();
    }

    @Test
    public void syncFromWangxinKeepsReadyRows() {
        ModelSyncClient.SyncStats first = ModelSyncClient.sync(
                "openai-completions", WANGXIN_BASE, apiKey, catalog, "wangxin");
        assertThat(first.getAdded()).isGreaterThan(0);

        String modelId = catalog.findModel("wangxin", firstModelId()).getModelId();
        catalog.saveCapabilities("wangxin", modelId, false, 32768, 4096, "none");

        ModelSyncClient.SyncStats second = ModelSyncClient.sync(
                "openai-completions", WANGXIN_BASE, apiKey, catalog, "wangxin");
        assertThat(second.getAdded()).isEqualTo(0);
        assertThat(second.getExisting()).isEqualTo(first.getAdded());
        assertThat(catalog.findModel("wangxin", modelId).isReady()).isTrue();
        assertThat(catalog.findModel("wangxin", modelId).getThinkingFormat()).isEqualTo("none");
    }

    @Test
    public void anthropicIsRejectedWithoutCallingModels() {
        assertThatThrownBy(() -> ModelSyncClient.sync(
                "anthropic-messages", WANGXIN_BASE, apiKey, catalog, "wangxin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("请手工添加模型");
    }

    @Test
    public void failureTextStripsApiKey() {
        assertThat(ModelSyncClient.stripKey("请求失败：" + apiKey, apiKey)).isEqualTo("请求失败：***");
        assertThat(ModelSyncClient.stripKey("请求失败：" + apiKey, apiKey)).doesNotContain(apiKey);
    }

    private String firstModelId() {
        ModelSyncClient.ProbeResult probe = ModelSyncClient.probe(WANGXIN_BASE, apiKey, 10000, 15000);
        assertThat(probe.isOk()).isTrue();
        return ModelSyncClient.parseModelIds(probe.getBody()).get(0);
    }

    private static String readWangxinKey() throws Exception {
        File file = new File("../ai-lite-demo/src/main/resources/application-local.yml");
        if (!file.isFile()) {
            throw new IllegalStateException("缺少 " + file.getAbsolutePath());
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                int index = line.indexOf("api-key:");
                if (index < 0) {
                    continue;
                }
                String value = line.substring(index + "api-key:".length()).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!value.isEmpty()) {
                    return value;
                }
            }
        } finally {
            reader.close();
        }
        throw new IllegalStateException("application-local.yml 里没有 api-key");
    }
}
