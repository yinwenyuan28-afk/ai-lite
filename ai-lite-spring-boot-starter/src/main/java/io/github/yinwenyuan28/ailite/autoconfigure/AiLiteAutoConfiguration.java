package io.github.yinwenyuan28.ailite.autoconfigure;

import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.assembly.Workflow;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.executor.PlatformExecutorProvider;
import io.github.yinwenyuan28.ailite.guard.GuardMetrics;
import io.github.yinwenyuan28.ailite.guard.ProviderGuard;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
        // key 冷却时长来自出口保护配置（限流熔断计划 §3.4），默认 60s。
        return new ModelResolver(config.getLlm(),
                config.getGuard().getKeyCooldownSeconds() * 1000L);
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

    // 出口保护指标回调（aiLiteGuardMetrics）与指标存储装饰器都移入文末的
    // MicrometerMetricsConfiguration 嵌套类：consumer 的 classpath 不一定有
    // micrometer，外层 @Bean 方法签名出现 MeterRegistry 会让无 micrometer 的
    // 应用在装配期直接 ClassNotFoundException（外部真实供应商测试发现的回归）。

    /**
     * 供应商出口保护表（限流熔断计划 §3.7）：只为配置了 guard 的 provider 建实例
     * ——provider 级 {@code guard.concurrency > 0}，或顶级熔断 {@code enabled=true}
     * （此时并发不限，只做熔断）。全默认时返回空表：网关直通，acquire 不发生。
     *
     * <p>刻意不做独立 Bean：Spring 对 {@code Map<K,V>} 参数注入的是"所有 V 类型
     * bean 收集成的 map"而非 Map bean 本身，独立 bean 会让网关拿到空表。用户覆盖
     * 途径是自定义 LlmGateway Bean（与回调策略 CB-09 一致）。</p>
     */
    private Map<String, ProviderGuard> buildProviderGuards(AiLiteConfig config,
            GuardMetrics guardMetrics, ScheduledExecutorService retryScheduler) {
        Map<String, ProviderGuard> guards = new LinkedHashMap<String, ProviderGuard>();
        AiLiteConfig.Guard guardCfg = config.getGuard();
        AiLiteConfig.Guard.Circuit circuitCfg = guardCfg.getCircuit();
        ProviderGuard.CircuitSettings circuit = circuitCfg.isEnabled()
                ? new ProviderGuard.CircuitSettings(circuitCfg.getFailureRate(),
                        circuitCfg.getWindowSeconds() * 1000L, circuitCfg.getMinimumCalls(),
                        circuitCfg.getOpenSeconds() * 1000L, circuitCfg.getProbeTimeoutMs())
                : null;
        // 排队超时定时器复用重试调度器（同一 daemon 池，不新增线程资源）。
        ProviderGuard.Delayer delayer = new ProviderGuard.Delayer() {
            @Override
            public ScheduledFuture<?> schedule(Runnable task, long delayMs) {
                return retryScheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            }
        };
        if (config.getLlm().getProviders() != null) {
            for (Map.Entry<String, AiLiteConfig.Provider> entry
                    : config.getLlm().getProviders().entrySet()) {
                AiLiteConfig.Provider provider = entry.getValue();
                int concurrency = provider.getGuard() == null
                        ? 0 : provider.getGuard().getConcurrency();
                boolean limited = concurrency > 0;
                if (!limited && circuit == null) {
                    // 未配置任何 guard 项：不建实例（NOOP 直通，零开销）。
                    continue;
                }
                guards.put(entry.getKey(), new ProviderGuard(entry.getKey(),
                        limited ? concurrency : Integer.MAX_VALUE,
                        guardCfg.getConcurrencyWaitMs(),
                        guardCfg.getAsyncQueueCapacity(),
                        circuit, System::currentTimeMillis, delayer, guardMetrics));
            }
        }
        return guards;
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmGateway aiLiteGateway(ModelResolver resolver, List<LlmTransport> transports,
                                    LlmCallStore store, ExecutorProvider executor,
                                    ToolExecutor toolExecutor,
                                    @Qualifier(RetryScheduler.BEAN_NAME)
                                    ScheduledExecutorService retryScheduler,
                                    ObjectProvider<CallbackPolicy> callbackPolicy,
                                    ObjectProvider<Function<LlmCallStore, LlmCallStore>> metricsStoreDecorator,
                                    ObjectProvider<GuardMetrics> guardMetrics,
                                    AiLiteConfig config) {
        // 可观测性计划：micrometer 在 classpath 且启用时，由嵌套条件类提供的装饰器
        // 包装记录存储（不替换用户 Bean，装饰只发生在传给网关的引用上）。
        // micrometer 缺失时该 Bean 不存在，getIfAvailable 返回 null，零影响。
        LlmCallStore effectiveStore = store;
        Function<LlmCallStore, LlmCallStore> decorator = metricsStoreDecorator.getIfAvailable();
        if (decorator != null) {
            effectiveStore = decorator.apply(store);
        }
        LlmGateway gateway = new LlmGateway(resolver, transports, effectiveStore, executor,
                toolExecutor, retryScheduler);
        gateway.setCallbackPolicy(callbackPolicy.getIfAvailable());
        GuardMetrics metrics = guardMetrics.getIfAvailable();
        gateway.setGuards(buildProviderGuards(config,
                metrics == null ? GuardMetrics.NOOP : metrics, retryScheduler));
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

    /**
     * Micrometer 存在时的指标装配（可观测性计划 + 限流熔断计划）。
     *
     * <p>嵌套独立成类的原因：{@code @ConditionalOnClass} 在评估阶段只通过 ASM 读
     * 字节码元数据，不会真正加载 {@link MeterRegistry}，因此 consumer 没有引入
     * micrometer 时整个内部类被安全跳过。若把这些 Bean 放在外层配置类，外层
     * {@code @Bean} 方法签名里的 {@code ObjectProvider<MeterRegistry>} 会在装配期
     * 触发类型解析，无 micrometer 的应用直接 ClassNotFoundException（2026-10-25
     * 外部真实供应商测试发现的发布形态回归，固化为结构约束）。</p>
     */
    @Configuration
    @ConditionalOnClass(MeterRegistry.class)
    static class MicrometerMetricsConfiguration {

        /**
         * 出口保护指标回调（限流熔断计划 §3.5）：registry Bean 存在时挂 Micrometer，
         * 否则 NOOP。嵌套类保证 MeterRegistry 类可加载；ObjectProvider 再容忍
         * "classpath 有 micrometer 但容器里没有 registry Bean"的应用（无 actuator）。
         * core 侧只依赖接口。
         */
        @Bean(name = "aiLiteGuardMetrics")
        @ConditionalOnMissingBean
        public GuardMetrics aiLiteGuardMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            return registry == null ? GuardMetrics.NOOP : new MicrometerGuardMetrics(registry);
        }

        /**
         * 记录存储的指标装饰器（可观测性计划）：返回"包装一层的函数"而不是包装好的
         * store——装饰对象必须等网关解析完用户 store Bean 之后再套（网关负责传入），
         * 这里只决定"有没有装饰、怎么装饰"。未启用指标或 registry Bean 缺失时返回
         * 恒等函数（等价于无装饰）。
         */
        @Bean(name = "aiLiteMetricsStoreDecorator")
        @ConditionalOnMissingBean(name = "aiLiteMetricsStoreDecorator")
        public Function<LlmCallStore, LlmCallStore> aiLiteMetricsStoreDecorator(
                ObjectProvider<MeterRegistry> meterRegistry, AiLiteConfig config) {
            final MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry == null || !config.getMetrics().isLlmEnabled()) {
                return new Function<LlmCallStore, LlmCallStore>() {
                    @Override
                    public LlmCallStore apply(LlmCallStore store) {
                        return store;
                    }
                };
            }
            return new Function<LlmCallStore, LlmCallStore>() {
                @Override
                public LlmCallStore apply(LlmCallStore store) {
                    return new MicrometerLlmCallStore(store, registry);
                }
            };
        }
    }
}
