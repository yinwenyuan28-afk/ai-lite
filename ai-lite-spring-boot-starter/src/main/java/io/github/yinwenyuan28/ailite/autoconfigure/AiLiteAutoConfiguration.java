package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.assembly.Workflow;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.executor.PlatformExecutorProvider;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor;
import io.github.yinwenyuan28.ailite.llm.AsyncLlmCallLogger;
import io.github.yinwenyuan28.ailite.llm.LlmCallStore;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.llm.ToolExecutor;
import io.github.yinwenyuan28.ailite.transport.AnthropicMessagesTransport;
import io.github.yinwenyuan28.ailite.transport.OpenAiCompletionsTransport;
import io.github.yinwenyuan28.ailite.transport.OpenAiResponsesTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * AI Lite 主自动装配。只做装配，不改变核心模块的行为。
 *
 * <p>Boot 2.1 兼容：使用 {@code spring.factories} 注册，不使用
 * {@code @AutoConfiguration} 或 {@code AutoConfiguration.imports}。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "ai-lite", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AiLiteAutoConfiguration {

    /**
     * 配置模型。绑定在 Bean 方法上，核心类不需要 Spring 注解。
     *
     * @return 配置对象
     */
    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "ai-lite")
    public AiLiteConfig aiLiteConfig() {
        return new AiLiteConfig();
    }

    /**
     * 异步执行器。mode 支持 platform、direct、virtual、coroutines。
     *
     * @param config 配置
     * @return 执行器
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ExecutorProvider aiLiteExecutorProvider(AiLiteConfig config) {
        String mode = config.getExecutor().getMode();
        if ("direct".equalsIgnoreCase(mode)) {
            return new DirectExecutorProvider();
        }
        if ("virtual".equalsIgnoreCase(mode)) {
            return virtualExecutor();
        }
        if ("coroutines".equalsIgnoreCase(mode)) {
            return coroutineExecutor(config);
        }
        return new PlatformExecutorProvider(config.getExecutor());
    }

    /**
     * 默认 HTTP 实现。OkHttp 不在类路径时由使用者自行提供 HttpExecutor。
     *
     * @return HTTP 执行器
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "io.github.yinwenyuan28.ailite.http.okhttp.OkHttpExecutor")
    public HttpExecutor aiLiteHttpExecutor() {
        return new OkHttpExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelResolver aiLiteModelResolver(AiLiteConfig config) {
        return new ModelResolver(config.getLlm());
    }

    @Bean
    @ConditionalOnMissingBean(name = "openAiCompletionsTransport")
    public LlmTransport openAiCompletionsTransport(HttpExecutor http) {
        return new OpenAiCompletionsTransport(http);
    }

    @Bean
    @ConditionalOnMissingBean(name = "openAiResponsesTransport")
    public LlmTransport openAiResponsesTransport(HttpExecutor http) {
        return new OpenAiResponsesTransport(http);
    }

    @Bean
    @ConditionalOnMissingBean(name = "anthropicMessagesTransport")
    public LlmTransport anthropicMessagesTransport(HttpExecutor http) {
        return new AnthropicMessagesTransport(http);
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmCallStore aiLiteCallStore() {
        return new NoopLlmCallStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolExecutor aiLiteToolExecutor() {
        return new SequentialToolExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmGateway aiLiteGateway(ModelResolver resolver, List<LlmTransport> transports,
                                    LlmCallStore store, ExecutorProvider executor,
                                    ToolExecutor toolExecutor) {
        return new LlmGateway(resolver, transports, store, executor, toolExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public Workflow aiLiteWorkflow(LlmGateway gateway) {
        return new Workflow(gateway);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentLoop aiLiteAgentLoop(LlmGateway gateway, ExecutorProvider executor) {
        return new AgentLoop(gateway, executor);
    }

    /**
     * 虚拟线程执行器在独立模块里，用反射加载，JDK 8 运行时不会解析该类。
     */
    private static ExecutorProvider virtualExecutor() {
        try {
            Class<?> type = Class.forName(
                    "io.github.yinwenyuan28.ailite.executor.virtual.VirtualThreadExecutorProvider");
            return (ExecutorProvider) type.newInstance();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "ai-lite.executor.mode=virtual 需要 JDK 21 和 ai-lite-executor-jdk21 依赖", ex);
        }
    }

    /**
     * 协程执行器是 JDK 8 可用的可选模块，按配置显式开启。
     */
    private static ExecutorProvider coroutineExecutor(AiLiteConfig config) {
        try {
            Class<?> type = Class.forName(
                    "io.github.yinwenyuan28.ailite.executor.coroutines.CoroutineExecutorProvider");
            AiLiteConfig.Executor executor = config.getExecutor();
            return (ExecutorProvider) type.getConstructor(int.class, String.class)
                    .newInstance(Integer.valueOf(executor.getCoreSize()),
                            executor.getThreadNamePrefix() + "coroutine-");
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "ai-lite.executor.mode=coroutines 需要 ai-lite-executor-coroutines 依赖", ex);
        }
    }
}
