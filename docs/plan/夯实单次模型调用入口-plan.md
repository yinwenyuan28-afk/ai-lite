# 夯实单次模型调用入口

这个计划只做一件事：**把「单次模型调用入口」的行为钉成可证明一致的契约。** 不加新功能，不改编排层。

> **状态（2026-10-21）：Phase 0–5 与第 12 节已实现。** 新增 `one(LlmCall, LlmCancelHandle)`，修复回调内空值导致的挂起、`future.cancel` 漏记和 `LlmCancelHandle` 非幂等；图片链路收口 MIME/base64/data URL。新增 72 个测试（入口 45 + 图片 27）。执行记录见第 11、12 节。

「单次模型调用入口」指：

- `LlmGateway.one`
- `LlmGateway.one(call, cancel)`
- `LlmGateway.oneAsync`（含取消重载）
- `LlmCoroutines.one`
- `LlmCoroutines.oneAsync`

流式变体 `oneByStream` / `oneByStreamAsync` 属于同一个单次调用家族，取消契约一并覆盖，但不重复设计流式解析用例。

**明确不在本次范围：**

- `AgentLoop.run` / `runAsync`
- `AgentSessionLoop.resume` / `resumeAsync`
- `Workflow` 的步骤编排
- 编排层的重试、并行工具、子 Agent

编排层的 parity 和取消改造另立计划。本次只改网关与协程适配层。

---

## 1. 取消契约：L1–L4

“可取消”必须分层承诺，不能笼统说“支持取消”。

| 层级 | 含义 | 本次是否保证 |
| --- | --- | --- |
| L1 请求中止 | 正在进行 HTTP 请求被 abort，不再等响应 | **OkHttp 保证；JDK 8 传输在正文/SSE 读取阶段 best-effort** |
| L2 不再推进 | 取消后不再发新请求、不重试、不产生新的业务推进 | **保证** |
| L3 无副作用 | 取消后不写任何业务数据 | 不保证；调用日志仍可能写入 |
| L4 供应商停止计费 | 服务端立即停止生成和计费 | 不保证，客户端无法控制 |

所以「所有单次模型调用入口可取消」在本计划中的准确表述是：

> **所有单次模型调用入口都保证 L2；网关在正常完成分支也会检查取消状态。L1 由传输层实现决定：OkHttp 全阶段可中止，JDK 8 `HttpURLConnection` 在正文/SSE 阻塞读上可能等到读超时。L3/L4 不承诺。**

具体到四个入口：

| 入口 | L1 在途中止 | L2 不再推进 | 取消方式 |
| --- | --- | --- | --- |
| `gateway.one(call, cancel)` | 传输相关（见上） | 是 | `LlmCancelHandle.cancel()` |
| `gateway.oneAsync(call, cancel)` | 传输相关（见上） | 是 | handle 或 `future.cancel()` |
| `LlmCoroutines.one(gateway, call, cancel?)` | 传输相关（见上） | 是 | 取消协程 `Job` 或 handle |
| `LlmCoroutines.oneAsync(gateway, call, cancel?)` | 传输相关（见上） | 是 | handle 或 `future.cancel()` |

**已补的 API（2026-10-21）：** 原 `gateway.one(call)` 没有取消重载，无法中止在途请求。本次新增：

```java
LlmResult one(LlmCall call, LlmCancelHandle cancel);
```

`one(call)` 保留为便捷方法，但文档明确它只受超时约束，不提供在途取消。这样“所有入口可取消”才成立。

取消后的终态语义要固定：

- 通过 `LlmCancelHandle` 取消：同步返回 `LlmResult(stopReason=ABORTED)`；异步 future 正常完成，结果为 `ABORTED`。
- 通过 `future.cancel(true)` 取消：future 处于 cancelled 状态，`get()` 抛 `CancellationException`；底层 HTTP 仍要 abort。
- 通过协程 `Job.cancel()` 取消：`await()` 抛 `CancellationException`；底层 future 被取消，HTTP abort。
- 重复取消：只有一个终态，不重复产生结果。
- 取消与完成竞态：要么得到正常结果，要么得到 ABORTED/取消，不能两者都出现。

---

