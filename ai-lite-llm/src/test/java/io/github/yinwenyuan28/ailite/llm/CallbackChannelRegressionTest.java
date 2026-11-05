package io.github.yinwenyuan28.ailite.llm;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-02 对抗性用例：单个回调任务抛错不能让整条回调通道停摆。
 *
 * <p>「完成屏障」（唯一调用 result.complete 的任务）也排在这个队列里，
 * 所以通道停摆 = 调用方的 future 永久挂起。批次 A 只补用例，不改产品代码。</p>
 */
public class CallbackChannelRegressionTest {

    private static final long TIMEOUT_SECONDS = 3L;

    @Test
    public void runtimeExceptionInTaskDoesNotStallQueue() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        SerialCallbackExecutor serial = new SerialCallbackExecutor(pool, 16);
        final AtomicInteger executed = new AtomicInteger();
        final CountDownLatch third = new CountDownLatch(1);
        final CountDownLatch later = new CountDownLatch(1);
        try {
            assertThat(serial.submit(new Runnable() {
                @Override
                public void run() {
                    executed.incrementAndGet();
                }
            })).isTrue();
            assertThat(serial.submit(new Runnable() {
                @Override
                public void run() {
                    throw new IllegalStateException("onDelta 抛了 RuntimeException");
                }
            })).isTrue();
            // 排在出错任务后面的就是完成屏障的等价物。
            assertThat(serial.submit(new Runnable() {
                @Override
                public void run() {
                    executed.incrementAndGet();
                    third.countDown();
                }
            })).isTrue();

            assertThat(third.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("出错任务之后的任务仍须执行")
                    .isTrue();

            // 工人退出后「正在作业」的牌子必须摘掉，否则后续 submit 只入队不执行。
            assertThat(serial.submit(new Runnable() {
                @Override
                public void run() {
                    later.countDown();
                }
            })).isTrue();
            assertThat(later.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("通道自愈后新提交的任务仍须执行")
                    .isTrue();
            assertThat(executed.get()).isEqualTo(2);
        } finally {
            serial.close();
            pool.shutdownNow();
        }
    }

    @Test
    public void errorInTaskDoesNotStallQueue() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        SerialCallbackExecutor serial = new SerialCallbackExecutor(pool, 16);
        final CountDownLatch after = new CountDownLatch(1);
        try {
            assertThat(serial.submit(new Runnable() {
                @Override
                public void run() {
                    throw new AssertionError("onDelta 抛了 Error");
                }
            })).isTrue();
            assertThat(serial.submit(new Runnable() {
                @Override
                public void run() {
                    after.countDown();
                }
            })).isTrue();

            assertThat(after.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("Error 同样不能打断队列")
                    .isTrue();
        } finally {
            serial.close();
            pool.shutdownNow();
        }
    }

    @Test
    public void inlineExecutorDoesNotStallAfterFirstSubmit() {
        // 内联 Executor：worker 在 pool.execute 内同步跑完并把 running 复位，
        // 旧实现随后又把 running 覆写成 true，第二次 submit 起不再启动工人。
        final Executor inline = new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
        SerialCallbackExecutor serial = new SerialCallbackExecutor(inline, 16);
        final AtomicInteger executed = new AtomicInteger();
        try {
            for (int i = 0; i < 5; i++) {
                assertThat(serial.submit(new Runnable() {
                    @Override
                    public void run() {
                        executed.incrementAndGet();
                    }
                })).isTrue();
            }
            assertThat(executed.get()).isEqualTo(5);
        } finally {
            serial.close();
        }
    }

    @Test
    public void sharedPoolSaturationIsVisible() throws Exception {
        // 单线程共享池：一个长生命周期 run 不能独占池线程，让第二个 run 静默排队。
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(4));
        final SerialCallbackExecutor busy = new SerialCallbackExecutor(pool, 64);
        final SerialCallbackExecutor other = new SerialCallbackExecutor(pool, 16);
        final CountDownLatch arrived = new CountDownLatch(1);
        final AtomicInteger pulses = new AtomicInteger();
        try {
            // busy 持续给自己排队，模拟一次长流式调用的连续 delta。
            busy.submit(new Runnable() {
                @Override
                public void run() {
                    if (pulses.incrementAndGet() < 200) {
                        busy.submit(this);
                    }
                }
            });
            assertThat(other.submit(new Runnable() {
                @Override
                public void run() {
                    arrived.countDown();
                }
            })).isTrue();

            assertThat(arrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("共享池只有一条线程时，另一个 run 也必须能推进")
                    .isTrue();
        } finally {
            busy.close();
            other.close();
            pool.shutdownNow();
        }
    }

    @Test
    public void closeResetsRunningFlag() throws Exception {
        // 白盒护栏：run 结束时「正在作业」标志必须复位，不能留下假成功状态。
        ExecutorService pool = Executors.newSingleThreadExecutor();
        SerialCallbackExecutor serial = new SerialCallbackExecutor(pool, 16);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            serial.submit(new Runnable() {
                @Override
                public void run() {
                    started.countDown();
                    try {
                        release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            serial.close();
            assertThat(readRunningFlag(serial)).isFalse();
            assertThat(serial.submit(new Runnable() {
                @Override
                public void run() {
                }
            })).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static boolean readRunningFlag(SerialCallbackExecutor serial) throws Exception {
        java.lang.reflect.Field field =
                SerialCallbackExecutor.class.getDeclaredField("running");
        field.setAccessible(true);
        synchronized (serial) {
            return field.getBoolean(serial);
        }
    }
}
