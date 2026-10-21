package io.github.yinwenyuan28.ailite.transport;

import io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI Responses 适配器的对抗性用例。
 *
 * <p>重点：失败/错误事件、incomplete、缺少 completed、工具参数增量异常和索引乱序。</p>
 */
public class OpenAiResponsesAdversarialTest {

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
    public void responseFailedEventBecomesLlmCallException() {
        enqueueSse("data: {\"type\":\"response.failed\",\"response\":{\"error\":"
                + "{\"code\":\"server_error\",\"message\":\"boom\"}}}\n\n");

        assertThatThrownBy(() -> transport.complete(request(null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型请求失败")
                .hasMessageContaining("server_error");
    }

    @Test
    public void errorEventBecomesLlmCallException() {
        enqueueSse("data: {\"type\":\"error\",\"code\":\"invalid_request\","
                + "\"message\":\"bad input\"}\n\n");

        assertThatThrownBy(() -> transport.complete(request(null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型请求失败")
                .hasMessageContaining("invalid_request");
    }

    @Test
    public void incompleteForMaxOutputTokensMapsToLength() {
        enqueueSse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
                + "data: {\"type\":\"response.incomplete\",\"response\":{\"usage\":"
                + "{\"input_tokens\":4,\"output_tokens\":2,\"total_tokens\":6},"
                + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n");

        LlmCompletion result = transport.complete(request(null));

        assertThat(result.getContent()).isEqualTo("partial");
        assertThat(result.getStop()).isEqualTo(LlmStopReason.LENGTH);
        assertThat(result.getUsage().getTotalTokens()).isEqualTo(6);
    }

    @Test
    public void incompleteForOtherReasonMapsToStop() {
        enqueueSse("data: {\"type\":\"response.incomplete\",\"response\":{"
                + "\"incomplete_details\":{\"reason\":\"content_filter\"}}}\n\n");

        assertThat(transport.complete(request(null)).getStop()).isEqualTo(LlmStopReason.STOP);
    }

    @Test
    public void missingCompletedEventStillReturnsWhatWasReceived() {
        enqueueSse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"half\"}\n\n");

        LlmCompletion result = transport.complete(request(null));

        assertThat(result.getContent()).isEqualTo("half");
        assertThat(result.getStop()).isEqualTo(LlmStopReason.STOP);
        assertThat(result.getUsage().getTotalTokens()).isZero();
    }

    @Test
    public void completedWithoutUsageKeepsZeroUsage() {
        enqueueSse("data: {\"type\":\"response.completed\",\"response\":{}}\n\n");

        LlmCompletion result = transport.complete(request(null));

        assertThat(result.getStop()).isEqualTo(LlmStopReason.STOP);
        assertThat(result.getUsage().getTotalTokens()).isZero();
    }

    @Test
    public void invalidJsonIsSkippedButCompletedStillParses() {
        enqueueSse("data: {not json\n\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n");

        assertThat(transport.complete(request(null)).getContent()).isEqualTo("ok");
    }

    @Test
    public void toolCallFallsBackToItemIdWhenCallIdIsMissing() {
        enqueueSse("data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"type\":\"function_call\",\"id\":\"item_1\",\"name\":\"echo\","
                + "\"arguments\":\"{}\"}}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n");

        LlmCompletion result = transport.complete(request(null));

        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(result.getToolCalls().get(0).getId()).isEqualTo("item_1");
        assertThat(result.getStop()).isEqualTo(LlmStopReason.TOOL_USE);
    }

    @Test
    public void outputIndexGapsAreSortedAndArgumentsDoneOverridesDeltas() {
        enqueueSse("data: {\"type\":\"response.output_item.added\",\"output_index\":5,"
                + "\"item\":{\"type\":\"function_call\",\"call_id\":\"c5\",\"name\":\"second\","
                + "\"arguments\":\"\"}}\n\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":5,"
                + "\"delta\":\"{\\\"x\\\":\"}\n\n"
                + "data: {\"type\":\"response.function_call_arguments.done\",\"output_index\":5,"
                + "\"arguments\":\"{\\\"x\\\":1}\"}\n\n"
                + "data: {\"type\":\"response.output_item.added\",\"output_index\":1,"
                + "\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"first\","
                + "\"arguments\":\"{}\"}}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n");

        LlmCompletion result = transport.complete(request(null));

        assertThat(result.getToolCalls()).hasSize(2);
        assertThat(result.getToolCalls().get(0).getName()).isEqualTo("first");
        assertThat(result.getToolCalls().get(1).getName()).isEqualTo("second");
        assertThat(result.getToolCalls().get(1).getArgumentsJson()).isEqualTo("{\"x\":1}");
    }

    @Test
    public void outputItemDoneReconstructsMessageWhenNoTextDeltaArrived() {
        enqueueSse("data: {\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"final\"}]}}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n");

        assertThat(transport.complete(request(null)).getContent()).isEqualTo("final");
    }

    @Test
    public void reasoningThenTextEmitsExactlyOneSeparator() {
        enqueueSse("data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"think\"}\n\n"
                + "data: {\"type\":\"response.reasoning_summary_part.done\"}\n\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"a\"}\n\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"b\"}\n\n");
        final StringBuilder deltas = new StringBuilder();

        LlmCompletion result = transport.complete(request(new Consumer<String>() {
            @Override
            public void accept(String delta) {
                deltas.append(delta);
            }
        }));

        assertThat(deltas.toString()).isEqualTo("think\n\n" + "ab");
        assertThat(result.getContent()).isEqualTo("ab");
        assertThat(result.getThinkingText()).isEqualTo("think");
    }

    @Test
    public void argumentDeltaWithoutDeltaFieldShouldNotProduceNullText() {
        enqueueSse("data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"echo\","
                + "\"arguments\":\"\"}}\n\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n");

        LlmCompletion result = transport.complete(request(null));

        // 期望：空 delta 不改变参数，最终为 "{}"。实际：argumentsJson 变成 "null"。
        assertThat(result.getToolCalls().get(0).getArgumentsJson()).isEqualTo("{}");
    }

    @Test
    public void serverErrorStatusIsReported() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("upstream exploded"));

        assertThatThrownBy(() -> transport.complete(request(null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("状态码：500")
                .hasMessageContaining("upstream exploded");
    }

    @Test
    public void unicodeDeltasArePreserved() {
        enqueueSse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好😀\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n");

        assertThat(transport.complete(request(null)).getContent()).isEqualTo("你好😀");
    }

    private void enqueueSse(String body) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body));
    }

    private LlmRequest request(Consumer<String> onDelta) {
        ResolvedModel model = TestModels.model(OpenAiResponsesTransport.API,
                server.url("/v1").toString(), "reasoning-effort", LlmThinkingLevel.HIGH, true);
        return new LlmRequest(model,
                Arrays.asList(LlmMessage.system("rules"), LlmMessage.user("hello")),
                Collections.<LlmTool>emptyList(), true, onDelta, null, null, null, null);
    }
}
