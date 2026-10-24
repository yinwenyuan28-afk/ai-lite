# 夯实 AgentLoop 异步执行器

**状态（2026-10-24）：已实现并全量回归通过（执行记录见第 10 节）。** 本计划覆盖 `AgentLoop.runAsync` 的异步专项加固与能力扩展，是《夯实AgentLoop一次性执行-plan.md》（下称"同步计划"，其 Phase 0–5 已完成）的后续独立计划。契约基线沿用同步计划 §5，本文不重复，只写异步侧的差异与新增。

一句话总纲：

> **异步 `runAsync` 必须与同步 `run` 同一套决策、同一套 afterRun 契约，且永不挂起：future 永不因递归、非合作传输、Error 逃逸或调用方取消而永久 pending。**

---

## 1. 范围

### 1.1 本次范围

- `runAsync` 的循环推进结构（照 `AsyncWorkflowRun` 完整泵结构重构，修 AL-H04）。
- 非合作 transport 下取消的终态收敛（`LlmCancelHandle` 取消监听，修 AL-H05）。
- 异步/同步语义对齐：终态优先级（两处错位）、续行判定、CONTINUE 上下文、取消检查点②③、afterRun 恰好一次、hooks 防御性拷贝。
- 两段式共享决策函数 `decideToolPhase` / `decideTerminal`；同步侧顺带修复 AL-D08（审查新发现的重复追加）。
- AL-A1 并行工具执行（显式开关）、AL-A2 执行器 future 自适配、AL-A3 整轮超时。
- 异步专项测试矩阵与文档。

### 1.2 明确不在本次范围

- 流式 `runAsync`（`runByStream` 的异步形态）。
- 循环内置的模型轮次自动重试：`AgentLoop` 内部固定走 `gateway.round/roundAsync`，外部无法把 `oneWithRetryAsync` 套进循环；需要"每轮重试"的场景用 Workflow 的 `oneStepWithRetryAsync` 步骤或自定义 transport 组合（审查 T-2 修正，原表述误导）。
- Reactor / 虚拟线程依赖；线程模型仍为手写 CPS + CompletableFuture。
- 会话、持久化、HITL、图编排（同同步计划 §8）。
- 每工具/总调用次数限额（先例已记录于同步计划 10.4，将来另立计划）。

---

## 2. 缺陷清单（2026-10-24 独立审查逐行核实，含两处同步侧新发现）

