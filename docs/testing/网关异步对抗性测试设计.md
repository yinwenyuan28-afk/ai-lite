# LlmGateway 异步调用与传输层对抗性测试设计

只针对 `LlmGateway` 的 `*Async` 路径、传输层 future 契约、流式回调、异步工具循环、执行器/日志与 future 语义；不重复 `LlmGatewayAsyncTest` 的 happy path。单次调用用例对应**「单次模型调用入口」**（`one` / `oneAsync` / `LlmCoroutines.one` / `oneAsync`）。共 **35** 个用例：**P0 6 个**（会挂起、丢取消或重复推进），**P1 18 个**（错误状态/记录/语义偏差），**P2 11 个**（契约与防御性细节）。
建议新增 `LlmGatewayAsyncAdversarialTest`（core）与 `TransportAsyncAdversarialTest`（okhttp/jdk8 各自模块）；所有等待统一用 `get(timeout)` 或 `CountDownLatch`，把挂起转成失败，禁止无超时 `join()`。路径缩写：`Gateway`=`ai-lite-core/src/main/java/io/github/yinwenyuan28/ailite/llm/LlmGateway.java`，`Handle`=`.../llm/LlmCancelHandle.java`，其余类名同理。

## 总表

| ID | 优先级 | 场景 | 代码路径 | 现有测试 |
|---|---|---|---|---|
| GW-01 | P0 | 传输层返回 null future | Gateway:348-353,290 | 无 |
| GW-02 | P0 | ToolExecutor.executeAsync 返回 null future | Gateway:387-394 | 无 |
| GW-03 | P0 | 工具 future 以 null 列表完成 | Gateway:402-404 | 无 |
| GW-04 | P0 | 传输层 future 以 null 完成 | Gateway:362-364 | 无 |
| GW-05 | P1 | HTTP 完成与 cancel 竞态 | Gateway:337,353-368,285-289 | 部分（仅取消单测） |
| GW-06 | P2 | 重复 cancel 的 abort 次数 | Handle:19-25 | 无 |
| GW-07 | P1 | cancel 先于 bindAbort、轮间 unbind | Handle:39-44；OkHttpExecutor:258-277 | 无 |
| GW-08 | P1 | 传输层无视 cancel 仍返回 STOP | Gateway:337,353-370 | 无 |
| GW-09 | P1 | onDelta 内 cancel 后返回 STOP | Gateway:333-377 | 部分（同步版） |
| GW-10 | P1 | 异步工具执行中 cancel | Gateway:394-411 | 部分（同步版） |
| GW-11 | P1 | 取消后发生网络错误走 ABORTED | Gateway:416-421 | 无 |
| GW-12 | P2 | future.cancel 返回值与异常语义 | Gateway:285-289 | 部分 |
| GW-13 | P1 | completeAsync 同步抛 LlmCallException | Gateway:347-351 | 无 |
| GW-14 | P1 | completeAsync 同步抛 RuntimeException | Gateway:349-351,422-427 | 无 |
| GW-15 | P1 | future 被 complete 两次 | Gateway:353-363 | 无 |
| GW-16 | P1 | CompletionException 多层包装 | Gateway:431-437 | 无 |
| GW-17 | P2 | 未取消但 stop=ABORTED | Gateway:366-369 | 无 |
| GW-18 | P2 | stop=TOOL_USE 且 toolCalls 为 null/空 | Gateway:371-372 | 部分（空列表） |
| GW-19 | P0 | toolCalls 列表含 null 元素 | Gateway:402-405 | 无 |
| GW-20 | P2 | usage/stop 为 null 的归一化 | LlmCompletion:28-37 | 无 |
| GW-21 | P0 | onDelta 抛异常导致 future 挂起 | LlmTransport:31-33；OkHttpExecutor:175-181 | 部分（同步版） |
| GW-22 | P1 | 流中途取消：delta 停发、部分结果 | OkHttpExecutor:168-173,202-213 | 部分（传输层单测） |
| GW-23 | P1 | SSE 半帧、坏 JSON、空 data | TransportSupport:114-149；OpenAi…:370-379 | 部分 |
| GW-24 | P2 | 流式与非流式结果一致性 | OpenAi…:77-120,320-416 | 无 |
| GW-25 | P1 | executeAsync 同步抛异常 | Gateway:387-393 | 无 |
| GW-26 | P1 | 工具结果长度不匹配/含 null | Gateway:402-406；LlmMessage:23-26 | 无 |
| GW-27 | P1 | 异步 maxRounds=1 且首轮 tool_use | Gateway:379-382 | 部分（同步版） |
| GW-28 | P2 | 多工具结果按索引回填 | Gateway:402-406 | 无 |
| GW-29 | P2 | 工具 future 异常多层包装 | Gateway:394-401,431 | 无 |
| GW-30 | P1 | io() 拒绝任务（AbortPolicy） | AsyncLlmCallLogger:42-60 | 部分（logger 级） |
| GW-31 | P1 | 日志落库阻塞是否阻塞主调用 | Gateway:374-376,591-609 | 无 |
| GW-32 | P2 | 日志写入抛异常/Error | AsyncLlmCallLogger:46-54 | 部分（logger 级） |
| GW-33 | P2 | 取消/异常路径的记录正确性 | Gateway:337-339,407-410,416-422 | 无 |
| GW-34 | P1 | 回调被触发两次导致重复推进 | Gateway:353-363 | 无 |
| GW-35 | P2 | 并发调用+随机取消压测 | Gateway:285-289,333-414 | 无 |

