package io.github.yinwenyuan28.ailite.internal.session;

import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.llm.LlmCall;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCancelHandle;
import io.github.yinwenyuan28.ailite.llm.LlmGateway;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmResult;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.session.AgentSessionEntry;
import io.github.yinwenyuan28.ailite.session.AgentSessionMessage;
import io.github.yinwenyuan28.ailite.session.CompactionEntry;
import io.github.yinwenyuan28.ailite.util.Strings;
import io.github.yinwenyuan28.ailite.util.TokenEstimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 会话压缩：挑切点、生成摘要、产出 {@link CompactionEntry}。
 *
 * <p><b>不销毁任何原始条目</b>：本类只往条目流里追加一条压缩条目，
 * 原始消息一条不删。读取时由投影决定从哪里开始看，关掉压缩开关即可立刻恢复完整历史。</p>
 *
 * <p><b>切点三条硬规则</b>：</p>
 * <ol>
 *   <li>从最后一条前缀 SYSTEM 之后开始，向前累计到 {@code keepRecentTokens} 即停</li>
 *   <li>切点必须落在 user 消息上——从 assistant 或 tool_result 开始会被供应商拒绝（400）</li>
 *   <li>绝不拆开 toolCall 与 toolResult；以 user 为边界天然满足</li>
 * </ol>
 *
 * <p><b>重复压缩的起点</b>：摘要输入 = 上一条压缩条目的摘要（迭代上下文）
 * + 从上次 {@code firstKeptEntryId} 到新切点之间的消息。否则每次从数组头摘要，
 * 输入量随会话增长，最终退化成"摘要请求自己超窗口"。</p>
 *
 * <p><b>分块</b>：待摘要内容超过摘要模型输入预算时按消息边界切块，
 * 逐块摘要并把上一块结果作为下一块上下文。</p>
 */
public final class SessionCompactor {

    /** 摘要提示词：固定文本，避免每次调用都不同导致摘要风格漂移。 */
    private static final String SYSTEM_PROMPT =
            "你是一个会话摘要器。把给定的对话历史压缩成一段供后续对话使用的摘要。\n"
            + "要求：\n"
            + "1. 保留用户的目标、已确认的结论、被否决的方案及原因、关键事实与数字\n"
            + "2. 保留尚未完成的待办与约束条件\n"
            + "3. 省略寒暄、重复内容和推理过程\n"
            + "4. 用第三人称陈述，不要加\"根据以上\"这类前后缀\n"
            + "5. 直接输出摘要正文，不要标题";

    private static final String CHUNK_PROMPT =
            "以下是对话历史的一个片段（第 %d 段，共约 %d 段）。请只摘要这一段内容，"
            + "保留其中的事实、结论、约束与待办。";

    private static final int SAFETY_MARGIN_TOKENS = 256;

    private final LlmGateway gateway;
    private final TokenEstimator estimator;
    private final ContextBudget budget;
    private final AiLiteConfig.Context config;

    /**
     * @param gateway   网关，用于发起摘要调用
     * @param estimator token 估算器
     * @param budget    预算参数
     * @param config    上下文配置
     */
    public SessionCompactor(LlmGateway gateway, TokenEstimator estimator, ContextBudget budget,
                            AiLiteConfig.Context config) {
        this.gateway = gateway;
        this.estimator = estimator;
        this.budget = budget;
        this.config = config;
    }

    /**
     * 压缩结果。
     */
    public static final class Outcome {

        /** 是否成功追加了压缩条目。 */
        public final boolean compacted;
        /** 失败原因，成功时为 null。 */
        public final String reason;
        /** 新追加的压缩条目，失败时为 null。 */
        public final CompactionEntry entry;

        private Outcome(boolean compacted, String reason, CompactionEntry entry) {
            this.compacted = compacted;
            this.reason = reason;
            this.entry = entry;
        }

        static Outcome ok(CompactionEntry entry) {
            return new Outcome(true, null, entry);
        }

        static Outcome fail(String reason) {
            return new Outcome(false, reason, null);
        }
    }

