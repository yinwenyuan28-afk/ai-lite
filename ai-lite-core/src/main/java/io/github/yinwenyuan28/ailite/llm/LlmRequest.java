package io.github.yinwenyuan28.ailite.llm;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 一次协议适配器调用所需的全部信息。HTTP 细节不在这个对象里，由 {@code HttpExecutor} 负责。
 */
public final class LlmRequest {

    private final ResolvedModel model;
    private final List<LlmMessage> messages;
    private final List<LlmTool> tools;
    private final boolean stream;
    private final Consumer<String> onDelta;
    private final LlmCancelHandle cancel;
    private final Integer connectTimeoutMs;
    private final Integer readTimeoutMs;
    private final Map<String, Object> extraBody;
    /** 本次调用的输出 token 上限覆盖；null 表示沿用模型配置。 */
    private final Integer maxTokens;
    /** 类型化增量通道；null 表示退回无类型的 onDelta。 */
    private final LlmDeltaSink deltaSink;

    public LlmRequest(ResolvedModel model, List<LlmMessage> messages, List<LlmTool> tools,
                      boolean stream, Consumer<String> onDelta, LlmCancelHandle cancel,
                      Integer connectTimeoutMs, Integer readTimeoutMs, Map<String, Object> extraBody) {
        this(model, messages, tools, stream, onDelta, cancel, connectTimeoutMs, readTimeoutMs,
                extraBody, null, null);
    }

    /**
     * @param maxTokens 输出 token 上限覆盖；null 表示沿用模型配置
     */
    public LlmRequest(ResolvedModel model, List<LlmMessage> messages, List<LlmTool> tools,
                      boolean stream, Consumer<String> onDelta, LlmCancelHandle cancel,
                      Integer connectTimeoutMs, Integer readTimeoutMs, Map<String, Object> extraBody,
                      Integer maxTokens) {
        this(model, messages, tools, stream, onDelta, cancel, connectTimeoutMs, readTimeoutMs,
                extraBody, maxTokens, null);
    }

    /**
     * @param maxTokens  输出 token 上限覆盖；null 表示沿用模型配置
     * @param deltaSink  类型化增量通道；非 null 时传输层不再推 {@code "\n\n"} 分隔符
     */
    public LlmRequest(ResolvedModel model, List<LlmMessage> messages, List<LlmTool> tools,
                      boolean stream, Consumer<String> onDelta, LlmCancelHandle cancel,
                      Integer connectTimeoutMs, Integer readTimeoutMs, Map<String, Object> extraBody,
                      Integer maxTokens, LlmDeltaSink deltaSink) {
        this.model = model;
        this.messages = messages == null ? Collections.<LlmMessage>emptyList() : messages;
        this.tools = tools;
        this.stream = stream;
        this.onDelta = onDelta;
        this.cancel = cancel;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.extraBody = extraBody == null ? Collections.<String, Object>emptyMap() : extraBody;
        this.maxTokens = maxTokens;
        this.deltaSink = deltaSink;
    }

    public ResolvedModel getModel() {
        return model;
    }

    public List<LlmMessage> getMessages() {
        return messages;
    }

    public List<LlmTool> getTools() {
        return tools;
    }

    public boolean isStream() {
        return stream;
    }

    public Consumer<String> getOnDelta() {
        return onDelta;
    }

    public LlmCancelHandle getCancel() {
        return cancel;
    }

    /**
     * @return 本次调用的连接超时；未覆盖时使用供应商配置
     */
    public int effectiveConnectTimeoutMs() {
        return connectTimeoutMs == null ? model.getConnectTimeoutMs() : connectTimeoutMs.intValue();
    }

    /**
     * @return 本次调用的读取超时；未覆盖时使用供应商配置
     */
    public int effectiveReadTimeoutMs() {
        return readTimeoutMs == null ? model.getReadTimeoutMs() : readTimeoutMs.intValue();
    }

    public Map<String, Object> getExtraBody() {
        return extraBody;
    }

    /**
     * @return 本次调用实际使用的输出 token 上限；未覆盖时取模型配置
     */
    public int effectiveMaxTokens() {
        if (maxTokens != null && maxTokens.intValue() > 0) {
            return maxTokens.intValue();
        }
        return model.getMaxTokens();
    }

    /**
     * @return 输出 token 上限覆盖值；null 表示未覆盖
     */
    public Integer getMaxTokensOverride() {
        return maxTokens;
    }

    /**
     * @return 类型化增量通道；null 表示未启用
     */
    public LlmDeltaSink getDeltaSink() {
        return deltaSink;
    }

    /**
     * @return 是否已经被调用方取消
     */
    public boolean isCancelled() {
        return cancel != null && cancel.isCancelled();
    }
}
