package io.github.yinwenyuan28.ailite.session;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import io.github.yinwenyuan28.ailite.internal.session.AgentSessionStoreSupport;

/**
 * Agent 会话存储 SPI。
 *
 * <p><b>演化承诺（A 组：允许生长）</b>：会话存储是业务扩展点，未来可能新增
 * 分页查询、按用户批量删除、迁移辅助方法。届时<b>只允许以 default 方法新增</b>，
 * 默认实现应抛 {@link UnsupportedOperationException} 或委托
 * {@link AgentSessionStoreSupport} 的通用实现；不得新增抽象方法，
 * 不得修改或删除现有方法。</p>
 *
 * <p>本接口已有的 4 个 default 消息级方法就是这种演化方式的样板：新增能力
 * 通过 {@code AgentSessionStoreSupport} 在默认方法里实现，自定义存储只实现
 * 8 个核心抽象方法即可。</p>
 */
public interface AgentSessionStore {

    Optional<AgentSession> find(String userId, String sessionId);

    /**
     * 整包替换 messages。入参 version 为当前读到的值；成功后存储侧为 version+1。
     * version=0 且无行时可创建（允许 messages 为空）。
     */
    void save(AgentSession session);

    void delete(String userId, String sessionId);

    boolean exists(String userId, String sessionId);

    default Optional<AgentSessionMessage> findMessage(String userId, String sessionId, String messageId) {
        return AgentSessionStoreSupport.findMessage(this, userId, sessionId, messageId);
    }

    default void appendMessage(String userId, String sessionId, AgentSessionMessage message) {
        AgentSessionStoreSupport.appendMessage(this, userId, sessionId, message);
    }

    default void replaceMessage(String userId, String sessionId, AgentSessionMessage message) {
        AgentSessionStoreSupport.replaceMessage(this, userId, sessionId, message);
    }

    default void deleteMessage(String userId, String sessionId, String messageId) {
        AgentSessionStoreSupport.deleteMessage(this, userId, sessionId, messageId);
    }

    /**
     * 按用户分页列出会话摘要，按更新时间倒序。
     *
     * <p><b>默认实现返回空列表</b>（不是抛异常）：这样旧的第三方实现升级后仍能启动，
     * 只是不支持列举。要判断是否支持，先调 {@link #supportsListing()}。</p>
     *
     * <p><b>分页用 offset 而不只是 limit</b>：{@code updatedAt} 相同的情况很常见
     * （同一批导入），只有 limit 会导致翻页丢行。实现必须附加第二排序键
     * {@code sessionId} 才能稳定。</p>
     *
     * @param userId 用户 id
     * @param offset 跳过条数，负数按 0 处理
     * @param limit  最多返回条数，小于等于 0 表示不限制
     * @return 会话摘要列表，永不为 null
     */
    default List<AgentSessionSummary> listByUser(String userId, int offset, int limit) {
        return Collections.emptyList();
    }

    /**
     * 是否支持 {@link #listByUser(String, int, int)}。
     *
     * @return 默认 false；实现了列举的存储应覆盖为 true
     */
    default boolean supportsListing() {
        return false;
    }

    /**
     * 尝试抢占指定会话的压缩租约，用于多节点去重。
     *
     * <p><b>失败方的行为是"跳过压缩、直接用现有投影发请求"</b>，不是报错：
     * 压缩是尽力而为的优化，抢不到租约说明别人正在压，等它压完下一轮自然变短。</p>
     *
     * <p><b>租约不是正确性依赖</b>：两个节点都压也只是多一条摘要条目，数据不会损坏
     * （追加语义）。租约只是省钱的手段。</p>
     *
     * <p>默认实现恒返回 true（无租约概念，单机场景不做限制）。</p>
     *
     * @param userId      用户 id
     * @param sessionId   会话 id
     * @param leaseMillis 租约时长毫秒；小于等于 0 表示用实现默认值
     * @return 是否抢到
     */
    default boolean tryAcquireCompaction(String userId, String sessionId, long leaseMillis) {
        return true;
    }

    /**
     * 释放压缩租约。未抢到过也应能安全调用（幂等）。
     *
     * @param userId    用户 id
     * @param sessionId 会话 id
     */
    default void releaseCompaction(String userId, String sessionId) {
    }
}
