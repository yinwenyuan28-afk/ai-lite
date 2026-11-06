package io.github.yinwenyuan28.ailite.admin.jdbc;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单节点的目录写锁：JVM 内按 key 分段加锁。
 *
 * <p>只在一个进程内有效。多节点部署必须换 {@code RedisModelKeyLock}，否则两个节点
 * 的请求仍然会插出重复行——这一点由 {@code ai-lite.catalog.multi-node} 显式声明，
 * 而不是靠猜。</p>
 *
 * <p><b>为什么按 key 分段而不是一把大锁</b>：不同模型之间没有竞争关系，一把大锁
 * 会让「同步一批模型」这种批量操作串行化。按 key 分段后只有同一个模型 id 的操作
 * 互斥。</p>
 *
 * <p><b>为什么用 ReentrantLock 而不是 synchronized</b>：需要在 finally 里精确释放，
 * 并且同一线程重入同一 key 时不死锁（管理页保存时可能在外层已经持锁的情况下
 * 再走一次插入）。</p>
 */
public final class LocalModelKeyLock implements ModelKeyLock {

    /**
     * key → 锁。用 ConcurrentHashMap 的 computeIfAbsent 保证「取锁」这一步原子。
     *
     * <p>这里不会主动删除条目：模型数量有上限（一个供应商下的模型列表），
     * 每个条目只是一个 ReentrantLock，占用可忽略。删除反而要处理「刚拿到锁、
     * 条目被删掉、另一个线程又建了一个新锁」的竞态。</p>
     */
    private final ConcurrentHashMap<String, ReentrantLock> locks =
            new ConcurrentHashMap<String, ReentrantLock>();

    @Override
    public <T> T withLock(String providerId, String modelId, Callable<T> body) {
        ReentrantLock lock = locks.computeIfAbsent(key(providerId, modelId),
                new java.util.function.Function<String, ReentrantLock>() {
                    @Override
                    public ReentrantLock apply(String ignored) {
                        return new ReentrantLock();
                    }
                });
        lock.lock();
        try {
            return body.call();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("目录写入失败: " + ex.getMessage(), ex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param providerId 供应商 id
     * @param modelId    模型 id
     * @return 锁的键
     */
    private static String key(String providerId, String modelId) {
        return providerId + "\n" + modelId;
    }
}
