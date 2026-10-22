package io.github.yinwenyuan28.ailite.executor.coroutines

import io.github.yinwenyuan28.ailite.llm.LlmCall
import io.github.yinwenyuan28.ailite.llm.LlmCallException
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle
import io.github.yinwenyuan28.ailite.llm.LlmGateway
import io.github.yinwenyuan28.ailite.llm.LlmResult
import io.github.yinwenyuan28.ailite.llm.LlmTool
import io.github.yinwenyuan28.ailite.llm.RetryPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import java.util.concurrent.CompletableFuture

/**
 * 网关的 suspend 桥接。
 *
 * Java 调用方可以直接用 [LlmGateway] 的 `*Async` 方法；Kotlin 调用方用这里的
 * suspend 方法，取消协程会同步取消底层 CompletableFuture，再触发 HTTP 中断。
 */
object LlmCoroutines {

    /**
     * 单次调用。
     *
     * @param gateway 网关
     * @param call 调用参数
     * @param cancel 取消句柄，可为 null
     * @return 调用结果
     */
    @JvmStatic
    @JvmOverloads
    suspend fun one(gateway: LlmGateway, call: LlmCall,
                    cancel: LlmCancelHandle? = null): LlmResult =
        gateway.oneAsync(call, cancel).await()

    /**
     * 单次调用 + 显式重试瞬时错误。
     *
     * <p>与 Java 侧 [LlmGateway.oneWithRetry] 共用 [RetryPolicy]；这里用
     * [gateway.oneAsync] + [await] 发起尝试，用 [delay] 做退避，因此退避期间
     * 不占载体线程，`Job.cancel()` / `withTimeout` 可以直接打断。</p>
     *
     * @param gateway 网关
     * @param call 调用参数
     * @param policy 重试策略
     * @param cancel 取消句柄，可为 null
     * @return 调用结果
     */
    @JvmStatic
    @JvmOverloads
    suspend fun oneWithRetry(gateway: LlmGateway, call: LlmCall,
                             policy: RetryPolicy,
                             cancel: LlmCancelHandle? = null): LlmResult {
        val started = System.currentTimeMillis()
        val failures = ArrayList<LlmCallException>()
        var attempt = 1
        while (true) {
            if (cancel?.isCancelled == true) {
                return gateway.oneAsync(call, cancel).await()
            }
            val deadline = policy.totalDeadlineMs
            if (deadline != null
                && System.currentTimeMillis() - started >= deadline) {
                throw deadlineExceeded(failures)
            }
            try {
                return gateway.oneAsync(call, cancel).await()
            } catch (ex: LlmCallException) {
                failures.add(ex)
                if (!policy.shouldRetry(ex) || attempt >= policy.maxAttempts) {
                    throw withSuppressed(ex, failures)
                }
                if (cancel?.isCancelled == true) {
                    return gateway.oneAsync(call, cancel).await()
                }
                var wait = policy.delayMs(attempt)
                if (deadline != null) {
                    val remaining = deadline - (System.currentTimeMillis() - started)
                    if (remaining <= 0L) {
                        throw deadlineExceeded(failures)
                    }
                    wait = minOf(wait, remaining)
                }
                policy.notifyRetry(attempt, wait, ex)
                delay(wait)
                attempt++
            }
        }
    }

    private fun withSuppressed(last: LlmCallException,
                               failures: List<LlmCallException>): LlmCallException {
        for (i in 0 until failures.size - 1) {
            val failure = failures[i]
            if (failure !== last) {
                last.addSuppressed(failure)
            }
        }
        return last
    }

    private fun deadlineExceeded(failures: List<LlmCallException>): LlmCallException {
        if (failures.isEmpty()) {
            return LlmCallException("重试超过总时限", null,
                LlmCallException.Kind.TRANSPORT)
        }
        val last = failures[failures.size - 1]
        val ex = LlmCallException("重试超过总时限", last, last.kind, last.statusCode)
        for (i in 0 until failures.size - 1) {
            ex.addSuppressed(failures[i])
        }
        return ex
    }

    /**
     * 单轮工具声明。
     *
     * @param gateway 网关
     * @param call 调用参数
     * @param tools 工具列表
     * @param cancel 取消句柄，可为 null
     * @return 调用结果
     */
    @JvmStatic
    @JvmOverloads
    suspend fun round(gateway: LlmGateway, call: LlmCall, tools: List<LlmTool>,
                      cancel: LlmCancelHandle? = null): LlmResult =
        gateway.roundAsync(call, tools, cancel).await()

    /**
     * 工具循环。
     *
     * @param gateway 网关
     * @param call 调用参数
     * @param tools 工具列表
     * @param maxRounds 最大轮数，null 表示 8
     * @param cancel 取消句柄，可为 null
     * @return 最后一轮结果
     */
    @JvmStatic
    @JvmOverloads
    suspend fun toolLoop(gateway: LlmGateway, call: LlmCall, tools: List<LlmTool>,
                         maxRounds: Int? = null,
                         cancel: LlmCancelHandle? = null): LlmResult =
        gateway.toolLoopAsync(call, tools, maxRounds, cancel).await()

    /**
     * Java 侧的非阻塞入口，保留静态方法形式。
     */
    @JvmStatic
    fun oneAsync(gateway: LlmGateway, call: LlmCall,
                 cancel: LlmCancelHandle? = null): CompletableFuture<LlmResult> =
        gateway.oneAsync(call, cancel)
}
