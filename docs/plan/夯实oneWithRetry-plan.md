# 夯实 oneWithRetry

**状态（2026-10-22）：同步版、Kotlin suspend 版、异步版 `oneWithRetryAsync` 均已实现；55 个异步测试用例已落成 JUnit/Kotlin 测试。** 本计划只改单次模型调用入口，**不涉及编排层**：不改 `Workflow`、`AgentLoop`、`AgentSessionLoop`，也不新增工具/步骤级重试。

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
- Java 异步 `oneWithRetryAsync`：原计划不在首轮范围；第 9 节已单独设计并实施。
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
- 总 deadline 覆盖所有尝试和退避；超时后不再发起新尝试，但不打断已在途的 HTTP 请求。

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
| 调用取消（传输层内部异常） | `CANCELLED` | null |

> 逻辑入口的 `LlmCancelHandle.cancel()` 不是抛 `CANCELLED` 异常，而是让 future/结果
> 正常收敛为 `ABORTED`；`CANCELLED` 只用于传输层内部的取消异常路径。

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
- 首轮不新增 `oneWithRetryAsync`；后续在第 9 节单独设计调度器与取消链并已实现。

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
- 更新 `docs/重试说明.md`、`docs/单次模型调用入口.md`、`docs/上层调用API使用指南.md`。
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

- `docs/重试说明.md`：补充 `oneWithRetry` 用法、结构化分类和默认关闭语义，含 Kotlin 示例。
- `docs/单次模型调用入口.md`：入口表增加 `oneWithRetry` 和 `LlmCoroutines.oneWithRetry`，说明只覆盖同步非流式与协程非流式。
- `docs/上层调用API使用指南.md`：§3.1.1 与 §7 增加重载说明。
- `ai-lite-executor-coroutines/README.md`：补充 suspend 重试用法。
- `README.md`：文档索引补充本计划。

---

## 9. 异步版 `oneWithRetryAsync`（已实施）

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
  ├─ long startNanos                      // 计算总 deadline 的起点
  ├─ Long deadlineNanos                   // null 表示无总 deadline；0 表示立即过期
  ├─ CompletableFuture<LlmResult> currentAttemptFuture
  ├─ LlmCancelHandle currentAttemptCancel // 每次尝试新建
  ├─ ScheduledFuture<?> scheduledRetry
  ├─ LlmCallException lastFailure
  ├─ List<LlmCallException> suppressed
  ├─ boolean cancelRequested              // 取消已请求，禁止新尝试
  └─ AtomicBoolean terminal
```

关键约束：

- `terminal` 只作为**终态裁判**：完成回调入口只读；只有成功、不可重试、次数耗尽、
  deadline、取消这些收敛动作才 `compareAndSet(false, true)` 占有它。
  **不能在完成回调入口就 CAS 占有 terminal**，否则第一次失败后所有重试都会被丢弃。
- 所有状态变更（`attemptsStarted`、三个引用、`suppressed`、`lastFailure`、终态）
  统一在 `synchronized (state)` 临界区内完成；`terminal` 只提供无锁快路径读取。
  不要用 CAS 和锁分别保护同一份状态。
- `attemptsStarted` 在**发起请求前**、同一临界区内递增；`RetryPolicy.maxAttempts` 含第一次。
- `currentAttemptFuture` / `currentAttemptCancel` / `scheduledRetry` 允许为 null，
  但发布顺序固定：**先赋值，再注册回调，再放开锁**。
- 终态后清空 `scheduledRetry`、`currentAttemptFuture`、`currentAttemptCancel` 引用，
  并 `outerCancel.bindAbort(null)`，避免无谓持有 `LlmCall`（可能含大图 base64）。
- `deadlineNanos` 用 `Long` 而不是 `0` 哨兵：`RetryPolicy.totalDeadlineMs=0` 是合法的
  “立即过期”，必须与“未设置 deadline”区分。

---

### 9.4 线程模型

```text
调用线程
  └─ oneWithRetryAsync
       ├─ 参数/取消预检查
       ├─ 创建 RetryState
       ├─ 注册 outer future 的 whenComplete（检测 isCancelled）
       ├─ startAttempt()                         // 第一次直接发起
       └─ 返回 outer future

