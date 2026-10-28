# HTTP 客户端使用指南

> **本文在哪一层**：HTTP 传输层（外加一页单次调用 API 速查）。
> **讲什么**：五层里最底下那层——六种传输怎么选怎么切、连接池与超时调优、自定义 `HttpExecutor`；以及构造 `LlmCall`、结果字段、常见问题速查。
> **不讲什么**：传输层为什么这样设计（线程模型、SSE 解析、取消怎么贯穿）→ [HTTP传输层原理](desc-HTTP传输.md)；一次调用的内部旅程（线程接力、重试环、出口保护）→ [单次模型调用入口](desc-单次调用.md)；重试策略细节 → [重试边界](desc-重试.md)；编排层 → [Workflow异步执行器使用指南](guide-Workflow异步执行器.md)。生词查 [术语表](ref-术语表.md)。

适用版本：`0.1.8`；JDK 8+；Spring Boot 2.1 ~ 2.7。

本文结构：§1 配置参考；§2 单次调用 API 速查（行为细节在《单次模型调用入口》）；§3–§5 HTTP 客户端层本体（三层关系、切换传输、装配覆盖）；§6 调用记录；§7 常见问题。

---

## 1. 配置参考

只列本层相关的字段；完整字段见 [ref-配置.md](ref-配置.md)。

### 1.1 `ai-lite.llm`

| 字段 | 说明 |
| --- | --- |
| `biz-ids` | 允许调用的业务 id；未登记会在发请求前失败 |
| `default-profile` | 未指定 profile / provider / model 时使用的组合 |
| `providers` | 供应商端点，key 是供应商 id |
| `profiles` | 供应商 + 模型 + thinking 档的组合 |

供应商字段：

| 字段 | 说明 |
| --- | --- |
| `api` | 协议：`openai-completions`、`openai-responses`、`anthropic-messages` |
| `base-url` | 端点根地址，不要带具体路径 |
| `api-key` / `api-key-env` | 密钥；后者在 `api-key` 为空时从环境变量读取 |
| `headers` / `extra-body` | 追加请求头和请求体字段 |
| `connect-timeout-ms` | 连接超时，默认 10000 |
| `read-timeout-ms` | 读取超时，默认 120000；流式按两次数据之间计算 |
| `models` | 模型列表，每项必须有 `id` |

模型字段（常用）：

| 字段 | 说明 |
| --- | --- |
| `id` | 模型 id，必填 |
| `reasoning` | 是否支持思考，影响 `thinking-level` 的裁剪 |
| `input` | 输入能力，`[text]` 或 `[text, image]` |
| `context-window` / `max-tokens` | 上下文窗口和输出上限；`max-tokens` 缺省 8192 |
| `thinking-level-map` | 业务档位到供应商档位的映射 |
| `sampler-params` | 采样参数，例如 `temperature`、`top-p` |

### 1.2 `ai-lite.http`

```yaml
ai-lite:
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

- `okhttp` / `okhttp-virtual` 使用 `dispatcher.*`；连接池大小会跟随 `max-requests` 调整。
- `jetty` / `ktor-cio` / `webclient` / `jdk8` 不使用 `dispatcher.*`，配置了会得到启动警告。
- 只配置 `transport` 而没有引入对应模块，会在启动时 fail-fast，不会静默退回 OkHttp。
- **`jetty.threads` 不能随手调小**：Jetty 的 selector 线程先占掉一部分，线程池小于
  `max(1, CPU核数/2) + 2` 时客户端会在**第一次调模型时**（懒创建）报
  `Insufficient configured threads`。默认 `0` 最安全；确需限制时按
  [配置参考 §4](ref-配置.md) 的对照表取值。

### 1.3 `ai-lite.executor`

```yaml
ai-lite:
  executor:
    mode: platform             # platform | direct | virtual | coroutines
    core-size: 4
    max-size: 16
    queue-capacity: 1000
