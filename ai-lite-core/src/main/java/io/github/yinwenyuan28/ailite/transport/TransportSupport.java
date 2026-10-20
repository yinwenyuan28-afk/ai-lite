package io.github.yinwenyuan28.ailite.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.yinwenyuan28.ailite.json.AiLiteJson;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmImage;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.util.Strings;

import java.util.List;
import java.util.Map;

/**
 * 三个协议适配器共用的 JSON、SSE 和字段处理工具。
 */
final class TransportSupport {

    private TransportSupport() {
    }

    static ObjectNode object() {
        return AiLiteJson.mapper().createObjectNode();
    }

    static ArrayNode array() {
        return AiLiteJson.mapper().createArrayNode();
    }

    static JsonNode json(Object value) {
        return AiLiteJson.mapper().valueToTree(value);
    }

    static JsonNode textNode(String text) {
        return AiLiteJson.mapper().getNodeFactory().textNode(text == null ? "" : text);
    }

    static String joinUrl(String baseUrl, String path) {
        String base = Strings.trimToNull(baseUrl);
        if (base == null) {
            throw new LlmCallException("模型地址未配置");
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (path == null || path.isEmpty()) {
            return base;
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

    /**
     * 深合并：Map 递归合并，其他类型后者覆盖前者。
     */
    @SuppressWarnings("unchecked")
    static void merge(ObjectNode target, Map<String, Object> source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Object value = entry.getValue();
            JsonNode existing = target.get(entry.getKey());
            if (value instanceof Map && existing instanceof ObjectNode) {
                merge((ObjectNode) existing, (Map<String, Object>) value);
            } else {
                target.set(entry.getKey(), AiLiteJson.mapper().valueToTree(value));
            }
        }
    }

    static void putIfNotBlank(ObjectNode target, String field, String value) {
        if (Strings.isNotBlank(value)) {
            target.put(field, value);
        }
    }

    static String sseData(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("data:")) {
            return null;
        }
        String payload = trimmed.substring(5);
        if (payload.startsWith(" ")) {
            payload = payload.substring(1);
        }
        return payload.isEmpty() ? null : payload;
    }

    static JsonNode parse(String json) {
        try {
            JsonNode node = AiLiteJson.readTree(json);
            if (node == null) {
                throw new LlmCallException("模型响应无法解析");
            }
            return node;
        } catch (LlmCallException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new LlmCallException("模型响应无法解析", ex);
        }
    }

    /**
     * 按 SSE 规范把连续多个 data: 行合并成一个事件，空行表示事件结束。
     */
    static final class SseCollector {

        private final StringBuilder data = new StringBuilder();
        private boolean hasData;

        String accept(String line) {
            if (line == null) {
                return null;
            }
            if (line.trim().isEmpty()) {
                return flush();
            }
            if (line.startsWith("data:")) {
                String chunk = line.substring(5);
                if (chunk.startsWith(" ")) {
                    chunk = chunk.substring(1);
                }
                if (hasData) {
                    data.append('\n');
                }
                data.append(chunk);
                hasData = true;
            }
            return null;
        }

        String flush() {
            if (!hasData) {
                return null;
            }
            String value = data.toString();
            data.setLength(0);
            hasData = false;
            return value.isEmpty() ? null : value;
        }
    }

    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static String firstText(JsonNode node, String first, String second) {
        String value = text(node, first);
        return Strings.isNotBlank(value) ? value : text(node, second);
    }

    static String imageDataUrl(LlmImage image) {
        return "data:" + image.getMime() + ";base64," + image.getData();
    }

    static LlmUsage usage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return LlmUsage.zero();
        }
        int input = usage.path("input_tokens").asInt(usage.path("prompt_tokens").asInt(0));
        int output = usage.path("output_tokens").asInt(usage.path("completion_tokens").asInt(0));
        int total = usage.path("total_tokens").asInt(input + output);
        return LlmUsage.of(input, output, total);
    }

    static LlmStopReason mapOpenAiStop(String finishReason, boolean hasToolCalls) {
        if (hasToolCalls) {
            return LlmStopReason.TOOL_USE;
        }
        if ("length".equals(finishReason)) {
            return LlmStopReason.LENGTH;
        }
        if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) {
            return LlmStopReason.TOOL_USE;
        }
        return LlmStopReason.STOP;
    }

    static LlmStopReason mapAnthropicStop(String stopReason, boolean hasToolCalls) {
        if (hasToolCalls) {
            return LlmStopReason.TOOL_USE;
        }
        if ("max_tokens".equals(stopReason)) {
            return LlmStopReason.LENGTH;
        }
        if ("tool_use".equals(stopReason)) {
            return LlmStopReason.TOOL_USE;
        }
        return LlmStopReason.STOP;
    }

    static String mappedThinkingLevel(Map<String, String> levelMap, String level) {
        if (levelMap == null) {
            return level;
        }
        String mapped = levelMap.get(level);
        return mapped == null ? level : mapped;
    }

    static boolean offUnsupported(Map<String, String> levelMap) {
        return levelMap != null && levelMap.containsKey("off") && levelMap.get("off") == null;
    }

    static String brief(Throwable ex) {
        String message = ex == null ? null : ex.getMessage();
        if (message == null) {
            return "模型请求失败";
        }
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) : oneLine;
    }

    static void emit(java.util.function.Consumer<String> onDelta, String text) {
        if (onDelta != null && Strings.isNotEmpty(text)) {
            onDelta.accept(text);
        }
    }

    static boolean isNotEmptyList(List<?> list) {
        return list != null && !list.isEmpty();
    }
}
