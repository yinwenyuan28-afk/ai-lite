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

    public LlmRequest(ResolvedModel model, List<LlmMessage> messages, List<LlmTool> tools,
                      boolean stream, Consumer<String> onDelta, LlmCancelHandle cancel,
                      Integer connectTimeoutMs, Integer readTimeoutMs, Map<String, Object> extraBody) {
        this.model = model;
        this.messages = messages == null ? Collections.<LlmMessage>emptyList() : messages;
        this.tools = tools;
        this.stream = stream;
        this.onDelta = onDelta;
        this.cancel = cancel;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.extraBody = extraBody == null ? Collections.<String, Object>emptyMap() : extraBody;
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
     * @return 是否已经被调用方取消
     */
    public boolean isCancelled() {
        return cancel != null && cancel.isCancelled();
    }
}
