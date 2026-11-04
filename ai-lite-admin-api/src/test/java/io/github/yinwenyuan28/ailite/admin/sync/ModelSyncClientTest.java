package io.github.yinwenyuan28.ailite.admin.sync;

import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;
import io.github.yinwenyuan28.ailite.admin.jdbc.SqliteFiles;
import org.junit.Assume;
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
 *
 * <p><b>为什么整类按需跳过</b>：密钥从 {@code ai-lite-demo/src/main/resources/application-local.yml}
 * 读取，而该文件在 {@code .gitignore} 里（真实密钥不能入库）。CI 检出的是干净仓库，
 * 文件必然不存在；此前直接抛 {@code IllegalStateException} 导致构建变红。
 * 这里改为与 {@code RedisSessionCacheTest} 一致的策略：前置条件不满足就
 * {@link Assume#assumeTrue} 跳过，而不是失败。本地配了密钥的人仍会真跑这些用例。</p>
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
        // 密钥缺失时跳过而不是失败：CI 是干净检出，本地配置文件按设计不入库。
        Assume.assumeTrue("未配置本地 api-key，跳过网新真请求测试", apiKey != null);
    }

    /**
     * 端点连通性探测。只给需要真发请求的用例用，避免把纯本地逻辑的用例也一起跳过。
     *
     * @return true 表示能建立到端点的连接
     */
    private static boolean reachable() {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection)
                    new java.net.URL(trimSlash(WANGXIN_BASE) + "/models").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.getResponseCode();
            return true;
        } catch (Exception ex) {
            System.out.println("[ModelSyncClientTest] 网新端点不可达，跳过真请求：" + ex);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String trimSlash(String baseUrl) {
        String value = baseUrl;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    @Test
    public void syncFromWangxinKeepsReadyRows() {
        Assume.assumeTrue("网新端点不可达，跳过真请求测试", reachable());
        ModelSyncClient.SyncStats first = ModelSyncClient.sync(
                "openai-completions", WANGXIN_BASE, apiKey, catalog, "wangxin");
        assertThat(first.getAdded()).isGreaterThan(0);

        String modelId = catalog.findModel("wangxin", firstModelId()).getModelId();
        catalog.saveCapabilities("wangxin", modelId, false, 32768, 4096, "none");
        catalog.setReady("wangxin", modelId, true);

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

    /**
     * 请求回显必须用真实请求头，不能写死。自定义头要能看到，密钥要脱敏。
     */
    @Test
    public void requestDumpShowsRealHeadersAndMasksKey() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer real-secret-key");
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("X-Custom-Header", "custom-value");

        String dump = ModelSyncClient.requestDump("POST", "http://example/v1/chat/completions",
                headers, "{\"model\":\"m\"}", "real-secret-key");

        assertThat(dump).contains("X-Custom-Header: custom-value");
        assertThat(dump).contains("Content-Type: application/json; charset=utf-8");
        assertThat(dump).contains("Authorization: Bearer ***");
        assertThat(dump).doesNotContain("real-secret-key");
        assertThat(dump).contains("{\"model\":\"m\"}");
    }

    /**
     * 失败文本仍要脱敏。
     */
    @Test
    public void failureTextStripsApiKey() {
        assertThat(ModelSyncClient.stripKey("请求失败：" + apiKey, apiKey)).isEqualTo("请求失败：***");
        assertThat(ModelSyncClient.stripKey("请求失败：" + apiKey, apiKey)).doesNotContain(apiKey);
    }

    /**
     * 供应商级自定义请求头会带到探测请求上，且不允许覆盖 Authorization。
     *
     * <p>这条锁住「管理页配了自定义头之后，测试连通与业务调用带同一份头」。
     * 用本地假服务抓请求头，不依赖外部网络。</p>
     */
    @Test
    public void probeCarriesExtraHeaders() throws Exception {
        final java.util.List<String> received = new java.util.ArrayList<String>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(com.sun.net.httpserver.HttpExchange exchange)
                    throws java.io.IOException {
                received.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Request-Source")));
                received.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                byte[] body = "{\"data\":[{\"id\":\"m1\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            java.util.Map<String, String> extra = new java.util.LinkedHashMap<String, String>();
            extra.put("X-Request-Source", "my-app");
            // 试图覆盖 Authorization：应被忽略，仍用真实密钥。
            extra.put("authorization", "Token forged");

            ModelSyncClient.ProbeResult probe = ModelSyncClient.probe(
                    null, base, "real-key", 5000, 5000, extra);

            assertThat(probe.isOk()).isTrue();
            assertThat(received).contains("my-app");
            assertThat(received).contains("Bearer real-key");
            assertThat(received).doesNotContain("Token forged");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 测试调用同样要带供应商级头。
     */
    @Test
    public void tryChatCarriesExtraHeaders() throws Exception {
        final java.util.List<String> received = new java.util.ArrayList<String>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(com.sun.net.httpserver.HttpExchange exchange)
                    throws java.io.IOException {
                received.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Trace-Id")));
                byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            java.util.Map<String, String> extra = new java.util.LinkedHashMap<String, String>();
            extra.put("X-Trace-Id", "abc-123");

            ModelSyncClient.TryResult result = ModelSyncClient.tryChat(
                    null, "openai-completions", base, "k", "m", "none",
                    Integer.valueOf(64), "off", 5000, 5000, extra);

            assertThat(result.isOk()).isTrue();
            assertThat(received).contains("abc-123");
        } finally {
            server.stop(0);
        }
    }

    private String firstModelId() {
        ModelSyncClient.ProbeResult probe = ModelSyncClient.probe(WANGXIN_BASE, apiKey, 10000, 15000);
        assertThat(probe.isOk()).isTrue();
        return ModelSyncClient.parseModelIds(probe.getBody()).get(0);
    }

    /**
     * 从被 gitignore 的本地配置读取密钥。
     *
     * @return api-key；文件缺失或没有 api-key 时返回 {@code null}，
     *         由 {@link #setUp()} 决定跳过整类
     */
    private static String readWangxinKey() throws Exception {
        File file = new File("../ai-lite-demo/src/main/resources/application-local.yml");
        if (!file.isFile()) {
            return null;
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
        return null;
    }
}
