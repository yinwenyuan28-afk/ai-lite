package io.github.yinwenyuan28.ailite.llm;

import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
        this.logger = new AsyncLlmCallLogger(executor, store);
        this.toolExecutor = toolExecutor == null ? new SequentialToolExecutor() : toolExecutor;
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
                effectiveCancel.cancel();
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

        private int completed;
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
                record(attempt, completed == 0 ? 0 : completed, LlmStopReason.ABORTED, 0, 0L, null);
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
            future.whenComplete((completion, error) -> {
                if (result.isDone()) {
                    return;
                }
                long latency = System.currentTimeMillis() - started;
                if (error != null) {
                    fail(unwrap(error), round, latency);
                    return;
                }
                completed = round;
                last = completion;
                attempt.add(completion.getUsage(), latency);

                if (completion.getStop() == LlmStopReason.ABORTED) {
                    record(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                    result.complete(result(attempt, completion, LlmStopReason.ABORTED, completed));
                    return;
                }
                boolean wantsTool = executeTools && completion.getStop() == LlmStopReason.TOOL_USE
                        && !completion.getToolCalls().isEmpty();
                if (!wantsTool) {
                    record(attempt, round, completion.getStop(), success(completion.getStop()),
                            latency, null);
                    result.complete(result(attempt, completion, completion.getStop(), completed));
                    return;
                }
                if (round >= limit) {
                    record(attempt, round, LlmStopReason.MAX_ROUNDS, 0, latency, null);
                    result.complete(result(attempt, completion, LlmStopReason.MAX_ROUNDS, completed));
                    return;
                }
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
                toolFuture.whenComplete((results, toolError) -> {
                    if (result.isDone()) {
                        return;
                    }
                    if (toolError != null) {
                        fail(new LlmCallException("工具执行失败", unwrap(toolError)), round, latency);
                        return;
                    }
                    for (int i = 0; i < completion.getToolCalls().size(); i++) {
                        LlmToolCall toolCall = completion.getToolCalls().get(i);
                        String text = i < results.size() ? results.get(i) : "";
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
                record(attempt, round, LlmStopReason.ABORTED, 0, latency, null);
                result.complete(result(attempt, null, LlmStopReason.ABORTED, completed));
                return;
            }
            record(attempt, round, LlmStopReason.ERROR, 0, latency, cause.getMessage());
            if (cause instanceof LlmCallException) {
                result.completeExceptionally(cause);
            } else {
                result.completeExceptionally(new LlmCallException("模型调用失败", cause));
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
            completed = round;
            last = completion;
            attempt.add(completion.getUsage(), latency);

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
