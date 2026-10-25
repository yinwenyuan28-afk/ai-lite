package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.llm.LlmCallRecord;
import io.github.yinwenyuan28.ailite.llm.LlmCallStore;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;

import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 调用指标装饰器：计数/计时/用量/轮次映射、outcome 派生、
 * 指标故障隔离（不影响 delegate 记录）与标签基数有界。
 */
public class MicrometerLlmCallStoreTest {

    @Test
    public void successRecordProducesCallsDurationAndTokens() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<LlmCallRecord> delegated = new CopyOnWriteArrayList<LlmCallRecord>();
        MicrometerLlmCallStore store = new MicrometerLlmCallStore(delegated::add, registry);

        store.insert(record("STOP", 1, 1, 10, 20, 30, 120L, null));

        assertThat(delegated).hasSize(1);
        Double calls = registry.get("ailite.llm.calls")
                .tag("biz", "demo").tag("model", "m").tag("api", "openai-completions")
                .tag("stop", "STOP").tag("outcome", "success")
                .counter().count();
        assertThat(calls).isEqualTo(1.0d);
        assertThat(registry.get("ailite.llm.duration")
                .tag("biz", "demo").tag("model", "m")
                .timer().count()).isEqualTo(1L);
        assertThat(registry.get("ailite.llm.tokens").tag("type", "input").counter().count())
                .isEqualTo(10.0d);
        assertThat(registry.get("ailite.llm.tokens").tag("type", "output").counter().count())
                .isEqualTo(20.0d);
        assertThat(registry.get("ailite.llm.tokens").tag("type", "total").counter().count())
                .isEqualTo(30.0d);
        assertThat(registry.find("ailite.llm.rounds").counter()).isNull();
    }

    @Test
    public void errorAndAbortedDeriveOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerLlmCallStore store = new MicrometerLlmCallStore(r -> { }, registry);

        store.insert(record("STOP", 0, 1, 1, 1, 2, 50L, "boom"));
        store.insert(record("ABORTED", 0, 1, 0, 0, 0, 10L, null));

        assertThat(registry.get("ailite.llm.calls").tag("outcome", "error").tag("stop", "STOP")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("ailite.llm.calls").tag("outcome", "aborted")
                .tag("stop", "ABORTED").counter().count()).isEqualTo(1.0d);
    }

    @Test
    public void nonFirstRoundCountsAsRound() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerLlmCallStore store = new MicrometerLlmCallStore(r -> { }, registry);

        store.insert(record("TOOL_USE", 1, 1, 0, 0, 0, 30L, null));
        store.insert(record("STOP", 1, 2, 0, 0, 0, 30L, null));

        assertThat(registry.get("ailite.llm.rounds").tag("biz", "demo").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    public void errorBriefNeverBecomesATag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerLlmCallStore store = new MicrometerLlmCallStore(r -> { }, registry);

        store.insert(record("STOP", 0, 1, 1, 1, 2, 5L, "some free text error"));

        boolean leaked = false;
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                if (tag.getValue().contains("free text")) {
                    leaked = true;
                }
            }
        }
        assertThat(leaked).isFalse();
    }

    @Test
    public void meterFailureDoesNotBlockDelegate() {
        // registry 的 counter 抛异常：delegate 仍收到记录，insert 不向上抛。
        MeterRegistry hostile = new SimpleMeterRegistry() {
            @Override
            public io.micrometer.core.instrument.Counter counter(String name, String... tags) {
                throw new IllegalStateException("metrics down");
            }
        };
        List<LlmCallRecord> delegated = new CopyOnWriteArrayList<LlmCallRecord>();
        MicrometerLlmCallStore store = new MicrometerLlmCallStore(delegated::add, hostile);

        store.insert(record("STOP", 1, 1, 1, 1, 2, 5L, null));

        assertThat(delegated).hasSize(1);
    }

    @Test
    public void tagKeysAreBounded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerLlmCallStore store = new MicrometerLlmCallStore(r -> { }, registry);

        store.insert(record("STOP", 1, 1, 1, 1, 2, 5L, "msg"));

        Set<String> keys = registry.get("ailite.llm.calls").counter().getId().getTags()
                .stream().map(Tag::getKey).collect(java.util.stream.Collectors.toSet());
        assertThat(keys).containsExactlyInAnyOrder("biz", "provider", "model", "api",
                "stop", "outcome");
    }

    private static LlmCallRecord record(String stop, int success, int roundNo, int input,
                                        int output, int total, long latency, String errorBrief) {
        LlmCallRecord record = new LlmCallRecord();
        record.setBizId("demo");
        record.setProvider("prov");
        record.setModelId("m");
        record.setApi("openai-completions");
        record.setRoundNo(Integer.valueOf(roundNo));
        record.setInputTokens(Integer.valueOf(input));
        record.setOutputTokens(Integer.valueOf(output));
        record.setTotalTokens(Integer.valueOf(total));
        record.setLatencyMs(Long.valueOf(latency));
        record.setStopReason(stop);
        record.setSuccess(Integer.valueOf(success));
        record.setErrorBrief(errorBrief);
        record.setCreateTime(new Date());
        return record;
    }
}
