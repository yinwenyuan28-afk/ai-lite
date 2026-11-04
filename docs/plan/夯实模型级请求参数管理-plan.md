---
status: planned
created: "2026-11-04 15:20"
updated: "2026-11-04 15:20"
model: "deepseek-v4.1-flash"
---

# 夯实模型级请求参数管理（0/11 done）

给 `AI_LITE_MODEL` 补五个 JSON 列、给 `AI_LITE_PROVIDER_OVERLAY` 补两个 JSON 列，让目录模式下也能发出完整请求体。当前 `toConfigModel` 只从数据库行取七项，YAML 里的 `thinking-level-map`、`sampler-params`、`sampler-params-by-thinking-level`、`headers`、`extra-body` 全部丢失；供应商级的 `headers`、`extra-body` 虽然由 `applyOverlay` 保住，但管理页没有入口。两条一起解决，之后「请求怎么发」这件事在页面上完整可见可改。

## 背景与问题

用户 2026-11-04 提问「行为变了怎么修」，在对比三个方案后选择「给 `AI_LITE_MODEL` 加列」，而不是「用 YAML 补字段」或「装饰 `RuntimeCatalog`」。随后补充要求：供应商级的 `headers` 和 `extra-body` 也要补列并提供管理能力。

问题一在 `ModelResolver.resolveBiz`（`ModelResolver.java:176`）：

```java
AiLiteConfig.Model m = toConfigModel(row);       // 从数据库行现造，只 set 七个字段
return resolve(providerId, effective, m, binding.getThinkingLevel());
```

`toConfigModel`（`ModelResolver.java:237`）只做七次 `set`，其余五个字段是 `new AiLiteConfig.Model()` 的默认空 Map。

问题二在供应商覆盖：`ProviderOverlay` 只有 `providerId`、`baseUrl`、`apiKey`、两个超时、`api`，没有 `headers` 和 `extraBody`。这两个字段在目录模式下由 `applyOverlay`（`ModelResolver.java:209-210`）从 YAML 复制，所以**行为是对的**，但管理页看不到、改不了。

已实测的后果（`docs/desc-配置和运行时配置.md` §9.1 有完整记录）：

| 场景                       | 走目录                     | 走 YAML    |
| ------------------------ | ----------------------- | --------- |
| 模型只支持 low/high，请求 medium | `medium`（原样发出）          | `low`（钳制） |
| 请求体                      | 无 `temperature`/`top_p` | 有         |
| 模型级请求头                   | 无 `X-Model`             | 有         |
| 模型级附加字段                  | 无 `vendor_field`        | 有         |
| 供应商级请求头                  | 有（`applyOverlay` 复制）    | 有         |
| 供应商级附加字段                 | 有（同上）                   | 有         |

这不是「少一个设置」，是发出去的请求体真的不同。且不报错——问题会表现为供应商返回 400，或输出风格悄悄变了。

## 目标与验收

1. 模型级五个字段能在管理页编辑并存进数据库，走 `LlmCall.biz` 时进入请求体，行为与 YAML 路径一致。
2. 供应商级 `headers` 与 `extra-body` 能在管理页编辑并存进覆盖表，走 `LlmCall.biz` 时进入请求头与请求体。
3. 旧库升级不丢配置：新列为 NULL 时从 YAML 对应层级继承，升级前后行为不变。
4. 空对象与 NULL 可区分：`{}` 表示「明确清空」，NULL 表示「沿用 YAML」。
5. 已有 `CatalogModel`（10 参）与 `ProviderOverlay`（6 参）的构造器保留，老调用方不破坏（japicmp 二进制兼容）。
6. 建表脚本、自动补列、管理 API、页面、文档、测试同步更新。

## 范围

仓库：`ai-lite-spring-boot-starter`。涉及模块：`ai-lite-llm`（模型对象、覆盖对象与解析）、`ai-lite-admin-api`（JDBC 读写与校验）、`ai-lite-admin-ui`（模型与供应商两个编辑弹窗）、`ai-lite-demo`（建表脚本）、`docs/`。

增量版本：`0.1.14` → `0.1.15`。

## 能力清单

| 能力         | 说明与验收口径                                                                                                                    |
| ---------- | -------------------------------------------------------------------------------------------------------------------------- |
| 模型五列存储     | `AI_LITE_MODEL` 增加 `THINKING_LEVEL_MAP`、`SAMPLER_PARAMS`、`SAMPLER_PARAMS_BY_LEVEL`、`MODEL_HEADERS`、`EXTRA_BODY`，均为 JSON 文本 |
| 覆盖两列存储     | `AI_LITE_PROVIDER_OVERLAY` 增加 `PROVIDER_HEADERS`、`PROVIDER_EXTRA_BODY`，均为 JSON 文本                                          |
| 旧库自动补列     | 构造时逐列探测，缺则 `ALTER TABLE ... ADD COLUMN`。策略见 §「加列的可移植性」                                                                     |
| NULL 继承    | 新列为 NULL 时从 YAML 对应层级取；`{}` 表示清空；不写该键表示不改                                                                                  |
| 解析一致       | `resolveBiz` 的结果与 `resolveExplicit` 对同一模型完全一致（五个模型级字段 + 两个供应商级字段）                                                          |
| 页面可编辑（模型）  | 五个字段在模型编辑弹窗里可查看、可改，保存前做 JSON 语法校验                                                                                          |
| 页面可编辑（供应商） | 两个字段在供应商覆盖弹窗里可查看、可改，与地址、密钥、超时同一处保存                                                                                         |
| 非法输入拒绝     | 根节点不是对象、思考档键不是合法档位名、超长，均返回 400 并说明哪一项                                                                                      |
| 页面展示请求预览   | 模型编辑时可展开「这次会发什么」，显示合并后的请求头与请求体关键字段，并标出每个键来自供应商级还是模型级                                                                     |

### 七个字段的层级归属

