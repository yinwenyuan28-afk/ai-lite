# 用户级线程实现说明

> 适用版本：0.1.0-SNAPSHOT
> 代码基线：JDK 8 字节码，Spring Boot 2.1 ~ 2.7
> 相关模块：`ai-lite-core`、`ai-lite-http-okhttp`、`ai-lite-executor-coroutines`、`ai-lite-executor-jdk21`

## 0. 一句话结论

AI Lite 的用户级并发不是"自己造一个虚拟线程"，而是两条互相独立、可以组合的路径：

- **JDK 21 原生虚拟线程**：JVM 管理的 stackful 线程，阻塞时自动卸载载体线程。
- **Kotlin 协程桥接**：编译期 CPS 变换的协作式任务，挂起点不占载体线程。它是 JDK 8 上的重点方案。

两条路径的共同底座是**异步回调**：网关先做到"调用线程不阻塞"，执行器再决定"用什么跑代码"。但调用线程不阻塞不等于传输层不占线程——OkHttp 的 `enqueue` 仍会在 Dispatcher 线程上执行 I/O。

| 方案 | JDK 要求 | 等待时是否占调用方线程 | 编程模型 |
| --- | --- | --- | --- |
| 平台线程池 + 阻塞调用 | 8+ | 是 | 最直观，线程数即并发数 |
| Java 异步 API + `CompletableFuture` | 8+ | 否（但可能占传输线程） | 回调/链式组合 |
| Kotlin 协程桥接 | 8+ | 否 | 顺序写法 + suspend |
| JDK 21 虚拟线程 | 21+ | 否（阻塞时卸载载体） | 顺序写法 + 阻塞 API |

> `Java 异步 API` 这一行只承诺“不占调用方线程”。OkHttp `enqueue` 每个在途请求仍占一个 Dispatcher 线程；只有 NIO/event-loop 传输才能把大量等待压到少量线程。详见 [HTTP 传输并发模型评估](http-transport-concurrency-evaluation.md)。

---

## 1. 问题与约束

LLM 调用的时间几乎都花在等网络上：首 token 延迟、生成延迟、工具调用往返。用"一个请求一个平台线程"的模型，吞吐会被线程数锁死：

```text
QPS ≈ 线程数 / 单次调用延迟
```

8 个线程、50ms 延迟，理论上限只有 160 QPS。堆到 1000 个线程可以到 1.5 万 QPS，但线程栈、上下文切换和内存都会变成新的瓶颈。

JDK 8 没有虚拟线程，可选方案只有：

1. **字节码增强的 stackful fiber**：Quasar、Kilim 一类。
2. **编译器/运行时支持的协作式任务**：Kotlin 协程。
3. **异步回调 + 状态机（或协程）**。
4. 自己写调度器和 continuation。

我们选了 2 + 3，并在 JDK 21 上补了 1 的原生版本。第 4 条没有选，原因见第 9 节。

---

## 2. 分层架构

```text
业务代码
  ├─ 阻塞 API      gateway.one(...) / AgentLoop.run(...)
  ├─ Java 异步     gateway.oneAsync(...) / AgentLoop.runAsync(...)
  └─ Kotlin 挂起   LlmCoroutines.one(...)
                          │
                  LlmGateway.AsyncRun
                  （每轮一个 continuation 状态机）
                          │
                  LlmTransport.completeAsync
                          │
                  AsyncHttpExecutor
                  （okhttp / okhttp-virtual / jetty / jdk8）
                          │
                        网络
                          │
        ExecutorProvider：platform / coroutines / virtual
        （给日志、工具、阻塞 SDK 提供载体线程）
```

两个轴要分开看：

- **I/O 轴**：`AsyncHttpExecutor` 决定“等待时占哪类线程、占多少”。OkHttp `enqueue` 不占调用线程，但每个在途请求占一个 Dispatcher 线程；只有真正的 NIO/event-loop 实现才能把大量等待压到少量线程。
- **执行轴**：`ExecutorProvider` 决定“代码在哪种线程上跑”。

协程桥接和虚拟线程都只解决执行轴；在这个基准里，QPS 主要由 I/O 轴（调用方是否被阻塞）决定。上一节的基准里，纯 Java `CompletableFuture` 和 Kotlin 协程的 QPS 几乎一样，就是这个原因。注意：该基准使用独立调度器模拟响应，不包含 OkHttp Dispatcher 线程。

四种执行器模式（platform / direct / virtual / coroutines）各自执行什么、横向对比和配置字段的生效范围，见 [architecture.md](architecture.md) 的 5.2 节和 [configuration.md](configuration.md) 第 3 节。本节只展开与“用户级线程”直接相关的 virtual 和 coroutines 两个实现。

---

## 3. 公共底座：异步调用链

### 3.1 `AsyncHttpExecutor`

接口在 `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/http/AsyncHttpExecutor.java:12`：

