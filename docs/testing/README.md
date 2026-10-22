# 编排层异步对抗性测试设计（索引）

三份设计文档覆盖 `AgentLoop.runAsync`、`AgentSessionLoop.resumeAsync`、`LlmGateway.*Async` 的竞态、异常出口和 future 契约。它们只设计用例，不修改生产代码；实现时按优先级补测试，先让“会挂死/会丢取消”的路径变成可观察失败。

## 总览

| 文档 | 范围 | 用例数 | P0 | P1 | P2 |
| --- | --- | ---: | ---: | ---: | ---: |
| [async-adversarial-agent-loop.md](async-adversarial-agent-loop.md) | 生命周期钩子、工具执行、轮次推进、取消 | 33 | 13 | 17 | 3 |
| [async-adversarial-session-loop.md](async-adversarial-session-loop.md) | prepare/finish、落盘、版本冲突、Recorder | 35 | 10 | 23 | 2 |
| [async-adversarial-gateway.md](async-adversarial-gateway.md) | 传输层 future、流式、工具循环、日志与执行器 | 35 | 6 | 18 | 11 |
| **合计** | | **103** | **29** | **58** | **16** |

## 统一约定

- 所有异步断言必须带超时：`get(5, TimeUnit.SECONDS)`、`CompletableFuture.orTimeout` 或 `CountDownLatch.await(timeout)`。
- 禁止用 `Thread.sleep` 等结果；用 latch、`CompletableFuture` 或可控的 fake transport 驱动时序。
- 每个用例至少断言一个可观察结果：future 是否完成、完成值/异常、钩子调用次数、请求次数、落盘版本、线程名。
- 竞态用例优先用“单线程执行器 + 手动完成 future”的方式复现，避免依赖真实网络时序。
- 挂起类用例必须把“当前实现会挂起”写成超时失败，而不是让 CI 一直等。

## 最优先实现的 P0 主题

1. **回调内未捕获异常导致 future 永久不完成。** 传输层返回 `null` future、以 `null` 完成、`toolCalls` 含 `null`、`onDelta` 抛异常，都会在 `whenComplete` 内触发 NPE/异常；派生 stage 失败但主 future 无人完成。对应 AL-30、GW-01、GW-04、GW-19、GW-21。
2. **工具执行器契约。** `ToolExecutor.executeAsync` 返回 `null` future、返回 `null` 列表、长度不匹配、含 `null` 元素，都会让工具循环挂起或写出残缺消息。对应 GW-02、GW-03、GW-26、AL-23。
3. **取消终态与幂等。** 重复 `cancel`、`future.cancel` 与外部句柄混用、取消与 `result.complete` 竞态，必须只产生一个终态，且不再发下一轮。对应 AL-03、AL-04、AL-05、GW-05、GW-06。
4. **钩子异常不能吞掉结果。** `afterRun` 抛异常会把已经成功的 `LlmResult` 覆盖成异常 future；`beforeRun` / `afterTurn` / `beforeTool` 的异常路径需要明确是否执行后续钩子。对应 AL-08、AL-10、AL-11、AL-12。
5. **落盘竞态。** `finish` 落盘期间取消可能出现“调用方已取消但数据已提交”；`save` 成功但读回失败是部分成功。对应 SL-03、SL-17、SL-18。
6. **同步完成导致的深递归。** transport 同步完成 + `afterTurn` 持续 CONTINUE 时，`next()` 在回调内递归，可能抛 `StackOverflowError`；该 `Error` 不在 `catch (RuntimeException)` 范围内。对应 AL-33。

## 已确认的实现与注释不一致

`LlmCancelHandle` 的 Javadoc 曾声称“取消是幂等的”，但旧实现每次 `cancel()` 都会重复执行 `abort`。**已在「夯实单次模型调用入口」中修复**：`cancel()` 与 `bindAbort()` 的任意交错下，abort 最多执行一次，并有 `LlmSingleCallCancellationTest` 锁定。

## 已实现（单次调用路径）