`thinking-level-map`、`sampler-params`、`sampler-params-by-thinking-level` 只有模型级。`headers` 与 `extra-body` 两级都有，合并顺序是供应商级在前、模型级覆盖同名键（`ModelResolver.java:263-269`）。

| 字段                                 | 供应商级 | 模型级 | 本次补在哪                                                                       | 合并后谁生效        |
| ---------------------------------- | ---- | --- | --------------------------------------------------------------------------- | ------------- |
| `thinking-level-map`               | 没有   | 有   | `AI_LITE_MODEL.THINKING_LEVEL_MAP`                                          | 模型级           |
| `sampler-params`                   | 没有   | 有   | `AI_LITE_MODEL.SAMPLER_PARAMS`                                              | 模型级           |
| `sampler-params-by-thinking-level` | 没有   | 有   | `AI_LITE_MODEL.SAMPLER_PARAMS_BY_LEVEL`                                     | 模型级           |
| `headers`                          | 有    | 有   | `AI_LITE_PROVIDER_OVERLAY.PROVIDER_HEADERS` + `AI_LITE_MODEL.MODEL_HEADERS` | 同名键模型级覆盖      |
| `extra-body`                       | 有    | 有   | `AI_LITE_PROVIDER_OVERLAY.PROVIDER_EXTRA_BODY` + `AI_LITE_MODEL.EXTRA_BODY` | 同名键模型级覆盖（深合并） |

已实测确认（`TwoLevels` 探针）：目录模式下 `X-Provider`（供应商级头）和 `provider_field`（供应商级附加字段）本来就能保住，丢的是模型级的 `X-Model` 和 `model_field`。所以本次改动的性质是：

- **模型级五列：修 bug。** 现在确实丢了。
- **供应商级两列：补能力。** 现在不丢，但从 YAML 读，页面改不了。

两件事性质不同，在验收上要分开看：模型级看「请求体是否恢复完整」，供应商级看「页面改完是否生效」。

## 不做的事

- 不改 `LlmCall.explicit` / `LlmCall.profile` 的解析路径。它们继续只读 YAML。
- 不给 `sampler-params` 做参数名白名单。`temperature`、`top_p`、`top_k`、`repetition_penalty` 各家不同，写死会挡住新供应商。
- 不做「YAML 与数据库同时有值时的合并」。数据库有值就用数据库，不叠 YAML。理由见 §「谁优先」。
- 不改 `context-window` 的 NULL 语义。它现在 NULL → 0 → 跳过预算检查，属于另一个问题，本次不动（记在观察项）。
- 不引入新依赖。JSON 用已有的 `AiLiteJson`（`ai-lite-llm` 已依赖 jackson-databind）。
- 不在页面上新增供应商或业务 id。
- 不改两级之间的合并顺序。供应商级在前、模型级覆盖，保持现状。

## 设计原则

- **数据库定「能不能用」，YAML 定「怎么发」。** 前七个字段（就绪、能力参数）是运营状态，进数据库。后五个是请求的固有属性，进数据库后也随之版本化。
- **NULL 与空对象必须可区分。** 沿用现有代码的两处先例：`JdbcCallBodyPolicy.cachedLevel` 用 `null` 表示「没缓存」而非「缓存了 none」；`CatalogModel.contextWindow` 用 `Integer` 保留 NULL。
- **JSON 解析留在 admin-api，不放核心。** `CatalogModel` 只持有已解析的 `Map`，不依赖 Jackson 类型；`JdbcRuntimeCatalog` 负责 JSON↔Map。
- **二进制兼容优先。** `CatalogModel` 与 `ProviderOverlay` 同款做法：新增全参构造器，旧构造器委托并填默认值。
- JDK 8、Spring Boot 2.1 基线。不使用 `var`、`Map.of`。

## 已确认决策

| 决策       | 选择                                      | 理由与后果                                         | 被否选项                   |
| -------- | --------------------------------------- | --------------------------------------------- | ---------------------- |
| 存储形态     | 七列 JSON 文本（`CLOB` / `TEXT`），模型五列 + 覆盖两列 | 字段里有嵌套 Map，开子表要关联表加联表查询；JSON 列一次读写完成          | 子表；只存扁平字段              |
| 谁优先      | 数据库有值覆盖 YAML，不合并两个来源                    | 合并会让「为什么这个值是这个」无法一眼看出                         | 深合并 YAML 与数据库          |
| NULL 语义  | NULL = 沿用 YAML；`{}` = 明确清空              | 升级平滑：老行不填也能正常跑                                | NULL 当作空对象（会让升级后静默丢配置） |
| 加列时机     | `JdbcRuntimeCatalog` 构造时自动补             | 与 `ensureProviderApiColumn` 一致，用户不必手工执行 ALTER | 只提供 SQL、让用户自己跑         |
| 校验深度     | 结构校验（必须是对象 / 档位名合法 / 长度上限），不校验参数名       | 参数名由供应商决定，白名单必然过时                             | 全量字段白名单                |
| 供应商级入口位置 | 放在已有的供应商覆盖弹窗里，与地址密钥同处保存                 | 用户改「怎么连」和「连的时候带什么头」是同一件事；不另开页面                | 单独一个「请求参数」页签           |
| 两级合并顺序   | 保持现状，供应商级在前、模型级覆盖                       | 已经发布的行为，改顺序会让既有配置含义翻转                         | 模型级在前                  |

### 加列的可移植性

`ensureProviderApiColumn` 能自动补列，是因为 `ADD COLUMN API VARCHAR(64)` 在所有目标库上都合法。`CLOB` 不行——MySQL 没有 `CLOB`，要用 `TEXT`。

处理办法：**逐列尝试两种类型**。

```
尝试 1: ALTER TABLE <t> ADD COLUMN <c> CLOB      // SQLite / PostgreSQL / Oracle / 达梦
尝试 2: ALTER TABLE <t> ADD COLUMN <c> TEXT      // MySQL / 部分国产库
都失败: 抛异常，消息里给出两种方言的手工 SQL
```

