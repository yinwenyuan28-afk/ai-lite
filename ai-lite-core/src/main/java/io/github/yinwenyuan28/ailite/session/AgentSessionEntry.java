package io.github.yinwenyuan28.ailite.session;

import java.util.Date;

/**
 * 会话条目的公共基类。条目是会话持久化的最小单元，按 {@link #type()} 分派编解码。
 *
 * <p><b>设计意图</b>：会话不再只是"一串消息"，而是"一串带类型的条目"。
 * 消息（{@link AgentSessionMessage}）只是其中一种；压缩摘要、会话元信息、用量记账
 * 都是平级条目，各自有稳定位置。这样做的直接好处是：</p>
 *
 * <ul>
 *   <li>压缩摘要可以追加到条目流的中间位置，原始消息一条不删，随时可还原</li>
 *   <li>新增条目类型不改变 {@code AgentSession} 的结构，也不破坏已落盘数据</li>
 *   <li>读路径按 {@code type} 分发，未知类型跳过即可向前兼容</li>
 * </ul>
 *
 * <p><b>演化承诺</b>：{@code id} 与 {@code type()} 是稳定契约，任何实现都必须给出
 * 非空且会话内唯一的值。{@code createTime} 仅用于可读性，不参与排序判定。</p>
 */
public abstract class AgentSessionEntry {

    private String id;
    private Date createTime;

    /**
     * 供子类反序列化使用。
     */
    protected AgentSessionEntry() {
    }

    /**
     * @param id         条目 id，会话内唯一
     * @param createTime 创建时间，可为 null
     */
    protected AgentSessionEntry(String id, Date createTime) {
        this.id = id;
        this.createTime = createTime;
    }

    /**
     * 条目类型标识，决定 JSON 的 {@code type} 字段与编解码实现。
     *
     * <p>已冻结的取值：{@code message}、{@code compaction}、{@code session_info}、{@code usage}。
     * 业务自定义类型必须使用 {@code x-} 前缀，本库永不解释。</p>
     *
     * @return 非空的类型标识
     */
    public abstract String type();

    /**
     * @return 条目 id，会话内唯一
     */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * @return 创建时间，可为 null
     */
    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
