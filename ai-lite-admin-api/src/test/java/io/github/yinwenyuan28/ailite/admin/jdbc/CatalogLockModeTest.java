package io.github.yinwenyuan28.ailite.admin.jdbc;

import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 锁的选型与配置项语义。
 *
 * <p>{@code ai-lite.catalog.multi-node} 必须显式配置，因为部署形态代码猜不出来：
 * 同机多进程、容器多副本都算多节点，但看上去都是「一个应用」。配错的代价是重复写入
 * 悄悄发生，所以这里守住两条：默认必须是单节点；配成多节点却没有 Redis 时必须报错，
 * 不能悄悄退回进程内锁。</p>
 *
 * <p><b>为什么不用完整应用上下文验证</b>：目录 Bean 的前置条件还包括提示词存储，
 * 为一条配置项语义把整套依赖拖进来，用例会脆而慢。装配层的选择逻辑在这里直接测，
 * 完整上下文的启动路径由 {@code CatalogAssemblyTest} 覆盖。</p>
 */
public class CatalogLockModeTest {

    /**
     * 默认值是单节点。
     */
    @Test
    public void multiNodeDefaultsToFalse() {
        assertThat(new AiLiteCatalogProperties().isMultiNode()).isFalse();
    }

    /**
     * 锁参数有合理的默认值：租期与等待都不能是 0（否则锁立刻过期或立刻超时）。
     */
    @Test
    public void lockTimingHasUsableDefaults() {
        AiLiteCatalogProperties properties = new AiLiteCatalogProperties();
        assertThat(properties.getLockLeaseMillis()).isGreaterThan(1000L);
        assertThat(properties.getLockWaitMillis()).isGreaterThan(1000L);
    }

    /**
     * 多节点模式必须拿到 Redis：构造器传 null 要立刻报错，而不是拖到第一次写入。
     */
    @Test
    public void multiNodeRequiresRedis() {
        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                new RedisModelKeyLock(null, 1000L, 1000L);
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("StringRedisTemplate");
    }

    /**
     * 进程内锁在并发下把「先查再插」变成串行，库里只留一行。
     *
     * <p>这条不依赖 Spring 上下文，直接测锁本身与目录的配合。</p>
     */
    @Test
    public void localLockSerializesInsertOfSameKey() throws Exception {
        final org.springframework.jdbc.core.JdbcTemplate jdbc =
                SqliteFiles.open("lock-mode-local");
        JdbcRuntimeCatalogTest.createTables(jdbc);
        final JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0, null, new LocalModelKeyLock());

        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger added = new AtomicInteger();
        Thread[] threads = new Thread[6];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        if (catalog.insertModelIfAbsent("demo", "qwen")) {
                            added.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5000);
        }

        assertThat(added.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AI_LITE_MODEL"
                + " WHERE PROVIDER_ID = 'demo' AND MODEL_ID = 'qwen'", Integer.class)).isEqualTo(1);
    }

    /**
     * 不传锁时默认用进程内锁，老的构造器不需要改动。
     */
    @Test
    public void nullLockDefaultsToLocal() {
        final org.springframework.jdbc.core.JdbcTemplate jdbc = SqliteFiles.open("lock-mode-null");
        JdbcRuntimeCatalogTest.createTables(jdbc);
        JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0);

        assertThat(catalog.insertModelIfAbsent("demo", "qwen")).isTrue();
        assertThat(catalog.insertModelIfAbsent("demo", "qwen")).isFalse();
    }
}
