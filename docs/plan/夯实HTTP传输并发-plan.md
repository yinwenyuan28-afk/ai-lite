# 夯实 HTTP 传输并发（B/C 双路径）

**状态（2026-10-22）：Phase 0–5 已实现，Phase 6 文档已补齐。** 配置选择、`okhttp-virtual`（JDK 21）、`ai-lite-http-jetty`、`ai-lite-http-webclient`、`ai-lite-http-ktor-cio` 及对应测试、并发基准均已落地。本计划只改 HTTP 传输层与 Starter 装配，不改 `LlmGateway`、`Workflow`、`AgentLoop`、`AgentSessionLoop` 的公开 API。

一句话总纲：

> **默认行为不变；用户通过 `ai-lite.http.transport` 显式选择 `okhttp`（默认）、`okhttp-virtual`（JDK 21）、`jetty`（跨 Boot 2.1/2.7 的 NIO 主路径）、`webclient`（WebFlux 可选）或 `ktor-cio`（Kotlin 可选）。缺失模块或环境不满足时启动即失败。**

---

## 0. 调研结论（先看这个）

| 方案 | 能否一个模块跨 Boot 2.1/2.7 | 结论 |
| --- | --- | --- |
| **Jetty 9.4 HttpClient** | **能**：Boot 的 `jetty-bom` 在 2.1 管 9.4.22、2.7 管 9.4.53 | **C 的主路径**：无 Netty/Reactor，约 1.35MB，NIO，实现最简单 |
| **Spring WebClient 5.1 API** | **能**：同一份字节码在 Boot 2.1/2.7 探针通过 | **可选 C2**：真实的响应式方案，但需 spring-webflux；Boot 2.1 的 RN 0.8 有“连接重置自动重试一次”的重复 POST 风险 |
| **Ktor CIO 2.3.13** | **能**：JDK 8 编译通过 | **可选 C3**：真 NIO + 协程，无 Netty/Reactor；但需覆盖 Kotlin 1.9.25/coroutines 1.7.3，Ktor 2.x 已 EOL |
| Reactor Netty 1.0 | 不能：需要 reactor-core 3.4 / Netty 4.1.101 | **放弃作为 C 主路径**；Boot 2.1 不可用 |
| 直接 Reactor Netty 0.8 | 不能：与 1.0 API 二进制不兼容 | 不维护两套适配器 |
| Vert.x / AsyncHttpClient | 不能：Netty 版本被 Boot 降级 | 不推荐 |
| 自己写 JDK NIO | 能，但 TLS/SSE/连接池/取消全部自研 | 不推荐 |

**工程决策：**

- **C 默认做 Jetty**，因为它同时满足 Boot 2.1/2.7、JDK 8、无 Reactor/Netty 版本冲突、实现量最小。
- **WebClient 作为可选 C2**，给已经使用 WebFlux 的项目；文档必须写明 Boot 2.1 的 RN 0.8 重复 POST 风险和 `exchange()` 的兼容边界。
- **Ktor CIO 作为可选 C3**，给 Kotlin 用户与高并发流式场景；不进入默认依赖。
- **不再把 Reactor Netty 1.0 作为 C**；它的 Boot 2.1 不兼容问题无法用“一个模块”解决。

---

## 1. 背景与目标

### 1.1 要解决的问题

当前 OkHttp `enqueue` 只释放调用线程；每个在途请求仍占一个 OkHttp Dispatcher 线程，流式请求占至流结束。默认 `maxRequests=64`、`maxRequestsPerHost=5`。

```text
调用线程    → enqueue 后释放
Dispatcher  → 每个在途请求一个平台线程
io()        → 工具、日志、会话落盘，不参与模型 HTTP
```

目标：

```text
B  okhttp-virtual → 虚拟线程 Dispatcher；JDK 21；不改 OkHttp 语义
C  NIO 传输       → 少量 event loop/selector 线程承载大量连接
```

### 1.2 本次范围

- `AiLiteConfig.Http` 配置模型。
- Starter 的传输选择、反射装配、启动日志、fail-fast。
- 新增 `ai-lite-http-okhttp-jdk21`（B）。
- 新增 `ai-lite-http-jetty`（C1，主路径）。
- 可选：`ai-lite-http-webclient`（C2）、`ai-lite-http-ktor-cio`（C3）。
- 配置、测试、文档、基准。

### 1.3 不在本次范围

- 改 `LlmGateway` / `Workflow` / `AgentLoop` / `AgentSessionLoop` 的公开签名。
- 把 `Mono` / `Flux` / `suspend` 暴露到公开 API。
- 默认引入 Netty/Reactor/Jetty/Ktor 依赖。
- 改写 SSE/JSON 协议层；`AsyncHttpExecutor` 仍只负责行回调和完整响应体。
- 自动重试、限流、熔断（已有 `oneWithRetry`）。
- 代理、客户端证书、HTTP/2 的完整 parity（第一版给出明确限制）。

### 1.4 兼容矩阵

