package io.github.yinwenyuan28.ailite.executor.coroutines

import io.github.yinwenyuan28.ailite.config.AiLiteConfig
import io.github.yinwenyuan28.ailite.config.ModelResolver
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider
import io.github.yinwenyuan28.ailite.llm.LlmCall
import io.github.yinwenyuan28.ailite.llm.LlmCallException
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle
import io.github.yinwenyuan28.ailite.llm.LlmCompletion
import io.github.yinwenyuan28.ailite.llm.LlmGateway
import io.github.yinwenyuan28.ailite.llm.LlmMessage
import io.github.yinwenyuan28.ailite.llm.LlmRequest
import io.github.yinwenyuan28.ailite.llm.LlmResult
import io.github.yinwenyuan28.ailite.llm.LlmStopReason
import io.github.yinwenyuan28.ailite.llm.LlmTransport
import io.github.yinwenyuan28.ailite.llm.LlmUsage
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 协程适配层的 parity：LlmCoroutines 只是薄桥接，不重试、不改写请求、不吞异常。
 */
class LlmCoroutinesParityTest {

    @Test
    fun `one matches gateway oneAsync await`() {
        val bridgeTransport = ScriptedTransport().enqueue(stop("answer"))
        val awaitTransport = ScriptedTransport().enqueue(stop("answer"))
        val bridgeGateway = gateway(bridgeTransport)
        val awaitGateway = gateway(awaitTransport)

        val viaBridge: LlmResult = runBlocking {
            LlmCoroutines.one(bridgeGateway, call("hi"))
        }
        val viaAwait: LlmResult = runBlocking {
            awaitGateway.oneAsync(call("hi")).await()
        }

        assertThat(viaBridge.content).isEqualTo(viaAwait.content)
        assertThat(viaBridge.stopReason).isEqualTo(viaAwait.stopReason)
        assertThat(viaBridge.rounds).isEqualTo(viaAwait.rounds)
        assertThat(viaBridge.usage.totalTokens).isEqualTo(viaAwait.usage.totalTokens)
        assertThat(viaBridge.provider).isEqualTo(viaAwait.provider)
        assertThat(viaBridge.modelId).isEqualTo(viaAwait.modelId)
    }

    @Test
    fun `oneAsync is a pure delegate`() {
        val transport = ScriptedTransport().enqueue(stop("ok"))
        val handle = LlmCancelHandle()

        val result = LlmCoroutines.oneAsync(gateway(transport), call("hi"), handle)
            .get(5, TimeUnit.SECONDS)

        assertThat(result.content).isEqualTo("ok")
        assertThat(transport.requests).hasSize(1)
        assertThat(transport.requests[0].cancel).isSameAs(handle)
        assertThat(transport.requests[0].messages).hasSize(1)
        assertThat(transport.requests[0].isStream).isFalse()
    }

    @Test
    fun `cancelling the job cancels future and transport`() {
        val provider = CoroutineExecutorProvider(2)
        val transport = ScriptedTransport(delayMs = 60_000L)
        val gateway = gateway(transport)
        val handle = LlmCancelHandle()
        try {
            runBlocking(provider.dispatcher()) {
                val job = launch {
                    LlmCoroutines.one(gateway, call("hi"), handle)
                }
                awaitRequest(transport)
                job.cancelAndJoin()
            }
            awaitAborts(transport)

            assertThat(handle.isCancelled).isTrue()
            assertThat(transport.aborts.get()).isEqualTo(1)
        } finally {
            provider.close()
            transport.close()
        }
    }

    @Test
    fun `withTimeout cancels the underlying call and keeps the exception type`() {
        val provider = CoroutineExecutorProvider(2)
        val transport = ScriptedTransport(delayMs = 60_000L)
        val gateway = gateway(transport)
        val handle = LlmCancelHandle()
        try {
            assertThatThrownBy {
                runBlocking(provider.dispatcher()) {
                    withTimeout(100L) {
                        LlmCoroutines.one(gateway, call("hi"), handle)
                    }
                }
            }.isInstanceOf(TimeoutCancellationException::class.java)

            awaitAborts(transport)
            assertThat(handle.isCancelled).isTrue()
            assertThat(transport.aborts.get()).isEqualTo(1)
        } finally {
            provider.close()
            transport.close()
        }
    }

    @Test
    fun `sibling failure cancels the other child`() {
        val provider = CoroutineExecutorProvider(4)
        val transport = KindTransport()
        val gateway = gateway(transport)
        val handle = LlmCancelHandle()
        try {
            assertThatThrownBy {
                runBlocking(provider.dispatcher()) {
                    coroutineScope {
                        val failing = async { LlmCoroutines.one(gateway, call("fail")) }
                        val hanging = async {
                            LlmCoroutines.one(gateway, call("hang"), handle)
                        }
                        awaitAll(failing, hanging)
                    }
                }
            }.hasMessageContaining("boom")

            awaitAborts(transport)
            assertThat(handle.isCancelled).isTrue()
        } finally {
            provider.close()
            transport.close()
        }
    }