只在启动时跑一次，失败代价可接受。`hasColumn` 探测放在每次尝试之后，避免「列已存在」被误判为失败。

七列都走同一个方法，把它从「只管 API 列」改成通用的 `ensureColumn(jdbc, table, column)`，`ensureProviderApiColumn` 保留为对它的调用（japicmp 已排除 admin 模块，但仍保留旧方法名以免外部直接调用者失效）。

## 方案设计

### 数据表

```sql
CREATE TABLE AI_LITE_MODEL (
    MODEL_ROW_ID        VARCHAR(64)  NOT NULL,
    PROVIDER_ID         VARCHAR(64)  NOT NULL,
    MODEL_ID            VARCHAR(128) NOT NULL,
    READY               INT          NOT NULL,
    REASONING           INT,
    INPUT_MODES         VARCHAR(64),
    CONTEXT_WINDOW      INT,
    MAX_TOKENS          INT,
    THINKING_FORMAT     VARCHAR(64),
    DISPLAY_NAME        VARCHAR(128),
    -- 新增：模型级请求参数，JSON 文本。NULL = 沿用 YAML；'{}' = 明确清空
    THINKING_LEVEL_MAP  CLOB,
    SAMPLER_PARAMS      CLOB,
    SAMPLER_PARAMS_BY_LEVEL CLOB,
    MODEL_HEADERS       CLOB,
    EXTRA_BODY          CLOB,
    UPDATE_TIME         TIMESTAMP,
    PRIMARY KEY (MODEL_ROW_ID)
);

CREATE TABLE AI_LITE_PROVIDER_OVERLAY (
    PROVIDER_ID        VARCHAR(64)  NOT NULL,
    API                VARCHAR(64),
    BASE_URL           VARCHAR(500),
    API_KEY            VARCHAR(500),
    CONNECT_TIMEOUT_MS INT,
    READ_TIMEOUT_MS    INT,
    -- 新增：供应商级请求参数，JSON 文本。NULL = 沿用 YAML；'{}' = 明确清空
    PROVIDER_HEADERS      CLOB,
    PROVIDER_EXTRA_BODY   CLOB,
    UPDATE_TIME        TIMESTAMP,
    PRIMARY KEY (PROVIDER_ID)
);
```

列名用 `MODEL_HEADERS` / `PROVIDER_HEADERS` 而不是裸的 `HEADERS`：一是避开保留字风险，二是两级都有这个字段，列名带层级前缀后读库时不会搞混是哪一级。

### 核心对象

`CatalogModel` 增加五个字段，均为可空 Map：

| 字段                     | 类型                                 | null 的含义      |
| ---------------------- | ---------------------------------- | ------------- |
| `thinkingLevelMap`     | `Map<String, String>`              | 数据库没配，沿用 YAML |
| `samplerParams`        | `Map<String, Object>`              | 同上            |
| `samplerParamsByLevel` | `Map<String, Map<String, Object>>` | 同上            |
| `modelHeaders`         | `Map<String, String>`              | 同上            |
| `extraBody`            | `Map<String, Object>`              | 同上            |

`ProviderOverlay` 增加两个字段：

| 字段          | 类型                    | null 的含义      |
| ----------- | --------------------- | ------------- |
| `headers`   | `Map<String, String>` | 数据库没配，沿用 YAML |
| `extraBody` | `Map<String, Object>` | 同上            |

构造器策略（两个类同样处理）：

```java
// 旧签名保留，委托并填 null（表示沿用 YAML）
public CatalogModel(String providerId, String modelId, boolean ready, boolean reasoning,
                    List<String> inputModes, Integer contextWindow, Integer maxTokens,
                    String thinkingFormat, String displayName, long updatedAtMillis) {
    this(providerId, modelId, ready, reasoning, inputModes, contextWindow, maxTokens,
         thinkingFormat, displayName, updatedAtMillis,
         null, null, null, null, null);
}

// 新签名
public CatalogModel(..., long updatedAtMillis,
                    Map<String, String> thinkingLevelMap,
                    Map<String, Object> samplerParams,
                    Map<String, Map<String, Object>> samplerParamsByLevel,
                    Map<String, String> modelHeaders,
                    Map<String, Object> extraBody) { ... }
```

`ModelResolver.toConfigModel` 改成两参：数据库行 + YAML 同名模型。

```java
private static AiLiteConfig.Model toConfigModel(CatalogModel row, AiLiteConfig.Model yaml) {
    AiLiteConfig.Model model = new AiLiteConfig.Model();
    // 七项来自数据库（不变）
    model.setId(row.getModelId());
    ...
    // 五项：数据库有值用数据库；NULL 回落 YAML；都没有则空 Map
    model.setThinkingLevelMap(copyOr(row.getThinkingLevelMap(),
            yaml == null ? null : yaml.getThinkingLevelMap()));
    model.setSamplerParams(copyOr(row.getSamplerParams(),
            yaml == null ? null : yaml.getSamplerParams()));
    model.setSamplerParamsByThinkingLevel(copyByLevel(row.getSamplerParamsByLevel(),
            yaml == null ? null : yaml.getSamplerParamsByThinkingLevel()));
    model.setHeaders(copyOr(row.getModelHeaders(),
            yaml == null ? null : yaml.getHeaders()));
    model.setExtraBody(copyOr(row.getExtraBody(),
            yaml == null ? null : yaml.getExtraBody()));
    return model;
}
```

`copyOr` 在两个来源都为空时返回新的空 `LinkedHashMap`，保证 `resolve()` 里的 `getSamplerParamsByThinkingLevel().get(...)` 不 NPE。

供应商级的两项在 `applyOverlay` 里补同样的回落逻辑：

