# 夯实回调执行策略

**状态（2026-10-24）：已实现并全量回归通过（执行记录见第 10 节）。** AgentLoop 异步泵结构（见《夯实AgentLoop异步执行器-plan.md》）已顺带完成本计划的一部分（CB-03 全部、CB-02 结构半边）。本计划从 `docs/夯实HTTP传输并发-plan.md` 拆出，专门处理“用户回调与 Agent 钩子不得无条件跑在共享 I/O 线程”的问题。它不改 HTTP 客户端实现。

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
| `oneByStreamAsync` | 完成线程 inline | 用户 `onDelta` 按 `mode`（StreamListener 是传输内部组件，不在包装范围，T-2） |
| `LlmCoroutines.*` | 完成线程 inline；`await()` 恢复到协程 dispatcher | 按 `mode` |
| `one` / `oneWithRetry` / `oneByStream` / `toolLoopByStream` | 调用线程 | 调用线程 inline |
| `AgentLoop.runAsync` | mode=io 时泵（drain）运行在 per-run 串行回调执行器，模型请求从泵发起 | 7 个钩子随泵按 `mode`；工具本体在 `io()`；例外：`beforeRun` 恒在调用方线程，`future.cancel` 路径的 afterRun 在取消方线程（D-2） |
| `AgentLoop.run` | 调用线程 | 调用线程 inline |
| `AgentSessionLoop.resumeAsync` | prepare/finish 在 `io()`；轮次复用 AgentLoop | 按 AgentLoop |

### 1.4 现状对照（2026-10-24，AgentLoop 异步泵落地后）

| 计划条目 | 现状 | 说明 |
| --- | --- | --- |
| CB-03 / §3.4 `finishOnce` | ✅ 已完成 | 异步泵的 `finished` CAS：成功/异常/取消/`future.cancel` 全部恰好一次 afterRun，error 可见 |
| CB-02 | 🔶 结构半边完成 | 工具完成回调现在只写结果槽 + 唤醒泵；afterTurn 聚合集中在 pump 单点。但 pump 跑在"触发推进的线程"上，线程归属仍不符合 mode=io 契约 |
| CB-01 | ❌ 未动 | `runTools` 整体（含 beforeTool/afterTool）仍提交到 `executor.io()` |
| Phase 0–2（配置、SerialCallbackExecutor、Gateway onDelta 包装/完成屏障） | ❌ 未动 | |
| CB-04/05/06/07/08/09/10 | ❌ 未动 | |

**Phase 3 需按泵重写**：原设想的"每个异步跳点手工投递到串行执行器"已被泵结构取代——mode=io 的正确落点是"泵本身运行在 per-run 串行回调执行器上"（advance 把 pump 派发到 SerialCallbackExecutor，其余结构不变）；beforeTool/afterTool 从 `runTools` 的 io-runnable 中拆出、改为投递。实施前先按此修订本计划并补审查。

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
    queue-capacity: 1024      # 每 run 的回调队列上限；溢出即 abort（无 caller-runs，见 D-1）
    executor:
      core-size: 2
      max-size: 8
      queue-capacity: 2048
      thread-name-prefix: ai-lite-callback-
