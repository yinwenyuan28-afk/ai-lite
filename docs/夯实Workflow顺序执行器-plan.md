# 夯实 Workflow 顺序执行器

**状态（2026-10-22）：已按 Phase 0–5 实现并回归通过。** 审查结论与补充决策见第 10 节，执行记录见第 11 节。同步版本已落地；异步 `runAsync` 留作后续，不在本轮范围。

一句话总纲：

> **Workflow 是“可预测的顺序执行器”，不是编排框架；它只组合已有的单次入口，任何新增能力都必须能用一条可断言的顺序级契约说清楚。**

---

## 1. 定位与范围

### 1.1 它是什么

`Workflow` 把“单次调用”和“简单工具循环”按调用方给定的顺序执行，返回每步结果。它不决定下一步做什么，不持有会话，不做模型驱动的循环。

### 1.2 本次范围

- `Workflow` 的 `one` / `oneByStream` / `toolLoop` / `toolLoopByStream` 委托方法。
- `run` / `execute` / `Step` / `oneStep` / `toolLoopStep`。
- 必要的网关前置重载：同步 `toolLoop(call, tools, maxRounds, cancel)`。`round(call, tools, cancel)` 在 Workflow 中没有调用点，本次不补，避免范围蔓延。
- 网关前置修复（审查 2 发现，影响 `toolLoopStep`）：同步 `ToolExecutor.execute` 异常/null 收口；异步工具循环终态记录被首轮 `TOOL_USE` 吞掉；工具执行后取消缺 `ABORTED` 记录；同步多轮记录 usage/latency 语义不一致。
- `WorkflowTest` 扩充与文档同步。

### 1.3 明确不在本次范围

- `AgentLoop.run` / `runAsync`
- `AgentSessionLoop.resume` / `resumeAsync`
- `WorkflowSpec` 的运行时引擎化
- 异步 `runAsync`、并行步骤、DAG、条件分支
- 自动重试、退避、熔断、步骤级超时
- 钩子、中间件、步骤注册表、跨步骤自动传值
- 把 `AgentLoop` 作为步骤（继续禁止）

### 1.4 归属判断规则

> 如果一个能力需要“读取上一步输出再决定下一步”，它属于 `AgentLoop`、`WorkflowSpec` 实现或业务自己的 `for`，不属于 `Workflow`。

---

## 2. 指导原则

九条原则按优先级排列。每条都给出“代码落点”和“验收方式”，避免变成口号。

### P1 只组合，不做决策

- **含义**：Workflow 不规划、不分支、不重规划，只按给定顺序执行。组合单元仅限单次调用和简单工具循环，**不含 `AgentLoop`**。
- **代码落点**：`run` 只顺序迭代；不新增 `if/else`、条件步骤、循环步骤、接收上一步结果再返回下一步的回调。
- **验收**：`run` 只接收静态 `List`；API 形状审查确认没有“结果→下一步”的函数式入口。`WorkflowSpec` 里的业务 `if/for` 属于业务代码，不受本原则约束。

### P2 不改写下游结果与根因

- **含义**：四个直通方法（`one` / `oneByStream` / `toolLoop` / `toolLoopByStream`）必须原样返回网关结果；`run` / `execute` 只允许在**步骤边界**增加顺序级包装。
- **代码落点**：直通方法不吞异常、不重试、不改 prompt/工具/超时；`WorkflowStepException`、`WorkflowCancelledException` 必须保留原始异常为 cause；不得修改已完成步骤的 `LlmResult` 字段。
- **验收**：同一 fake transport 下，直通方法与网关的结果字段、异常类型、cause 完全一致；`run` 的包装异常只新增 stepIndex/已完成结果，不改写 cause 和已完成结果。

### P3 先做结构校验，再产生任何请求