```java
private static AiLiteConfig.Provider applyOverlay(AiLiteConfig.Provider source,
                                                  ProviderOverlay overlay) {
    AiLiteConfig.Provider copy = new AiLiteConfig.Provider();
    ...
    copy.setHeaders(new LinkedHashMap<String, String>(source.getHeaders()));
    copy.setExtraBody(new LinkedHashMap<String, Object>(source.getExtraBody()));
    if (overlay == null) {
        return copy;
    }
    ...
    // 新增：覆盖表有值就用覆盖值；NULL 保留上一步从 YAML 复制来的那份
    if (overlay.getHeaders() != null) {
        copy.setHeaders(new LinkedHashMap<String, String>(overlay.getHeaders()));
    }
    if (overlay.getExtraBody() != null) {
        copy.setExtraBody(new LinkedHashMap<String, Object>(overlay.getExtraBody()));
    }
    return copy;
}
```

注意这里是「整份替换」而不是「逐键合并」：覆盖表里有 `X-Request-Source` 时，YAML 里的 `X-Provider-Version` 不会保留。这是刻意的——与现有 `baseUrl`、`apiKey` 的覆盖语义一致（覆盖是「换掉」，不是「叠加」）。合并发生在更外层的供应商级 vs 模型级之间，不是 YAML vs 数据库之间。

### 管理接口

`PUT /ai-lite/admin/api/providers/{name}/models/{modelId}` 的请求体增加五个可选键：

```json
{
  "reasoning": true,
  "contextWindow": 32768,
  "maxTokens": 4000,
  "thinkingFormat": "deepseek",
  "thinkingLevelMap": { "low": "low", "high": "high", "off": null },
  "samplerParams": { "temperature": 0.2, "top_p": 0.9 },
  "samplerParamsByLevel": { "high": { "temperature": 0.7 } },
  "modelHeaders": { "X-Model": "v2" },
  "extraBody": { "vendor_field": "x" }
}
```

`POST /ai-lite/admin/api/providers/{name}/overlay` 的请求体增加两个可选键：

```json
{
  "api": "openai-completions",
  "baseUrl": "https://example.com/v1",
  "apiKey": "",
  "connectTimeoutMs": 10000,
  "readTimeoutMs": 120000,
  "providerHeaders": { "X-Request-Source": "my-app" },
  "providerExtraBody": { "chat_template_kwargs": { "enable_thinking": true } }
}
```

`providerView` 增加这两项，页面才能显示当前生效值并标出来源（YAML 还是数据库）。

三种状态（两个接口同样）：

| 请求里的键        | 行为               |
| ------------ | ---------------- |
| 不出现          | 不改（沿用当前值）        |
| 出现且为空对象 `{}` | 写入 `'{}'`，表示明确清空 |
| 出现且非空        | 序列化后写入           |

校验：

- 根必须是对象；数组或标量 → 400。
- `thinkingLevelMap` 的键必须是 `off`/`low`/`medium`/`high`/`xhigh`/`max` 之一，值必须是字符串或 `null`。
- `samplerParamsByLevel` 的键同上，值必须是对象。
- `modelHeaders` / `providerHeaders` 键值都必须是字符串（不能用数字或嵌套）。
- 单个序列化结果超过 16384 字符 → 400（防止有人把图片 base64 塞进来）。
- JSON 语法错误 → 400，消息里带字段名和 Jackson 的原始错误位置。
- `providerHeaders` 里不允许出现 `Authorization`：密钥有专门的覆盖字段，从这里绕过会让「页面上密钥脱敏」失去意义，也会让 `hasPool` 的判断错乱。出现则 400，提示改用「密钥」字段。

`modelView` 增加五个模型级字段，`providerView` 增加两个供应商级字段，页面才能显示。

`saveCapabilities` 与 `saveOverlay` 的 SQL 都改成动态拼 SET 子句：只拼请求里出现的列，避免为所有组合各写一条语句，也不需要先读后写（无读改写的竞态）。

### 一个必须一起改的地方：测试调用与连通探测

`ModelSyncClient.tryChat`（`ModelSyncClient.java:192-194`）和 `probe`（`:241-242`）自己拼请求头，只放 `Authorization` 和 `Content-Type`，不读供应商配置里的 `headers`。上下文治理的摘要调用也走同一条路。

这意味着：**在页面上给供应商配了 `X-Request-Source` 之后，业务调用会带这个头，但「测试调用」和「测试连通」不带。** 如果供应商靠这个头做路由或灰度，会出现「页面上测通了但业务失败」，或者反过来——这正是 `ref-管理API.md` 里明确说要避免的错位。

所以本次要给 `ModelSyncClient` 的四个入口（`listRemote`、`tryChat`、`probe` 以及它们不带 `HttpExecutor` 的重载）都加一个 `Map<String, String> extraHeaders` 参数，由 `AdminController` 把 `describe()` 里的生效头传进去。这是 `ai-lite-admin-api` 的内部改动，不涉及公开契约。

带 `HttpExecutor` 的那几个入口用新参数，旧签名保留并委托传空 Map，避免改动调用方。

### 页面

**模型编辑弹窗**在「思考格式」下方加一组「请求参数（高级）」：

| 控件       | 内容                                            |
| -------- | --------------------------------------------- |
| 行编辑表     | 思考档映射。左列下拉选档位（`off`…`max`），右列文本填供应商档位，留空表示不支持 |
| 行编辑表     | 模型请求头。左列头名，右列头值，可增删行                          |
| JSON 文本框 | 采样参数                                          |
| JSON 文本框 | 分档采样参数                                        |
| JSON 文本框 | 附加请求体                                         |

**供应商覆盖弹窗**在「读取超时」下方加一组「请求参数（高级）」：

| 控件       | 内容                    |
| -------- | --------------------- |
| 行编辑表     | 供应商请求头。左列头名，右列头值，可增删行 |
| JSON 文本框 | 供应商附加请求体              |

约定：

- `thinking-level-map`、`headers`、`providerHeaders` 用行编辑表，因为它们都是扁平键值对，行编辑比手写 JSON 直观。
- 三个嵌套结构用 JSON 文本框，配「格式化」按钮；失焦时校验，错误就地显示红字，不弹原生 `alert`。
- 空状态显示占位提示。两级要分开写：
  - 模型级：「未配置。走业务调用时继承配置文件。」
  - 供应商级：「未配置。走业务调用时使用配置文件里的值。」
