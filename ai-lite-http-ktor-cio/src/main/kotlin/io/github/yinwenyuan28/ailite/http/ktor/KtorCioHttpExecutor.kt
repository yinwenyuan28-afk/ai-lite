package io.github.yinwenyuan28.ailite.http.ktor

import io.github.yinwenyuan28.ailite.config.AiLiteConfig
import io.github.yinwenyuan28.ailite.http.AsyncHttpExecutor
import io.github.yinwenyuan28.ailite.http.HttpExecutor
import io.github.yinwenyuan28.ailite.http.HttpRequest
import io.github.yinwenyuan28.ailite.http.HttpResult
import io.github.yinwenyuan28.ailite.http.StreamListener
import io.github.yinwenyuan28.ailite.llm.LlmCallException
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readUTF8LineTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ktor 2.3 CIO 传输。
 *
 * <p>CIO 是真正的 NIO 引擎，少量 selector 线程承载连接；JDK 8 可用。SSE 用
 * {@code readUTF8LineTo} 读原始行，空行、{@code data:} 和 {@code [DONE]} 原样
 * 交给协议适配器。</p>
 *
 * <p>Ktor 的 engine 级 {@code requestTimeout} 默认 15 秒，会杀掉长 SSE；这里
 * 固定为 0，单请求用 {@link HttpTimeout} 的 {@code requestTimeoutMillis} 设为
 * 无限，连接/读空闲才使用调用方传入的超时。</p>
 */