```

它决定**阻塞副作用任务**在哪里跑：调用记录落库、工具执行、会话读写，以及协程模式
下显式提交的阻塞代码。它**不负责模型网络等待**；后者由 HTTP 传输层承担。

---

## 2. 单次调用 API 速查

本节只放"怎么用"；行为细节（线程接力、取消分层、重试环、出口保护）都在 [单次模型调用入口](desc-单次调用.md)，本文不重复。

### 2.1 入口矩阵

完整矩阵（10 个入口的返回、阻塞、取消对照）见 [单次模型调用入口](desc-单次调用.md) §11.1。日常最常用的四个：

| 任务 | 入口 |
| --- | --- |
| 同步等结果 | `gateway.one(call)` |
| Web 请求释放线程 | `gateway.oneAsync(call)`，controller 返回 future |
| 边生成边推送 | `gateway.oneByStreamAsync(call, onDelta, cancel)` |
| 显式重试（非阻塞） | `gateway.oneWithRetryAsync(call, policy, cancel)` |

`round` / `toolLoop` 属于工具相关入口，本文不展开；它们复用同一套 HTTP 与取消语义。

### 2.2 构造 `LlmCall`

```java
// 1) 用配置里的 profile
LlmCall call = LlmCall.profile("my-biz", "main", messages);

// 2) 显式指定供应商和模型
LlmCall call = LlmCall.explicit("my-biz", "my-provider", "my-model", "off", messages);

// 3) 覆盖本次连接/读取超时
call.timeouts(5_000, 60_000);

// 4) 保留模型选择，只替换消息
call = call.withMessages(nextMessages);
```

未指定模型时的解析顺序：

| `LlmCall` 内容 | 结果 |
| --- | --- |
| 指定了 `profile` | 用该 profile |
| profile 为空，provider 和 model 都为空 | 用 `ai-lite.llm.default-profile` |
| 同上但没有 `default-profile` | 抛 `供应商不能为空` |
| 只给 provider，没有 model | 抛 `未知模型: null`，不会自动选第一个模型 |
| 只给 model，没有 provider | 抛 `供应商不能为空` |

消息类型：

```java
LlmMessage.system("你是助手");
LlmMessage.user("你好");
LlmMessage.user("描述这张图", Collections.singletonList(new LlmImage("image/png", base64)));
LlmMessage.assistant("回答", toolCalls);
LlmMessage.toolResult(toolCallId, "工具结果");
LlmMessage.other("其他角色内容");
```

图片要求模型配置的 `input` 包含 `image`，否则发请求前抛 `LlmCallException`。

### 2.3 同步调用

```java
LlmResult result = gateway.one(call);

String content = result.getContent();
LlmStopReason stop = result.getStopReason();
LlmUsage usage = result.getUsage();
String model = result.getModelId();
long latency = result.getLatencyMs();
```

同步入口会占住调用线程直到响应、超时或取消。适合脚本、批处理、CLI。

### 2.4 异步调用

```java
CompletableFuture<LlmResult> future = gateway.oneAsync(call);
future.thenAccept(result -> log.info("回答：{}", result.getContent()));
```

Spring MVC 可以直接返回 future，让容器释放请求线程：

```java
@GetMapping("/chat")
public CompletableFuture<LlmResult> chat(@RequestParam String q) {
    return gateway.oneAsync(LlmCall.profile("my-biz", "main",
            Collections.singletonList(LlmMessage.user(q))));
}
```

注意：`oneAsync` 释放的是**调用线程**。内置 OkHttp 传输仍在 Dispatcher 线程上执行
I/O、读 body 和解析，每个在途请求占一个 Dispatcher 线程；流式请求占到流结束。

如果需要“客户端断连就取消底层请求”，用 `DeferredResult` / `SseEmitter`，在
`onCompletion` / `onTimeout` 里调用 `cancel.cancel()`。直接返回 `CompletableFuture`
时，Servlet 容器不保证断连会取消这个 future。

### 2.5 流式调用

```java
LlmCancelHandle cancel = new LlmCancelHandle();

