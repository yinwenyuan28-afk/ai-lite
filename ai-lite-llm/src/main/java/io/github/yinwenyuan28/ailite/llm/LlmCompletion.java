package io.github.yinwenyuan28.ailite.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次协议往返的归一化结果。三种协议都必须转换成这个对象。
 *
 * <p>{@code content} 只放可见正文，{@code thinkingText} 单独放推理原文，
 * 不再把两者拼接到一起。</p>
 */
public final class LlmCompletion {

    private final String content;
    private final String thinkingText;
    private final String thinkingSignature;
    private final LlmStopReason stop;
    private final LlmUsage usage;
    private final List<LlmToolCall> toolCalls;

    public LlmCompletion(String content, String thinkingText, LlmStopReason stop,
                         LlmUsage usage, List<LlmToolCall> toolCalls) {
        this(content, thinkingText, null, stop, usage, toolCalls);
    }

    public LlmCompletion(String content, String thinkingText, String thinkingSignature,
                         LlmStopReason stop, LlmUsage usage, List<LlmToolCall> toolCalls) {
        this.content = content == null ? "" : content;
        this.thinkingText = thinkingText;
        this.thinkingSignature = thinkingSignature;
        this.stop = stop == null ? LlmStopReason.STOP : stop;
        this.usage = usage == null ? LlmUsage.zero() : usage;
        this.toolCalls = toolCalls == null
                ? Collections.<LlmToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<LlmToolCall>(toolCalls));
    }

    /**
     * 取消时的结果。
     *
     * @param content      已经收到的正文
     * @param thinkingText 已经收到的推理
     * @return 取消结果
     */
    public static LlmCompletion aborted(String content, String thinkingText) {
        return new LlmCompletion(content, thinkingText, LlmStopReason.ABORTED, LlmUsage.zero(), null);
    }

    /**
     * @return 可见正文，不含推理
     */
    public String getContent() {
        return content;
    }

    /**
     * @return 推理原文，可为 null
     */
    public String getThinkingText() {
        return thinkingText;
    }

    /**
     * @return 推理签名（Anthropic），可为 null
     */
    public String getThinkingSignature() {
        return thinkingSignature;
    }

    public LlmStopReason getStop() {
        return stop;
    }

    public LlmUsage getUsage() {
        return usage;
    }

    public List<LlmToolCall> getToolCalls() {
        return toolCalls;
    }
}