## 用例明细

### GW-01 传输层返回 null future（P0）
- 构造/动作：`completeAsync` 第 1 轮返回 `null`，或第 2 轮才返回 `null`（工具循环触发）。
- 期望：`oneAsync` 返回的 future 在 1s 内以 `LlmCallException` 完成并落一条 `stop=error`；现状是首轮抛裸 NPE、后续轮在回调里抛 NPE 后永久挂起。
- 重要性/断言：`future.whenComplete` 未做 null 检查；挂起无法由业务超时兜底。断言 `future.get(1,S)` 不超时且 `store.rows` 恰 1 条。

### GW-02 ToolExecutor.executeAsync 返回 null future（P0）
- 构造/动作：首轮返回 `tool_use`，自定义 `ToolExecutor` 的 `executeAsync` 返回 `null`。
- 期望：future 以“工具执行失败”异常完成并记录 `stop=error`；现状 `toolFuture.whenComplete` NPE 逃逸回调，外层 future 永不完成。
- 重要性/断言：工具 SPI 未声明非空约束，第三方实现容易踩坑。断言 future 1s 内完成、记录 round=1、无第二次请求。

### GW-03 工具 future 以 null 列表完成（P0）
- 构造/动作：`executeAsync` 返回 `CompletableFuture.completedFuture(null)`，模型给出 1 个工具调用。
- 期望：按“无结果”处理（全部补 `""`）或显式失败；现状 `results.size()` NPE 发生在回调内，future 挂起且只有 `toolUse` 记录。
- 重要性/断言：与 GW-02 同属回调内未捕获异常，验证是否会留下“半个 transcript”。断言 future 完成、transcript 中工具结果文本为空。

### GW-04 传输层 future 以 null 完成（P0）
- 构造/动作：`future.complete(null)`。
- 期望：future 以 `LlmCallException("模型响应为空")` 完成并记录 error；现状 `completion.getUsage()`（或 `getStop()`）NPE，future 挂起且无记录。
- 重要性/断言：适配器解析失败时可能“完成 null”而非异常；断言 `ExecutionException` 根因类型与 1 条 error 记录。

### GW-05 HTTP 完成与 cancel 竞态（P1）
- 构造/动作：传输层绑定 abort 并在 1ms 后完成；另一线程随机延迟 0-1ms 调 `cancel()`；用 `CountDownLatch` 让两者同时起跑，循环 500 次。
- 期望：每次调用都终止且只有一种终态；`stop` 只能是 `aborted` 或 `stop`；每次恰一条记录；不得出现“取消后仍继续下一轮”。
- 重要性/断言：`next()` 只在发请求前检查一次取消。断言循环内所有 `get(2,S)` 成功、记录数=调用数、`aborted` 记录 `success=0`，并统计 `stop` 占比以确认窗口存在。

### GW-06 重复 cancel 的 abort 次数（P2）
- 构造/动作：`bindAbort` 计数 Runnable，连续调用 `cancel()` 3 次；再测取消后 `bindAbort(null)`。
- 期望：abort 仅执行 1 次（2026-10-21 已修复：`cancel()` 与 `bindAbort()` 任意交错下 abort 最多一次）。
- 重要性/断言：重复 `OkHttp Call.cancel()` 虽安全，但自定义传输可能非幂等（如关闭流、减引用计数）。断言 `abortCount==1`。