## 2. Phase 0：契约基线（已实现）

产出 `docs/单次模型调用入口.md`：

- 四个入口的返回类型、是否阻塞、取消方式、适用场景。
- 第 1 节的 L1–L4 取消契约和终态语义。
- 错误分类：配置/校验错误、传输错误、协议解析错误、取消、超时。
- 重试边界：网关不做自动重试；单次入口的重试策略放在调用方或独立的 `RetryPolicy` 装饰器。

同时确定 `LlmCoroutines.oneAsync` 的定位：0.x 保留，文档标注“等价于 `gateway.oneAsync`，不是 suspend 函数”；1.0 再评估改名或移除。

---

## 3. Phase 1：入口 parity（P0，已实现）

新增 `LlmSingleCallEntryParityTest`，用可控 fake transport 覆盖：

| 维度 | 断言 |
| --- | --- |
| 结果 | `one` 与 `oneAsync` 的 content、stopReason、usage、rounds、provider/model 一致 |
| 请求 | 两条路径发出的 `LlmRequest` 消息、超时、stream 标记一致 |
| 校验 | null call、空 bizId、未登记 bizId、未配置模型、不支持协议：全部在发请求前抛同样的 `LlmCallException` |
| 异常 | transport 同步抛 `LlmCallException` / `RuntimeException`、future 异常完成、`CompletionException` 多层包装：根因和暴露类型一致 |
| 日志 | 每次调用一条 `LlmCallRecord`；stopReason、success、tokens、latency 一致；错误摘要遮蔽 api key |
| 线程 | `one` 在调用线程；`oneAsync` 提交后返回；注入 `AsyncHttpExecutor` 时异步传输不落 commonPool，否则回落到 SPI 默认的 commonPool |

验收：同一 fake transport 下，`one` 和 `oneAsync` 的请求次数、结果字段完全一致；所有异步断言带 `get(5, SECONDS)`。

---

## 4. Phase 2：取消契约（P0，已实现）

本阶段是计划的核心。新增 `LlmSingleCallCancellationTest`，对四个入口分别覆盖：

1. **发请求前取消**：handles 已取消 → 不发 HTTP，返回 `ABORTED`；异步 future 正常完成。
2. **在途取消**：transport 绑定 abort 后，从另一线程调用 `handle.cancel()` → abort 被触发，结果 `ABORTED`。
3. **bindAbort 之前取消**：先 cancel 再 bind → bind 时立即 abort，不丢取消。
4. **`future.cancel(true)`**：future 进入 cancelled，底层 abort 触发；`get()` 抛 `CancellationException`。
5. **协程 `Job.cancel()`**：`LlmCoroutines.one` 的 `await()` 抛 `CancellationException`，底层 future 被取消，HTTP abort。
6. **重复取消**：多次 cancel 只产生一个终态；不重复完成 future。
7. **取消与完成竞态**：响应和 cancel 同时到达，只允许一个终态。
8. **取消后不再重试**：当前网关没有重试；测试锁定“取消不会被任何内部逻辑重新发起”。
9. **取消不误伤其他调用**：两个并发调用共用一个 transport，取消 A 不影响 B。
10. **sync `one(call, cancel)`**：新增重载的在途取消行为与异步版一致。
11. **非合作 transport 兜底**：传输层忽略 abort 并正常返回时，网关仍把结果改写为 `ABORTED`（取消优先）。
12. **future 取消也落记录**：`future.cancel(true)` 写一条 `aborted` 记录，且只写一条。

验收：每个适用入口都验证 L2；L1 在 OkHttp 上验证，JDK 8 的正文读取限制在文档中标注。所有等待带超时；取消后请求次数不再增长。`future.cancel` 不适用于同步入口，`Job.cancel` 只适用于协程入口，矩阵中记为 N/A。

---

## 5. Phase 3：协程适配 parity（P0，已实现）

新增 `LlmCoroutinesParityTest`：