```java
public interface AsyncHttpExecutor extends HttpExecutor {
    CompletableFuture<HttpResult> executeAsync(HttpRequest request);
    CompletableFuture<Void> executeStreamingAsync(HttpRequest request, StreamListener listener);
}
```

OkHttp 实现用 `enqueue`，见
`ai-lite-http-okhttp/src/main/java/io/github/yinwenyuan28/ailite/http/okhttp/OkHttpExecutor.java:107`：

```java
call.enqueue(new Callback() {
    @Override public void onFailure(Call call, IOException ex) { ... }
    @Override public void onResponse(Call call, Response response) { ... }
});
```

非流式请求在等服务端响应时**不占业务线程**；响应到达后，OkHttp 用 dispatcher 线程读 body 并完成 future。流式请求的 SSE 读取发生在回调线程上，所以一条活跃的流会占一个 OkHttp dispatcher 线程——这是流式协议本身的成本，不是协程能消除的。

`Jdk8HttpExecutor` 没有非阻塞网络 API，`executeAsync` 退化为把阻塞调用交给线程池：

```java
CompletableFuture.supplyAsync(() -> execute(request), executor);
```

这也是为什么默认推荐 OkHttp：JDK8 的"真异步"依赖它。

### 3.2 `LlmTransport.completeAsync`

`ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/llm/LlmTransport.java:31` 给了默认实现：

```java
default CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
    return CompletableFuture.supplyAsync(() -> complete(request));
}
```

内置的三个适配器会覆盖它：

- `openai-completions` 非流式走 `AsyncHttpExecutor.executeAsync`。
- `openai-responses`、`anthropic-messages` 本身就是 SSE，走 `executeStreamingAsync`，在回调线程解析事件。

自定义传输如果只有阻塞实现，默认会落到 ForkJoinPool；需要高并发时请自己覆盖 `completeAsync`。

### 3.3 `LlmGateway.AsyncRun`

`LlmGateway` 的异步入口最终都进 `invokeAsync`，见
`ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/llm/LlmGateway.java:228`。
它和同步版本共享同一套校验、模型解析、工具校验和日志语义，区别只在"轮次怎么推进"。

核心是 `AsyncRun`（`LlmGateway.java:280`）：

```text
next()
  ├─ 已取消？→ 记录 ABORTED，完成 future
  ├─ transport.completeAsync(request)
  ├─ whenComplete
  │    ├─ 异常 → 记录 ERROR，future 异常完成
  │    ├─ 不需要工具 → 记录结果，完成 future
  │    ├─ 需要工具 → 记录 TOOL_USE → 执行工具 → 写回 tool_result → next()
  │    └─ 超过轮数 → 记录 MAX_ROUNDS，完成 future
  └─ 返回（不阻塞）
```

每一轮只在 future 回调里推进，所以调用线程提交完就可以离开。工具执行走 `ToolExecutor.executeAsync`，默认为 `CompletableFuture.supplyAsync`。

日志仍然走 `AsyncLlmCallLogger`，不改变原有落库格式。

### 3.4 上层异步封装

- `AgentLoop.runAsync`（`ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/assembly/AgentLoop.java:143`）
  把 7 个生命周期钩子按原顺序串到异步轮次上；工具批次交给注入的
  `ExecutorProvider.io()`，不再落到 `ForkJoinPool.commonPool()`。
- `AgentSessionLoop.resumeAsync`（`ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/session/AgentSessionLoop.java:112`）
  会话加载、循环、落盘全部在注入的 `io()` 上执行；取消返回的 future 会同步取消
  内层循环和正在进行的 HTTP 请求。

这样，从 HTTP 到 Agent 循环再到会话续聊，整条链路都不需要为"等待"占一个业务线程。

---

## 4. JDK 21 原生虚拟线程

### 4.1 它是什么

虚拟线程是 JVM 管理的 stackful 线程：

- 用户代码看起来仍是阻塞写法。
- 虚拟线程阻塞在 socket、sleep、队列等可挂起操作时，会从载体线程（carrier）上卸载。
- 载体线程默认是 ForkJoinPool，并行度默认等于 CPU 核数，可用 `jdk.virtualThreadScheduler.parallelism` 调整。
- 不需要池化：`Executors.newVirtualThreadPerTaskExecutor()` 每个任务创建一个虚拟线程。

它和协程的区别：虚拟线程由 JVM 保存栈，协程由编译器把函数改写成状态机。前者对代码无侵入，后者需要 `suspend`。

### 4.2 我们的实现

模块：`ai-lite-executor-jdk21`，见
`ai-lite-executor-jdk21/src/main/java/io/github/yinwenyuan28/ailite/executor/virtual/VirtualThreadExecutorProvider.java:15`。

```java
public final class VirtualThreadExecutorProvider implements ExecutorProvider {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override public Executor io() { return executor; }
    @Override public void close() { executor.shutdownNow(); }
}
```

这个模块的 POM 用 `maven.compiler.release=21` 编译，父 POM 只在 `jdk21` profile（`[21,)`）里包含它。Starter 通过反射加载：

