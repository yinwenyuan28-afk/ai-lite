package io.github.yinwenyuan28.ailite.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.yinwenyuan28.ailite.json.AiLiteJson;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话 JSON 编解码。
 *
 * <p><b>条目化格式</b>：{@code messages} 字段存的是条目数组，每条带 {@code type}。
 * 已冻结类型：{@code message}、{@code compaction}、{@code session_info}、{@code usage}。
 * 用注册表按 {@code type} 分发，新增类型不改主流程。</p>
 *
 * <p><b>两类兼容必须分开看</b>：</p>
 * <ul>
 *   <li><b>向前兼容（保留）</b>：遇到未知 {@code type}（例如未来版本新增的类型，或
 *       业务自定义的 {@code x-} 条目）跳过并打 DEBUG 日志，不报错。这保证旧 jar
 *       读到新数据不会崩。</li>
 *   <li><b>老数据兼容（不做）</b>：缺 {@code type} 字段视为非法数据并抛异常。
 *       项目尚未正式使用，不需要兼容 0.1.x 存的纯消息数组。</li>
 * </ul>
 *
 * <p>其余格式约定与旧版一致：{@code updatedAt} 是毫秒时间戳，role 用枚举名，
 * toolCalls 的 argumentsJson 保持字符串。未知字段忽略，便于以后加字段。</p>
 */
