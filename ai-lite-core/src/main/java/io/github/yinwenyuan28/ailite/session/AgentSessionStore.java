package io.github.yinwenyuan28.ailite.session;

import java.util.Optional;
import io.github.yinwenyuan28.ailite.internal.session.AgentSessionStoreSupport;

/**
 * Agent 会话存储 SPI。
 *
 * <p><b>演化承诺（A 组：允许生长）</b>：会话存储是业务扩展点，未来可能新增
 * 分页查询、按用户批量删除、迁移辅助方法。届时<b>只允许以 default 方法新增</b>，
 * 默认实现应抛 {@link UnsupportedOperationException} 或委托
 * {@link AgentSessionStoreSupport} 的通用实现；不得新增抽象方法，
 * 不得修改或删除现有方法。</p>
 *
 * <p>本接口已有的 4 个 default 消息级方法就是这种演化方式的样板：新增能力
 * 通过 {@code AgentSessionStoreSupport} 在默认方法里实现，自定义存储只实现
 * 8 个核心抽象方法即可。</p>
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
