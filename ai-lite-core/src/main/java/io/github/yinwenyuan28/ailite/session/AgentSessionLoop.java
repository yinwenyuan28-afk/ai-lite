package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.assembly.AgentLifecycle;
import io.github.yinwenyuan28.ailite.assembly.AgentLoop;
import io.github.yinwenyuan28.ailite.assembly.AgentLoopContext;
import io.github.yinwenyuan28.ailite.assembly.AgentModelOutput;
import io.github.yinwenyuan28.ailite.assembly.AgentToolCall;
import io.github.yinwenyuan28.ailite.assembly.ToolDecision;
import io.github.yinwenyuan28.ailite.assembly.TurnDecision;
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

/**
 * 可续聊封装：组合 AgentLoop + AgentSessionStore。
 *
 * <p>加载会话 → 追加本轮用户消息 → 跑循环 → 按轮落盘 → save。
 * 取消、抛错或 save 失败时不会落半截。</p>
 */
public final class AgentSessionLoop {

    private final AgentLoop loop;
    private final AgentSessionStore store;

    public AgentSessionLoop(AgentLoop loop, AgentSessionStore store) {
        if (loop == null) {
            throw new LlmCallException("AgentLoop 不能为空");
        }
        if (store == null) {
            throw new LlmCallException("AgentSessionStore 不能为空");
        }
        this.loop = loop;
        this.store = store;
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

        LlmResult result = loop.run(call.withMessages(llmMessages), tools, hooks, maxRounds, cancel);
        if (result.getStopReason() == LlmStopReason.ABORTED) {
            return new ResumeResult(result, session);
        }

        pending.addAll(recorder.recorded());
        AgentSession toSave = new AgentSession();
        toSave.setUserId(session.getUserId());
        toSave.setSessionId(session.getSessionId());
        toSave.setBizId(Strings.isNotBlank(session.getBizId()) ? session.getBizId() : call.getBizId());
        toSave.setMessages(pending);
        toSave.setVersion(session.getVersion());
        store.save(toSave);
        AgentSession saved = store.find(userId, sessionId)
                .orElseThrow(() -> new LlmCallException("保存会话后读取失败"));
        return new ResumeResult(result, saved);
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
                    raw.getThinkingText(), output.getContent(), raw.getToolCalls()));
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
