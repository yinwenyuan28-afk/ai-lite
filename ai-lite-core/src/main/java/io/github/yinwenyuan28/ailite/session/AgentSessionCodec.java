package io.github.yinwenyuan28.ailite.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.yinwenyuan28.ailite.json.AiLiteJson;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.util.Strings;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话 JSON 编解码。
 *
 * <p>格式与旧版保持兼容：{@code updatedAt} 是毫秒时间戳，role 用枚举名，
 * toolCalls 的 argumentsJson 保持字符串。未知字段忽略，便于以后加字段。</p>
 */
public final class AgentSessionCodec {

    private AgentSessionCodec() {
    }

    public static String encodeMessages(List<AgentSessionMessage> messages) {
        return toMessageArray(messages).toString();
    }

    public static List<AgentSessionMessage> decodeMessages(String json) {
        if (Strings.isBlank(json)) {
            return new ArrayList<AgentSessionMessage>();
        }
        JsonNode root;
        try {
            root = AiLiteJson.readTree(json);
        } catch (RuntimeException ex) {
            throw new LlmCallException("会话消息 JSON 无法解析", ex);
        }
        if (!root.isArray()) {
            throw new LlmCallException("会话消息 JSON 无法解析");
        }
        return fromMessageArray(root);
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
        root.set("messages", toMessageArray(session.getMessages()));
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
        long updatedAt = root.path("updatedAt").asLong(0L);
        if (updatedAt > 0L) {
            session.setUpdatedAt(new Date(updatedAt));
        }
        JsonNode messages = root.path("messages");
        if (!messages.isMissingNode() && !messages.isNull()) {
            if (!messages.isArray()) {
                throw new LlmCallException("会话消息 JSON 无法解析");
            }
            session.setMessages(fromMessageArray(messages));
        }
        return session;
    }

    public static void validateMessageIds(List<AgentSessionMessage> messages) {
        if (messages == null) {
            return;
        }
        Set<String> seen = new HashSet<String>();
        for (AgentSessionMessage message : messages) {
            if (message == null) {
                throw new LlmCallException("会话消息不能为空");
            }
            if (Strings.isBlank(message.getId())) {
                throw new LlmCallException("会话消息缺少 id");
            }
            if (!seen.add(message.getId())) {
                throw new LlmCallException("会话消息 id 重复");
            }
        }
    }

    private static ArrayNode toMessageArray(List<AgentSessionMessage> messages) {
        ArrayNode array = AiLiteJson.mapper().createArrayNode();
        if (messages == null) {
            return array;
        }
        for (AgentSessionMessage message : messages) {
            if (message == null) {
                throw new LlmCallException("会话消息不能为空");
            }
            ObjectNode item = AiLiteJson.mapper().createObjectNode();
            item.put("id", message.getId());
            item.put("role", message.getRole() == null ? null : message.getRole().name());
            item.put("text", message.getText());
            if (message.getThinkingText() != null) {
                item.put("thinkingText", message.getThinkingText());
            }
            if (message.getThinkingSignature() != null) {
                item.put("thinkingSignature", message.getThinkingSignature());
            }
            if (message.getToolCallId() != null) {
                item.put("toolCallId", message.getToolCallId());
            }
            if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
                ArrayNode calls = AiLiteJson.mapper().createArrayNode();
                for (LlmToolCall call : message.getToolCalls()) {
                    ObjectNode node = AiLiteJson.mapper().createObjectNode();
                    node.put("id", call.getId());
                    node.put("name", call.getName());
                    node.put("argumentsJson", call.getArgumentsJson());
                    calls.add(node);
                }
                item.set("toolCalls", calls);
            }
            array.add(item);
        }
        return array;
    }

    private static List<AgentSessionMessage> fromMessageArray(JsonNode array) {
        List<AgentSessionMessage> out = new ArrayList<AgentSessionMessage>();
        for (JsonNode node : array) {
            out.add(fromMessage(node));
        }
        return out;
    }

    private static AgentSessionMessage fromMessage(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new LlmCallException("会话消息不能为空");
        }
        String id = text(node, "id");
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

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
