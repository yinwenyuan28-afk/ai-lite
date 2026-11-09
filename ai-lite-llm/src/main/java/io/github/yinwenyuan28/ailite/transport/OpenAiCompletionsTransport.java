package io.github.yinwenyuan28.ailite.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.yinwenyuan28.ailite.http.AsyncHttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.StreamListener;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmImage;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
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
 * OpenAI Chat Completions 适配器。
 *
 * <p>覆盖 OpenAI、DeepSeek、vLLM、Ollama、Qwen 等兼容端点。思考参数按
 * {@code thinking-format} 写入不同字段，避免在代码里按供应商名字分支。</p>
 */
public final class OpenAiCompletionsTransport implements LlmTransport {

    public static final String API = "openai-completions";

    private final HttpExecutor http;

    public OpenAiCompletionsTransport(HttpExecutor http) {
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
    public LlmCompletion complete(LlmRequest request) {
        ResolvedModel model = request.getModel();
        ObjectNode body = body(request, model);
        Map<String, String> headers = headers(request, model);
        String url = TransportSupport.joinUrl(model.getBaseUrl(), "/chat/completions");
        HttpRequest httpRequest = new HttpRequest(url, headers, body.toString(),
                request.effectiveConnectTimeoutMs(), request.effectiveReadTimeoutMs(), request.getCancel());
        if (request.isStream()) {
            return streaming(request, httpRequest);
        }
        HttpResult result = http.execute(httpRequest);
        return parseBlocking(result.getBody());
    }

    /**
     * 非流式走 OkHttp enqueue，等待响应期间不占线程；流式则在回调线程逐行解析。
     */
    @Override
    public CompletableFuture<LlmCompletion> completeAsync(final LlmRequest request) {
        if (!(http instanceof AsyncHttpExecutor)) {
            return LlmTransport.super.completeAsync(request);
        }
        ResolvedModel model = request.getModel();
        ObjectNode body = body(request, model);
        Map<String, String> headers = headers(request, model);
        String url = TransportSupport.joinUrl(model.getBaseUrl(), "/chat/completions");
        HttpRequest httpRequest = new HttpRequest(url, headers, body.toString(),
                request.effectiveConnectTimeoutMs(), request.effectiveReadTimeoutMs(), request.getCancel());
        final AsyncHttpExecutor async = (AsyncHttpExecutor) http;
        if (!request.isStream()) {
            return async.executeAsync(httpRequest).thenApply(result -> parseBlocking(result.getBody()));
        }

        final State state = new State();
        final RawFallback raw = new RawFallback();
        final TransportSupport.SseCollector sse = new TransportSupport.SseCollector();
        return async.executeStreamingAsync(httpRequest, new StreamListener() {
            @Override
            public void onLine(String line) {
                raw.accept(line);
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
            if (!state.sawData && raw.hasContent()) {
                return parseBlocking(raw.text());
            }
            return state.result();
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
        ObjectNode body = TransportSupport.object();
        body.put("model", model.getModelId());
        body.put("max_tokens", request.effectiveMaxTokens());
        body.put("stream", request.isStream());
        body.set("messages", messages(request.getMessages(), model));
        if (request.isStream()) {
            ObjectNode options = TransportSupport.object();
            options.put("include_usage", true);
            body.set("stream_options", options);
        }
        if (TransportSupport.isNotEmptyList(request.getTools())) {
            body.set("tools", tools(request.getTools()));
        }
        TransportSupport.merge(body, model.getSamplerParams());
        applyThinking(body, model);
        TransportSupport.merge(body, model.getExtraBody());
        TransportSupport.merge(body, request.getExtraBody());
        return body;
    }

    private static void applyThinking(ObjectNode body, ResolvedModel model) {
        if (!model.isReasoning()) {
            return;
        }
        boolean enabled = LlmThinking.thinkingEnabled(model.getThinkingLevel());
        String level = model.getThinkingLevel().wire();
        String format = model.getThinkingFormat();
        Map<String, String> map = model.getThinkingLevelMap();

        if ("none".equals(format)) {
            return;
        }
        if ("chat-template".equals(format)) {
            ObjectNode kwargs = TransportSupport.object();
            kwargs.put("enable_thinking", enabled);
            body.set("chat_template_kwargs", kwargs);
            return;
        }
        if ("qwen".equals(format)) {
            body.put("enable_thinking", enabled);
            return;
        }
        if ("deepseek".equals(format)) {
            if (enabled) {
                ObjectNode thinking = TransportSupport.object();
                thinking.put("type", "enabled");
                body.set("thinking", thinking);
                body.put("reasoning_effort",
                        TransportSupport.mappedThinkingLevel(map, level));
            } else if (!TransportSupport.offUnsupported(map)) {
                ObjectNode thinking = TransportSupport.object();
                thinking.put("type", "disabled");
                body.set("thinking", thinking);
            }
            return;
        }
        // reasoning-effort 及默认分支
        if (enabled) {
            body.put("reasoning_effort", TransportSupport.mappedThinkingLevel(map, level));
        } else {
            String off = map == null ? null : map.get("off");
            if (Strings.isNotBlank(off)) {
                body.put("reasoning_effort", off);
            }
        }
    }

    private static ArrayNode messages(List<LlmMessage> messages, ResolvedModel model) {
        ArrayNode array = TransportSupport.array();
        for (LlmMessage message : messages) {
            ObjectNode item = TransportSupport.object();
            switch (message.getRole()) {
                case SYSTEM:
                    item.put("role", "system");
                    item.put("content", message.getText());
                    break;
                case USER:
                case OTHER:
                    item.put("role", "user");
                    item.set("content", userContent(message));
                    break;
                case ASSISTANT:
                    item.put("role", "assistant");
                    item.put("content", message.getText());
                    if (supportsReasoningContent(model.getThinkingFormat())
                            && Strings.isNotBlank(message.getThinkingText())) {
                        item.put("reasoning_content", message.getThinkingText());
                    }
                    if (!message.getToolCalls().isEmpty()) {
                        item.set("tool_calls", toolCallsJson(message.getToolCalls()));
                    }
                    break;
                case TOOL_RESULT:
                    item.put("role", "tool");
                    item.put("tool_call_id", message.getToolCallId());
                    item.put("content", message.getText());
                    break;
                default:
                    item.put("role", "user");
                    item.put("content", message.getText());
                    break;
            }
            array.add(item);
        }
        return array;
    }

    private static JsonNode userContent(LlmMessage message) {
        if (message.getImages().isEmpty()) {
            return TransportSupport.textNode(message.getText());
        }
        ArrayNode parts = TransportSupport.array();
        if (Strings.isNotEmpty(message.getText())) {
            ObjectNode text = TransportSupport.object();
            text.put("type", "text");
            text.put("text", message.getText());
            parts.add(text);
        }
        for (LlmImage image : message.getImages()) {
            ObjectNode part = TransportSupport.object();
            part.put("type", "image_url");
            ObjectNode imageUrl = TransportSupport.object();
            imageUrl.put("url", TransportSupport.imageDataUrl(image));
            part.set("image_url", imageUrl);
            parts.add(part);
        }
        return parts;
    }

    /**
     * 只有兼容 reasoning_content 的协议才回传推理；OpenAI 官方 reasoning-effort
     * 不接受该字段，避免把历史推理塞回请求体。
     */
    private static boolean supportsReasoningContent(String thinkingFormat) {
        return "deepseek".equals(thinkingFormat)
                || "qwen".equals(thinkingFormat)
                || "chat-template".equals(thinkingFormat);
    }

    private static ArrayNode tools(List<LlmTool> tools) {
        ArrayNode array = TransportSupport.array();
        for (LlmTool tool : tools) {
            ObjectNode function = TransportSupport.object();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", TransportSupport.json(tool.parameters()));
            ObjectNode wrap = TransportSupport.object();
            wrap.put("type", "function");
            wrap.set("function", function);
            array.add(wrap);
        }
        return array;
    }

    private static ArrayNode toolCallsJson(List<LlmToolCall> calls) {
        ArrayNode array = TransportSupport.array();
        for (LlmToolCall call : calls) {
            ObjectNode function = TransportSupport.object();
            function.put("name", call.getName());
            function.put("arguments", call.getArgumentsJson() == null ? "{}" : call.getArgumentsJson());
            ObjectNode item = TransportSupport.object();
            item.put("id", call.getId());
            item.put("type", "function");
            item.set("function", function);
            array.add(item);
        }
        return array;
    }

    private Map<String, String> headers(LlmRequest request, ResolvedModel model) {
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

    private LlmCompletion parseBlocking(String json) {
        JsonNode root = TransportSupport.parse(json);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new LlmCallException("响应无法解析", null,
                    LlmCallException.Kind.PARSE);
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        if (message.isMissingNode()) {
            throw new LlmCallException("响应无法解析", null,
                    LlmCallException.Kind.PARSE);
        }
        String reasoning = TransportSupport.firstText(message, "reasoning_content", "reasoning");
        String content = TransportSupport.text(message, "content");
        List<LlmToolCall> calls = parseToolCalls(message.path("tool_calls"));
        LlmStopReason stop = TransportSupport.mapOpenAiStop(
                TransportSupport.text(choice, "finish_reason"), !calls.isEmpty());
        return new LlmCompletion(content, blankToNull(reasoning),
                stop, TransportSupport.usage(root.path("usage")), calls);
    }

    private LlmCompletion streaming(final LlmRequest request, HttpRequest httpRequest) {
        final State state = new State();
        final RawFallback raw = new RawFallback();
        final TransportSupport.SseCollector sse = new TransportSupport.SseCollector();
        try {
            http.executeStreaming(httpRequest, new StreamListener() {
                @Override
                public void onLine(String line) throws IOException {
                    raw.accept(line);
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
            if (!state.sawData && raw.hasContent()) {
                return parseBlocking(raw.text());
            }
            return state.result();
        } catch (LlmCallException ex) {
            if (request.isCancelled()) {
                return state.result(LlmStopReason.ABORTED);
            }
            throw ex;
        }
    }

    private static void handleEvent(String payload, State state, LlmRequest request) {
        if ("[DONE]".equals(payload)) {
            state.sawData = true;
            return;
        }
        state.sawData = true;
        JsonNode root;
        try {
            root = TransportSupport.parse(payload);
        } catch (LlmCallException ex) {
            return;
        }
        if (!root.path("usage").isMissingNode() && !root.path("usage").isNull()) {
            state.usage = TransportSupport.usage(root.path("usage"));
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            return;
        }
        JsonNode choice = choices.get(0);
        String finish = TransportSupport.text(choice, "finish_reason");
        if (Strings.isNotBlank(finish)) {
            state.stop = TransportSupport.mapOpenAiStop(finish, false);
        }
        JsonNode delta = choice.path("delta");
        if (delta.isMissingNode()) {
            return;
        }
        String reasoning = TransportSupport.firstText(delta, "reasoning_content", "reasoning");
        String content = TransportSupport.text(delta, "content");
        if (Strings.isNotEmpty(reasoning)) {
            state.reasoning.append(reasoning);
            TransportSupport.emitReasoning(request, reasoning);
        }
        if (Strings.isNotEmpty(content)) {
            if (state.reasoning.length() > 0 && !state.blankEmitted) {
                TransportSupport.emitSeparator(request);
                state.blankEmitted = true;
            }
            state.content.append(content);
            TransportSupport.emitContent(request, content);
        }
        mergeToolDeltas(state.tools, delta.path("tool_calls"));
    }

    private static void mergeToolDeltas(Map<Integer, ToolBuf> tools, JsonNode array) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            int index = item.path("index").asInt(tools.size());
            ToolBuf buf = tools.get(Integer.valueOf(index));
            if (buf == null) {
                buf = new ToolBuf();
                tools.put(Integer.valueOf(index), buf);
            }
            String id = TransportSupport.text(item, "id");
            if (Strings.isNotBlank(id)) {
                buf.id = id;
            }
            JsonNode function = item.path("function");
            if (function.isMissingNode()) {
                continue;
            }
            String name = TransportSupport.text(function, "name");
            if (Strings.isNotBlank(name)) {
                buf.name = name;
            }
            JsonNode arguments = function.path("arguments");
            if (arguments.isTextual()) {
                buf.arguments.append(arguments.asText());
            } else if (!arguments.isMissingNode() && !arguments.isNull()) {
                buf.arguments.append(arguments.toString());
            }
        }
    }

    private static List<LlmToolCall> parseToolCalls(JsonNode array) {
        List<LlmToolCall> calls = new ArrayList<LlmToolCall>();
        if (!array.isArray()) {
            return calls;
        }
        for (JsonNode item : array) {
            JsonNode function = item.path("function");
            String arguments = TransportSupport.text(function, "arguments");
            calls.add(new LlmToolCall(TransportSupport.text(item, "id"),
                    TransportSupport.text(function, "name"),
                    Strings.isBlank(arguments) ? "{}" : arguments));
        }
        return calls;
    }

    private static String blankToNull(String text) {
        return Strings.isBlank(text) ? null : text;
    }

    /**
     * 「假流式」兜底的原文累积器（F-14 内存修复）。
     *
     * <p><b>解决什么问题</b>：有些供应商你传 {@code stream:true}，它却回一整段普通
     * JSON 而不是 SSE。为了兼容这种情况，原来把收到的每一行都无条件 append 进一个
     * {@code StringBuilder}，末尾如果发现一个 SSE 事件都没解析出来
     * （{@code !state.sawData}），就把这段原文当成整段 JSON 再解析一次。</p>
     *
     * <p>问题在于：正常流式响应下这段累积**从来不会被读取**（{@code sawData} 必然为
     * true），却实打实占了一份和响应体等长的内存。实测一个 4MB 的回复会因此多要
     * 约 22MB 堆（JDK 8 里 {@code char} 占 2 字节，且累积期间新旧缓冲区并存），
     * 并发上来就是几百 MB。</p>
     *
     * <p><b>怎么省</b>：判定挪到第一个有内容的行。真 SSE 的行一定以 {@code data:} 开头；
     * 假流式（整段 JSON）的首个有内容行是 {@code {} 之类的结构字符。判定一次即可，
     * 之后不再改变：</p>
     * <ul>
     *   <li>首行是 SSE 语法 → 判定为真流式 → 本对象<b>永远不启动累积</b>，正常路径零开销；</li>
     *   <li>首行不是 SSE 语法 → 判定为假流式 → <b>从这一行起</b>累积，末尾交给
     *       {@code parseBlocking}。</li>
     * </ul>
     *
     * <p>空行不参与判定——SSE 允许在事件之间插入空行，真实响应也可能以空行开头。
     * 只跳过空白，不因为「第一行是空的」就误判成假流式。</p>
     *
     * <p><b>为什么不直接删掉</b>：假流式供应商确实存在，删了这类调用会全部失败。
     * 这里是把「一直抄」改成「按需抄」，兼容性不变。</p>
     *
     * <p>每个流一个实例，不做并发保护；回调线程串行调用即可。</p>
     */
    private static final class RawFallback {

        private final StringBuilder text = new StringBuilder();
        /** 是否已判定响应形态；null 表示还没遇到有内容的行。 */
        private Boolean sse;

        /**
         * 收一行。只在判定为假流式之后才累积。
         *
         * @param line 原始行，可为 null
         */
        void accept(String line) {
            if (line == null) {
                return;
            }
            if (sse == null) {
                if (line.trim().isEmpty()) {
                    // 空行不用于判定：SSE 允许事件间空行，响应也可能以空行开头。
                    return;
                }
                sse = Boolean.valueOf(isSseLine(line));
                if (sse.booleanValue()) {
                    // 真流式：不启动累积，后续行直接返回。
                    return;
                }
            }
            if (sse.booleanValue()) {
                return;
            }
            text.append(line).append('\n');
        }

        /**
         * @return true 表示已经累积了内容，可以尝试当成整段 JSON 解析
         */
        boolean hasContent() {
            return text.length() > 0;
        }

        /**
         * @return 累积的原文；没有内容时为空串
         */
        String text() {
            return text.toString();
        }

        /**
         * 判断一行是否属于 SSE 语法。
         *
         * <p>三处来源都要认：{@code data:}（数据）、{@code event:} / {@code id:} /
         * {@code retry:}（SSE 的其他字段）、{@code :} 开头（注释行）。
         * 只要首行是其中任一，就说明服务端确实在按 SSE 分帧，不需要原文兜底。</p>
         *
         * @param line 已确认非空的行
         * @return true 表示这一行是 SSE 语法
         */
        private static boolean isSseLine(String line) {
            String value = line.trim();
            return value.startsWith("data:")
                    || value.startsWith("event:")
                    || value.startsWith("id:")
                    || value.startsWith("retry:")
                    || value.startsWith(":");
        }
    }

    private static final class ToolBuf {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }

    private static final class State {
        private final StringBuilder reasoning = new StringBuilder();
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolBuf> tools = new TreeMap<Integer, ToolBuf>();
        private boolean blankEmitted;
        private boolean sawData;
        private LlmUsage usage = LlmUsage.zero();
        private LlmStopReason stop = LlmStopReason.STOP;

        private LlmCompletion result() {
            return result(stop);
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
                    blankToNull(reasoning.toString()), effective, usage, calls);
        }
    }
}