```java
Class<?> type = Class.forName(
    "io.github.yinwenyuan28.ailite.executor.virtual.VirtualThreadExecutorProvider");
return (ExecutorProvider) type.newInstance();
```

见 `ai-lite-spring-boot-starter/src/main/java/io/github/yinwenyuan28/ailite/autoconfigure/AiLiteAutoConfiguration.java:146`。
JDK 8 运行时不会解析到任何虚拟线程 API，所以主工程仍然是 JDK 8 字节码。

开启方式：

```yaml
ai-lite:
  executor:
    mode: virtual
```

依赖：

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-executor-jdk21</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 4.3 它和网关怎么配合

两条路径可以独立使用：

- **只用异步网关**：不需要虚拟线程。`oneAsync` 本来就不占**调用**线程；传输层线程占用取决于 HTTP 客户端。
- **有阻塞 SDK / JDBC / 老代码**：把 `mode` 设为 `virtual`，`ExecutorProvider.io()` 返回虚拟线程执行器。异步日志和任何显式通过 `io()` 提交的阻塞任务会跑在虚拟线程上。
- **两者一起用**：异步网关负责网络等待，虚拟线程负责阻塞工具或阻塞 SDK；载体线程不会被这些阻塞任务拖住。

工具批次现在通过 `AgentLoop` 注入的 `ExecutorProvider.io()` 执行，并逐条走
`ToolExecutor`，不再使用 `ForkJoinPool.commonPool()`。`AgentLoop` 默认复用网关的
执行器，也可以显式传入；`mode=virtual` 时工具自然跑在虚拟线程上。逐条执行是为了
保留 `beforeTool` / `afterTool` 的顺序和拒绝语义；需要整批并行时用网关的
`toolLoop` 或自定义 `ToolExecutor.executeAsync`。

`mode=virtual` 不会改变网关 API，也不会自动把同步 `gateway.one()` 变成异步。它只是把"执行阻塞任务的地方"换成了虚拟线程。

### 4.4 注意事项

- **不要池化虚拟线程**。每次任务创建一个即可；池化会失去卸载优势。
- **JDK 21–23 的 pinning**：在 `synchronized` 块或 native 帧里阻塞会钉住载体线程。JDK 24 的 JEP 491 已经大幅缓解。不要在 `synchronized` 里做长阻塞。
- **ThreadLocal 仍可用**，但大量虚拟线程各自持有 ThreadLocal 会放大内存；新代码可考虑 `ScopedValue`（JDK 21 预览，后续版本转正）。
- **CPU 密集任务不会变快**。虚拟线程解决的是"等待"，不是"计算"。
- **兼容性**：`mode=virtual` 需要 JDK 21+ 且引入模块；JDK 8 下会得到一条明确的启动错误。

---

## 5. Kotlin 协程桥接（重点）

### 5.1 不会产生第二个 JVM

这是最常见的误解。Kotlin/JVM 编译产物就是 `.class`：

- `kotlin-stdlib`、`kotlinx-coroutines-core-jvm` 是普通 jar。
- `suspend` 是编译期转换，不需要独立运行时或独立进程。
- 应用启动后仍然只有一个 JVM，协程和 Java 代码共享同一个堆、同一个类加载器、同一个线程池。

构建期可能看到 Kotlin daemon 进程，那是编译器工具，不是应用运行时。顺带一提：OkHttp 4.x 本身就是 Kotlin 写的，所以默认 HTTP 实现早就把 `kotlin-stdlib` 带进同一个 JVM 了。

### 5.2 `suspend` 在字节码层面是什么

Kotlin 编译器对每个 `suspend` 函数做 CPS（Continuation-Passing Style）变换：函数多一个 `Continuation` 参数，函数体被改写成状态机。`LlmCoroutines.one` 大致等价于：

```java
Object one(LlmGateway gateway, LlmCall call, Continuation<? super LlmResult> cont) {
    if (cont instanceof OneContinuation) {
        OneContinuation c = (OneContinuation) cont;
        if (c.label == 1) {
            // 从挂起点恢复，取回上次保存的 future
            Object value = c.futureResult;
            return value;
        }
    }
    OneContinuation c = new OneContinuation(cont);
    c.future = gateway.oneAsync(call, c.cancel);
    c.label = 1;
    Object value = await(c.future, c);
    if (value == COROUTINE_SUSPENDED) {
        return COROUTINE_SUSPENDED;   // 告诉调度器：我挂起了
    }
    return value;
}
```

关键点：

- 局部变量被搬进 `Continuation` 对象，所以挂起后能恢复。
- 返回 `COROUTINE_SUSPENDED` 时，当前载体线程立刻空出来。
- 恢复时调用 `Continuation.resumeWith(result)`，调度器把后续代码重新派发到某个载体线程。

协程不是线程，也不是回调地狱的语法糖；它是**可以在任意挂起点保存和恢复的任务**。

