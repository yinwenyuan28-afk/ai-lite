package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.session.AgentSession;
import io.github.yinwenyuan28.ailite.session.AgentSessionStore;
import io.github.yinwenyuan28.ailite.session.InMemoryAgentSessionStore;
import org.junit.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 自动装配的对抗性用例：开关、用户覆盖、缺失依赖和配置错误。
 */
public class AiLiteAutoConfigurationAdversarialTest {

    private static final String[] BASE = {
            "ai-lite.llm.biz-ids[0]=demo",
            "ai-lite.llm.providers.demo.api=openai-completions",
            "ai-lite.llm.providers.demo.base-url=http://127.0.0.1:1/v1",
            "ai-lite.llm.providers.demo.api-key=test-key",
            "ai-lite.llm.providers.demo.models[0].id=m",
            "ai-lite.llm.profiles.main.provider=demo",
            "ai-lite.llm.profiles.main.model=m",
            "ai-lite.llm.profiles.main.thinking-level=off"
    };

    private static ApplicationContextRunner runner(String... extra) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AiLiteAutoConfiguration.class, AiLiteSessionAutoConfiguration.class,
                        ConfigurationPropertiesAutoConfiguration.class))
                .withPropertyValues(BASE);
        return extra.length == 0 ? runner : runner.withPropertyValues(extra);
    }

    /**
     * 与 {@link #BASE} 相同，但故意不设置 api-key/base-url/models，用于验证启动期行为。
     */
    private static ApplicationContextRunner runnerWithout(String propertyToDrop, String... extra) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AiLiteAutoConfiguration.class, AiLiteSessionAutoConfiguration.class,
                        ConfigurationPropertiesAutoConfiguration.class));
        for (String property : BASE) {
            if (!property.startsWith(propertyToDrop)) {
                runner = runner.withPropertyValues(property);
            }
        }
        return extra.length == 0 ? runner : runner.withPropertyValues(extra);
    }

    @Test
    public void globalSwitchRemovesAllAiLiteBeans() {
        runner("ai-lite.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(LlmGateway.class);
            assertThat(context).doesNotHaveBean(ModelResolver.class);
            assertThat(context).doesNotHaveBean(AgentSessionStore.class);
            assertThat(context).doesNotHaveBean(ExecutorProvider.class);
        });
    }

    @Test
    public void sessionSwitchKeepsGatewayButRemovesSessionBeans() {
        runner("ai-lite.session.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(LlmGateway.class);
            assertThat(context).hasSingleBean(ModelResolver.class);
            assertThat(context).doesNotHaveBean(AgentSessionStore.class);
            assertThat(context).doesNotHaveBean(io.github.yinwenyuan28.ailite.session.AgentSessionLoop.class);
        });
    }

    @Test
    public void userGatewayOverridesDefault() {
        runner().withUserConfiguration(CustomGatewayConfig.class).run(context -> {
            assertThat(context).hasSingleBean(LlmGateway.class);
            assertThat(context).hasBean("customGateway");
            assertThat(context).doesNotHaveBean("aiLiteGateway");
        });
    }

    @Test
    public void userSessionStoreOverridesDefault() {
        runner().withUserConfiguration(CustomSessionStoreConfig.class).run(context -> {
            assertThat(context).hasSingleBean(AgentSessionStore.class);
            assertThat(context.getBean(AgentSessionStore.class)).isInstanceOf(CustomSessionStore.class);
            assertThat(context).doesNotHaveBean("aiLiteSessionStore");
        });
    }

    @Test
    public void userHttpExecutorOverridesOkHttpDefault() {
        runner().withUserConfiguration(CustomHttpConfig.class).run(context -> {
            assertThat(context).hasSingleBean(HttpExecutor.class);
            assertThat(context.getBean(HttpExecutor.class)).isInstanceOf(NoopHttpExecutor.class);
            assertThat(context).doesNotHaveBean(OkHttpExecutor.class);
        });
    }

    @Test
    public void missingHttpExecutorMakesTheContextFail() {
        runner().withClassLoader(new FilteredClassLoader("io.github.yinwenyuan28.ailite.http.okhttp"))
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(allMessages(context.getStartupFailure())).contains("HttpExecutor");
                });
    }

    @Test
    public void virtualExecutorWithoutJdk21ModuleFailsWithActionableMessage() {
        runner("ai-lite.executor.mode=virtual").run(context -> {
            assertThat(context.getStartupFailure()).isNotNull();
            // 异常链里应同时出现包装后的可操作提示和底层 ClassNotFoundException。
            assertThat(hasCause(context.getStartupFailure(), IllegalStateException.class)).isTrue();
            assertThat(allMessages(context.getStartupFailure()))
                    .contains("virtual").contains("jdk21");
        });
    }

    @Test
    public void coroutineExecutorModeIsHonoured() {
        runner("ai-lite.executor.mode=coroutines").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(ExecutorProvider.class);
            assertThat(context.getBean(ExecutorProvider.class).getClass().getName())
                    .isEqualTo("io.github.yinwenyuan28.ailite.executor.coroutines.CoroutineExecutorProvider");
        });
    }

    @Test
    public void directExecutorModeIsHonoured() {
        runner("ai-lite.executor.mode=direct").run(context -> {
            assertThat(context).hasSingleBean(ExecutorProvider.class);
            assertThat(context.getBean(ExecutorProvider.class)).isInstanceOf(DirectExecutorProvider.class);
        });
    }

    @Test
    public void missingApiKeyStartsAndFailsOnlyWhenCalled() {
        runnerWithout("ai-lite.llm.providers.demo.api-key").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            LlmGateway gateway = context.getBean(LlmGateway.class);

            assertThatThrownBy(() -> gateway.one(LlmCall.explicit("demo", "demo", "m", null,
                    Collections.singletonList(LlmMessage.user("hi")))))
                    .isInstanceOf(LlmCallException.class)
                    .hasMessageContaining("模型密钥未配置");
        });
    }

    @Test
    public void missingBaseUrlFailsFastAtStartup() {
        runnerWithout("ai-lite.llm.providers.demo.base-url").run(context -> {
            assertThat(context.getStartupFailure()).isNotNull();
            assertThat(allMessages(context.getStartupFailure())).contains("base-url");
        });
    }

    @Test
    public void providerWithoutModelsFailsFastAtStartup() {
        runnerWithout("ai-lite.llm.providers.demo.models").run(context -> {
            assertThat(context.getStartupFailure()).isNotNull();
            assertThat(allMessages(context.getStartupFailure())).contains("没有配置模型");
        });
    }

    @Test
    public void modelWithoutIdFailsFastAtStartup() {
        // 保留一个模型条目但只给 name，确保走到“缺少 id”而不是“没有配置模型”。
        runnerWithout("ai-lite.llm.providers.demo.models[0].id",
                "ai-lite.llm.providers.demo.models[0].name=no-id").run(context -> {
            assertThat(context.getStartupFailure()).isNotNull();
            assertThat(allMessages(context.getStartupFailure())).contains("缺少 id");
        });
    }

    @Test
    public void emptyBizIdListStartsButCallsAreRejected() {
        runnerWithout("ai-lite.llm.biz-ids").run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            LlmGateway gateway = context.getBean(LlmGateway.class);

            assertThatThrownBy(() -> gateway.one(LlmCall.explicit("demo", "demo", "m", null,
                    Collections.singletonList(LlmMessage.user("hi")))))
                    .isInstanceOf(LlmCallException.class)
                    .hasMessageContaining("业务 id 未登记");
        });
    }

    // ------------------------------------------------------------- helpers

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static String allMessages(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            text.append(current.toString()).append('\n');
            current = current.getCause() == current ? null : current.getCause();
        }
        return text.toString();
    }

    /**
     * 用户自定义网关，打断默认网关的创建。
     */
    @Configuration
    static class CustomGatewayConfig {
        @Bean
        public LlmGateway customGateway(ModelResolver resolver) {
            return new LlmGateway(resolver, Collections.<LlmTransport>emptyList(),
                    new NoopLlmCallStore(), new DirectExecutorProvider(),
                    new SequentialToolExecutor());
        }
    }

    /**
     * 用户自定义会话存储。
     */
    @Configuration
    static class CustomSessionStoreConfig {
        @Bean
        public AgentSessionStore customSessionStore() {
            return new CustomSessionStore();
        }
    }

    /**
     * 用户自定义 HTTP 执行器。
     */
    @Configuration
    static class CustomHttpConfig {
        @Bean
        public HttpExecutor customHttpExecutor() {
            return new NoopHttpExecutor();
        }
    }

    static final class CustomSessionStore implements AgentSessionStore {
        private final InMemoryAgentSessionStore delegate = new InMemoryAgentSessionStore(10, 60);

        @Override
        public Optional<AgentSession> find(String userId, String sessionId) {
            return delegate.find(userId, sessionId);
        }

        @Override
        public void save(AgentSession session) {
            delegate.save(session);
        }

        @Override
        public void delete(String userId, String sessionId) {
            delegate.delete(userId, sessionId);
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return delegate.exists(userId, sessionId);
        }
    }

    static final class NoopHttpExecutor implements HttpExecutor {
        @Override
        public HttpResult execute(HttpRequest request) {
            return new HttpResult(200, "{}");
        }

        @Override
        public void executeStreaming(HttpRequest request, StreamListener listener) {
            // 测试不需要真实 I/O。
        }

        @Override
        public void close() {
            // 无资源。
        }
    }
}