- 供应商弹窗里，如果当前值来自 YAML 而不是数据库，要在标签旁标一个「来自配置文件」的小标记，避免用户以为页面上显示的就是他能改的那份。
- 模型弹窗底部加可折叠的「这次会发什么」，显示合并后的请求头键名清单（标出每个键来自供应商级还是模型级）和请求体里将出现的非标准字段名。只显示键，不显示值。

`admin.src.css` 补三种新样式：行编辑表、JSON 框、「来自配置文件」标记。构建产物 `admin.css` 一并提交。

### 谁优先（写进文档的那句话）

> 同一层级，数据库里有值就用数据库；数据库为 NULL 时从 YAML 继承；两边都没有则为空。
> 
> 模型级与供应商级之间，同名键模型级覆盖供应商级。

不使用「深合并」。原因：一个 Map 里既有数据库来源又有 YAML 来源时，排查「这个 `temperature` 是谁写的」需要读两处配置再加一次合并逻辑，比现在更糟。

## 流程

### 主路径

1. 启动 → `JdbcRuntimeCatalog` 构造 → 逐列探测七个新列 → 缺则 `ALTER TABLE`。
2. 管理页打开供应商 → `GET /providers` → `providerView` 带上两个字段 → 供应商弹窗渲染行编辑表与 JSON 框。
3. 管理页打开模型 → `modelView` 带上五个字段 → 模型弹窗渲染同样两组控件。
4. 用户修改 → 保存 → `PUT /models/{id}` 或 `POST /providers/{name}/overlay` → 校验 → 序列化 → 动态 UPDATE → `invalidate()`。
5. 业务调用 `LlmCall.biz` → `findBinding` → `findModel`（带五个 Map）+ `findProvider`（带两个 Map）→ `toConfigModel(row, yaml)` 与 `applyOverlay(source, overlay)` → `resolve()` 合并进 `ResolvedModel` → 传输层写入请求头与请求体。
6. 管理页「测试调用」→ `describe()` 产出同一份生效头 → 传给 `ModelSyncClient.tryChat` → 与业务调用带同样的头。

### 意外与边界

| 情况                                              | 行为                                                                |
| ----------------------------------------------- | ----------------------------------------------------------------- |
| 七个列不存在且自动补列失败                                   | 启动失败，消息给出两种方言的手工 SQL                                              |
| 数据库里 JSON 损坏（手工写入非法文本）                          | 读取时抛 `IllegalStateException`，消息带表名、模型 id、列名。不静默当空处理——否则又变成难查的静默降级 |
| YAML 里也没有这个模型 id                                | 模型级五个字段为空 Map，行为与修复前一致                                            |
| YAML 里也没有这两项供应商配置                               | 供应商级两个字段为空 Map，行为与修复前一致                                           |
| 请求同时带 `{}` 与 YAML 有值                            | 用 `{}`，不回落 YAML（明确清空优先）                                           |
| 页面提交非法 JSON                                     | 400，页面红字定位到具体字段                                                   |
| 老版本页面提交（不带新键）                                   | 不改这些列，行为同现在                                                       |
| 供应商级和模型级配了同名头                                   | 供应商级在前、模型级覆盖，顺序不变（`ModelResolver.java:263-269`）。本次不改合并逻辑，只补数据来源   |
| 同一供应商下多个模型需要不同 `temperature`                    | 可以，因为列在模型行上。这正是选数据库而非只存供应商级的原因                                    |
| 覆盖表设了 `providerHeaders`，配置文件里也有 `headers`       | 整份替换，不逐键合并。与 `baseUrl`、`apiKey` 的覆盖语义一致                           |
| 覆盖表设了 `providerHeaders` 但没设 `providerExtraBody` | 前者用数据库、后者继续用 YAML，两者独立判断                                          |

## 风险/待确认

| 风险                                     | 影响                         | 缓解                                                                        |
| -------------------------------------- | -------------------------- | ------------------------------------------------------------------------- |
| 自动补列在权限受限的库上失败                         | 启动失败                       | 错误消息给出手工 SQL；文档写明需要 DDL 权限                                                |
| CLOB 在部分库上读回需要特殊处理                     | 读取异常                       | 统一用 `rs.getString(...)`，与 `JdbcAgentSessionStore` 读 `MESSAGES_JSON` 的做法一致 |
| 七列让表变宽                                 | 每 5 秒一次的全表快照变大             | 体积可控（每行几百字节）；表变大时按现有建议调 `cache-seconds`                                   |
| 页面把 JSON 写坏了                           | 调用失败                       | 保存前校验 + 读取时明确报错，不静默                                                       |
| 覆盖表存了 `headers` 后，测试调用与业务调用请求头不一致      | 「测通了但业务失败」                 | 同一个 `describe()` 产出传给 `ModelSyncClient`；T8 加专项测试                          |
| 覆盖表存了 `headers`，配置文件的同名内容被整份替换         | 用户以为两级叠加，实际是替换             | 文档与页面提示都写明「覆盖是替换不是叠加」                                                     |
| 两级头名大小写不同，变成两个头                        | 模型级的「覆盖」没生效，服务器可能取第一个或直接报错 | T10 做大小写无关的去重                                                             |
| 在 `headers` 里写 `authorization` 会顶掉真实密钥 | 请求带上错误的凭据，且不报错             | T10 一并拦掉保留头                                                               |

## 任务分解

