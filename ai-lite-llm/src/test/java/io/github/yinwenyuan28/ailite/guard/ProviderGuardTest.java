package io.github.yinwenyuan28.ailite.guard;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * ProviderGuard 单测（限流熔断计划 G0）：熔断状态机全转换（含探测超时）、
 * epoch 窗口滑动、闸门同步等待/超时、异步排队交接 FIFO 与取消/熔断竞态。
 */
public class ProviderGuardTest {

    private static final LlmCallException HTTP_500 =
            new LlmCallException("server error", null, LlmCallException.Kind.HTTP, 500);
    private static final LlmCallException HTTP_429 =
            new LlmCallException("rate limited", null, LlmCallException.Kind.HTTP, 429);
    private static final LlmCallException PARSE_EX =
            new LlmCallException("bad json", null, LlmCallException.Kind.PARSE);
    private static final LlmCallException CANCELLED_EX =
            new LlmCallException("aborted", null, LlmCallException.Kind.CANCELLED);

    /** 可拨动的假时钟。 */
    private static final class FakeClock implements LongSupplier {
        volatile long now = 1_000_000L;

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }

    /** 手动触发的延迟器：捕获超时任务，测试自行拨动时钟后触发。 */
    private static final class ManualDelayer implements ProviderGuard.Delayer {

