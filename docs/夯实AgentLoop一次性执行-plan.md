# 夯实 AgentLoop 一次性执行

**状态（2026-10-24）：同步 `run` 已实现并回归通过（Phase 0–5 全部完成，见第 11 节执行记录）；异步 `runAsync` 已拆分至独立计划《夯实AgentLoop异步执行器-plan.md》（含 AL-H04/H05、decideRound 共享与 AL-A1–A3）。** 本计划只覆盖一次性、无上下文持久化的 `AgentLoop`，不涉及 `AgentSessionLoop`、会话存储或 Workflow。

一句话总纲：

> **AgentLoop 是一次性 run 内的“模型驱动工具循环”：它必须保证每个 run 都有唯一、可解释的终态，取消能穿透 I/O，工具错误是数据而不是静默，上下文始终自洽；持久化、记忆、图编排全部留给上层。**

---

## 1. 定位与范围

### 1.1 它是什么

`AgentLoop` 在一次调用内反复执行“模型请求 → 工具调用 → 再请求”，直到模型停止、达到轮数上限或取消。它只持有本次 run 的局部状态（消息、usage、轮数），不读写会话存储。

### 1.2 本次范围

- `AgentLoop.run` / `runAsync` 的轮次推进、终止条件、结果聚合。
- 7 个生命周期钩子的调用顺序、异常策略、取消交互。
- 工具批次的执行、错误回喂、上下文配对、部分完成语义。
- 同步/异步取消：在途模型请求、轮间、工具启动前、工具执行中。
- 必要的网关前置：同步 `round(call, tools, cancel)`；非合作传输下取消的终态收敛。
- 测试、文档、调用记录口径。

### 1.3 明确不在本次范围

- `AgentSessionLoop`、会话持久化、跨 run 记忆、checkpoint、resume。
- `Workflow`（另有计划）。
- 自动重试、退避、熔断、总预算、总 deadline。
- 并行工具执行（本轮保持串行，修正文档；并行另立计划）。
- 流式 AgentLoop 入口（`runByStream`）；本轮只修正已有 `run`/`runAsync`。
- 结构化输出、guardrail、handoff、多 Agent 编排。
- 持久化 transcript、HITL、审批中断后恢复。

### 1.4 归属判断规则

> 需要跨 run 保留的东西（消息、记忆、审批状态、checkpoint）不属于 AgentLoop；需要模型决定“下一步做什么”的循环才属于 AgentLoop。

---

## 2. 指导原则

### A1 每个 run 必须且只能有一个可解释终态

- 任何回调、工具、传输、执行器异常都必须收敛为“结果”或“异常”，不能挂起。
- 终态使用现有 `LlmStopReason`：`STOP`、`LENGTH`、`TOOL_USE`、`MAX_ROUNDS`、`ABORTED`、`ERROR`、`CONTEXT_LENGTH_EXCEEDED`。0.x 不新增 `AgentRunResult` 类型，先把现有字段的含义修正准确。
- 验收：正常、工具失败、钩子失败、取消、超限、同步完成六类场景都有带 5 秒超时的终态断言。

### A2 取消优先，且穿透 I/O

- 循环层保证 L2：取消后不再发模型请求、不再启动新工具。
- 在途模型请求的 L1 由网关和传输层负责；AgentLoop 必须把 `LlmCancelHandle` 传到 `round`/`roundAsync`，不能只在轮间检查布尔位。
- 已启动的工具允许跑完；未启动的工具一条都不再启动。取消不重试。
- 验收：同步/异步在途取消 1 秒内结束；`beforeModel`/`afterModel` 内取消不再发请求/不再启动工具；非合作传输不会永久挂起。

### A3 钩子顺序固定，异常策略明确

- 顺序：`beforeRun → (beforeModel → 模型 → afterModel → 工具 → afterTurn) × N → afterRun`。
- `before*` 与 `afterModel`/`afterTool`/`afterTurn` 抛 `RuntimeException`：终止 run，后续钩子不再执行，终态为异常。
- `afterRun` 是**观察钩子**：无论正常、取消还是异常都恰好执行一次（`beforeRun` 失败除外）；抛异常只记录，不覆盖已经构造好的 `LlmResult`，也不阻止后续 `afterRun`。
- 钩子列表做防御性拷贝；钩子实例的线程安全由调用方负责。
- 验收：每个钩子各有一个抛异常用例；`afterRun` 抛异常时成功结果仍返回；异常/取消路径 `afterRun` 也只执行一次。

### A4 上下文始终自洽

- 每次发给模型的消息必须满足：每条 assistant `tool_use` 都有对应 `tool_result`，顺序与 call id 一致；取消/异常时不把半截 turn 发出去。
- `afterTurn` 返回 `CONTINUE` 时，如果本轮不是工具轮，必须先把 assistant 正文/thinking/signature 追加到上下文，否则下一轮会重复同一请求。
- 工具列表在构造请求时做快照，避免并发修改。
- 验收：第二轮请求能看到第一轮 assistant 正文；缺失 tool_result 的历史不会发往模型；取消后不再发下一轮。

### A5 终止原因可解释

