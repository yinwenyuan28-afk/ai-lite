package io.github.yinwenyuan28.ailite.session;

import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRole;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 持久化会话快照。version 为调用方读到的当前值；成功 save 后存储侧为 version+1。
 *
 * <p><b>两套视图</b>：内部唯一事实源是 {@link #getEntries()}（全量条目，含压缩摘要、
 * 用量记录等）。{@link #getMessages()} 是过滤视图，只返回消息条目，语义与旧版完全一致，
 * 这样既保住了二进制兼容，也不用改动几十处既有调用点。</p>
 *
 * <p><b>写消息必须用 {@link #setMessages(List)}</b>：它会走位置感知合并，
 * 保留非消息条目在原位。直接对 {@code getMessages()} 的返回值调 {@code add}
 * 不会影响会话（该返回是只读副本），这是刻意的——避免绕过合并逻辑丢掉压缩条目。</p>
 */
public final class AgentSession {

    private String userId;
    private String sessionId;
    private String bizId;
    private List<AgentSessionEntry> entries = new ArrayList<AgentSessionEntry>();
    private long version;
    private Date updatedAt;
    /** 会话级累计 token 数，只统计成功轮次（含压缩摘要调用）。 */
    private long totalTokens;
    /** 会话标题，取首条用户消息的截断形式；供会话列举展示。 */
    private String title;

    public AgentSession() {
    }

    /**
     * 旧构造入口：只给消息，等价于把消息数组当作全部条目。
     *
     * @param userId    用户 id
     * @param sessionId 会话 id
     * @param bizId     业务 id
     * @param messages  消息列表
     * @param version   版本
     * @param updatedAt 更新时间
     */
    public AgentSession(String userId, String sessionId, String bizId,
                        List<AgentSessionMessage> messages, long version, Date updatedAt) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.bizId = bizId;
        setMessages(messages);
        this.version = version;
        this.updatedAt = updatedAt;
    }

    /**
     * 续聊默认：只映射 role/text/tool*，丢弃 thinkingText 与 id。
     *
     * @return 运行时消息
     */
    public List<LlmMessage> toLlmMessages() {
        return toLlmMessages(false);
    }

    /**
     * @param includeThinking true 时把 thinkingText / thinkingSignature 放进独立字段
     * @return 运行时消息
     */
    public List<LlmMessage> toLlmMessages(boolean includeThinking) {
        return messagesToLlm(getMessages(), includeThinking);
    }

    public static List<LlmMessage> messagesToLlm(List<AgentSessionMessage> source, boolean includeThinking) {
        List<LlmMessage> out = new ArrayList<LlmMessage>();
        if (source == null) {
            return out;
        }
        for (AgentSessionMessage m : source) {
            if (m == null || m.getRole() == null) {
                continue;
            }
            LlmRole role = m.getRole();
            if (role == LlmRole.SYSTEM) {
                out.add(LlmMessage.system(m.getText()));
            } else if (role == LlmRole.USER) {
                out.add(LlmMessage.user(m.getText()));
            } else if (role == LlmRole.ASSISTANT) {
                if (includeThinking) {
                    out.add(LlmMessage.assistant(m.getText(), m.getThinkingText(),
                            m.getThinkingSignature(), m.getToolCalls()));
                } else {
                    out.add(LlmMessage.assistant(m.getText(), m.getToolCalls()));
                }
            } else if (role == LlmRole.TOOL_RESULT) {
                out.add(LlmMessage.toolResult(m.getToolCallId(), m.getText()));
            } else if (role == LlmRole.OTHER) {
                out.add(LlmMessage.other(m.getText()));
            }
        }
        return out;
    }

    /**
     * 复制一份会话，条目列表独立。用于内存存储隔离调用方修改。
     *
     * <p>条目本身是不可变值对象，因此只复制列表容器，不深拷贝元素。</p>
     *
     * @param source 源会话
     * @return 新会话
     */
    public static AgentSession copyOf(AgentSession source) {
        if (source == null) {
            return null;
        }
        AgentSession copy = new AgentSession();
        copy.setUserId(source.getUserId());
        copy.setSessionId(source.getSessionId());
        copy.setBizId(source.getBizId());
        copy.setEntries(source.getEntries());
        copy.setVersion(source.getVersion());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.totalTokens = source.totalTokens;
        copy.title = source.title;
        return copy;
    }

    /**
     * 累加用量条目里的 token 数。
     *
     * <p>只从 {@link UsageEntry} 汇总，不重复遍历消息：用量以条目为准，
     * 这样"取消/失败轮次不记"的口径天然成立（那些轮次压根不产生条目）。</p>
     *
     * @return 会话累计 token 数
     */
    public long sumUsageTokens() {
        long total = 0L;
        for (AgentSessionEntry entry : entries) {
            if (entry instanceof UsageEntry) {
                total += ((UsageEntry) entry).getTotalTokens();
            }
        }
        return total;
    }

    /**
     * 把一批消息合入现有条目，非消息条目保持原位。
     *
     * <p><b>按 id 对齐，不按位置对齐</b>。位置对齐在删除中间消息时会把后续消息
     * 向左挤过压缩条目，导致"本应保留的消息跑到摘要前面"，语义就坏了。
     * 按 id 对齐的语义清晰且与 {@code appendMessage}/{@code replaceMessage}/
     * {@code deleteMessage} 三个动作天然吻合：</p>
     *
     * <ul>
     *   <li>id 在新列表里 → 原位替换成新实例（内容可能变了）</li>
     *   <li>id 不在新列表里 → 视为删除，从自己的槽位移除，其他消息不动</li>
     *   <li>新 id 不在旧列表里 → 追加到末尾，保持新列表内顺序</li>
     * </ul>
     *
     * <p><b>为什么位置稳定重要</b>：投影依赖"数组开头连续 SYSTEM = 前缀区"以及
     * 压缩条目的相对位置。任何把非消息条目提前或把已保留消息跨界移动的写法都会破坏它。</p>
     *
     * @param newMessages 新消息序列，必须非 null
     * @throws LlmCallException 新消息含 null
     */
    public void mergeMessages(List<AgentSessionMessage> newMessages) {
        if (newMessages == null) {
            throw new LlmCallException("会话消息不能为空");
        }
        Map<String, AgentSessionMessage> incoming = new LinkedHashMap<String, AgentSessionMessage>();
        for (AgentSessionMessage message : newMessages) {
            if (message == null) {
                throw new LlmCallException("会话消息不能为空");
            }
            String id = message.getId();
            if (id != null && id.length() > 0) {
                incoming.put(id, message);
            }
        }

        List<AgentSessionEntry> merged = new ArrayList<AgentSessionEntry>(
                entries.size() + newMessages.size());
        Set<String> placed = new HashSet<String>();
        for (AgentSessionEntry entry : entries) {
            if (!(entry instanceof AgentSessionMessage)) {
                // 压缩摘要等条目原样留在原位，不参与消息对齐。
                merged.add(entry);
                continue;
            }
            String existingId = entry.getId();
            if (existingId == null || existingId.length() == 0) {
                // 无 id 的旧消息无法对齐；保留原样，由保存前的 id 校验负责报错。
                merged.add(entry);
                continue;
            }
            AgentSessionMessage replacement = incoming.get(existingId);
            if (replacement != null) {
                merged.add(replacement);
                placed.add(existingId);
            }
            // replacement 为 null 表示该消息已被删除，跳过即完成移除。
        }
        for (AgentSessionMessage message : newMessages) {
            String id = message.getId();
            if (id == null || id.length() == 0 || !placed.contains(id)) {
                merged.add(message);
            }
        }
        this.entries = merged;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    /**
     * 全量条目视图，含消息以外类型。返回只读副本，修改请走
     * {@link #setEntries(List)} 或 {@link #mergeMessages(List)}。
     *
     * @return 条目列表，永不为 null
     */
    public List<AgentSessionEntry> getEntries() {
        return entries;
    }

    /**
     * 整包替换条目。用于编解码与压缩逻辑写入，业务代码通常不需要调用。
     *
     * @param entries 新条目列表，null 视为空
     */
    public void setEntries(List<AgentSessionEntry> entries) {
        this.entries = entries == null
                ? new ArrayList<AgentSessionEntry>()
                : new ArrayList<AgentSessionEntry>(entries);
    }

    /**
     * 只返回消息条目，语义与旧版一致。
     *
     * <p>返回列表是只读副本：拿到它做查询没问题，但修改它不会影响会话。
     * 要写消息请用 {@link #setMessages(List)}，否则会绕过位置感知合并。</p>
     *
     * @return 消息列表，永不为 null
     */
    public List<AgentSessionMessage> getMessages() {
        List<AgentSessionMessage> messages = new ArrayList<AgentSessionMessage>();
        for (AgentSessionEntry entry : entries) {
            if (entry instanceof AgentSessionMessage) {
                messages.add((AgentSessionMessage) entry);
            }
        }
        return messages;
    }

    /**
     * 按位置合并写入消息，保留压缩等非消息条目。
     *
     * @param messages 新消息序列，null 视为空
     */
    public void setMessages(List<AgentSessionMessage> messages) {
        mergeMessages(messages == null ? new ArrayList<AgentSessionMessage>() : messages);
    }

    /**
     * @return 非消息条目数量，用于观测与测试
     */
    public int nonMessageEntryCount() {
        int count = 0;
        for (AgentSessionEntry entry : entries) {
            if (!(entry instanceof AgentSessionMessage)) {
                count++;
            }
        }
        return count;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * @return 会话级累计 token 数（只含成功轮次）
     */
    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    /**
     * @return 会话标题，可为 null
     */
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
