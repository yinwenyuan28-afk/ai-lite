package io.github.yinwenyuan28.ailite.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.json.AiLiteJson;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmImage;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
import io.github.yinwenyuan28.ailite.llm.LlmTexts;
import io.github.yinwenyuan28.ailite.llm.LlmThinking;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmToolCall;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.LlmUsage;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
import io.github.yinwenyuan28.ailite.util.Strings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Anthropic Messages 适配器。
 *
 * <p>与 OpenAI 协议的主要差异：system 独立字段、鉴权用 x-api-key、
 * 消息 content 是 block 数组、工具结果作为 user 消息的 tool_result block、
 * 流事件是 message_start / content_block_delta / message_delta / message_stop。</p>
 */
public final class AnthropicMessagesTransport implements LlmTransport {

    public static final String API = "anthropic-messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpExecutor http;

    public AnthropicMessagesTransport(HttpExecutor http) {
        if (http == null) {
            throw new LlmCallException("HttpExecutor 不能为空");
        }
        this.http = http;
    }

    @Override
    public String api() {
        return API;
    }

    @Override
    public LlmCompletion complete(final LlmRequest request) {
        ResolvedModel model = request.getModel();
        ObjectNode body = body(request, model);
        Map<String, String> headers = headers(model);
        String url = TransportSupport.joinUrl(model.getBaseUrl(), "/v1/messages");
        HttpRequest httpRequest = new HttpRequest(url, headers, body.toString(),
                request.effectiveConnectTimeoutMs(), request.effectiveReadTimeoutMs(), request.getCancel());

        final AnthropicState state = new AnthropicState();
        final TransportSupport.SseCollector sse = new TransportSupport.SseCollector();
        try {
            http.executeStreaming(httpRequest, new StreamListener() {
                @Override
                public void onLine(String line) throws IOException {
                    String payload = sse.accept(line);
                    if (payload != null) {
                        handleEvent(payload, state, request);
                    }
                }
            });
            String tail = sse.flush();
            if (tail != null) {
                handleEvent(tail, state, request);
            }
        } catch (LlmCallException ex) {
            if (request.isCancelled()) {
                return state.result(LlmStopReason.ABORTED);
            }
            throw ex;
        }
        return state.result(state.stop);
    }

    private ObjectNode body(LlmRequest request, ResolvedModel model) {
        ObjectNode body = TransportSupport.object();
        body.put("model", model.getModelId());
        body.put("max_tokens", model.getMaxTokens());
        body.put("stream", true);
        String system = systemText(request.getMessages());
        if (Strings.isNotBlank(system)) {
            body.put("system", system);
        }
        body.set("messages", messages(request.getMessages()));
        if (TransportSupport.isNotEmptyList(request.getTools())) {
            body.set("tools", tools(request.getTools()));
        }

        boolean thinking = LlmThinking.thinkingEnabled(model.getThinkingLevel());
        if (thinking) {
            applyThinking(body, model);
        } else if (model.isReasoning() && !TransportSupport.offUnsupported(model.getThinkingLevelMap())) {
            ObjectNode thinkingNode = TransportSupport.object();
            thinkingNode.put("type", "disabled");
            body.set("thinking", thinkingNode);
        }
        if (!thinking) {
            TransportSupport.merge(body, model.getSamplerParams());
        } else if (!model.getSamplerParams().isEmpty()) {
            Map<String, Object> filtered = new LinkedHashMap<String, Object>(model.getSamplerParams());
            filtered.remove("temperature");
            TransportSupport.merge(body, filtered);
        }
        TransportSupport.merge(body, model.getExtraBody());
        TransportSupport.merge(body, request.getExtraBody());
        return body;
    }

    private static void applyThinking(ObjectNode body, ResolvedModel model) {
        ObjectNode thinking = TransportSupport.object();
        if ("anthropic-adaptive".equals(model.getThinkingFormat())) {
            thinking.put("type", "adaptive");
        } else {
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", budget(model.getThinkingLevel()));
        }
        body.set("thinking", thinking);
    }

    private static int budget(io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel level) {
        switch (level) {
            case MINIMAL:
                return 1024;
            case LOW:
                return 2048;
            case MEDIUM:
                return 8192;
            case HIGH:
            case XHIGH:
            case MAX:
                return 16384;
            default:
                return 1024;
        }
    }

    private static String systemText(List<LlmMessage> messages) {
        StringBuilder system = new StringBuilder();
        for (LlmMessage message : messages) {
            if (message.getRole() == LlmRole.SYSTEM && Strings.isNotBlank(message.getText())) {
                if (system.length() > 0) {
                    system.append("\n\n");
                }
                system.append(message.getText());
            }
        }
        return system.toString();
    }

