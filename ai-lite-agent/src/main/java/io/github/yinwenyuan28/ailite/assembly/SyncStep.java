package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmResult;

/**
 * 同步步骤。执行会阻塞调用线程，直到步骤完成。
 *
 * <p>{@link Workflow#run(java.util.List)} 只接受本类型。异步流程请实现
 * {@link AsyncStep}；需要在异步流程里复用同步步骤时，必须显式使用
 * {@link Workflow#adapt(SyncStep)}。</p>
 */
public interface SyncStep {

    /**
     * 执行步骤。
     *
     * @param gateway 模型网关
     * @return 步骤结果
     */
    LlmResult execute(LlmGateway gateway);

    /**
     * 执行步骤，并把取消句柄透传给底层调用。
     *
     * <p>默认委托旧方法；忽略句柄的自定义步骤只保证步骤边界 L2，不保证在途 L1。</p>
     *
     * @param gateway 模型网关
     * @param cancel  取消句柄，可为 null
     * @return 步骤结果
     */
    default LlmResult execute(LlmGateway gateway, LlmCancelHandle cancel) {
        return execute(gateway);
    }
}
