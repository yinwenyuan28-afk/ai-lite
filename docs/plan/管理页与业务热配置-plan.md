---
status: done
created: "2026-10-27 15:45"
updated: "2026-10-27 20:43"
model: "Grok 4.7"
---

# 管理页与业务热配置 **（8/8 done）**

在引入起步依赖的应用自己的端口上挂一个可关闭的管理页，用来看调用日志、改供应商连接、从 `/models` 同步模型、按业务 id 配置模型和一份提示词。配置文件只保留启动所必须的供应商名单和业务 id，运行中要改的内容进数据库。

## 背景与问题

网新 `A104527` 近期提交已经有三块可对照的能力，但它们长在业务系统里，不能原样搬进这个库：

- `ad066a00`：供应商以 yml 为基线，运行时覆盖写进 Redis，经 Pub/Sub 刷新各实例；管理接口不能新增供应商名。
- `b30b656d`：连通性探测调用 OpenAI 兼容 `GET {baseUrl}/models`，只看 HTTP 结果，不落库、不写调用日志。
- `475eb5f6`、`bd9ba4fb`：调用日志只读；提示词 key 仍要在配置里登记。前端是 `A104526` 的 AI Hub（提示词 / 供应商 / 调用日志）。

本库现状（`0.1.8-SNAPSHOT`）要新增一个业务，配置文件里要写三处：`ai-lite.llm.biz-ids`、`profiles`（供应商 + 模型 + 思考档）、`ai-lite.prompt.keys`（编码、标题、占位符、默认模板）。模型 id 还写在 `providers.*.models`。`ModelResolver.validateStructure()` 在供应商没有模型时直接拒绝启动。

调用链路已经存在，本次只改「按业务 id 找模型」和「按业务 id 找提示词」：

- `LlmGateway` 先检查业务 id 是否在白名单，再走 `resolve()`：有 profile 就 `resolveProfile`，否则在 provider 与 model 都空时用 `default-profile`，再否则 `resolveExplicit`。
- 提示词表 `AI_LITE_PROMPT_VERSION` / `ACTIVE` / `AUDIT` 已经按 key 做草稿和发布。`PromptManager.render` 的顺序是：生效版本 → 调用方兜底 → 配置默认模板。
- 调用记录表 `AI_LITE_LLM_CALL` 只存摘要，不含提示词、响应正文和密钥。`JdbcLlmCallStore` 目前只写不查。
- `ai-lite-demo` 依赖的是 `spring-boot-starter`，没有 Web 服务器，启动后没有可挂页面的端口。

## 目标与验收

1. 配置文件里新增业务只加一行 `ai-lite.llm.biz-ids`。这个 id 对应一份提示词，以及一条「供应商 + 模型 + 思考档」绑定，两者都在数据库里，管理页保存后本机立即生效。
2. 业务调用只传业务 id。库里没有模型绑定或没有已发布提示词时，调用失败并说明缺的是哪一项，不回退到 yml 的 profile 或默认模板。
3. 模型 id 由管理页调用该供应商的 `GET {baseUrl}/models` 增量写入数据库。能力参数在页面上补齐之后，这个模型才能被业务选中。
4. 管理页默认不注册。`ai-lite-demo` 的配置把开关打开，浏览器打开该应用端口上的 `/ai-lite/admin` 能看到三个页签并完成上面三件事。
5. 不引入新模块的现有测试与调用方式保持原行为：`LlmCall.explicit`、`LlmCall.profile`、yml 里的 models / profiles / prompt.keys 仍按现在的解析。

## 范围

仓库：`ai-lite-spring-boot-starter`（父工程 `ai-lite-parent`）。

覆盖：

- 核心解析：新增「只按业务 id 解析」的入口，以及可插拔的运行时目录接口。
- 新模块 `ai-lite-admin`：目录表的 JDBC 实现、管理接口、单页面。
- 提示词：目录模式下，提示词 key 就是业务 id，复用已有三张提示词表。
- 调用日志：按已有 `AI_LITE_LLM_CALL` 做只读查询。
- demo：补上 Web 服务器、数据源和打开的开关，供当前调试使用。
- 文档：配置参考、上层调用指南、仓库 README 中的配置片段、CHANGELOG、demo README。

## 能力清单

| 能力域 | 能力 | 说明与验收口径 |
| --- | --- | --- |
| 开关 | 管理页默认关闭 | 不写 `ai-lite.admin.enabled` 时，上下文里没有页面和 `/ai-lite/admin/**` 接口 |
| 开关 | 调试环境打开 | `ai-lite-demo` 的 `application.yml` 写 `enabled: true`，启动后 `/ai-lite/admin` 返回页面 |
| 供应商 | 查看并覆盖连接信息 | 可改 Base URL、Api Key、连接超时、读取超时。不能新增或改名供应商。yml 文件不被修改。列表上的密钥脱敏 |
| 供应商 | 测试连通 | 调用 `GET {baseUrl}/models`，返回 HTTP 状态与耗时，不写调用日志、不改模型表 |
| 模型 | 从 `/models` 增量同步 | 新 id 插入且标为未就绪；已存在的行不覆盖；接口里消失的 id 不删除 |
| 模型 | 补齐能力参数 | 推理开关、上下文窗口、max-tokens、思考格式保存后才可被业务选中。`/models` 带不回这些字段 |
| 模型 | 非 OpenAI 兼容协议手工录入 | `anthropic-messages` 不调 `/models`，页面上手工添加模型 id，仍然要补齐能力参数后才能选 |
| 业务 | 一个 id 一份提示词 | 提示词 key 等于业务 id。页面上只显示 yml 白名单里的 id |
| 业务 | 绑定所用模型 | 保存供应商、已就绪模型、思考档。未就绪模型不能保存 |
| 调用 | 按业务 id 取模型 | `LlmCall.biz(bizId, messages)` 走数据库绑定。缺绑定时错误文案为「业务未配置模型: {id}」 |
| 提示词 | 按业务 id 渲染 | 没有已发布版本时错误文案为「提示词未发布: {id}」，不使用 yml 默认模板 |
| 日志 | 只读查询 | 按业务、供应商、时间倒序查近期记录。响应不含提示词正文、响应正文、密钥 |