| transport | JDK | Spring Boot | 依赖 | 状态 |
| --- | --- | --- | --- | --- |
| `okhttp` | 8+ | 2.1 / 2.7 | `ai-lite-http-okhttp` | 默认 |
| `okhttp-virtual` | 21+ | 2.7 官方；2.1 best-effort | `ai-lite-http-okhttp-jdk21` | B |
| `jetty` | 8+ | 2.1 / 2.7 | `ai-lite-http-jetty` + Boot 管理的 Jetty 9.4 | C1 主路径；9.4 已 EOL/CVE 见 §7 |
| `webclient` | 8+ | 2.1 / 2.7 | `ai-lite-http-webclient` + spring-webflux + **显式 reactor-netty** | C2 可选 |
| `ktor-cio` | 8+ | 2.1 / 2.7 | `ai-lite-http-ktor-cio` + Ktor 2.3.13 / Kotlin 1.9.25 / coroutines 1.7.3 | C3 可选；需覆盖消费者 BOM 的 Kotlin/coroutines |
| `jdk8` | 8+ | 2.1 / 2.7 | `ai-lite-http-jdk8` | 零依赖备选 |

> Boot 2.1 + JDK 21 不是 Spring Boot 官方支持组合；`okhttp-virtual` 在该组合下只标 best-effort。Jetty 9.4 已 EOL，安全风险与替代方案见 §7。

---

## 2. 配置契约

### 2.1 配置示例

```yaml
ai-lite:
  http:
    transport: okhttp          # okhttp | okhttp-virtual | jetty | webclient | ktor-cio | jdk8
    dispatcher:
      max-requests: 64
      max-requests-per-host: 5
    jetty:
      max-connections-per-destination: 64
      threads: 0               # 0 = Jetty 默认
    ktor:
      max-connections: 1000
      max-connections-per-route: 100
      pipelining: true
```

数值字段默认 `null`，在工厂里套默认值，用来判断“用户是否显式设置”。

### 2.2 选择语义

- 默认 `okhttp`，现有应用行为不变。
- 选择未引入的模块 → 启动失败，消息列出缺少的依赖和可用选项。
- 选择未知值且没有自定义 `HttpExecutor` Bean → 启动失败，列出允许值。
- 用户自定义 `HttpExecutor` / `AsyncHttpExecutor` Bean 仍然优先；`transport` 只打 INFO/WARN。
- 启动日志打印实际 `HttpExecutor` 类型、`transport`、是否被自定义 Bean 覆盖。
- `dispatcher.*` 只对 OkHttp 变体生效；`jetty.*` 只对 Jetty；`ktor.*` 只对 Ktor；`webclient` 的连接池由应用的 `ReactorResourceFactory` 决定。不适用的显式配置打 WARN。
- 用户回调与 Agent 钩子的线程策略不在本计划：见 [夯实回调执行策略](夯实回调执行策略-plan.md)。本计划只保证“传输层内部解析 inline、完成线程不被内部逻辑长时间占用”。
- `jdk8` 要真正不引入 OkHttp，需要从 Starter 排除 `ai-lite-http-okhttp`，再引入 `ai-lite-http-jdk8`。

### 2.3 与执行器配置的关系

```text
ai-lite.executor.mode = platform | direct | virtual | coroutines
ai-lite.http.transport = okhttp | okhttp-virtual | jetty | webclient | ktor-cio | jdk8
```

两者独立：`executor.mode` 管 `io()`（工具/日志/会话），`http.transport` 管模型 HTTP。`executor.mode=virtual` 不会让 OkHttp 用虚拟线程。

---

## 3. 模块设计

### 3.1 B：`ai-lite-http-okhttp-jdk21`

```text
ai-lite-http-okhttp-jdk21
  ├─ 依赖 ai-lite-http-okhttp
  ├─ JDK 21 编译（release=21）
  ├─ 根 pom 的 jdk21 profile 引入
  └─ 提供 VirtualThreadOkHttpFactory
```

先在 `ai-lite-http-okhttp` 抽公共的 `OkHttpClientFactory`：

```text
OkHttpClientFactory.create(AiLiteConfig.Http config, ExecutorService dispatcherExecutor)
  ├─ dispatcherExecutor == null → OkHttp 默认 Dispatcher
  ├─ 否则 new Dispatcher(executor)
  ├─ maxRequests / maxRequestsPerHost 默认 64/5
  └─ 保留 retryOnConnectionFailure(false)
```

B 的工厂：

```java
ThreadFactory threads = Thread.ofVirtual()
        .name("ai-lite-http-virtual-", 0)
        .factory();
ExecutorService executor = Executors.newThreadPerTaskExecutor(threads);

Dispatcher dispatcher = new Dispatcher(executor);
dispatcher.setMaxRequests(cfg.getDispatcher().maxRequestsOr(64));
dispatcher.setMaxRequestsPerHost(cfg.getDispatcher().maxRequestsPerHostOr(5));

OkHttpClient client = new OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .retryOnConnectionFailure(false)
        .build();

return new OkHttpExecutor(client);
```

要点：

- 只有 async 路径使用虚拟线程；同步 `one`/`oneWithRetry` 仍在调用线程上阻塞。
- `OkHttpExecutor.close()` 会 shutdown dispatcher executor，但不会中断在途 SSE。
- JDK 21–23 的 `synchronized` 可能 pin 载体；文档提示优先 JDK 24+。
- `maxRequestsPerHost` 仍限流；虚拟线程不提高供应商配额。

### 3.2 C1 主路径：`ai-lite-http-jetty`

