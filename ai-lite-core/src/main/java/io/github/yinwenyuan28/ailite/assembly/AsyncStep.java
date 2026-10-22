package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmResult;

import java.util.concurrent.CompletableFuture;

/**
 * 异步步骤。{@link #executeAsync} 必须快速返回 future，不能阻塞调用线程。
 *
 * <p>{@link Workflow#runAsync(java.util.List, LlmCancelHandle)} 只接受本类型。
 * 不要在实现里调用同步的 {@code gateway.one} 或 {@code Thread.sleep}；需要复用同步
 * 步骤时，使用 {@link Workflow#adapt(SyncStep)} 显式声明阻塞成本。</p>
 */
public interface AsyncStep {

    /**
     * 执行步骤。
     *
     * @param gateway 模型网关
     * @param cancel  本次步骤专用的取消句柄
     * @return 结果 future
     */
    CompletableFuture<LlmResult> executeAsync(LlmGateway gateway, LlmCancelHandle cancel);
}