| 编号 | 缺陷 | 位置（AgentLoop.java 除注明外） | 影响 |
| --- | --- | --- | --- |
| AL-H04 | future 同步完成时 `afterTurn → next()` 直接递归 `advance()`，`next()` 只 catch `RuntimeException` | :309-317 / :336-388 / :429-431 | 连续 CONTINUE + completedFuture 型 transport → StackOverflowError；Error 逃逸 → 调用方 future 永不完成 |
| AL-H05 | 非合作 transport（不 `bindAbort`）下 `handle.cancel()` 无法终结 roundFuture，网关只在 transport future 完成时收敛（LlmGateway.java:943-966） | `advance()` 内 `roundAsync` 之后 | 调用方 future 永久 pending |
| AL-D01 | 终态优先级与同步错位，**两个场景**：① 最后轮 TOOL_USE+hook STOP：异步先判 MAX_ROUNDS（:419-422）后判 stop（:424），同步相反（:212-215 先 stop）→ 异步 MAX_ROUNDS、同步 STOP；② CONTINUE+非工具轮+轮数耗尽：异步落模型 stopReason（:429-433），同步返回 MAX_ROUNDS（:216-223，契约 §5.1 明文） | `afterTurn()` | 同一输入两条入口终态不同 |
| AL-D02 | CONTINUE 非工具轮不追加 assistant（同步 :226-229 已追加） | `advance()` whenComplete：assistant 只在工具轮 runnable 里追加（:365-371） | 纯 CONTINUE 轮下一轮重复发同一请求 |
| AL-D03 | 全部 7 处 `completeExceptionally`（:315/:333/:341/:354/:373/:381/:410）与 `future.cancel` 桥（:275-279）都不跑 afterRun；仅 `completeWith→finish` 有 | AsyncRun 各异常分支 | 违反契约 §5.2/§5.3：观察钩子拿不到 error，`future.cancel` 时 afterRun 零次 |
| AL-D04 | hooks 列表未防御性拷贝（同步 :101-104 有） | `runAsync` :260-261 | run 进行中调用方改列表串扰循环 |
| AL-D05 | 检查点③未显式化，且异步**多条路径缺 appendAssistant/合成 tool_result**：`toolUse&&lastRound`（:362 只处理非最后轮）、`ABORTED/ERROR+toolCalls`（toolUse=false 跳过一切）均不追加、不合成；同步在 :168-175/:183-188 都有 | `advance()` whenComplete | 取消/超限时异步侧 ctx 出现 dangling tool_use（"语义等价"仅对取消+非最后工具轮成立——审查 T-1 修正） |
| AL-D06（审查 M-1 新登记） | 异步续行判定用 `toolUse = stopReason==TOOL_USE && toolCalls 非空`（:358-359），续行条件 `(again\|\|toolUse)`（:429）同错；同步是 `runnable = hasToolCalls && stopReason != ERROR`（:178-179） | `advance()` / `afterTurn()` | 模型返回 STOP 但带 toolCalls：同步执行工具并继续（契约 §5.1 头条、测试 `stopWithToolCallsExecutesToolsAndContinues` 锁定），异步直接落 STOP、工具一次不执行 |
| AL-D07（审查 M-2 新登记） | 检查点②缺失：`beforeModel` 后取消时异步直接 `roundAsync`，网关对预取消句柄返回**同步已完成的 ABORTED 结果**（LlmGateway.java:920-925，零 HTTP），异步照常 `completed++`（:344）、调 afterModel（:352）、调 afterTurn（:400） | `advance()` | 幽灵轮：rounds=N+1、content 被幽灵轮覆盖、多调两组钩子；同步是 rounds=N、零多余钩子（契约 §5.3"不计轮次"） |
| AL-D08（**同步侧新发现**，审查 D-B 证据二） | `stopReason=ERROR + toolCalls + hook CONTINUE + 非最后轮`：:183-188 的 else-if 已 `appendAssistant+appendSkipped`，:225-229 的 `again && !runnable` **再次 appendAssistant** | 同步 `runRounds` | ctx 出现重复 assistant(tool_use)，第二份无 tool_result，正是契约 §5.4 要避免的 dangling；现有测试未覆盖该组合（`errorWithToolCallsDoesNotExecuteTools` 无 CONTINUE 钩子）。已由主对话抽验属实 |

---

## 3. 设计决策

### 3.1 泵结构（AL-H04，按审查 D-A 修订）

**不再采用"给 `next()` 加 wip 标志 + 扩 catch"的轻改法**——它救不了传输线程上完成的回调体：钩子抛 Error 时回调不在任何 `next()` 的 try 内，future 照样挂起，计划自己的 A1 验收用例都过不了。改为照 `AsyncWorkflowRun` 的**完整结构**（Workflow.java:512-618 先例）：

- 回调（roundFuture/toolsFuture 的 whenComplete）只做一件事：把结果写入 outcome 槽 + 调 `advance()`。
- 全部决策与副作用逻辑进 `pump()`，由 drain 层的 `catch (Throwable)` 统一兜底收敛 future。
- 同步完成型 future 由同线程 drain 循环消化，不涨栈；跨线程天然不递归。
- 回调体内对 `shown.getToolCalls()` 判空（审查 S-3：TOOL_USE+null toolCalls 会 NPE）。
- drain 落在"当时触发推进的线程"（首轮=调用方线程，后续=传输线程），钩子线程归属与现状一致，"钩子线程安全由调用方负责"的契约不变，写入文档（审查 T-5）。