```text
ai-lite-http-jetty
  ├─ 依赖 ai-lite-core
  ├─ jetty-client:9.4.22.v20191022（按 Boot 2.1 基线编译）
  ├─ 运行时可被 Boot 升到 9.4.53（Boot 2.7）
  ├─ 不引入 Netty / Reactor / Jackson
  └─ 实现 AsyncHttpExecutor
```

为什么选它：

- Boot 2.1 和 2.7 都通过 `jetty-bom` 管理 `jetty-client/http/io/util`，一个模块不需要覆盖版本。
- 无 Netty/Reactor 版本冲突；约 1.35MB。
- NIO selector + 连接池；`Request.abort` 真正中断连接。

实现职责：

| 方法 | 实现 |
| --- | --- |
| `execute` | `request.send()` → `ContentResponse`；读 status/body |
| `executeStreaming` | `InputStreamResponseListener` + `BufferedReader(UTF_8)`；同步阻塞调用线程 |
| `executeAsync` | `request.send(Response.Listener.Adapter)`；`onBegin` 先取 status；2xx 的 `onContent(ByteBuffer)` 喂行缓冲；非 2xx 收集错误体后失败 |
| `executeStreamingAsync` | 同上，2xx 时逐行回调 `StreamListener` |
| 生命周期 | 创建后立即 `client.start()`；`close()` 遍历缓存并 `stop()` 所有自己创建的 client；幂等 |

关键实现点：

1. **生命周期**：`HttpClient` 创建后必须 `start()`；未 start 会报 Not started。`stop()` 抛 checked Exception，`close()` 内包装为运行时异常；缓存的每个 client 都要 start/stop。
2. **UTF-8 行缓冲**：按字节切 `\n`/`\r`/`\r\n`，只对完整行解码；`\r\n` 跨 chunk 用 pending CR；空行回调 `""`；末尾无换行时 flush；单行上限（如 1MB）防止 OOM。`onContent` 的 ByteBuffer 在回调返回后可复用，必须同步复制字节，不能持有引用。
3. **超时**：
   - `HttpRequest.connectTimeoutMs` 是每请求的，但 Jetty connect timeout 是 `HttpClient` 级；优先单 client + 固定 timeout，确需缓存时共享线程池/selector、限制数量并压测。
   - 非流式和流式都用 `Request.idleTimeout(readTimeoutMs)`（连接无流量空闲）；`0` 表示不设。
   - 不要用 `Request.timeout(total)` 约束长响应/SSE，避免总时长误杀；默认 `Request.timeout(0)`。
4. **取消**：预取消必须在 `send()` 前显式检查 `cancel.isCancelled()`（`abort` 在 send 前没有 exchange，是 no-op）；`future.cancel(true)` → `request.abort`；`bindAbort` 完成时解绑；`AtomicBoolean` 单终态。
5. **非 2xx**：`onBegin` 先取 status；错误体有界读取（如 64KB）并 drain，消息截断 300 字符，`Kind.HTTP` + statusCode；错误体不能进入 `onLine`。
6. **提前 EOF**：Jetty `earlyEOF()` 是 `EOFException`，应按 `Kind.TRANSPORT` 失败，不能静默当正常结束；取消时按 `CANCELLED`。
7. **gzip/重定向/HTTP2**：SSE 请求加 `Accept-Encoding: identity`，避免 gzip 缓冲；Jetty 9.4 默认 HTTP/1.1；默认关闭重定向（LLM 端点不应跟随），不用 proxy auth/Basic auth。
8. **线程**：Jetty selector/线程池回调；不占调用线程；同步方法仍阻塞调用线程（预期）。`jetty.threads=0` 表示不设置 executor，使用 Jetty 默认，不能 `new QueuedThreadPool(0)`。
9. **close**：只停自己的 `HttpClient`，不影响用户应用的其他 Jetty client；如果共享外部 executor，`stop()` 可能把它当 managed bean 停掉，需明确所有权。
10. **安全**：Jetty 9.4 已 EOL；OSS 最新 9.4.58，2026 advisory 的修复版 9.4.64/65 不在 Maven Central。文档如实标注；默认关闭重定向、不用 proxy auth/Basic auth 规避主要攻击面；安全硬要求时评估 Apache HttpClient 5 async。
11. **SSE 解析**：core 的 `SseCollector` 是包私有，Jetty 模块不能复用；executor 只负责 `Utf8LineBuffer` 交出原始行，SSE 事件拼装仍在 core transport。

装配：`transport=jetty` → 反射 `JettyHttpExecutor`；缺模块 fail-fast。

### 3.3 C2 可选：`ai-lite-http-webclient`

适用：已经使用 Spring WebFlux，想用响应式栈；MVC 应用会增加约 6–8MB 依赖。

```text
ai-lite-http-webclient
  ├─ 依赖 ai-lite-core + spring-webflux
  ├─ 按 Spring WebFlux 5.1.11 API 编译（Boot 2.1 基线）
  ├─ 显式依赖 reactor-netty（spring-webflux 5.1 的 RN 是 optional，5.3 不再依赖）
  ├─ 源码零 reactor.netty.* 引用；连接器用 ReactorClientHttpConnector/ReactorResourceFactory
  └─ 实现 AsyncHttpExecutor
```

跨版本事实（已做双运行时探针）：

