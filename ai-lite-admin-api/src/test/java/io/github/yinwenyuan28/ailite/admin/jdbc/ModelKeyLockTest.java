package io.github.yinwenyuan28.ailite.admin.jdbc;

import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 目录写入的进程内锁。
 *
 * <p><b>回归背景</b>：{@code insertModelIfAbsent} 是「先查再插」。两个请求同时
 * adopt 同一对 {@code (供应商, 模型)} 时都查不到、都插入，库里出现两行。之后
 * {@code saveCapabilities} 的 {@code UPDATE ... WHERE PROVIDER_ID=? AND MODEL_ID=?}
 * 会一次改掉全部重复行，而读取只认其中一行，表现为「保存成功、刷新又变回去」。</p>
 *
 * <p>这里不加唯一索引（那要改表结构），改成在写入路径上加锁。单节点用进程内锁，
 * 多节点用 Redis 锁，由 {@code ai-lite.catalog.multi-node} 显式声明——
 * 部署形态代码猜不出来。</p>
 */
public class ModelKeyLockTest {

    private JdbcTemplate jdbc;

    @Before
    public void setUp() {
        jdbc = SqliteFiles.open("model-key-lock");
        JdbcRuntimeCatalogTest.createTables(jdbc);
    }

    /**
     * 并发插入同一对键：库里恰好一行，恰好一次返回 true。
     */
    @Test
    public void concurrentInsertsOfSameKeyProduceOneRow() throws Exception {
        final JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc,
                "AI_LITE_PROVIDER_OVERLAY", "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0);

        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger added = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        if (catalog.insertModelIfAbsent("demo", "qwen")) {
                            added.incrementAndGet();
                        }
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                    }
                }
            }, "adopt-" + i);
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5000);
        }

        assertThat(failed.get()).isZero();
        assertThat(added.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AI_LITE_MODEL"
                + " WHERE PROVIDER_ID = 'demo' AND MODEL_ID = 'qwen'", Integer.class)).isEqualTo(1);
    }

    /**
     * 不同模型 id 之间不该互相阻塞：锁是按 key 分段的。
     */
    @Test
    public void differentKeysDoNotBlockEachOther() throws Exception {
        final JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc,
                "AI_LITE_PROVIDER_OVERLAY", "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0);
        final CountDownLatch bothInside = new CountDownLatch(2);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        for (final String modelId : new String[]{"a", "b"}) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        catalog.insertModelIfAbsent("demo", modelId);
                        bothInside.countDown();
                    } catch (Throwable ex) {
                        error.set(ex);
                    }
                }
            }, "insert-" + modelId).start();
        }

        assertThat(bothInside.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
    }

    /**
     * 锁内抛异常也要释放：否则后续请求会永久卡住。
     */
    @Test
    public void lockIsReleasedWhenBodyThrows() {
        final LocalModelKeyLock lock = new LocalModelKeyLock();

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                lock.withLock("demo", "qwen", new java.util.concurrent.Callable<String>() {
                    @Override
                    public String call() {
                        throw new IllegalStateException("写入失败");
                    }
                });
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("写入失败");

        // 还能再拿到同一把锁，说明上一次已经释放。
        String value = lock.withLock("demo", "qwen", new java.util.concurrent.Callable<String>() {
            @Override
            public String call() {
                return "ok";
            }
        });
        assertThat(value).isEqualTo("ok");
    }

    /**
     * 同一线程重入同一 key 不死锁。管理页保存时可能在外层持锁的情况下再走一次插入。
     */
    @Test
    public void sameThreadCanReenter() {
        final LocalModelKeyLock lock = new LocalModelKeyLock();

        String result = lock.withLock("demo", "qwen", new java.util.concurrent.Callable<String>() {
            @Override
            public String call() {
                return lock.withLock("demo", "qwen", new java.util.concurrent.Callable<String>() {
                    @Override
                    public String call() {
                        return "inner";
                    }
                });
            }
        });

        assertThat(result).isEqualTo("inner");
    }

    /**
     * 不加锁直接插两次会插出两行——这条证明锁确实是必需的，而不是巧合通过。
     */
    @Test
    public void withoutLockDuplicatesArePossible() {
        jdbc.update("INSERT INTO AI_LITE_MODEL (MODEL_ROW_ID, PROVIDER_ID, MODEL_ID, READY)"
                + " VALUES ('a', 'demo', 'qwen', 0)");
        jdbc.update("INSERT INTO AI_LITE_MODEL (MODEL_ROW_ID, PROVIDER_ID, MODEL_ID, READY)"
                + " VALUES ('b', 'demo', 'qwen', 0)");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AI_LITE_MODEL"
                + " WHERE PROVIDER_ID = 'demo' AND MODEL_ID = 'qwen'", Integer.class)).isEqualTo(2);
    }

    /**
     * 老库已有的重复行仍要能读，取更新时间较新的那行。
     */
    @Test
    public void legacyDuplicateRowsStillReadDeterministically() {
        jdbc.update("INSERT INTO AI_LITE_MODEL (MODEL_ROW_ID, PROVIDER_ID, MODEL_ID, READY, UPDATE_TIME)"
                + " VALUES ('old', 'demo', 'qwen', 0, ?)", new java.sql.Timestamp(1L));
        jdbc.update("INSERT INTO AI_LITE_MODEL (MODEL_ROW_ID, PROVIDER_ID, MODEL_ID, READY, UPDATE_TIME)"
                + " VALUES ('new', 'demo', 'qwen', 1, ?)", new java.sql.Timestamp(2000L));
        JdbcRuntimeCatalog catalog = new JdbcRuntimeCatalog(jdbc, "AI_LITE_PROVIDER_OVERLAY",
                "AI_LITE_MODEL", "AI_LITE_BIZ_BINDING", 0);

        boolean first = catalog.findModel("demo", "qwen").isReady();
        assertThat(first).isTrue();
        for (int i = 0; i < 5; i++) {
            catalog.invalidate();
            assertThat(catalog.findModel("demo", "qwen").isReady()).isEqualTo(first);
        }
    }
}