    private static ArrayNode messages(List<LlmMessage> messages) {
        List<ObjectNode> out = new ArrayList<ObjectNode>();
        for (LlmMessage message : messages) {
            if (message.getRole() == LlmRole.SYSTEM) {
                continue;
            }
            if (message.getRole() == LlmRole.TOOL_RESULT) {
                ObjectNode block = TransportSupport.object();
                block.put("type", "tool_result");
                block.put("tool_use_id", message.getToolCallId());
                block.put("content", message.getText());
                ArrayNode content = TransportSupport.array();
                content.add(block);
                add(out, "user", content);
                continue;
            }
            if (message.getRole() == LlmRole.ASSISTANT) {
                ArrayNode content = TransportSupport.array();
                if (Strings.isNotEmpty(message.getText())) {
                    ObjectNode text = TransportSupport.object();
                    text.put("type", "text");
                    text.put("text", message.getText());
                    content.add(text);
                }
                for (LlmToolCall call : message.getToolCalls()) {
                    ObjectNode toolUse = TransportSupport.object();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id", call.getId());
                    toolUse.put("name", call.getName());
                    toolUse.set("input", arguments(call.getArgumentsJson()));
                    content.add(toolUse);
                }
                if (content.size() > 0) {
                    add(out, "assistant", content);
                }
                continue;
            }
            ArrayNode content = TransportSupport.array();
            if (Strings.isNotEmpty(message.getText())) {
                ObjectNode text = TransportSupport.object();
                text.put("type", "text");
                text.put("text", message.getText());
                content.add(text);
            }
            for (LlmImage image : message.getImages()) {
                ObjectNode block = TransportSupport.object();
                block.put("type", "image");
                ObjectNode source = TransportSupport.object();
                source.put("type", "base64");
                source.put("media_type", image.getMime());
                source.put("data", image.getData());
                block.set("source", source);
                content.add(block);
            }
            add(out, "user", content);
        }
        ArrayNode array = TransportSupport.array();
        for (ObjectNode item : out) {
            array.add(item);
        }
        return array;
    }

    private static void add(List<ObjectNode> out, String role, ArrayNode content) {
        if (content.size() == 0) {
            return;
        }
        if (!out.isEmpty()) {
            ObjectNode last = out.get(out.size() - 1);
            if (role.equals(last.path("role").asText())) {
                ArrayNode merged = (ArrayNode) last.get("content");
                for (JsonNode block : content) {
                    merged.add(block);
                }
                return;
            }
        }
        ObjectNode item = TransportSupport.object();
        item.put("role", role);
        item.set("content", content);
        out.add(item);
    }

    private static JsonNode arguments(String json) {
        if (Strings.isBlank(json)) {
            return TransportSupport.object();
        }
        try {
            return AiLiteJson.readTree(json);
        } catch (RuntimeException ex) {
            return TransportSupport.object();
        }
    }

    private static ArrayNode tools(List<LlmTool> tools) {
        ArrayNode array = TransportSupport.array();
        for (LlmTool tool : tools) {
            ObjectNode item = TransportSupport.object();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.set("input_schema", TransportSupport.json(tool.parameters()));
            array.add(item);
        }
        return array;
    }

