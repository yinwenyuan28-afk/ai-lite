# 夯实 AgentLoop 一次性执行

**状态（2026-10-22）：现状审计、Python/TypeScript 调研、Java 生态调研均已完成，计划可进入评审/实现。** 本计划只覆盖一次性、无上下文持久化的 `AgentLoop.run` / `runAsync`，不涉及 `AgentSessionLoop`、会话存储或 Workflow。

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
| AL-H04 | 已完成 future 触发同步递归 `next()`，且只捕获 `RuntimeException` | 多轮 CONTINUE 可 `StackOverflowError`；Error 可能导致 future 永不完成 |
| AL-H05 | 非合作 transport 下 `handle.cancel()` 不能终结 future | `roundFuture` 永久 pending，调用方挂起 |

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

| 场景 | stopReason | rounds | content | toolCalls |
| --- | --- | --- | --- | --- |
| 模型正常停止且无 toolCalls | `STOP` / `LENGTH` | 实际请求数 | 最后一轮 | 最后一轮 |
| 模型返回 STOP 但带 toolCalls | 继续执行工具 | — | — | — |
| 最后一轮 toolUse | `MAX_ROUNDS` | = maxRounds | 最后一轮 | 保留 |
| afterTurn CONTINUE 耗尽 | `MAX_ROUNDS` | = maxRounds | 最后一轮 | 最后一轮 |
| 取消（句柄） | `ABORTED` | 已完成的请求数 | 最后一轮或空 | 最后一轮 |
| 钩子/工具执行器异常 | 抛异常（不构造结果） | — | — | — |
| `future.cancel` | JDK cancelled | — | — | — |

**最后一轮未执行的 toolCalls**：达到 `MAX_ROUNDS` 或取消导致本轮 toolCalls 未执行时，向 `AgentLoopContext` 追加合成的 tool result（例如“未执行：达到最大轮数/调用已取消”），保证 ctx 历史可被上层复用；返回的 `LlmResult.toolCalls` 仍保留原始调用。

### 5.2 钩子异常策略

`afterRun` **恰好执行一次**：只要 `beforeRun` 成功进入，无论正常、取消还是异常，都调用一次；新增 `default afterRun(ctx, result, error)`，旧 `afterRun(ctx, result)` 默认委托，成功/取消时 error=null，异常路径 result=null、error=cause。

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

- 进入 `beforeRun` 前：已取消 → 直接返回 `ABORTED`，零请求，零 `afterModel`。
- `beforeModel` 后、发请求前：已取消 → 不请求，不计轮次。
- 模型返回后、工具启动前：已取消 → 不启动工具，返回 `ABORTED`。
- **每个工具执行前**：已取消 → 该工具及后续工具都不启动；已启动的工具允许跑完。
- 工具批次结束后：已取消 → 不再发下一轮。
- 非合作 transport：由网关在取消时主动以 `ABORTED` 收敛 future，不依赖传输层响应 abort。
- `future.cancel(true)`：future 进入 cancelled，底层 abort；不产生 `LlmResult`，但 `afterRun` 仍按 5.2 调用一次（result=null, error=CancellationException）。

### 5.4 工具语义

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

### Phase 0：契约基线与网关前置（P0）

- 在 `docs/` 固化 5.1–5.6 的终态、钩子、取消、工具、上下文契约（并入 `api-usage` 与 `architecture`）。
- 新增 `LlmGateway.round(LlmCall, List<LlmTool>, LlmCancelHandle)`，复用现有同步 `invoke(..., cancel)`。
- 网关取消收敛：`LlmCancelHandle` 增加取消监听（additive），`invokeAsync` 注册“取消时以 `ABORTED` 完成 result”，解决非合作 transport 的永久 pending。
- 验收：预取消零请求；非合作 transport 取消 1 秒内返回 `ABORTED`；现有测试通过。

### Phase 1：不挂起（P0）

- `run` 把 `ctx.getCancel()` 传给 `gateway.round(call, tools, cancel)`。
- `beforeModel` 后、模型返回后、工具启动前各加一次取消检查。
- `afterRun` 改为观察钩子：无论正常、取消还是异常都恰好执行一次（`beforeRun` 失败除外）；新增 `default afterRun(ctx, result, error)`，逐个 try/catch、记录、继续；不覆盖结果。
- 异步推进改为 trampoline：`next()` 通过 `executor.io()` 提交，禁止在 `whenComplete` 内递归；所有 future 边界 `catch (Throwable)` 并完成 `result`。
- 验收：AL-H01–H05 全部有回归测试；同步完成 + 大 maxRounds 不栈溢出；`AssertionError` 不会让 future 挂起；异常/取消路径 `afterRun` 恰好一次。

### Phase 2：上下文与终止（P1）

- 继续条件改为“toolCalls 非空”，不再要求 `stopReason == TOOL_USE`。
- `CONTINUE` 非工具轮追加 assistant 消息。
- `beforeModel` 取消不计轮次、不调用 `afterModel`。
- 停止优先级：取消 > STOP > MAX_ROUNDS > 模型；最后一轮 `TOOL_USE + STOP` 返回 `STOP`。
- `CONTINUE` 耗尽返回 `MAX_ROUNDS`。
- 最后一轮未执行的 toolCalls 向 ctx 追加合成 tool_result。
- 明确首轮前取消时 provider/model/thinkingLevel 为 null，并写入文档。
- 验收：第二轮请求包含第一轮 assistant；六组冲突场景终态正确；ctx 历史无 dangling tool call。

### Phase 3：工具语义（P1）

- 工具异常结构化：toolName/callId/round/cause 记录；回喂文本包含异常类名和安全摘要，不含堆栈。
- 区分业务失败与执行器故障；执行器故障写 `ERROR` 记录并终止。
- `beforeTool` deny 不调用 `afterTool`，写入文档并测试。
- 每个工具执行前检查取消；部分工具批次失败时，run 终止，不发下一轮。
- 工具列表请求前快照。
- 与 `gateway.toolLoop` 做 parity 测试；无法统一的行为写入文档。
- 验收：工具错误可定位、无第二次调用、上下文不残缺。

### Phase 4：钩子与并发（P1）

- 钩子列表防御性拷贝；工具列表快照。
- 按 5.2 实现异常策略；`afterRun` 只读。
- 取消与钩子并发：取消后不再启动新钩子链；已进入的同步钩子允许完成。
- `future.cancel` 与 `handle.cancel` 的终态差异写入文档和测试。
- 验收：每个钩子异常用例；并发 run 互不串扰。

### Phase 5：校验、记录与文档（P2）

- 入口结构校验与网关业务校验的边界；决定是否暴露 `LlmGateway.validateCall`。
- 决定记录口径：每轮 `roundNo`、run 级聚合、轮间取消是否补记录。
- 文档修正：类注释、architecture §6、api-usage、testing 设计中的过期条目。
- 验收：文档与实现一致；记录回归测试通过。

### Phase 6：测试与全量回归

- 新增约 25–30 个用例，覆盖第 7 节矩阵。
- 全量 `mvn test` 通过，所有异步断言带超时。

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
- 并行工具执行（本轮）；流式 AgentLoop 入口。
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
