package io.github.yinwenyuan28.ailite.session;

import java.util.Date;

/**
 * 会话列表用的只读摘要。
 *
 * <p><b>为什么不直接返回 {@link AgentSession}</b>：列表页不需要完整条目数组，
 * 一个大会话的 {@code MESSAGES_JSON} 可能有数 MB。返回摘要能让"列出我的会话"
 * 这类请求的开销与消息体量解耦。</p>
 *
 * <p><b>字段口径</b>：{@code messageCount} 只数消息条目（不含压缩/用量）；
 * {@code totalTokens} 只统计成功轮次，与 {@link AgentSession#getTotalTokens()} 一致。</p>
 */
public final class AgentSessionSummary {

    private final String userId;
    private final String sessionId;
    private final String bizId;
    private final String title;
    private final int messageCount;
    private final long totalTokens;
    private final Date updatedAt;

    /**
     * @param userId       用户 id
     * @param sessionId    会话 id
     * @param bizId        业务 id，可为 null
     * @param title        会话标题，可为 null
     * @param messageCount 消息条目数
     * @param totalTokens  累计 token 数
     * @param updatedAt    最后更新时间，可为 null
     */
    public AgentSessionSummary(String userId, String sessionId, String bizId, String title,
                               int messageCount, long totalTokens, Date updatedAt) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.bizId = bizId;
        this.title = title;
        this.messageCount = messageCount;
        this.totalTokens = totalTokens;
        this.updatedAt = updatedAt;
    }

    /**
     * 从完整会话构造摘要，消息条数与 token 数由条目现算。
     *
     * @param session 会话
     * @return 摘要
     */
    public static AgentSessionSummary of(AgentSession session) {
        if (session == null) {
            return null;
        }
        return new AgentSessionSummary(session.getUserId(), session.getSessionId(),
                session.getBizId(), session.getTitle(), session.getMessages().size(),
                session.sumUsageTokens(), session.getUpdatedAt());
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getBizId() {
        return bizId;
    }

    public String getTitle() {
        return title;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }
}