| 编号  | 任务与验收                                                                                           | 涉及文件                                                                        | 状态   |
| --- | ----------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- | ---- |
| T1  | `CatalogModel` 加五个可空字段，`ProviderOverlay` 加两个，两个旧构造器都保留。旧测试全通过                                   | `CatalogModel.java`、`ProviderOverlay.java`                                  | todo |
| T2  | `toConfigModel` 改成两参实现 NULL→YAML 回落；`applyOverlay` 补供应商级两项。单测覆盖：数据库有值、NULL 回落、两边都空、供应商级覆盖       | `ModelResolver.java`、`ModelResolverCatalogTest`                             | todo |
| T3  | `JdbcRuntimeCatalog` 读写七列，`ensureColumn` 通用补列（两种方言），`ensureProviderApiColumn` 改为调用它。单测覆盖往返与旧表补列 | `JdbcRuntimeCatalog.java`、`JdbcRuntimeCatalogTest`                          | todo |
| T4  | 建表脚本补七列（admin 与 demo 两处）                                                                        | `ai-lite-catalog.sql`、`schema.sql`                                          | todo |
| T5  | 管理接口：两个 PUT 的解析、校验、`modelView`/`providerView` 暴露。MockMvc 覆盖三种状态与五类非法输入                          | `AdminController.java`、`AdminWebTest`                                       | todo |
| T6  | `ModelSyncClient` 四个入口加 `extraHeaders`，`AdminController` 传入生效头。测试断言测试调用带的头与业务调用一致               | `ModelSyncClient.java`、`AdminController.java`、`ModelSyncClientTest`         | todo |
| T7  | 页面：模型弹窗五行 + 供应商弹窗两行 + 请求预览 + 「来自配置文件」标记。浏览器验证渲染与保存                                              | `admin.js`、`admin.src.css`、`admin.css`                                      | todo |
| T8  | 端到端：同一模型两种调用方式的请求体与请求头一致                                                                        | 新增 `ModelLevelParamsTest`（断言请求体含 `temperature`、头含 `X-Model` 与 `X-Provider`） | todo |
| T9  | 文档（含升级说明）                                                                                       | `ref-配置.md`、`ref-管理API.md`、`desc-数据表与缓存.md`、`desc-配置和运行时配置.md`、demo README  | todo |
| T10 | 头名大小写去重 + 保留头拦截                                                                                 | `ModelResolver.java`、`AdminController.java`、`TransportSupport.java`、对应测试    | todo |
| T11 | CHANGELOG 升级说明                                                                                  | `CHANGELOG.md`                                                              | todo |

### T10：头名大小写去重与保留头

**问题。** `ModelResolver.java:263-265` 用 `Map.putAll` 合并两级的头，而 `LinkedHashMap` 区分大小写。实测：

```
供应商级 { X-Request-Source: from-provider }
模型级   { x-request-source: from-model }
→ [X-Request-Source] = from-provider
  [x-request-source] = from-model
  同名不同大小写的键数量 = 2
```

两个头都发出去了。服务器要么取第一个（模型级的覆盖没生效），要么因为重复头报错，与配置意图相反。

**另一个更危险的情况。** 模型级写 `authorization`（小写）实测结果：

```
[Content-Type] = application/json; charset=utf-8
[authorization] = Token custom
authorization 键数=1
```

`containsHeader`（`OpenAiCompletionsTransport.java:310`）是大小写无关的判断，所以它认出了「已经有 Authorization」，就不再补 `Bearer {真实密钥}`。结果是**真实密钥被静默顶掉**，请求带着 `Token custom` 发出去。

同理，写 `content-type` 会得到两个 Content-Type：

```
[Content-Type] = application/json; charset=utf-8
[content-type] = application/x-custom
content-type 键数=2
```

**处理办法分两层。**

第一层，合并时做大小写无关去重。不改 `Map` 的类型，改合并函数：先放供应商级，再放模型级，放模型级时若已存在大小写不同的同名键，先移除旧键。

```java
/** 大小写无关地放入；已存在同名键（忽略大小写）时先移除，实现「后来的覆盖先前的」。 */
private static void putHeaderIgnoreCase(Map<String, String> target, String name, String value) {
    String existing = null;
    for (String key : target.keySet()) {
        if (key.equalsIgnoreCase(name)) {
            existing = key;
            break;
        }
    }
    if (existing != null) {
        target.remove(existing);
    }
    target.put(name, value);
}
```

`resolve()` 里改用这个方法，替换两处 `putAll`：

```java
Map<String, String> headers = new LinkedHashMap<String, String>();
for (Map.Entry<String, String> e : p.getHeaders().entrySet()) {
    putHeaderIgnoreCase(headers, e.getKey(), e.getValue());
}
for (Map.Entry<String, String> e : m.getHeaders().entrySet()) {
    putHeaderIgnoreCase(headers, e.getKey(), e.getValue());
}
```

去重后保留**后写入那个的原始大小写**（即模型级写的那个），这样用户看到的就是他填的样子。

第二层，拦掉保留头。`Authorization` 与 `Content-Type` 由库管理，不允许通过 `headers` 覆盖：

- 配置阶段（`validateStructure`）：`provider.headers` 或 `model.headers` 里出现这两个键（忽略大小写）时抛 `LlmCallException`，文案说明「Authorization 与 Content-Type 由库管理，请用 api-key 配置密钥」。启动即失败，比运行时发错密钥好查得多。
- 管理接口（T5 的校验）：同样拦掉，返回 400。这一条已在计划的校验清单里写了 `Authorization`，现在把 `Content-Type` 也加进去。

**为什么不改成允许覆盖。** 允许覆盖 `Authorization` 有正当场景（非 Bearer 的认证方式），但那应该做成显式的配置项而不是靠 `headers` 碰运气——现在这样写错一个大小写就会静默失效，是陷阱不是能力。

**验收。**

| 用例                   | 期望                      |
| -------------------- | ----------------------- |
| 两级同名同大小写             | 只有一个头，模型级的值             |
| 两级同名不同大小写            | 只有一个头，模型级的值与大小写         |
| 两级不同名                | 两个头都在                   |
| 模型级写 `authorization` | 启动失败，文案指向 api-key       |
| 模型级写 `Content-Type`  | 启动失败                    |
| 两级都没有保留头             | 正常，`Authorization` 由库补上 |

### T11：CHANGELOG 升级说明