## 不做的事

- 不做网新那套 Vue 页面，也不引入前端构建。页面是模块里的一份静态 HTML，由后端直接返回。
- 不引入 Redis，不做集群 Pub/Sub。多实例靠短缓存过期看到别的实例写入的数据。
- 不做访问口令。开关关闭时接口不存在；打开后本次不再加鉴权。
- 不在管理页上新增供应商名或业务 id。这两个名单只来自配置文件，改完要重启。
- 不删除 `LlmCall.explicit` 和 `LlmCall.profile`，也不改它们在「未装配运行时目录」时的行为。
- 不自动执行建表。和现有 prompt-jdbc / store-jdbc 一样，只提供 SQL 文件；表不在则启动失败并指出文件。
- 不新建业务唯一索引或 UNIQUE 约束。主键可以建。模型重复在写入前用代码查询拒绝。
- 不把提示词正文、模型响应正文、密钥写进调用日志。
- 不改执行器、HTTP 传输、会话、上下文治理的行为。

## 设计原则

- 配置仍收在已有的 `ai-lite` 前缀下，不新建配置文件。密钥可以留在 yml 的环境变量占位符里；管理页写入的覆盖密钥只进数据库，列表接口只返回脱敏值。
- 库的可选能力沿用独立模块 + `spring.factories`，不把 `spring-web` 塞进所有使用者的依赖树。
- 运行时读目录与管理页写目录分开：页面关闭时，已经装配的目录仍然参与解析，否则关掉页面就会退回 yml。
- JDK 8、Spring Boot 2.1 基线。不使用 `var`、`List.of`、记录类型。
- 新表只建主键。模型是否已存在，在插入前按供应商 id + 模型 id 查一次；查到就返回业务错误，不靠数据库抛重复键再翻译。
- 中文文案与现有异常一致，例如已有的「业务 id 未登记: …」。

## 已确认决策

| 决策 | 来源与确认时间 | 选择 | 理由与后果 | 被否选项 |
| --- | --- | --- | --- | --- |
| 管理页挂载位置 | 用户 2026-10-27 对齐需求 | 页面和接口都在引入该依赖的应用端口上 | 不单独起前端工程 | 搬进网新的 Vue 业务系统 |
| 业务与提示词 | 用户 2026-10-27 15:37 | 一个业务 id 只对应一份提示词 | 配置文件只登记 id，页面上一行同时管模型和这一份提示词 | 一个业务下多份提示词、另设 prompt key |
| 管理页默认值 | 用户 2026-10-27 15:37 | 库默认关闭 | 对外端口默认不暴露改密钥和提示词的入口 | 默认打开 |
| 当前调试 | 用户 2026-10-27 15:37 | demo 配置里先打开 | 调试期要能直接打开页面；调试结束再把 demo 的开关改回 false | 连库默认值一起改成 true |
| 缺配置时的行为 | 用户确认收口后的规格（2026-10-27） | 没有模型绑定或没有已发布提示词就失败，不回退 yml | 避免配置文件和数据库各算一套 | 回退到 profile 或默认模板 |
| 模型名单来源 | 用户 2026-10-27 对齐需求 | `/models` 增量入库；能力参数页面补齐后才可选 | 接口只返回 id，带不回窗口和思考格式 | 把能力参数继续写在 yml；同步时删掉接口里已经消失的 id |
| 供应商名单 | 用户确认的收口规格 | 名字和协议写在配置文件；页面只覆盖地址、密钥、超时 | 没有端点就无法调 `/models`；与网新「未知供应商须先在 yml 声明」一致 | 在页面上凭空新增供应商 |

实施授权：用户 2026-10-27 15:54 要求审查计划后开始执行。审查见下方修订。状态 `executing`。

### 审查修订（2026-10-27）

三名只读审查分别看了方案与代码衔接、范围、交付顺序。结论都是先改计划再写代码。下面这些是落实已确认规格时补上的实现约束，不改变增量同步、默认关闭、一个业务一份提示词。

