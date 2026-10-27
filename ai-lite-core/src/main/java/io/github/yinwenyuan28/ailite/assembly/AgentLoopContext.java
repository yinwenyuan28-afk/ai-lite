package io.github.yinwenyuan28.ailite.assembly;

import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmTool;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 Agent loop 送给模型的消息、工具名单和取消句柄。
 *
 * <p>消息列表和工具名单是本次循环里的活列表，生命周期可以直接修改。</p>
 */
public final class AgentLoopContext {

    private final String bizId;
    private final List<LlmMessage> messages;
    private final List<LlmTool> tools;
    private final LlmCancelHandle cancel;
    /**
     * 本轮工具结果字符上限；0 表示不限。
     *
     * <p>放在上下文里而不是构造器参数，是为了让编排入口按调用方参数覆盖，
     * 同时不改变 {@code AgentLoopContext.copy} 的签名（那是 39 处调用点的前提）。</p>
     */
    private int toolResultMaxChars;

    AgentLoopContext(String bizId, List<LlmMessage> messages, List<LlmTool> tools, LlmCancelHandle cancel) {
        this.bizId = bizId;
        this.messages = messages;
        this.tools = tools;
        this.cancel = cancel;
    }

    static AgentLoopContext copy(String bizId, List<LlmMessage> messages, List<LlmTool> tools,
                                 LlmCancelHandle cancel) {
        List<LlmMessage> copiedMessages = new ArrayList<LlmMessage>();
        if (messages != null) {
            copiedMessages.addAll(messages);
        }
        List<LlmTool> copiedTools = new ArrayList<LlmTool>();
        if (tools != null) {
            copiedTools.addAll(tools);
        }
        return new AgentLoopContext(bizId, copiedMessages, copiedTools, cancel);
    }

    public String getBizId() {
        return bizId;
    }

    public List<LlmMessage> getMessages() {
        return messages;
    }

    public List<LlmTool> getTools() {
        return tools;
    }

    /**
     * 替换本次循环可用的工具。
     *
     * @param replacement 新工具列表，可为 null
     */
    public void setTools(List<LlmTool> replacement) {
        tools.clear();
        if (replacement != null) {
            tools.addAll(replacement);
        }
    }

    public LlmCancelHandle getCancel() {
        return cancel;
    }

    /**
     * @return 本轮工具结果字符上限；0 表示不限
     */
    public int getToolResultMaxChars() {
        return toolResultMaxChars;
    }

    /**
     * 设置本轮工具结果字符上限。
     *
     * @param toolResultMaxChars 上限字符数；0 表示不限
     */
    public void setToolResultMaxChars(int toolResultMaxChars) {
        this.toolResultMaxChars = toolResultMaxChars;
    }

    public boolean isCancelled() {
        return cancel != null && cancel.isCancelled();
    }
}
