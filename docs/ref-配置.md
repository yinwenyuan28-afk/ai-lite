# 配置参考

> **本文在哪一层**：横切层（不属于某一层，所有配置集中在这里）。
> **讲什么**：`ai-lite.*` 全部配置键的取值与默认值，按配置段分节（llm / executor / callback / metrics / guard / http / session / prompt）。
> **不讲什么**：这些配置改变行为后，运行起来是什么样——去看对应层的文档（单次调用 → [单次模型调用入口](desc-单次调用.md)；HTTP 传输 → [HTTP客户端使用指南](guide-HTTP客户端.md)）。生词查 [术语表](ref-术语表.md)。

所有配置都在 `ai-lite` 前缀下。完整示例见 `ai-lite-demo/src/main/resources/application.yml`；业务 API 的用法见 [guide-上层调用API.md](guide-上层调用API.md)。

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
    api-keys:                    # 多 key 池（可省）；元素支持与 api-key 相同的占位符
      - ${KEY_ONE}
      - ${KEY_TWO:fallback}
    guard:
      concurrency: 8             # 该供应商并发上限（每次模型调用占一个许可）；0=不限
    models: [ ... ]
```

| 字段 | 说明 |
| --- | --- |
| `api` | 协议：`openai-completions`、`openai-responses`、`anthropic-messages` |
| `base-url` | 端点根地址，不要带 `/chat/completions`、`/responses`、`/v1/messages` |
| `api-key` | 支持字面量、`${ENV}`、`${ENV:default}`、`$ENV` |
| `api-key-env` | `api-key` 为空时从该环境变量读取 |
| `api-keys` | 多 key 轮换池；配置后优先于 `api-key`/`api-key-env`。429/401 失败时把当前 key 冷却一段时间并换下一把（换 key 的指针只在失败时推进；全部 key 都在冷却中就先用第一把，冷却到期自动恢复） |
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

`ExecutorProvider.io()` 执行的是**阻塞副作用任务**：会话加载/落盘、工具执行、调用记录落表，以及协程模式下显式提交的阻塞代码。模型网络等待由异步 transport 和 HTTP 回调承担，不占这个池。四种模式的完整说明和对比见 [desc-架构总览.md](desc-架构总览.md) 的 5.2 节。

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
| `core-size` | `max(2, CPU)` | platform、coroutines | platform 的核心线程数；coroutines 的协程载体线程数（运行协程的平台线程，见 [术语表](ref-术语表.md) §4） |
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
| `virtual` | 每任务一个虚拟线程 | JDK 21 + `ai-lite-executor-jdk21` | 阻塞时自动让出底层线程；注意 `synchronized`/native pinning |
| `coroutines` | 固定协程线程池的阻塞视图 | `ai-lite-executor-coroutines` | 协程代码走 `dispatcher()`（挂起不占线程）；`io()` 与协程线程池共用，长阻塞会拖垮其他协程 |

选型建议：

- JDK 8、Java 为主、阻塞任务量中等：`platform`。
- 单元测试、需要确定性时序：`direct`。
- JDK 21、工具或老 SDK 是阻塞式：`virtual`。
- JDK 8 + Kotlin、LLM 等待占主导：`coroutines`，并实际使用 `LlmCoroutines`。
- 如果主要是等模型、阻塞任务很少：`platform` 就够了，换执行器不会显著提升模型 QPS。

注意：`direct` 和 `virtual` 会忽略所有池字段；`coroutines` 只使用 `core-size` 和 `thread-name-prefix`，`max-size`、`queue-capacity`、`keep-alive-seconds`、`rejection-policy` 不生效。`platform` 默认 `abort`，队列满会拒绝会话加载/落盘；异步调用日志会吞掉拒绝并打 warn，不影响主调用。

## 3.1 回调策略 `ai-lite.callback`

控制用户回调（流式 `onDelta`）与 Agent 钩子跑在哪个线程。默认 `inline`，行为与历史版本完全一致。

```yaml
ai-lite:
  callback:
    mode: inline              # inline | io
    queue-capacity: 1024      # 每 run 的回调队列上限（io 模式）
    executor:                 # 仅 io 模式使用
      core-size: 2
      max-size: 8
      queue-capacity: 2048
      thread-name-prefix: ai-lite-callback-
