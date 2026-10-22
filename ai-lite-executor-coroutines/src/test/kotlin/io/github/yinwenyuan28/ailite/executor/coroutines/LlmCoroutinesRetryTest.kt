package io.github.yinwenyuan28.ailite.executor.coroutines

import io.github.yinwenyuan28.ailite.config.AiLiteConfig
import io.github.yinwenyuan28.ailite.config.ModelResolver
import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider
import io.github.yinwenyuan28.ailite.llm.LlmCall
import io.github.yinwenyuan28.ailite.llm.LlmCallException
import io.github.yinwenyuan28.ailite.llm.LlmCallRecord
import io.github.yinwenyuan28.ailite.llm.LlmCallStore
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle
import io.github.yinwenyuan28.ailite.llm.LlmCompletion
import io.github.yinwenyuan28.ailite.llm.LlmGateway
import io.github.yinwenyuan28.ailite.llm.LlmMessage
import io.github.yinwenyuan28.ailite.llm.LlmRequest
import io.github.yinwenyuan28.ailite.llm.LlmResult
import io.github.yinwenyuan28.ailite.llm.LlmStopReason
import io.github.yinwenyuan28.ailite.llm.LlmTransport
import io.github.yinwenyuan28.ailite.llm.LlmUsage
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore
import io.github.yinwenyuan28.ailite.llm.RetryPolicy
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 协程版 oneWithRetry：await() + delay() 退避，取消不占载体线程。
 */
class LlmCoroutinesRetryTest {

    private val transports = mutableListOf<RetryTransport>()

    @After
    fun tearDown() {
        transports.forEach { it.close() }
    }

    @Test
    fun `retries transient failure then succeeds`() {
        runBlocking {
            val transport = transport().fail(transportError()).success("ok")

            val result = LlmCoroutines.oneWithRetry(gateway(transport), call(), fastPolicy(3))

            assertThat(result.content).isEqualTo("ok")
            assertThat(transport.requests).hasSize(2)
        }
    }

    @Test
    fun `does not retry http 400`() {
        val transport = transport().fail(httpError(400))

        assertThatThrownBy {
            runBlocking { LlmCoroutines.oneWithRetry(gateway(transport), call(), fastPolicy(3)) }
        }.isInstanceOf(LlmCallException::class.java)
            .satisfies({
                val error = it as LlmCallException
                assertThat(error.kind).isEqualTo(LlmCallException.Kind.HTTP)
                assertThat(error.statusCode).isEqualTo(400)
            })
        assertThat(transport.requests).hasSize(1)
    }

    @Test
    fun `does not retry parse failure`() {
        val transport = transport().fail(
            LlmCallException("bad json", null, LlmCallException.Kind.PARSE))

        assertThatThrownBy {
            runBlocking { LlmCoroutines.oneWithRetry(gateway(transport), call(), fastPolicy(3)) }
        }.isInstanceOf(LlmCallException::class.java)
        assertThat(transport.requests).hasSize(1)
    }

    @Test
    fun `exhausted attempts keep suppressed failures`() {
        val transport = transport()
            .fail(transportError())
            .fail(transportError())
            .fail(transportError())

        assertThatThrownBy {
            runBlocking { LlmCoroutines.oneWithRetry(gateway(transport), call(), fastPolicy(3)) }
        }.isInstanceOf(LlmCallException::class.java)
            .satisfies({ assertThat(it.suppressed.size).isEqualTo(2) })
        assertThat(transport.requests).hasSize(3)
    }

    @Test
    fun `cancel handle before start returns aborted without request`() {
        runBlocking {
            val transport = transport()
            val cancel = LlmCancelHandle()
            cancel.cancel()

            val result = LlmCoroutines.oneWithRetry(gateway(transport), call(), fastPolicy(3), cancel)

            assertThat(result.stopReason).isEqualTo(LlmStopReason.ABORTED)
            assertThat(transport.requests).isEmpty()
        }
    }