- **继续条件由“本轮是否还有 toolCalls”决定**，`stopReason` 只作诊断；模型返回 `STOP` 但带 toolCalls 时仍执行工具并继续。
- 没有 toolCalls 时，优先级：**取消 > STOP > MAX_ROUNDS > 模型自身 stopReason**。
- 最后一轮同时出现 `TOOL_USE` 和 `STOP` 时，`STOP` 优先（与 architecture 流程图一致，修正当前 MAX_ROUNDS 优先）。
- `CONTINUE` 耗尽 `maxRounds` 时返回 `MAX_ROUNDS`，而不是模型最后一次 `STOP`。
- `beforeModel` 内取消不产生“幽灵轮”：`rounds` 不增加，不调用 `afterModel`。
- 验收：六组冲突场景各一个用例。

### A6 工具错误是数据，但必须可诊断

- 工具业务异常默认转成 tool_result 文本回喂模型并继续（与 OpenAI Agents SDK、Vercel AI SDK、Claude 的默认行为一致），不自动重试。
- 工具 call id、工具名、轮次、cause 必须进入结构化诊断（日志或钩子可观察），不能只剩一句“工具执行失败”。
- 工具执行器自身故障（`ToolExecutor` 抛异常/返回 null）与工具业务失败区分：前者至少写 `ERROR` 记录，后者作为 tool_result。
- 验收：抛异常的工具让下一轮请求包含错误文本；日志/钩子能拿到 toolName/callId；不产生第二次工具调用。

### A7 与网关语义一致

- AgentLoop 复用 `gateway.round` / `roundAsync`，不复制网关的校验、取消、记录逻辑。
- 同一个抛异常工具，AgentLoop 与 `gateway.toolLoop` 的“回喂还是中断”必须有明确、文档化的差异或统一；不能一个继续、一个抛异常且无人说明。
- 验收：parity 测试覆盖同一 fake transport + 同一工具在两条入口下的终态。

### A8 无状态、可并发、无隐藏行为

- `AgentLoop` 只持有构造注入的 `gateway`/`executor`/`toolExecutor`；每次 run 的状态都在局部 `AgentLoopContext` 中。
- 不跨 run 缓存消息、usage、钩子结果；不写会话存储。
- 不自动并行、不自动重试、不自动改写 prompt、不自动追加 token 预算。
- 验收：同一实例并发 run 结果互不串扰；两次相同输入第二次不携带第一次状态。

### A9 兼容演进

- 公开签名只增不改；`run` / `runAsync` 现有参数顺序不变。
- 新增取消重载优先走网关；`AgentLoop` 不新增必填参数。
- 行为收紧（取消后不启动工具、`afterRun` 不再覆盖结果、STOP 优先）逐条写入文档，并标注为修复。
- 验收：现有 `AgentLoopTest` 除明确修正的断言外全部通过。

---

## 3. 现状缺陷（审计结论）

审计共 25 条，完整清单见第 10 节。**P0 5 条**：

| 编号 | 问题 | 影响 |
| --- | --- | --- |
| AL-H01 | 同步 `run` 调 `gateway.round(call, tools)`，没有 cancel 重载 | 在途取消无效，只能等读超时；`readTimeout=0` 时永久阻塞 |
| AL-H02 | 取消发生在模型返回后、工具启动前，仍执行全部工具 | 取消后产生真实业务副作用 |
| AL-H03 | `afterRun` 抛异常覆盖成功结果 | 同步丢弃 `LlmResult`，异步把成功结果变成异常 |
| AL-H04 | 已完成 future 触发同步递归 `next()`，且只捕获 `RuntimeException` | 多轮 CONTINUE 可 `StackOverflowError`；Error 可能导致 future 永不完成 | **仅异步，本轮不做** |
| AL-H05 | 非合作 transport 下 `handle.cancel()` 不能终结 future | `roundFuture` 永久 pending，调用方挂起 | **仅异步，本轮不做** |

**P1 重点**：

- 纯 `CONTINUE` 轮不追加 assistant 消息，下一轮重复同一请求。
- 工具异常被吞成固定文本，cause 丢失。
- 最后一轮 `TOOL_USE` + `STOP` 时 `MAX_ROUNDS` 优先，与 architecture 流程图相反。
- `beforeModel` 内取消被计为一轮并触发 `afterModel`。
- `future.cancel` 与 `handle.cancel` 终态不一致（前者无结果、无 `afterRun`）。
- AgentLoop 与 `gateway.toolLoop` 对工具异常的行为不同。
- 每轮调用记录 `roundNo` 恒为 1；轮间取消零记录。
- `AgentLoop` 逐条 `execute(singleton)`，`ToolExecutor.executeAsync` 从不调用；文档声称可并行，不成立。
- 钩子列表未拷贝，工具列表是活引用。

---

## 4. 行业对照

### 4.1 Python / TypeScript 生态

调研对象：OpenAI Agents SDK 0.24.0、Claude Agent SDK 0.2.170、LangGraph 1.2.16、PydanticAI main、Vercel AI SDK 7.0.148、smolagents 1.27。

