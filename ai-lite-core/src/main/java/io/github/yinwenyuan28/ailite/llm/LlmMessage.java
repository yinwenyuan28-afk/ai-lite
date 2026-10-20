package io.github.yinwenyuan28.ailite.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条结构化消息。助手正文如果包含思考，已经由传输层拼在 text 前面。
 *
 * <p>图片和工具调用在构造时复制成只读列表，避免调用方后续修改影响已发出的请求。</p>
 */
public final class LlmMessage {

    private final LlmRole role;
    private final String text;
    private final List<LlmImage> images;
    private final List<LlmToolCall> toolCalls;
    private final String toolCallId;

    private LlmMessage(LlmRole role, String text, List<LlmImage> images,
                       List<LlmToolCall> toolCalls, String toolCallId) {
        this.role = role;
        this.text = text == null ? "" : text;
        this.images = images == null
                ? Collections.<LlmImage>emptyList()
                : Collections.unmodifiableList(new ArrayList<LlmImage>(images));
        this.toolCalls = toolCalls == null
                ? Collections.<LlmToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<LlmToolCall>(toolCalls));
        this.toolCallId = toolCallId;
    }

    /**
     * 系统消息。
     *
     * @param text 规则文本
     * @return 消息对象
     */
    public static LlmMessage system(String text) {
        return new LlmMessage(LlmRole.SYSTEM, text, null, null, null);
    }

    /**
     * 纯文本用户消息。
     *
     * @param text 用户文本
     * @return 消息对象
     */
    public static LlmMessage user(String text) {
        return new LlmMessage(LlmRole.USER, text, null, null, null);
    }

    /**
     * 带图片的用户消息。
     *
     * @param text   用户文本
     * @param images 图片列表
     * @return 消息对象
     */
    public static LlmMessage user(String text, List<LlmImage> images) {
        return new LlmMessage(LlmRole.USER, text, images, null, null);
    }

    /**
     * 助手消息。工具循环中用于把模型返回的正文和工具调用写回上下文。
     *
     * @param text      助手正文
     * @param toolCalls 工具调用列表，可为 null
     * @return 消息对象
     */
    public static LlmMessage assistant(String text, List<LlmToolCall> toolCalls) {
        return new LlmMessage(LlmRole.ASSISTANT, text, null, toolCalls, null);
    }

    /**
     * 工具结果消息。
     *
     * @param toolCallId 对应的工具调用 id
     * @param text       工具返回给模型的文本
     * @return 消息对象
     */
    public static LlmMessage toolResult(String toolCallId, String text) {
        return new LlmMessage(LlmRole.TOOL_RESULT, text, null, null, toolCallId);
    }

    /**
     * 其他角色。出站前会折叠成用户文本。
     *
     * @param text 文本
     * @return 消息对象
     */
    public static LlmMessage other(String text) {
        return new LlmMessage(LlmRole.OTHER, text, null, null, null);
    }

    /**
     * 带图片的其他角色消息。
     *
     * @param text   文本
     * @param images 图片列表
     * @return 消息对象
     */
    public static LlmMessage other(String text, List<LlmImage> images) {
        return new LlmMessage(LlmRole.OTHER, text, images, null, null);
    }

    public LlmRole getRole() {
        return role;
    }

    public String getText() {
        return text;
    }

    public List<LlmImage> getImages() {
        return images;
    }

    public List<LlmToolCall> getToolCalls() {
        return toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }
}