- `LlmCoroutines.one(gateway, call)` 与 `gateway.oneAsync(call).await()` 的结果、异常、取消行为一致。
- `LlmCoroutines.oneAsync(gateway, call, cancel)` 与 `gateway.oneAsync(call, cancel)` 语义一致，证明它只是委托。
- `withTimeout` / `job.cancel()` 会取消底层 future 并触发 abort；`CancellationException` 不被吞掉。
- 并行 `async` + `awaitAll`：一个子协程失败时兄弟协程被取消；`supervisorScope` 下互不影响。
- 适配层不改变请求参数：请求次数、消息、取消句柄与直接调网关完全一致。

验收：`LlmCoroutines` 中没有任何重试、改写请求或吞异常的逻辑；所有 suspend 测试使用单线程调度器 + `withTimeout`。

---

## 6. Phase 4：边界收口（P0/P1，已实现）

只针对单次调用路径：

1. transport 返回 `null` future：不能静默挂起，必须以异常完成或超时失败暴露。
2. transport future 以 `null` completion 完成：不能挂起，必须异常完成。
3. `completeAsync` 同步抛异常、future 完成两次、`CompletionException` 多层包装：终态唯一、根因清晰。
4. `usage` / `stopReason` 为 null：已由 `LlmCompletion` 构造器归一化为 `zero()` / `STOP`，本阶段只做回归验证；真正要收口的是 null completion。
5. `onDelta` 抛异常（流式单次调用）：必须以 `LlmCallException("模型调用失败")` 失败完成，不能挂起；同步与异步一致。只补回归测试，不改现有行为。
6. `LlmCancelHandle` 的 Javadoc 与实现不一致：要么改成真正幂等，要么改注释并文档化。

每修一个，补一个回归测试，并在本文档标记“已实现”。

---

## 7. Phase 5：文档与 CI（P2，已实现）

- `docs/单次模型调用入口.md`：入口契约矩阵、L1–L4 取消语义、推荐用法。
- `docs/重试说明.md`：单次入口的重试边界、可重试错误、幂等要求。
- CI：所有异步测试带超时；新增测试类命名规范；对无超时 `get()` 的用例做评审检查。
- README 文档索引补充本计划入口。

---

## 8. 验收标准

- 四个单次模型调用入口 × 六个维度（结果、校验、异常、取消、日志、线程）都有测试覆盖。
- 四个入口全部满足 L2；OkHttp 传输满足 L1；JDK 8 正文读取阶段的限制已文档化。`gateway.one(call, cancel)` 重载已提供。
- 所有**新增**异步测试 5 秒内失败，不会挂死 CI。
- 正常路径行为不变；差异只出现在已文档化的地方（同步抛异常 vs future 异常、JDK 8 取消时延）。
- `LlmCoroutines` 仍然是薄适配层；`LlmCoroutines.oneAsync` 的定位有文档。
- 新增测试 45 个。
- **不改动 `AgentLoop` / `AgentSessionLoop` / `Workflow`。**

---

## 9. 重试放在哪里

### 当前状态：没有自带重试

- `LlmGateway` 每轮只调用一次 transport，失败就进入 `ERROR` 或抛出异常。
- `OkHttpExecutor` 默认 `retryOnConnectionFailure(false)`，关闭 OkHttp 自己的连接重试；如果调用方注入自定义 `OkHttpClient`，以客户端配置为准。
- `Attempt` 只是 usage/latency 累加器，不承担重试。

### 本次的边界

本计划**不实现重试**，只锁定“网关不做自动重试”这个契约。单次入口的重试属于调用方策略：

```text
调用方 / RetryPolicy
  └─ gateway.oneAsync(call, cancel)
       ├─ 次数、指数退避、可重试分类、总 deadline
       └─ 每次尝试独立调用，独立记录
```

可重试：429、5xx、连接/读超时、可识别的瞬时 IO 错误。
不可重试：400/401/403/404、参数/协议错误、内容审核拒绝、取消。
流式：未收到首个 delta 前可重试；收到后不自动重试。

编排层的重试（AgentLoop 每轮、SessionLoop 整体）不在本次范围，另立计划。

---

## 10. 与编排层的关系

本次计划不碰编排层。单次入口的契约稳定后，编排层可以：

- 在 `AgentLoop` 里复用 `gateway.roundAsync(call, tools, cancel)` 实现异步在途取消（同步 `round(call, tools, cancel)` 目前不存在，需要时由编排层计划补齐）；
- 在 `AgentSessionLoop` 里复用单次入口的取消语义；
- 在编排层增加 `RetryPolicy`，而不是修改网关。

