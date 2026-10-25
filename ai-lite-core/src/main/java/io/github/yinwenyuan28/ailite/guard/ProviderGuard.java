package io.github.yinwenyuan28.ailite.guard;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.RetryPolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一个供应商的出口自我保护：并发闸门 + 熔断状态机（限流熔断计划 §3.1/§3.2）。
 *
 * <p>许可粒度是<b>单次模型调用</b>（每次 transport 调用一个许可，D4）：
 * 工具循环的每一轮各自 acquire/release，工具执行期间不占许可。
 * 同步等待与异步排队共用同一把锁和同一个许可池，避免两套入口互相饿死。</p>
 *
 * <p>并发闸门自研（JDK Semaphore 没有 release 监听，无法做异步排队交接）：
 * {@code ReentrantLock + Condition + ArrayDeque<Waiter>}。release 时把许可
 * <b>直接交接</b>给队头存活 waiter（不做真 release），天然无丢唤醒、无双发、
 * FIFO 公平；队列空才真正归还许可并唤醒同步等待者。</p>
 *
 * <p>熔断失败口径<b>直调</b> {@link RetryPolicy#defaultRetryable}（单一事实来源，
 * 禁止复述）；guard 自身合成的拒绝（OVERLOAD/CIRCUIT_OPEN）不产生采样，
 * 不回灌窗口，防止自我强化。</p>
 *
 * <p>线程纪律：回调（onGranted/onRejected）一律在锁外执行——快速路径在
 * acquire 调用线程同步回调，排队路径投递到调用方给的载体执行器，绝不占用
 * release 方的传输回调线程。时钟通过 {@code LongSupplier} 注入，测试可拨动。</p>
 */
public final class ProviderGuard {

    /** 一次模型调用的许可句柄：release 归还并发许可，verdict 采样熔断窗口。 */
    public interface Permit {

        /**
         * 采样本次传输结局。error 为 null 记成功；非 null 按
         * {@link RetryPolicy#defaultRetryable} 口径判定失败（429/5xx/IO 记失败，
         * PARSE/CONFIG/CANCELLED/400 只计样本不计失败）。每个许可至多采样一次。
         * 建议在 release 之前调用，交接给下一个人时熔断状态已是本局的结论。
         *
         * @param error 传输完成异常；成功为 null（可为未解包的 CompletionException）
         */
        void verdict(Throwable error);

        /** 归还并发许可（幂等）。不采样；未发起传输就放弃的轮次只调它。 */
        void release();
    }

    /** 异步获取回调。快速路径在 acquireAsync 调用线程<b>同步</b>触发 onGranted。 */
    public interface AcquireListener {

        /** 拿到许可，可以发起传输。 */
        void onGranted(Permit permit);

        /**
         * 仅排队超时路径异步触发（载体线程上）；并发满/队列满/熔断开启这类
         * 立即拒绝直接从 acquireAsync 抛出，不走这里。
         *
         * @param reason OVERLOAD 语义的拒绝异常
         */
        void onRejected(LlmCallException reason);
    }

    /** 排队者存活探测：false 表示结果已被取消等其他路径收敛，交接时跳过。 */
    public interface AliveCheck {

        /**
         * @return 该轮次是否仍在等许可
         */
        boolean isAlive();
    }

    /** 排队时限的延迟任务来源；null 表示只在交接时惰性判超时（无定时器兜底）。 */
    public interface Delayer {

        /**
         * @param task    延迟任务
         * @param delayMs 延迟毫秒
         * @return 可取消句柄；无法安排时返回 null（调用方退化为惰性判超时）
         */
        ScheduledFuture<?> schedule(Runnable task, long delayMs);
    }

    /** 熔断参数快照（启用即非 null）。 */
    public static final class CircuitSettings {

        private final int failureRatePercent;
        private final long windowMs;
        private final int minimumCalls;
        private final long openMs;
        private final long probeTimeoutMs;

        /**
         * @param failureRatePercent 窗口失败率阈值（%，1～100）
         * @param windowMs           统计窗口毫秒（内部按 6 片环形滑动）
         * @param minimumCalls       触发门槛的最少样本数
         * @param openMs             OPEN 冷却时长，期满进 HALF_OPEN
         * @param probeTimeoutMs     半开探测超时，超时按探测失败回 OPEN
         */
        public CircuitSettings(int failureRatePercent, long windowMs, int minimumCalls,
                               long openMs, long probeTimeoutMs) {
            if (failureRatePercent < 1 || failureRatePercent > 100) {
                throw new IllegalArgumentException("failureRatePercent 必须在 1~100");
            }
            if (windowMs <= 0L || minimumCalls < 1 || openMs <= 0L || probeTimeoutMs <= 0L) {
                throw new IllegalArgumentException("熔断参数必须为正数");
            }
            this.failureRatePercent = failureRatePercent;
            this.windowMs = windowMs;
            this.minimumCalls = minimumCalls;
            this.openMs = openMs;
            this.probeTimeoutMs = probeTimeoutMs;
        }
    }

    private static final int SLICE_COUNT = 6;

    private final String providerId;
    /** Integer.MAX_VALUE 表示不限并发（只做熔断的 guard）。 */
    private final int concurrency;
    private final long waitMs;
    private final int queueCapacity;
    private final CircuitSettings circuit;
    private final GuardMetrics metrics;
    private final java.util.function.LongSupplier clock;
    private final Delayer delayer;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final ArrayDeque<AsyncWaiter> waiters = new ArrayDeque<AsyncWaiter>();

    private int inUse;

    // 熔断状态（锁内读写）。
    private int state = 0; // 0=CLOSED, 1=OPEN, 2=HALF_OPEN
    private long openUntil;
    private boolean probeInFlight;
    private long probeDeadline;

    // 环形窗口：每片带 epoch 标签，写时发现过期整片作废（审查 F9）。
    private final long sliceMs;
    private final long[] sliceEpoch = new long[SLICE_COUNT];
    private final int[] sliceTotal = new int[SLICE_COUNT];
    private final int[] sliceFail = new int[SLICE_COUNT];

    /**
     * @param providerId        供应商 id（异常消息与指标标签用）
     * @param concurrency       并发许可数；必须大于 0，不限并发传 Integer.MAX_VALUE
     * @param concurrencyWaitMs 同步等待/异步排队的时长上限；小于等于 0 表示不等待
     * @param asyncQueueCapacity 异步排队容量，超出即拒绝
     * @param circuit           熔断参数；null 表示熔断关闭（verdict 只喂指标）
     * @param clock             毫秒时钟（测试可注入）
     * @param delayer           排队超时定时器来源；null 退化为交接时惰性判超时
     * @param metrics           指标回调；null 按 no-op 处理
     */
    public ProviderGuard(String providerId, int concurrency, long concurrencyWaitMs,
                         int asyncQueueCapacity, CircuitSettings circuit,
                         java.util.function.LongSupplier clock, Delayer delayer,
                         GuardMetrics metrics) {
        if (concurrency <= 0) {
            throw new IllegalArgumentException("concurrency 必须大于 0（不限并发由调用方不建 guard 表达）");
        }
        this.providerId = providerId;
        this.concurrency = concurrency;
        this.waitMs = Math.max(0L, concurrencyWaitMs);
        this.queueCapacity = Math.max(0, asyncQueueCapacity);
        this.circuit = circuit;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.delayer = delayer;
        this.metrics = metrics == null ? GuardMetrics.NOOP : metrics;
        this.sliceMs = circuit == null ? 10000L : Math.max(1L, circuit.windowMs / SLICE_COUNT);
    }

    /**
     * 同步获取许可：有空位立即返回；否则 park 等待，最长 concurrencyWaitMs。
     * 调用方线程会被占用——这是同步入口的本意，异步入口请用 {@link #acquireAsync}。
     *
     * @return 许可
     * @throws LlmCallException OVERLOAD（等待超时）或 CIRCUIT_OPEN（熔断开启）
     */
    public Permit acquireSync() {
        long deadline = clock.getAsLong() + waitMs;
        lock.lock();
        try {
            while (true) {
                boolean[] probeOut = new boolean[1];
                if (!admitCircuitLocked(clock.getAsLong(), probeOut)) {
                    metrics.onRejected(providerId, "circuit");
                    throw reject("熔断开启，快速失败", LlmCallException.Kind.CIRCUIT_OPEN);
                }
                if (inUse < concurrency) {
                    inUse++;
                    return new RoundPermit(probeOut[0]);
                }
                long remaining = deadline - clock.getAsLong();
                if (remaining <= 0L) {
                    metrics.onRejected(providerId, "concurrency");
                    throw reject("并发等待超时", LlmCallException.Kind.OVERLOAD);
                }
                try {
                    available.awaitNanos(TimeUnit.MILLISECONDS.toNanos(remaining));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new LlmCallException("并发等待被中断", ex, LlmCallException.Kind.TRANSPORT);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 异步获取许可：不阻塞调用线程。
     * 有空位→立即在调用线程同步回调 onGranted；无空位→入队，交接时把
     * onGranted 投递到 {@code dispatcher}；排队超时在 dispatcher 上回调
     * onRejected。立即拒绝（熔断开/队列满/不等待）同步抛出。
     *
     * @param listener   获取回调
     * @param alive      排队期间存活探测（交接时校验）
     * @param dispatcher 交接/超时回调的载体执行器
     * @throws LlmCallException OVERLOAD（队列满）或 CIRCUIT_OPEN（熔断开启）
     */
    public void acquireAsync(AcquireListener listener, AliveCheck alive,
                             java.util.concurrent.Executor dispatcher) {
        if (listener == null || alive == null || dispatcher == null) {
            throw new IllegalArgumentException("listener/alive/dispatcher 不能为空");
        }
        Permit fastPermit = null;
        lock.lock();
        try {
            boolean[] probeOut = new boolean[1];
            if (!admitCircuitLocked(clock.getAsLong(), probeOut)) {
                metrics.onRejected(providerId, "circuit");
                throw reject("熔断开启，快速失败", LlmCallException.Kind.CIRCUIT_OPEN);
            }
            if (inUse < concurrency) {
                inUse++;
                fastPermit = new RoundPermit(probeOut[0]);
            } else {
                if (waitMs <= 0L || waiters.size() >= queueCapacity) {
                    metrics.onRejected(providerId, "concurrency");
                    throw reject(waitMs <= 0L ? "并发已满且未开启等待" : "并发排队已满",
                            LlmCallException.Kind.OVERLOAD);
                }
                AsyncWaiter waiter = new AsyncWaiter(listener, alive, dispatcher,
                        clock.getAsLong());
                waiters.addLast(waiter);
                if (delayer != null) {
                    try {
                        waiter.expiry = delayer.schedule(new Runnable() {
                            @Override
                            public void run() {
                                expireWaiter(waiter);
                            }
                        }, waitMs);
                    } catch (RuntimeException scheduleFailure) {
                        // 调度器拒绝：退化为交接时惰性判超时，不影响正确性。
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        if (fastPermit != null) {
            listener.onGranted(fastPermit);
        }
    }

    /**
     * @return 熔断状态序号（0/1/2），供 Gauge 轮询；熔断关闭恒为 0
     */
    public int circuitStateOrdinal() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // 以下均为锁内原语；回调一律由调用方在锁外执行。
    // ------------------------------------------------------------------

    /**
     * 熔断准入（审查 F7：交接时刻也要重查）。OPEN 期满在这里翻 HALF_OPEN 并
     * 授予探测资格；探测超时在这里按失败回 OPEN。
     *
     * @return true 放行；probeOut[0] 指示本次是否为半开探测
     */
    private boolean admitCircuitLocked(long now, boolean[] probeOut) {
        probeOut[0] = false;
        if (circuit == null) {
            return true;
        }
        if (state == 0) {
            return true;
        }
        if (state == 1) {
            if (now >= openUntil) {
                state = 2;
                probeInFlight = true;
                probeDeadline = now + circuit.probeTimeoutMs;
                metrics.onCircuitState(providerId, 2);
                probeOut[0] = true;
                return true;
            }
            return false;
        }
        // HALF_OPEN：探测在途，其他人一律拒绝。
        if (probeInFlight && now < probeDeadline) {
            return false;
        }
        // 探测超时未归（或状态异常）→ 按探测失败回 OPEN 重新计时。
        probeInFlight = false;
        state = 1;
        openUntil = now + circuit.openMs;
        metrics.onCircuitState(providerId, 1);
        return false;
    }

    /** 交接或归还许可。返回需要锁外执行的回调动作。 */
    private List<Runnable> transferOrReturnLocked() {
        List<Runnable> after = new ArrayList<Runnable>(2);
        long now = clock.getAsLong();
        while (!waiters.isEmpty()) {
            AsyncWaiter head = waiters.peekFirst();
            if (!head.alive.isAlive()) {
                dropWaiterLocked(head);
                continue;
            }
            if (now - head.enqueuedAt >= waitMs) {
                dropWaiterLocked(head);
                final AsyncWaiter dead = head;
                after.add(new Runnable() {
                    @Override
                    public void run() {
                        dispatchReject(dead, reject("并发排队超时", LlmCallException.Kind.OVERLOAD));
                    }
                });
                continue;
            }
            boolean[] probeOut = new boolean[1];
            if (!admitCircuitLocked(now, probeOut)) {
                dropWaiterLocked(head);
                // 排队中熔断翻开：按熔断拒绝该 waiter，许可继续看下一个。
                final AsyncWaiter dead = head;
                after.add(new Runnable() {
                    @Override
                    public void run() {
                        dispatchReject(dead, reject("熔断开启，快速失败",
                                LlmCallException.Kind.CIRCUIT_OPEN));
                    }
                });
                continue;
            }
            dropWaiterLocked(head);
            final AsyncWaiter target = head;
            final boolean probe = probeOut[0];
            // 许可直接转移（inUse 不变），被唤醒方在载体池上续跑。
            after.add(new Runnable() {
                @Override
                public void run() {
                    dispatchGrant(target, probe);
                }
            });
            return after;
        }
        inUse--;
        available.signalAll();
        return after;
    }

    private void dropWaiterLocked(AsyncWaiter waiter) {
        waiters.pollFirst();
        waiter.removed = true;
        if (waiter.expiry != null) {
            waiter.expiry.cancel(false);
            waiter.expiry = null;
        }
    }

    private void dispatchGrant(AsyncWaiter waiter, boolean probe) {
        Permit permit = new RoundPermit(probe);
        try {
            waiter.dispatcher.execute(new Runnable() {
                @Override
                public void run() {
                    waiter.listener.onGranted(permit);
                }
            });
        } catch (RejectedExecutionException ex) {
            // 载体池拒绝：许可立即回流，不能泄漏。
            permit.release();
        }
    }

    private void dispatchReject(AsyncWaiter waiter, LlmCallException reason) {
        metrics.onRejected(providerId, reason.getKind() == LlmCallException.Kind.CIRCUIT_OPEN
                ? "circuit" : "concurrency");
        try {
            waiter.dispatcher.execute(new Runnable() {
                @Override
                public void run() {
                    waiter.listener.onRejected(reason);
                }
            });
        } catch (RejectedExecutionException ex) {
            // 载体池已关闭：应用正在停机，没有安全出口；记日志级别的事件即可。
        }
    }

    /** 排队超时任务（delayer 触发）：仍在队则移除并异步拒绝。 */
    private void expireWaiter(final AsyncWaiter waiter) {
        boolean fire = false;
        lock.lock();
        try {
            if (!waiter.removed && waiters.remove(waiter)) {
                waiter.removed = true;
                fire = true;
            }
        } finally {
            lock.unlock();
        }
        if (fire) {
            dispatchReject(waiter, reject("并发排队超时", LlmCallException.Kind.OVERLOAD));
        }
    }

    private LlmCallException reject(String action, LlmCallException.Kind kind) {
        return new LlmCallException("供应商 " + providerId + " " + action, null, kind);
    }

    /** 熔断采样：记录窗口/探测结论。调用方已持有许可且尚未采样。 */
    private void sampleLocked(RoundPermit permit, boolean failure, long now) {
        if (circuit == null) {
            return;
        }
        if (permit.probe) {
            if (!probeInFlight) {
                // 探测已超时回 OPEN，迟到的结论作废。
                return;
            }
            probeInFlight = false;
            if (failure) {
                state = 1;
                openUntil = now + circuit.openMs;
                metrics.onCircuitState(providerId, 1);
            } else {
                state = 0;
                resetWindowLocked();
                metrics.onCircuitState(providerId, 0);
            }
            return;
        }
        if (state != 0) {
            // OPEN/HALF_OPEN 期间非探测样本不回灌窗口。
            return;
        }
        windowRecordLocked(now, failure);
        int total = 0;
        int fails = 0;
        long liveEpochFloor = now / sliceMs - (SLICE_COUNT - 1);
        for (int i = 0; i < SLICE_COUNT; i++) {
            if (sliceEpoch[i] >= liveEpochFloor && sliceEpoch[i] > 0L) {
                total += sliceTotal[i];
                fails += sliceFail[i];
            }
        }
        if (total >= circuit.minimumCalls
                && fails * 100L >= (long) circuit.failureRatePercent * total) {
            state = 1;
            openUntil = now + circuit.openMs;
            metrics.onCircuitState(providerId, 1);
        }
    }

    private void windowRecordLocked(long now, boolean failure) {
        long epoch = now / sliceMs;
        int idx = (int) (epoch % SLICE_COUNT);
        if (sliceEpoch[idx] != epoch) {
            // epoch 换片：整片作废，避免滚动清零与并发自增互相丢计数。
            sliceEpoch[idx] = epoch;
            sliceTotal[idx] = 0;
            sliceFail[idx] = 0;
        }
        sliceTotal[idx]++;
        if (failure) {
            sliceFail[idx]++;
        }
    }

    private void resetWindowLocked() {
        Arrays.fill(sliceEpoch, 0L);
        Arrays.fill(sliceTotal, 0);
        Arrays.fill(sliceFail, 0);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** 异步排队者。 */
    private static final class AsyncWaiter {
        private final AcquireListener listener;
        private final AliveCheck alive;
        private final java.util.concurrent.Executor dispatcher;
        private final long enqueuedAt;
        private boolean removed;
        private ScheduledFuture<?> expiry;

        private AsyncWaiter(AcquireListener listener, AliveCheck alive,
                            java.util.concurrent.Executor dispatcher, long enqueuedAt) {
            this.listener = listener;
            this.alive = alive;
            this.dispatcher = dispatcher;
            this.enqueuedAt = enqueuedAt;
        }
    }

    /** 许可实现。verdict/release 各自幂等（独立标记）。 */
    private final class RoundPermit implements Permit {

        private final boolean probe;
        private boolean sampled;
        private boolean released;

        private RoundPermit(boolean probe) {
            this.probe = probe;
        }

        @Override
        public void verdict(Throwable error) {
            Throwable cause = unwrap(error);
            boolean failure = false;
            if (cause != null) {
                LlmCallException ex = cause instanceof LlmCallException
                        ? (LlmCallException) cause
                        : new LlmCallException("模型调用失败", cause);
                failure = RetryPolicy.defaultRetryable(ex);
                if (ex.getKind() == LlmCallException.Kind.HTTP && ex.getStatusCode() != null
                        && ex.getStatusCode().intValue() == 429) {
                    metrics.onRateLimited(providerId);
                }
            }
            lock.lock();
            try {
                if (sampled) {
                    return;
                }
                sampled = true;
                if (circuit != null) {
                    sampleLocked(this, failure, clock.getAsLong());
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void release() {
            List<Runnable> after;
            lock.lock();
            try {
                if (released) {
                    return;
                }
                released = true;
                after = transferOrReturnLocked();
            } finally {
                lock.unlock();
            }
            for (Runnable task : after) {
                task.run();
            }
        }
    }
}
