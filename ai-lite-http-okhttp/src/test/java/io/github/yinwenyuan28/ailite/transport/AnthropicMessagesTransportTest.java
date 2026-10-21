package io.github.yinwenyuan28.ailite.transport;

import io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmImage;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anthropic Messages 适配器的流事件、工具 block 与请求体。
 */
public class AnthropicMessagesTransportTest {

    private MockWebServer server;
    private OkHttpExecutor http;
    private AnthropicMessagesTransport transport;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        http = new OkHttpExecutor();
        transport = new AnthropicMessagesTransport(http);
    }

    @After
    public void tearDown() throws Exception {
        http.close();
        server.shutdown();
    }

    @Test
    public void parsesThinkingTextToolUseAndUsage() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"message_start\",\"message\":{\"usage\":"
                        + "{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"
                        + "data: {\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"thinking\"}}\n\n"
                        + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"think\"}}\n\n"
                        + "data: {\"type\":\"content_block_start\",\"index\":1,"
                        + "\"content_block\":{\"type\":\"text\"}}\n\n"
                        + "data: {\"type\":\"content_block_delta\",\"index\":1,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}\n\n"
                        + "data: {\"type\":\"content_block_start\",\"index\":2,"
                        + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool_1\","
                        + "\"name\":\"now\",\"input\":{}}}\n\n"
                        + "data: {\"type\":\"content_block_delta\",\"index\":2,"
                        + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}\n\n"
                        + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                        + "\"usage\":{\"output_tokens\":5}}\n\n"
                        + "data: {\"type\":\"message_stop\"}\n\n"));

        final StringBuilder deltas = new StringBuilder();
        LlmCompletion result = transport.complete(request(deltas, Collections.<LlmTool>emptyList()));

        assertThat(deltas.toString()).isEqualTo("think\n\nhello");
        assertThat(result.getContent()).isEqualTo("hello");
        assertThat(result.getThinkingText()).isEqualTo("think");
        assertThat(result.getStop()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(result.getToolCalls().get(0).getId()).isEqualTo("tool_1");
        assertThat(result.getUsage().getInputTokens()).isEqualTo(10);
        assertThat(result.getUsage().getOutputTokens()).isEqualTo(5);
    }

    @Test
    public void requestBodyUsesSystemToolsAndThinkingBudget() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"message_stop\"}\n\n"));

        transport.complete(request(null, Collections.singletonList(tool())));

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"system\":\"rules\"");
        assertThat(body).contains("\"input_schema\"");
        assertThat(body).contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":16384}");
        assertThat(body).contains("\"stream\":true");
    }

    @Test
    public void assistantThinkingAndSignatureAreSentAsBlock() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"message_stop\"}\n\n"));

        String base = server.url("").toString();
        io.github.yinwenyuan28.ailite.llm.ResolvedModel model = TestModels.model(
                AnthropicMessagesTransport.API, base, "anthropic-budget", LlmThinkingLevel.HIGH, true);
        LlmRequest request = new LlmRequest(model,
                Arrays.asList(LlmMessage.system("rules"), LlmMessage.user("hi"),
                        LlmMessage.assistant("answer", "think", "sig", null)),
                Collections.<LlmTool>emptyList(), true, null, null, null, null, null);

        transport.complete(request);

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"type\":\"thinking\"");
        assertThat(body).contains("\"thinking\":\"think\"");
        assertThat(body).contains("\"signature\":\"sig\"");
        assertThat(body).contains("\"text\":\"answer\"");
    }

    @Test
    public void imagePartsUseBase64SourceBlocks() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"message_stop\"}\n\n"));

        String base = server.url("").toString();
        io.github.yinwenyuan28.ailite.llm.ResolvedModel model = TestModels.model(
                AnthropicMessagesTransport.API, base, "anthropic-budget",
                LlmThinkingLevel.OFF, false);
        LlmRequest request = new LlmRequest(model,
                Collections.<LlmMessage>singletonList(LlmMessage.user("describe", Arrays.asList(
                        new LlmImage("image/png", "AAAA"),
                        new LlmImage("image/jpeg", "BBBB")))),
                Collections.<LlmTool>emptyList(), true, null, null, null, null, null);

        transport.complete(request);

        JsonNode content = io.github.yinwenyuan28.ailite.json.AiLiteJson.readTree(
                server.takeRequest().getBody().readUtf8())
                .get("messages").get(0).get("content");
        assertThat(content.size()).isEqualTo(3);
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("describe");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image");
        assertThat(content.get(1).path("source").path("type").asText()).isEqualTo("base64");
        assertThat(content.get(1).path("source").path("media_type").asText())
                .isEqualTo("image/png");
        assertThat(content.get(1).path("source").path("data").asText()).isEqualTo("AAAA");
        assertThat(content.get(2).path("source").path("media_type").asText())
                .isEqualTo("image/jpeg");
        assertThat(content.get(2).path("source").path("data").asText()).isEqualTo("BBBB");
    }

    @Test
    public void adjacentUserMessagesMergeWithoutLosingImageBlocks() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"message_stop\"}\n\n"));

        String base = server.url("").toString();
        io.github.yinwenyuan28.ailite.llm.ResolvedModel model = TestModels.model(
                AnthropicMessagesTransport.API, base, "anthropic-budget",
                LlmThinkingLevel.OFF, false);
        LlmRequest request = new LlmRequest(model,
                Arrays.asList(
                        LlmMessage.user("see", Collections.singletonList(
                                new LlmImage("image/png", "AAAA"))),
                        LlmMessage.user("and also")),
                Collections.<LlmTool>emptyList(), true, null, null, null, null, null);

        transport.complete(request);

        JsonNode content = io.github.yinwenyuan28.ailite.json.AiLiteJson.readTree(
                server.takeRequest().getBody().readUtf8())
                .get("messages").get(0).get("content");
        assertThat(content.size()).isEqualTo(3);
        assertThat(content.get(0).path("text").asText()).isEqualTo("see");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image");
        assertThat(content.get(1).path("source").path("data").asText()).isEqualTo("AAAA");
        assertThat(content.get(2).path("text").asText()).isEqualTo("and also");
    }

    private LlmRequest request(final StringBuilder deltas, List<LlmTool> tools) {
        String base = server.url("").toString();
        io.github.yinwenyuan28.ailite.llm.ResolvedModel model = TestModels.model(
                AnthropicMessagesTransport.API, base, "anthropic-budget", LlmThinkingLevel.HIGH, true);
        return new LlmRequest(model,
                Arrays.asList(LlmMessage.system("rules"), LlmMessage.user("hello")),
                tools, true,
                deltas == null ? null : new java.util.function.Consumer<String>() {
                    @Override
                    public void accept(String delta) {
                        deltas.append(delta);
                    }
                }, null, null, null, null);
    }

    private static LlmTool tool() {
        return new LlmTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "echo";
            }

            @Override
            public Map<String, Object> parameters() {
                return Collections.<String, Object>singletonMap("type", "object");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return "ok";
            }
        };
    }
}