HTTP 客户端线程（OkHttp/Jetty/Netty/Ktor）
  └─ currentAttemptFuture.whenComplete
       ├─ terminal 已为 true → 丢弃（只读，不占有）
       ├─ cancelRequested 或 result.stopReason=ABORTED → outer ABORTED
       ├─ 异常先 unwrap CompletionException
       ├─ 成功 → 占 terminal，outer future.complete
       ├─ 不可重试/次数耗尽/deadline → 占 terminal，outer future 失败
       └─ 需要重试 → scheduler.schedule(startAttempt, delay)
                      ↑ 只做 O(1) 判断，不 sleep

ai-lite-retry-<n> 调度线程
  └─ startAttempt（整体 try/catch Throwable）
       ├─ 锁内再检查 terminal / cancel / deadline
       ├─ 锁内：新建 handle → 赋值 → oneAsync → 赋值 future → 注册回调
       ├─ policy.notifyRetry(...)（轻量；异常只记日志）
       ├─ oneAsync(call, currentAttemptCancel)
       └─ 立即返回；不等待响应
```

线程纪律：

- I/O 线程永不 `sleep`、永不等待调度器、永不递归发起同步完成的下一轮。
- 调度线程只做短任务；`oneAsync` 的同步校验和 `enqueue` 必须快速返回。
- `startAttempt` 必须整体 `try/catch (Throwable)`：后续尝试的同步校验异常、
  `RejectedExecutionException`、监听器 `Error` 都不能让 outer future 永久挂起。
- 若自定义 transport 的 `completeAsync` 是阻塞实现，会占住调度线程；文档明确要求
  异步 transport 遵守非阻塞契约。
- 自定义 `RetryPolicy.retryable(Predicate)` 可能在 I/O 线程执行；文档要求谓词必须
  无阻塞、无 I/O；默认谓词是字段判断。
- `future.cancel`、handle 取消、退避任务、完成回调可能在四个不同线程同时发生；
  状态字段统一由 `synchronized(state)` 保护，`terminal` 只在真正收敛终态时 CAS。

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
- 2 个不是并发上限：任务只做“检查 + 发起 `oneAsync`”，不会等待 HTTP 响应。

生命周期：

- Starter 提供：

```java
@Bean(name = "aiLiteRetryScheduler", destroyMethod = "shutdownNow")
@ConditionalOnMissingBean(name = "aiLiteRetryScheduler")
public ScheduledExecutorService aiLiteRetryScheduler() { ... }
```

- `aiLiteGateway` 的构造器参数必须加 `@Qualifier("aiLiteRetryScheduler")`。
  只按类型注入会在用户同时定义另一个 `ScheduledExecutorService` 时产生
  `NoUniqueBeanDefinitionException`；Bean 名 `aiLiteRetryScheduler` 是覆盖契约。
- `LlmGateway` 增加构造器重载接收 `ScheduledExecutorService`；旧构造器保留，
  内部通过 lazy holder 使用一个静态共享守护调度器（只创建一次），避免破坏手工构造。
- 自定义 Bean 优先；不要在 `close()` 里关闭用户提供的调度器。
- 静态共享调度器的线程必须是 daemon，并有 `ai-lite-retry-` 命名计数；它不随单个
  Gateway 关闭，JVM 退出时自然结束。
- 取消的任务通过 `removeOnCancelPolicy` 从延迟队列移除，避免长退避任务堆积。

不新增 `ai-lite.retry.*` 配置；第一版固定 2 个线程。需要更大并发或自定义线程名的
用户直接提供名为 `aiLiteRetryScheduler` 的 Bean。

> `ScheduledThreadPoolExecutor` 的延迟队列本身无界。每条待重试任务持有
> `RetryState` → `LlmCall` → messages（可能含大图 base64）。`removeOnCancelPolicy`
> 只移除已取消任务；正常排队的任务仍会占内存。文档要求 `maxBackoffMs` 有界，
> 并提示高并发长退避场景的内存成本。

---

### 9.6 取消与终态

`LlmCancelHandle` 是一次性的：第一次取消后进入终态，不能复用于下一次尝试。

**外层 handle 只绑定一次：**

```java
outerCancel.bindAbort(state::cancelFromOuter);
```

- 不要每次尝试重新 `bindAbort`。`LlmCancelHandle` 只有一个 abort 槽，反复绑定会
  覆盖 `state::cancelFromOuter`，导致外层取消只取消当前尝试、不占终态、不取消退避任务。
- 每次尝试新建 `currentAttemptCancel`；它只由 `cancelFromOuter` 主动 `cancel()`，
  绝不反向绑定到 outer handle。
- 终态时 `outerCancel.bindAbort(null)`，解除对 `RetryState`（含 messages/图片）的引用。

**取消终态必须与 `oneAsync` 一致：**

- `outerCancel.cancel()` → outer future **正常完成，结果为 `ABORTED`**，不是
  `LlmCallException(Kind.CANCELLED)`。这与 `oneAsync`、同步 `oneWithRetry`、
  `LlmCoroutines.oneWithRetry` 的既有契约一致。
- `future.cancel(true)` → 保持 JDK cancelled 语义：outer future 是 cancelled，
  内部只清理，不再 `completeExceptionally`。

**取消路径：**

```text
cancelFromOuter:
  synchronized (state):
      标记 cancelRequested = true
      复制并清空 scheduledRetry / currentAttemptFuture / currentAttemptCancel
      if 没有在途尝试:
          占 terminal
  scheduledRetry.cancel(false)
  if 有在途尝试:
      currentAttemptCancel.cancel()          // 协作取消，oneAsync 会收敛为 ABORTED
      // 不 cancel(true) currentAttemptFuture；让 oneAsync 自己完成 ABORTED
  else:
      outer future 以规范 ABORTED 结果完成    // 零 HTTP；不新增调用记录

