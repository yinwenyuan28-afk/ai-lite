package io.github.yinwenyuan28.ailite.executor.coroutines

import io.github.yinwenyuan28.ailite.config.AiLiteConfig
import io.github.yinwenyuan28.ailite.config.ModelResolver
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider
import io.github.yinwenyuan28.ailite.llm.LlmCall
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle
import io.github.yinwenyuan28.ailite.llm.LlmCompletion
import io.github.yinwenyuan28.ailite.llm.LlmGateway
import io.github.yinwenyuan28.ailite.llm.LlmMessage
import io.github.yinwenyuan28.ailite.llm.LlmRequest
import io.github.yinwenyuan28.ailite.llm.LlmResult
import io.github.yinwenyuan28.ailite.llm.LlmStopReason
import io.github.yinwenyuan28.ailite.llm.LlmTool
import io.github.yinwenyuan28.ailite.llm.LlmToolCall
import io.github.yinwenyuan28.ailite.llm.LlmTransport
import io.github.yinwenyuan28.ailite.llm.LlmUsage
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 协程执行器与 suspend 桥接：载体线程有界、海量协程并发、取消和工具循环。
 */
class LlmCoroutinesTest {

    @Test
    fun `io executor runs tasks and respects parallelism`() {
        val provider = CoroutineExecutorProvider(2)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val done = CountDownLatch(40)
        try {
            repeat(40) {
                provider.io().execute {
                    val now = active.incrementAndGet()
                    maxActive.updateAndGet { max -> maxOf(max, now) }
                    Thread.sleep(20)
                    active.decrementAndGet()
                    done.countDown()
                }
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
            assertThat(maxActive.get()).isLessThanOrEqualTo(2)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `close rejects new tasks`() {
        val provider = CoroutineExecutorProvider(1)
        provider.close()

        assertThatThrownBy { provider.io().execute { } }
            .isInstanceOf(RejectedExecutionException::class.java)
    }

    @Test
    fun `thousands of suspend calls share two carrier threads`() {
        val provider = CoroutineExecutorProvider(2)
        val transport = DelayedTransport(10L)
        repeat(2000) { transport.enqueue(stop("ok")) }
        val gateway = gateway(transport)
        try {
            val results = runBlocking(provider.dispatcher()) {
                (1..2000).map {
                    async { LlmCoroutines.one(gateway, call()) }
                }.awaitAll()
            }
            assertThat(results).hasSize(2000)
            assertThat(transport.requests).hasSize(2000)
        } finally {
            provider.close()
            transport.close()
        }
    }

    @Test
    fun `cancelling a coroutine cancels the underlying call`() {
        val provider = CoroutineExecutorProvider(2)
        val transport = DelayedTransport(60_000L)
        val gateway = gateway(transport)
        val cancel = LlmCancelHandle()
        try {
            assertThatThrownBy {
                runBlocking(provider.dispatcher()) {
                    withTimeout(100L) {
                        LlmCoroutines.one(gateway, call(), cancel)
                    }
                }
            }.isInstanceOf(TimeoutCancellationException::class.java)
            assertThat(cancel.isCancelled).isTrue()
        } finally {
            provider.close()
            transport.close()
        }
    }

    @Test
    fun `tool loop suspend feeds result back`() {
        val provider = CoroutineExecutorProvider(2)
        val transport = DelayedTransport(5L)
            .enqueue(toolUse("c1", "echo", "{\"q\":\"x\"}"))
            .enqueue(stop("done"))
        val gateway = gateway(transport)
        val executions = AtomicInteger()
        try {
            val result: LlmResult = runBlocking(provider.dispatcher()) {
                LlmCoroutines.toolLoop(gateway, call(), listOf(echoTool(executions)), 4)
            }
            assertThat(result.content).isEqualTo("done")
            assertThat(result.rounds).isEqualTo(2)
            assertThat(executions.get()).isEqualTo(1)
        } finally {
            provider.close()
            transport.close()
        }
    }

    @Test
    fun `round suspend does not execute tools`() {
        val provider = CoroutineExecutorProvider(2)
        val transport = DelayedTransport(5L)
            .enqueue(toolUse("c1", "echo", "{}"))
        val gateway = gateway(transport)
        val executions = AtomicInteger()
        try {
            val result = runBlocking(provider.dispatcher()) {
                LlmCoroutines.round(gateway, call(), listOf(echoTool(executions)))
            }
            assertThat(result.stopReason).isEqualTo(LlmStopReason.TOOL_USE)
            assertThat(executions.get()).isZero()
        } finally {
            provider.close()
            transport.close()
        }
    }

    @Test
    fun `transport failure surfaces from suspend call`() {
        val provider = CoroutineExecutorProvider(2)
        val transport = DelayedTransport(5L).failNext("boom")
        val gateway = gateway(transport)
        try {
            assertThatThrownBy {
                runBlocking(provider.dispatcher()) {
                    LlmCoroutines.one(gateway, call())
                }
            }.hasMessageContaining("模型调用失败")
                .hasStackTraceContaining("boom")
        } finally {
            provider.close()
            transport.close()
        }
    }

    @Test
    fun `java style async entry returns future`() {
        val provider = CoroutineExecutorProvider(1)
        val transport = DelayedTransport(5L).enqueue(stop("ok"))
        val gateway = gateway(transport)
        try {
            val result = LlmCoroutines.oneAsync(gateway, call()).get(5, TimeUnit.SECONDS)
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

    private fun call(): LlmCall =
        LlmCall.profile("biz", "main", listOf(LlmMessage.user("hi")))

    private fun stop(content: String): LlmCompletion =
        LlmCompletion(content, null, LlmStopReason.STOP, LlmUsage.zero(), null)

    private fun toolUse(id: String, name: String, arguments: String): LlmCompletion =
        LlmCompletion("", null, LlmStopReason.TOOL_USE, LlmUsage.zero(),
            listOf(LlmToolCall(id, name, arguments)))

    private fun echoTool(executions: AtomicInteger) = object : LlmTool {
        override fun name(): String = "echo"
        override fun description(): String = "echo"
        override fun parameters(): Map<String, Any> = mapOf("type" to "object")
        override fun execute(arguments: Map<String, Any>): String {
            executions.incrementAndGet()
            return "ok:" + arguments["q"]
        }
    }

    /**
     * 用单线程调度器模拟异步 I/O，确保并发能力不是来自 HTTP 线程池。
     */
    private class DelayedTransport(private val delayMs: Long) : LlmTransport {

        private val responses = ArrayDeque<LlmCompletion>()
        private val failures = ArrayDeque<String>()
        val requests: MutableList<LlmRequest> = Collections.synchronizedList(mutableListOf())
        private val scheduler = Executors.newScheduledThreadPool(1) { runnable ->
            Thread(runnable, "fake-async-llm").apply { isDaemon = true }
        }

        fun enqueue(completion: LlmCompletion): DelayedTransport {
            synchronized(responses) { responses.addLast(completion) }
            return this
        }

        fun failNext(message: String): DelayedTransport {
            synchronized(responses) { failures.addLast(message) }
            return this
        }

        override fun api(): String = "fake"

        override fun complete(request: LlmRequest): LlmCompletion =
            throw UnsupportedOperationException("async only")

        override fun completeAsync(request: LlmRequest): CompletableFuture<LlmCompletion> {
            requests.add(request)
            val future = CompletableFuture<LlmCompletion>()
            scheduler.schedule({
                var completion: LlmCompletion? = null
                var failure: String? = null
                synchronized(responses) {
                    failure = if (failures.isEmpty()) null else failures.removeFirst()
                    completion = if (failure == null && responses.isNotEmpty()) {
                        responses.removeFirst()
                    } else {
                        null
                    }
                }
                val chosen = completion
                val failed = failure
                when {
                    failed != null -> future.completeExceptionally(RuntimeException(failed))
                    chosen != null -> {
                        if (request.onDelta != null && chosen.content.isNotEmpty()) {
                            request.onDelta!!.accept(chosen.content)
                        }
                        future.complete(chosen)
                    }
                    else -> future.complete(
                        LlmCompletion("", null, LlmStopReason.STOP, LlmUsage.zero(), null))
                }
            }, delayMs, TimeUnit.MILLISECONDS)
            return future
        }

        fun close() {
            scheduler.shutdownNow()
        }
    }
}
