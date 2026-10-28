package io.github.yinwenyuan28.ailite.admin.web;

import com.sun.net.httpserver.HttpServer;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcCallBodyPolicy;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalogTest;
import io.github.yinwenyuan28.ailite.admin.jdbc.SqliteFiles;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.http.AsyncHttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.RawHttpExecutor;
import io.github.yinwenyuan28.ailite.http.jdk8.Jdk8HttpExecutor;
import io.github.yinwenyuan28.ailite.json.AiLiteJson;
import io.github.yinwenyuan28.ailite.prompt.PromptCatalog;
import io.github.yinwenyuan28.ailite.prompt.PromptKey;
import io.github.yinwenyuan28.ailite.prompt.PromptManager;
import io.github.yinwenyuan28.ailite.prompt.RenderedPrompt;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理页开关、供应商覆盖、模型能力、业务发布和调用日志。
 */
public class AdminWebTest {

    private static final String SECRET = "super-secret-key";

    private JdbcTemplate jdbc;
    private JdbcRuntimeCatalog catalog;
    private PromptManager prompts;
    private AiLiteConfig config;
    private MockMvc mockMvc;
    private HttpServer server;
    /** 配置启用的传输；诊断必须走它，用例据此断言"没有绕开网关"。 */
    private HttpExecutor httpExecutor;