future.cancel(true):
  外层 future 已经是 cancelled
  cancelRequested = true
  scheduledRetry.cancel(false)
  currentAttemptCancel.cancel()
  不再 completeExceptionally；在外尝试完成后丢弃结果
```

`CompletableFuture` 没有 onCancel 回调，必须在返回前显式注册：

```java
result.whenComplete((value, error) -> {
    if (result.isCancelled()) {
        state.cancelFromOuterFuture();   // 只清理，不再 complete
    }
});
```

与现有 `LlmGateway.invokeAsync` 的 `run.result.whenComplete` 处理方式一致。

**发布顺序（防取消漏网）：**

`startAttempt` 必须在同一临界区完成：

```text
synchronized (state) {
    再检查 terminal / cancelRequested / deadline
    创建 currentAttemptCancel
    currentAttemptCancel = newHandle           // 先赋值
    currentAttemptFuture = oneAsync(...)       // 先赋值
    currentAttemptFuture.whenComplete(...)     // 再注册回调
    scheduledRetry = scheduler.schedule(...)   // 锁内调用并立即赋值
}
```

`scheduler.schedule` 返回前，延迟 0 的任务不能执行（它需要同一把锁）；取消线程在
锁内复制三个引用，出锁后再调用 `cancel`，避免在锁内执行用户代码/阻塞操作。

**预取消与记录：**

- 预取消（outer handle 已取消）：直接调用 `oneAsync(call, outerCancel)`。它不会发
  HTTP，但会按现有 `oneAsync` 语义返回 `ABORTED` 并写一条 aborted 记录。
- 退避中取消：没有新的在途尝试，不再补“从未发生的尝试”记录；前一次失败记录已经
  存在。这一点与同步版略有差异，parity 测试只比较尝试次数/最终终态，不比较记录总数。
- `future.cancel(true)` 是硬取消，不补合成记录；若当前有在途尝试，由 `oneAsync`
  自己的取消路径决定是否记录 aborted。
- 完成回调在取消之后到达：`cancelRequested`/`terminal` 已置位，直接丢弃，不能覆盖结果。
- **`oneAsync` 的取消是正常完成 `ABORTED`，不是异常**。完成回调必须先判断
  `cancelRequested || result.getStopReason() == ABORTED`，再进入“成功”分支；
  否则会把一次取消误判为成功并停止重试。
- 取消是协作式的：不会强制 `complete` outer future。在途 transport 不响应 abort 时，
  与 `oneAsync` 一样等其回调或超时；L2（取消后不再推进）仍由 `cancelRequested`
  阻止新尝试保证。若未来要“取消立即完成”，需要额外定义记录与资源清理，属于独立增强。

**完成回调的异常归一化：**

- `whenComplete` 的错误可能是 `CompletionException` 包装；先 unwrap。
- 只有 `LlmCallException` 才交给 `RetryPolicy.shouldRetry`；其他 `Throwable` 包装成
  `LlmCallException("模型调用失败", cause)` 并按不可重试处理，避免 `ClassCastException`
  或 future 永久不完成。

---

### 9.7 退避、deadline 与错误

- `delay = policy.delayMs(attemptsStarted)`（含 jitter），**只计算一次**，随调度任务
  一起传递；`notifyRetry` 上报的 delay 必须与真实调度一致，不能在调度线程重新计算。
- `deadlineNanos`：
  - `null` 表示没有总 deadline；
  - 非 null 时，`RetryPolicy.totalDeadlineMs=0` 表示“立即过期”，不是“无限”。
    `startNanos + totalDeadlineMs` 用 `System.nanoTime()`；0 也要正确比较。
- 每次准备重试：
  - `remaining = deadlineNanos - now`；
  - `remaining <= 0` → 立即以 deadline 错误收敛，不再发起；
  - `delay > remaining` → 与同步版保持一致，将 delay clamp 到 `remaining`，
    到点后由 deadline 检查收敛；不要静默超出总 deadline；
  - 否则按 `delay` 调度。
- **deadline 只阻止新尝试，不打断在途请求**。在途请求仍受 `connectTimeoutMs` /
  `readTimeoutMs` 约束；验收标准不能写成“deadline 覆盖在途请求”。若未来要打断，
  需要额外调度 deadline 取消任务，并定义终态（属于可选增强，不在本计划）。
- deadline 异常复用同步版 `deadlineExceeded(failures)` 的组装方式：保留最后一次
  失败的 `Kind`/`statusCode` 和根因，之前的失败挂 suppressed。不要新增未定义的
  `Kind.DEADLINE`（会破坏兼容性）。
- `RetryPolicy.shouldRetry(error)` 只对 unwrap 后的 `LlmCallException` 调用；
  `CANCELLED`、`PARSE`、`CONFIG`、4xx 不可重试。
- 每次尝试失败：
  - 若不是最后一次：把当前 `LlmCallException` 加入 `suppressed`，继续；
  - 若是最后一次/不可重试：`lastFailure` 作为主异常，之前错误 `addSuppressed`；
  - suppressed 的挂接必须在占 terminal 之前完成，避免取消线程与失败回调同时收敛时
    丢失异常链。
- `policy.notifyRetry` 在调度线程、发起下一次请求之前调用：
  - 监听器必须轻量、无阻塞；慢监听器会占住 2 个全局调度线程之一。
  - `RetryPolicy.notifyRetry` 目前静默吞 `RuntimeException`、不捕 `Error`；
    实现侧用 `try/catch (Throwable)` 包住调度任务并记日志，不能让监听器的 `Error`
    杀死 worker 或让 outer future 挂起。
- 每次尝试都由 `oneAsync` 独立写一条 `LlmCallRecord`；重试会重复计费，文档继续强调。
- `LlmCallRecord` 没有 attempt 序号，`roundNo` 每次仍是 1；重试记录只能按时间窗口/
  顺序/`bizId` 聚合，文档要提醒不要按 `roundNo` 去重。
- `policy == null`：在方法入口同步抛 `LlmCallException(Kind.CONFIG)`，与同步版顺序一致
  （先于预取消检查）。

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
- **必须先赋值，再注册回调**：

```java
currentAttemptFuture = oneAsync(call, currentAttemptCancel);
currentAttemptFuture.whenComplete(this::onAttemptComplete);
```

如果写成 `currentAttemptFuture = oneAsync(...).whenComplete(...)`，当 `oneAsync`
返回已完成的 future 时，回调会在赋值前执行；取消线程可能读到旧的 null，或终态清理
后引用又被赋值“复活”。赋值和注册都必须在 `synchronized (state)` 内完成。
- 后续尝试的 `oneAsync` 同步抛异常（参数/配置错误）或 `scheduler.schedule` 抛
  `RejectedExecutionException` 时，整体 `try/catch (Throwable)` 收敛 outer future；
  不能让调度任务异常终止而 future 永久不完成。

---

### 9.9 API 与错误契约

```java
CompletableFuture<LlmResult> oneWithRetryAsync(LlmCall call, RetryPolicy policy);

