package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.llm.LlmCallRecord;
import io.github.yinwenyuan28.ailite.llm.LlmCallStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * LLM 调用指标的 Micrometer 装饰器（可观测性计划）。
 *
 * <p>把 delegate store 收到的每条 {@link LlmCallRecord} 聚合进四个指标：
 * 调用计数（按 biz/provider/model/api/stop/outcome）、耗时计时、token 累计
 * （input/output/total）、非首轮调用计数（工具轮/重试轮）。所有标签均为有限
 * 集合（biz 白名单、provider/model 来自配置、api 三协议、stop 七种）；
 * errorBrief 是自由文本，只进 delegate 记录与日志，永不进标签。</p>
 *
 * <p>本类装饰既有 {@code LlmCallStore}（默认或用户自定义），不替换、不夺 Bean；
 * 全部指标更新包在 try/catch 里——指标故障绝不影响调用结果与记录写入。</p>
 */
public final class MicrometerLlmCallStore implements LlmCallStore {

    private static final String CALLS = "ailite.llm.calls";
    private static final String DURATION = "ailite.llm.duration";
    private static final String TOKENS = "ailite.llm.tokens";
    private static final String ROUNDS = "ailite.llm.rounds";

    private final LlmCallStore delegate;
    private final MeterRegistry registry;

    public MicrometerLlmCallStore(LlmCallStore delegate, MeterRegistry registry) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate store 不能为空");
        }
        if (registry == null) {
            throw new IllegalArgumentException("MeterRegistry 不能为空");
        }
        this.delegate = delegate;
        this.registry = registry;
    }

    /**
     * @return 被装饰的记录存储
     */
    public LlmCallStore getDelegate() {
        return delegate;
    }

    @Override
    public void insert(LlmCallRecord record) {
        try {
            observe(record);
        } catch (RuntimeException ignored) {
            // 指标故障绝不影响调用结果与记录写入。
        } catch (Throwable ignored) {
            // Error 同样吞掉：观测代码不能压垮业务线程。
        }
        delegate.insert(record);
    }

    private void observe(LlmCallRecord record) {
        if (record == null) {
            return;
        }
        String biz = tag(record.getBizId());
        String provider = tag(record.getProvider());
        String model = tag(record.getModelId());
        String api = tag(record.getApi());
        String stop = tag(record.getStopReason());
        String outcome = outcome(record);

        registry.counter(CALLS,
                "biz", biz, "provider", provider, "model", model,
                "api", api, "stop", stop, "outcome", outcome).increment();

        if (record.getLatencyMs() != null) {
            Timer.builder(DURATION)
                    .tags("biz", biz, "provider", provider, "model", model, "api", api)
                    .register(registry)
                    .record(record.getLatencyMs(), TimeUnit.MILLISECONDS);
        }

        recordToken(record.getInputTokens(), "input", biz, provider, model);
        recordToken(record.getOutputTokens(), "output", biz, provider, model);
        recordToken(record.getTotalTokens(), "total", biz, provider, model);

        if (record.getRoundNo() != null && record.getRoundNo().intValue() > 1) {
            registry.counter(ROUNDS, "biz", biz, "model", model).increment();
        }
    }

    private void recordToken(Integer value, String type, String biz, String provider,
                             String model) {
        if (value == null || value.intValue() <= 0) {
            return;
        }
        registry.counter(TOKENS,
                "biz", biz, "provider", provider, "model", model, "type", type)
                .increment(value.doubleValue());
    }

    /** outcome 由 success + stopReason 派生：aborted > error > success。 */
    private static String outcome(LlmCallRecord record) {
        if ("ABORTED".equals(record.getStopReason())) {
            return "aborted";
        }
        Integer success = record.getSuccess();
        if (success == null || success.intValue() <= 0) {
            return "error";
        }
        return "success";
    }

    private static String tag(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }
}
