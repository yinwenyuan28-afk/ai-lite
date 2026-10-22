# 夯实 oneWithRetry

**状态（2026-10-22）：Java 同步版与 Kotlin suspend 版已实现；`oneWithRetryAsync` 设计已补充（第 9 节，待实施）。** 本计划只改单次模型调用入口，**不涉及编排层**：不改 `Workflow`、`AgentLoop`、`AgentSessionLoop`，也不新增工具/步骤级重试。

一句话总纲：

> **`oneWithRetry` 是“一次模型请求”的显式重试入口：默认关闭、只重试瞬时错误、每次尝试独立记录、取消与总 deadline 优先；`one` 仍然只尝试一次。**

---

## 1. 定位与范围

### 1.1 是什么

`LlmGateway.oneWithRetry(call, policy, cancel?)` 在 `one(call, cancel)` 外面循环：

```text
oneWithRetry
  ├─ attempt 1 → one(call, cancel)
  ├─ attempt 2 → one(call, cancel)
  └─ attempt N → one(call, cancel)
```

每次尝试都是一次完整的单次调用，独立校验、独立记录、独立取消。

### 1.2 本次范围

- `LlmCallException` 增加结构化错误分类（`Kind`、`statusCode`）。
- `RetryPolicy`：最大次数、退避、抖动、总 deadline、可重试判定、重试监听。
- `LlmGateway.oneWithRetry` 同步重载（含/不含取消句柄）。
- `LlmCoroutines.oneWithRetry` suspend 版：`await()` + `delay()`，不占载体线程。
- 传输层填充 `HTTP` / `TRANSPORT` / `PARSE` / `CANCELLED` 分类。
- 测试与文档。

### 1.3 明确不在本次范围

- `Workflow`、`AgentLoop`、`AgentSessionLoop` 的任何改动。
- 工具重试、步骤重试、整轮编排重试。
- Java 异步 `oneWithRetryAsync`：同步/Kotlin 轮次不做；异步版设计见第 9 节，待实施。
- 用户回调与 Agent 钩子的线程归属：见独立的 [夯实回调执行策略](夯实回调执行策略-plan.md)。
- 流式重试、协程流式重试。
- `Retry-After` 响应头支持；本轮只用指数退避。
- 超时策略、熔断、限流、总预算。
- 修改 `one` 的默认行为（仍然一次尝试）。

---

## 2. 指导原则

### R1 默认关闭，显式启用

- `one` 不重试；只有调用 `oneWithRetry` 才重试。
- 不新增配置项隐式打开重试，避免同一业务在不同环境行为不同。

### R2 只重试瞬时错误

- 可重试：429、500、502、503、504、408、连接/读超时、连接重置、可识别瞬时 IO。
- 不可重试：400/401/403/404/422、参数/配置/协议错误、解析错误、内容审核、上下文超长、取消。
- 判定依据是结构化 `Kind`/`statusCode`，不解析错误消息字符串。

### R3 每次尝试独立

- 每次尝试调用一次 `one`，各自写一条 `LlmCallRecord`。
- 不合并 usage/latency；调用方按时间窗口或 `bizId` 聚合。
- 不把多次尝试包装成一条“成功”记录。

### R4 取消与总 deadline 优先

- 同步/协程：同一个 `LlmCancelHandle` 贯穿所有尝试；每个尝试前检查。
- 异步：外层 handle 只做代理，每次尝试使用新的 `LlmCancelHandle`（句柄一次性）；外层取消时取消当前尝试和已排期的退避任务。
- 退避等待可被取消打断，不能 `Thread.sleep(2s)` 后再发现取消；异步用调度器延迟，不占工作线程。
- 总 deadline 覆盖所有尝试和退避；超时后不再发起新尝试。

### R5 `one` 的语义不变

- `one` 仍然只发一次请求，只写一条记录。
- `oneWithRetry` 不改变 `LlmResult` 字段、`ABORTED` 终态、异常类型。
- 重试耗尽后抛最后一次的 `LlmCallException`，前几次作为 suppressed。

