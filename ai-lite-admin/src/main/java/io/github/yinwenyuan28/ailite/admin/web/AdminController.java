package io.github.yinwenyuan28.ailite.admin.web;

import io.github.yinwenyuan28.ailite.admin.call.CallLogQuery;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcCallBodyPolicy;
import io.github.yinwenyuan28.ailite.admin.jdbc.JdbcRuntimeCatalog;
import io.github.yinwenyuan28.ailite.admin.sync.ModelSyncClient;
import io.github.yinwenyuan28.ailite.catalog.BizBinding;
import io.github.yinwenyuan28.ailite.catalog.CatalogModel;
import io.github.yinwenyuan28.ailite.catalog.ProviderOverlay;
import io.github.yinwenyuan28.ailite.config.AiLiteConfig;
import io.github.yinwenyuan28.ailite.llm.CallBodyPolicy;
import io.github.yinwenyuan28.ailite.llm.LlmCallException;
import io.github.yinwenyuan28.ailite.llm.LlmThinkingLevel;
import io.github.yinwenyuan28.ailite.prompt.PromptException;
import io.github.yinwenyuan28.ailite.prompt.PromptManager;
import io.github.yinwenyuan28.ailite.prompt.PromptStatus;
import io.github.yinwenyuan28.ailite.prompt.PromptVersion;
import io.github.yinwenyuan28.ailite.util.Strings;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
 * 管理页与 JSON 接口。密钥只用于出站请求，响应里只放脱敏结果。
 */
@RestController
public class AdminController {

    static final String UNKNOWN_PROVIDER = "未知供应商，须先在配置文件声明";
    private static final String ACTOR = "admin";
    private static final Set<String> THINKING_FORMATS = new HashSet<String>(Arrays.asList(
            "none", "chat-template", "deepseek", "qwen",
            "reasoning-effort", "anthropic-budget", "anthropic-adaptive"));

    private final AiLiteConfig config;
    private final JdbcRuntimeCatalog catalog;
    private final PromptManager prompts;
    private final JdbcTemplate jdbc;
    private final String callTable;
    private final boolean pageEnabled;
    private final JdbcCallBodyPolicy bodyPolicy;

    /**
     * @param config      配置文件里的供应商与业务 id
     * @param catalog     运行时目录
     * @param prompts     提示词管理器，可为 null
     * @param jdbc        调用记录查询
     * @param callTable   调用记录表名，未配置时为 null
     * @param pageEnabled 是否提供自带页面
     * @param bodyPolicy  输入输出记录档位，可为 null
     */
    public AdminController(AiLiteConfig config, JdbcRuntimeCatalog catalog, PromptManager prompts,
                           JdbcTemplate jdbc, String callTable, boolean pageEnabled,
                           JdbcCallBodyPolicy bodyPolicy) {
        this.config = config;
        this.catalog = catalog;
        this.prompts = prompts;
        this.jdbc = jdbc;
        this.callTable = callTable;
        this.pageEnabled = pageEnabled;
        this.bodyPolicy = bodyPolicy;
    }

    /**
     * @return 管理页；未打开自带页面时 404
     */
    @GetMapping(value = "/ai-lite/admin", produces = "text/html")
    public ResponseEntity<byte[]> page() {
        if (!pageEnabled) {
            return ResponseEntity.notFound().build();
        }
        return staticFile("index.html", "text/html;charset=UTF-8");
    }