这些都属于后续独立计划，不在本文件范围内。

---

## 11. 执行记录（2026-10-21）

### 生产代码

| 文件 | 改动 |
| --- | --- |
| `ai-lite-core/.../llm/LlmGateway.java` | 新增 `one(LlmCall, LlmCancelHandle)`；`completeAsync` 返回 `null` future、future 以 `null` 完成、工具执行器返回 `null` future 或 `null` 列表时统一转为可观察失败；同步路径 `null` completion 转为 `LlmCallException`；正常完成前再检查一次取消状态；`future.cancel(true)` 补一条 `aborted` 调用记录 |
| `ai-lite-core/.../llm/LlmCancelHandle.java` | `cancel()` / `bindAbort()` 竞态下 abort 最多执行一次；文档明确“一个句柄只服务一次调用” |

### 测试

| 测试类 | 模块 | 用例数 |
| --- | --- | ---: |
| `LlmSingleCallEntryParityTest` | core | 14 |
| `LlmSingleCallCancellationTest` | core | 16 |
| `LlmSingleCallBoundaryTest` | core | 7 |
| `Jdk8HttpExecutorTest`（新增取消用例） | http-jdk8 | 2 |
| `LlmCoroutinesParityTest` | executor-coroutines | 6 |
| **合计** | | **45** |

所有异步断言使用 `get(5, TimeUnit.SECONDS)` 或 `CountDownLatch.await(5, ...)`，挂起会变成测试失败。

### 覆盖矩阵

| 维度 | `one` | `one(call, cancel)` | `oneAsync` | `LlmCoroutines.one` | `LlmCoroutines.oneAsync` |
| --- | --- | --- | --- | --- | --- |
| 结果 parity | ✓ | ✓ | ✓ | ✓ | 委托 |
| 请求一致性 | ✓ | ✓ | ✓ | ✓ | 委托 |
| 校验/异常 | ✓ | ✓ | ✓ | ✓ | 委托 |
| 预取消 / 在途取消 | N/A | ✓ | ✓ | ✓ | 委托 |
| `future.cancel` / `Job.cancel` | N/A | N/A | ✓ | ✓ | ✓ |
| 取消记录 | ✓ | ✓ | ✓ | 委托 | 委托 |
| 线程归属 | ✓ | ✓ | ✓ | ✓ | 委托 |

`N/A` 表示该入口没有对应能力，不是缺口；标“委托”表示由 `gateway.oneAsync` 的同一段代码覆盖，避免重复用例。

### 文档

- 新增 `docs/单次模型调用入口.md`：入口矩阵、L1–L4、终态语义、错误分类。
- 新增 `docs/重试说明.md`：重试边界、可重试分类、`RetryPolicy` 结构。
- `docs/上层调用API使用指南.md` 补充 `one(call, cancel)` 与取消/重试链接。
- `docs/testing/测试索引.md` 记录已实现用例和已修复缺陷。
- `README.md` 文档索引补充两个新文档。

---

## 12. 多模态图片（base64）补充（已实现）

### 12.1 现状核查

单次调用入口**已经支持** base64 图片，不需要新增 API：

| 环节 | 现状 |
| --- | --- |
| 构造 | `LlmMessage.user(text, images)` / `LlmMessage.other(text, images)`，`LlmImage(mime, base64)` |
| 模型能力 | 模型配置 `input` 含 `image` 时 `ResolvedModel.isAcceptsImage()` 为 true |
| 发请求前校验 | `LlmTexts.validate` 拒绝非 USER/OTHER 角色带图、模型不接受图片 |
| OpenAI Chat Completions | `content` 数组：`{"type":"text"}` + `{"type":"image_url","image_url":{"url":"data:<mime>;base64,<data>"}}` |
| OpenAI Responses | `{"type":"input_image","detail":"auto","image_url":"data:..."}` |
| Anthropic Messages | `{"type":"image","source":{"type":"base64","media_type":<mime>,"data":<base64>}}` |
| 同步/异步 | 两条路径共用同一个 `body(...)`，payload 天然一致 |

