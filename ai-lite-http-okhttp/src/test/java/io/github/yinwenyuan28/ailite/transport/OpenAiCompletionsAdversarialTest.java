package io.github.yinwenyuan28.ailite.transport;

import io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
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
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI Chat Completions 适配器的对抗性用例。
 *
 * <p>覆盖不完整 SSE、空 payload、非法 JSON、缺/重复 [DONE]、工具分片乱序、
 * 空 choices、非 2xx、Unicode 和取消。</p>
 */
public class OpenAiCompletionsAdversarialTest {

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

    // ------------------------------------------------------------ blocking

    @Test
    public void blockingEmptyObjectIsRejected() {
        enqueueBlocking("{}");

        assertThatThrownBy(() -> transport.complete(request(false, null, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("响应无法解析");
    }

    @Test
    public void blockingEmptyChoicesIsRejected() {
        enqueueBlocking("{\"choices\":[]}");

        assertThatThrownBy(() -> transport.complete(request(false, null, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("响应无法解析");
    }

    @Test
    public void blockingMissingMessageIsRejected() {
        enqueueBlocking("{\"choices\":[{\"finish_reason\":\"stop\"}]}");

        assertThatThrownBy(() -> transport.complete(request(false, null, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("响应无法解析");
    }

    @Test
    public void blockingNullContentBecomesEmptyString() {
        enqueueBlocking("{\"choices\":[{\"message\":{\"content\":null},"
                + "\"finish_reason\":\"stop\"}]}");

        LlmCompletion result = transport.complete(request(false, null, null));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getThinkingText()).isNull();
        assertThat(result.getStop()).isEqualTo(LlmStopReason.STOP);
    }

    @Test
    public void blockingPrefersReasoningContentWhenBothFieldsExist() {
        enqueueBlocking("{\"choices\":[{\"message\":{\"content\":\"answer\","
                + "\"reasoning_content\":\"primary\",\"reasoning\":\"secondary\"},"
                + "\"finish_reason\":\"stop\"}]}");

        LlmCompletion result = transport.complete(request(false, null, null));

        assertThat(result.getThinkingText()).isEqualTo("primary");
        assertThat(result.getContent()).isEqualTo("answer");
    }

    @Test
    public void blockingLengthFinishReasonIsMapped() {
        enqueueBlocking("{\"choices\":[{\"message\":{\"content\":\"cut\"},"
                + "\"finish_reason\":\"length\"}]}");

        assertThat(transport.complete(request(false, null, null)).getStop())
                .isEqualTo(LlmStopReason.LENGTH);
    }

    @Test
    public void blockingSuccessfullyHandlesUnicodeAndSurrogatePairs() {
        enqueueBlocking("{\"choices\":[{\"message\":{\"content\":\"你好😀 \\uD83D\\uDE00\"},"
                + "\"finish_reason\":\"stop\"}]}");

        assertThat(transport.complete(request(false, null, null)).getContent())
                .isEqualTo("你好😀 😀");
    }

    @Test
    public void blockingEmptyBodyShouldFailWithLlmCallException() {
        enqueueBlocking("");

        assertThatThrownBy(() -> transport.complete(request(false, null, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("响应无法解析");
    }

    // ------------------------------------------------------------ streaming

    @Test
    public void missingDoneMarkerStillReturnsAccumulatedContent() {
        enqueueSse("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n");

        LlmCompletion result = transport.complete(request(true, null, null));

        assertThat(result.getContent()).isEqualTo("hello");
        assertThat(result.getStop()).isEqualTo(LlmStopReason.STOP);
    }

    @Test
    public void duplicateDoneMarkersAreIgnored() {
        enqueueSse("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                + "data: [DONE]\n\n"
                + "data: [DONE]\n\n");

        LlmCompletion result = transport.complete(request(true, null, null));

        assertThat(result.getContent()).isEqualTo("hi");
    }

    @Test
    public void invalidJsonLinesAreSkippedAndLaterLinesStillParse() {
        enqueueSse("data: {this is not json\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                + "data: [DONE]\n\n");

        LlmCompletion result = transport.complete(request(true, null, null));

        assertThat(result.getContent()).isEqualTo("ok");
    }

    @Test
    public void emptyChoicesAndMissingDeltaAreIgnored() {
        enqueueSse("data: {\"choices\":[]}\n\n"
                + "data: {\"choices\":[{\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n");

        assertThat(transport.complete(request(true, null, null)).getContent()).isEqualTo("x");
    }

    @Test
    public void nonDataLinesAreIgnored() {
        enqueueSse("event: message\n\n"
                + "id: 1\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n");

        assertThat(transport.complete(request(true, null, null)).getContent()).isEqualTo("x");
    }

    @Test
    public void streamingPrefersReasoningContentWhenBothFieldsExist() {
        enqueueSse("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"r1\","
                + "\"reasoning\":\"r2\",\"content\":\"c\"}}]}\n\n");

        LlmCompletion result = transport.complete(request(true, null, null));

        assertThat(result.getThinkingText()).isEqualTo("r1");
        assertThat(result.getContent()).isEqualTo("c");
    }

    @Test
    public void toolCallFragmentsMergeByIndexAndKeepIdFromFirstFragment() {
        enqueueSse("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"a\\\":\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"1}\"}}]}}]}\n\n");

        LlmCompletion result = transport.complete(request(true, null, null));

        assertThat(result.getToolCalls()).hasSize(1);
        LlmToolCall call = result.getToolCalls().get(0);
        assertThat(call.getId()).isEqualTo("call_1");
        assertThat(call.getName()).isEqualTo("echo");
        assertThat(call.getArgumentsJson()).isEqualTo("{\"a\":1}");
        assertThat(result.getStop()).isEqualTo(LlmStopReason.TOOL_USE);
    }

    @Test
    public void toolCallIndexGapsAreSortedByIndex() {
        enqueueSse("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":2,\"id\":\"c2\","
                + "\"function\":{\"name\":\"second\",\"arguments\":\"{}\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c0\","
                + "\"function\":{\"name\":\"first\",\"arguments\":\"{}\"}}]}}]}\n\n");

        LlmCompletion result = transport.complete(request(true, null, null));

        assertThat(result.getToolCalls()).hasSize(2);
        assertThat(result.getToolCalls().get(0).getName()).isEqualTo("first");
        assertThat(result.getToolCalls().get(1).getName()).isEqualTo("second");
    }

    @Test
    public void objectArgumentsAreSerializedInsteadOfDropped() {
        enqueueSse("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                + "\"function\":{\"name\":\"echo\",\"arguments\":{\"x\":1}}}]}}]}\n\n");

        LlmCompletion result = transport.complete(request(true, null, null));

        assertThat(result.getToolCalls().get(0).getArgumentsJson()).isEqualTo("{\"x\":1}");
    }

    @Test
    public void finishReasonToolCallsWithoutFragmentsStillReportsToolUse() {
        enqueueSse("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n");

        LlmCompletion result = transport.complete(request(true, null, null));

        // 当前行为：状态是 toolUse 但调用列表为空；网关会直接把它当成最终结果。
        assertThat(result.getStop()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(result.getToolCalls()).isEmpty();
    }

    @Test
    public void streamingLengthFinishReasonIsMapped() {
        enqueueSse("data: {\"choices\":[{\"delta\":{\"content\":\"cut\"},\"finish_reason\":\"length\"}]}\n\n");

        assertThat(transport.complete(request(true, null, null)).getStop())
                .isEqualTo(LlmStopReason.LENGTH);
    }

    @Test
    public void streamingUnicodeSurvivesOneByteChunks() {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"你好😀\"}}]}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body, 1));

        LlmCompletion result = transport.complete(request(true, null, null));

        assertThat(result.getContent()).isEqualTo("你好😀");
    }

    @Test
    public void deltaCallbackReceivesReasoningSeparatorAndContentExactlyOnce() {
        enqueueSse("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\n\n");
        final StringBuilder deltas = new StringBuilder();

        transport.complete(request(true, new Consumer<String>() {
            @Override
            public void accept(String delta) {
                deltas.append(delta);
            }
        }, null));

        assertThat(deltas.toString()).isEqualTo("think\n\na" + "b");
    }

    @Test
    public void multilineSseDataShouldBeJoined() {
        // 按 SSE 规范，连续多个 data: 行应以换行拼接成一个事件；这里把 JSON 在两个 token 之间断开。
        enqueueSse("data: {\"choices\":[{\"delta\":\n"
                + "data: {\"content\":\"hello\"}}]}\n\n");

        assertThat(transport.complete(request(true, null, null)).getContent())
                .isEqualTo("hello");
    }

    @Test
    public void emptyDataPayloadShouldNotThrowNullPointer() {
        enqueueSse("data:\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n");

        // 期望：跳过空 payload。实际：NPE。
        assertThat(transport.complete(request(true, null, null)).getContent()).isEqualTo("ok");
    }

    // --------------------------------------------------------------- cancel

    @Test
    public void cancelBeforeStreamingRequestReturnsAborted() {
        final LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("never"));

        LlmCompletion result = transport.complete(request(true, null, cancel));

        assertThat(result.getStop()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    public void cancelFromFirstDeltaReturnsAbortedWithPartialContent() {
        final LlmCancelHandle cancel = new LlmCancelHandle();
        enqueueSse("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"c\"}}]}\n\n");

        LlmCompletion result = transport.complete(request(true, new Consumer<String>() {
            @Override
            public void accept(String delta) {
                cancel.cancel();
            }
        }, cancel));

        assertThat(result.getStop()).isEqualTo(LlmStopReason.ABORTED);
        assertThat(result.getContent()).isEqualTo("a");
    }

    // --------------------------------------------------------------- errors

    @Test
    public void unauthorizedResponseBecomesLlmCallException() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("bad key"));

        assertThatThrownBy(() -> transport.complete(request(false, null, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("状态码：401");
    }

    // -------------------------------------------------------------- helpers

    private void enqueueBlocking(String body) {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));
    }

    private void enqueueSse(String body) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body));
    }

    private LlmRequest request(boolean stream, Consumer<String> onDelta, LlmCancelHandle cancel) {
        ResolvedModel model = TestModels.model(OpenAiCompletionsTransport.API,
                server.url("/v1").toString(), "none", LlmThinkingLevel.OFF, true);
        return new LlmRequest(model,
                Arrays.asList(LlmMessage.system("rules"), LlmMessage.user("hello")),
                Collections.<LlmTool>emptyList(), stream, onDelta, cancel, null, null, null);
    }
}