### 3.2 取消监听与主动收敛（AL-H05，按审查 M-5 补全）

`LlmCancelHandle` 新增 `onCancel(Runnable)`：取消时执行一次；**在已取消句柄上注册应立即触发**（对齐 `bindAbort` 的 late-bind 语义，LlmCancelHandle.java:42-47）；重复 `cancel()`（调用方 + future.cancel 桥 + 超时任务可能各调一次）下监听器恰好一次——现 `cancelled` 是普通 set 非 CAS（:26），需改为 CAS 或同步块。

注册点决策（必须写死，不能留给实现随手选）：`roundAsync` 与 `oneAsync/oneByStreamAsync/toolLoopAsync` 共用 `invokeAsync`（LlmGateway.java:451-500）。**采用在 `invokeAsync` 统一注册**，理由：非合作 transport 的挂起问题对所有异步入口同样存在；与 `RetryRun` 的交互已分析兼容——每 attempt 新句柄各自挂监听，`cancelFromOuter` 的 `attemptCancel.cancel()`（:777）经监听提前完成 attempt future，不与 `terminal` CAS、`recordTerminal` 单写（:1059-1064）冲突；实现时补一条 RetryRun 回归测试锁定。

同步 `run` 不受益也不受损：阻塞调用无法被监听器唤醒（同步计划审查已确认），文档写明。

### 3.3 两段式共享决策（修 AL-D01/D02/D05/D06/D07，按审查 D-B 重写）

~~单函数 `decideRound`~~ 与钩子顺序矛盾：同步流里**工具执行决定发生在 afterTurn 聚合之前**（:180-182 先 runTools，:192-204 后聚合 stop/again），一次调用拿不到两阶段输入。改为两个静态纯函数，副作用（appendAssistant/appendSkipped）全部留在调用方，且 assistant 追加时机单点化（维护"本轮是否已追加"状态，修 AL-D08 同款问题）：

```
decideToolPhase(shown, isLastRound, cancelled)
  → EXECUTE_TOOLS | ABORTED(取消/ABORTED，需合成) | SYNTHESIZE(最后轮/ERROR+toolCalls，需合成) | PASS(无 toolCalls)
decideTerminal(stop, again, isLastRound, hasToolCalls, cancelled)
  → ABORTED | STOP | MAX_ROUNDS | MODEL(模型 stopReason 原样) | NEXT_ROUND
```

同步 `runRounds` 与异步 `pump()` 都调用这两个函数，优先级（取消 > STOP > MAX_ROUNDS > 模型）、续行条件（`hasToolCalls && stopReason ∉ {ABORTED, ERROR}`）只剩一份实现。异步侧检查点②③显式化：beforeModel 后查取消不走网关兜底（修 AL-D07）；模型返回后先查取消再决定提交工具批次（修 AL-D05）。

### 3.4 afterRun 单出口（修 AL-D03）

所有终结路径收敛到一个收尾函数：成功 → `afterRun(ctx, result, null)` 后 complete；异常 → `afterRun(ctx, null, cause)` 后 completeExceptionally；`future.cancel` → `afterRun(ctx, null, CancellationException)` 后保持 cancelled（该路径的 afterRun 在**取消方线程**执行，与成功路径的完成方线程不同，属可接受差异，写入文档——审查 S-4）。`beforeRun` 失败仍不调用 afterRun（契约 §5.2 原样适用）。

### 3.5 AL-A1 并行工具（显式开关，默认串行，按审查 M-7 补全）