| 维度 | 主流做法 | 对 ai-lite 的取舍 |
| --- | --- | --- |
| 终止条件 | 无 tool call / 输出完成自然结束；`max_turns`/`recursion_limit`/`request_limit` 是硬上限；LangGraph 用 `remaining_steps` 软着陆 | 采用“取消 > STOP > MAX_ROUNDS > 模型停止”；超限不抛异常，返回 `MAX_ROUNDS` 结果 |
| 工具执行 | OpenAI/LangGraph/Vercel/PydanticAI 默认并行，按请求顺序聚合，call id 绑定；Claude 由 CLI 决定 | 本轮保持串行；文档修正“可并行”的错误说法；并行另立计划 |
| 工具错误 | 默认作为 tool result 回喂（OpenAI、Vercel、Claude、smolagents）；PydanticAI 只有显式 `ModelRetry` 才回喂；LangGraph 默认只吞特定异常 | 采用“默认回喂 + 可诊断 + 不重试”；执行器故障单独写 `ERROR` 记录 |
| 取消 | 必须传播到 I/O；PydanticAI `RunCancelled` 带部分消息和 usage；Vercel `abortSignal` 同时给模型和工具；smolagents 只在步骤边界，属于反例 | 采用“取消穿透网关 + 未启动工具不启动 + 返回 ABORTED”；已启动工具跑完 |
| 生命周期 | OpenAI 钩子只读、异常即中断；Claude 钩子可 allow/deny/modify；PydanticAI capability 可包裹替换 | 采用“before* / afterModel / afterTool / afterTurn 失败即终止；afterRun 只读不覆盖结果” |
| 结果聚合 | usage 逐轮累加；max steps 保留最后一次 tool call/output；latency 多数不内建 | 保留现有 `LlmResult` 聚合；明确 content 取最后一轮；补 usage 覆盖取消/超限 |
| 无状态边界 | OpenAI `Runner.run` 默认无状态；Claude `query()` 明确 stateless；LangGraph checkpointer 可选 | 采用严格无状态；持久化全部交给上层 |
| 上下文配对 | LangGraph 强制每个 tool call 有 ToolMessage；PydanticAI 会补 `interrupted` return、丢 orphan result | 本轮保证“不发半截 turn”；完整的 dangling 修复留作后续 |

**必须借鉴的四点**：终止原因显式化、max steps 保留部分结果、工具错误数据化并绑定 call id、取消穿透 I/O。
**明确不借鉴**：checkpoint、session、HITL、guardrail agent、`Agent.memory`、图执行器、按 provider 持久化 response id。

### 4.2 Java 生态

调研对象：Spring AI 2.0.1、LangChain4j 1.9.1、Semantic Kernel（.NET main）、Quarkus LangChain4j、Google ADK Java。

| 维度 | 主流做法 | 对 ai-lite 的取舍 |
| --- | --- | --- |
| 终止条件 | 没有一家只看 `finish_reason`；Spring AI/LangChain4j/ADK 都检查“是否还有 tool call”。SEMANTIC KERNEL 源码明确注释“可能返回 stop 但仍有 tool calls”。上限语义各异：轮次、每工具次数、总调用次数、LLM 调用次数 | 采用“toolCalls 非空就继续，stopReason 只做诊断”；`maxRounds` 仍是唯一上限，每工具/总调用限额本轮不做 |
| 工具执行 | Spring AI 串行；LangChain4j 默认串行、可选并行；SK 默认串行、可开并发；ADK 默认并行但阻塞工具实际串行 | 本轮保持串行；并行另立计划；文档不再声称 AgentLoop 可并行 |
| 工具异常 | LangChain4j/SK 默认回喂；Spring AI 只对 `ToolExecutionException` 回喂，其他 RuntimeException 中断；ADK 默认中断，除非注册错误回调；都不自动重试 | 采用“业务异常回喂 + 执行器故障中断 + 不重试”；错误文本含异常类名/安全摘要，不含堆栈 |
| 取消 | 同步阻塞调用普遍只能“尽力而为”；SK 全链路 `CancellationToken`；ADK RxJava dispose；Spring AI 只能停在阶段边界 | 采用“四个检查点 + 网关取消收敛”；明确阻塞工具不可强杀 |
| 生命周期 | Spring AI Advisor 环绕；LangChain4j 事件监听；SK 函数调用过滤器；ADK before/after agent/model/tool 回调 | 采用现有 7 个钩子；`afterRun` 恰好一次；异常策略见 5.2 |
| 结果聚合 | Spring AI/LangChain4j 跨轮累加 usage；SK/ADK 把每轮 usage 留在响应/事件里 | 保留现有聚合；补取消/超限路径的 usage |
| 无状态边界 | `ChatMemory`/Advisor/SessionService 独立于循环；循环只消费本轮消息 | ai-lite 的 `AgentLoopContext.copy` 方向正确，继续严格无状态 |
| 上下文 | 各框架都要求 assistant tool call 与 tool result 用 call id 对齐；ADK 并行结果合并时保持输入顺序 | 本轮保证顺序与 id；取消/超限时补合成 tool result，避免残缺历史 |

**必须借鉴**：以 tool call 存在与否决定继续；工具错误默认回喂；四类取消检查点；`afterRun` 恰好一次；无状态循环与 memory 分层。
**明确不借鉴**：Spring AI Advisor/Reactor 链、LangChain4j 动态代理 + CDI、SK Kernel 管道、ADK RxJava 事件溯源、魔法默认上限（128/500）。

---

## 5. 目标契约与决策

### 5.1 终态

**继续条件**：由“本轮是否还有 toolCalls”决定，`stopReason` 只作诊断。模型返回 `STOP` 但带 `toolCalls` 时仍执行工具并继续；这是 Spring AI、LangChain4j、ADK 的共同做法。

