package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.llm.RetryPolicy;
import io.github.yinwenyuan28.ailite.internal.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.internal.llm.RetryScheduler;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starter 对重试调度器的装配契约。
 *
 * <p>Bean 名 {@code aiLiteRetryScheduler} 是覆盖契约；网关按 {@code @Qualifier}
 * 注入，不能因为用户另有 {@link ScheduledExecutorService} 而注入失败。</p>
 */
public class AsyncRetryAssemblyTest {

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

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AiLiteAutoConfiguration.class,
                        ConfigurationPropertiesAutoConfiguration.class))
                .withPropertyValues(BASE)
                .withUserConfiguration(FakeTransportConfig.class);
    }

    @Test
    public void e01DefaultSchedulerIsDaemonAndNamed() {
        final ScheduledExecutorService[] captured = new ScheduledExecutorService[1];
        final String[] name = new String[1];
        final boolean[] daemon = new boolean[1];

        runner().run(context -> {
            assertThat(context).hasBean(RetryScheduler.BEAN_NAME);
            ScheduledExecutorService scheduler =
                    context.getBean(RetryScheduler.BEAN_NAME, ScheduledExecutorService.class);
            captured[0] = scheduler;
            scheduler.submit(new Runnable() {
                @Override
                public void run() {
                    name[0] = Thread.currentThread().getName();
                    daemon[0] = Thread.currentThread().isDaemon();
                }
            }).get(2, TimeUnit.SECONDS);
        });

        assertThat(name[0]).startsWith(RetryScheduler.THREAD_PREFIX);
        assertThat(daemon[0]).isTrue();
        // ApplicationContextRunner 已关闭上下文，默认调度器应被 shutdownNow。
        assertThat(captured[0].isShutdown()).isTrue();
    }

    @Test
    public void e02CustomSameNameBeanOverridesDefaultAndIsUsed() {
        runner().withUserConfiguration(CustomSchedulerConfig.class).run(context -> {
            assertThat(context.getBean(RetryScheduler.BEAN_NAME))
                    .isInstanceOf(MarkerScheduler.class);
            MarkerScheduler marker = (MarkerScheduler) context.getBean(RetryScheduler.BEAN_NAME);

            LlmGateway gateway = context.getBean(LlmGateway.class);
            LlmResult result = gateway.oneWithRetryAsync(call(), zeroPolicy())
                    .get(5, TimeUnit.SECONDS);

            assertThat(result.getContent()).isEqualTo("ok");
            assertThat(marker.scheduled.get()).isGreaterThan(0);
        });
    }

    @Test
    public void e03OtherScheduledExecutorBeansDoNotCreateAmbiguity() {
        runner().withUserConfiguration(ExtraSchedulerConfig.class).run(context -> {
            assertThat(context).hasBean("unrelatedScheduler");
            assertThat(context).hasBean(RetryScheduler.BEAN_NAME);
            assertThat(context.getBean(LlmGateway.class)).isNotNull();
        });
    }

    @Test
    public void e04ManualFiveArgumentConstructorStillWorks() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        LlmGateway gateway = new LlmGateway(resolver(), Collections.<LlmTransport>singletonList(transport),
                new NoopLlmCallStore(),
                new DirectExecutorProvider(), new SequentialToolExecutor());

        LlmResult result = gateway.oneWithRetryAsync(call(), zeroPolicy())
                .get(5, TimeUnit.SECONDS);

        assertThat(result.getContent()).isEqualTo("ok");
        // 旧构造器走共享守护调度器，第一次失败后第二次在重试线程上成功。
        assertThat(transport.attempts.get()).isEqualTo(2);
    }

    @Test
    public void e05InjectedSchedulerIsNotClosedByGateway() throws Exception {
        ScheduledThreadPoolExecutor scheduler = RetryScheduler.create();
        try {
            RecordingTransport transport = new RecordingTransport();
            LlmGateway gateway = new LlmGateway(resolver(),
                    Collections.<LlmTransport>singletonList(transport),
                    new NoopLlmCallStore(),
                    new DirectExecutorProvider(), new SequentialToolExecutor(), scheduler);

            gateway.oneWithRetryAsync(call(), zeroPolicy()).get(5, TimeUnit.SECONDS);

            assertThat(scheduler.isShutdown()).isFalse();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    public void e06UserSchedulerIsNotClosedByContext() {
        final MarkerScheduler[] captured = new MarkerScheduler[1];
        runner().withUserConfiguration(CustomSchedulerConfig.class).run(context -> {
            captured[0] = (MarkerScheduler) context.getBean(RetryScheduler.BEAN_NAME);
        });

        // 用户 Bean 显式关闭了 destroy 推断，上下文关闭不代表普通库可以替它善后。
        assertThat(captured[0].isShutdown()).isFalse();
        captured[0].shutdownNow();
    }

    @Test
    public void e07ContextCloseLeavesNoDefaultSchedulerThread() throws Exception {
        final ScheduledExecutorService[] captured = new ScheduledExecutorService[1];
        runner().run(context -> captured[0] =
                context.getBean(RetryScheduler.BEAN_NAME, ScheduledExecutorService.class));

        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!captured[0].isTerminated() && System.nanoTime() < end) {
            Thread.sleep(10L);
        }
        assertThat(captured[0].isShutdown()).isTrue();
    }

    // ---------------------------------------------------------------- 配置与替身

    private static LlmCall call() {
        return LlmCall.profile("demo", "main",
                Collections.singletonList(LlmMessage.user("hi")));
    }

    private static RetryPolicy zeroPolicy() {
        return RetryPolicy.builder()
                .maxAttempts(3).initialBackoffMs(0L).maxBackoffMs(0L)
                .jitter(false).build();
    }

    static ModelResolver resolver() {
        AiLiteConfig.Llm llm = new AiLiteConfig.Llm();
        llm.setBizIds(Collections.singletonList("demo"));

        AiLiteConfig.Model model = new AiLiteConfig.Model();
        model.setId("m");
        model.setReasoning(false);
        model.setInput(Collections.singletonList("text"));
        model.setMaxTokens(Integer.valueOf(4096));

        AiLiteConfig.Provider provider = new AiLiteConfig.Provider();
        provider.setApi("fake");
        provider.setBaseUrl("http://localhost");
        provider.setApiKey("key");
        provider.setModels(Collections.singletonList(model));
        llm.getProviders().put("test", provider);

        AiLiteConfig.Profile profile = new AiLiteConfig.Profile();
        profile.setProvider("test");
        profile.setModel("m");
        profile.setThinkingLevel("off");
        llm.getProfiles().put("main", profile);
        return new ModelResolver(llm);
    }

    @Configuration
    static class FakeTransportConfig {

        @Bean
        public LlmTransport fakeTransport() {
            return new RecordingTransport();
        }
    }

    @Configuration
    static class CustomSchedulerConfig {

        @Bean(name = RetryScheduler.BEAN_NAME, destroyMethod = "")
        public ScheduledExecutorService customRetryScheduler() {
            return new MarkerScheduler();
        }
    }

    @Configuration
    static class ExtraSchedulerConfig {

        @Bean(name = "unrelatedScheduler", destroyMethod = "")
        public ScheduledExecutorService unrelatedScheduler() {
            return new MarkerScheduler();
        }
    }

    /**
     * 记录 schedule 次数，证明网关确实使用了这个 Bean。
     */
    static final class MarkerScheduler extends ScheduledThreadPoolExecutor {

        final AtomicInteger scheduled = new AtomicInteger();

        MarkerScheduler() {
            super(1, r -> {
                Thread thread = new Thread(r, "marker-retry-1");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(
                Runnable command, long delay, TimeUnit unit) {
            scheduled.incrementAndGet();
            return super.schedule(command, delay, unit);
        }
    }

    /**
     * 第一次失败、第二次成功，确保重试路径被走到。
     */
    static final class RecordingTransport implements LlmTransport {

        final AtomicInteger attempts = new AtomicInteger();

        @Override
        public String api() {
            return "fake";
        }

        @Override
        public LlmCompletion complete(io.github.yinwenyuan28.ailite.llm.LlmRequest request) {
            if (attempts.getAndIncrement() == 0) {
                throw new io.github.yinwenyuan28.ailite.llm.LlmCallException(
                        "transient", new java.io.IOException("io"),
                        io.github.yinwenyuan28.ailite.llm.LlmCallException.Kind.TRANSPORT);
            }
            return completion();
        }

        @Override
        public CompletableFuture<LlmCompletion> completeAsync(
                io.github.yinwenyuan28.ailite.llm.LlmRequest request) {
            if (attempts.getAndIncrement() == 0) {
                CompletableFuture<LlmCompletion> failed =
                        new CompletableFuture<LlmCompletion>();
                failed.completeExceptionally(
                        new io.github.yinwenyuan28.ailite.llm.LlmCallException(
                                "transient", new java.io.IOException("io"),
                                io.github.yinwenyuan28.ailite.llm.LlmCallException.Kind.TRANSPORT));
                return failed;
            }
            return CompletableFuture.completedFuture(completion());
        }

        private static LlmCompletion completion() {
            return new LlmCompletion("ok", null, LlmStopReason.STOP, LlmUsage.zero(), null);
        }
    }
}