    @Test
    fun `withTimeout cancels during backoff and does not retry`() {
        val transport = transport().fail(transportError())
        val policy = RetryPolicy.builder()
            .maxAttempts(3)
            .initialBackoffMs(60_000L)
            .maxBackoffMs(60_000L)
            .jitter(false)
            .build()

        assertThatThrownBy {
            runBlocking {
                withTimeout(100L) {
                    LlmCoroutines.oneWithRetry(gateway(transport), call(), policy)
                }
            }
        }.isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(transport.requests).hasSize(1)
    }

    @Test
    fun `withTimeout cancels in-flight attempt and aborts transport`() {
        val transport = transport(delayMs = 60_000L)
        val cancel = LlmCancelHandle()

        assertThatThrownBy {
            runBlocking {
                withTimeout(100L) {
                    LlmCoroutines.oneWithRetry(gateway(transport), call(), fastPolicy(3), cancel)
                }
            }
        }.isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(transport.requests).hasSize(1)
        assertThat(cancel.isCancelled).isTrue
    }

    @Test
    fun `job cancel during backoff stops further attempts`() {
        runBlocking {
            val transport = transport().fail(transportError())
            val policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialBackoffMs(60_000L)
                .maxBackoffMs(60_000L)
                .jitter(false)
                .build()
            val job = launch {
                LlmCoroutines.oneWithRetry(gateway(transport), call(), policy)
            }
            withTimeout(5_000L) {
                while (transport.requests.isEmpty()) {
                    delay(5L)
                }
            }
            job.cancelAndJoin()

            assertThat(transport.requests).hasSize(1)
        }
    }

    @Test
    fun `total deadline stops before another attempt`() {
        val transport = transport().fail(transportError())
        val policy = RetryPolicy.builder()
            .maxAttempts(5)
            .initialBackoffMs(1000L)
            .maxBackoffMs(1000L)
            .jitter(false)
            .totalDeadlineMs(30L)
            .build()

        assertThatThrownBy {
            runBlocking { LlmCoroutines.oneWithRetry(gateway(transport), call(), policy) }
        }.isInstanceOf(LlmCallException::class.java)
            .hasMessageContaining("重试超过总时限")
        assertThat(transport.requests).hasSize(1)
    }

    @Test
    fun `every attempt writes its own record`() {
        runBlocking {
            val transport = transport().fail(transportError()).success("ok")
            val store = RecordingStore()

            LlmCoroutines.oneWithRetry(gateway(transport, store), call(), fastPolicy(3))

            assertThat(store.records).hasSize(2)
            assertThat(store.records[0].stopReason).isEqualTo(LlmStopReason.ERROR.wire())
            assertThat(store.records[1].stopReason).isEqualTo(LlmStopReason.STOP.wire())
        }
    }

    @Test
    fun `coroutine and async retry agree on attempts and terminal state`() {
        runBlocking {
            val coroutineTransport = transport()
                .fail(transportError()).fail(httpError(503)).success("ok")
            val asyncTransport = transport()
                .fail(transportError()).fail(httpError(503)).success("ok")

            val coroutineResult = LlmCoroutines.oneWithRetry(
                gateway(coroutineTransport), call(), fastPolicy(3))
            val asyncResult = gateway(asyncTransport).oneWithRetryAsync(call(), fastPolicy(3))
                .get(5, TimeUnit.SECONDS)

            assertThat(coroutineResult.content).isEqualTo(asyncResult.content)
            assertThat(coroutineTransport.requests.size)
                .isEqualTo(asyncTransport.requests.size)
        }
    }

    @Test
    fun `coroutine and async agree on exhausted error`() {
        runBlocking {
            val coroutineTransport = transport().fail(transportError()).fail(httpError(503))
            val asyncTransport = transport().fail(transportError()).fail(httpError(503))

            val coroutineError = try {
                LlmCoroutines.oneWithRetry(gateway(coroutineTransport), call(), fastPolicy(2))
                throw AssertionError("expected failure")
            } catch (ex: LlmCallException) {
                ex
            }
            val asyncError = try {
                gateway(asyncTransport).oneWithRetryAsync(call(), fastPolicy(2))
                    .get(5, TimeUnit.SECONDS)
                throw AssertionError("expected failure")
            } catch (ex: java.util.concurrent.ExecutionException) {
                ex.cause as LlmCallException
            }

            assertThat(coroutineError.kind).isEqualTo(asyncError.kind)
            assertThat(coroutineError.statusCode).isEqualTo(asyncError.statusCode)
            assertThat(coroutineError.suppressed.size).isEqualTo(asyncError.suppressed.size)
            assertThat(coroutineTransport.requests.size)
                .isEqualTo(asyncTransport.requests.size)
        }
    }

