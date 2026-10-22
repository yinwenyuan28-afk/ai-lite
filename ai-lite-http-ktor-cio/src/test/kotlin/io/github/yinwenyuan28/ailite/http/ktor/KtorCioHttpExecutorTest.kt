package io.github.yinwenyuan28.ailite.http.ktor

import io.github.yinwenyuan28.ailite.config.AiLiteConfig
import io.github.yinwenyuan28.ailite.http.HttpRequest
import io.github.yinwenyuan28.ailite.http.StreamListener
import io.github.yinwenyuan28.ailite.llm.LlmCallException
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Ktor CIO 传输：同步/异步/流式/取消/错误分类/生命周期。
 */
class KtorCioHttpExecutorTest {

    private lateinit var server: MockWebServer
    private lateinit var executor: KtorCioHttpExecutor

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        executor = KtorCioHttpExecutor.create(AiLiteConfig.Http()) as KtorCioHttpExecutor
    }

    @After
    fun tearDown() {
        executor.close()
        server.shutdown()
    }

    @Test
    fun executePostsJsonAndReturnsBody() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))

        val result = executor.execute(request("/v1/chat"))

        assertThat(result.status).isEqualTo(200)
        assertThat(result.body).isEqualTo("{\"ok\":true}")
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.getHeader("Content-Type")).contains("application/json")
        assertThat(recorded.body.readUtf8()).isEqualTo("{\"x\":1}")
    }

    @Test
    fun executeNon2xxThrowsHttpWithStatus() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("busy"))

        assertThatThrownBy { executor.execute(request("/v1/chat")) }
                .isInstanceOfSatisfying(LlmCallException::class.java) { ex ->
                    assertThat(ex.kind).isEqualTo(LlmCallException.Kind.HTTP)
                    assertThat(ex.statusCode).isEqualTo(429)
                    assertThat(ex.message).contains("busy")
                }
    }

    @Test
    fun executeStreamingReturnsRawLines() {
        server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: one\r\n\r\ndata: 你好\r\n\r\n", 1))
        val lines = ArrayList<String>()

        executor.executeStreaming(request("/v1/chat"), StreamListener { lines.add(it) })

        assertThat(lines).containsExactly("data: one", "", "data: 你好", "")
    }

    @Test
    fun executeAsyncCompletes() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val result = executor.executeAsync(request("/v1/chat")).get(5, TimeUnit.SECONDS)

        assertThat(result.status).isEqualTo(200)
        assertThat(result.body).isEqualTo("ok")
    }

    @Test
    fun executeStreamingAsyncReturnsRawLines() {
        server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: one\r\n\r\ndata: 你好\r\n\r\n", 1))
        val lines = CopyOnWriteArrayList<String>()

        executor.executeStreamingAsync(request("/v1/chat"), StreamListener { lines.add(it) })
                .get(5, TimeUnit.SECONDS)

        assertThat(lines).containsExactly("data: one", "", "data: 你好", "")
    }

    @Test
    fun streamingFlushesTailWithoutNewline() {
        server.enqueue(MockResponse().setBody("last"))
        val lines = CopyOnWriteArrayList<String>()

        executor.executeStreamingAsync(request("/v1/chat"), StreamListener { lines.add(it) })
                .get(5, TimeUnit.SECONDS)

        assertThat(lines).containsExactly("last")
    }

    @Test
    fun streamingNon2xxDoesNotFeedLines() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val lines = CopyOnWriteArrayList<String>()

        val error = awaitFailure(executor.executeStreamingAsync(
                request("/v1/chat"), StreamListener { lines.add(it) }))

        assertThat(error.kind).isEqualTo(LlmCallException.Kind.HTTP)
        assertThat(error.statusCode).isEqualTo(500)
        assertThat(error.message).contains("boom")
        assertThat(lines).isEmpty()
    }

    @Test
    fun preCancelledRequestNeverReachesServer() {
        val cancel = LlmCancelHandle()
        cancel.cancel()

        val error = awaitFailure(executor.executeAsync(request("/v1/chat", cancel)))

        assertThat(error.kind).isEqualTo(LlmCallException.Kind.CANCELLED)
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun cancelDuringStreamingAbortsAndCompletesCancelled() {
        val body = StringBuilder()
        for (i in 0 until 200) {
            body.append("data: chunk-").append(i).append('\n')
        }
        server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body.toString(), 1)
                .throttleBody(1, 5, TimeUnit.MILLISECONDS))
        val cancel = LlmCancelHandle()
        val firstLine = CountDownLatch(1)

        val future = executor.executeStreamingAsync(request("/v1/chat", cancel),
                StreamListener { firstLine.countDown() })

        assertThat(firstLine.await(10, TimeUnit.SECONDS)).isTrue()
        cancel.cancel()
        val error = awaitFailure(future)

        assertThat(error.kind).isEqualTo(LlmCallException.Kind.CANCELLED)
    }

    @Test
    fun earlyEofIsTransportFailure() {
        // body 必须足够大，MockWebServer 才会真的在响应体中间断开。
        val partial = "data: " + "x".repeat(200_000) + "\n"
        server.enqueue(MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                .setChunkedBody(partial, 8192))

        val error = awaitFailure(executor.executeStreamingAsync(
                request("/v1/chat"), StreamListener { }))

        assertThat(error.kind).isEqualTo(LlmCallException.Kind.TRANSPORT)
    }

    @Test
    fun closeIsIdempotentAndRejectsNewRequests() {
        executor.close()
        executor.close()

        assertThatThrownBy { executor.execute(request("/v1/chat")) }
                .isInstanceOfSatisfying(LlmCallException::class.java) { ex ->
                    assertThat(ex.kind).isEqualTo(LlmCallException.Kind.CONFIG)
                }
    }

    private fun request(path: String, cancel: LlmCancelHandle? = null): HttpRequest {
        return HttpRequest(server.url(path).toString(),
                Collections.emptyMap<String, String>(), "{\"x\":1}",
                3000, 3000, cancel)
    }

    private fun awaitFailure(future: CompletableFuture<*>): LlmCallException {
        try {
            future.get(5, TimeUnit.SECONDS)
            fail("expected failure")
            throw AssertionError("unreachable")
        } catch (ex: ExecutionException) {
            assertThat(ex.cause).isInstanceOf(LlmCallException::class.java)
            return ex.cause as LlmCallException
        } catch (ex: Exception) {
            throw AssertionError("unexpected exception", ex)
        }
    }
}