LlmResult result = gateway.oneByStream(call,
        delta -> sseEmitter.send(delta),
        cancel);

// 或者非阻塞
CompletableFuture<LlmResult> future = gateway.oneByStreamAsync(call,
        delta -> { /* 不要在这里做重活 */ },
        cancel);
```

- 同步流式的 `onDelta` 在调用线程上执行；异步流式在 HTTP 传输的 I/O 线程上执行。
- 回调必须短小：写数据库、调用下游、长时间阻塞都会拖住 HTTP 线程。
- 已经收到 delta 后再失败，不会自动重试；是否续写由业务决定。
- 流式读取超时按“两次数据之间”的间隔计算，不是整个响应时长。

### 2.6 取消

```java
LlmCancelHandle cancel = new LlmCancelHandle();
LlmResult result = gateway.one(call, cancel);

// 其他线程、超时任务、断连回调里：
cancel.cancel();
```

`handle.cancel()`：同步入口返回 `ABORTED` 结果，异步入口 future 正常完成 `ABORTED`。`future.cancel(true)`：future 进入 cancelled，底层 HTTP 同时被中止。发请求前取消则不发 HTTP 直接 `ABORTED`。

分层保证（L1–L4）、传输差异、取消竞态的完整契约见 [单次模型调用入口](desc-单次调用.md) §9。要点只有两条：`LlmCancelHandle` 是一次性的，不要跨调用复用；取消后仍可能写一条 `aborted` 调用记录，这是预期行为。

### 2.7 显式重试

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(3)                  // 含第一次
        .initialBackoffMs(200)
        .multiplier(2.0)
        .maxBackoffMs(2000)
        .jitter(true)
        .totalDeadlineMs(10_000L)        // 可空
        .build();

// 同步：退避阻塞调用线程
LlmResult result = gateway.oneWithRetry(call, policy, cancel);

// 异步：退避在专用调度线程
CompletableFuture<LlmResult> future =
        gateway.oneWithRetryAsync(call, policy, cancel);
```

默认策略：3 次尝试、200ms 起、2 倍退避、封顶 2s、带抖动、无总 deadline。

可重试/不可重试分类、`Retry-After` 采纳、异步重试的线程与取消、自定义调度器 Bean（`aiLiteRetryScheduler`）见 [重试边界](desc-重试.md)；重试的完整流程图见 [单次模型调用入口](desc-单次调用.md) §4。

### 2.8 结果与错误

`LlmResult` 常用字段：

| 方法 | 说明 |
| --- | --- |
| `getContent()` | 可见正文 |
| `getThinkingText()` / `getThinkingSignature()` | 推理原文与签名 |
| `getRawContent()` | thinking + 正文拼接，调试用 |
| `getStopReason()` | `STOP` / `LENGTH` / `TOOL_USE` / `ERROR` / `ABORTED` / `MAX_ROUNDS` / `CONTEXT_LENGTH_EXCEEDED` |
| `getUsage()` | 输入、输出、总 token |
| `getProvider()` / `getModelId()` / `getThinkingLevel()` | 实际解析结果 |
| `getLatencyMs()` / `getRounds()` | 耗时与轮数 |
| `getToolCalls()` | 声明工具时模型返回的调用 |

失败统一是 `LlmCallException`：

| `Kind` | 典型原因 | 可重试 |
| --- | --- | --- |
| `CONFIG` | biz-id 未登记、模型不存在、协议不支持、密钥缺失、参数校验失败 | 否 |
| `TRANSPORT` | 连接失败、DNS/TLS、读写超时、连接重置 | 是 |
| `HTTP` | 非 2xx，`getStatusCode()` 带状态码 | 408/429/5xx 是 |
| `PARSE` | JSON / SSE 结构异常 | 否 |
| `CANCELLED` | 传输层报告的取消 | 否 |
| `UNKNOWN` | 其他；根因是 `IOException` 时按瞬时错误处理 | 视根因 |

