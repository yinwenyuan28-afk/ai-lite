package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.assembly.AgentLifecycle;
import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.assembly.AgentLoopContext;
import io.github.yinwenyuan28.ailite.assembly.AgentModelOutput;
import io.github.yinwenyuan28.ailite.assembly.AgentToolCall;
import io.github.yinwenyuan28.ailite.assembly.ToolDecision;
import io.github.yinwenyuan28.ailite.assembly.TurnDecision;
import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
import io.github.yinwenyuan28.ailite.util.Strings;
import io.github.yinwenyuan28.ailite.util.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import io.github.yinwenyuan28.ailite.internal.session.AgentSessionStoreSupport;
import io.github.yinwenyuan28.ailite.internal.session.ContextBudget;
import io.github.yinwenyuan28.ailite.internal.session.SessionCompactor;

/**
 * 可续聊封装：组合 AgentLoop + AgentSessionStore。
 *
 * <p>加载会话 → 追加本轮用户消息 → 跑循环 → 按轮落盘 → save。
 * 取消、抛错或 save 失败时不会落半截。</p>
 */
public final class AgentSessionLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionLoop.class);

    /** 自动推导的会话标题最大长度。 */
    private static final int TITLE_MAX_LENGTH = 64;
    /** 压缩租约时长：覆盖一次摘要调用的常见耗时并留余量。 */
    private static final long COMPACTION_LEASE_MS = 120000L;

    private final AgentLoop loop;
    private final AgentSessionStore store;
    private final ExecutorProvider executor;
    /** 上下文预算配置；null 表示未接入配置（不做预算检查也不压缩）。 */
    private final ContextBudget budget;
    /** token 估算器，默认加权实现。 */
    private final TokenEstimator estimator;
    /**
     * 投影时是否应用压缩条目。
     *
     * <p>与 {@code budget.compactionEnabled()} 分开的理由：预算关闭（
     * {@code ai-lite.context.enabled=false}）但压缩开着时，仍然应该应用已有的压缩条目；
     * 而压缩关闭时代理应回到完整历史。两者语义不同，不能混用一个开关。</p>
     */
    private final boolean applyCompaction;

    /**
     * 复用 AgentLoop 的 I/O 执行器。
     *
     * @param loop  Agent 循环
     * @param store 会话存储
     */
    public AgentSessionLoop(AgentLoop loop, AgentSessionStore store) {
        this(loop, store, null);
    }

    /**
     * @param loop     Agent 循环
     * @param store    会话存储
     * @param executor 会话加载和落盘使用的 I/O 执行器，null 表示复用 AgentLoop 的
     */
    public AgentSessionLoop(AgentLoop loop, AgentSessionStore store, ExecutorProvider executor) {
        this(loop, store, executor, null);
    }

    /**
     * 接入上下文治理配置。
     *
     * @param loop     Agent 循环
     * @param store    会话存储
     * @param executor 会话加载和落盘使用的 I/O 执行器，null 表示复用 AgentLoop 的
     * @param config   上下文配置，null 表示不做预算检查与压缩
     */
    public AgentSessionLoop(AgentLoop loop, AgentSessionStore store, ExecutorProvider executor,
                            AiLiteConfig.Context config) {
        if (loop == null) {
            throw new LlmCallException("AgentLoop 不能为空");
        }
        if (store == null) {
            throw new LlmCallException("AgentSessionStore 不能为空");
        }
        this.loop = loop;
        this.store = store;
        this.executor = executor == null ? loop.getExecutor() : executor;
        this.budget = config == null ? null : new ContextBudget(config);
        this.estimator = new TokenEstimator();
        // 未接入配置时不做压缩，投影按完整历史（与旧行为一致）。
        this.applyCompaction = config != null && config.getCompaction() != null
                && config.getCompaction().isEnabled();
        if (this.budget != null) {
            // 未知窗口时先做与窗口无关的校验（max-per-run / summary-max 等）。
            this.budget.validate(null, null);
        }
    }

    /**
     * 不存在则创建空会话；已存在则原样返回。
     *
     * @param userId    用户 id
     * @param sessionId 会话 id
     * @param bizId     业务 id
     * @return 会话快照
     */
    public AgentSession ensureSession(String userId, String sessionId, String bizId) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        Optional<AgentSession> existing = store.find(userId, sessionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        AgentSession created = new AgentSession();
        created.setUserId(userId);
        created.setSessionId(sessionId);
        created.setBizId(bizId);
        created.setMessages(new ArrayList<AgentSessionMessage>());
        created.setVersion(0L);
        store.save(created);
        return store.find(userId, sessionId)
                .orElseThrow(() -> new LlmCallException("创建会话失败"));
    }

    /**
     * 续聊一轮。
     *
     * <p><b>这是保留的旧签名，永久可用</b>，内部转调
     * {@link #resume(String, String, LlmCall, ResumeOptions)}。
     * 新增能力只在 {@link ResumeOptions} 上加字段，本方法签名不随能力增长而改变。</p>
     *
     * @param userId       用户 id
     * @param sessionId    会话 id
     * @param call         调用参数（消息会被会话历史替换）
     * @param tools        工具
     * @param lifecycles   调用方生命周期，排在内置 recorder 之前
     * @param maxRounds    最大轮数
     * @param cancel       取消句柄
     * @param newUserTurns 本轮新增的用户消息
     * @return 结果与最新会话
     */
    public ResumeResult resume(String userId, String sessionId, LlmCall call,
                               List<LlmTool> tools, List<AgentLifecycle> lifecycles,
                               Integer maxRounds, LlmCancelHandle cancel,
                               List<AgentSessionMessage> newUserTurns) {
        return resume(userId, sessionId, call, ResumeOptions.builder()
                .tools(tools)
                .lifecycles(lifecycles)
                .maxRounds(maxRounds)
                .cancel(cancel)
                .newUserTurns(newUserTurns)
                .build());
    }

    /**
     * 续聊一轮（参数收敛入口）。
     *
     * @param userId    用户 id
     * @param sessionId 会话 id
     * @param call      调用参数（消息会被会话历史替换）
     * @param options   本轮可选参数，非 null
     * @return 结果与最新会话
     * @throws LlmCallException options 为空，或设置了同步不支持的 {@code timeoutMs}
     */
    public ResumeResult resume(String userId, String sessionId, LlmCall call, ResumeOptions options) {
        if (options == null) {
            throw new LlmCallException("续聊参数不能为空");
        }
        // 同步入口没有超时通道：整轮超时依赖 future 的取消路径，只有异步链能表达。
        // 静默忽略会让调用方误以为有时间保护，因此显式拒绝。
        if (options.getTimeoutMs() != null && options.getTimeoutMs().longValue() > 0L) {
            throw new LlmCallException("同步续聊不支持 timeoutMs，请改用 resumeAsync 或自行调用 cancel");
        }        Prepared prepared = prepare(userId, sessionId, call, options.getLifecycles(),
                options.getNewUserTurns());
        LlmResult result;
        if (options.getDeltaListener() == null) {
            result = loop.run(call.withMessages(prepared.llmMessages), options.getTools(),
                    prepared.hooks, options.getMaxRounds(), options.getCancel(),
                    options.getMaxToolResultChars());
        } else {
            result = loop.runStream(call.withMessages(prepared.llmMessages), options.getTools(),
                    prepared.hooks, options.getMaxRounds(), options.getCancel(),
                    options.getDeltaListener(), options.getMaxToolResultChars());
        }
        return finish(userId, sessionId, call, prepared, result);
    }

    /**
     * 异步续聊。模型等待不占调用线程；加载和保存都在注入的 I/O 执行器里完成。
     *
     * <p>取消返回的 future 会同时取消内层 AgentLoop 和正在进行的 HTTP 请求，
     * 不需要调用方额外传取消句柄。</p>
     *
     * <p><b>这是保留的旧签名，永久可用</b>，内部转调
     * {@link #resumeAsync(String, String, LlmCall, ResumeOptions)}。</p>
     *
     * @param userId       用户 id
     * @param sessionId    会话 id
     * @param call         调用参数（消息会被会话历史替换）
     * @param tools        工具
     * @param lifecycles   调用方生命周期，排在内置 recorder 之前
     * @param maxRounds    最大轮数
     * @param cancel       取消句柄
     * @param newUserTurns 本轮新增的用户消息
     * @return 结果与最新会话
     */
    public CompletableFuture<ResumeResult> resumeAsync(final String userId, final String sessionId,
                                                       final LlmCall call, final List<LlmTool> tools,
                                                       final List<AgentLifecycle> lifecycles,
                                                       final Integer maxRounds,
                                                       final LlmCancelHandle cancel,
                                                       final List<AgentSessionMessage> newUserTurns) {
        return resumeAsync(userId, sessionId, call, ResumeOptions.builder()
                .tools(tools)
                .lifecycles(lifecycles)
                .maxRounds(maxRounds)
                .cancel(cancel)
                .newUserTurns(newUserTurns)
                .build());
    }

    /**
     * 异步续聊（参数收敛入口）。
     *
     * <p>{@code timeoutMs} 到点会消费掉 {@code cancel}（与 {@code AgentLoop.runAsync} 6 参一致），
     * 之后该句柄不能复用。</p>
     *
     * @param userId    用户 id
     * @param sessionId 会话 id
     * @param call      调用参数（消息会被会话历史替换）
     * @param options   本轮可选参数，非 null
     * @return 结果与最新会话
     * @throws LlmCallException options 为空
     */
    public CompletableFuture<ResumeResult> resumeAsync(final String userId, final String sessionId,
                                                       final LlmCall call,
                                                       final ResumeOptions options) {
        if (options == null) {
            throw new LlmCallException("续聊参数不能为空");
        }
        // 异步流式本轮不做（用户决策：后端任务优先，同步版已覆盖 SSE 场景）。
        // 静默忽略会让调用方以为会收到增量，因此显式拒绝。
        if (options.getDeltaListener() != null) {
            throw new LlmCallException("异步续聊暂不支持流式回调，请改用同步 resume");
        }
        final List<LlmTool> tools = options.getTools();
        final List<AgentLifecycle> lifecycles = options.getLifecycles();
        final Integer maxRounds = options.getMaxRounds();
        final List<AgentSessionMessage> newUserTurns = options.getNewUserTurns();
        final Long timeoutMs = options.getTimeoutMs();
        final Integer maxToolResultChars = options.getMaxToolResultChars();
        // 即使调用方只拿到返回的 future，也要让 cancel() 能传到内层循环和 HTTP。
        final LlmCancelHandle effectiveCancel = options.getCancel() == null
                ? new LlmCancelHandle() : options.getCancel();
        // 内层链（loop.runAsync + finish）的引用：外层 future.cancel 时一并取消，
        // 避免"run 已完成、finish（落盘）尚未执行"的窗口里取消后仍落盘（SL-08）。
        final AtomicReference<CompletableFuture<ResumeResult>> running =
                new AtomicReference<CompletableFuture<ResumeResult>>();
        final AtomicBoolean outerCancelled = new AtomicBoolean(false);
        CompletableFuture<Prepared> prepared = CompletableFuture.supplyAsync(
                () -> prepare(userId, sessionId, call, lifecycles, newUserTurns), executor.io());
        final CompletableFuture<ResumeResult> future =
                prepared.thenComposeAsync(new java.util.function.Function<Prepared,
                        CompletableFuture<ResumeResult>>() {
                    @Override
                    public CompletableFuture<ResumeResult> apply(final Prepared preparedTurn) {
                        CompletableFuture<ResumeResult> inner =
                                loop.runAsync(call.withMessages(preparedTurn.llmMessages), tools,
                                        preparedTurn.hooks, maxRounds, effectiveCancel, timeoutMs,
                                        maxToolResultChars)
                                        .thenApplyAsync(result -> finish(userId, sessionId, call,
                                                preparedTurn, result), executor.io());
                        running.set(inner);
                        if (outerCancelled.get() && !inner.isDone()) {
                            inner.cancel(true);
                        }
                        return inner;
                    }
                }, executor.io());
        future.whenComplete((value, error) -> {
            if (future.isCancelled()) {
                outerCancelled.set(true);
                effectiveCancel.cancel();
                CompletableFuture<ResumeResult> inner = running.get();
                if (inner != null && !inner.isDone()) {
                    // 内层链取消 → AgentLoop 的 future.cancel 桥触发 → abort + afterRun；
                    // thenApplyAsync 在上游 cancelled 时跳过，finish 不再执行、不落盘。
                    inner.cancel(true);
                }
            }
        });
        return future;
    }

    private Prepared prepare(String userId, String sessionId, LlmCall call,
                             List<AgentLifecycle> lifecycles,
                             List<AgentSessionMessage> newUserTurns) {
        AgentSessionStoreSupport.requireKeys(userId, sessionId);
        if (call == null) {
            throw new LlmCallException("调用不能为空");
        }
        AgentSession session = store.find(userId, sessionId)
                .orElseThrow(() -> new LlmCallException("会话不存在"));

        // 幂等去重只针对"会话里已存在"的 id（调用方重试同一批 turn 时不应重复落盘）；
        // 同一批次内部重复仍是调用方错误，交给下面的 validateMessageIds 报错。
        Set<String> existingIds = new HashSet<String>();
        for (AgentSessionEntry entry : session.getEntries()) {
            if (entry.getId() != null) {
                existingIds.add(entry.getId());
            }
        }
        List<AgentSessionMessage> pending = new ArrayList<AgentSessionMessage>(session.getMessages());
        List<AgentSessionMessage> turnsToAppend = new ArrayList<AgentSessionMessage>();
        if (newUserTurns != null) {
            for (AgentSessionMessage turn : newUserTurns) {
                if (turn == null || existingIds.contains(turn.getId())) {
                    continue;
                }
                pending.add(turn);
                turnsToAppend.add(turn);
            }
        }
        AgentSessionCodec.validateMessageIds(pending);

        // 投影 + 预算检查：压缩只发生在这一层。
        // 估算对象必须是"这一轮真正要发出去的全部内容"，含本轮新消息，
        // 否则会出现"压缩了但这次请求仍然超限"。
        //
        // 注意只能追加 turnsToAppend，不能追加 pending：pending 是完整历史，
        // 其中被压缩条目挡掉的消息再加回去就等于压缩失效。
        List<AgentSessionEntry> projected = projectEntries(session);
        int historyCount = projected.size();
        for (AgentSessionMessage turn : turnsToAppend) {
            if (!containsEntryId(projected, turn.getId())) {
                projected.add(turn);
            }
        }
        // 必须先跑预算检查（可能压缩并改写 projected），再据此生成发送用消息列表。
        // 顺序反过来会让压缩对本次请求完全失效——这是测试抓出来的真实缺陷。
        enforceBudget(call, session, projected, historyCount);
        List<LlmMessage> llmMessages = messagesToLlm(projected, false);

        SessionRecorder recorder = new SessionRecorder();
        List<AgentLifecycle> hooks = new ArrayList<AgentLifecycle>();
        if (lifecycles != null) {
            for (AgentLifecycle hook : lifecycles) {
                if (hook != null) {
                    hooks.add(hook);
                }
            }
        }
        hooks.add(recorder);
        return new Prepared(session, pending, llmMessages, hooks, recorder);
    }

    /**
     * 把会话条目投影成本轮要发送的条目列表。
     *
     * <p><b>规则</b>：</p>
     * <ol>
     *   <li>前缀区 = 数组开头连续的 SYSTEM 条目，永远保留在最前、不参与压缩</li>
     *   <li>取数组里最后一条 {@link CompactionEntry}；有则把它的摘要作为一条 SYSTEM
     *       接在前缀区之后，并从它的 {@code firstKeptEntryId} 开始取消息</li>
     *   <li>没有压缩条目则取前缀区之后的全部消息</li>
     * </ol>
     *
     * <p><b>压缩关闭时忽略压缩条目</b>：这是"可逆"承诺的落点——关掉开关应立刻
     * 恢复完整历史，不需要改数据。因此是否应用压缩由本方法按配置判断。</p>
     *
     * <p>这是纯计算，结果不缓存也不落盘：改了配置下一轮立刻按新规则算。</p>
     *
     * @param session 会话
     * @return 新一轮条目列表（新对象，可安全修改）
     */
    public List<AgentSessionEntry> projectEntries(AgentSession session) {
        List<AgentSessionEntry> entries = session.getEntries();
        List<AgentSessionEntry> projected = new ArrayList<AgentSessionEntry>();

        // 1. 前缀区
        int prefixEnd = 0;
        for (int i = 0; i < entries.size(); i++) {
            AgentSessionEntry entry = entries.get(i);
            if (entry instanceof AgentSessionMessage
                    && ((AgentSessionMessage) entry).getRole() == LlmRole.SYSTEM) {
                projected.add(entry);
                prefixEnd = i + 1;
            } else {
                break;
            }
        }

        CompactionEntry lastCompaction = applyCompaction ? lastCompaction(entries) : null;
        int lastCompactionIndex = applyCompaction ? lastCompactionIndex(entries) : -1;

        if (lastCompaction == null) {
            appendMessages(projected, entries, prefixEnd);
            return projected;
        }

        // 3. 摘要作为一条 SYSTEM 接在前缀区之后。
        if (Strings.isNotBlank(lastCompaction.getSummary())) {
            projected.add(AgentSessionMessage.system(lastCompaction.getSummary()));
        }
        int from = indexOfEntry(entries, lastCompaction.getFirstKeptEntryId());
        if (from < 0) {
            // 切点失效（条目被删了）：退化为"压缩条目之后"的位置，保证不返回空历史。
            from = lastCompactionIndex + 1;
        }
        appendMessages(projected, entries, from);
        return projected;
    }

    private static void appendMessages(List<AgentSessionEntry> target, List<AgentSessionEntry> source,
                                       int from) {
        for (int i = Math.max(0, from); i < source.size(); i++) {
            if (source.get(i) instanceof AgentSessionMessage) {
                target.add(source.get(i));
            }
        }
    }

    private static int indexOfEntry(List<AgentSessionEntry> entries, String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (id.equals(entries.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return 最后一条压缩条目；没有则 null
     */
    private static CompactionEntry lastCompaction(List<AgentSessionEntry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof CompactionEntry) {
                return (CompactionEntry) entries.get(i);
            }
        }
        return null;
    }

    /**
     * @return 最后一条压缩条目的下标；没有则 -1
     */
    private static int lastCompactionIndex(List<AgentSessionEntry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof CompactionEntry) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsEntryId(List<AgentSessionEntry> entries, String id) {
        return indexOfEntry(entries, id) >= 0;
    }

    /**
     * 把条目列表转成运行时消息。
     */
    private static List<LlmMessage> messagesToLlm(List<AgentSessionEntry> entries,
                                                  boolean includeThinking) {
        List<AgentSessionMessage> messages = new ArrayList<AgentSessionMessage>();
        for (AgentSessionEntry entry : entries) {
            if (entry instanceof AgentSessionMessage) {
                messages.add((AgentSessionMessage) entry);
            }
        }
        return AgentSession.messagesToLlm(messages, includeThinking);
    }

    /**
     * 估算条目列表的 token 数。
     *
     * @param entries 条目
     * @return 估算值，至少为 0
     */
    public int estimateTokens(List<AgentSessionEntry> entries) {
        int total = 0;
        for (AgentSessionEntry entry : entries) {
            if (entry instanceof AgentSessionMessage) {
                AgentSessionMessage message = (AgentSessionMessage) entry;
                total += estimator.estimateMessage(message.getText());
                if (message.getThinkingText() != null) {
                    total += estimator.estimate(message.getThinkingText());
                }
            } else if (entry instanceof CompactionEntry) {
                total += estimator.estimateMessage(((CompactionEntry) entry).getSummary());
            }
        }
        return total;
    }

    /**
     * 预算检查：超限时按决策表处理。
     *
     * <pre>
     * 窗口未知 / 预算关闭        → 跳过
     * 估算 &lt;= 触发线            → 放行
     * 超线 且 压缩关闭           → 抛 CONTEXT_OVERFLOW
     * 超线 且 压缩开启           → 压缩一次后重新估算
     *      ├─ 回到线内           → 放行
     *      └─ 仍超线             → 抛 CONTEXT_OVERFLOW
     * </pre>
     *
     * @param call      调用参数，用于解析模型窗口
     * @param session   会话
     * @param projected 本轮投影后的条目（可变，压缩会往里追加条目）
     * @param historyCount projected 里属于已落盘历史的长度
     */
    private void enforceBudget(LlmCall call, AgentSession session,
                               List<AgentSessionEntry> projected, int historyCount) {
        if (budget == null || !budget.budgetEnabled()) {
            return;
        }
        ResolvedModel model;
        try {
            model = loop.getGateway().resolveFor(call);
        } catch (RuntimeException ex) {
            // 解析失败交给下面的正常调用路径报错，不在预算检查里抢先抛。
            return;
        }
        Integer window = Integer.valueOf(model.getContextWindow());
        budget.validate(window, model.getProvider() + "/" + model.getModelId());
        int trigger = budget.triggerTokens(model.getContextWindow());
        if (trigger <= 0) {
            // 窗口未知：无法判断，行为与未接入预算时一致。
            return;
        }
        int estimated = estimateTokens(projected);
        if (estimated <= trigger) {
            return;
        }
        if (!budget.compactionEnabled()) {
            throw LlmCallException.contextOverflow(estimated, trigger, "自动压缩已关闭");
        }
        CompactionOutcome outcome = compact(call, session, projected, historyCount, estimated, trigger);
        if (outcome.compacted) {
            int after = estimateTokens(projected);
            if (after <= trigger) {
                return;
            }
            throw LlmCallException.contextOverflow(after, trigger,
                    "压缩后仍超限（压缩前 " + estimated + "）");
        }
        throw LlmCallException.contextOverflow(estimated, trigger, outcome.reason);
    }

    /**
     * 执行一次压缩并把新条目同步进本轮投影列表。
     *
     * <p>压缩写回 {@code session}（追加压缩条目），随后重新投影覆盖 {@code projected}，
     * 保证本次请求用的是压缩后的上下文。</p>
     *
     * @param call      主调用参数
     * @param session   会话
     * @param projected 本轮投影列表（会被原地替换内容）
     * @param historyCount projected 里属于已落盘历史的长度（压缩切点上限）
     * @param estimated 压缩前估算值
     * @param trigger   触发线
     * @return 压缩结果
     */
    private CompactionOutcome compact(LlmCall call, AgentSession session,
                                      List<AgentSessionEntry> projected, int historyCount,
                                      int estimated, int trigger) {
        // 多节点去重：抢不到租约说明别的实例正在压同一会话，跳过本次压缩。
        // 跳过不是失败——下一轮读到别人的摘要后估算自然下降。
        boolean leased = store.tryAcquireCompaction(session.getUserId(), session.getSessionId(),
                COMPACTION_LEASE_MS);
        if (!leased) {
            log.debug("跳过压缩：另一实例持有租约 sessionId={}", session.getSessionId());
            return CompactionOutcome.fail("另一实例正在压缩该会话，本次跳过");
        }
        try {
            SessionCompactor compactor = new SessionCompactor(loop.getGateway(), estimator, budget,
                    budgetConfig());
            SessionCompactor.Outcome outcome = compactor.compact(call, session, projected,
                    historyCount, estimated);
            if (outcome.compacted) {
                // 重新投影：压缩条目已写进 session，本轮要按新视图发送。
                List<AgentSessionEntry> reprojected = projectEntries(session);
                projected.clear();
                projected.addAll(reprojected);
                log.info("会话已压缩 sessionId={} 压缩前估算={} 触发线={} 摘要条目={}",
                        session.getSessionId(), Integer.valueOf(estimated), Integer.valueOf(trigger),
                        outcome.entry == null ? null : outcome.entry.getId());
                return CompactionOutcome.ok();
            }
            return CompactionOutcome.fail(outcome.reason);
        } finally {
            store.releaseCompaction(session.getUserId(), session.getSessionId());
        }
    }

    private AiLiteConfig.Context budgetConfig() {
        return budget == null ? null : budget.config();
    }

    /**
     * 压缩调用结果，只用于决策表分流。
     */
    private static final class CompactionOutcome {

        private final boolean compacted;
        private final String reason;

        private CompactionOutcome(boolean compacted, String reason) {
            this.compacted = compacted;
            this.reason = reason;
        }

        static CompactionOutcome ok() {
            return new CompactionOutcome(true, null);
        }

        static CompactionOutcome fail(String reason) {
            return new CompactionOutcome(false, reason);
        }
    }

    private ResumeResult finish(String userId, String sessionId, LlmCall call, Prepared prepared,
                                LlmResult result) {
        if (result.getStopReason() == LlmStopReason.ABORTED) {
            return new ResumeResult(result, prepared.session);
        }

        prepared.pending.addAll(prepared.recorder.recorded());
        // 必须是"复制 + 位置感知合并"，不能 new AgentSession() 重建：
        // 重建会只带走消息，把压缩摘要等非消息条目静默丢掉。
        // finish 是每轮续聊的必经路径，这个缺陷会让压缩完全失效。
        AgentSession toSave = AgentSession.copyOf(prepared.session);
        toSave.setBizId(Strings.isNotBlank(prepared.session.getBizId())
                ? prepared.session.getBizId() : call.getBizId());
        toSave.mergeMessages(prepared.pending);
        appendUsageEntry(toSave, call, result);
        toSave.setTotalTokens(toSave.sumUsageTokens());
        if (Strings.isBlank(toSave.getTitle())) {
            toSave.setTitle(deriveTitle(toSave));
        }
        store.save(toSave);
        AgentSession saved = store.find(userId, sessionId)
                .orElseThrow(() -> new LlmCallException("保存会话后读取失败"));
        return new ResumeResult(result, saved);
    }

    /**
     * 从首条 USER 消息推导会话标题。只推导一次：已经有标题就不再覆盖，
     * 避免用户后来改了标题又被下一轮冲掉（改标题走 SessionInfoEntry）。
     *
     * @param session 会话
     * @return 标题；没有用户消息时返回 null
     */
    private static String deriveTitle(AgentSession session) {
        for (AgentSessionEntry entry : session.getEntries()) {
            if (entry instanceof AgentSessionMessage
                    && ((AgentSessionMessage) entry).getRole() == LlmRole.USER) {
                String text = ((AgentSessionMessage) entry).getText();
                if (Strings.isBlank(text)) {
                    continue;
                }
                String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
                return oneLine.length() > TITLE_MAX_LENGTH
                        ? oneLine.substring(0, TITLE_MAX_LENGTH) : oneLine;
            }
        }
        return null;
    }

    /**
     * 追加本轮用量条目。
     *
     * <p><b>只记成功轮次</b>（用户决策 Q4）：取消轮在 {@code finish} 之前就 return 了，
     * 异常轮进不到 {@code finish}，因此这里天然只处理正常收敛的轮次。
     * 账单口径（含取消与失败）由 {@code LlmCallRecord} 覆盖，两者不冲突。</p>
     *
     * @param session 待保存会话
     * @param call    调用参数，用于记录 provider/model
     * @param result  本轮结果，含 usage
     */
    private void appendUsageEntry(AgentSession session, LlmCall call, LlmResult result) {
        if (result == null || result.getUsage() == null || result.getUsage().getTotalTokens() <= 0) {
            return;
        }
        List<AgentSessionEntry> entries = new ArrayList<AgentSessionEntry>(session.getEntries());
        entries.add(new UsageEntry(UUID.randomUUID().toString(), UsageEntry.KIND_CHAT,
                result.getProvider(), result.getModelId(),
                result.getUsage().getInputTokens(), result.getUsage().getOutputTokens(),
                result.getUsage().getTotalTokens()));
        session.setEntries(entries);
    }

    /**
     * 一次续聊在进入 Agent 循环前的快照。
     */
    private static final class Prepared {
        private final AgentSession session;
        private final List<AgentSessionMessage> pending;
        private final List<LlmMessage> llmMessages;
        private final List<AgentLifecycle> hooks;
        private final SessionRecorder recorder;

        private Prepared(AgentSession session, List<AgentSessionMessage> pending,
                         List<LlmMessage> llmMessages, List<AgentLifecycle> hooks,
                         SessionRecorder recorder) {
            this.session = session;
            this.pending = pending;
            this.llmMessages = llmMessages;
            this.hooks = hooks;
            this.recorder = recorder;
        }
    }

    /**
     * 续聊结果。
     */
    public static final class ResumeResult {
        private final LlmResult result;
        private final AgentSession session;

        public ResumeResult(LlmResult result, AgentSession session) {
            this.result = result;
            this.session = session;
        }

        public LlmResult getResult() {
            return result;
        }

        public AgentSession getSession() {
            return session;
        }
    }

    /**
     * 内置落盘钩子，必须排在调用方 hooks 之后。
     */
    static final class SessionRecorder implements AgentLifecycle {

        private final List<AgentSessionMessage> recorded = new ArrayList<AgentSessionMessage>();
        private final Set<String> toolResultIds = new HashSet<String>();

        List<AgentSessionMessage> recorded() {
            return recorded;
        }

        @Override
        public void afterModel(AgentLoopContext ctx, AgentModelOutput output) {
            if (output == null || output.getResult() == null) {
                return;
            }
            LlmResult raw = output.getResult();
            recorded.add(AgentSessionMessage.fromResult(
                    raw.getThinkingText(), raw.getThinkingSignature(),
                    output.getContent(), raw.getToolCalls()));
        }

        @Override
        public String afterTool(AgentLoopContext ctx, AgentToolCall call, String resultText) {
            if (call != null && Strings.isNotBlank(call.getId())) {
                recorded.add(AgentSessionMessage.toolResult(call.getId(), resultText));
                toolResultIds.add(call.getId());
            }
            return resultText;
        }

        @Override
        public ToolDecision beforeTool(AgentLoopContext ctx, AgentToolCall call) {
            return ToolDecision.allow();
        }

        @Override
        public TurnDecision afterTurn(AgentLoopContext ctx, LlmResult roundResult) {
            // 前面 hook deny 时本 recorder 的 afterTool 不会被调用，从 ctx 补本轮缺失的 tool_result。
            if (roundResult == null || roundResult.getToolCalls() == null
                    || roundResult.getToolCalls().isEmpty()) {
                return TurnDecision.modelDecides();
            }
            for (LlmToolCall call : roundResult.getToolCalls()) {
                if (call == null || Strings.isBlank(call.getId()) || toolResultIds.contains(call.getId())) {
                    continue;
                }
                String text = findToolResultText(ctx, call.getId());
                if (text != null) {
                    recorded.add(AgentSessionMessage.toolResult(call.getId(), text));
                    toolResultIds.add(call.getId());
                }
            }
            return TurnDecision.modelDecides();
        }

        private static String findToolResultText(AgentLoopContext ctx, String toolCallId) {
            if (ctx == null || ctx.getMessages() == null) {
                return null;
            }
            List<LlmMessage> messages = ctx.getMessages();
            for (int i = messages.size() - 1; i >= 0; i--) {
                LlmMessage message = messages.get(i);
                if (message != null && message.getRole() == LlmRole.TOOL_RESULT
                        && toolCallId.equals(message.getToolCallId())) {
                    return message.getText();
                }
            }
            return null;
        }
    }
}
