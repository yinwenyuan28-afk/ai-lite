package io.github.yinwenyuan28.ailite.session.redis;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.session.AgentSession;
import io.github.yinwenyuan28.ailite.session.AgentSessionCodec;
import io.github.yinwenyuan28.ailite.internal.session.AgentSessionStoreSupport;
import io.github.yinwenyuan28.ailite.session.SessionCache;
import io.github.yinwenyuan28.ailite.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis 会话缓存。只存序列化后的会话快照，不承担版本校验。
 */
public final class RedisSessionCache implements SessionCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionCache.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final boolean requireRedis;

    public RedisSessionCache(StringRedisTemplate redis, String keyPrefix, boolean requireRedis) {
        this.redis = redis;
        this.keyPrefix = keyPrefix == null ? "ai-lite:session:" : keyPrefix;
        this.requireRedis = requireRedis;
    }

    @Override
    public Optional<AgentSession> get(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        if (redis == null) {
            return unavailableOptional();
        }
        try {
            String json = redis.opsForValue().get(key(userId, sessionId));
            if (Strings.isBlank(json)) {
                return Optional.empty();
            }
            AgentSession session;
            try {
                session = AgentSessionCodec.decodeSession(json);
            } catch (LlmCallException ex) {
                // 缓存里的坏数据按 miss 处理，不能让它影响主流程。
                log.warn("session cache decode failed userId={} sessionId={}", userId, sessionId);
                safeDelete(userId, sessionId);
                return Optional.empty();
            }
            if (session == null || !userId.equals(session.getUserId())
                    || !sessionId.equals(session.getSessionId())) {
                return Optional.empty();
            }
            return Optional.of(session);
        } catch (RuntimeException ex) {
            return unavailable(ex);
        }
    }

    @Override
    public void put(AgentSession session, long ttlSeconds) {
        AgentSessionStoreSupport.requireSession(session);
        if (redis == null) {
            unavailableVoid();
            return;
        }
        try {
            String json = AgentSessionCodec.encodeSession(session);
            if (ttlSeconds > 0) {
                redis.opsForValue().set(key(session.getUserId(), session.getSessionId()), json,
                        ttlSeconds, TimeUnit.SECONDS);
            } else {
                redis.opsForValue().set(key(session.getUserId(), session.getSessionId()), json);
            }
        } catch (RuntimeException ex) {
            unavailable(ex);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        if (redis == null) {
            unavailableVoid();
            return;
        }
        try {
            redis.delete(key(userId, sessionId));
        } catch (RuntimeException ex) {
            unavailable(ex);
        }
    }

    /**
     * @return Redis 是否可用
     */
    public boolean available() {
        return redis != null;
    }

    private <T> Optional<T> unavailableOptional() {
        if (requireRedis) {
            throw new LlmCallException("会话缓存需要 Redis");
        }
        return Optional.<T>empty();
    }

    private <T> Optional<T> unavailable(RuntimeException ex) {
        if (requireRedis) {
            throw new LlmCallException("会话缓存需要 Redis", ex);
        }
        log.warn("session redis access failed: {}", ex.getMessage());
        return Optional.<T>empty();
    }

    private void unavailableVoid() {
        if (requireRedis) {
            throw new LlmCallException("会话缓存需要 Redis");
        }
    }

    private String key(String userId, String sessionId) {
        return keyPrefix + userId + ":" + sessionId;
    }

    private void safeDelete(String userId, String sessionId) {
        try {
            redis.delete(key(userId, sessionId));
        } catch (RuntimeException ex) {
            log.warn("session cache cleanup failed userId={} sessionId={}: {}",
                    userId, sessionId, ex.getMessage());
        }
    }
}