        final List<Runnable> tasks = new CopyOnWriteArrayList<Runnable>();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delayMs) {
            tasks.add(task);
            return null;
        }
    }

    private static final class RecordingMetrics implements GuardMetrics {

        final List<String> rejected = new CopyOnWriteArrayList<String>();
        final List<Integer> states = new CopyOnWriteArrayList<Integer>();
        final AtomicInteger rateLimited = new AtomicInteger();

        @Override
        public void onRejected(String providerId, String reason) {
            rejected.add(reason);
        }

        @Override
        public void onCircuitState(String providerId, int state) {
            states.add(Integer.valueOf(state));
        }

        @Override
        public void onRateLimited(String providerId) {
            rateLimited.incrementAndGet();
        }
    }

    /** 记录授予/拒绝的监听器。 */
    private static final class RecordingListener implements ProviderGuard.AcquireListener {

        final AtomicReference<ProviderGuard.Permit> permit = new AtomicReference<ProviderGuard.Permit>();
        final AtomicReference<LlmCallException> rejection = new AtomicReference<LlmCallException>();
        final AtomicReference<String> grantedThread = new AtomicReference<String>();
        final CountDownLatch granted = new CountDownLatch(1);
        final CountDownLatch rejected = new CountDownLatch(1);

        @Override
        public void onGranted(ProviderGuard.Permit p) {
            permit.set(p);
            grantedThread.set(Thread.currentThread().getName());
            granted.countDown();
        }

        @Override
        public void onRejected(LlmCallException reason) {
            rejection.set(reason);
            rejected.countDown();
        }
    }

    private static ProviderGuard guard(int concurrency, long waitMs, int queueCapacity,
                                       ProviderGuard.CircuitSettings circuit, FakeClock clock,
                                       ManualDelayer delayer, RecordingMetrics metrics) {
        return new ProviderGuard("demo", concurrency, waitMs, queueCapacity, circuit,
                clock, delayer, metrics);
    }

    /** 熔断开启、门槛 3 样本、50% 失败率、窗口 60s、OPEN 30s、探测超时 30s。 */
    private static ProviderGuard.CircuitSettings defaultCircuit() {
        return new ProviderGuard.CircuitSettings(50, 60_000L, 3, 30_000L, 30_000L);
    }

    @Test
    public void constructorRejectsInvalidConcurrency() {
        assertThatThrownBy(() -> guard(0, 100, 10, null, new FakeClock(), null, new RecordingMetrics()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderGuard.CircuitSettings(0, 60_000L, 3, 30_000L, 30_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void closedAdmitsByDefault() {
        ProviderGuard guard = guard(2, 100, 10, defaultCircuit(), new FakeClock(),
                new ManualDelayer(), new RecordingMetrics());
        ProviderGuard.Permit first = guard.acquireSync();
        ProviderGuard.Permit second = guard.acquireSync();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        first.release();
        second.release();
    }

    @Test
    public void failureRateOpensCircuitAndRejectsWithoutFeeding() {
        FakeClock clock = new FakeClock();
        RecordingMetrics metrics = new RecordingMetrics();
        ProviderGuard guard = guard(10, 100, 10, defaultCircuit(), clock, new ManualDelayer(), metrics);

        ProviderGuard.Permit ok = guard.acquireSync();
        ok.verdict(null);
        ok.release();
        for (int i = 0; i < 2; i++) {
            ProviderGuard.Permit bad = guard.acquireSync();
            bad.verdict(HTTP_500);
            bad.release();
        }
        // 3 样本、2 失败 = 66% ≥ 50% → OPEN；后续获取毫秒级拒绝。
        assertThatThrownBy(() -> guard.acquireSync())
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN));
        assertThat(metrics.rejected).contains("circuit");
        assertThat(metrics.states).contains(1);
        // guard 自己的拒绝不回灌窗口：OPEN 期间继续拒绝不会改变任何状态。
        assertThatThrownBy(() -> guard.acquireSync());
    }

    @Test
    public void belowMinimumCallsDoesNotOpen() {
        ProviderGuard guard = guard(10, 100, 10, defaultCircuit(), new FakeClock(),
                new ManualDelayer(), new RecordingMetrics());
        ProviderGuard.Permit bad = guard.acquireSync();
        bad.verdict(HTTP_500);
        bad.release();
        // 1 样本 < 3：即便 100% 失败也不开。
        ProviderGuard.Permit next = guard.acquireSync();
        assertThat(next).isNotNull();
        next.release();
    }

    @Test
    public void openExpiresToHalfOpenProbeSuccessCloses() {
        FakeClock clock = new FakeClock();
        RecordingMetrics metrics = new RecordingMetrics();
        ProviderGuard guard = guard(10, 100, 10, defaultCircuit(), clock, new ManualDelayer(), metrics);
        openAtMinimumSamples(guard);

        clock.advance(30_001L);
        ProviderGuard.Permit probe = guard.acquireSync();
        probe.verdict(null);
        probe.release();
        assertThat(metrics.states).containsSequence(1, 2, 0);

        // CLOSED 后立即恢复放行。
        ProviderGuard.Permit normal = guard.acquireSync();
        normal.release();
    }

    @Test
    public void probeFailureReopensWithFreshTimer() {
        FakeClock clock = new FakeClock();
        ProviderGuard guard = guard(10, 100, 10, defaultCircuit(), clock, new ManualDelayer(),
                new RecordingMetrics());
        openAtMinimumSamples(guard);

        clock.advance(30_001L);
        ProviderGuard.Permit probe = guard.acquireSync();
        probe.verdict(HTTP_500);
        probe.release();
        // 探测失败 → 重新 OPEN 且重新计时：不足 openSeconds 前依旧拒绝。
        assertThatThrownBy(() -> guard.acquireSync())
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN));
        clock.advance(30_001L);
        ProviderGuard.Permit again = guard.acquireSync();
        again.release();
    }

    @Test
    public void probeTimeoutReopensInsteadOfStuckHalfOpen() {
        FakeClock clock = new FakeClock();
        ProviderGuard guard = guard(10, 100, 10, defaultCircuit(), clock, new ManualDelayer(),
                new RecordingMetrics());
        openAtMinimumSamples(guard);

        clock.advance(30_001L);
        ProviderGuard.Permit probe = guard.acquireSync();
        // 探测挂死不 verdict：超时后按失败回 OPEN。
        clock.advance(30_001L);
        assertThatThrownBy(() -> guard.acquireSync())
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN));
        clock.advance(30_001L);
        ProviderGuard.Permit reprobe = guard.acquireSync();
        reprobe.release();
    }

    @Test
    public void lateProbeVerdictAfterTimeoutIsIgnored() {
        FakeClock clock = new FakeClock();
        ProviderGuard guard = guard(10, 100, 10, defaultCircuit(), clock, new ManualDelayer(),
                new RecordingMetrics());
        openAtMinimumSamples(guard);

        clock.advance(30_001L);
        ProviderGuard.Permit probe = guard.acquireSync();
        clock.advance(30_001L);
        assertThatThrownBy(() -> guard.acquireSync());
        // 迟到的探测成功不能把已超时回 OPEN 的状态改成 CLOSED。
        probe.verdict(null);
        probe.release();
        assertThatThrownBy(() -> guard.acquireSync())
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN));
    }

    @Test
    public void parseAndCancelledDoNotCountAsFailures() {
        ProviderGuard guard = guard(10, 100, 10, defaultCircuit(), new FakeClock(),
                new ManualDelayer(), new RecordingMetrics());
        for (LlmCallException ex : new LlmCallException[]{PARSE_EX, PARSE_EX, HTTP_500, CANCELLED_EX}) {
            ProviderGuard.Permit permit = guard.acquireSync();
            permit.verdict(ex);
            permit.release();
        }
        // 4 样本、1 失败 = 25% < 50%：PARSE/CANCELLED 只计样本不计失败。
        ProviderGuard.Permit next = guard.acquireSync();
        next.release();
    }

    @Test
    public void windowResetsAfterProbeSuccess() {
        FakeClock clock = new FakeClock();
        ProviderGuard guard = guard(10, 100, 10, defaultCircuit(), clock, new ManualDelayer(),
                new RecordingMetrics());
        openAtMinimumSamples(guard);
        clock.advance(30_001L);
        ProviderGuard.Permit probe = guard.acquireSync();
        probe.verdict(null);
        probe.release();

        // 窗口已重置：1 个失败远不到门槛 3；若未重置则为 3 样本 100% 会开。
        ProviderGuard.Permit late = guard.acquireSync();
        late.verdict(HTTP_500);
        late.release();
        ProviderGuard.Permit still = guard.acquireSync();
        still.release();
    }

    @Test
    public void windowSlidesWithEpochRollover() {
        FakeClock clock = new FakeClock();
        ProviderGuard.CircuitSettings windowCircuit =
                new ProviderGuard.CircuitSettings(50, 60_000L, 2, 30_000L, 30_000L);
        ProviderGuard guard = guard(10, 100, 10, windowCircuit, clock, new ManualDelayer(),
                new RecordingMetrics());

        ProviderGuard.Permit first = guard.acquireSync();
        first.verdict(HTTP_500);
        first.release();
        // 前进整窗：旧切片过期，只剩 1 样本 < 门槛 2，不开。
        clock.advance(61_000L);
        ProviderGuard.Permit second = guard.acquireSync();
        second.verdict(HTTP_500);
        second.release();
        ProviderGuard.Permit stillOpen = guard.acquireSync();
        stillOpen.release();
        // 同窗口内再补 1 个 429：新窗口 2 样本 100% 失败（D3 计 429）→ 开。
        ProviderGuard.Permit more = guard.acquireSync();
        more.verdict(HTTP_429);
        more.release();
        assertThatThrownBy(() -> guard.acquireSync())
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN));
    }

    @Test
    public void syncThirdCallerWaitsThenSucceeds() throws Exception {
        FakeClock clock = new FakeClock();
        ProviderGuard guard = guard(1, 5_000, 10, null, clock, new ManualDelayer(),
                new RecordingMetrics());
        final ProviderGuard.Permit first = guard.acquireSync();

        final AtomicReference<ProviderGuard.Permit> secondRef = new AtomicReference<ProviderGuard.Permit>();
        final CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                secondRef.set(guard.acquireSync());
                done.countDown();
            }
        });
        waiter.start();
        Thread.sleep(100L);
        assertThat(done.getCount()).isEqualTo(1L);

        first.verdict(null);
        first.release();
        assertThat(done.await(5L, TimeUnit.SECONDS)).isTrue();
        assertThat(secondRef.get()).isNotNull();
        secondRef.get().release();
    }

    @Test
    public void syncWaitTimesOutWithOverload() {
        RecordingMetrics metrics = new RecordingMetrics();
        // 等待超时依赖真实时间流逝（awaitNanos 按真实时钟 park），这里必须用真时钟。
        ProviderGuard guard = new ProviderGuard("demo", 1, 80, 10, null,
                System::currentTimeMillis, null, metrics);
        ProviderGuard.Permit first = guard.acquireSync();

        long started = System.currentTimeMillis();
        assertThatThrownBy(guard::acquireSync)
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.OVERLOAD));
        assertThat(System.currentTimeMillis() - started).isBetween(60L, 5000L);
        assertThat(metrics.rejected).contains("concurrency");
        first.release();
    }

    @Test
    public void asyncFastPathGrantsSynchronouslyInCallerThread() {
        ProviderGuard guard = guard(1, 100, 10, null, new FakeClock(), new ManualDelayer(),
                new RecordingMetrics());
        RecordingListener listener = new RecordingListener();
        guard.acquireAsync(listener, () -> true, Runnable::run);
        assertThat(listener.granted.getCount()).isZero();
        assertThat(listener.grantedThread.get()).isEqualTo(Thread.currentThread().getName());
        listener.permit.get().release();
    }

    @Test
    public void asyncQueueHandoverRunsOnDispatcherInFifoOrder() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ProviderGuard guard = guard(1, 60_000, 10, null, new FakeClock(), new ManualDelayer(),
                    new RecordingMetrics());
            final ProviderGuard.Permit first = guard.acquireSync();
            RecordingListener a = new RecordingListener();
            RecordingListener b = new RecordingListener();
            guard.acquireAsync(a, () -> true, pool);
            guard.acquireAsync(b, () -> true, pool);
            assertThat(a.granted.getCount()).isEqualTo(1L);
            assertThat(b.granted.getCount()).isEqualTo(1L);

            first.release();
            assertThat(a.granted.await(5L, TimeUnit.SECONDS)).isTrue();
            assertThat(b.granted.getCount()).isEqualTo(1L);
            // 续跑不在释放方线程上（线程纪律）。
            assertThat(a.grantedThread.get()).isNotEqualTo(Thread.currentThread().getName());

            a.permit.get().release();
            assertThat(b.granted.await(5L, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void asyncQueueFullRejectsImmediately() {
        ProviderGuard guard = guard(1, 60_000, 1, null, new FakeClock(), new ManualDelayer(),
                new RecordingMetrics());
        ProviderGuard.Permit first = guard.acquireSync();
        guard.acquireAsync(new RecordingListener(), () -> true, Runnable::run);
        assertThatThrownBy(() -> guard.acquireAsync(new RecordingListener(), () -> true, Runnable::run))
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.OVERLOAD));
        first.release();
    }

    @Test
    public void asyncQueueTimeoutRejectsAndEntryIsRemoved() throws Exception {
        FakeClock clock = new FakeClock();
        ManualDelayer delayer = new ManualDelayer();
        ProviderGuard guard = guard(1, 100, 10, null, clock, delayer, new RecordingMetrics());
        ProviderGuard.Permit first = guard.acquireSync();
        RecordingListener queued = new RecordingListener();
        guard.acquireAsync(queued, () -> true, Runnable::run);
        assertThat(delayer.tasks).hasSize(1);

        clock.advance(100L);
        delayer.tasks.get(0).run();
        assertThat(queued.rejected.await(5L, TimeUnit.SECONDS)).isTrue();
        assertThat(queued.rejection.get().getKind()).isEqualTo(LlmCallException.Kind.OVERLOAD);

        // 超时条目已移除：释放后许可回池，新调用快速拿到，而不是交给死条目。
        first.release();
        RecordingListener fresh = new RecordingListener();
        guard.acquireAsync(fresh, () -> true, Runnable::run);
        assertThat(fresh.granted.getCount()).isZero();
        assertThat(queued.granted.getCount()).isEqualTo(1L);
        fresh.permit.get().release();
    }

    @Test
    public void deadWaiterSkippedAtHandover() {
        ProviderGuard guard = guard(1, 60_000, 10, null, new FakeClock(), new ManualDelayer(),
                new RecordingMetrics());
        ProviderGuard.Permit first = guard.acquireSync();
        RecordingListener dead = new RecordingListener();
        guard.acquireAsync(dead, () -> false, Runnable::run);
        first.release();

        assertThat(dead.granted.getCount()).isEqualTo(1L);
        assertThat(dead.rejected.getCount()).isEqualTo(1L);
        RecordingListener alive = new RecordingListener();
        guard.acquireAsync(alive, () -> true, Runnable::run);
        assertThat(alive.granted.getCount()).isZero();
        alive.permit.get().release();
    }

    @Test
    public void handoverRechecksCircuitAndRejectsWithCircuitOpen() throws Exception {
        FakeClock clock = new FakeClock();
        ProviderGuard.CircuitSettings touchy =
                new ProviderGuard.CircuitSettings(1, 60_000L, 1, 30_000L, 30_000L);
        ProviderGuard guard = guard(2, 60_000, 10, touchy, clock, new ManualDelayer(),
                new RecordingMetrics());
        ProviderGuard.Permit first = guard.acquireSync();
        ProviderGuard.Permit second = guard.acquireSync();
        RecordingListener queued = new RecordingListener();
        guard.acquireAsync(queued, () -> true, Runnable::run);

        // 排队期间熔断翻开：交接时刻重查，按熔断拒绝而非放行。
        first.verdict(HTTP_500);
        first.release();
        assertThat(queued.rejected.await(5L, TimeUnit.SECONDS)).isTrue();
        assertThat(queued.rejection.get().getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN);
        second.release();
    }

    @Test
    public void unlimitedConcurrencyGuardStillBreaks() {
        FakeClock clock = new FakeClock();
        ProviderGuard.CircuitSettings touchy =
                new ProviderGuard.CircuitSettings(1, 60_000L, 1, 30_000L, 30_000L);
        ProviderGuard guard = guard(Integer.MAX_VALUE, 100, 10, touchy, clock,
                new ManualDelayer(), new RecordingMetrics());
        for (int i = 0; i < 5; i++) {
            ProviderGuard.Permit permit = guard.acquireSync();
            permit.verdict(null);
            permit.release();
        }
        ProviderGuard.Permit bad = guard.acquireSync();
        bad.verdict(HTTP_500);
        bad.release();
        assertThatThrownBy(() -> guard.acquireSync())
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN));
    }

    @Test
    public void zeroWaitRejectsImmediatelyWhenFull() {
        ProviderGuard guard = guard(1, 0, 10, null, new FakeClock(), new ManualDelayer(),
                new RecordingMetrics());
        ProviderGuard.Permit first = guard.acquireSync();
        assertThatThrownBy(guard::acquireSync)
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.OVERLOAD));
        assertThatThrownBy(() -> guard.acquireAsync(new RecordingListener(), () -> true, Runnable::run))
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.OVERLOAD));
        first.release();
    }

    @Test
    public void fourTwentyNineCountsAsFailureAndRateLimitedMetric() {
        FakeClock clock = new FakeClock();
        RecordingMetrics metrics = new RecordingMetrics();
        ProviderGuard.CircuitSettings touchy =
                new ProviderGuard.CircuitSettings(1, 60_000L, 1, 30_000L, 30_000L);
        ProviderGuard guard = guard(10, 100, 10, touchy, clock, new ManualDelayer(), metrics);
        ProviderGuard.Permit bad = guard.acquireSync();
        bad.verdict(HTTP_429);
        bad.release();
        assertThat(metrics.rateLimited.get()).isEqualTo(1);
        assertThatThrownBy(() -> guard.acquireSync())
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN));
    }

    @Test
    public void unknownIoCauseCountsAsFailure() {
        FakeClock clock = new FakeClock();
        ProviderGuard.CircuitSettings touchy =
                new ProviderGuard.CircuitSettings(1, 60_000L, 1, 30_000L, 30_000L);
        ProviderGuard guard = guard(10, 100, 10, touchy, clock, new ManualDelayer(),
                new RecordingMetrics());
        ProviderGuard.Permit bad = guard.acquireSync();
        bad.verdict(new LlmCallException("io", new IOException("reset"),
                LlmCallException.Kind.UNKNOWN));
        bad.release();
        assertThatThrownBy(() -> guard.acquireSync())
                .isInstanceOfSatisfying(LlmCallException.class, ex ->
                        assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN));
    }

    @Test
    public void circuitDisabledStillReportsRateLimitedMetric() {
        RecordingMetrics metrics = new RecordingMetrics();
        ProviderGuard guard = guard(1, 100, 10, null, new FakeClock(), new ManualDelayer(), metrics);
        ProviderGuard.Permit permit = guard.acquireSync();
        permit.verdict(HTTP_429);
        permit.release();
        assertThat(metrics.rateLimited.get()).isEqualTo(1);
        // 熔断关闭：状态恒 CLOSED，许可照常发放。
        assertThat(guard.circuitStateOrdinal()).isZero();
        assertThat(guard.acquireSync()).isNotNull();
    }

    @Test
    public void interruptedSyncWaitRestoresFlagAndThrowsTransport() throws Exception {
        FakeClock clock = new FakeClock();
        ProviderGuard guard = guard(1, 60_000, 10, null, clock, new ManualDelayer(),
                new RecordingMetrics());
        final ProviderGuard.Permit first = guard.acquireSync();

        final AtomicReference<Throwable> caught = new AtomicReference<Throwable>();
        // 标志位在线程内断言：线程终止后 isInterrupted 的保留语义不作为契约。
        final AtomicBoolean flagRestored = new AtomicBoolean(false);
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    guard.acquireSync();
                    fail("应当抛出中断异常");
                } catch (Throwable ex) {
                    caught.set(ex);
                    flagRestored.set(Thread.currentThread().isInterrupted());
                }
            }
        });
        waiter.start();
        Thread.sleep(100L);
        waiter.interrupt();
        waiter.join(5000L);
        assertThat(caught.get()).isInstanceOfSatisfying(LlmCallException.class, ex ->
                assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.TRANSPORT));
        assertThat(flagRestored.get()).isTrue();
        // 被中断的等待者不占许可。
        first.release();
        RecordingListener fresh = new RecordingListener();
        guard.acquireAsync(fresh, () -> true, Runnable::run);
        assertThat(fresh.granted.getCount()).isZero();
    }

    /** 用最小样本凑出一次 OPEN（1 成功 + 2 失败 = 3 样本 66%）。 */
    private static void openAtMinimumSamples(ProviderGuard guard) {
        ProviderGuard.Permit ok = guard.acquireSync();
        ok.verdict(null);
        ok.release();
        for (int i = 0; i < 2; i++) {
            ProviderGuard.Permit bad = guard.acquireSync();
            bad.verdict(HTTP_500);
            bad.release();
        }
    }
}
