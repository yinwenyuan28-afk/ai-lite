package io.github.yinwenyuan28.ailite.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 协作式取消句柄。调用方创建后交给传输层，另一个线程调用 {@link #cancel()}。
 *
 * <p>取消是幂等的：cancel 与 bindAbort 的任意交错下，实际的 abort 动作最多执行一次；
 * 取消监听器也恰好执行一次。传输层通过 {@link #bindAbort(Runnable)} 绑定连接中断动作；
 * 如果绑定前已经取消，会立即执行一次。网关等编排方通过 {@link #onCancel(Runnable)}
 * 注册取消监听（例如主动收敛挂起的 future），已取消的句柄上注册同样立即触发。</p>
 *
 * <p><b>一个句柄只服务一次调用。</b>第一次 abort 之后句柄进入终态，不会复位；
 * 再次用于新调用时，网关的入口检查会直接返回 {@code ABORTED}。需要重试或发起
 * 新请求时请创建新的句柄；监听器随句柄生命周期一起回收，无需显式解绑。</p>
 */
public final class LlmCancelHandle {

    private static final Logger log = LoggerFactory.getLogger(LlmCancelHandle.class);

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private volatile Runnable abort;
    private final CopyOnWriteArrayList<Runnable> cancelListeners = new CopyOnWriteArrayList<Runnable>();

    /**
     * 请求取消当前调用。可以在任意线程调用；重复调用不会重复执行 abort 或监听器
     * （cancelled 用 CAS 判定，只有第一个 cancel 生效）。
     */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        runAbort();
        runCancelListeners();
    }

    /**
     * @return 是否已经取消
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 由传输层绑定连接中断动作。传 null 表示解绑。
     *
     * @param abort 中断动作；通常关闭连接或流
     */
    public void bindAbort(Runnable abort) {
        this.abort = abort;
        if (abort != null && cancelled.get()) {
            runAbort();
        }
    }

    /**
     * 注册取消监听器（AL-H05）：cancel 触发时在取消方线程上执行，用于主动收敛
     * 挂起的 future 等非合作传输场景。已取消的句柄上注册会立即触发一次；
     * 监听器异常只记录，不影响后续监听器和 abort。
     *
     * @param listener 取消动作
     */
    public void onCancel(final Runnable listener) {
        if (listener == null) {
            return;
        }
        // 每个监听器包一层恰好一次闸门：注册/取消竞态下不被重复执行。
        final AtomicBoolean fired = new AtomicBoolean(false);
        final Runnable guarded = new Runnable() {
            @Override
            public void run() {
                if (!fired.compareAndSet(false, true)) {
                    return;
                }
                try {
                    listener.run();
                } catch (RuntimeException ex) {
                    log.warn("取消监听器异常，已忽略: {}", ex.toString());
                }
            }
        };
        cancelListeners.add(guarded);
        if (cancelled.get()) {
            guarded.run();
        }
    }

    /**
     * 只允许一个调用者真正执行 abort，避免 cancel 与 bindAbort 竞态时重复中断。
     */
    private void runAbort() {
        Runnable action = abort;
        if (action != null && aborted.compareAndSet(false, true)) {
            action.run();
        }
    }

    /** 逐个执行监听器；guarded 包装保证每个恰好一次。 */
    private void runCancelListeners() {
        for (Runnable listener : cancelListeners) {
            listener.run();
        }
    }
}