- **含义**：LLM 调用花钱、工具可能有业务副作用，步骤列表的结构错误必须在第一步执行前被发现。
- **代码落点**：`run` 先对原始列表做**不可变快照**，再对快照做结构校验：null 列表、null 元素、非 `Step`、`AgentLoop`。`LlmCall`、工具列表等业务校验仍由网关在发请求前完成，Workflow 不深入 Step 内部。
- **验收**：`[合法步骤, AgentLoop]`、`[合法步骤, null]`、`[合法步骤, "x"]` 都零 HTTP 请求失败；结构错误与预取消同时存在时，结构错误优先。

### P4 取消分层：顺序层保证 L2，在途层交给网关

- **含义**：Workflow 保证取消后不再启动下一步；当前在途 HTTP 由 `LlmCancelHandle` 中止。不承诺传输层做不到的 L1。
- **代码落点**：`run(steps, cancel)` 在每步开始前检查；每步结果返回后识别 `ABORTED`；`Step` 增加向后兼容的 `execute(gateway, cancel)` 默认方法，内置步骤把句柄透传给网关。默认方法委托旧方法，因此**自定义 Step 若忽略 cancel，只保证步骤边界 L2，不保证在途 L1**。
- **句柄生命周期**：同一次 `run` 内顺序复用同一句柄，前提是尚未 abort；正常完成的步骤不会让句柄进入终态。一旦 abort，句柄终态，`run` 必须停止，不得用于下一次调用。步骤间取消触发的可能是上一步遗留 abort，属于已关闭连接上的无害动作，需要测试锁定。
- **验收**：预取消零 HTTP 请求；步骤间取消不再发下一步；在途取消由 abort-aware fake transport 观察到 abort；取消不重试、不继续；预取消仍可能写一条 `aborted` 调用记录（L3 不保证）。

### P5 失败必须可定位，且不泄露敏感信息

- **含义**：异常要回答“第几步、什么原因、已经完成了什么”，保留根因，但异常消息不能带 prompt、图片、密钥。
- **代码落点**：捕获步骤抛出的 `RuntimeException`，包装为带 `stepIndex`、不可变已完成结果的异常；cause 保留原始异常；`Error` 不包装。异常消息只拼步骤序号和安全摘要，不复制 cause 的完整消息。
- **验收**：中途失败时异常包含索引和 cause；`getCompletedResults()` 不可变；已完成结果字段不被改写；消息中不出现提示词、图片 base64、api key。流式步骤已产生部分增量后失败时，已完成结果**不包含**该步的部分输出。

### P6 同步、顺序、无隐藏行为

- **含义**：第一轮只做同步顺序执行；不自动并行、不自动重试、不自动加超时、不做步骤间数据流。
- **代码落点**：不新增线程池、不调用 `CompletableFuture`、不引入 Sleep/退避。每个步骤只调用一次网关入口，不额外发请求。
- **自定义 Step 契约**：`Step.execute` 必须在返回前完成其全部工作；Workflow 不等待 Step 自行创建的异步任务。
- **既有异步记录不算隐藏行为**：调用记录通过 `ExecutorProvider.io()` 异步写入是已发布行为，不属于 Workflow 新增线程。
- **验收**：`run` 在调用线程执行；总请求数 = Σ 每步入站请求数（`oneStep` = 1，`toolLoopStep` = 1..maxRounds），没有额外的第二次请求；没有隐藏的重试或并行。

### P7 无状态、可并发、返回不可变

- **含义**：Workflow 不持有会话、不跨 `run` 缓存结果、不记录全局状态；返回值和异常中的已完成结果都是不可变快照。
- **代码落点**：除构造注入的 `gateway` 外无实例字段；`run` 内部的局部结果列表是必要的，但对外返回 `Collections.unmodifiableList` 或等价不可变视图。
- **并发范围**：并发保证只覆盖 Workflow 自身和内置无状态步骤；自定义 Step 的线程安全由调用方负责。
- **验收**：同一实例多线程 `run` 结果互不串扰；返回列表和 `getCompletedResults()` 修改时抛 `UnsupportedOperationException`。

### P8 不造第二套编排，保持兼容

