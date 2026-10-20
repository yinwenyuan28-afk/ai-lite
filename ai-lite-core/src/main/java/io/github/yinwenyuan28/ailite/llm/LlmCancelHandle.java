package io.github.yinwenyuan28.ailite.llm;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 协作式取消句柄。调用方创建后交给传输层，另一个线程调用 {@link #cancel()}。
 *
 * <p>取消是幂等的：重复调用不会重复执行 abort。传输层通过 {@link #bindAbort(Runnable)}
 * 绑定实际的连接中断动作；如果绑定前已经取消，会立即执行一次。</p>
 */
public final class LlmCancelHandle {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Runnable abort;

    /**
     * 请求取消当前调用。可以在任意线程调用。
     */
    public void cancel() {
        cancelled.set(true);
        Runnable action = abort;
        if (action != null) {
            action.run();
        }
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
            abort.run();
        }
    }
}
