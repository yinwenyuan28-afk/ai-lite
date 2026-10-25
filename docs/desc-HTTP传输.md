# HTTP 传输层原理

> **本文在哪一层**：HTTP 传输层（原理说明）。
> **讲什么**：三层职责怎么切、六种传输各自的线程模型、一次异步请求在传输层内部的生命周期、SSE 逐行解析、取消怎么贯穿到 socket、Retry-After 从哪来、并发上限的真正决定者。
> **不讲什么**：传输怎么配怎么切、依赖怎么加 → [HTTP客户端使用指南](guide-HTTP客户端.md)；线程池全景与配置键 → [架构总览](desc-架构总览.md) §5.5；选型的完整论证过程 → [HTTP传输并发选型评估](plan/HTTP传输并发选型评估.md)（内部留档）。生词查 [术语表](ref-术语表.md)。

---

## 1. 三层职责切分

```text
LlmGateway          网关层：校验、选模型、出口保护、重试、调用记录（本层不管网络）
  └─ LlmTransport   协议层：按协议拼请求体、解析响应/SSE（OpenAI ×2、Anthropic 各一个实现）
       └─ HttpExecutor   HTTP 层：真正发请求——POST、超时、连接池、流式逐行读取
            （同步 HttpExecutor；支持异步的实现再实现 AsyncHttpExecutor）
```

切分的意义：**协议知识和传输实现互相独立**。新增一个传输（比如换 HTTP 客户端）不需要动三个协议适配器；新增一个协议（比如 Google Gemini）也不需要动任何传输。网关只认 `LlmTransport` 的 `api()` 名字，配置里 `api: openai-completions` 就是路由到哪个传输的依据。

传输层对网关的承诺只有一条：**非阻塞**。`completeAsync` 立即返回 future，I/O、解析、完成回调全部发生在传输自己的线程上；网关的异步链路（排队、重试调度）都建立在这个承诺上。

---

## 2. 六种传输的线程模型

这是选传输时最本质的差异，一句话版本：**OkHttp 是"每在途请求占一个线程"，Jetty/WebClient/Ktor 是"少量 event loop 线程看管大量连接"，JDK8 是"每请求一个临时线程"，okhttp-virtual 是"线程还在但成本变成虚拟线程级"。**

| 传输 | I/O 模型 | 线程从哪来 | 每个在途请求占用 | 流式 | 取消能力 |
| --- | --- | --- | --- | --- | --- |
| okhttp（默认） | 阻塞 I/O + 任务队列 | OkHttp Dispatcher 线程池（默认 64，每主机 5） | 1 个平台线程，流式占到流结束 | onDelta 在 Dispatcher 线程 | 全阶段 `Call.cancel()` 即时生效 |
| okhttp-virtual | 同上 | JDK 21 虚拟线程接管 Dispatcher | 1 个**虚拟**线程，阻塞时让出底层线程 | 同上 | 同上 |
| jetty | NIO + selector | Jetty 客户端线程池（默认 CPU 级） | 不占等待线程 | onContent 回调 | `request.abort()` 即时 |
| webclient | Reactor Netty event loop | reactor-nio 线程（CPU 级） | 不占等待线程 | DataBuffer 逐行解码 | `Disposable.dispose()` |
| ktor-cio | NIO + 协程 | Ktor CIO 引擎线程 | 不占等待线程 | 逐行挂起读取 | `Job.cancel()` |
| jdk8 | 阻塞 I/O | 自建 cached 池（`ai-lite-jdk8-http-N`，daemon） | 1 个临时线程直到响应读完 | 阻塞读循环 | 等响应头阶段 `disconnect()` 有效；**读正文阶段 best-effort** |

三个容易误解的点：

- **`enqueue` 是"调用线程异步"，不是"I/O 非阻塞"。** OkHttp 的异步只是把任务从调用线程挪到 Dispatcher 线程，每个在途请求仍然实打实占一个平台线程。所以"换成 oneAsync"减少的是调用线程，不是传输层线程；想把传输层线程也压下来，才需要 NIO 传输或虚拟线程。
- **`maxRequestsPerHost` 默认 5 是最常撞到的天花板。** 所有请求都打到同一个供应商域名，默认并发到 5 就在 OkHttp 队列里排队了；协程、虚拟线程、换 NIO 传输都**不会**改变这个数字，必须显式调大（见 guide §1.2）。同理，供应商的服务端限流是任何客户端配置都绕不开的。
- **JDK8 传输的取消最弱。** `HttpURLConnection.disconnect()` 不保证唤醒正在阻塞的 `read()`，取消后最坏要等到读超时。网关的"取消后不再推进"（L2）仍然保证，但在途连接可能多挂一段时间。对取消时延敏感就用 OkHttp 及以上。

---

## 3. 一次异步请求在传输层内部的生命周期

```text
调用线程                      transport.completeAsync(request)
   │                              │ 只做提交，立即返回 future
   ▼
传输线程池（如 OkHttp Dispatcher）
   ├─ 建连/复用连接 → 发出请求
   ├─ 等响应头 → 状态码非 2xx？读 Retry-After 头，抛 LlmCallException(HTTP)
   ├─ 读响应体 → 完整读入 → JSON 解析 → LlmCompletion
   │    （流式则逐行回调 onDelta，见 §4）
   └─ future.complete(...) ──→ 网关完成回调（采样熔断、还名额、定终态）
```

两个边界情况：