    /**
     * 对会话执行一次压缩。
     *
     * <p>成功时会直接改写 {@code session} 的条目列表（追加压缩条目），
     * 调用方随后重新投影即可得到变短的上下文。</p>
     *
     * @param call            主调用参数，用于沿用供应商/模型
     * @param session         会话（会被追加压缩条目）
     * @param combined        本轮真正要发送的条目（历史 + 本轮新消息）
     * @param historyCount    {@code combined} 里属于"已落盘历史"的前缀长度。
     *                        切点必须落在这段之内——否则 {@code firstKeptEntryId}
     *                        会指向尚未写入会话的本轮新消息，投影时找不到该 id 而退化
     * @param estimatedTokens 压缩前的估算 token 数
     * @return 压缩结果
     */
    public Outcome compact(LlmCall call, io.github.yinwenyuan28.ailite.session.AgentSession session,
                           List<AgentSessionEntry> combined, int historyCount, int estimatedTokens) {
        int keepRecent = budget.keepRecentTokens();
        int prefixEnd = prefixEndIndex(combined);
        int cut = findCutIndex(combined, prefixEnd, keepRecent, historyCount);
        if (cut < 0) {
            return Outcome.fail("可压缩区过短，无法找到安全的切点");
        }
        // 切点之前必须有内容可摘要。
        if (cut <= prefixEnd) {
            return Outcome.fail("可压缩区为空");
        }

        CompactionEntry previous = lastCompaction(session.getEntries());
        int summaryFrom = prefixEnd;
        String previousSummary = null;
        if (previous != null) {
            previousSummary = previous.getSummary();
            int from = indexOf(session.getEntries(), previous.getFirstKeptEntryId());
            if (from >= 0) {
                summaryFrom = Math.max(prefixEnd, mapToCombinedIndex(combined, session.getEntries(),
                        from));
            }
        }
        if (summaryFrom >= cut) {
            return Outcome.fail("没有新的可摘要内容");
        }

        List<AgentSessionMessage> toSummarize = messagesBetween(combined, summaryFrom, cut);
        if (toSummarize.isEmpty()) {
            return Outcome.fail("可摘要消息为空");
        }

        String summary;
        String summaryModel;
        int summaryTokens;
        try {
            SummaryResult result = summarize(call, previousSummary, toSummarize);
            summary = result.text;
            summaryModel = result.model;
            summaryTokens = result.tokens;
        } catch (RuntimeException ex) {
            // 摘要失败不降级成截断：静默修改历史比报错更难排查。
            return Outcome.fail("摘要生成失败：" + ex.getMessage());
        }
        if (Strings.isBlank(summary)) {
            return Outcome.fail("摘要为空");
        }

        String firstKeptId = firstMessageIdAt(combined, cut);
        CompactionEntry entry = new CompactionEntry(UUID.randomUUID().toString(), summary,
                firstKeptId, estimatedTokens, summaryModel, summaryTokens, new Date());

        List<AgentSessionEntry> entries =
                new ArrayList<AgentSessionEntry>(session.getEntries());
        entries.add(entry);
        session.setEntries(entries);
        return Outcome.ok(entry);
    }

    /**
     * 前缀区边界：数组开头连续 SYSTEM 消息的数量。
     *
     * @param entries 条目
     * @return 前缀区结束位置（不含）
     */
    static int prefixEndIndex(List<AgentSessionEntry> entries) {
        int end = 0;
        for (int i = 0; i < entries.size(); i++) {
            AgentSessionEntry entry = entries.get(i);
            if (entry instanceof AgentSessionMessage
                    && ((AgentSessionMessage) entry).getRole() == LlmRole.SYSTEM) {
                end = i + 1;
            } else {
                break;
            }
        }
        return end;
    }

    /**
     * 从可压缩区末尾向前累计，找到切点。
     *
     * <p>切点必须落在 USER 消息上：投影后的第一条是切点那条 user，
     * 这样发给模型的历史以 user 开头，符合所有供应商的协议要求。</p>
     *
     * <p><b>切点上限是历史边界</b>：{@code combined} 尾部是本轮新消息，它们还没写进
     * 会话。切到那一段会产生指向不存在 id 的 {@code firstKeptEntryId}，
     * 投影时找不到而退化成"压缩条目之后"，等于压缩对本次请求无效。</p>
     *
     * @param entries          条目
     * @param from             可压缩区起点
     * @param keepRecentTokens 需要保留的最近 token 数
     * @param historyCount     已落盘历史在 {@code entries} 里的长度上限
     * @return 切点下标；找不到安全切点返回 -1
     */
    static int findCutIndex(List<AgentSessionEntry> entries, int from, int keepRecentTokens,
                            int historyCount) {
        if (from >= entries.size()) {
            return -1;
        }
        int upperBound = Math.min(historyCount, entries.size());
        if (upperBound <= from) {
            return -1;
        }
        int accumulated = 0;
        int candidate = -1;
        for (int i = upperBound - 1; i >= from; i--) {
            AgentSessionEntry entry = entries.get(i);
            if (entry instanceof AgentSessionMessage) {
                AgentSessionMessage message = (AgentSessionMessage) entry;
                accumulated += estimateMessage(message);
                if (message.getRole() == LlmRole.USER && accumulated >= keepRecentTokens) {
                    candidate = i;
                    break;
                }
            }
        }
        if (candidate < 0) {
            return -1;
        }
        // 切点若等于可压缩区起点，说明没有可摘要内容。
        return candidate <= from ? -1 : candidate;
    }

