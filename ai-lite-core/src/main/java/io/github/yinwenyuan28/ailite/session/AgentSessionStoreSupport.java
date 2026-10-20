package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.util.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Store 共用校验与消息级 CRUD（读出整包 → 改 list → 带 version 写回）。
 */
public final class AgentSessionStoreSupport {

    private AgentSessionStoreSupport() {
    }

    public static void requireKeys(String userId, String sessionId) {
        if (Strings.isBlank(userId) || Strings.isBlank(sessionId)) {
            throw new LlmCallException("userId 与 sessionId 不能为空");
        }
    }

    public static void requireSession(AgentSession session) {
        if (session == null) {
            throw new LlmCallException("会话不能为空");
        }
        requireKeys(session.getUserId(), session.getSessionId());
        AgentSessionCodec.validateMessageIds(session.getMessages());
    }

    static boolean sameUser(AgentSession session, String userId) {
        return session != null && userId != null && userId.equals(session.getUserId());
    }

    public static Optional<AgentSessionMessage> findMessage(AgentSessionStore store, String userId,
                                                           String sessionId, String messageId) {
        requireKeys(userId, sessionId);
        if (Strings.isBlank(messageId)) {
            throw new LlmCallException("messageId 不能为空");
        }
        Optional<AgentSession> session = store.find(userId, sessionId);
        if (!session.isPresent()) {
            return Optional.empty();
        }
        for (AgentSessionMessage message : session.get().getMessages()) {
            if (message != null && messageId.equals(message.getId())) {
                return Optional.of(message);
            }
        }
        return Optional.empty();
    }

    public static void appendMessage(AgentSessionStore store, String userId, String sessionId,
                                     AgentSessionMessage message) {
        requireKeys(userId, sessionId);
        if (message == null || Strings.isBlank(message.getId())) {
            throw new LlmCallException("会话消息缺少 id");
        }
        AgentSession session = store.find(userId, sessionId)
                .orElseThrow(() -> new LlmCallException("会话不存在"));
        List<AgentSessionMessage> messages = new ArrayList<AgentSessionMessage>(session.getMessages());
        for (AgentSessionMessage existing : messages) {
            if (message.getId().equals(existing.getId())) {
                throw new LlmCallException("会话消息 id 重复");
            }
        }
        messages.add(message);
        session.setMessages(messages);
        store.save(session);
    }

    public static void replaceMessage(AgentSessionStore store, String userId, String sessionId,
                                      AgentSessionMessage message) {
        requireKeys(userId, sessionId);
        if (message == null || Strings.isBlank(message.getId())) {
            throw new LlmCallException("会话消息缺少 id");
        }
        AgentSession session = store.find(userId, sessionId)
                .orElseThrow(() -> new LlmCallException("会话不存在"));
        List<AgentSessionMessage> messages = new ArrayList<AgentSessionMessage>(session.getMessages());
        int index = indexOf(messages, message.getId());
        if (index < 0) {
            throw new LlmCallException("会话消息不存在");
        }
        messages.set(index, message);
        session.setMessages(messages);
        store.save(session);
    }

    public static void deleteMessage(AgentSessionStore store, String userId, String sessionId,
                                     String messageId) {
        requireKeys(userId, sessionId);
        if (Strings.isBlank(messageId)) {
            throw new LlmCallException("messageId 不能为空");
        }
        AgentSession session = store.find(userId, sessionId)
                .orElseThrow(() -> new LlmCallException("会话不存在"));
        List<AgentSessionMessage> messages = new ArrayList<AgentSessionMessage>(session.getMessages());
        int index = indexOf(messages, messageId);
        if (index < 0) {
            throw new LlmCallException("会话消息不存在");
        }
        messages.remove(index);
        session.setMessages(messages);
        store.save(session);
    }

    private static int indexOf(List<AgentSessionMessage> messages, String messageId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }
}
