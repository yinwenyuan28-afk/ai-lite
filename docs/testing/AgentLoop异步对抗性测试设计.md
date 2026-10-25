# AgentLoop 异步编排对抗性测试设计

基线：`AgentLoop.runAsync` 当前实现（含回调异常出口、工具执行器注入、`LlmCancelHandle` 取消链）。本文只设计测试，不修改生产代码。
共 **33** 个用例：**P0 13 个、P1 17 个、P2 3 个**。全部用例在现有测试中没有完整覆盖，最多只有同步版或单钩子版的部分覆盖；happy path 不重复。

约定：代码路径省略前缀 `ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/`；测试路径省略 `ai-lite-core/src/test/java/io/github/yinwenyuan28/ailite/`。所有异步断言必须带 `get(timeout)` 或 `CompletableFuture` 超时，禁止无界等待。

## 总表

| ID | 优先级 | 场景一句话 | 代码路径 | 现有测试覆盖 |
|----|--------|------------|----------|--------------|
| AL-01 | P0 | beforeModel 中取消，请求未发出却被计为一轮且仍跑 afterModel/afterTurn | AgentLoop.java:249-253,270-274 | 无（gateway 同步版类似） |
| AL-02 | P0 | 阻塞工具执行中外部取消，工具跑完、钩子照跑、结果 ABORTED | AgentLoop.java:288-310,338-343 | 部分（gateway 同步版） |
| AL-03 | P0 | 多线程重复 cancel，abort 被重复触发而非幂等一次 | LlmCancelHandle.java:19-25 | 无 |
| AL-04 | P0 | 外部句柄 cancel 与 future.cancel 混用/并发，终态互斥、afterRun 差异 | AgentLoop.java:201-205,249-252 | 部分（两条链各自单测） |
| AL-05 | P1 | handle.cancel 与 result.complete 竞态，单一终态且不挂起 | AgentLoop.java:201-205,362-368 | 无 |
| AL-06 | P1 | ABORTED 结果的 usage/latency/content/toolCalls/rounds 聚合 | AgentLoop.java:339-343,485-492 | 无 |
| AL-07 | P1 | afterTurn 想 CONTINUE 但已取消，取消优先且不发下一轮 | AgentLoop.java:317-343 | 无 |
| AL-08 | P0 | beforeRun 抛异常：不向调用方同步抛，返回 failed future，无 afterRun | AgentLoop.java:193-199 | 无 |
| AL-09 | P1 | beforeModel 第 N 轮抛异常，多钩子短路且无 afterRun | AgentLoop.java:235-243,253 | 部分（单钩子、未断言 afterRun） |
| AL-10 | P0 | afterTurn 抛异常：终止循环、不发下一轮、无 afterRun | AgentLoop.java:317-337 | 无 |
| AL-11 | P0 | afterRun 抛异常把成功/ABORTED 结果覆盖成异常 future | AgentLoop.java:362-368,466-473 | 无 |
| AL-12 | P0 | beforeTool 在 io 线程抛异常，future 异常且无 tool_result | AgentLoop.java:288-310,390-402 | 无 |
| AL-13 | P1 | afterTool 抛异常，工具已执行、assistant 已写入、future 异常 | AgentLoop.java:408-418,301-310 | 无 |
| AL-14 | P1 | afterModel 把 content 改成 null/空串的归一化行为 | AgentModelOutput.java:26-28,AgentLoop.java:118 | 部分（只测非空改写） |
| AL-15 | P1 | beforeTool 返回 null / allow(null) / allow("") 三种语义 | AgentLoop.java:394-405 | 无（仅测合法 JSON 改写） |
| AL-16 | P1 | 多钩子 beforeTool：先改参数后被拒绝，拒绝优先并短路 | AgentLoop.java:390-407 | 部分（单钩子拒绝） |
| AL-17 | P1 | afterTool 返回 null 与空串的区别及链式改写 | AgentLoop.java:408-418 | 部分（单钩子非空改写） |
| AL-18 | P1 | async 工具轮中 STOP 与 CONTINUE 并存，STOP 优先 | AgentLoop.java:317-359 | 部分（同步、无工具） |
| AL-19 | P1 | 最后一轮 TOOL_USE 时 afterModel 取消，ABORTED 优于 MAX_ROUNDS | AgentLoop.java:339-348 | 部分（同步、非最后一轮） |
| AL-20 | P0 | ToolExecutor 抛异常被吞成“工具执行失败”，循环继续 | AgentLoop.java:427-439 | 无（gateway 层工具抛错不算） |
| AL-21 | P0 | executor.io() 拒绝任务或返回 null，future 异常而非挂起 | AgentLoop.java:288-300 | 无 |
| AL-22 | P0 | 同一轮多工具顺序，中间拒绝，tool_result 与调用严格对齐 | AgentLoop.java:383-421 | 无 |
| AL-23 | P0 | ToolExecutor 返回 null/空表/null 元素/超长列表的归一化 | AgentLoop.java:429-435 | 无（gateway 层有 null 结果） |
| AL-24 | P2 | hook 清空/替换工具列表，影响执行与下一轮声明 | AgentLoopContext.java:59-64,AgentLoop.java:407 | 部分（同步 beforeRun 替换） |
| AL-25 | P2 | 未知工具/非法 JSON 的文本回灌，afterTool 可再改写 | AgentLoop.java:407-419 | 部分（同步、未改写） |
| AL-26 | P1 | maxRounds=1 且最后一轮 TOOL_USE：不跑工具但仍调 afterTurn | AgentLoop.java:283-286,344-348 | 部分（同步、未断言钩子） |
| AL-27 | P1 | maxRounds=null 走默认 8；0/负数同步抛且不触发任何钩子 | AgentLoop.java:179-185 | 部分（仅 0） |
| AL-28 | P1 | 空 tool_calls 但 stop_reason=TOOL_USE 不触发工具和下一轮 | AgentLoop.java:283-286,354-359 | 无（仅有 gateway 同步版） |
| AL-29 | P2 | 正常多轮 usage/latency/rounds 与最终 content 累加 | AgentLoop.java:270-274,485-492 | 无 |
| AL-30 | P0 | 传输完成 null 或永不完成时 future 的挂起风险（超时断言） | AgentLoop.java:262-274,LlmGateway.java:353-365 | 部分（仅钩子异常） |
| AL-31 | P1 | 各钩子与工具的线程亲和性，beforeRun 阻塞会阻塞入口 | AgentLoop.java:193-206,288-296 | 部分（仅工具线程） |
| AL-32 | P1 | ctx.getMessages()/getTools() 跨线程交接的可见性与作用范围 | AgentLoopContext.java:29-40,AgentLoop.java:385-419 | 部分（happy path 间接） |
| AL-33 | P1 | 大量 CONTINUE + 已完成 future 触发深递归/StackOverflow 逃逸 | AgentLoop.java:235-243,354-357 | 无 |