- **含义**：不做 DSL/DAG/引擎；`WorkflowSpec` 是业务接口，不是运行时。兼容性指**签名只增不改 + 合法输入的既有行为不变**。
- **代码落点**：新增重载和默认方法；保留 `one` / `oneByStream` / `toolLoop` / `toolLoop(call, tools)` / `toolLoopByStream` / `run(List<?>)` / `execute(Object)` / `oneStep` / `toolLoopStep` 的签名。`Step` 新方法必须是 default。
- **非法输入收紧**：预校验、不可变返回、`ABORTED` 归一为取消异常、`oneStep(null)` 工厂期拒绝，都属于非法输入行为收紧，在 4.x 明示，不视为破坏兼容。
- **验收**：现有 7 个用例不改断言通过；自定义 `Step` 无需改代码即可继续使用；没有新增 DSL/注册表/引擎。

### P9 不做回滚与幂等，部分完成必须可见

- **含义**：Workflow 不提供回滚、补偿、幂等键或断点续跑；每个 Step 是唯一的原子边界。
- **代码落点**：不新增事务、补偿、重放逻辑；失败/取消异常携带的已完成结果只用于诊断。
- **验收**：步骤失败或取消时，已完成步骤的副作用已经发生；文档明确“重跑 `run` 会重复执行所有步骤、重复计费”；`getCompletedResults()` 不被描述为恢复入口。

### 原则之间的约束关系

- P1 决定“不做什么”，P6 决定“怎么做”，P3/P4/P5 是顺序执行器必须补的三类保证，P2/P7/P8 是防止演化和兼容性失控的护栏。
- 当原则冲突时，优先级是：**P3（结构安全）> P4（取消）> P5（可定位）> P6（简单）**。P2 已限定为直通语义与 cause 保留，不再与 P4/P5 冲突；P8 是约束（签名只增不改、合法输入行为不变），不是可交换的优先级。

---

## 3. 现状核查

已知事实：

| 事实 | 位置 | 影响 |
| --- | --- | --- |
| `run` 边执行边校验 | `Workflow.java:57-66` | `[合法步骤, AgentLoop]` 会先发真实请求再报错 |
| `one` 没有取消重载 | `Workflow.java:30-32`；网关已有 `one(call, cancel)` | 同步单步无法在途取消 |
| `toolLoop` 没有取消重载 | `Workflow.java:38-44`；网关同步 `toolLoop` 也没有 | 工具循环步骤无法统一取消 |
| `run` 没有统一取消句柄 | `Workflow.java:57` | 多步执行无法整体停止 |
| 失败无步骤定位 | `Workflow.java:63,81` | 异常不知道停在第几步、已完成什么 |
| 仅同步 | `Workflow.java` 全部方法 | 无 `runAsync`，符合第一轮范围 |
| 步骤列表是 `List<?>` | `Workflow.java:57` | 类型不安全，依赖运行时校验 |
| 仅 7 个测试 | `WorkflowTest.java` | 无取消、失败定位、预校验、并发用例 |

审查 2 需要补充：空列表语义、null 元素、步骤抛 `Error`、结果列表可变性、`Step` 捕获 gateway 后异步阻塞、重复执行、流式步骤取消、文档与实现不一致等。

---

## 4. 目标契约

### 4.1 输入契约

- `run(null)` → `LlmCallException("Workflow 步骤不能为空")`。
- `run(empty)` → 返回空不可变列表，不检查取消、不报错。
- 列表中任一元素为 `null` / 非 `Step` / `AgentLoop` → 在任何步骤执行前抛 `LlmCallException`。
- `execute(null)` → `LlmCallException("步骤不能为空")`（改进报错信息，不改变“拒绝 null”的语义）。
- 自定义 `Step` 返回 `null` → 视为步骤失败，抛带 index 的 `WorkflowStepException`，结果列表不出现 null。
- `oneStep(null)`、`toolLoopStep(null, ...)`：在工厂调用时立即拒绝（非法输入收紧，不改变合法输入行为）。
- 步骤列表在进入 `run` 时做不可变快照；结构校验与预取消同时存在时，结构错误优先。
- `call` / `tools` 在工厂期做浅拷贝，避免调用方在 run 期间修改影响已构造步骤；`Step` 对象本身不深拷贝。

