package io.github.yinwenyuan28.ailite.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次调用的结果。
 *
 * <p>{@code content} 在有推理时是「推理、空行、正文」；{@code thinkingText} 单独保留推理原文。
 * {@code rounds} 是实际请求模型的轮数，{@code latencyMs} 是各轮耗时之和。</p>
 */
public final class LlmResult {

    private final String content;
    private final String thinkingText;
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
        this.content = content == null ? "" : content;
        this.thinkingText = thinkingText;
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

    public String getContent() {
        return content;
    }

    public String getThinkingText() {
        return thinkingText;
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