### GW-07 cancel 先于 bindAbort、轮间 unbind（P1）
- 构造/动作：先 `cancel()` 再 `bindAbort(A)`；随后 `bindAbort(B)`；另测一轮完成后 `bindAbort(null)` 再 `cancel()`。
- 期望：A 立即执行一次；句柄随后进入终态，B 不再执行；unbind 后 cancel 不得触发旧连接 abort。多轮场景由网关在每轮发请求前检查取消，取消后不再发下一轮。
- 重要性/断言：旧 abort 若未解绑会误杀后续/已关闭连接。断言 `A==1`、`B==0`，unbind 后计数不变。

### GW-08 传输层无视 cancel 仍返回 STOP（P1）
- 构造/动作：传输层不读 `isCancelled`，cancel 后 100ms 返回 `stop`。
- 期望：当前实现返回 STOP 且 future 正常完成；应明确契约：取消只在传输层配合时生效，或网关在完成回调里补查 `cancel` 并改写为 ABORTED。
- 重要性/断言：用户点了停止却得到正常结果。断言 `result.getStopReason()==STOP`（记录现状），并作为“是否要网关兜底”的决策用例。

### GW-09 onDelta 内 cancel 后返回 STOP（P1）
- 构造/动作：流式回调第一次 delta 时 `cancel()`，传输层仍返回 `stop`；同时测工具循环版。
- 期望：当前返回 STOP（非 ABORTED），但不得再发下一轮请求；工具循环应停在首轮。
- 重要性/断言：最后一个 token 到达时用户取消，语义应稳定。断言 rounds=1、requests=1、stop=STOP（现状），工具未执行。

### GW-10 异步工具执行中 cancel（P1）
- 构造/动作：工具 future 延迟 200ms 完成；完成后前调 `cancel()`；结果返回 2 条文本。
- 期望：`stop=aborted`、rounds=1、无第二次模型请求；工具结果仍被写入 transcript；应有一条终态 aborted 记录。
- 重要性/断言：现状在 407-410 直接 `complete(ABORTED)` 且不写记录，审计里只剩 `toolUse`。断言 requests=1、rows 含 aborted 终态（当前会失败）。

### GW-11 取消后发生网络错误走 ABORTED（P1）
- 构造/动作：传输层在 cancel 之后以 `LlmCallException("boom")` 异常完成。
- 期望：走 `fail()` 的取消分支，记录 `stop=aborted`、`success=0`、`errorBrief=null`；异常仍以 ABORTED 结果（非异常完成）交付。
- 重要性/断言：避免把用户取消误报为系统错误；断言记录不含 “boom”，future 正常返回 ABORTED。

### GW-12 future.cancel 返回值与异常语义（P2）
- 构造/动作：未完成时 `cancel(true)`、再次 cancel、已完成后再 cancel；分别 `get()`。
- 期望：返回值依次 `true/false/false`；`get()` 抛 `CancellationException` 而非 `ExecutionException`；`handle.isCancelled()` 为真；abort 仅一次。
- 重要性/断言：Kotlin `await()` 依赖该语义映射协程取消。另断言取消后底层 `result.complete(...)` 返回 false，不覆盖取消态。

### GW-13 completeAsync 同步抛 LlmCallException（P1）
- 构造/动作：`completeAsync` 直接 `throw new LlmCallException("bad")`。
- 期望：future 以同一异常实例异常完成（不二次包装），记录 `stop=error`、round=1、`errorBrief="bad"`，无挂起。
- 重要性/断言：验证 347-351 的同步 catch 覆盖；断言 `future.get` 的 cause 与原始异常同一引用。

### GW-14 completeAsync 同步抛 RuntimeException（P1）
- 构造/动作：抛 `IllegalStateException("x")`。
- 期望：future 以 `LlmCallException("模型调用失败")` 完成，`cause` 保留原始异常；记录 error。
- 重要性/断言：避免业务层看到裸运行时异常；断言 message 与 cause 类型。

### GW-15 future 被 complete 两次（P1）
- 构造/动作：`future.complete(c1); future.complete(c2);` 以及正常完成后 `completeExceptionally`。
- 期望：只推进 1 轮、只执行 1 次工具、只写 1 条记录；内容取 c1。
- 重要性/断言：适配器可能共享 future 或重复回调；断言 requests=1、工具计数=1、rows=1。

### GW-16 CompletionException 多层包装（P1）
- 构造/动作：`completeExceptionally(new CompletionException(new CompletionException(new LlmCallException("x"))))`；另测根因为 `IllegalStateException` 及 cause 为 null 的 CompletionException。
- 期望：unwrap 到根因；根因是 `LlmCallException` 时原样抛出，否则包装；cause 为 null 时不 NPE；记录 errorBrief 为根因消息。
- 重要性/断言：日志/监控依赖根因。断言异常链深度为 1、记录文本正确。

