package io.github.yinwenyuan28.ailite.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Date;

/**
 * 单种会话条目的 JSON 编解码器。
 *
 * <p><b>设计意图</b>：条目类型会增长，用 {@code switch(type)} 会让每次新增都要改
 * 编解码主流程，容易漏改。改成注册表后，新增类型只需实现本接口并注册，
 * 主流程完全不感知具体类型。</p>
 *
 * <p><b>约定</b>：{@link #write} 只负责写类型特有的字段，{@code id}、{@code type}、
 * {@code createTime} 由主流程统一处理；{@link #read} 收到的节点已确认 {@code type}
 * 与本编解码器匹配。</p>
 */
interface AgentSessionEntryCodec {

    /**
     * @return 本编解码器负责的类型标识
     */
    String type();

    /**
     * 写类型特有字段。
     *
     * @param node  目标 JSON 对象
     * @param entry 条目
     */
    void write(ObjectNode node, AgentSessionEntry entry);

    /**
     * 读类型特有字段。
     *
     * @param node        JSON 对象
     * @param id          已解析的条目 id
     * @param createTime  已解析的创建时间，可为 null
     * @return 条目实例
     */
    AgentSessionEntry read(JsonNode node, String id, Date createTime);
}
