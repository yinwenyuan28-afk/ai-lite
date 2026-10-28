package io.github.yinwenyuan28.ailite.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import io.github.yinwenyuan28.ailite.internal.session.AgentSessionStoreSupport;

/**
 * 持久存储 + 热缓存的组合。
 *
 * <p>先库后缓存：写库成功才更新缓存；缓存失败只打日志并删除脏缓存，
 * 不把缓存故障升级成业务失败。</p>
 */
public final class CachingAgentSessionStore implements AgentSessionStore {

    private static final Logger log = LoggerFactory.getLogger(CachingAgentSessionStore.class);

    private final AgentSessionStore durable;
    private final SessionCache cache;
    private final long ttlSeconds;

    public CachingAgentSessionStore(AgentSessionStore durable, SessionCache cache, long ttlSeconds) {
        if (durable == null) {
            throw new IllegalArgumentException("持久存储不能为空");
        }
        this.durable = durable;
        this.cache = cache;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public Optional<AgentSession> find(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        if (cache != null) {
            try {
                Optional<AgentSession> cached = cache.get(userId, sessionId);
                if (cached.isPresent()) {
                    return cached;
                }
            } catch (RuntimeException ex) {
                log.warn("session cache get failed userId={} sessionId={}: {}",
                        userId, sessionId, ex.getMessage());
            }
        }
        Optional<AgentSession> fromStore = durable.find(userId, sessionId);
        if (fromStore.isPresent() && cache != null) {
            try {
                cache.put(fromStore.get(), ttlSeconds);
            } catch (RuntimeException ex) {
                log.warn("session cache backfill failed userId={} sessionId={}: {}",
                        userId, sessionId, ex.getMessage());
            }
        }
        return fromStore;
    }

    @Override
    public void save(AgentSession session) {
        AgentSessionStoreSupport.requireSession(session);
        durable.save(session);
        if (cache != null) {
            AgentSession stored = AgentSession.copyOf(session);
            stored.setVersion(session.getVersion() + 1L);
            stored.setUpdatedAt(new Date());
            try {
                cache.put(stored, ttlSeconds);
            } catch (RuntimeException ex) {
                log.warn("session cache put failed userId={} sessionId={}: {}",
                        session.getUserId(), session.getSessionId(), ex.getMessage());
                try {
                    cache.delete(session.getUserId(), session.getSessionId());
                } catch (RuntimeException ignored) {
                    // 缓存已经不可用，删除失败也不影响主流程。
                }
            }
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        durable.delete(userId, sessionId);
        if (cache != null) {
            try {
                cache.delete(userId, sessionId);
            } catch (RuntimeException ex) {
                log.warn("session cache delete failed userId={} sessionId={}: {}",
                        userId, sessionId, ex.getMessage());
            }
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        return find(userId, sessionId).isPresent();
    }

    /**
     * 列举委托给持久存储。
     *
     * <p>必须显式委托：{@code AgentSessionStore} 的 default 实现返回空列表，
     * 不覆盖的话带缓存的部署会"列不出任何会话"。</p>
     *
     * @param userId 用户 id
     * @param offset 跳过条数
     * @param limit  最多返回条数
     * @return 摘要列表
     */
    @Override
    public List<AgentSessionSummary> listByUser(String userId, int offset, int limit) {
        return durable.listByUser(userId, offset, limit);
    }

    @Override
    public boolean supportsListing() {
        return durable.supportsListing();
    }
}