**例外（审查补充，必须实现）**：`stopReason` 为 `ABORTED` 或 `ERROR` 时，即使 `toolCalls` 非空也**不执行工具**。
网关在 `MODE_ROUND` 下遇到取消会带着 toolCalls 返回 `ABORTED`，若不排除会执行真实副作用。

完整判定：`shouldRunTools = toolCalls 非空 && stopReason ∉ {ABORTED, ERROR} && !ctx.isCancelled()`。

**终态矩阵（审查补充，覆盖全部组合）**：

| toolCalls | stopReason | 最后一轮 | afterTurn | 终态 |
| --- | --- | --- | --- | --- |
| 非空 | 非 ABORTED/ERROR | 否 | 任意 | 执行工具 → 下一轮 |
| 非空 | 任意 | 是 | STOP | `STOP` + 合成 tool_result |
| 非空 | 任意 | 是 | 其它 | `MAX_ROUNDS` + 合成 tool_result |
| 空 | STOP | — | STOP / CONTINUE | `STOP` / 下一轮或 `MAX_ROUNDS` |
| 空 | 任意 | — | MODEL_DECIDES | 模型 stopReason（LENGTH / CONTEXT_LENGTH_EXCEEDED / ERROR 原样） |
| 任意 | 取消标志 / ABORTED | — | — | `ABORTED`（最高优先级） |

“模型 STOP + toolCalls + 最后一轮”由 `afterTurn` 决定：有 `STOP` 决策就是 `STOP`，否则 `MAX_ROUNDS`。

| 场景 | stopReason | rounds | content | toolCalls |
| --- | --- | --- | --- | --- |
| 模型正常停止且无 toolCalls | `STOP` / `LENGTH` | 实际请求数 | 最后一轮 | 最后一轮 |
| 模型返回 STOP 但带 toolCalls | 继续执行工具 | — | — | — |
| 最后一轮 toolUse | `MAX_ROUNDS` | = maxRounds | 最后一轮 | 保留 |
| afterTurn CONTINUE 耗尽 | `MAX_ROUNDS` | = maxRounds | 最后一轮 | 最后一轮 |
| 取消（句柄） | `ABORTED` | 已完成的请求数 | 最后一轮或空 | 最后一轮 |
| 钩子/工具执行器异常 | 抛异常（不构造结果） | — | — | — |
| `future.cancel` | JDK cancelled | — | — | — |

**未执行 toolCalls 的合成规则（审查修正）**：

达到 `MAX_ROUNDS`、取消、或批次执行到一半被取消时，本轮**所有未执行**的 toolCalls 都要合成
一条 tool_result（“未执行：达到最大轮数” / “未执行：调用已取消”），不只是最后一轮。
否则上层复用 `AgentLoopContext` 时会出现 assistant tool_use 没有对应 tool_result 的 dangling 历史。

返回的 `LlmResult.toolCalls` 仍保留原始调用。

异常路径（钩子/执行器故障）**不做合成**：整个 turn 随 run 丢弃，不把半截历史交给上层。
这是取消与异常的有意差异。

### 5.2 钩子异常策略

`afterRun` **恰好执行一次**：只要 `beforeRun` 成功进入，无论正常、取消还是异常，都调用一次；
新增 `default afterRun(ctx, result, error)`，旧 `afterRun(ctx, result)` 默认委托，成功/取消时
error=null，异常路径 result=null、error=cause。

**实现约束（审查补充）**：

- 两个方法都只应在实现类里覆写**一个**；只覆写旧 2 参方法时，新 3 参方法委托它。
- `run` 必须改成单出口 `try/catch/finally`：现有 6 个 `return finish(...)` 无法保证“恰好一次”，
  也无法保证钩子/网关异常时 `afterRun` 被执行。
- `afterRun` 的异常用 `catch (Throwable)` 捕获（含 `AssertionError`），记录后继续下一个钩子；
  原始结果不被覆盖。原异常可用 `addSuppressed` 保留在诊断里。
- `finish()` 是同步/异步共用入口，改动会同时生效于 `runAsync`；这是期望行为（异步侧无
  “afterRun 异常必须外抛”的断言）。

| 钩子 | 异常策略 | 后续钩子 | afterRun |
| --- | --- | --- | --- |
| `beforeRun` | 终止，抛异常 | 否 | 不调用（run 未开始） |
| `beforeModel` | 终止，抛异常 | 否 | 调用一次（result=null, error=cause） |
| `afterModel` | 终止，抛异常 | 否 | 调用一次（result=null, error=cause） |
| `beforeTool` | 终止，抛异常 | 否 | 调用一次（result=null, error=cause） |
| `afterTool` | 终止，抛异常 | 否 | 调用一次（result=null, error=cause） |
| `afterTurn` | 终止，抛异常 | 否 | 调用一次（result=null, error=cause） |
| `afterRun` | 记录并继续下一个 afterRun | 是 | — |

`afterRun` 的异常不改变结果；异步路径也不得把成功 future 覆盖成异常。

### 5.3 取消边界

- **审查修正**：`beforeRun` 仍然执行；取消检查放在 `beforeRun` 之后、首轮 `beforeModel` 之前。
  理由：现有测试 `cancelledBeforeRunReturnsAbortedAndStillCallsAfterRun` 期望 `beforeRun` 和
  `afterRun` 各执行一次；`beforeRun` 是“run 已开始”的边界。已取消 → 零请求、零 `afterModel`。
