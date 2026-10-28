package io.github.yinwenyuan28.ailite.session;

import java.util.Optional;

/**
 * 会话热缓存 SPI。Redis 实现放在可选模块，核心不依赖 Spring Data Redis。
 *
 * <p><b>演化承诺（A 组：允许生长）</b>：会话缓存是业务扩展点，未来可能新增
 * 批量读写、按用户清理、统计类方法。届时<b>只允许以 default 方法新增</b>，
 * 默认实现应抛 {@link UnsupportedOperationException} 或委托旧方法；
 * 不得新增抽象方法，不得修改或删除现有方法。</p>
 */
public interface SessionCache {

    Optional<AgentSession> get(String userId, String sessionId);

    void put(AgentSession session, long ttlSeconds);

    void delete(String userId, String sessionId);
}
