# 管理 API

自带页面不是必须依赖。引入 `ai-lite-admin-api` 并把 `ai-lite.admin.enabled` 设为 `true` 后，下面这些接口就在应用自己的端口上。若还要自带控制台，再引入 `ai-lite-admin-ui` 并设 `ai-lite.admin.page-enabled: true`，地址是 `GET /ai-lite/admin`。

自己写管理服务时，注入目录请用 `RuntimeCatalog`，需要写操作再转 `JdbcRuntimeCatalog`；不要按具体类型注入。说明见 [配置参考 §2.4](ref-配置.md)。

接口没有口令。不要把打开了管理接口的端口暴露到公网。

统一响应：成功 `{ "ok": true, "data": ... }`，失败 `{ "ok": false, "message": "..." }`，参数错误是 HTTP 400。响应里没有密钥明文。

| 方法与路径 | 作用 |
| --- | --- |
| GET `/ai-lite/admin/api/providers` | 配置文件里的供应商、生效地址、脱敏密钥、本地模型；`overlay=true` 表示正在用库内覆盖；`apiOverridden=true` 表示协议被覆盖，`configApi` 是配置文件原值 |
| POST `/ai-lite/admin/api/providers/{name}/overlay` | 写覆盖。空字段表示不改。`api` 传空串表示撤销协议覆盖、回到配置文件；传未知协议返回 400。不改配置文件 |
| POST `/ai-lite/admin/api/providers/{name}/overlay/clear` | 删掉该供应商的覆盖 |
| GET `/ai-lite/admin/api/apis` | 可选的供应商协议，以及各自在 `base-url` 之后追加的路径（`/chat/completions`、`/responses`、`/v1/messages`） |
| POST `/ai-lite/admin/api/providers/{name}/connectivity` | 探测 `GET {baseUrl}/models`，不写库 |
| POST `/ai-lite/admin/api/providers/{name}/models/sync` | 只返回远端模型 id，以及是否已在本地。不插入、不覆盖、不删除 |
| POST `/ai-lite/admin/api/providers/{name}/models/adopt` | 正文 `{ "modelIds": ["..."] }`。只把勾选的新 id 插入为未就绪 |
| POST `/ai-lite/admin/api/providers/{name}/models` | 手工添加一个未就绪模型，正文 `{ "modelId": "..." }` |
| PUT `/ai-lite/admin/api/providers/{name}/models/{modelId}` | 保存能力参数，**不改就绪状态**。需要推理开关、上下文窗口、输出上限、思考格式；可选 `inputModes`（`["text","image"]` 这类数组，决定模型能否接收图片）和 `displayName`（只用于展示，空串表示回落到模型 id）。不传 `inputModes` 按 `["text"]` 写，不传 `displayName` 保留原值 |
| PUT `/ai-lite/admin/api/providers/{name}/models/{modelId}/ready` | 启用或停用模型，正文 `{ "ready": true \| false }`。就绪只由这个接口改变 |
| DELETE `/ai-lite/admin/api/providers/{name}/models/{modelId}` | 用户删除。仍被业务绑定时拒绝 |
| POST `/ai-lite/admin/api/providers/{name}/models/{modelId}/try` | 实测调用。正文可含 `thinkingLevel`（默认 `off`）。`max_tokens` 用模型配置的输出上限；响应含脱敏后的完整 HTTP 请求与原始响应。Anthropic / Responses 协议尚未接入 |
| GET `/ai-lite/admin/api/thinking-formats` | 思考格式的 id、中文名和说明 |
| GET `/ai-lite/admin/api/biz` | 白名单业务、当前绑定、是否已有生效提示词 |
| PUT `/ai-lite/admin/api/biz/{bizId}` | 保存绑定。模型必须已就绪（`ready=true`） |
| DELETE `/ai-lite/admin/api/biz/{bizId}/binding` | 清除模型绑定；业务 id 仍在白名单 |
| GET `/ai-lite/admin/api/biz/{bizId}/prompt` | 生效版本、最近草稿、`versions`、`audits` |
| GET `/ai-lite/admin/api/biz/{bizId}/prompt/versions` | 仅返回版本记录列表 |
| GET `/ai-lite/admin/api/biz/{bizId}/prompt/audits` | 仅返回操作审计 |
| POST `/ai-lite/admin/api/biz/{bizId}/prompt/draft` | 无 `versionId` 时新建草稿；有 `versionId` 时改已有草稿。可含 `systemTemplate`、`userTemplate`、`outputSchema`、`message` |
| POST `/ai-lite/admin/api/biz/{bizId}/prompt/preview` | 本地预览。正文 `systemTemplate`、`userTemplate`、`vars`，不改库 |
| POST `/ai-lite/admin/api/biz/{bizId}/prompt/publish` | 发布指定草稿 `versionId` |
| POST `/ai-lite/admin/api/biz/{bizId}/prompt/rollback` | 把已发布/已退役版本重新置为生效，正文 `{ "versionId": "..." }` |
| GET `/ai-lite/admin/api/calls` | 分页摘要。查询参数 `bizId`、`provider`、`modelId`、`success`、`limit`（默认 50，最大 200）、`offset`（默认 0）。`data` 为 `{ items, total, limit, offset }` |
| GET `/ai-lite/admin/api/calls/{callId}` | 单条调用摘要；开启正文记录时附带输入输出 |
| GET `/ai-lite/admin/api/calls/body-level` | 当前输入输出记录档位 |
| PUT `/ai-lite/admin/api/calls/body-level` | 正文 `{ "level": "none" \| "failure" \| "all" }` |