    /**
     * @param name 静态文件名，只允许页面脚本和样式
     * @return 资源；未打开自带页面或其他名字返回 404
     */
    @GetMapping("/ai-lite/admin/assets/{name:.+}")
    public ResponseEntity<byte[]> asset(@PathVariable("name") String name) {
        if (!pageEnabled) {
            return ResponseEntity.notFound().build();
        }
        if ("admin.js".equals(name)) {
            return staticFile("admin.js", "application/javascript;charset=UTF-8");
        }
        if ("admin.css".equals(name)) {
            return staticFile("admin.css", "text/css;charset=UTF-8");
        }
        return ResponseEntity.notFound().build();
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
                endpoint.baseUrl, endpoint.apiKey, endpoint.connectMs, endpoint.readMs);
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
        List<String> remote = ModelSyncClient.listRemote(provider.getApi(), endpoint.baseUrl, endpoint.apiKey);
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
     * 用当前生效的地址和密钥向该模型发一次很短的请求。不写调用日志，不改模型表。
     *
     * @param name    供应商 id
     * @param modelId 模型 id
     * @return 是否成功、耗时和回复摘要
     */
    @PostMapping("/ai-lite/admin/api/providers/{name}/models/{modelId:.+}/try")
    public Map<String, Object> tryModel(@PathVariable("name") String name,
                                        @PathVariable("modelId") String modelId) {
        AiLiteConfig.Provider provider = requireProvider(name);
        Endpoint endpoint = endpoint(name, provider);
        CatalogModel model = catalog.findModel(name, modelId);
        String format = model == null ? null : model.getThinkingFormat();
        Integer maxTokens = model == null ? null : model.getMaxTokens();
        ModelSyncClient.TryResult result = ModelSyncClient.tryChat(
                provider.getApi(), endpoint.baseUrl, endpoint.apiKey, modelId, format, maxTokens,
                endpoint.connectMs, endpoint.readMs);
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
     * @return 思考格式的可选值和说明
     */
    @GetMapping("/ai-lite/admin/api/thinking-formats")
    public Map<String, Object> thinkingFormats() {
        return ok(thinkingFormatOptions());
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
     * 保存能力参数并标为可选。
     *
     * @param name    供应商 id
     * @param modelId 模型 id
     * @param body    推理开关、上下文窗口、输出上限、思考格式
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
        catalog.saveCapabilities(name, modelId, reasoning, contextWindow, maxTokens, format);
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
     * @param bizId 业务 id，同时作为提示词 key
     * @param body  系统模板与用户模板
     * @return 新草稿
     */
    @PostMapping("/ai-lite/admin/api/biz/{bizId}/prompt/draft")
    public Map<String, Object> draft(@PathVariable("bizId") String bizId,
                                     @RequestBody(required = false) Map<String, Object> body) {
        requireBiz(bizId);
        PromptManager manager = requirePrompts();
        String system = body == null ? "" : text(body.get("systemTemplate"));
        String user = body == null ? "" : text(body.get("userTemplate"));
        String schema = body == null ? null : nullableText(body.get("outputSchema"));
        PromptVersion version = manager.createDraft(bizId, system, user, schema, ACTOR);
        return ok(versionView(version));
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
     * @param limit    条数，默认 50，上限 200
     * @return 调用摘要；开启正文记录时附带输入输出
     */
    @GetMapping("/ai-lite/admin/api/calls")
    public Map<String, Object> calls(@RequestParam(value = "bizId", required = false) String bizId,
                                     @RequestParam(value = "provider", required = false) String provider,
                                     @RequestParam(value = "limit", required = false) Integer limit) {
        int size = limit == null ? 50 : limit.intValue();
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("limit 必须在 1 到 200 之间");
        }
        return ok(CallLogQuery.list(jdbc, callTable, bizId, provider, size));
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
        row.put("api", provider.getApi());
        row.put("baseUrl", endpoint.baseUrl);
        row.put("apiKeyMasked", mask(endpoint.apiKey));
        row.put("apiKeyConfigured", Boolean.valueOf(!endpoint.apiKey.isEmpty()));
        row.put("keyPool", Boolean.valueOf(hasPool(provider)));
        row.put("connectTimeoutMs", Integer.valueOf(endpoint.connectMs));
        row.put("readTimeoutMs", Integer.valueOf(endpoint.readMs));
        row.put("overlay", Boolean.valueOf(overlay != null));
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
        row.put("contextWindow", model.getContextWindow());
        row.put("maxTokens", model.getMaxTokens());
        row.put("thinkingFormat", model.getThinkingFormat());
        row.put("displayName", model.getDisplayName());
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

    private static Map<String, Object> latestDraft(PromptManager manager, String bizId) {
        for (PromptVersion version : manager.versions(bizId)) {
            if (version.getStatus() == PromptStatus.DRAFT) {
                return versionView(version);
            }
        }
        return null;
    }

    private ProviderOverlay merge(String name, ProviderOverlay current, Map<String, Object> body) {
        String baseUrl = current == null ? null : current.getBaseUrl();
        String apiKey = current == null ? null : current.getApiKey();
        Integer connect = current == null ? null : current.getConnectTimeoutMs();
        Integer read = current == null ? null : current.getReadTimeoutMs();
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
        return new ProviderOverlay(name, baseUrl, apiKey, connect, read);
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
        String baseUrl = provider.getBaseUrl() == null ? "" : provider.getBaseUrl().trim();
        String apiKey = effectiveKey(provider);
        int connect = provider.getConnectTimeoutMs() == null ? 10000 : provider.getConnectTimeoutMs().intValue();
        int read = provider.getReadTimeoutMs() == null ? 120000 : provider.getReadTimeoutMs().intValue();
        if (overlay != null) {
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
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return new Endpoint(baseUrl, apiKey, connect, read);
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

    private static ResponseEntity<byte[]> staticFile(String name, String contentType) {
        ClassPathResource resource = new ClassPathResource("ai-lite-admin/" + name);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        try {
            InputStream input = resource.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = input.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                }
                return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType)).body(out.toByteArray());
            } finally {
                input.close();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("管理页资源读取失败");
        }
    }

    private static final class Endpoint {
        private final String baseUrl;
        private final String apiKey;
        private final int connectMs;
        private final int readMs;

        private Endpoint(String baseUrl, String apiKey, int connectMs, int readMs) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.connectMs = connectMs;
            this.readMs = readMs;
        }
    }
}
