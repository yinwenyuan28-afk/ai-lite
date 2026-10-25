package io.github.yinwenyuan28.ailite.llm;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 串行回调执行器：单 in-flight FIFO、队列满返回 false、取消清空、关闭幂等。
 */
public class SerialCallbackExecutorTest {

    @Test
    public void tasksRunInSubmitOrderOnSingleWorker() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        SerialCallbackExecutor serial = new SerialCallbackExecutor(pool, 100);
        final List<Integer> order = new CopyOnWriteArrayList<Integer>();
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(50);
        try {
            for (int i = 0; i < 50; i++) {
                final int seq = i;
                assertThat(serial.submit(new Runnable() {
                    @Override
                    public void run() {
                        int now = concurrent.incrementAndGet();
                        maxConcurrent.getAndUpdate(m -> Math.max(m, now));
                        try {
                            Thread.sleep(2L);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        order.add(seq);
                        concurrent.decrementAndGet();
                        done.countDown();
                    }
                })).isTrue();
            }
            assertThat(done.await(5L, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(maxConcurrent.get()).isEqualTo(1);
        for (int i = 0; i < 50; i++) {
            assertThat(order.get(i)).isEqualTo(i);
        }
    }

    @Test
    public void submitReturnsFalseWhenQueueIsFull() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        SerialCallbackExecutor serial = new SerialCallbackExecutor(pool, 2);
        CountDownLatch block = new CountDownLatch(1);
        try {
            // 第一个任务阻塞 worker；随后 2 个占满队列，第 4 个应被拒。
            assertThat(serial.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        block.await(3L, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            })).isTrue();
            assertThat(serial.submit(new Runnable() { public void run() { } })).isTrue();
            assertThat(serial.submit(new Runnable() { public void run() { } })).isTrue();
            assertThat(serial.submit(new Runnable() { public void run() { } })).isFalse();
        } finally {
            block.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void closeStopsAcceptingAndClearsQueue() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        SerialCallbackExecutor serial = new SerialCallbackExecutor(pool, 100);
        CountDownLatch first = new CountDownLatch(1);
        final AtomicInteger executed = new AtomicInteger();
        try {
            serial.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        first.await(3L, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    executed.incrementAndGet();
                }
            });
            serial.submit(new Runnable() { public void run() { executed.incrementAndGet(); } });
            serial.close();
            serial.close(); // 幂等
            assertThat(serial.isClosed()).isTrue();
            assertThat(serial.submit(new Runnable() { public void run() { } })).isFalse();
        } finally {
            first.countDown();
            pool.shutdownNow();
        }
        Thread.sleep(100L);
        assertThat(executed.get()).isEqualTo(1); // 队列里的任务被清空，不执行
    }

    @Test
    public void inlinePolicyProducesNullExecutorAndIoRequiresPool() {
        assertThat(CallbackPolicy.inline().newSerialExecutor()).isNull();
        assertThat(CallbackPolicy.inline().isIo()).isFalse();
        assertThat(CallbackPolicy.io(new ExecutorServiceAdapter(), 16).isIo()).isTrue();

        IllegalArgumentException thrown = null;
        try {
            CallbackPolicy.io(null, 16);
        } catch (IllegalArgumentException ex) {
            thrown = ex;
        }
        assertThat(thrown).isNotNull();
    }

    /** 最小 Executor 适配。 */
    private static final class ExecutorServiceAdapter implements java.util.concurrent.Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