```

- `mode=inline` 默认，不改变现有行为。
- `mode=io` 使用**独立的 callback executor**，不复用 `ExecutorProvider.io()`（CB-05 维持：钩子是任意用户代码，共享有界池仍是经典池饥饿；per-run 队列与共享池两层背压不可混用）。
- **D-1（审查采纳）：rejection-policy=caller-runs 剔除**——队列满时让提交线程就地执行会破坏单 in-flight FIFO 与泵的线程独占假设（两个回调并发、transport 线程与 callback 线程同时跑 pump），溢出统一走 abort 收敛。
- **D-3/S-1（审查采纳，CB-04 决策落定）：允许 `executor.mode=direct` 与 `callback.mode=io` 组合**——mode=io 的核心承诺（钩子离开 I/O 线程）由独立 callback 池保证，不再失效；残余后果是 direct 下工具本体在 callback 线程内联执行、阻塞本 run 回调通道，属吞吐问题，启动打一次 WARN + 文档写明，不再 fail-fast。
- `callback.mode` 只对异步入口生效；同步入口始终 inline，文档列全（CB-10）。

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

- 内置**三个**协议适配器（OpenAI Completions / Responses、Anthropic Messages）都通过 `TransportSupport.emit(onDelta, ...)` 调用，因此网关包装一次即可覆盖（T-1）。
- 完成屏障：transport future 完成后，不直接 `result.complete`；先把”完成事件”投递到同一串行执行器，保证所有已入队的 `onDelta` 先执行。**M-2（审查采纳）**：溢出/回调异常的收敛顺序固定为**先** `result.completeExceptionally(OVERLOAD)` **再** `cancel()`——顺序反了会让取消监听的 ABORTED 抢先占住 result、吃掉 OVERLOAD 语义；cancel 在先时监听因 `result.isDone()` 为 no-op，但 bindAbort 的传输中断仍触发。
- `onDelta` 抛异常：在串行执行器内捕获，按上述顺序收敛；同时 abort 传输。
- 取消：停止接收新 delta，清空队列；`future.cancel` 仍保持 JDK cancelled 语义。
- 同步入口（`one`/`oneByStream`）不包装，保持 inline。
- `oneWithRetryAsync` 的每个 attempt 经 `oneAsync` 各自包装、各自持有 per-attempt 串行执行器；attempt future 完成即 close，attempt N+1 新建（RetryRun 无需改动）。

### 3.4 AgentLoop（2026-10-24 按泵结构重写）

泵结构落地后，本节从"每个异步跳点手工投递"改为"**泵整体搬进回调执行器**"：

- `mode=inline`（默认）：现状不变——泵跑在触发推进的线程（调用方/传输/载体）。
- `mode=io`：`runAsync` 每个 run 创建一个 `SerialCallbackExecutor`；`advance()` 的 drain
  不再就地执行，而是投递到该执行器。回调（`onRoundDone`/`onToolsDone`）仍只写结果槽并唤醒；
  唤醒 = 投递一个 drain 任务。wip 计数协议不变（多次唤醒合并为一次 drain），泵的全部逻辑
  （afterModel、决策、afterTurn 聚合、发下一轮请求、afterRun）因此都落在 `ai-lite-callback-*`
  线程上，传输线程送完便条立刻回去接活。

```text
模型 future 完成（传输线程）──┐
                              ├─ 只写结果槽 + 唤醒 ──► 泵（callback 线程）：
工具批次完成（io/工具线程）────┘     （便条 + 按铃）      ├─ afterModel 钩子
                                                     ├─ 决策 / 写消息
                                                     ├─ 发下一轮请求（提交即返）
                                                     └─ afterRun（终态）
