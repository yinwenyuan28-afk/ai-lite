# 夯实 oneWithRetry

**状态（2026-10-22）：Java 同步版与 Kotlin suspend 版已实现。** 本计划只改单次模型调用入口，**不涉及编排层**：不改 `Workflow`、`AgentLoop`、`AgentSessionLoop`，也不新增工具/步骤级重试。

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
- Java 异步 `oneWithRetryAsync`：网关没有调度器，本轮不做；Java 异步调用方自行包策略。
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

- 同一个 `LlmCancelHandle` 贯穿所有尝试；每个尝试前检查。
- 退避等待可被取消打断，不能 `Thread.sleep(2s)` 后再发现取消。
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