| 事项 | 选择 | 依据 |
| --- | --- | --- |
| 业务 id 格式 | 目录模式下业务 id 必须通过 `PromptCatalog.validKey`（小写字母、数字、短横线，最长 64）。`AI_LITE_BIZ_BINDING.BIZ_ID` 用 `VARCHAR(64)`。不符合的 id 在启动期失败，文案说明业务 id 不符合提示词编码 | 提示词 key 就是业务 id，现有 `PROMPT_KEY` 列和 `PromptCatalog` 正则装不下任意字符串。放宽正则或加宽列会改已有提示词契约，本次不改 |
| 缺提示词的失败点 | 缺模型绑定在 `LlmCall.biz` 进网关时失败。缺已发布提示词在 `promptManager.render(bizId)` 失败。网关不读提示词 | 现有调用就是先渲染再发请求。网关里的消息由调用方传入，塞进提示词检查会把两套入口耦在一起 |
| 提示词失败文案 | 只有目录模式的 key（`PromptKey` 上标记 `catalogBound`）在存储正常且没有生效版本、也没有调用方兜底时抛「提示词未发布」。存储抛错仍走现有路径，不伪装成未发布。无目录的 key 继续抛「提示词模板未配置」 | `PromptManagerTest.renderRejectsMissingTemplate` 锁着旧文案。全局改文案会破坏「prompt.keys 保持现状」 |
| 提示词存储 | 目录模式必须装配已有 `JdbcPromptStore`，并已执行 `ai-lite-prompt.sql`。没有这个存储则启动失败并指向该文件 | 只引入 admin 时若落到 `InMemoryPromptStore`，发布在重启后丢失，不满足热更新 |
| 装配顺序 | 目录自动装配：`@AutoConfigureAfter` JdbcTemplate 自动装配；`@AutoConfigureBefore` 用字符串类名指向 `AiLitePromptAutoConfiguration`。外层套 `ai-lite.enabled`，内层开关拆到嵌套类。`aiLiteModelResolver` 不改参数列表，从已有 `BeanFactory` 取 `RuntimeCatalog`，没有则 Noop | Boot 2.1 的 `@ConditionalOnBean` 看不到更晚注册的 Bean；`@Bean` 方法加参数会被 japicmp 判成方法删除。`AiLiteAutoConfiguration` 第 353 行已有同样约束 |
| 管理页注册 | `admin.enabled=true` 且 `@ConditionalOnWebApplication`，不只看 `DispatcherServlet` 类在不在 | 模块自己依赖 spring-webmvc，类在类路径上不代表进程有 Web 端口 |
| 密钥覆盖 | 探测和 `resolveBiz` 共用同一套取密钥。没有 `api-keys` 时，非空覆盖替换单把 `api-key`。配置了密钥池时，调用和探测都走池，页面上的单把覆盖不参与，并提示「已配置密钥池，此处密钥不参与调用」 | `apiKeyFor` 在池非空时不读单 key。探测若单独优先覆盖，会和真实调用不是同一把密钥 |
| 能力参数范围 | 本次可保存的是推理开关、上下文窗口、max-tokens、思考格式。`input` 默认 `text`。思考档映射、采样、headers、extra-body 用模型对象的现有默认值，页面不编辑 | 用户点名的是 `/models` 带不回、不补就不能选的那几项。其余字段不在本次范围 |
| 摘要模型 | 上下文压缩若单独配置了 `summary-provider` 与 `summary-model`，仍走 `LlmCall.explicit`，也就是仍读 yml。demo 不配这两项 | 「不改上下文治理」已排除改这条路径 |
| 验证命令 | `mvn -pl` 带上 `-am`。父 pom 的 `<modules>` 和 `dependencyManagement` 放在 T3，不放到 T8。admin 的测试依赖对齐 prompt-jdbc，并像 demo 一样跳过 japicmp | 不带 `-am` 时反应器找不到父 pom。未发布模块没有 0.1.7 基线，`contract-check` 会红 |
| demo 建表 | `schema.sql` 写入三段 DDL 的内容，不引用 jar 内脚本名。同时写 `spring.datasource.initialization-mode: always` 与 `spring.sql.init.mode: always`。`AiLiteDemoRunner` 改为 `LlmCall.biz` | Boot 只执行 classpath 上的 `schema.sql`。2.7 已废弃 2.1 的初始化键。现有 Runner 调 `LlmCall.profile("demo-chat", "demo")`，删掉 profile 后会报未知配置档 |

连通探测留在供应商页，不单独扩成定时探测。

### agent 实现裁决

| 事项 | 选择与理由 | 依据 |
| --- | --- | --- |
| 模块边界 | 新建 `ai-lite-admin`。目录读写和页面都在这个模块。starter 不依赖它。demo 显式引入 | 现有 jdbc 能力都是独立模块；demo 目前没有 Web 服务器，不能让所有使用者被传递 `spring-web` |
| 目录何时生效 | 类路径上有本模块、容器里有 `JdbcTemplate`、三张目录表可访问时，注册 `RuntimeCatalog`。此时 yml 的 `models` 和 `profiles` 不参与业务解析。没有这个 Bean 时，解析与现在完全一致 | 单元测试直接构造 `ModelResolver`，不能因为新功能失效。模块在位却没有数据源时不注册目录，并打一条 warn，避免静默退回却让使用者以为已经在用数据库 |
| 业务调用入口 | 新增 `LlmCall.biz(bizId, messages)`。profile 和 provider/model 都空。`LlmGateway.resolve` 在这种调用上只读目录绑定 | 现有工厂方法都带着 profile 或显式模型，没有「只传业务 id」的入口 |
| 显式调用 | 目录存在时，`explicit` / `profile` 仍按原方法解析 yml。业务路径不走它们 | 用户要求的是业务不再依赖 yml 组合，没有要求拆掉已发布的显式入口 |
| 提示词目录 | 目录模式下，`PromptCatalog` 由 `biz-ids` 生成，key 等于业务 id，默认模板为空。不再读 `ai-lite.prompt.keys`。没有目录 Bean 时仍读 `prompt.keys` | 复用已有草稿/发布/审计表，避免再建一套提示词表。默认模板为空时，现有 `render` 会因为没有系统模板而失败；本次把这句文案改成「提示词未发布」 |
| 缓存 | 目录读取缓存默认 5 秒，配置键 `ai-lite.catalog.cache-seconds`。管理页在本机保存后清掉本机缓存。提示词仍用现有 `ai-lite.prompt.cache-seconds`，发布已经会 `invalidate` | 与现有提示词缓存同一模式；5 秒用于别的实例，本机保存不等待 |
| 表名 | 默认 `AI_LITE_PROVIDER_OVERLAY`、`AI_LITE_MODEL`、`AI_LITE_BIZ_BINDING`，可用 `ai-lite.catalog.*-table` 覆盖。只允许字母、数字、下划线 | 调用记录表名仍走已有的 `ai-lite.store.jdbc.table-name`，本次不改那个约定 |
| 未就绪 | 同步或手工新增的模型 `READY=0`。保存能力参数后置 `READY=1`。业务绑定只接受 `READY=1` | 布尔默认值会让刚同步的模型看起来像已经能用，与「补完才能选」冲突 |
| 页面形态 | 一个 HTML，三个页签，普通脚本请求 JSON。路径 `/ai-lite/admin` 与 `/ai-lite/admin/api/**` | 对齐 AI Hub 的三个页签，避免构建工具链 |
| demo 数据源 | demo 使用 SQLite 文件 `ai-lite-demo/data/ai-lite-demo.db`。自动测试在各模块 `target/sqlite` 里单独建库，不和这份调试库混用。库文件不入库 | 用户要求测试用 SQLite，并把网新 `application-my.yml` 的密钥抄到已忽略的 `application-local.yml` |

