# ai-lite-executor-coroutines

JDK 8 可用的用户级并发：Kotlin 协程调度器 + 网关 suspend 桥接。

## 为什么不是自己实现 fiber

JDK 8 的 stackful fiber 需要字节码增强（Quasar 一类方案），会引入 `-javaagent`
和额外许可证约束。Kotlin 协程由编译器做 CPS 变换，是 JDK 8 上维护成本最低的
用户级并发方案；它和 JDK 21 的虚拟线程在"挂起不占载体线程"这一点上目标一致。

## 用法

### Kotlin

```kotlin
val provider = CoroutineExecutorProvider(parallelism = 8)
runBlocking(provider.dispatcher()) {
    val results = calls.map { call ->
        async { LlmCoroutines.one(gateway, call) }
    }.awaitAll()
}
```

需要重试瞬时错误时：

```kotlin
val policy = RetryPolicy.builder()
        .maxAttempts(3)
        .initialBackoffMs(200)
        .maxBackoffMs(2000)
        .build()

val result = LlmCoroutines.oneWithRetry(gateway, call, policy, cancel)
```

### Java

```java
CompletableFuture<LlmResult> future = gateway.oneAsync(call);
// 或让 Starter 装配协程执行器：
// ai-lite.executor.mode=coroutines
```

`LlmCoroutines.one` 是**单次模型调用入口**的 Kotlin suspend 形式；`LlmCoroutines.oneAsync` 是同一个入口的 Java future 别名，等价于 `gateway.oneAsync`。

`LlmCoroutines.oneWithRetry` 是 suspend 版显式重试：内部用 `await()` 发起尝试、`delay()` 退避，退避期间不占载体线程，`Job.cancel()` / `withTimeout` 可以直接打断。它与 Java 的 `gateway.oneWithRetry` 共用同一个 `RetryPolicy`。

## 边界

- `provider.io()` 返回的 Executor 仍会阻塞载体线程，只适合短任务。
- 真正高并发请使用 `dispatcher()` + `LlmCoroutines`，或直接使用网关的 `*Async`。
- 协程取消会取消返回的 `CompletableFuture`，网关再把取消转成 `LlmCancelHandle.cancel()`，触发 HTTP 请求中断。`await()` 使用 `future.cancel(false)`，不要依赖线程中断。
- L1（真正打断在途 HTTP）取决于传输层：OkHttp 全阶段有效；JDK 8 `HttpURLConnection` 在正文阻塞读阶段可能等到读超时。完整分层见 `docs/desc-单次调用.md`。
- `LlmCoroutinesParityTest` 锁定桥接语义：不重试、不改写请求、不吞 `CancellationException`。
- 默认载体线程数是 `max(4, CPU 核数 * 2)`。