### GW-17 未取消但 stop=ABORTED（P2）
- 构造/动作：传输层直接返回 `LlmCompletion.aborted("部分", null)`，handle 未取消。
- 期望：返回 ABORTED、`rounds=1`、`success=0`、工具不执行、无下一轮、记录 `stop=aborted`。
- 重要性/断言：适配器在服务端中断/客户端断流时可能这样返回；断言不误判为 STOP、不触发重试。

### GW-18 stop=TOOL_USE 且 toolCalls 为 null/空（P2）
- 构造/动作：`new LlmCompletion("", null, TOOL_USE, null, null)` 与空列表各一次。
- 期望：构造器归一化为空列表；`wantsTool=false`，直接以 TOOL_USE 终态返回，不执行工具、不发第二轮。
- 重要性/断言：防止空工具调用导致死循环；断言 requests=1、executions=0、result.toolCalls 为空。

### GW-19 toolCalls 列表含 null 元素（P0）
- 构造/动作：模型返回 `TOOL_USE`，列表为 `[call1, null]`。
- 期望：跳过 null 或转成错误文本后继续；现状在 402-405 调 `toolCall.getId()` NPE，回调中断，future 挂起。
- 重要性/断言：解析器对残缺 tool_calls 可能产出 null 项；断言 future 1s 内完成、tool_result 只对非 null 项回填。

### GW-20 usage/stop 为 null 的归一化（P2）
- 构造/动作：`new LlmCompletion(null, null, null, null, null)`。
- 期望：content 为 `""`、stop=STOP、usage=zero；记录 `success=1`、tokens=0，不 NPE。
- 重要性/断言：锁定构造器的兜底契约，避免适配器漏字段时炸在网关累加处。

### GW-21 onDelta 抛异常导致 future 挂起（P0）
- 构造/动作：传输层在 I/O 回调中先 `onDelta.accept` 再 `future.complete`，consumer 抛 `IllegalStateException`；另用会 catch 并异常完成的“合规”传输层对照。
- 期望：合规传输层把 consumer 异常转成异常完成，网关记录 error；若传输层不 catch，网关不得永久挂起（现状会挂起）。
- 重要性/断言：异步模式回调在 I/O 线程执行，异常会杀死完成路径。断言两种传输层下 future 都在 1s 内终止，且 error 记录存在。

### GW-22 流中途取消：delta 停发、部分结果（P1）
- 构造/动作：传输层每帧检查 `isCancelled()`，发出 d1 后取消，返回 `aborted("a",null)`。
- 期望：`onDelta` 只收到“取消前”的前缀；取消后不再回调；结果 `stop=aborted`、content 为部分文本；future 完成后 delta 列表不再增长。
- 重要性/断言：防止 UI 在取消后继续追加文本。用线程安全列表，在 `get()` 返回后快照并断言等于前缀。

### GW-23 SSE 半帧、坏 JSON、空 data（P1）
- 构造/动作：`data: {...content a...}`、`data: {broken`、`data: {...content b, finish_reason=stop...}`，EOF 前无空行；另测 `data:` 空负载与非 `data:` 行。
- 期望：坏 JSON 事件被跳过，尾帧被 `SseCollector.flush()` 提交，最终 content="ab"、stop=STOP，不抛异常。
- 重要性/断言：真实代理常在流末尾截断。断言无异常且两段有效内容都保留。

### GW-24 流式与非流式结果一致性（P2）
- 构造/动作：同一语义响应分别构造 blocking JSON 与 SSE 帧（含 reasoning、tool_calls 分片、usage 尾帧）。
- 期望：content、thinking、stop、toolCalls 的 id/name/arguments 完全一致；usage 仅在流式带 `include_usage` 尾帧时一致，否则为 zero（需在文档中注明差异）。
- 重要性/断言：两套解析器分叉是最常见的静默 bug。用参数化断言逐字段比对。

### GW-25 executeAsync 同步抛异常（P1）
- 构造/动作：`ToolExecutor.executeAsync` 直接抛 `IllegalStateException("boom")`。
- 期望：future 以 `LlmCallException("工具执行失败")` 完成，cause=boom；记录 error、无第二轮请求。
- 重要性/断言：验证 387-393 的 catch；断言异常 message、cause 与 requests=1。

### GW-26 工具结果长度不匹配/含 null（P1）
- 构造/动作：3 个 tool_call，executor 分别返回 2 条、4 条、`["r", null, "x"]`。
- 期望：短缺补 `""`、多余忽略、null 元素归一化为 `""`；transcript 中 tool_result 数量恒等于 calls 数量且 id 对齐。
- 重要性/断言：204-205 只做 `i < size` 判断；断言三条消息文本依次为 `r1,"",""`、`r1,r2,""`、`r,"",x`。

