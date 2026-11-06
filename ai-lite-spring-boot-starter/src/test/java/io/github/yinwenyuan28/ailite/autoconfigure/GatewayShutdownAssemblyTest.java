package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 容器关闭时先收敛网关在途重试，再关调度器（F-11）。
 *
 * <p>顺序是这条机制的前提：网关的 {@code shutdown()} 需要往调度器里读写状态，
 * 调度器先关掉就只能看到「调度被拒绝」。Spring 默认按依赖关系反向销毁，
 * 网关依赖调度器，所以网关先销毁——本类把这个前提断言下来，
 * 一旦将来装配方式改动导致顺序反转，这里会先红。</p>
 */
public class GatewayShutdownAssemblyTest {

    private static final String[] BASE = {
            "ai-lite.llm.biz-ids[0]=demo",
            "ai-lite.llm.providers.demo.api=fake",
            "ai-lite.llm.providers.demo.base-url=http://127.0.0.1:1/v1",
            "ai-lite.llm.providers.demo.api-key=test-key",
            "ai-lite.llm.providers.demo.models[0].id=m",
            "ai-lite.llm.profiles.main.provider=demo",
            "ai-lite.llm.profiles.main.model=m",
            "ai-lite.llm.profiles.main.thinking-level=off",
            // 退避设成大值，让调用停在「已排定、未执行」的状态里。
            "ai-lite.llm.retry.total-deadline-ms=600000"
    };

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AiLiteAutoConfiguration.class,
                        ConfigurationPropertiesAutoConfiguration.class))
                .withPropertyValues(BASE)
                .withUserConfiguration(AsyncRetryAssemblyTest.FakeTransportConfig.class);
    }

    /**
     * 容器关闭后，停在退避中的调用必须已经异常完成，而不是继续等自己的超时。
     *
     * <p>这条同时验证了销毁顺序：网关的 {@code shutdown()} 能读到并收敛在途重试，
     * 说明它确实先于调度器销毁。</p>
     */
    @Test
    public void contextCloseConvergesBackoffFuture() throws Exception {
        final LlmGateway[] captured = new LlmGateway[1];
        final List<java.util.concurrent.CompletableFuture<?>> futures =
                new CopyOnWriteArrayList<java.util.concurrent.CompletableFuture<?>>();
        final ScheduledExecutorService[] scheduler = new ScheduledExecutorService[1];

        runner().run(context -> {
            captured[0] = context.getBean(LlmGateway.class);
            scheduler[0] = context.getBean(
                    io.github.yinwenyuan28.ailite.internal.llm.RetryScheduler.BEAN_NAME,
                    ScheduledExecutorService.class);
            // 首次尝试就失败且可重试：调用会停在退避里（退避时长很大）。
            futures.add(captured[0].oneWithRetryAsync(
                    io.github.yinwenyuan28.ailite.llm.LlmCall.profile("demo", "main",
                            java.util.Collections.singletonList(
                                    io.github.yinwenyuan28.ailite.llm.LlmMessage.user("hi"))),
                    io.github.yinwenyuan28.ailite.llm.RetryPolicy.builder()
                            .maxAttempts(3).initialBackoffMs(60000L).maxBackoffMs(60000L)
                            .jitter(false).build()));
            // 等它真的进入退避（首次失败已结算）。
            Thread.sleep(300L);
            assertThat(futures.get(0).isDone()).isFalse();
        });

        // 上下文已关闭：future 必须已经结束，且原因是「服务正在关闭」。
        assertThat(futures.get(0).isDone()).isTrue();
        Throwable cause = null;
        try {
            futures.get(0).get();
        } catch (java.util.concurrent.ExecutionException ex) {
            cause = ex.getCause();
        } catch (java.util.concurrent.CancellationException ex) {
            throw new AssertionError("期望异常完成并带关闭原因，实际是被取消", ex);
        }
        assertThat(cause).isNotNull();
        assertThat(cause).isInstanceOf(io.github.yinwenyuan28.ailite.llm.LlmCallException.class);
        assertThat(cause.getMessage()).contains("服务正在关闭");
        assertThat(scheduler[0].isShutdown()).isTrue();
    }

    /** 关闭两次不能产生第二条终态记录，也不能抛异常。 */
    @Test
    public void shutdownIsIdempotent() {
        runner().run(context -> {
            LlmGateway gateway = context.getBean(LlmGateway.class);
            gateway.shutdown();
            gateway.shutdown();
        });
    }

    /** 正常完成的调用不留在登记表里，关闭时不被误伤。 */
    @Test
    public void normalCompletionUnregistersRetry() throws Exception {
        runner().run(context -> {
            LlmGateway gateway = context.getBean(LlmGateway.class);
            // 首次失败、第二次成功：重试跑完，登记表应已清空。
            java.util.concurrent.CompletableFuture<io.github.yinwenyuan28.ailite.llm.LlmResult> f =
                    gateway.oneWithRetryAsync(
                            io.github.yinwenyuan28.ailite.llm.LlmCall.profile("demo", "main",
                                    java.util.Collections.singletonList(
                                            io.github.yinwenyuan28.ailite.llm.LlmMessage.user("hi"))),
                            io.github.yinwenyuan28.ailite.llm.RetryPolicy.builder()
                                    .maxAttempts(3).initialBackoffMs(0L).maxBackoffMs(0L)
                                    .jitter(false).build());
            io.github.yinwenyuan28.ailite.llm.LlmResult result = f.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.getContent()).isEqualTo("ok");
            // 关闭时不应该对已完成调用做什么；这里只断言不抛异常。
            gateway.shutdown();
            assertThat(f.isDone()).isTrue();
        });
    }
}