### R6 不碰编排层

- Workflow/AgentLoop 需要重试时，自己显式调用 `oneWithRetry`；本次不改它们的代码或计划。
- `toolLoop`、`round` 不加 retry 参数。

---

## 3. 目标契约

### 3.1 错误分类

`LlmCallException` 新增：

```java
enum Kind { CONFIG, TRANSPORT, HTTP, PARSE, CANCELLED, UNKNOWN }

Kind getKind();
Integer getStatusCode(); // 可空
```

默认构造器保持兼容，`Kind.UNKNOWN`。传输层负责填充：

| 来源 | Kind | statusCode |
| --- | --- | --- |
| 配置/校验/模型不存在 | `CONFIG`（新代码）或 `UNKNOWN` | null |
| 连接/读超时、连接重置 | `TRANSPORT` | null |
| 非 2xx | `HTTP` | 实际状态码 |
| JSON/SSE 解析失败 | `PARSE` | null |
| 调用取消 | `CANCELLED` | null |

兼容性：旧调用方只看到 `LlmCallException`，新字段是附加信息；旧构造器行为不变。

### 3.2 RetryPolicy

```java
RetryPolicy.builder()
        .maxAttempts(3)                 // 总尝试次数，含第一次，>=1
        .initialBackoffMs(200)
        .multiplier(2.0)
        .maxBackoffMs(2000)
        .jitter(true)
        .totalDeadlineMs(null)          // null = 不设总 deadline
        .retryable(predicate)           // 默认 transient
        .listener(listener)             // 可空，观察每次重试
        .build();
```

- `maxAttempts=1` 等价于不重试。
- 退避：`initial * multiplier^(n-1)`，封顶 `maxBackoff`；jitter 为 ±20%。
- `totalDeadlineMs` 从 `oneWithRetry` 开始计时。
- `retryable` 默认实现：
  - `TRANSPORT` → true
  - `HTTP` 且 statusCode ∈ {408, 429, 500, 502, 503, 504} → true
  - `UNKNOWN` 且 cause 链含 `IOException` → true（兼容第三方 transport）
  - 其他 → false
- `listener` 在每次**即将重试**时回调：`attempt`（已失败次数）、`delayMs`、`error`。回调异常被吞掉，不影响重试。

### 3.3 终态

| 情况 | 行为 |
| --- | --- |
| 某次尝试成功 | 立即返回该 `LlmResult` |
| 某次尝试返回 `ABORTED` | 立即返回，不重试 |
| 不可重试异常 | 立即抛出，不重试 |
| 可重试异常且还有次数 | 退避后继续 |
| 全部尝试失败 | 抛最后一次 `LlmCallException`，前几次 `addSuppressed` |
| 退避/尝试前发现取消 | 调用一次 `one(call, cancel)` 取得规范 `ABORTED` 并返回，不再发 HTTP |
| 总 deadline 到期 | 抛最后一次错误，消息注明“重试超过总时限”，不再尝试 |
| `policy == null` | 抛 `LlmCallException("重试策略不能为空", CONFIG)` |

### 3.4 调用记录与成本

- 每次尝试一条记录；`success/stopReason/tokens/latency` 反映该次尝试。
- 重试会重复计费；文档写明。
- 不在 `oneWithRetry` 里新增记录，避免重复。

### 3.5 Kotlin suspend 版

```kotlin
LlmCoroutines.oneWithRetry(gateway, call, policy, cancel?)
```

- 复用同一个 `RetryPolicy`，语义与 Java 同步版一致。
- 用 `gateway.oneAsync(call, cancel).await()` 发起尝试，不占用载体线程。
- 用 `delay(delayMs)` 做退避；`Job.cancel()` / `withTimeout` 会直接打断退避。
- 协程取消会取消底层 `CompletableFuture`，网关再把取消转成 HTTP abort。
- `CancellationException` 不捕获、不重试；只捕获 `LlmCallException`。
- 不新增 `oneWithRetryAsync`：Java 异步重试需要调度器和额外的取消链，另立计划。