## 方案设计

### 配置

目录模式下，业务侧最小配置是：

```yaml
ai-lite:
  admin:
    enabled: false          # 库默认；demo 的 application.yml 写 true
  catalog:
    cache-seconds: 5
    provider-table: AI_LITE_PROVIDER_OVERLAY
    model-table: AI_LITE_MODEL
    biz-table: AI_LITE_BIZ_BINDING
  llm:
    biz-ids: [demo-chat]    # 新业务只加这里
    providers:
      demo:
        api: openai-completions
        base-url: http://127.0.0.1:8000/v1
        api-key: ${AI_LITE_DEMO_API_KEY:demo}
        # 目录模式下不再写 models
  prompt:
    enabled: true
    # 目录模式下不再读 keys
  store:
    jdbc:
      table-name: AI_LITE_LLM_CALL
```

`ai-lite.llm.profiles` 与 `providers.*.models` 仍可以出现在没有 `RuntimeCatalog` 的应用里。目录模式下启动校验放过「供应商没有模型」；若 yml 仍写了 profile，仍按现在的规则校验它是否指向 yml 里的模型，供 `LlmCall.profile` 继续使用。

### 数据表

SQL 放在 `ai-lite-admin/src/main/resources/ai-lite-catalog.sql`。类型写法对齐 `ai-lite-prompt.sql`（`VARCHAR` / `TIMESTAMP`），注释说明按实际数据库调整 CLOB 等类型。提示词继续用 `ai-lite-prompt.sql`，调用日志继续用 `ai-lite-llm-call.sql`。

`AI_LITE_PROVIDER_OVERLAY`

| 列 | 约束 | 含义 |
| --- | --- | --- |
| PROVIDER_ID | 主键，VARCHAR(64) | 必须已经出现在 yml 的 providers 里 |
| BASE_URL | VARCHAR(500)，可空 | 空表示沿用 yml |
| API_KEY | VARCHAR(500)，可空 | 空表示沿用 yml；接口不回明文 |
| CONNECT_TIMEOUT_MS | INT，可空 | 空表示沿用 yml |
| READ_TIMEOUT_MS | INT，可空 | 空表示沿用 yml |
| UPDATE_TIME | TIMESTAMP | 最后写入时间 |

`AI_LITE_MODEL`

| 列 | 约束 | 含义 |
| --- | --- | --- |
| MODEL_ROW_ID | 主键，VARCHAR(64) | 应用生成的 id |
| PROVIDER_ID | VARCHAR(64)，非空 | 供应商 id |
| MODEL_ID | VARCHAR(128)，非空 | 供应商侧模型名 |
| READY | INT，非空 | 0 未就绪，1 可被业务选中 |
| REASONING | INT，可空 | 补齐后才有意义 |
| INPUT_MODES | VARCHAR(64)，可空 | 逗号分隔，默认补齐成 `text` |
| CONTEXT_WINDOW | INT，可空 | 对齐现有字段；0 表示未知 |
| MAX_TOKENS | INT，可空 | 输出上限 |
| THINKING_FORMAT | VARCHAR(64)，可空 | 与现有 `thinking-format` 取值相同 |
| DISPLAY_NAME | VARCHAR(128)，可空 | 页面显示名，默认等于 MODEL_ID |
| UPDATE_TIME | TIMESTAMP | 最后写入时间 |

同一供应商下同一 `MODEL_ID` 只允许一行。插入前查询；已存在则同步计入「已存在、未改动」，手工新增则返回「模型已存在」。不加 UNIQUE 索引。两个管理端同时插入同一对键时可能出现两行，解析时取更新时间较新的一行，并在列表接口里把重复带出来。这是不建唯一索引的代价，记在风险里。

`AI_LITE_BIZ_BINDING`

| 列 | 约束 | 含义 |
| --- | --- | --- |
| BIZ_ID | 主键，VARCHAR(64) | 等于 yml 白名单中的业务 id，且必须能通过提示词编码校验 |
| PROVIDER_ID | VARCHAR(64)，非空 | 已声明的供应商 |
| MODEL_ID | VARCHAR(128)，非空 | 必须是该供应商下 `READY=1` 的模型 |
| THINKING_LEVEL | VARCHAR(32)，非空 | 与现有思考档取值相同，默认 `off` |
| UPDATE_TIME | TIMESTAMP | 最后写入时间 |

提示词不新建表。`PROMPT_KEY` 写业务 id。已有的 `UK_AI_LITE_PROMPT_VERSION (PROMPT_KEY, VERSION_NO)` 保持不动，本次不新增唯一索引。

### 核心接口

放在 `ai-lite-core`，使核心不依赖 JDBC：

```text
RuntimeCatalog
  ProviderOverlay findProvider(String providerId)   // 没有覆盖时返回 null
  CatalogModel findModel(String providerId, String modelId)
  BizBinding findBinding(String bizId)
  void invalidate()
```

`NoopRuntimeCatalog` 三个查询都返回 null。`ModelResolver` 增加一个接受 `RuntimeCatalog` 的构造；传入 null 或 Noop 时走现有逻辑。传入真实目录时：

- 启动校验允许供应商的 models 为空。
- 新增 `ResolvedModel resolveBiz(String bizId)`：读绑定；没有绑定抛「业务未配置模型: {bizId}」；模型不存在或 `READY!=1` 抛「模型尚未补齐能力参数: {modelId}」。端点用覆盖的非空字段，其余用 yml。能力参数用数据库行，不读 yml 的 models。
- `LlmGateway.resolve`：调用上 profile 非空则 `resolveProfile`；provider 或 model 非空则 `resolveExplicit`；两者都空且目录不是 Noop 则 `resolveBiz`；目录是 Noop 则保持现在的 `default-profile` 回退。

