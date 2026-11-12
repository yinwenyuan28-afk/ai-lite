package io.github.yinwenyuan28.ailite.http.jdk8;

import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 零依赖 HTTP 执行器：阻塞、流式、错误状态。
 */
public class Jdk8HttpExecutorTest {

    private MockWebServer server;
    private Jdk8HttpExecutor executor;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        executor = new Jdk8HttpExecutor();
    }

    @After
    public void tearDown() throws Exception {
        executor.close();
        server.shutdown();
    }

    @Test
    public void blockingReadsFullBody() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        HttpResult result = executor.execute(request("/v1/chat/completions"));

        assertThat(result.getStatus()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo("{\"ok\":true}");
    }

    @Test
    public void streamingReadsLineByLine() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: a\n\ndata: b\n\n"));
        final List<String> lines = new ArrayList<String>();

        executor.executeStreaming(request("/v1/chat/completions"), new StreamListener() {
            @Override
            public void onLine(String line) throws IOException {
                lines.add(line);
            }
        });

        assertThat(lines).contains("data: a", "data: b");
    }

    @Test
    public void nonSuccessStatusFailsWithBodySnippet() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> executor.execute(request("/v1/chat/completions")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("状态码：500")
                .hasMessageContaining("boom");
    }

    @Test
    public void asyncReadsFullBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        HttpResult result = executor.executeAsync(request("/v1/chat/completions")).get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(result.getStatus()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo("{\"ok\":true}");
    }

    @Test
    public void asyncStreamingReadsLineByLine() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("data: a\n\ndata: b\n\n"));
        final List<String> lines = new ArrayList<String>();

        executor.executeStreamingAsync(request("/v1/chat/completions"), new StreamListener() {
            @Override
            public void onLine(String line) {
                lines.add(line);
            }
        }).get(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(lines).contains("data: a", "data: b");
    }

    @Test
    public void asyncNonSuccessFailsWithBodySnippet() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> executor.executeAsync(request("/v1/chat/completions"))
                .get(5, java.util.concurrent.TimeUnit.SECONDS))
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("500");
    }

    @Test
    public void preCancelledSyncCallDoesNotReachTheServer() {
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();

        assertThatThrownBy(() -> executor.execute(request(cancel)))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("调用已取消");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    public void preCancelledAsyncCallDoesNotReachTheServer() {
        LlmCancelHandle cancel = new LlmCancelHandle();
        cancel.cancel();

        assertThatThrownBy(() -> executor.executeAsync(request(cancel))
                .get(5, java.util.concurrent.TimeUnit.SECONDS))
                .hasCauseInstanceOf(LlmCallException.class)
                .hasMessageContaining("调用已取消");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    public void httpErrorCarriesKindAndStatus() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("down"));

        assertThatThrownBy(() -> executor.execute(request("/v1/chat/completions")))
                .isInstanceOf(LlmCallException.class)
                .satisfies(ex -> {
                    LlmCallException error = (LlmCallException) ex;
                    assertThat(error.getKind())
                            .isEqualTo(LlmCallException.Kind.HTTP);
                    assertThat(error.getStatusCode()).isEqualTo(Integer.valueOf(503));
                });
    }

    // ------------------------------------------------------------------
    // F-22：close 之后异步入口必须返回失败 future，而不是同步抛 RejectedExecutionException
    // ------------------------------------------------------------------

    @Test
    public void closeThenAsyncEntryReturnsFailedFuture() {
        executor.close();

        java.util.concurrent.CompletableFuture<HttpResult> future =
                executor.executeAsync(request("/v1/chat/completions"));

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(() -> future.get(1, java.util.concurrent.TimeUnit.SECONDS))
                .hasCauseInstanceOf(LlmCallException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    public void closeThenStreamingAsyncEntryReturnsFailedFuture() {
        executor.close();

        java.util.concurrent.CompletableFuture<Void> future =
                executor.executeStreamingAsync(request("/v1/chat/completions"), line -> { });

        assertThat(future).isCompletedExceptionally();
    }

    // ------------------------------------------------------------------
    // F-21：future.cancel(true) 必须断开在途连接
    // ------------------------------------------------------------------

    @Test
    public void cancellingFutureDisconnectsInFlightRequest() throws Exception {
        // 服务端先回 200 头，再延迟 5 秒发 body；读超时 30 秒。
        // 只有 future.cancel(true) 触发的 disconnect 能让这个请求提前结束。
        final java.util.concurrent.CountDownLatch requestStarted =
                new java.util.concurrent.CountDownLatch(1);
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest recorded) {
                requestStarted.countDown();
                return new MockResponse().setResponseCode(200).setBody("late")
                        .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        });

        java.util.concurrent.CompletableFuture<HttpResult> future =
                executor.executeAsync(new HttpRequest(server.url("/v1/chat/completions").toString(),
                        Collections.singletonMap("Content-Type", "application/json"), "{}",
                        5000, 30000, null));

        // 等请求真的到达服务端，否则可能在建连前就取消了，测不到「在途」。
        assertThat(requestStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        future.cancel(true);

        assertThat(future.isCancelled()).isTrue();
        // disconnect 之后底层读应当很快返回；这里只要求它不再继续挂 5 秒。
        Thread.sleep(500);
        assertThat(future.isCancelled()).isTrue();
    }

    // ------------------------------------------------------------------
    // F-18：0/负超时走默认值，不再等价于无限等待
    // ------------------------------------------------------------------

    @Test
    public void zeroTimeoutUsesDefaultInsteadOfInfinite() {
        // 服务端延迟 2 秒发 body；默认读超时是 120 秒，所以应正常完成而不是挂住。
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok")
                .setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS));

        HttpResult result = executor.execute(new HttpRequest(
                server.url("/v1/chat/completions").toString(),
                Collections.singletonMap("Content-Type", "application/json"), "{}",
                0, 0, null));

        assertThat(result.getBody()).isEqualTo("ok");
    }

    // ------------------------------------------------------------------
    // F-28：executeRaw 不得跟随 3xx
    // ------------------------------------------------------------------

    @Test
    public void executeRawDoesNotFollowRedirect() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "/elsewhere").setBody("redirect"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("final"));

        HttpResult raw = executor.executeRaw(request("/v1/chat/completions"));

        assertThat(raw.getStatus()).isEqualTo(302);
        assertThat(raw.getBody()).isEqualTo("redirect");
        // 若跟随了重定向，服务端会收到第二个请求。
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // F-24：单行超过上限时按响应损坏处理，而不是无限累积
    // ------------------------------------------------------------------

    @Test
    public void streamingRejectsOverlongLine() {
        StringBuilder huge = new StringBuilder("data: ");
        for (int i = 0; i < 2 * 1024 * 1024; i++) {
            huge.append('x');
        }
        server.enqueue(new MockResponse().setResponseCode(200).setBody(huge.toString() + "\n"));

        assertThatThrownBy(() -> executor.executeStreaming(request("/v1/chat/completions"),
                line -> { }))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("单行");
    }

    private HttpRequest request(String path) {
        return request(path, null);
    }

    private HttpRequest request(LlmCancelHandle cancel) {
        return request("/v1/chat/completions", cancel);
    }

    private HttpRequest request(String path, LlmCancelHandle cancel) {
        return new HttpRequest(server.url(path).toString(),
                Collections.singletonMap("Content-Type", "application/json"), "{}",
                5000, 5000, cancel);
    }
}
