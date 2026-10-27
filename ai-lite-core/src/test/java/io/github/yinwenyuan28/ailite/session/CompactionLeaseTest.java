package io.github.yinwenyuan28.ailite.session;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩租约的并发语义。
 *
 * <p>租约只是省钱手段，不是正确性依赖：抢不到的一方跳过压缩即可，
 * 数据不会损坏（压缩是追加语义）。</p>
 */
public class CompactionLeaseTest {

    @Test
    public void onlyOneOfManyConcurrentAcquiresSucceeds() throws Exception {
        final InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s"));

        final int threads = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger acquired = new AtomicInteger();
        final List<Thread> workers = new ArrayList<Thread>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (store.tryAcquireCompaction("u", "s", 60000L)) {
                        acquired.incrementAndGet();
                    }
                    done.countDown();
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        done.await();

        assertThat(acquired.get()).isEqualTo(1);
    }

    @Test
    public void releaseAllowsReacquire() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s"));

        assertThat(store.tryAcquireCompaction("u", "s", 60000L)).isTrue();
        assertThat(store.tryAcquireCompaction("u", "s", 60000L)).isFalse();

        store.releaseCompaction("u", "s");
        assertThat(store.tryAcquireCompaction("u", "s", 60000L)).isTrue();
    }

    @Test
    public void expiredLeaseCanBeTakenOver() throws Exception {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s"));

        // 1ms 租约：宁可短暂等待也不要依赖时钟精度，用轮询等它过期。
        assertThat(store.tryAcquireCompaction("u", "s", 1L)).isTrue();
        long deadline = System.currentTimeMillis() + 2000L;
        boolean acquired = false;
        while (System.currentTimeMillis() < deadline) {
            if (store.tryAcquireCompaction("u", "s", 60000L)) {
                acquired = true;
                break;
            }
            Thread.sleep(5L);
        }

        assertThat(acquired).as("过期租约必须可被抢占，否则节点崩溃会让压缩永久卡死").isTrue();
    }

    @Test
    public void leaseIsPerSession() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);
        store.save(session("u", "s1"));
        store.save(session("u", "s2"));

        assertThat(store.tryAcquireCompaction("u", "s1", 60000L)).isTrue();
        // 不同会话互不影响。
        assertThat(store.tryAcquireCompaction("u", "s2", 60000L)).isTrue();
    }

    @Test
    public void releasingWithoutAcquiringIsSafe() {
        InMemoryAgentSessionStore store = new InMemoryAgentSessionStore(10, 60);

        store.releaseCompaction("u", "s");
        assertThat(store.tryAcquireCompaction("u", "s", 60000L)).isTrue();
    }

    @Test
    public void customStoreDefaultsToAlwaysGrant() {
        AgentSessionStore minimal = new AgentSessionStore() {
            @Override
            public java.util.Optional<AgentSession> find(String userId, String sessionId) {
                return java.util.Optional.empty();
            }

            @Override
            public void save(AgentSession session) {
            }

            @Override
            public void delete(String userId, String sessionId) {
            }

            @Override
            public boolean exists(String userId, String sessionId) {
                return false;
            }
        };

        // 无租约概念的单机存储必须恒返回 true，否则压缩永远不会发生。
        assertThat(minimal.tryAcquireCompaction("u", "s", 1000L)).isTrue();
        minimal.releaseCompaction("u", "s");
    }

    private static AgentSession session(String userId, String sessionId) {
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setSessionId(sessionId);
        session.setBizId("biz");
        session.setVersion(0L);
        return session;
    }
}