- 同一份 5.1 API 字节码在 Boot 2.1.10（WebFlux 5.1.11 / RN 0.8.13）和 Boot 2.7.18（WebFlux 5.3.31 / RN 1.0.39）都能链接运行。
- 禁止使用 5.2+/5.3 API：`bodyValue`、`exchangeToMono`、`ClientResponse.createException`、`exchangeStrategies(Consumer)`、`codecs(...)`。
- `exchange()` 在 5.3 已废弃；第一版接受，未来 Boot 3 需要重写。

实现要点：

1. **依赖**：Boot BOM 只管理版本，不添加 artifact；模块必须显式声明 `io.projectreactor.netty:reactor-netty`（或要求 `spring-boot-starter-webflux`），并排除 QUIC/Brave 可选依赖。
2. **SSE 必须拿原始字节**：`body(BodyExtractors.toDataBuffers())` + `SseLineDecoder`；不能用 `bodyToFlux(String)` / `ServerSentEvent`，它们会丢掉空行和 `data:` 前缀。
3. **UTF-8 跨 chunk**：增量 `CharsetDecoder`，保留未消费尾部字节；`\r\n` 跨 chunk、空行、`[DONE]`、末行无换行都要测试。
4. **超时**：`exchange().timeout(connectTimeout)` 覆盖连接/响应头；body `Flux.timeout(readTimeout)` 覆盖读空闲。`connectTimeout=0`/`readTimeout=0` 时跳过 `.timeout`（`Duration.ZERO` 会立即失败）。不要依赖 RN 的 `responseTimeout`（0.8 不存在）。
5. **取消**：`Disposable.dispose()`；`doFinally(CANCEL)` 显式完成 `CANCELLED`；`future.cancel` 与 handle 都要 dispose。
6. **非 2xx**：`exchange()` 分支先读错误体，再 `Flux.error`；错误体不能进入行回调；所有分支都要消费/释放 `DataBuffer`，5.1 没有 `releaseBody()`。
7. **错误分类**：不能 import 5.2+ 的 `WebClientRequestException`；遍历 cause chain 识别 IO/超时，按 `TRANSPORT`；非 2xx 按 `HTTP`。
8. **Boot 2.1 的 P0 风险**：Reactor Netty 0.8 在连接重置时会自动重试一次，且没有 `disableRetry`；已发出 body 的 POST 可能重复。对 LLM 意味着重复计费。文档必须写明；对重复计费敏感的用户改用 Jetty/OkHttp。
9. **资源生命周期**：默认接收应用提供的 `WebClient`，`close()` no-op；如果自建，使用 `ReactorResourceFactory(useGlobalResources=false)`，先 `afterPropertiesSet()` 再建 connector，close 里 `destroy()`，绝不动全局 `HttpResources`。
10. **验证**：双 Boot 探针必须提交为可执行测试/CI job，而不只是文档结论。

### 3.4 C3 可选：`ai-lite-http-ktor-cio`

适用：Kotlin 用户、JDK 8、Boot 2.1/2.7、高并发流式；不进入默认依赖。

```text
ai-lite-http-ktor-cio
  ├─ 依赖 ai-lite-core
  ├─ Kotlin 1.9.25 / coroutines 1.7.3
  ├─ Ktor 2.3.13（CIO engine）
  ├─ JDK 8 编译
  └─ 实现 AsyncHttpExecutor
```

版本约束：

- Ktor 2.3.13 需要 Kotlin ≥1.8.22、coroutines ≥1.7.1；Boot 2.1 的 Kotlin 1.2.71、Boot 2.7 的 Kotlin 1.6.21/coroutines 1.6.4 都必须显式覆盖。
- Ktor 3.x 需要 Kotlin 2.x metadata，当前项目编译不了；2.x 已 EOL。
- 增量依赖约 3.2MB（不含已有 Kotlin/coroutines），无 Netty/Reactor。

实现要点：

1. **SSE 原始行**：`response.bodyAsChannel().readUTF8Line(limit)`（设置单行上限，如 1MB）；空行返回 `""`，`data:` 和 `[DONE]` 原样保留；不要用 SSE 插件（2.3 没有；3.x 插件会丢原始格式）。
2. **UTF-8 跨 chunk**：`readUTF8Line()` 内部保留未完成字节，正确。
3. **超时**：长 SSE 必须用 **engine 级** `engine { requestTimeout = 0 }`；`HttpTimeout` 插件的 setter 要求 >0，只能用 `INFINITE_TIMEOUT_MS`，不能传 0。`socketTimeout = readTimeoutMs`（0 表示无限）；`connectTimeout = connectTimeoutMs`。
4. **连接复用**：CIO 默认 `pipelining=false`，每请求新建连接；设 `pipelining=true`，并在 `endpoint { pipelineMaxSize = 1 }` 复用连接，避免真正的 HTTP pipelining。
5. **取消**：`CompletableFuture.cancel(true)` 不会自动取消协程 Job；需要显式 `job.cancel()`。`bindAbort` 放在 future/job 创建之后，用原子引用处理预取消竞态；完成时解绑。
6. **非 2xx**：`expectSuccess=false`；先判 status，再读错误体，截断 300 字符。
7. **线程**：CIO selector 使用 `Dispatchers.IO`；DNS 解析也可能占用 IO 线程；应用把 IO 池打满会影响 selector；文档提示。
8. **close**：关闭自己的 `HttpClient` 和 `CoroutineScope`；如果注入共享 client，明确所有权；`close()` 幂等。

