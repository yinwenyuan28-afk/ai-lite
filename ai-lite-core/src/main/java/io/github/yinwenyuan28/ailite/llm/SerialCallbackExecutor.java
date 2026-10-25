package io.github.yinwenyuan28.ailite.llm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 每 run（或每次流式调用）一个的串行回调执行器。
 *
 * <p>线程纪律：内部维护 FIFO 队列，同一时刻最多向共享回调池提交一个 worker；
 * worker 循环取任务执行，队列空了归还自己。因此任务顺序严格等于提交顺序，
 * 且任意两个任务绝不并发——这是"用户回调不得并发"与"泵单线程推进"两个契约
 * 的共同落点。</p>
 *
 * <p>背压：队列达到容量时 {@link #submit(Runnable)} 返回 false，由调用方决定
 * 收敛方式（AgentLoop 的 drain 被拒 → OVERLOAD；网关的 delta 被拒 → 先异常
 * 完成再 cancel）。执行器不自行抛异常。</p>
 *
 * <p>生命周期：run 结束时 {@link #close()}——停止接收、清空未执行任务；幂等。
 * 不提供"取消监听"之类接口：清空队列只影响排队任务，已提交给外部系统的
 * future 组合（如 AgentLoop 的工具链）靠段头取消检查停止，见回调策略计划 M-6。</p>
 */
public final class SerialCallbackExecutor {

    private final Executor pool;
    private final int capacity;
    private final Deque<Runnable> queue = new ArrayDeque<Runnable>();
    private boolean running;
    private boolean closed;

    /**
     * @param pool     共享回调线程池（如 ai-lite-callback-*），只承载 worker，不直接接收任务
     * @param capacity 每 run 的队列上限
     */
    public SerialCallbackExecutor(Executor pool, int capacity) {
        this.pool = pool;
        this.capacity = Math.max(1, capacity);
    }

    /**
     * 提交一个回调任务。
     *
     * @param task 回调任务；实现应自行捕获业务异常，避免影响后续任务
     * @return true 已入队；false 队列已满或执行器已关闭（调用方据此收敛）
     */
    public synchronized boolean submit(final Runnable task) {
        if (closed || task == null) {
            return false;
        }
        if (queue.size() >= capacity) {
            return false;
        }
        queue.addLast(task);
        if (!running) {
            try {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        drainWorker();
                    }
                });
                running = true;
            } catch (RejectedExecutionException ex) {
                // 共享池拒绝：归还队列状态，让调用方按队列满处理。
                queue.removeLast();
                return false;
            }
        }
        return true;
    }

    /**
     * 结束本 run 的回调通道：停止接收并清空未执行任务。幂等；
     * 正在执行的任务不受影响（由任务自身与调用方的终态收敛负责）。
     */
    public synchronized void close() {
        closed = true;
        queue.clear();
    }

    /**
     * @return 是否已关闭
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    /** worker 循环：一次只取一个任务执行，队列空则归还，保证单 in-flight。 */
    private void drainWorker() {
        for (;;) {
            final Runnable task;
            synchronized (this) {
                task = queue.pollFirst();
                if (task == null) {
                    running = false;
                    return;
                }
            }
            task.run();
        }
    }
}
