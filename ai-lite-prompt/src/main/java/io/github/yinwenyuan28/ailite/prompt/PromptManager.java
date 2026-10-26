package io.github.yinwenyuan28.ailite.prompt;

import io.github.yinwenyuan28.ailite.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词版本管理器。草稿、发布、回滚、审计和渲染都从这里走。
 *
 * <p>读取生效版本时带一个短 TTL 缓存：发布/回滚会立即让本节点缓存失效，
 * 多节点部署最多等一个 TTL。存储不可用时 {@link #current} 和 {@link #render}
 * 不抛异常，分别返回空和配置里的默认模板，保证业务不被提示词中心拖垮。</p>
 */
public final class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);

    public static final int LIST_LIMIT = 50;
    public static final int TEXT_LIMIT = 100000;
    /** 审计 detail 列宽（见 ai-lite-prompt.sql 的 DETAIL VARCHAR(1000)）。 */
    public static final int DETAIL_LIMIT = 1000;

    private final PromptCatalog catalog;
    private final PromptStore store;
    private final long cacheSeconds;
    private final ConcurrentHashMap<String, CacheEntry> cache =
            new ConcurrentHashMap<String, CacheEntry>();
    private final ConcurrentHashMap<String, Object> keyLocks =
            new ConcurrentHashMap<String, Object>();

    /**
     * @param catalog      提示词目录
     * @param store        存储
     * @param cacheSeconds 生效版本缓存秒数；小于等于 0 表示不缓存
     */
    public PromptManager(PromptCatalog catalog, PromptStore store, long cacheSeconds) {
        if (catalog == null) {
            throw new PromptException("提示词目录不能为空");
        }
        if (store == null) {
            throw new PromptException("提示词存储不能为空");
        }
        this.catalog = catalog;
        this.store = store;
        this.cacheSeconds = cacheSeconds;
    }

    /**
     * 使用内存存储的便捷构造，适合单机或测试。
     *
     * @param catalog 提示词目录
     */
    public PromptManager(PromptCatalog catalog) {
        this(catalog, new InMemoryPromptStore(), 0L);
    }

    /**
     * @return 已登记的提示词
     */
    public List<PromptKey> keys() {
        return catalog.keys();
    }

    /**
     * @param promptKey 提示词 key
     * @return 当前生效版本；没有生效版本或存储不可用时为空
     */
    public Optional<PromptVersion> current(String promptKey) {
        PromptKey spec = catalog.require(promptKey);
        String key = spec.getKey();
        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.value == null
                    ? Optional.<PromptVersion>empty() : Optional.of(cached.value.copy());
        }
        Optional<PromptVersion> loaded;
        try {
            loaded = store.findActive(key);
        } catch (RuntimeException ex) {
            log.warn("prompt store unavailable key={}: {}", key, ex.getMessage());
            return Optional.empty();
        }
        if (cacheSeconds > 0L) {
            PromptVersion value = loaded.isPresent() ? loaded.get().copy() : null;
            cache.put(key, new CacheEntry(value, System.currentTimeMillis() + cacheSeconds * 1000L));
        }
        return loaded.isPresent() ? Optional.of(loaded.get().copy()) : Optional.<PromptVersion>empty();
    }

    /**
     * @param promptKey 提示词 key
     * @return 版本列表，按版本号倒序
     */
    public List<PromptVersion> versions(String promptKey) {
        PromptKey spec = catalog.require(promptKey);
        return call(() -> store.versions(spec.getKey(), LIST_LIMIT));
    }

    /**
     * @param promptKey 提示词 key
     * @return 审计列表，按时间正序
     */
    public List<PromptAudit> audits(String promptKey) {
        PromptKey spec = catalog.require(promptKey);
        return call(() -> store.audits(spec.getKey(), LIST_LIMIT));
    }

    /**
     * 创建草稿。系统模板为空时继承当前生效版本，没有生效版本则继承配置默认值。
     *
     * @param promptKey      提示词 key
     * @param systemTemplate 系统模板
     * @param userTemplate   用户模板
     * @param outputSchema   输出结构描述
     * @param actor          操作人
     * @return 新草稿
     */
    public PromptVersion createDraft(String promptKey, String systemTemplate, String userTemplate,
                                     String outputSchema, String actor) {
        return createDraft(promptKey, systemTemplate, userTemplate, outputSchema, actor, null);
    }

    /**
     * 创建草稿，并记录这次改动的说明。
     *
     * <p>{@code message} 会写进审计记录的 {@code detail}，用于事后回答"这次改了什么、
     * 为什么改"。不传时自动生成摘要（如"从第 3 版继承"）。审计表的 detail 列历史
     * 上一直写 null，这个重载是补齐该缺口。</p>
     *
     * @param promptKey      提示词 key
     * @param systemTemplate 系统模板
     * @param userTemplate   用户模板
     * @param outputSchema   输出结构描述
     * @param actor          操作人
     * @param message        本次改动说明，可为 null
     * @return 新草稿
     */
    public PromptVersion createDraft(String promptKey, String systemTemplate, String userTemplate,
                                     String outputSchema, String actor, String message) {
        PromptKey spec = catalog.require(promptKey);
        String system = systemTemplate;
        String user = userTemplate;
        String schema = outputSchema;
        String inheritedFrom = null;
        if (Strings.isBlank(system)) {
            Optional<PromptVersion> active = current(spec.getKey());
            if (active.isPresent()) {
                system = active.get().getSystemTemplate();
                inheritedFrom = "从第 " + active.get().getVersionNo() + " 版继承";
                if (Strings.isBlank(user)) {
                    user = active.get().getUserTemplate();
                }
                if (schema == null) {
                    schema = active.get().getOutputSchema();
                }
            } else {
                system = spec.getDefaultSystemTemplate();
                inheritedFrom = "从配置默认模板继承";
                if (Strings.isBlank(user)) {
                    user = spec.getDefaultUserTemplate();
                }
            }
        }
        if (inheritedFrom == null) {
            // 显式填写了系统模板：没有可继承的来源，给一个能说明"这是手动新建"的摘要。
            inheritedFrom = "手动填写模板";
        }
        validateTemplates(system, user, schema);
        final String finalSystem = system;
        final String finalUser = user;
        final String finalSchema = blankToNull(schema);
        final String detail = auditDetail(message, inheritedFrom);
        synchronized (lockFor(spec.getKey())) {
            int versionNo = call(() -> store.nextVersionNo(spec.getKey()));
            PromptVersion version = new PromptVersion();
            version.setVersionId(newId());
            version.setPromptKey(spec.getKey());
            version.setVersionNo(versionNo);
            version.setStatus(PromptStatus.DRAFT);
            version.setSystemTemplate(finalSystem);
            version.setUserTemplate(finalUser);
            version.setOutputSchema(finalSchema);
            version.setContentHash(hash(finalSystem, finalUser, finalSchema));
            version.setCreatedBy(actorOf(actor));
            version.setCreateTime(new Date());
            call(() -> {
                store.saveVersion(version);
                store.audit(audit(spec.getKey(), PromptActions.DRAFT, version.getVersionId(),
                        actorOf(actor), detail));
                return null;
            });
            return version.copy();
        }
    }

    /**
     * 只修改草稿。
     *
     * @param versionId      版本 id
     * @param systemTemplate 系统模板
     * @param userTemplate   用户模板
     * @param outputSchema   输出结构描述
     * @param actor          操作人
     * @return 修改后的草稿
     */
    public PromptVersion saveDraft(String versionId, String systemTemplate, String userTemplate,
                                   String outputSchema, String actor) {
        return saveDraft(versionId, systemTemplate, userTemplate, outputSchema, actor, null);
    }

    /**
     * 只修改草稿，并记录这次改动的说明。
     *
     * <p>{@code message} 会写进审计记录的 {@code detail}。不传时自动生成摘要，
     * 说明本次相比修改前改动了哪几段（系统模板 / 用户模板 / 输出结构）。</p>
     *
     * @param versionId      版本 id
     * @param systemTemplate 系统模板
     * @param userTemplate   用户模板
     * @param outputSchema   输出结构描述
     * @param actor          操作人
     * @param message        本次改动说明，可为 null
     * @return 修改后的草稿
     */
    public PromptVersion saveDraft(String versionId, String systemTemplate, String userTemplate,
                                   String outputSchema, String actor, String message) {
        PromptVersion version = requireVersion(versionId);
        catalog.require(version.getPromptKey());
        if (version.getStatus() != PromptStatus.DRAFT) {
            throw new PromptException("只能修改草稿");
        }
        validateTemplates(systemTemplate, userTemplate, outputSchema);
        String changed = changedFields(version.getSystemTemplate(), version.getUserTemplate(),
                version.getOutputSchema(), systemTemplate, userTemplate, blankToNull(outputSchema));
        version.setSystemTemplate(systemTemplate);
        version.setUserTemplate(userTemplate);
        version.setOutputSchema(blankToNull(outputSchema));
        version.setContentHash(hash(version.getSystemTemplate(), version.getUserTemplate(),
                version.getOutputSchema()));
        final PromptVersion toSave = version;
        final String detail = auditDetail(message, changed);
        call(() -> {
            store.saveVersion(toSave);
            store.audit(audit(toSave.getPromptKey(), PromptActions.UPDATE, toSave.getVersionId(),
                    actorOf(actor), detail));
            return null;
        });
        return version.copy();
    }

    /**
     * 发布草稿。发布前检查配置要求的占位符。
     *
     * @param versionId 版本 id
     * @param actor     操作人
     * @return 已发布版本
     */
    public PromptVersion publish(String versionId, String actor) {
        PromptVersion version = requireVersion(versionId);
        if (version.getStatus() != PromptStatus.DRAFT) {
            throw new PromptException("只能发布草稿");
        }
        PromptKey spec = catalog.require(version.getPromptKey());
        requirePlaceholders(version.getSystemTemplate(), version.getUserTemplate(),
                spec.getPlaceholders());
        Optional<PromptVersion> previous = current(spec.getKey());
        final String detail = previous.isPresent() && !previous.get().getVersionId()
                .equals(version.getVersionId())
                ? "接替第 " + previous.get().getVersionNo() + " 版生效"
                : "首次发布生效";
        final PromptVersion toPublish = version;
        final String key = version.getPromptKey();
        call(() -> {
            store.activate(key, toPublish.getVersionId(), actorOf(actor), new Date());
            store.audit(audit(key, PromptActions.PUBLISH, toPublish.getVersionId(),
                    actorOf(actor), detail));
            return null;
        });
        invalidate(key);
        version.setStatus(PromptStatus.PUBLISHED);
        return version.copy();
    }

    /**
     * 回滚到某个已发布过的版本。
     *
     * @param versionId 版本 id
     * @param actor     操作人
     * @return 重新生效的版本
     */
    public PromptVersion rollback(String versionId, String actor) {
        PromptVersion version = requireVersion(versionId);
        if (version.getStatus() == PromptStatus.DRAFT) {
            throw new PromptException("草稿不能回滚，请先发布");
        }
        PromptKey spec = catalog.require(version.getPromptKey());
        Optional<PromptVersion> active = current(spec.getKey());
        if (active.isPresent() && versionId.trim().equals(active.get().getVersionId())) {
            throw new PromptException("这已经是当前生效版本");
        }
        final PromptVersion toRollback = version;
        final String key = version.getPromptKey();
        final String detail = active.isPresent()
                ? "从第 " + active.get().getVersionNo() + " 版回滚"
                : "回滚生效";
        call(() -> {
            store.activate(key, toRollback.getVersionId(), actorOf(actor), new Date());
            store.audit(audit(key, PromptActions.ROLLBACK, toRollback.getVersionId(),
                    actorOf(actor), detail));
            return null;
        });
        invalidate(key);
        version.setStatus(PromptStatus.PUBLISHED);
        return version.copy();
    }

    /**
     * 用当前生效版本渲染；没有生效版本时用配置里的默认模板。
     *
     * @param promptKey 提示词 key
     * @param vars      占位符变量
     * @return 渲染结果
     */
    public RenderedPrompt render(String promptKey, Map<String, String> vars) {
        return render(promptKey, vars, null, null);
    }

    /**
     * 渲染提示词。优先级：生效版本 &gt; 调用方兜底模板 &gt; 配置默认模板。
     *
     * @param promptKey      提示词 key
     * @param vars           占位符变量
     * @param fallbackSystem 调用方兜底系统模板，可为 null
     * @param fallbackUser   调用方兜底用户模板，可为 null
     * @return 渲染结果
     */
    public RenderedPrompt render(String promptKey, Map<String, String> vars,
                                 String fallbackSystem, String fallbackUser) {
        PromptKey spec = catalog.require(promptKey);
        String system;
        String user;
        Optional<PromptVersion> active = current(spec.getKey());
        if (active.isPresent()) {
            system = active.get().getSystemTemplate();
            user = active.get().getUserTemplate();
        } else {
            system = Strings.isNotBlank(fallbackSystem)
                    ? fallbackSystem : spec.getDefaultSystemTemplate();
            user = fallbackUser != null ? fallbackUser : spec.getDefaultUserTemplate();
        }
        if (Strings.isBlank(system)) {
            throw new PromptException("提示词模板未配置: " + spec.getKey());
        }
        return new RenderedPrompt(apply(system, vars), apply(user, vars));
    }

    /**
     * 清除某个 key 的本地缓存。发布/回滚会自动调用，多节点场景也可由外部触发。
     *
     * @param promptKey 提示词 key
     */
    public void invalidate(String promptKey) {
        String key = Strings.trimToNull(promptKey);
        if (key != null) {
            cache.remove(key);
        }
    }

    /**
     * 清空本地缓存。
     */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * @param key 提示词 key
     * @return 下一个版本号
     */
    public int nextVersionNo(String key) {
        PromptKey spec = catalog.require(key);
        return call(() -> store.nextVersionNo(spec.getKey()));
    }

    /**
     * 把 {{name}} 替换成变量值。未知占位符原样保留，缺少变量按空串处理。
     *
     * @param template 模板
     * @param vars     变量
     * @return 渲染文本
     */
    public static String apply(String template, Map<String, String> vars) {
        if (template == null || vars == null || vars.isEmpty()) {
            return template;
        }
        String text = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                continue;
            }
            text = text.replace("{{" + entry.getKey().trim() + "}}",
                    Strings.defaultString(entry.getValue()));
        }
        return text;
    }

    /**
     * 检查每个配置要求的占位符都出现在系统或用户模板里。
     *
     * @param systemTemplate 系统模板
     * @param userTemplate   用户模板
     * @param placeholders   要求的占位符
     */
    public static void requirePlaceholders(String systemTemplate, String userTemplate,
                                           List<String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return;
        }
        String haystack = Strings.defaultString(systemTemplate) + "\n"
                + Strings.defaultString(userTemplate);
        for (String name : placeholders) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            if (!haystack.contains("{{" + name.trim() + "}}")) {
                throw new PromptException("系统模板或用户模板里缺少占位符: " + name.trim());
            }
        }
    }

    /**
     * @param system 系统模板
     * @param user   用户模板
     * @param schema 输出结构
     * @return SHA-256 十六进制摘要
     */
    public static String hash(String system, String user, String schema) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((Strings.defaultString(system)
                    + Strings.defaultString(user)
                    + Strings.defaultString(schema)).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private PromptVersion requireVersion(String versionId) {
        String id = Strings.trimToNull(versionId);
        if (id == null) {
            throw new PromptException("提示词版本不存在");
        }
        Optional<PromptVersion> version = call(() -> store.findVersion(id));
        if (!version.isPresent()) {
            throw new PromptException("提示词版本不存在");
        }
        return version.get();
    }

    private void validateTemplates(String system, String user, String schema) {
        if (Strings.isBlank(system)) {
            throw new PromptException("请填写系统模板");
        }
        int userLength = user == null ? 0 : user.length();
        int schemaLength = schema == null ? 0 : schema.length();
        if (system.length() > TEXT_LIMIT || userLength > TEXT_LIMIT || schemaLength > TEXT_LIMIT) {
            throw new PromptException("提示词过长");
        }
    }

    private Object lockFor(String key) {
        Object created = new Object();
        Object existing = keyLocks.putIfAbsent(key, created);
        return existing == null ? created : existing;
    }

    private static PromptAudit audit(String key, String action, String versionId,
                                     String actor, String detail) {
        PromptAudit row = new PromptAudit();
        row.setAuditId(newId());
        row.setPromptKey(key);
        row.setAction(action);
        row.setVersionId(versionId);
        row.setActor(actor);
        row.setDetail(detail);
        row.setCreateTime(new Date());
        return row;
    }

    private static String actorOf(String actor) {
        String value = Strings.trimToNull(actor);
        if (value == null) {
            return "unknown";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    /**
     * 生成审计 detail：调用方给了说明就用它，否则用自动摘要。
     *
     * <p>detail 列宽 1000，超出时截断而不是抛错——审计是旁路，不该阻断主操作。</p>
     *
     * @param message 调用方说明，可为 null/空白
     * @param auto    自动摘要，可为 null
     * @return 写入 detail 的文本，可为 null
     */
    private static String auditDetail(String message, String auto) {
        String value = Strings.trimToNull(message);
        if (value == null) {
            value = auto;
        }
        if (value == null) {
            return null;
        }
        return value.length() > DETAIL_LIMIT ? value.substring(0, DETAIL_LIMIT) : value;
    }

    /**
     * 比较修改前后的模板，列出发生变化的字段名。
     *
     * @param oldSystem 原系统模板
     * @param oldUser   原用户模板
     * @param oldSchema 原输出结构
     * @param newSystem 新系统模板
     * @param newUser   新用户模板
     * @param newSchema 新输出结构
     * @return 例如"系统模板、用户模板"；无变化时返回"内容未变化"
     */
    private static String changedFields(String oldSystem, String oldUser, String oldSchema,
                                        String newSystem, String newUser, String newSchema) {
        StringBuilder changed = new StringBuilder();
        if (!equalText(oldSystem, newSystem)) {
            changed.append("系统模板");
        }
        if (!equalText(oldUser, newUser)) {
            appendSeparator(changed).append("用户模板");
        }
        if (!equalText(oldSchema, newSchema)) {
            appendSeparator(changed).append("输出结构");
        }
        return changed.length() == 0 ? "内容未变化" : changed.toString();
    }

    private static StringBuilder appendSeparator(StringBuilder builder) {
        return builder.length() == 0 ? builder : builder.append("、");
    }

    private static boolean equalText(String left, String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        return a.equals(b);
    }

    private static String blankToNull(String value) {
        return Strings.trimToNull(value);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private <T> T call(StoreCall<T> call) {
        try {
            return call.get();
        } catch (PromptException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PromptException("提示词存储不可用", ex);
        }
    }

    private interface StoreCall<T> {
        T get();
    }

    private static final class CacheEntry {
        private final PromptVersion value;
        private final long expiresAt;

        private CacheEntry(PromptVersion value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        private boolean expired() {
            return expiresAt <= System.currentTimeMillis();
        }
    }
}