### 3.5 不把响应式/协程类型暴露到公开 API

```text
LlmGateway / Workflow / AgentLoop 仍然只看 CompletableFuture / suspend
WebClient 的 Mono/Flux、Ktor 的 suspend 只存在于传输模块内部
```

核心模块保持 JDK 8 + 无 Reactor/Ktor 依赖；不引入对应模块时 jar 和 API 完全不变。

### 3.6 回调执行策略不在本计划

用户 `onDelta`、`StreamListener`、AgentLifecycle 钩子的线程归属、串行执行器、队列/取消策略、`afterRun` 终态收敛，已经拆到独立计划：

- [夯实回调执行策略](夯实回调执行策略-plan.md)

本计划只负责：

```text
传输层内部解析 / SSE 行缓冲 / 网关状态机 → 完成线程 inline
用户回调/钩子 → 由回调执行策略计划负责
```

HTTP 计划与回调计划可以独立实施；不引入 NIO 传输时，OkHttp 默认路径同样适用回调策略。

---

## 4. 实施阶段

### Phase 0：配置与选择骨架（P0）

- `AiLiteConfig` 增加 `Http`、`Dispatcher`、`Jetty`、`Ktor` 嵌套配置；数值字段 `Integer`/`Long` 且默认 `null`。
- 重构 `AiLiteAutoConfiguration.aiLiteHttpExecutor(AiLiteConfig, Environment)`：
  - 移除聚合方法上的 `@ConditionalOnClass(OkHttpExecutor)`。
  - 聚合方法不再直接 `new OkHttpExecutor()`；所有 transport 通过反射加载，避免可选模块排除后的类加载问题。
  - 反射用当前线程 classloader，捕获 `ClassNotFoundException` / `LinkageError`。
  - 按 transport 分支：`okhttp` / `jdk8` / `okhttp-virtual` / `jetty` / `webclient` / `ktor-cio`。
  - 无自定义 Bean 时未知值/缺模块 fail-fast；有自定义 Bean 时只 WARN。
- 启动日志打印实际 transport 和 `HttpExecutor` 类型。
- `ai-lite-http-okhttp` 新增 `OkHttpClientFactory`，默认 64/5 + `retryOnConnectionFailure(false)`。
- 测试：配置绑定、默认值、每种 transport、缺模块、未知值、用户 Bean 覆盖、默认 okhttp 回归。

### Phase 1：B（JDK 21 虚拟线程 Dispatcher）（P1）

- 新增 `ai-lite-http-okhttp-jdk21` 与 jdk21 profile。
- `VirtualThreadOkHttpFactory`：命名虚拟线程 + 自定义 `Dispatcher`。
- 测试：async 非流式/流式/取消、Dispatcher 限制、`Thread.isVirtual()`；同步路径不受益。
- 文档：pinning、`maxRequestsPerHost` 仍限流。

### Phase 2：C1 Jetty（主路径，P0）

- 新增 `ai-lite-http-jetty`，按 Jetty 9.4.22 API 编译。
- `JettyHttpExecutor` + `Utf8LineBuffer`。
- 同步/异步/流式/取消/超时/非 2xx/close。
- 测试：SSE 跨 chunk、UTF-8 中文/emoji、CRLF、空行、多行 data、`[DONE]`、末行无换行、提前 EOF、错误体、取消、连接超时缓存、close。
- 与 OkHttp parity：请求体/headers/status/错误 Kind。
- 文档：connect timeout 全局/缓存、Jetty 9.4 CVE 提示、重定向/gzip 行为。

### Phase 3：C2 WebClient（可选，P1）

- 新增 `ai-lite-http-webclient`，按 WebFlux 5.1.11 API 编译。
- 显式依赖 `reactor-netty`（Boot BOM 只管理版本，spring-webflux 5.1 的 RN 是 optional，5.3 不再依赖）。
- `SseLineDecoder` + `DataBuffer` 释放 + 取消 dispose；`connect/read timeout=0` 时跳过 `.timeout(...)`。
- 双 Boot 探针测试：2.1.10 和 2.7.18 下同一字节码的 SSE/错误体/取消。
- 文档：RN 0.8 重复 POST 风险、`exchange()` 5.3 废弃、MVC 应用依赖体积。

### Phase 4：C3 Ktor CIO（可选，P1）

- 新增 `ai-lite-http-ktor-cio`，Ktor 2.3.13 / Kotlin 1.9.25。
- `KtorCioHttpExecutor`：`readUTF8Line(limit)`、`engine { requestTimeout = 0 }`、`pipelining`、取消。
- `pipelineMaxSize` 配置在 `endpoint { }`；`HttpTimeout` 插件不要传 0，用 `INFINITE_TIMEOUT_MS`。
- 测试：SSE 原始行、UTF-8、长 SSE 不被 15s 掐断、连接复用、取消、close。
- 文档：Kotlin/coroutines 版本覆盖、Ktor 2.x EOL、`Dispatchers.IO`/DNS 风险。

### Phase 5：并发基准（P1）