    private static int estimateMessage(AgentSessionMessage message) {
        int tokens = new TokenEstimator().estimateMessage(message.getText());
        if (message.getThinkingText() != null) {
            tokens += new TokenEstimator().estimate(message.getThinkingText());
        }
        return tokens;
    }

    private static CompactionEntry lastCompaction(List<AgentSessionEntry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof CompactionEntry) {
                return (CompactionEntry) entries.get(i);
            }
        }
        return null;
    }

    private static int indexOf(List<AgentSessionEntry> entries, String id) {
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
     * 把"会话条目里的下标"映射到"合并列表里的下标"。
     *
     * <p>合并列表 = 会话条目 + 本轮新消息，新消息追加在末尾，
     * 因此会话条目的下标在合并列表里保持不变。这个方法存在只是为了把
     * 意图写清楚，避免以后有人改动合并顺序时静默算错切点。</p>
     */
    private static int mapToCombinedIndex(List<AgentSessionEntry> combined,
                                          List<AgentSessionEntry> sessionEntries, int sessionIndex) {
        if (sessionIndex < 0 || sessionIndex >= sessionEntries.size()) {
            return -1;
        }
        String id = sessionEntries.get(sessionIndex).getId();
        int index = indexOf(combined, id);
        return index >= 0 ? index : sessionIndex;
    }

    private static List<AgentSessionMessage> messagesBetween(List<AgentSessionEntry> entries,
                                                             int from, int to) {
        List<AgentSessionMessage> messages = new ArrayList<AgentSessionMessage>();
        for (int i = Math.max(0, from); i < Math.min(to, entries.size()); i++) {
            if (entries.get(i) instanceof AgentSessionMessage) {
                messages.add((AgentSessionMessage) entries.get(i));
            }
        }
        return messages;
    }

    private static String firstMessageIdAt(List<AgentSessionEntry> entries, int from) {
        for (int i = Math.max(0, from); i < entries.size(); i++) {
            if (entries.get(i) instanceof AgentSessionMessage) {
                return entries.get(i).getId();
            }
        }
        return null;
    }

    /**
     * 生成摘要，超长时按消息边界分块迭代。
     */
    private SummaryResult summarize(LlmCall call, String previousSummary,
                                    List<AgentSessionMessage> messages) {
        int window = summaryContextWindow(call);
        int summaryMax = budget.summaryMaxTokens();
        int inputBudget = window - summaryMax - SAFETY_MARGIN_TOKENS;
        if (inputBudget <= 0) {
            // 摘要模型窗口太小/未知：不做分块，交给调用自身失败并报错。
            inputBudget = Integer.MAX_VALUE;
        }
        List<List<AgentSessionMessage>> chunks = chunk(messages, inputBudget);
        String carry = previousSummary;
        String modelLabel = null;
        int totalSummaryTokens = 0;
        for (int i = 0; i < chunks.size(); i++) {
            List<LlmMessage> prompt = new ArrayList<LlmMessage>();
            prompt.add(LlmMessage.system(SYSTEM_PROMPT));
            if (chunks.size() > 1) {
                prompt.add(LlmMessage.system(String.format(
                        CHUNK_PROMPT, Integer.valueOf(i + 1), Integer.valueOf(chunks.size()))));
            }
            if (Strings.isNotBlank(carry)) {
                prompt.add(LlmMessage.system("此前已有摘要（供衔接）：\n" + carry));
            }
            for (AgentSessionMessage message : chunks.get(i)) {
                prompt.add(toLlm(message));
            }
            LlmCall summaryCall = summaryCall(call, prompt);
            LlmResult result = invokeWithTimeout(summaryCall);
            String text = result == null ? null : Strings.defaultString(result.getContent()).trim();
            if (Strings.isBlank(text)) {
                throw new LlmCallException("摘要模型返回空内容");
            }
            carry = text;
            modelLabel = result.getProvider() + "/" + result.getModelId();
            if (result.getUsage() != null) {
                totalSummaryTokens += result.getUsage().getTotalTokens();
            }
        }
        return new SummaryResult(carry, modelLabel, totalSummaryTokens);
    }

    private LlmCall summaryCall(LlmCall call, List<LlmMessage> prompt) {
        AiLiteConfig.Context.Compaction compaction = config.getCompaction();
        LlmCall summaryCall;
        if (Strings.isNotBlank(compaction.getSummaryProvider())
                && Strings.isNotBlank(compaction.getSummaryModel())) {
            summaryCall = LlmCall.explicit(call.getBizId(), compaction.getSummaryProvider(),
                    compaction.getSummaryModel(), "off", prompt);
        } else {
            summaryCall = call.withMessages(prompt);
        }
        summaryCall.maxTokens(Integer.valueOf(budget.summaryMaxTokens()));
        return summaryCall;
    }

    /**
     * 发起一次摘要调用，并施加独立超时。
     *
     * <p><b>为什么用取消句柄而不是 future 超时</b>：摘要走同步 {@code round}，
     * 真正的耗时在 socket 上；只有 {@link LlmCancelHandle} 能中止在途请求。
     * 用调度器到点 cancel，既限制住耗时，也能让传输层关掉连接。</p>
     *
     * <p><b>为什么不绑主 run 的取消</b>：摘要是一次独立、短时的辅助调用。
     * 若绑定主 run，用户取消主请求会连带把已花掉一半钱的摘要也中止，
     * 下次续聊还得重新生成。因此这里用独立句柄，只受 {@code timeout-ms} 约束。</p>
     *
     * @param summaryCall 摘要调用参数
     * @return 模型结果
     * @throws LlmCallException 超时或调用失败
     */
    private LlmResult invokeWithTimeout(LlmCall summaryCall) {
        long timeoutMs = budget.summaryTimeoutMs();
        if (timeoutMs <= 0L) {
            return gateway.round(summaryCall, null);
        }
        final LlmCancelHandle handle = new LlmCancelHandle();
        ScheduledFuture<?> timeoutTask = gateway.retryScheduler().schedule(new Runnable() {
            @Override
            public void run() {
                handle.cancel();
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            return gateway.round(summaryCall, null, handle);
        } finally {
            timeoutTask.cancel(false);
            if (handle.isCancelled()) {
                throw new LlmCallException("摘要调用超时（" + timeoutMs + "ms）");
            }
        }
    }

    /**
     * 摘要模型的输入窗口。优先按摘要模型自己的配置解析，未单独配置时沿用主调用模型。
     */
    private int summaryContextWindow(LlmCall call) {
        AiLiteConfig.Context.Compaction compaction = config.getCompaction();
        LlmCall probe = call;
        if (Strings.isNotBlank(compaction.getSummaryProvider())
                && Strings.isNotBlank(compaction.getSummaryModel())) {
            probe = LlmCall.explicit(call.getBizId(), compaction.getSummaryProvider(),
                    compaction.getSummaryModel(), "off", Collections.<LlmMessage>emptyList());
        }
        try {
            return gateway.resolveFor(probe).getContextWindow();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private List<List<AgentSessionMessage>> chunk(List<AgentSessionMessage> messages, int inputBudget) {
        if (inputBudget == Integer.MAX_VALUE) {
            return Collections.singletonList(messages);
        }
        List<List<AgentSessionMessage>> chunks = new ArrayList<List<AgentSessionMessage>>();
        List<AgentSessionMessage> current = new ArrayList<AgentSessionMessage>();
        int currentTokens = 0;
        for (AgentSessionMessage message : messages) {
            int tokens = estimateMessage(message);
            if (!current.isEmpty() && currentTokens + tokens > inputBudget) {
                chunks.add(current);
                current = new ArrayList<AgentSessionMessage>();
                currentTokens = 0;
            }
            current.add(message);
            currentTokens += tokens;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private static LlmMessage toLlm(AgentSessionMessage message) {
        LlmRole role = message.getRole();
        if (role == LlmRole.SYSTEM) {
            return LlmMessage.system(message.getText());
        }
        if (role == LlmRole.ASSISTANT) {
            return LlmMessage.assistant(message.getText(), message.getToolCalls());
        }
        if (role == LlmRole.TOOL_RESULT) {
            return LlmMessage.toolResult(message.getToolCallId(), message.getText());
        }
        if (role == LlmRole.OTHER) {
            return LlmMessage.other(message.getText());
        }
        return LlmMessage.user(message.getText());
    }

    /**
     * 摘要结果。
     */
    private static final class SummaryResult {

        private final String text;
        private final String model;
        private final int tokens;

        private SummaryResult(String text, String model, int tokens) {
            this.text = text;
            this.model = model;
            this.tokens = tokens;
        }
    }
}
