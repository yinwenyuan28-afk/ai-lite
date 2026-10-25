package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.assembly.AgentLifecycle;
import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.assembly.AgentLoopContext;
import io.github.yinwenyuan28.ailite.assembly.AgentModelOutput;
import io.github.yinwenyuan28.ailite.assembly.AgentToolCall;
import io.github.yinwenyuan28.ailite.assembly.ToolDecision;
import io.github.yinwenyuan28.ailite.assembly.TurnDecision;
import io.github.yinwenyuan28.ailite.executor.ExecutorProvider;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.util.Strings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可续聊封装：组合 AgentLoop + AgentSessionStore。
 *
 * <p>加载会话 → 追加本轮用户消息 → 跑循环 → 按轮落盘 → save。
 * 取消、抛错或 save 失败时不会落半截。</p>
 */
public final class AgentSessionLoop {

    private final AgentLoop loop;
    private final AgentSessionStore store;
    private final ExecutorProvider executor;

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
        if (loop == null) {
            throw new LlmCallException("AgentLoop 不能为空");
        }
        if (store == null) {
            throw new LlmCallException("AgentSessionStore 不能为空");
        }
        this.loop = loop;
        this.store = store;
        this.executor = executor == null ? loop.getExecutor() : executor;
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
        Prepared prepared = prepare(userId, sessionId, call, lifecycles, newUserTurns);
        LlmResult result = loop.run(call.withMessages(prepared.llmMessages), tools,
                prepared.hooks, maxRounds, cancel);
        return finish(userId, sessionId, call, prepared, result);
    }

    /**
     * 异步续聊。模型等待不占调用线程；加载和保存都在注入的 I/O 执行器里完成。
     *
     * <p>取消返回的 future 会同时取消内层 AgentLoop 和正在进行的 HTTP 请求，
     * 不需要调用方额外传取消句柄。</p>
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
        // 即使调用方只拿到返回的 future，也要让 cancel() 能传到内层循环和 HTTP。
        final LlmCancelHandle effectiveCancel = cancel == null ? new LlmCancelHandle() : cancel;
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
                                        preparedTurn.hooks, maxRounds, effectiveCancel)
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

        List<AgentSessionMessage> pending = new ArrayList<AgentSessionMessage>(session.getMessages());
        if (newUserTurns != null) {
            for (AgentSessionMessage turn : newUserTurns) {
                if (turn != null) {
                    pending.add(turn);
                }
            }
        }
        AgentSessionCodec.validateMessageIds(pending);

        List<LlmMessage> llmMessages = AgentSession.messagesToLlm(pending, false);
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

    private ResumeResult finish(String userId, String sessionId, LlmCall call, Prepared prepared,
                                LlmResult result) {
        if (result.getStopReason() == LlmStopReason.ABORTED) {
            return new ResumeResult(result, prepared.session);
        }

        prepared.pending.addAll(prepared.recorder.recorded());
        AgentSession toSave = new AgentSession();
        toSave.setUserId(prepared.session.getUserId());
        toSave.setSessionId(prepared.session.getSessionId());
        toSave.setBizId(Strings.isNotBlank(prepared.session.getBizId())
                ? prepared.session.getBizId() : call.getBizId());
        toSave.setMessages(prepared.pending);
        toSave.setVersion(prepared.session.getVersion());
        store.save(toSave);
        AgentSession saved = store.find(userId, sessionId)
                .orElseThrow(() -> new LlmCallException("保存会话后读取失败"));
        return new ResumeResult(result, saved);
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