### 5.3 `await()` 怎么把 future 变成挂起点

`kotlinx-coroutines-jdk8` 的 `CompletableFuture.await()` 本质是：

```kotlin
suspendCancellableCoroutine { cont ->
    future.whenComplete { value, error ->
        if (error == null) cont.resume(value)
        else cont.resumeWithException(error)
    }
    cont.invokeOnCancellation {
        future.cancel(false)
    }
}
```

这正好对上我们的异步网关：

- `gateway.oneAsync(...)` 返回 `CompletableFuture`。
- `await()` 注册 `whenComplete`，然后挂起。
- HTTP 回调完成 future，`whenComplete` 恢复协程。
- 协程取消时，`invokeOnCancellation` 取消 future，触发我们内部的取消链。

### 5.4 组件一：`CoroutineExecutorProvider`

`ai-lite-executor-coroutines/src/main/kotlin/io/github/yinwenyuan28/ailite/executor/coroutines/CoroutineExecutorProvider.kt:26`（简化）：

```kotlin
class CoroutineExecutorProvider @JvmOverloads constructor(
    parallelism: Int = max(4, Runtime.getRuntime().availableProcessors() * 2),
    private val threadNamePrefix: String = "ai-lite-coroutine-"
) : ExecutorProvider {

    private val counter = AtomicInteger(1)
    private val pool = Executors.newFixedThreadPool(
        max(1, parallelism),
        ThreadFactory { runnable ->
            Thread(runnable, threadNamePrefix + counter.getAndIncrement())
                .apply { isDaemon = true }
        }
    )
    private val coroutineDispatcher = pool.asCoroutineDispatcher()

    override fun io(): Executor = coroutineDispatcher.asExecutor()
    fun dispatcher(): CoroutineDispatcher = coroutineDispatcher
    override fun close() = coroutineDispatcher.close()
}
```

它做三件事：

1. 创建一个**有界**的载体线程池，默认 `max(4, CPU * 2)`。
2. 用 `asCoroutineDispatcher()` 把它包装成协程调度器。
3. `io()` 返回同一个池的 `Executor` 视图，供异步日志和你自己的阻塞任务使用。

注意：`io()` 仍然是阻塞语义。一个 50ms 的 `Thread.sleep` 放进 `io()` 会占住一个载体线程。真正的高并发路径是 `dispatcher()` + `LlmCoroutines`，不是 `io()`。

### 5.5 组件二：`LlmCoroutines`

`ai-lite-executor-coroutines/src/main/kotlin/io/github/yinwenyuan28/ailite/executor/coroutines/LlmCoroutines.kt:17`

```kotlin
object LlmCoroutines {
    suspend fun one(
        gateway: LlmGateway,
        call: LlmCall,
        cancel: LlmCancelHandle? = null
    ): LlmResult = gateway.oneAsync(call, cancel).await()

    suspend fun round(
        gateway: LlmGateway,
        call: LlmCall,
        tools: List<LlmTool>,
        cancel: LlmCancelHandle? = null
    ): LlmResult = gateway.roundAsync(call, tools, cancel).await()

    suspend fun toolLoop(
        gateway: LlmGateway,
        call: LlmCall,
        tools: List<LlmTool>,
        maxRounds: Int? = null,
        cancel: LlmCancelHandle? = null
    ): LlmResult = gateway.toolLoopAsync(call, tools, maxRounds, cancel).await()

    @JvmStatic
    fun oneAsync(
        gateway: LlmGateway,
        call: LlmCall,
        cancel: LlmCancelHandle? = null
    ): CompletableFuture<LlmResult> = gateway.oneAsync(call, cancel)
}
```

这四个方法统称**「单次模型调用入口」**：`gateway.one`（同步）、`gateway.oneAsync`（future）、`LlmCoroutines.one`（suspend）、`LlmCoroutines.oneAsync`（Java 别名）。

Java 不能直接调用 `suspend` 函数（它多一个 `Continuation` 参数），所以：

- Java 调用方用 `gateway.oneAsync(...)` 或 `LlmCoroutines.oneAsync(...)`。
- Kotlin 调用方用 `LlmCoroutines.one(...)`。

### 5.6 一次调用的完整时序

```text
调用方（Kotlin）
  │
  ├─ runBlocking(dispatcher) / coroutineScope
  │
  ├─ async { LlmCoroutines.one(gateway, call) }
  │        │
  │        ├─ 在载体线程 C1 上开始执行
  │        ├─ gateway.oneAsync(...) → AsyncRun.next()
  │        ├─ transport.completeAsync → OkHttp call.enqueue()
  │        ├─ await() 注册 whenComplete
  │        └─ 返回 COROUTINE_SUSPENDED
  │                 │
  │                 └─ C1 回到载体池，可以跑下一个协程
  │
  ├─ ... 3000 个协程都停在挂起点，堆里只有 3000 个 continuation 对象 ...
  │
  └─ OkHttp 回调线程完成 future
           │
           ├─ whenComplete 恢复 continuation
           ├─ 调度器把后续代码派发到 C1 或 C2
           └─ 协程返回 LlmResult
```