### 4.2 执行契约

- 严格按列表顺序执行，前一步完成后才开始下一步。
- 返回列表长度等于步骤数，顺序一致；返回不可变列表。
- 不合并、不跳过、不去重。

### 4.3 取消契约

- `run(steps, cancel)`：
  - 进入 `run` 时已取消 → 零请求，抛 `WorkflowCancelledException`，`completedResults` 为空。
  - 第 i 步开始前已取消 → 不执行第 i 步，异常携带前 i 步结果。
  - 第 i 步返回 `ABORTED` → 不再执行第 i+1 步，异常携带包含该 `ABORTED` 结果在内的已完成结果。
  - 第 i 步抛出异常且此时 `cancel.isCancelled()` → 归类为取消，而不是普通失败。
- `WorkflowCancelledException extends LlmCallException`，至少提供 `getStepIndex()`、`getCompletedResults()`（不可变快照），并保留原始 cause。
- 传入 `null` 句柄等价于不可取消；行为与现有 `run(steps)` 一致。
- **`ABORTED` 的权威性**：结果列表中出现 `ABORTED` 即视为取消，无论是否传句柄；`run(steps)` 无句柄时也抛 `WorkflowCancelledException`。这是取消路径的行为收紧。
- **其他停止原因**：`MAX_ROUNDS`、`LENGTH`、`CONTEXT_LENGTH_EXCEEDED`、`STOP` 都继续执行下一步；只有 `ABORTED` 终止。`ERROR` 网关正常不会作为结果返回（以异常表达）；自定义 Step 若返回 `ERROR`，按普通结果继续，文档写明。
- **空列表 + 预取消**：空列表直接返回空，不检查取消。
- **句柄复用规则**：同一次 `run` 内顺序复用同一句柄，前提是尚未 abort；正常完成的步骤不会让句柄进入终态。一旦某步 abort，句柄终端化，`run` 停止，不得用于下一次调用。**并发 `run` 必须各用各的句柄，不得共享。** 步骤间取消可能触发上一步遗留的 abort（已关闭连接上的无害动作），需要测试锁定。

### 4.4 失败契约

- 第 i 步抛出 `RuntimeException`：
  - 未取消 → 抛 `WorkflowStepException extends LlmCallException`，提供 `getStepIndex()`、`getCompletedResults()`（不可变快照），cause 为原始异常。
  - 消息只包含步骤序号和安全摘要，不包含提示词、图片、密钥；不复制 cause 的完整消息。
- `Error`（如 `OutOfMemoryError`）不包装、不吞。
- 预校验失败使用 `LlmCallException`，不包装成步骤异常。
- 流式步骤已产生部分增量后失败时，`getCompletedResults()` 不包含该步的部分输出。

### 4.5 兼容契约

- `run(List<?>)` 等价于 `run(steps, null)`。
- `execute(Object)` 等价于 `execute(step, null)`。
- `Step` 新增 `default LlmResult execute(LlmGateway gateway, LlmCancelHandle cancel)`，默认委托旧方法；现有实现无需修改。自定义 Step 若忽略 cancel，只保证步骤边界 L2，不保证在途 L1。
- 统一返回不可变列表；异常中的已完成结果也是不可变快照。
- `oneStep(null)` / `toolLoopStep(null, ...)` 改为工厂期拒绝，属于非法输入收紧；合法输入行为不变。
- `run(steps)` 无句柄时，`ABORTED` 结果也转 `WorkflowCancelledException`；这是取消路径的行为收紧，不视为破坏兼容。
- 现有 7 个用例只读断言，预校验和不可变返回后仍应通过。

---

## 5. 实施阶段

### Phase 0：网关前置（P0）