---

## 4. 实施阶段

### Phase 0：错误结构化（P0）

- `LlmCallException` 增加 `Kind`、`statusCode`、新构造器、getter；旧构造器保持兼容。
- `OkHttpExecutor`：
  - `ensureSuccessful` → `HTTP` + code。
  - `wrap` / `completeFailure` → `TRANSPORT` 或 `CANCELLED`。
  - 响应体为空的解析错误 → `PARSE`。
- `Jdk8HttpExecutor`：同上。
- `TransportSupport.parse` → `PARSE`。
- `OpenAiCompletionsTransport` / `Responses` / `Anthropic` 中的响应解析/失败事件 → `PARSE` 或 `HTTP`（按语义）。
- 验收：429/503 异常带 `HTTP` + statusCode；超时带 `TRANSPORT`；取消带 `CANCELLED`。

### Phase 1：RetryPolicy（P0）

- 不可变 builder、参数校验、默认可重试判定、退避计算、jitter。
- `RetryPolicyTest` 覆盖边界。

### Phase 2：oneWithRetry（P0）

- `LlmGateway.oneWithRetry(call, policy)` / `(call, policy, cancel)`。
- 取消感知退避（分片 sleep + cancel 检查）。
- 总 deadline、suppressed、listener。
- `LlmCoroutines.oneWithRetry(gateway, call, policy, cancel?)`：`await()` + `delay()`，不调用同步版。
- 不修改 `invoke` / `AsyncRun`。

### Phase 3：测试与文档（P1）

- 新增 `LlmGatewayRetryTest`、`RetryPolicyTest`，扩充传输错误分类测试。
- 更新 `docs/retry.md`、`docs/entry-points.md`、`docs/api-usage.md`。
- README 文档索引补本计划。

---

## 5. 测试矩阵

| 类别 | 用例 | 断言 |
| --- | --- | --- |
| 成功 | 首次成功 | 1 次请求，1 条记录 |
| 重试 | TRANSPORT 失败后成功 | 2 次请求，返回成功 |
| 重试 | HTTP 503 后成功 | 2 次请求 |
| 不重试 | HTTP 400/401/404/422 | 1 次请求，抛原异常 |
| 不重试 | CONFIG/PARSE | 1 次请求 |
| 不重试 | 取消 | 返回 ABORTED，不重试 |
| 次数 | maxAttempts=3 全部失败 | 3 次请求，抛最后一次，前两次 suppressed |
| 次数 | maxAttempts=1 | 1 次请求 |
| 退避 | 退避序列与上限 | 计算值符合策略 |
| 抖动 | jitter 关闭 | 延迟确定 |
| 取消 | 退避中取消 | 1 秒内返回，无第二次请求 |
| deadline | 总时限到期 | 不发起新尝试，抛错含“总时限” |
| 记录 | 每次尝试独立记录 | 记录数 = 请求数 |
| 监听 | listener 收到每次重试 | attempt/delay/error 正确 |
| 兼容 | 旧 `LlmCallException` 构造器 | Kind=UNKNOWN，行为不变 |
| 分类 | 429/503/超时/解析 | Kind/statusCode 正确 |
| 协程 | `withTimeout` 在退避中取消 | 100ms 内抛 `TimeoutCancellationException`，无第二次请求 |
| 协程 | `withTimeout` 在途取消 | 底层 abort，不重试，无第三次请求 |
| 协程 | `job.cancel()` 在退避中取消 | 不再发起下一次 |
| 协程 | 每次尝试独立记录 | 记录数 = 请求数 |

---

## 6. 验收标准

- `one` 行为、记录、取消语义完全不变。
- `oneWithRetry` 只重试瞬时错误；400/401/取消/解析不重试。
- 取消在退避期间 1 秒内生效，不再发请求。
- 总 deadline 生效。
- 每次尝试独立记录；全部失败保留最后一次 cause 和 suppressed。
- 不修改 Workflow/AgentLoop/AgentSessionLoop。
- Kotlin suspend 版与 Java 同步版共用 `RetryPolicy`；退避不占载体线程，取消可打断。
- 全量测试通过。