整个过程中：

- 业务侧线程数 ≈ 载体数 + OkHttp dispatcher 线程，不随并发调用数增长。
- 每个挂起的协程只占一个 continuation 对象，不占栈。
- 恢复后的代码可能跑在另一个载体线程上；协程不绑定线程。

### 5.7 取消链：从 `withTimeout` 到 socket

这是桥接设计里最容易出错的部分，我们做了端到端打通：

```text
withTimeout(100) / job.cancel()
        │
        ▼
await() 的 invokeOnCancellation
        │
        ▼
CompletableFuture.cancel(false)
        │
        ▼
LlmGateway.AsyncRun 的 result.whenComplete
  if (result.isCancelled()) effectiveCancel.cancel()
        │
        ▼
LlmCancelHandle.cancel()
        │
        ▼
OkHttpExecutor.buildCall 绑定过的 abort
        │
        ▼
okhttp3.Call.cancel() → 关闭连接
```

代码位置：

- `LlmGateway.invokeAsync` 在 `cancel == null` 时也会创建一个内部 `LlmCancelHandle`，所以 `future.cancel(...)` 永远能中断底层请求，不要求调用方显式传句柄。
- `AgentLoop.runAsync` 同样创建内部句柄，并把 `ctx.getCancel()` 传给 `gateway.roundAsync`，所以取消能打断进行中的模型请求，而不只是"下一轮不再发"。
- `AgentSessionLoop.resumeAsync` 也会在内部创建句柄，并把返回 future 的 `cancel()` 接到句柄上；取消会穿过 `thenCompose` 传到内层循环，不再需要调用方额外传句柄。
- `OkHttpExecutor.buildCall` 把 `Call.cancel()` 绑定到 `LlmCancelHandle`。

取消是协作式的：如果工具或自定义传输忽略中断，协程会停止等待，但底层工作可能继续。写工具时请检查 `Thread.interrupted()` 或尊重传入的取消句柄。

### 5.8 结构化并发

推荐在边界用 `runBlocking`，内部用结构化并发：

```kotlin
val results = runBlocking(provider.dispatcher()) {
    calls.map { call ->
        async { LlmCoroutines.one(gateway, call) }
    }.awaitAll()
}
```

- `async` 的失败会取消同作用域的兄弟协程（默认 `Job`）。
- 如果希望单个失败不影响其他调用，用 `supervisorScope { ... }` 或 `SupervisorJob`。
- `withTimeout` 会取消整个协程树中受影响的子协程。
- 不要在协程里调用 `runBlocking`；它会把载体线程重新阻塞住。

### 5.9 线程上下文：ThreadLocal 和 MDC

协程恢复时可能换载体线程，所以：

- 普通 `ThreadLocal` 的值**不会自动跨挂起点传递**。请求级用户、租户、traceId 不要只放在 ThreadLocal 里。
- 需要传递时用 `ThreadContextElement`，例如 `kotlinx-coroutines-slf4j` 的 `MDCContext`：

```kotlin
withContext(MDCContext(mapOf("traceId" to traceId))) {
    LlmCoroutines.one(gateway, call)
}
```

- OkHttp 回调线程不是载体线程；`onDelta` 里不要依赖请求线程的 ThreadLocal。
- **Spring 事务绑定在 ThreadLocal 上**，不要把一个 `@Transactional` 方法跨越挂起点。正确做法是把事务收在一个阻塞片段里，或使用显式事务模板。

### 5.10 常见陷阱

**1. 在 suspend 函数里做阻塞调用**

```kotlin
// 错误：占住载体线程
suspend fun bad() {
    Thread.sleep(1000)
    jdbcTemplate.query(...)
}
```

载体线程被占住，并发度退化成载体数。正确做法：

- 网络调用走 `LlmCoroutines` / `*Async`。
- 必须阻塞的 JDBC / 老 SDK 放到独立的阻塞线程池：

```kotlin
withContext(blockingDispatcher) {
    jdbcTemplate.query(...)
}
```

`CoroutineExecutorProvider.io()` 可以作为这个阻塞池，但要记住它是有界的。

**2. 用 `Dispatchers.IO` 跑 LLM 调用**

`Dispatchers.IO` 是为阻塞 I/O 设计的弹性线程池。我们的 LLM 调用已经通过 `await()` 挂起，不需要阻塞线程；用 `Dispatchers.IO` 只会让载体数不可控。用 `CoroutineExecutorProvider.dispatcher()` 或 `Dispatchers.Default` 更合适。

**3. 异常包装**

`await()` 在不同 kotlinx-coroutines 版本里可能把异常包在 `CompletionException` 中。只关心业务异常时先解包：

