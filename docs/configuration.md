# 配置参考

所有配置都在 `ai-lite` 前缀下。完整示例见 `ai-lite-demo/src/main/resources/application.yml`。

## 1. 顶层

```yaml
ai-lite:
  enabled: true
  llm: { ... }
  executor: { ... }
  session: { ... }
```

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关，关闭后不装配任何 Bean |

## 2. 模型调用 `ai-lite.llm`

```yaml
ai-lite:
  llm:
    biz-ids: [my-biz]
    default-profile: main
    providers: { ... }
    profiles: { ... }
```

| 字段 | 说明 |
| --- | --- |
| `biz-ids` | 允许调用的业务 id。未登记会在发请求前被拒绝 |
| `default-profile` | 未指定 profile 时使用的组合，可空 |
| `providers` | 供应商端点，key 是供应商 id |
| `profiles` | 业务使用的预置组合 |

### 2.1 供应商

```yaml
providers:
  my-provider:
    api: openai-completions
    base-url: https://example.com/v1
    api-key: ${MY_API_KEY}
    api-key-env: MY_API_KEY
    headers: {}
    extra-body: {}
    connect-timeout-ms: 10000
    read-timeout-ms: 120000
    models: [ ... ]
```

| 字段 | 说明 |
| --- | --- |
| `api` | 协议：`openai-completions`、`openai-responses`、`anthropic-messages` |
| `base-url` | 端点根地址，不要带 `/chat/completions`、`/responses`、`/v1/messages` |
| `api-key` | 支持字面量、`${ENV}`、`${ENV:default}`、`$ENV` |
| `api-key-env` | `api-key` 为空时从该环境变量读取 |
| `headers` | 追加到每个请求的 header |
| `extra-body` | 深合并进请求体，用于厂商私有字段 |
| `connect-timeout-ms` | 连接超时 |
| `read-timeout-ms` | 读取超时，流式按两次数据之间计算 |

### 2.2 模型

```yaml
models:
  - id: my-model
    name: My Model
    reasoning: true
    input: [text, image]
    context-window: 131072
    max-tokens: 8192
    thinking-format: reasoning-effort
    thinking-level-map:
      xhigh: high
      max: high
    sampler-params:
      temperature: 0.7
    sampler-params-by-thinking-level:
      high:
        top_p: 0.95
    headers: {}
    extra-body: {}
```

| 字段 | 说明 |
| --- | --- |
| `id` | 供应商侧模型名 |
| `reasoning` | 是否支持推理；false 时思考档一律为 off |
| `input` | 入参类型，含 `image` 才会校验并通过图片 |
| `context-window` | 上下文窗口，0 表示未知 |
| `max-tokens` | 输出上限 |
| `thinking-format` | `reasoning-effort`（默认）、`chat-template`、`deepseek`、`none`、`anthropic-budget`、`anthropic-adaptive` |
| `thinking-level-map` | 业务档 → 供应商档；value 为 null 表示不支持 |
| `sampler-params` | 模型默认采样参数 |
| `sampler-params-by-thinking-level` | 按生效档覆盖 |
| `headers` / `extra-body` | 覆盖供应商级配置 |

### 2.3 预置组合

```yaml
profiles:
  main:
    provider: my-provider
    model: my-model
    thinking-level: "off"
```

业务代码用 `LlmCall.profile("my-biz", "main", messages)`。

> **注意**：`thinking-level` 的 `off` / `on` 建议加引号。YAML 会把不带引号的
> `off` / `on` / `yes` / `no` 解析成布尔值，绑定后变成字符串 `"false"` / `"true"`。
> 库对这两个值做了兼容（`false` → off，`true` → high），但显式加引号最清楚。

## 3. 执行器 `ai-lite.executor`

```yaml
executor:
  mode: platform
  core-size: 4
  max-size: 16
  queue-capacity: 1000
  keep-alive-seconds: 60
  thread-name-prefix: ai-lite-
  rejection-policy: abort
```

| 字段 | 说明 |
| --- | --- |
| `mode` | `platform`、`direct`、`virtual` |
| `virtual` | 需要 JDK 21 和 `ai-lite-executor-jdk21` 依赖 |
| `rejection-policy` | `abort` 或 `caller-runs` |

## 4. 会话 `ai-lite.session`

```yaml
session:
  enabled: true
  max-entries: 10000
  ttl-seconds: 604800
```

默认是有界内存存储。引入 `ai-lite-session-redis` 或 `ai-lite-session-jdbc` 后，
自行提供一个 `AgentSessionStore` / `SessionCache` Bean 覆盖默认实现。

## 5. 密钥处理

推荐顺序：

1. 环境变量：`api-key: ${MY_API_KEY}`
2. Spring 外部配置：`--ai-lite.llm.providers.my-provider.api-key=...`
3. 本地文件：`application-local.yml`（已在 `.gitignore`）

不推荐把密钥写进 `application.yml` 或代码。开源仓库里不要出现任何真实密钥。

## 6. 常见供应商示例

### vLLM / Qwen

```yaml
providers:
  vllm:
    api: openai-completions
    base-url: http://127.0.0.1:8000/v1
    api-key: not-needed
    extra-body:
      chat_template_kwargs:
        enable_thinking: true
    models:
      - id: Qwen3.6-27B
        reasoning: true
        input: [text]
        thinking-format: chat-template
        max-tokens: 8192
```

### DeepSeek

```yaml
providers:
  deepseek:
    api: openai-completions
    base-url: https://api.deepseek.com
    api-key: ${DEEPSEEK_API_KEY}
    models:
      - id: deepseek-chat
        reasoning: false
        input: [text]
        max-tokens: 8192
```

### OpenAI Responses

```yaml
providers:
  openai:
    api: openai-responses
    base-url: https://api.openai.com/v1
    api-key: ${OPENAI_API_KEY}
    models:
      - id: gpt-4.1
        reasoning: false
        input: [text, image]
        max-tokens: 8192
```

### Anthropic

```yaml
providers:
  anthropic:
    api: anthropic-messages
    base-url: https://api.anthropic.com
    api-key: ${ANTHROPIC_API_KEY}
    models:
      - id: claude-sonnet-4-5
        reasoning: true
        input: [text, image]
        thinking-format: anthropic-budget
        max-tokens: 8192
```
