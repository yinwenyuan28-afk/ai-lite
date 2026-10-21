package io.github.yinwenyuan28.ailite.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.yinwenyuan28.ailite.http.AsyncHttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmImage;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmRole;
import io.github.yinwenyuan28.ailite.llm.LlmStopReason;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * OpenAI Responses 适配器。
 *
 * <p>与 Chat Completions 的差异集中在：system 走 {@code instructions}，
 * 输出上限字段是 {@code max_output_tokens}，输入是带类型的 item 数组，
 * 思考开关走 {@code reasoning.effort}，流事件也是另一套名字。</p>
 */
public final class OpenAiResponsesTransport implements LlmTransport {

    public static final String API = "openai-responses";
    private static final int MIN_OUTPUT_TOKENS = 16;

    private final HttpExecutor http;

    public OpenAiResponsesTransport(HttpExecutor http) {
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
        String url = TransportSupport.joinUrl(model.getBaseUrl(), "/responses");
        HttpRequest httpRequest = new HttpRequest(url, headers, body.toString(),
                request.effectiveConnectTimeoutMs(), request.effectiveReadTimeoutMs(), request.getCancel());

        final ResponsesState state = new ResponsesState();
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

    /**
     * Responses 协议本身始终是流式事件，所以异步版本直接在 HTTP 回调线程解析事件。
     */
    @Override
    public CompletableFuture<LlmCompletion> completeAsync(final LlmRequest request) {
        if (!(http instanceof AsyncHttpExecutor)) {
            return LlmTransport.super.completeAsync(request);
        }
        ResolvedModel model = request.getModel();
        ObjectNode body = body(request, model);
        Map<String, String> headers = headers(model);
        String url = TransportSupport.joinUrl(model.getBaseUrl(), "/responses");
        HttpRequest httpRequest = new HttpRequest(url, headers, body.toString(),
                request.effectiveConnectTimeoutMs(), request.effectiveReadTimeoutMs(), request.getCancel());

        final ResponsesState state = new ResponsesState();
        final TransportSupport.SseCollector sse = new TransportSupport.SseCollector();
        return ((AsyncHttpExecutor) http).executeStreamingAsync(httpRequest, new StreamListener() {
            @Override
            public void onLine(String line) {
                String payload = sse.accept(line);
                if (payload != null) {
                    handleEvent(payload, state, request);
                }
            }
        }).handle((ignored, error) -> {
            if (error != null) {
                if (request.isCancelled()) {
                    return state.result(LlmStopReason.ABORTED);
                }
                throw new CompletionException(unwrap(error));
            }
            String tail = sse.flush();
            if (tail != null) {
                handleEvent(tail, state, request);
            }
            return state.result(state.stop);
        });
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private ObjectNode body(LlmRequest request, ResolvedModel model) {
        String instructions = systemText(request.getMessages());
        ObjectNode body = TransportSupport.object();
        body.put("model", model.getModelId());
        body.put("stream", true);
        body.put("store", false);
        body.put("max_output_tokens", Math.max(MIN_OUTPUT_TOKENS, model.getMaxTokens()));
        if (Strings.isNotBlank(instructions)) {
            body.put("instructions", instructions);
        }
        body.set("input", input(request.getMessages()));
        if (TransportSupport.isNotEmptyList(request.getTools())) {
            body.set("tools", tools(request.getTools()));
        }
        TransportSupport.merge(body, model.getSamplerParams());
        applyReasoning(body, model);
        TransportSupport.merge(body, model.getExtraBody());
        TransportSupport.merge(body, request.getExtraBody());
        return body;
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

    private static ArrayNode input(List<LlmMessage> messages) {
        ArrayNode input = TransportSupport.array();
        int index = 0;
        for (LlmMessage message : messages) {
            if (message.getRole() == LlmRole.SYSTEM) {
                continue;
            }
            if (message.getRole() == LlmRole.TOOL_RESULT) {
                ObjectNode out = TransportSupport.object();
                out.put("type", "function_call_output");
                out.put("call_id", message.getToolCallId());
                out.put("output", message.getText());
                input.add(out);
                continue;
            }
            if (message.getRole() == LlmRole.ASSISTANT) {
                if (Strings.isNotEmpty(message.getText())) {
                    ObjectNode textItem = TransportSupport.object();
                    textItem.put("type", "message");
                    textItem.put("role", "assistant");
                    textItem.put("status", "completed");
                    textItem.put("id", "msg_lite_" + index);
                    ArrayNode content = TransportSupport.array();
                    ObjectNode text = TransportSupport.object();
                    text.put("type", "output_text");
                    text.put("text", message.getText());
                    text.set("annotations", TransportSupport.array());
                    content.add(text);
                    textItem.set("content", content);
                    input.add(textItem);
                    index++;
                }
                for (LlmToolCall call : message.getToolCalls()) {
                    ObjectNode callItem = TransportSupport.object();
                    callItem.put("type", "function_call");
                    callItem.put("call_id", call.getId());
                    callItem.put("name", call.getName());
                    callItem.put("arguments", call.getArgumentsJson() == null ? "{}" : call.getArgumentsJson());
                    input.add(callItem);
                }
                continue;
            }
            ObjectNode user = TransportSupport.object();
            user.put("role", "user");
            user.set("content", userContent(message));
            input.add(user);
            index++;
        }
        return input;
    }

    private static ArrayNode userContent(LlmMessage message) {
        ArrayNode content = TransportSupport.array();
        if (Strings.isNotEmpty(message.getText())) {
            ObjectNode text = TransportSupport.object();
            text.put("type", "input_text");
            text.put("text", message.getText());
            content.add(text);
        }
        for (LlmImage image : message.getImages()) {
            ObjectNode part = TransportSupport.object();
            part.put("type", "input_image");
            part.put("detail", "auto");
            part.put("image_url", TransportSupport.imageDataUrl(image));
            content.add(part);
        }
        return content;
    }

    private static ArrayNode tools(List<LlmTool> tools) {
        ArrayNode array = TransportSupport.array();
        for (LlmTool tool : tools) {
            ObjectNode item = TransportSupport.object();
            item.put("type", "function");
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.set("parameters", TransportSupport.json(tool.parameters()));
            array.add(item);
        }
        return array;
    }

    private static void applyReasoning(ObjectNode body, ResolvedModel model) {
        if (!model.isReasoning()) {
            return;
        }
        boolean enabled = LlmThinking.thinkingEnabled(model.getThinkingLevel());
        Map<String, String> map = model.getThinkingLevelMap();
        if (enabled) {
            body.remove("temperature");
            ObjectNode reasoning = TransportSupport.object();
            reasoning.put("effort", TransportSupport.mappedThinkingLevel(
                    map, model.getThinkingLevel().wire()));
            reasoning.put("summary", "auto");
            body.set("reasoning", reasoning);
            ArrayNode include = TransportSupport.array();
            include.add("reasoning.encrypted_content");
            body.set("include", include);
        } else if (!TransportSupport.offUnsupported(map)) {
            ObjectNode reasoning = TransportSupport.object();
            String off = map == null ? null : map.get("off");
            reasoning.put("effort", Strings.isBlank(off) ? "none" : off);
            body.set("reasoning", reasoning);
        }
    }

    private static Map<String, String> headers(ResolvedModel model) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.putAll(model.getHeaders());
        if (!containsHeader(headers, "Authorization")) {
            headers.put("Authorization", "Bearer " + model.getApiKey());
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

    private static void handleEvent(String payload, ResponsesState state, LlmRequest request) {
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
        if ("response.output_text.delta".equals(type)) {
            String delta = TransportSupport.text(event, "delta");
            if (Strings.isNotEmpty(delta)) {
                state.beforeContent(request);
                state.content.append(delta);
                TransportSupport.emit(request.getOnDelta(), delta);
            }
        } else if ("response.reasoning_summary_text.delta".equals(type)
                || "response.reasoning_text.delta".equals(type)) {
            String delta = TransportSupport.text(event, "delta");
            if (Strings.isNotEmpty(delta)) {
                state.reasoning.append(delta);
                TransportSupport.emit(request.getOnDelta(), delta);
            }
        } else if ("response.output_item.added".equals(type)) {
            JsonNode item = event.path("item");
            if ("function_call".equals(TransportSupport.text(item, "type"))) {
                int index = event.path("output_index").asInt(state.tools.size());
                state.tools.put(Integer.valueOf(index), ToolBuf.from(item));
            }
        } else if ("response.function_call_arguments.delta".equals(type)) {
            int index = event.path("output_index").asInt(0);
            ToolBuf buf = state.tools.get(Integer.valueOf(index));
            if (buf != null) {
                String delta = TransportSupport.text(event, "delta");
                if (Strings.isNotEmpty(delta)) {
                    buf.arguments.append(delta);
                }
            }
        } else if ("response.function_call_arguments.done".equals(type)) {
            int index = event.path("output_index").asInt(0);
            ToolBuf buf = state.tools.get(Integer.valueOf(index));
            if (buf != null) {
                buf.arguments = new StringBuilder(Strings.defaultString(
                        TransportSupport.text(event, "arguments")));
            }
        } else if ("response.output_item.done".equals(type)) {
            JsonNode item = event.path("item");
            String itemType = TransportSupport.text(item, "type");
            if ("function_call".equals(itemType)) {
                int index = event.path("output_index").asInt(0);
                ToolBuf buf = state.tools.get(Integer.valueOf(index));
                if (buf == null) {
                    buf = ToolBuf.from(item);
                    state.tools.put(Integer.valueOf(index), buf);
                }
                String arguments = TransportSupport.text(item, "arguments");
                if (Strings.isNotBlank(arguments)) {
                    buf.arguments = new StringBuilder(arguments);
                }
            } else if ("message".equals(itemType) && state.content.length() == 0) {
                JsonNode content = item.path("content");
                if (content.isArray()) {
                    for (JsonNode part : content) {
                        if ("output_text".equals(TransportSupport.text(part, "type"))) {
                            state.content.append(TransportSupport.text(part, "text"));
                        }
                    }
                }
            }
        } else if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
            state.sawTerminal = true;
            JsonNode response = event.path("response");
            state.usage = TransportSupport.usage(response.path("usage"));
            if ("response.incomplete".equals(type)) {
                String reason = TransportSupport.text(response.path("incomplete_details"), "reason");
                state.stop = Strings.defaultString(reason).contains("max_output_tokens")
                        ? LlmStopReason.LENGTH : LlmStopReason.STOP;
            } else {
                state.stop = LlmStopReason.STOP;
            }
        } else if ("response.failed".equals(type)) {
            JsonNode error = event.path("response").path("error");
            throw new LlmCallException("模型请求失败: " + error.toString());
        } else if ("error".equals(type)) {
            throw new LlmCallException("模型请求失败: " + event.toString());
        }
    }

    private static final class ToolBuf {
        private String id;
        private String name;
        private StringBuilder arguments = new StringBuilder();

        private static ToolBuf from(JsonNode item) {
            ToolBuf buf = new ToolBuf();
            String callId = TransportSupport.text(item, "call_id");
            buf.id = Strings.isNotBlank(callId) ? callId : TransportSupport.text(item, "id");
            buf.name = TransportSupport.text(item, "name");
            String arguments = TransportSupport.text(item, "arguments");
            if (Strings.isNotBlank(arguments)) {
                buf.arguments.append(arguments);
            }
            return buf;
        }
    }

    private static final class ResponsesState {
        private final StringBuilder reasoning = new StringBuilder();
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolBuf> tools = new TreeMap<Integer, ToolBuf>();
        private boolean blankEmitted;
        private boolean sawTerminal;
        private LlmUsage usage = LlmUsage.zero();
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
                calls.add(new LlmToolCall(buf.id, buf.name,
                        buf.arguments.length() == 0 ? "{}" : buf.arguments.toString()));
            }
            LlmStopReason effective = forced;
            if (!calls.isEmpty()) {
                effective = forced == LlmStopReason.ABORTED
                        ? LlmStopReason.ABORTED : LlmStopReason.TOOL_USE;
            }
            return new LlmCompletion(content.toString(),
                    Strings.isBlank(reasoning.toString()) ? null : reasoning.toString(),
                    effective, usage, calls);
        }
    }
}
