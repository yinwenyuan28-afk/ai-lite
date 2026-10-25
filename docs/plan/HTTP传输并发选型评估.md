# HTTP 传输并发模型评估

> **本文是什么**：《夯实HTTP传输并发》计划的立项前调研记录（选型评估），与计划本体同目录留档。常驻的用户面文档（配置、选型结论、使用方式）已吸收进 [HTTP客户端使用指南](../guide-HTTP客户端.md) 与 [配置参考](../ref-配置.md)，本文只保留问题分析、三个选项的论证过程和基准判据。

**状态（2026-10-22）：背景评估，Phase 0–5 已落地。** Reactor Netty 1.0 不能用于 Boot 2.1；最终选型为 **Jetty 9.4 作为 C 主路径**，WebClient 与 Ktor CIO 作为可选路径。已实现 `ai-lite.http.transport` 配置选择、`okhttp-virtual`（JDK 21）、`ai-lite-http-jetty`、`ai-lite-http-webclient`、`ai-lite-http-ktor-cio` 和并发基准。具体配置键、模块设计与验收见 [夯实 HTTP 传输并发（B/C 双路径）](夯实HTTP传输并发-plan.md)，实测数据见 [HTTP 传输并发基准](../../benchmark/http-transport-benchmark/README.md)。本文保留问题分析和基准判据。

---

## 1. 问题

`gateway.oneAsync(...)` 返回 future 后，调用线程确实被释放了。但这不等于“请求不占线程”：

```text
调用线程        → 参数校验、构造请求、enqueue，然后释放
OkHttp Dispatcher → socket I/O、读 body、解析、网关回调
ai-lite io()     → 调用记录、工具、会话落盘
```

OkHttp 的 `enqueue` 是**调用线程异步**，不是 **I/O 非阻塞**。每个在途请求仍占一个 Dispatcher 线程；流式请求会占住该线程直到流结束。

默认限制：

```text
maxRequests        = 64
maxRequestsPerHost = 5
```

对同一个 provider host，默认最多 5 个并发请求。协程和虚拟线程都不会改变这个数字。

---

## 2. 当前架构

```text
LlmGateway
  └─ LlmTransport
       └─ AsyncHttpExecutor（SPI）
            ├─ OkHttpExecutor      enqueue + Dispatcher 线程
            └─ Jdk8HttpExecutor    cached thread pool，每请求一线程
```

`AsyncHttpExecutor` 是干净的扩展点：新增传输实现不需要改 Gateway、Workflow、AgentLoop 或协程层。

`ai-lite-executor-jdk21` 只接管 `ExecutorProvider.io()`（工具、日志、会话落盘），**不影响 OkHttp Dispatcher**。

---

## 3. 三个选项

### 选项 A：保持现状，调 OkHttp Dispatcher

```java
Dispatcher dispatcher = new Dispatcher();
dispatcher.setMaxRequests(512);
dispatcher.setMaxRequestsPerHost(128);

OkHttpClient client = new OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(new ConnectionPool(200, 5, TimeUnit.MINUTES))
        .build();

new OkHttpExecutor(client);
```

**适合**：并发几十到一两百、以非流式为主、供应商 rate limit 本身不高。

**代价**：线程数仍与在途请求数同阶；流式长连接会长期占线程；调太大可能撞上服务端限流、文件描述符和带宽。

### 选项 B：JDK 21 虚拟线程接管 OkHttp Dispatcher

OkHttp 的 `Dispatcher` 支持注入 `ExecutorService`：

```java
Dispatcher dispatcher = new Dispatcher(
        Executors.newVirtualThreadPerTaskExecutor());
dispatcher.setMaxRequests(512);
dispatcher.setMaxRequestsPerHost(128);

OkHttpClient client = new OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .build();
```

线程模型：

```text
每个在途请求仍是一个“线程”，但它是虚拟线程
  ├─ 阻塞在 socket read 时卸载载体线程
  ├─ 创建/销毁成本远低于平台线程
  └─ 流式请求长期挂起也不会占平台线程
```

**优点**：

- 代码改动最小，OkHttp 的 TLS、代理、HTTP/2、连接池、取消语义全部保留。
- 不需要新增 HTTP 依赖。
- 公开 API 不变。
- 对流式长连接尤其有效：线程仍然“存在”，但不再占用平台线程。

**限制**：

