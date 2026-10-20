package io.github.yinwenyuan28.ailite.session;

import java.util.Optional;

/**
 * Agent 会话存储 SPI。
 */
public interface AgentSessionStore {

    Optional<AgentSession> find(String userId, String sessionId);

    /**
     * 整包替换 messages。入参 version 为当前读到的值；成功后存储侧为 version+1。
     * version=0 且无行时可创建（允许 messages 为空）。
     */
    void save(AgentSession session);

    void delete(String userId, String sessionId);

    boolean exists(String userId, String sessionId);

    default Optional<AgentSessionMessage> findMessage(String userId, String sessionId, String messageId) {
        return AgentSessionStoreSupport.findMessage(this, userId, sessionId, messageId);
    }

    default void appendMessage(String userId, String sessionId, AgentSessionMessage message) {
        AgentSessionStoreSupport.appendMessage(this, userId, sessionId, message);
    }

    default void replaceMessage(String userId, String sessionId, AgentSessionMessage message) {
        AgentSessionStoreSupport.replaceMessage(this, userId, sessionId, message);
    }

    default void deleteMessage(String userId, String sessionId, String messageId) {
        AgentSessionStoreSupport.deleteMessage(this, userId, sessionId, messageId);
    }
}
