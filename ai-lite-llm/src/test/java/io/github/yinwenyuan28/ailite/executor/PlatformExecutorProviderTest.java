package io.github.yinwenyuan28.ailite.executor;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.executor.PlatformExecutorProvider;

/**
 * 平台线程池：正常执行、线程命名、拒绝策略和关闭。
 */
public class PlatformExecutorProviderTest {

    @Test
    public void executesTasksOnNamedDaemonThreads() throws Exception {
        AiLiteConfig.Executor config = new AiLiteConfig.Executor();
        config.setCoreSize(1);
        config.setMaxSize(1);
        config.setThreadNamePrefix("test-pool-");
        PlatformExecutorProvider provider = new PlatformExecutorProvider(config);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Thread> thread = new AtomicReference<Thread>();
        try {
            provider.io().execute(new Runnable() {
                @Override
                public void run() {
                    thread.set(Thread.currentThread());
                    done.countDown();
                }
            });
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get().getName()).startsWith("test-pool-");
            assertThat(thread.get().isDaemon()).isTrue();
        } finally {
            provider.close();
        }
    }

    @Test
    public void abortsWhenQueueIsFull() throws Exception {
        AiLiteConfig.Executor config = new AiLiteConfig.Executor();
        config.setCoreSize(1);
        config.setMaxSize(1);
        config.setQueueCapacity(1);
        final PlatformExecutorProvider provider = new PlatformExecutorProvider(config);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        try {
            provider.io().execute(new Runnable() {
                @Override
                public void run() {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            provider.io().execute(new Runnable() {
                @Override
                public void run() {
                }
            });

            assertThatThrownBy(() -> provider.io().execute(new Runnable() {
                @Override
                public void run() {
                }
            })).isInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            provider.close();
        }
    }

    @Test
    public void callerRunsPolicyExecutesOnCaller() throws Exception {
        AiLiteConfig.Executor config = new AiLiteConfig.Executor();
        config.setCoreSize(1);
        config.setMaxSize(1);
        config.setQueueCapacity(1);
        config.setRejectionPolicy("caller-runs");
        final PlatformExecutorProvider provider = new PlatformExecutorProvider(config);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicReference<String> callerThread = new AtomicReference<String>();
        try {
            provider.io().execute(new Runnable() {
                @Override
                public void run() {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            provider.io().execute(new Runnable() {
                @Override
                public void run() {
                }
            });
            provider.io().execute(new Runnable() {
                @Override
                public void run() {
                    callerThread.set(Thread.currentThread().getName());
                }
            });

            assertThat(callerThread.get()).isEqualTo(Thread.currentThread().getName());
        } finally {
            release.countDown();
            provider.close();
        }
    }

    @Test
    public void closesUnderlyingPool() {
        PlatformExecutorProvider provider = new PlatformExecutorProvider(new AiLiteConfig.Executor());
        provider.close();

        assertThatThrownBy(() -> provider.io().execute(new Runnable() {
            @Override
            public void run() {
            }
        })).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    public void nullConfigUsesDefaults() {
        PlatformExecutorProvider provider = new PlatformExecutorProvider(null);
        try {
            assertThat(provider.io()).isNotNull();
        } finally {
            provider.close();
        }
    }

    @Test
    public void directProviderRunsOnCallerThread() {
        final AtomicReference<String> name = new AtomicReference<String>();
        new DirectExecutorProvider().io().execute(new Runnable() {
            @Override
            public void run() {
                name.set(Thread.currentThread().getName());
            }
        });

        assertThat(name.get()).isEqualTo(Thread.currentThread().getName());
    }
}
