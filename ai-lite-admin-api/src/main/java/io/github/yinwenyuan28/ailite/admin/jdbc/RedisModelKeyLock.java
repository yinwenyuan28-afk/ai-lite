package io.github.yinwenyuan28.ailite.admin.jdbc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 多节点的目录写锁：Redis {@code SET NX PX}。
 *
 * <p>多节点部署时，{@link LocalModelKeyLock} 只在单个进程内有效，两个节点同时
 * adopt 同一对模型仍然会插出重复行。这个实现把锁放到 Redis 上，跨进程互斥。</p>
 *
 * <p><b>为什么释放要用 Lua 比对持有者</b>：如果只做 {@code DEL}，会出现这种情况——
 * 本进程的锁因为执行超时已经过期、被另一个进程拿到，这时本进程执行完毕去删锁，
 * 删掉的是别人的锁。所以写入一个随机 token，释放时「只有值还是我的才删」，
 * 比对与删除必须原子，因此用 Lua。</p>
 *
 * <p><b>持有时间要有上限</b>：进程崩溃时锁不能永久残留，所以 {@code SET NX} 带
 * 过期时间。租期要明显长于一次写入（几条 SQL）的耗时。</p>
 */
public final class RedisModelKeyLock implements ModelKeyLock {

    private static final Logger log = LoggerFactory.getLogger(RedisModelKeyLock.class);

    /** 锁的前缀。与其他 AI Lite 的 key 区分开，便于排查与人工清理。 */
    static final String KEY_PREFIX = "ai-lite:catalog:lock:";

    /**
     * 释放脚本：值等于自己的 token 才删除。
     *
     * <p>返回 1 表示删掉了自己的锁，0 表示锁已经不属于自己（过期后被别人拿走）。
     * 后者不是错误，只记一条 debug。</p>
     */
    private static final RedisScript<Long> RELEASE_SCRIPT = new RedisScript<Long>() {
        @Override
        public String getSha1() {
            return "ai-lite:release-lock";
        }

        @Override
        public Class<Long> getResultType() {
            return Long.class;
        }

        @Override
        public String getScriptAsString() {
            return "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end";
        }
    };

    private final StringRedisTemplate redis;
    /** 锁的租期：超时未释放会被 Redis 自动清掉，防止进程崩溃后死锁。 */
    private final long leaseMillis;
    /** 抢锁的最长等待时间。超时说明有别的写入长时间占着锁。 */
    private final long waitMillis;
    /** 抢锁失败后的重试间隔。 */
    private final long retryMillis;

    /**
     * @param redis       Redis 客户端
     * @param leaseMillis 锁租期（毫秒）
     * @param waitMillis  抢锁最长等待（毫秒）
     */
    public RedisModelKeyLock(StringRedisTemplate redis, long leaseMillis, long waitMillis) {
        if (redis == null) {
            throw new IllegalArgumentException("StringRedisTemplate 不能为空，"
                    + "多节点部署需要 Redis 来实现目录写锁");
        }
        this.redis = redis;
        this.leaseMillis = leaseMillis <= 0 ? 10000L : leaseMillis;
        this.waitMillis = waitMillis <= 0 ? 10000L : waitMillis;
        // 重试间隔取租期的二十分之一，最多 50ms：既不会打爆 Redis，也不会让等待过钝。
        this.retryMillis = Math.max(1L, Math.min(50L, this.leaseMillis / 20L));
    }

    @Override
    public <T> T withLock(String providerId, String modelId, Callable<T> body) {
        String key = KEY_PREFIX + providerId + ":" + modelId;
        String token = UUID.randomUUID().toString();
        if (!acquire(key, token)) {
            throw new IllegalStateException("等待目录写锁超时（" + waitMillis + "ms），"
                    + "可能有另一个节点正在写入同一个模型: " + providerId + "/" + modelId);
        }
        try {
            return body.call();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("目录写入失败: " + ex.getMessage(), ex);
        } finally {
            release(key, token);
        }
    }

    /**
     * 抢锁，带重试。
     *
     * @param key   锁的键
     * @param token 持有者标识
     * @return true 表示抢到
     */
    private boolean acquire(String key, String token) {
        long deadline = System.currentTimeMillis() + waitMillis;
        while (true) {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, token, leaseMillis,
                    TimeUnit.MILLISECONDS);
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(retryMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * 释放锁。只在值仍是自己的 token 时删除。
     *
     * @param key   锁的键
     * @param token 持有者标识
     */
    private void release(String key, String token) {
        try {
            List<String> keys = Collections.singletonList(key);
            Long deleted = redis.execute(RELEASE_SCRIPT, keys, token);
            if (deleted == null || deleted.longValue() == 0L) {
                // 锁已经过期并被别人拿走。写入本身已经完成，这个结果不影响正确性。
                log.debug("目录写锁已过期，未删除: {}", key);
            }
        } catch (RuntimeException ex) {
            // 释放失败不能盖掉业务结果：锁会自然过期。
            log.warn("释放目录写锁失败，将等待租期到期: {} {}", key, ex.toString());
        }
    }
}
