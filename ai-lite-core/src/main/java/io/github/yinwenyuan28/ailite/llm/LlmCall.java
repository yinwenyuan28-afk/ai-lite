package io.github.yinwenyuan28.ailite.llm;

import java.util.List;

/**
 * 一次模型调用的公共参数：业务 id、模型选择、消息，以及这一次的独立超时。
 *
 * <p>显式模型用 {@link #explicit}，预置组合用 {@link #profile}。工具、轮数上限、
 * 流式回调和取消句柄仍由网关方法接收，不放进这个对象。</p>
 */
public final class LlmCall {

    private final String bizId;
    private final String provider;
    private final String modelId;
    private final String thinkingLevel;
    private final String profile;
    private final List<LlmMessage> messages;
    private Integer connectTimeoutMs;
    private Integer readTimeoutMs;
    private Integer maxTokens;

    private LlmCall(String bizId, String provider, String modelId, String thinkingLevel, String profile,
                    List<LlmMessage> messages, Integer connectTimeoutMs, Integer readTimeoutMs) {
        this.bizId = bizId;
        this.provider = provider;
        this.modelId = modelId;
        this.thinkingLevel = thinkingLevel;
        this.profile = profile;
        this.messages = messages;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * 显式指定供应商、模型和思考档。
     *
     * @param bizId        已登记的业务 id
     * @param provider     供应商 id
     * @param modelId      模型 id
     * @param thinkingLevel 思考档，例如 off、high
     * @param messages     消息列表
     * @return 调用参数
     */
    public static LlmCall explicit(String bizId, String provider, String modelId, String thinkingLevel,
                                   List<LlmMessage> messages) {
        return new LlmCall(bizId, provider, modelId, thinkingLevel, null, messages, null, null);
    }

    /**
     * 使用配置里的预置组合。
     *
     * @param bizId   已登记的业务 id
     * @param profile 配置档名称
     * @param messages 消息列表
     * @return 调用参数
     */
    public static LlmCall profile(String bizId, String profile, List<LlmMessage> messages) {
        return new LlmCall(bizId, null, null, null, profile, messages, null, null);
    }

    /**
     * 只按业务 id 解析。供应商和模型从运行时目录读取，不使用配置文件里的 profile。
     *
     * @param bizId    已登记的业务 id
     * @param messages 消息列表
     * @return 调用参数
     */
    public static LlmCall biz(String bizId, List<LlmMessage> messages) {
        return new LlmCall(bizId, null, null, null, null, messages, null, null);
    }

    /**
     * 设置这一次的连接超时和读取超时，单位毫秒。传 null 表示沿用供应商配置。
     *
     * @param connectTimeoutMs 连接超时
     * @param readTimeoutMs    读取超时
     * @return this
     */
    public LlmCall timeouts(Integer connectTimeoutMs, Integer readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        return this;
    }

    /**
     * 设置这一次的输出 token 上限。传 null 表示沿用模型配置的 maxTokens。
     *
     * <p>存在的理由：压缩摘要需要一个明显小于主调用的上限（{@code summary-max-tokens}），
     * 而模型配置里只允许一个值。没有这个覆盖，摘要请求可能把整个窗口都用来生成摘要。</p>
     *
     * @param maxTokens 输出 token 上限；null 表示不覆盖
     * @return this
     */
    public LlmCall maxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    /**
     * 复制调用参数。用于步骤工厂在创建时做快照，避免调用方后续修改
     * {@link #timeouts(Integer, Integer)} 影响已构造的步骤。
     *
     * @return 新的调用参数，消息列表仍为浅拷贝
     */
    public LlmCall copy() {
        LlmCall copy = new LlmCall(bizId, provider, modelId, thinkingLevel, profile, messages,
                connectTimeoutMs, readTimeoutMs);
        copy.maxTokens = maxTokens;
        return copy;
    }

    /**
     * 复制调用，只替换消息。模型选择、超时与 maxTokens 保持调用当下的值。
     *
     * @param messages 新消息列表
     * @return 新的调用参数
     */
    public LlmCall withMessages(List<LlmMessage> messages) {
        LlmCall copy = new LlmCall(bizId, provider, modelId, thinkingLevel, profile, messages,
                connectTimeoutMs, readTimeoutMs);
        copy.maxTokens = maxTokens;
        return copy;
    }

    public String getBizId() {
        return bizId;
    }

    public String getProvider() {
        return provider;
    }

    public String getModelId() {
        return modelId;
    }

    public String getThinkingLevel() {
        return thinkingLevel;
    }

    public String getProfile() {
        return profile;
    }

    public List<LlmMessage> getMessages() {
        return messages;
    }

    public Integer getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public Integer getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * @return 输出 token 上限覆盖；null 表示沿用模型配置
     */
    public Integer getMaxTokens() {
        return maxTokens;
    }
}
