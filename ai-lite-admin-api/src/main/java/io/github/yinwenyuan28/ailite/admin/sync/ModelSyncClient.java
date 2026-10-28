package io.github.yinwenyuan28.ailite.admin.sync;

import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.RawHttpExecutor;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调用 OpenAI 兼容的 {@code GET {baseUrl}/models} 与 {@code POST {baseUrl}/chat/completions}。
 * 不写调用日志。
 *
 * <p>同步是增量的：新 id 插入且未就绪，已有行不改，接口里消失的 id 不删。</p>
 *
 * <p><b>走哪条出站链路</b>：所有方法都优先使用调用方传入的
 * {@link HttpExecutor}——也就是 {@code ai-lite.http.transport} 配置启用的那个。
 * 管理台的"测试连通/测试调用"必须与真实模型调用同一条链路，否则会出现
 * "管理台显示成功、业务调用全挂"的错位（典型场景：网关配 https 的 Jetty 而
 * 诊断走了另一个没配 TLS 的客户端）。</p>
 *
 * <p>只有在没有可用执行器时（例如脱离 Starter 单独使用 admin-api 做单元测试），
 * 才退化为 {@link HttpURLConnection} 兜底实现；该兜底与网关无关，仅保证接口可用。</p>
 */
public final class ModelSyncClient {

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"\\\\]*)\"");
    private static final int MAX_READ_MS = 15000;

    private ModelSyncClient() {
    }

    /**
     * 同步远端模型列表到目录。
     *
     * @param http       出站执行器，可为 null（退化为 HttpURLConnection）
     * @param api        协议
     * @param baseUrl    供应商根地址
     * @param apiKey     生效密钥
     * @param catalog    目录
     * @param providerId 供应商 id
     * @return 新增数与已存在数
     */
    public static SyncStats sync(HttpExecutor http, String api, String baseUrl, String apiKey,
                                 JdbcRuntimeCatalog catalog, String providerId) {
        if ("anthropic-messages".equals(api)) {
            throw new IllegalStateException("该协议没有 /models，请手工添加模型");
        }
        ProbeResult probe = probe(http, baseUrl, apiKey, 10000, MAX_READ_MS);
        if (!probe.isOk()) {
            throw new IllegalStateException(probe.getMessage());
        }
        int added = 0;
        int existing = 0;
        for (String id : parseModelIds(probe.getBody())) {
            if (catalog.insertModelIfAbsent(providerId, id)) {
                added++;
            } else {
                existing++;
            }
        }
        return new SyncStats(added, existing);
    }

    /**
     * 只读取远端模型 id，不写库。
     *
     * @param http    出站执行器，可为 null
     * @param api     协议
     * @param baseUrl 供应商根地址
     * @param apiKey  生效密钥
     * @return 远端模型 id，保持出现顺序
     */
    public static List<String> listRemote(HttpExecutor http, String api, String baseUrl,
                                          String apiKey) {
        if ("anthropic-messages".equals(api)) {
            throw new IllegalStateException("该协议没有 /models，请手工添加模型");
        }
        ProbeResult probe = probe(http, baseUrl, apiKey, 10000, MAX_READ_MS);
        if (!probe.isOk()) {
            throw new IllegalStateException(probe.getMessage());
        }
        return parseModelIds(probe.getBody());
    }

    /**
     * 无执行器的重载，仅用于脱离 Starter 的场景（如 admin-api 的独立单元测试）。
     *
     * <p>生产路径必须走 {@link #sync(HttpExecutor, String, String, String,
     * JdbcRuntimeCatalog, String)}，否则诊断与真实调用不是同一条链路。</p>
     *
     * @param api        协议
     * @param baseUrl    供应商根地址
     * @param apiKey     生效密钥
     * @param catalog    目录
     * @param providerId 供应商 id
     * @return 新增数与已存在数
     */
    public static SyncStats sync(String api, String baseUrl, String apiKey,
                                 JdbcRuntimeCatalog catalog, String providerId) {
        return sync(null, api, baseUrl, apiKey, catalog, providerId);
    }

    /**
     * 无执行器的重载，见 {@link #sync(String, String, String, JdbcRuntimeCatalog, String)}。
     *
     * @param api     协议
     * @param baseUrl 供应商根地址
     * @param apiKey  生效密钥
     * @return 远端模型 id
     */
    public static List<String> listRemote(String api, String baseUrl, String apiKey) {
        return listRemote(null, api, baseUrl, apiKey);
    }

    /**
     * 无执行器的重载，见 {@link #sync(String, String, String, JdbcRuntimeCatalog, String)}。
     *
     * @param api            协议
     * @param baseUrl        供应商根地址
     * @param apiKey         生效密钥
     * @param modelId        模型 id
     * @param thinkingFormat 思考格式
     * @param maxTokens      输出上限
     * @param thinkingLevel  思考档
     * @param connectMs      连接超时
     * @param readMs         读取超时
     * @return 探测结果
     */
    public static TryResult tryChat(String api, String baseUrl, String apiKey, String modelId,
                                    String thinkingFormat, Integer maxTokens, String thinkingLevel,
                                    int connectMs, int readMs) {
        return tryChat(null, api, baseUrl, apiKey, modelId, thinkingFormat, maxTokens,
                thinkingLevel, connectMs, readMs);
    }

    /**
     * 无执行器的重载，见 {@link #sync(String, String, String, JdbcRuntimeCatalog, String)}。
     *
     * @param baseUrl   根地址
     * @param apiKey    密钥
     * @param connectMs 连接超时
     * @param readMs    读取超时
     * @return 探测结果
     */
    public static ProbeResult probe(String baseUrl, String apiKey, int connectMs, int readMs) {
        return probe(null, baseUrl, apiKey, connectMs, readMs);
    }

    /**
     * 向模型发一次最短对话。不写调用日志。思考开关关掉，避免测试本身触发长推理。
     *
     * @param http           出站执行器，可为 null（退化为 HttpURLConnection）
     * @param api            协议
     * @param baseUrl        供应商根地址
     * @param apiKey         生效密钥
     * @param modelId        模型 id
     * @param thinkingFormat 思考格式，可空
     * @param maxTokens      输出上限，可空
     * @param thinkingLevel  思考档
     * @param connectMs      连接超时
     * @param readMs         读取超时，上限 60 秒
     * @return 探测结果。requestText 是脱敏后的请求，responseText 是原始响应
     */
    public static TryResult tryChat(HttpExecutor http, String api, String baseUrl, String apiKey,
                                    String modelId, String thinkingFormat, Integer maxTokens,
                                    String thinkingLevel, int connectMs, int readMs) {
        if ("anthropic-messages".equals(api)) {
            return TryResult.failed(trimSlash(baseUrl), "Anthropic 协议的测试调用尚未接入");
        }
        if ("openai-responses".equals(api)) {
            return TryResult.failed(trimSlash(baseUrl), "Responses 协议的测试调用尚未接入");
        }
        String url = trimSlash(baseUrl) + "/chat/completions";
        String payload = chatPayload(modelId, thinkingFormat, maxTokens, thinkingLevel);
        String requestText = redact(requestDump("POST", url, payload), apiKey);
        int read = readMs <= 0 ? 30000 : Math.min(readMs, 60000);
        int connect = connectMs <= 0 ? 10000 : connectMs;
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json; charset=utf-8");
        HttpRequest request = new HttpRequest(HttpRequest.POST, url, headers, payload,
                connect, read, null);
        long started = System.currentTimeMillis();
        try {
            HttpResult result = send(http, request);
            long latency = System.currentTimeMillis() - started;
            int status = result.getStatus();
            String body = result.getBody();
            String raw = redact(limit(body, 20000), apiKey);
            String responseText = "HTTP " + status + "\n" + raw;
            if (status >= 200 && status < 300) {
                String content = stripKey(chatContent(body), apiKey);
                String message = content.isEmpty() ? "调用成功，但没有解析到回复" : "调用成功";
                return new TryResult(true, status, latency, message, content, requestText, responseText);
            }
            return new TryResult(false, status, latency,
                    stripKey("调用失败，HTTP " + status, apiKey), "", requestText, responseText);
        } catch (LlmCallException ex) {
            long latency = System.currentTimeMillis() - started;
            String message = ex.getMessage() == null ? "请求失败" : ex.getMessage();
            return new TryResult(false, statusOf(ex), latency,
                    stripKey("请求失败：" + message, apiKey), "", requestText,
                    redact(message, apiKey));
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - started;
            String message = ex.getMessage() == null ? "请求失败" : ex.getMessage();
            return new TryResult(false, 0, latency, stripKey("请求失败：" + message, apiKey),
                    "", requestText, redact(message, apiKey));
        }
    }

    /**
     * 只探测连通，不改模型表。
     *
     * @param http      出站执行器，可为 null（退化为 HttpURLConnection）
     * @param baseUrl   根地址
     * @param apiKey    密钥
     * @param connectMs 连接超时
     * @param readMs    读取超时，超过 15 秒按 15 秒
     * @return 探测结果
     */
    public static ProbeResult probe(HttpExecutor http, String baseUrl, String apiKey,
                                    int connectMs, int readMs) {
        String url = trimSlash(baseUrl) + "/models";
        int read = readMs <= 0 ? MAX_READ_MS : Math.min(readMs, MAX_READ_MS);
        int connect = connectMs <= 0 ? 10000 : connectMs;
        Map<String, String> headers = Collections.singletonMap(
                "Authorization", "Bearer " + apiKey);
        HttpRequest request = new HttpRequest(HttpRequest.GET, url, headers, null,
                connect, read, null);
        long started = System.currentTimeMillis();
        try {
            HttpResult result = send(http, request);
            long latency = System.currentTimeMillis() - started;
            int status = result.getStatus();
            if (status >= 200 && status < 300) {
                return new ProbeResult(true, status, latency, url, "连通成功", result.getBody());
            }
            return new ProbeResult(false, status, latency, url,
                    stripKey("连通失败，HTTP " + status, apiKey), "");
        } catch (LlmCallException ex) {
            long latency = System.currentTimeMillis() - started;
            String message = ex.getMessage() == null ? "请求失败" : ex.getMessage();
            return new ProbeResult(false, statusOf(ex), latency, url,
                    stripKey("请求失败：" + message, apiKey), "");
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - started;
            String message = ex.getMessage() == null ? "请求失败" : ex.getMessage();
            return new ProbeResult(false, 0, latency, url,
                    stripKey("请求失败：" + message, apiKey), "");
        }
    }

    /**
     * 发送请求：优先走配置启用的执行器，没有执行器时才用 HttpURLConnection 兜底。
     *
     * <p>执行器未实现 {@link RawHttpExecutor} 时，用
     * {@link HttpExecutor#execute} 并在非 2xx 时把状态码从异常里取回来——正文会
     * 丢失（老接口的契约如此），但不至于让诊断功能不可用。</p>
     *
     * @param http    执行器，可为 null
     * @param request 请求
     * @return 状态码与正文；非 2xx 也正常返回
     * @throws LlmCallException 网络层失败
     */
    private static HttpResult send(HttpExecutor http, HttpRequest request) {
        if (http instanceof RawHttpExecutor) {
            return ((RawHttpExecutor) http).exchange(request);
        }
        if (http != null) {
            try {
                return http.execute(request);
            } catch (LlmCallException ex) {
                if (ex.getKind() == LlmCallException.Kind.HTTP && ex.getStatusCode() != null) {
                    // 老契约在此处丢弃正文；至少把状态码交给上层呈现。
                    return new HttpResult(ex.getStatusCode().intValue(), "");
                }
                throw ex;
            }
        }
        return fallbackSend(request);
    }

    /**
     * 无执行器时的兜底：与旧实现等价，只保证接口可用，不代表网关链路。
     */
    private static HttpResult fallbackSend(HttpRequest request) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(request.getUrl()).openConnection();
            connection.setRequestMethod(request.getMethod());
            connection.setConnectTimeout(request.getConnectTimeoutMs());
            connection.setReadTimeout(request.getReadTimeoutMs());
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (!request.isGet()) {
                connection.setDoOutput(true);
                byte[] bytes = request.getBody().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                java.io.OutputStream output = connection.getOutputStream();
                try {
                    output.write(bytes);
                } finally {
                    output.close();
                }
            }
            int status = connection.getResponseCode();
            String body = readBody(status >= 400
                    ? connection.getErrorStream() : connection.getInputStream());
            return new HttpResult(status, body);
        } catch (LlmCallException ex) {
            throw ex;
        } catch (Exception ex) {
            // 消息里带上最深层原因（connect timed out / Connection refused 等），
            // 只写"请求失败"会让管理台无法区分网络问题和配置问题。
            throw new LlmCallException("请求失败：" + rootMessage(ex), ex,
                    LlmCallException.Kind.TRANSPORT);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 取异常链最深层带消息的那条，用于给管理员一个可执行的失败原因。
     *
     * @param error 原始异常
     * @return 最深层原因的消息；全链都没有消息时返回空串
     */
    private static String rootMessage(Throwable error) {
        String message = "";
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().trim().isEmpty()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message;
    }

    /**
     * @param error 失败异常
     * @return HTTP 状态码；非 HTTP 失败为 0
     */
    private static int statusOf(LlmCallException error) {
        return error.getStatusCode() == null ? 0 : error.getStatusCode().intValue();
    }

    static String requestDump(String method, String url, String payload) {
        return method + " " + url + "\n"
                + "Content-Type: application/json; charset=utf-8\n"
                + "Authorization: Bearer ***\n\n"
                + payload;
    }

    private static String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…已截断";
    }

    static String chatPayload(String modelId, String thinkingFormat, Integer maxTokens,
                              String thinkingLevel) {
        int tokens = maxTokens == null || maxTokens.intValue() <= 0 ? 8192 : maxTokens.intValue();
        String level = normalizeThinkingLevel(thinkingLevel);
        boolean enabled = !"off".equals(level);
        String format = thinkingFormat == null || thinkingFormat.trim().isEmpty()
                ? "none" : thinkingFormat.trim();
        StringBuilder json = new StringBuilder();
        json.append("{\"model\":\"").append(escapeJson(modelId)).append("\",");
        json.append("\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}],");
        json.append("\"max_tokens\":").append(tokens);
        if ("chat-template".equals(format)) {
            json.append(",\"chat_template_kwargs\":{\"enable_thinking\":").append(enabled).append("}");
        } else if ("qwen".equals(format)) {
            json.append(",\"enable_thinking\":").append(enabled);
        } else if ("deepseek".equals(format)) {
            if (enabled) {
                json.append(",\"thinking\":{\"type\":\"enabled\"},\"reasoning_effort\":\"")
                        .append(escapeJson(level)).append("\"");
            } else {
                json.append(",\"thinking\":{\"type\":\"disabled\"}");
            }
        } else if ("reasoning-effort".equals(format) && enabled) {
            json.append(",\"reasoning_effort\":\"").append(escapeJson(level)).append("\"");
        }
        json.append("}");
        return json.toString();
    }

    static String normalizeThinkingLevel(String thinkingLevel) {
        if (thinkingLevel == null || thinkingLevel.trim().isEmpty()) {
            return "off";
        }
        String name = thinkingLevel.trim().toLowerCase();
        if ("false".equals(name) || "no".equals(name) || "none".equals(name)) {
            return "off";
        }
        if ("true".equals(name) || "yes".equals(name) || "on".equals(name)) {
            return "high";
        }
        if ("off".equals(name) || "minimal".equals(name) || "low".equals(name)
                || "medium".equals(name) || "high".equals(name)
                || "xhigh".equals(name) || "max".equals(name)) {
            return name;
        }
        throw new IllegalArgumentException("未知思考档: " + thinkingLevel.trim());
    }

    static String chatContent(String body) {
        if (body == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(body);
        String found = "";
        while (matcher.find()) {
            if (!matcher.group(1).isEmpty()) {
                found = unescapeJson(matcher.group(1));
            }
        }
        return found.length() > 200 ? found.substring(0, 200) : found;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJson(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) {
                out.append(ch);
                continue;
            }
            char next = value.charAt(++i);
            if (next == 'n') {
                out.append('\n');
            } else if (next == 't') {
                out.append('\t');
            } else if (next == 'u' && i + 4 < value.length()) {
                String hex = value.substring(i + 1, i + 5);
                out.append((char) Integer.parseInt(hex, 16));
                i += 4;
            } else {
                out.append(next);
            }
        }
        return out.toString();
    }

    /**
     * 从 OpenAI 兼容响应里取出模型 id。不解析其余字段。
     *
     * @param body 响应正文
     * @return id 列表，保持出现顺序
     */
    public static List<String> parseModelIds(String body) {
        List<String> ids = new ArrayList<String>();
        if (body == null) {
            return ids;
        }
        Matcher matcher = ID_PATTERN.matcher(body);
        while (matcher.find()) {
            String id = matcher.group(1);
            if (id != null && !id.trim().isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    static String stripKey(String message, String apiKey) {
        String text = redact(message, apiKey).replace('\n', ' ').replace('\r', ' ').trim();
        if (text.length() > 200) {
            return text.substring(0, 200);
        }
        return text;
    }

    /**
     * 去掉密钥，保留换行，不截断。
     */
    static String redact(String text, String apiKey) {
        String value = text == null ? "" : text;
        if (apiKey != null && !apiKey.isEmpty() && value.contains(apiKey)) {
            value = value.replace(apiKey, "***");
        }
        return value;
    }

    private static String trimSlash(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String readBody(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int n;
            while ((n = input.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    /**
     * 一次同步的计数。
     */
    public static final class SyncStats {
        private final int added;
        private final int existing;

        /**
         * @param added    新插入的 id 数
         * @param existing 已存在、未改动的 id 数
         */
        public SyncStats(int added, int existing) {
            this.added = added;
            this.existing = existing;
        }

        public int getAdded() {
            return added;
        }

        public int getExisting() {
            return existing;
        }
    }

    /**
     * 一次测试调用。requestText 已把密钥换成 ***。
     */
    public static final class TryResult {
        private final boolean ok;
        private final int httpStatus;
        private final long latencyMs;
        private final String message;
        private final String content;
        private final String requestText;
        private final String responseText;

        private TryResult(boolean ok, int httpStatus, long latencyMs, String message, String content,
                          String requestText, String responseText) {
            this.ok = ok;
            this.httpStatus = httpStatus;
            this.latencyMs = latencyMs;
            this.message = message;
            this.content = content;
            this.requestText = requestText;
            this.responseText = responseText;
        }

        static TryResult failed(String url, String message) {
            return new TryResult(false, 0, 0L, message, "", url, "");
        }

        public boolean isOk() {
            return ok;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public String getMessage() {
            return message;
        }

        public String getContent() {
            return content;
        }

        public String getRequestText() {
            return requestText;
        }

        public String getResponseText() {
            return responseText;
        }
    }

    /**
     * 一次连通探测。
     */
    public static final class ProbeResult {
        private final boolean ok;
        private final int httpStatus;
        private final long latencyMs;
        private final String checkedUrl;
        private final String message;
        private final String body;

        /**
         * @param ok         是否 2xx
         * @param httpStatus HTTP 状态，失败且没有响应时为 0
         * @param latencyMs  耗时
         * @param checkedUrl 实际请求的地址
         * @param message    给人看的摘要，不含密钥
         * @param body       成功时的正文
         */
        public ProbeResult(boolean ok, int httpStatus, long latencyMs, String checkedUrl,
                           String message, String body) {
            this.ok = ok;
            this.httpStatus = httpStatus;
            this.latencyMs = latencyMs;
            this.checkedUrl = checkedUrl;
            this.message = message;
            this.body = body;
        }

        public boolean isOk() {
            return ok;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public String getCheckedUrl() {
            return checkedUrl;
        }

        public String getMessage() {
            return message;
        }

        public String getBody() {
            return body;
        }
    }
}
