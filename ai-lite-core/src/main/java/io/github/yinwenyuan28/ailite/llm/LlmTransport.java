package io.github.yinwenyuan28.ailite.llm;

import java.util.concurrent.CompletableFuture;

/**
 * 协议适配器 SPI。每种 {@code api} 对应一个实现，例如 openai-completions。
 */
public interface LlmTransport {

    /**
     * @return 配置里使用的协议名
     */
    String api();

    /**
     * 执行一次请求。流式时由实现负责调用 {@link LlmRequest#getOnDelta()}。
     *
     * @param request 已解析的请求
     * @return 归一化结果
     * @throws LlmCallException 请求失败、响应无法解析或已取消时抛出
     */
    LlmCompletion complete(LlmRequest request);

    /**
     * 非阻塞执行。默认把阻塞调用放到 ForkJoinPool，内置适配器在有
     * {@code AsyncHttpExecutor} 时会覆盖为真正的非阻塞路径。
     *
     * @param request 已解析的请求
     * @return 结果 future
     */
    default CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
        return CompletableFuture.supplyAsync(() -> complete(request));
    }
}
