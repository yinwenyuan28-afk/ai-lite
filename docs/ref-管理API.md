# 管理 API

自带页面不是必须依赖。引入 `ai-lite-admin` 并把 `ai-lite.admin.enabled` 设为 `true` 后，下面这些接口就在应用自己的端口上。自带页面还要再设 `ai-lite.admin.page-enabled: true`，地址是 `GET /ai-lite/admin`。

接口没有口令。不要把打开了管理接口的端口暴露到公网。

统一响应：成功 `{ "ok": true, "data": ... }`，失败 `{ "ok": false, "message": "..." }`，参数错误是 HTTP 400。响应里没有密钥明文。

| 方法与路径 | 作用 |
| --- | --- |
| GET `/ai-lite/admin/api/providers` | 配置文件里的供应商、生效地址、脱敏密钥、本地模型 |
| POST `/ai-lite/admin/api/providers/{name}/overlay` | 写覆盖。空字段表示不改。不改配置文件 |
| POST `/ai-lite/admin/api/providers/{name}/overlay/clear` | 删掉该供应商的覆盖 |
| POST `/ai-lite/admin/api/providers/{name}/connectivity` | 探测 `GET {baseUrl}/models`，不写库 |
| POST `/ai-lite/admin/api/providers/{name}/models/sync` | 只返回远端模型 id，以及是否已在本地。不插入、不覆盖、不删除 |
| POST `/ai-lite/admin/api/providers/{name}/models/adopt` | 正文 `{ "modelIds": ["..."] }`。只把勾选的新 id 插入为未就绪 |
| POST `/ai-lite/admin/api/providers/{name}/models` | 手工添加一个未就绪模型，正文 `{ "modelId": "..." }` |
| PUT `/ai-lite/admin/api/providers/{name}/models/{modelId}` | 保存能力参数并标为可用。需要推理开关、上下文窗口、输出上限、思考格式 |
| DELETE `/ai-lite/admin/api/providers/{name}/models/{modelId}` | 用户删除。仍被业务绑定时拒绝 |
| GET `/ai-lite/admin/api/thinking-formats` | 思考格式的 id、中文名和说明 |
| GET `/ai-lite/admin/api/biz` | 白名单业务、当前绑定、是否已有生效提示词 |
| PUT `/ai-lite/admin/api/biz/{bizId}` | 保存绑定。模型必须已就绪 |
| GET `/ai-lite/admin/api/biz/{bizId}/prompt` | 生效版本、最近草稿，以及版本记录（`versions`，按版本号倒序） |
| GET `/ai-lite/admin/api/biz/{bizId}/prompt/versions` | 仅返回版本记录列表 |
| POST `/ai-lite/admin/api/biz/{bizId}/prompt/draft` | 创建草稿，key 就是业务 id |
| POST `/ai-lite/admin/api/biz/{bizId}/prompt/publish` | 发布指定草稿 `versionId` |
| POST `/ai-lite/admin/api/biz/{bizId}/prompt/rollback` | 把已发布/已退役版本重新置为生效，正文 `{ "versionId": "..." }` |
| GET `/ai-lite/admin/api/calls` | 调用摘要。查询参数 `bizId`、`provider`、`limit`（默认 50，最大 200） |
| GET `/ai-lite/admin/api/calls/body-level` | 当前输入输出记录档位 |
| PUT `/ai-lite/admin/api/calls/body-level` | 正文 `{ "level": "none" \| "failure" \| "all" }` |

`none` 不记模型输入输出。`failure` 只记失败调用。`all` 每次都记。正文落在 `AI_LITE_LLM_CALL_BODY`，不含密钥和图片原文。档位存在 `AI_LITE_ADMIN_SETTING`。

思考格式不是自由文本，只能用 `GET /thinking-formats` 返回的 id。它决定请求体里用哪一种字段打开或关闭思考，例如 `reasoning-effort` 写 `reasoning_effort`，`chat-template` 写 `chat_template_kwargs.enable_thinking`。
