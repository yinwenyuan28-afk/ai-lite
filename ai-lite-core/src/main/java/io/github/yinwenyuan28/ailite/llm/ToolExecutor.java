package io.github.yinwenyuan28.ailite.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 工具批量执行 SPI。
 *
 * <p>默认实现串行。未来并行实现必须保证返回列表与入参 calls 顺序一致，
 * 这样工具结果才能按助手消息里的原始顺序写回。</p>
 */
public interface ToolExecutor {

    /**
     * 执行一批工具调用。
     *
     * @param tools 本次可用工具
     * @param calls 模型给出的调用，按原顺序
     * @return 与 calls 等长的结果文本，顺序一致
     */
    List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls);

    /**
     * 异步执行。默认把阻塞实现交给 ForkJoinPool，需要的实现可以覆盖。
     *
     * @param tools 本次可用工具
     * @param calls 模型给出的调用，按原顺序
     * @return 结果 future
     */
    default CompletableFuture<List<String>> executeAsync(List<LlmTool> tools, List<LlmToolCall> calls) {
        return CompletableFuture.supplyAsync(() -> execute(tools, calls));
    }
}
