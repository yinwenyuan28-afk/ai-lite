package io.github.yinwenyuan28.ailite.executor;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 JDK ThreadPoolExecutor 的线程池实现。
 *
 * <p>没有引入任何第三方“轻量线程池”依赖。JDK 8 下的用户级线程库要么停更、
 * 要么需要字节码增强，不适合作为 Starter 的基础设施。需要虚拟线程时，
 * 换成 JDK 21 模块即可，上层 API 不变。</p>
 */
public final class PlatformExecutorProvider implements ExecutorProvider {

    private final ThreadPoolExecutor executor;

    /**
     * @param config 线程池配置
     */
    public PlatformExecutorProvider(AiLiteConfig.Executor config) {
        AiLiteConfig.Executor cfg = config == null ? new AiLiteConfig.Executor() : config;
        int core = Math.max(1, cfg.getCoreSize());
        int max = Math.max(core, cfg.getMaxSize());
        int queue = Math.max(1, cfg.getQueueCapacity());
        this.executor = new ThreadPoolExecutor(
                core, max, cfg.getKeepAliveSeconds(), TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(queue),
                new NamedThreadFactory(cfg.getThreadNamePrefix()),
                rejection(cfg.getRejectionPolicy()));
        this.executor.allowCoreThreadTimeOut(false);
    }

    private static RejectedExecutionHandler rejection(String policy) {
        if ("caller-runs".equalsIgnoreCase(policy)) {
            return new ThreadPoolExecutor.CallerRunsPolicy();
        }
        return new ThreadPoolExecutor.AbortPolicy();
    }

    @Override
    public Executor io() {
        return executor;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * 命名线程，便于排查日志和线程 dump。
     */
    private static final class NamedThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix == null || prefix.isEmpty() ? "ai-lite-" : prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