覆盖密钥只替换单把 `api-key`。yml 里的 `api-keys` 池仍按现有轮换，本次管理页不编辑密钥池。

### 管理接口

只在 `ai-lite.admin.enabled=true` 且类路径存在 `DispatcherServlet` 时注册。统一 JSON：成功 `{ "ok": true, "data": ... }`，失败 `{ "ok": false, "message": "..." }`，HTTP 仍用 200 以外的 400 表示参数错误。密钥不进 message。

| 方法与路径 | 行为 |
| --- | --- |
| GET `/ai-lite/admin` | 返回页面 |
| GET `/ai-lite/admin/api/providers` | yml 供应商 + 覆盖后的生效值；密钥脱敏 |
| POST `/ai-lite/admin/api/providers/{name}/overlay` | 名字不在 yml 则 400。字段缺省或空串表示不改该字段。写库后清本机缓存 |
| POST `/ai-lite/admin/api/providers/{name}/overlay/clear` | 删掉该供应商的覆盖行 |
| POST `/ai-lite/admin/api/providers/{name}/connectivity` | GET `{baseUrl}/models`，返回 url、httpStatus、latencyMs、ok、message。不写调用日志，不改模型表 |
| POST `/ai-lite/admin/api/providers/{name}/models/sync` | 解析 OpenAI 兼容响应的 `data[].id`。返回新增数、已存在数。`anthropic-messages` 返回 400，文案说明请手工添加 |
| POST `/ai-lite/admin/api/providers/{name}/models` | 手工添加一个模型 id，`READY=0` |
| PUT `/ai-lite/admin/api/providers/{name}/models/{modelId}` | 保存能力参数并置 `READY=1`。缺少推理开关、上下文窗口、max-tokens、思考格式之一则 400 |
| GET `/ai-lite/admin/api/biz` | 白名单中的每个 id，附带当前绑定（可以为空）和是否已有生效提示词 |
| PUT `/ai-lite/admin/api/biz/{bizId}` | 保存绑定。id 不在白名单、供应商未声明、模型未就绪则 400 |
| GET `/ai-lite/admin/api/biz/{bizId}/prompt` | 当前生效版本与最近草稿。没有则字段为空 |
| POST `/ai-lite/admin/api/biz/{bizId}/prompt/draft` | 走现有 `PromptManager` 创建草稿，key = bizId |
| POST `/ai-lite/admin/api/biz/{bizId}/prompt/publish` | 发布指定版本，并使本机提示词缓存失效 |
| GET `/ai-lite/admin/api/calls` | 查询参数 `bizId`、`provider`、`limit`（默认 50，上限 200）。按 `CREATE_TIME` 倒序。未配置调用记录表名时返回明确错误，不要空列表假装成没有记录 |

连通与同步使用当前生效的 Base URL 和 Api Key（覆盖优先）。读超时上限 15 秒，与网新探测一致。HTTP 客户端用 JDK 8 的 `HttpURLConnection`，避免管理模块再绑一套 OkHttp。

### 页面

`ai-lite-admin/src/main/resources/ai-lite-admin/index.html` 加同目录的 `admin.js`、`admin.css`。控制器在开关打开时把这三个文件映射到 `/ai-lite/admin` 与 `/ai-lite/admin/assets/*`。不放进 Spring Boot 默认静态目录，避免开关关闭时仍能被直接访问。

三个页签：供应商、业务与提示词、调用日志。文案用中文。供应商页说明「这里的修改写入数据库，不改配置文件」。密钥输入框留空表示不改。模型表标出未就绪。业务页每个白名单 id 一行：选模型、编辑系统模板与用户模板、保存草稿、发布。

## 流程

### 主路径

1. 应用启动。yml 声明供应商和业务 id。目录模块检查三张表，注册 `RuntimeCatalog`。提示词目录改为这些业务 id。
2. 管理员打开 `/ai-lite/admin`，在供应商页确认地址和密钥，点测试连通，再点同步模型。新 id 以未就绪写入 `AI_LITE_MODEL`。
3. 管理员补齐能力参数并保存，该行 `READY=1`。
4. 在业务页选这个模型，写提示词草稿并发布。发布写入已有提示词表，本机缓存失效。
5. 业务代码调用 `LlmCall.biz(bizId, messages)`。网关检查白名单，再按绑定解析端点和模型能力，发出请求。提示词由 `promptManager.render(bizId, vars)` 取已发布版本。
6. 调用结束后，摘要写入 `AI_LITE_LLM_CALL`。管理页的日志页签按时间倒序读出。

### 意外与边界

| 场景 | 系统行为 | 数据/状态处理 | 验证方式 |
| --- | --- | --- | --- |
| 开关为 false | 不注册页面和管理接口 | 不写库 | 装配测试：上下文没有对应映射 |
| 业务 id 不在白名单 | 发请求前拒绝，文案「业务 id 未登记」 | 不读绑定表 | 网关测试 |
| 白名单内但没有绑定 | `resolveBiz` 失败，文案「业务未配置模型」 | 不回退 default-profile | 网关测试 |
| 模型 `READY=0` 被选为绑定 | 保存接口 400 | 不写绑定行 | 接口测试 |
| 没有已发布提示词 | `render` 抛「提示词未发布」 | 不使用空的默认模板 | 提示词测试 |
| `/models` 里已有的 id | 同步计入已存在，不改 READY 和能力参数 | 原行保持 | 同步测试，先把一行置为就绪再同步 |
| `/models` 不再返回某 id | 不删除 | 库内行保留 | 同步测试 |
| 协议是 anthropic-messages | 同步接口 400 | 不发 HTTP | 接口测试 |
| 覆盖保存时密钥为空 | 不改数据库里的密钥 | 其余非空字段更新 | 接口测试 |
| 未知供应商名 | 400，「未知供应商，须先在配置文件声明」 | 不插入 | 接口测试 |
| 目录表不存在 | 启动失败，错误指向 `ai-lite-catalog.sql` | 不注册半截目录 | 装配测试：有 JdbcTemplate、无表 |
| 模块在位但没有 JdbcTemplate | 不注册目录，打 warn | 解析回到 yml 路径 | 装配测试 |
| 连通失败 | 返回 ok=false 与摘要，摘要里不出现密钥 | 不写调用日志 | 用本地假 HTTP 服务测 |

