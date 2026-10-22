# 配置参考

所有配置都在 `ai-lite` 前缀下。完整示例见 `ai-lite-demo/src/main/resources/application.yml`。
业务 API 的用法见 [api-usage.md](api-usage.md)。

## 1. 顶层

```yaml
ai-lite:
  enabled: true
  llm: { ... }
  executor: { ... }
  http: { ... }
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

## 4. HTTP 传输 `ai-lite.http`

模型请求的 HTTP 客户端与 `ai-lite.executor` 相互独立：前者决定“等待网络时占哪类线程”，后者决定“工具、日志、会话落盘在哪里跑”。默认 `okhttp`，与旧版本行为一致。

```yaml
http:
  transport: okhttp          # okhttp | okhttp-virtual | jetty | webclient | ktor-cio | jdk8
  dispatcher:
    max-requests: 64
    max-requests-per-host: 5
  jetty:
    max-connections-per-destination: 64
    threads: 0               # 0 表示使用 Jetty 默认线程池
  ktor:
    max-connections: 1000
    max-connections-per-route: 100
    pipelining: true
```

| 字段 | 默认值 | 生效范围 | 说明 |
| --- | --- | --- | --- |
| `transport` | `okhttp` | 全部 | 选择 HTTP 实现；大小写不敏感 |
| `dispatcher.max-requests` | `64` | `okhttp`、`okhttp-virtual` | 全局在途请求上限 |
| `dispatcher.max-requests-per-host` | `5` | `okhttp`、`okhttp-virtual` | 单 host 在途请求上限 |
| `jetty.max-connections-per-destination` | Jetty 默认 | `jetty` | 单目标连接数上限 |
| `jetty.threads` | Jetty 默认 | `jetty` | 自定义 Jetty 线程池大小；0 表示不覆盖 |
| `ktor.max-connections` | 引擎默认 | `ktor-cio` | 最大连接数 |
| `ktor.max-connections-per-route` | 引擎默认 | `ktor-cio` | 单路由连接数 |
| `ktor.pipelining` | 引擎默认 | `ktor-cio` | 是否复用连接 |

### 4.1 选择语义

| transport | JDK | 依赖 | 线程模型 |
| --- | --- | --- | --- |
| `okhttp` | 8+ | `ai-lite-http-okhttp`（Starter 默认引入） | 每个在途请求占一个 Dispatcher 平台线程 |
| `okhttp-virtual` | 21+ | `ai-lite-http-okhttp-jdk21` | 每个在途请求一个虚拟线程；同步入口仍阻塞调用线程 |
| `jetty` | 8+ | `ai-lite-http-jetty` | Jetty selector + 连接池，少量线程承载大量连接 |
| `webclient` | 8+ | `ai-lite-http-webclient` + `spring-webflux` | Reactor Netty event loop（可选路径） |
| `ktor-cio` | 8+ | `ai-lite-http-ktor-cio` | Ktor CIO selector（可选路径） |
| `jdk8` | 8+ | `ai-lite-http-jdk8` | 把阻塞 `HttpURLConnection` 交给线程池 |

规则：

- 选择了未引入的模块，启动直接失败，错误消息会列出缺少的依赖。
- 选择了未知值，启动直接失败，错误消息会列出允许值。
- 用户自己提供 `HttpExecutor` / `AsyncHttpExecutor` Bean 时优先使用用户 Bean，`transport` 只打 WARN。
- 与当前 `transport` 不匹配的显式配置（例如给 Jetty 配 `dispatcher.*`）会在启动时 WARN。
- `jdk8` 要真正去掉 OkHttp，需要从 Starter 排除 `ai-lite-http-okhttp`，引入 `ai-lite-http-jdk8`，并显式设置 `transport: jdk8`。

`okhttp-virtual` 需要 JDK 21 和独立模块：

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-http-okhttp-jdk21</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
ai-lite:
  http:
    transport: okhttp-virtual
```

> JDK 21–23 的 `synchronized` 可能 pin 载体线程，高并发流式场景建议 JDK 24+。
> `max-requests-per-host` 仍然限流：虚拟线程让等待更便宜，但不会提高供应商配额。

Jetty 作为跨 Boot 2.1/2.7 的 NIO 主路径：

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-http-jetty</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
ai-lite:
  http:
    transport: jetty
    jetty:
      max-connections-per-destination: 64
```

> Jetty 9.4 已 EOL，安全要求高的项目应评估替代实现；本实现默认关闭重定向、
> 不使用 proxy auth/Basic auth，并强制 `Accept-Encoding: identity` 避免 SSE 被 gzip 缓冲。
> 更多背景与风险见 [夯实 HTTP 传输并发](夯实HTTP传输并发-plan.md)。

WebClient（真实响应式栈，适合已经使用 WebFlux 的项目）：

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-http-webclient</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
ai-lite:
  http:
    transport: webclient
```

> 模块按 Spring WebFlux 5.1 API 编译，同一份字节码在 Boot 2.1/2.7 通过测试。
> 会引入 `spring-webflux` 和 Reactor Netty；MVC 应用依赖体积约增加 6–8MB。
> Boot 2.1 的 Reactor Netty 0.8 在连接重置时可能自动重试一次已发出的 POST，
> 对重复计费敏感的场景请用 Jetty/OkHttp。

Ktor CIO（Kotlin 项目，JDK 8 可用）：

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-http-ktor-cio</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
ai-lite:
  http:
    transport: ktor-cio
    ktor:
      max-connections: 1000
      max-connections-per-route: 100
```

> Ktor 2.3.13 是 2.x 末版，已 EOL；3.x 需要 Kotlin 2.x，当前模块不兼容。
> 模块要求 Kotlin 1.9.25 / coroutines 1.7.3，Boot 2.1/2.7 的默认 Kotlin 版本
> 都需要显式覆盖。CIO selector 使用 `Dispatchers.IO`，IO 池饱和会影响读事件。

用户回调与 Agent 钩子的线程策略不属于本节，见 [夯实回调执行策略](夯实回调执行策略-plan.md)。

## 5. 会话 `ai-lite.session`

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

## 6. 提示词 `ai-lite.prompt`

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

## 7. 密钥处理

推荐顺序：

1. 环境变量：`api-key: ${MY_API_KEY}`
2. Spring 外部配置：`--ai-lite.llm.providers.my-provider.api-key=...`
3. 本地文件：`application-local.yml`（已在 `.gitignore`）

不推荐把密钥写进 `application.yml` 或代码。开源仓库里不要出现任何真实密钥。

## 8. 常见供应商示例

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