```kotlin
try {
    LlmCoroutines.one(gateway, call)
} catch (ex: CompletionException) {
    throw ex.cause ?: ex
}
```

**4. `runBlocking` 的位置**

`runBlocking` 只放在最外层（Web 请求入口、命令行、测试）。在协程内部调用会把载体线程重新阻塞，失去意义。

**5. 载体数不是越多越好**

载体线程主要执行协程恢复后的短 CPU 片段。LLM 网关通常 4–16 个足够；如果代码里有较多同步 CPU 工作，再适当增加。载体太多会退化成平台线程池。

### 5.11 测试与基准

- `LlmCoroutinesTest` 用单线程调度器模拟异步响应，验证 **2000 个 suspend 调用在 2 个载体线程上完成**，并覆盖取消、错误传播和工具循环。
- `benchmark/qps-benchmark` 对平台线程池、Java 异步、Kotlin 协程做同延迟对比。完整实测数据见第 6 节，复现命令和边界说明见该目录的 README。

---

## 6. 实测数据

### 6.1 环境与参数

| 项 | 值 |
| --- | --- |
| 机器 | Windows 10，8 核 |
| JDK | OpenJDK 8u292（BellSoft） |
| 总调用数 | 3000 |
| 单次模拟延迟 | 50ms |
| 协程载体数 | 8 |
| 传输层 | 测试用 fake transport；由一个单线程调度器在 50ms 后完成 future |
| 运行方式 | `mvn -B compile exec:java -Dexec.args="3000 50 8"` |

单线程调度器是故意的：并发来自异步等待，不来自"服务端线程多"。这对应真实 LLM 调用的主要形态——业务侧在等网络，服务端在它自己的线程/进程里处理。

### 6.2 结果

| 变体 | 墙钟(ms) | QPS | 峰值线程数 | p50 | p95 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `platform-pool-8` | 23313.1 | 128.7 | 28 | 62ms | 63ms | 63ms |
| `platform-pool-1000` | 195.2 | 15372.7 | 1017 | 61ms | 64ms | 65ms |
| `coroutines-8` | 87.6 | 34257.3 | 26 | 64ms | 67ms | 67ms |
| `java-async` | 72.1 | 41594.0 | 18 | 60ms | 64ms | 65ms |

换算一下：

- `platform-pool-8` 的理论上限是 `8 / 0.05s = 160 QPS`，实测 128.7，约为理论值的 80%。
- `platform-pool-1000` 需要三波才能跑完 3000 个调用，QPS 约 1.5 万。
- `coroutines-8` 和 `java-async` 让 3000 个调用同时在途，QPS 分别约 3.4 万和 4.2 万。
- 按每个线程的吞吐算：`coroutines-8` 约 1318 QPS/线程，`platform-pool-1000` 约 15 QPS/线程，差两个数量级。

### 6.3 怎么读这组数据

1. **吞吐提升主要来自“调用线程不再阻塞”。** `java-async` 不使用协程，QPS 与 `coroutines-8` 同一量级；协程不是"把 I/O 变快"的魔法。基准用 fake transport 模拟延迟，真实 OkHttp 仍受 Dispatcher 线程数限制。
2. **协程解决的是编程模型。** 它让你用顺序写法表达多步调用，同时保留挂起能力；取消、超时、结构化并发也比回调链自然。
3. **线程数才是成本差异。** 同样的 QPS 量级，`coroutines-8` 只占 26 个线程，`platform-pool-1000` 占 1017 个。线程栈、上下文切换和内存都会随线程数增长。
4. **8 线程池是"稳定但低"的基线。** 128.7 QPS 不是实现问题，而是"线程数 / 延迟"的物理上限。

`coroutines-8` 和 `java-async` 之间谁高谁低是运行间波动，不要把这 20% 当作协程的性能优势。多次复跑中，协程大约在 3.3–3.7 万 QPS，Java 异步大约在 3.4–4.2 万 QPS。

### 6.4 方法：为什么这组对比是公平的

- 每个变体测前调用 `resetPeakThreadCount()`，并等待上一个变体的线程真正退出，避免线程峰值串味。
- 平台线程池调用 `prestartAllCoreThreads()`，把线程创建成本排除在 QPS 之外，测的是稳态吞吐。
- 两条代码路径都预热 200 次调用，避免把类加载和 JIT 算进结果。
- 单次延迟在任务内部测量，不含排队；排队成本体现在墙钟和 QPS 上。所以 `platform-pool-8` 的 p50 看起来只有 62ms，但墙钟是 23 秒。
- 异步"服务端"只有一个调度线程，不参与业务逻辑，也不构成并发来源。

### 6.5 边界：这些数字不能直接外推到生产

