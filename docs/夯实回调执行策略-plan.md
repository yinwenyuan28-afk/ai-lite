# 夯实回调执行策略

**状态（2026-10-22）：计划初稿。** 本计划从 `docs/夯实HTTP传输并发-plan.md` 拆出，专门处理“用户回调与 Agent 钩子不得无条件跑在共享 I/O 线程”的问题。它不改 HTTP 客户端实现。

一句话总纲：

> **传输层内部解析保持 inline；用户 `onDelta`、`StreamListener`、Agent 钩子在 `mode=io` 下走 per-run 单 in-flight 串行执行器；同步入口保持 inline；取消清空队列；`afterRun` 在所有终态恰好一次。**

---

## 1. 范围

### 1.1 本次范围

- `ai-lite.callback.*` 配置。
- `CallbackPolicy` / `SerialCallbackExecutor`（core）。
- `LlmGateway` 的 `onDelta` 包装。
- `AgentLoop.runAsync` 的续体 offload、工具前后钩子、`afterRun` 终态收敛。
- `AgentSessionLoop` 复用 `AgentLoop` 策略。
- 线程契约、测试、文档。

### 1.2 不在本次范围

- HTTP 客户端实现（Jetty/WebClient/Ktor/OkHttp）。
- 同步入口的 offload。
- MDC/ThreadLocal 自动传播。
- 回调的分布式追踪、指标。
- 把回调策略暴露到 `Workflow` 的同步步骤。

### 1.3 线程契约

| 入口 | 内部解析/状态机 | 用户回调/钩子 |
| --- | --- | --- |
| `oneAsync` / `roundAsync` / `toolLoopAsync` | 完成线程 inline | `onDelta` 按 `mode` |
| `oneByStreamAsync` | 完成线程 inline | `onDelta` / StreamListener 按 `mode` |
| `LlmCoroutines.*` | 完成线程 inline；`await()` 恢复到协程 dispatcher | 按 `mode` |
| `one` / `oneWithRetry` / `oneByStream` / `toolLoopByStream` | 调用线程 | 调用线程 inline |
| `AgentLoop.runAsync` | 续体投递到串行执行器 | 7 个钩子按 `mode` |
| `AgentLoop.run` | 调用线程 | 调用线程 inline |
| `AgentSessionLoop.resumeAsync` | prepare/finish 在 `io()`；轮次复用 AgentLoop | 按 AgentLoop |

---

## 2. 现状问题（审查结论）

| 编号 | 问题 | 影响 |
| --- | --- | --- |
| CB-01 | `AgentLoop.runAsync` 把 `runTools` 整体提交到 `executor.io()`，其中 `beforeTool`/`afterTool` 也在 `io()` 线程 | 7 个钩子“全部不在 I/O 线程”不成立 |
| CB-02 | 工具 future 的 `whenComplete` 直接调用 `afterTurn`，在工具线程执行 | `afterTurn` 也不在串行执行器 |
| CB-03 | 错误/取消路径直接 `completeExceptionally`，不经过 `finish`/`afterRun` | `afterRun` 不是“恰好一次” |
| CB-04 | `DirectExecutorProvider.io()` 是 inline；`callback.mode=io` 在这种模式下仍会跑在 I/O 线程 | 承诺失效 |
| CB-05 | 若串行执行器复用 `io()`，钩子阻塞会占满工具/会话/日志线程；钩子等待 `io()` 任务可能死锁 | 饥饿/死锁 |
| CB-06 | 回调队列满时只抛异常，没有 abort 正在读的 HTTP | 连接继续读、资源泄漏 |
| CB-07 | `future` 可能在所有 `onDelta` 出队前完成 | 调用方拿到结果时 delta 不完整 |
| CB-08 | 队列满用 `Kind.UNKNOWN`，语义混淆且可能被 `RetryPolicy` 误判 | 可观测性/重试边界不清 |
| CB-09 | 自定义 `LlmGateway`/`AgentLoop` Bean 时，Starter 的 callback policy 不会应用 | 用户以为开启了隔离，实际没有 |
| CB-10 | 同步入口（`oneByStream`、`AgentLoop.run`）仍 inline，文档容易让用户误以为 `mode=io` 对所有入口生效 | 期望不一致 |

---

## 3. 目标设计

### 3.1 配置

```yaml
ai-lite:
  callback:
    mode: inline              # inline | io
    queue-capacity: 1024      # 每个 run 的上限
    rejection-policy: abort   # abort | caller-runs
    executor:
      core-size: 2
      max-size: 8
      queue-capacity: 2048
      thread-name-prefix: ai-lite-callback-
```