```

| mode | 行为 |
| --- | --- |
| `inline`（默认） | 回调跑在触发它的线程上（传输/载体/调用方），零额外开销 |
| `io` | 回调经独立回调池上的 per-run 串行执行器执行：单 in-flight FIFO、慢回调只阻塞自己 |

- `io` 模式只对**异步入口**生效（`runAsync`、`*Async` 流式/非流式）；同步入口恒 inline。
- 队列满 → `LlmCallException`（`Kind.OVERLOAD`，不重试）：流式调用先把 future 异常完成、再中断传输；`AgentLoop.runAsync` 的推进循环在提交线程完成收尾并中止在途请求。
- `io` 模式下 AgentLoop 的钩子线程为 `ai-lite-callback-*`；工具本体仍在 `ai-lite.executor` 的载体池。
- 文档化例外：`beforeRun` 恒在调用方线程；`future.cancel` 路径的 `afterRun` 在取消方线程。
- 与 `ai-lite.executor.mode=direct` 组合：允许，启动 WARN——钩子隔离由独立回调池保证，但工具本体会内联在回调线程。
- 自定义 `LlmGateway`/`AgentLoop` Bean：策略不会自动应用（构造时自行传入 `CallbackPolicy`），启动 WARN 提醒。
- 禁止在钩子里同步等待排在后面的回调任务（单 in-flight 下必然死锁）。

## 3.2 指标 `ai-lite.metrics`

LLM 调用指标经 Micrometer 暴露（可观测性计划）：micrometer-core 在 classpath 且 `llm-enabled=true`（默认）时，网关的每条调用记录自动聚合进四个指标——无新增采集路径，core 模块零改动。

| 指标 | 类型 | 标签 |
| --- | --- | --- |
| `ailite.llm.calls` | Counter | `biz`,`provider`,`model`,`api`,`stop`,`outcome`（success/error/aborted） |
| `ailite.llm.duration` | Timer | `biz`,`provider`,`model`,`api` |
| `ailite.llm.tokens` | Counter | `biz`,`provider`,`model`,`type`（input/output/total） |
| `ailite.llm.rounds` | Counter | `biz`,`model`（只计 roundNo>1，即工具轮/重试轮） |

- 标签基数全部有界（biz 白名单、provider/model 来自配置、api 三协议、stop 七种）；错误摘要永不进标签。
- 指标故障不影响调用结果与记录写入（装饰器全量兜底）。
- 用户自定义 `LlmCallStore` Bean 会被装饰而非替换，记录与指标同时生效。
- micrometer 不在 classpath 或 `llm-enabled=false` 时行为与旧版完全一致。
- trace 贯通本轮不做：异步链上 span 传播强绑 Sleuth 且复杂，调用记录里的 `callId` 可作上层关联键。

## 3.3 出口保护 `ai-lite.guard`

网关的出口自我保护（限流熔断计划）：并发限流、熔断、多 key 冷却。**全部默认关闭**——未给供应商配置 `guard.concurrency` 且熔断 `enabled=false` 时，网关不建任何 guard 实例，行为与旧版零差异。

```yaml
ai-lite:
  llm:
    providers:
      demo:
        guard:
          concurrency: 8        # 该供应商并发上限；0=不限（默认）
  guard:                        # 顶级：跨供应商通用参数
    concurrency-wait-ms: 2000   # 拿许可的有界等待（同步等待与异步排队共用）
    async-queue-capacity: 100   # 异步排队容量；超出即拒绝
    circuit:
      enabled: false
      failure-rate: 50          # 窗口失败率阈值（%）
      window-seconds: 60
      minimum-calls: 20
      open-seconds: 30          # 冷却满 30s 进 HALF_OPEN
      probe-timeout-ms: 30000   # 半开探测超时，超时按探测失败回 OPEN
    key-cooldown-seconds: 60    # 429/401 失败标记后单把 key 的冷却时长