单独成任务，因为这次改动的迁移路径需要写清楚，不能只在「新增」里列一句。

`CHANGELOG.md` 的 0.1.15 段要包含：

**新增**

- 模型级五个请求参数可在管理页配置：思考档映射、采样参数、分档采样参数、模型请求头、模型附加请求体。
- 供应商级两个请求参数可在管理页配置：供应商请求头、供应商附加请求体。
- 两级头名大小写无关去重；`Authorization` 与 `Content-Type` 改为保留头。

**修复**

- 目录模式下模型级请求参数丢失：`LlmCall.biz` 与 `LlmCall.explicit` 对同一模型发出的请求体此前不一致，现已一致。
- 管理页「测试调用」「测试连通」此前不读供应商级请求头，配了自定义头后与业务调用不一致，现已读取同一份生效头。

**升级说明（单独一节，不能省）**

1. **必须加列，但不需要迁移数据。** 表结构要变，否则启动失败。正常情况由 `JdbcRuntimeCatalog` 构造时自动补列；数据库账号没有 `ALTER TABLE` 权限时需手工执行，错误消息会给出两种方言的完整 SQL。
2. **已有数据不受影响。** 新列默认 NULL，解析时回落到配置文件对应的层级，行为与升级前完全一致。不需要回填，也不需要改 YAML。
3. **回滚安全。** 老版本代码的查询不含新列，加列之后回滚到旧版本仍可正常运行。因此可以先在所有环境加列，再逐步替换实例。
4. **行为变化警告：`headers` 里写 `Authorization` 或 `Content-Type` 的应用会在升级后启动失败。** 此前这些头能生效（`Authorization` 还会顶掉真实密钥），现在被明确拒绝。改用 `api-key` 配置密钥。这是刻意的破坏性变更，理由是静默顶掉密钥比启动失败危险得多。如果确有非 Bearer 认证需求，在 issue 里说明场景，后续做成显式配置项。
5. **行为变化警告：两级同名但大小写不同的头会合并成一个。** 此前会发出两个头。如果有依赖「两个头都发出去」的用法（基本不可能，但需说明），升级后会变成只有一个。

第 4 条是唯一会让人升级即启动失败的改动，必须在 CHANGELOG 顶部用醒目格式标出，并在 `ref-配置.md` 的 `headers` 字段说明里同步写明。

### 文件改动清单

| 文件                                                                                 | 操作                                        |
| ---------------------------------------------------------------------------------- | ----------------------------------------- |
| `ai-lite-llm/.../catalog/CatalogModel.java`                                        | 修改：五个可空字段 + 新构造器                          |
| `ai-lite-llm/.../catalog/ProviderOverlay.java`                                     | 修改：两个可空字段 + 新构造器                          |
| `ai-lite-llm/.../config/ModelResolver.java`                                        | 修改：`toConfigModel` 两参、`applyOverlay` 补两项  |
| `ai-lite-admin-api/.../jdbc/JdbcRuntimeCatalog.java`                               | 修改：七列读写、`ensureColumn` 通用补列、动态 SET        |
| `ai-lite-admin-api/.../web/AdminController.java`                                   | 修改：解析、校验、两个 view、把生效头传给 `ModelSyncClient` |
| `ai-lite-admin-api/.../sync/ModelSyncClient.java`                                  | 修改：四个入口加 `extraHeaders`，旧签名委托             |
| `ai-lite-admin-api/src/main/resources/ai-lite-catalog.sql`                         | 修改：七列                                     |
| `ai-lite-demo/src/main/resources/schema.sql`                                       | 修改：七列                                     |
| `ai-lite-admin-ui/.../admin.js`                                                    | 修改：模型弹窗与供应商弹窗各加编辑区                        |
| `ai-lite-admin-ui/frontend/admin.src.css`                                          | 修改：三种新样式                                  |
| `ai-lite-admin-ui/.../admin.css`                                                   | 重新构建                                      |
| `ai-lite-llm/src/test/.../ModelResolverCatalogTest.java`                           | 修改：四个回落/覆盖用例                              |
| `ai-lite-admin-api/src/test/.../JdbcRuntimeCatalogTest.java`                       | 修改：往返与补列                                  |
| `ai-lite-admin-api/src/test/.../AdminWebTest.java`                                 | 修改：三种状态与非法输入                              |
| `ai-lite-admin-api/src/test/.../ModelSyncClientTest.java`                          | 修改：生效头透传                                  |
| `ai-lite-llm/src/test/.../ModelLevelParamsTest.java`                               | 新增：请求体与请求头断言                              |
| `docs/ref-配置.md`、`docs/ref-管理API.md`、`docs/desc-数据表与缓存.md`、`docs/desc-配置和运行时配置.md` | 修改                                        |
| `CHANGELOG.md`、`ai-lite-demo/README.md`、`pom.xml`（版本号）                             | 修改                                        |

清单是预估。实施中为落实行为补的测试或类，更新本表后继续。

## 实现步骤

1. T1 先写失败用例（断言两级的字段存在），再加字段。跑 `ai-lite-llm` 全量确认两个旧构造器不破。
2. T2 写 `toConfigModel` 与 `applyOverlay` 的回落用例再改实现。用假目录验证，不依赖数据库。
3. T3 加列与读写，用 SQLite 测试库验证往返、补列、JSON 损坏三种情况。
4. T4 同步两份 SQL 脚本，与 T3 一起验证（旧表启动后能读到新列）。
5. T10 先做大小写去重与保留头拦截——它改的是 `resolve()` 的合并逻辑，T5 的接口校验要复用同一套判定。先写「两级同名不同大小写只留一个」与「写 authorization 启动失败」两个用例。
6. T5 接口与校验，先写 400 用例再实现；保留头拦截复用 T10 的判定。
7. T6 先写「测试调用带的头与业务调用一致」的用例，再给 `ModelSyncClient` 加参数。
8. T8 用捕获式 `HttpExecutor` 断言两种调用方式的请求体与请求头一致——这是本次的验收核心。
9. T7 页面最后做，改完重新构建 `admin.css`。
10. T11 先于 T9 写 CHANGELOG 的升级说明，因为其中的「破坏性变更」条目会影响 `ref-配置.md` 的措辞。
11. T9 文档最后写，把 §9.1 从「已知限制」改成「已修复」，并保留那段实测对比作为回归依据。