- **只实现了同步 `HttpExecutor` 的自定义传输**：网关的 `completeAsync` 会退化成 `CompletableFuture.supplyAsync(...)`，扔进 `ForkJoinPool.commonPool`——高并发下这不是想要的线程模型，自定义传输请同时实现 `AsyncHttpExecutor`。
- **配了 `ai-lite.http.transport` 但没引入对应模块**：启动直接失败（fail-fast），不会静默退回 OkHttp——静默换传输会让人以为自己在测 Jetty 实际跑的是 OkHttp。

---

## 4. SSE 逐行解析：细节在哪处理

流式响应是一行一行的 `data: {...}` 事件，解析要处理三件麻烦事，都由传输层内部消化：

```text
网络字节流（一块一块到达，边界不保证对齐行）
   ├─ 跨 chunk 的 UTF-8：一个多字节字符可能被切在两块之间 → 用增量解码器攒
   ├─ 跨 chunk 的行边界：CRLF / LF 可能分开到达 → 行缓冲器攒到凑齐换行符
   └─ Accept-Encoding: identity → 流式请求禁用 gzip，避免解压缓冲破坏逐行语义
   ▼
完整的行（不含换行符）→ onDelta / StreamListener.onLine
```

所以给自定义传输的流式契约是三条：逐行回调且不含换行符、处理跨 chunk 的 UTF-8 与 CRLF、流式请求设置 `Accept-Encoding: identity`。前两条做错的典型症状是"偶发乱码"和"偶发两行粘成一行"，只在长响应或慢网络下出现，很难在测试环境复现。

---

## 5. 取消怎么贯穿到 socket

```text
LlmCancelHandle.cancel() / future.cancel(true)
   ▼
网关把 abort 动作绑定进请求（request.getCancel().bindAbort(...)）
   ▼
传输层执行真正的中断：
   OkHttp    → Call.cancel()：socket 层中断，正在阻塞的读立刻抛出
   Jetty     → request.abort()
   WebClient → Disposable.dispose()
   Ktor      → Job.cancel()
   JDK8      → disconnect()：等响应头阶段有效；读正文阶段 best-effort
   ▼
被中断的请求以 TRANSPORT/CANCELLED 异常完成 → 网关按取消收敛（不再发新请求）
```

设计要点：取消不是"设个标志位等传输自己看"，而是**把中断动作绑到具体请求上**。因为 I/O 阻塞在传输线程里，轮询标志位永远来不及救它。JDK8 的读正文阶段是唯一兜不住的地方，网关在完成回调最头部还有一次取消检查兜底（保证 L2，不保证 L1 时延）。

---

## 6. Retry-After 从哪来

5 个 HTTP 执行器在响应非 2xx 时统一走一个错误工厂：读到 `Retry-After` 头 → 只有 **429/503 + 正整数秒**才采纳 → 换算成毫秒挂到 `LlmCallException.getRetryAfterMs()`。HTTP 日期形式、0、负数、非数字一律忽略（退回指数退避）。

这个字段放在异常上而不是让传输直接改退避——因为"等多久"是重试策略的事，传输只负责"供应商说了什么"。自定义重试策略同样能拿到这个字段自己决定怎么用。

---

## 7. 并发上限的真正决定者

一个供应商域名实际能跑多高的并发，由四层限制取最小值，从外到内：

```text
① 网关出口保护 guard.concurrency      ← 你配的（默认不启用）
② HTTP 客户端连接/任务上限             ← maxRequests(64)/perHost(5)、
                                          jetty max-connections-per-destination、
                                          ktor max-connections-per-route
③ 连接池与文件描述符、带宽
④ 供应商服务端限流（429 + Retry-After）← 客户端怎么调都绕不开
```

排查"并发上不去"时按这个顺序看：先看是不是 ②的 perHost 默认 5，再问 ④是不是已经 429 了（出口保护的 `ailite.llm.guard.rate-limited` 指标和 429 计数能直接看到）。**换传输只改变线程模型（每请求占多少线程），不提高 ③④ 的天花板。**

---

## 8. 选型决策摘要

为什么 Boot 2.1 基线上默认 OkHttp、NIO 主路径是 Jetty 9.4，而"看起来更现代"的 Reactor Netty 落选：

- **Reactor Netty 1.0 需要 reactor-core 3.2+ / Netty 4.1.43+，与 Boot 2.1 的依赖线冲突**；降级到 RN 0.8 又有重复 POST 的已知缺陷。此路不通。
- **Jetty 9.4 由 Boot BOM 管理，跨 Boot 2.1/2.7 都能用，纯 NIO 无 Netty/Reactor 传递依赖**，成为 C 路径主选；代价是 9.4 的连接超时全局共享、无内置 SSE 解析（行解码自己做）。
- **WebClient / Ktor CIO 作为可选路径**：前者给已用 WebFlux 的应用，后者给 Kotlin 技术栈；都有额外依赖成本。
- 基准数据（100/500/1000 并发的吞吐、延迟、线程数）见 [HTTP 传输并发基准](../benchmark/http-transport-benchmark/README.md)；完整论证过程（三个选项、判定规则）见内部留档 [HTTP传输并发选型评估](plan/HTTP传输并发选型评估.md)。

---

## 9. 相关文档

- [HTTP客户端使用指南](guide-HTTP客户端.md)——怎么配、怎么切、怎么自定义传输
- [架构总览](desc-架构总览.md)——线程池总清单（§5.5）、线程角色（§5.1）
- [用户级线程实现](desc-用户级线程.md)——okhttp-virtual 与执行器模式的实测数据
- [单次模型调用入口](desc-单次调用.md)——传输层之上的网关主线（含线程接力）
- [配置参考](ref-配置.md)——`ai-lite.http.*` 全部配置键
- [HTTP传输并发选型评估](plan/HTTP传输并发选型评估.md)——选型论证全文（内部留档）