- 开关形态：构造器级选项（如 `new AgentLoop(gateway, executor, toolExecutor, ToolRunMode.PARALLEL[, Executor toolPool])`），参照 LangChain4j `executeToolsConcurrently(Executor)`；默认 SEQUENTIAL。**PARALLEL 对 `run` 与 `runAsync` 两条入口都生效**，与同步计划 §8"并行工具执行（同步轮次不做）"的字面冲突按此修订：旧表述指"本轮不实现"，实现后两条入口共享同一执行语义（开工时同步修订旧计划 §8 措辞）。
- 执行结构：`beforeTool` 对本批调用全量串行预检（deny-wins、参数改写都在并发前确定）→ 允许的调用并发执行（单工具不切线程，直接在当前线程跑）→ 全部完成后按模型原始顺序串行回调 `afterTool` 并写回 tool_result。
- **执行器故障语义**（审查 M-7a，必须定义）：与串行对等——故障即收敛 run 失败；已提交的调用不再等待 allOf（已在线程池上的由调用方线程池自然跑完但结果丢弃）；已完成工具的 `afterTool` 不再调用（串行语义即"故障后后续钩子不执行"）。该差异写入文档并有测试。
- 取消语义与同步一致：已提交的跑完，未提交的合成"未执行：调用已取消"。

### 3.6 AL-A2 执行器 future 自适配（自有设计，无先例，按审查 T-3 修正命名）

新增可选接口 `AsyncToolExecutor extends ToolExecutor`，方法**改名 `executeOneAsync`**（原名 `executeAsync` 与继承自 `ToolExecutor` 的批量 `executeAsync(List, List)`（ToolExecutor.java:30）同名不同义）：

```java
CompletableFuture<String> executeOneAsync(LlmTool tool, AgentToolCall call, int roundNo);
```

`runAsync` 遇该类型自动走异步链；同步 `run` 遇该类型 `.join()`——join 无超时、坏实现会挂起调用线程，文档与测试写明该边界。

### 3.7 AL-A3 整轮超时（自有设计，无先例，按审查 M-6 补全）

新增 `runAsync(call, tools, lifecycles, maxRounds, cancel, timeoutMs)` 重载（只增不改，契约 A9）。

- **调度器获取**：`retryScheduler` 是 `LlmGateway` 私有字段（:47/:73），`RetryScheduler.shared()` 静态单例在 Starter 注入自定义 `aiLiteRetryScheduler` bean 时是**另一个实例**、会绕开用户调度器——采用给网关加 `retryScheduler()` getter，构造注入（与 LlmGateway 现有构造一致）。
- 到点触发 `cancel` 句柄 → 走 3.2 监听收敛 → ABORTED 终态；run 提前结束取消调度任务。
- 调用方自带句柄时，超时会**烧掉该句柄**（one-shot），文档写明；`timeoutMs` 为 null 或 <=0 表示无整轮预算。

### 3.8 目标流程图

```
调用方                循环（谁触发就跑在谁的线程上）          后台线程
────────────────────────────────────────────────────────────────
runAsync(...)
 ├─ 参数检查、钩子快照
 ├─ beforeRun 钩子
 ├─ 接上取消通道（调用方 cancel future = 取消整个循环）
 ├─ 设了总超时 → 到点自动触发取消
 └─ 启动循环 ─┐
              ▼
 ┌─► 一轮
 │    ├─ 已取消？ → 结束：ABORTED
 │    ├─ beforeModel 钩子
 │    ├─ 已取消？ → 结束：ABORTED（不算一轮）
 │    ├─ 请求模型 ────► 模型返回
 │    │                 （取消时请求被强制结束，不会永远卡住）
 │    │
 │    ├─ 已取消？ → 记下本轮助手消息 + 未执行工具的说明 → ABORTED
 │    ├─ afterModel 钩子；累计 token / 耗时
 │    │
 │    ├─ 模型要调工具、且还能继续？
 │    │    ├─ 是 → 执行工具，逐个：
 │    │    │     执行前查取消 → 钩子审核（可拒绝/改参数）
 │    │    │     → 执行 → 钩子可改写结果 → 按模型给的顺序记入上下文
 │    │    │     （开了并行就同时跑，结果仍按原顺序记）
 │    │    └─ 否（最后一轮 / 模型出错）→ 不执行，
 │    │          补一条"未执行"说明，上下文不留悬空调用
 │    ├─ afterTurn 钩子（可喊停，也可要求再来一轮）
 │    │
 │    └─ 怎么收场？★
 │         ├─ 被取消 ─────────→ ABORTED
 │         ├─ 钩子喊停 ────────→ STOP
 │         ├─ 轮数用完 ─────────→ MAX_ROUNDS
 │         ├─ 还要继续 → 把本轮助手消息补进上下文 → 回到"一轮"顶部
 │         └─ 模型自己停了 ────→ 按模型给的理由收尾
 │
 └─ 收尾（全流程只有这一个出口）
      afterRun 钩子：无论成功、失败、取消都恰好执行一次，
      只做记录，不改结果
      → future 完成（正常结果 / 异常 / 取消）
```