## 验证计划

| 验收项     | 方法                                                                                | 通过标准                                                                |
| ------- | --------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| 旧构造器兼容  | `mvn -pl ai-lite-llm -am test`                                                    | 全绿，无编译错误                                                            |
| 解析一致    | `mvn -pl ai-lite-llm test "-Dtest=ModelResolverCatalogTest,ModelLevelParamsTest"` | 两级字段都进请求；NULL 时回落 YAML；两边空时与修复前一致                                   |
| 头去重与保留头 | `mvn -pl ai-lite-llm test "-Dtest=HeaderMergeTest"`                               | 同名不同大小写只剩一个且用模型级的值与大小写；不同名都保留；写 `authorization`/`Content-Type` 启动失败 |
| 补列与往返   | `mvn -pl ai-lite-admin-api test "-Dtest=JdbcRuntimeCatalogTest"`                  | 旧表自动补七列；七个字段读写往返；JSON 损坏明确报错                                        |
| 接口校验    | `mvn -pl ai-lite-admin-api test "-Dtest=AdminWebTest"`                            | 不传不改、`{}` 清空、非空写入；保留头与五类非法输入均 400                                   |
| 测试调用一致  | `mvn -pl ai-lite-admin-api test "-Dtest=ModelSyncClientTest"`                     | 生效头透传到 `tryChat` 与 `probe`                                          |
| 端到端     | demo 上把两级参数写进数据库，用假网关抓请求                                                          | 与 YAML 路径的请求体、请求头一致                                                 |
| 页面      | 启动 demo 打开两个弹窗                                                                    | 七个控件可见、可保存、非法 JSON 就地报错                                             |
| 二进制兼容   | `mvn -pl ai-lite-llm verify`                                                      | japicmp 通过（两个旧构造器仍在）                                                |

## 观察项

- `context-window` 为 NULL 时会被 `resolve` 读成 0，而 0 表示「未知，跳过全部预算检查与压缩」。同步来的模型只点了就绪、没填能力参数时，长会话不会触发压缩。这与本次修的不是同一个问题（本次是「数据库没值时回落哪」），记在这里备查，不在本次范围。
- 页面新增的 JSON 输入框如果不做长度限制，用户可能把整段提示词粘进去。校验上限 16384 字符只是兜底，不是设计意图。
- 两级 `extra-body` 之间是 `putAll` 顶层覆盖，嵌套对象整块替换；而 `extra-body` 与单次调用的 `extraBody` 之间是递归深合并。这个不一致在本次之前就存在，T10 只处理头名，不动这里。若要统一，属于另一个改动。

## 实施记录

- 2026-11-04：只写计划，未改代码。用户明确要求「给 `AI_LITE_MODEL` 加列」。
- 2026-11-04：用户追问「这些字段全是模型级的吗」，核对后确认 `headers` 与 `extra-body` 两级都有，且供应商级在目录模式下本来就正常（`applyOverlay` 会复制）。补充「七个字段的层级归属」一节。
- 2026-11-04：用户要求「供应商级的 headers 和 extra body 也要在对应的表中补列，并提供管理能力」。计划扩为七列：`AI_LITE_MODEL` 五列（修 bug）+ `AI_LITE_PROVIDER_OVERLAY` 两列（补能力）。同时发现并纳入一个新问题：`ModelSyncClient` 自己拼请求头，配了供应商级头之后「测试调用」与业务调用会不一致，新增 T6 处理。
- 2026-11-04：用户要求加 T10 处理头名大小写去重、并补一条 CHANGELOG 升级说明任务。实测发现两处更严重的问题：两级同名不同大小写会发出两个头（模型级的覆盖没生效）；在 `headers` 里写 `authorization`（小写）会顶掉真实密钥且不报错。T10 因此扩为「去重 + 保留头拦截」，T11 单列 CHANGELOG 升级说明，其中「写保留头会在升级后启动失败」标为破坏性变更。
- 2026-11-04：T1–T11 全部完成并通过测试。交付内容包括：七列 JSON 存储、NULL→配置文件回落、头名大小写去重、保留头拦截、管理页两处编辑区、请求预览、「测试调用」带两级参数并改走真实协议适配器、请求回显显示真实头。
- 2026-11-04：用户反馈「测试调用弹窗里选不了思考档，档位跑到弹窗后面」。查实是两个问题叠加：
  1. **层级**：`.select-menu` 的 `z-index` 是 40，`.modal-backdrop` 是 60。菜单为了不被 overflow 裁切挂在 `body` 上，所以在弹窗里打开时被遮罩盖住。修法是新增 `raiseAboveModals()`，打开菜单时按「当前最上层弹窗 + 1」动态抬高。没有改成固定值，因为弹窗还能叠弹窗（确认框叠在弹窗上），固定值会在下一层复发。
  2. **变量重名**：`THINKING_LEVELS` 被定义了两次——原有的是纯 id 数组（含 `minimal`），本次新加的是 `{id,label}` 数组，后者覆盖前者。于是测试调用弹窗和业务绑定处的 `THINKING_LEVELS.map` 拿到整个对象当 id，选项显示成 `[object Object]`。这是本次改动引入的缺陷。修法是拆成两个名字：`THINKING_LEVEL_IDS`（纯 id，旧弹窗用）与 `THINKING_LEVEL_OPTIONS`（带标签，行编辑表用），并把 `minimal` 补回选项列表。
  浏览器实测：弹窗内菜单 `z-index=61 > 60`、命中最上层是 `select-option`、7 个档位标签正常、点选后回填为 `high`；非弹窗场景（业务绑定）也正常。