class KtorCioHttpExecutor private constructor(
        private val config: AiLiteConfig.Http
) : AsyncHttpExecutor {

    companion object {
        private const val MAX_LINE_CHARS = 1024 * 1024
        private const val MAX_ERROR_BYTES = 64 * 1024

        /**
         * 供 Starter 反射调用的工厂。Kotlin companion 方法需要 @JvmStatic 才会
         * 生成真正的静态方法。
         */
        @JvmStatic
        fun create(config: AiLiteConfig.Http): HttpExecutor = KtorCioHttpExecutor(config)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val client: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        engine {
            // 长 SSE 不能被引擎默认的 15 秒请求超时掐断。
            requestTimeout = 0
            val maxConnections = config.ktor.maxConnections
            if (maxConnections != null && maxConnections > 0) {
                maxConnectionsCount = maxConnections
            }
            val perRoute = config.ktor.maxConnectionsPerRoute
            if (perRoute != null && perRoute > 0) {
                endpoint.maxConnectionsPerRoute = perRoute
            }
            // CIO 默认不复用连接；pipelineMaxSize=1 表示串行复用，而不是 HTTP pipelining。
            endpoint.pipelineMaxSize = 1
            endpoint.connectTimeout = 0
            endpoint.socketTimeout = 0
        }
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
        }
    }

    override fun execute(request: HttpRequest): HttpResult {
        ensureOpen()
        return try {
            runBlocking { executeInternal(request) }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LlmCallException("调用已取消", ex, LlmCallException.Kind.CANCELLED)
        }
    }

    override fun executeStreaming(request: HttpRequest, listener: StreamListener) {
        ensureOpen()
        try {
            runBlocking { executeStreamingInternal(request, listener) }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LlmCallException("调用已取消", ex, LlmCallException.Kind.CANCELLED)
        }
    }

    override fun executeAsync(request: HttpRequest): CompletableFuture<HttpResult> {
        val future = CompletableFuture<HttpResult>()
        if (!openOrFail(future) || cancelOrFail(future, request.cancel)) {
            return future
        }
        val job = scope.launch {
            try {
                future.complete(executeInternal(request))
            } catch (ex: Throwable) {
                if (!future.isDone) {
                    future.completeExceptionally(mapError(ex, request.cancel))
                }
            }
        }
        bind(future, job, request.cancel)
        return future
    }

    override fun executeStreamingAsync(request: HttpRequest,
                                       listener: StreamListener): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        if (!openOrFail(future) || cancelOrFail(future, request.cancel)) {
            return future
        }
        val job = scope.launch {
            try {
                executeStreamingInternal(request, listener)
                future.complete(null)
            } catch (ex: Throwable) {
                if (!future.isDone) {
                    future.completeExceptionally(mapError(ex, request.cancel))
                }
            }
        }
        bind(future, job, request.cancel)
        return future
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            client.close()
            scope.cancel()
        }
    }

    private suspend fun executeInternal(request: HttpRequest): HttpResult {
        checkCancelled(request.cancel)
        return statement(request).execute { response ->
            val status = response.status.value
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw httpError(status, body)
            }
            HttpResult(status, body)
        }
    }

    private suspend fun executeStreamingInternal(request: HttpRequest,
                                                 listener: StreamListener) {
        checkCancelled(request.cancel)
        statement(request).execute { response ->
            if (!response.status.isSuccess()) {
                val body = readBoundedText(response.bodyAsChannel())
                throw httpError(response.status.value, body)
            }
            val channel = response.bodyAsChannel()
            while (true) {
                checkCancelled(request.cancel)
                val line = readLine(channel) ?: break
                try {
                    listener.onLine(line)
                } catch (ex: LlmCallException) {
                    throw ex
                } catch (ex: Exception) {
                    throw LlmCallException("流式响应处理失败", ex,
                            LlmCallException.Kind.PARSE)
                }
            }
        }
    }

    private suspend fun statement(request: HttpRequest) = client.preparePost(request.url) {
        request.headers.forEach { (name, value) -> header(name, value) }
        if (request.headers.keys.none { it.equals("Content-Type", true) }) {
            contentType(ContentType.Application.Json)
        }
        setBody(request.body)
        timeout {
            requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            if (request.connectTimeoutMs > 0) {
                connectTimeoutMillis = request.connectTimeoutMs.toLong()
            }
            if (request.readTimeoutMs > 0) {
                socketTimeoutMillis = request.readTimeoutMs.toLong()
            }
        }
    }

    private suspend fun readLine(channel: ByteReadChannel): String? {
        val sink = BoundedAppendable(MAX_LINE_CHARS)
        return if (channel.readUTF8LineTo(sink)) sink.toString() else null
    }

    private suspend fun readBoundedText(channel: ByteReadChannel): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < MAX_ERROR_BYTES) {
            val max = minOf(buffer.size, MAX_ERROR_BYTES - out.size())
            val read = channel.readAvailable(buffer, 0, max)
            if (read < 0) {
                break
            }
            if (read > 0) {
                out.write(buffer, 0, read)
            }
        }
        return out.toString("UTF-8")
    }

    private fun bind(future: CompletableFuture<*>, job: Job, cancel: LlmCancelHandle?) {
        // launch 可能在 bind 之前就被取消（甚至还没开始执行），需要一个兜底收敛。
        job.invokeOnCompletion { cause ->
            if (!future.isDone) {
                if (cancel != null && cancel.isCancelled) {
                    future.completeExceptionally(cancelled(cause))
                } else if (cause != null) {
                    future.completeExceptionally(mapError(cause, cancel))
                } else {
                    future.completeExceptionally(LlmCallException(
                            "模型请求未完成", null, LlmCallException.Kind.UNKNOWN))
                }
            }
        }
        if (cancel != null) {
            cancel.bindAbort { job.cancel() }
            if (cancel.isCancelled) {
                job.cancel()
            }
        }
        future.whenComplete { _, _ ->
            if (future.isCancelled) {
                job.cancel()
            }
            if (cancel != null) {
                cancel.bindAbort(null)
            }
        }
    }

    private fun ensureOpen() {
        if (closed.get()) {
            throw LlmCallException("Ktor HTTP 执行器已关闭", null,
                    LlmCallException.Kind.CONFIG)
        }
    }

    private fun openOrFail(future: CompletableFuture<*>): Boolean {
        if (!closed.get()) {
            return true
        }
        future.completeExceptionally(LlmCallException("Ktor HTTP 执行器已关闭", null,
                LlmCallException.Kind.CONFIG))
        return false
    }

    private fun cancelOrFail(future: CompletableFuture<*>, cancel: LlmCancelHandle?): Boolean {
        if (cancel == null || !cancel.isCancelled) {
            return false
        }
        future.completeExceptionally(cancelled(null))
        return true
    }

    private fun checkCancelled(cancel: LlmCancelHandle?) {
        if (cancel != null && cancel.isCancelled) {
            throw cancelled(null)
        }
    }

    private fun mapError(error: Throwable, cancel: LlmCancelHandle?): Throwable {
        if (cancel != null && cancel.isCancelled) {
            return cancelled(error)
        }
        var current: Throwable? = error
        while (current != null) {
            if (current is LlmCallException) {
                return current
            }
            if (current is CancellationException) {
                return cancelled(current)
            }
            if (current is TimeoutException || current is SocketTimeoutException) {
                return LlmCallException("模型请求超时", error,
                        LlmCallException.Kind.TRANSPORT)
            }
            if (current is IOException) {
                return LlmCallException("模型请求失败", error,
                        LlmCallException.Kind.TRANSPORT)
            }
            current = current.cause
        }
        return LlmCallException("模型请求失败", error, LlmCallException.Kind.TRANSPORT)
    }

    private fun cancelled(cause: Throwable?): LlmCallException {
        return LlmCallException("调用已取消", cause, LlmCallException.Kind.CANCELLED)
    }

    private fun httpError(status: Int, body: String): LlmCallException {
        return LlmCallException("模型请求失败，状态码：" + status + brief(body), null,
                LlmCallException.Kind.HTTP, Integer.valueOf(status))
    }

    private fun brief(text: String): String {
        val oneLine = text.replace('\n', ' ').replace('\r', ' ').trim()
        val cut = if (oneLine.length > 300) oneLine.substring(0, 300) else oneLine
        return if (cut.isEmpty()) "" else "，响应：" + cut
    }

    /**
     * 限制单行长度，避免损坏或恶意响应在 readUTF8LineTo 内部无限增长。
     */
    private class BoundedAppendable(private val max: Int) : Appendable {

        private val builder = StringBuilder()

        override fun append(csq: CharSequence?): Appendable {
            val text = csq?.toString() ?: "null"
            ensure(text.length)
            builder.append(text)
            return this
        }

        override fun append(csq: CharSequence?, start: Int, end: Int): Appendable {
            val text = if (csq == null) "null" else csq.subSequence(start, end).toString()
            ensure(text.length)
            builder.append(text)
            return this
        }

        override fun append(c: Char): Appendable {
            ensure(1)
            builder.append(c)
            return this
        }

        private fun ensure(extra: Int) {
            if (builder.length + extra > max) {
                throw LlmCallException("SSE 单行超过 $max 字符", null,
                        LlmCallException.Kind.PARSE)
            }
        }

        override fun toString(): String = builder.toString()
    }
}
