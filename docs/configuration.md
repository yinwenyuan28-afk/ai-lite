# 配置参考

所有配置都在 `ai-lite` 前缀下。完整示例见 `ai-lite-demo/src/main/resources/application.yml`。
业务 API 的用法见 [api-usage.md](api-usage.md)。

## 1. 顶层

```yaml
ai-lite:
  enabled: true
  llm: { ... }
  executor: { ... }
  session: { ... }
  prompt: { ... }
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

`ExecutorProvider.io()` 执行的是**阻塞副作用任务**：会话加载/落盘、工具执行、调用记录落表，以及协程模式下显式提交的阻塞代码。模型网络等待由异步 transport 和 HTTP 回调承担，不占这个池。四种模式的完整说明和对比见 [architecture.md](architecture.md) 的 5.2 节。

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

| 字段 | 默认值 | 生效模式 | 说明 |
| --- | --- | --- | --- |
| `mode` | `platform` | 全部 | `platform`、`direct`、`virtual`、`coroutines` |
| `core-size` | `max(2, CPU)` | platform、coroutines | platform 的核心线程数；coroutines 通过 Starter 时的载体线程数 |
| `max-size` | `max(4, CPU×2)` | platform | 最大线程数 |
| `queue-capacity` | `1000` | platform | 有界队列容量；队列满后按拒绝策略处理 |
| `keep-alive-seconds` | `60` | platform | 空闲线程存活时间 |
| `thread-name-prefix` | `ai-lite-` | platform、coroutines | coroutines 模式会在后面追加 `coroutine-` |
| `rejection-policy` | `abort` | platform | `abort` 或 `caller-runs` |

### 3.1 模式差异

| 模式 | `io()` 的行为 | 依赖 | 说明 |
| --- | --- | --- | --- |
| `platform` | 有界 `ThreadPoolExecutor` | 无 | 每个阻塞任务占一个平台线程；并发上限由 `max-size` 和队列控制 |
| `direct` | 调用线程内联执行 | 无 | 不建池、不切线程；适合测试或确定任务很短、不需要并发的场景 |
| `virtual` | 每任务一个虚拟线程 | JDK 21 + `ai-lite-executor-jdk21` | 阻塞在可挂起操作时卸载载体；注意 `synchronized`/native pinning |
| `coroutines` | 固定载体池的阻塞视图 | `ai-lite-executor-coroutines` | 协程续体走 `dispatcher()`；`io()` 与载体池共用，长阻塞会饿死其他协程 |

选型建议：

- JDK 8、Java 为主、阻塞任务量中等：`platform`。
- 单元测试、需要确定性时序：`direct`。
- JDK 21、工具或老 SDK 是阻塞式：`virtual`。
- JDK 8 + Kotlin、LLM 等待占主导：`coroutines`，并实际使用 `LlmCoroutines`。
- 如果主要是等模型、阻塞任务很少：`platform` 就够了，换执行器不会显著提升模型 QPS。

注意：`direct` 和 `virtual` 会忽略所有池字段；`coroutines` 只使用 `core-size` 和 `thread-name-prefix`，`max-size`、`queue-capacity`、`keep-alive-seconds`、`rejection-policy` 不生效。`platform` 默认 `abort`，队列满会拒绝会话加载/落盘；异步调用日志会吞掉拒绝并打 warn，不影响主调用。

## 4. 会话 `ai-lite.session`

```yaml
session:
  enabled: true
  max-entries: 10000
  ttl-seconds: 604800
  jdbc:
    table-name: AI_LITE_SESSION
```

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 会话 Bean 开关 |
| `max-entries` | `10000` | 内存存储最大会话数 |
| `ttl-seconds` | `604800` | 内存存储过期秒数 |
| `jdbc.table-name` | `AI_LITE_SESSION` | 引入 `ai-lite-session-jdbc` 且容器有 `JdbcTemplate` 时使用的表名；只允许字母、数字和下划线 |

默认是有界内存存储。引入 `ai-lite-session-jdbc` 后，只要容器里有 `JdbcTemplate`，
自动改用 `JdbcAgentSessionStore`，Starter 的内存存储会让位；表名由
`ai-lite.session.jdbc.table-name` 指定。引入 `ai-lite-session-redis` 后，
自行提供一个 `SessionCache` Bean，并用 `CachingAgentSessionStore` 包装持久层。

## 5. 提示词 `ai-lite.prompt`

```yaml
prompt:
  enabled: true
  cache-seconds: 60
  keys:
    - key: resume-parse
      title: 简历解析
      placeholders: [resume, job]
      biz-id: my-biz
      profile: main
      default-system-template: "请解析这份简历：{{resume}}"
      default-user-template: "目标岗位：{{job}}"
```

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 关闭后不装配提示词 Bean |
| `cache-seconds` | `60` | 生效版本本地缓存秒数；0 表示每次都读存储 |
| `keys[].key` | - | 稳定编码，只能用小写字母、数字和短横线，最长 64 |
| `keys[].placeholders` | `[]` | 发布前必须出现在模板里的占位符名 |
| `keys[].default-system-template` | - | 没有生效版本或存储不可用时使用的系统模板 |
| `keys[].default-user-template` | - | 兜底用户模板 |
| `keys[].biz-id` / `profile` | - | 业务侧关联信息，库不强制使用 |

渲染优先级：生效版本 → 调用方兜底 → 配置默认模板。数据库不可用时 `render`
不会抛异常；只有完全没有系统模板时才报错。

## 6. 密钥处理

推荐顺序：

1. 环境变量：`api-key: ${MY_API_KEY}`
2. Spring 外部配置：`--ai-lite.llm.providers.my-provider.api-key=...`
3. 本地文件：`application-local.yml`（已在 `.gitignore`）

不推荐把密钥写进 `application.yml` 或代码。开源仓库里不要出现任何真实密钥。

## 7. 常见供应商示例

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