public final class AgentSessionCodec {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionCodec.class);

    /**
     * 条目字段名。
     */
    static final String FIELD_TYPE = "type";

    private static final Map<String, AgentSessionEntryCodec> CODECS =
            new LinkedHashMap<String, AgentSessionEntryCodec>();

    static {
        register(new MessageCodec());
        register(new CompactionCodec());
        register(new SessionInfoCodec());
        register(new UsageCodec());
    }

    private AgentSessionCodec() {
    }

    private static void register(AgentSessionEntryCodec codec) {
        CODECS.put(codec.type(), codec);
    }

    /**
     * 已注册的条目类型标识，按注册顺序。
     *
     * @return 不可变集合
     */
    public static Set<String> registeredTypes() {
        return Collections.unmodifiableSet(new HashSet<String>(CODECS.keySet()));
    }

    /**
     * 编码消息数组。只接受消息条目，供消息级 API 使用。
     *
     * @param messages 消息列表
     * @return JSON 字符串
     */
    public static String encodeMessages(List<AgentSessionMessage> messages) {
        List<AgentSessionEntry> entries = new ArrayList<AgentSessionEntry>();
        if (messages != null) {
            entries.addAll(messages);
        }
        return toEntryArray(entries).toString();
    }

    /**
     * 解码消息数组。未知类型跳过；返回结果只含消息。
     *
     * @param json JSON 字符串
     * @return 消息列表
     */
    public static List<AgentSessionMessage> decodeMessages(String json) {
        List<AgentSessionMessage> messages = new ArrayList<AgentSessionMessage>();
        for (AgentSessionEntry entry : decodeEntries(json)) {
            if (entry instanceof AgentSessionMessage) {
                messages.add((AgentSessionMessage) entry);
            }
        }
        return messages;
    }

    /**
     * 编码全量条目数组。
     *
     * @param entries 条目列表
     * @return JSON 字符串
     */
    public static String encodeEntries(List<AgentSessionEntry> entries) {
        return toEntryArray(entries).toString();
    }

    /**
     * 解码全量条目数组。未知类型跳过，缺 type 抛异常。
     *
     * @param json JSON 字符串
     * @return 条目列表
     * @throws LlmCallException JSON 非法或缺 type
     */
    public static List<AgentSessionEntry> decodeEntries(String json) {
        if (Strings.isBlank(json)) {
            return new ArrayList<AgentSessionEntry>();
        }
        JsonNode root;
        try {
            root = AiLiteJson.readTree(json);
        } catch (RuntimeException ex) {
            throw new LlmCallException("会话消息 JSON 无法解析", ex);
        }
        if (root == null || !root.isArray()) {
            throw new LlmCallException("会话消息 JSON 无法解析");
        }
        return fromEntryArray(root);
    }

    public static String encodeSession(AgentSession session) {
        if (session == null) {
            throw new LlmCallException("会话不能为空");
        }
        ObjectNode root = AiLiteJson.mapper().createObjectNode();
        root.put("userId", session.getUserId());
        root.put("sessionId", session.getSessionId());
        root.put("bizId", session.getBizId());
        root.put("version", session.getVersion());
        if (session.getUpdatedAt() != null) {
            root.put("updatedAt", session.getUpdatedAt().getTime());
        }
        if (session.getTitle() != null) {
            root.put("title", session.getTitle());
        }
        root.put("totalTokens", session.getTotalTokens());
        // 必须写全量条目：只写消息会静默丢掉压缩条目。
        root.set("messages", toEntryArray(session.getEntries()));
        return root.toString();
    }

    public static AgentSession decodeSession(String json) {
        if (Strings.isBlank(json)) {
            return null;
        }
        JsonNode root;
        try {
            root = AiLiteJson.readTree(json);
        } catch (RuntimeException ex) {
            throw new LlmCallException("会话 JSON 无法解析", ex);
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        AgentSession session = new AgentSession();
        session.setUserId(text(root, "userId"));
        session.setSessionId(text(root, "sessionId"));
        session.setBizId(text(root, "bizId"));
        session.setVersion(root.path("version").asLong(0L));
        session.setTotalTokens(root.path("totalTokens").asLong(0L));
        session.setTitle(text(root, "title"));
        long updatedAt = root.path("updatedAt").asLong(0L);
        if (updatedAt > 0L) {
            session.setUpdatedAt(new Date(updatedAt));
        }
        JsonNode messages = root.path("messages");
        if (!messages.isMissingNode() && !messages.isNull()) {
            if (!messages.isArray()) {
                throw new LlmCallException("会话消息 JSON 无法解析");
            }
            session.setEntries(fromEntryArray(messages));
        }
        return session;
    }

    /**
     * 校验消息 id 非空且会话内唯一。压缩条目里的 {@code firstKeptEntryId}
     * 也指向这些 id，因此这条校验同时也是压缩切点的完整性前提。
     *
     * @param messages 消息列表
     */
    public static void validateMessageIds(List<AgentSessionMessage> messages) {
        if (messages == null) {
            return;
        }
        Set<String> seen = new HashSet<String>();
        for (AgentSessionMessage message : messages) {
            validateEntryId(message, seen);
        }
    }

    /**
     * 校验条目 id 非空且会话内唯一。
     *
     * @param entries 条目列表
     */
    public static void validateEntryIds(List<AgentSessionEntry> entries) {
        if (entries == null) {
            return;
        }
        Set<String> seen = new HashSet<String>();
        for (AgentSessionEntry entry : entries) {
            validateEntryId(entry, seen);
        }
    }

    private static void validateEntryId(AgentSessionEntry entry, Set<String> seen) {
        if (entry == null) {
            throw new LlmCallException("会话消息不能为空");
        }
        if (Strings.isBlank(entry.getId())) {
            throw new LlmCallException("会话消息缺少 id");
        }
        if (!seen.add(entry.getId())) {
            throw new LlmCallException("会话消息 id 重复");
        }
    }

    private static ArrayNode toEntryArray(List<AgentSessionEntry> entries) {
        ArrayNode array = AiLiteJson.mapper().createArrayNode();
        if (entries == null) {
            return array;
        }
        for (AgentSessionEntry entry : entries) {
            if (entry == null) {
                throw new LlmCallException("会话消息不能为空");
            }
            AgentSessionEntryCodec codec = CODECS.get(entry.type());
            if (codec == null) {
                // 写入未知类型属于编程错误：本库不允许写出自己读不回来的数据。
                throw new LlmCallException("未知的会话条目类型: " + entry.type());
            }
            ObjectNode item = AiLiteJson.mapper().createObjectNode();
            item.put("type", entry.type());
            item.put("id", entry.getId());
            if (entry.getCreateTime() != null) {
                item.put("createTime", entry.getCreateTime().getTime());
            }
            codec.write(item, entry);
            array.add(item);
        }
        return array;
    }

    private static List<AgentSessionEntry> fromEntryArray(JsonNode array) {
        List<AgentSessionEntry> out = new ArrayList<AgentSessionEntry>();
        for (JsonNode node : array) {
            AgentSessionEntry entry = fromEntry(node);
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * @return 解析出的条目；未知类型返回 null（调用方跳过）
     */
    private static AgentSessionEntry fromEntry(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new LlmCallException("会话消息不能为空");
        }
        String type = text(node, FIELD_TYPE);
        if (Strings.isBlank(type)) {
            // 不兼容老格式：缺 type 说明数据不是本版本写的，静默当成 message 会掩盖问题。
            throw new LlmCallException("会话条目缺少 type");
        }
        AgentSessionEntryCodec codec = CODECS.get(type);
        if (codec == null) {
            if (log.isDebugEnabled()) {
                log.debug("跳过未知会话条目类型 type={} id={}", type, text(node, "id"));
            }
            return null;
        }
        long createMillis = node.path("createTime").asLong(0L);
        Date createTime = createMillis > 0L ? new Date(createMillis) : null;
        return codec.read(node, text(node, "id"), createTime);
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static int intValue(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asInt(defaultValue);
    }

    // ------------------------------------------------------------ codecs

    /**
     * 消息条目编解码。字段与旧版一致。
     */
    private static final class MessageCodec implements AgentSessionEntryCodec {

        @Override
        public String type() {
            return AgentSessionMessage.TYPE;
        }

        @Override
        public void write(ObjectNode node, AgentSessionEntry entry) {
            AgentSessionMessage message = (AgentSessionMessage) entry;
            node.put("role", message.getRole() == null ? null : message.getRole().name());
            node.put("text", message.getText());
            if (message.getThinkingText() != null) {
                node.put("thinkingText", message.getThinkingText());
            }
            if (message.getThinkingSignature() != null) {
                node.put("thinkingSignature", message.getThinkingSignature());
            }
            if (message.getToolCallId() != null) {
                node.put("toolCallId", message.getToolCallId());
            }
            if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                ArrayNode calls = AiLiteJson.mapper().createArrayNode();
                for (LlmToolCall call : message.getToolCalls()) {
                    ObjectNode callNode = AiLiteJson.mapper().createObjectNode();
                    callNode.put("id", call.getId());
                    callNode.put("name", call.getName());
                    callNode.put("argumentsJson", call.getArgumentsJson());
                    calls.add(callNode);
                }
                node.set("toolCalls", calls);
            }
        }

        @Override
        public AgentSessionEntry read(JsonNode node, String id, Date createTime) {
            String roleName = text(node, "role");
            if (Strings.isBlank(roleName)) {
                throw new LlmCallException("会话消息缺少 role");
            }
            LlmRole role;
            try {
                role = LlmRole.valueOf(roleName);
            } catch (RuntimeException ex) {
                throw new LlmCallException("会话消息 role 非法");
            }
            List<LlmToolCall> toolCalls = null;
            JsonNode calls = node.path("toolCalls");
            if (calls.isArray() && calls.size() > 0) {
                toolCalls = new ArrayList<LlmToolCall>();
                for (JsonNode call : calls) {
                    toolCalls.add(new LlmToolCall(text(call, "id"), text(call, "name"),
                            text(call, "argumentsJson")));
                }
            }
            return AgentSessionMessage.of(id, role, text(node, "text"), text(node, "thinkingText"),
                    text(node, "thinkingSignature"), toolCalls, text(node, "toolCallId"));
        }
    }

    /**
     * 压缩条目编解码。
     */
    private static final class CompactionCodec implements AgentSessionEntryCodec {

        @Override
        public String type() {
            return CompactionEntry.TYPE;
        }

        @Override
        public void write(ObjectNode node, AgentSessionEntry entry) {
            CompactionEntry compaction = (CompactionEntry) entry;
            node.put("summary", compaction.getSummary());
            if (compaction.getFirstKeptEntryId() != null) {
                node.put("firstKeptEntryId", compaction.getFirstKeptEntryId());
            }
            node.put("tokensBefore", compaction.getTokensBefore());
            if (compaction.getSummaryModel() != null) {
                node.put("summaryModel", compaction.getSummaryModel());
            }
            node.put("summaryTokens", compaction.getSummaryTokens());
        }

        @Override
        public AgentSessionEntry read(JsonNode node, String id, Date createTime) {
            return new CompactionEntry(id, text(node, "summary"),
                    text(node, "firstKeptEntryId"), intValue(node, "tokensBefore", 0),
                    text(node, "summaryModel"), intValue(node, "summaryTokens", 0), createTime);
        }
    }

    /**
     * 会话信息条目编解码。
     */
    private static final class SessionInfoCodec implements AgentSessionEntryCodec {

        @Override
        public String type() {
            return SessionInfoEntry.TYPE;
        }

        @Override
        public void write(ObjectNode node, AgentSessionEntry entry) {
            SessionInfoEntry info = (SessionInfoEntry) entry;
            if (info.getName() != null) {
                node.put("name", info.getName());
            }
        }

        @Override
        public AgentSessionEntry read(JsonNode node, String id, Date createTime) {
            return new SessionInfoEntry(id, text(node, "name"), createTime);
        }
    }

    /**
     * 用量条目编解码。
     */
    private static final class UsageCodec implements AgentSessionEntryCodec {

        @Override
        public String type() {
            return UsageEntry.TYPE;
        }

        @Override
        public void write(ObjectNode node, AgentSessionEntry entry) {
            UsageEntry usage = (UsageEntry) entry;
            if (usage.getKind() != null) {
                node.put("kind", usage.getKind());
            }
            if (usage.getProvider() != null) {
                node.put("provider", usage.getProvider());
            }
            if (usage.getModel() != null) {
                node.put("model", usage.getModel());
            }
            node.put("inputTokens", usage.getInputTokens());
            node.put("outputTokens", usage.getOutputTokens());
            node.put("totalTokens", usage.getTotalTokens());
        }

        @Override
        public AgentSessionEntry read(JsonNode node, String id, Date createTime) {
            return new UsageEntry(id, text(node, "kind"), text(node, "provider"),
                    text(node, "model"), intValue(node, "inputTokens", 0),
                    intValue(node, "outputTokens", 0), intValue(node, "totalTokens", 0),
                    createTime);
        }
    }
}