- `beforeModel` 后、发请求前：已取消 → 不请求，不计轮次。
- 模型返回后、工具启动前：已取消 → 不启动工具，返回 `ABORTED`。
- **每个工具执行前**：已取消 → 该工具及后续工具都不启动；已启动的工具允许跑完。
- 工具批次结束后：已取消 → 不再发下一轮。
- 非合作 transport（**仅异步**，由网关在取消时以 `ABORTED` 收敛 future）：本轮不做。
  同步 `run` 是阻塞调用，取消只能靠传输层响应 abort 或超时——`LlmCancelHandle` 增加监听
  无法唤醒一个不调用 `bindAbort` 的阻塞 transport，所以同步验收不含这一项。
- `future.cancel(true)`（**仅异步**）：future 进入 cancelled，底层 abort；不产生 `LlmResult`，
  但 `afterRun` 仍按 5.2 调用一次（result=null, error=CancellationException）。

### 5.4 工具语义

**ToolExecutor SPI 契约（审查补充，必须先写死）**：

- 实现**必须**自行捕获单个工具的业务异常并返回文本；抛出异常视为**执行器级故障**。
- `AgentLoop` 与 `gateway.toolLoop` 都依赖这条契约；内置 `SequentialToolExecutor` 已满足。
- `AgentLoop` 逐条调用 `execute(singleton)` 是为了保留 `beforeTool`/`afterTool` 顺序；
  `toolLoop` 一次调用 `execute(all)`。这是两者唯一的批次粒度差异。
- 自定义 `ToolExecutor` 若不吞业务异常，`AgentLoop` 会把它当执行器故障中断，与 `toolLoop`
  的行为不同；文档必须写明，不能静默分叉。
- 工具异常回喂文本沿用现有文案“工具执行失败”，不追加异常类名，避免打破
  `LlmGatewayAdversarialTest:236` 的断言；**诊断信息通过日志和 `afterTool` 钩子暴露**。

其余规则：
- 同一轮多个工具：串行，按模型返回顺序执行；结果顺序与 call id 对齐。
- `beforeTool` deny：deny-wins，跳过执行；`afterTool` 不调用，写入文档。
- 工具业务异常：返回 tool_result 文本回喂模型，循环继续；文本包含异常类名和安全摘要，不包含堆栈；记录 toolName/callId/round/cause。
- `ToolExecutor` 抛异常或返回 null：视为执行器故障，终止 run 并写 `ERROR` 记录（与业务失败区分）。
- `beforeTool`/`afterTool` 抛异常：终止 run；已写入的 assistant 消息和部分 tool_result 不再发给模型（上下文随 run 丢弃）。
- 工具列表在每轮请求前快照。
- 本轮不引入每工具/总调用限额，也不引入可配置 `ToolErrorPolicy` SPI；默认策略固定为“业务异常回喂、执行器故障中断、不重试”。
- 最后一轮未执行的 toolCalls：向 ctx 追加合成 tool_result，避免上层复用历史时出现 dangling tool call。

### 5.5 上下文与 CONTINUE

- 工具轮：先写 assistant（content + thinking + signature + toolCalls），再按顺序写 tool_result。
- 非工具轮 + `CONTINUE`：写 assistant（content + thinking + signature），再进入下一轮。
- 取消/异常导致 turn 不完整时，不把该 turn 发往模型。
- `afterModel` 只允许改可见 content；thinking/signature 与 toolCalls 不可改（文档写明）。

### 5.6 校验与记录

- 入口校验：`call != null`、`maxRounds > 0`、hooks 列表可空、tools 结构（null/空名/重复）在第一次请求前失败。
- 业务校验（bizId 登记、模型解析、消息合法性）仍由网关负责；如果要在 `beforeRun` 前失败，需要网关暴露校验入口，列入 Phase 5 决策。
- 记录：每个真实模型请求由网关写一条；AgentLoop 合成的 `MAX_ROUNDS`/轮间 `ABORTED` 目前没有独立记录，需在 Phase 5 决定是否补 run 级记录或文档说明。
- 错误摘要只写安全信息，不打印 prompt、图片 base64、api key。

---

## 6. 实施阶段

**范围声明（2026-10-23 修订）：本轮只实现同步 `run`。** 异步 `runAsync` 的 AL-H04（递归）、
AL-H05（非合作 transport 收敛）、`LlmCancelHandle` 取消监听全部延后；共享代码的改动必须保证
`runAsync` 现有测试继续通过。
**（2026-10-24 更新：同步 Phase 0–5 已全部完成；异步内容已拆分至《夯实AgentLoop异步执行器-plan.md》。）**

### Phase 0：网关前置（同步）✅ 已完成（2026-10-24）

- 新增 `LlmGateway.round(LlmCall, List<LlmTool>, LlmCancelHandle)`，复用现有同步 `invoke(..., cancel)`。
- 只加 3 参重载；**不得**再新增 `round(call, tools, Integer)`，否则 `round(c, t, null)` 会歧义。
- 验收：预取消零请求；现有测试通过。

### Phase 1：不挂起（同步，P0）✅ 已完成（2026-10-24）

- `run` 把 `ctx.getCancel()` 传给 `gateway.round(call, tools, cancel)`。
- `run` 改为单出口 `try/catch/finally`：用一个局部 `LlmResult outcome`，所有终止分支只赋值不返回；
  `finally` 里调用一次 `afterRun`。
