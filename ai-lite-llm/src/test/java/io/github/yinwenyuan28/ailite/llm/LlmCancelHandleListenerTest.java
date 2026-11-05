package io.github.yinwenyuan28.ailite.llm;

import org.junit.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取消监听器（AL-H05 配套）的语义：恰好一次、已取消后注册立即触发、异常不影响他人。
 */
public class LlmCancelHandleListenerTest {

    @Test
    public void listenerFiresExactlyOnceUnderRepeatedCancel() {
        LlmCancelHandle handle = new LlmCancelHandle();
        AtomicInteger fired = new AtomicInteger();
        handle.onCancel(fired::incrementAndGet);

        handle.cancel();
        handle.cancel();
        handle.cancel();

        assertThat(fired.get()).isEqualTo(1);
        assertThat(handle.isCancelled()).isTrue();
    }

    @Test
    public void listenerRegisteredAfterCancelFiresImmediately() {
        LlmCancelHandle handle = new LlmCancelHandle();
        handle.cancel();

        AtomicInteger fired = new AtomicInteger();
        handle.onCancel(fired::incrementAndGet);

        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    public void listenerExceptionIsSwallowedAndOthersStillRun() throws Exception {
        LlmCancelHandle handle = new LlmCancelHandle();
        CountDownLatch second = new CountDownLatch(1);
        handle.onCancel(() -> {
            throw new IllegalStateException("listener boom");
        });
        handle.onCancel(second::countDown);

        handle.cancel();

        assertThat(second.await(1L, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void registerDuringCancelRaceFiresAtMostOnce() throws Exception {
        // 注册与取消并发：监听器要么在注册时立即触发，要么被 cancel 遍历触发，总量恰好一次。
        LlmCancelHandle handle = new LlmCancelHandle();
        final AtomicInteger fired = new AtomicInteger();
        final CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<Throwable>();
        Thread canceller = new Thread(() -> {
            try {
                handle.cancel();
            } catch (Throwable ex) {
                errors.add(ex);
            }
        });
        canceller.start();
        handle.onCancel(fired::incrementAndGet);
        canceller.join(1000L);

        assertThat(errors).isEmpty();
        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    public void listenerErrorIsSwallowedAndOthersStillRun() throws Exception {
        // F-16：监听器抛 Error 时后续监听器（含网关的终态收敛器）仍须执行。
        LlmCancelHandle handle = new LlmCancelHandle();
        CountDownLatch second = new CountDownLatch(1);
        handle.onCancel(() -> {
            throw new AssertionError("listener error");
        });
        handle.onCancel(second::countDown);

        handle.cancel();

        assertThat(second.await(1L, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void abortFailureDoesNotSkipCancelListeners() {
        // F-16：abort 动作抛 Error 不能越过 cancel() 的 finally，否则 future 永挂。
        LlmCancelHandle handle = new LlmCancelHandle();
        handle.bindAbort(() -> {
            throw new AssertionError("abort error");
        });
        AtomicInteger listener = new AtomicInteger();
        handle.onCancel(listener::incrementAndGet);

        handle.cancel();

        assertThat(listener.get()).isEqualTo(1);
        assertThat(handle.isCancelled()).isTrue();
    }

    @Test
    public void abortBindingStillRunsOnceAfterCancelWithListeners() {
        LlmCancelHandle handle = new LlmCancelHandle();
        AtomicInteger listener = new AtomicInteger();
        handle.onCancel(listener::incrementAndGet);
        handle.cancel();
        AtomicInteger aborted = new AtomicInteger();

        handle.bindAbort(aborted::incrementAndGet);

        assertThat(aborted.get()).isEqualTo(1);
        assertThat(listener.get()).isEqualTo(1);
    }
}
