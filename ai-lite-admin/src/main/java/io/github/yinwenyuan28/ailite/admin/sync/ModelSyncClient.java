package io.github.yinwenyuan28.ailite.admin.sync;

import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调用 OpenAI 兼容的 {@code GET {baseUrl}/models}。不写调用日志。
 *
 * <p>同步是增量的：新 id 插入且未就绪，已有行不改，接口里消失的 id 不删。</p>
 */
public final class ModelSyncClient {

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"\\\\]*)\"");
    private static final int MAX_READ_MS = 15000;

    private ModelSyncClient() {
    }

    /**
     * @param api        协议
     * @param baseUrl    供应商根地址
     * @param apiKey     生效密钥
     * @param catalog    目录
     * @param providerId 供应商 id
     * @return 新增数与已存在数
     */
    public static SyncStats sync(String api, String baseUrl, String apiKey,
                                 JdbcRuntimeCatalog catalog, String providerId) {
        if ("anthropic-messages".equals(api)) {
            throw new IllegalStateException("该协议没有 /models，请手工添加模型");
        }
        ProbeResult probe = probe(baseUrl, apiKey, 10000, MAX_READ_MS);
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
     * @param api     协议
     * @param baseUrl 供应商根地址
     * @param apiKey  生效密钥
     * @return 远端模型 id，保持出现顺序
     */
    public static List<String> listRemote(String api, String baseUrl, String apiKey) {
        if ("anthropic-messages".equals(api)) {
            throw new IllegalStateException("该协议没有 /models，请手工添加模型");
        }
        ProbeResult probe = probe(baseUrl, apiKey, 10000, MAX_READ_MS);
        if (!probe.isOk()) {
            throw new IllegalStateException(probe.getMessage());
        }
        return parseModelIds(probe.getBody());
    }

    /**
     * 向模型发一次最短对话。不写调用日志。思考开关关掉，避免测试本身触发长推理。
     *
     * @param api            协议
     * @param baseUrl        供应商根地址
     * @param apiKey         生效密钥
     * @param modelId        模型 id
     * @param thinkingFormat 思考格式，可空
     * @param connectMs      连接超时
     * @param readMs         读取超时，上限 60 秒
     * @return 探测结果。requestText 是脱敏后的请求，responseText 是原始响应
     */
    public static TryResult tryChat(String api, String baseUrl, String apiKey, String modelId,
                                      String thinkingFormat, Integer maxTokens,
                                      int connectMs, int readMs) {
        if ("anthropic-messages".equals(api)) {
            return TryResult.failed(trimSlash(baseUrl), "Anthropic 协议的测试调用尚未接入");
        }
        if ("openai-responses".equals(api)) {
            return TryResult.failed(trimSlash(baseUrl), "Responses 协议的测试调用尚未接入");
        }
        String url = trimSlash(baseUrl) + "/chat/completions";
        String payload = chatPayload(modelId, thinkingFormat, maxTokens);
        String requestText = redact(requestDump("POST", url, payload), apiKey);
        int read = readMs <= 0 ? 30000 : Math.min(readMs, 60000);
        int connect = connectMs <= 0 ? 10000 : connectMs;
        long started = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connect);
            connection.setReadTimeout(read);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            java.io.OutputStream output = connection.getOutputStream();
            try {
                output.write(bytes);
            } finally {
                output.close();
            }
            int status = connection.getResponseCode();
            String body = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            long latency = System.currentTimeMillis() - started;
            String raw = redact(limit(body, 20000), apiKey);
            String responseText = "HTTP " + status + "\n" + raw;
            if (status >= 200 && status < 300) {
                String content = stripKey(chatContent(body), apiKey);
                String message = content.isEmpty() ? "调用成功，但没有解析到回复" : "调用成功";
                return new TryResult(true, status, latency, message, content, requestText, responseText);
            }
            return new TryResult(false, status, latency,
                    stripKey("调用失败，HTTP " + status, apiKey), "", requestText, responseText);
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - started;
            String message = ex.getMessage() == null ? "请求失败" : ex.getMessage();
            return new TryResult(false, 0, latency, stripKey("请求失败：" + message, apiKey),
                    "", requestText, redact(message, apiKey));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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

    static String chatPayload(String modelId, String thinkingFormat, Integer maxTokens) {
        int tokens = maxTokens == null || maxTokens.intValue() <= 0 ? 8192 : maxTokens.intValue();
        StringBuilder json = new StringBuilder();
        json.append("{\"model\":\"").append(escapeJson(modelId)).append("\",");
        json.append("\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}],");
        json.append("\"max_tokens\":").append(tokens);
        if ("chat-template".equals(thinkingFormat)) {
            json.append(",\"chat_template_kwargs\":{\"enable_thinking\":false}");
        } else if ("qwen".equals(thinkingFormat)) {
            json.append(",\"enable_thinking\":false");
        } else if ("deepseek".equals(thinkingFormat)) {
            json.append(",\"thinking\":{\"type\":\"disabled\"}");
        }
        json.append("}");
        return json.toString();
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
     * 只探测连通，不改模型表。
     *
     * @param baseUrl  根地址
     * @param apiKey   密钥
     * @param connectMs 连接超时
     * @param readMs    读取超时，超过 15 秒按 15 秒
     * @return 探测结果
     */
    public static ProbeResult probe(String baseUrl, String apiKey, int connectMs, int readMs) {
        String url = trimSlash(baseUrl) + "/models";
        int read = readMs <= 0 ? MAX_READ_MS : Math.min(readMs, MAX_READ_MS);
        int connect = connectMs <= 0 ? 10000 : connectMs;
        long started = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(connect);
            connection.setReadTimeout(read);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            int status = connection.getResponseCode();
            String body = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            long latency = System.currentTimeMillis() - started;
            if (status >= 200 && status < 300) {
                return new ProbeResult(true, status, latency, url, "连通成功", body);
            }
            return new ProbeResult(false, status, latency, url,
                    stripKey("连通失败，HTTP " + status, apiKey), "");
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - started;
            String message = ex.getMessage() == null ? "请求失败" : ex.getMessage();
            return new ProbeResult(false, 0, latency, url, stripKey("请求失败：" + message, apiKey), "");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
