package io.github.yinwenyuan28.ailite.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每 run（或每次流式调用）一个的串行回调执行器。
 *
 * <p>线程纪律：内部维护 FIFO 队列，同一时刻最多一位 worker 活跃；worker 每执行
 * 一个任务就把剩余工作交回共享回调池，不再长期占住一条池线程（F-07）。因此任务
 * 顺序严格等于提交顺序，且任意两个任务绝不并发——这是"用户回调不得并发"与
 * "泵单线程推进"两个契约的共同落点。</p>
 *
 * <p>健壮性：任务抛出的任何 {@code Throwable} 都只记录、不打断队列；不论 worker
 * 以何种方式退出，"正在作业"标志都在退出路径复位（F-02）。完成屏障也排在这个
 * 队列里，所以通道停摆等于调用方的 future 永久挂起。</p>
 *
 * <p>背压：队列达到容量时 {@link #submit(Runnable)} 返回 false，由调用方决定
 * 收敛方式（AgentLoop 的 drain 被拒 → OVERLOAD；网关的 delta 被拒 → 先异常
 * 完成再 cancel）。共享池拒绝 worker 同样映射为一次 {@code false}。</p>
 *
 * <p>生命周期：run 结束时 {@link #close()}——停止接收、清空未执行任务；幂等。
 * 不提供"取消监听"之类接口：清空队列只影响排队任务，已提交给外部系统的
 * future 组合（如 AgentLoop 的工具链）靠段头取消检查停止，见回调策略计划 M-6。</p>
 */
public final class SerialCallbackExecutor {

    private static final Logger log = LoggerFactory.getLogger(SerialCallbackExecutor.class);

    /** worker 已交给共享池异步接棒，当前 worker 可以退出。 */
    private static final int HANDED_OFF = 0;
    /** 传入的是内联 Executor，包装任务在调用线程上执行；由调用方继续 drain。 */
    private static final int INLINE = 1;
    /** 共享池拒绝，调用方按 OVERLOAD / 本地兜底处理。 */
    private static final int REJECTED = 2;

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
     * @param task 回调任务；实现无需自行捕获业务异常，执行器会逐个兜底
     * @return true 已入队；false 队列已满、执行器已关闭或共享池拒绝（调用方据此收敛）
     */
    public boolean submit(final Runnable task) {
        final boolean start;
        synchronized (this) {
            if (closed || task == null) {
                return false;
            }
            if (queue.size() >= capacity) {
                return false;
            }
            start = !running;
            // 先置位再投递：内联 Executor 会在 execute 内同步跑完 worker 并复位，
            // 若把赋值放在 execute 之后，会把 false 覆写回 true（F-02 同源缺陷）。
            if (start) {
                running = true;
            }
            queue.addLast(task);
        }
        if (!start) {
            return true;
        }

        int outcome = scheduleWorker();
        if (outcome == REJECTED) {
            synchronized (this) {
                // 只撤本次任务：并发 submit 可能已往队尾追加了别的任务。
                queue.removeLastOccurrence(task);
                running = false;
            }
            return false;
        }
        if (outcome == INLINE) {
            // 内联 Executor 的包装任务不干活，由本线程把队列跑完，避免同线程递归。
            drain();
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
        // run 已结束，通道不再需要工人；复位后即使 worker 尚在退出也不会留下"假成功"。
        running = false;
    }

    /**
     * @return 是否已关闭
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * 提交一位 worker。用"包装任务是否在 {@code execute} 调用期间同步执行"区分
     * 内联 Executor 与异步池：内联时包装直接返回，由调用方继续自己的循环，从而
     * 既不递归、也不会出现两位 worker 并发。
     *
     * <p>不能用"是否同一个线程"判定：共享池完全可能把交接任务派给刚让出线程的
     * 那位 worker，此时线程相同但并非内联，误判会让队列永久滞留。</p>
     */
    private int scheduleWorker() {
        // 0 = execute 调用中；1 = 已返回。包装任务据此判断自己是不是同步执行的。
        final AtomicInteger phase = new AtomicInteger(0);
        final AtomicBoolean synchronous = new AtomicBoolean(false);
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (phase.get() == 0) {
                        synchronous.set(true);
                        return;
                    }
                    drain();
                }
            });
        } catch (RuntimeException ex) {
            log.warn("回调池拒绝 worker，按 OVERLOAD 处理: {}", ex.toString());
            return REJECTED;
        } catch (Error ex) {
            log.warn("回调池提交失败: {}", ex.toString());
            return REJECTED;
        }
        phase.set(1);
        return synchronous.get() ? INLINE : HANDED_OFF;
    }

    /**
     * worker 循环：每次取一个任务执行，然后把剩余工作交回共享池。
     * 单个任务抛错只记录并继续；任何退出路径都会复位 running。
     */
    private void drain() {
        for (;;) {
            final Runnable task;
            synchronized (this) {
                task = queue.pollFirst();
                if (task == null || closed) {
                    running = false;
                    return;
                }
            }
            try {
                task.run();
            } catch (Throwable t) {
                // 一个任务的 Error / RuntimeException 不能让后面所有任务（含完成屏障）停摆。
                log.warn("回调任务异常，已忽略并继续后续任务: {}", t.toString());
            }

            boolean more;
            synchronized (this) {
                more = !closed && !queue.isEmpty();
                if (!more) {
                    running = false;
                    return;
                }
            }
            // 一个任务后让出池线程（F-07）。接棒成功就退出；内联或池拒绝时
            // 继续在本线程消化，绝不让队列里的任务静默滞留。
            if (scheduleWorker() == HANDED_OFF) {
                return;
            }
        }
    }
}
