package io.github.yinwenyuan28.ailite.llm;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 协作式取消句柄。调用方创建后交给传输层，另一个线程调用 {@link #cancel()}。
 *
 * <p>取消是幂等的：cancel 与 bindAbort 的任意交错下，实际的 abort 动作最多执行一次。
 * 传输层通过 {@link #bindAbort(Runnable)} 绑定连接中断动作；如果绑定前已经取消，
 * 会立即执行一次。</p>
 *
 * <p><b>一个句柄只服务一次调用。</b>第一次 abort 之后句柄进入终态，不会复位；
 * 再次用于新调用时，网关的入口检查会直接返回 {@code ABORTED}。需要重试或发起
 * 新请求时请创建新的句柄。</p>
 */
public final class LlmCancelHandle {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private volatile Runnable abort;

    /**
     * 请求取消当前调用。可以在任意线程调用；重复调用不会重复执行 abort。
     */
    public void cancel() {
        cancelled.set(true);
        runAbort();
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
     * 只允许一个调用者真正执行 abort，避免 cancel 与 bindAbort 竞态时重复中断。
     */
    private void runAbort() {
        Runnable action = abort;
        if (action != null && aborted.compareAndSet(false, true)) {
            action.run();
        }
    }
}