- 新增 `LlmGateway.toolLoop(LlmCall, List<LlmTool>, Integer, LlmCancelHandle)`。
- 复用现有同步 `invoke(..., cancel)`，只补公开入口和测试。
- 同步 `ToolExecutor.execute` 包 try/catch：异常包装为 `LlmCallException`，`null` 结果按空处理，并写终态 `ERROR` 记录（对齐异步路径）。
- 修复异步工具循环记录：终态 `STOP`/`ERROR`/`MAX_ROUNDS`/`ABORTED` 必须落一条，不能被首轮 `TOOL_USE` 的 `recorded` 标志吞掉。
- 工具执行后取消补 `ABORTED` 记录；统一同步/异步多轮记录的 usage/latency 语义（每轮独立或最终累计，二选一）。
- `round(call, tools, cancel)` 没有 Workflow 调用点，本次不补；如后续自定义 Step 需要，另立小改动。
- 验收：与异步重载的取消语义一致；`cancel` 预取消零 HTTP 请求；记录回归用例覆盖 W-04/W-11/W-12/W-13。

### Phase 1：预校验（P0）

- `Workflow.run` 先校验整表，再执行。
- 抽出 `validateStep(Object)` 供 `run` 和 `execute` 复用。
- 验收：所有非法列表零请求失败；现有 7 个用例保持通过。

### Phase 2：取消（P0）

- `run(List<?>, LlmCancelHandle)`、`execute(Object, LlmCancelHandle)`。
- `one(LlmCall, LlmCancelHandle)`。
- `oneStep(call, cancel)`、`toolLoopStep(call, tools, maxRounds, cancel)`。
- `Step.execute(gateway, cancel)` 默认方法；内置步骤覆盖。
- 新增 `WorkflowCancelledException`。
- 验收：4.3 全部场景。

### Phase 3：失败定位（P1）

- 新增 `WorkflowStepException`。
- 包装步骤异常，保留 cause 与已完成结果。
- 验收：4.4 全部场景。

### Phase 4：边界与并发（P1）

- 空列表、单步、不可变返回、并发调用、重复执行。
- 自定义 `Step` 返回 `null` → 步骤失败；返回 `ABORTED` → 取消；返回其他停止原因 → 继续。
- 明确并测试“自定义 Step 可以绕过 AgentLoop 禁令和同步契约”是已知边界，不在本轮引入受限网关门面（会破坏兼容性）。
- 确认 `Workflow` 无新增可变状态；并发 `run` 各用各的取消句柄。
- 验收：并发 `run` 结果互不串扰；返回列表不可修改；null 结果不进入结果列表。

### Phase 5：测试与文档（P1）

- `WorkflowTest` 扩充到约 25 个用例；必要时新增 `WorkflowCancellationTest` / `WorkflowFailureTest`。
- 新增 abort-aware fake transport（`bindAbort` 可观察），否则无法验证在途取消。
- 更新 `docs/api-usage.md` §4.1、`docs/testing/README.md`，并在本文档回填审查结论与执行记录。
- 验收：全量测试通过，文档与实现一致。

---

## 6. 测试矩阵

