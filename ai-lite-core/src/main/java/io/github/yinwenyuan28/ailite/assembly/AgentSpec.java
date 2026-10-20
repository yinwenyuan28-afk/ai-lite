package io.github.yinwenyuan28.ailite.assembly;

/**
 * 一套已经排好序的 AgentLifecycle。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface AgentSpec<I, O> {

    /**
     * @param agentLoop Agent 循环
     * @param input     业务输入
     * @return 业务输出
     */
    O run(AgentLoop agentLoop, I input);
}
