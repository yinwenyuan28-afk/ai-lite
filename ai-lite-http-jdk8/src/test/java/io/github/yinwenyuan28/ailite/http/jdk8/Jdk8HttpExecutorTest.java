package io.github.yinwenyuan28.ailite.http.jdk8;

import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
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

    private HttpRequest request(String path) {
        return new HttpRequest(server.url(path).toString(),
                Collections.singletonMap("Content-Type", "application/json"), "{}",
                5000, 5000, null);
    }
}
