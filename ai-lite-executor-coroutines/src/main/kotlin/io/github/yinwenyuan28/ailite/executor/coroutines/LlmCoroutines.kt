package io.github.yinwenyuan28.ailite.executor.coroutines

import io.github.yinwenyuan28.ailite.llm.LlmCall
import io.github.yinwenyuan28.ailite.llm.LlmCallException
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle
import io.github.yinwenyuan28.ailite.llm.LlmGateway
import io.github.yinwenyuan28.ailite.llm.LlmResult
import io.github.yinwenyuan28.ailite.llm.LlmTool
import io.github.yinwenyuan28.ailite.llm.RetryPolicy
import io.github.yinwenyuan28.ailite.internal.llm.RetrySupport
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
        val startNanos = System.nanoTime()
        val deadlineNanos = RetrySupport.deadlineNanos(startNanos, policy.totalDeadlineMs)
        val failures = ArrayList<LlmCallException>()
        var attempt = 1
        while (true) {
            if (cancel?.isCancelled == true) {
                return gateway.oneAsync(call, cancel).await()
            }
            if (RetrySupport.deadlineReached(deadlineNanos, System.nanoTime())) {
                throw RetrySupport.deadlineFailure(failures)
            }
            try {
                // 每次尝试一份独立句柄，外层取消经 abort 槽转发；避免上一轮
                // 传输的迟到解绑擦掉下一轮的中断动作（F-03）。
                return gateway.oneAsync(call, freshAttemptCancel(cancel)).await()
            } catch (ex: LlmCallException) {
                failures.add(ex)
                if (!policy.shouldRetry(ex) || attempt >= policy.maxAttempts) {
                    throw RetrySupport.withSuppressed(ex, failures)
                }
                if (cancel?.isCancelled == true) {
                    return gateway.oneAsync(call, cancel).await()
                }
                // 退避口径与同步、异步两个 Java 入口共用 RetrySupport.backoffMs（F-13）：
                // 服务端 Retry-After 优先，受 maxBackoffMs 约束。此前这里只按
                // policy.delayMs 指数退避，429 下会按 1/2/4 秒猛打。
                var wait = RetrySupport.backoffMs(policy, ex, attempt)
                if (deadlineNanos != null) {
                    val now = System.nanoTime()
                    if (RetrySupport.deadlineReached(deadlineNanos, now)) {
                        throw RetrySupport.deadlineFailure(failures)
                    }
                    wait = RetrySupport.clampDelayMs(wait, deadlineNanos, now)
                }
                policy.notifyRetry(attempt, wait, ex)
                delay(wait)
                attempt++
            }
        }
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

    private fun freshAttemptCancel(outer: LlmCancelHandle?): LlmCancelHandle? {
        if (outer == null) {
            return null
        }
        val fresh = LlmCancelHandle()
        // 外层取消 → 当前尝试的句柄。
        outer.bindAbort { fresh.cancel() }
        // 反向传播：Job 取消 / 网关收敛让本次尝试的句柄被取消时，外层句柄也要
        // 进入取消态，循环开头的 isCancelled 检查才能拦住后续尝试（L2 契约）。
        fresh.onCancel { outer.cancel() }
        return fresh
    }
}
