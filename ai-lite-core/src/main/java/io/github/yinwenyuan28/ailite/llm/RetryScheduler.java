package io.github.yinwenyuan28.ailite.llm;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步重试专用调度器。
 *
 * <p>重试只做“检查状态 + 发起下一次 {@code oneAsync}”，不等待 HTTP 响应，
 * 所以固定 2 个守护线程足够。监听器回调也在这里执行，慢监听器不应放进这个池；
 * 文档要求 {@link RetryPolicy.Listener} 保持轻量。</p>
 *
 * <p>与 {@code ExecutorProvider.io()} 严格分离：HTTP 客户端线程只负责把重试任务
 * 投递进来，绝不在 I/O 线程上睡眠。</p>
 */
public final class RetryScheduler {

    /**
     * Starter 中的 Bean 名。用户定义同名 Bean 即可覆盖默认调度器。
     */
    public static final String BEAN_NAME = "aiLiteRetryScheduler";

    /**
     * 线程名前缀，测试和线上排查都依赖它识别重试启动线程。
     */
    public static final String THREAD_PREFIX = "ai-lite-retry-";

    private static final int DEFAULT_THREADS = 2;

    private RetryScheduler() {
    }

    /**
     * 创建独立调度器。调用方负责关闭；Starter 用它实现 destroyMethod。
     *
     * @return 已配置 removeOnCancelPolicy 的调度器
     */
    public static ScheduledThreadPoolExecutor create() {
        final AtomicInteger counter = new AtomicInteger();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                DEFAULT_THREADS, new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable,
                                THREAD_PREFIX + counter.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        // 取消的退避任务立即从延迟队列移除，避免长退避 + 高并发时堆积引用。
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setKeepAliveTime(30L, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * 手工构造 {@link LlmGateway} 时使用的共享调度器。
     *
     * <p>懒加载且只创建一次；线程是 daemon，JVM 退出时自然结束。它不随单个
     * Gateway 关闭，避免手工构造场景出现“谁负责关闭”的歧义。</p>
     *
     * @return 进程级共享调度器
     */
    public static ScheduledExecutorService shared() {
        return Holder.SHARED;
    }

    private static final class Holder {
        private static final ScheduledExecutorService SHARED = create();
    }
}
