package io.github.yinwenyuan28.ailite.session;

import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 有界内存缓存。用于没有 Redis 的环境，或者测试。
 */
public final class InMemorySessionCache implements SessionCache {

    private final int maxEntries;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<String, Entry>();

    public InMemorySessionCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    @Override
    public Optional<AgentSession> get(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        Entry entry = entries.get(key(userId, sessionId));
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt > 0L && entry.expiresAt <= System.currentTimeMillis()) {
            entries.remove(key(userId, sessionId), entry);
            return Optional.empty();
        }
        return Optional.of(AgentSession.copyOf(entry.session));
    }

    @Override
    public void put(AgentSession session, long ttlSeconds) {
        AgentSessionStoreSupport.requireSession(session);
        long expiresAt = ttlSeconds <= 0 ? 0L : System.currentTimeMillis() + ttlSeconds * 1000L;
        entries.put(key(session.getUserId(), session.getSessionId()),
                new Entry(AgentSession.copyOf(session), expiresAt));
        evictIfNeeded();
    }

    @Override
    public void delete(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        entries.remove(key(userId, sessionId));
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