## 风险/待确认

| 类型 | 事项及影响 | 应对/推荐方案 | 决策或解除条件 |
| --- | --- | --- | --- |
| 风险 | 管理页打开后，能访问该端口的人可以改密钥和提示词。demo 会打开 | 库默认关闭。本次不加口令。调试结束把 demo 开关改回 false | 用户已选择调试期打开、不做口令。若以后端口对外，另行加口令 |
| 风险 | 不建 `(PROVIDER_ID, MODEL_ID)` 唯一索引，并发插入可能产生两行 | 写入前查询；解析取较新一行；列表标出重复 | 用户规则要求未经同意不建 UNIQUE。若要加，需要单独同意 |
| 风险 | 覆盖密钥落在数据库明文列 | 接口脱敏；SQL 注释说明该列是凭据。不写入日志 | 与网新覆盖密钥的做法相同。若要改成只许环境变量，需要改变已确认的「页面可改密钥」 |
| 风险 | 目录模式下忽略 `ai-lite.prompt.keys`。旧配置若同时引入本模块，原来的提示词 key 不再登记 | 启动时若发现配了 `prompt.keys`，打 warn，说明目录模式以业务 id 为准 | 已确认「一个业务一份提示词」的推导。不单独再问 |
| 未核实 | demo 加上 `spring-boot-starter-web` 后会占用一个 HTTP 端口。当前 demo 不占端口 | 沿用 Boot 默认 8080，不在本次改端口 | 实施时若 8080 冲突，在 demo 的 `application.yml` 写明 `server.port`，不改库默认值 |

## 前置任务与外部依赖

| 前置项 | 影响任务 | 完成条件/证据 | 当前状态 | 责任人/恢复条件 |
| --- | --- | --- | --- | --- |
| 实施授权 | 全部 | 用户明确同意按本计划改代码 | 已满足 | 2026-10-27 15:54 审查后执行；17:48 要求 T4 完成后暂停 |
| 可连通的 OpenAI 兼容 `/models` | T4 的真请求、T8 的手工点击 | 使用网新 `providers.wangxin`，`base-url` 为 `https://tq.insigma-dev.com/doapi/insigma-ddd-ops/open/access/ai/v1` | 地址已在网新 `application-my.yml` / `application-dev.yml` 核对。密钥留在网新配置里，不抄进本仓库 | 外网不通时，解析与增量规则的自动测试仍可完成，真请求记为未执行 |

## 任务分解

| 编号 | 任务与验收 | 涉及文件 | 状态 | 依赖/备注 |
| --- | --- | --- | --- | --- |
| T1 | 核心能按业务 id 解析模型。没有目录时旧测试仍通过；有目录时缺绑定、未就绪模型、覆盖端点三种情况有测试 | core 的 `RuntimeCatalog`、`LlmCall`、`ModelResolver`、`LlmGateway` 及对应测试 | done | `ModelResolverCatalogTest`、`GatewayBizCatalogTest` 通过 |
| T2 | 目录模式下提示词目录等于业务 id，未发布时文案为「提示词未发布」。无目录时 `prompt.keys` 仍生效 | `PromptManager`、提示词自动装配、测试 | done | 文案与存储失败已有测试。自动装配让出目录仍在 T3 |
| T3 | JDBC 目录：覆盖、模型、业务绑定的读写与 5 秒缓存。缺表启动失败。SQLite 测试覆盖增量规则 | `ai-lite-admin` 的存储、SQL、自动装配 | done | `JdbcRuntimeCatalogTest` 6 项、`CatalogAssemblyTest` 2 项通过。库文件在 `target/sqlite` |
| T4 | 连通与 `/models` 同步。真正请求打网新 `wangxin`，不自建假 HTTP 服务 | `ModelSyncClient` 与 `ModelSyncClientTest` | done | 3 项通过：增量同步且已就绪行不被覆盖、Anthropic 拒绝、失败摘要去掉密钥。管理接口留在 T5 |
| T5 | 开关、页面与供应商/模型接口。`enabled=false` 时没有映射；打开后 MockMvc 能改覆盖、拒绝未知供应商、保存能力参数 | 控制器、静态页、自动装配 | done | `AdminWebTest` 覆盖开关、覆盖留空不改密钥、未知供应商、连通失败不回密钥、增量同步、能力参数 |
| T6 | 业务绑定与提示词发布接口，以及页面上对应页签。未就绪模型不能绑定；发布后 `render(bizId)` 读到新模板 | 控制器与页面 | done | 未就绪绑定被拒绝；发布后 `render` 读到「你好模板」 |
| T7 | 调用日志只读查询与日志页签。缺表名时返回明确错误；结果不含提示词与密钥字段 | admin 模块查询与页面 | done | 未配置表名返回明确错误；按业务过滤，响应不含密钥和模板字段 |
| T8 | demo 能启动并打开页面；文档与 CHANGELOG 写明新配置、建表和 `LlmCall.biz`。相关模块测试通过 | demo、父 pom、文档 | done | 依赖 T1–T7。demo 使用 SQLite，不是 H2 |

### 文件改动清单