    @Test
    fun `supervisorScope keeps the sibling alive`() {
        val provider = CoroutineExecutorProvider(4)
        val transport = KindTransport()
        val gateway = gateway(transport)
        try {
            val result = runBlocking(provider.dispatcher()) {
                supervisorScope {
                    val failing = async { LlmCoroutines.one(gateway, call("fail")) }
                    val healthy = async { LlmCoroutines.one(gateway, call("ok")) }
                    val failure = runCatching { failing.await() }.exceptionOrNull()
                    assertThat(failure).isInstanceOf(LlmCallException::class.java)
                    healthy.await()
                }
            }

            assertThat(result.content).isEqualTo("ok")
        } finally {
            provider.close()
            transport.close()
        }
    }

    private fun gateway(transport: LlmTransport): LlmGateway {
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
        return LlmGateway(ModelResolver(llm), listOf(transport), NoopLlmCallStore(),
            DirectExecutorProvider(), SequentialToolExecutor())
    }

    private fun call(text: String): LlmCall =
        LlmCall.profile("biz", "main", listOf(LlmMessage.user(text)))

    private fun stop(content: String): LlmCompletion =
        newCompletion(content, LlmStopReason.STOP)

    private fun newCompletion(content: String, stop: LlmStopReason): LlmCompletion =
        LlmCompletion(content, null, stop, LlmUsage.zero(), null)

    private fun awaitRequest(transport: ScriptedTransport) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (transport.requests.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L)
        }
    }

    private fun awaitAborts(transport: LlmTransport) {
        val aborts = when (transport) {
            is ScriptedTransport -> transport.aborts
            is KindTransport -> transport.aborts
            else -> return
        }
        val deadline = System.currentTimeMillis() + 5_000L
        while (aborts.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L)
        }
    }

    /**
     * 先入先出响应；延迟完成时注册 abort，模拟真实传输的中断路径。
     */
    private class ScriptedTransport(private val delayMs: Long = 0L) : LlmTransport {

        val requests: MutableList<LlmRequest> = Collections.synchronizedList(mutableListOf())
        val aborts = AtomicInteger()
        private val responses = ArrayDeque<LlmCompletion>()
        private val scheduler: ScheduledExecutorService =
            Executors.newScheduledThreadPool(1) { runnable ->
                Thread(runnable, "parity-llm").apply { isDaemon = true }
            }

        fun enqueue(completion: LlmCompletion): ScriptedTransport {
            synchronized(responses) { responses.addLast(completion) }
            return this
        }

        override fun api(): String = "fake"

        override fun complete(request: LlmRequest): LlmCompletion {
            requests.add(request)
            return take()
        }

        override fun completeAsync(request: LlmRequest): CompletableFuture<LlmCompletion> {
            requests.add(request)
            val future = CompletableFuture<LlmCompletion>()
            request.cancel?.bindAbort {
                aborts.incrementAndGet()
                future.completeExceptionally(LlmCallException("调用已取消"))
            }
            scheduler.schedule({
                if (!future.isDone) {
                    if (request.isCancelled) {
                        future.completeExceptionally(LlmCallException("调用已取消"))
                    } else {
                        future.complete(take())
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
            return future
        }

        private fun take(): LlmCompletion = synchronized(responses) {
            if (responses.isEmpty()) {
                LlmCompletion("", null, LlmStopReason.STOP, LlmUsage.zero(), null)
            } else {
                responses.removeFirst()
            }
        }

        fun close() {
            scheduler.shutdownNow()
        }
    }

    /**
     * 按消息文本决定成功、失败（带延迟）或挂起，用于结构化并发用例。
     */
    private class KindTransport : LlmTransport {

        val aborts = AtomicInteger()
        private val scheduler: ScheduledExecutorService =
            Executors.newScheduledThreadPool(2) { runnable ->
                Thread(runnable, "kind-llm").apply { isDaemon = true }
            }

        override fun api(): String = "fake"

        override fun complete(request: LlmRequest): LlmCompletion =
            throw UnsupportedOperationException("async only")

        override fun completeAsync(request: LlmRequest): CompletableFuture<LlmCompletion> {
            val future = CompletableFuture<LlmCompletion>()
            request.cancel?.bindAbort {
                aborts.incrementAndGet()
                future.completeExceptionally(LlmCallException("调用已取消"))
            }
            val text = request.messages.last().text
            val delay = when (text) {
                "fail" -> 150L
                "ok" -> 200L
                else -> 60_000L
            }
            scheduler.schedule({
                if (future.isDone) {
                    return@schedule
                }
                if (request.isCancelled) {
                    future.completeExceptionally(LlmCallException("调用已取消"))
                } else if (text == "fail") {
                    future.completeExceptionally(LlmCallException("boom"))
                } else {
                    future.complete(
                        LlmCompletion("ok", null, LlmStopReason.STOP, LlmUsage.zero(), null))
                }
            }, delay, TimeUnit.MILLISECONDS)
            return future
        }

        fun close() {
            scheduler.shutdownNow()
        }
    }
}
