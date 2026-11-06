package io.github.yinwenyuan28.ailite.internal.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程级共享的调用记录线程池（F-14）。
 *
 * <p>为什么需要它：手工构造 {@code LlmGateway} 时执行器默认为
 * {@link io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider}，
 * 内联执行意味着 JDBC 落库直接跑在 transport 的回调线程上——生产环境里就是
 * OkHttp dispatcher 线程。一次慢插入会拖住同一线程上的其他响应回调。</p>
 *
 * <p>生命周期：懒加载、只创建一次、线程是 daemon，不随单个网关关闭。这与
 * {@link RetryScheduler#shared()} 是同一套取舍——手工构造场景下「谁负责关闭」
 * 没有答案，让 JVM 退出时自然结束最省事。Starter 装配时注入的
 * {@code ExecutorProvider} 优先，本池只是兜底。</p>
 */
public final class CallLogExecutors {

    private static final Logger log = LoggerFactory.getLogger(CallLogExecutors.class);

    /** 线程名前缀：线上排查靠它识别「这条记录是谁写的」。 */
    public static final String THREAD_PREFIX = "ai-lite-call-log-";

    /** 队列上限：记录丢了只影响可观测性，不能把内存吃穿。 */
    private static final int QUEUE_CAPACITY = 4096;
    private static final int THREADS = 2;

    private CallLogExecutors() {
    }

    /**
     * @return 进程级共享的记录执行器（daemon，不随网关关闭）
     */
    public static Executor shared() {
        return Holder.SHARED;
    }

    private static final class Holder {
        private static final Executor SHARED = create();
    }

    /**
     * 建一个有界池。满了直接拒绝——调用方按「记录丢失」处理，绝不能让
     * 可观测性反过来阻塞模型调用。
     */
    private static Executor create() {
        final AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, THREAD_PREFIX + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(THREADS, THREADS,
                30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(QUEUE_CAPACITY),
                factory, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * 判断一次 {@code execute} 的失败是否属于「池已满/已关闭」。
     *
     * @param ex 捕获到的异常
     * @return true 表示可以按记录丢失处理
     */
    public static boolean isRejection(RuntimeException ex) {
        return ex instanceof RejectedExecutionException;
    }

    /**
     * 记录一条「写记录被拒绝」的事件。放在这里是为了让日志文案只有一处。
     *
     * @param bizId 业务 id，可为 null
     * @param cause 拒绝原因
     */
    public static void warnRejected(String bizId, Throwable cause) {
        log.warn("llm call record rejected bizId={} {}", bizId, cause.toString());
    }
}