- **不是真实网络。** 真实链路还有 OkHttp dispatcher、连接池、服务端并发、带宽和限流。
- **OkHttp 默认会先卡住并发。** OkHttp 4.x 默认 `maxRequests=64`、`maxRequestsPerHost=5`。不调大 dispatcher，协程开再多，对同一 provider 也只有 5 个并发请求。调参方法见第 10.1 节。
- **流式调用会占 dispatcher 线程。** 一条活跃的 SSE 流在读取期间占一个 OkHttp 回调线程；长流很多时，线程占用会重新上升。
- **CPU 密集任务不适用。** 协程和虚拟线程都不增加算力；CPU 密集任务请用平台线程池或并行算法。
- **机器相关。** 8 核机器上 1000 个平台线程已经很重；核数更少、内存更紧时，线程池的表现会更差。

### 6.6 复现

```bash
# 在仓库根目录安装 SNAPSHOT
mvn -B install -DskipTests

cd benchmark/qps-benchmark
mvn -B compile exec:java -Dexec.args="3000 50 8"
```

参数依次是：总调用数、单次模拟延迟（毫秒）、协程载体数。

---

## 7. 四种执行模型对比

| 维度 | 平台线程池 + 阻塞 | Java 异步 | Kotlin 协程 | JDK 21 虚拟线程 |
| --- | --- | --- | --- | --- |
| JDK | 8+ | 8+ | 8+ | 21+ |
| 等待时占线程 | 是 | 否 | 否 | 否（阻塞时卸载） |
| 编程模型 | 顺序 | 链式/回调 | 顺序 + suspend | 顺序 + 阻塞 |
| 并发上限 | 线程数 | 连接/队列 | 连接/队列 | 连接/队列 |
| 取消 | interrupt / 关闭连接 | future + handle | 结构化取消 | interrupt |
| ThreadLocal | 天然 | 手动传递 | 需 `ThreadContextElement` | 天然 |
| 调试 | 线程栈直观 | 回调栈较深 | 协程栈需工具支持 | 线程栈直观 |
| 额外依赖 | 无 | 无 | kotlinx-coroutines | JDK 21 模块 |
| 适合 | 低并发/老代码 | Java 高并发 | Kotlin 高并发 | 阻塞代码高并发 |

---

## 8. 选型建议

- **JDK 8 + Java 项目**：用 `gateway.oneAsync` / `AgentLoop.runAsync`，执行器保持 `platform`。不需要引入协程。
- **JDK 8 + Kotlin 项目**：引入 `ai-lite-executor-coroutines`，`mode=coroutines`，用 `LlmCoroutines`。
- **JDK 21+ 项目**：引入 `ai-lite-executor-jdk21`，`mode=virtual`。有阻塞 SDK 时收益最大；网络调用仍优先用异步 API。
- **混合项目**：异步网关是常量；执行器只是替换 `ExecutorProvider`，业务代码不需要改。

配置示例：

```yaml
ai-lite:
  executor:
    mode: coroutines     # platform / direct / coroutines / virtual
    core-size: 8         # 协程载体数
```

---

## 9. 为什么没有自己实现 stackful fiber

自己实现 JDK 8 的 stackful fiber 需要：

1. 在类加载期做字节码增强，改写方法以支持保存/恢复栈。
2. 提供 `-javaagent` 或自定义 ClassLoader。
3. 处理 `synchronized`、JNI、反射、异常表等边界。
4. 长期维护 JDK 版本兼容性。

Quasar 是这条路线最成熟的实现，但已经停更，且需要 agent 和额外的许可证约束；Kilim 更老。把它们作为 Starter 的默认基础设施，风险大于收益。

Kotlin 协程把"保存/恢复"交给了编译器，运行时只需要一个调度器；JDK 21 把 stackful 线程做进了 JVM。两者都比自研 fiber 更适合作为库的底座。

---

## 10. 运维参数与限制

### 10.1 OkHttp 的并发上限

这是实际部署最容易忽略的一点。默认 `okhttp` 使用 OkHttp 4.x 的 `Dispatcher`：

- `maxRequests = 64`
- `maxRequestsPerHost = 5`

也就是说，默认情况下对同一个 provider 主机最多只有 **5 个并发请求**。协程开得再多，也会卡在这里。可以直接用配置调整：

```yaml
ai-lite:
  http:
    transport: okhttp
    dispatcher:
      max-requests: 512
      max-requests-per-host: 256
```

也可以自建 client 并交给 `new OkHttpExecutor(client)`；这时 Starter 不会覆盖用户 Bean。

不要盲目调到几千：服务端限流、连接池、文件描述符和带宽都会成为新瓶颈。

### 10.2 传输层线程模型

`ai-lite.http.transport` 决定“等待模型响应时占哪类线程”，与 `ai-lite.executor` 无关：

| transport | 等待时占用 | 何时选 |
| --- | --- | --- |
| `okhttp` | 每个在途请求一个 Dispatcher 平台线程 | 默认；并发几十到一两百 |
| `okhttp-virtual` | 每个在途请求一个虚拟线程 | JDK 21+；改动最小 |
| `jetty` | selector + 连接池，少量线程承载大量连接 | JDK 8、Boot 2.1/2.7、高并发流式 |
| `webclient` | Reactor Netty event loop | 已使用 WebFlux |
| `ktor-cio` | Ktor CIO selector | Kotlin 项目 |