## 用例详情

### 一、取消与终态

#### AL-01 beforeModel 中取消（P0）
- 构造/动作：首轮 `beforeModel` 内调用 `ctx.getCancel().cancel()`；`FakeTransport` 记录请求。
- 期望：`transport.requests` 为空；future 是正常完成而非 cancelled，`stopReason=ABORTED`，但 `rounds=1`、`usage=0`；`afterModel`、`afterTurn`、`afterRun` 各执行 1 次。
- 价值/断言：取消发生在发送前仍被 gateway 立刻返回 ABORTED，AgentLoop 把这次“空轮”计入 `completed` 并触发模型后钩子。断言 `rounds==1 && requests.isEmpty()` 同时成立，锁定或暴露该语义。

#### AL-02 阻塞工具执行中取消（P0）
- 构造/动作：首轮 TOOL_USE，工具阻塞在 latch；外部线程 `cancel()` 后释放工具；transport 预置第二轮 STOP。
- 期望：工具完整执行 1 次且不抛中断；`afterTool` 1 次、`afterTurn` 1 次（均早于取消检查）；结果 ABORTED、`rounds=1`；只发出 1 次请求；`afterRun` 1 次。
- 价值/断言：取消是协作式，不中断正在执行的工具；取消检查在工具和 afterTurn 之后。断言 `executions==1`、`requests.size()==1`、`future.get(2s)`。

#### AL-03 多线程重复 cancel（P0）
- 构造/动作：transport 在 `completeAsync` 中 `bindAbort(counter::incrementAndGet)` 并返回未完成 future；4 个线程各调用 `cancel()` 10 次。
- 期望：无异常；`abort` 计数等于 1（2026-10-21 已修复为 exactly-once）；future 最终由外层 cancel 收尾且不挂起。
- 价值/断言：重复取消不得重复触发连接中断动作。断言 abort 精确计数，而不是只断言 `isCancelled()`。

