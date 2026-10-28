package io.github.yinwenyuan28.ailite.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 异步单工具执行 SPI（AgentLoop 的 AL-A2，无行业先例的自有设计）。
 *
 * <p>{@code AgentLoop.runAsync} 遇到该类型时逐工具走 {@link #executeOneAsync}，
 * 串行组合成异步链，不再把阻塞调用丢进载体池；同步 {@code run} 仍走继承的
 * {@link ToolExecutor#execute}，行为不变。</p>
 *
 * <p>契约与 {@link ToolExecutor} 一致：实现必须自行捕获单个工具的业务异常并转成
 * 文本返回；抛出异常或返回空 future 视为执行器级故障，会终止整个 run。</p>
 */
public interface AsyncToolExecutor extends ToolExecutor {

    /**
     * 异步执行单个工具调用。
     *
     * @param tools   本次可用工具
     * @param call    单个调用（参数可能已被 beforeTool 钩子改写）
     * @param roundNo 当前轮次
     * @return 结果文本 future
     */
    CompletableFuture<String> executeOneAsync(List<LlmTool> tools, LlmToolCall call, int roundNo);
}
