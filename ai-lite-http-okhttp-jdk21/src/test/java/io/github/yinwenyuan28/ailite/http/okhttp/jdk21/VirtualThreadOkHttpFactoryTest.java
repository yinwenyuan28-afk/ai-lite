package io.github.yinwenyuan28.ailite.http.okhttp.jdk21;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.http.AsyncHttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 路径：异步回调必须跑在虚拟线程上，同步入口不受影响。
 */
public class VirtualThreadOkHttpFactoryTest {

    private MockWebServer server;
    private HttpExecutor executor;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        executor = VirtualThreadOkHttpFactory.create(new AiLiteConfig.Http());
    }

    @After
    public void tearDown() throws Exception {
        executor.close();
        server.shutdown();
    }

    @Test
    public void asyncResponseCompletesOnVirtualThread() throws Exception {
        server.enqueue(new MockResponse().setBody("ok"));
        AtomicReference<Thread> callbackThread = new AtomicReference<Thread>();

        String body = ((AsyncHttpExecutor) executor)
                .executeAsync(request(null))
                .thenApply(result -> {
                    callbackThread.set(Thread.currentThread());
                    return result.getBody();
                })
                .get(5, TimeUnit.SECONDS);

        assertThat(body).isEqualTo("ok");
        assertThat(callbackThread.get().isVirtual()).isTrue();
    }

    @Test
    public void streamingCallbacksRunOnVirtualThread() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: one\ndata: two\n"));
        List<String> lines = new CopyOnWriteArrayList<String>();
        AtomicReference<Thread> callbackThread = new AtomicReference<Thread>();

        ((AsyncHttpExecutor) executor).executeStreamingAsync(request(null), line -> {
            callbackThread.compareAndSet(null, Thread.currentThread());
            lines.add(line);
        }).get(5, TimeUnit.SECONDS);

        assertThat(lines).containsExactly("data: one", "data: two");
        assertThat(callbackThread.get().isVirtual()).isTrue();
    }

    @Test
    public void cancelDuringStreamingCompletesCancelled() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            body.append("data: chunk-").append(i).append('\n');
        }
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body.toString(), 1)
                .throttleBody(1, 5, TimeUnit.MILLISECONDS));
        LlmCancelHandle cancel = new LlmCancelHandle();

        CompletableFuture<Void> future = ((AsyncHttpExecutor) executor)
                .executeStreamingAsync(request(cancel), line -> {
                });
        Thread.sleep(50);
        cancel.cancel();

        try {
            future.get(5, TimeUnit.SECONDS);
            org.junit.Assert.fail("expected cancellation");
        } catch (ExecutionException ex) {
            assertThat(ex.getCause()).isInstanceOf(LlmCallException.class);
            assertThat(((LlmCallException) ex.getCause()).getKind())
                    .isEqualTo(LlmCallException.Kind.CANCELLED);
        }
    }

    private HttpRequest request(LlmCancelHandle cancel) {
        return new HttpRequest(server.url("/v1/chat").toString(),
                Collections.<String, String>emptyMap(), "{}", 3000, 3000, cancel);
    }
}
