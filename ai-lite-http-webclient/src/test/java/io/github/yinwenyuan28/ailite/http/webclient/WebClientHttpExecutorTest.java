package io.github.yinwenyuan28.ailite.http.webclient;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.fail;

/**
 * WebClient 传输：同步/异步/流式/取消/错误分类/生命周期。
 */
public class WebClientHttpExecutorTest {

    private MockWebServer server;
    private WebClientHttpExecutor executor;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        executor = new WebClientHttpExecutor(new AiLiteConfig.Http());
    }

    @After
    public void tearDown() throws Exception {
        executor.close();
        server.shutdown();
    }

    @Test
    public void executePostsJsonAndReturnsBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        HttpResult result = executor.execute(request("/v1/chat", null));

        assertThat(result.getStatus()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo("{\"ok\":true}");
        RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("Content-Type")).contains("application/json");
        assertThat(recorded.getBody().readUtf8()).isEqualTo("{\"x\":1}");
    }

    @Test
    public void executeNon2xxThrowsHttpWithStatus() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("busy"));

        assertThatThrownBy(() -> executor.execute(request("/v1/chat", null)))
                .isInstanceOfSatisfying(LlmCallException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.HTTP);
                    assertThat(ex.getStatusCode()).isEqualTo(429);
                    assertThat(ex.getMessage()).contains("busy");
                });
    }

    @Test
    public void executeStreamingReturnsRawLines() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: one\r\n\r\ndata: 你好\r\n\r\n", 1));
        final List<String> lines = new ArrayList<String>();

        executor.executeStreaming(request("/v1/chat", null), lines::add);

        assertThat(lines).containsExactly("data: one", "", "data: 你好", "");
    }

    @Test
    public void executeAsyncCompletes() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        HttpResult result = executor.executeAsync(request("/v1/chat", null))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getStatus()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo("ok");
    }

    @Test
    public void executeStreamingAsyncReturnsRawLines() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: one\r\n\r\ndata: 你好\r\n\r\n", 1));
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

        executor.executeStreamingAsync(request("/v1/chat", null), lines::add)
                .get(5, TimeUnit.SECONDS);

        assertThat(lines).containsExactly("data: one", "", "data: 你好", "");
    }

    @Test
    public void streamingFlushesTailWithoutNewline() throws Exception {
        server.enqueue(new MockResponse().setBody("last"));
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

        executor.executeStreamingAsync(request("/v1/chat", null), lines::add)
                .get(5, TimeUnit.SECONDS);

        assertThat(lines).containsExactly("last");
    }

    @Test
    public void streamingNon2xxDoesNotFeedLines() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

        CompletableFuture<Void> future = executor.executeStreamingAsync(
                request("/v1/chat", null), lines::add);
        LlmCallException error = awaitFailure(future);

        assertThat(error.getKind()).isEqualTo(LlmCallException.Kind.HTTP);
        assertThat(error.getStatusCode()).isEqualTo(500);
        assertThat(error.getMessage()).contains("boom");
        assertThat(lines).isEmpty();
    }

    @Test
    public void preCancelledRequestNeverReachesServer() {
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();

        LlmCallException error = awaitFailure(
                executor.executeAsync(request("/v1/chat", cancel)));

        assertThat(error.getKind()).isEqualTo(LlmCallException.Kind.CANCELLED);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    public void cancelDuringStreamingAbortsAndCompletesCancelled() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append("data: chunk-").append(i).append('\n');
        }
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body.toString(), 1)
                .throttleBody(1, 5, TimeUnit.MILLISECONDS));
        final LlmCancelHandle cancel = new LlmCancelHandle();
        final CountDownLatch firstLine = new CountDownLatch(1);

        CompletableFuture<Void> future = executor.executeStreamingAsync(
                request("/v1/chat", cancel), line -> firstLine.countDown());

        assertThat(firstLine.await(10, TimeUnit.SECONDS)).isTrue();
        cancel.cancel();
        LlmCallException error = awaitFailure(future);

        assertThat(error.getKind()).isEqualTo(LlmCallException.Kind.CANCELLED);
    }

    @Test
    public void earlyEofIsTransportFailure() {
        server.enqueue(new MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                .setBody("data: partial\n"));

        LlmCallException error = awaitFailure(
                executor.executeStreamingAsync(request("/v1/chat", null), line -> {
                }));

        assertThat(error.getKind()).isEqualTo(LlmCallException.Kind.TRANSPORT);
    }

    @Test
    public void closeIsIdempotentAndRejectsNewRequests() {
        executor.close();
        executor.close();

        assertThatThrownBy(() -> executor.execute(request("/v1/chat", null)))
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CONFIG));
    }

    private HttpRequest request(String path, LlmCancelHandle cancel) {
        return new HttpRequest(server.url(path).toString(),
                Collections.<String, String>emptyMap(), "{\"x\":1}",
                3000, 3000, cancel);
    }

    private static LlmCallException awaitFailure(CompletableFuture<?> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("expected failure");
            return null;
        } catch (ExecutionException ex) {
            assertThat(ex.getCause()).isInstanceOf(LlmCallException.class);
            return (LlmCallException) ex.getCause();
        } catch (Exception ex) {
            throw new AssertionError("unexpected exception", ex);
        }
    }
}
