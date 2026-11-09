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

/**
 * 流式响应的两条路径：真 SSE 与"假流式"（声称 stream 却回整段 JSON）。
 *
 * <p>为什么单独一个类：{@code OpenAiCompletionsTransport} 里有一个
 * {@code raw} 累积器，把收到的每一行原文都抄一份，只在
 * {@code !state.sawData} 时才用它——也就是"这个响应根本没走 SSE"的情况。
 * 正常流式下它完全是白占内存（一个 4MB 的回复会多留 8MB 的 char 数组）。</p>
 *
 * <p>本类的目标有两个：
 * <ol>
 *   <li><b>先锁住假流式的兼容行为</b>——改动 {@code raw} 之前必须确认这条路径
 *       还能正常工作，它是唯一需要 {@code raw} 的场景；</li>
 *   <li>确认正常流式下不会因为 {@code raw} 的改动而损坏解析结果。</li>
 * </ol>
 *
 * <p>注意：{@code raw} 是方法内局部变量，测试无法直接断言它的大小。
 * 因此这里从<b>行为</b>入手：假流式必须能解析出内容，真流式必须按 SSE 解析。
 * 一旦引入惰性累积，这两条行为断言就是保护网——任何一条变红都说明判定逻辑写错了。</p>
 */
public class OpenAiCompletionsStreamFallbackTest {

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

    // ------------------------------------------------- 假流式：必须继续可用

    /**
     * 声称 stream、实际返回整段 JSON：应回退成一次性解析。
     *
     * <p>这是 {@code raw} 累积器存在的唯一理由。任何对 {@code raw} 的改动
     * 只要让这条测试变红，就必须回退。</p>
     */
    @Test
    public void claimedStreamButReturnedPlainJsonStillParses() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"plain\"},"
                        + "\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,"
                        + "\"total_tokens\":3}}"));

        LlmCompletion result = transport.complete(request(true, null));

        assertThat(result.getContent()).isEqualTo("plain");
        assertThat(result.getStop()).isEqualTo(LlmStopReason.STOP);
        assertThat(result.getUsage().getTotalTokens()).isEqualTo(3);
    }

    /**
     * 假流式且带 reasoning：两个字段都要解析出来。
     */
    @Test
    public void plainJsonFallbackKeepsReasoningSeparate() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"answer\","
                        + "\"reasoning_content\":\"think\"},\"finish_reason\":\"stop\"}]}"));

        LlmCompletion result = transport.complete(request(true, null));

        assertThat(result.getContent()).isEqualTo("answer");
        assertThat(result.getThinkingText()).isEqualTo("think");
    }

    /**
     * 假流式且带 tool_calls：工具调用要能解析出来。
     */
    @Test
    public void plainJsonFallbackKeepsToolCalls() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":null,"
                        + "\"tool_calls\":[{\"id\":\"c1\",\"function\":"
                        + "{\"name\":\"now\",\"arguments\":\"{}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}"));

        LlmCompletion result = transport.complete(request(true, null));

        assertThat(result.getStop()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(result.getToolCalls().get(0).getName()).isEqualTo("now");
    }

    // --------------------------------------------- 真流式：必须按 SSE 解析

    /**
     * 正常 SSE：多事件拼成完整正文。
     */
    @Test
    public void realSseConcatenatesAllDeltas() {
        server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"c\"}}]}\n\n"
                + "data: [DONE]\n\n"));

        LlmCompletion result = transport.complete(request(true, null));

        assertThat(result.getContent()).isEqualTo("abc");
    }

    /**
     * 真流式下正文必须来自 SSE 增量，而不是被 {@code raw} 的整体回退覆盖。
     *
     * <p>如果惰性累积的判定写错（例如把真 SSE 误判成假流式），
     * {@code parseBlocking} 会去解析一段带 {@code data:} 前缀的文本，
     * 结果是抛异常。这条用例就是那个错误的第一道防线。</p>
     */
    @Test
    public void realSseIsNotTreatedAsPlainJson() {
        server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"
                + "data: [DONE]\n\n"));

        LlmCompletion result = transport.complete(request(true, null));

        assertThat(result.getContent()).isEqualTo("x");
    }

    /**
     * 大响应：真流式下逐事件累积，内容完整且不重复。
     *
     * <p>把响应放大到 200 条事件，确认拼接结果的长度正确——
     * 惰性累积若漏掉第一行，这里会少一截。</p>
     */
    @Test
    public void largeSseAccumulatesEveryEvent() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append("data: {\"choices\":[{\"delta\":{\"content\":\"")
                    .append(i % 10)
                    .append("\"}}]}\n\n");
        }
        body.append("data: [DONE]\n\n");
        server.enqueue(sse(body.toString()));

        LlmCompletion result = transport.complete(request(true, null));

        assertThat(result.getContent()).hasSize(200);
        assertThat(result.getContent()).startsWith("0123456789");
    }

    /**
     * 首行为空行的 SSE：判定必须能跨过空行，仍按真流式处理。
     *
     * <p>这条针对惰性累积的判定位置：不能"看第一行"就下结论，
     * 因为第一行可能是空行。判定要基于第一个<b>有内容</b>的行。</p>
     */
    @Test
    public void leadingBlankLineDoesNotBreakSseDetection() {
        server.enqueue(sse("\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                + "data: [DONE]\n\n"));

        LlmCompletion result = transport.complete(request(true, null));

        assertThat(result.getContent()).isEqualTo("ok");
    }

    /**
     * SSE 事件之间带注释行（以冒号开头）：应被忽略而不是当成数据。
     */
    @Test
    public void sseCommentLineIsIgnored() {
        server.enqueue(sse(": keep-alive\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                + "data: [DONE]\n\n"));

        LlmCompletion result = transport.complete(request(true, null));

        assertThat(result.getContent()).isEqualTo("ok");
    }

    /**
     * 流式响应为空（服务端什么都不发）：应得到空内容而不是异常。
     */
    @Test
    public void emptyStreamYieldsEmptyCompletion() {
        server.enqueue(sse(""));

        LlmCompletion result = transport.complete(request(true, null));

        assertThat(result.getContent()).isEmpty();
    }

    // -------------------------------------------------------------- helpers

    private MockResponse sse(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body);
    }

    private LlmRequest request(boolean stream, Consumer<String> onDelta) {
        ResolvedModel model = TestModels.model(OpenAiCompletionsTransport.API,
                server.url("/v1").toString(), "none", LlmThinkingLevel.OFF, true);
        return new LlmRequest(model,
                Arrays.asList(LlmMessage.system("rules"), LlmMessage.user("hello")),
                Collections.<LlmTool>emptyList(), stream, onDelta,
                (LlmCancelHandle) null, null, null, null);
    }
}
