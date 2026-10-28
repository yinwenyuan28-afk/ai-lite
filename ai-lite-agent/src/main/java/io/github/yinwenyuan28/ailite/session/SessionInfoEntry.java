package io.github.yinwenyuan28.ailite.session;

import java.util.Date;

/**
 * 会话元信息条目。承载不进入模型上下文的会话属性。
 *
 * <p><b>设计意图</b>：会话级属性（标题、标签等）与对话内容生命周期不同——
 * 它们可能被用户随时改名，也不应参与压缩。放在条目流里而不是会话字段上，
 * 是为了让"改标题"也能留痕，并且不需要为每个属性加一列。</p>
 *
 * <p><b>投影时跳过</b>：本条目不会出现在发给模型的消息里。</p>
 */
public final class SessionInfoEntry extends AgentSessionEntry {

    /**
     * 类型标识，1.0 后冻结。
     */
    public static final String TYPE = "session_info";

    private final String name;

    /**
     * @param id   条目 id
     * @param name 会话标题，可为 null
     */
    public SessionInfoEntry(String id, String name) {
        this(id, name, null);
    }

    /**
     * @param id         条目 id
     * @param name       会话标题
     * @param createTime 创建时间
     */
    public SessionInfoEntry(String id, String name, Date createTime) {
        super(id, createTime);
        this.name = name;
    }

    @Override
    public String type() {
        return TYPE;
    }

    /**
     * @return 会话标题，可为 null
     */
    public String getName() {
        return name;
    }
}
