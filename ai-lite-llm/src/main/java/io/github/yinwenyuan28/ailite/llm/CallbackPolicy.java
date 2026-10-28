package io.github.yinwenyuan28.ailite.llm;

import java.util.concurrent.Executor;

/**
 * 回调执行策略：用户 {@code onDelta}、Agent 钩子跑在哪里。
 *
 * <ul>
 *   <li>{@link Mode#INLINE}（默认）：维持现状——回调跑在触发它的线程上
 *       （传输/载体/调用方），零额外开销。</li>
 *   <li>{@link Mode#IO}：回调经由独立回调线程池上的 per-run
 *       {@link SerialCallbackExecutor} 串行执行，慢回调只阻塞自己，
 *       不拖慢共享 I/O 线程上的其他请求。</li>
 * </ul>
 *
 * <p>只对异步入口生效；同步入口恒 inline。IO 模式必须传入独立的共享回调池
 * （不复用 {@code ExecutorProvider.io()}，避免钩子阻塞占满工具/会话/日志线程、
 * 以及"回调等待 io() 任务"的死锁面）。</p>
 */
public final class CallbackPolicy {

    /**
     * 回调执行模式。
     */
    public enum Mode {

        /** 调用线程/触发线程内联执行（默认）。 */
        INLINE,

        /** 经独立回调池上的每 run 串行执行器执行。 */
        IO
    }

    private static final CallbackPolicy INLINE_POLICY =
            new CallbackPolicy(Mode.INLINE, 1024, null);

    private final Mode mode;
    private final int queueCapacity;
    private final Executor sharedExecutor;

    private CallbackPolicy(Mode mode, int queueCapacity, Executor sharedExecutor) {
        this.mode = mode;
        this.queueCapacity = Math.max(1, queueCapacity);
        this.sharedExecutor = sharedExecutor;
    }

    /**
     * inline 策略（默认）。
     */
    public static CallbackPolicy inline() {
        return INLINE_POLICY;
    }

    /**
     * io 策略。
     *
     * @param sharedExecutor 独立回调线程池（ai-lite-callback-*），不能为 null
     * @param queueCapacity  每 run 回调队列上限
     */
    public static CallbackPolicy io(Executor sharedExecutor, int queueCapacity) {
        if (sharedExecutor == null) {
            throw new IllegalArgumentException("回调共享线程池不能为空");
        }
        return new CallbackPolicy(Mode.IO, queueCapacity, sharedExecutor);
    }

    /**
     * @return 执行模式
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * @return 是否为 io 模式
     */
    public boolean isIo() {
        return mode == Mode.IO;
    }

    /**
     * 创建本次调用专用的串行回调执行器（每 run / 每次流式调用一个）。
     * inline 模式返回 null（调用方走内联路径）。
     */
    public SerialCallbackExecutor newSerialExecutor() {
        if (!isIo()) {
            return null;
        }
        return new SerialCallbackExecutor(sharedExecutor, queueCapacity);
    }
}