#### AL-04 句柄 cancel 与 future.cancel 混用（P0）
- 构造/动作：A 场景 transport 挂起，线程 1 调 `handle.cancel()`、线程 2 同时 `future.cancel(true)`；B 场景正常完成后依次调 `handle.cancel()` 与 `future.cancel(true)`。
- 期望：A 终态互斥，`future.isCancelled()` 与“正常完成且 ABORTED”恰有一个成立；`afterRun` 次数 ≤1；abort 至少触发 1 次。B 中 `future.cancel` 返回 false，结果保持原 STOP，`afterRun` 仍只 1 次。
- 价值/断言：handle 路径产出 ABORTED 结果并执行 afterRun；future.cancel 路径直接 cancelled、不执行 afterRun。用 `get(5s)` 包住并断言终态数量。

#### AL-05 cancel 与 result.complete 竞态（P1）
- 构造/动作：transport 在 `CyclicBarrier` 处等待，测试线程同时在 barrier 后 `cancel()`，随后传输正常完成；重复 100 次。
- 期望：每次 future 都在 1s 内结束；不出现重复完成异常；`afterRun` ≤1；非 cancelled 结果只能是 STOP 或 ABORTED。
- 价值/断言：验证 `result.complete` 与取消回调交错时不会丢失/重复终态。关键是把超时放进循环，任何一次挂起立即失败。

#### AL-06 ABORTED 结果聚合（P1）
- 构造/动作：两轮 TOOL_USE，usage 分别为 (10,5,15)/(20,10,30)，latency 100/200；第二轮 `afterTurn` 中取消。
- 期望：结果 `stopReason=ABORTED`，content/toolCalls 来自第二轮，usage=(30,15,45)，latency=300，`rounds=2`。
- 价值/断言：取消不能丢已消耗用量和最后可见内容。逐字段断言四个聚合值。

#### AL-07 CONTINUE 与取消冲突（P1）
- 构造/动作：首轮 STOP；钩子 A 在 afterTurn 中取消，钩子 B 返回 `continueRound()`。
- 期望：两个 afterTurn 都执行；只发出 1 次请求；结果 ABORTED 而非继续；afterRun 1 次。
- 价值/断言：终态优先级为 取消 > 停止/继续；验证取消检查位于钩子循环之后。断言 `requests.size()==1` 且 `stopReason==ABORTED`。

### 二、生命周期钩子

#### AL-08 beforeRun 抛异常（P0）
- 构造/动作：`beforeRun` 抛 `IllegalStateException`；调用 `runAsync` 时不包 try。
- 期望：调用方不收到同步异常；返回的 future 已失败（`get(1s)` 抛 `ExecutionException`，cause 为原异常）；transport 0 请求；`afterRun/afterModel/afterTurn` 均 0 次。
- 价值/断言：异步入口统一用 future 表达钩子错误，但 afterRun 不会兜底执行。断言 `future.isDone()` 立即为 true。

#### AL-09 beforeModel 第 N 轮抛异常（P1）
- 构造/动作：两个钩子 A/B，A 在第 2 轮 `beforeModel` 抛异常；首轮 TOOL_USE。
- 期望：future 异常且 cause 为原始异常；B 第 2 轮 `beforeModel` 未执行；第 2 轮未发出（requests=1）；`afterRun` 0 次。
- 价值/断言：多钩子串行执行，前一个抛错会短路后续钩子；异常出口在 `next()` 的 catch。断言 B 的事件计数为 0。

#### AL-10 afterTurn 抛异常（P0）
- 构造/动作：首轮 TOOL_USE 且工具正常；`afterTurn` 抛异常；maxRounds=4。
- 期望：工具已执行且 tool_result 已写入；future 异常；不发第 2 轮；`afterRun` 0 次。
- 价值/断言：afterTurn 是轮次推进点，异常必须终止循环而不是继续。断言 requests=1、executions=1、afterRun=0。

#### AL-11 afterRun 抛异常（P0）
- 构造/动作：A 场景首轮 STOP 正常结束；B 场景预先取消进入 ABORTED；两种场景的 afterRun 都抛异常。
- 期望：两种场景 future 都异常完成（cause 为 afterRun 异常），不返回 LlmResult；afterRun 各调用 1 次；A 请求 1 次、B 请求 0 次。
- 价值/断言：`completeWith` 把 `finish` 包在 try 里，afterRun 失败会覆盖业务结果。断言 `ExecutionException` 而非拿到 ABORTED/STOP。

