package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.executor.DirectExecutorProvider;
import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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

    /**
     * @param resolver    配置解析器
     * @param transports  协议适配器列表，按 {@link LlmTransport#api()} 注册
     * @param store       调用记录存储
     * @param executor    异步执行器
     * @param toolExecutor 工具执行器
     */
    public LlmGateway(ModelResolver resolver, List<LlmTransport> transports,
                      LlmCallStore store, ExecutorProvider executor, ToolExecutor toolExecutor) {
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
        long started = System.currentTimeMillis();
        List<LlmCallException> failures = new ArrayList<LlmCallException>();
        for (int attempt = 1; attempt <= policy.getMaxAttempts(); attempt++) {
            if (cancel != null && cancel.isCancelled()) {
                return one(call, cancel);
            }
            Long deadline = policy.getTotalDeadlineMs();
            if (deadline != null
                    && System.currentTimeMillis() - started >= deadline.longValue()) {
                throw deadlineExceeded(failures);
            }
            try {
                return one(call, cancel);
            } catch (LlmCallException ex) {
                failures.add(ex);
                if (!policy.shouldRetry(ex) || attempt >= policy.getMaxAttempts()) {
                    throw withSuppressed(ex, failures);
                }
                if (cancel != null && cancel.isCancelled()) {
                    return one(call, cancel);
                }
                long delay = policy.delayMs(attempt);
                if (deadline != null) {
                    long remaining = deadline.longValue()
                            - (System.currentTimeMillis() - started);
                    if (remaining <= 0L) {
                        throw deadlineExceeded(failures);
                    }
                    delay = Math.min(delay, remaining);
                }
                policy.notifyRetry(attempt, delay, ex);
                if (!sleepCancellable(delay, cancel)) {
                    return one(call, cancel);
                }
            }
        }
        throw deadlineExceeded(failures);
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
     * 把之前的失败挂到最终异常上，保留每次尝试的根因。
     */
    private static LlmCallException withSuppressed(LlmCallException last,
                                                   List<LlmCallException> failures) {
        for (int i = 0; i < failures.size() - 1; i++) {
            LlmCallException failure = failures.get(i);
            if (failure != last) {
                last.addSuppressed(failure);
            }
        }
        return last;
    }

    /**
     * 总时限到期时抛出的异常；保留最后一次失败的分类和根因。
     */
    private static LlmCallException deadlineExceeded(List<LlmCallException> failures) {
        if (failures.isEmpty()) {
            return new LlmCallException("重试超过总时限", null,
                    LlmCallException.Kind.TRANSPORT);
        }
        LlmCallException last = failures.get(failures.size() - 1);
        LlmCallException ex = new LlmCallException("重试超过总时限", last,
                last.getKind(), last.getStatusCode());
        for (int i = 0; i < failures.size() - 1; i++) {
            ex.addSuppressed(failures.get(i));
        }
        return ex;
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
        return invoke(call, tools, maxRounds, MODE_LOOP, false, null, null);
    }

    public LlmResult toolLoop(LlmCall call, List<LlmTool> tools) {
        return toolLoop(call, tools, null);
    }

    public LlmResult toolLoopByStream(LlmCall call, List<LlmTool> tools, Integer maxRounds,
                                      Consumer<String> onDelta, LlmCancelHandle cancel) {
        return invoke(call, tools, maxRounds, MODE_LOOP, true, onDelta, cancel);
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
     * 非阻塞问一次。等待模型响应时不占调用线程，适合 JDK 8 下高并发接入。
     *
     * @param call 调用参数
     * @return 结果 future
     */
    public CompletableFuture<LlmResult> oneAsync(LlmCall call) {
        return invokeAsync(call, null, null, MODE_ONE, false, null, null);
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
        AsyncRun run = new AsyncRun(call, bizId, model, transcript, tools, declareTools,
                executeTools, limit, stream, onDelta, effectiveCancel);
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
        private final CompletableFuture<LlmResult> result = new CompletableFuture<LlmResult>();
        private final AtomicBoolean recorded = new AtomicBoolean(false);

        private volatile int completed;
        private LlmCompletion last;

        private AsyncRun(LlmCall call, String bizId, ResolvedModel model, List<LlmMessage> transcript,
                         List<LlmTool> tools, boolean declareTools, boolean executeTools, int limit,
                         boolean stream, Consumer<String> onDelta, LlmCancelHandle cancel) {
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
            this.onDelta = onDelta;
            this.cancel = cancel;
        }

        private void next() {
            if (result.isDone()) {
                return;
            }
            if (cancel != null && cancel.isCancelled()) {
                recordOnce(attempt, completed == 0 ? 0 : completed,
                        LlmStopReason.ABORTED, 0, 0L, null);
                result.complete(result(attempt, last, LlmStopReason.ABORTED, completed));
                return;
            }
            final int round = completed + 1;
            final LlmRequest request = new LlmRequest(model, transcript, declareTools ? tools : null,
                    stream, onDelta, cancel, call.getConnectTimeoutMs(), call.getReadTimeoutMs(), null);
            final long started = System.currentTimeMillis();
            CompletableFuture<LlmCompletion> future;
            try {
                future = transports.get(model.getApi()).completeAsync(request);
            } catch (RuntimeException ex) {
                fail(ex, round, System.currentTimeMillis() - started);
                return;
            }
            if (future == null) {
                // 适配器违反 SPI 时不能让调用方挂在一个永远不完成的 future 上。
                fail(new LlmCallException("协议适配器返回了空 future"), round,
                        System.currentTimeMillis() - started);
                return;
            }
            future.whenComplete((completion, error) -> {
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
                    recordOnce(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                    result.complete(result(attempt, completion, LlmStopReason.ABORTED, completed));
                    return;
                }
                if (completion.getStop() == LlmStopReason.ABORTED) {
                    recordOnce(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                    result.complete(result(attempt, completion, LlmStopReason.ABORTED, completed));
                    return;
                }
                boolean wantsTool = executeTools && completion.getStop() == LlmStopReason.TOOL_USE
                        && !completion.getToolCalls().isEmpty();
                if (!wantsTool) {
                    recordOnce(attempt, round, completion.getStop(), success(completion.getStop()),
                            latency, null);
                    result.complete(result(attempt, completion, completion.getStop(), completed));
                    return;
                }
                if (round >= limit) {
                    recordOnce(attempt, round, LlmStopReason.MAX_ROUNDS, 0, latency, null);
                    result.complete(result(attempt, completion, LlmStopReason.MAX_ROUNDS, completed));
                    return;
                }
                recordOnce(attempt, round, LlmStopReason.TOOL_USE, 1, latency, null);
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
                    if (toolError != null) {
                        fail(new LlmCallException("工具执行失败", unwrap(toolError)), round, latency);
                        return;
                    }
                    List<String> texts = results == null
                            ? Collections.<String>emptyList() : results;
                    for (int i = 0; i < completion.getToolCalls().size(); i++) {
                        LlmToolCall toolCall = completion.getToolCalls().get(i);
                        String text = i < texts.size() ? texts.get(i) : "";
                        transcript.add(LlmMessage.toolResult(toolCall.getId(), text));
                    }
                    if (cancel != null && cancel.isCancelled()) {
                        result.complete(result(attempt, completion, LlmStopReason.ABORTED, completed));
                        return;
                    }
                    next();
                });
            });
        }

        private void fail(Throwable cause, int round, long latency) {
            if (cancel != null && cancel.isCancelled()) {
                recordOnce(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                result.complete(result(attempt, null, LlmStopReason.ABORTED, completed));
                return;
            }
            recordOnce(attempt, round, LlmStopReason.ERROR, 0, latency, cause.getMessage());
            if (cause instanceof LlmCallException) {
                result.completeExceptionally(cause);
            } else {
                result.completeExceptionally(new LlmCallException("模型调用失败", cause));
            }
        }

        /**
         * future 被调用方取消时补一条记录。终态已被取消，不能再 complete 结果。
         */
        private void cancelledByFuture() {
            recordOnce(attempt, completed == 0 ? 0 : completed,
                    LlmStopReason.ABORTED, 0, 0L, null);
        }

        private void recordOnce(Attempt attempt, int round, LlmStopReason stop, int success,
                                long latencyMs, String error) {
            if (recorded.compareAndSet(false, true)) {
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
                    stream, onDelta, cancel, call.getConnectTimeoutMs(), call.getReadTimeoutMs(), null);
            long started = System.currentTimeMillis();
            LlmCompletion completion;
            try {
                completion = transport.complete(request);
            } catch (LlmCallException ex) {
                long latency = System.currentTimeMillis() - started;
                if (cancel != null && cancel.isCancelled()) {
                    record(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                    return result(attempt, null, LlmStopReason.ABORTED, completed);
                }
                record(attempt, round, LlmStopReason.ERROR, 0, latency, ex.getMessage());
                throw ex;
            } catch (RuntimeException ex) {
                long latency = System.currentTimeMillis() - started;
                if (cancel != null && cancel.isCancelled()) {
                    record(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                    return result(attempt, null, LlmStopReason.ABORTED, completed);
                }
                record(attempt, round, LlmStopReason.ERROR, 0, latency, ex.getMessage());
                throw new LlmCallException("模型调用失败", ex);
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
            List<String> results = toolExecutor.execute(tools, completion.getToolCalls());
            for (int i = 0; i < completion.getToolCalls().size(); i++) {
                LlmToolCall toolCall = completion.getToolCalls().get(i);
                String text = i < results.size() ? results.get(i) : "";
                transcript.add(LlmMessage.toolResult(toolCall.getId(), text));
            }
            if (cancel != null && cancel.isCancelled()) {
                return result(attempt, completion, LlmStopReason.ABORTED, completed);
            }
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