- B 路径（`okhttp-virtual`）只影响异步入口；`one` / `oneWithRetry` 仍在调用线程上阻塞。
- C 路径（`jetty`）的同步入口同样阻塞调用线程，异步入口由 Jetty 线程驱动回调。
- `jetty` 的 connect timeout 是客户端级配置，实现按 connect timeout 缓存客户端；请尽量统一供应商的超时配置。
- 选择未引入的模块会启动失败，不会静默退回 OkHttp。

### 10.3 流式调用的线程占用

非流式异步请求在等待响应时不占调用线程；OkHttp 的流式 SSE 在响应到达后会占一个 Dispatcher 线程读取事件，直到流结束。长流很多时，`maxRequests` 要相应放大，或者改用 Jetty 这类 NIO 传输，把大量长连接压到少量 selector 线程上。

### 10.4 超时

- `connect-timeout-ms` 控制建连。
- `read-timeout-ms` 对流式调用是"两次数据之间"的超时。
- 协程侧再加 `withTimeout` 时，注意它是总时长；两者不要互相替代。

### 10.5 Spring 事务与请求上下文

- 不要把事务跨越挂起点。
- 不要把请求级 ThreadLocal 当作协程上下文。
- 日志 MDC 用 `MDCContext` 或显式传 traceId。

### 10.6 协程异常与日志

- `await()` 可能抛 `CompletionException`，按需解包。
- `AsyncLlmCallLogger` 仍然异步落库；协程取消不会阻塞日志线程。
- 工具执行走注入的 `ExecutorProvider.io()`；如果工具有阻塞 I/O，`mode=virtual` 或自定义执行器即可，不需要再单独换池。

---

## 11. FAQ

**Q：Kotlin 桥接是不是两个 JVM？**

不是。Kotlin 编译成 JVM 字节码，`kotlin-stdlib` 和协程库只是 classpath 上的 jar。应用运行时只有一个 JVM。

**Q：协程是线程吗？**

不是。协程是可挂起的任务，载体线程是执行它的线程。一个载体可以先后执行很多协程。

**Q：协程能让 CPU 计算变快吗？**

不能。它只减少等待期间的线程占用。CPU 密集任务需要更多核或并行算法。

**Q：在协程里调用阻塞代码会怎样？**

载体线程被占住，并发度下降。用 `withContext(blockingDispatcher)` 隔离，或改用异步 API。

**Q：Java 能直接调用 `LlmCoroutines.one` 吗？**

不能直接调用 `suspend` 函数。Java 用 `gateway.oneAsync(...)` 或 `LlmCoroutines.oneAsync(...)`；后者是单次模型调用入口的 Java future 别名。

**Q：为什么不用 Quasar？**

停更、需要 `-javaagent`、额外的许可证和维护成本。Kotlin 协程和 JDK 21 虚拟线程已经覆盖了同样的使用场景。

**Q：`mode=virtual` 会自动把同步网关变快吗？**

不会。它只是把阻塞任务放到虚拟线程上。网络等待仍然建议走异步 API。

**Q：协程取消后 HTTP 请求会停吗？**

会。`future.cancel` → `LlmCancelHandle.cancel` → `Call.cancel` 已经打通；即使没有显式传取消句柄，网关内部也会创建。工具或自定义传输如果不响应中断，仍可能继续执行。

---

## 12. 代码地图

| 能力 | 文件 |
| --- | --- |
| 异步 HTTP SPI | `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/http/AsyncHttpExecutor.java` |
| OkHttp 异步实现 | `ai-lite-http-okhttp/src/main/java/io/github/yinwenyuan28/ailite/http/okhttp/OkHttpExecutor.java` |
| 传输层异步默认实现 | `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/llm/LlmTransport.java` |
| 网关异步状态机 | `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/llm/LlmGateway.java` |
| Agent 异步循环 | `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/assembly/AgentLoop.java` |
| 会话异步续聊 | `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/session/AgentSessionLoop.java` |
| 协程执行器 | `ai-lite-executor-coroutines/src/main/kotlin/io/github/yinwenyuan28/ailite/executor/coroutines/CoroutineExecutorProvider.kt` |
| suspend 桥接 | `ai-lite-executor-coroutines/src/main/kotlin/io/github/yinwenyuan28/ailite/executor/coroutines/LlmCoroutines.kt` |
| JDK 21 虚拟线程 | `ai-lite-executor-jdk21/src/main/java/io/github/yinwenyuan28/ailite/executor/virtual/VirtualThreadExecutorProvider.java` |
| 执行器反射装配 | `ai-lite-spring-boot-starter/src/main/java/io/github/yinwenyuan28/ailite/autoconfigure/AiLiteAutoConfiguration.java` |
| QPS 基准 | `benchmark/qps-benchmark/` |
