package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.guard.GuardMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 出口保护指标的 Micrometer 挂接（限流熔断计划 §3.5/G5）。
 *
 * <p>三个指标：{@code ailite.llm.guard.rejected}（按 reason）、
 * {@code ailite.llm.guard.circuit-state}（Gauge，0=CLOSED/1=OPEN/2=HALF_OPEN）、
 * {@code ailite.llm.guard.rate-limited}。</p>
 *
 * <p>Micrometer 的 gauge 只持弱引用：状态值对象由本类的 map 强持有（审查 F20），
 * 首次状态事件时注册 gauge，之后只更新值，避免引用被回收导致 gauge 消失。</p>
 *
 * <p>回调可能发生在闸门锁外的高频路径上，实现无阻塞；tag 值恒为 provider id 与
 * 固定 reason 枚举，基数有界，key 值永不进标签。</p>
 */
public final class MicrometerGuardMetrics implements GuardMetrics {

    private final MeterRegistry registry;
    /** providerId → 最新熔断状态（强持有，gauge 弱引用指向它）。 */
    private final ConcurrentHashMap<String, AtomicInteger> circuitStates =
            new ConcurrentHashMap<String, AtomicInteger>();

    public MicrometerGuardMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onRejected(String providerId, String reason) {
        try {
            Counter.builder("ailite.llm.guard.rejected")
                    .tag("provider", tag(providerId))
                    .tag("reason", tag(reason))
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) {
            // 指标失败不能影响调用主路径。
        }
    }

    @Override
    public void onCircuitState(String providerId, int state) {
        try {
            String provider = tag(providerId);
            AtomicInteger value = circuitStates.get(provider);
            if (value == null) {
                AtomicInteger created = new AtomicInteger(state);
                AtomicInteger winner = circuitStates.putIfAbsent(provider, created);
                value = winner == null ? created : winner;
                registry.gauge("ailite.llm.guard.circuit-state",
                        Tags.of("provider", provider), value);
            }
            value.set(state);
        } catch (RuntimeException ignored) {
            // 同上：观测失败不影响主路径。
        }
    }

    @Override
    public void onRateLimited(String providerId) {
        try {
            Counter.builder("ailite.llm.guard.rate-limited")
                    .tag("provider", tag(providerId))
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) {
            // 同上。
        }
    }

    private static String tag(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }
}
