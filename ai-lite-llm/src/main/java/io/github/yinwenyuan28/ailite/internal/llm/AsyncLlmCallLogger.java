package io.github.yinwenyuan28.ailite.internal.llm;

import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.LlmCallRecord;
import io.github.yinwenyuan28.ailite.llm.LlmCallStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 在注入的线程池里写一行摘要日志并落表。
 *
 * <p>任务内部吞掉所有异常，避免存储故障影响模型调用；线程池拒绝策略也不会传播到业务线程。</p>
 */
public final class AsyncLlmCallLogger {

    private static final Logger log = LoggerFactory.getLogger(AsyncLlmCallLogger.class);

    private final ExecutorProvider executor;
    private final LlmCallStore store;

    /**
     * 用注入的执行器写记录。
     *
     * @param executor 执行器；null 退化为直接执行
     * @param store    记录存储；null 退化为空实现
     */
    public AsyncLlmCallLogger(ExecutorProvider executor, LlmCallStore store) {
        this.executor = executor == null ? new DirectExecutorProvider() : executor;
        this.store = store == null ? new NoopLlmCallStore() : store;
    }

    /**
     * 用显式执行器写记录。用于「调用方明确不要内联」的场景（F-14）。
     *
     * @param executor 记录执行器，不能为 null
     * @param store    记录存储；null 退化为空实现
     * @param ignored  仅为区分重载的占位参数，恒传 true
     */
    public AsyncLlmCallLogger(Executor executor, LlmCallStore store, boolean ignored) {
        this(executor == null ? null : new FixedExecutorProvider(executor), store);
    }

    /**
     * 把一个 {@link Executor} 包成 {@link ExecutorProvider}，只为复用同一段记录逻辑。
     */
    private static final class FixedExecutorProvider implements ExecutorProvider {

        private final Executor delegate;

        private FixedExecutorProvider(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public Executor io() {
            return delegate;
        }

        @Override
        public void close() {
            // 执行器由调用方负责关闭。
        }
    }

    /**
     * 异步记录一行。调用方不应依赖它完成。
     *
     * @param row 调用记录
     */
    public void record(final LlmCallRecord row) {
        if (row == null) {
            return;
        }
        if (row.getCallId() == null) {
            row.setCallId(UUID.randomUUID().toString().replace("-", ""));
        }
        if (row.getCreateTime() == null) {
            row.setCreateTime(new Date());
        }
        try {
            executor.io().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        log.info("llm call bizId={} provider={} model={} api={} round={} stop={} success={} tokens={} latencyMs={}",
                                row.getBizId(), row.getProvider(), row.getModelId(), row.getApi(),
                                row.getRoundNo(), row.getStopReason(), row.getSuccess(),
                                row.getTotalTokens(), row.getLatencyMs());
                        store.insert(row);
                    } catch (Throwable ex) {
                        log.warn("llm call record failed bizId={} {}", row.getBizId(), ex.toString());
                    }
                }
            });
        } catch (RuntimeException ex) {
            // 线程池拒绝时只记日志，不影响主调用。
            log.warn("llm call record rejected bizId={} {}", row.getBizId(), ex.toString());
        }
    }
}