| 类别 | 用例 | 断言 |
| --- | --- | --- |
| 预校验 | null 列表 / null 元素 / 非 Step / AgentLoop 在首位 / AgentLoop 在末位 | 全部零请求失败 |
| 预校验 | 空列表 | 返回空列表，不发请求 |
| 预校验 | 结构错误 + 预取消并存 | 结构错误优先，零请求 |
| 取消 | 进入前已取消 | 零请求，`stepIndex=0`，已完成结果为空 |
| 取消 | 第 1 步后取消 | 第 2 步不发请求，异常携带第 1 步结果 |
| 取消 | 在途取消（步骤句柄） | 传输层 abort 被观察，当前步 `ABORTED`，后续不执行 |
| 取消 | 步骤返回 `ABORTED` | 后续步骤不执行，异常包含该结果 |
| 取消 | 步骤抛异常且已取消 | 归类为取消，不是普通失败 |
| 失败 | 第 1 步抛异常 | `WorkflowStepException`，index=0，cause 保留 |
| 失败 | 第 2 步抛异常 | index=1，已完成结果含第 1 步 |
| 失败 | 步骤抛 `Error` | 原样抛出，不包装 |
| 失败 | 自定义 Step 返回 null | 带 index 的 `WorkflowStepException`，结果不含 null |
| 停止原因 | 自定义 Step 返回 `MAX_ROUNDS` / `LENGTH` | 继续执行下一步 |
| 停止原因 | 自定义 Step 返回 `ABORTED`（无句柄） | 抛 `WorkflowCancelledException`，后续不执行 |
| 结果 | 正常多步 | 顺序、数量、字段与网关一致 |
| 结果 | 返回列表不可变 | 修改抛 `UnsupportedOperationException` |
| 并发 | 同一实例并发 run | 结果互不串扰，请求总数正确 |
| 兼容 | 旧 `run(steps)` / 自定义 Step | 行为不变，无需改代码 |
| 边界 | 自定义 Step 内调用 `AgentLoop` | 锁定“可绕过”的已知边界，文档写明 |
| 记录 | `toolLoopStep` 多轮/取消 | 终态记录存在且 stopReason 正确（W-04/W-12） |
| 流式 | 流式步骤取消 | 回调收到取消前的增量，结果 `ABORTED` |

所有异步/阻塞断言带 5 秒超时；禁止无超时 `join()`。

---

## 7. 验收标准

- 第 2 节九条原则都有对应测试或明确的“不适用”说明。
- `[合法步骤, AgentLoop]` 等非法列表在任何 LLM 请求前失败。
- 取消在步骤边界和结果层都可观察，后续步骤不再发起。
- 失败异常包含步骤序号、已完成结果和根因。
- 现有 7 个 Workflow 用例不改断言通过。
- 新增测试约 18–25 个，全量测试不挂起。
- 不修改 `AgentLoop` / `AgentSessionLoop` / `WorkflowSpec` 签名。

---

## 8. 明确不做

- 条件分支、循环、DAG、并行步骤、跨步骤自动传值。
- 自动重试、退避、熔断、步骤级超时。
- 回滚、补偿、幂等键、断点续跑；`getCompletedResults()` 只用于诊断，不是恢复入口。
- Workflow 层新增调用记录、日志或指标；调用记录仍由网关负责，避免重复计数。多步骤同 bizId 无法按步骤归因，文档写明。
- 总预算、总 deadline；成本上界由步骤列表和每步 `maxRounds` 决定。
- 流式步骤工厂（`oneByStreamStep` / `toolLoopByStreamStep`）；`run` 内需要流式时使用自定义 Step，并遵守“返回前完成全部工作”的契约。
- `Thread.interrupt()` 支持；取消只能通过 `LlmCancelHandle`。
- 钩子、中间件、步骤 AOP。
- 异步 `runAsync`。
- 嵌套 `AgentLoop`。
- 把 `WorkflowSpec` 变成运行时引擎。

---

## 9. 与其他编排层的关系

- `AgentLoop`：模型驱动的多轮工具循环 + 7 个生命周期钩子；Workflow 不能替代，也不能嵌入。
- `AgentSessionLoop`：带持久化的会话续聊；Workflow 不读写会话。
- `WorkflowSpec` / `Assembly`：业务代码组织方式，不是 Workflow 的执行引擎。

Workflow 夯实完成后，同样的“预校验 / 取消分层 / 失败可定位”模式再向 `AgentLoop` 和 `AgentSessionLoop` 推广。

---

## 10. 审查记录

### 审查 1：原则检验

**结论：不能按原稿直接实现，已按审查修正。** 主要问题与处理：

