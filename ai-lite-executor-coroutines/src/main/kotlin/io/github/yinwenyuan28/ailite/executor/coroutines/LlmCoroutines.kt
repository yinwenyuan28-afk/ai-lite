package io.github.yinwenyuan28.ailite.executor.coroutines

import io.github.yinwenyuan28.ailite.llm.LlmCall
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle
import io.github.yinwenyuan28.ailite.llm.LlmGateway
import io.github.yinwenyuan28.ailite.llm.LlmResult
import io.github.yinwenyuan28.ailite.llm.LlmTool
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
