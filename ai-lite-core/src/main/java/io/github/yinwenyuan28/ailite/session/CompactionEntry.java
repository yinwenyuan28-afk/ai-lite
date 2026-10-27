package io.github.yinwenyuan28.ailite.session;

import java.util.Date;

/**
 * 压缩条目。表示"从某条消息之前的历史已被摘要替代"。
 *
 * <p><b>设计意图</b>：压缩不能销毁原始消息。本条目只追加一条"读取时从这里开始看"的
 * 指针，原始消息全部保留在条目数组里。投影（{@code AgentSession.projectEntries()}）
 * 遇到它时把 {@link #getSummary()} 当成一条 SYSTEM 消息放在保留区之前，从
 * {@link #getFirstKeptEntryId()} 指向的条目往后取消息。</p>
 *
 * <p><b>为什么要在条目里冗余 summary 而不是只存指针</b>：摘要是一次实实在在的 LLM 调用，
 * 花了钱。存进条目后，后续每一轮续聊都直接复用，不再重算；而原始消息保留是为了
 * "关掉压缩立刻恢复完整历史"这种可逆性。</p>
 *
 * <p><b>多次压缩</b>：投影只认数组里最后一条压缩条目；更早的压缩条目仍然保留，
 * 供审计与回放使用。这样重复压缩的输入可以从上一次的 {@code firstKeptEntryId}
 * 开始，不会每次都从数组头摘要。</p>
 */
public final class CompactionEntry extends AgentSessionEntry {

    /**
     * 类型标识，1.0 后冻结。
     */
    public static final String TYPE = "compaction";

    private final String summary;
    private final String firstKeptEntryId;
    private final int tokensBefore;
    private final String summaryModel;
    private final int summaryTokens;

    /**
     * @param id               条目 id
     * @param summary          摘要正文
     * @param firstKeptEntryId 保留区第一条消息的 id，压缩后从这里往后取
     * @param tokensBefore     压缩前的估算 token 数，用于观测与不变式校验
     * @param summaryModel     生成摘要所用模型，可为 null
     * @param summaryTokens    摘要自身消耗的 token 数，未知时传 0
     */
    public CompactionEntry(String id, String summary, String firstKeptEntryId, int tokensBefore,
                           String summaryModel, int summaryTokens) {
        this(id, summary, firstKeptEntryId, tokensBefore, summaryModel, summaryTokens, null);
    }

    /**
     * @param id               条目 id
     * @param summary          摘要正文
     * @param firstKeptEntryId 保留区第一条消息的 id
     * @param tokensBefore     压缩前的估算 token 数
     * @param summaryModel     摘要模型，可为 null
     * @param summaryTokens    摘要消耗 token 数
     * @param createTime       创建时间
     */
    public CompactionEntry(String id, String summary, String firstKeptEntryId, int tokensBefore,
                           String summaryModel, int summaryTokens, Date createTime) {
        super(id, createTime);
        this.summary = summary == null ? "" : summary;
        this.firstKeptEntryId = firstKeptEntryId;
        this.tokensBefore = tokensBefore;
        this.summaryModel = summaryModel;
        this.summaryTokens = summaryTokens;
    }

    @Override
    public String type() {
        return TYPE;
    }

    /**
     * @return 摘要正文，永不为 null
     */
    public String getSummary() {
        return summary;
    }

    /**
     * @return 保留区第一条消息的 id；可能为 null（表示保留区为空）
     */
    public String getFirstKeptEntryId() {
        return firstKeptEntryId;
    }

    /**
     * @return 压缩前的估算 token 数
     */
    public int getTokensBefore() {
        return tokensBefore;
    }

    /**
     * @return 生成摘要所用模型
     */
    public String getSummaryModel() {
        return summaryModel;
    }

    /**
     * @return 摘要消耗的 token 数
     */
    public int getSummaryTokens() {
        return summaryTokens;
    }
}
