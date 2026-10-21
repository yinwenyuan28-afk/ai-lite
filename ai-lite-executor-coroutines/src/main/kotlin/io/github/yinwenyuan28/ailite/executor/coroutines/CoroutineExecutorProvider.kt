package io.github.yinwenyuan28.ailite.executor.coroutines

import io.github.yinwenyuan28.ailite.executor.ExecutorProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * 基于 Kotlin 协程调度器的执行器。
 *
 * JDK 8 没有虚拟线程，协程是唯一不需要字节码增强的用户级并发方案：
 * 挂起点不占载体线程，少量线程就能承载大量等待中的 LLM 调用。
 *
 * [io] 返回的 Executor 仍然会阻塞载体线程，只适合短任务；真正需要高并发时，
 * 用 [dispatcher] 配合 [LlmCoroutines] 的 suspend 方法，或直接用异步网关。
 *
 * @param parallelism 载体线程数，默认 CPU 核数的两倍，至少 4
 * @param threadNamePrefix 线程名前缀，便于线程 dump
 */
class CoroutineExecutorProvider @JvmOverloads constructor(
    parallelism: Int = max(4, Runtime.getRuntime().availableProcessors() * 2),
    private val threadNamePrefix: String = "ai-lite-coroutine-"
) : ExecutorProvider {

    private val counter = AtomicInteger(1)

    private val pool = Executors.newFixedThreadPool(
        max(1, parallelism),
        ThreadFactory { runnable ->
            Thread(runnable, threadNamePrefix + counter.getAndIncrement()).apply {
                isDaemon = true
            }
        }
    )

    private val coroutineDispatcher: ExecutorCoroutineDispatcher = pool.asCoroutineDispatcher()

    /**
     * @return 有界载体线程池的 Executor 视图
     */
    override fun io(): Executor = coroutineDispatcher.asExecutor()

    /**
     * @return 协程调度器；协程之间通过挂起而不是阻塞来复用载体线程
     */
    fun dispatcher(): CoroutineDispatcher = coroutineDispatcher

    override fun close() {
        coroutineDispatcher.close()
    }
}
