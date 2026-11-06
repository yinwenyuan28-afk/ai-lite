package io.github.yinwenyuan28.ailite.admin.jdbc;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多节点的 Redis 目录写锁。
 *
 * <p>默认连接 127.0.0.1:6379 的 DB 4。密码从 {@code -Dredis.password} 或
 * {@code REDIS_PASSWORD} 读取；Redis 不可用时整类跳过，避免 CI 红。</p>
 *
 * <p><b>为什么单节点锁不够</b>：进程内锁在每个节点上各有一份，节点 A 持有锁时
 * 节点 B 拿到的是自己的另一把锁，两边都能进临界区。这里用一个「绕过进程内状态、
 * 只认 Redis」的场景来验证：两个 {@link RedisModelKeyLock} 实例互斥，等价于两个节点。</p>
 */
public class RedisModelKeyLockTest {

    private static final String PREFIX = RedisModelKeyLock.KEY_PREFIX + "test:";

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private static boolean available;

    private RedisModelKeyLock lock;

    @BeforeClass
    public static void connect() {
        String host = property("redis.host", "REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(property("redis.port", "REDIS_PORT", "6379"));
        int database = Integer.parseInt(property("redis.database", "REDIS_DATABASE", "4"));
        String password = property("redis.password", "REDIS_PASSWORD", "");

        factory = new LettuceConnectionFactory(host, port);
        if (!password.isEmpty()) {
            factory.setPassword(password);
        }
        factory.setDatabase(database);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();

        try {
            template.hasKey(PREFIX + "__ping__");
            available = true;
        } catch (RuntimeException ex) {
            available = false;
        }
        Assume.assumeTrue("本机 Redis 不可用，跳过 Redis 锁测试", available);
    }

    @AfterClass
    public static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Before
    public void setUp() {
        cleanup();
        lock = new RedisModelKeyLock(template, 5000L, 3000L);
    }

    @After
    public void tearDown() {
        cleanup();
    }

    /**
     * 两个实例（等价于两个节点）在同一 key 上互斥。
     */
    @Test
    public void twoNodesAreMutuallyExclusive() throws Exception {
        final RedisModelKeyLock nodeA = new RedisModelKeyLock(template, 5000L, 3000L);
        final RedisModelKeyLock nodeB = new RedisModelKeyLock(template, 5000L, 3000L);
        final CountDownLatch aInside = new CountDownLatch(1);
        final CountDownLatch bTried = new CountDownLatch(1);
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();

        Thread a = new Thread(new Runnable() {
            @Override
            public void run() {
                nodeA.withLock("demo", "test:qwen", new Callable<String>() {
                    @Override
                    public String call() {
                        enter(concurrent, maxConcurrent);
                        aInside.countDown();
                        try {
                            bTried.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        concurrent.decrementAndGet();
                        return "a";
                    }
                });
            }
        }, "node-a");
        a.start();

        assertThat(aInside.await(3, TimeUnit.SECONDS)).isTrue();
        Thread b = new Thread(new Runnable() {
            @Override
            public void run() {
                nodeB.withLock("demo", "test:qwen", new Callable<String>() {
                    @Override
                    public String call() {
                        enter(concurrent, maxConcurrent);
                        bTried.countDown();
                        concurrent.decrementAndGet();
                        return "b";
                    }
                });
            }
        }, "node-b");
        b.start();
        b.join(5000);
        a.join(5000);

        // 同时进入临界区的最多 1 个。
        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    /**
     * 释放后另一个节点能拿到锁。
     */
    @Test
    public void lockIsAvailableAfterRelease() {
        String first = lock.withLock("demo", "test:qwen", new Callable<String>() {
            @Override
            public String call() {
                return "first";
            }
        });
        String second = lock.withLock("demo", "test:qwen", new Callable<String>() {
            @Override
            public String call() {
                return "second";
            }
        });

        assertThat(first).isEqualTo("first");
        assertThat(second).isEqualTo("second");
        assertThat(template.hasKey(RedisModelKeyLock.KEY_PREFIX + "demo:test:qwen")).isFalse();
    }

    /**
     * 不同 key 不互斥。
     */
    @Test
    public void differentKeysAreIndependent() {
        String a = lock.withLock("demo", "test:a", new Callable<String>() {
            @Override
            public String call() {
                return "a";
            }
        });
        String b = lock.withLock("demo", "test:b", new Callable<String>() {
            @Override
            public String call() {
                return "b";
            }
        });

        assertThat(a).isEqualTo("a");
        assertThat(b).isEqualTo("b");
    }

    /**
     * 执行体抛异常时锁必须释放。
     */
    @Test
    public void lockIsReleasedWhenBodyThrows() {
        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                lock.withLock("demo", "test:qwen", new Callable<String>() {
                    @Override
                    public String call() {
                        throw new IllegalStateException("写入失败");
                    }
                });
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("写入失败");

        assertThat(template.hasKey(RedisModelKeyLock.KEY_PREFIX + "demo:test:qwen")).isFalse();
    }

    /**
     * 抢不到锁时要超时报错，不能无限等。
     */
    @Test
    public void acquireTimeoutFailsLoudly() throws Exception {
        final RedisModelKeyLock shortWait = new RedisModelKeyLock(template, 5000L, 300L);
        // 先由另一实例占住锁。
        template.opsForValue().set(RedisModelKeyLock.KEY_PREFIX + "demo:test:busy", "other-node", 5000L,
                TimeUnit.MILLISECONDS);

        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                shortWait.withLock("demo", "test:busy", new Callable<String>() {
                    @Override
                    public String call() {
                        return "never";
                    }
                });
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("等待目录写锁超时");
    }

    /**
     * 释放时比对 token：别人的锁不能被删掉。
     */
    @Test
    public void releaseDoesNotDeleteSomeoneElsesLock() {
        String key = RedisModelKeyLock.KEY_PREFIX + "demo:test:foreign";
        template.opsForValue().set(key, "another-node-token", 5000L, TimeUnit.MILLISECONDS);

        // 直接走过期路径：抢不到就超时，锁内容不能被改动。
        try {
            new RedisModelKeyLock(template, 5000L, 200L).withLock("demo", "test:foreign",
                    new Callable<String>() {
                        @Override
                        public String call() {
                            return "never";
                        }
                    });
        } catch (IllegalStateException expected) {
            // 期望超时。
        }

        assertThat(template.opsForValue().get(key)).isEqualTo("another-node-token");
    }

    /**
     * 构造器要求 Redis 客户端，传 null 要明确报错（装配层据此在启动期拦住配置错误）。
     */
    @Test
    public void nullRedisIsRejected() {
        assertThatThrownBy(new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
            @Override
            public void call() {
                new RedisModelKeyLock(null, 1000L, 1000L);
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("StringRedisTemplate");
    }

    /**
     * @param concurrent    当前进入数
     * @param maxConcurrent 观察到的峰值
     */
    private static void enter(AtomicInteger concurrent, AtomicInteger maxConcurrent) {
        int now = concurrent.incrementAndGet();
        int seen;
        do {
            seen = maxConcurrent.get();
        } while (now > seen && !maxConcurrent.compareAndSet(seen, now));
    }

    /**
     * 清掉本测试用到的 key。
     */
    private static void cleanup() {
        if (template == null) {
            return;
        }
        Set<String> keys = template.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
    }

    /**
     * @param key      系统属性名
     * @param env      环境变量名
     * @param fallback 都取不到时的默认值
     * @return 配置值
     */
    private static String property(String key, String env, String fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
            value = System.getenv(env);
        }
        return value == null || value.isEmpty() ? fallback : value;
    }
}
