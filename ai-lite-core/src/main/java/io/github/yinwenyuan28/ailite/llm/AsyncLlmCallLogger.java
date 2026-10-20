package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.UUID;

/**
 * 在注入的线程池里写一行摘要日志并落表。
 *
 * <p>任务内部吞掉所有异常，避免存储故障影响模型调用；线程池拒绝策略也不会传播到业务线程。</p>
 */
public final class AsyncLlmCallLogger {

    private static final Logger log = LoggerFactory.getLogger(AsyncLlmCallLogger.class);

    private final ExecutorProvider executor;
    private final LlmCallStore store;

    public AsyncLlmCallLogger(ExecutorProvider executor, LlmCallStore store) {
        this.executor = executor == null ? new io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider() : executor;
        this.store = store == null ? new NoopLlmCallStore() : store;
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
