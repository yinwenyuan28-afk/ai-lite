# 重试边界

本文说明 `ai-lite` 在哪一层做重试、哪些错误可以重试、以及为什么网关本身不自动重试。

---

## 1. 结论

- **`LlmGateway.one` 不做自动重试。** 一次调用只调用一次 transport，失败就结束。
- **单次入口提供显式重试：同步 `oneWithRetry(call, policy, cancel?)` 与异步
  `oneWithRetryAsync(call, policy, cancel?)`。** 默认关闭，调用方显式选择；只覆盖非流式模型调用。
- **异步重试不占 I/O 线程。** 退避任务投递到专用 `ai-lite-retry-*` 调度器，
  HTTP 客户端回调线程只做 O(1) 判断。
- **`OkHttpExecutor` 关闭了 OkHttp 的连接重试**（`retryOnConnectionFailure(false)`），避免和上层策略叠加。
- **`oneWithRetry` / `oneWithRetryAsync` 只重试瞬时错误。** 400/401/404/解析/配置/取消不重试。
- **编排层的重试另立计划。** `AgentLoop` 每轮、`AgentSessionLoop` 整体、`Workflow` 整链的重试不在本文范围内。Workflow 只提供显式的异步步骤重试入口 `Workflow.oneStepWithRetryAsync(call, policy)`；它重试的是该步骤，不是整个 `runAsync`。

---

## 2. 当前行为

`LlmGateway.one` / `oneAsync` 对一次调用只做一件事：发出请求，等待一次响应。

| 情况 | 网关行为 |
| --- | --- |
| transport 抛 `LlmCallException` | 同步入口直接抛；异步入口 future 异常完成 |
| transport 抛其他 `RuntimeException` | 包装成 `LlmCallException("模型调用失败", cause)` |
| HTTP 5xx / 429 | transport 抛 `LlmCallException`，`one` 不重试；`oneWithRetry` 可重试 |
| 连接或读取超时 | transport 抛 `LlmCallException(TRANSPORT)`，`one` 不重试 |
| 调用被取消 | 返回 `ABORTED`，**绝不重试** |
| 流式中途断开 | 已经产生的增量保留，本轮以异常结束，不自动续传 |

`LlmCallException` 现在带 `Kind` 和 `statusCode`：`HTTP`、`TRANSPORT`、`PARSE`、`CANCELLED`、`CONFIG`、`UNKNOWN`。重试判定只看结构化字段，不解析错误消息。

`Attempt` 只是 usage / latency 累加器，不承担重试。调用记录里每一轮只对应一次实际请求。

---

## 3. 推荐的重试结构

### 3.1 优先使用内置的 `oneWithRetry`

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(3)                  // 含第一次
        .initialBackoffMs(200)
        .multiplier(2.0)
        .maxBackoffMs(2000)
        .jitter(true)
        .totalDeadlineMs(10000L)         // 可空
        .build();

LlmResult result = gateway.oneWithRetry(call, policy, cancel);
```

异步入口使用同一份策略，退避既不在调用线程也不在 HTTP 客户端线程上执行：

```java
CompletableFuture<LlmResult> future =
        gateway.oneWithRetryAsync(call, policy, cancel);

// 也可以只用 future 取消，不传 handle
CompletableFuture<LlmResult> future2 = gateway.oneWithRetryAsync(call, policy);
future2.cancel(true);
```

```text
调用线程 ──► oneWithRetryAsync
              └─ attempt 1 → oneAsync
HTTP 回调线程 ◄─ 完成回调：分类 + 计算 delay（不 sleep）
              └─ schedule(startAttempt, delay) ──► ai-lite-retry-*
ai-lite-retry-* ──► policy.notifyRetry + attempt N → oneAsync
```

Kotlin 协程使用 suspend 版：

```kotlin
val result = LlmCoroutines.oneWithRetry(gateway, call, policy, cancel)
```

它用 `await()` 发起尝试、`delay()` 退避，退避期间不占载体线程；`Job.cancel()` / `withTimeout` 会直接打断退避并取消底层请求。它与 Java 版共用同一个 `RetryPolicy`。

```text
oneWithRetry
  ├─ attempt 1 → one(call, cancel)
  ├─ attempt 2 → one(call, cancel)
  └─ attempt N → one(call, cancel)