---

## 7. 与其他层的关系

- `Workflow.oneStep` 可以在业务侧改用 `gateway.oneWithRetry(...)`，但本计划不改 Workflow。
- `AgentLoop` 本轮不接入重试；未来可在每轮 `round` 外包 `RetryPolicy`，另立计划。
- `oneAsync` / 流式入口本轮不提供重试；Java 异步重试另立计划。
- `LlmCoroutines.oneWithRetry` 只覆盖 suspend 单次调用，不覆盖 `round` / `toolLoop`。

---

## 8. 执行记录（2026-10-22）

### 生产代码

| 文件 | 改动 |
| --- | --- |
| `ai-lite-core/.../llm/LlmCallException.java` | 新增 `Kind`、`statusCode` 和带分类的构造器；旧构造器保持 `UNKNOWN` |
| `ai-lite-core/.../llm/RetryPolicy.java` | 新增不可变策略、默认可重试判定、退避/抖动/总 deadline、监听器 |
| `ai-lite-core/.../llm/LlmGateway.java` | 新增 `oneWithRetry(call, policy)` / `(call, policy, cancel)`，取消感知退避、总 deadline、suppressed |
| `ai-lite-executor-coroutines/.../LlmCoroutines.kt` | 新增 suspend `oneWithRetry`：`await()` + `delay()`，取消/超时可打断退避 |
| `ai-lite-http-okhttp/.../OkHttpExecutor.java` | HTTP/TRANSPORT/PARSE/CANCELLED/CONFIG 分类 |
| `ai-lite-http-jdk8/.../Jdk8HttpExecutor.java` | HTTP/TRANSPORT/CANCELLED 分类 |
| `ai-lite-core/.../transport/TransportSupport.java` | 解析失败 `PARSE`、地址缺失 `CONFIG` |

### 测试

| 测试类 | 模块 | 用例数 |
| --- | --- | ---: |
| `RetryPolicyTest` | core | 9 |
| `LlmGatewayRetryTest` | core | 17 |
| `OkHttpExecutorAdversarialTest`（新增分类用例） | http-okhttp | 2 |
| `Jdk8HttpExecutorTest`（新增分类用例） | http-jdk8 | 1 |
| `LlmCoroutinesRetryTest` | executor-coroutines | 10 |
| **合计** | | **39** |

### 文档

- `docs/retry.md`：补充 `oneWithRetry` 用法、结构化分类和默认关闭语义，含 Kotlin 示例。
- `docs/entry-points.md`：入口表增加 `oneWithRetry` 和 `LlmCoroutines.oneWithRetry`，说明只覆盖同步非流式与协程非流式。
- `docs/api-usage.md`：§3.1.1 与 §7 增加重载说明。
- `ai-lite-executor-coroutines/README.md`：补充 suspend 重试用法。
- `README.md`：文档索引补充本计划。

---

## 9. 异步版 `oneWithRetryAsync`（待实施）

### 9.1 对“剩余次数 + 完成回调提交固定线程池”的评审

结论：**方向正确**，异步重试确实不应该在 OkHttp/Jetty/Netty/Ktor 的 I/O 线程上做退避和下一次请求。但需要修正四点：

| 提议 | 结论 | 需要修正的地方 |
| --- | --- | --- |
| 在内存区域记录每个 LLM 请求的剩余重试次数 | 可行 | 用每次逻辑调用创建的 `RetryState` 对象，**不要**全局 `Map<requestId, state>` |
| future 完成后提交新任务到固定线程池 | 可行 | 只在“确实要重试”时提交；成功/不可重试的终态直接收敛 |
| 在任务里判断剩余次数、取消、是否重试 | 可行 | 再加 terminal CAS、总 deadline、每次尝试新句柄、future.cancel 处理 |
| 固定线程池 | 不够精确 | 退避期间不能用池线程 `sleep`；应使用 `ScheduledExecutorService.schedule` |

