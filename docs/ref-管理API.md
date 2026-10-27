# 管理 API

自带页面不是必须依赖。引入 `ai-lite-admin-api` 并把 `ai-lite.admin.enabled` 设为 `true` 后，下面这些接口就在应用自己的端口上。若还要自带控制台，再引入 `ai-lite-admin-ui` 并设 `ai-lite.admin.page-enabled: true`，地址是 `GET /ai-lite/admin`。

接口没有口令。不要把打开了管理接口的端口暴露到公网。

统一响应：成功 `{ "ok": true, "data": ... }`，失败 `{ "ok": false, "message": "..." }`，参数错误是 HTTP 400。响应里没有密钥明文。

| 方法与路径 | 作用 |
| --- | --- |
| GET `/ai-lite/admin/api/providers` | 配置文件里的供应商、生效地址、脱敏密钥、本地模型；`overlay=true` 表示正在用库内覆盖 |
| POST `/ai-lite/admin/api/providers/{name}/overlay` | 写覆盖。空字段表示不改。不改配置文件 |
| POST `/ai-lite/admin/api/providers/{name}/overlay/clear` | 删掉该供应商的覆盖 |
| POST `/ai-lite/admin/api/providers/{name}/connectivity` | 探测 `GET {baseUrl}/models`，不写库 |
| POST `/ai-lite/admin/api/providers/{name}/models/sync` | 只返回远端模型 id，以及是否已在本地。不插入、不覆盖、不删除 |
| POST `/ai-lite/admin/api/providers/{name}/models/adopt` | 正文 `{ "modelIds": ["..."] }`。只把勾选的新 id 插入为未就绪 |
| POST `/ai-lite/admin/api/providers/{name}/models` | 手工添加一个未就绪模型，正文 `{ "modelId": "..." }` |
| PUT `/ai-lite/admin/api/providers/{name}/models/{modelId}` | 保存能力参数并标为可用。需要推理开关、上下文窗口、输出上限、思考格式 |
| DELETE `/ai-lite/admin/api/providers/{name}/models/{modelId}` | 用户删除。仍被业务绑定时拒绝 |
| POST `/ai-lite/admin/api/providers/{name}/models/{modelId}/try` | 实测调用。正文可含 `thinkingLevel`（默认 `off`）。`max_tokens` 用模型配置的输出上限；响应含脱敏后的完整 HTTP 请求与原始响应。Anthropic / Responses 协议尚未接入 |
| GET `/ai-lite/admin/api/thinking-formats` | 思考格式的 id、中文名和说明 |
| GET `/ai-lite/admin/api/biz` | 白名单业务、当前绑定、是否已有生效提示词 |
| PUT `/ai-lite/admin/api/biz/{bizId}` | 保存绑定。模型必须已就绪 |
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

`none` 不记模型输入输出。`failure` 只记失败调用。`all` 每次都记。正文落在 `AI_LITE_LLM_CALL_BODY`，不含密钥和图片原文。档位存在 `AI_LITE_ADMIN_SETTING`。

思考格式不是自由文本，只能用 `GET /thinking-formats` 返回的 id。它决定请求体里怎么写思考开关。测试调用的 `thinkingLevel` 决定是否打开思考以及 `reasoning_effort` 等字段的值（`off` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max`）。
