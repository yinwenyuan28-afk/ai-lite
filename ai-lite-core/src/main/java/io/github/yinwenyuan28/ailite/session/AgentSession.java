package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmTexts;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 持久化会话快照。version 为调用方读到的当前值；成功 save 后存储侧为 version+1。
 */
public final class AgentSession {

    private String userId;
    private String sessionId;
    private String bizId;
    private List<AgentSessionMessage> messages = new ArrayList<AgentSessionMessage>();
    private long version;
    private Date updatedAt;

    public AgentSession() {
    }

    public AgentSession(String userId, String sessionId, String bizId,
                        List<AgentSessionMessage> messages, long version, Date updatedAt) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.bizId = bizId;
        setMessages(messages);
        this.version = version;
        this.updatedAt = updatedAt;
    }

    /**
     * 续聊默认：只映射 role/text/tool*，丢弃 thinkingText 与 id。
     *
     * @return 运行时消息
     */
    public List<LlmMessage> toLlmMessages() {
        return toLlmMessages(false);
    }

    /**
     * @param includeThinking true 时用 splice(thinking, text) 还原旧行为（调试）
     * @return 运行时消息
     */
    public List<LlmMessage> toLlmMessages(boolean includeThinking) {
        return messagesToLlm(messages, includeThinking);
    }

    public static List<LlmMessage> messagesToLlm(List<AgentSessionMessage> source, boolean includeThinking) {
        List<LlmMessage> out = new ArrayList<LlmMessage>();
        if (source == null) {
            return out;
        }
        for (AgentSessionMessage m : source) {
            if (m == null || m.getRole() == null) {
                continue;
            }
            LlmRole role = m.getRole();
            if (role == LlmRole.SYSTEM) {
                out.add(LlmMessage.system(m.getText()));
            } else if (role == LlmRole.USER) {
                out.add(LlmMessage.user(m.getText()));
            } else if (role == LlmRole.ASSISTANT) {
                String text = includeThinking ? LlmTexts.splice(m.getThinkingText(), m.getText()) : m.getText();
                out.add(LlmMessage.assistant(text, m.getToolCalls()));
            } else if (role == LlmRole.TOOL_RESULT) {
                out.add(LlmMessage.toolResult(m.getToolCallId(), m.getText()));
            } else if (role == LlmRole.OTHER) {
                out.add(LlmMessage.other(m.getText()));
            }
        }
        return out;
    }

    /**
     * 复制一份会话，消息列表独立。用于内存存储隔离调用方修改。
     *
     * @param source 源会话
     * @return 新会话
     */
    public static AgentSession copyOf(AgentSession source) {
        if (source == null) {
            return null;
        }
        return new AgentSession(source.getUserId(), source.getSessionId(), source.getBizId(),
                source.getMessages(), source.getVersion(), source.getUpdatedAt());
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public List<AgentSessionMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<AgentSessionMessage> messages) {
        this.messages = messages == null
                ? new ArrayList<AgentSessionMessage>()
                : new ArrayList<AgentSessionMessage>(messages);
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}