#### 为什么不用全局 Map

- 终止路径很多：成功、不可重试、次数耗尽、deadline、handle 取消、future 取消、异常。
  任何一条路径漏删都会泄漏 state，并让 requestId 复用变得危险。
- 全局 Map 还引入额外的并发和清理逻辑；`RetryState` 被闭包/回调持有，终态后自然可被 GC。
- “剩余次数”是逻辑调用的一部分，不是进程级共享数据。每次 `oneWithRetryAsync` 创建
  一个 `RetryState`，不跨调用复用。

#### 为什么不是“所有完成都提交到固定池”

- 成功和不可重试的终态只需要一次 `complete`，额外线程切换只会增加尾延迟。
- 错误分类是 O(1) 的字段判断（`Kind`/`statusCode`），在完成线程上执行是安全的。
- 真正需要离开 I/O 线程的是**退避等待和下一次请求的启动**；把这两步交给调度器即可。

#### 为什么固定线程池要升级为调度器

如果任务在固定池里 `sleep(backoff)`，每个等待重试的调用会长期占住一个池线程；
并发一高，池被打满，新的重试任务排队，实际退避时间失真。

正确做法：

```text
completion callback
  └─ shouldRetry?
       ├─ 否 → 直接完成 outer future
       └─ 是 → scheduler.schedule(startAttempt, delay)
```

`scheduler` 的线程只在任务真正执行时被占用；退避期间是延迟队列里的一个任务。

#### 为什么不复用 `ExecutorProvider.io()`

`io()` 负责工具、日志、会话落盘。重试决策虽然短，但会反复创建请求、触发 `RetryPolicy`
监听器；和 `io()` 混用会导致：

- 重试尖峰挤占工具/日志线程；
- 用户在重试监听器里做阻塞操作时，拖垮 `io()`；
- 未来回调执行策略引入串行执行器后，职责更混乱。

因此使用独立的 `ai-lite-retry-` 调度器。

---

### 9.2 范围

**新增：**

- `LlmGateway.oneWithRetryAsync(LlmCall, RetryPolicy)`
- `LlmGateway.oneWithRetryAsync(LlmCall, RetryPolicy, LlmCancelHandle)`
- core 内部 `RetryState` / `RetryScheduler` 支撑代码。
- Starter 的 `ScheduledExecutorService` Bean 与 `LlmGateway` 构造器注入。
- 测试与文档。

**不做：**

- 流式重试（`oneByStreamAsync` / SSE）；重试会重复 delta。
- 工具、多轮、`roundAsync`、`toolLoopAsync` 的重试。
- `Workflow`、`AgentLoop`、`AgentSessionLoop` 的改动。
- 修改 `oneAsync` 的默认行为（仍然只尝试一次）。
- 用户回调与 Agent 钩子的线程策略；见 [夯实回调执行策略](夯实回调执行策略-plan.md)。

---

### 9.3 状态模型

每次逻辑调用一个 `RetryState`，由异步状态机独占修改；不进入全局注册表。

```text
RetryState
  ├─ LlmCall call
  ├─ RetryPolicy policy
  ├─ CompletableFuture<LlmResult> result
  ├─ LlmCancelHandle outerCancel          // 可空
  ├─ int attemptsStarted                  // 已发起的尝试数
  ├─ long deadlineNanos                   // 0 表示无总 deadline
  ├─ CompletableFuture<LlmResult> currentAttemptFuture
  ├─ LlmCancelHandle currentAttemptCancel // 每次尝试新建
  ├─ ScheduledFuture<?> scheduledRetry
  ├─ Throwable lastFailure
  ├─ List<Throwable> suppressed
  └─ AtomicBoolean terminal
```

关键约束：

- `terminal` 用 CAS 保证成功、失败、取消只发生一次。
- `attemptsStarted` 在**发起请求前**递增；`RetryPolicy.maxAttempts` 含第一次。
- `currentAttemptFuture` / `currentAttemptCancel` / `scheduledRetry` 允许为 null；
  所有读取都在状态锁或原子引用下完成。
