package io.github.yinwenyuan28.ailite.transport;

import io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor;
import io.github.yinwenyuan28.ailite.json.AiLiteJson;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Anthropic Messages 适配器的对抗性用例。
 *
 * <p>重点：缺失 usage、未知 delta、tool_use 与 partial_json 混用、错误事件和乱序索引。</p>
 */
public class AnthropicMessagesAdversarialTest {

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
    public void messageStartWithoutUsageKeepsZeroInput() {
        enqueueSse("data: {\"type\":\"message_start\",\"message\":{}}\n\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":5}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n");

        LlmCompletion result = transport.complete(request(null));

        assertThat(result.getUsage().getInputTokens()).isZero();
        assertThat(result.getUsage().getOutputTokens()).isEqualTo(5);
        assertThat(result.getUsage().getTotalTokens()).isEqualTo(5);
    }

    @Test
    public void messageDeltaWithoutUsageKeepsUsageFromMessageStart() {
        enqueueSse("data: {\"type\":\"message_start\",\"message\":{\"usage\":"
                + "{\"input_tokens\":11,\"output_tokens\":2}}}\n\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n");

        LlmCompletion result = transport.complete(request(null));

        assertThat(result.getUsage().getInputTokens()).isEqualTo(11);
        assertThat(result.getUsage().getOutputTokens()).isEqualTo(2);
    }

    @Test
    public void messageDeltaWithOnlyOutputKeepsInputFromStart() {
        enqueueSse("data: {\"type\":\"message_start\",\"message\":{\"usage\":"
                + "{\"input_tokens\":11,\"output_tokens\":0}}}\n\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},"
                + "\"usage\":{\"output_tokens\":7}}\n\n");

        LlmCompletion result = transport.complete(request(null));

        assertThat(result.getUsage().getInputTokens()).isEqualTo(11);
        assertThat(result.getUsage().getOutputTokens()).isEqualTo(7);
        assertThat(result.getStop()).isEqualTo(LlmStopReason.LENGTH);
    }

    @Test
    public void unknownContentBlockDeltaTypeIsIgnored() {
        enqueueSse("data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"citations_delta\",\"citation\":\"?\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n");

        assertThat(transport.complete(request(null)).getContent()).isEqualTo("ok");
    }

    @Test
    public void errorEventBecomesLlmCallException() {
        enqueueSse("data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\","
                + "\"message\":\"overloaded\"}}\n\n");

        assertThatThrownBy(() -> transport.complete(request(null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型请求失败")
                .hasMessageContaining("overloaded_error");
    }

    @Test
    public void invalidJsonLineIsIgnoredAndFollowingTextSurvives() {
        enqueueSse("data: {broken\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n");

        assertThat(transport.complete(request(null)).getContent()).isEqualTo("ok");
    }

    @Test
    public void doneMarkerIsIgnoredAndMessageStopEndsCleanly() {
        enqueueSse("data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                + "data: [DONE]\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n");

        LlmCompletion result = transport.complete(request(null));

        assertThat(result.getContent()).isEqualTo("ok");
        assertThat(result.getStop()).isEqualTo(LlmStopReason.STOP);
    }

    @Test
    public void thinkingThenTextEmitsExactlyOneSeparator() {
        enqueueSse("data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"think\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"a\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":1,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"b\"}}\n\n");
        final StringBuilder deltas = new StringBuilder();

        LlmCompletion result = transport.complete(request(new Consumer<String>() {
            @Override
            public void accept(String delta) {
                deltas.append(delta);
            }
        }));

        assertThat(deltas.toString()).isEqualTo("think\n\nab");
        assertThat(result.getContent()).isEqualTo("think\n\nab");
    }

    @Test
    public void toolUseIndexGapsAreSortedByIndex() {
        enqueueSse("data: {\"type\":\"content_block_start\",\"index\":4,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t4\",\"name\":\"second\","
                + "\"input\":{}}}\n\n"
                + "data: {\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"first\","
                + "\"input\":{}}}\n\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n");

        LlmCompletion result = transport.complete(request(null));

        assertThat(result.getToolCalls()).hasSize(2);
        assertThat(result.getToolCalls().get(0).getName()).isEqualTo("first");
        assertThat(result.getToolCalls().get(1).getName()).isEqualTo("second");
        assertThat(result.getStop()).isEqualTo(LlmStopReason.TOOL_USE);
    }

    @Test
    public void inputJsonDeltaBeforeBlockStartIsIgnored() {
        enqueueSse("data: {\"type\":\"content_block_delta\",\"index\":9,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n");

        assertThat(transport.complete(request(null)).getToolCalls()).isEmpty();
    }

    @Test
    public void toolUseWithoutIdKeepsNullId() {
        enqueueSse("data: {\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"name\":\"echo\",\"input\":{}}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n");

        LlmCompletion result = transport.complete(request(null));

        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(result.getToolCalls().get(0).getId()).isNull();
    }

    @Test
    public void unicodeIsPreserved() {
        enqueueSse("data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"你好😀\"}}\n\n");

        assertThat(transport.complete(request(null)).getContent()).isEqualTo("你好😀");
    }

    @Test
    public void rateLimitResponseIsReported() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("slow down"));

        assertThatThrownBy(() -> transport.complete(request(null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("状态码：429")
                .hasMessageContaining("slow down");
    }

    @Test
    public void nonEmptyToolInputAndPartialJsonShouldProduceValidJson() {
        enqueueSse("data: {\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"echo\","
                + "\"input\":{\"a\":1}}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\",\\\"b\\\":2\"}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n");

        LlmCompletion result = transport.complete(request(null));

        // 期望：合并为 {"a":1,"b":2}。实际：{"a":1},"b":2，无法解析。
        Map<String, Object> arguments =
                AiLiteJson.readMap(result.getToolCalls().get(0).getArgumentsJson());
        assertThat(arguments).containsEntry("a", Integer.valueOf(1))
                .containsEntry("b", Integer.valueOf(2));
    }

    private void enqueueSse(String body) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body));
    }

    private LlmRequest request(Consumer<String> onDelta) {
        ResolvedModel model = TestModels.model(AnthropicMessagesTransport.API,
                server.url("/").toString(), "anthropic-budget", LlmThinkingLevel.HIGH, true);
        return new LlmRequest(model,
                Arrays.asList(LlmMessage.system("rules"), LlmMessage.user("hello")),
                Collections.<LlmTool>emptyList(), true, onDelta, null, null, null, null);
    }
}