实现约束（图上看不出来但必须遵守）："一轮"由泵结构推进——任何回调只登记结果并唤醒循环，全部逻辑集中在循环里统一兜底异常（这是防递归爆栈和防卡死的关键）；图中每个"已取消？"就是一个取消检查点，共 5 处。

图中位置 ↔ 计划条目对照（实现与评审用）：

| 图中位置 | 条目 |
| --- | --- |
| 钩子快照 | AL-D04 |
| 泵结构、统一兜底 | AL-H04 |
| 请求模型（取消强制结束） | AL-H05 |
| 发请求前取消不计轮 | AL-D07 |
| 取消时记消息+补说明 | AL-D05 |
| "要调工具且还能继续"的判定 | AL-D06 |
| 最后一轮/出错补"未执行" | AL-D05 |
| 钩子喊停（含最后一轮仍要工具时也算 STOP） | AL-D01① |
| 轮数用完（含钩子要求继续但轮数耗尽） | AL-D01② |
| 继续前补助手消息 | AL-D02 / AL-D08 |
| 单一收尾出口、afterRun 恰好一次 | AL-D03 |
| 并行开关（默认关） | AL-A1 |
| 异步工具自动识别 | AL-A2 |
| 总超时 | AL-A3 |

---

## 4. 实施阶段

顺序原则：先结构（泵/单出口），再语义对齐（共享决策），再能力（超时/并行/自适配）。每阶段全量回归；审查 S-5 已确认：`AgentLoopTest` 8 个异步用例（预取消短路、cancel 桥、快速失败校验）与 `AgentLoopSyncHardeningTest` 18 条在本计划改动下均可保持通过。

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| Phase A0 | 抽取两段式 `decideToolPhase`/`decideTerminal`，同步 `runRounds` 改调用；**同步顺带修 AL-D08**（"本轮 assistant 已追加"状态位）并补回归测试——不再是纯重构 | 同步全量测试不变绿；ERROR+toolCalls+CONTINUE 组合 ctx 无重复 assistant；终态矩阵全组合单测 |
| Phase A1 | `AsyncRun` 改泵结构：回调只写 outcome + advance，逻辑进 pump，drain 层 `catch (Throwable)`（§3.1） | completedFuture+CONTINUE+大 maxRounds 不爆栈、5 秒内完成；**传输线程完成场景**钩子抛 AssertionError future 异常完成 |
| Phase A2 | 收尾单出口：所有异常路径与 `future.cancel` 恰好一次 afterRun 且 error 可见（AL-D03）；hooks 防御性拷贝（AL-D04） | 每类异常路径 afterRun 计数=1；run 进行中修改入参列表不影响循环 |
| Phase A3 | 异步改用两段式决策：终态优先级两处错位（AL-D01）、CONTINUE 追加 assistant（AL-D02）、检查点②③（AL-D05/D07）、续行判定 runnable 化（AL-D06）、缺失的合成路径 | sync/async parity 套件：同一脚本两条入口终态/rounds/content/ctx 历史逐一相等 |
| Phase A4 | `LlmCancelHandle.onCancel`（含已取消后注册、重复 cancel 恰好一次）+ 网关 `invokeAsync` 统一注册主动收敛（AL-H05，含 RetryRun 回归） | 非合作 transport 取消后 1 秒内 ABORTED；监听器解绑无泄漏；RetryRun 全量回归 |
| Phase A5 | 整轮超时重载（AL-A3：网关 `retryScheduler()` getter + 调度任务） | 超时触发 ABORTED、in-flight 请求被取消、任务不泄漏；提前完成不误触 |
| Phase A6 | 并行工具显式开关（AL-A1，含 §3.5 故障语义） | 并行结果按原始顺序写回；单工具不跳线程；取消/deny/执行器故障语义与串行对等 |
| Phase A7 | `AsyncToolExecutor.executeOneAsync` 自适配（AL-A2） | runAsync 走异步链不阻塞；同步 run join 边界有测试 |
| Phase A8 | 测试统计与文档：api-usage §4.2、architecture、docs/testing/README、README、同步计划 §8 措辞修订、本计划执行记录 | 文档与行为一致，测试数同步 |