- 终态后清空 `scheduledRetry`、`currentAttemptFuture` 引用，避免无谓持有。

---

### 9.4 线程模型

```text
调用线程
  └─ oneWithRetryAsync
       ├─ 参数/取消预检查
       ├─ 创建 RetryState
       ├─ startAttempt()                         // 第一次直接发起
       └─ 返回 outer future

HTTP 客户端线程（OkHttp/Jetty/Netty/Ktor）
  └─ currentAttemptFuture.whenComplete
       ├─ terminal CAS 失败 → 丢弃
       ├─ 成功 → outer future.complete
       ├─ 取消 → outer future 取消/CANCELLED
       ├─ 不可重试/次数耗尽/deadline → outer future 失败
       └─ 需要重试 → scheduler.schedule(startAttempt, delay)
                      ↑ 只做 O(1) 判断，不 sleep

ai-lite-retry-<n> 调度线程
  └─ startAttempt
       ├─ 再检查 terminal / cancel / deadline
       ├─ policy.notifyRetry(...)
       ├─ 新建 currentAttemptCancel
       ├─ oneAsync(call, currentAttemptCancel)
       └─ 立即返回；不等待响应
```

线程纪律：

- I/O 线程永不 `sleep`、永不等待调度器、永不递归发起同步完成的下一轮。
- 调度线程只做短任务；`oneAsync` 的同步校验和 `enqueue` 必须快速返回。
- 若自定义 transport 的 `completeAsync` 是阻塞实现，会占住调度线程；文档明确要求
  异步 transport 遵守非阻塞契约。
- `future.cancel`、handle 取消、退避任务、完成回调可能在四个不同线程同时发生，
  全部通过 `terminal` CAS 和同步块收敛。

---

### 9.5 调度器

```java
ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(2, daemonThreadFactory("ai-lite-retry-"));
scheduler.setRemoveOnCancelPolicy(true);
scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
```

为什么是 2：

- 任务很短，1 个通常够用；
- `RetryPolicy.notifyRetry` 是用户监听器，可能做轻量日志/指标；2 个可以避免一个
  慢监听器完全阻塞所有重试。若监听器阻塞严重，属于用户代码问题，文档提示。

生命周期：

- Starter 提供 `@Bean(name = "aiLiteRetryScheduler", destroyMethod = "shutdownNow")`，
  `@ConditionalOnMissingBean(name = "aiLiteRetryScheduler")`。
- `LlmGateway` 增加构造器重载接收 `ScheduledExecutorService`；旧构造器保留，
  内部使用一个静态共享守护调度器（首次使用时创建），避免破坏手工构造的调用方。
- 自定义 Bean 优先；不要在 `close()` 里关闭用户提供的调度器。
- 取消的任务通过 `removeOnCancelPolicy` 从延迟队列移除，避免长退避任务堆积。

不新增 `ai-lite.retry.*` 配置；第一版固定 2 个线程。需要更大并发或自定义线程名的
用户直接提供自己的 `ScheduledExecutorService` Bean。

---

### 9.6 取消与终态

`LlmCancelHandle` 是一次性的：第一次取消后进入终态，不能复用于下一次尝试。因此：

- 外层用户 handle 只作为**代理**：
  - `outerCancel.bindAbort(state::cancelFromOuter)`；
  - 每次尝试创建新的 `currentAttemptCancel`，并把 `outerCancel` 的状态转发给它；
  - 如果 `outerCancel` 已取消，不再发起任何尝试。
- `currentAttemptFuture` 是 `oneAsync` 返回的 future；取消它时，网关现有的
  `future.cancel` 逻辑会 cancel 当前 handle 并补一条 `aborted` 记录。
- `cancelFromOuter` / `future.cancel` 要做：
  1. `terminal.compareAndSet(false, true)`；失败直接返回；
  2. `scheduledRetry.cancel(false)`；
  3. `currentAttemptFuture.cancel(true)`；
  4. `currentAttemptCancel.cancel()`（幂等）；
  5. 外层未取消时，用 `Kind.CANCELLED` 完成 outer future。