`none` 不记模型输入输出。`failure` 只记失败调用。`all` 每次都记。正文落在正文表（默认 `AI_LITE_LLM_CALL_BODY`；把 `ai-lite.store.jdbc.table-name` 改成 `MY_CALL` 后自动跟随为 `MY_CALL_BODY`，也可用 `ai-lite.store.jdbc.body-table-name` 显式指定），不含密钥和图片原文。档位存在 `AI_LITE_ADMIN_SETTING`。

**连通探测与测试调用走配置启用的 HTTP 传输**（`ai-lite.http.transport`），与真实模型调用同一连接池、同一 TLS 配置、同一超时。因此管理台说"通"就意味着业务侧也能通——这是刻意为之：早期版本用独立的 `HttpURLConnection`，曾出现"管理台调用成功、业务调用全挂"的错位（网关走 Jetty 但没配 TLS 时尤其典型）。只有单独引入 `ai-lite-admin-api`、容器中没有 `HttpExecutor` 时才会退化为 `HttpURLConnection`，并在启动日志里打 WARN。连通探测固定 `GET {baseUrl}/models`；测试调用固定 `POST {baseUrl}/chat/completions`。

思考格式不是自由文本，只能用 `GET /thinking-formats` 返回的 id。它决定请求体里怎么写思考开关。测试调用的 `thinkingLevel` 决定是否打开思考以及 `reasoning_effort` 等字段的值（`off` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max`）。

**「就绪」是一个独立的人工开关。** 从远端同步或手工添加的模型初始为未就绪（`READY=0`）。
保存能力参数**不会**自动把它变就绪——两者是不同的事：参数可能只是照抄了默认值还没验证，
或者供应商那边还没开通。启用与否由 `PUT .../models/{modelId}/ready` 单独决定，可以随时停用。

未就绪的模型不能被业务绑定（`PUT /biz/{bizId}` 会拒绝），调用时 `ModelResolver` 也会再检查一次。
停用一个已绑定的模型**不会解除绑定**：绑定保留，但下一次调用在解析阶段失败。这是刻意的——
静默解绑会让业务在无人察觉的情况下失去模型。

**入参类型决定模型能不能收图片。** `inputModes` 存进 `AI_LITE_MODEL.INPUT_MODES`，由 `ModelResolver` 转成
`ResolvedModel.acceptsImage`：只有其中包含 `image`，调用方才被允许发送图片消息。早先这一列写死成 `text`，
所以支持图片的模型在管理页上怎么配都不会被当成多模态。可选值为 `text`、`image`、`audio`、`video`、`file`，
写错会被 400 拒绝（`未知入参类型`）。

**显示名只影响展示。** `displayName` 写进 `DISPLAY_NAME`，不参与模型请求；留空时回落到模型 id。