#### AL-12 beforeTool 抛异常（P0）
- 构造/动作：首轮 TOOL_USE；`beforeTool` 抛异常；记录回调线程名。
- 期望：future 异常（原始异常经 `toolsFuture` 解包）；工具未执行；无 tool_result；`afterTool/afterTurn/afterRun` 0 次；无第 2 轮；异常发生在 io 线程。
- 价值/断言：工具阶段的钩子错误走 `CompletableFuture.runAsync` 的异常出口，不能被降级成“工具执行失败”。断言 executions=0 与 future cause。

#### AL-13 afterTool 抛异常（P1）
- 构造/动作：首轮 TOOL_USE，工具正常返回；`afterTool` 抛异常。
- 期望：future 异常；executions=1；assistant 消息已写入 ctx；tool_result 未写入；afterTurn/afterRun 0；无第 2 轮。
- 价值/断言：与 AL-20 对照，钩子异常不被吞、执行器异常被吞。断言 tool_result 数量为 0。

#### AL-14 afterModel 改写 content 为 null/空串（P1）
- 构造/动作：首轮 TOOL_USE content="orig"，afterModel 调 `setContent(null)`；另一轮调 `setContent("")`。
- 期望：助手消息 text 为 ""；工具照常执行；stopReason、toolCalls、usage 不受影响；最终结果取后续轮。
- 价值/断言：`AgentModelOutput.setContent` 做 null→"" 归一化；content 为空不能影响 `toolUse` 判定（判定看 stopReason 和 toolCalls）。断言第 2 次请求中 assistant 消息 text 为空。

#### AL-15 beforeTool 返回 null / allow(null) / allow("")（P1）
- 构造/动作：三个独立运行分别让钩子返回 `null`、`ToolDecision.allow(null)`、`ToolDecision.allow("")`；工具记录收到的参数 Map。
- 期望：前两者视为放行且不改参数；`allow("")` 把参数改成空串，SequentialToolExecutor 视为空 JSON 得到空 Map，工具仍执行；三次均执行 1 次。
- 价值/断言：null 与空串语义不同，空串是“改写为空参数”。断言工具收到的 Map 分别为原值、原值、空 Map。

#### AL-16 多钩子 beforeTool 改参数后拒绝（P1）
- 构造/动作：同一轮两个调用；钩子 A 对两个调用都 `allow(改写参数)`，钩子 B 拒绝第二个调用；钩子 C 记录调用。
- 期望：第一个调用用 A 改写后的参数执行；第二个调用不执行，tool_result 为拒绝原因；C 的 beforeTool 对第二个调用未执行；afterTool 对第二个调用未执行；ToolExecutor 只收到 1 次 singleton 调用。
- 价值/断言：拒绝短路发生在参数改写之后，改写不会泄漏到执行；后续钩子整体跳过。断言 executor 调用次数与 tool_result 顺序。

#### AL-17 afterTool 返回 null 与空串（P1）
- 构造/动作：两个 afterTool 钩子 A/B。场景一 A 返回 null、B 返回 "B"；场景二 A 返回 ""、B 返回 null。
- 期望：null 表示沿用当前文本；B 收到 A 改写后的文本；空串会把 tool_result 清空；最终写入的文本与钩子链结果一致。
- 价值/断言：afterTool 是链式 reducer，null 有特殊含义，空串是真实改写。断言 B 的入参和最终 tool_result。

#### AL-18 async 工具轮 STOP 与 CONTINUE 并存（P1）
- 构造/动作：首轮 TOOL_USE；钩子 A 返回 stop，钩子 B 返回 continueRound。
- 期望：工具执行 1 次；两个 afterTurn 都执行；结果 STOP、rounds=1、请求 1 次、afterRun 1 次。
- 价值/断言：异步路径与同步语义一致，停止优先于继续。断言工具已执行且没有第 2 轮。

#### AL-19 最后一轮 TOOL_USE 时 afterModel 取消（P1）
- 构造/动作：maxRounds=1，首轮 TOOL_USE；`afterModel` 中 cancel。
- 期望：工具不执行（lastRound 判断先于 runTools）；afterTurn 仍执行；结果 ABORTED 而非 MAX_ROUNDS；rounds=1；toolCalls 保留。
- 价值/断言：终态优先级为 取消 > MAX_ROUNDS > STOP；验证 afterTurn 在取消检查之前。断言 `stopReason==ABORTED` 且 executions=0。

### 三、工具执行