```

每次尝试都是一次完整的单次调用，独立写一条 `LlmCallRecord`；取消后不再发新请求；退避等待可被取消打断。

### 3.2 异步版的取消与调度

`oneWithRetryAsync` 与 `oneAsync` 的取消契约一致：

| 操作 | 结果 |
| --- | --- |
| 预取消（调用前 `handle.cancel()`） | 零 HTTP；future 正常完成 `ABORTED`；保留一条 aborted 记录 |
| 尝试中 `handle.cancel()` | 当前尝试协作中断；future 正常完成 `ABORTED`；不再发下一次 |
| 退避中 `handle.cancel()` | 取消已排队的重试任务；future 正常完成 `ABORTED`；不补“未发生尝试”的记录 |
| `future.cancel(true)` | JDK cancelled 语义；内部句柄与排队任务一并清理 |

实现要点：

- 每次尝试新建 `LlmCancelHandle`；外层句柄只绑定一次取消代理，终态解绑。
- 所有状态变更在 `synchronized (state)` 内完成；`terminal` CAS 只用于终态收敛，
  完成回调入口不占有它。
- 零延迟重试也经过调度器，避免同步完成的 transport 形成深递归。
- 退避 delay 只计算一次；监听器收到的值与真实调度一致。
- deadline 只阻止**新**尝试，不打断在途请求。

调度器由 Starter 提供，Bean 名 `aiLiteRetryScheduler`，默认 2 个 daemon 线程，
线程名 `ai-lite-retry-*`，并开启 `removeOnCancelPolicy`。需要自定义时提供同名 Bean：

```java
@Bean(name = "aiLiteRetryScheduler", destroyMethod = "shutdownNow")
public ScheduledExecutorService myRetryScheduler() { ... }
```

手工构造 `LlmGateway`（5 参构造器）时使用进程级共享 daemon 调度器；6 参构造器
可注入自己的 `ScheduledExecutorService`。网关不会关闭注入的调度器。

### 3.3 自定义策略

需要接入自己的重试框架时，仍可以在 `one` / `oneAsync` 外面包一层：

```text
业务调用方
  └─ 自定义 RetryPolicy（次数、退避、分类、总 deadline）
       └─ LlmGateway.oneAsync(call, cancel)
            └─ 每次尝试是一条独立的 LlmCallRecord
```

一个最小实现应包含：

1. **最大尝试次数**，而不是无限重试。
2. **指数退避 + 抖动**，避免上游恢复瞬间被打满。
3. **总 deadline**，包含所有尝试和退避时间；超过就放弃。
4. **取消贯通**：同步 `one` 可以把同一个 `LlmCancelHandle` 传给每次尝试；用户取消后
   立即停止退避和下一次尝试。异步版每次尝试必须新建句柄，由外层句柄代理取消
   （`LlmCancelHandle` 一次性）；内置 `oneWithRetryAsync` 已经这样实现。
5. **每次尝试独立记录**，不要把多次尝试的 usage 混进一条记录。

参考伪代码：

```java
LlmResult callWithRetry(LlmGateway gateway, LlmCall call, int maxAttempts,
                        LlmCancelHandle cancel) {
    LlmCallException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        if (cancel.isCancelled()) {
            throw new LlmCallException("调用已取消");
        }
        try {
            return gateway.one(call, cancel);
        } catch (LlmCallException ex) {
            if (!retryable(ex) || attempt == maxAttempts) {
                throw ex;
            }
            last = ex;
            sleepBackoff(attempt, cancel);
        }
    }
    throw last;
}
```

---

## 4. 可重试分类

### 4.1 可重试

| 情况 | 说明 |
| --- | --- |
| HTTP 429 | 遵守 `Retry-After`；没有该头时用指数退避 |
| HTTP 500 / 502 / 503 / 504 | 上游临时故障 |
| 连接超时、读取超时 | 网络抖动或上游过载 |
| 连接被重置、DNS 临时失败 | 可识别的瞬时 IO 错误 |
| 流式尚未收到首个 delta | 此时没有已提交的部分输出，重试安全 |

### 4.2 不可重试

| 情况 | 原因 |
| --- | --- |
| HTTP 400 / 401 / 403 / 404 / 422 | 参数、鉴权或资源问题，重试不会变好 |
| 模型不存在、协议不支持、配置缺失 | 配置错误 |
| 消息/图片/工具参数校验失败 | 请求本身非法 |
| 内容审核拒绝 | 重试只会再次被拒绝 |
| 上下文超长 | 需要截断或换模型，不是重试 |
| 取消 | 用户意图优先，L2 明确禁止再推进 |
| 流式已经收到 delta | 重试会产生重复输出，调用方需自己决定是否续写 |

---

## 5. 幂等与副作用

单次入口只读模型输出，本身没有业务副作用，所以重试是安全的。但调用方要注意：

- **流式回调**可能已经推给前端；重试前要由业务决定是否清空已展示内容。
- **调用记录**会按尝试次数写多条，统计 QPS 和成功率时应按 `callId` 或时间窗口聚合。
- **计费**：失败的尝试也可能已被供应商计费，重试不会退款。

---

## 6. 为什么默认不自动重试

1. **取消语义会变复杂**：L2 要求取消后不再推进，自动重试很容易在取消竞态里多打一次请求。
2. **重试策略因业务而异**：实时对话可能一次都不重试，离线批处理可以重试五次。
3. **deadline 属于调用方**：只有调用方知道整个请求还剩多少时间。
4. **可观测性**：每次尝试独立记录，按 `callId` 聚合即可看清重试成本。

因此 `one` / `oneAsync` 只承诺“一次尝试的契约”；需要重试时显式选择
`oneWithRetry` / `oneWithRetryAsync` 或 Kotlin 的 `LlmCoroutines.oneWithRetry`。

---

## 7. 相关文档

- [单次模型调用入口](entry-points.md)
- [单次调用与 HTTP 客户端使用指南](single-call-http-usage.md)
- [架构说明](architecture.md)
- [用户级并发与取消](user-level-concurrency.md)
- [夯实单次模型调用入口](夯实单次模型调用入口-plan.md)
- [异步重试实现计划](夯实oneWithRetry-plan.md)
