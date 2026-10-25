package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * 出口保护装配契约（限流熔断计划 G5，行为断言）：全默认零实例（NOOP 直通）、
 * 熔断 enabled 时 OPEN 快速失败 + 指标、429 出 rate-limited 计数、
 * 并发限流按 provider 生效（OVERLOAD 拒绝）。
 *
 * <p>传输层按测试意图拆成独立配置类，避免跨测试的调用计数耦合。</p>
 */
public class AiLiteGuardAssemblyTest {

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
                    AiLiteAutoConfiguration.class));

    private static LlmCall call() {
        return LlmCall.profile("demo", "main", Collections.singletonList(LlmMessage.user("hi")));
    }

    @Test
    public void defaultConfigIsNoopPassThrough() {
        runner.withUserConfiguration(RegistryConfig.class, OkTransportConfig.class)
                .withPropertyValues(BASE)
                .run(context -> {
                    LlmGateway gateway = context.getBean(LlmGateway.class);
                    assertThat(gateway.one(call()).getStopReason()).isEqualTo(LlmStopReason.STOP);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("ailite.llm.guard.rejected").counter()).isNull();
                    assertThat(registry.find("ailite.llm.guard.circuit-state").gauge()).isNull();
                });
    }

    @Test
    public void circuitOpensFastFailsAndEmitsMetrics() {
        // 1 样本即开：第一次 500 失败开闸，第二次毫秒级 CIRCUIT_OPEN 且零出站。
        runner.withUserConfiguration(RegistryConfig.class, Fail500TransportConfig.class)
                .withPropertyValues(BASE)
                .withPropertyValues("ai-lite.guard.circuit.enabled=true",
                        "ai-lite.guard.circuit.failure-rate=1",
                        "ai-lite.guard.circuit.minimum-calls=1")
                .run(context -> {
                    LlmGateway gateway = context.getBean(LlmGateway.class);
                    try {
                        gateway.one(call());
                        fail("第一次应当 500 失败");
                    } catch (LlmCallException expected) {
                        assertThat(expected.getStatusCode()).isEqualTo(500);
                    }
                    long started = System.currentTimeMillis();
                    try {
                        gateway.one(call());
                        fail("第二次应当被熔断拒绝");
                    } catch (LlmCallException expected) {
                        assertThat(expected.getKind()).isEqualTo(LlmCallException.Kind.CIRCUIT_OPEN);
                    }
                    assertThat(System.currentTimeMillis() - started).isLessThan(2000L);
                    // OPEN 拒绝零出站：传输只被调过 1 次。
                    assertThat(context.getBean(Fail500TransportConfig.Transport.class).calls.get())
                            .isEqualTo(1);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("ailite.llm.guard.rejected")
                            .tag("provider", "demo").tag("reason", "circuit")
                            .counter().count()).isEqualTo(1.0d);
                    assertThat(registry.get("ailite.llm.guard.circuit-state")
                            .tag("provider", "demo").gauge().value()).isEqualTo(1.0d);
                });
    }

    @Test
    public void rateLimitedCounterEmits429() {
        // 失败率 100% 但门槛 5 样本：429 只计数不开闸。
        runner.withUserConfiguration(RegistryConfig.class, Fail429TransportConfig.class)
                .withPropertyValues(BASE)
                .withPropertyValues("ai-lite.guard.circuit.enabled=true",
                        "ai-lite.guard.circuit.failure-rate=100",
                        "ai-lite.guard.circuit.minimum-calls=5")
                .run(context -> {
                    LlmGateway gateway = context.getBean(LlmGateway.class);
                    try {
                        gateway.one(call());
                        fail("应当 429 失败");
                    } catch (LlmCallException expected) {
                        assertThat(expected.getStatusCode()).isEqualTo(429);
                    }
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("ailite.llm.guard.rate-limited")
                            .tag("provider", "demo").counter().count()).isEqualTo(1.0d);
                    assertThat(registry.find("ailite.llm.guard.rejected").counter()).isNull();
                });
    }

    @Test
    public void concurrencyLimitRejectsWithOverload() throws Exception {
        runner.withUserConfiguration(RegistryConfig.class, BlockingTransportConfig.class)
                .withPropertyValues(BASE)
                .withPropertyValues("ai-lite.llm.providers.demo.guard.concurrency=1",
                        "ai-lite.guard.concurrency-wait-ms=80")
                .run(context -> {
                    LlmGateway gateway = context.getBean(LlmGateway.class);
                    BlockingTransportConfig.Transport transport =
                            context.getBean(BlockingTransportConfig.Transport.class);
                    // 第一次调用阻塞在传输层，占住唯一许可。
                    ExecutorService pool = Executors.newSingleThreadExecutor();
                    try {
                        pool.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    gateway.one(call());
                                } catch (RuntimeException ignored) {
                                    // 释放闸门后可能被中断，测试只关心主线程的拒绝。
                                }
                            }
                        });
                        transport.awaitFirstCall();

                        long started = System.currentTimeMillis();
                        assertThatThrownBy(() -> gateway.one(call()))
                                .isInstanceOfSatisfying(LlmCallException.class, ex -> {
                                    assertThat(ex.getKind()).isEqualTo(LlmCallException.Kind.OVERLOAD);
                                    assertThat(ex.getMessage()).contains("并发等待超时");
                                });
                        assertThat(System.currentTimeMillis() - started).isBetween(60L, 5000L);
                        assertThat(transport.calls.get()).isEqualTo(1);
                    } finally {
                        transport.release();
                        pool.shutdownNow();
                    }
                });
    }

    @Configuration
    static class RegistryConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    /** 恒成功传输：NOOP 直通断言用。 */
    @Configuration
    static class OkTransportConfig {

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

    /** 首次 500 失败、之后成功：熔断开启与零出站断言用。 */
    @Configuration
    static class Fail500TransportConfig {

        static final class Transport implements LlmTransport {
            final AtomicInteger calls = new AtomicInteger();

            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                if (calls.incrementAndGet() == 1) {
                    throw new LlmCallException("server error", null,
                            LlmCallException.Kind.HTTP, Integer.valueOf(500));
                }
                return new LlmCompletion("ok", null, LlmStopReason.STOP,
                        LlmUsage.of(3, 4, 7), null);
            }
        }

        @Bean
        LlmTransport fakeTransport() {
            return new Transport();
        }
    }

    /** 首次 429 失败、之后成功：rate-limited 计数断言用。 */
    @Configuration
    static class Fail429TransportConfig {

        @Bean
        LlmTransport fakeTransport() {
            final AtomicInteger calls = new AtomicInteger();
            return new LlmTransport() {
                @Override
                public String api() {
                    return "fake";
                }

                @Override
                public LlmCompletion complete(LlmRequest request) {
                    if (calls.incrementAndGet() == 1) {
                        throw new LlmCallException("rate limited", null,
                                LlmCallException.Kind.HTTP, Integer.valueOf(429));
                    }
                    return new LlmCompletion("ok", null, LlmStopReason.STOP,
                            LlmUsage.of(3, 4, 7), null);
                }
            };
        }
    }

    /** 首次调用阻塞到 release：并发限流 OVERLOAD 断言用。 */
    @Configuration
    static class BlockingTransportConfig {

        static final class Transport implements LlmTransport {
            final AtomicInteger calls = new AtomicInteger();
            private final CountDownLatch firstCall = new CountDownLatch(1);
            private volatile boolean released;

            void awaitFirstCall() throws InterruptedException {
                long deadline = System.currentTimeMillis() + 5000L;
                while (calls.get() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20L);
                }
            }

            void release() {
                released = true;
                firstCall.countDown();
            }

            @Override
            public String api() {
                return "fake";
            }

            @Override
            public LlmCompletion complete(LlmRequest request) {
                calls.incrementAndGet();
                if (!released) {
                    try {
                        firstCall.await(5L, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new LlmCallException("测试闸门中断", ex,
                                LlmCallException.Kind.TRANSPORT);
                    }
                }
                return new LlmCompletion("ok", null, LlmStopReason.STOP,
                        LlmUsage.of(3, 4, 7), null);
            }
        }

        @Bean
        LlmTransport fakeTransport() {
            return new Transport();
        }
    }
}