- 新增 `AgentLifecycle.afterRun(ctx, result, error)` default 方法，默认委托旧方法。
- `afterRun` 逐个 `try/catch (Throwable)`，异常只记录、不覆盖结果；`beforeRun` 失败时不调用。
- 验收：钩子或网关异常时 `afterRun` 恰好一次且原异常仍抛出；`afterRun` 抛异常时成功结果
  仍返回；现有 22 个测试除明确修正项外全部通过。

### Phase 2：上下文与终止（同步，P1）✅ 已完成（2026-10-24）

- 续行条件改为 `toolCalls 非空 && stopReason ∉ {ABORTED, ERROR} && !ctx.isCancelled()`。
- `CONTINUE` 非工具轮追加 assistant 消息（content + thinking + signature）。
- 取消检查点：`beforeRun` 之后、每次 `beforeModel` 之后、模型返回之后、每个工具执行之前、
  工具批次之后。
- 停止优先级：取消 > STOP > MAX_ROUNDS > 模型；最后一轮 `toolCalls + STOP` 且 `afterTurn` 有
  STOP 决策时返回 `STOP`，否则 `MAX_ROUNDS`。
- `CONTINUE` 耗尽返回 `MAX_ROUNDS`。
- **所有未执行的 toolCalls 都合成 tool_result**（最后一轮、取消、批次中途取消三种情况）。
- 明确首轮前取消时 provider/model/thinkingLevel 为 null，并写入文档。
- 验收：第二轮请求包含第一轮 assistant；终态矩阵逐格覆盖；ctx 历史无 dangling tool call。

### Phase 3：工具语义（同步，P1）✅ 已完成（2026-10-24）

- 写死 `ToolExecutor` SPI 契约：实现必须自行捕获单工具业务异常；抛出即执行器故障。
- `AgentLoop.execute` 的 catch 只处理执行器故障：终止 run，并把 toolName/callId/round/cause
  写入日志。
- 业务异常回喂文本保持“工具执行失败”，不追加类名，避免打破既有断言。
- `beforeTool` deny 不调用 `afterTool`（现状已如此，补测试）。
- 每个工具执行前检查取消；未启动的工具不启动。
- 与 `gateway.toolLoop` 的批次粒度差异写入文档，并做 parity 测试（同一个吞异常工具在两条入口
  下终态一致）。
- 验收：执行器故障终止一次；业务失败回喂后循环继续；无第二次工具调用。

### Phase 4：钩子与并发（同步，P1）✅ 已完成（2026-10-24）

- 钩子列表和工具列表做防御性拷贝。
- 并发 `run` 互不串扰；每个 run 独立 ctx。
- 验收：每个钩子异常用例；并发用例。

### Phase 5：测试与文档（P2）✅ 已完成（2026-10-24）

- 新增约 15–20 个同步用例；异步专属用例标注为后续。
- 更新 `api-usage`、`architecture`、`docs/testing/README.md`、README 统计、本计划执行记录。

### Phase 6：异步——已拆分至独立计划（2026-10-24）

异步 `runAsync` 不再以存根形式挂在本计划。AL-H04（蹦床）、AL-H05（取消监听收敛）、`decideRound` 共享、AL-A1/A2/A3（并行工具 / 执行器 future 自适配 / 整轮超时），以及异步侧专项缺陷（终态优先级错位、CONTINUE 非工具轮不追加 assistant、异常路径不跑 afterRun、hooks 未拷贝）全部移入《夯实AgentLoop异步执行器-plan.md》，含分阶段实施与测试矩阵；调研依据见本文 10.4。

---

## 7. 测试矩阵

| 类别 | 用例 | 断言 |
| --- | --- | --- |
| 终态 | 正常 STOP / LENGTH / 最后一轮 TOOL_USE | stopReason、rounds、content、toolCalls 正确 |
| 终态 | 模型返回 STOP 但带 toolCalls | 工具执行并继续，不提前结束 |
| 终态 | CONTINUE 耗尽 / 最后一轮 TOOL_USE+STOP | MAX_ROUNDS / STOP 优先级正确 |
| 终态 | MAX_ROUNDS 时仍有未执行 toolCalls | ctx 追加合成 tool_result，历史无 dangling call |
| 取消 | 同步在途取消 | 1 秒内 ABORTED，传输层 abort 被观察 |
| 取消 | beforeModel 内取消 | 零请求、rounds=0、无 afterModel |
| 取消 | afterModel 内取消 | 零工具执行、ABORTED |
| 取消 | 工具执行中取消 | 已启动工具跑完，后续工具不启动，不再发下一轮 |
| 取消 | 工具批次内取消 | 每个工具执行前的检查点生效 |
| 取消 | 非合作 transport 取消 | 1 秒内 ABORTED，不挂起 |
| 取消 | future.cancel | cancelled、底层 abort、afterRun 契约 |
| 钩子 | 7 个钩子各抛 RuntimeException | 按 5.2 终止/继续 |
| 钩子 | afterRun 抛异常 | 成功结果不被覆盖 |
| 钩子 | 异常/取消路径的 afterRun | 恰好执行一次，error 可见 |
| 钩子 | 多钩子中第一个抛异常 | 后续钩子不执行 |
| 上下文 | CONTINUE 第二轮请求 | 包含第一轮 assistant 正文/thinking/signature |
| 上下文 | 工具轮 assistant/tool_result | 顺序、call id 对齐 |
| 上下文 | 取消/异常留下半截 turn | 不发下一轮 |
| 工具 | 工具业务异常 | 错误文本含类名/安全摘要，回喂后循环继续，cause 可诊断 |
| 工具 | 错误文本不含堆栈/密钥/图片 | 脱敏断言 |
| 工具 | ToolExecutor 抛异常/null | 终止 + ERROR 记录，不 NPE |
| 工具 | deny + afterTool | deny 生效，afterTool 不调用 |
| 工具 | 同轮多工具一个失败 | 其余是否继续按契约，call id 不错配 |
| 并发 | 同实例并发 run | 结果互不串扰 |
| 递归 | completedFuture + CONTINUE + 大 maxRounds | 不 StackOverflow，5 秒内完成 |
| Error | 钩子抛 AssertionError | future 终结，不挂起 |
| Parity | 同一工具在 AgentLoop / gateway.toolLoop | 差异被文档化并测试 |
| 校验 | null call / maxRounds<=0 / 非法 tools | 发请求前失败 |