- 本地 fake LLM server：非流式/流式，100/500/1000 并发。
- 对比：okhttp 默认、okhttp 调参、okhttp-virtual、jetty、webclient、ktor-cio。
- 指标：平台线程、虚拟线程（JFR/工厂计数）、RSS、p95、吞吐、错误率、取消释放。
- 输出推荐矩阵。

### Phase 6：文档与发布（P2）

- `docs/配置说明.md`：`ai-lite.http.*` 字段、默认值、适用 transport、兼容矩阵。
- `docs/用户级线程实现说明.md`：各传输线程模型。
- `docs/plan/HTTP传输并发选型评估.md`：更新为“已调研 + 选型结论”，删除残留的“优先 Reactor Netty”旧建议。
- `README.md`：模块表、配置示例、exclusion、兼容矩阵。
- 发布说明：Jetty 主路径、WebClient/Ktor 可选、Boot 2.1 legacy。

---

## 5. 测试矩阵

| 类别 | 用例 | 断言 |
| --- | --- | --- |
| 配置 | 默认 | okhttp，64/5，`retryOnConnectionFailure(false)` |
| 配置 | 每种 transport | 正确装配；缺模块/未知值 fail-fast |
| 配置 | 自定义 Bean | 用户 Bean 优先，transport 只 WARN |
| 配置 | 不适用配置 | 显式设置时 WARN |
| 配置 | jdk8 + 排除 okhttp | 能装配，且显式 `transport=jdk8` |
| B | async 非流式/流式/取消 | 成功；`Thread.isVirtual()` |
| B | 同步 `one` | 不走虚拟线程 |
| B | Boot 2.7 + JDK21 | 官方支持组合；Boot 2.1 + JDK21 标 best-effort |
| Jetty | 生命周期 | 未 `start()` 发送失败；start 后成功；close 幂等 |
| Jetty | 非流式 | status/body 正确；请求 headers 由假服务端断言 |
| Jetty | SSE | 空行、`data:`、`[DONE]`、多行 data 原样 |
| Jetty | UTF-8 跨 chunk | 中文/emoji 不乱码；ByteBuffer 同步复制 |
| Jetty | CRLF 切开/末行无换行 | 行正确 |
| Jetty | 提前 EOF | `Kind.TRANSPORT`，不静默当正常结束 |
| Jetty | 非 2xx | `HTTP` + status，错误体不进 onLine，截断 300 |
| Jetty | 取消 | 预取消零请求；future/handle 都 abort |
| Jetty | 超时 | `idleTimeout` 为读空闲；`Request.timeout(0)`；connect 缓存不无限增长 |
| Jetty | close | 幂等；遍历缓存 stop 所有自己的 client |
| Jetty | parity | 假服务端收到的请求 body/headers 与 OkHttp 一致；status/Kind 一致 |
| WebClient | Boot 2.1/2.7 双运行 | 同一字节码链接通过；显式 reactor-netty 依赖 |
| WebClient | SSE 原始行 | DataBuffer + decoder，空行/`data:`/`[DONE]` 正确 |
| WebClient | 非 2xx | 错误体先读，后抛 HTTP；DataBuffer 全部 release |
| WebClient | 取消 | dispose，future CANCELLED；timeout=0 时跳过 `.timeout` |
| WebClient | RN 0.8 重试 | 连接重置场景记录请求次数（预期风险可见） |
| Ktor | SSE 原始行 | `readUTF8Line(limit)` 保留空行/`data:`/`[DONE]` |
| Ktor | 长 SSE | `engine { requestTimeout = 0 }` 不被 15s 掐断 |
| Ktor | 连接复用 | `pipelining=true`，`endpoint { pipelineMaxSize = 1 }` |
| Ktor | 取消 | future.cancel 显式桥接 Job → socket 关闭 |
| 基准 | 并发/流式 | 线程数、RSS、p95、吞吐 |

所有异步断言带 5 秒超时；测试使用本地 server，不依赖外网。

---

## 6. 验收标准

- 默认 `okhttp` 行为不变。
- `transport` 六种取值可显式选择；缺模块/未知值启动即失败。
- B 在 JDK 21 下 async 路径使用虚拟线程；Boot 2.7 + JDK21 为官方支持组合，Boot 2.1 + JDK21 标 best-effort。
- Jetty 在 Boot 2.1/2.7、JDK 8 下通过生命周期（start/close）、同步/异步/流式/取消/超时/错误分类。
- Jetty 的 early EOF 按 `TRANSPORT` 处理；`onContent` 的 ByteBuffer 在回调内同步复制。
- WebClient 同一字节码在 Boot 2.1/2.7 探针通过；显式依赖 reactor-netty；RN 0.8 重复 POST 风险文档化。
- Ktor 在 Boot 2.1/2.7 下 SSE/取消/连接复用通过；`engine { requestTimeout = 0 }`；Kotlin/coroutines 版本覆盖文档化。
- 公开 API 与核心模块签名不变；不引入模块时 jar 不变。
- 回调执行策略不在本计划验收范围，见 [夯实回调执行策略](夯实回调执行策略-plan.md)。
- 文档、配置表、兼容矩阵、基准结论齐全。

---

## 7. 风险与待决策

