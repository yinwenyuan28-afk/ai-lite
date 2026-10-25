package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallStore;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标装配契约：registry 存在且启用时网关拿到装饰器；关闭或无 registry 时
 * 行为同旧版；用户自定义 store 被装饰而非替换。
 */
public class AiLiteMetricsAssemblyTest {

    private static final String[] BASE = {
            "ai-lite.llm.biz-ids[0]=demo",
            "ai-lite.llm.providers.demo.api=fake",
            "ai-lite.llm.providers.demo.base-url=http://127.0.0.1:1/v1",
            "ai-lite.llm.providers.demo.api-key=test-key",
            "ai-lite.llm.providers.demo.models[0].id=m",
            "ai-lite.llm.profiles.main.provider=demo",
            "ai-lite.llm.profiles.main.model=m",
            "ai-lite.llm.profiles.main.thinking-level=off"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                    AiLiteAutoConfiguration.class, FakeTransportConfig.class));

    @Test
    public void gatewayEmitsMetricsWhenRegistryPresent() {
        runner.withUserConfiguration(RegistryConfig.class)
                .withPropertyValues(BASE)
                .run(context -> {
                    LlmGateway gateway = context.getBean(LlmGateway.class);
                    gateway.one(LlmCall.profile("demo", "main",
                            Collections.singletonList(LlmMessage.user("hi"))));
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    // 记录经 AsyncLlmCallLogger 异步落盘，轮询等待指标出现。
                    awaitMeter(registry, "ailite.llm.calls");
                    assertThat(registry.get("ailite.llm.calls")
                            .tag("biz", "demo").tag("outcome", "success")
                            .counter().count()).isEqualTo(1.0d);
                });
    }

    @Test
    public void disabledByProperty() {
        runner.withUserConfiguration(RegistryConfig.class)
                .withPropertyValues(BASE)
                .withPropertyValues("ai-lite.metrics.llm-enabled=false")
                .run(context -> {
                    LlmGateway gateway = context.getBean(LlmGateway.class);
                    gateway.one(LlmCall.profile("demo", "main",
                            Collections.singletonList(LlmMessage.user("hi"))));
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("ailite.llm.calls").counter()).isNull();
                });
    }

    @Test
    public void customStoreIsDecoratedNotReplaced() {
        runner.withUserConfiguration(RegistryConfig.class, CustomStoreConfig.class)
                .withPropertyValues(BASE)
                .run(context -> {
                    LlmGateway gateway = context.getBean(LlmGateway.class);
                    gateway.one(LlmCall.profile("demo", "main",
                            Collections.singletonList(LlmMessage.user("hi"))));
                    // 用户 store 收到记录（未被替换），指标同时生效。
                    CustomStoreConfig.Store store =
                            context.getBean(CustomStoreConfig.Store.class);
                    assertThat(store.rows).hasSize(1);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    awaitMeter(registry, "ailite.llm.calls");
                    assertThat(registry.get("ailite.llm.calls")
                            .tag("biz", "demo").counter().count()).isEqualTo(1.0d);
                });
    }

    private static void awaitMeter(MeterRegistry registry, String name)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (registry.find(name).counter() == null && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
    }

    @Configuration
    static class RegistryConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration
    static class FakeTransportConfig {

        @Bean
        LlmTransport fakeTransport() {
            return new LlmTransport() {
                @Override
                public String api() {
                    return "fake";
                }

                @Override
                public LlmCompletion complete(LlmRequest request) {
                    return new LlmCompletion("ok", null, LlmStopReason.STOP,
                            LlmUsage.of(3, 4, 7), null);
                }
            };
        }
    }

    @Configuration
    static class CustomStoreConfig {

        static final class Store implements LlmCallStore {
            final List<Object> rows = new CopyOnWriteArrayList<Object>();

            @Override
            public void insert(io.github.yinwenyuan28.ailite.llm.LlmCallRecord record) {
                rows.add(record);
            }
        }

        @Bean
        LlmCallStore aiLiteCallStore() {
            return new Store();
        }
    }
}