---

## 8. 明确不做

- 会话持久化、跨 run 记忆、checkpoint、resume、HITL、审批。
- 图编排、DAG、多 Agent、handoff、guardrail agent。
- 自动重试工具或模型请求；自动退避/熔断。
- 并行工具执行（本轮不做；已由《夯实AgentLoop异步执行器-plan.md》的 AL-A1 落地，`ToolRunMode.PARALLEL` 对 `run` 与 `runAsync` 都生效）；流式 AgentLoop 入口。
- 每工具/总调用限额；`ToolErrorPolicy` SPI（本轮固定默认策略）。
- 自动 token 预算、上下文截断、模型降级。
- 结构化输出、输出重试预算。
- 在 AgentLoop 内新增独立调用记录/指标（记录口径在 Phase 5 决定）。
- 把 `LlmResult` 改成新的 `AgentRunResult` 类型（0.x 先用现有 stopReason）。

---

## 9. 验收标准

- P0 AL-H01–H05 全部修复并有带超时的回归测试。
- 7 个钩子的异常策略与 5.2 一致；`afterRun` 不再覆盖结果。
- 取消后不再发请求、不再启动新工具；非合作 transport 不挂起。
- `CONTINUE` 上下文正确；终态优先级符合 5.1/5.3。
- 工具错误可诊断、无静默吞异常；执行器故障有 `ERROR` 记录。
- 现有 `AgentLoopTest` 除明确修正的断言外全部通过。
- 全量测试通过，新增测试约 25–30 个，不挂起 CI。
- 文档中不再有“同步取消可用”“工具可并行”“afterRun 一定执行”等过期表述。

---

## 10. 调研与审计记录

### 10.1 现状审计

共 25 条：P0 5 条（AL-H01–H05）、P1 9 条（AL-H06–H14）、P2 11 条（AL-H15–H25）。

已确认过期、不要重复实现的条目：AL-03（cancel 幂等已修）、AL-30-A（null completion 已在网关修复）、AL-21（io() 拒绝已处理）、AL-06/19/27/28/29（已实现，缺测试）。

### 10.2 Python/TypeScript 调研

对象：OpenAI Agents SDK、Claude Agent SDK、LangGraph、PydanticAI、Vercel AI SDK、smolagents。关键结论：

- 终止原因是一等数据；max steps 保留最后一次 tool call/output。
- 工具错误默认作为数据回喂，并绑定 call id；工具不自动重试。
- 取消必须传播到 HTTP/工具；只在循环边界检查是反例。
- 钩子趋势是只读观察 + fail-fast；可改写结果的中间件属于更重的框架。
- 无状态 run 与 session/checkpoint 明确分层。

### 10.3 Java 生态调研

对象：Spring AI 2.0.1、LangChain4j 1.9.1、Semantic Kernel（.NET）、Quarkus LangChain4j、Google ADK Java。关键结论：

- 没有一家只看 `finish_reason`；继续条件统一为“是否还有 tool call”。
- 工具异常默认回喂是主流，但致命异常边界各家不同；都不自动重试工具。
- 并行执行常见，但都保证“按请求顺序写回 + call id 关联”；ai-lite 本轮保持串行。
- 同步阻塞取消普遍只能尽力而为；SK 的 `CancellationToken` 和 ADK 的 dispose 也只能停在阶段边界。
- Spring AI/LangChain4j 跨轮累加 usage；SK/ADK 把每轮 usage 留给调用方。
- memory/session 与循环分层清晰；ai-lite 的 `AgentLoopContext.copy` 方向正确。
- Spring AI/LangChain4j/ADK 都要求 Java 17+；可移植的是状态机、call id 关联、错误回喂、限额和 usage 累加，不能照搬容器/响应式抽象。

### 10.4 补充调研（2026-10-24，异步阶段对照；同日二次核实）

**来源声明：首轮为检索摘要级，同日用官方文档原文（LangChain4j Tools、Spring AI Tool Calling）与 issue #1778 二次核实，修正首轮两处错误。** 10.3 早期源码级调研的结论（LangChain4j 默认串行可选并行、并行按请求顺序写回）与本次核实一致。

核实后的结论：

