package io.github.yinwenyuan28.ailite.session;

import java.util.Date;

/**
 * 单轮用量条目。记录一次模型调用的实际计费口径。
 *
 * <p><b>设计意图</b>：{@code AgentSessionMessage} 只有 7 个字段，没有用量位；
 * 会话级累计又不能告诉你"钱花在哪一轮、哪个模型"。把用量做成独立条目后：
 * 可以按 {@code kind} 区分主调用与压缩摘要调用；可以在不改表结构的前提下
 * 按 provider/model 做成本归因；也可以在将来做"只导出用量"的按类型查询。</p>
 *
 * <p><b>投影时跳过</b>：本条目不会出现在发给模型的消息里。</p>
 *
 * <p><b>口径</b>：被取消或失败的轮次不产生本条目（会话累计不记，账单表另记）。</p>
 */
public final class UsageEntry extends AgentSessionEntry {

    /**
     * 类型标识，1.0 后冻结。
     */
    public static final String TYPE = "usage";

    /**
     * 调用种类：常规续聊轮次。
     */
    public static final String KIND_CHAT = "chat";

    /**
     * 调用种类：压缩摘要调用。
     */
    public static final String KIND_COMPACTION = "compaction";

    private final String kind;
    private final String provider;
    private final String model;
    private final int inputTokens;
    private final int outputTokens;
    private final int totalTokens;

    /**
     * @param id           条目 id
     * @param kind         调用种类，见 {@link #KIND_CHAT} / {@link #KIND_COMPACTION}
     * @param provider     供应商 id，可为 null
     * @param model        模型 id，可为 null
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     * @param totalTokens  合计 token 数
     */
    public UsageEntry(String id, String kind, String provider, String model, int inputTokens,
                      int outputTokens, int totalTokens) {
        this(id, kind, provider, model, inputTokens, outputTokens, totalTokens, null);
    }

    /**
     * @param id           条目 id
     * @param kind         调用种类
     * @param provider     供应商 id
     * @param model        模型 id
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     * @param totalTokens  合计 token 数
     * @param createTime   创建时间
     */
    public UsageEntry(String id, String kind, String provider, String model, int inputTokens,
                      int outputTokens, int totalTokens, Date createTime) {
        super(id, createTime);
        this.kind = kind;
        this.provider = provider;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
    }

    @Override
    public String type() {
        return TYPE;
    }

    /**
     * @return 调用种类，可为 null
     */
    public String getKind() {
        return kind;
    }

    /**
     * @return 供应商 id，可为 null
     */
    public String getProvider() {
        return provider;
    }

    /**
     * @return 模型 id，可为 null
     */
    public String getModel() {
        return model;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }
}
