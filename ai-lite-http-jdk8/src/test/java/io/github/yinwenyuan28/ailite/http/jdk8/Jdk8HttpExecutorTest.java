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
