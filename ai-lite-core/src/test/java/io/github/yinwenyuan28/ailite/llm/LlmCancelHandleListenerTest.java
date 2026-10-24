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
