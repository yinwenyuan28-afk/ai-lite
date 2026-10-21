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

### Java

```java
CompletableFuture<LlmResult> future = gateway.oneAsync(call);
// 或让 Starter 装配协程执行器：
// ai-lite.executor.mode=coroutines
```

## 边界

- `provider.io()` 返回的 Executor 仍会阻塞载体线程，只适合短任务。
- 真正高并发请使用 `dispatcher()` + `LlmCoroutines`，或直接使用网关的 `*Async`。
- 协程取消会取消返回的 `CompletableFuture`，进而触发 HTTP 请求中断。
- 默认载体线程数是 `max(4, CPU 核数 * 2)`。
