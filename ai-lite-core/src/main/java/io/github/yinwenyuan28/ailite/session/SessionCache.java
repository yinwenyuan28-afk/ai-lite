package io.github.yinwenyuan28.ailite.session;

import java.util.Optional;

/**
 * 会话热缓存 SPI。Redis 实现放在可选模块，核心不依赖 Spring Data Redis。
 */
public interface SessionCache {

    Optional<AgentSession> get(String userId, String sessionId);

    void put(AgentSession session, long ttlSeconds);

    void delete(String userId, String sessionId);
}
