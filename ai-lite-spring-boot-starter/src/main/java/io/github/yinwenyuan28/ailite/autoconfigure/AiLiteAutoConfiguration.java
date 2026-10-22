package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.assembly.Workflow;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.executor.PlatformExecutorProvider;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

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
     * 按 {@code ai-lite.http.transport} 选择 HTTP 实现。
     *
     * <p>所有实现都通过反射加载，这样排除 OkHttp 或没有引入 Jetty/WebClient/Ktor
     * 模块时，类加载不会在装配阶段失败。用户自定义的 {@link HttpExecutor} Bean
     * 仍然优先（{@code @ConditionalOnMissingBean}）。</p>
     *
     * @param config 配置
     * @return HTTP 执行器
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public HttpExecutor aiLiteHttpExecutor(AiLiteConfig config) {
        return createHttpExecutor(config);
    }

    /**
     * 启动后打印实际传输实现，并提示被忽略的配置。放在独立 Bean 里，避免
     * 自定义 HttpExecutor 时 {@link #aiLiteHttpExecutor(AiLiteConfig)} 不执行导致没有日志。
     *
     * @param config  配置
     * @param context Spring 上下文
     * @return 报告器
     */
    @Bean
    public HttpTransportReporter aiLiteHttpTransportReporter(AiLiteConfig config,
                                                             ApplicationContext context) {
        return new HttpTransportReporter(config, context);
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
     * 传输名与实现类的映射。实现类都由可选模块提供，因此统一反射加载。
     */
    private static HttpExecutor createHttpExecutor(AiLiteConfig config) {
        String transport = normalizeTransport(config.getHttp().getTransport());
        if ("okhttp".equals(transport)) {
            return invokeFactory("io.github.yinwenyuan28.ailite.http.okhttp.OkHttpClientFactory",
                    "executor", config, transport, "ai-lite-http-okhttp");
        }
        if ("okhttp-virtual".equals(transport)) {
            return invokeFactory(
                    "io.github.yinwenyuan28.ailite.http.okhttp.jdk21.VirtualThreadOkHttpFactory",
                    "create", config, transport, "ai-lite-http-okhttp-jdk21（JDK 21+）");
        }
        if ("jetty".equals(transport)) {
            return invokeFactory("io.github.yinwenyuan28.ailite.http.jetty.JettyHttpExecutor",
                    "create", config, transport, "ai-lite-http-jetty");
        }
        if ("webclient".equals(transport)) {
            return invokeFactory("io.github.yinwenyuan28.ailite.http.webclient.WebClientHttpExecutor",
                    "create", config, transport, "ai-lite-http-webclient 与 spring-webflux");
        }
        if ("ktor-cio".equals(transport)) {
            return invokeFactory("io.github.yinwenyuan28.ailite.http.ktor.KtorCioHttpExecutor",
                    "create", config, transport, "ai-lite-http-ktor-cio");
        }
        if ("jdk8".equals(transport)) {
            return instantiate("io.github.yinwenyuan28.ailite.http.jdk8.Jdk8HttpExecutor",
                    transport, "ai-lite-http-jdk8");
        }
        throw new IllegalStateException("不支持 ai-lite.http.transport="
                + config.getHttp().getTransport()
                + "，可选值：okhttp、okhttp-virtual、jetty、webclient、ktor-cio、jdk8");
    }

    /**
     * 调用可选模块的静态工厂 {@code create(AiLiteConfig.Http)}。
     */
    private static HttpExecutor invokeFactory(String className, String methodName,
                                              AiLiteConfig config,
                                              String transport, String module) {
        try {
            Class<?> type = loadClass(className);
            Method create = type.getMethod(methodName, AiLiteConfig.Http.class);
            return (HttpExecutor) create.invoke(null, config.getHttp());
        } catch (ClassNotFoundException ex) {
            throw missingModule(transport, module, ex);
        } catch (LinkageError ex) {
            throw missingModule(transport, module, ex);
        } catch (InvocationTargetException ex) {
            throw rethrowFactoryFailure(transport, ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法初始化 ai-lite.http.transport=" + transport, ex);
        }
    }

    /**
     * 实例化无参实现，用于 jdk8 这类不依赖配置的执行器。
     */
    private static HttpExecutor instantiate(String className, String transport, String module) {
        try {
            Class<?> type = loadClass(className);
            return (HttpExecutor) type.newInstance();
        } catch (ClassNotFoundException ex) {
            throw missingModule(transport, module, ex);
        } catch (LinkageError ex) {
            throw missingModule(transport, module, ex);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法初始化 ai-lite.http.transport=" + transport, ex);
        }
    }

    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = AiLiteAutoConfiguration.class.getClassLoader();
        }
        return Class.forName(className, true, loader);
    }

    private static IllegalStateException missingModule(String transport, String module,
                                                       Throwable cause) {
        return new IllegalStateException("ai-lite.http.transport=" + transport
                + " 需要 " + module + " 提供 HttpExecutor，且运行时类路径必须完整", cause);
    }

    private static RuntimeException rethrowFactoryFailure(String transport, Throwable cause) {
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new IllegalStateException("无法初始化 ai-lite.http.transport=" + transport, cause);
    }

    private static String normalizeTransport(String transport) {
        if (transport == null || transport.trim().isEmpty()) {
            return "okhttp";
        }
        return transport.trim().toLowerCase(Locale.ROOT);
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
