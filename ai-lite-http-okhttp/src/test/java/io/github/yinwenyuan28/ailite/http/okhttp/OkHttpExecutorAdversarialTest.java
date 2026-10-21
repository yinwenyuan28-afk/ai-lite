package io.github.yinwenyuan28.ailite.http.okhttp;

import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OkHttpExecutor 的对抗性用例：非 2xx、超长/HTML/空错误体、超时、断连和取消。
 */
public class OkHttpExecutorAdversarialTest {

    private MockWebServer server;
    private OkHttpExecutor http;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        http = new OkHttpExecutor();
    }

    @After
    public void tearDown() throws Exception {
        http.close();
        server.shutdown();
    }

    @Test
    public void blockingSuccessReturnsStatusAndBody() {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("payload"));

        HttpResult result = http.execute(request(5000, null));

        assertThat(result.getStatus()).isEqualTo(201);
        assertThat(result.getBody()).isEqualTo("payload");
    }

    @Test
    public void emptySuccessfulBodyIsReturnedAsEmptyString() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(""));

        assertThat(http.execute(request(5000, null)).getBody()).isEmpty();
    }

    @Test
    public void badRequest400IsReported() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));

        assertThatThrownBy(() -> http.execute(request(5000, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("状态码：400")
                .hasMessageContaining("bad request");
    }

    @Test
    public void non2xxHugeBodyIsTruncatedInErrorMessage() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            body.append(i % 80 == 0 ? '\n' : 'x');
        }
        server.enqueue(new MockResponse().setResponseCode(500).setBody(body.toString()));

        assertThatThrownBy(() -> http.execute(request(5000, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("状态码：500")
                .satisfies(ex -> {
                    String message = ex.getMessage();
                    // 状态码 + 说明 + 300 字响应摘要，不应把 5000 字全部带出来。
                    assertThat(message.length()).isLessThan(400);
                    assertThat(message).doesNotContain("\n");
                });
    }

    @Test
    public void non2xxHtmlBodyDoesNotCrashAndKeepsStatus() {
        server.enqueue(new MockResponse().setResponseCode(502)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>Bad Gateway</body></html>"));

        assertThatThrownBy(() -> http.execute(request(5000, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("状态码：502")
                .hasMessageContaining("<html>");
    }

    @Test
    public void non2xxEmptyBodyStillReportsStatus() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody(""));

        assertThatThrownBy(() -> http.execute(request(5000, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessage("模型请求失败，状态码：503");
    }

    @Test
    public void streamingNon2xxIsRejectedBeforeAnyLineCallback() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("rate limited"));

        final List<String> lines = new ArrayList<String>();
        assertThatThrownBy(() -> http.executeStreaming(request(5000, null), collecting(lines)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("状态码：429")
                .hasMessageContaining("rate limited");
        assertThat(lines).isEmpty();
    }

    @Test
    public void bodyDelayTriggersReadTimeoutInsteadOfHanging() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("late")
                .setBodyDelay(2, TimeUnit.SECONDS));

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> http.execute(request(150, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型请求失败");
        assertThat(System.currentTimeMillis() - started).isLessThan(5000L);
    }

    @Test
    public void disconnectAtStartBecomesLlmCallException() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        assertThatThrownBy(() -> http.execute(request(2000, null)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型请求失败");
    }

    @Test
    public void disconnectDuringResponseBodyBecomesLlmCallException() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            body.append("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n");
        }
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody(body.toString())
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

        final List<String> lines = new ArrayList<String>();
        assertThatThrownBy(() -> http.executeStreaming(request(2000, null), collecting(lines)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("模型请求失败");
    }

    @Test
    public void cancelBeforeExecuteFailsFastWithCancelMessage() {
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();
        server.enqueue(new MockResponse().setResponseCode(200).setBody("never"));

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> http.execute(request(5000, cancel)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("调用已取消");
        assertThat(System.currentTimeMillis() - started).isLessThan(5000L);
    }

    @Test
    public void cancelFromStreamingCallbackStopsFurtherLines() {
        final LlmCancelHandle cancel = new LlmCancelHandle();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: one\n\n"
                        + "data: two\n\n"
                        + "data: three\n\n"));

        final List<String> lines = new ArrayList<String>();
        assertThatThrownBy(() -> http.executeStreaming(request(5000, cancel), new StreamListener() {
            @Override
            public void onLine(String line) throws IOException {
                lines.add(line);
                cancel.cancel();
            }
        })).isInstanceOf(LlmCallException.class)
                .hasMessageContaining("调用已取消");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo("data: one");
    }

    @Test
    public void unicodePayloadSurvivesOneByteChunks() throws Exception {
        String body = "data: 你好😀\n\n"
                + "data: surrogate-\uD83D\uDE00-end\n\n";
        server.enqueue(new MockResponse().setResponseCode(200)
                .setChunkedBody(body, 1));

        final List<String> lines = new ArrayList<String>();
        http.executeStreaming(request(5000, null), collecting(lines));

        assertThat(lines).containsExactly("data: 你好😀", "", "data: surrogate-\uD83D\uDE00-end", "");
    }

    @Test
    public void asyncSuccessReturnsStatusAndBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("payload"));

        HttpResult result = http.executeAsync(request(5000, null)).get(5, TimeUnit.SECONDS);

        assertThat(result.getStatus()).isEqualTo(201);
        assertThat(result.getBody()).isEqualTo("payload");
    }

    @Test
    public void asyncNon2xxCompletesExceptionally() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("rate limited"));

        assertThatThrownBy(() -> http.executeAsync(request(5000, null)).get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("429");
    }

    @Test
    public void asyncStreamingDeliversLines() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: a\n\ndata: b\n\n"));
        final List<String> lines = new ArrayList<String>();

        http.executeStreamingAsync(request(5000, null), collecting(lines)).get(5, TimeUnit.SECONDS);

        assertThat(lines).contains("data: a", "data: b");
    }

    @Test
    public void asyncCancelHandleStopsStreaming() {
        final LlmCancelHandle cancel = new LlmCancelHandle();
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: one\n\ndata: two\n\n"));
        final List<String> lines = new ArrayList<String>();

        assertThatThrownBy(() -> http.executeStreamingAsync(request(5000, cancel),
                new StreamListener() {
                    @Override
                    public void onLine(String line) {
                        lines.add(line);
                        cancel.cancel();
                    }
                }).get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("调用已取消");
        assertThat(lines).hasSize(1);
    }

    @Test
    public void cancellingFutureMarksItCancelled() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("late")
                .setBodyDelay(2, TimeUnit.SECONDS));

        java.util.concurrent.CompletableFuture<HttpResult> future =
                http.executeAsync(request(5000, null));
        future.cancel(true);

        assertThat(future.isCancelled()).isTrue();
    }

    private HttpRequest request(int readTimeoutMs, LlmCancelHandle cancel) {
        return new HttpRequest(server.url("/chat").toString(), Collections.<String, String>emptyMap(),
                "{\"hello\":1}", 2000, readTimeoutMs, cancel);
    }

    private static StreamListener collecting(final List<String> lines) {
        return new StreamListener() {
            @Override
            public void onLine(String line) {
                lines.add(line);
            }
        };
    }
}
