package io.github.yinwenyuan28.ailite.admin.web;

import io.github.yinwenyuan28.ailite.admin.call.CallLogQuery;
import io.github.yinwenyuan28.ailite.admin.jdbc.CallBodySettings;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcCallBodyPolicy;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;
import io.github.yinwenyuan28.ailite.admin.sync.ModelSyncClient;
import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.config.ModelResolver;
import io.github.yinwenyuan28.ailite.llm.LlmTransport;
import io.github.yinwenyuan28.ailite.llm.ResolvedModel;
import io.github.yinwenyuan28.ailite.http.HttpExecutor;
import io.github.yinwenyuan28.ailite.json.AiLiteJson;
import io.github.yinwenyuan28.ailite.llm.CallBodyPolicy;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.prompt.PromptException;
import io.github.yinwenyuan28.ailite.prompt.PromptManager;
import io.github.yinwenyuan28.ailite.prompt.PromptStatus;
import io.github.yinwenyuan28.ailite.prompt.PromptVersion;
import io.github.yinwenyuan28.ailite.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 管理 REST。密钥只用于出站请求，响应里只放脱敏结果。自带页面在 {@code ai-lite-admin-ui}。
 */
@RestController
public class AdminController {

    static final String UNKNOWN_PROVIDER = "未知供应商，须先在配置文件声明";
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final String ACTOR = "admin";
    private static final Set<String> THINKING_FORMATS = new HashSet<String>(Arrays.asList(
            "none", "chat-template", "deepseek", "qwen",
            "reasoning-effort", "anthropic-budget", "anthropic-adaptive"));
    /** 模型可以接收的输入类型。含 image 时调用方才允许发送图片消息。 */
    private static final Set<String> INPUT_MODES = new HashSet<String>(Arrays.asList(
            "text", "image", "audio", "video", "file"));
    /** 可覆盖的供应商协议。与 transport 注册的 api 一致。 */
    private static final Set<String> SUPPORTED_APIS = new HashSet<String>(Arrays.asList(
            "openai-completions", "openai-responses", "anthropic-messages"));

    private final AiLiteConfig config;
    private final JdbcRuntimeCatalog catalog;
    private final PromptManager prompts;
    private final JdbcTemplate jdbc;
    private final String callTable;
    private final String callBodyTable;
    private final CallBodySettings bodyPolicy;
    private final HttpExecutor http;
    /** 模型解析器；为 null 时「测试调用」退回手写实现（只支持 OpenAI 兼容协议）。 */
    private final ModelResolver resolver;
    /** 协议适配器，按 api 注册。为空时同上。 */
    private final Map<String, LlmTransport> transports;

    /**
     * 兼容构造器：不传执行器时诊断走 HttpURLConnection 兜底。
     *
     * <p>生产装配必须用带 {@link HttpExecutor} 的构造器——只有那样"测试连通"
     * 与"测试调用"才与真实模型调用走同一条出站链路。</p>
     *
     * @param config     配置文件里的供应商与业务 id
     * @param catalog    运行时目录
     * @param prompts    提示词管理器，可为 null
     * @param jdbc       调用记录查询
     * @param callTable  调用记录表名，未配置时为 null
     * @param bodyPolicy 输入输出记录档位，可为 null
     */
    public AdminController(AiLiteConfig config, JdbcRuntimeCatalog catalog, PromptManager prompts,
                           JdbcTemplate jdbc, String callTable,
                           JdbcCallBodyPolicy bodyPolicy) {
        this(config, catalog, prompts, jdbc, callTable, null,
                (CallBodySettings) bodyPolicy, null);
    }

    /**
     * 兼容构造器：档位策略按接口接收，便于注入容器里带自定义表名的 Bean。
     *
     * @param config     配置文件里的供应商与业务 id
     * @param catalog    运行时目录
     * @param prompts    提示词管理器，可为 null
     * @param jdbc       调用记录查询
     * @param callTable  调用记录表名，未配置时为 null
     * @param bodyPolicy 输入输出记录档位，可为 null
     * @param http       出站执行器（配置启用的传输）；null 表示退化为 HttpURLConnection
     */
    public AdminController(AiLiteConfig config, JdbcRuntimeCatalog catalog, PromptManager prompts,
                           JdbcTemplate jdbc, String callTable,
                           JdbcCallBodyPolicy bodyPolicy, HttpExecutor http) {
        this(config, catalog, prompts, jdbc, callTable, null,
                (CallBodySettings) bodyPolicy, http);
    }

    /**
     * @param config     配置文件里的供应商与业务 id
     * @param catalog    运行时目录
     * @param prompts    提示词管理器，可为 null
     * @param jdbc       调用记录查询
     * @param callTable  调用记录表名，未配置时为 null
     * @param bodyPolicy 输入输出记录档位（接口类型，可注入配置了表名的实现），可为 null
     * @param http       出站执行器（配置启用的传输）；null 表示退化为 HttpURLConnection
     */
    public AdminController(AiLiteConfig config, JdbcRuntimeCatalog catalog, PromptManager prompts,
                           JdbcTemplate jdbc, String callTable,
                           CallBodySettings bodyPolicy, HttpExecutor http) {
        this(config, catalog, prompts, jdbc, callTable, null, bodyPolicy, http);
    }

    /**
     * @param config        配置文件里的供应商与业务 id
     * @param catalog       运行时目录
     * @param prompts       提示词管理器，可为 null
     * @param jdbc          调用记录查询
     * @param callTable     调用记录表名，未配置时为 null
     * @param callBodyTable 调用正文表名；null 表示按摘要表名推导
     * @param bodyPolicy    输入输出记录档位（接口类型，可注入配置了表名的实现），可为 null
     * @param http          出站执行器（配置启用的传输）；null 表示退化为 HttpURLConnection
     */
    public AdminController(AiLiteConfig config, JdbcRuntimeCatalog catalog, PromptManager prompts,
                           JdbcTemplate jdbc, String callTable, String callBodyTable,
                           CallBodySettings bodyPolicy, HttpExecutor http) {
        this(config, catalog, prompts, jdbc, callTable, callBodyTable, bodyPolicy, http,
                null, null);
    }

    /**
     * 完整构造器：额外接收解析器与协议适配器，「测试调用」才能走与业务一致的链路。
     *
     * @param config        配置文件里的供应商与业务 id
     * @param catalog       运行时目录
     * @param prompts       提示词管理器，可为 null
     * @param jdbc          调用记录查询
     * @param callTable     调用记录表名，未配置时为 null
     * @param callBodyTable 调用正文表名；null 表示按摘要表名推导
     * @param bodyPolicy    调用正文档位
     * @param http          出站执行器，可为 null
     * @param resolver      模型解析器，可为 null；为 null 时测试调用退回手写实现
     * @param transports    协议适配器，可为 null；为空时同上
     */
    public AdminController(AiLiteConfig config, JdbcRuntimeCatalog catalog, PromptManager prompts,
                           JdbcTemplate jdbc, String callTable, String callBodyTable,
                           CallBodySettings bodyPolicy, HttpExecutor http,
                           ModelResolver resolver, Map<String, LlmTransport> transports) {
        this.config = config;
        this.catalog = catalog;
        this.prompts = prompts;
        this.jdbc = jdbc;
        this.callTable = callTable;
        this.callBodyTable = callBodyTable;
        this.bodyPolicy = bodyPolicy;
        this.http = http;
        this.resolver = resolver;
        this.transports = transports == null
                ? Collections.<String, LlmTransport>emptyMap() : transports;
    }