| 风险/决策 | 说明 | 建议 |
| --- | --- | --- |
| Jetty 9.4 EOL/CVE | OSS 最新 9.4.58；2026-09/10 advisory 影响 9.4.0–9.4.64，修复版 9.4.64/65 不在 Maven Central | 文档如实标注；默认关闭重定向、不用 proxy auth/Basic auth；安全硬要求时评估 Apache HttpClient 5 async |
| Jetty 安全替代 | Apache HttpClient 5.6 维护活跃、Java 8、NIO、per-request timeout、无 Netty | 做 time-box spike，作为 Jetty 的安全替代候选；不先实现 |
| Jetty 生命周期 | 缺 `start()` 会在第一次请求报 Not started；`stop()` 抛 checked | 创建后 start；close 遍历缓存 stop 所有自己创建的 client；幂等 |
| Jetty connect timeout 全局 | 每请求 connectTimeout 需要多 client | 优先单 client + 固定 timeout；确需缓存时共享线程池/selector，限制数量并压测 |
| Jetty 空闲语义 | `idleTimeout` 是连接无流量空闲，不是严格读空闲 | 非流式也用 idleTimeout；不要用 `Request.timeout(total)` 误杀长响应 |
| Jetty ByteBuffer 生命周期 | `onContent` 的 buffer 在回调返回后可复用 | 只同步复制字节，不持有引用 |
| Jetty 预取消 | `abort` 在 send 前没有 exchange，是 no-op | send 前显式检查 `cancel.isCancelled()`，已取消直接返回 CANCELLED |
| Jetty early EOF | `earlyEOF()` 是 `EOFException`，不是正常结束 | 按 `TRANSPORT` 处理；如要容忍，显式配置并文档化 |
| Jetty SSE | core 的 `SseCollector` 是包私有，不能跨模块复用 | Jetty 只负责 `Utf8LineBuffer` 交行；SSE 事件拼装仍在 core transport |
| WebClient 依赖体积 | MVC 应用 +6–8MB | 仅作为 WebFlux 用户的可选 C2 |
| WebClient reactor-netty | spring-webflux 5.1 的 RN 是 optional，5.3 不再依赖 | 模块显式声明 `reactor-netty`（或要求 starter-webflux）；BOM 只管理版本 |
| WebClient RN 0.8 重复 POST | 连接重置自动重试一次，无 `disableRetry` | 文档警告；敏感场景用 Jetty/OkHttp |
| WebClient `exchange()` 废弃 | 5.3 起弃用，Boot 3 移除 | 第一版接受；未来重写 |
| WebClient timeout=0 | `.timeout(Duration.ZERO)` 会立即失败 | connect/read timeout=0 时跳过 `.timeout` |
| Ktor 2.x EOL | 2.3.13 是末版；3.x 需 Kotlin 2.x | 文档标注；不进入默认依赖 |
| Ktor Kotlin 版本覆盖 | Boot 2.1 Kotlin 1.2.71；Boot 2.7 Kotlin 1.6.21/coroutines 1.6.4 | 文档/示例显式覆盖；或提供 BOM |
| Ktor requestTimeout=0 | `HttpTimeout` 插件 setter 要求 >0；只有 engine 级 `requestTimeout` 允许 0 | 写死 `engine { requestTimeout = 0 }`；插件用 `INFINITE_TIMEOUT_MS` |
| Ktor `Dispatchers.IO` 饱和 | selector、协程、DNS 可能共用 IO 池 | 压测观察；文档提示 |
| Boot 2.1 + JDK 21 | 不是 Spring Boot 官方支持组合 | B 在 Boot 2.1 + JDK21 标 best-effort；只承诺 Boot 2.7 + JDK21 |
| 测试类路径隔离 | 缺模块与成功装配互相干扰 | 缺模块测试放 Starter；成功测试放各自模块 |
| 虚拟线程计数 | `Thread.getAllStackTraces()` 不含虚拟线程 | JFR 或工厂计数 |
| jdk8 迁移 | 排除 okhttp 后不显式设 transport 会默认 okhttp 并失败 | 文档要求同时设 `transport=jdk8` |
| Boot 2.1 EOL | 依赖无 OSS 安全维护 | README 兼容矩阵标注 legacy，建议升级 |

---

## 8. 与现有计划的关系

- 本计划取代 `docs/plan/HTTP传输并发选型评估.md` 中的“待评估”部分；评估文档保留为背景与基准依据（已与本计划同目录留档）。
- 不影响 `夯实oneWithRetry-plan.md`：重试入口不变，传输实现可替换。
- 不影响 Workflow / AgentLoop 计划：它们只依赖 `HttpExecutor`/`AsyncHttpExecutor`。
- 用户回调与 Agent 钩子的线程策略见 [夯实回调执行策略](夯实回调执行策略-plan.md)；本计划不修改 `LlmGateway`/`AgentLoop` 的回调语义。

---

## 9. 调研记录（2026-10-22）

### 9.1 版本事实

| 组件 | Boot 2.1.10 | Boot 2.7.18 |
| --- | --- | --- |
| Spring WebFlux | 5.1.11 | 5.3.31 |
| reactor-core | 3.2.12 | 3.4.34 |
| reactor-netty | 0.8.13 | 1.0.39 |
| Netty | 4.1.43 | 4.1.101 |
| Jetty | 9.4.22 | 9.4.53 |

### 9.2 关键探针结论

