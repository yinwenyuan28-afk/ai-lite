package io.github.yinwenyuan28.ailite.executor.virtual;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JDK 21 虚拟线程执行器：每个任务一个虚拟线程，关闭后拒绝新任务。
 */
public class VirtualThreadExecutorProviderTest {

    @Test
    public void runsEachTaskOnAVirtualThread() throws Exception {
        VirtualThreadExecutorProvider provider = new VirtualThreadExecutorProvider();
        try {
            int tasks = 100;
            final CountDownLatch done = new CountDownLatch(tasks);
            final AtomicInteger virtualThreads = new AtomicInteger();
            for (int i = 0; i < tasks; i++) {
                provider.io().execute(() -> {
                    if (Thread.currentThread().isVirtual()) {
                        virtualThreads.incrementAndGet();
                    }
                    done.countDown();
                });
            }

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(virtualThreads.get()).isEqualTo(tasks);
        } finally {
            provider.close();
        }
    }

    @Test
    public void closeStopsAcceptingTasks() {
        VirtualThreadExecutorProvider provider = new VirtualThreadExecutorProvider();
        provider.close();

        AtomicBoolean executed = new AtomicBoolean(false);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> provider.io().execute(() -> executed.set(true)))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        assertThat(executed.get()).isFalse();
    }
}