1. **P2 与 P4/P5 冲突**：原文要求“异常类型原样保留”，会否决 `WorkflowCancelledException` / `WorkflowStepException`。已改为“直通方法不改结果，步骤边界允许包装且必须保留 cause”。
2. **P4 与句柄一次性冲突**：已明确“同一次 run 内、尚未 abort 时可顺序复用；一旦 abort 即停止，不得复用”，并补 abort-aware fake transport 要求。
3. **P3 过宽**：已限定为结构校验（null/非 Step/AgentLoop）+ 列表快照；业务参数仍由网关校验；结构错误优先于预取消。
4. **P5 缺脱敏与不可变**：已补异常消息脱敏、`getCompletedResults()` 不可变、流式部分输出不进入已完成结果。
5. **P6 请求计数错误**：已改为“总请求数 = Σ 每步入站请求数”，并补自定义 Step 同步契约与调用记录归属。
6. **P7/P8 缺口**：已补返回不可变快照、并发范围限定、兼容性改为“签名只增不改 + 合法输入行为不变”。
7. **新增 P9**：明确不做回滚/补偿/幂等/断点续跑，部分完成副作用已发生。
8. **范围修正**：Phase 0 删除没有调用点的 `round(call, tools, cancel)`；§8 补三条非目标（回滚/可观测性归属/总预算）。

### 审查 2：Workflow 全量缺陷检查

共 25 条：**P0 3 条、P1 5 条、P2 17 条**。完整缺陷表与复现思路见本节末尾摘要。

**P0**

1. **W-01 `run` 边执行边校验**：`[合法 Step, AgentLoop/null/非 Step]` 会先执行前面的步骤、发出真实请求，再在非法元素处失败。计划 Phase 1 已覆盖：先快照、整表结构校验、再执行。
2. **W-02 `run` 无法整体取消，`ABORTED` 不终止**：后续步骤继续发请求。计划 §4.3 已覆盖：`run(steps, cancel)`、`ABORTED` 权威性、`WorkflowCancelledException`。
3. **W-03 `one` / `toolLoop` 没有取消入口**：计划 Phase 0/2 已覆盖：网关补 `toolLoop(..., cancel)`，Workflow 补取消重载与步骤工厂。

**P1**

4. **W-04 异步工具循环终态记录被吞**：`recordOnce` 的全局 `recorded` 让首轮 `TOOL_USE` 吃掉最终 `STOP/ERROR/ABORTED` 记录；同步路径正常。已补进 Phase 0。
5. **W-05 失败无步骤上下文**：计划 §4.4/Phase 3 已覆盖。
6. **W-06 返回列表可变、null 结果语义不明**：计划 §4.1/§4.2/Phase 4 已覆盖：不可变返回、null 结果视为步骤失败。
7. **W-07 步骤列表无快照**：计划 §4.1/Phase 1 已覆盖：不可变快照；Step 对象不深拷贝，并发修改由调用方负责。
8. **W-11 同步 `ToolExecutor.execute` 异常/null 未收口**：异常原样逃逸、null 触发 NPE、无 `ERROR` 记录。已补进 Phase 0。
9. **W-08 自定义 Step 可绕过 AgentLoop 禁令与同步契约**：`Step.execute(LlmGateway)` 把完整网关交给业务，能内部 `new AgentLoop(...)` 或发起未 join 的异步请求。**决策：接受为已知边界，不引入受限网关门面**（会破坏 `Step.execute` 签名）。Phase 4 增加锁定测试，文档写明“禁令只覆盖 `run`/`execute` 的直接参数”。

**P2 重点**

- W-12 工具执行后取消缺 `ABORTED` 记录；W-13 同步多轮记录 usage/latency 语义不一致；W-21 网关错误摘要只脱敏 apiKey。已补进 Phase 0 或文档边界。
- W-09 工厂期校验范围；W-10 `execute(null)` 报错信息；W-14 `call`/`tools` 快照；W-15 `ERROR/MAX_ROUNDS` 是否终止；W-18 并发 run 共享句柄；W-19 流式步骤工厂；W-20 不支持 `Thread.interrupt`；W-22 空列表 + 预取消；W-23 记录无法按步骤归因；W-24 自定义 Step 返回 null。
  - 已决策：`oneStep(null)` / `toolLoopStep(null, ...)` 工厂期拒绝；`call`/`tools` 浅拷贝；只有 `ABORTED` 终止；空列表直接返回空、不检查取消；并发 run 各用各的句柄；不补流式步骤工厂；不支持 `Thread.interrupt`；不新增 Workflow 记录字段。
