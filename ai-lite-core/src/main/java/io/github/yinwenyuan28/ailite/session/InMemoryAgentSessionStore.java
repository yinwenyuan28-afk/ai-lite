package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;

import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import io.github.yinwenyuan28.ailite.internal.session.AgentSessionStoreSupport;

/**
 * 有界内存会话存储。默认给 Starter 用，不需要数据库或 Redis 就能跑通续聊。
 *
 * <p>save 使用同步块和 version 校验，保证并发写不会静默覆盖；
 * find 返回副本，避免调用方修改内部状态。</p>
 */
public final class InMemoryAgentSessionStore implements AgentSessionStore {

    private final int maxEntries;
    private final long ttlSeconds;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<String, Entry>();

    /**
     * @param maxEntries 最大会话数
     * @param ttlSeconds 过期秒数
     */
    public InMemoryAgentSessionStore(int maxEntries, long ttlSeconds) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlSeconds = ttlSeconds <= 0 ? 604800L : ttlSeconds;
    }

    @Override
    public Optional<AgentSession> find(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        Entry entry = entries.get(key(userId, sessionId));
        if (entry == null) {
            return Optional.empty();
        }
        if (expired(entry)) {
            entries.remove(key(userId, sessionId), entry);
            return Optional.empty();
        }
        return Optional.of(AgentSession.copyOf(entry.session));
    }

    @Override
    public synchronized void save(AgentSession session) {
        AgentSessionStoreSupport.requireSession(session);
        String key = key(session.getUserId(), session.getSessionId());
        Entry existing = entries.get(key);
        if (existing != null && expired(existing)) {
            entries.remove(key);
            existing = null;
        }
        if (existing != null) {
            if (existing.session.getVersion() != session.getVersion()) {
                throw new LlmCallException("会话版本冲突");
            }
        } else if (session.getVersion() != 0L) {
            throw new LlmCallException("会话版本冲突");
        }
        AgentSession stored = AgentSession.copyOf(session);
        stored.setVersion(session.getVersion() + 1L);
        stored.setUpdatedAt(new Date());
        entries.put(key, new Entry(stored, expiresAt()));
        evictIfNeeded();
    }

    @Override
    public void delete(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        entries.remove(key(userId, sessionId));
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return find(userId, sessionId).isPresent();
    }

    private boolean expired(Entry entry) {
        return entry.expiresAt > 0L && entry.expiresAt <= System.currentTimeMillis();
    }

    private long expiresAt() {
        return ttlSeconds <= 0 ? 0L : System.currentTimeMillis() + ttlSeconds * 1000L;
    }

    private void evictIfNeeded() {
        if (entries.size() <= maxEntries) {
            return;
        }
        String oldestKey = null;
        Date oldest = null;
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Entry> item = iterator.next();
            Date updated = item.getValue().session.getUpdatedAt();
            if (oldest == null || (updated != null && updated.before(oldest))) {
                oldest = updated;
                oldestKey = item.getKey();
            }
        }
        if (oldestKey != null) {
            entries.remove(oldestKey);
        }
    }

    private static String key(String userId, String sessionId) {
        return userId + "\u0000" + sessionId;
    }

    private static final class Entry {
        private final AgentSession session;
        private final long expiresAt;

        private Entry(AgentSession session, long expiresAt) {
            this.session = session;
            this.expiresAt = expiresAt;
        }
    }
}