| 文件 | 操作 | 职责与改动意图 |
| --- | --- | --- |
| `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/catalog/RuntimeCatalog.java` | 新增 | 运行时目录接口：覆盖、模型、业务绑定、失效缓存 |
| `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/catalog/NoopRuntimeCatalog.java` | 新增 | 默认空实现，保持旧解析 |
| `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/catalog/CatalogModel.java` | 新增 | 目录中的模型行，含 READY 与能力参数 |
| `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/catalog/ProviderOverlay.java` | 新增 | 覆盖字段：地址、密钥、两个超时 |
| `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/catalog/BizBinding.java` | 新增 | 业务 id 到供应商、模型、思考档 |
| `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/llm/LlmCall.java` | 修改 | 增加 `biz` 工厂方法 |
| `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/config/ModelResolver.java` | 修改 | 接受目录；目录模式允许空模型列表；新增 `resolveBiz` |
| `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/llm/LlmGateway.java` | 修改 | `resolve` 在只有业务 id 时走目录 |
| `ai-lite-core/src/test/java/io/github/yinwenyuan28/ailite/config/ModelResolverCatalogTest.java` | 新增 | 缺绑定、未就绪、覆盖优先、空模型列表可启动 |
| `ai-lite-core/src/test/java/io/github/yinwenyuan28/ailite/llm/GatewayBizCatalogTest.java` | 新增 | 网关对 `LlmCall.biz` 的解析；显式 profile 仍走 yml |
| `ai-lite-prompt/src/main/java/io/github/yinwenyuan28/ailite/prompt/PromptManager.java` | 修改 | 没有生效版本且没有默认模板时，文案改为「提示词未发布」 |
| `ai-lite-spring-boot-starter/src/main/java/io/github/yinwenyuan28/ailite/autoconfigure/AiLiteAutoConfiguration.java` | 修改 | 构造 `ModelResolver` 时注入容器中的 `RuntimeCatalog`，没有则用 Noop |
| `ai-lite-spring-boot-starter/src/main/java/io/github/yinwenyuan28/ailite/autoconfigure/AiLitePromptAutoConfiguration.java` | 修改 | 已有来自业务 id 的 `PromptCatalog` 时不再用 `prompt.keys` 覆盖 |
| `ai-lite-admin/pom.xml` | 新增 | 模块描述；依赖 core、prompt、spring-jdbc、spring-webmvc；测试依赖 H2 与 spring-boot-starter-web |
| `pom.xml` | 修改 | 注册模块与 `dependencyManagement` 条目 |
| `ai-lite-admin/src/main/resources/ai-lite-catalog.sql` | 新增 | 三张目录表，只有主键 |
| `ai-lite-admin/src/main/resources/META-INF/spring.factories` | 新增 | 注册目录与管理页两个自动装配 |
| `ai-lite-admin/src/main/java/io/github/yinwenyuan28/ailite/admin/jdbc/JdbcRuntimeCatalog.java` | 新增 | 目录的 JDBC 实现与本机缓存 |
| `ai-lite-admin/src/main/java/io/github/yinwenyuan28/ailite/admin/jdbc/AiLiteCatalogAutoConfiguration.java` | 新增 | 有 `JdbcTemplate` 且表可访问时注册目录；生成以业务 id 为 key 的 `PromptCatalog` |
| `ai-lite-admin/src/main/java/io/github/yinwenyuan28/ailite/admin/web/AiLiteAdminAutoConfiguration.java` | 新增 | `admin.enabled=true` 且存在 `DispatcherServlet` 时注册控制器 |
| `ai-lite-admin/src/main/java/io/github/yinwenyuan28/ailite/admin/web/AdminController.java` | 新增 | 页面与本文接口表 |
| `ai-lite-admin/src/main/java/io/github/yinwenyuan28/ailite/admin/sync/ModelSyncClient.java` | 新增 | `HttpURLConnection` 调用 `/models`，解析 id 列表，错误摘要去掉密钥 |
| `ai-lite-admin/src/main/java/io/github/yinwenyuan28/ailite/admin/call/CallLogQuery.java` | 新增 | 按表名只读查询调用记录 |
| `ai-lite-admin/src/main/resources/ai-lite-admin/index.html` | 新增 | 三个页签的页面 |
| `ai-lite-admin/src/main/resources/ai-lite-admin/admin.js` | 新增 | 调接口并渲染三个页签 |
| `ai-lite-admin/src/main/resources/ai-lite-admin/admin.css` | 新增 | 页面布局 |
| `ai-lite-admin/src/test/java/io/github/yinwenyuan28/ailite/admin/jdbc/JdbcRuntimeCatalogTest.java` | 新增 | H2：覆盖、增量同步、重复拒绝、缓存失效 |
| `ai-lite-admin/src/test/java/io/github/yinwenyuan28/ailite/admin/web/AdminWebTest.java` | 新增 | MockMvc：开关、未知供应商、绑定校验、日志查询 |
| `ai-lite-admin/src/test/java/io/github/yinwenyuan28/ailite/admin/sync/ModelSyncClientTest.java` | 新增 | 本地假服务上的同步与密钥脱敏 |
| `ai-lite-demo/pom.xml` | 修改 | 加 web、jdbc、H2、admin、prompt-jdbc、store-jdbc |
| `ai-lite-demo/src/main/resources/application.yml` | 修改 | 打开管理页；供应商去掉写死的 models；去掉业务所依赖的 profiles；补目录表名与调用记录表名 |
| `ai-lite-demo/src/main/resources/schema.sql` | 新增 | 执行三份建表脚本，供 H2 启动初始化 |
| `ai-lite-demo/src/main/resources/application.yml` 中的数据源 | 修改 | H2 内存，`spring.datasource.initialization-mode=always`（Boot 2.1 的键名） |
| `docs/ref-配置.md` | 修改 | 写明目录模式下配置文件还留什么，以及 admin / catalog 键 |
| `docs/guide-上层调用API.md` | 修改 | 写明 `LlmCall.biz` 与 `render(bizId)` |
| `README.md` | 修改 | 配置片段改成目录模式的最小例子，并保留一句旧路径仍可用 |
| `CHANGELOG.md` | 修改 | 记录本次行为 |
| `ai-lite-demo/README.md` | 修改 | 写明管理页地址、开关和 H2 |