- W-16/W-17/W-25 是文档与 API 形状问题：Phase 5 逐条修正；`List<?>` + `Object` 的类型不安全是有意保留的兼容性取舍。

**兼容性陷阱（实现时禁止）**

- 不要新增 3 参 `toolLoop(call, tools, LlmCancelHandle)`：会与 `toolLoop(call, tools, Integer)` 产生二义性；必须用 4 参重载。
- 不要新增 `execute(Step)` 或 `run(Step...)`：会让 `execute(null)` / `run(null)` 变成二义性调用。
- `Step` 新方法必须是 `default`，保持单抽象方法性质。
- 预校验消息必须保留“步骤”“Agent loop”等现有断言依赖的子串。

**审查 2 结论**：计划已覆盖全部 P0 和 P1；P2 中与网关记录/工具异常相关的内容放入 Phase 0，其余按上述决策文档化。可以进入实现，但建议先做 Phase 0 的网关修复，再改 Workflow。

---

## 11. 执行记录（2026-10-22）

### Phase 0：网关前置

- 新增 `LlmGateway.toolLoop(call, tools, maxRounds, cancel)`（4 参，避免和 `Integer` 重载二义性）。
- 同步工具执行补 `try/catch`：异常包装为 `LlmCallException("工具执行失败", cause)` 并写 ERROR 记录；`null` 结果按空列表处理；结果元素为 null 时写空字符串。
- 工具执行后取消补 ABORTED 记录（同步/异步）。
- 异步工具循环记录改为“每轮一条”：
  - `TOOL_USE` 轮次用普通 `record`，不再被全局 `recorded` 标志吞掉；
  - 终态用 `terminalRecorded` CAS 保护，避免完成与取消竞态重复写；
  - `MAX_ROUNDS`、`STOP`、`ERROR`、`ABORTED` 都会落终态记录。
- 记录语义统一为：每轮一条；usage 为该逻辑调用截至当前的累计值，latency 为当轮耗时。同步/异步一致。
- 工具结果处理回调增加 `try/catch`，避免回调内 NPE 导致 future 永不完成。

### Phase 1–4：Workflow

- `run` 先做列表快照和整表结构校验，再执行；空列表返回不可变空列表；结构错误优先于预取消。
- 新增 `run(steps, cancel)`、`execute(step, cancel)`、`one(call, cancel)`、`toolLoop(..., cancel)`。
- `Step` 新增 default `execute(gateway, cancel)`，旧自定义 Step 无需修改。
- 新增 `WorkflowCancelledException`（`Kind.CANCELLED`）与 `WorkflowStepException`，携带 `stepIndex`、不可变 `completedResults`、原始 cause；异常消息不复制 cause 内容。
- `ABORTED` 具有权威性：即使没有取消句柄，也终止后续步骤并抛取消异常。
- 结果列表、已完成结果快照均不可变；`Error` 不包装。
- `oneStep` / `toolLoopStep` 在工厂期拒绝 null call，并对 `LlmCall` / 工具列表做快照；`LlmCall.copy()` 支持超时快照。
- 工厂的 `cancel` 参数作为默认句柄：`run` / `execute` 传入外层句柄时以外层为准。

### Phase 5：测试与文档

- 新增 `WorkflowHardeningTest`（24 条）与 `LlmGatewayToolLoopRecordTest`（9 条）；现有 7 条 WorkflowTest 不改断言通过。
- 更新 `docs/api-usage.md` §4.1、`docs/testing/README.md`、README 测试统计。

### 后续

- 异步 `runAsync` 见 [夯实 Workflow 异步执行器](夯实Workflow异步执行器-plan.md)；实现时复用 `oneWithRetryAsync` 的调度/取消模型，但 Workflow 自身仍不内置重试。
- 编排层重试仍是独立决策：需要先定义步骤级/整链级、工具副作用和请求数语义。
