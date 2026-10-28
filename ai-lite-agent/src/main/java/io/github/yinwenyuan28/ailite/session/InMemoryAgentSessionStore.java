package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
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
    /** 压缩租约：key = userId\0sessionId\0compaction，value = 到期时间戳。 */
    private final ConcurrentHashMap<String, Long> compactionLeases =
            new ConcurrentHashMap<String, Long>();

    /** 调用方未指定租约时长时的默认值。 */
    private static final long COMPACTION_LEASE_DEFAULT_MS = 120000L;

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

    /**
     * 内存列举：按更新时间倒序，更新时间相同按 sessionId 倒序保证稳定。
     *
     * @param userId 用户 id
     * @param offset 跳过条数
     * @param limit  最多返回条数
     * @return 摘要列表
     */
    @Override
    public List<AgentSessionSummary> listByUser(String userId, int offset, int limit) {
        AgentSessionStoreSupport.requireKeys(userId, "list");
        List<AgentSessionSummary> matched = new ArrayList<AgentSessionSummary>();
        for (Entry entry : entries.values()) {
            AgentSession session = entry.session;
            if (!userId.equals(session.getUserId()) || expired(entry)) {
                continue;
            }
            matched.add(AgentSessionSummary.of(session));
        }
        Collections.sort(matched, UPDATED_DESC);
        int from = Math.max(0, offset);
        if (from >= matched.size()) {
            return new ArrayList<AgentSessionSummary>();
        }
        int to = limit <= 0 ? matched.size() : Math.min(matched.size(), from + limit);
        return new ArrayList<AgentSessionSummary>(matched.subList(from, to));
    }

    @Override
    public boolean supportsListing() {
        return true;
    }

    /**
     * 内存租约：用 {@code putIfAbsent} 的原子性保证同一会话只有一个持有者。
     *
     * <p>过期租约允许抢占：节点崩溃后租约不能永久卡住压缩。</p>
     *
     * @param userId      用户 id
     * @param sessionId   会话 id
     * @param leaseMillis 租约时长
     * @return 是否抢到
     */
    @Override
    public boolean tryAcquireCompaction(String userId, String sessionId, long leaseMillis) {
        String key = key(userId, sessionId) + "\u0000compaction";
        long now = System.currentTimeMillis();
        long ttl = leaseMillis <= 0 ? COMPACTION_LEASE_DEFAULT_MS : leaseMillis;
        Long expiresAt = Long.valueOf(now + ttl);
        while (true) {
            Long existing = compactionLeases.putIfAbsent(key, expiresAt);
            if (existing == null) {
                return true;
            }
            if (existing.longValue() > now) {
                return false;
            }
            // 已过期：CAS 替换，避免两个节点同时抢到。
            if (compactionLeases.replace(key, existing, expiresAt)) {
                return true;
            }
        }
    }

    @Override
    public void releaseCompaction(String userId, String sessionId) {
        compactionLeases.remove(key(userId, sessionId) + "\u0000compaction");
    }

    /**
     * 更新时间倒序；时间相同或缺失时按 sessionId 倒序，保证分页稳定。
     */
    private static final Comparator<AgentSessionSummary> UPDATED_DESC =
            new Comparator<AgentSessionSummary>() {
                @Override
                public int compare(AgentSessionSummary left, AgentSessionSummary right) {
                    Date l = left.getUpdatedAt();
                    Date r = right.getUpdatedAt();
                    if (l != null && r != null && !l.equals(r)) {
                        return r.compareTo(l);
                    }
                    if (l == null && r != null) {
                        return 1;
                    }
                    if (l != null && r == null) {
                        return -1;
                    }
                    return right.getSessionId().compareTo(left.getSessionId());
                }
            };

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