CompletableFuture<LlmResult> oneWithRetryAsync(LlmCall call, RetryPolicy policy,
                                               LlmCancelHandle cancel);
```

- `policy == null`、参数/配置/模型解析错误：与 `oneAsync` 一致，在返回 future 前
  **同步抛出**；`policy == null` 抛 `LlmCallException(Kind.CONFIG)`，且先于预取消检查。
- 传输/HTTP/解析错误：future 异常完成；是否重试由 `RetryPolicy` 决定。
- 成功：future 正常完成，值与 `oneAsync` 一致。
- 取消：
  - `outerCancel.cancel()` → future **正常完成，结果 `ABORTED`**，与 `oneAsync` 一致；
  - `future.cancel(true)` → JDK cancelled 语义，内部清理但不再完成异常；
  - 预取消 → 零 HTTP，返回规范 `ABORTED`；
  - 退避中取消 → 不再发起新尝试，前一次失败记录保留，不补“未发生尝试”的记录。
- 首次尝试的校验错误同步抛出；第 2 次及以后在调度线程发生的同步校验错误，只能通过
  outer future 异常完成（无法再同步抛给调用方）。
- 流式、工具、多轮不在本 API 范围内。

---

### 9.10 测试矩阵

详细用例（数据、线程、竞态、记录数、断言）见
[docs/testing/异步重试测试用例.md](testing/异步重试测试用例.md)。
本表只保留分组总览。

| 分组 | 用例数 | 覆盖 |
| --- | ---: | --- |
| 基本结果 | 3 | 第一次成功、瞬时错误后成功、`maxAttempts=1` |
| 错误分类 | 5 | 400/401/404、`PARSE`、`CONFIG`、`CANCELLED`、`ABORTED` 不重试 |
| 次数与 suppressed | 3 | 次数耗尽、主异常、前几次 suppressed、每次独立记录 |
| deadline | 4 | 未设置、`0ms`、退避超过剩余时间、在途请求不被 deadline 打断 |
| 退避 | 4 | 200/400/800ms、jitter 范围、delay 只计算一次、监听器收到同一 delay |
| 线程 | 3 | 第 1 次在调用线程；后续在 `ai-lite-retry-`；I/O 线程不 sleep |
| 取消 | 8 | 预取消、尝试中 handle 取消、退避中取消、future 取消、重复取消、完成/取消竞态、ABORTED 契约 |
| 同步完成 | 2 | 立即 `complete` 不递归、零延迟仍经调度器 |
| 记录/监听器 | 4 | 每次尝试一条记录、取消记录、监听器异常/`Error`、监听器阻塞不拖垮状态机 |
| 装配/资源 | 5 | 自定义 scheduler Bean、另一个 `ScheduledExecutorService` 不歧义、调度器关闭、队列无残留、代理解绑 |
| parity | 2 | 与同步 `oneWithRetry` 的次数/最终终态一致；与 `oneAsync` 的取消终态一致 |

线程断言修正：

- `whenComplete` 跑在**完成 future 的线程**，不是重试启动线程。线程名断言应放在
  fake transport 的 `completeAsync(request)` 入口：记录每次调用的线程名，
  第 2 次及以后应为 `ai-lite-retry-*`。
- 队列断言必须使用测试注入的独立 `ScheduledThreadPoolExecutor`，不能读静态共享
  调度器或 Spring Bean，否则会被其他测试的任务污染。
- 取消竞态用 `CountDownLatch` 控制“完成回调已进入/取消已发生”的交错，重复跑
  100 次，断言请求次数、终态、记录数不随交错变化。

---

### 9.11 验收标准

- `oneWithRetryAsync` 不占用 HTTP 客户端线程做退避；I/O 线程只做常量时间判断。
- 所有重试启动都经过 `ai-lite-retry-` 调度器；包括零延迟。
- 每次尝试使用新的 `LlmCancelHandle`；外层 handle 只绑定一次 `state::cancelFromOuter`。
- `handle.cancel` → `ABORTED`；`future.cancel(true)` → JDK cancelled；
  两者都能停止后续尝试。
- `terminal` 只在真正的终态收敛时占有；成功/失败/取消只发生一次。
- deadline 到期后不再发起新尝试；**在途请求仍受 transport 超时约束**。
- 每次尝试独立记录；重试耗尽保留 suppressed；退避中取消不补“未发生尝试”的记录。
- 调度器 Bean 名为 `aiLiteRetryScheduler`，Gateway 用 `@Qualifier` 注入；
  自定义 Bean 可覆盖，且不会与用户其他 `ScheduledExecutorService` 冲突。
- 不修改 `oneAsync`、流式入口和编排层。
- JDK 8 下测试通过；JDK 21/25 下虚拟线程传输可用。

---

### 9.12 风险与对策

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| 取消竞态 | 完成、取消、调度任务同时发生 | `synchronized(state)` 管状态；`terminal` 只在终态 CAS；竞态测试重复 100 次 |
| 发布竞态 | 取消读到 null 的当前尝试/调度任务 | 锁内“先赋值再注册回调”；延迟 0 任务必须等锁释放 |
| 调度任务泄漏 | 长退避后取消 | `removeOnCancelPolicy`；终态清空引用；队列断言 |
| 监听器阻塞/Error | `notifyRetry` 做重活或抛 `Error` | 2 个调度线程；`catch (Throwable)`；文档要求轻量 |
| 用户 `retryable` 谓词阻塞 | 自定义谓词在 I/O 线程执行 | 文档要求无阻塞；默认谓词只做字段/cause 判断 |
| 自定义 transport 阻塞 | `completeAsync` 实际阻塞 | 文档要求异步契约；阻塞 transport 会占调度线程 |
| 重复计费 | 每次尝试真实请求 | 文档明确；调用记录可审计 |
| 无界延迟队列 | 每条待重试任务持有 messages（可能含大图） | `maxBackoffMs` 有界；文档提示内存成本 |
| 最终完成线程 | outer future 可能由 HTTP 线程完成，用户 `thenAccept` 可能在 I/O 线程 | 属于回调执行策略范围，见独立计划 |
| 同步/协程/异步漂移 | 三套重试逻辑 | 只抽 `RetrySupport` 纯函数（delay/deadline/shouldRetry/suppressed）；测试做 parity |

---

### 9.13 实施阶段

- **Phase A（core）**：只抽 `RetrySupport` **纯函数**（delay、deadline 判断、
  `shouldRetry` 归一化、suppressed 组装），同步与协程版改为复用这些纯函数，
  **不改它们的控制流**。`RetryState` 含 future/handle/scheduledRetry，只服务异步版，
  不强行塞给同步/协程。
- **Phase B（调度器）**：`ScheduledThreadPoolExecutor` 工具与 Starter Bean；
  `LlmGateway` 构造器注入；旧构造器兼容。
- **Phase C（API）**：实现 `oneWithRetryAsync` 两个重载、取消代理、状态机。
- **Phase D（测试）**：9.10 的用例，含竞态与调度器队列断言。
- **Phase E（文档）**：`docs/重试说明.md`、`docs/单次模型调用入口.md`、`docs/上层调用API使用指南.md`、
  `docs/配置说明.md`（如有新 Bean 配置）、README。

建议顺序：A → B → C → D → E。A 是纯重构，先确保同步/协程测试全绿，再动异步状态机。

---

### 9.14 执行记录（2026-10-22）

Phase A–E 已完成：

| 阶段 | 落地内容 |
| --- | --- |
| A | `RetrySupport`：deadline 纳秒换算、剩余时间、退避裁剪（向上取整防止早到）、suppressed 组装、deadline 异常。同步 `oneWithRetry` 与 `LlmCoroutines.oneWithRetry` 改为复用，控制流不变。 |
| B | `RetryScheduler`：2 个 daemon 线程、`ai-lite-retry-*`、`removeOnCancelPolicy`、共享实例。Starter 新增 `aiLiteRetryScheduler` Bean；`LlmGateway` 新增 6 参构造器并保留 5 参兼容；`aiLiteGateway` 用 `@Qualifier` 注入。 |
| C | `LlmGateway.oneWithRetryAsync` 两个重载与 `RetryRun` 状态机：终态 CAS、锁内发布、每尝试新 handle、外层取消代理、future 取消清理、零延迟调度、deadline 裁剪、异常归一化。 |
| D | `LlmGatewayAsyncRetryTest`（42）、`LlmGatewayAsyncRetryParityTest`（6）、`AsyncRetryAssemblyTest`（7）、Kotlin parity（2）。 |
| E | `docs/重试说明.md`、`单次模型调用入口.md`、`上层调用API使用指南.md`、`配置说明.md`、README、测试文档。 |

实现中的两个细节：

1. **退避裁剪向上取整**：`remainingNanos` 截断成毫秒会让调度早到不足 1ms，deadline 检查尚未过期，从而多打一次请求。`clampDelayMs` 改为向上取整，宁可晚到由 deadline 收敛。
2. **监听器在调度线程执行**：`notifyRetry` 在 `ai-lite-retry-*` 上调用，而不是 I/O 回调线程；
   因此 `delay` 随调度任务一起传递，避免二次计算导致监听值与真实调度不一致。