---

## 5. 测试矩阵（异步专项）

| 类别 | 用例 | 断言 |
| --- | --- | --- |
| 递归 | completedFuture 型 transport + CONTINUE + 大 maxRounds | 不 StackOverflow，5 秒内完成 |
| Error | 钩子抛 AssertionError——**分别构造调用方线程完成与传输线程完成两种** | future 均异常完成，不挂起 |
| 挂起 | 非合作 transport 在途取消 | 1 秒内 ABORTED，roundFuture 被监听收敛 |
| 监听 | 句柄已取消后注册 onCancel；句柄+future.cancel+超时任务三源重复 cancel | 立即触发；监听器恰好一次 |
| future.cancel | 调用方 cancel(true) | cancelled、afterRun 恰好一次（error=CancellationException，取消方线程）、底层 abort |
| afterRun | 每类异常路径（7 钩子 + 网关异常） | 恰好一次、result=null、error=cause |
| parity | 同一脚本跑 run 与 runAsync：正常 STOP / 中途 STOP+toolCalls / 最后轮 TOOL_USE+STOP / CONTINUE+非工具轮耗尽 | 终态/rounds/content/toolCalls 逐一相等（后三个分别锁 AL-D06/AL-D01①/AL-D01②） |
| parity | 取消落在同一检查点（beforeModel 内 / afterModel 内 / 工具批次内） | 两条入口终态与 ctx 历史一致；beforeModel 场景 rounds 不+1、零 afterModel（锁 AL-D07） |
| parity | transport 返回 ABORTED/ERROR + toolCalls | 两条入口均合成 tool_result、无 dangling、不执行工具 |
| 上下文 | 纯 CONTINUE 多轮 | 每轮请求包含上一轮 assistant 正文/thinking/signature，不重复同请求 |
| 上下文 | 取消/超限的合成 tool_result | ctx 无 dangling tool_use |
| 同步回归 | ERROR+toolCalls+hook CONTINUE（AL-D08） | assistant 恰好追加一次，无 dangling |
| 隔离 | run 进行中修改传入的 lifecycles/tools 列表 | 循环不受影响 |
| 超时 | timeoutMs 到点 / 提前完成 | ABORTED 且 in-flight 被取消、任务解绑；不误触 |
| 并行 | 多工具并发完成乱序 | tool_result 按模型原始顺序写回、call id 对齐 |
| 并行 | 单工具 | 在当前线程执行（断言线程 id） |
| 并行 | 批次中取消 / deny / 执行器故障 | 已提交跑完、未提交合成 skipped；deny 不执行不 afterTool；故障即收敛、已完成工具 afterTool 不调 |
| 自适配 | AsyncToolExecutor + runAsync / 同步 run | 异步链不阻塞调用线程；join 结果一致且边界文档化 |
| 并发 | 同实例并发 runAsync | 结果互不串扰 |

---

## 6. 明确不做

