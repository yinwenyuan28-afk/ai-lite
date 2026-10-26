package io.github.yinwenyuan28.ailite.benchmark

import io.github.yinwenyuan28.ailite.config.AiLiteConfig
import io.github.yinwenyuan28.ailite.config.ModelResolver
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider
import io.github.yinwenyuan28.ailite.executor.coroutines.CoroutineExecutorProvider
import io.github.yinwenyuan28.ailite.executor.coroutines.LlmCoroutines
import io.github.yinwenyuan28.ailite.llm.LlmCall
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在相同模拟延迟下比较：
 *
 * 1. 小平台线程池 + 阻塞调用
 * 2. 大平台线程池 + 阻塞调用（用线程数换 QPS）
 * 3. 小协程载体池 + suspend 调用
 *
 * 延迟由“服务端”调度器模拟，不占业务侧线程；这正是 LLM 调用的主要形态。
 *
 * 运行：
 *   mvn -q compile exec:java -Dexec.args="1000 50 8"
 * 参数依次是：总调用数、单次延迟毫秒、协程载体线程数。
 */
fun main(args: Array<String>) {
    val calls = args.getOrNull(0)?.toIntOrNull() ?: 1000
    val latencyMs = args.getOrNull(1)?.toLongOrNull() ?: 50L
    val carriers = args.getOrNull(2)?.toIntOrNull() ?: 8

    require(calls > 0) { "calls must be > 0" }
    require(latencyMs > 0L) { "latencyMs must be > 0" }

    val mx = ManagementFactory.getThreadMXBean()
    println("=".repeat(96))
    println("AI Lite QPS benchmark")
    println("calls=$calls, latency=${latencyMs}ms, coroutine carriers=$carriers")
    println("JVM threads before warmup: ${mx.threadCount}")
    println("=".repeat(96))

    // 预热两条代码路径，避免把类加载和 JIT 算进结果。
    warmup(carriers, latencyMs)

    val results = ArrayList<BenchResult>()
    results.add(runBlockingPool(carriers, calls, latencyMs, "platform-pool-$carriers"))
    // 线程数封顶 1000，避免用 5000 个平台线程把机器压垮；队列会承担超出部分。
    val largePool = minOf(calls, 1000)
    results.add(runBlockingPool(largePool, calls, latencyMs, "platform-pool-$largePool"))
    results.add(runCoroutines(carriers, calls, latencyMs, "coroutines-$carriers"))
    results.add(runJavaAsync(calls, latencyMs, "java-async"))

    printTable(results)
    println()
    println("说明：")
    println("- QPS = 完成调用数 / 墙钟时间；延迟是每次调用自身耗时，不含排队。")
    println("- platform-pool-$largePool 用线程数换并发，线程数会出现在 peakThreads。")
    println("- coroutines-$carriers 只占 $carriers 个载体线程；等待发生在挂起点。")
    println("- java-async 直接用 CompletableFuture，不需要协程；它说明吞吐提升主要来自异步 I/O。")
}

private fun warmup(carriers: Int, latencyMs: Long) {
    val calls = 200
    println("warmup: platform-pool-$carriers x $calls ...")
    runBlockingPool(carriers, calls, latencyMs, "warmup-blocking")
    println("warmup: coroutines-$carriers x $calls ...")
    runCoroutines(carriers, calls, latencyMs, "warmup-coroutines")
    println("warmup done")
    println("-".repeat(96))
}

private fun runBlockingPool(threads: Int, calls: Int, latencyMs: Long, name: String): BenchResult {
    val gateway = gateway(BlockingTransport(latencyMs))
    val pool = Executors.newFixedThreadPool(threads, daemonFactory("bench-blocking-")) as ThreadPoolExecutor
    // 预热载体线程，让 QPS 反映稳态吞吐，而不是线程创建开销。
    pool.prestartAllCoreThreads()
    val done = CountDownLatch(calls)
    val latencies = LongArray(calls)
    val mx = ManagementFactory.getThreadMXBean()
    resetPeak(mx)

    val call = call()
    val started = System.nanoTime()
    for (i in 0 until calls) {
        val index = i
        pool.execute {
            val begin = System.nanoTime()
            gateway.one(call)
            latencies[index] = (System.nanoTime() - begin) / 1_000_000L
            done.countDown()
        }
    }
    done.await()
    val wallNs = System.nanoTime() - started
    val peak = mx.peakThreadCount
    pool.shutdownNow()
    pool.awaitTermination(10, TimeUnit.SECONDS)
    return result(name, calls, wallNs, latencies, peak)
}

private fun runCoroutines(carriers: Int, calls: Int, latencyMs: Long, name: String): BenchResult {    val provider = CoroutineExecutorProvider(carriers)
    val transport = AsyncTransport(latencyMs)
    val gateway = gateway(transport)
    val latencies = LongArray(calls)
    val mx = ManagementFactory.getThreadMXBean()
    resetPeak(mx)

    val call = call()
    val started = System.nanoTime()
    runBlocking(provider.dispatcher()) {
        (0 until calls).map { index ->
            async {
                val begin = System.nanoTime()
                LlmCoroutines.one(gateway, call)
                latencies[index] = (System.nanoTime() - begin) / 1_000_000L
            }
        }.awaitAll()
    }
    val wallNs = System.nanoTime() - started
    val peak = mx.peakThreadCount
    provider.close()
    transport.close()
    // 等载体线程真正退出，避免影响下一个变体的线程峰值统计。
    waitForThreadDrop(mx.threadCount, 500L)
    return result(name, calls, wallNs, latencies, peak)
}

