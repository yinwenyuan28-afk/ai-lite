package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.llm.LlmResult;

/**
 * Agent loop 的生命周期。多个实现按传入顺序执行。
 *
 * <p>不覆盖的方法保持默认：放行工具、不改文本、这一轮交给模型自己的停止原因。</p>
 */
public interface AgentLifecycle {

    /** 整个循环开始，只一次。可以改消息和本次工具名单。 */
    default void beforeRun(AgentLoopContext ctx) {
    }

    /** 每一轮发出去之前。可以改消息和本次工具名单。 */
    default void beforeModel(AgentLoopContext ctx) {
    }

    /** 这一轮模型已返回，工具还没执行。可以改助手正文。 */
    default void afterModel(AgentLoopContext ctx, AgentModelOutput output) {
    }

    /** 某个工具执行前。可以改参数或拒绝这次调用。 */
    default ToolDecision beforeTool(AgentLoopContext ctx, AgentToolCall call) {
        return ToolDecision.allow();
    }

    /** 某个工具已经执行。返回交回模型的文本；返回 null 表示沿用当前文本。 */
    default String afterTool(AgentLoopContext ctx, AgentToolCall call, String resultText) {
        return resultText;
    }

    /** 这一轮的工具都处理完。决定停止、再来一轮，或交给模型。 */
    default TurnDecision afterTurn(AgentLoopContext ctx, LlmResult roundResult) {
        return TurnDecision.modelDecides();
    }

    /** 循环已经停。这里再改上下文也不会再发请求。 */
    default void afterRun(AgentLoopContext ctx, LlmResult lastResult) {
    }
}