```

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `providers.<id>.guard.concurrency` | `0` | 供应商并发上限，按**单次模型调用**计许可（工具循环每轮各占各还，工具执行期间不占）；0=不限 |
| `concurrency-wait-ms` | `2000` | 拿许可的等待上限；小于等于 0 表示不等待（满即拒绝） |
| `async-queue-capacity` | `100` | 异步排队容量，超出即 OVERLOAD 拒绝 |
| `circuit.enabled` | `false` | 熔断总开关；开启后所有供应商都受熔断保护（并发不限的供应商只做熔断） |
| `circuit.failure-rate` | `50` | 窗口失败率阈值（%） |
| `circuit.window-seconds` | `60` | 统计窗口（内部 6 片环形滑动，读数为近似值） |
| `circuit.minimum-calls` | `20` | 触发门槛的最少样本数 |
| `circuit.open-seconds` | `30` | 拉闸冷却时长，期满后进入 HALF_OPEN（放行 1 个试探请求，带超时） |
| `circuit.probe-timeout-ms` | `30000` | 半开探测超时，超时按探测失败回 OPEN |
| `key-cooldown-seconds` | `60` | 429/401 失败标记后 key 冷却时长 |

行为要点：

- **许可按轮**：每次 transport 调用（含重试的每次 attempt）获取一次许可；同步路径有界等待（超时抛 OVERLOAD），异步路径在载体池排队（不阻塞提交线程）。
- **拒绝语义**：并发/排队拒绝抛 `Kind.OVERLOAD`、熔断拒绝抛新增的 `Kind.CIRCUIT_OPEN`——两者**不可重试**（重试只会空转）。guard 自身的拒绝不计入熔断窗口。
- **失败口径**：熔断窗口直接采用 `RetryPolicy.defaultRetryable` 判定（429/408/5xx/传输 IO 计失败；PARSE/CONFIG/取消不计）；429 同时计入失败率与 `rate-limited` 指标。
- **Retry-After**：5 个 HTTP 执行器在 429/503 时带出 `Retry-After`（仅正整数秒），显式重试的下一次退避优先采用该值（clamp 到 `max-backoff-ms` 与总时限内）。
- **key 脱敏**：轮换与冷却只按 key 值匹配索引，key 永不进日志/记录/指标/异常消息。

指标（micrometer 在 classpath 时）：

| 指标 | 类型 | 标签 |
| --- | --- | --- |
| `ailite.llm.guard.rejected` | Counter | `provider`,`reason`（concurrency/circuit） |
| `ailite.llm.guard.circuit-state` | Gauge | `provider`（0=CLOSED/1=OPEN/2=HALF_OPEN） |
| `ailite.llm.guard.rate-limited` | Counter | `provider` |

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
    <version>0.1.7</version>
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
    <version>0.1.7</version>
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
> 更多背景与风险见 [夯实 HTTP 传输并发](plan/夯实HTTP传输并发-plan.md)。

WebClient（真实响应式栈，适合已经使用 WebFlux 的项目）：

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-http-webclient</artifactId>
    <version>0.1.7</version>
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
    <version>0.1.7</version>
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

用户回调与 Agent 钩子的线程策略不属于本节，见 [夯实回调执行策略](plan/夯实回调执行策略-plan.md)。

### 4.2 异步重试调度器

`oneWithRetryAsync` 需要一个 `ScheduledExecutorService`。Starter 默认提供名为
`aiLiteRetryScheduler` 的 Bean：2 个 daemon 线程、线程名 `ai-lite-retry-*`、
开启 `removeOnCancelPolicy`，容器关闭时 `shutdownNow`。

没有独立的 `ai-lite.retry.*` 配置项。需要调整线程数或线程名时，提供同名 Bean 覆盖：

```java
@Bean(name = "aiLiteRetryScheduler", destroyMethod = "shutdownNow")
public ScheduledExecutorService retryScheduler() {
    ScheduledThreadPoolExecutor executor =
            new ScheduledThreadPoolExecutor(4, r -> {
                Thread t = new Thread(r, "my-retry-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
}
```

网关按 `@Qualifier("aiLiteRetryScheduler")` 注入，所以用户同时定义其他
`ScheduledExecutorService` 不会产生歧义。手工构造 `LlmGateway` 时：
5 参构造器使用进程级共享 daemon 调度器，6 参构造器可传入自己的调度器；
网关不会关闭注入的调度器。

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

## 5.1 上下文治理 `ai-lite.context`

```yaml
context:
  enabled: true
  estimator: chars-weighted
  budget:
    reserve-tokens: 8192
  compaction:
    enabled: true
    keep-recent-tokens: 12000
    summary-max-tokens: 2048
    summary-provider:        # 留空 = 用当前主调用的供应商
    summary-model:           # 留空 = 用当前主调用的模型
    timeout-ms: 60000
    max-per-run: 3
  tool-result:
    enabled: true
    max-chars: 2000
```

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 预算检测总开关；关闭后不做 token 估算与超限判断 |
| `estimator` | `chars-weighted` | 估算器实现标识，目前只有这一个 |
| `budget.reserve-tokens` | `8192` | 留给模型回复的余量；触发线 = `context-window - reserve-tokens` |
| `compaction.enabled` | `true` | 自动压缩开关；关闭后超限直接抛 `CONTEXT_OVERFLOW` |
| `compaction.keep-recent-tokens` | `12000` | 压缩后保留的最近 token 数 |
| `compaction.summary-max-tokens` | `2048` | 摘要正文的目标上限（同时作为摘要请求的 `max_tokens`） |
| `compaction.summary-provider` | 空 | 摘要调用的供应商；空表示沿用主调用 |
| `compaction.summary-model` | 空 | 摘要调用的模型；空表示沿用主调用 |
| `compaction.timeout-ms` | `60000` | 摘要调用独立超时（不绑定主 run 的取消） |
| `compaction.max-per-run` | `3` | 单次 run 内压缩次数上限，防止不收敛时无限循环 |
| `tool-result.enabled` | `true` | 工具结果截断开关 |
| `tool-result.max-chars` | `2000` | 单条工具结果保留的最大字符数 |

**启动期校验**：`keep-recent-tokens + summary-max-tokens` 必须小于
`context-window - reserve-tokens`。不满足会导致**启动失败**，错误消息给出三个实际数值。
理由：压缩后估算值仍高于触发线时，每轮都会重新压缩、每轮都花钱，而这种故障在运行期
只表现为账单异常，不会报错。

**窗口未知时跳过检查**：模型的 `context-window` 未配置（null/0）或小于等于
`reserve-tokens` 时视为"无法判断"，不做预算检查也不压缩，行为与未接入本能力时一致。

**三组独立开关的理由**：拆成 `budget` / `compaction` / `tool-result` 才能表达
"只要截断、不要压缩"这类组合。

**估算精度**：按字符类型加权（ASCII 0.25、中文 0.6 tokens/字符）。这是一个
中文为主的库，常见的"字符数 / 2.5"对中文低估 40%~60%，会让压缩判断失效。
精确 token 数以供应商返回的 usage 为准，估算只用于判断"该不该压缩"。

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

## 7. 调用记录存储 `ai-lite.store.jdbc`

引入 `ai-lite-store-jdbc` 且容器里有 `JdbcTemplate` 时生效，自动注册 `JdbcLlmCallStore`。

```yaml
store:
  jdbc:
    enabled: true                    # 默认 true
    table-name: AI_LITE_LLM_CALL     # 必填，无默认值
```

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 关闭后不注册 `LlmCallStore` |
| `table-name` | - | **必填**。调用记录表名，只允许字母、数字和下划线 |

> **为什么表名没有默认值**：调用记录写失败会被 `AsyncLlmCallLogger` 静默吞掉
> （只留一行 warn），用户可能几周后才发现账单表一直是空的。强制显式配置是让
> 用户确认"我知道我在写这张表"的唯一机会，同时确保已执行 `ai-lite-llm-call.sql` 建表。
>
> 引入模块但未配置 `table-name` → **应用启动失败**，错误消息会提示建表。
> 不需要 JDBC 调用记录时设 `ai-lite.store.jdbc.enabled=false`。

**两个开关的区别**：写 `enabled: false` 是"我知道有这个功能但不用"；不配
`table-name` 是"忘了配"，所以前者安静关闭、后者启动报错。

用户自定义 `LlmCallStore` Bean 时本配置整体让位（`@ConditionalOnMissingBean`），
不会出现两个实现同时写库。

## 8. 密钥处理

推荐顺序：

1. 环境变量：`api-key: ${MY_API_KEY}`
2. Spring 外部配置：`--ai-lite.llm.providers.my-provider.api-key=...`
3. 本地文件：`application-local.yml`（已在 `.gitignore`）

不推荐把密钥写进 `application.yml` 或代码。开源仓库里不要出现任何真实密钥。

## 9. 常见供应商示例

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