```

- **工具钩子拆离载体线程（修 CB-01）**：`submitToolBatch` 统一走异步链——泛化现有
  `runToolsAsyncChain`，不再只服务 `AsyncToolExecutor`。每个工具一段：
  `取消检查 → beforeTool（callback 线程）→ 工具执行（io()/toolPool）→ afterTool + 写回（callback 线程）`；
  串行语义（逐工具、执行器故障即停、逐工具取消检查、deny 不执行不 afterTool）全部保持。
  并行模式（`ToolRunMode.PARALLEL`）的三段结构天然符合：串行预检与按序写回本就在泵侧，
  只需随泵落在 callback 线程，并发执行段留在 io()/toolPool。
- **终态收敛已由泵完成**（`finished` CAS：成功/异常/取消/`future.cancel` 恰好一次
  afterRun），本计划不重复实现；`afterRun` 随泵运行在 callback 线程。
- `run`（同步入口）不变：全部 inline，`mode` 对它无效（CB-10 文档列全）。
- **D-2（审查采纳）**：`future.cancel` 路径的 afterRun 仍在**取消方线程** inline 执行——继承异步计划已锁定的"可接受差异、写入文档"决议，§1.3 表补例外说明；其余所有终态的 afterRun 随泵落在 callback 线程。`beforeRun` 恒在调用方线程（run 准入判定，早失败路径不进泵，T-3）。
- **M-1（审查采纳）**：`advance()` 的 drain 投递发生在 `wip.getAndIncrement()` 返回 0 的分支内，任意时刻队列中至多一个 drain 任务；投递被执行器拒绝（队列满/关闭）时在提交线程收敛：`convergeFailure(OVERLOAD)`（finished CAS 保证幂等）+ `ctx.getCancel().cancel()` 中断在途请求；finished 后一切后到回调与后到投递 no-op（`onRoundDone`/`onToolsDone` 的 finished 检查已具备）。
- **M-3（审查采纳）**：并行模式汇合去阻塞化——两种 mode 统一改为 `allOf(...)` 非阻塞组合 + `thenRun(stage3, callback 执行器)`，fail-fast 语义由"首个失败即异常完成汇合 future"保持；不再在泵线程 `join()`（会堵死单 in-flight 通道）。
- **M-4（审查采纳）**：并行单允许工具捷径——mode=inline 保留当前线程直接执行（现有测试锁定）；mode=io 时提交 `io()`，避免工具本体跑在 callback 线程违反契约。
- **M-6（审查采纳）**：取消清空队列 ≠ 取消链——泛化链的后续段是 future 组合不是排队任务；停止执行依赖逐工具段头的 `ctx.isCancelled()` 检查（现状语义：已执行工具跑完、未执行合成 skipped，保持）。
- **S-3（审查采纳）**：泛化链的"工具本体段"两个来源——`AsyncToolExecutor.executeOneAsync()` 的 future 直接使用（不得再包一层 io()，否则破坏 A7 不占载体承诺）；普通 `ToolExecutor` 用 `supplyAsync(io())`。`AsyncToolExecutor` 由此退化为链的一个特例。
- **S-4（审查采纳，风险表补充）**：在钩子内同步等待任何排在当前任务之后的回调任务必然死锁（单 in-flight 的结构性死锁，与 io 环无关），文档写明禁止。

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

- `AiLiteConfig.Callback` + `Callback.Executor`（无 rejection-policy，D-1）。
- `CallbackPolicy`（inline/io、queue capacity）。
- **CB-09（审查补落点）**：Starter 检测到用户自定义 `LlmGateway`/`AgentLoop` Bean 时，`callback.mode=io` 的策略不会自动应用——启动打 WARN（M-5）。
- direct + io 允许组合，启动 WARN 一次（D-3 决策）。
- 测试：配置绑定、默认值、WARN。

### Phase 1：SerialCallbackExecutor（P0）

- 单 in-flight FIFO、有界队列、取消清空、溢出 submit 返回 false（由调用方 abort 收敛）、关闭幂等。
- 独立 callback 线程池（`ai-lite-callback-`）。
- 测试：FIFO、单 in-flight、队列满、取消、关闭幂等。

### Phase 2：Gateway（P0）

- `onDelta` 包装；完成屏障；异常收敛；同步 inline。
- 测试：阻塞 delta 不拖慢另一条流；顺序；future 完成时 delta 已全部回调；取消；异常。

### Phase 3：AgentLoop 泵上回调执行器（P0，2026-10-24 重写）

- 3a：`advance()` 的 drain 按 `mode` 派发——inline 就地执行（现状）；io 投递到 per-run
  `SerialCallbackExecutor`；投递被拒在提交线程收敛（M-1）。wip 计数与泵结构不变。
- 3b：工具批次统一异步链（泛化 `runToolsAsyncChain`，`AsyncToolExecutor` 退化为特例）：
  beforeTool/afterTool/写回在 callback 执行器，工具本体在 `io()`/toolPool 或
  `executeOneAsync` future 直接使用（S-3）；并行汇合去阻塞化（M-3）、单允许工具捷径
  按 mode 分流（M-4）、取消清空≠取消链（M-6）。串行/并行语义与既有测试全部保持。
- 3c：既有 AgentLoop 系列全部测试（AgentLoop*，统计口径见 testing/README）全绿
  （mode=inline 默认路径语义零变化——inline 下泛化链以 `executor.io()` 兼作 callback
  执行器，任务数会变多但行为等价）。
- 测试：钩子线程名断言（`ai-lite-callback-`，且不出现 OkHttp/Jetty/ForkJoin 线程）、
  工具线程名断言（io 载体）、执行器模式下 trampoline 万轮不爆栈、STOP/CONTINUE 顺序、
  取消、同步完成 fake transport 不重入、afterRun 线程名、drain 被拒收敛（M-1）、
  future.cancel 路径 afterRun 在取消方线程（D-2 例外锁定）。

### Phase 4：文档与回归（P1）

- 配置文档、线程契约表、同步入口列表、MDC 不传播。
- 全量测试。

---

## 6. 测试矩阵

| 类别 | 用例 | 断言 |
| --- | --- | --- |
| 配置 | 默认 inline | 行为不变 |
| 配置 | direct + io | 允许组合，启动 WARN（D-3） |
| 配置 | 自定义 Gateway/AgentLoop Bean + mode=io | 启动 WARN（CB-09/M-5） |
| 执行器 | FIFO / 单 in-flight | 顺序正确，不并发 |
| 执行器 | 队列满 | submit 返回 false，由调用方 abort 收敛 |
| 执行器 | 取消 | 队列清空，无新回调 |
| 执行器 | 关闭幂等 | 重复 close 不抛 |
| Gateway | 阻塞 onDelta | 另一条流不受影响 |
| Gateway | future 完成 | 所有 delta 已回调 |
| Gateway | onDelta 异常 / 队列溢出 | 先 OVERLOAD 异常完成、再 cancel（M-2 顺序） |
| Gateway | OVERLOAD | RetryPolicy 不重试（M-5） |
| AgentLoop | 钩子线程名 | `ai-lite-callback-*`；不出现 Jetty/Netty/OkHttp/ForkJoin 线程 |
| AgentLoop | 工具线程归属 | beforeTool/afterTool 在串行执行器；工具本体在 io()（线程名断言） |
| AgentLoop | 泵 drain 派发 | 同步完成 transport 不重入；执行器模式下万轮不爆栈 |
| AgentLoop | drain 投递被拒 | OVERLOAD 收敛 + 在途请求被中断（M-1） |
| AgentLoop | future.cancel 路径 | afterRun 恰好一次且在取消方线程（D-2 例外） |
| AgentLoop | afterRun 6 条终态 | 恰好一次，异常不覆盖结果 |
| AgentLoop | 同步完成 transport | 不重入/不栈溢出 |
| AgentLoop | 取消 | 清空队列，afterRun 一次 |

---

## 7. 验收标准

- `mode=inline` 行为、性能、线程不变。
- `mode=io` 下用户回调和 7 个钩子不跑在共享 I/O 线程（例外已文档化：`beforeRun` 调用方线程、`future.cancel` 路径取消方线程）。
- `beforeTool`/`afterTool`/工具后 `afterTurn` 的线程归属符合契约。
- `afterRun` 在所有终态恰好一次。
- 队列满会 abort 传输，不只抛异常。
- 同步入口保持 inline，文档列全。
- 不引入死锁/饥饿；direct + io 允许组合并 WARN。
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

## 9. 审查记录（2026-10-24，独立子代理）

- **总体**：泵改法经逐行对照确认成立——投递放 `wip` 0→1 分支内则无双重 drain、无丢失唤醒；泵与串行执行器组合无死锁环（泵只投递不等待）。
- **设计缺陷 3（已落）**：D-1 caller-runs 剔除（破坏单 in-flight/泵独占）；D-2 future.cancel 路径 afterRun 留在取消方线程（文档化例外）；D-3 CB-04 决策落定=允许 direct+io 组合 + WARN，Phase 0/矩阵/验收已统一回改。
- **遗漏 6（已补）**：M-1 drain 被拒收敛；M-2 溢出先 OVERLOAD 后 cancel 的顺序；M-3 并行汇合去阻塞化；M-4 单允许工具捷径按 mode 分流；M-5 OVERLOAD 不重试测试 + CB-09 启动 WARN 落点；M-6 清空队列≠取消链。
- **表述 4（已改）**：三个 transport（非四个）；StreamListener 是传输内部组件移出包装范围；beforeRun 恒调用方线程；测试统计口径改"AgentLoop* 系列"。
- **采纳建议**：S-1（CB-04 结论）、S-2（CB-05 维持独立池）、S-3（AsyncToolExecutor 链特例）、S-4（钩子内等待后续回调任务必死锁的禁止说明）。

---

## 10. 执行记录（2026-10-24，全部完成）

Phase 0–4 当日实现完毕，全量回归通过：core 475（+7）、全模块 746，0 失败。

### 已落地

| 阶段 | 内容 |
| --- | --- |
| Phase 0 | `AiLiteConfig.Callback`（mode/queue-capacity/executor 嵌套）；Starter `aiLiteCallbackPolicy` + `aiLiteCallbackExecutor`（仅 io 模式创建，daemon 线程，容器关闭自动 shutdown）；CB-09 自定义 Bean WARN、D-3 direct+io 组合 WARN |
| Phase 1 | core `SerialCallbackExecutor`（单 in-flight FIFO worker、有界队列、submit false 背压、close 清空幂等、共享池拒绝归位）+ `CallbackPolicy`（inline/io 静态工厂） |
| Phase 2 | `LlmGateway.setCallbackPolicy`；`invokeAsync` 流式且 io 模式时 per-call 串行执行器承接 onDelta；完成屏障 `completeBarrier/failBarrier`（CB-07：future 完成前所有 delta 已回调，异常路径同样冲刷）；`failOverload` 按 M-2 顺序（先 OVERLOAD 异常完成再 cancel）；`Kind.OVERLOAD` additive（`defaultRetryable` 落 default=false） |
| Phase 3 | `AgentLoop` 6 参构造器注入策略；`AsyncRun` per-run 串行执行器；`advance()` drain 派发（io 投递/inline 就地，M-1 投递被拒在提交线程 convergeFailure(OVERLOAD)+cancel）；工具链泛化——`submitToolBatch` 统一链，beforeTool/afterTool/写回走 `callbackExec`，工具本体双来源（`executeOneAsync` future 直接用/普通 `supplyAsync(io)`，S-3）；并行异步版三段非阻塞（M-3 fail-fast 汇合、M-4 异步无捷径）；同步 `run` 的 `runToolsParallel` 不变；终态三收敛点关闭回调通道 |
| Phase 4 | 文档：configuration.md §3.1 新节、api-usage onDelta 提示、testing/README、README 统计、本记录 |

### 测试（新增 7 条，全部通过）

- `SerialCallbackExecutorTest` 4：单 in-flight FIFO（50 任务顺序+最大并发 1）、队列满 false、close 清空幂等、inline/io 策略。
- `AgentLoopIoModeTest` 3：io 模式 7 钩子全部 `ai-lite-callback-*` 且工具本体在 `ai-lite-io-*` 载体池（Direct io 内联是 D-3 文档化组合，测试用命名载体池）；执行器模式 trampoline 5000 轮不爆栈 9 秒内；beforeModel 取消收敛 ABORTED、afterRun 恰好一次。

### 实现期确认的行为边界

- inline 模式下泛化链以 `executor.io()` 兼作钩子执行器：direct 载体池时钩子与工具本体同线程内联（与现状语义等价），platform 载体池时多 2N 次池交接但行为等价——"默认路径零变化"的口径是语义而非任务数。
- `AgentLoopIoModeTest` 首版用 `DirectExecutorProvider` 断言工具在载体线程失败——正是 D-3 场景的直接体现：工具本体落在回调线程。测试改用命名非 direct 载体池后通过，D-3 的 WARN 提示必要。
- 一次全量回归偶发失败（core），连续两次重跑全绿，确认与并发资源争用相关而非代码缺陷。

### 与计划的偏差

- 无实质偏差；`rejection-policy` 配置项按 D-1 直接未实现（计划原文的 yaml 示例已修订）。

---

## 9. 与 HTTP 传输计划的关系

- HTTP 计划定义“完成线程”和“内部解析 inline”；本计划消费这个契约。
- 本计划可以在 OkHttp 默认路径上独立实施，不依赖 Jetty/WebClient/Ktor。
- HTTP 计划不再包含 `ai-lite.callback.*`；配置键统一在本计划。
