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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI Responses 适配器的流事件与请求体。
 */
public class OpenAiResponsesTransportTest {

    private MockWebServer server;
    private OkHttpExecutor http;
    private OpenAiResponsesTransport transport;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        http = new OkHttpExecutor();
        transport = new OpenAiResponsesTransport(http);
    }

    @After
    public void tearDown() throws Exception {
        http.close();
        server.shutdown();
    }

    @Test
    public void parsesTextReasoningToolCallsAndUsage() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"think\"}\n\n"
                        + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n"
                        + "data: {\"type\":\"response.output_item.added\",\"output_index\":1,"
                        + "\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\","
                        + "\"name\":\"now\",\"arguments\":\"\"}}\n\n"
                        + "data: {\"type\":\"response.function_call_arguments.delta\","
                        + "\"output_index\":1,\"delta\":\"{}\"}\n\n"
                        + "data: {\"type\":\"response.function_call_arguments.done\","
                        + "\"output_index\":1,\"arguments\":\"{}\"}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":"
                        + "{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n"));

        final StringBuilder deltas = new StringBuilder();
        LlmCompletion result = transport.complete(request(deltas));

        assertThat(deltas.toString()).isEqualTo("think\n\nhello");
        assertThat(result.getContent()).isEqualTo("think\n\nhello");
        assertThat(result.getThinkingText()).isEqualTo("think");
        assertThat(result.getStop()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(result.getToolCalls().get(0).getId()).isEqualTo("call_1");
        assertThat(result.getUsage().getTotalTokens()).isEqualTo(15);
    }

    @Test
    public void requestBodyUsesInstructionsAndReasoning() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\",\"response\":{}}\n\n"));

        List<LlmTool> tools = Collections.singletonList(tool());
        transport.complete(request(null, tools));

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"instructions\":\"rules\"");
        assertThat(body).contains("\"max_output_tokens\":4096");
        assertThat(body).contains("\"reasoning\":{\"effort\":\"high\",\"summary\":\"auto\"}");
        assertThat(body).contains("\"tools\"");
        assertThat(body).contains("\"name\":\"echo\"");
    }

    private LlmRequest request(StringBuilder deltas) {
        return request(deltas, Collections.<LlmTool>emptyList());
    }

    private LlmRequest request(final StringBuilder deltas, List<LlmTool> tools) {
        String base = server.url("/v1").toString();
        io.github.yinwenyuan28.ailite.llm.ResolvedModel model = TestModels.model(
                OpenAiResponsesTransport.API, base, "reasoning-effort", LlmThinkingLevel.HIGH, true);
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