| 测试类 | 模块 | 用例数 | 覆盖 |
| --- | --- | ---: | --- |
| `LlmSingleCallEntryParityTest` | core | 14 | 结果/请求/校验/异常/日志/线程六个维度的 sync-async parity |
| `LlmSingleCallCancellationTest` | core | 16 | 发请求前取消、在途取消、future/Job 取消、重复取消、竞态、非合作 transport 兜底、互不影响、取消记录 |
| `LlmSingleCallBoundaryTest` | core | 7 | null future、null completion、适配器同步抛异常、异步 onDelta、null usage/stop、取消竞态 |
| `Jdk8HttpExecutorTest`（新增取消用例） | http-jdk8 | 2 | 预取消时同步/异步都不发请求 |
| `LlmCoroutinesParityTest` | executor-coroutines | 6 | suspend 桥接 parity、Job/withTimeout 取消、结构化并发、请求不改写 |
| `LlmGatewayImageTest` | core | 8 | sync/async 图片消息一致、图片-only、多图顺序、OTHER 折叠、非图片模型拒绝、非法参数 |
| `LlmTextsTest`（新增图片校验） | core | 7 | null 元素、空 MIME、非 image/*、空 data、非 base64 data URL、MIME 大小写 |
| `LlmImageTest` | core | 6 | data URL 前缀剥离、裸 base64 去空白、非 base64 data URL 保留待校验 |
| 三个 transport test（图片分片） | http-okhttp | 6 | OpenAI `image_url`、Responses `input_image`、Anthropic base64 source、流式与合并 |
| `RetryPolicyTest` / `LlmGatewayRetryTest` | core | 26 | 显式重试、瞬时错误分类、退避、总 deadline、取消感知、每次尝试独立记录 |
| `LlmCoroutinesRetryTest` | executor-coroutines | 10 | suspend 重试、`delay()` 退避、`withTimeout`/`job.cancel`、deadline、记录 |
| `Utf8LineBufferTest` | core | 8 | CRLF 跨 chunk、多字节字符逐字节切分、空行、末尾无换行、单行上限 |
| `JettyHttpExecutorTest` | http-jetty | 11 | 同步/异步/原始行、非 2xx、预取消、流中取消、提前 EOF、close |
| `WebClientHttpExecutorTest` | http-webclient | 11 | 同 Jetty，另覆盖 DataBuffer 原始行与 Reactor 取消 |
| `KtorCioHttpExecutorTest` | http-ktor-cio | 11 | 同 Jetty，另覆盖 `readUTF8LineTo`、Job 取消、Socket 提前断开 |
| `VirtualThreadOkHttpFactoryTest` | http-okhttp-jdk21 | 3 | 异步/流式回调运行在虚拟线程、取消（JDK 21+） |
| `OkHttpClientFactoryTest` | http-okhttp | 3 | 默认 64/5、显式配置、非法值 |
| `AiLiteHttpTransportAutoConfigurationTest` | starter | 10 | 六种 transport、缺模块/未知值 fail-fast、自定义 Bean 覆盖 |

已修复：`one(call, cancel)` 缺失、回调内 NPE 导致 future 挂起、`future.cancel` 漏记调用记录、`LlmCancelHandle` 非幂等、图片参数未收口（空 MIME/data、非 base64 data URL、MIME 大小写误拒）。**L1 的传输差异**（JDK 8 正文读取阶段可能等到读超时）已写入 `docs/entry-points.md`，编排层（AgentLoop / SessionLoop）的 P0 仍按本文档后续实现。

## 建议实现顺序

1. 先补 AL-30 / GW-01 / GW-02 / GW-04 / GW-19 / GW-21，把“挂起”变成超时失败。
2. 再补取消终态：AL-03、AL-04、AL-05、GW-05、GW-06。
3. 再补钩子异常出口：AL-08 至 AL-13、AL-31、AL-33。
4. 再补会话落盘与并发：SL-01、SL-03、SL-17、SL-18、SL-22、SL-26。
5. 最后补工具契约、日志和执行器拒绝等 P1/P2。

## 与现有测试的关系

现有 `AgentLoopTest`、`AgentSessionLoopAsyncTest`、`LlmGatewayAsyncTest`、`LlmGatewayAdversarialTest` 已覆盖 happy path、基本取消和部分同步异常；三份设计文档刻意避开这些重复路径，只列缺口。实现时建议新建：

- `AgentLoopAsyncAdversarialTest`
- `AgentSessionLoopAsyncAdversarialTest`
- `LlmGatewayAsyncAdversarialTest`
- `TransportAsyncAdversarialTest`（OkHttp/JDK8 各自模块）
