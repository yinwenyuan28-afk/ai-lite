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
import io.github.yinwenyuan28.ailite.llm.CallbackPolicy;
import io.github.yinwenyuan28.ailite.llm.LlmCallStore;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.NoopLlmCallStore;
import io.github.yinwenyuan28.ailite.llm.RetryScheduler;
import io.github.yinwenyuan28.ailite.llm.SequentialToolExecutor;
import io.github.yinwenyuan28.ailite.llm.ToolExecutor;
import io.github.yinwenyuan28.ailite.transport.AnthropicMessagesTransport;
import io.github.yinwenyuan28.ailite.transport.OpenAiCompletionsTransport;
import io.github.yinwenyuan28.ailite.transport.OpenAiResponsesTransport;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI Lite 主自动装配。只做装配，不改变核心模块的行为。
 *
 * <p>Boot 2.1 兼容：使用 {@code spring.factories} 注册，不使用
 * {@code @AutoConfiguration} 或 {@code AutoConfiguration.imports}。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "ai-lite", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AiLiteAutoConfiguration implements BeanFactoryAware {

    private static final Logger log = LoggerFactory.getLogger(AiLiteAutoConfiguration.class);

    private ConfigurableListableBeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
    }

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

    /**
     * 异步重试专用调度器。
     *
     * <p>按 Bean 名覆盖：用户定义名为 {@code aiLiteRetryScheduler} 的 Bean 即可
     * 替换默认实现。网关通过 {@code @Qualifier} 精确注入，因此用户同时定义其他
     * {@link ScheduledExecutorService} 也不会产生歧义。默认线程是 daemon，
     * 容器关闭时由 {@code shutdownNow} 回收。</p>
     *
     * @return 重试调度器
     */
    @Bean(name = RetryScheduler.BEAN_NAME, destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean(name = RetryScheduler.BEAN_NAME)
    public ScheduledExecutorService aiLiteRetryScheduler() {
        return RetryScheduler.create();
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmGateway aiLiteGateway(ModelResolver resolver, List<LlmTransport> transports,
                                    LlmCallStore store, ExecutorProvider executor,
                                    ToolExecutor toolExecutor,
                                    @Qualifier(RetryScheduler.BEAN_NAME)
                                    ScheduledExecutorService retryScheduler,
                                    ObjectProvider<CallbackPolicy> callbackPolicy,
                                    ObjectProvider<MeterRegistry> meterRegistry,
                                    AiLiteConfig config) {
        // 可观测性计划：micrometer 在 classpath 且启用时，用指标装饰器包装记录存储
        //（不替换用户 Bean，装饰只发生在传给网关的引用上）。registry 缺失时零影响。
        LlmCallStore effectiveStore = store;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null && config.getMetrics().isLlmEnabled()) {
            effectiveStore = new MicrometerLlmCallStore(store, registry);
        }
        LlmGateway gateway = new LlmGateway(resolver, transports, effectiveStore, executor,
                toolExecutor, retryScheduler);
        gateway.setCallbackPolicy(callbackPolicy.getIfAvailable());
        return gateway;
    }

    @Bean
    @ConditionalOnMissingBean
    public Workflow aiLiteWorkflow(LlmGateway gateway) {
        return new Workflow(gateway);
    }

    /**
     * 回调策略共享线程池（回调策略 Phase 0）：仅 callback.mode=io 时创建；
     * Spring 推断 destroyMethod 自动调用 shutdown()。
     */
    @Bean(name = "aiLiteCallbackExecutor")
    @ConditionalOnProperty(prefix = "ai-lite.callback", name = "mode", havingValue = "io")
    public ThreadPoolExecutor aiLiteCallbackExecutor(AiLiteConfig config) {
        AiLiteConfig.Callback.ExecutorCfg cfg = config.getCallback().getExecutor();
        final String prefix = cfg.getThreadNamePrefix() == null
                ? "ai-lite-callback-" : cfg.getThreadNamePrefix();
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, prefix + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        return new ThreadPoolExecutor(cfg.getCoreSize(), cfg.getMaxSize(), 60L,
                TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(cfg.getQueueCapacity()),
                factory);
    }

    /**
     * 回调策略（回调策略 Phase 0/CB-04/D-3）：默认 inline 行为不变；io 时绑定独立回调池。
     * direct + io 允许组合（泵结构下钩子隔离由独立池保证），打一次 WARN 提示工具本体
     * 将内联在回调线程；自定义 Gateway/AgentLoop Bean 时策略不会自动应用，同样 WARN
     * （CB-09/M-5）。
     */
    @Bean(name = "aiLiteCallbackPolicy")
    @ConditionalOnMissingBean
    public CallbackPolicy aiLiteCallbackPolicy(AiLiteConfig config,
            ObjectProvider<ThreadPoolExecutor> callbackExecutor) {
        AiLiteConfig.Callback cfg = config.getCallback();
        if (!"io".equalsIgnoreCase(cfg.getMode())) {
            return CallbackPolicy.inline();
        }
        ThreadPoolExecutor pool = callbackExecutor.getIfAvailable();
        if (pool == null) {
            pool = aiLiteCallbackExecutor(config);
        }
        if ("direct".equalsIgnoreCase(config.getExecutor().getMode())) {
            log.warn("ai-lite.executor.mode=direct 与 ai-lite.callback.mode=io 组合："
                    + "钩子隔离由独立回调池保证，但工具本体将内联在回调线程执行，"
                    + "可能阻塞本 run 的回调通道（见回调策略计划 D-3）");
        }
        warnIfCustomBean("LlmGateway", "aiLiteLlmGateway", LlmGateway.class);
        warnIfCustomBean("AgentLoop", "aiLiteAgentLoop", AgentLoop.class);
        return CallbackPolicy.io(pool, cfg.getQueueCapacity());
    }

    /** CB-09：mode=io 时用户自定义编排 Bean 不会自动获得回调策略，启动提醒。 */
    private void warnIfCustomBean(String label, String autoName, Class<?> type) {
        if (beanFactory == null) {
            return;
        }
        if (!beanFactory.containsBeanDefinition(autoName)
                && beanFactory.getBeanNamesForType(type, false, false).length > 0) {
            log.warn("检测到自定义 {} Bean：ai-lite.callback.mode=io 不会自动应用到它，"
                    + "请自行在构造时传入 CallbackPolicy（CB-09）", label);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentLoop aiLiteAgentLoop(LlmGateway gateway, ExecutorProvider executor,
            ObjectProvider<CallbackPolicy> callbackPolicy) {
        return new AgentLoop(gateway, executor, null, null, null,
                callbackPolicy.getIfAvailable());
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
