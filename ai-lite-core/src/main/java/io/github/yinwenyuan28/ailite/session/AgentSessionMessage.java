package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmTexts;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.util.Strings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 会话线消息。比 LlmMessage 多稳定 id 与可选的 thinking 字段；不存图片。
 * {@code text} 只放可见正文，{@code thinkingText} 单独放推理原文。
 */
public final class AgentSessionMessage {

    private final String id;
    private final LlmRole role;
    private final String text;
    private final String thinkingText;
    private final String thinkingSignature;
    private final List<LlmToolCall> toolCalls;
    private final String toolCallId;

    private AgentSessionMessage(String id, LlmRole role, String text, String thinkingText,
                                String thinkingSignature, List<LlmToolCall> toolCalls,
                                String toolCallId) {
        this.id = id;
        this.role = role;
        this.text = text == null ? "" : text;
        this.thinkingText = Strings.isBlank(thinkingText) ? null : thinkingText;
        this.thinkingSignature = Strings.isBlank(thinkingSignature) ? null : thinkingSignature;
        this.toolCalls = toolCalls == null
                ? Collections.<LlmToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<LlmToolCall>(toolCalls));
        this.toolCallId = toolCallId;
    }

    public static AgentSessionMessage system(String text) {
        return new AgentSessionMessage(newId(), LlmRole.SYSTEM, text, null, null, null, null);
    }

    public static AgentSessionMessage user(String text) {
        return new AgentSessionMessage(newId(), LlmRole.USER, text, null, null, null, null);
    }

    public static AgentSessionMessage assistant(String text, String thinkingText,
                                                List<LlmToolCall> toolCalls) {
        return assistant(text, thinkingText, null, toolCalls);
    }

    public static AgentSessionMessage assistant(String text, String thinkingText,
                                                String thinkingSignature,
                                                List<LlmToolCall> toolCalls) {
        return new AgentSessionMessage(newId(), LlmRole.ASSISTANT, text, thinkingText,
                thinkingSignature, toolCalls, null);
    }

    public static AgentSessionMessage toolResult(String toolCallId, String text) {
        return new AgentSessionMessage(newId(), LlmRole.TOOL_RESULT, text, null, null, null, toolCallId);
    }

    /**
     * 从一轮模型结果建 assistant。thinking 用独立字段；可见正文对 hook 后 content 做对称剥分。
     *
     * @param thinkingText   推理原文
     * @param hookedContent  hook 之后的正文（可能是 splice 形态）
     * @param toolCalls      工具调用
     * @return 会话消息
     */
    public static AgentSessionMessage fromResult(String thinkingText, String hookedContent,
                                                 List<LlmToolCall> toolCalls) {
        return fromResult(thinkingText, null, hookedContent, toolCalls);
    }

    /**
     * 从一轮模型结果建 assistant，保留 thinking 签名。
     *
     * @param thinkingText      推理原文
     * @param thinkingSignature 推理签名
     * @param hookedContent     hook 之后的正文（可能是 splice 形态）
     * @param toolCalls         工具调用
     * @return 会话消息
     */
    public static AgentSessionMessage fromResult(String thinkingText, String thinkingSignature,
                                                 String hookedContent,
                                                 List<LlmToolCall> toolCalls) {
        String thinking = Strings.isBlank(thinkingText) ? null : thinkingText;
        String visible = peelVisible(thinking, hookedContent);
        return assistant(visible, thinking, thinkingSignature, toolCalls);
    }

    /**
     * 反序列化或测试用：保留给定 id。
     */
    public static AgentSessionMessage of(String id, LlmRole role, String text, String thinkingText,
                                         List<LlmToolCall> toolCalls, String toolCallId) {
        return of(id, role, text, thinkingText, null, toolCalls, toolCallId);
    }

    /**
     * 反序列化或测试用：保留给定 id 和 thinking 签名。
     */
    public static AgentSessionMessage of(String id, LlmRole role, String text, String thinkingText,
                                         String thinkingSignature, List<LlmToolCall> toolCalls,
                                         String toolCallId) {
        return new AgentSessionMessage(id, role, text, thinkingText, thinkingSignature,
                toolCalls, toolCallId);
    }

    static String peelVisible(String thinking, String content) {
        String body = content == null ? "" : content;
        if (Strings.isBlank(thinking)) {
            return body;
        }
        if (body.equals(thinking)) {
            return "";
        }
        String prefix = thinking + "\n\n";
        if (body.startsWith(prefix)) {
            return body.substring(prefix.length());
        }
        return body;
    }

    /**
     * 调试用：把 thinking 与 text 再拼回 splice 形态。
     *
     * @return 拼接后的正文
     */
    public String splicedText() {
        return LlmTexts.splice(thinkingText, text);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }

    public LlmRole getRole() {
        return role;
    }

    /**
     * @return 可见正文，不含 thinking
     */
    public String getText() {
        return text;
    }

    /**
     * @return 推理原文，可为 null
     */
    public String getThinkingText() {
        return thinkingText;
    }

    /**
     * @return 推理签名，可为 null
     */
    public String getThinkingSignature() {
        return thinkingSignature;
    }

    public List<LlmToolCall> getToolCalls() {
        return toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }
}
