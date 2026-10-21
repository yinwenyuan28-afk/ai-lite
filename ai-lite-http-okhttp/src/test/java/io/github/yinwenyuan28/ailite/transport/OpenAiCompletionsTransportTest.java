package io.github.yinwenyuan28.ailite.transport;

import io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI Chat Completions 适配器的阻塞、流式、工具与请求体行为。
 */
public class OpenAiCompletionsTransportTest {

    private MockWebServer server;
    private OkHttpExecutor http;
    private OpenAiCompletionsTransport transport;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        http = new OkHttpExecutor();
        transport = new OpenAiCompletionsTransport(http);
    }

    @After
    public void tearDown() throws Exception {
        http.close();
        server.shutdown();
    }

    @Test
    public void blockingResponseKeepsReasoningSeparate() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"choices\":[{\"message\":{\"content\":\"answer\","
                        + "\"reasoning_content\":\"think\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"));

        LlmCompletion result = transport.complete(request(false, Collections.<LlmTool>emptyList(), null));

        assertThat(result.getContent()).isEqualTo("answer");
        assertThat(result.getThinkingText()).isEqualTo("think");
        assertThat(result.getStop()).isEqualTo(LlmStopReason.STOP);
        assertThat(result.getUsage().getTotalTokens()).isEqualTo(15);
    }

    @Test
    public void streamingEmitsReasoningThenContentAndParsesToolCalls() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_1\",\"function\":{\"name\":\"now\",\"arguments\":\"{}\"}}]}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n"
                        + "data: [DONE]\n\n"));

        final StringBuilder deltas = new StringBuilder();
        LlmCompletion result = transport.complete(request(true, Collections.<LlmTool>emptyList(), deltas));

        assertThat(deltas.toString()).isEqualTo("think\n\nhi");
        assertThat(result.getContent()).isEqualTo("hi");
        assertThat(result.getThinkingText()).isEqualTo("think");
        assertThat(result.getStop()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(result.getToolCalls().get(0).getName()).isEqualTo("now");
        assertThat(result.getUsage().getTotalTokens()).isEqualTo(5);
    }

    @Test
    public void requestBodyUsesChatTemplateThinkingAndTools() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{}}"));

        List<LlmTool> tools = Collections.singletonList(tool());
        transport.complete(request(false, tools, null, "chat-template", LlmThinkingLevel.HIGH));

        RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"model\":\"test-model\"");
        assertThat(body).contains("\"chat_template_kwargs\":{\"enable_thinking\":true}");
        assertThat(body).contains("\"tools\"");
        assertThat(body).contains("\"name\":\"echo\"");
        assertThat(body).contains("\"max_tokens\":4096");
    }

    @Test
    public void deepSeekThinkingFormatUsesThinkingObject() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{}}"));

        transport.complete(request(false, Collections.<LlmTool>emptyList(), null,
                "deepseek", LlmThinkingLevel.HIGH));

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"thinking\":{\"type\":\"enabled\"}");
        assertThat(body).contains("\"reasoning_effort\":\"high\"");
    }

    @Test
    public void assistantThinkingIsSentAsSeparateReasoningContent() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{}}"));

        String base = server.url("/v1").toString();
        io.github.yinwenyuan28.ailite.llm.ResolvedModel model = TestModels.model(
                OpenAiCompletionsTransport.API, base, "deepseek", LlmThinkingLevel.OFF, true);
        LlmRequest request = new LlmRequest(model,
                Arrays.asList(LlmMessage.system("rules"), LlmMessage.user("hi"),
                        LlmMessage.assistant("answer", "think", null)),
                Collections.<LlmTool>emptyList(), false, null, null, null, null, null);

        transport.complete(request);

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"content\":\"answer\"");
        assertThat(body).contains("\"reasoning_content\":\"think\"");
    }

    private LlmRequest request(boolean stream, List<LlmTool> tools,
                               StringBuilder deltas) {
        return request(stream, tools, deltas, "none", LlmThinkingLevel.OFF);
    }

    private LlmRequest request(boolean stream, List<LlmTool> tools, final StringBuilder deltas,
                               String thinkingFormat, LlmThinkingLevel level) {
        String base = server.url("/v1").toString();
        io.github.yinwenyuan28.ailite.llm.ResolvedModel model = TestModels.model(
                OpenAiCompletionsTransport.API, base, thinkingFormat, level, true);
        return new LlmRequest(model,
                Arrays.asList(LlmMessage.system("rules"), LlmMessage.user("hello")),
                tools, stream,
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
