package io.github.yinwenyuan28.ailite.admin.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.http.HttpRequest;
import io.github.yinwenyuan28.ailite.http.HttpResult;
import io.github.yinwenyuan28.ailite.http.RawHttpExecutor;
import io.github.yinwenyuan28.ailite.json.AiLiteJson;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmCompletion;
import io.github.yinwenyuan28.ailite.llm.LlmMessage;
import io.github.yinwenyuan28.ailite.llm.LlmRequest;
import io.github.yinwenyuan28.ailite.llm.LlmTool;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
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

    /** 测试调用不填消息时用的默认内容。短、无歧义、不触发长推理。 */
    public static final String DEFAULT_PROBE_PROMPT = "你好";

    private ModelSyncClient() {
    }

    /**
     * 把供应商级自定义请求头并进探测用的头。
     *
     * <p>保留 {@code Authorization} 与 {@code Content-Type} 由本类决定：这两个由密钥与
     * 请求编码固定，不允许通过配置覆盖（配置阶段已经拦过一次，这里再兜一层，
     * 避免有人直接调这个类时绕过）。</p>
     *
     * @param target 目标头
     * @param extra  自定义头，可为 null
     */
    private static void mergeExtraHeaders(Map<String, String> target, Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            if ("authorization".equalsIgnoreCase(name) || "content-type".equalsIgnoreCase(name)) {
                continue;
            }
            target.put(name, entry.getValue());
        }
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
        return sync(http, api, baseUrl, apiKey, catalog, providerId, null);
    }

    /**
     * 同步远端模型列表到目录，并带上供应商的自定义请求头。
     *
     * <p>{@code extraHeaders} 存在的理由：供应商级请求头会影响路由、灰度或鉴权，
     * 探测与业务调用必须带同一份。修复前探测只带 Authorization，配了自定义头之后
     * 会出现「页面上测通了但业务失败」的错位。</p>
     *
     * @param http        出站执行器，可为 null（退化为 HttpURLConnection）
     * @param api         协议
     * @param baseUrl     供应商根地址
     * @param apiKey      生效密钥
     * @param catalog     目录
     * @param providerId  供应商 id
     * @param extraHeaders 供应商级请求头，可为 null
     * @return 新增数与已存在数
     */
    public static SyncStats sync(HttpExecutor http, String api, String baseUrl, String apiKey,
                                 JdbcRuntimeCatalog catalog, String providerId,
                                 Map<String, String> extraHeaders) {
        if ("anthropic-messages".equals(api)) {
            throw new IllegalStateException("该协议没有 /models，请手工添加模型");
        }
        ProbeResult probe = probe(http, baseUrl, apiKey, 10000, MAX_READ_MS, extraHeaders);
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
        return listRemote(http, api, baseUrl, apiKey, null);
    }

    /**
     * 读取远端模型列表，带供应商自定义请求头。
     *
     * @param http         出站执行器，可为 null
     * @param api          协议
     * @param baseUrl      供应商根地址
     * @param apiKey       生效密钥
     * @param extraHeaders 供应商级请求头，可为 null
     * @return 远端模型 id，保持出现顺序
     */
    public static List<String> listRemote(HttpExecutor http, String api, String baseUrl,
                                          String apiKey, Map<String, String> extraHeaders) {
        if ("anthropic-messages".equals(api)) {
            throw new IllegalStateException("该协议没有 /models，请手工添加模型");
        }
        ProbeResult probe = probe(http, baseUrl, apiKey, 10000, MAX_READ_MS, extraHeaders);
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
        return tryChat(http, api, baseUrl, apiKey, modelId, thinkingFormat, maxTokens,
                thinkingLevel, connectMs, readMs, null);
    }

    /**
     * 向模型发一次最短对话，带上供应商自定义请求头。不写调用日志。
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
     * @param extraHeaders   供应商级请求头，可为 null
     * @return 探测结果
     */
    public static TryResult tryChat(HttpExecutor http, String api, String baseUrl, String apiKey,
                                    String modelId, String thinkingFormat, Integer maxTokens,
                                    String thinkingLevel, int connectMs, int readMs,
                                    Map<String, String> extraHeaders) {
        return tryChat(http, api, baseUrl, apiKey, modelId, thinkingFormat, maxTokens,
                thinkingLevel, connectMs, readMs, extraHeaders, null);
    }

    /**
     * 用真实的协议适配器发一次测试调用。
     *
     * <p>与 {@link #tryChat} 的区别：这个方法走 {@code LlmTransport}，也就是业务调用
     * 用的同一套请求构造代码，因此三种协议都支持，请求头与请求体的拼装规则也完全一致。
     * {@link #tryChat} 是手写 JSON 的旧实现，只支持 OpenAI 兼容协议，且样本消息、
     * 思考参数写法都是自己的分支——两边迟早会走偏。</p>
     *
     * <p>代价：仍不写调用日志、不过出口保护（guard），因为这两件事在
     * {@code LlmGateway} 里，而这个方法只借用了传输层。测试调用产生真实计费，
     * 但不应污染业务调用记录，也不该占业务的并发许可。</p>
     *
     * @param transport 协议适配器
     * @param model     解析好的模型（含两级请求参数）
     * @param maxTokens 输出上限覆盖；null 表示用模型配置
     * @param timeoutMs 读超时，上限 60 秒
     * @return 探测结果
     */
    public static TryResult tryChatViaTransport(LlmTransport transport, ResolvedModel model,
                                                Integer maxTokens, int timeoutMs) {
        return tryChatViaTransport(transport, model, DEFAULT_PROBE_PROMPT, maxTokens, timeoutMs);
    }

    /**
     * 用真实的协议适配器发一次测试调用，自定义用户消息。
     *
     * <p>允许自定义消息是为了能测真实场景：固定发「你好」只够验证连通，测不出
     * 提示词是否起作用、结构化输出是否按预期返回、长输入会不会超窗口。</p>
     *
     * @param transport 协议适配器
     * @param model     解析好的模型（含两级请求参数）
     * @param prompt    用户消息；空时用默认的 {@link #DEFAULT_PROBE_PROMPT}
     * @param maxTokens 输出上限覆盖；null 表示用模型配置
     * @param timeoutMs 读超时，上限 60 秒
     * @return 探测结果
     */
    public static TryResult tryChatViaTransport(LlmTransport transport, ResolvedModel model,
                                                String prompt, Integer maxTokens, int timeoutMs) {
        if (transport == null) {
            return TryResult.failed(trimSlash(model == null ? null : model.getBaseUrl()),
                    "没有可用的协议适配器");
        }
        if (model == null) {
            return TryResult.failed("", "模型不存在");
        }
        String text = prompt == null || prompt.trim().isEmpty() ? DEFAULT_PROBE_PROMPT : prompt;
        int read = timeoutMs <= 0 ? 30000 : Math.min(timeoutMs, 60000);
        LlmRequest request = new LlmRequest(model,
                Collections.<LlmMessage>singletonList(LlmMessage.user(text)),
                Collections.<LlmTool>emptyList(), false, null, null, null,
                Integer.valueOf(read), null, maxTokens);
        String url = describeUrl(model);
        long started = System.currentTimeMillis();
        try {
            LlmCompletion completion = transport.complete(request);
            long latency = System.currentTimeMillis() - started;
            String content = completion == null || completion.getContent() == null
                    ? "" : completion.getContent();
            String message = content.isEmpty() ? "调用成功，但没有解析到回复" : "调用成功";
            String requestText = describeRequest(model, url, maxTokens, text);
            String responseText = "HTTP 200\n" + limit(content, 20000);
            return new TryResult(true, 200, latency, message, content, requestText, responseText);
        } catch (LlmCallException ex) {
            long latency = System.currentTimeMillis() - started;
            String message = ex.getMessage() == null ? "请求失败" : ex.getMessage();
            return new TryResult(false, statusOf(ex), latency,
                    stripKey(message, model.getApiKey()), "",
                    describeRequest(model, url, maxTokens, text),
                    stripKey("请求失败：" + message, model.getApiKey()));
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - started;
            String message = ex.getMessage() == null ? "请求失败" : ex.getMessage();
            return new TryResult(false, 0, latency,
                    stripKey(message, model.getApiKey()), "",
                    describeRequest(model, url, maxTokens, text),
                    stripKey("请求失败：" + message, model.getApiKey()));
        }
    }

    /**
     * 拼一个「这次大概会发什么」的展示。
     *
     * <p>传输层不接受外部传入的完整报文，所以这里的请求体是按同样规则重建的近似值：
     * 模型 id、消息、输出上限、采样参数与附加字段都是真实值，但思考开关的具体字段名
     * 由各协议自己决定，这里不做模拟。请求头是真实生效值（含两级合并结果）。</p>
     *
     * <p>请求体用 JSON 序列化生成而不是字符串拼接：用户可以在页面上输入任意文本，
     * 手拼会让引号、换行、emoji 破坏回显的 JSON 结构。</p>
     *
     * @param model     模型
     * @param url       地址
     * @param maxTokens 输出上限覆盖
     * @param prompt    用户消息
     * @return 展示文本
     */
    private static String describeRequest(ResolvedModel model, String url, Integer maxTokens,
                                          String prompt) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.putAll(model.getHeaders());
        if (!containsIgnoreCase(headers, "Authorization")) {
            headers.put("Authorization", "Bearer " + model.getApiKey());
        }
        if (!containsIgnoreCase(headers, "Content-Type")) {
            headers.put("Content-Type", "application/json; charset=utf-8");
        }
        int tokens = maxTokens == null || maxTokens.intValue() <= 0
                ? model.getMaxTokens() : maxTokens.intValue();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", model.getModelId());
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", prompt);
        body.put("messages", Collections.singletonList(message));
        body.put("max_tokens", Integer.valueOf(tokens));
        if (model.getSamplerParams() != null) {
            body.putAll(model.getSamplerParams());
        }
        if (model.getExtraBody() != null) {
            body.putAll(model.getExtraBody());
        }
        return requestDump("POST", url, headers, AiLiteJson.toJson(body), model.getApiKey());
    }

    /**
     * @param headers 头
     * @param name    头名
     * @return 是否存在（忽略大小写）
     */
    private static boolean containsIgnoreCase(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) {
            if (key != null && key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按协议拼出会实际请求的地址，供回显使用。
     *
     * @param model 模型
     * @return 完整地址
     */
    private static String describeUrl(ResolvedModel model) {
        String base = trimSlash(model.getBaseUrl());
        String api = model.getApi();
        if ("anthropic-messages".equals(api)) {
            return base + "/v1/messages";
        }
        if ("openai-responses".equals(api)) {
            return base + "/responses";
        }
        return base + "/chat/completions";
    }

    /**
     * 向模型发一次最短对话，带上两级请求参数。
     *
     * <p>{@code extraBody} 是模型级与供应商级附加字段合并后的结果。带上它，
     * 「测试调用」才与业务调用发出同样的请求体；否则页面显示采样参数已配好，
     * 测试时却一个都没发出去，会让人误判配置没生效。</p>
     *
     * @param http           出站执行器，可为 null
     * @param api            协议
     * @param baseUrl        供应商根地址
     * @param apiKey         生效密钥
     * @param modelId        模型 id
     * @param thinkingFormat 思考格式，可空
     * @param maxTokens      输出上限，可空
     * @param thinkingLevel  思考档
     * @param connectMs      连接超时
     * @param readMs         读取超时
     * @param extraHeaders   供应商级请求头，可为 null
     * @param extraBody      两级附加请求体合并结果，可为 null
     * @return 探测结果
     */
    public static TryResult tryChat(HttpExecutor http, String api, String baseUrl, String apiKey,
                                    String modelId, String thinkingFormat, Integer maxTokens,
                                    String thinkingLevel, int connectMs, int readMs,
                                    Map<String, String> extraHeaders,
                                    Map<String, Object> extraBody) {
        return tryChat(http, api, baseUrl, apiKey, modelId, thinkingFormat, maxTokens,
                thinkingLevel, connectMs, readMs, extraHeaders, extraBody, null);
    }

    /**
     * 手写实现，支持自定义用户消息。
     *
     * @param http           出站执行器，可为 null
     * @param api            协议
     * @param baseUrl        供应商根地址
     * @param apiKey         生效密钥
     * @param modelId        模型 id
     * @param thinkingFormat 思考格式，可空
     * @param maxTokens      输出上限，可空
     * @param thinkingLevel  思考档
     * @param connectMs      连接超时
     * @param readMs         读取超时
     * @param extraHeaders   供应商级请求头
     * @param extraBody      两级附加请求体合并结果
     * @param prompt         用户消息；空时用 {@link #DEFAULT_PROBE_PROMPT}
     * @return 探测结果
     */
    public static TryResult tryChat(HttpExecutor http, String api, String baseUrl, String apiKey,
                                    String modelId, String thinkingFormat, Integer maxTokens,
                                    String thinkingLevel, int connectMs, int readMs,
                                    Map<String, String> extraHeaders,
                                    Map<String, Object> extraBody,
                                    String prompt) {
        if ("anthropic-messages".equals(api)) {
            return TryResult.failed(trimSlash(baseUrl), "Anthropic 协议的测试调用尚未接入");
        }
        if ("openai-responses".equals(api)) {
            return TryResult.failed(trimSlash(baseUrl), "Responses 协议的测试调用尚未接入");
        }
        String url = trimSlash(baseUrl) + "/chat/completions";
        String payload = chatPayload(modelId, thinkingFormat, maxTokens, thinkingLevel, extraBody,
                prompt);
        int read = readMs <= 0 ? 30000 : Math.min(readMs, 60000);
        int connect = connectMs <= 0 ? 10000 : connectMs;
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json; charset=utf-8");
        mergeExtraHeaders(headers, extraHeaders);
        // 先拼好真实请求头，再据此生成回显：这样自定义头也能在页面上看到。
        String requestText = requestDump("POST", url, headers, payload, apiKey);
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
        return probe(http, baseUrl, apiKey, connectMs, readMs, null);
    }

    /**
     * 探测连通，带上供应商自定义请求头。
     *
     * @param http         出站执行器，可为 null
     * @param baseUrl      根地址
     * @param apiKey       生效密钥
     * @param connectMs    连接超时
     * @param readMs       读取超时
     * @param extraHeaders 供应商级请求头，可为 null
     * @return 探测结果
     */
    public static ProbeResult probe(HttpExecutor http, String baseUrl, String apiKey,
                                    int connectMs, int readMs, Map<String, String> extraHeaders) {
        String url = trimSlash(baseUrl) + "/models";
        int read = readMs <= 0 ? MAX_READ_MS : Math.min(readMs, MAX_READ_MS);
        int connect = connectMs <= 0 ? 10000 : connectMs;
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + apiKey);
        mergeExtraHeaders(headers, extraHeaders);
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
            return ((RawHttpExecutor) http).executeRaw(request);
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

    /**
     * 拼「这次发了什么」的展示文本。
     *
     * <p>必须用真实的请求头，不能写死。早先这里固定输出
     * {@code Content-Type: ...} 与 {@code Authorization: Bearer ***} 两行，
     * 于是管理页配的自定义请求头在回显里根本看不到，用户会以为没生效。</p>
     *
     * <p>密钥在这里统一脱敏：真实值替换成 {@code ***}，避免把密钥回显到浏览器。</p>
     *
     * @param method  方法
     * @param url     完整地址
     * @param headers 真实请求头
     * @param payload 真实请求体
     * @param apiKey  生效密钥，用于脱敏
     * @return 可展示的请求文本
     */
    static String requestDump(String method, String url, Map<String, String> headers,
                              String payload, String apiKey) {
        StringBuilder text = new StringBuilder();
        text.append(method).append(" ").append(url).append('\n');
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue();
                if (isAuthorization(entry.getKey()) && apiKey != null && !apiKey.isEmpty()) {
                    value = value.replace(apiKey, "***");
                }
                text.append(entry.getKey()).append(": ").append(value).append('\n');
            }
        }
        text.append('\n').append(payload == null ? "" : payload);
        return redact(text.toString(), apiKey);
    }

    /**
     * @param name 头名
     * @return 是否是授权头（忽略大小写）
     */
    private static boolean isAuthorization(String name) {
        return name != null && "authorization".equalsIgnoreCase(name.trim());
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
        return chatPayload(modelId, thinkingFormat, maxTokens, thinkingLevel, null);
    }

    /**
     * 拼测试调用的请求体。
     *
     * @param modelId        模型 id
     * @param thinkingFormat 思考格式
     * @param maxTokens      输出上限
     * @param thinkingLevel  思考档
     * @param extraBody      附加字段，会深合并进请求体；可为 null
     * @return JSON 文本
     */
    static String chatPayload(String modelId, String thinkingFormat, Integer maxTokens,
                              String thinkingLevel, Map<String, Object> extraBody) {
        return chatPayload(modelId, thinkingFormat, maxTokens, thinkingLevel, extraBody, null);
    }

    /**
     * 拼测试调用的请求体。
     *
     * @param modelId        模型 id
     * @param thinkingFormat 思考格式
     * @param maxTokens      输出上限
     * @param thinkingLevel  思考档
     * @param extraBody      附加字段，会深合并进请求体；可为 null
     * @param prompt         用户消息；空时用 {@link #DEFAULT_PROBE_PROMPT}
     * @return JSON 文本
     */
    static String chatPayload(String modelId, String thinkingFormat, Integer maxTokens,
                              String thinkingLevel, Map<String, Object> extraBody,
                              String prompt) {
        int tokens = maxTokens == null || maxTokens.intValue() <= 0 ? 8192 : maxTokens.intValue();
        String text = prompt == null || prompt.trim().isEmpty() ? DEFAULT_PROBE_PROMPT : prompt;
        String level = normalizeThinkingLevel(thinkingLevel);
        boolean enabled = !"off".equals(level);
        String format = thinkingFormat == null || thinkingFormat.trim().isEmpty()
                ? "none" : thinkingFormat.trim();
        StringBuilder json = new StringBuilder();
        json.append("{\"model\":\"").append(escapeJson(modelId)).append("\",");
        json.append("\"messages\":[{\"role\":\"user\",\"content\":\"")
                .append(escapeJson(text)).append("\"}],");
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
        if (extraBody == null || extraBody.isEmpty()) {
            return json.toString();
        }
        // 与网关的 TransportSupport.merge 同语义：Map 递归合并，其他类型后者覆盖。
        ObjectNode body = (ObjectNode) AiLiteJson.readTree(json.toString());
        mergeDeep(body, extraBody);
        return AiLiteJson.toJson(body);
    }

    /**
     * 深合并：嵌套对象递归合并，其余按键覆盖。与网关侧实现保持一致。
     *
     * @param target 目标 JSON 对象
     * @param source 待合并的 Map
     */
    @SuppressWarnings("unchecked")
    private static void mergeDeep(ObjectNode target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Object value = entry.getValue();
            JsonNode existing = target.get(entry.getKey());
            if (value instanceof Map && existing instanceof ObjectNode) {
                mergeDeep((ObjectNode) existing, (Map<String, Object>) value);
            } else {
                target.set(entry.getKey(), AiLiteJson.mapper().valueToTree(value));
            }
        }
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