    @Before
    public void setUp() throws Exception {
        jdbc = SqliteFiles.open("admin-web");
        JdbcRuntimeCatalogTest.createTables(jdbc);
        catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY", "AI_LITE_MODEL",
                "AI_LITE_BIZ_BINDING", 0);
        PromptKey key = new PromptKey();
        key.setKey("demo-chat");
        key.setCatalogBound(true);
        prompts = new PromptManager(new PromptCatalog(Collections.singletonList(key)));
        config = new AiLiteConfig();
        config.getLlm().getBizIds().add("demo-chat");
        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi("openai-completions");
        provider.setBaseUrl("http://127.0.0.1:9/v1");
        provider.setApiKey(SECRET);
        config.getLlm().getProviders().put("demo", provider);
        AiLiteConfig.Provider anthropic = new AiLiteConfig.Provider();
        anthropic.setApi("anthropic-messages");
        anthropic.setBaseUrl("http://127.0.0.1:9");
        anthropic.setApiKey(SECRET);
        config.getLlm().getProviders().put("claude", anthropic);
        httpExecutor = new Jdk8HttpExecutor();
        mockMvc = MockMvcBuilders.standaloneSetup(controller())
                .setMessageConverters(
                        new ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(AiLiteJson.mapper()))
                .build();
    }

    @Test
    public void tryChatReturnsErrorBodyFromConfiguredTransport() throws Exception {
        catalog.insertModelIfAbsent("demo", "boom");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
                byte[] body = "{\"error\":{\"message\":\"quota exceeded\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        server.start();
        config.getLlm().getProviders().get("demo").setBaseUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");

        mockMvc.perform(post("/ai-lite/admin/api/providers/demo/models/boom/try")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thinkingLevel\":\"off\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"ok\":false")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("429")))
                // 完整错误正文必须出现在 response 字段里：这是试跑的核心价值。
                .andExpect(content().string(org.hamcrest.Matchers.containsString("quota exceeded")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SECRET))));
    }

    /**
     * 试跑必须经过配置启用的执行器。
     *
     * <p>用 {@link Jdk8HttpExecutor} 的子类打标记：只有请求真的从执行器发出，
     * 假服务才会看到这个头。若退回 HttpURLConnection 兜底，标记不会出现。</p>
     */
    @Test
    public void tryChatGoesThroughConfiguredExecutor() throws Exception {
        catalog.insertModelIfAbsent("demo", "marker");
        final java.util.concurrent.atomic.AtomicBoolean seen = new java.util.concurrent.atomic.AtomicBoolean();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
                seen.set("executor".equals(exchange.getRequestHeaders().getFirst(MARKER_HEADER)));
                byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        server.start();
        config.getLlm().getProviders().get("demo").setBaseUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        // 换成一个会在出站请求里打标记的执行器。
        httpExecutor = new MarkerExecutor();
        mockMvc = MockMvcBuilders.standaloneSetup(controller())
                .setMessageConverters(
                        new ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(AiLiteJson.mapper()))
                .build();

        mockMvc.perform(post("/ai-lite/admin/api/providers/demo/models/marker/try")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thinkingLevel\":\"off\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"ok\":true")));

        assertThat(seen.get()).as("试跑必须经过配置启用的 HttpExecutor").isTrue();
    }

    /** 给每个出站请求加一个识别头，用于证明请求来自执行器。 */
    private static final String MARKER_HEADER = "X-AiLite-Transport";

    /** 包一层真实执行器并打标记；Jdk8HttpExecutor 是 final，只能委托。 */
    private static final class MarkerExecutor implements AsyncHttpExecutor, RawHttpExecutor {
        private final Jdk8HttpExecutor delegate = new Jdk8HttpExecutor();

        @Override
        public HttpResult execute(HttpRequest request) {
            return delegate.execute(mark(request));
        }

        @Override
        public HttpResult exchange(HttpRequest request) {
            return delegate.exchange(mark(request));
        }

        @Override
        public void executeStreaming(HttpRequest request, io.github.yinwenyuan28.ailite.http.StreamListener listener) {
            delegate.executeStreaming(mark(request), listener);
        }

        @Override
        public java.util.concurrent.CompletableFuture<HttpResult> executeAsync(HttpRequest request) {
            return delegate.executeAsync(mark(request));
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> executeStreamingAsync(
                HttpRequest request, io.github.yinwenyuan28.ailite.http.StreamListener listener) {
            return delegate.executeStreamingAsync(mark(request), listener);
        }

        @Override
        public void close() {
            delegate.close();
        }

        private static HttpRequest mark(HttpRequest request) {
            java.util.Map<String, String> headers =
                    new java.util.LinkedHashMap<String, String>(request.getHeaders());
            headers.put(MARKER_HEADER, "executor");
            return new HttpRequest(request.getMethod(), request.getUrl(), headers, request.getBody(),
                    request.getConnectTimeoutMs(), request.getReadTimeoutMs(), request.getCancel());
        }
    }

    private AdminController controller() {
        return new AdminController(config, catalog, prompts, jdbc, null, new JdbcCallBodyPolicy(jdbc),
                httpExecutor);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void overlayKeepsBlankKeyAndRejectsUnknownProvider() throws Exception {
        mockMvc.perform(post("/ai-lite/admin/api/providers/demo/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"http://override/v1\",\"apiKey\":\"first-key\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/ai-lite/admin/api/providers/demo/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"http://again/v1\",\"apiKey\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("first-key"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SECRET))));

        assertThat(catalog.findProvider("demo").getBaseUrl()).isEqualTo("http://again/v1");
        assertThat(catalog.findProvider("demo").getApiKey()).isEqualTo("first-key");

        mockMvc.perform(post("/ai-lite/admin/api/providers/missing/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("未知供应商")));
    }

    @Test
    public void connectivityFailureDoesNotLeakKey() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
                byte[] body = SECRET.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        server.start();
        config.getLlm().getProviders().get("demo").setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");

        mockMvc.perform(post("/ai-lite/admin/api/providers/demo/connectivity"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"ok\":false")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SECRET))));
        assertThat(catalog.findModel("demo", "leaked")).isNull();
    }

    @Test
    public void syncAddsOnlyNewIdsAndAnthropicIsRejected() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
                byte[] body = "{\"data\":[{\"id\":\"qwen\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        server.start();
        config.getLlm().getProviders().get("demo").setBaseUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        catalog.insertModelIfAbsent("demo", "qwen");
        catalog.saveCapabilities("demo", "qwen", true, 32768, 4096, "chat-template");

        mockMvc.perform(post("/ai-lite/admin/api/providers/demo/models/sync"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"stored\":true")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"added\""))));
        assertThat(catalog.findModel("demo", "qwen").isReady()).isTrue();
        assertThat(catalog.findModel("demo", "qwen").getThinkingFormat()).isEqualTo("chat-template");

        mockMvc.perform(post("/ai-lite/admin/api/providers/demo/models/adopt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelIds\":[\"qwen\",\"extra\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"added\":1")));
        assertThat(catalog.findModel("demo", "extra").isReady()).isFalse();
        assertThat(catalog.findModel("demo", "qwen").getThinkingFormat()).isEqualTo("chat-template");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/ai-lite/admin/api/providers/demo/models/extra"))
                .andExpect(status().isOk());
        assertThat(catalog.findModel("demo", "extra")).isNull();

        mockMvc.perform(post("/ai-lite/admin/api/providers/claude/models/sync"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("请手工添加")));
    }

    @Test
    public void capabilitiesAndBinding() throws Exception {
        mockMvc.perform(post("/ai-lite/admin/api/providers/demo/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"qwen\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/ai-lite/admin/api/providers/demo/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"qwen\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("模型已存在")));
        mockMvc.perform(put("/ai-lite/admin/api/providers/demo/models/qwen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasoning\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("能力参数")));
        mockMvc.perform(put("/ai-lite/admin/api/biz/demo-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"demo\",\"modelId\":\"qwen\",\"thinkingLevel\":\"off\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("尚未补齐")));

        mockMvc.perform(put("/ai-lite/admin/api/providers/demo/models/qwen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasoning\":true,\"contextWindow\":32768,\"maxTokens\":4096,"
                                + "\"thinkingFormat\":\"chat-template\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/ai-lite/admin/api/biz/demo-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"demo\",\"modelId\":\"qwen\",\"thinkingLevel\":\"off\"}"))
                .andExpect(status().isOk());
        assertThat(catalog.findBinding("demo-chat").getModelId()).isEqualTo("qwen");

        mockMvc.perform(put("/ai-lite/admin/api/biz/unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"demo\",\"modelId\":\"qwen\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("未登记")));
    }

    @Test
    public void publishThenRenderReadsNewTemplate() throws Exception {
        String draft = mockMvc.perform(post("/ai-lite/admin/api/biz/demo-chat/prompt/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemTemplate\":\"你好模板\",\"userTemplate\":\"问题\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String versionId = draft.replaceAll(".*\"versionId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/ai-lite/admin/api/biz/demo-chat/prompt/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"" + versionId + "\"}"))
                .andExpect(status().isOk());

        RenderedPrompt rendered = prompts.render("demo-chat", null);
        assertThat(rendered.getSystem()).isEqualTo("你好模板");

        String second = mockMvc.perform(post("/ai-lite/admin/api/biz/demo-chat/prompt/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemTemplate\":\"第二版\",\"userTemplate\":\"问题\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondId = second.replaceAll(".*\"versionId\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(post("/ai-lite/admin/api/biz/demo-chat/prompt/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"" + secondId + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/ai-lite/admin/api/biz/demo-chat/prompt"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"versions\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"retired\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"published\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("createTime")));
        mockMvc.perform(get("/ai-lite/admin/api/biz/demo-chat/prompt/versions"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(versionId)));

        mockMvc.perform(post("/ai-lite/admin/api/biz/demo-chat/prompt/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"" + versionId + "\"}"))
                .andExpect(status().isOk());
        assertThat(prompts.render("demo-chat", null).getSystem()).isEqualTo("你好模板");
    }

    @Test
    public void tryChatReturnsReplyAndReadyModelsComeFirst() throws Exception {
        catalog.insertModelIfAbsent("demo", "zzz");
        catalog.insertModelIfAbsent("demo", "aaa");
        catalog.saveCapabilities("demo", "zzz", true, 1024, 128, "reasoning-effort");
        final String[] seenRequest = new String[1];
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                int n;
                while ((n = exchange.getRequestBody().read(chunk)) >= 0) {
                    buffer.write(chunk, 0, n);
                }
                seenRequest[0] = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                byte[] body = "{\"choices\":[{\"message\":{\"content\":\"好\"}}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        server.start();
        config.getLlm().getProviders().get("demo").setBaseUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");

        mockMvc.perform(post("/ai-lite/admin/api/providers/demo/models/zzz/try")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thinkingLevel\":\"high\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("你好")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("POST ")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/chat/completions")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Bearer ***")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("choices")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SECRET))));
        assertThat(seenRequest[0]).contains("\"max_tokens\":128");
        assertThat(seenRequest[0]).contains("\"reasoning_effort\":\"high\"");

        String listed = mockMvc.perform(get("/ai-lite/admin/api/providers"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed.indexOf("\"modelId\":\"zzz\"")).isLessThan(listed.indexOf("\"modelId\":\"aaa\""));
    }

    @Test
    public void callsRequireTableNameAndOmitSecrets() throws Exception {
        mockMvc.perform(get("/ai-lite/admin/api/calls"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("未配置调用记录表名")));

        jdbc.execute("CREATE TABLE AI_LITE_LLM_CALL ("
                + "CALL_ID VARCHAR(64) PRIMARY KEY, BIZ_ID VARCHAR(128), PROVIDER VARCHAR(64),"
                + "MODEL_ID VARCHAR(128), API VARCHAR(64), PROFILE VARCHAR(64), THINKING_LEVEL VARCHAR(32),"
                + "ROUND_NO INT, INPUT_TOKENS INT, OUTPUT_TOKENS INT, TOTAL_TOKENS INT, LATENCY_MS BIGINT,"
                + "STOP_REASON VARCHAR(32), SUCCESS INT, ERROR_BRIEF VARCHAR(255), CREATE_TIME TIMESTAMP)");
        jdbc.update("INSERT INTO AI_LITE_LLM_CALL (CALL_ID, BIZ_ID, PROVIDER, MODEL_ID, SUCCESS, ERROR_BRIEF, CREATE_TIME)"
                        + " VALUES ('c1', 'demo-chat', 'demo', 'qwen', 0, 'timeout', ?)",
                new Timestamp(2_000L));
        jdbc.update("INSERT INTO AI_LITE_LLM_CALL (CALL_ID, BIZ_ID, PROVIDER, MODEL_ID, SUCCESS, ERROR_BRIEF, CREATE_TIME)"
                        + " VALUES ('c2', 'other', 'demo', 'qwen', 1, 'ok', ?)",
                new Timestamp(1_000L));
        MockMvc calls = MockMvcBuilders.standaloneSetup(
                        new AdminController(config, catalog, prompts, jdbc, "AI_LITE_LLM_CALL",
                                new JdbcCallBodyPolicy(jdbc)))
                .setMessageConverters(
                        new ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(AiLiteJson.mapper()))
                .build();

        calls.perform(get("/ai-lite/admin/api/calls").param("bizId", "demo-chat"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"items\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"total\":1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("demo-chat")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("other"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SECRET))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("systemTemplate"))));
        calls.perform(get("/ai-lite/admin/api/calls").param("success", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"total\":1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("c2")));
        calls.perform(get("/ai-lite/admin/api/calls/c1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("timeout")));

        catalog.insertModelIfAbsent("demo", "qwen");
        catalog.saveCapabilities("demo", "qwen", false, 1024, 128, "none");
        mockMvc.perform(put("/ai-lite/admin/api/biz/demo-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"demo\",\"modelId\":\"qwen\",\"thinkingLevel\":\"off\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/ai-lite/admin/api/biz/demo-chat/binding"))
                .andExpect(status().isOk());
        assertThat(catalog.findBinding("demo-chat")).isNull();
    }

    @Test
    public void switchRegistersOnlyWhenEnabledOnWeb() {
        WebApplicationContextRunner web = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AiLiteAdminAutoConfiguration.class))
                .withUserConfiguration(ReadyBeans.class);
        web.withPropertyValues("ai-lite.admin.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(AdminController.class);
        });
        web.withPropertyValues("ai-lite.admin.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(AdminController.class);
        });
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AiLiteAdminAutoConfiguration.class))
                .withUserConfiguration(ReadyBeans.class)
                .withPropertyValues("ai-lite.admin.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(AdminController.class));
    }

    /**
     * 给开关测试一个已经建好的目录，避免管理页配置去连真实数据库。
     */
    @Configuration
    static class ReadyBeans {
        @Bean
        public JdbcTemplate jdbcTemplate() {
            JdbcTemplate template = SqliteFiles.open("admin-switch");
            JdbcRuntimeCatalogTest.createTables(template);
            return template;
        }

        @Bean
        public JdbcRuntimeCatalog aiLiteRuntimeCatalog(JdbcTemplate jdbcTemplate) {
            return new JdbcRuntimeCatalog(jdbcTemplate, "AI_LITE_PROVIDER_OVERLAY", "AI_LITE_MODEL",
                    "AI_LITE_BIZ_BINDING", 0);
        }

        @Bean
        public AiLiteConfig aiLiteConfig() {
            return new AiLiteConfig();
        }
    }
}