### GW-27 异步 maxRounds=1 且首轮 tool_use（P1）
- 构造/动作：`toolLoopAsync(..., maxRounds=1)`，首轮返回 `tool_use`。
- 期望：不执行工具，future 返回 `MAX_ROUNDS`、rounds=1、requests=1；`result.toolCalls` 保留模型给出的调用；记录 `stop=maxRounds`、`success=0`。
- 重要性/断言：379 在工具执行前判限；异步路径无对应测试，且“最后一轮是否保留 toolCalls”需锁定。

### GW-28 多工具结果按索引回填（P2）
- 构造/动作：3 个调用 c1/c2/c3，自定义 executor 故意返回 `[r3, r2, r1]`（乱序/并行实现）。
- 期望：网关按索引而非内容配对，transcript 为 c1→r3、c2→r2、c3→r1；不得按 id 排序或丢弃。
- 重要性/断言：ToolExecutor 注释要求顺序一致；断言消息序列与返回值序列一一对应。

### GW-29 工具 future 异常多层包装（P2）
- 构造/动作：工具 future 以 `CompletionException(CompletionException(new IllegalStateException("boom")))` 完成。
- 期望：unwrap 到 `IllegalStateException`，再包成 `LlmCallException("工具执行失败", boom)`；future 异常完成，记录 error，无下一轮。
- 重要性/断言：与 GW-16 对称，验证工具路径也走 unwrap；断言 cause 链只有两层。

### GW-30 io() 拒绝任务（AbortPolicy）（P1）
- 构造/动作：`ExecutorProvider.io()` 返回抛 `RejectedExecutionException` 的 Executor；另测 `io()` 返回 null。
- 期望：日志被丢弃（warn），模型调用正常完成，future 不异常，rows=0。
- 重要性/断言：生产使用有界队列+AbortPolicy 时日志洪峰可能拒绝；断言 result 正确且无异常逃逸。

### GW-31 日志落库阻塞是否阻塞主调用（P1）
- 构造/动作：`store.insert` 阻塞在 latch；分别注入 `DirectExecutorProvider` 与单线程 `ExecutorProvider`。
- 期望：Direct 下 `record` 在完成回调线程内联执行，`result.complete` 被推迟（现状）；异步 provider 下 future 应立即完成，不受落库阻塞影响。
- 重要性/断言：默认/测试常用 Direct，慢库会直接拖慢响应。断言 Direct 下 future 未完成、释放后完成；异步下先完成。

### GW-32 日志写入抛异常/Error（P2）
- 构造/动作：`insert` 抛 `RuntimeException`、`OutOfMemoryError`；日志框架 appender 抛异常。
- 期望：全部被 52 行 catch(Throwable) 吞掉，主 future 不受影响；行丢失只体现在 warn。
- 重要性/断言：日志绝不应反噬业务；断言 future 正常、无异常传播。

### GW-33 取消/异常路径的记录正确性（P2）
- 构造/动作：分别构造“开始前取消”“两轮之间取消”“传输异常”“工具异常”。
- 期望：开始前取消写 1 条 round=0、aborted、success=0、tokens=0、latency=0；异常写 1 条 error；工具执行后取消应补 1 条 aborted 终态（现状缺失，只有 `toolUse`）。
- 重要性/断言：审计需要终态且只能一条。断言每种路径的 rows 数量、stopReason、success 与 roundNo。

### GW-34 回调被触发两次导致重复推进（P1）
- 构造/动作：传输层返回 `CompletableFuture` 子类，重写 `whenComplete` 连续调用 action 两次；首轮 `tool_use`，工具 future 延迟完成。
- 期望：网关应有轮次/推进守卫，工具只执行一次、第二轮请求只发一次；现状会重复执行工具并可能并发推进两轮。
- 重要性/断言：`result.isDone()` 在工具完成前为 false，无法防重。断言 executions=1（当前为 2）与 requests=2（首轮+一次二轮）。

### GW-35 并发调用+随机取消压测（P2）
- 构造/动作：500 次 `oneAsync`/`toolLoopAsync`，传输延迟 0-3ms，30% 在随机时刻 cancel；收集所有 future 与未捕获异常。
- 期望：全部 future 在 5s 内终止；无 NPE/CME；每条调用记录数在 1-2 条之间；无重复终态。
- 重要性/断言：暴露可见性、回调重入和记录竞争问题；用 `allOf(...).get(5,S)` 断言不挂起，并统计 `aborted+stop+error == 500`。