### 12.2 本次已修复

1. **协议级测试**：三个适配器都补了图片分片断言（字段名、data URL、顺序、`detail=auto`）。
2. **sync/async parity**：`LlmGatewayImageTest` 逐字段比较两条路径的 MIME、base64 和顺序。
3. **参数收口**：`LlmTexts.validateImage` 在发请求前拒绝 null 元素、空 MIME、非 `image/*`（大小写不敏感）、空 data、非 base64 data URL。
4. **data URL 前缀**：`LlmImage` 构造时剥离 `data:...;base64,`，裸 base64 的首尾空白也会清理。
5. **会话边界**：`AgentSessionMessage` 不存图片，`AgentSessionLoop` 的首轮 `call.getMessages()` 也会被会话历史覆盖；图片不会进入会话存储、快照或 prompt 渲染。

### 12.3 契约

- 图片只允许出现在 `USER` / `OTHER`（`OTHER` 折叠为 `USER`）。
- 模型配置 `input` 必须包含 `image`，否则发请求前抛 `LlmCallException("模型不接受图片")`。
- `mime` 必须非空且以 `image/` 开头（大小写不敏感）；`data` 必须非空。
- `data` 同时接受裸 base64 和 `data:<mime>;base64,<data>`；后者在构造时去掉前缀。非 base64 的 data URL（例如 `data:image/svg+xml;charset=utf-8,...`）会被拒绝。
- 同一消息允许多张图片，顺序保持调用方给定顺序；**每条源消息内部**文本分片在前、图片分片在后。Anthropic 会合并相邻同角色消息，合并后按各源消息的块顺序拼接，不保证所有文本块都在所有图片块之前。
- 图片-only 消息（文本为空且图片非空）合法，只发送图片分片。
- 网关不做压缩、缩放或大小校验；调用方负责遵守供应商的尺寸/字节限制。base64 会经过消息对象、JSON 树和 HTTP body 至少三份内存副本，超大图片的内存开销由调用方承担。
- 同步与异步入口发送的图片消息、MIME、base64 完全一致。
- **会话层不持久化图片**：`AgentSessionMessage` 明确不存图片，`AgentSession.messagesToLlm` 只还原文本，`AgentSessionLoop` 的首轮 `call.getMessages()` 也会被会话历史覆盖。因此 `LlmGateway.one/round/toolLoop` 和 `AgentLoop.run` 支持图片输入，`AgentSessionLoop` 不支持；续聊需要调用方重新携带图片。会话层支持图片属于另一个计划。

### 12.4 测试清单

| 测试类 | 模块 | 用例 |
| --- | --- | ---: |
| `LlmGatewayImageTest` | core | 8 |
| `LlmTextsTest`（新增图片校验） | core | 7 |
| `LlmImageTest` | core | 6 |
| `OpenAiCompletionsTransportTest`（图片分片 + 流式） | http-okhttp | 2 |
| `OpenAiResponsesTransportTest`（图片分片 + 图片-only） | http-okhttp | 2 |
| `AnthropicMessagesTransportTest`（图片分片 + 相邻消息合并） | http-okhttp | 2 |
| **合计** | | **27** |

覆盖 sync/async 请求消息一致、图片-only（同步与异步）、多图顺序、OTHER 折叠、非图片模型拒绝、非法图片参数、data URL 归一化、非 base64 data URL 拒绝、MIME 大小写，以及三个协议的实际请求体和 Anthropic 合并行为。公开 API 无法构造“ASSISTANT 带图”，`LlmTexts` 的角色检查保留为防御性分支，不计入测试覆盖。

### 12.5 验收

- 三个协议都能从实际请求体中读到正确的 base64 和 MIME。
- `one` 与 `oneAsync` 的图片消息逐字段一致。
- 结构性非法参数（null 元素、空 MIME、非 `image/*`、空 data、非 base64 data URL）全部在发请求前失败，错误信息可读；供应商专属 MIME 和尺寸/字节限制不在网关保证范围。
- 文档写明裸 base64 与 data URL 两种输入、模型配置要求、会话不持久化图片，以及大小限制责任。