异步入口的失败通过 future 异常完成；`ExecutionException` 的 `getCause()` 是
`LlmCallException`。参数/配置错误在返回 future 前同步抛出。

---

## 3. HTTP 客户端层

### 3.1 三层关系

```text
LlmGateway                 业务入口：校验、解析模型、记录、取消、重试
  └─ LlmTransport          协议层：拼 OpenAI / Responses / Anthropic 的 JSON 与 SSE
       └─ HttpExecutor     HTTP 层：POST、超时、连接池、流式逐行读取
```

业务代码通常只接触 `LlmGateway`；只有要换 HTTP 客户端或自定义协议时才需要往下看。

### 3.2 默认实现

Starter 默认引入 OkHttp 4.12：

- `retryOnConnectionFailure(false)`，避免与上层重试叠加；
- `Dispatcher.maxRequests` 默认 64，`maxRequestsPerHost` 默认 5；
- 连接池大小跟随 `max-requests`，避免高并发下反复建连；
- 提供 `executeAsync` / `executeStreamingAsync`，`oneAsync` 不占调用线程。

### 3.3 切换传输

| 传输 | 额外依赖 | 配置 | 特点 |
| --- | --- | --- | --- |
| OkHttp | 无（Starter 自带） | `transport: okhttp` | 默认，稳定，每在途请求占一个 Dispatcher 线程 |
| OkHttp + 虚拟线程 | `ai-lite-http-okhttp-jdk21`（JDK 21+） | `transport: okhttp-virtual` | 回调跑在虚拟线程，平台线程数显著下降 |
| Jetty NIO | `ai-lite-http-jetty` | `transport: jetty` | 少量平台线程承载高并发，跨 Boot 2.1/2.7 |
| JDK8 | `ai-lite-http-jdk8` | `transport: jdk8` | 零额外依赖；异步退化为线程池，取消能力较弱 |
| WebClient | `ai-lite-http-webclient` + `spring-boot-starter-webflux` | `transport: webclient` | Reactor 背压，线程占用少 |
| Ktor CIO | `ai-lite-http-ktor-cio`（Kotlin） | `transport: ktor-cio` | Kotlin 项目可选，CIO 引擎 |

示例：切 Jetty

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-http-jetty</artifactId>
    <version>0.1.8</version>
</dependency>
```

```yaml
ai-lite:
  http:
    transport: jetty
    jetty:
      max-connections-per-destination: 1000
```

切换后 `dispatcher.*`、`jetty.*`、`ktor.*` 中与当前传输无关的配置不会生效，启动日志会提示。

### 3.4 调优建议

- **先看瓶颈在哪。** 如果主要时间在等上游，换传输不会提高单请求延迟，只会改变线程占用。
- **OkHttp**：提高 `max-requests` 时同步观察连接池和上游限流；`max-requests-per-host` 默认 5，单端点高并发时要显式调大。
- **Jetty / WebClient / Ktor**：关注连接数上限与上游配额，不要只调本地线程数。
- **JDK8**：只适合低并发或禁用第三方 HTTP 客户端的场景；取消在正文读取阶段是 best-effort。
- **超时**：供应商级 `connect-timeout-ms` / `read-timeout-ms` 是默认值，单次调用可用
  `LlmCall.timeouts(...)` 覆盖；重试的总时限用 `RetryPolicy.totalDeadlineMs`。

### 3.5 自定义 `HttpExecutor`

实现 `HttpExecutor`（推荐同时实现 `AsyncHttpExecutor`），注册成 Bean：

```java
@Bean
public HttpExecutor myHttpExecutor() {
    return new MyAsyncHttpExecutor();
}
```

Starter 的默认 `HttpExecutor` 带 `@ConditionalOnMissingBean`，用户 Bean 优先；
此时 `ai-lite.http.transport` 不再生效，启动日志会警告。

契约：

| 方法 | 要求 |
| --- | --- |
| `execute(HttpRequest)` | 同步 POST；非 2xx 抛 `LlmCallException(Kind.HTTP, status)`；网络错误 `TRANSPORT`；取消 `CANCELLED`；必须关闭响应资源 |
| `executeStreaming(HttpRequest, StreamListener)` | 逐行回调，不含换行符；处理跨 chunk 的 UTF-8 / CRLF；尊重 `request.getCancel()` |
| `executeAsync(HttpRequest)` | 立即返回 future，不阻塞调用线程；取消时尽量中断底层请求 |
| `executeStreamingAsync(...)` | 同上，行回调在实现自己的线程上执行 |
| `close()` | 幂等释放连接池和线程 |

只实现同步 `HttpExecutor` 也能工作，但内置 transport 的 `completeAsync` 会退化为
`CompletableFuture.supplyAsync(...)`，使用 `ForkJoinPool.commonPool`；高并发下这通常不是
想要的结果。

建议：

- 流式请求设置 `Accept-Encoding: identity`，避免 gzip 缓冲破坏逐行语义；
- 取消通过 `request.getCancel().bindAbort(runnable)` 绑定连接中断动作；
- 不要在 `onLine` 里做长时间阻塞操作。

### 3.6 不用 Spring 的手工装配

```java
AiLiteConfig config = new AiLiteConfig();
config.getLlm().setBizIds(Collections.singletonList("my-biz"));
// 填 providers / profiles ...

