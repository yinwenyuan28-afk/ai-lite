package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.guard.ProviderGuard;
import io.github.yinwenyuan28.ailite.internal.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.internal.llm.AsyncLlmCallLogger;
import io.github.yinwenyuan28.ailite.internal.llm.RetryScheduler;
import io.github.yinwenyuan28.ailite.internal.llm.RetrySupport;
import io.github.yinwenyuan28.ailite.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import io.github.yinwenyuan28.ailite.internal.llm.SequentialToolExecutor;

/**
 * 模型调用入口。业务只和这个类打交道。
 *
 * <p>单次调用走 {@code one} / {@code oneByStream}，简单工具循环走
 * {@code toolLoop}，只发一轮并带工具声明走 {@code round}。</p>
 */
public final class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    public static final int DEFAULT_MAX_ROUNDS = 8;

    private static final int MODE_ONE = 0;
    private static final int MODE_LOOP = 1;
    private static final int MODE_ROUND = 2;

    private final ModelResolver resolver;
    private final Map<String, LlmTransport> transports;
    private final AsyncLlmCallLogger logger;
    private final ExecutorProvider executor;
    private final ToolExecutor toolExecutor;
    private final ScheduledExecutorService retryScheduler;
    /** 回调执行策略（回调策略 Phase 2）；默认 inline，Starter 按配置注入。 */
    private volatile CallbackPolicy callbackPolicy = CallbackPolicy.inline();
    /**
     * 出口保护（限流熔断计划 §3.7）：providerId → guard。默认空 Map = NOOP 直通，
     * 未配置 guard 的 provider 连 acquire 调用都不发生。
     */
    private volatile Map<String, ProviderGuard> guards = Collections.emptyMap();

    /**
     * 手工构造入口。异步重试使用进程级共享调度器，避免调用方额外管理生命周期。
     *
     * @param resolver    配置解析器
     * @param transports  协议适配器列表，按 {@link LlmTransport#api()} 注册
     * @param store       调用记录存储
     * @param executor    异步执行器
     * @param toolExecutor 工具执行器
     */
    public LlmGateway(ModelResolver resolver, List<LlmTransport> transports,
                      LlmCallStore store, ExecutorProvider executor, ToolExecutor toolExecutor) {
        this(resolver, transports, store, executor, toolExecutor, RetryScheduler.shared());
    }

    /**
     * @param resolver       配置解析器
     * @param transports     协议适配器列表，按 {@link LlmTransport#api()} 注册
     * @param store          调用记录存储
     * @param executor       异步执行器
     * @param toolExecutor   工具执行器
     * @param retryScheduler 异步重试调度器；Starter 注入，手工构造可传共享实例
     */
    public LlmGateway(ModelResolver resolver, List<LlmTransport> transports,
                      LlmCallStore store, ExecutorProvider executor, ToolExecutor toolExecutor,
                      ScheduledExecutorService retryScheduler) {
        if (resolver == null) {
            throw new LlmCallException("ModelResolver 不能为空");
        }
        this.resolver = resolver;
        this.transports = new HashMap<String, LlmTransport>();
        if (transports != null) {
            for (LlmTransport transport : transports) {
                if (transport == null || Strings.isBlank(transport.api())) {
                    continue;
                }
                if (this.transports.put(transport.api(), transport) != null) {
                    throw new LlmCallException("协议适配器重复: " + transport.api());
                }
            }
        }
        this.executor = executor == null ? new DirectExecutorProvider() : executor;
        this.logger = new AsyncLlmCallLogger(this.executor, store);
        this.toolExecutor = toolExecutor == null ? new SequentialToolExecutor() : toolExecutor;
        this.retryScheduler = retryScheduler == null ? RetryScheduler.shared() : retryScheduler;
    }

    /**
     * @return 网关使用的 I/O 执行器，供 AgentLoop / AgentSessionLoop 复用，避免落到公共池
     */
    public ExecutorProvider getExecutor() {
        return executor;
    }

    /**
     * @return 工具执行器，供 AgentLoop 复用
     */
    public ToolExecutor getToolExecutor() {
        return toolExecutor;
    }

    /**
     * 问一次，不带工具。
     *
     * @param call 调用参数
     * @return 调用结果
     */
    public LlmResult one(LlmCall call) {
        return invoke(call, null, null, MODE_ONE, false, null, null);
    }

    /**
     * 问一次，并允许另一个线程中止在途请求。
     *
     * <p>取消会触发传输层绑定的 abort：OkHttp 传输下在途请求立即中断；JDK 8 传输
     * 在正文读取阶段可能等到读超时。无论传输是否响应 abort，网关都会在返回前把
     * 终态改写为 {@code ABORTED}。不传句柄时只能等超时。</p>
     *
     * @param call   调用参数
     * @param cancel 取消句柄，可为 null
     * @return 调用结果；取消时为 {@code ABORTED}
     */
    public LlmResult one(LlmCall call, LlmCancelHandle cancel) {
        return invoke(call, null, null, MODE_ONE, false, null, cancel);
    }

    /**
     * 问一次，并按策略重试瞬时错误。
     *
     * <p>{@link #one(LlmCall, LlmCancelHandle)} 仍然只尝试一次；本方法在它外面循环。
     * 每次尝试都是一次完整的单次调用，独立校验、独立写调用记录。只重试
     * {@link RetryPolicy#defaultRetryable} 认定的瞬时错误；取消和不可重试错误立即返回/抛出。</p>
     *
     * @param call   调用参数
     * @param policy 重试策略
     * @return 调用结果
     */
    public LlmResult oneWithRetry(LlmCall call, RetryPolicy policy) {
        return oneWithRetry(call, policy, null);
    }

    /**
     * 问一次，并按策略重试瞬时错误，同时支持外部取消。
     *
     * <p>同一个取消句柄贯穿所有尝试；退避等待可被取消打断。取消后不再发起新的
     * HTTP 请求，返回规范的 {@code ABORTED} 结果。</p>
     *
     * @param call   调用参数
     * @param policy 重试策略
     * @param cancel 取消句柄，可为 null
     * @return 调用结果
     */
    public LlmResult oneWithRetry(LlmCall call, RetryPolicy policy, LlmCancelHandle cancel) {
        if (policy == null) {
            throw new LlmCallException("重试策略不能为空", null, LlmCallException.Kind.CONFIG);
        }
        long startNanos = System.nanoTime();
        Long deadlineNanos = RetrySupport.deadlineNanos(startNanos, policy.getTotalDeadlineMs());
        List<LlmCallException> failures = new ArrayList<LlmCallException>();
        for (int attempt = 1; attempt <= policy.getMaxAttempts(); attempt++) {
            if (cancel != null && cancel.isCancelled()) {
                return one(call, cancel);
            }
            if (RetrySupport.deadlineReached(deadlineNanos, System.nanoTime())) {
                throw RetrySupport.deadlineFailure(failures);
            }
            try {
                return one(call, cancel);
            } catch (LlmCallException ex) {
                failures.add(ex);
                if (!policy.shouldRetry(ex) || attempt >= policy.getMaxAttempts()) {
                    throw RetrySupport.withSuppressed(ex, failures);
                }
                if (cancel != null && cancel.isCancelled()) {
                    return one(call, cancel);
                }
                // 退避优先采用服务端 Retry-After（限流熔断 §3.3），与异步口径一致。
                long delay = policy.delayMs(attempt);
                if (ex.getRetryAfterMs() != null) {
                    delay = Math.min(ex.getRetryAfterMs().longValue(), policy.getMaxBackoffMs());
                }
                if (deadlineNanos != null) {
                    long now = System.nanoTime();
                    if (RetrySupport.deadlineReached(deadlineNanos, now)) {
                        throw RetrySupport.deadlineFailure(failures);
                    }
                    delay = RetrySupport.clampDelayMs(delay, deadlineNanos, now);
                }
                policy.notifyRetry(attempt, delay, ex);
                if (!sleepCancellable(delay, cancel)) {
                    return one(call, cancel);
                }
            }
        }
        throw RetrySupport.deadlineFailure(failures);
    }

    /**
     * 分片睡眠，保证取消或总时限不会等到整段退避结束。
     *
     * @return true 表示正常睡完；false 表示被取消
     */
    private static boolean sleepCancellable(long delayMs, LlmCancelHandle cancel) {
        long end = System.currentTimeMillis() + Math.max(0L, delayMs);
        while (true) {
            if (cancel != null && cancel.isCancelled()) {
                return false;
            }
            long remaining = end - System.currentTimeMillis();
            if (remaining <= 0L) {
                return true;
            }
            try {
                Thread.sleep(Math.min(remaining, 50L));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new LlmCallException("重试等待被中断", ex,
                        LlmCallException.Kind.TRANSPORT);
            }
        }
    }

    /**
     * 问一次，边生成边回调。
     *
     * @param call    调用参数
     * @param onDelta 增量回调，可为 null
     * @param cancel  取消句柄，可为 null
     * @return 调用结果
     */
    public LlmResult oneByStream(LlmCall call, Consumer<String> onDelta, LlmCancelHandle cancel) {
        return invoke(call, null, null, MODE_ONE, true, onDelta, cancel);
    }

    /**
     * 模型要工具就执行，再请求下一轮。
     *
     * @param call      调用参数
     * @param tools     工具列表
     * @param maxRounds 最大轮数，null 表示 8
     * @return 最后一轮结果
     */
    public LlmResult toolLoop(LlmCall call, List<LlmTool> tools, Integer maxRounds) {
        return toolLoop(call, tools, maxRounds, null);
    }

    /**
     * 模型要工具就执行，再请求下一轮，并允许其他线程中止。
     *
     * <p>使用 4 参重载而不是 3 参 {@code (call, tools, cancel)}：后者会和
     * {@code (call, tools, Integer)} 在传 null 时产生二义性。</p>
     *
     * @param call      调用参数
     * @param tools     工具列表
     * @param maxRounds 最大轮数，null 表示 8
     * @param cancel    取消句柄，可为 null
     * @return 最后一轮结果
     */
    public LlmResult toolLoop(LlmCall call, List<LlmTool> tools, Integer maxRounds,
                              LlmCancelHandle cancel) {
        return invoke(call, tools, maxRounds, MODE_LOOP, false, null, cancel);
    }

    public LlmResult toolLoop(LlmCall call, List<LlmTool> tools) {
        return toolLoop(call, tools, null);
    }

    public LlmResult toolLoopByStream(LlmCall call, List<LlmTool> tools, Integer maxRounds,
                                      Consumer<String> onDelta, LlmCancelHandle cancel) {
        return invoke(call, tools, maxRounds, MODE_LOOP, true, onDelta, cancel);
    }

    /**
     * 解析本次调用将使用的模型元数据，供编排层做预算判断。
     *
     * <p>与真正发请求的区别：只解析、不发请求、不占 guard 许可、不写调用记录。
     * 编排层需要 {@code contextWindow} 判断是否要压缩，但又不应该为了读一个配置
     * 去发一次模型调用。</p>
     *
     * @param call 调用参数
     * @return 解析后的模型元数据
     * @throws LlmCallException 配置缺失或 profile 不存在
     */
    public ResolvedModel resolveFor(LlmCall call) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        return resolve(call);
    }

    /**
     * 只发一轮并带上工具声明，但不执行工具。
     *
     * @param call  调用参数
     * @param tools 工具列表
     * @return 模型返回，可能带 toolCalls
     */
    public LlmResult round(LlmCall call, List<LlmTool> tools) {
        return invoke(call, tools, null, MODE_ROUND, false, null, null);
    }

    /**
     * 只发一轮并带上工具声明，但不执行工具，并允许其他线程中止。
     *
     * <p><b>不要再加 {@code round(call, tools, Integer)} 重载</b>：那样
     * {@code round(call, tools, null)} 会产生二义性。最大轮数只属于 toolLoop。</p>
     *
     * @param call   调用参数
     * @param tools  工具列表
     * @param cancel 取消句柄，可为 null
     * @return 模型返回，可能带 toolCalls
     */
    public LlmResult round(LlmCall call, List<LlmTool> tools, LlmCancelHandle cancel) {
        return invoke(call, tools, null, MODE_ROUND, false, null, cancel);
    }

    /**
     * 只发一轮并带工具声明，同步流式产出增量，并区分推理与正文。
     *
     * <p><b>线程契约</b>：增量回调恒在调用方线程上执行（同步传输直接读流）。</p>
     *
     * @param call     调用参数
     * @param tools    工具列表，可为 null
     * @param sink     类型化增量通道，非 null
     * @param cancel   取消句柄，可为 null
     * @return 模型返回
     */
    public LlmResult roundByStream(LlmCall call, List<LlmTool> tools, LlmDeltaSink sink,
                                   LlmCancelHandle cancel) {
        if (sink == null) {
            throw new LlmCallException("增量通道不能为空");
        }
        return invoke(call, tools, null, MODE_ROUND, true, null, cancel, sink);
    }

    /**
     * 非阻塞问一次。等待模型响应时不占调用线程，适合 JDK 8 下高并发接入。
     *
     * @param call 调用参数
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> oneAsync(LlmCall call) {
        return invokeAsync(call, null, null, MODE_ONE, false, null, null);
    }

    /**
     * @return 重试/超时共用的调度器；编排层（如 AgentLoop 的整轮超时）挂一次性任务用。
     * Starter 注入自定义 aiLiteRetryScheduler bean 时这里返回的就是该实例。
     */
    public ScheduledExecutorService retryScheduler() {
        return retryScheduler;
    }

    /**
     * 设置回调执行策略（回调策略 Phase 2）：io 模式下流式 onDelta 经独立回调池上的
     * per-call 串行执行器执行并带完成屏障。启动阶段设置一次；默认 inline 行为不变。
     *
     * @param policy 回调策略，null 按 inline 处理
     */
    public void setCallbackPolicy(CallbackPolicy policy) {
        this.callbackPolicy = policy == null ? CallbackPolicy.inline() : policy;
    }

    /**
     * 设置出口保护 guard 表（限流熔断计划）。启动阶段设置一次；null/空 = 直通。
     * 只应包含配置了 guard 的 provider，其余 provider 不建实例。
     *
     * @param guards providerId → ProviderGuard
     */
    public void setGuards(Map<String, ProviderGuard> guards) {
        this.guards = guards == null
                ? Collections.<String, ProviderGuard>emptyMap()
                : Collections.unmodifiableMap(new HashMap<String, ProviderGuard>(guards));
    }

    /** 按 provider 取 guard；未配置返回 null（调用方直接跳过 acquire/release）。 */
    private ProviderGuard guardFor(ResolvedModel model) {
        if (guards.isEmpty()) {
            return null;
        }
        return guards.get(model.getProvider());
    }

    /**
     * 非阻塞问一次，并允许外部取消底层请求。
     *
     * @param call   调用参数
     * @param cancel 取消句柄
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> oneAsync(LlmCall call, LlmCancelHandle cancel) {
        return invokeAsync(call, null, null, MODE_ONE, false, null, cancel);
    }

    /**
     * 非阻塞问一次，并按策略重试瞬时错误。
     *
     * <p>与同步 {@link #oneWithRetry(LlmCall, RetryPolicy)} 语义一致：每次尝试
     * 独立校验、独立记录，只重试 {@link RetryPolicy} 认定的瞬时错误。区别在于
     * 退避不占用调用线程，也不占用 HTTP 客户端线程：完成回调只做 O(1) 判断，
     * 然后投递到 {@code ai-lite-retry-*} 调度器。</p>
     *
     * @param call   调用参数
     * @param policy 重试策略
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> oneWithRetryAsync(LlmCall call, RetryPolicy policy) {
        return oneWithRetryAsync(call, policy, null);
    }

    /**
     * 非阻塞问一次，并按策略重试瞬时错误，同时支持外部取消。
     *
     * <p>取消契约与 {@link #oneAsync(LlmCall, LlmCancelHandle)} 一致：句柄取消后
     * 不再发起新尝试，在途尝试由传输层协作中断，最终 future 正常完成一个
     * {@link LlmStopReason#ABORTED} 结果；{@link CompletableFuture#cancel(boolean)}
     * 保持 JDK cancelled 语义。参数/配置错误在返回 future 前同步抛出。</p>
     *
     * @param call   调用参数
     * @param policy 重试策略
     * @param cancel 取消句柄，可为 null
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> oneWithRetryAsync(LlmCall call, RetryPolicy policy,
                                                          LlmCancelHandle cancel) {
        if (policy == null) {
            throw new LlmCallException("重试策略不能为空", null, LlmCallException.Kind.CONFIG);
        }
        if (cancel != null && cancel.isCancelled()) {
            // 预取消走 oneAsync 的既有路径：零 HTTP，但保留一条 aborted 记录。
            return oneAsync(call, cancel);
        }
        RetryRun run = new RetryRun(call, policy, cancel);
        run.start();
        return run.result;
    }

    /**
     * 非阻塞流式问一次。
     *
     * @param call    调用参数
     * @param onDelta 增量回调
     * @param cancel  取消句柄
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> oneByStreamAsync(LlmCall call, Consumer<String> onDelta,
                                                         LlmCancelHandle cancel) {
        return invokeAsync(call, null, null, MODE_ONE, true, onDelta, cancel);
    }

    /**
     * 非阻塞的单轮工具声明。
     *
     * @param call  调用参数
     * @param tools 工具列表
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> roundAsync(LlmCall call, List<LlmTool> tools) {
        return invokeAsync(call, tools, null, MODE_ROUND, false, null, null);
    }

    /**
     * 非阻塞的单轮工具声明，并允许外部取消。
     *
     * @param call   调用参数
     * @param tools  工具列表
     * @param cancel 取消句柄
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> roundAsync(LlmCall call, List<LlmTool> tools,
                                                   LlmCancelHandle cancel) {
        return invokeAsync(call, tools, null, MODE_ROUND, false, null, cancel);
    }

    /**
     * 非阻塞工具循环。
     *
     * @param call      调用参数
     * @param tools     工具列表
     * @param maxRounds 最大轮数
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> toolLoopAsync(LlmCall call, List<LlmTool> tools,
                                                      Integer maxRounds) {
        return invokeAsync(call, tools, maxRounds, MODE_LOOP, false, null, null);
    }

    public CompletableFuture<LlmResult> toolLoopAsync(LlmCall call, List<LlmTool> tools) {
        return toolLoopAsync(call, tools, null);
    }

    /**
     * 非阻塞工具循环，并允许外部取消。
     *
     * @param call      调用参数
     * @param tools     工具列表
     * @param maxRounds 最大轮数
     * @param cancel    取消句柄
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> toolLoopAsync(LlmCall call, List<LlmTool> tools,
                                                      Integer maxRounds, LlmCancelHandle cancel) {
        return invokeAsync(call, tools, maxRounds, MODE_LOOP, false, null, cancel);
    }

    /**
     * 非阻塞流式工具循环。
     *
     * @param call      调用参数
     * @param tools     工具列表
     * @param maxRounds 最大轮数
     * @param onDelta   增量回调
     * @param cancel    取消句柄
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> toolLoopByStreamAsync(LlmCall call, List<LlmTool> tools,
                                                              Integer maxRounds,
                                                              Consumer<String> onDelta,
                                                              LlmCancelHandle cancel) {
        return invokeAsync(call, tools, maxRounds, MODE_LOOP, true, onDelta, cancel);
    }

    private CompletableFuture<LlmResult> invokeAsync(LlmCall call, List<LlmTool> tools,
                                                     Integer maxRounds, int mode, boolean stream,
                                                     Consumer<String> onDelta,
                                                     LlmCancelHandle cancel) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }        String bizId = Strings.trimToNull(call.getBizId());
        if (bizId == null) {
            throw new LlmCallException("业务 id 不能为空");
        }
        if (!resolver.isConfigured()) {
            throw new LlmCallException("ai.llm 未配置");
        }
        if (!resolver.allows(bizId)) {
            throw new LlmCallException("业务 id 未登记: " + bizId);
        }
        ResolvedModel model = resolve(call);
        if (!transports.containsKey(model.getApi())) {
            throw new LlmCallException("不支持的协议: " + model.getApi());
        }
        List<LlmMessage> transcript = new ArrayList<LlmMessage>(LlmTexts.fold(call.getMessages()));
        LlmTexts.validate(transcript, model.isAcceptsImage());
        boolean declareTools = mode != MODE_ONE;
        boolean executeTools = mode == MODE_LOOP;
        if (mode == MODE_LOOP) {
            validateTools(tools);
        } else if (mode == MODE_ROUND && tools != null && !tools.isEmpty()) {
            validateTools(tools);
        } else if (mode == MODE_ROUND) {
            declareTools = false;
        }
        int limit = maxRounds == null ? DEFAULT_MAX_ROUNDS : maxRounds.intValue();
        if (executeTools && limit <= 0) {
            throw new LlmCallException("最大轮数必须大于 0");
        }
        // 即使调用方没有显式传取消句柄，也要让 future.cancel 能中断底层 HTTP。
        final LlmCancelHandle effectiveCancel = cancel == null ? new LlmCancelHandle() : cancel;
        // 回调策略 Phase 2：mode=io 且流式时，per-call 串行执行器承接 onDelta 与完成屏障。
        final SerialCallbackExecutor callbackBarrier =
                (stream && onDelta != null && callbackPolicy.isIo())
                        ? callbackPolicy.newSerialExecutor() : null;
        AsyncRun run = new AsyncRun(call, bizId, model, transcript, tools, declareTools,
                executeTools, limit, stream, onDelta, effectiveCancel, callbackBarrier);
        // AL-H05：非合作 transport 不 bindAbort 时 cancel 无法唤醒挂起的 future；
        // 取消监听器在取消方线程主动收敛。句柄是 one-shot 生命周期，无需显式解绑。
        effectiveCancel.onCancel(new Runnable() {
            @Override
            public void run() {
                run.cancelledByListener();
            }
        });
        run.result.whenComplete((value, error) -> {
            if (run.result.isCancelled()) {
                // future.cancel(true) 不是终态：底层要 abort，调用记录也要留下一条 aborted。
                effectiveCancel.cancel();
                run.cancelledByFuture();
            }
        });
        run.next();
        return run.result;
    }

    /**
     * 一次“带重试的异步调用”的状态机。
     *
     * <p>线程纪律：HTTP 客户端线程只负责判断和投递调度任务，绝不在 I/O 线程
     * 上睡眠或递归发起下一次尝试；退避结束后的发起动作在
     * {@code ai-lite-retry-*} 线程上执行。</p>
     *
     * <p>并发纪律：{@link #terminal} 只作为终态裁判，完成回调入口只读；
     * 成功、不可重试、次数耗尽、deadline、取消这些收敛动作才 CAS 占有它。
     * 其余可变状态统一由 {@code synchronized (this)} 保护。</p>
     */
    private final class RetryRun {

        private final LlmCall call;
        private final RetryPolicy policy;
        private final LlmCancelHandle outerCancel;
        private final CompletableFuture<LlmResult> result = new CompletableFuture<LlmResult>();
        private final Long deadlineNanos;
        private final List<LlmCallException> failures = new ArrayList<LlmCallException>();
        private final AtomicBoolean terminal = new AtomicBoolean(false);

        // 以下字段只在 synchronized (this) 内读写。
        private int attemptsStarted;
        private CompletableFuture<LlmResult> currentAttemptFuture;
        private LlmCancelHandle currentAttemptCancel;
        private ScheduledFuture<?> scheduledRetry;
        private boolean cancelRequested;

        private RetryRun(LlmCall call, RetryPolicy policy, LlmCancelHandle outerCancel) {
            this.call = call;
            this.policy = policy;
            this.outerCancel = outerCancel;
            this.deadlineNanos = RetrySupport.deadlineNanos(
                    System.nanoTime(), policy.getTotalDeadlineMs());
        }

        /**
         * 绑定外层取消、注册 future 取消回调，然后发起第一次尝试。
         * 第一次尝试的校验异常按 {@code oneAsync} 契约同步抛出。
         */
        private void start() {
            if (outerCancel != null) {
                // 只绑定一次；每次尝试用自己的新句柄，避免覆盖这个代理。
                outerCancel.bindAbort(this::cancelFromOuter);
            }
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) {
                    // CompletableFuture 没有 onCancel 回调，只能在这里收口。
                    abandonFromFuture();
                }
            });
            try {
                startAttempt();
            } catch (RuntimeException ex) {
                unbindOuter();
                throw ex;
            } catch (Error ex) {
                unbindOuter();
                throw ex;
            }
        }

        /**
         * 发起一次尝试。首轮在调用线程执行；后续轮次只从调度线程调用。
         */
        private void startAttempt() {
            LlmCallException deadlineFailure = null;
            synchronized (this) {
                if (terminal.get() || cancelRequested || result.isCancelled()) {
                    return;
                }
                if (RetrySupport.deadlineReached(deadlineNanos, System.nanoTime())) {
                    if (claimTerminalLocked()) {
                        deadlineFailure = RetrySupport.deadlineFailure(failures);
                    }
                } else {
                    attemptsStarted++;
                    LlmCancelHandle attemptCancel = new LlmCancelHandle();
                    currentAttemptCancel = attemptCancel;
                    // 先赋值再注册回调：oneAsync 可能同步完成，回调不能看到旧引用。
                    CompletableFuture<LlmResult> attemptFuture = oneAsync(call, attemptCancel);
                    currentAttemptFuture = attemptFuture;
                    attemptFuture.whenComplete(this::onAttemptComplete);
                }
            }
            if (deadlineFailure != null) {
                result.completeExceptionally(deadlineFailure);
            }
        }

        /**
         * 单次尝试完成。这里可能运行在 OkHttp/Jetty/Netty/Ktor 的回调线程上，
         * 因此只做判断和投递，不睡眠、不发起同步请求。
         */
        private void onAttemptComplete(LlmResult value, Throwable error) {
            LlmResult valueOut = null;
            LlmCallException failureOut = null;
            long retryDelayMs = -1L;
            LlmCallException retryCause = null;
            int retryFailedAttempt = -1;
            boolean abortedOut = false;

            synchronized (this) {
                currentAttemptFuture = null;
                currentAttemptCancel = null;

                // 终态已由别的线程收敛；完成回调只读，不能反过来占有 terminal。
                if (terminal.get() || result.isCancelled()) {
                    return;
                }
                if (cancelRequested) {
                    // 取消优先于正常完成/失败；oneAsync 会自己留下 aborted 记录。
                    abortedOut = claimTerminalLocked();
                } else if (error == null && value != null
                        && value.getStopReason() == LlmStopReason.ABORTED) {
                    if (claimTerminalLocked()) {
                        valueOut = value;
                    }
                } else {
                    Throwable cause = error == null ? null : unwrap(error);
                    boolean retryable = false;
                    LlmCallException failure;
                    if (cause instanceof LlmCallException) {
                        failure = (LlmCallException) cause;
                        retryable = policy.shouldRetry(failure);
                    } else if (cause != null) {
                        // SPI 违约或未知异常：包装后按不可重试处理，避免永久挂起。
                        failure = new LlmCallException("模型调用失败", cause);
                    } else if (value == null) {
                        failure = new LlmCallException("协议适配器返回了空结果");
                    } else {
                        failure = null;
                    }

                    if (failure == null) {
                        if (claimTerminalLocked()) {
                            valueOut = value;
                        }
                    } else {
                        failures.add(failure);
                        if (!retryable || attemptsStarted >= policy.getMaxAttempts()) {
                            if (claimTerminalLocked()) {
                                failureOut = RetrySupport.withSuppressed(failure, failures);
                            }
                        } else {
                            long now = System.nanoTime();
                            if (RetrySupport.deadlineReached(deadlineNanos, now)) {
                                if (claimTerminalLocked()) {
                                    failureOut = RetrySupport.deadlineFailure(failures);
                                }
                            } else {
                                // 退避优先采用服务端 Retry-After（限流熔断 §3.3）：
                                // 头值有效时 min(retryAfterMs, maxBackoffMs)，再被
                                // deadline 收口；无头值保持指数退避。
                                long backoff = policy.delayMs(attemptsStarted);
                                if (failure.getRetryAfterMs() != null) {
                                    backoff = Math.min(failure.getRetryAfterMs().longValue(),
                                            policy.getMaxBackoffMs());
                                }
                                // delay 只算一次，监听器与真实调度使用同一个值。
                                retryDelayMs = RetrySupport.clampDelayMs(
                                        backoff, deadlineNanos, now);
                                retryCause = failure;
                                retryFailedAttempt = attemptsStarted;
                            }
                        }
                    }
                }
            }

            if (abortedOut) {
                result.complete(abortedResult());
            } else if (valueOut != null) {
                result.complete(valueOut);
            } else if (failureOut != null) {
                result.completeExceptionally(failureOut);
            } else if (retryDelayMs >= 0L) {
                scheduleRetry(retryDelayMs, retryCause, retryFailedAttempt);
            }
        }

        /**
         * 把下一次尝试投递到专用调度器。零延迟也必须走这里，避免同步完成后
         * 形成深递归。
         */
        private void scheduleRetry(final long delayMs, final LlmCallException failure,
                                   final int failedAttempt) {
            ScheduledFuture<?> scheduled;
            try {
                scheduled = retryScheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                        runScheduledAttempt(delayMs, failure, failedAttempt);
                    }
                }, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ex) {
                convergeFailure(new LlmCallException("重试调度被拒绝", ex,
                        LlmCallException.Kind.TRANSPORT));
                return;
            } catch (RuntimeException ex) {
                convergeFailure(new LlmCallException("重试调度失败", ex,
                        LlmCallException.Kind.TRANSPORT));
                return;
            }

            boolean cancelNow = false;
            synchronized (this) {
                if (terminal.get() || cancelRequested || result.isCancelled()) {
                    cancelNow = true;
                } else {
                    scheduledRetry = scheduled;
                }
            }
            if (cancelNow) {
                scheduled.cancel(false);
            }
        }

        /**
         * 调度线程入口：检查状态、通知监听器、发起下一次尝试。
         */
        private void runScheduledAttempt(long delayMs, LlmCallException failedError,
                                         int failedAttempt) {
            LlmCallException failureOut = null;
            synchronized (this) {
                scheduledRetry = null;
                if (terminal.get() || cancelRequested || result.isCancelled()) {
                    return;
                }
                if (RetrySupport.deadlineReached(deadlineNanos, System.nanoTime())) {
                    if (claimTerminalLocked()) {
                        failureOut = RetrySupport.deadlineFailure(failures);
                    }
                }
            }
            if (failureOut != null) {
                result.completeExceptionally(failureOut);
                return;
            }

            try {
                policy.notifyRetry(failedAttempt, delayMs, failedError);
            } catch (Throwable listenerFailure) {
                // 监听器失败不能改变重试行为，也不能杀死调度 worker。
                log.warn("重试监听器异常，继续重试: {}", listenerFailure.toString());
            }

            try {
                startAttempt();
            } catch (Throwable ex) {
                convergeFailure(wrapFailure(ex));
            }
        }

        /**
         * 外层句柄取消。取消优先于在途结果，最终 future 正常完成 ABORTED。
         */
        private void cancelFromOuter() {
            LlmCancelHandle attemptCancel;
            ScheduledFuture<?> scheduled;
            boolean completeAborted = false;

            synchronized (this) {
                if (terminal.get() || cancelRequested) {
                    return;
                }
                cancelRequested = true;
                scheduled = scheduledRetry;
                scheduledRetry = null;
                attemptCancel = currentAttemptCancel;
                boolean inFlight = currentAttemptFuture != null;
                currentAttemptFuture = null;
                currentAttemptCancel = null;
                if (!inFlight && claimTerminalLocked()) {
                    completeAborted = true;
                }
            }

            if (scheduled != null) {
                scheduled.cancel(false);
            }
            if (attemptCancel != null) {
                // 不要 cancel(true) 在途 future：oneAsync 需要自己的取消路径来写记录。
                attemptCancel.cancel();
            }
            if (completeAborted) {
                result.complete(abortedResult());
            }
        }

        /**
         * future.cancel(true)：JDK 取消语义，只清理，不再 complete 结果。
         */
        private void abandonFromFuture() {
            LlmCancelHandle attemptCancel;
            ScheduledFuture<?> scheduled;

            synchronized (this) {
                cancelRequested = true;
                terminal.set(true);
                scheduled = scheduledRetry;
                scheduledRetry = null;
                attemptCancel = currentAttemptCancel;
                currentAttemptFuture = null;
                currentAttemptCancel = null;
                unbindOuter();
            }

            if (scheduled != null) {
                scheduled.cancel(false);
            }
            if (attemptCancel != null) {
                attemptCancel.cancel();
            }
        }

        /**
         * 非取消路径的失败收敛入口，用于调度器拒绝、后续尝试同步抛异常等。
         */
        private void convergeFailure(LlmCallException failure) {
            boolean won;
            synchronized (this) {
                won = claimTerminalLocked();
            }
            if (won) {
                result.completeExceptionally(failure);
            }
        }

        /**
         * 在锁内占有终态并释放引用。调用方负责在锁外完成 future，
         * 避免用户回调在状态锁内执行。
         */
        private boolean claimTerminalLocked() {
            if (!terminal.compareAndSet(false, true)) {
                return false;
            }
            if (scheduledRetry != null) {
                scheduledRetry.cancel(false);
                scheduledRetry = null;
            }
            currentAttemptFuture = null;
            currentAttemptCancel = null;
            unbindOuter();
            return true;
        }

        private void unbindOuter() {
            if (outerCancel != null) {
                outerCancel.bindAbort(null);
            }
        }

        /**
         * 取消时使用的规范结果，字段与 {@code oneAsync} 的 ABORTED 结果一致。
         */
        private LlmResult abortedResult() {
            ResolvedModel model = null;
            try {
                model = resolve(call);
            } catch (RuntimeException ignored) {
                // 配置在这一刻失效也不能让取消路径挂起；退化为空模型信息。
            }
            return new LlmResult("", null, null, LlmStopReason.ABORTED, LlmUsage.zero(),
                    model == null ? null : model.getProvider(),
                    model == null ? null : model.getModelId(),
                    model == null ? null : model.getThinkingLevel(),
                    0L, 0, null);
        }

        private LlmCallException wrapFailure(Throwable error) {
            Throwable cause = unwrap(error);
            if (cause instanceof LlmCallException) {
                return (LlmCallException) cause;
            }
            return new LlmCallException("模型调用失败", cause);
        }
    }

    /**
     * 一次异步调用的状态机。每一轮只在 future 回调里推进，不阻塞载体线程。
     */
    private final class AsyncRun {

        private final LlmCall call;
        private final String bizId;
        private final ResolvedModel model;
        private final Attempt attempt;
        private final List<LlmMessage> transcript;
        private final List<LlmTool> tools;
        private final boolean declareTools;
        private final boolean executeTools;
        private final int limit;
        private final boolean stream;
        private final Consumer<String> onDelta;
        private final LlmCancelHandle cancel;
        /** 回调策略 io 模式的 per-call 串行执行器；inline 为 null。 */
        private final SerialCallbackExecutor barrier;
        private final CompletableFuture<LlmResult> result = new CompletableFuture<LlmResult>();
        /**
         * 只保护“终态记录”不被竞态重复写；TOOL_USE 这类非终态轮次按轮正常记录。
         */
        private final AtomicBoolean terminalRecorded = new AtomicBoolean(false);
        /**
         * 当前在途轮次持有的 guard 许可。whenComplete 头部正常归还；取消监听、
         * 回调队列溢出这类"run 已收敛但传输 future 可能永不完成"的兜底路径
         * （不合作传输，AL-H05）经 {@link #abandonInFlightPermit} 幂等归还，
         * 否则许可永久泄漏。引用相等 CAS 保证每张许可只被一条路径处理。
         */
        private final java.util.concurrent.atomic.AtomicReference<ProviderGuard.Permit> inFlightPermit =
                new java.util.concurrent.atomic.AtomicReference<ProviderGuard.Permit>();

        private volatile int completed;
        private LlmCompletion last;

        private AsyncRun(LlmCall call, String bizId, ResolvedModel model, List<LlmMessage> transcript,
                         List<LlmTool> tools, boolean declareTools, boolean executeTools, int limit,
                         boolean stream, Consumer<String> onDelta, LlmCancelHandle cancel,
                         SerialCallbackExecutor callbackBarrier) {
            this.call = call;
            this.bizId = bizId;
            this.model = model;
            this.attempt = new Attempt(bizId, model, call.getProfile());
            this.transcript = transcript;
            this.tools = tools;
            this.declareTools = declareTools;
            this.executeTools = executeTools;
            this.limit = limit;
            this.stream = stream;
            this.cancel = cancel;
            this.barrier = callbackBarrier;
            // 回调策略 Phase 2：io 模式把用户 onDelta 包装成屏障任务；inline 保持原样。
            if (callbackBarrier != null && onDelta != null) {
                final Consumer<String> user = onDelta;
                this.onDelta = new Consumer<String>() {
                    @Override
                    public void accept(final String delta) {
                        boolean accepted = callbackBarrier.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    user.accept(delta);
                                } catch (RuntimeException ex) {
                                    // 回调异常按 OVERLOAD 之外的语义收敛：异常完成 + abort 传输。
                                    failBarrier(new LlmCallException("onDelta 回调失败", ex));
                                }
                            }
                        });
                        if (!accepted) {
                            failOverload();
                        }
                    }
                };
            } else {
                this.onDelta = onDelta;
            }
        }

        /**
         * 完成屏障（CB-07）：先把"完成事件"排到所有已入队 delta 之后，再真正完成
         * result，保证调用方拿到结果前所有 delta 已回调。屏障 submit 失败时直接
         * 完成（此时通常已有 OVERLOAD 收敛占住 result，该调用为 no-op）。
         */
        private void completeBarrier(final LlmResult value) {
            if (barrier == null) {
                result.complete(value);
                return;
            }
            boolean accepted = barrier.submit(new Runnable() {
                @Override
                public void run() {
                    barrier.close();
                    result.complete(value);
                }
            });
            if (!accepted) {
                barrier.close();
                result.complete(value);
            }
        }

        /** 异常完成同样经过屏障：先冲刷已入队 delta，再异常完成。 */
        private void failBarrier(final Throwable error) {
            if (barrier == null) {
                result.completeExceptionally(error);
                return;
            }
            boolean accepted = barrier.submit(new Runnable() {
                @Override
                public void run() {
                    barrier.close();
                    result.completeExceptionally(error);
                }
            });
            if (!accepted) {
                barrier.close();
                result.completeExceptionally(error);
            }
        }

        /** 收敛兜底路径归还许可：不采样（传输结局未知/被取消），幂等。 */
        private void abandonInFlightPermit() {
            ProviderGuard.Permit permit = inFlightPermit.getAndSet(null);
            if (permit != null) {
                permit.release();
            }
        }

        /**
         * 回调队列溢出（M-2 顺序）：先异常完成 result 占住终态（OVERLOAD 语义
         * 不被取消监听的 ABORTED 吃掉），再 cancel——此时取消监听因 result 已
         * done 为 no-op，但 bindAbort 的传输中断仍触发。
         */
        private void failOverload() {
            recordTerminal(attempt, completed == 0 ? 0 : completed,
                    LlmStopReason.ERROR, 0, 0L, "回调队列已满");
            result.completeExceptionally(new LlmCallException("回调队列已满",
                    null, LlmCallException.Kind.OVERLOAD));
            cancel.cancel();
            // 不合作传输的 future 可能永不完成，whenComplete 释放锚点等不到。
            abandonInFlightPermit();
        }

        /**
         * 取消监听收敛（AL-H05）：transport 不配合 abort 时由取消方线程主动完成
         * result，调用方不再永久挂起；result 已完成时是 no-op，与正常完成路径无竞态。
         */
        private void cancelledByListener() {
            if (result.isDone()) {
                return;
            }
            recordTerminal(attempt, completed == 0 ? 0 : completed,
                    LlmStopReason.ABORTED, 0, 0L, null);
            completeBarrier(result(attempt, last, LlmStopReason.ABORTED, completed));
            abandonInFlightPermit();
        }

        private void next() {
            if (result.isDone()) {
                return;
            }
            if (cancel != null && cancel.isCancelled()) {
                recordTerminal(attempt, completed == 0 ? 0 : completed,
                        LlmStopReason.ABORTED, 0, 0L, null);
                completeBarrier(result(attempt, last, LlmStopReason.ABORTED, completed));
                return;
            }
            final ProviderGuard guard = guardFor(model);
            final int round = completed + 1;
            if (guard == null) {
                startRound(round, null);
                return;
            }
            // 许可按轮获取（D4）。快速路径 onGranted 在当前线程同步回调（行为与
            // 直通一致）；排队路径交接后在载体池上回调（线程纪律），排队超时经
            // onRejected 收敛。立即拒绝（熔断开/队列满）同步抛出。
            try {
                guard.acquireAsync(new ProviderGuard.AcquireListener() {
                    @Override
                    public void onGranted(ProviderGuard.Permit permit) {
                        startRound(round, permit);
                    }

                    @Override
                    public void onRejected(LlmCallException reason) {
                        fail(reason, round, 0L);
                    }
                }, new ProviderGuard.AliveCheck() {
                    @Override
                    public boolean isAlive() {
                        return !result.isDone();
                    }
                }, executor.io());
            } catch (LlmCallException ex) {
                fail(ex, round, 0L);
            }
        }

        /**
         * 发起一轮模型请求。permit 为 null 表示该 provider 未配置 guard（直通）。
         * 可能在三种线程上进入：调用线程、工具完成回调线程、guard 交接的载体线程。
         */
        private void startRound(final int round, final ProviderGuard.Permit permit) {
            if (permit != null) {
                inFlightPermit.set(permit);
            }
            if (result.isDone()) {
                // 排队期间已被取消等其他路径收敛：只归还许可，不采样（未发起传输）。
                abandonInFlightPermit();
                return;
            }
            if (cancel != null && cancel.isCancelled()) {
                abandonInFlightPermit();
                recordTerminal(attempt, completed == 0 ? 0 : completed,
                        LlmStopReason.ABORTED, 0, 0L, null);
                completeBarrier(result(attempt, last, LlmStopReason.ABORTED, completed));
                return;
            }
            final LlmRequest request = new LlmRequest(model, transcript, declareTools ? tools : null,
                    stream, onDelta, cancel, call.getConnectTimeoutMs(), call.getReadTimeoutMs(),
                    null, call.getMaxTokens());
            final long started = System.currentTimeMillis();
            CompletableFuture<LlmCompletion> future;
            try {
                future = transports.get(model.getApi()).completeAsync(request);
            } catch (RuntimeException ex) {
                // 没拿到 future：本轮结局在这里采样并归还，whenComplete 不会触发。
                if (permit != null) {
                    inFlightPermit.compareAndSet(permit, null);
                    permit.verdict(ex);
                    permit.release();
                }
                fail(ex, round, System.currentTimeMillis() - started);
                return;
            }
            if (future == null) {
                // 适配器违反 SPI 时不能让调用方挂在一个永远不完成的 future 上。
                LlmCallException nullFuture = new LlmCallException("协议适配器返回了空 future");
                if (permit != null) {
                    inFlightPermit.compareAndSet(permit, null);
                    permit.verdict(nullFuture);
                    permit.release();
                }
                fail(nullFuture, round, System.currentTimeMillis() - started);
                return;
            }
            future.whenComplete((completion, error) -> {
                // 释放锚点在 isDone 早退判断之前（审查 F1）：先按本轮传输结局采样并
                // 归还许可，再判断终态——否则"取消先收敛、在途轮次后完成"会漏释放。
                if (permit != null) {
                    permit.verdict(error);
                    permit.release();
                    inFlightPermit.compareAndSet(permit, null);
                }
                if (result.isDone()) {
                    return;
                }
                long latency = System.currentTimeMillis() - started;
                if (error != null) {
                    fail(unwrap(error), round, latency);
                    return;
                }
                if (completion == null) {
                    // 空结果按协议错误收口，避免 NPE 落在回调线程里导致 future 永不完成。
                    fail(new LlmCallException("协议适配器返回了空结果"), round, latency);
                    return;
                }
                completed = round;
                last = completion;
                attempt.add(completion.getUsage(), latency);

                if (cancel != null && cancel.isCancelled()) {
                    // transport 不配合 abort 时由网关兜底：取消优先于正常完成。
                    recordTerminal(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                    completeBarrier(result(attempt, completion, LlmStopReason.ABORTED, completed));
                    return;
                }
                if (completion.getStop() == LlmStopReason.ABORTED) {
                    recordTerminal(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                    completeBarrier(result(attempt, completion, LlmStopReason.ABORTED, completed));
                    return;
                }
                boolean wantsTool = executeTools && completion.getStop() == LlmStopReason.TOOL_USE
                        && !completion.getToolCalls().isEmpty();
                if (!wantsTool) {
                    recordTerminal(attempt, round, completion.getStop(),
                            success(completion.getStop()), latency, null);
                    completeBarrier(result(attempt, completion, completion.getStop(), completed));
                    return;
                }
                if (round >= limit) {
                    recordTerminal(attempt, round, LlmStopReason.MAX_ROUNDS, 0, latency, null);
                    completeBarrier(result(attempt, completion, LlmStopReason.MAX_ROUNDS, completed));
                    return;
                }
                // 非终态轮次：每轮一条记录，不能被后续终态记录“合并”。
                record(attempt, round, LlmStopReason.TOOL_USE, 1, latency, null);
                transcript.add(LlmMessage.assistant(completion.getContent(),
                        completion.getToolCalls()));
                CompletableFuture<List<String>> toolFuture;
                try {
                    toolFuture = toolExecutor.executeAsync(tools, completion.getToolCalls());
                } catch (RuntimeException ex) {
                    fail(new LlmCallException("工具执行失败", ex), round, latency);
                    return;
                }
                if (toolFuture == null) {
                    fail(new LlmCallException("工具执行器返回了空 future"), round, latency);
                    return;
                }
                toolFuture.whenComplete((results, toolError) -> {
                    if (result.isDone()) {
                        return;
                    }
                    try {
                        if (toolError != null) {
                            fail(new LlmCallException("工具执行失败", unwrap(toolError)),
                                    round, latency);
                            return;
                        }
                        List<String> texts = results == null
                                ? Collections.<String>emptyList() : results;
                        for (int i = 0; i < completion.getToolCalls().size(); i++) {
                            LlmToolCall toolCall = completion.getToolCalls().get(i);
                            String text = i < texts.size() ? texts.get(i) : "";
                            transcript.add(LlmMessage.toolResult(toolCall.getId(),
                                    text == null ? "" : text));
                        }
                        if (cancel != null && cancel.isCancelled()) {
                            recordTerminal(attempt, round, LlmStopReason.ABORTED, 0,
                                    System.currentTimeMillis() - started, null);
                            completeBarrier(result(attempt, completion,
                                    LlmStopReason.ABORTED, completed));
                            return;
                        }
                        next();
                    } catch (RuntimeException ex) {
                        // 工具结果处理异常不能落在回调线程里导致 future 永不完成。
                        fail(new LlmCallException("工具结果处理失败", ex), round, latency);
                    }
                });
            });
        }

        private void fail(Throwable cause, int round, long latency) {
            if (cancel != null && cancel.isCancelled()) {
                recordTerminal(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                completeBarrier(result(attempt, null, LlmStopReason.ABORTED, completed));
                return;
            }
            markKeyFailure(attempt.model, cause);
            recordTerminal(attempt, round, LlmStopReason.ERROR, 0, latency, cause.getMessage());
            if (cause instanceof LlmCallException) {
                failBarrier(cause);
            } else {
                failBarrier(new LlmCallException("模型调用失败", cause));
            }
        }

        /**
         * future 被调用方取消时补一条记录。终态已被取消，不能再 complete 结果。
         */
        private void cancelledByFuture() {
            recordTerminal(attempt, completed == 0 ? 0 : completed,
                    LlmStopReason.ABORTED, 0, 0L, null);
            abandonInFlightPermit();
        }

        /**
         * 终态记录只允许写一次；TOOL_USE 等中间轮次使用 {@link #record} 按轮记录。
         */
        private void recordTerminal(Attempt attempt, int round, LlmStopReason stop, int success,
                                    long latencyMs, String error) {
            if (terminalRecorded.compareAndSet(false, true)) {
                record(attempt, round, stop, success, latencyMs, error);
            }
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private LlmResult invoke(LlmCall call, List<LlmTool> tools, Integer maxRounds, int mode,
                             boolean stream, Consumer<String> onDelta, LlmCancelHandle cancel) {
        return invoke(call, tools, maxRounds, mode, stream, onDelta, cancel, null);
    }

    /**
     * @param deltaSink 类型化增量通道；同步入口专用，异步入口本轮不接（异步流式延后）
     */
    private LlmResult invoke(LlmCall call, List<LlmTool> tools, Integer maxRounds, int mode,
                             boolean stream, Consumer<String> onDelta, LlmCancelHandle cancel,
                             LlmDeltaSink deltaSink) {
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        String bizId = Strings.trimToNull(call.getBizId());
        if (bizId == null) {
            throw new LlmCallException("业务 id 不能为空");
        }
        if (!resolver.isConfigured()) {
            throw new LlmCallException("ai.llm 未配置");
        }
        if (!resolver.allows(bizId)) {
            throw new LlmCallException("业务 id 未登记: " + bizId);
        }

        ResolvedModel model = resolve(call);
        String api = model.getApi();
        LlmTransport transport = transports.get(api);
        if (transport == null) {
            throw new LlmCallException("不支持的协议: " + api);
        }

        List<LlmMessage> transcript = new ArrayList<LlmMessage>(LlmTexts.fold(call.getMessages()));
        LlmTexts.validate(transcript, model.isAcceptsImage());
        boolean declareTools = mode != MODE_ONE;
        boolean executeTools = mode == MODE_LOOP;
        if (mode == MODE_LOOP) {
            validateTools(tools);
        } else if (mode == MODE_ROUND && tools != null && !tools.isEmpty()) {
            validateTools(tools);
        } else if (mode == MODE_ROUND) {
            // round 也允许不带工具，此时等同一次单轮调用。
            declareTools = false;
        }
        int limit = maxRounds == null ? DEFAULT_MAX_ROUNDS : maxRounds.intValue();
        if (executeTools && limit <= 0) {
            throw new LlmCallException("最大轮数必须大于 0");
        }

        Attempt attempt = new Attempt(bizId, model, call.getProfile());
        ProviderGuard guard = guardFor(model);
        int completed = 0;
        LlmCompletion last = null;
        while (true) {
            if (cancel != null && cancel.isCancelled()) {
                record(attempt, completed == 0 ? 0 : completed, LlmStopReason.ABORTED, 0,
                        0L, null);
                return result(attempt, last, LlmStopReason.ABORTED, completed);
            }
            int round = completed + 1;
            LlmRequest request = new LlmRequest(model, transcript, declareTools ? tools : null,
                    stream, onDelta, cancel, call.getConnectTimeoutMs(), call.getReadTimeoutMs(),
                    null, call.getMaxTokens(), deltaSink);
            long started = System.currentTimeMillis();
            LlmCompletion completion;
            // 许可按单次模型调用（每轮）获取（D4）：校验抛出点全部之后（F22），
            // 工具执行期间不占许可。同步等待会 park 调用线程至多 concurrencyWaitMs。
            ProviderGuard.Permit permit = null;
            try {
                if (guard != null) {
                    permit = guard.acquireSync();
                }
                completion = transport.complete(request);
            } catch (LlmCallException ex) {
                long latency = System.currentTimeMillis() - started;
                markKeyFailure(model, ex);
                if (permit != null) {
                    permit.verdict(ex);
                    permit.release();
                    permit = null;
                }
                if (cancel != null && cancel.isCancelled()) {
                    record(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                    return result(attempt, null, LlmStopReason.ABORTED, completed);
                }
                record(attempt, round, LlmStopReason.ERROR, 0, latency, ex.getMessage());
                throw ex;
            } catch (RuntimeException ex) {
                long latency = System.currentTimeMillis() - started;
                LlmCallException wrapped = new LlmCallException("模型调用失败", ex);
                if (permit != null) {
                    permit.verdict(wrapped);
                    permit.release();
                    permit = null;
                }
                if (cancel != null && cancel.isCancelled()) {
                    record(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                    return result(attempt, null, LlmStopReason.ABORTED, completed);
                }
                record(attempt, round, LlmStopReason.ERROR, 0, latency, ex.getMessage());
                throw wrapped;
            } finally {
                if (permit != null) {
                    // 正常返回（含 completion==null 的 SPI 违约抛出）按成功口径采样并归还。
                    permit.verdict(null);
                    permit.release();
                }
            }
            long latency = System.currentTimeMillis() - started;
            if (completion == null) {
                // 同步路径同样按协议错误收口，避免空结果在后面的字段访问里变成 NPE。
                record(attempt, round, LlmStopReason.ERROR, 0, latency,
                        "协议适配器返回了空结果");
                throw new LlmCallException("协议适配器返回了空结果");
            }
            completed = round;
            last = completion;
            attempt.add(completion.getUsage(), latency);

            if (cancel != null && cancel.isCancelled()) {
                // 与异步路径一致：传输层忽略 abort 并正常返回时，取消仍然优先。
                record(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                return result(attempt, completion, LlmStopReason.ABORTED, completed);
            }
            if (completion.getStop() == LlmStopReason.ABORTED) {
                record(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                return result(attempt, completion, LlmStopReason.ABORTED, completed);
            }
            boolean wantsTool = executeTools && completion.getStop() == LlmStopReason.TOOL_USE
                    && !completion.getToolCalls().isEmpty();
            if (!wantsTool) {
                record(attempt, round, completion.getStop(), success(completion.getStop()), latency, null);
                return result(attempt, completion, completion.getStop(), completed);
            }
            if (round >= limit) {
                record(attempt, round, LlmStopReason.MAX_ROUNDS, 0, latency, null);
                return result(attempt, completion, LlmStopReason.MAX_ROUNDS, completed);
            }
            record(attempt, round, LlmStopReason.TOOL_USE, 1, latency, null);
            transcript.add(LlmMessage.assistant(completion.getContent(), completion.getToolCalls()));
            List<String> results;
            try {
                results = toolExecutor.execute(tools, completion.getToolCalls());
            } catch (RuntimeException ex) {
                long failedLatency = System.currentTimeMillis() - started;
                if (cancel != null && cancel.isCancelled()) {
                    record(attempt, round, LlmStopReason.ABORTED, 0, failedLatency, null);
                    return result(attempt, completion, LlmStopReason.ABORTED, completed);
                }
                record(attempt, round, LlmStopReason.ERROR, 0, failedLatency, ex.getMessage());
                throw new LlmCallException("工具执行失败", ex);
            }
            if (results == null) {
                // SPI 违约时按空结果处理，避免 NPE 打断整个工具循环。
                results = Collections.emptyList();
            }
            for (int i = 0; i < completion.getToolCalls().size(); i++) {
                LlmToolCall toolCall = completion.getToolCalls().get(i);
                String text = i < results.size() ? results.get(i) : "";
                transcript.add(LlmMessage.toolResult(toolCall.getId(),
                        text == null ? "" : text));
            }
            if (cancel != null && cancel.isCancelled()) {
                record(attempt, round, LlmStopReason.ABORTED, 0,
                        System.currentTimeMillis() - started, null);
                return result(attempt, completion, LlmStopReason.ABORTED, completed);
            }
        }
    }

    /**
     * 429/401 失败标记触发多 key 轮换（限流熔断计划 §3.4）：只认 HTTP 429/401，
     * 其余类别（含 guard 合成的 OVERLOAD/CIRCUIT_OPEN）不标记。轮换失败不影响
     * 主失败路径。key 值只在 resolver 内做值匹配，不出现在任何日志。
     */
    private void markKeyFailure(ResolvedModel model, Throwable cause) {
        if (model == null || cause == null) {
            return;
        }
        Throwable current = unwrap(cause);
        if (!(current instanceof LlmCallException)) {
            return;
        }
        LlmCallException ex = (LlmCallException) current;
        if (ex.getKind() != LlmCallException.Kind.HTTP || ex.getStatusCode() == null) {
            return;
        }
        int code = ex.getStatusCode().intValue();
        if (code != 429 && code != 401) {
            return;
        }
        try {
            resolver.markKeyFailure(model.getProvider(), model.getApiKey());
        } catch (RuntimeException ignored) {
            // 轮换是尽力而为的兜底，不能改变本次调用的失败收敛。
        }
    }

    private ResolvedModel resolve(LlmCall call) {
        if (Strings.isNotBlank(call.getProfile())) {
            return resolver.resolveProfile(call.getProfile());
        }
        if (Strings.isBlank(call.getProvider()) && Strings.isBlank(call.getModelId())) {
            String defaultProfile = resolver.getDefaultProfile();
            if (defaultProfile != null) {
                return resolver.resolveProfile(defaultProfile);
            }
        }
        return resolver.resolveExplicit(call.getProvider(), call.getModelId(), call.getThinkingLevel());
    }

    private static void validateTools(List<LlmTool> tools) {
        if (tools == null || tools.isEmpty()) {
            throw new LlmCallException("工具列表不能为空");
        }
        Set<String> names = new HashSet<String>();
        for (LlmTool tool : tools) {
            if (tool == null || Strings.isBlank(tool.name())) {
                throw new LlmCallException("工具名称不能为空");
            }
            if (!names.add(tool.name())) {
                throw new LlmCallException("工具名称重复: " + tool.name());
            }
        }
    }

    private static int success(LlmStopReason stop) {
        if (stop == LlmStopReason.ERROR || stop == LlmStopReason.ABORTED
                || stop == LlmStopReason.MAX_ROUNDS) {
            return 0;
        }
        return 1;
    }

    private LlmResult result(Attempt attempt, LlmCompletion completion, LlmStopReason stop, int rounds) {
        String content = completion == null ? "" : completion.getContent();
        String thinking = completion == null ? null : completion.getThinkingText();
        String signature = completion == null ? null : completion.getThinkingSignature();
        List<LlmToolCall> calls = completion == null ? null : completion.getToolCalls();
        return new LlmResult(content, thinking, signature, stop, attempt.usage(),
                attempt.model.getProvider(), attempt.model.getModelId(),
                attempt.model.getThinkingLevel(), attempt.latencyMs, rounds, calls);
    }

    private void record(Attempt attempt, int round, LlmStopReason stop, int success,
                        long latencyMs, String error) {
        LlmCallRecord row = new LlmCallRecord();
        row.setBizId(attempt.bizId);
        row.setProvider(attempt.model.getProvider());
        row.setModelId(attempt.model.getModelId());
        row.setApi(attempt.model.getApi());
        row.setProfile(attempt.profile);
        row.setThinkingLevel(attempt.model.getThinkingLevel().wire());
        row.setRoundNo(Integer.valueOf(round));
        row.setInputTokens(Integer.valueOf(attempt.input));
        row.setOutputTokens(Integer.valueOf(attempt.output));
        row.setTotalTokens(Integer.valueOf(attempt.total));
        row.setLatencyMs(Long.valueOf(latencyMs));
        row.setStopReason(stop.wire());
        row.setSuccess(Integer.valueOf(success));
        row.setErrorBrief(brief(error, attempt.model.getApiKey()));
        logger.record(row);
    }

    static String brief(String message, String apiKey) {
        if (message == null) {
            return null;
        }
        String text = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (Strings.isNotEmpty(apiKey) && text.contains(apiKey)) {
            text = text.replace(apiKey, "***");
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static final class Attempt {
        private final String bizId;
        private final ResolvedModel model;
        private final String profile;
        private int input;
        private int output;
        private int total;
        private long latencyMs;

        private Attempt(String bizId, ResolvedModel model, String profile) {
            this.bizId = bizId;
            this.model = model;
            this.profile = profile;
        }

        private void add(LlmUsage usage, long latency) {
            input += usage.getInputTokens();
            output += usage.getOutputTokens();
            total += usage.getTotalTokens();
            latencyMs += latency;
        }

        private LlmUsage usage() {
            return LlmUsage.of(input, output, total);
        }
    }
}