- `mode=inline` 默认，不改变现有行为。
- `mode=io` 使用**独立的 callback executor**，不复用 `ExecutorProvider.io()`。原因：钩子阻塞不能占满工具/会话/日志；回调不能等待 `io()` 任务，避免死锁。
- `executor.mode=direct` + `callback.mode=io` → 启动 fail-fast，提示两者冲突；不要静默退化。
- `callback.mode` 只对异步入口生效；同步入口始终 inline，文档列全。

### 3.2 SerialCallbackExecutor

```text
每个 run 一个实例：
  ├─ 单 in-flight：同一时刻只执行一个任务
  ├─ FIFO：任务按提交顺序执行
  ├─ 有界队列：容量来自配置
  ├─ 取消：停止接收，清空队列
  ├─ 溢出：abort 当前 run + abort 传输
  └─ 完成屏障：finish 任务排在最后一个用户事件之后
```

实现要点：

- 不能用“把所有任务一次性提交到多线程池”；必须 `单 in-flight + 任务完成后调度下一个`，否则 FIFO 不成立。
- 任务只持有不可变数据（String、事件对象），不持有 `ByteBuffer`/`DataBuffer`。
- 溢出时调用 `onOverflow` 回调：绑定 `LlmCancelHandle.cancel()` / transport abort；后续事件丢弃。
- `close()` 幂等；取消后不再执行新任务。

### 3.3 LlmGateway

- 在 `onDelta` 入口包装：

```java
Consumer<String> wrapped = callbackPolicy.wrap(onDelta, cancel, runCallbackExecutor);
```

- 内置四个 transport 都通过 `TransportSupport.emit(onDelta, ...)` 调用，因此网关包装一次即可覆盖。
- 完成屏障：transport future 完成后，不直接 `result.complete`；先把“完成事件”投递到同一串行执行器，保证所有已入队的 `onDelta` 先执行。
- `onDelta` 抛异常：在串行执行器内捕获，`result.completeExceptionally(LlmCallException)`；同时 abort 传输。
- 取消：停止接收新 delta，清空队列；`future.cancel` 仍保持 JDK cancelled 语义。
- 同步入口（`one`/`oneByStream`）不包装，保持 inline。

### 3.4 AgentLoop

- `runAsync` 每个 run 创建一个 `SerialCallbackExecutor`。
- **所有异步跳点重新入队**：

```text
模型 future 完成
  → 投递 handleRound 到串行执行器
       ├─ afterModel
       ├─ 需要工具：
       │    ├─ beforeTool（串行执行器）
       │    ├─ 实际工具调用（executor.io()）
       │    ├─ 工具完成 → 投递 afterTool/afterTurn 回串行执行器
       │    └─ 下一轮模型请求从串行执行器发起
       └─ 不需要工具：afterTurn → 下一轮/结束
```

- 工具执行本身仍在 `io()`；`beforeTool`/`afterTool` 在串行执行器；工具完成回调必须重新入队，不能在 `io()` 线程直接跑 `afterTurn`。
- `afterTurn` 的 STOP/CONTINUE 聚合逻辑不变；顺序由单 in-flight 保证。
- **终态收敛**：新增 `finishOnce`，用 `AtomicBoolean` 保证所有终态统一走一次 `afterRun`：
  - 正常 STOP/LENGTH/MAX_ROUNDS
  - 模型异常
  - 工具异常
  - `beforeTool`/`afterTool`/`afterTurn` 异常
  - 取消（handle / future）
  - `beforeRun` 异常（按现有契约：run 未开始，不调用 afterRun；需明确）
- `afterRun` 是观察钩子：异常只记录，不覆盖结果；继续执行后续 `afterRun`。
- `future.cancel` 路径：如果结果已取消，仍要投递一个 finish 任务执行 `afterRun`（传入 null/error），或明确文档化为不调用；二选一，推荐执行一次。

### 3.5 AgentSessionLoop

- 复用 `AgentLoop` 的 `CallbackPolicy`，不新增独立配置。
- `prepare`/`finish` 仍在 `executor.io()`；它们不是用户回调。
- 会话钩子（如果有）按 AgentLoop 策略执行。

### 3.6 LlmCoroutines

- `await()` 已恢复到协程 dispatcher；不需要额外 offload。
- `onDelta` 仍通过网关包装器。
- `LlmCoroutines.oneWithRetry` 的 `delay()` 不占载体；重试循环仍在协程内，不经过 callback executor。

---

## 4. 异常与重试

- 队列满新增 `LlmCallException.Kind.OVERLOAD`（additive）：
  - `RetryPolicy.defaultRetryable` 显式返回 false；
  - 错误消息“回调队列已满”；
  - 监控可按 Kind 区分。