- WebClient 5.1 API 的同一份字节码在 Boot 2.1/2.7 双运行时链接通过；SSE 原始行、UTF-8、错误体、取消均通过。
- 使用 5.3-only API（`bodyValue`/`exchangeToMono`）在 Boot 2.1 上 `NoSuchMethodError`。
- 直接 Reactor Netty 0.8/1.0 无法二进制兼容；0.8 无 `responseTimeout`/`disableRetry`。
- Jetty 9.4 由 Boot BOM 统一管理，单模块跨 2.1/2.7 可行。
- Ktor CIO 2.3.13 在 JDK 8 / Kotlin 1.9.25 编译通过；3.x metadata 2.x 编译失败；2.3 无客户端 SSE 插件，用 `readUTF8Line`。
- Reactor Netty 0.8 连接重置会重试一次，Boot 2.1 上可能重复 POST。

### 9.3 选型

```text
默认        okhttp
B           okhttp-virtual（JDK 21）
C 主路径    jetty（Boot 2.1/2.7，Java-only，无 Netty/Reactor）
C2 可选     webclient（WebFlux 用户；注意 RN 0.8 重复 POST）
C3 可选     ktor-cio（Kotlin 用户/高并发流式；覆盖 Kotlin/coroutines）
```

---

## 10. 实施记录（2026-10-22）

### 10.1 已完成

| 阶段 | 内容 |
| --- | --- |
| Phase 0 | `AiLiteConfig.Http`（transport/dispatcher/jetty/ktor）；Starter 全反射装配；`HttpTransportReporter` 启动日志、覆盖提示、不适用配置 WARN；未知值/缺模块 fail-fast |
| Phase 1 | `ai-lite-http-okhttp-jdk21`；`VirtualThreadOkHttpFactory`；虚拟线程回调、流式、取消测试（JDK 25 运行通过） |
| Phase 2 | `ai-lite-http-jetty`；`JettyHttpExecutor` + core `Utf8LineBuffer`；同步/异步/流式/CRLF/UTF-8/提前 EOF/非 2xx/预取消/流中取消/close 测试 |
| Phase 3 | `ai-lite-http-webclient`；按 WebFlux 5.1 API 编译；DataBuffer 原始行、`ReactorResourceFactory` 独立连接资源、取消/错误体/close 测试 |
| Phase 4 | `ai-lite-http-ktor-cio`；Ktor 2.3.13 + Kotlin 1.9.25；`readUTF8LineTo` 原始行、engine `requestTimeout=0`、Job 取消、错误分类测试 |
| Phase 5 | `benchmark/http-transport-benchmark`；Jetty fake LLM server；100/500/1000 并发、非流式/流式、六种传输的吞吐/延迟/线程数 |

验证：

- JDK 8 + Boot 2.1.10：全量 570 个测试通过。
- JDK 8 + Boot 2.7.18：WebClient、Jetty、Starter 及相关模块测试通过，同一份字节码可用。
- JDK 25：`ai-lite-http-okhttp-jdk21` 3 个用例通过（`Thread.isVirtual()`、流式回调、取消）。
- 基准：六种传输 100/500/1000 并发全部 0 错误（JDK8 的 `stream/1000` 在独立 JVM 运行以避开端口累积）。

### 10.2 踩坑与修复

1. **`StringContentProvider` 三参顺序**：Jetty 9.4 是 `(contentType, content, charset)`，不是 `(content, contentType, charset)`。传反后 `Content-Type` 变成请求体，服务端 400。
2. **`Request.header` 是追加语义**：`HttpClient` 构造时已放入 `Accept-Encoding: gzip`，再用 `header(...)` 会得到重复头。必须用 `request.getHeaders().put(...)` 覆盖；SSE 统一 `identity`。
3. **`HttpClient` 必须 `start()`**：创建后未启动会在第一次请求报 Not started；`close()` 需要遍历缓存逐个 `stop()`，并保证幂等。
4. **`onContent` 的 `ByteBuffer` 只在回调内有效**：必须同步复制字节，不能把 buffer 引用交给后续线程。
5. **提前 EOF 不能当正常结束**：Jetty 的 EofException 按 `Kind.TRANSPORT` 失败；取消优先按 `Kind.CANCELLED`。
6. **connect timeout 是客户端级**：按 connect timeout 缓存客户端，上限 16 组，避免异常配置无限创建 selector/线程池。
7. **JDK8 每次请求 `disconnect()` 会杀掉 keep-alive**：基准高并发时出现端口耗尽；改为正常完成只关流，取消/异常才 disconnect。
8. **OkHttp 默认只保留 5 个空闲连接**：调大 `dispatcher.max-requests` 后连接池没跟上，虚拟线程高并发会反复建连；连接池大小已与 `max-requests` 对齐。
9. **JDK `HttpServer` 不适合做高并发 fake server**：会主动断连产生 `EOFException`；基准改用 Jetty Server。
10. **WebClient 线程名是 `webflux-http-nio-*`**：线程统计和排查时不要只找 `reactor-http`。

### 10.3 仍待推进

- 回调执行策略：独立计划 [夯实回调执行策略](夯实回调执行策略-plan.md)，尚未实施；当前用户回调和 Agent 钩子仍可能在传输/工具线程上执行。
- 基准是开发机上的相对比较，不是受控容量测试；生产容量需要按真实供应商、网络和 CPU 复测。