- 仅 JDK 21+；JDK 8/11/17 用户无法使用。
- 仍然受 `maxRequests` / `maxRequestsPerHost` 限制；虚拟线程让等待更便宜，但不提高服务端配额。
- JDK 21–23 的 pinning：`synchronized` 块或 native 帧中阻塞会钉住载体；OkHttp 的连接池/部分内部同步可能触发，需要实测。JDK 24 的 JEP 491 大幅缓解。
- 虚拟线程不是零成本：每个在途请求仍有栈和对象开销，只是比平台线程小。
- 需要一个新模块或反射装配：`ai-lite-http-okhttp` 本身是 JDK 8 字节码，不能直接引用 `newVirtualThreadPerTaskExecutor()`。

**落地方式建议**：新增可选模块 `ai-lite-http-okhttp-jdk21`（或 `ai-lite-http-jdk21`），只在 JDK 21 profile 下编译；Starter 在 `ai-lite.http.dispatcher=virtual` 时反射装配。不要改动现有 `OkHttpExecutor` 的默认行为。

### 选项 C：新增 NIO 传输模块

真正的 NIO/event-loop 客户端可以用少量线程处理大量连接：

```text
Event loop 线程（通常 CPU 核数级别）
  ├─ 连接 1 响应到达 → 回调
  ├─ 连接 2 响应到达 → 回调
  └─ ...
```

候选实现：

| 实现 | JDK 基线 | 特点 | 风险 |
| --- | --- | --- | --- |
| Jetty 9.4 HttpClient | Java 8 | Boot BOM 管理 9.4.22/9.4.53；无 Netty/Reactor；NIO+连接池 | 9.4 EOL/CVE；connect timeout 全局；无 SSE 解析 |
| Spring WebClient 5.1 API | Java 8 | 同一字节码跑 Boot 2.1/2.7；响应式；SSE 原始行需 DataBuffer | 需 WebFlux；RN 0.8 重复 POST；依赖体积 |
| Ktor CIO 2.3.13 | Java 8 | 真 NIO+协程；无 Netty/Reactor | 需覆盖 Kotlin/coroutines；2.x EOL；+3.2MB |
| Reactor Netty 1.0 | Java 8 | 与 Boot 2.7 对齐 | Boot 2.1 的 reactor-core 3.2/Netty 4.1.43 不兼容 |
| Apache HttpClient 5 async | Java 8（5.6） | NIO、per-request timeout、维护活跃 | 需 shade 或按 5.1.4 编译；流式 API 复杂 |
| 自己写 JDK NIO | Java 8 | 零依赖 | TLS/SSE/连接池/取消全部自研，不推荐 |

实现方式（最终以计划为准）：

```text
ai-lite-http-jetty（主路径）
  └─ JettyHttpExecutor implements AsyncHttpExecutor
       ├─ executeAsync         → request.send(Response.Listener)
       ├─ executeStreamingAsync → onContent(ByteBuffer) → Utf8LineBuffer
       └─ cancel               → request.abort

ai-lite-http-webclient（可选）
  └─ WebClientHttpExecutor implements AsyncHttpExecutor
       ├─ executeAsync         → exchange() → Mono<HttpResult>
       ├─ executeStreamingAsync → DataBuffer → SseLineDecoder
       └─ cancel               → Disposable.dispose()

ai-lite-http-ktor-cio（可选）
  └─ KtorCioHttpExecutor implements AsyncHttpExecutor
       ├─ executeAsync         → scope.future { ... }
       ├─ executeStreamingAsync → readUTF8Line()
       └─ cancel               → Job.cancel()
```

Gateway、Workflow、AgentLoop、协程层都不需要改。

**优点**：

- 真正减少传输层线程：少量 event loop 线程承载大量并发连接。
- 高并发流式场景收益最大。
- 可以只作为可选模块，保持核心轻量。

**代价**：

- 新增较重依赖；需要评估 jar 体积、Boot 2 依赖冲突、许可证。
- TLS、代理、HTTP/2、连接池、DNS 行为与 OkHttp 不同，需要重新验证。
- SSE/JSON 解析仍在 event loop 上做 CPU 工作，必要时还要卸载到解析线程池。
- 取消、超时、背压、错误分类都要重新映射到 `LlmCallException`。
- 测试成本高：需要真实 SSE server 和并发/资源测试。
- 不要把 `Mono`/`Flux` 泄漏到公开 API；对外仍然只用 `CompletableFuture`。