- Reactor / 虚拟线程 / 响应式全栈化（手写 CPS 是差异化，不是债务）。
- 循环内置模型轮次重试、退避、熔断。
- 流式 AgentLoop 异步入口。
- 每工具/总调用次数限额（有 Spring AI 先例，另立计划）。
- 工具级超时（AL-A3 是整轮预算；单工具超时由执行器内部实现，循环不管）。
- 改动 `LlmCancelHandle` 既有对外语义（单槽 bindAbort、one-shot、幂等）；onCancel 只增不改（内部 `cancelled` 字段 CAS 化属实现细节）。

---

## 7. 验收标准

- AL-H04、AL-H05、AL-D01–D08 全部修复，各有一条带超时（≤5 秒）的回归用例。
- sync/async parity 套件落地：同一脚本两条入口终态、rounds、ctx 历史逐一相等。
- `AgentLoopTest` 现有 8 个异步用例与 `AgentLoopSyncHardeningTest` 18 条除明确修正项（终态优先级、AL-D08 修复）外全部通过。
- 新增异步用例约 20–28 条；全量测试通过、不挂起 CI。
- 文档不再有"异步与同步行为一致"的无依据表述，线程归属、故障语义等差异全部显式写入。

---

## 8. 引用

- 契约基线：《夯实AgentLoop一次性执行-plan.md》§5（终态矩阵、钩子策略、取消边界、工具语义）、§10.4（Spring AI / LangChain4j 二次核实结论）。
- 结构先例：`Workflow` 的 `AsyncWorkflowRun`（Workflow.java:512-629：wip drain + catch Throwable、pendingOutcome 单写者、每步独立取消句柄）。
- 网关交互点：`invokeAsync`（LlmGateway.java:451-500）、预取消短路（:920-925）、transport 完成时收敛（:943-966）、`RetryRun.startAttempt/cancelFromOuter/recordTerminal`（:582/:777/:1059-1064）。
- 调度器：`RetryScheduler`（`aiLiteRetryScheduler` bean 与 `shared()` 单例是两个实例——AL-A3 取 getter 注入）。

---

## 9. 审查记录（2026-10-24，独立子代理）

- **缺陷核实**：7 条登记项 6 条完全真实、AL-D05"语义等价"言过其实（仅取消+非最后工具轮成立）。
- **设计缺陷 2 条（已修订）**：D-A——原 §3.1"next() 扩 catch"覆盖不到传输线程回调体，照字面实现 A1 验收必挂，已改为照 AsyncWorkflowRun 完整泵结构；D-B——单函数 decideRound 与"先执行工具后聚合 afterTurn"的钩子顺序矛盾，已拆两段式纯函数 + 副作用归调用方 + 追加时机单点化。
- **遗漏 7 条（已采纳）**：M-1→AL-D06、M-2→AL-D07、M-3/M-4→并入 AL-D05/AL-D01、M-5→§3.2（注册点统一 invokeAsync、onCancel 语义、重复 cancel 恰好一次）、M-6→§3.7（调度器 getter 注入、句柄烧掉、timeoutMs 语义）、M-7→§3.5（并行故障语义、开关两入口生效）、M-4 场景→parity 用例。
- **同步侧新发现**：AL-D08 重复追加（ERROR+toolCalls+CONTINUE），主对话已抽验 :183-188 与 :226-229 属实，A0 带测试修复。
- **表述 5 条**：T-1～T-5 全部落入对应节。
- **兼容性结论（S-5）**：现有异步 8 用例 + 同步 18 条在计划改动下均可保持通过，"除明确修正项外全过"的验收判断成立。

---

## 10. 执行记录（2026-10-24，全部完成）

Phase A0–A7 当日实现完毕，A8 全量回归通过：core 468（+30）、JDK8 全量 725（+30）、全口径 748，0 失败。

### 已落地