    private static Map<String, String> headers(ResolvedModel model) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        headers.putAll(model.getHeaders());
        if (!containsHeader(headers, "x-api-key")) {
            headers.put("x-api-key", model.getApiKey());
        }
        return headers;
    }

    private static boolean containsHeader(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (name.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private static void handleEvent(String payload, AnthropicState state, LlmRequest request) {
        if ("[DONE]".equals(payload)) {
            return;
        }
        JsonNode event;
        try {
            event = TransportSupport.parse(payload);
        } catch (LlmCallException ex) {
            return;
        }
        String type = TransportSupport.text(event, "type");
        if (type == null) {
            return;
        }
        if ("message_start".equals(type)) {
            JsonNode usage = event.path("message").path("usage");
            state.inputTokens = usage.path("input_tokens").asInt(0);
            state.outputTokens = usage.path("output_tokens").asInt(0);
        } else if ("content_block_start".equals(type)) {
            JsonNode block = event.path("content_block");
            if ("tool_use".equals(TransportSupport.text(block, "type"))) {
                int index = event.path("index").asInt(state.tools.size());
                ToolBuf buf = new ToolBuf();
                buf.id = TransportSupport.text(block, "id");
                buf.name = TransportSupport.text(block, "name");
                JsonNode input = block.path("input");
                if (input.isObject()) {
                    buf.baseInput = input;
                }
                state.tools.put(Integer.valueOf(index), buf);
            }
        } else if ("content_block_delta".equals(type)) {
            JsonNode delta = event.path("delta");
            String deltaType = TransportSupport.text(delta, "type");
            if ("text_delta".equals(deltaType)) {
                String text = TransportSupport.text(delta, "text");
                if (Strings.isNotEmpty(text)) {
                    state.beforeContent(request);
                    state.content.append(text);
                    TransportSupport.emit(request.getOnDelta(), text);
                }
            } else if ("thinking_delta".equals(deltaType)) {
                String text = TransportSupport.text(delta, "thinking");
                if (Strings.isNotEmpty(text)) {
                    state.reasoning.append(text);
                    TransportSupport.emit(request.getOnDelta(), text);
                }
            } else if ("input_json_delta".equals(deltaType)) {
                int index = event.path("index").asInt(0);
                ToolBuf buf = state.tools.get(Integer.valueOf(index));
                if (buf != null) {
                    buf.partial.append(Strings.defaultString(
                            TransportSupport.text(delta, "partial_json")));
                }
            }
        } else if ("content_block_stop".equals(type)) {
            // 参数在 delta 中已经累积，无需额外处理。
        } else if ("message_delta".equals(type)) {
            String stop = TransportSupport.text(event.path("delta"), "stop_reason");
            if (Strings.isNotBlank(stop)) {
                state.stop = TransportSupport.mapAnthropicStop(stop, false);
            }
            JsonNode usage = event.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                state.outputTokens = usage.path("output_tokens").asInt(state.outputTokens);
                state.inputTokens = usage.path("input_tokens").asInt(state.inputTokens);
            }
        } else if ("error".equals(type)) {
            throw new LlmCallException("模型请求失败: " + event.toString());
        }
    }

    private static final class ToolBuf {
        private String id;
        private String name;
        private JsonNode baseInput;
        private final StringBuilder partial = new StringBuilder();

        /**
         * 合并起始 input 与后续 partial_json。partial 可能是完整对象，也可能是省略外层花括号的片段。
         */
        private String arguments() {
            String fragment = partial.toString();
            if (Strings.isBlank(fragment)) {
                return baseInput == null || baseInput.isNull() ? "{}" : baseInput.toString();
            }
            JsonNode parsed = parseFragment(fragment);
            if (parsed == null || !parsed.isObject()) {
                return fragment;
            }
            if (baseInput == null || !baseInput.isObject()) {
                return parsed.toString();
            }
            ObjectNode merged = ((ObjectNode) baseInput).deepCopy();
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = parsed.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                merged.set(field.getKey(), field.getValue());
            }
            return merged.toString();
        }

        private static JsonNode parseFragment(String text) {
            String value = text.trim();
            try {
                return AiLiteJson.readTree(value);
            } catch (RuntimeException ignored) {
                // 继续尝试补外层花括号。
            }
            String candidate = value.startsWith(",") ? value.substring(1) : value;
            try {
                return AiLiteJson.readTree("{" + candidate + "}");
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static final class AnthropicState {
        private final StringBuilder reasoning = new StringBuilder();
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolBuf> tools = new TreeMap<Integer, ToolBuf>();
        private boolean blankEmitted;
        private int inputTokens;
        private int outputTokens;
        private LlmStopReason stop = LlmStopReason.STOP;

        private void beforeContent(LlmRequest request) {
            if (reasoning.length() > 0 && !blankEmitted) {
                TransportSupport.emit(request.getOnDelta(), "\n\n");
                blankEmitted = true;
            }
        }

        private LlmCompletion result(LlmStopReason forced) {
            List<LlmToolCall> calls = new ArrayList<LlmToolCall>();
            for (ToolBuf buf : tools.values()) {
                calls.add(new LlmToolCall(buf.id, buf.name, buf.arguments()));
            }
            LlmStopReason effective = forced;
            if (!calls.isEmpty()) {
                effective = forced == LlmStopReason.ABORTED
                        ? LlmStopReason.ABORTED : LlmStopReason.TOOL_USE;
            }
            return new LlmCompletion(LlmTexts.splice(reasoning.toString(), content.toString()),
                    Strings.isBlank(reasoning.toString()) ? null : reasoning.toString(),
                    effective, LlmUsage.of(inputTokens, outputTokens, inputTokens + outputTokens), calls);
        }
    }
}