- 回调异常按 `LlmCallException.Kind.UNKNOWN` 包装，保留 cause；不自动重试。
- `caller-runs` 会阻塞 transport 线程，仅作为显式背压选项；NIO 传输下不推荐。

---

## 5. 实施阶段

### Phase 0：契约与配置（P0）

- `AiLiteConfig.Callback` + `Callback.Executor`。
- `CallbackPolicy`（inline/io、queue capacity、rejection）。
- `direct + io` 冲突 fail-fast。
- 测试：配置绑定、默认值、冲突。

### Phase 1：SerialCallbackExecutor（P0）

- 单 in-flight FIFO、有界队列、取消清空、溢出 abort、完成屏障。
- 独立 callback 线程池（默认 `ai-lite-callback-`）。
- 测试：FIFO、单 in-flight、队列满、取消、溢出 abort、关闭幂等。

### Phase 2：Gateway（P0）

- `onDelta` 包装；完成屏障；异常收敛；同步 inline。
- 测试：阻塞 delta 不拖慢另一条流；顺序；future 完成时 delta 已全部回调；取消；异常。

### Phase 3：AgentLoop（P0）

- 续体 offload；工具前后钩子；工具完成回队；`finishOnce` + afterRun 所有终态。
- 测试：线程名、顺序、STOP/CONTINUE、工具顺序、afterRun 6 条路径、取消、同步完成 fake transport 不重入。

### Phase 4：文档与回归（P1）

- 配置文档、线程契约表、同步入口列表、MDC 不传播。
- 全量测试。

---

## 6. 测试矩阵

| 类别 | 用例 | 断言 |
| --- | --- | --- |
| 配置 | 默认 inline | 行为不变 |
| 配置 | direct + io | 启动失败 |
| 执行器 | FIFO / 单 in-flight | 顺序正确，不并发 |
| 执行器 | 队列满 abort | run 异常 + 传输 abort |
| 执行器 | 取消 | 队列清空，无新回调 |
| 执行器 | 关闭幂等 | 重复 close 不抛 |
| Gateway | 阻塞 onDelta | 另一条流不受影响 |
| Gateway | future 完成 | 所有 delta 已回调 |
| Gateway | onDelta 异常 | result 异常完成，传输 abort |
| AgentLoop | 钩子线程名 | 不出现 Jetty/Netty/OkHttp 线程 |
| AgentLoop | beforeTool/afterTool | 串行执行器，工具在 io() |
| AgentLoop | 工具后 afterTurn | 回队后执行 |
| AgentLoop | afterRun 6 条终态 | 恰好一次，异常不覆盖结果 |
| AgentLoop | 同步完成 transport | 不重入/不栈溢出 |
| AgentLoop | 取消 | 清空队列，afterRun 一次 |

---

## 7. 验收标准

- `mode=inline` 行为、性能、线程不变。
- `mode=io` 下用户回调和 7 个钩子不跑在共享 I/O 线程。
- `beforeTool`/`afterTool`/工具后 `afterTurn` 的线程归属符合契约。
- `afterRun` 在所有终态恰好一次。
- 队列满会 abort 传输，不只抛异常。
- 同步入口保持 inline，文档列全。
- 不引入死锁/饥饿；`direct + io` fail-fast。
- 全量测试通过。

---

## 8. 风险

| 风险 | 说明 | 建议 |
| --- | --- | --- |
| 回调 offload 延迟 | 每个事件一次线程切换 | 默认 inline；io 仅用于可能阻塞的回调 |
| 独立 callback 池成本 | 多一个线程池 | 有界；线程名可观测；可关闭 |
| 队列内存 | 每 run × 容量 × 事件大小 | 容量默认 1024；提供全局上限/监控 |
| 死锁 | 钩子等待 io() 任务 | 禁止在回调中同步等待；文档/测试 |
| 取消竞态 | future.cancel 与队列清空 | 单终态 + finishOnce |
| MDC/ThreadLocal | 线程变化不传播 | 文档；调用方显式恢复 |
| 自定义 Bean | policy 不自动应用 | 启动 WARN |
| 同步入口 | 仍 inline | 文档列全，避免误解 |

---

## 9. 与 HTTP 传输计划的关系

- HTTP 计划定义“完成线程”和“内部解析 inline”；本计划消费这个契约。
- 本计划可以在 OkHttp 默认路径上独立实施，不依赖 Jetty/WebClient/Ktor。
- HTTP 计划不再包含 `ai-lite.callback.*`；配置键统一在本计划。
