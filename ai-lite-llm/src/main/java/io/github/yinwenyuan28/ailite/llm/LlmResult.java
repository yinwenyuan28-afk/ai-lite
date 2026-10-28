package io.github.yinwenyuan28.ailite.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次调用的结果。
 *
 * <p>{@code content} 只放可见正文，{@code thinkingText} 单独保留推理原文，
 * {@code thinkingSignature} 保留需要回传的推理签名（Anthropic）。
 * {@code rounds} 是实际请求模型的轮数，{@code latencyMs} 是各轮耗时之和。</p>
 */
public final class LlmResult {

    private final String content;
    private final String thinkingText;
    private final String thinkingSignature;
    private final LlmStopReason stopReason;
    private final LlmUsage usage;
    private final String provider;
    private final String modelId;
    private final LlmThinkingLevel thinkingLevel;
    private final long latencyMs;
    private final int rounds;
    private final List<LlmToolCall> toolCalls;

    public LlmResult(String content, String thinkingText, LlmStopReason stopReason, LlmUsage usage,
                     String provider, String modelId, LlmThinkingLevel thinkingLevel,
                     long latencyMs, int rounds, List<LlmToolCall> toolCalls) {
        this(content, thinkingText, null, stopReason, usage, provider, modelId, thinkingLevel,
                latencyMs, rounds, toolCalls);
    }

    public LlmResult(String content, String thinkingText, String thinkingSignature,
                     LlmStopReason stopReason, LlmUsage usage,
                     String provider, String modelId, LlmThinkingLevel thinkingLevel,
                     long latencyMs, int rounds, List<LlmToolCall> toolCalls) {
        this.content = content == null ? "" : content;
        this.thinkingText = thinkingText;
        this.thinkingSignature = thinkingSignature;
        this.stopReason = stopReason;
        this.usage = usage == null ? LlmUsage.zero() : usage;
        this.provider = provider;
        this.modelId = modelId;
        this.thinkingLevel = thinkingLevel;
        this.latencyMs = latencyMs;
        this.rounds = rounds;
        this.toolCalls = toolCalls == null
                ? Collections.<LlmToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<LlmToolCall>(toolCalls));
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

    /**
     * 调试/兼容用：把推理和可见正文按 {@code thinking + "\n\n" + content} 拼起来。
     *
     * @return 拼接后的文本
     */
    public String getRawContent() {
        return LlmTexts.splice(thinkingText, content);
    }

    public LlmStopReason getStopReason() {
        return stopReason;
    }

    public LlmUsage getUsage() {
        return usage;
    }

    public String getProvider() {
        return provider;
    }

    public String getModelId() {
        return modelId;
    }

    public LlmThinkingLevel getThinkingLevel() {
        return thinkingLevel;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public int getRounds() {
        return rounds;
    }

    public List<LlmToolCall> getToolCalls() {
        return toolCalls;
    }
}