| 阶段 | 内容 |
| --- | --- |
| A0 | 抽取 `decideToolPhase` / `decideTerminal` 静态纯函数（副作用留调用方）；同步侧顺带修 AL-D08（`appended` 状态位，追加时机单点化） |
| A1-A3 | `AsyncRun` 泵结构重写：回调只写 `AsyncOutcome` 结果槽 + `advance()` 唤醒；`pump()` 单线程串行推进，drain 层 `catch (Throwable)` 兜底；收尾单出口（`finished` CAS 保证成功/异常/`future.cancel` 三路 afterRun 恰好一次）；hooks 防御性拷贝；检查点②显式化（幽灵轮消除）；续行判定与终态优先级与同步完全对齐 |
| A4 | `LlmCancelHandle.onCancel`（guarded 恰好一次、已取消后注册立即触发、`cancel()` 改 CAS 判定）；网关 `invokeAsync` 统一注册监听 + `cancelledByListener` 主动收敛（ABORTED）；RetryRun 42+6 回归通过 |
| A5 | `runAsync(..., timeoutMs)` 六参重载 + `gateway.retryScheduler()` getter（避免 `shared()` 绕开注入的调度器）；提前完成撤销调度任务 |
| A6 | `ToolRunMode.PARALLEL`（构造器级开关 + 可选专用池）：beforeTool 串行预检 → 并发执行（单个允许项不切线程）→ 按原始顺序 afterTool+写回；执行器故障 fail-fast、已完成工具 afterTool 跳过 |
| A7 | `AsyncToolExecutor.executeOneAsync`（编程式 SPI，llm 包避免反向依赖）：runAsync 逐工具异步链，同步 run 走 `execute` 不变 |
| A8 | 文档：api-usage §4.2 重写、testing/README 四行、README 统计 748/725、同步计划 §8 措辞修订、本记录 |

### 测试（新增 30 条，全部通过）

- `AgentLoopAsyncHardeningTest` 22：蹦床万轮不爆栈、传输线程 Error 收敛（cause 链含 AssertionError）、afterRun 恰好一次、future.cancel 桥、非合作 transport 1 秒内 ABORTED、预取消零请求、beforeModel 取消无幽灵轮、入参列表隔离、STOP+toolCalls 续行、最后轮 TOOL_USE+STOP 决策→STOP、CONTINUE 耗尽→MAX_ROUNDS、非工具轮 CONTINUE 补 assistant、ABORTED/最后轮合成 tool_result、AL-D08 异步版、整轮超时两条、并行三（并发+顺序写回 / 单允许项调用方线程 / 故障跳过 afterTool）、异步执行器两条。
- `AgentLoopDecisionTest` 2：两段式决策全组合矩阵。
- `AgentLoopSyncHardeningTest` +1：AL-D08 同步回归（assistant 恰好一次、无 dangling）。
- `LlmCancelHandleListenerTest` 5：监听器恰好一次 / 迟注册立即触发 / 异常不影响他人 / 注册-取消竞态 / abort+监听共存。

### 实现中发现并修复的问题

- **泵活循环（实现期 bug）**：`processRoundLocked` 走合成/直通路径返回 true 后，pump 直接 `continue` 回到循环顶开新一轮，绕过了 `finishTurnLocked`——终态判定与轮数上限全部失效，万轮/秒的请求风暴。诊断方式：请求计数轮询 + 线程栈连续快照定位为活循环，再由栈中 `postComplete → onRoundDone → startRound` 链路确认。修复：消费模型轮后立即进入收尾判定。
- **传输 Error 的包装边界**：网关把传输故障统一包成 `LlmCallException("模型调用失败", cause)`，AgentLoop 的 afterRun 看到的是包装异常、AssertionError 在 cause 链上——测试按 cause 链断言，属网关既有契约而非缺陷。

### 与计划的偏差

- §3.2 曾计划"正常完成时由注册方解绑监听"：句柄是 one-shot 生命周期、随调用回收，无需解绑 API，`onCancel` Javadoc 已写明（简化，无行为差异）。
- §3.6 同步 `run` 遇 `AsyncToolExecutor` 不做 `.join()` 适配，直接走其 `execute()` 实现——SPI 本身就有同步方法，join 是多余语义。