#### AL-20 ToolExecutor 抛异常（P0）
- 构造/动作：自定义 `ToolExecutor.execute` 抛 `IllegalStateException`；首轮 TOOL_USE，次轮 STOP。
- 期望：异常被 `execute` 捕获，tool_result 为“工具执行失败”；afterTool 收到该文本并可改写；循环继续到第二轮；future 正常完成；afterTurn/afterRun 正常。
- 价值/断言：执行器异常被降级为工具文本，与钩子异常（AL-12）形成契约边界。断言 future 不异常且末条 tool_result 为失败文案。

#### AL-21 executor.io() 拒绝或返回 null（P0）
- 构造/动作：A：`AgentLoop` 注入 core=max=1、queue=1、AbortPolicy 的 ExecutorProvider，先占满线程与队列，再跑首轮 TOOL_USE。B：`io()` 返回 null。
- 期望：A 中 `CompletableFuture.runAsync` 同步抛 `RejectedExecutionException`，被 288-300 的 catch 捕获，future 异常完成；工具未执行；afterTurn/afterRun 0；1s 内结束。B 中 NPE 同样被捕获为 failed future。
- 价值/断言：`runAsync` 的任务提交失败是同步异常，漏捕会让 future 永久挂起。断言 cause 类型与未执行计数。

#### AL-22 多工具顺序与中间拒绝（P0）
- 构造/动作：首轮三个调用 c1/c2/c3；自定义 ToolExecutor 记录每次收到的调用；钩子拒绝 c2。
- 期望：executor 被调用 2 次，每次 singleton，顺序 c1、c3；tool_result 顺序仍为 c1、c2、c3；c2 文本为拒绝原因；afterTool 只对 c1/c3 执行；assistant 消息一次带 3 个 calls；第 2 轮请求消息序列为 assistant + 3 条 tool_result。
- 价值/断言：AgentLoop 逐条调用丢失批量并行能力，且 tool_result 必须与 assistant 的 calls 顺序严格对齐。断言调用序列和消息顺序。

#### AL-23 ToolExecutor 返回值归一化（P0）
- 构造/动作：四组运行分别返回 `null`、空列表、`Arrays.asList((String)null)`、`Arrays.asList("a","b")`（每轮只有一个调用）。
- 期望：前三种 tool_result 均为 ""；超长列表只取第 0 个 "a"，多余结果丢弃；afterTool 收到对应文本；循环不抛异常。
- 价值/断言：执行器是 SPI 边界，需防御 null、空表和长度不匹配，避免 NPE/串位。逐组断言最终 tool_result 文本。

#### AL-24 hook 清空/替换工具列表（P2）
- 构造/动作：首轮 `beforeModel` 中 `ctx.setTools(emptyList())`；transport 仍返回 echo 调用。
- 期望：执行时 `ctx.getTools()` 为空，工具结果为“未找到工具: echo”；第二轮请求中工具声明为空（MODE_ROUND 空列表不声明）；不抛“工具列表不能为空”。
- 价值/断言：工具列表是活引用，修改影响“执行时”和“后续发送”，不影响已发出的请求。断言第 2 轮 request 的工具列表为空。

#### AL-25 未知工具与非法 JSON 的异步回灌（P2）
- 构造/动作：首轮两个调用：不存在工具 ghost、echo 参数 "not-json"；afterTool 统一加前缀 "[t]"；次轮 STOP。
- 期望：tool_result 分别为 "[t]未找到工具: ghost"、"[t]工具参数不是合法 JSON"；工具 execute 0 次；循环继续。
- 价值/断言：错误路径同样经过 afterTool，异步行为与同步一致。断言两条 tool_result 的前缀和工具执行计数。

### 四、轮次与边界

#### AL-26 maxRounds=1 且最后一轮 TOOL_USE（P1）
- 构造/动作：首轮 TOOL_USE，maxRounds=1，钩子记录事件。
- 期望：工具 0 次、afterTool 0 次；afterTurn 1 次、afterRun 1 次；结果 MAX_ROUNDS、rounds=1、content/usage 来自该轮、toolCalls 保留；请求 1 次。
- 价值/断言：最后一轮不执行工具，但 afterTurn 仍须告知钩子。断言 `MAX_ROUNDS` 与 afterTurn 计数。

#### AL-27 maxRounds 边界（P1）
- 构造/动作：null、0、-1 三种取值；null 场景 transport 始终返回 TOOL_USE。
- 期望：null 使用默认 8，共 8 次请求、rounds=8、MAX_ROUNDS；0 与 -1 在调用线程同步抛 `LlmCallException`（不是 failed future），beforeRun/afterRun/transport 均为 0。
- 价值/断言：入口校验在 future 创建之前，调用方不能只挂 whenComplete。断言同步抛出与 `requests.isEmpty()`。