    /**
     * @return 配置文件中的供应商，附带覆盖后的生效值和模型行
     */
    @GetMapping("/ai-lite/admin/api/providers")
    public Map<String, Object> providers() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, AiLiteConfig.Provider> entry : config.getLlm().getProviders().entrySet()) {
            rows.add(providerView(entry.getKey(), entry.getValue()));
        }
        return ok(rows);
    }

    /**
     * 空字段表示不改。写库后清本机缓存。
     *
     * @param name 供应商 id
     * @param body 覆盖字段
     * @return 更新后的供应商视图
     */
    @PostMapping("/ai-lite/admin/api/providers/{name}/overlay")
    public Map<String, Object> saveOverlay(@PathVariable("name") String name,
                                           @RequestBody(required = false) Map<String, Object> body) {
        AiLiteConfig.Provider provider = requireProvider(name);
        ProviderOverlay current = catalog.findProvider(name);
        catalog.saveOverlay(merge(name, current, body));
        return ok(providerView(name, provider));
    }

    /**
     * @param name 供应商 id
     * @return 清除覆盖后的供应商视图
     */
    @PostMapping("/ai-lite/admin/api/providers/{name}/overlay/clear")
    public Map<String, Object> clearOverlay(@PathVariable("name") String name) {
        AiLiteConfig.Provider provider = requireProvider(name);
        catalog.clearOverlay(name);
        return ok(providerView(name, provider));
    }

    /**
     * 探测连通。不写调用日志，不改模型表。
     *
     * @param name 供应商 id
     * @return 探测结果；HTTP 失败时外层仍成功，结果里的 ok 为 false
     */
    @PostMapping("/ai-lite/admin/api/providers/{name}/connectivity")
    public Map<String, Object> connectivity(@PathVariable("name") String name) {
        AiLiteConfig.Provider provider = requireProvider(name);
        Endpoint endpoint = endpoint(name, provider);
        ModelSyncClient.ProbeResult probe = ModelSyncClient.probe(
                http, endpoint.baseUrl, endpoint.apiKey, endpoint.connectMs, endpoint.readMs,
                endpoint.headers);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("url", probe.getCheckedUrl());
        data.put("httpStatus", Integer.valueOf(probe.getHttpStatus()));
        data.put("latencyMs", Long.valueOf(probe.getLatencyMs()));
        data.put("ok", Boolean.valueOf(probe.isOk()));
        data.put("message", probe.getMessage());
        return ok(data);
    }

    /**
     * 读取远端模型名单，不写库，也不删除本地已有模型。
     *
     * @param name 供应商 id
     * @return 远端 id，以及是否已经在本地列表里
     */
    @PostMapping("/ai-lite/admin/api/providers/{name}/models/sync")
    public Map<String, Object> syncModels(@PathVariable("name") String name) {
        AiLiteConfig.Provider provider = requireProvider(name);
        Endpoint endpoint = endpoint(name, provider);
        List<String> remote = ModelSyncClient.listRemote(
                http, endpoint.api, endpoint.baseUrl, endpoint.apiKey, endpoint.headers);
        Set<String> stored = new HashSet<String>();
        for (CatalogModel model : catalog.listModels(name)) {
            stored.add(model.getModelId());
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (String id : remote) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("modelId", id);
            row.put("stored", Boolean.valueOf(stored.contains(id)));
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("models", rows);
        return ok(data);
    }

    /**
     * 把用户勾选的远端模型加入本地列表。已有的不改，未勾选的不删。
     *
     * @param name 供应商 id
     * @param body 含 modelIds
     * @return 新插入数
     */
    @PostMapping("/ai-lite/admin/api/providers/{name}/models/adopt")
    public Map<String, Object> adoptModels(@PathVariable("name") String name,
                                           @RequestBody(required = false) Map<String, Object> body) {
        requireProvider(name);
        Object raw = body == null ? null : body.get("modelIds");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            throw new IllegalArgumentException("请选择要加入的模型");
        }
        int added = 0;
        for (Object item : (List<?>) raw) {
            String modelId = text(item);
            if (modelId.isEmpty() || modelId.length() > 128) {
                throw new IllegalArgumentException("模型 id 无效");
            }
            if (catalog.insertModelIfAbsent(name, modelId)) {
                added++;
            }
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("added", Integer.valueOf(added));
        return ok(data);
    }

    /**
     * 用户删除一个模型。仍被业务使用时拒绝。
     *
     * @param name    供应商 id
     * @param modelId 模型 id
     * @return 空数据
     */
    @DeleteMapping("/ai-lite/admin/api/providers/{name}/models/{modelId:.+}")
    public Map<String, Object> deleteModel(@PathVariable("name") String name,
                                           @PathVariable("modelId") String modelId) {
        requireProvider(name);
        catalog.deleteModel(name, modelId);
        return ok(Boolean.TRUE);
    }

    /**
     * 用当前生效的配置向该模型发一次很短的请求。不写调用日志，不改模型表。
     *
     * <p>走真实的协议适配器（与业务调用同一套请求构造），因此三种协议都支持、
     * 请求头与请求体的拼装规则完全一致。只有两处不同：不写调用日志、不过出口保护——
     * 测试不应污染业务调用记录，也不该占用业务的并发许可。</p>
     *
     * <p>容器里没有解析器或适配器时（单独引入 admin-api 的场景），退回手写的
     * OpenAI 兼容实现，并在响应里注明降级原因。</p>
     *
     * @param name    供应商 id
     * @param modelId 模型 id
     * @return 是否成功、耗时和回复摘要
     */
    @PostMapping("/ai-lite/admin/api/providers/{name}/models/{modelId:.+}/try")
    public Map<String, Object> tryModel(@PathVariable("name") String name,
                                        @PathVariable("modelId") String modelId,
                                        @RequestBody(required = false) Map<String, Object> body) {
        AiLiteConfig.Provider provider = requireProvider(name);
        Endpoint endpoint = endpoint(name, provider);
        CatalogModel model = catalog.findModel(name, modelId);
        String format = model == null ? null : model.getThinkingFormat();
        Integer maxTokens = model == null ? null : model.getMaxTokens();
        String thinkingLevel = text(body == null ? null : body.get("thinkingLevel"));
        if (thinkingLevel.isEmpty()) {
            thinkingLevel = LlmThinkingLevel.OFF.wire();
        } else {
            thinkingLevel = LlmThinkingLevel.parse(thinkingLevel).wire();
        }
        // prompt 可空：留空时用短的默认问候，避免把空消息发出去。
        String prompt = body == null ? null : nullableText(body.get("prompt"));
        ModelSyncClient.TryResult result = tryViaTransport(name, modelId, thinkingLevel, prompt,
                endpoint, model, maxTokens);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("ok", Boolean.valueOf(result.isOk()));
        data.put("httpStatus", Integer.valueOf(result.getHttpStatus()));
        data.put("latencyMs", Long.valueOf(result.getLatencyMs()));
        data.put("content", result.getContent());
        data.put("message", result.getMessage());
        data.put("request", result.getRequestText());
        data.put("response", result.getResponseText());
        return ok(data);
    }

    /**
     * 优先用真实协议适配器发测试请求；条件不满足时退回手写实现。
     *
     * <p>退回条件：容器里没有 {@code ModelResolver}、没有对应协议的适配器，
     * 或解析失败（例如供应商在配置文件里没有模型、目录里也没有这一行）。
     * 退回时请求内容可能和业务调用有差异，这一点会写在响应里，不静默降级。</p>
     *
     * @param name          供应商 id
     * @param modelId       模型 id
     * @param thinkingLevel 思考档
     * @param prompt        用户消息，可空
     * @param endpoint      生效端点（含合并后的请求头）
     * @param model         目录行，可为 null
     * @param maxTokens     输出上限
     * @return 测试结果
     */
    private ModelSyncClient.TryResult tryViaTransport(String name, String modelId,
                                                      String thinkingLevel, String prompt,
                                                      Endpoint endpoint,
                                                      CatalogModel model, Integer maxTokens) {
        if (resolver != null) {
            try {
                ResolvedModel resolved = resolver.resolveExplicit(name, modelId, thinkingLevel, true);
                LlmTransport transport = transports.get(resolved.getApi());
                if (transport != null) {
                    return ModelSyncClient.tryChatViaTransport(
                            transport, resolved, prompt, maxTokens, 60000);
                }
            } catch (RuntimeException ex) {
                // 解析失败（供应商没有模型、目录没有这一行等）：退回手写实现，
                // 保证「测试」这个动作至少还能用。
                log.debug("测试调用解析失败，退回手写实现: {}", ex.toString());
            }
        }
        return fallbackTry(name, modelId, thinkingLevel, prompt, endpoint, model);
    }

    /**
     * 老路径：手写 OpenAI 兼容请求。只在没有解析器或适配器时使用。
     *
     * @param name          供应商 id
     * @param modelId       模型 id
     * @param thinkingLevel 思考档
     * @param prompt        用户消息，可空
     * @param endpoint      生效端点
     * @param model         目录行
     * @return 测试结果
     */
    private ModelSyncClient.TryResult fallbackTry(String name, String modelId,
                                                  String thinkingLevel, String prompt,
                                                  Endpoint endpoint, CatalogModel model) {
        String format = model == null ? null : model.getThinkingFormat();
        Integer maxTokens = model == null ? null : model.getMaxTokens();
        return ModelSyncClient.tryChat(
                http, endpoint.api, endpoint.baseUrl, endpoint.apiKey, modelId, format, maxTokens,
                thinkingLevel, endpoint.connectMs, endpoint.readMs, endpoint.headers,
                effectiveExtraBody(name, requireProvider(name), model, thinkingLevel), prompt);
    }

    /**
     * 合并两级附加请求体，供「测试调用」使用。
     *
     * <p>合并顺序与网关一致：供应商级在前、模型级覆盖同名键，且嵌套对象递归合并。
     * 层级缺失时用配置文件里的那份，与真实调用相同的回落规则。</p>
     *
     * @param name     供应商 id，用于查覆盖表
     * @param provider 配置文件里的供应商
     * @param model    目录行；null 表示模型还没建
     * @return 合并结果，可能为空 Map
     */
    private Map<String, Object> effectiveExtraBody(String name, AiLiteConfig.Provider provider,
                                                   CatalogModel model, String thinkingLevel) {
        ProviderOverlay overlay = catalog.findProvider(name);
        Map<String, Object> providerLevel = overlay != null && overlay.getExtraBody() != null
                ? overlay.getExtraBody() : provider.getExtraBody();
        Map<String, Object> merged = new LinkedHashMap<String, Object>();
        deepMerge(merged, providerLevel);
        if (model != null) {
            // 采样参数与附加字段在真实调用里都进请求体，测试调用带同样的键，
            // 否则页面上显示已配置、测试时却一个都没发出去。
            deepMerge(merged, model.getSamplerParams());
            if (model.getSamplerParamsByLevel() != null) {
                deepMerge(merged, model.getSamplerParamsByLevel().get(thinkingLevel));
            }
            deepMerge(merged, model.getExtraBody());
        }
        return merged;
    }

    /**
     * 深合并到一个可写的 Map 上：嵌套 Map 递归，其他值覆盖。
     *
     * @param target 目标
     * @param source 来源，可为 null
     */
    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Object incoming = entry.getValue();
            Object existing = target.get(entry.getKey());
            if (incoming instanceof Map && existing instanceof Map) {
                deepMerge((Map<String, Object>) existing, (Map<String, Object>) incoming);
            } else {
                target.put(entry.getKey(), incoming);
            }
        }
    }

    /**
     * @return 思考格式的可选值和说明
     */
    @GetMapping("/ai-lite/admin/api/thinking-formats")
    public Map<String, Object> thinkingFormats() {
        return ok(thinkingFormatOptions());
    }

    /**
     * 可选的供应商协议，以及各自追加的路径。
     *
     * <p>页面拿它渲染下拉；同时把「base-url 之后会接什么」讲清楚，
     * 避免用户把完整端点写进 base-url 导致路径重复。</p>
     *
     * @return 协议 id、中文名、追加路径和说明
     */
    @GetMapping("/ai-lite/admin/api/apis")
    public Map<String, Object> apis() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(apiOption("openai-completions", "OpenAI Chat Completions", "/chat/completions",
                "最常见的 OpenAI 兼容接口。base-url 写到 /v1 即可。"));
        rows.add(apiOption("openai-responses", "OpenAI Responses", "/responses",
                "OpenAI 新版 Responses 协议。base-url 写到 /v1 即可。"));
        rows.add(apiOption("anthropic-messages", "Anthropic Messages", "/v1/messages",
                "Anthropic 协议。本库会自己追加 /v1/messages，base-url 不要再带 /v1。"));
        return ok(rows);
    }

    private static Map<String, Object> apiOption(String id, String label, String path, String detail) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("label", label);
        row.put("path", path);
        row.put("detail", detail);
        return row;
    }

    /**
     * 手工添加一个未就绪模型。
     *
     * @param name 供应商 id
     * @param body 含 modelId
     * @return 新行
     */
    @PostMapping("/ai-lite/admin/api/providers/{name}/models")
    public Map<String, Object> addModel(@PathVariable("name") String name,
                                        @RequestBody(required = false) Map<String, Object> body) {
        requireProvider(name);
        String modelId = text(body == null ? null : body.get("modelId"));
        if (modelId.isEmpty() || modelId.length() > 128) {
            throw new IllegalArgumentException("请填写模型 id");
        }
        if (!catalog.insertModelIfAbsent(name, modelId)) {
            throw new IllegalArgumentException("模型已存在");
        }
        return ok(modelView(catalog.findModel(name, modelId), false));
    }

    /**
     * 保存能力参数。**不改变就绪状态**，启用与否走 {@code PUT .../ready}。
     *
     * <p>除四个必填参数外，还可选地带 {@code inputModes}（如 {@code ["text","image"]}）
     * 和 {@code displayName}。入参类型决定模型能否接收图片：只有包含 {@code image}
     * 时调用方才被允许发送图片消息。显示名只用于展示，不参与请求。</p>
     *
     * @param name    供应商 id
     * @param modelId 模型 id
     * @param body    推理开关、上下文窗口、输出上限、思考格式；可选入参类型与显示名
     * @return 更新后的模型行
     */
    @PutMapping("/ai-lite/admin/api/providers/{name}/models/{modelId:.+}")
    public Map<String, Object> saveCapabilities(@PathVariable("name") String name,
                                                @PathVariable("modelId") String modelId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        requireProvider(name);
        if (body == null || !body.containsKey("reasoning") || body.get("reasoning") == null
                || !body.containsKey("contextWindow") || body.get("contextWindow") == null
                || !body.containsKey("maxTokens") || body.get("maxTokens") == null
                || text(body.get("thinkingFormat")).isEmpty()) {
            throw new IllegalArgumentException("请补齐能力参数：推理开关、上下文窗口、输出上限、思考格式");
        }
        boolean reasoning = bool(body.get("reasoning"));
        int contextWindow = integer(body.get("contextWindow"), "上下文窗口");
        int maxTokens = integer(body.get("maxTokens"), "输出上限");
        String format = text(body.get("thinkingFormat"));
        if (contextWindow < 0) {
            throw new IllegalArgumentException("上下文窗口不能为负");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("输出上限必须大于 0");
        }
        if (!THINKING_FORMATS.contains(format)) {
            throw new IllegalArgumentException("未知思考格式: " + format);
        }
        List<String> inputModes = inputModes(body.get("inputModes"));
        String displayName = body.containsKey("displayName")
                ? text(body.get("displayName")) : null;
        // 五个请求参数：键不出现表示不改（内部用 null 传递），出现空对象表示明确清空。
        catalog.saveCapabilities(name, modelId, reasoning, inputModes, contextWindow, maxTokens,
                format, displayName,
                stringMapOrNull(body.get("thinkingLevelMap"), "thinkingLevelMap", true),
                objectMapOrNull(body.get("samplerParams"), "samplerParams"),
                nestedMapOrNull(body.get("samplerParamsByLevel"), "samplerParamsByLevel"),
                stringMapOrNull(body.get("modelHeaders"), "modelHeaders", false),
                objectMapOrNull(body.get("extraBody"), "extraBody"));
        return ok(modelView(catalog.findModel(name, modelId), false));
    }

    /**
     * 启用或停用模型。就绪状态只由这个接口改变，与能力参数是否填齐无关。
     *
     * <p>停用不会解除已有业务绑定，只是让下一次调用在解析阶段失败——
     * 静默解绑会让业务在无人察觉时失去模型。</p>
     *
     * @param name    供应商 id
     * @param modelId 模型 id
     * @param body    {@code {"ready": true|false}}
     * @return 更新后的模型行
     */
    @PutMapping("/ai-lite/admin/api/providers/{name}/models/{modelId:.+}/ready")
    public Map<String, Object> setModelReady(@PathVariable("name") String name,
                                             @PathVariable("modelId") String modelId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        requireProvider(name);
        if (body == null || !body.containsKey("ready") || body.get("ready") == null) {
            throw new IllegalArgumentException("请指定 ready（true 或 false）");
        }
        catalog.setReady(name, modelId, bool(body.get("ready")));
        return ok(modelView(catalog.findModel(name, modelId), false));
    }

    /**
     * @return 白名单中的业务，附带绑定和是否已有生效提示词
     */
    @GetMapping("/ai-lite/admin/api/biz")
    public Map<String, Object> bizList() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (String bizId : bizIds()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("bizId", bizId);
            BizBinding binding = catalog.findBinding(bizId);
            row.put("binding", binding == null ? null : bindingView(binding));
            row.put("promptActive", Boolean.valueOf(prompts != null && prompts.current(bizId).isPresent()));
            rows.add(row);
        }
        return ok(rows);
    }

    /**
     * @param bizId 业务 id
     * @param body  供应商、模型、思考档
     * @return 保存后的绑定
     */
    @PutMapping("/ai-lite/admin/api/biz/{bizId}")
    public Map<String, Object> saveBiz(@PathVariable("bizId") String bizId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        requireBiz(bizId);
        String providerId = text(body == null ? null : body.get("providerId"));
        String modelId = text(body == null ? null : body.get("modelId"));
        if (providerId.isEmpty() || modelId.isEmpty()) {
            throw new IllegalArgumentException("请选择供应商和模型");
        }
        requireProvider(providerId);
        String level = text(body.get("thinkingLevel"));
        if (level.isEmpty()) {
            level = LlmThinkingLevel.OFF.wire();
        }
        level = LlmThinkingLevel.parse(level).wire();
        catalog.saveBinding(new BizBinding(bizId, providerId, modelId, level));
        return ok(bindingView(catalog.findBinding(bizId)));
    }

    /**
     * 清除业务模型绑定。业务 id 仍在白名单中，只是不再指向模型。
     *
     * @param bizId 业务 id
     * @return 空绑定
     */
    @DeleteMapping("/ai-lite/admin/api/biz/{bizId}/binding")
    public Map<String, Object> clearBizBinding(@PathVariable("bizId") String bizId) {
        requireBiz(bizId);
        catalog.clearBinding(bizId);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("bizId", bizId);
        data.put("binding", null);
        return ok(data);
    }

    /**
     * @param bizId 业务 id
     * @return 当前生效版本、最近草稿，以及版本记录（按版本号倒序）
     */
    @GetMapping("/ai-lite/admin/api/biz/{bizId}/prompt")
    public Map<String, Object> prompt(@PathVariable("bizId") String bizId) {
        requireBiz(bizId);
        PromptManager manager = requirePrompts();
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        Optional<PromptVersion> active = manager.current(bizId);
        data.put("active", active.isPresent() ? versionView(active.get()) : null);
        data.put("draft", latestDraft(manager, bizId));
        data.put("versions", versionList(manager, bizId));
        data.put("audits", auditList(manager, bizId));
        return ok(data);
    }

    /**
     * @param bizId 业务 id
     * @return 该业务提示词的全部版本记录（按版本号倒序）
     */
    @GetMapping("/ai-lite/admin/api/biz/{bizId}/prompt/versions")
    public Map<String, Object> promptVersions(@PathVariable("bizId") String bizId) {
        requireBiz(bizId);
        return ok(versionList(requirePrompts(), bizId));
    }

    /**
     * @param bizId 业务 id
     * @return 提示词操作审计（按时间正序）
     */
    @GetMapping("/ai-lite/admin/api/biz/{bizId}/prompt/audits")
    public Map<String, Object> promptAudits(@PathVariable("bizId") String bizId) {
        requireBiz(bizId);
        return ok(auditList(requirePrompts(), bizId));
    }

    /**
     * 有 versionId 时改已有草稿；否则新建草稿。
     *
     * @param bizId 业务 id，同时作为提示词 key
     * @param body  系统/用户模板、可选 versionId、outputSchema、message
     * @return 草稿
     */
    @PostMapping("/ai-lite/admin/api/biz/{bizId}/prompt/draft")
    public Map<String, Object> draft(@PathVariable("bizId") String bizId,
                                     @RequestBody(required = false) Map<String, Object> body) {
        requireBiz(bizId);
        PromptManager manager = requirePrompts();
        String system = body == null ? "" : text(body.get("systemTemplate"));
        String user = body == null ? "" : text(body.get("userTemplate"));
        String schema = body == null ? null : nullableText(body.get("outputSchema"));
        String message = body == null ? null : nullableText(body.get("message"));
        String versionId = body == null ? "" : text(body.get("versionId"));
        PromptVersion version;
        if (!versionId.isEmpty()) {
            requireOwnedVersion(manager, bizId, versionId);
            version = manager.saveDraft(versionId, system, user, schema, ACTOR, message);
        } else {
            version = manager.createDraft(bizId, system, user, schema, ACTOR, message);
        }
        return ok(versionView(version));
    }

    /**
     * 用编辑区模板和变量做本地预览，不改版本。
     *
     * @param bizId 业务 id
     * @param body  systemTemplate、userTemplate、vars
     * @return 渲染后的系统与用户文本
     */
    @PostMapping("/ai-lite/admin/api/biz/{bizId}/prompt/preview")
    public Map<String, Object> preview(@PathVariable("bizId") String bizId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        requireBiz(bizId);
        requirePrompts();
        String system = body == null ? "" : text(body.get("systemTemplate"));
        String user = body == null ? "" : text(body.get("userTemplate"));
        Map<String, String> vars = stringMap(body == null ? null : body.get("vars"));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("system", PromptManager.apply(system, vars));
        data.put("user", PromptManager.apply(user, vars));
        return ok(data);
    }

    /**
     * 发布指定草稿，并使本机提示词缓存失效。
     *
     * @param bizId 业务 id
     * @param body  含 versionId
     * @return 已发布版本
     */
    @PostMapping("/ai-lite/admin/api/biz/{bizId}/prompt/publish")
    public Map<String, Object> publish(@PathVariable("bizId") String bizId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        requireBiz(bizId);
        PromptManager manager = requirePrompts();
        String versionId = text(body == null ? null : body.get("versionId"));
        if (versionId.isEmpty()) {
            throw new IllegalArgumentException("请指定要发布的版本");
        }
        boolean owned = false;
        for (PromptVersion version : manager.versions(bizId)) {
            if (versionId.equals(version.getVersionId())) {
                owned = true;
                break;
            }
        }
        if (!owned) {
            throw new IllegalArgumentException("版本不属于该业务");
        }
        PromptVersion published = manager.publish(versionId, ACTOR);
        return ok(versionView(published));
    }

    /**
     * 将某个已发布或已退役的版本重新置为生效。
     *
     * @param bizId 业务 id
     * @param body  含 versionId
     * @return 重新生效的版本
     */
    @PostMapping("/ai-lite/admin/api/biz/{bizId}/prompt/rollback")
    public Map<String, Object> rollback(@PathVariable("bizId") String bizId,
                                        @RequestBody(required = false) Map<String, Object> body) {
        requireBiz(bizId);
        PromptManager manager = requirePrompts();
        String versionId = text(body == null ? null : body.get("versionId"));
        if (versionId.isEmpty()) {
            throw new IllegalArgumentException("请指定要回滚的版本");
        }
        boolean owned = false;
        for (PromptVersion version : manager.versions(bizId)) {
            if (versionId.equals(version.getVersionId())) {
                owned = true;
                break;
            }
        }
        if (!owned) {
            throw new IllegalArgumentException("版本不属于该业务");
        }
        return ok(versionView(manager.rollback(versionId, ACTOR)));
    }

    /**
     * @return 当前输入输出记录档位，以及三个可选项
     */
    @GetMapping("/ai-lite/admin/api/calls/body-level")
    public Map<String, Object> bodyLevel() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("level", bodyPolicy == null ? CallBodyPolicy.NONE : bodyPolicy.level());
        data.put("options", bodyLevelOptions());
        return ok(data);
    }

    /**
     * @param body 含 level：none、failure 或 all
     * @return 保存后的档位
     */
    @PutMapping("/ai-lite/admin/api/calls/body-level")
    public Map<String, Object> saveBodyLevel(@RequestBody(required = false) Map<String, Object> body) {
        if (bodyPolicy == null) {
            throw new IllegalStateException("调用记录档位不可用");
        }
        bodyPolicy.save(text(body == null ? null : body.get("level")));
        return bodyLevel();
    }

    /**
     * @param bizId    业务 id，可空
     * @param provider 供应商 id，可空
     * @param modelId  模型 id，可空
     * @param success  true/false，可空表示不过滤
     * @param limit    条数，默认 50，上限 200
     * @param offset   偏移，默认 0
     * @return items / total / limit / offset；开启正文记录时附带输入输出
     */
    @GetMapping("/ai-lite/admin/api/calls")
    public Map<String, Object> calls(@RequestParam(value = "bizId", required = false) String bizId,
                                     @RequestParam(value = "provider", required = false) String provider,
                                     @RequestParam(value = "modelId", required = false) String modelId,
                                     @RequestParam(value = "success", required = false) Boolean success,
                                     @RequestParam(value = "limit", required = false) Integer limit,
                                     @RequestParam(value = "offset", required = false) Integer offset) {
        int size = limit == null ? 50 : limit.intValue();
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("limit 必须在 1 到 200 之间");
        }
        int from = offset == null ? 0 : offset.intValue();
        if (from < 0) {
            throw new IllegalArgumentException("offset 不能为负");
        }
        return ok(CallLogQuery.page(jdbc, callTable, callBodyTable, bizId, provider, modelId,
                success, size, from));
    }

    /**
     * @param callId 调用 id
     * @return 单条摘要；找不到时 400
     */
    @GetMapping("/ai-lite/admin/api/calls/{callId}")
    public Map<String, Object> callDetail(@PathVariable("callId") String callId) {
        Map<String, Object> row = CallLogQuery.find(jdbc, callTable, callBodyTable, callId);
        if (row == null) {
            throw new IllegalArgumentException("调用记录不存在");
        }
        return ok(row);
    }

    /**
     * @param ex 参数或业务规则错误
     * @return 400，正文不含密钥
     */
    @ExceptionHandler({
            IllegalArgumentException.class,
            IllegalStateException.class,
            PromptException.class,
            LlmCallException.class
    })
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException ex) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ok", Boolean.FALSE);
        body.put("message", ex.getMessage() == null ? "请求无效" : ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private Map<String, Object> providerView(String name, AiLiteConfig.Provider provider) {
        ProviderOverlay overlay = catalog.findProvider(name);
        Endpoint endpoint = describe(provider, overlay);
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("api", endpoint.api);
        row.put("configApi", provider.getApi());
        row.put("apiOverridden", Boolean.valueOf(overlay != null
                && Strings.isNotBlank(overlay.getApi())));
        row.put("baseUrl", endpoint.baseUrl);
        row.put("apiKeyMasked", mask(endpoint.apiKey));
        row.put("apiKeyConfigured", Boolean.valueOf(!endpoint.apiKey.isEmpty()));
        row.put("keyPool", Boolean.valueOf(hasPool(provider)));
        row.put("connectTimeoutMs", Integer.valueOf(endpoint.connectMs));
        row.put("readTimeoutMs", Integer.valueOf(endpoint.readMs));
        row.put("overlay", Boolean.valueOf(overlay != null));
        // 生效值：覆盖表有值用覆盖表，否则用配置文件。页面据此显示当前实际会发出什么。
        Map<String, String> effectiveHeaders = overlay != null && overlay.getHeaders() != null
                ? overlay.getHeaders() : provider.getHeaders();
        Map<String, Object> effectiveExtra = overlay != null && overlay.getExtraBody() != null
                ? overlay.getExtraBody() : provider.getExtraBody();
        row.put("providerHeaders", effectiveHeaders);
        row.put("providerExtraBody", effectiveExtra);
        // 让页面知道这两个值是不是来自数据库：来自配置文件的不能直接改，要先保存一次覆盖。
        row.put("providerHeadersOverridden", Boolean.valueOf(
                overlay != null && overlay.getHeaders() != null));
        row.put("providerExtraBodyOverridden", Boolean.valueOf(
                overlay != null && overlay.getExtraBody() != null));
        row.put("models", modelRows(name));
        return row;
    }

    private List<Map<String, Object>> modelRows(String providerId) {
        List<CatalogModel> models = new ArrayList<CatalogModel>(catalog.listModels(providerId));
        Collections.sort(models, new Comparator<CatalogModel>() {
            @Override
            public int compare(CatalogModel left, CatalogModel right) {
                if (left.isReady() != right.isReady()) {
                    return left.isReady() ? -1 : 1;
                }
                String a = left.getModelId() == null ? "" : left.getModelId();
                String b = right.getModelId() == null ? "" : right.getModelId();
                return a.compareTo(b);
            }
        });
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (CatalogModel model : models) {
            Integer count = counts.get(model.getModelId());
            counts.put(model.getModelId(), Integer.valueOf(count == null ? 1 : count.intValue() + 1));
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (CatalogModel model : models) {
            boolean duplicate = counts.get(model.getModelId()).intValue() > 1;
            rows.add(modelView(model, duplicate));
        }
        return rows;
    }

    private static Map<String, Object> modelView(CatalogModel model, boolean duplicate) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        if (model == null) {
            return row;
        }
        row.put("modelId", model.getModelId());
        row.put("ready", Boolean.valueOf(model.isReady()));
        row.put("reasoning", Boolean.valueOf(model.isReasoning()));
        row.put("inputModes", model.getInputModes());
        row.put("contextWindow", model.getContextWindow());
        row.put("maxTokens", model.getMaxTokens());
        row.put("thinkingFormat", model.getThinkingFormat());
        row.put("displayName", model.getDisplayName());
        row.put("thinkingLevelMap", model.getThinkingLevelMap());
        row.put("samplerParams", model.getSamplerParams());
        row.put("samplerParamsByLevel", model.getSamplerParamsByLevel());
        row.put("modelHeaders", model.getModelHeaders());
        row.put("extraBody", model.getExtraBody());
        row.put("duplicate", Boolean.valueOf(duplicate));
        return row;
    }

    private static Map<String, Object> bindingView(BizBinding binding) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("bizId", binding.getBizId());
        row.put("providerId", binding.getProviderId());
        row.put("modelId", binding.getModelId());
        row.put("thinkingLevel", binding.getThinkingLevel());
        return row;
    }

    private static Map<String, Object> versionView(PromptVersion version) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("versionId", version.getVersionId());
        row.put("versionNo", Integer.valueOf(version.getVersionNo()));
        row.put("status", version.getStatus() == null ? null : version.getStatus().wire());
        row.put("systemTemplate", version.getSystemTemplate());
        row.put("userTemplate", version.getUserTemplate());
        row.put("outputSchema", version.getOutputSchema());
        row.put("createdBy", version.getCreatedBy());
        row.put("createTime", version.getCreateTime() == null ? null
                : Long.valueOf(version.getCreateTime().getTime()));
        return row;
    }

    private static List<Map<String, Object>> versionList(PromptManager manager, String bizId) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (PromptVersion version : manager.versions(bizId)) {
            rows.add(versionView(version));
        }
        return rows;
    }

    private static List<Map<String, Object>> auditList(PromptManager manager, String bizId) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (io.github.yinwenyuan28.ailite.prompt.PromptAudit audit : manager.audits(bizId)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("auditId", audit.getAuditId());
            row.put("action", audit.getAction());
            row.put("versionId", audit.getVersionId());
            row.put("actor", audit.getActor());
            row.put("detail", audit.getDetail());
            row.put("createTime", audit.getCreateTime() == null ? null
                    : Long.valueOf(audit.getCreateTime().getTime()));
            rows.add(row);
        }
        return rows;
    }

    private static void requireOwnedVersion(PromptManager manager, String bizId, String versionId) {
        for (PromptVersion version : manager.versions(bizId)) {
            if (versionId.equals(version.getVersionId())) {
                return;
            }
        }
        throw new IllegalArgumentException("版本不属于该业务");
    }

    /**
     * 解析入参类型。只接受 text、image、audio、video、file；空或缺失表示只收文本。
     *
     * @param raw 请求体里的 inputModes，可以是数组或逗号分隔字符串
     * @return 去重后的入参类型
     */
    private static List<String> inputModes(Object raw) {
        List<String> modes = new ArrayList<String>();
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                addMode(modes, item);
            }
        } else if (raw != null) {
            for (String part : String.valueOf(raw).split(",")) {
                addMode(modes, part);
            }
        }
        return modes;
    }

    private static void addMode(List<String> modes, Object raw) {
        String mode = text(raw);
        if (mode.isEmpty()) {
            return;
        }
        if (!INPUT_MODES.contains(mode)) {
            throw new IllegalArgumentException("未知入参类型: " + mode);
        }
        if (!modes.contains(mode)) {
            modes.add(mode);
        }
    }

    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> vars = new LinkedHashMap<String, String>();
        if (!(raw instanceof Map)) {
            return vars;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            vars.put(String.valueOf(entry.getKey()),
                    entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return vars;
    }

    /* ---------------- 模型/供应商级请求参数的解析与校验 ---------------- */

    /** JSON 文本单列的长度上限。超长多半是把图片 base64 或整段提示词粘了进来。 */
    private static final int JSON_LIMIT = 16384;

    /** 模型级请求头的保留名：库自己管理授权与内容类型。 */
    private static final String[] RESERVED_HEADERS = {"Authorization", "Content-Type"};

    /** 合法的思考档名。用于校验 thinkingLevelMap 与 samplerParamsByLevel 的键。 */
    private static final Set<String> THINKING_LEVELS = new HashSet<String>(Arrays.asList(
            "off", "low", "medium", "high", "xhigh", "max"));

    /**
     * 解析字符串 Map 列。键不存在时返回 null（表示不改）。
     *
     * @param raw           请求体里的原值
     * @param field         字段名，用于报错
     * @param thinkingLevel 为 true 时校验键是合法思考档且值是字符串或 null
     * @return 解析结果；raw 为 null 时返回 null
     */
    private static Map<String, String> stringMapOrNull(Object raw, String field, boolean thinkingLevel) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException(field + " 必须是对象");
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            String key = text(entry.getKey());
            if (key.isEmpty()) {
                throw new IllegalArgumentException(field + " 存在空键");
            }
            Object value = entry.getValue();
            if (value == null) {
                if (!thinkingLevel) {
                    throw new IllegalArgumentException(field + " 的 " + key + " 值不能为空");
                }
                // 思考档映射里的 null 有明确含义：这一档不支持。必须保留，不能变成空串。
                result.put(key, null);
                continue;
            }
            if (!(value instanceof String)) {
                throw new IllegalArgumentException(field + " 的 " + key + " 值必须是字符串");
            }
            String valueText = ((String) value).trim();
            if (thinkingLevel) {
                if (!THINKING_LEVELS.contains(key)) {
                    throw new IllegalArgumentException(field + " 的档位名无效: " + key);
                }
                if (valueText.isEmpty()) {
                    // 空串等同于 null：这一档不支持。
                    result.put(key, null);
                    continue;
                }
            }
            result.put(key, valueText);
        }
        rejectReservedHeaders(field, result);
        checkJsonLength(field, result);
        return result;
    }

    /**
     * 解析任意对象 Map 列。键不存在时返回 null（表示不改）。
     *
     * @param raw   请求体里的原值
     * @param field 字段名，用于报错
     * @return 解析结果；raw 为 null 时返回 null
     */
    private static Map<String, Object> objectMapOrNull(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException(field + " 必须是对象");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            String key = text(entry.getKey());
            if (key.isEmpty()) {
                throw new IllegalArgumentException(field + " 存在空键");
            }
            result.put(key, entry.getValue());
        }
        checkJsonLength(field, result);
        return result;
    }

    /**
     * 解析「思考档 → 对象」的嵌套列。键不存在时返回 null（表示不改）。
     *
     * @param raw   请求体里的原值
     * @param field 字段名，用于报错
     * @return 解析结果；raw 为 null 时返回 null
     */
    private static Map<String, Map<String, Object>> nestedMapOrNull(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException(field + " 必须是对象");
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            String level = text(entry.getKey());
            if (!THINKING_LEVELS.contains(level)) {
                throw new IllegalArgumentException(field + " 的档位名无效: " + level);
            }
            Object value = entry.getValue();
            if (!(value instanceof Map)) {
                throw new IllegalArgumentException(field + " 的 " + level + " 值必须是对象");
            }
            Map<String, Object> inner = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> innerEntry : ((Map<?, ?>) value).entrySet()) {
                inner.put(text(innerEntry.getKey()), innerEntry.getValue());
            }
            result.put(level, inner);
        }
        checkJsonLength(field, result);
        return result;
    }

    /**
     * 拒绝由库管理的请求头。
     *
     * <p>在 {@code headers} 里写 {@code authorization} 会顶掉真实密钥且不报错
     * （{@code containsHeader} 大小写无关），写 {@code content-type} 会发出两个头。
     * 这里提前拒绝，比运行时发错凭据好查。</p>
     *
     * @param field   字段名
     * @param headers 待检查的头
     */
    private static void rejectReservedHeaders(String field, Map<String, String> headers) {
        for (String reserved : RESERVED_HEADERS) {
            for (String name : headers.keySet()) {
                if (name != null && name.equalsIgnoreCase(reserved)) {
                    String hint = "Authorization".equals(reserved)
                            ? "请用供应商的密钥字段配置" : "Content-Type 由请求编码固定";
                    throw new IllegalArgumentException(field + " 不能配置 " + reserved + "，" + hint);
                }
            }
        }
    }

    /**
     * 检查序列化后的长度没有超过上限。
     *
     * @param field 字段名
     * @param value 待检查对象
     */
    private static void checkJsonLength(String field, Object value) {
        String json = AiLiteJson.toJson(value);
        if (json.length() > JSON_LIMIT) {
            throw new IllegalArgumentException(field + " 太长，超过 " + JSON_LIMIT + " 字符");
        }
    }

    private static Map<String, Object> latestDraft(PromptManager manager, String bizId) {
        for (PromptVersion version : manager.versions(bizId)) {
            if (version.getStatus() == PromptStatus.DRAFT) {
                return versionView(version);
            }
        }
        return null;
    }

    private ProviderOverlay merge(String name, ProviderOverlay current, Map<String, Object> body) {
        String api = current == null ? null : current.getApi();
        String baseUrl = current == null ? null : current.getBaseUrl();
        String apiKey = current == null ? null : current.getApiKey();
        Integer connect = current == null ? null : current.getConnectTimeoutMs();
        Integer read = current == null ? null : current.getReadTimeoutMs();
        if (body != null && body.containsKey("api")) {
            String next = text(body.get("api"));
            // 空串表示「撤销协议覆盖、回到配置文件」；非空必须是受支持的协议。
            if (next.isEmpty()) {
                api = null;
            } else if (!SUPPORTED_APIS.contains(next)) {
                throw new IllegalArgumentException("未知协议: " + next);
            } else {
                api = next;
            }
        }
        if (body != null && body.containsKey("baseUrl") && !text(body.get("baseUrl")).isEmpty()) {
            baseUrl = text(body.get("baseUrl"));
        }
        if (body != null && body.containsKey("apiKey") && !text(body.get("apiKey")).isEmpty()) {
            apiKey = text(body.get("apiKey"));
        }
        if (body != null && body.containsKey("connectTimeoutMs") && body.get("connectTimeoutMs") != null
                && !text(body.get("connectTimeoutMs")).isEmpty()) {
            connect = Integer.valueOf(positive(body.get("connectTimeoutMs"), "连接超时"));
        }
        if (body != null && body.containsKey("readTimeoutMs") && body.get("readTimeoutMs") != null
                && !text(body.get("readTimeoutMs")).isEmpty()) {
            read = Integer.valueOf(positive(body.get("readTimeoutMs"), "读取超时"));
        }
        // 请求参数两级：键不出现表示不改（null），出现空对象表示明确清空。
        Map<String, String> providerHeaders = current == null ? null : current.getHeaders();
        Map<String, Object> providerExtra = current == null ? null : current.getExtraBody();
        if (body != null && body.containsKey("providerHeaders")) {
            providerHeaders = stringMapOrNull(body.get("providerHeaders"), "providerHeaders", false);
        }
        if (body != null && body.containsKey("providerExtraBody")) {
            providerExtra = objectMapOrNull(body.get("providerExtraBody"), "providerExtraBody");
        }
        return new ProviderOverlay(name, baseUrl, apiKey, connect, read, api,
                providerHeaders, providerExtra);
    }

    private Endpoint endpoint(String name, AiLiteConfig.Provider provider) {
        Endpoint endpoint = describe(provider, catalog.findProvider(name));
        if (endpoint.baseUrl.isEmpty()) {
            throw new IllegalArgumentException("供应商缺少 base-url");
        }
        if (endpoint.apiKey.isEmpty()) {
            throw new IllegalArgumentException("模型密钥未配置: " + name);
        }
        return endpoint;
    }

    private static Endpoint describe(AiLiteConfig.Provider provider, ProviderOverlay overlay) {
        String api = provider.getApi() == null ? "" : provider.getApi().trim();
        String baseUrl = provider.getBaseUrl() == null ? "" : provider.getBaseUrl().trim();
        String apiKey = effectiveKey(provider);
        int connect = provider.getConnectTimeoutMs() == null ? 10000 : provider.getConnectTimeoutMs().intValue();
        int read = provider.getReadTimeoutMs() == null ? 120000 : provider.getReadTimeoutMs().intValue();
        Map<String, String> headers = provider.getHeaders();
        if (overlay != null) {
            if (Strings.isNotBlank(overlay.getApi())) {
                api = overlay.getApi().trim();
            }
            if (Strings.isNotBlank(overlay.getBaseUrl())) {
                baseUrl = overlay.getBaseUrl().trim();
            }
            if (!hasPool(provider) && Strings.isNotBlank(overlay.getApiKey())) {
                apiKey = overlay.getApiKey().trim();
            }
            if (overlay.getConnectTimeoutMs() != null) {
                connect = overlay.getConnectTimeoutMs().intValue();
            }
            if (overlay.getReadTimeoutMs() != null) {
                read = overlay.getReadTimeoutMs().intValue();
            }
            if (overlay.getHeaders() != null) {
                headers = overlay.getHeaders();
            }
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return new Endpoint(api, baseUrl, apiKey, connect, read, headers);
    }

    private static String effectiveKey(AiLiteConfig.Provider provider) {
        if (hasPool(provider)) {
            String first = provider.getApiKeys().get(0);
            return first == null ? "" : first.trim();
        }
        if (Strings.isNotBlank(provider.getApiKey())) {
            return provider.getApiKey().trim();
        }
        if (Strings.isNotBlank(provider.getApiKeyEnv())) {
            String env = System.getenv(provider.getApiKeyEnv().trim());
            return env == null ? "" : env.trim();
        }
        return "";
    }

    private static boolean hasPool(AiLiteConfig.Provider provider) {
        return provider.getApiKeys() != null && !provider.getApiKeys().isEmpty();
    }

    private AiLiteConfig.Provider requireProvider(String name) {
        AiLiteConfig.Provider provider = config.getLlm().getProviders().get(name);
        if (provider == null) {
            throw new IllegalArgumentException(UNKNOWN_PROVIDER);
        }
        return provider;
    }

    private void requireBiz(String bizId) {
        if (!bizIds().contains(bizId)) {
            throw new IllegalArgumentException("业务 id 未登记");
        }
    }

    private PromptManager requirePrompts() {
        if (prompts == null) {
            throw new IllegalStateException("提示词模块未启用");
        }
        return prompts;
    }

    private List<String> bizIds() {
        List<String> ids = config.getLlm().getBizIds();
        return ids == null ? new ArrayList<String>() : ids;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ok", Boolean.TRUE);
        body.put("data", data);
        return body;
    }

    private static List<Map<String, Object>> thinkingFormatOptions() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(format("none", "不传思考参数", "模型不需要思考开关时选这个。请求体里不会加思考字段。"));
        rows.add(format("reasoning-effort", "OpenAI 思考档", "兼容 OpenAI 的新模型。请求体写 reasoning_effort，例如 low、high。"));
        rows.add(format("chat-template", "对话模板开关", "vLLM 或部分 Qwen。请求体写 chat_template_kwargs.enable_thinking。"));
        rows.add(format("qwen", "Qwen 开关", "请求体直接写 enable_thinking。"));
        rows.add(format("deepseek", "DeepSeek 思考", "请求体写 thinking.type，并带 reasoning_effort。"));
        rows.add(format("anthropic-budget", "Anthropic 思考预算", "Anthropic 协议。按思考档写入 budget_tokens。"));
        rows.add(format("anthropic-adaptive", "Anthropic 自适应", "Anthropic 协议。thinking.type 为 adaptive。"));
        return rows;
    }

    private static List<Map<String, Object>> bodyLevelOptions() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(format(CallBodyPolicy.NONE, "不记模型输入输出", "只保留调用摘要。"));
        rows.add(format(CallBodyPolicy.FAILURE, "只记录失败调用的输入输出", "成功调用仍只记摘要。"));
        rows.add(format(CallBodyPolicy.ALL, "所有调用都记录输入输出", "每次调用都保存消息和回复，不含密钥和图片原文。"));
        return rows;
    }

    private static Map<String, Object> format(String id, String label, String detail) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("label", label);
        row.put("detail", detail);
        return row;
    }

    private static String mask(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "";
        }
        if (apiKey.length() <= 4) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nullableText(Object value) {
        String text = text(value);
        return text.isEmpty() ? null : text;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        String text = text(value);
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new IllegalArgumentException("推理开关必须是 true 或 false");
    }

    private static int integer(Object value, String label) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + "必须是整数");
        }
    }

    private static int positive(Object value, String label) {
        int number = integer(value, label);
        if (number <= 0) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return number;
    }

    private static final class Endpoint {
        private final String api;
        private final String baseUrl;
        private final String apiKey;
        private final int connectMs;
        private final int readMs;
        private final Map<String, String> headers;

        private Endpoint(String api, String baseUrl, String apiKey, int connectMs, int readMs,
                         Map<String, String> headers) {
            this.api = api;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.connectMs = connectMs;
            this.readMs = readMs;
            this.headers = headers == null ? Collections.<String, String>emptyMap() : headers;
        }
    }
}