HttpExecutor http = OkHttpClientFactory.executor(config.getHttp());
ExecutorProvider executor = new PlatformExecutorProvider(config.getExecutor());
try {
    LlmGateway gateway = new LlmGateway(
            new ModelResolver(config.getLlm()),
            Arrays.asList(
                    new OpenAiCompletionsTransport(http),
                    new OpenAiResponsesTransport(http),
                    new AnthropicMessagesTransport(http)),
            new NoopLlmCallStore(),
            executor,
            new SequentialToolExecutor());

    LlmResult result = gateway.one(LlmCall.profile("my-biz", "main",
            Collections.singletonList(LlmMessage.user("hi"))));
} finally {
    executor.close();
    http.close();
}
```

5 参构造器使用进程级共享 daemon 重试调度器；需要自己管理生命周期时用 6 参构造器
传入 `ScheduledExecutorService`。

---

## 4. 装配与覆盖

| Bean | 默认 | 覆盖方式 |
| --- | --- | --- |
| `LlmGateway` | 自动装配 | 自定义同类型 Bean |
| `HttpExecutor` | 按 `ai-lite.http.transport` 选择 | 自定义同类型 Bean |
| `LlmTransport` | 三个内置协议 | 同名 Bean：`openAiCompletionsTransport`、`openAiResponsesTransport`、`anthropicMessagesTransport` |
| `ExecutorProvider` | `ai-lite.executor.mode` | 自定义同类型 Bean |
| `LlmCallStore` | `NoopLlmCallStore` | 自定义 Bean |
| `ToolExecutor` | `SequentialToolExecutor` | 自定义同类型 Bean |
| `aiLiteRetryScheduler` | 2 个 daemon 线程 | 同名 Bean |

自定义协议：实现 `LlmTransport`，`api()` 返回新的协议名，并在供应商配置里使用该 `api`。
注意不要与内置协议重名；同名但不同 Bean 名会在 `LlmGateway` 构造时报“协议适配器重复”。

生命周期：

- `HttpExecutor.close()`：Starter 创建的执行器由容器关闭；用户 Bean 由用户自行管理。
- `ExecutorProvider.close()`：关闭阻塞任务线程池。
- `aiLiteRetryScheduler`：默认 Bean 在容器关闭时 `shutdownNow`；用户同名 Bean 不会被网关关闭。
- 网关不会关闭注入的调度器或 HTTP 客户端。

---

## 5. 线程模型

| 线程 | 谁在用 | 注意 |
| --- | --- | --- |
| 调用线程 | `one` / `oneByStream` / `oneWithRetry` | 同步入口会阻塞到这里 |
| HTTP 回调线程 | OkHttp Dispatcher / Jetty / Netty / Ktor | 完成 future；异步 `onDelta` 也在这里 |
| `ai-lite-retry-*` | `oneWithRetryAsync` 的退避与下一次尝试 | 监听器也在这里，必须轻量 |
| `ExecutorProvider.io()` | 调用记录落库、工具、会话、协程阻塞任务 | 不承载模型网络等待 |

排查问题时先确认代码运行在哪个线程上。用户回调和 Agent 钩子的线程归属属于独立的
回调执行策略，不在本层解决。

---

## 6. 调用记录

每次单次调用写一条 `LlmCallRecord`；字段含义、异步写入线程、重试计费语义见 [单次模型调用入口](desc-单次调用.md) §10.2。默认 `NoopLlmCallStore` 不落库；需要持久化时提供 `LlmCallStore` Bean：

```java
@Bean
public LlmCallStore llmCallStore(JdbcTemplate jdbc) {
    return new JdbcLlmCallStore(jdbc);   // 需要 ai-lite-store-jdbc
}
```

表结构见 `ai-lite-store-jdbc/src/main/resources/ai-lite-llm-call.sql`。

---

## 7. 常见问题

**启动报“需要 ai-lite-http-xxx 提供 HttpExecutor”**
配了 `ai-lite.http.transport`，但没有引入对应模块。加依赖，或改回 `okhttp`。

**启动日志说传输被自定义 Bean 覆盖**
容器里存在用户定义的 `HttpExecutor` Bean。删除它，或接受覆盖并忽略 `ai-lite.http.transport`。

**调用报“业务 id 未登记”**
`LlmCall` 的 `bizId` 不在 `ai-lite.llm.biz-ids` 里。

**调用报“未知模型: null”**
只给了 provider，没有给 model；显式调用必须同时给两者，或改用 profile。

**配了 `default-profile` 仍报“未知配置档”**
`default-profile` 指向的 profile 不存在；启动期只校验 profile 内部引用，不校验默认值。

**`oneAsync` 并没有减少线程**
它只释放调用线程。OkHttp Dispatcher 仍按在途请求占线程；要减少平台线程，考虑
`okhttp-virtual`、Jetty、WebClient 或 Ktor。

**自定义 `HttpExecutor` 后异步变慢/线程暴涨**
只实现了同步接口，异步退化为 `ForkJoinPool.commonPool`；实现 `AsyncHttpExecutor`。

**取消后请求还在跑**
确认使用的是支持 abort 的传输；JDK 8 在正文读取阶段可能等到读超时。L2（不再推进）
仍然保证。

**重试后账单变多**
每次尝试都是真实请求，也会各写一条调用记录。这是显式重试的预期成本。

**流式回调里写库导致吞吐下降**
`onDelta` 在 HTTP 线程上执行；把重活转交业务线程池或消息队列。

---

## 8. 不在本文范围

- 一次调用的内部旅程（线程接力、取消分层、错误分类、出口保护）→ [单次模型调用入口](desc-单次调用.md)；
- 重试策略细节 → [重试边界](desc-重试.md)；
- `AgentLoop`、`Workflow`、`AgentSessionLoop`、会话持久化、提示词中心 → 编排层文档；
- 执行器/协程/虚拟线程的实现原理 → [用户级线程实现说明](desc-用户级线程.md)；
- 模型路由、供应商降级、自动故障转移（框架暂未提供，需在网关之上实现）。

---

## 9. 相关文档

- [术语表](ref-术语表.md)
- [上层调用API使用指南](guide-上层调用API.md)（入门与全层 API 字典）
- [单次模型调用入口](desc-单次调用.md)
- [重试边界](desc-重试.md)
- [配置参考](ref-配置.md)
- [架构说明](desc-架构总览.md)
- [HTTP 传输并发模型评估](plan/HTTP传输并发选型评估.md)