- **并行工具**：LangChain4j 的并行是 AiServices 上的显式开关 `executeToolsConcurrently()` / `executeToolsConcurrently(Executor)`；ChatModel 下多工具用 Executor 并发、**单工具仍在调用者线程执行**（官方 Tools 文档原文）；工具无超时机制。Spring AI 文档未见工具级并发开关。首轮"工具返回 CompletableFuture 即并行"的说法**有误**，已修正 → AL-A1 采用显式开关形态。
- **返回类型自适配**：**两家都没有**。LangChain4j 文档未提供该形态；Spring AI 2.0 的 MethodToolCallback 明确禁止 `CompletableFuture`/`Future`/`Mono`/`Flux` 返回类型（"not serializable to the model"），`ToolCallback` 接口是同步的 `String call(...)`；原生响应式工具调用 issue #1778 至今 OPEN（tzolov："Reactive FunctionCallback isn't currently supported"；chemicL：需要整个技术栈响应式化；社区靠 boundedElastic 包装或第三方 `@ReactiveTool` 桥接）。首轮"Spring AI 检测 reactive 类型自动异步"的说法**有误**，已修正 → AL-A2 降级为无先例的自有设计。
- **超时与限额**：两家均无工具/循环级时间超时。Spring AI 提供的是次数维度限额 Tool Call Limits（`max-calls-per-tool` 默认 40、`max-total-tool-calls` 默认 150，超限 THROW 或 RETURN_ERROR_RESPONSE）——时间维度（AL-A3）无先例；次数限额有先例但属 §8 明确不做项。
- 两家的异步形态都是三层：整次调用返回 future/Flux、每轮模型调用异步、工具执行异步——与 AL-H04/H05 计划同构，方向得到印证；但 Reactor 调度天然遮掉了取消收敛问题，两家文档都没有展开我们手写 CPS 必须显式处理的部分。（该条为首轮结论，二次核实后维持。）

明确不借鉴（补充）：Reactor 自动调度魔法（ai-lite 手写 CPS 就是为了显式管住线程边界，属差异化而非落后）、agentic 模块的 planner/并行块编排（一次性 AgentLoop 不背该复杂度，归 Workflow）、human-in-the-loop（LangChain4j issue #3405 自认 naive，现有 `beforeTool.deny` 覆盖主场景）。

**方法论教训**：检索摘要级调研只能用于方向判断，进入计划的条目必须以官方文档或源码原文核实——本轮两处错误均由摘要引入、由原文纠正；AiServices 1.9.1 源码抓取超时，`executeToolsConcurrently` 的实现级细节（默认 Executor 构造等）开工时如需再读源码。

---

## 11. 执行记录（2026-10-24，同步部分）

本轮只实现同步 `run`；异步 `runAsync` 的 AL-H04 / AL-H05 与 `LlmCancelHandle` 取消监听延后。

### 审查结论（独立子代理）

- P0 全部真实存在；AL-H04、AL-H05 **仅影响异步**，同步不可修（监听器无法唤醒不调用
  `bindAbort` 的阻塞 transport）。
- 遗漏的同步 P0：`run` 全程无 try/catch，钩子/网关异常时 `afterRun` 一次都不执行。
- 3 参 `round(call, tools, cancel)` 无重载歧义，可以安全新增。
- “toolCalls 非空就继续”必须排除 `ABORTED` / `ERROR`，否则会执行真实副作用。
- “模型 STOP + toolCalls + 最后一轮”原先无定义，现由 `afterTurn` 决定。

### 已落地

| 阶段 | 内容 |
| --- | --- |
| 网关 | 新增 `round(call, tools, cancel)`；Javadoc 写明不得再加 `round(call, tools, Integer)` |
| 钩子 | `AgentLifecycle.afterRun(ctx, result, error)` default 委托旧方法；只覆写一个 |
| 单出口 | `run` 改为 `try/catch/finally`，`runRounds` 只返回结果，`afterRun` 在 finally 恰好一次 |
| 观察钩子 | `afterRun` 用 `catch (Throwable)`，异常只记日志，不覆盖结果、不阻止后续钩子 |
| 取消 | 五个检查点；`beforeRun` 后取消也保证 `beforeRun`/`afterRun` 各一次 |
| 续行 | `toolCalls 非空 && stopReason ∉ {ABORTED, ERROR}`；`STOP + toolCalls` 仍执行工具 |
| 终态 | 取消 > STOP > MAX_ROUNDS > 模型；最后轮 `toolCalls + STOP 决策` 返回 STOP |
| 上下文 | 非工具轮 `CONTINUE` 追加 assistant；所有未执行 toolCalls 合成 `tool_result` |
| 工具 | `ToolExecutor` 契约写死；执行器故障 → `LlmCallException("工具执行器故障")` + 日志；业务异常仍由执行器回喂“工具执行失败” |
| 测试 | 新增 `AgentLoopSyncHardeningTest` 18 条 |

### 顺带修复

`LlmGatewayAsyncRetryTest.awaitAttempts` 原断言“恰好等于 expected”，与零延迟重试存在竞态
（第 2 次尝试可能在断言前启动）。改为“至少 expected”，精确次数仍由调用方区间断言。
连续 3 次运行通过。

### 后续

- 异步：见独立计划《夯实AgentLoop异步执行器-plan.md》（蹦床化、`LlmCancelHandle` 取消监听、
  非合作 transport 收敛、`decideRound` 共享、AL-A1–A3）。
