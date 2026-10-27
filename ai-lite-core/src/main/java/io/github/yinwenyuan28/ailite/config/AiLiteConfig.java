package io.github.yinwenyuan28.ailite.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Lite 的完整配置模型。
 *
 * <p>这里刻意不加 Spring 注解：核心模块保持框架无关，Starter 通过
 * {@code @ConfigurationProperties} 在 Bean 方法上完成绑定。</p>
 */
public final class AiLiteConfig {

    private boolean enabled = true;
    private Llm llm = new Llm();
    private Executor executor = new Executor();
    private Http http = new Http();
    private Session session = new Session();
    /** 回调执行策略（回调策略计划）：inline 默认不变，io 启用独立回调池。 */
    private Callback callback = new Callback();
    /** 指标配置（可观测性计划）：micrometer 在 classpath 时把 LLM 调用记录聚合成指标。 */
    private Metrics metrics = new Metrics();
    /** 出口保护（限流熔断计划）：并发限流/熔断/多 key 冷却的跨供应商通用参数，默认全关。 */
    private Guard guard = new Guard();
    /** 上下文治理（运行时能力计划）：预算检测、自动压缩、工具结果截断。 */
    private Context context = new Context();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm == null ? new Llm() : llm;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor == null ? new Executor() : executor;
    }

    public Callback getCallback() {
        return callback;
    }

    public void setCallback(Callback callback) {
        this.callback = callback == null ? new Callback() : callback;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics == null ? new Metrics() : metrics;
    }

    public Guard getGuard() {
        return guard;
    }

    public void setGuard(Guard guard) {
        this.guard = guard == null ? new Guard() : guard;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http == null ? new Http() : http;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session == null ? new Session() : session;
    }

    public Context getContext() {
        return context;
    }

    public void setContext(Context context) {
        this.context = context == null ? new Context() : context;
    }

    /**
     * 模型调用配置。
     */
    public static final class Llm {

        /** 允许调用的业务 id。留空时所有调用都会在发请求前被拒绝。 */
        private List<String> bizIds = new ArrayList<String>();
        /** 不指定 profile 时使用的默认组合。 */
        private String defaultProfile;
        private Map<String, Provider> providers = new LinkedHashMap<String, Provider>();
        private Map<String, Profile> profiles = new LinkedHashMap<String, Profile>();

        public List<String> getBizIds() {
            return bizIds;
        }

        public void setBizIds(List<String> bizIds) {
            this.bizIds = bizIds == null ? new ArrayList<String>() : bizIds;
        }

        public String getDefaultProfile() {
            return defaultProfile;
        }

        public void setDefaultProfile(String defaultProfile) {
            this.defaultProfile = defaultProfile;
        }

        public Map<String, Provider> getProviders() {
            return providers;
        }

        public void setProviders(Map<String, Provider> providers) {
            this.providers = providers == null ? new LinkedHashMap<String, Provider>() : providers;
        }

        public Map<String, Profile> getProfiles() {
            return profiles;
        }

        public void setProfiles(Map<String, Profile> profiles) {
            this.profiles = profiles == null ? new LinkedHashMap<String, Profile>() : profiles;
        }
    }

    /**
     * 一个供应商端点。同一个供应商下可以挂多个模型。
     */
    public static final class Provider {

        private String api = "openai-completions";
        private String baseUrl;
        private String apiKey;
        /** apiKey 为空时从该环境变量读取。 */
        private String apiKeyEnv;
        /**
         * 多 key 池（限流熔断计划 §3.4）。元素支持与 api-key 相同的 {@code ${ENV}}/
         * {@code $ENV} 占位符；配置了 api-keys 时优先于 api-key/api-key-env。
         * 游标只在 429/401 失败标记时推进，key 值永不进日志/记录/指标。
         */
        private List<String> apiKeys = new ArrayList<String>();
        private Map<String, String> headers = new LinkedHashMap<String, String>();
        /** 合并进每个请求体的私有字段，例如 vLLM 的 chat_template_kwargs。 */
        private Map<String, Object> extraBody = new LinkedHashMap<String, Object>();
        private Integer connectTimeoutMs = Integer.valueOf(10000);
        private Integer readTimeoutMs = Integer.valueOf(120000);
        private List<Model> models = new ArrayList<Model>();
        /** 该供应商的出口保护（限流熔断计划 §3.0 第二个配置根）。 */
        private GuardCfg guard = new GuardCfg();

        public String getApi() {
            return api;
        }

        public void setApi(String api) {
            this.api = api;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiKeyEnv() {
            return apiKeyEnv;
        }

        public void setApiKeyEnv(String apiKeyEnv) {
            this.apiKeyEnv = apiKeyEnv;
        }

        public List<String> getApiKeys() {
            return apiKeys;
        }

        public void setApiKeys(List<String> apiKeys) {
            this.apiKeys = apiKeys == null ? new ArrayList<String>() : apiKeys;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<String, String>() : headers;
        }

        public Map<String, Object> getExtraBody() {
            return extraBody;
        }

        public void setExtraBody(Map<String, Object> extraBody) {
            this.extraBody = extraBody == null ? new LinkedHashMap<String, Object>() : extraBody;
        }

        public Integer getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(Integer connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public Integer getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(Integer readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public List<Model> getModels() {
            return models;
        }

        public void setModels(List<Model> models) {
            this.models = models == null ? new ArrayList<Model>() : models;
        }

        public GuardCfg getGuard() {
            return guard;
        }

        public void setGuard(GuardCfg guard) {
            this.guard = guard == null ? new GuardCfg() : guard;
        }

        /**
         * 供应商级出口保护。concurrency=0（默认）表示不限并发，未配置任何
         * guard 项的供应商不建 ProviderGuard 实例（NOOP 直通）。
         */
        public static final class GuardCfg {

            /** 该供应商的并发许可数（每次模型调用占一个）；0=不限。 */
            private int concurrency = 0;

            public int getConcurrency() {
                return concurrency;
            }

            public void setConcurrency(int concurrency) {
                this.concurrency = concurrency;
            }
        }
    }

    /**
     * 模型能力与请求差异。字段命名对齐 pi 的 model 元数据。
     */
    public static final class Model {

        private String id;
        private String name;
        private boolean reasoning = true;
        private List<String> input = new ArrayList<String>();
        private Integer contextWindow;
        private Integer maxTokens = Integer.valueOf(8192);
        /** 业务思考档到供应商档的映射；value 为 null 表示该档不支持。 */
        private Map<String, String> thinkingLevelMap = new LinkedHashMap<String, String>();
        /** none / chat-template / deepseek / reasoning-effort / anthropic-budget */
        private String thinkingFormat = "reasoning-effort";
        private Map<String, Object> samplerParams = new LinkedHashMap<String, Object>();
        private Map<String, Map<String, Object>> samplerParamsByThinkingLevel =
                new LinkedHashMap<String, Map<String, Object>>();
        private Map<String, String> headers = new LinkedHashMap<String, String>();
        private Map<String, Object> extraBody = new LinkedHashMap<String, Object>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isReasoning() {
            return reasoning;
        }

        public void setReasoning(boolean reasoning) {
            this.reasoning = reasoning;
        }

        public List<String> getInput() {
            return input;
        }

        public void setInput(List<String> input) {
            this.input = input == null ? new ArrayList<String>() : input;
        }

        public Integer getContextWindow() {
            return contextWindow;
        }

        public void setContextWindow(Integer contextWindow) {
            this.contextWindow = contextWindow;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Map<String, String> getThinkingLevelMap() {
            return thinkingLevelMap;
        }

        public void setThinkingLevelMap(Map<String, String> thinkingLevelMap) {
            this.thinkingLevelMap = thinkingLevelMap == null
                    ? new LinkedHashMap<String, String>() : thinkingLevelMap;
        }

        public String getThinkingFormat() {
            return thinkingFormat;
        }

        public void setThinkingFormat(String thinkingFormat) {
            this.thinkingFormat = thinkingFormat;
        }

        public Map<String, Object> getSamplerParams() {
            return samplerParams;
        }

        public void setSamplerParams(Map<String, Object> samplerParams) {
            this.samplerParams = samplerParams == null
                    ? new LinkedHashMap<String, Object>() : samplerParams;
        }

        public Map<String, Map<String, Object>> getSamplerParamsByThinkingLevel() {
            return samplerParamsByThinkingLevel;
        }

        public void setSamplerParamsByThinkingLevel(
                Map<String, Map<String, Object>> samplerParamsByThinkingLevel) {
            this.samplerParamsByThinkingLevel = samplerParamsByThinkingLevel == null
                    ? new LinkedHashMap<String, Map<String, Object>>() : samplerParamsByThinkingLevel;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<String, String>() : headers;
        }

        public Map<String, Object> getExtraBody() {
            return extraBody;
        }

        public void setExtraBody(Map<String, Object> extraBody) {
            this.extraBody = extraBody == null ? new LinkedHashMap<String, Object>() : extraBody;
        }
    }

    /**
     * 预置组合：业务只引用名字，不关心底层供应商。
     */
    public static final class Profile {

        private String provider;
        private String model;
        private String thinkingLevel = "off";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getThinkingLevel() {
            return thinkingLevel;
        }

        public void setThinkingLevel(String thinkingLevel) {
            this.thinkingLevel = thinkingLevel;
        }
    }

    /**
     * 异步任务与未来并行工具执行使用的线程池配置。
     */
    public static final class Executor {

        private String mode = "platform";
        private int coreSize = Math.max(2, Runtime.getRuntime().availableProcessors());
        private int maxSize = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        private int queueCapacity = 1000;
        private long keepAliveSeconds = 60L;
        private String threadNamePrefix = "ai-lite-";
        private String rejectionPolicy = "abort";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public long getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(long keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public String getRejectionPolicy() {
            return rejectionPolicy;
        }

        public void setRejectionPolicy(String rejectionPolicy) {
            this.rejectionPolicy = rejectionPolicy;
        }
    }

    /**
     * 模型 HTTP 传输配置。
     *
     * <p>只影响模型请求使用的 HTTP 客户端，与 {@link Executor} 里管工具、日志、
     * 会话的线程池相互独立。默认值保持 OkHttp 行为不变。</p>
     */
    public static final class Http {

        /** okhttp | okhttp-virtual | jetty | webclient | ktor-cio | jdk8。 */
        private String transport = "okhttp";
        private Dispatcher dispatcher = new Dispatcher();
        private Jetty jetty = new Jetty();
        private Ktor ktor = new Ktor();

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public Dispatcher getDispatcher() {
            return dispatcher;
        }

        public void setDispatcher(Dispatcher dispatcher) {
            this.dispatcher = dispatcher == null ? new Dispatcher() : dispatcher;
        }

        public Jetty getJetty() {
            return jetty;
        }

        public void setJetty(Jetty jetty) {
            this.jetty = jetty == null ? new Jetty() : jetty;
        }

        public Ktor getKtor() {
            return ktor;
        }

        public void setKtor(Ktor ktor) {
            this.ktor = ktor == null ? new Ktor() : ktor;
        }
    }

    /**
     * OkHttp Dispatcher 的并发上限。null 表示使用默认值 64/5。
     */
    public static final class Dispatcher {

        private Integer maxRequests;
        private Integer maxRequestsPerHost;

        public Integer getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(Integer maxRequests) {
            this.maxRequests = maxRequests;
        }

        public Integer getMaxRequestsPerHost() {
            return maxRequestsPerHost;
        }

        public void setMaxRequestsPerHost(Integer maxRequestsPerHost) {
            this.maxRequestsPerHost = maxRequestsPerHost;
        }
    }

    /**
     * Jetty 传输配置。null 表示使用 Jetty 默认值。
     */
    public static final class Jetty {

        private Integer maxConnectionsPerDestination;
        /** 0 或 null 表示不创建自定义线程池，使用 Jetty 默认。 */
        private Integer threads;

        public Integer getMaxConnectionsPerDestination() {
            return maxConnectionsPerDestination;
        }

        public void setMaxConnectionsPerDestination(Integer maxConnectionsPerDestination) {
            this.maxConnectionsPerDestination = maxConnectionsPerDestination;
        }

        public Integer getThreads() {
            return threads;
        }

        public void setThreads(Integer threads) {
            this.threads = threads;
        }
    }

    /**
     * Ktor CIO 传输配置。只有引入可选模块后才生效。
     */
    public static final class Ktor {

        private Integer maxConnections;
        private Integer maxConnectionsPerRoute;
        private Boolean pipelining;

        public Integer getMaxConnections() {
            return maxConnections;
        }

        public void setMaxConnections(Integer maxConnections) {
            this.maxConnections = maxConnections;
        }

        public Integer getMaxConnectionsPerRoute() {
            return maxConnectionsPerRoute;
        }

        public void setMaxConnectionsPerRoute(Integer maxConnectionsPerRoute) {
            this.maxConnectionsPerRoute = maxConnectionsPerRoute;
        }

        public Boolean getPipelining() {
            return pipelining;
        }

        public void setPipelining(Boolean pipelining) {
            this.pipelining = pipelining;
        }
    }

    /**
     * 会话默认存储配置。
     */
    public static final class Session {

        private boolean enabled = true;
        private int maxEntries = 10000;
        private long ttlSeconds = 604800L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    /**
     * 上下文治理配置（运行时能力计划）。
     *
     * <p>拆成 budget / compaction / tool-result 三组而不是一个总开关，
     * 是为了能表达"只要截断、不要压缩"这类组合。</p>
     *
     * <p><b>默认值必须自洽</b>：启动期会校验
     * {@code keepRecentTokens + summaryMaxTokens < contextWindow - reserveTokens}，
     * 不满足直接启动失败，不留到运行期变成"每轮压缩一次"的死循环。</p>
     */
    public static final class Context {

        /** 预算检测开关。关闭后不做任何 token 估算与超限判断。 */
        private boolean enabled = true;
        /** token 估算器实现标识，目前只有 chars-weighted。 */
        private String estimator = "chars-weighted";
        private Budget budget = new Budget();
        private Compaction compaction = new Compaction();
        private ToolResult toolResult = new ToolResult();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEstimator() {
            return estimator;
        }

        public void setEstimator(String estimator) {
            this.estimator = estimator;
        }

        public Budget getBudget() {
            return budget;
        }

        public void setBudget(Budget budget) {
            this.budget = budget == null ? new Budget() : budget;
        }

        public Compaction getCompaction() {
            return compaction;
        }

        public void setCompaction(Compaction compaction) {
            this.compaction = compaction == null ? new Compaction() : compaction;
        }

        public ToolResult getToolResult() {
            return toolResult;
        }

        public void setToolResult(ToolResult toolResult) {
            this.toolResult = toolResult == null ? new ToolResult() : toolResult;
        }

        /**
         * 预算参数。留给定模型回复的余量越大，触发线越低、压缩越早。
         */
        public static final class Budget {

            /**
             * 预留给模型回复的 token 数。触发线 = contextWindow - reserveTokens。
             * 窗口未知（null/0）或小于等于本值时视为"窗口未知"，跳过预算检查。
             */
            private int reserveTokens = 8192;

            public int getReserveTokens() {
                return reserveTokens;
            }

            public void setReserveTokens(int reserveTokens) {
                this.reserveTokens = reserveTokens;
            }
        }

        /**
         * 自动压缩参数。默认开启：上下文超限是必然故障，不是可选优化。
         */
        public static final class Compaction {

            private boolean enabled = true;
            /**
             * 压缩后保留的最近 token 数。必须满足
             * {@code keepRecentTokens + summaryMaxTokens < contextWindow - reserveTokens}。
             */
            private int keepRecentTokens = 12000;
            /** 摘要正文的目标 token 上限，作为摘要请求的 maxTokens。 */
            private int summaryMaxTokens = 2048;
            /** 摘要调用的供应商；留空表示沿用当前主调用的供应商。 */
            private String summaryProvider;
            /** 摘要调用的模型；留空表示沿用当前主调用的模型。 */
            private String summaryModel;
            /** 摘要调用独立超时，不绑定主 run 的取消。 */
            private long timeoutMs = 60000L;
            /** 单次 run 内压缩次数上限，防止压缩不收敛时无限循环。 */
            private int maxPerRun = 3;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getKeepRecentTokens() {
                return keepRecentTokens;
            }

            public void setKeepRecentTokens(int keepRecentTokens) {
                this.keepRecentTokens = keepRecentTokens;
            }

            public int getSummaryMaxTokens() {
                return summaryMaxTokens;
            }

            public void setSummaryMaxTokens(int summaryMaxTokens) {
                this.summaryMaxTokens = summaryMaxTokens;
            }

            public String getSummaryProvider() {
                return summaryProvider;
            }

            public void setSummaryProvider(String summaryProvider) {
                this.summaryProvider = summaryProvider;
            }

            public String getSummaryModel() {
                return summaryModel;
            }

            public void setSummaryModel(String summaryModel) {
                this.summaryModel = summaryModel;
            }

            public long getTimeoutMs() {
                return timeoutMs;
            }

            public void setTimeoutMs(long timeoutMs) {
                this.timeoutMs = timeoutMs;
            }

            public int getMaxPerRun() {
                return maxPerRun;
            }

            public void setMaxPerRun(int maxPerRun) {
                this.maxPerRun = maxPerRun;
            }
        }

        /**
         * 工具结果截断参数。截断发生在 afterTool 钩子之前，落盘的就是截断文本。
         */
        public static final class ToolResult {

            private boolean enabled = true;
            /** 单条工具结果保留的最大字符数。 */
            private int maxChars = 2000;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getMaxChars() {
                return maxChars;
            }

            public void setMaxChars(int maxChars) {
                this.maxChars = maxChars;
            }
        }
    }

    /**
     * 回调执行策略配置（回调策略计划 Phase 0）。
     *
     * <p>{@code mode=inline}（默认）保持现状——回调跑在触发线程上；
     * {@code mode=io} 时用户 onDelta 与 Agent 钩子经独立回调池上的 per-run
     * 串行执行器执行。只对异步入口生效。</p>
     */
    public static final class Callback {

        /** inline | io。 */
        private String mode = "inline";
        private int queueCapacity = 1024;
        private ExecutorCfg executor = new ExecutorCfg();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public ExecutorCfg getExecutor() {
            return executor;
        }

        public void setExecutor(ExecutorCfg executor) {
            this.executor = executor == null ? new ExecutorCfg() : executor;
        }

        /** 回调共享线程池配置（ai-lite-callback-*）。 */
        public static final class ExecutorCfg {

            private int coreSize = 2;
            private int maxSize = 8;
            private int queueCapacity = 2048;
            private String threadNamePrefix = "ai-lite-callback-";

            public int getCoreSize() {
                return coreSize;
            }

            public void setCoreSize(int coreSize) {
                this.coreSize = coreSize;
            }

            public int getMaxSize() {
                return maxSize;
            }

            public void setMaxSize(int maxSize) {
                this.maxSize = maxSize;
            }

            public int getQueueCapacity() {
                return queueCapacity;
            }

            public void setQueueCapacity(int queueCapacity) {
                this.queueCapacity = queueCapacity;
            }

            public String getThreadNamePrefix() {
                return threadNamePrefix;
            }

            public void setThreadNamePrefix(String threadNamePrefix) {
                this.threadNamePrefix = threadNamePrefix;
            }
        }
    }

    /**
     * 指标配置（可观测性计划）：LLM 调用指标经 Micrometer 暴露，复用
     * {@code LlmCallStore} 记录，无新增采集路径。micrometer 不在 classpath
     * 时自动无效果。
     */
    public static final class Metrics {

        private boolean llmEnabled = true;

        public boolean isLlmEnabled() {
            return llmEnabled;
        }

        public void setLlmEnabled(boolean llmEnabled) {
            this.llmEnabled = llmEnabled;
        }
    }

    /**
     * 出口保护配置（限流熔断计划 §3.0 第一个配置根）。
     *
     * <p>所有能力默认关闭：未给供应商配置 {@code guard.concurrency} 且熔断
     * {@code enabled=false} 时，网关不建 ProviderGuard，行为与现状零差异。
     * 供应商级并发上限在 {@code ai-lite.llm.providers.<id>.guard.concurrency}。</p>
     */
    public static final class Guard {

        /** 同步等待与异步排队共用的时长上限；小于等于 0 表示不等待。 */
        private long concurrencyWaitMs = 2000L;
        /** 异步排队容量，超出即 OVERLOAD 拒绝。 */
        private int asyncQueueCapacity = 100;
        private Circuit circuit = new Circuit();
        /** 429/401 失败标记后单把 key 的冷却时长。 */
        private long keyCooldownSeconds = 60L;

        public long getConcurrencyWaitMs() {
            return concurrencyWaitMs;
        }

        public void setConcurrencyWaitMs(long concurrencyWaitMs) {
            this.concurrencyWaitMs = concurrencyWaitMs;
        }

        public int getAsyncQueueCapacity() {
            return asyncQueueCapacity;
        }

        public void setAsyncQueueCapacity(int asyncQueueCapacity) {
            this.asyncQueueCapacity = asyncQueueCapacity;
        }

        public Circuit getCircuit() {
            return circuit;
        }

        public void setCircuit(Circuit circuit) {
            this.circuit = circuit == null ? new Circuit() : circuit;
        }

        public long getKeyCooldownSeconds() {
            return keyCooldownSeconds;
        }

        public void setKeyCooldownSeconds(long keyCooldownSeconds) {
            this.keyCooldownSeconds = keyCooldownSeconds;
        }

        /**
         * 熔断参数。状态机 CLOSED→OPEN→HALF_OPEN（单探测，带超时）；
         * 失败口径为 {@code RetryPolicy.defaultRetryable}，guard 自身拒绝不回灌窗口。
         */
        public static final class Circuit {

            private boolean enabled = false;
            /** 窗口失败率阈值（%）。 */
            private int failureRate = 50;
            /** 统计窗口秒数，内部按 6 片环形滑动（近似值）。 */
            private long windowSeconds = 60L;
            /** 触发门槛的最少样本数。 */
            private int minimumCalls = 20;
            /** OPEN 冷却秒数，期满进 HALF_OPEN。 */
            private long openSeconds = 30L;
            /** 半开探测超时毫秒，超时按探测失败回 OPEN。 */
            private long probeTimeoutMs = 30000L;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getFailureRate() {
                return failureRate;
            }

            public void setFailureRate(int failureRate) {
                this.failureRate = failureRate;
            }

            public long getWindowSeconds() {
                return windowSeconds;
            }

            public void setWindowSeconds(long windowSeconds) {
                this.windowSeconds = windowSeconds;
            }

            public int getMinimumCalls() {
                return minimumCalls;
            }

            public void setMinimumCalls(int minimumCalls) {
                this.minimumCalls = minimumCalls;
            }

            public long getOpenSeconds() {
                return openSeconds;
            }

            public void setOpenSeconds(long openSeconds) {
                this.openSeconds = openSeconds;
            }

            public long getProbeTimeoutMs() {
                return probeTimeoutMs;
            }

            public void setProbeTimeoutMs(long probeTimeoutMs) {
                this.probeTimeoutMs = probeTimeoutMs;
            }
        }
    }
}