清单是预估。实施时为落实上述行为补上的测试或装配类，更新本表后继续，不另行确认。

## 实现步骤

1. T1：先写失败测试，再让 `LlmCall.biz` 在有目录时解析绑定，无目录时旧测试不变。产出：核心解析行为可单独验证。
2. T2：改提示词缺省文案，并让自动装配在已有业务 id 目录时让出 `PromptCatalog`。产出：未发布必失败，旧的 `prompt.keys` 路径仍通过。
3. T3：建模块、SQL 和 JDBC 目录，用 H2 验证读写与缺表失败。产出：没有页面也能热读目录。
4. T4：接上 `/models` 同步与连通探测。解析和失败路径用本地样例；真正发请求时打网新 `wangxin` 供应商，不自建假服务。产出：增量同步规则可重复跑。
5. T5–T7：按供应商、业务与提示词、调用日志的顺序接接口和页签。每一步都先有 MockMvc 或 H2 测试。产出：开关打开时三个页签可用，关闭时映射不存在。
6. T8：把 demo 接上端口和 H2，更新文档。跑本文验证表里的命令。手工打开页面的那一步在有浏览器时做，没有则记明未执行。

## 验证计划与结果

| 验收项 | 方法/命令 | 通过标准 | 实际结果与证据 | 未执行原因/剩余事项 |
| --- | --- | --- | --- | --- |
| 核心解析与提示词文案 | `mvn -pl ai-lite-core test "-Dtest=ModelResolverCatalogTest,GatewayBizCatalogTest"`；`mvn -pl ai-lite-prompt -am test "-Dtest=PromptManagerTest" -DfailIfNoTests=false` | 目录解析、缺绑定、未发布文案通过，旧文案测试仍在 | 2026-10-27 通过 | 全量 `ai-lite-core` 回归尚未单独再跑 |
| 目录 JDBC 与装配 | `mvn -pl ai-lite-admin,ai-lite-spring-boot-starter -am test "-Dtest=JdbcRuntimeCatalogTest,CatalogAssemblyTest" -DfailIfNoTests=false` | 覆盖、增量不覆盖、缓存、缺表、业务 id 接入解析器 | 2026-10-27 通过：6 + 2 | 使用 SQLite 文件，不是 H2 |
| `/models` 同步 | 同一命令中的 `ModelSyncClientTest` | 打网新 `wangxin`；第二次同步不改已就绪行；Anthropic 不发请求；摘要不含密钥 | 2026-10-27 通过：3 项，约 1.1 秒 | 非 2xx 没有单独用假服务再测。页面上的同步按钮属于 T5 |
| demo 能装配 | `mvn -pl ai-lite-demo -am package -DskipTests` | 构建成功，且 demo 打开管理页并指向 SQLite | 2026-10-27 构建成功。`java -jar` 加 `--server.port=18080` 后页面返回 200，数据源是 `data/ai-lite-demo.db` | 验证进程已停。默认端口仍是 8080 |
| 页面三个页签 | 启动 demo 后打开 `/ai-lite/admin` | 三个页签能完成同步、绑定、发布、看日志 | 供应商页已渲染，密钥脱敏。业务和日志接口返回成功。同步、绑定、发布由 `AdminWebTest` 覆盖 | 自动化浏览器没有把按钮点击送到页面脚本，另外两个页签没有在浏览器里点开 |

## 观察项

无。本次没有需要跨天观察的运行指标。调试结束后把 demo 的开关改回关闭，那一步不在本计划的完成条件里，由使用者在调试结束时提出。

## 实施记录

- 2026-10-27：按已对齐的规格写成草稿。尚未改代码。
- 2026-10-27：三份审查结论都是先修计划。[正确性](b0f8b059-7164-46e4-8ec0-07c6075a4c5d)、[范围](e966919d-5abe-402b-9f39-08f5e2c490ba)、[交付](cc2ebf70-8165-4763-b13b-bdcb801221d7)。修订已写入「审查修订」，未改已确认的增量同步和默认关闭。
- 2026-10-27：T1、T2 完成。`ModelResolverCatalogTest`、`GatewayBizCatalogTest`、`PromptManagerTest` 通过。全量模块测试尚未跑。
- 2026-10-27：T3 完成。目录 JDBC、SQLite 测试库、`RuntimeCatalog` 接到 `ModelResolver`。`JdbcRuntimeCatalogTest` 6 项、`CatalogAssemblyTest` 2 项通过。
- 2026-10-27：按用户要求，调试库改为 `ai-lite-demo/data/ai-lite-demo.db`，密钥写入已忽略的 `application-local.yml`。自动测试库在 `target/sqlite`。
- 2026-10-27：T4 完成。`ModelSyncClient` 对网新 `wangxin` 做增量同步，`ModelSyncClientTest` 3 项通过。用户要求此处暂停，T5 管理页未开始。
- 2026-10-27：T5–T8 完成。管理页在 `ai-lite.admin.enabled=true` 且为 Servlet 应用时注册。`AdminWebTest` 8 项通过。`ai-lite-admin` 全量 17 项通过，连带 `ai-lite-core` 628 项、`ai-lite-prompt` 32 项通过。demo 在 18080 端口打开了供应商页，密钥只显示脱敏值。

## 完成结论

完成。管理页默认关闭；demo 为了调试保持打开。模型、提示词和业务绑定在 SQLite 里热更新，同步是增量的。调试结束后把 `ai-lite-demo` 的 `ai-lite.admin.enabled` 改回 `false`。