#### AL-28 空 tool_calls 但 stop_reason=TOOL_USE（P1）
- 构造/动作：首轮 stop=TOOL_USE、toolCalls=[]；maxRounds=4。
- 期望：不执行工具、无第 2 轮；afterTurn 1 次；结果 stopReason=TOOL_USE、rounds=1、toolCalls 为空；afterRun 1 次。附加：afterTurn 返回 CONTINUE 时会发第 2 轮。
- 价值/断言：`toolUse` 要求 stopReason 与 calls 同时满足，避免空调用死循环。断言请求数与 stopReason。

#### AL-29 正常多轮聚合（P2）
- 构造/动作：三轮 usage (1,1,2)/(2,2,4)/(3,3,6)，latency 10/20/30，前两轮 TOOL_USE、末轮 STOP。
- 期望：结果 usage=(6,6,12)、latency=60、rounds=3、content 为末轮内容、stopReason=STOP；afterRun 1 次。
- 价值/断言：多轮聚合只在 accumulate 时发生，与 AL-06 的 ABORTED 聚合对照。逐字段断言。

### 五、线程、上下文与挂起

#### AL-30 异常路径挂起与超时断言（P0）
- 构造/动作：A：`LlmTransport.completeAsync` 返回 `CompletableFuture.completedFuture(null)`（null LlmCompletion）。B：返回永不完成的 future，不绑定 abort，且不调用 cancel。
- 期望：A 必须在超时内失败或超时暴露缺陷：当前实现中 `null` 会在网关/AgentLoop 的回调里触发 NPE，而 `whenComplete` 内未捕获的异常只让派生 stage 失败，主 future 永不完成；测试用 `get(500ms)` 断言，当前预期为 `TimeoutException`（标记已知缺陷）。B 用 `future.cancel(true)` 后应立刻 cancelled，不挂起。
- 价值/断言：这是最容易被 CI 忽略的挂起路径。断言必须包含超时，且分别覆盖“完成 null”和“传输不响应 + 取消”两种。

#### AL-31 线程亲和性（P1）
- 构造/动作：异步 transport（调度线程）返回首轮 TOOL_USE；io executor 线程名前缀 agent-io-；`beforeRun` 阻塞 200ms；记录每个回调的线程名。
- 期望：`runAsync` 在 beforeRun 返回后才返回（首轮 beforeModel 也在调用线程执行）；afterModel/afterTurn 在传输完成线程；beforeTool/afterTool 在 agent-io-* 线程；第 2 轮 beforeModel 在 io 线程。
- 价值/断言：beforeRun/首轮 beforeModel 是同步入口，可能阻塞调用线程；线程亲和影响 ThreadLocal 与事务边界。断言线程名与入口耗时。
#### AL-32 ctx 跨线程交接与修改范围（P1）
- 构造/动作：工具在 io 线程执行；第 2 轮 `beforeModel` 向 `ctx.getMessages()` 追加一条 user 消息；同时断言第 2 轮请求能看到上一轮的 tool_result。
- 期望：tool_result 在下一轮请求中可见（由 `toolsFuture` 完成建立的 happens-before 保证）；beforeModel 追加的消息出现在同一轮请求中；工具列表修改只影响执行和后续轮，不影响已发出的请求。
- 价值/断言：`ctx` 是活 ArrayList 且跨线程传递，必须明确哪些交接有内存可见性保证。断言第 2 轮请求的消息内容与顺序；并发外部读 messages 的 CME 风险单独记录，不做不稳定断言。
#### AL-33 深递归与 StackOverflowError 逃逸（P1）
- 构造/动作：transport 的 `completeAsync` 直接返回已完成 future；afterTurn 始终返回 CONTINUE；maxRounds=20000。
- 期望：当前实现会在 `whenComplete` 内同步递归调用 `next()`，最终抛 `StackOverflowError`；该 Error 不被 `catch (RuntimeException)` 捕获，可能从 `runAsync` 调用线程逃逸或使 future 停留在未完成状态。测试用 `catch (Throwable)` 记录，并给 future 加 2s 超时；建议后续改为把下一轮调度到 io 线程以消除递归。
- 价值/断言：同步完成的传输 + 持续 CONTINUE 是真实可构造的栈溢出场景；断言“要么 2s 内终结，要么抛出 Error”，防止 CI 挂死。