    private fun transport(delayMs: Long = 0L): RetryTransport =
        RetryTransport(delayMs).also { transports.add(it) }

    private fun gateway(transport: LlmTransport,
                        store: LlmCallStore = NoopLlmCallStore()): LlmGateway {
        val llm = AiLiteConfig.Llm()
        llm.bizIds = listOf("biz")
        val model = AiLiteConfig.Model().apply {
            id = "m"
            isReasoning = false
            input = listOf("text")
            maxTokens = 4096
        }
        val provider = AiLiteConfig.Provider().apply {
            api = "fake"
            baseUrl = "http://localhost"
            apiKey = "key"
            models = listOf(model)
        }
        llm.providers["p"] = provider
        llm.profiles["main"] = AiLiteConfig.Profile().apply {
            this.provider = "p"
            this.model = "m"
            thinkingLevel = "off"
        }
        return LlmGateway(ModelResolver(llm), listOf(transport), store,
            DirectExecutorProvider(), SequentialToolExecutor())
    }

    private fun call(): LlmCall =
        LlmCall.profile("biz", "main", listOf(LlmMessage.user("hi")))

    private fun fastPolicy(attempts: Int): RetryPolicy =
        RetryPolicy.builder()
            .maxAttempts(attempts)
            .initialBackoffMs(1L)
            .maxBackoffMs(1L)
            .jitter(false)
            .build()

    private fun transportError(): LlmCallException =
        LlmCallException("boom", IOException("io"), LlmCallException.Kind.TRANSPORT)

    private fun httpError(status: Int): LlmCallException =
        LlmCallException("http $status", null, LlmCallException.Kind.HTTP, status)

    /**
     * 按顺序返回成功结果或抛出指定异常，并记录每次请求。
     */
    private class RetryTransport(private val delayMs: Long = 0L) : LlmTransport {

        private val outcomes = ArrayDeque<Any>()
        val requests: MutableList<LlmRequest> =
            Collections.synchronizedList(mutableListOf<LlmRequest>())
        private val scheduler: ScheduledExecutorService =
            Executors.newScheduledThreadPool(1) { runnable ->
                Thread(runnable, "coroutine-retry-test").apply { isDaemon = true }
            }

        fun success(content: String): RetryTransport {
            synchronized(outcomes) { outcomes.addLast(stop(content)) }
            return this
        }

        fun fail(error: LlmCallException): RetryTransport {
            synchronized(outcomes) { outcomes.addLast(error) }
            return this
        }

        override fun api(): String = "fake"

        override fun complete(request: LlmRequest): LlmCompletion =
            throw UnsupportedOperationException("async only")

        override fun completeAsync(request: LlmRequest): CompletableFuture<LlmCompletion> {
            requests.add(request)
            val future = CompletableFuture<LlmCompletion>()
            request.cancel?.bindAbort {
                future.completeExceptionally(
                    LlmCallException("调用已取消", null, LlmCallException.Kind.CANCELLED))
            }
            scheduler.schedule({
                if (!future.isDone) {
                    val outcome = synchronized(outcomes) {
                        if (outcomes.isEmpty()) stop("") else outcomes.removeFirst()
                    }
                    if (outcome is LlmCallException) {
                        future.completeExceptionally(outcome)
                    } else {
                        future.complete(outcome as LlmCompletion)
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
            return future
        }

        fun close() {
            scheduler.shutdownNow()
        }

        private fun stop(content: String): LlmCompletion =
            LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.zero(), null)
    }

    private class RecordingStore : LlmCallStore {
        val records: MutableList<LlmCallRecord> =
            Collections.synchronizedList(mutableListOf<LlmCallRecord>())

        override fun insert(record: LlmCallRecord) {
            records.add(record)
        }
    }
}