- `future.cancel(true)` 本身已经把 outer future 置为 cancelled；此时只清理内部资源，
  不要再 `completeExceptionally`，保持 JDK 的 cancelled 语义。
- 预取消：outer handle 已取消 → 直接返回一个异常完成的 future（`CANCELLED`），
  零请求。
- 完成回调在取消之后到达：`terminal` 已为 true，直接丢弃；不能覆盖结果。

---

### 9.7 退避、deadline 与错误

- `delay = policy.delayMs(attemptsStarted)`（含 jitter）。
- 若 `deadlineNanos > 0`：
  - `remaining = deadlineNanos - now`；
  - `remaining <= 0` → 立即以 deadline 错误收敛，不再发起；
  - `delay > remaining` → 不再等待完整 delay，直接以 deadline 错误收敛；
  - 否则按 `delay` 调度。
- `RetryPolicy.shouldRetry(error)` 只看结构化 `Kind`/`statusCode`；
  `CANCELLED`、`PARSE`、`CONFIG`、4xx 不可重试。
- 每次尝试失败：
  - 若不是最后一次：把当前错误加入 `suppressed`，继续；
  - 若是最后一次/不可重试：`lastFailure` 作为主异常，之前错误 `addSuppressed`。
- `policy.notifyRetry` 在调度线程、发起下一次请求之前调用；监听器抛异常只记录，
  不改变重试决策，也不让 outer future 以监听器异常失败。
- 每次尝试都由 `oneAsync` 独立写一条 `LlmCallRecord`；重试会重复计费，文档继续强调。

---

### 9.8 同步完成与递归

`oneAsync` 可能在以下情况**同步完成**：

- 参数校验失败（实际上通常是同步抛出，不会返回 future）；
- fake transport / 自定义 transport 立即 `complete`；
- 连接池/内存错误立即失败。

如果完成回调直接调用 `startAttempt()`，1000 次零延迟重试可能形成深递归。
因此：

- 第一次尝试允许在调用线程直接发起；
- 之后所有尝试，包括 `delay=0`，一律通过 `scheduler.schedule(..., 0, MILLISECONDS)`
  进入调度线程；
- 调度任务本身不再递归，只发起一次 `oneAsync` 后返回。

---

### 9.9 API 与错误契约

```java
CompletableFuture<LlmResult> oneWithRetryAsync(LlmCall call, RetryPolicy policy);

CompletableFuture<LlmResult> oneWithRetryAsync(LlmCall call, RetryPolicy policy,
                                               LlmCancelHandle cancel);
```

- 参数/配置/模型解析错误：与 `oneAsync` 一致，在返回 future 前**同步抛出**。
- 传输/HTTP/解析错误：future 异常完成；是否重试由 `RetryPolicy` 决定。
- 成功：future 正常完成，值与 `oneAsync` 一致。
- 取消：handle 取消 → `LlmCallException(Kind.CANCELLED)`；`future.cancel(true)` →
  JDK cancelled 语义。
- 流式、工具、多轮不在本 API 范围内。

---

### 9.10 测试矩阵