private fun runJavaAsync(calls: Int, latencyMs: Long, name: String): BenchResult {
    val transport = AsyncTransport(latencyMs)
    val gateway = gateway(transport)
    val latencies = LongArray(calls)
    val futures = ArrayList<CompletableFuture<*>>(calls)
    val mx = ManagementFactory.getThreadMXBean()
    resetPeak(mx)

    val call = call()
    val started = System.nanoTime()
    for (i in 0 until calls) {
        val index = i
        val begin = System.nanoTime()
        val future = gateway.oneAsync(call)
        futures.add(future.thenAccept {
            latencies[index] = (System.nanoTime() - begin) / 1_000_000L
        })
    }
    CompletableFuture.allOf(*futures.toTypedArray()).join()
    val wallNs = System.nanoTime() - started
    val peak = mx.peakThreadCount
    transport.close()
    return result(name, calls, wallNs, latencies, peak)
}

private fun result(name: String, calls: Int, wallNs: Long,
                   latencies: LongArray, peakThreads: Int): BenchResult {
    val sorted = latencies.clone()
    sorted.sort()
    val wallMs = wallNs / 1_000_000.0
    return BenchResult(
        name = name,
        calls = calls,
        wallMs = wallMs,
        qps = calls * 1000.0 / wallMs,
        p50Ms = percentile(sorted, 0.50),
        p95Ms = percentile(sorted, 0.95),
        p99Ms = percentile(sorted, 0.99),
        peakThreads = peakThreads
    )
}

private fun percentile(sorted: LongArray, p: Double): Long {
    if (sorted.isEmpty()) {
        return 0L
    }
    val index = ((sorted.size - 1) * p).toInt()
    return sorted[index]
}

private fun printTable(results: List<BenchResult>) {
    println(String.format(Locale.ROOT,
        "%-24s %7s %10s %11s %12s %8s %8s %8s",
        "variant", "calls", "wall(ms)", "QPS", "peakThreads", "p50", "p95", "p99"))
    println("-".repeat(96))
    for (r in results) {
        println(String.format(Locale.ROOT,
            "%-24s %7d %10.1f %11.1f %12d %7dms %7dms %7dms",
            r.name, r.calls, r.wallMs, r.qps, r.peakThreads, r.p50Ms, r.p95Ms, r.p99Ms))
    }
}

private fun gateway(transport: LlmTransport): LlmGateway {
    val llm = AiLiteConfig.Llm()
    llm.bizIds = listOf("bench")
    val model = AiLiteConfig.Model().apply {
        id = "m"
        isReasoning = false
        input = listOf("text")
        maxTokens = 1024
    }
    val provider = AiLiteConfig.Provider().apply {
        api = "fake"
        baseUrl = "http://localhost"
        apiKey = "bench"
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
    LlmCall.profile("bench", "main", listOf(LlmMessage.user("hi")))

private fun completion(): LlmCompletion =
    LlmCompletion("ok", null, LlmStopReason.STOP, LlmUsage.zero(), null)

private fun daemonFactory(prefix: String): ThreadFactory {
    val counter = AtomicInteger(0)
    return ThreadFactory { runnable ->
        Thread(runnable, prefix + counter.incrementAndGet()).apply { isDaemon = true }
    }
}

private fun resetPeak(mx: java.lang.management.ThreadMXBean) {
    try {
        mx.resetPeakThreadCount()
    } catch (ignored: UnsupportedOperationException) {
        // 某些 JVM 不支持，peakThreadCount 退化为 JVM 启动以来的峰值。
    }
}

/**
 * 关闭线程池后短暂等待，避免上一个变体的线程被算进下一个变体的峰值。
 */
private fun waitForThreadDrop(previous: Int, timeoutMs: Long) {
    val mx = ManagementFactory.getThreadMXBean()
    val deadline = System.currentTimeMillis() + timeoutMs
    while (mx.threadCount >= previous && System.currentTimeMillis() < deadline) {
        try {
            Thread.sleep(10L)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
    }
}

/**
 * 阻塞实现：模拟一次 50ms 的模型调用，占住调用线程。
 */
private class BlockingTransport(private val latencyMs: Long) : LlmTransport {
    override fun api(): String = "fake"

    override fun complete(request: LlmRequest): LlmCompletion {
        Thread.sleep(latencyMs)
        return completion()
    }

    override fun completeAsync(request: LlmRequest): CompletableFuture<LlmCompletion> =
        CompletableFuture.supplyAsync { complete(request) }
}

/**
 * 异步实现：由独立调度器在延迟后完成 future，等待期间不占业务线程。
 */
private class AsyncTransport(private val latencyMs: Long) : LlmTransport {
    private val scheduler = Executors.newScheduledThreadPool(1, daemonFactory("bench-server-"))

    override fun api(): String = "fake"

    override fun complete(request: LlmRequest): LlmCompletion =
        throw UnsupportedOperationException("async only")

    override fun completeAsync(request: LlmRequest): CompletableFuture<LlmCompletion> {
        val future = CompletableFuture<LlmCompletion>()
        scheduler.schedule({ future.complete(completion()) }, latencyMs, TimeUnit.MILLISECONDS)
        return future
    }

    fun close() {
        scheduler.shutdownNow()
    }
}

private data class BenchResult(
    val name: String,
    val calls: Int,
    val wallMs: Double,
    val qps: Double,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val peakThreads: Int
)