---

## 4. 对比

| 维度 | A 现状 + 调参 | B JDK 21 虚拟线程 Dispatcher | C NIO 传输模块 |
| --- | --- | --- | --- |
| 每个在途请求占平台线程 | 是 | 否（占虚拟线程） | 否（event loop 少量线程） |
| 流式长连接 | 长期占平台线程 | 长期占虚拟线程 | event loop 上挂起，成本最低 |
| JDK 8 可用 | 是 | 否 | 取决于实现（Vert.x/Netty 可以） |
| 新增依赖 | 无 | 无（JDK 21 模块） | 有，较重 |
| API 变化 | 无 | 无 | 无（只换 SPI 实现） |
| 改动量 | 配置 | 小模块 + 装配 | 新模块 + 大量测试 |
| 主要风险 | 线程/连接/限流 | pinning、JDK 版本 | 依赖、TLS/SSE/取消语义 |
| 推荐场景 | 低到中等并发 | JDK 21、以流式为主 | JDK 8/11、极高并发流式 |

---

## 5. 建议

### 短期

1. 保留 OkHttp 作为默认传输，修正文档表述（已完成）。
2. 在文档和配置中明确：
   - `oneAsync` 只释放调用线程；
   - OkHttp 每在途请求占一个 Dispatcher 线程；
   - `maxRequestsPerHost` 是默认 5，需要按 provider 配额调整。
3. 先做基准，确认瓶颈是线程还是供应商限流。

### 中期

- 如果用户主要在 JDK 21，优先做**选项 B**：新增 `ai-lite-http-okhttp-jdk21` 可选模块，装配虚拟线程 Dispatcher。这是性价比最高的改动，API 和 OkHttp 语义都不变。
- 如果仍有大量 JDK 8 用户并且确实需要数百/数千并发流式连接，做 **C1 `ai-lite-http-jetty`**：单模块跨 Boot 2.1/2.7、无 Netty/Reactor。
- 已经使用 WebFlux 的应用可选 **C2 `ai-lite-http-webclient`**；注意 Boot 2.1 的 Reactor Netty 0.8 重复 POST 风险。
- Kotlin 用户可选 **C3 `ai-lite-http-ktor-cio`**；需覆盖 Kotlin/coroutines 版本。
- 如果安全是硬要求，先做 **Apache HttpClient 5 async** 的 time-box spike，再决定是否替换 Jetty。

### 不建议

- 用 `Mono.fromFuture(okHttpFuture)` 包装 OkHttp：线程模型不变，只是换 API。
- 把 `Mono`/`Flux` 加到 `LlmGateway` 公开签名。
- 在 `ai-lite-executor-jdk21` 里顺手改 OkHttp：执行器模块不应依赖 HTTP 客户端。
- 为了“架构先进”默认引入 Netty；先看真实并发和供应商配额。

---

## 6. 基准计划（决定是否做 C 的依据）

本地 fake LLM server，不需要 API key：

```text
场景：
  · 非流式：100 / 500 / 1000 并发，响应延迟 200ms
  · 流式：100 / 500 / 1000 并发，每 50ms 一个 delta，持续 30s

对比：
  · OkHttp 默认（64 / 5）
  · OkHttp 调参（512 / 128）
  · OkHttp + JDK 21 虚拟线程 Dispatcher
  · Reactor Netty（如实现）

指标：
  · 峰值平台线程数、虚拟线程数
  · RSS
  · p50/p95/p99 延迟
  · 吞吐（请求/秒、活跃流数）
  · 错误率、超时率
  · 取消后连接是否及时释放
```

判定规则：

```text
如果 p95 主要由 maxRequestsPerHost 限制，且供应商配额足够
  → 先调参，不做 C

如果 JDK 21 环境下平台线程数/RSS 是瓶颈
  → 做 B

如果 JDK 8/11 环境下需要数千并发流式连接
  → 做 C，且只作为可选模块
```

---

## 7. 相关文档

- [用户级线程实现](../desc-用户级线程.md) §3.1、§10.1
- [架构说明](../desc-架构总览.md) §5.2
- [API 使用指南](../guide-上层调用API.md) §3.1.1
- [QPS 基准](../../benchmark/qps-benchmark/README.md)