| 类别 | 用例 | 断言 |
| --- | --- | --- |
| 成功 | 第一次成功 | 只发 1 次，无重试，future 正常完成 |
| 重试 | 瞬时错误后成功 | 发 2 次，第 2 次成功，记录 2 条 |
| 分类 | 400/401/404/PARSE/CONFIG | 不重试，立即失败 |
| 次数 | 每次 500，`maxAttempts=3` | 发 3 次，最后一次为主异常，前两次 suppressed |
| deadline | 退避超过剩余 deadline | 不再发新请求，deadline 错误 |
| 退避 | 200/400/800ms + jitter | 调度延迟在范围内，监听器按序调用 |
| 线程 | 重试任务线程名 | 以 `ai-lite-retry-` 开头；HTTP 线程不 sleep |
| 零延迟 | 同步完成 fake transport | 不栈溢出，仍按调度器推进 |
| 预取消 | handle 已取消 | 零请求，future `CANCELLED` |
| 尝试中取消 | 第一请求在途时 handle.cancel | 当前尝试取消，1 条 aborted 记录，无第二次 |
| 退避中取消 | 已 schedule、未开始 | scheduledRetry 被取消，无第二次请求 |
| future 取消 | 尝试中/退避中 `future.cancel(true)` | 内部 future/handle/scheduledRetry 全取消，future 为 cancelled |
| 竞态 | 完成与取消同时到达 | terminal 只生效一次，无重复完成/重复请求 |
| 记录 | 每次尝试 | 每次一条 `LlmCallRecord`，取消的那次为 aborted |
| 监听器 | notifyRetry 抛异常 | 不影响重试，日志记录 |
| 泄漏 | 所有终态 | scheduledRetry 已取消/移除，RetryState 可回收 |

线程断言建议：

- 用一个会阻塞的 fake transport 或可控 `CompletableFuture`，在 `whenComplete` 里记录
  当前线程名；断言重试启动线程是 `ai-lite-retry-`。
- 用 `ScheduledThreadPoolExecutor.getQueue().size()` 在终态后断言没有残留任务。
- 与同步 `oneWithRetry` 做 parity：相同错误序列、相同次数、相同最终异常。

---

### 9.11 验收标准

- `oneWithRetryAsync` 不占用 HTTP 客户端线程做退避/重试决策。
- 所有重试启动都经过 `ai-lite-retry-` 调度器；包括零延迟。
- 每次尝试使用新的 `LlmCancelHandle`；外层 handle 只代理取消。
- `handle.cancel`、`future.cancel(true)`、退避中取消都能停止后续尝试。
- terminal 只发生一次；成功后不再有请求，失败后不再有请求。
- deadline 覆盖所有尝试和退避；超时后不再发起。
- 每次尝试独立记录；重试耗尽保留 suppressed。
- 不修改 `oneAsync`、流式入口和编排层。
- JDK 8 下测试通过；JDK 21/25 下虚拟线程传输可用。

---

### 9.12 风险与对策

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| 取消竞态 | 完成、取消、调度任务同时发生 | `terminal` CAS + 同步状态锁；测试覆盖 |
| 调度任务泄漏 | 长退避后取消 | `removeOnCancelPolicy`；终态清空引用 |
| 监听器阻塞 | 用户 `notifyRetry` 做重活 | 2 个调度线程；文档要求轻量；不放入 `io()` |
| 自定义 transport 阻塞 | `completeAsync` 实际阻塞 | 文档要求异步契约；阻塞 transport 会占调度线程 |
| 重复计费 | 每次尝试真实请求 | 文档明确；调用记录可审计 |
| 最终完成线程 | outer future 可能由 HTTP 线程完成，用户 `thenAccept` 可能在 I/O 线程 | 属于回调执行策略范围，见独立计划 |
| 与协程版漂移 | 三套重试逻辑 | 抽共享 `RetrySupport` 判定；测试做 parity |

---

### 9.13 实施阶段

- **Phase A（core）**：抽 `RetryState` / `RetrySupport`（次数、delay、deadline、suppressed 判定），
  同步与协程版改为复用，保持行为不变。
- **Phase B（调度器）**：`ScheduledThreadPoolExecutor` 工具与 Starter Bean；
  `LlmGateway` 构造器注入；旧构造器兼容。
- **Phase C（API）**：实现 `oneWithRetryAsync` 两个重载、取消代理、状态机。
- **Phase D（测试）**：9.10 的用例，含竞态与调度器队列断言。
- **Phase E（文档）**：`docs/retry.md`、`docs/entry-points.md`、`docs/api-usage.md`、
  `docs/configuration.md`（如有新 Bean 配置）、README。

建议顺序：A → B → C → D → E。A 是纯重构，先确保同步/协程测试全绿，再动异步状态机。
