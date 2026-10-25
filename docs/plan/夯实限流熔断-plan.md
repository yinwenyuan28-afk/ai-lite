# 夯实限流熔断（出口自我保护）

**状态（2026-10-25）：计划初稿，§3.6 四项决策已由用户拍板落定；子代理审查中。** 本计划为网关增加出口保护：并发限流、熔断、`Retry-After` 尊重与多 key 轮换。全部能力**默认关闭**，未配置时行为与现状零差异。

一句话总纲：

> **保护的是自己的配额与调用方线程：按供应商限制并发、连续失败快速失败、尊重对方的退避指令、耗尽时换钥匙——降级与备用路由留给业务层。**

---

## 1. 调研结论（2026-10-25，源码级）

- `invoke` / `invokeAsync` 是全部入口的漏斗（one/oneAsync/oneByStream/round/toolLoop/重试全部经过），且此时 `ResolvedModel` 已解析——**provider 粒度的限流与熔断挂这里一次覆盖**，AgentLoop/Workflow/SessionLoop 无需任何改动。
- 错误分类已结构化：`LlmCallException.Kind`（TRANSPORT/HTTP/PARSE/...）+ `statusCode`；`RetryPolicy.defaultRetryable` 已按 429/5xx 判定可重试——**熔断的"失败"判定复用同一口径**，不发明第二套。
- `Retry-After` 目前完全未处理（全仓 grep 无引用）；重试退避只有固定指数。
- `AiLiteConfig.Provider` 只有单 `apiKey`/`apiKeyEnv`；`ResolvedModel.getApiKey()` 是传输层取 key 的唯一出口。
- 异步路径线程纪律：传输回调线程不可阻塞（回调策略计划已固化）——**限流的等待策略必须区分同步/异步入口**。

## 2. 范围

### 2.1 本次范围

- `ai-lite.guard.*` 配置（并发限流 + 熔断）。
- core：`ProviderGuard`（每 provider 一个：并发信号量 + 熔断状态机），纯 JDK 无依赖。
- 网关 `invoke`/`invokeAsync` 接入（tryAcquire/熔断快速失败）。
- `Retry-After` 尊重：传输层带出头值 → `LlmCallException` 扩展字段 → `RetryRun`/`oneWithRetry` 优先采用。
- 多 key 轮换：`api-keys` 列表配置 + 429/401 轮转。
- 熔断状态与限流拒绝的 Micrometer 指标（复用可观测性装饰器模式）。
- 测试与文档。

### 2.2 明确不做

- **自动降级到备用模型/provider**：降级是业务语义（成本、能力、数据边界），调用方 try-catch + 备用 profile 自行处理；框架只负责"快速失败得足够快"。
- QPS 令牌桶（速率限制）：并发上限已削峰；速率维度列后续计划（依赖调度器，复杂度独立）。
- 按模型/按 biz 粒度限流：provider 粒度对齐配额边界（账号才是限额主体）。
- 熔断期间的请求排队/缓冲：OPEN 就快速失败，不暂存重放。

## 3. 设计

### 3.1 并发限流（provider 粒度信号量）

```text
providers.<id>.guard.concurrency: 8      # 0=不限（默认）
guard.concurrency-wait-ms: 2000          # 拿不到许可时的有界等待
```

- **同步入口**（invoke）：`acquire` 有界等待（`tryAcquire(waitMs)`）；超时抛 `LlmCallException("并发已达上限", Kind.OVERLOAD)`——与回调策略的 OVERLOAD 同语义：框架主动背压，不可重试重试语义由调用方决定。
- **异步入口**（invokeAsync）：**不阻塞提交线程**——`tryAcquire(0)` 立即判定；拿不到时注册"许可释放即重试"的排队回调（`Semaphore.release` 监听 + 有界队列），队列也满才 OVERLOAD。等待期间 future 未完成，取消语义不变（句柄仍生效）。
- 许可在**终态收敛处释放**（成功/失败/取消三路，复用 recordTerminal 位置），而非传输返回处——重试属于上层，一次 attempt 一个许可。

### 3.2 熔断器（每 provider 状态机）

```yaml
guard.circuit:
  enabled: false            # 默认关
  failure-rate: 50          # 窗口失败率阈值（%）
  window-seconds: 60        # 滑动窗口
  minimum-calls: 20         # 窗口内最少样本数（不足不熔断）
  open-seconds: 30          # OPEN 冷却时长；过半进 HALF_OPEN
```

- 状态机：`CLOSED → (失败率超阈值) → OPEN → (冷却) → HALF_OPEN → (探测成功) → CLOSED / (探测失败) → OPEN`。
- **失败判定**与 `RetryPolicy.defaultRetryable` 同口径：`Kind.TRANSPORT`、`Kind.HTTP` 且 5xx/429。**429 计入限流事件但单独计数**（`ailite.llm.guard.rate-limited`），是否计入熔断失败率见 §3.6-D3。
- `Kind.PARSE`/`CONFIG`/`CANCELLED`/`OVERLOAD` 不计失败（框架/调用方自身问题，不是供应商故障信号）。
- OPEN/HALF_OPEN 期间请求快速失败：`LlmCallException("供应商熔断中: <id>", Kind.HTTP, 503)`——语义对齐"供应商侧不可用"，让上层重试策略自然判定不可重试。
- HALF_OPEN 只放行 **1 个**探测请求（CAS），其余继续快速失败。
- 计数窗口：环形时间片（60s/6 片，每片 10s）原子计数，无锁读取——样本量小（每 provider 每秒几十次）不值得上更重的结构。

### 3.3 Retry-After 尊重

- 传输层：429/503 且响应带 `Retry-After` 头（秒数或 HTTP 日期，只支持秒数形式，日期形式忽略）时，把值放进 `LlmCallException` 新增字段 `retryAfterMs`（`Integer`，additive 构造器）。
- `RetryRun` / `oneWithRetry`：`retryAfterMs != null` 时，该次退避取 `min(retryAfterMs, policy 上限)`，否则用原指数退避；deadline 逻辑不变（等待会消耗 deadline，超了按 deadline 收敛）。
- 上限 clamp 防恶意/错误大值（如 `Retry-After: 86400`）——默认上限取 policy 的 `maxDelayMs`。

### 3.4 多 key 轮换

```yaml
providers.demo:
  api-key: k1               # 现有单 key（保留）
  api-keys: [k1, k2, k3]    # 新增多 key；与 api-key 同时配置时以 api-keys 为准
```

- `ResolvedModel` 增加 `nextApiKey()` 语义？**不**——key 状态（哪个被耗尽）必须跨调用共享，放 `ModelResolver`：每 provider 维护轮转游标，`resolve` 时返回当前 key。
- 触发轮转：某次调用以 **429 或 401** 终态失败且该 provider 配了多 key → 该 key 标记冷却（`guard.key-cooldown-seconds`，默认 60s）→ 游标切到下一个可用 key（全部冷却则回到第一个，等价单 key 行为）。
- 429 同时计入熔断窗口与 `rate-limited` 指标；轮换不改变熔断状态。
- 密钥安全：冷却标记只含 key 的**索引**不含值；日志/记录/指标永不出现 key（沿用现有脱敏纪律）。

### 3.5 指标（联动可观测性）

| 指标 | 类型 | 标签 |
| --- | --- | --- |
| `ailite.llm.guard.rejected` | Counter | `provider`,`reason`（concurrency/circuit） |
| `ailite.llm.guard.circuit-state` | Gauge | `provider`（0=CLOSED,1=HALF_OPEN,2=OPEN） |
| `ailite.llm.guard.rate-limited` | Counter | `provider` |

经 starter 挂 Micrometer（与可观测性计划同模式）；core 内部只暴露计数回调（`GuardMetrics` 接口，默认 no-op）。

### 3.6 设计决策（2026-10-25 用户拍板，均按推荐落定）

- **D1 异步拿不到并发许可 → 排队等待**（有界队列，许可释放即唤醒）。对调用方平滑；实现复用回调策略的串行队列经验。
- **D2 熔断粒度 → provider 级**。对齐账号配额边界；同供应商多协议共享同一个供应商健康度。
- **D3 429 → 计入熔断失败率**，同时单独计 `rate-limited` 指标；多 key 轮换兜底（换 key 后新请求用新 key，熔断窗口照常滑动恢复）。
- **D4 并发许可 → 按请求（单次模型调用）**。工具循环每轮模型调用各占一个许可，粒度公平且与重试语义对齐（一次 attempt 一个许可）。

## 4. 实施阶段

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| G0 | 配置（AiLiteConfig.Guard + Provider.apiKeys）+ `ProviderGuard` 骨架（信号量 + 熔断状态机 + 环形窗口） | 单测：状态机全转换、窗口滑动、信号量有界等待/超时 |
| G1 | 网关接入：invoke/invokeAsync 同步/异步两路 acquire/release（终态释放）；OVERLOAD 异常 | 单测：并发第 N+1 个同步等待后成功/超时；异步排队与队列满；取消时许可归还 |
| G2 | 熔断接入：失败判定口径（与 RetryPolicy 对齐）、OPEN 快速失败、HALF_OPEN 单探测 | 单测：失败率触发 OPEN、OPEN 快速失败、半开探测成败两路、窗口内样本不足不触发 |
| G3 | Retry-After：三个传输层带出（OkHttp/Jetty/WebClient/Ktor 错误路径）→ 异常字段 → RetryRun 采用与 clamp | 单测：头值优先于指数退避、超上限 clamp、deadline 消耗、无头退化原行为 |
| G4 | key 轮换：ModelResolver 游标与冷却、429/401 触发、api-keys 配置 | 单测：轮转顺序、全冷却回退单 key、脱敏（日志无 key） |
| G5 | 指标挂钩（starter）+ 全量回归 + 文档（配置说明 §guard、README、testing/测试索引）+ 执行记录 | 全量绿；文档与行为一致 |

## 5. 测试矩阵

| 类别 | 用例 | 断言 |
| --- | --- | --- |
| 限流 | 并发=2，第 3 个同步请求 | 有界等待后成功（前两个完成） |
| 限流 | 等待超时 | OVERLOAD、不发出请求 |
| 限流 | 异步排队 | 提交线程不阻塞，许可释放后完成 |
| 限流 | 排队上限 | OVERLOAD |
| 限流 | 取消排队中请求 | 许可/队列位归还，future cancelled |
| 熔断 | 20 次窗口内 ≥50% 失败 | OPEN，后续请求 503 快速失败、零 HTTP |
| 熔断 | 冷却后半开探测成功/失败 | CLOSED / 重新 OPEN |
| 熔断 | 样本不足 / PARSE / 取消 | 不计失败、不触发 |
| 熔断 | 429 计入（按 D3 定夺） | 触发路径与 rate-limited 指标同计数 |
| Retry-After | 429 带 Retry-After: 3 | 重试等待 3s（clamp 内），非指数值 |
| Retry-After | 头值超 maxDelay | clamp 到上限 |
| Retry-After | deadline 剩 1s、头 3s | 按 deadline 收敛不越界 |
| 轮换 | 3 key、429 | 下次 resolve 返回下一 key；冷却期跳过 |
| 轮换 | 全部冷却 | 回退第一个 key（等价单 key） |
| 轮换 | 401 | 同 429 轮换 |
| 隔离 | 默认全关（不配置 guard） | 行为与现状逐字节一致（回归锁定） |
| 指标 | 拒绝/状态/rate-limited | 计数与状态值正确 |

## 6. 验收标准

- 未配置任何 guard 时，全量既有测试零变化（行为回归锁定）。
- 异步入口任何路径不阻塞提交/传输线程。
- 熔断 OPEN 期间零出站请求；HALF_OPEN 单探测。
- 失败判定与 RetryPolicy 口径一致（单一事实来源，测试互证）。
- Retry-After 永不突破 policy maxDelay 与 deadline。
- key 值永不进日志/记录/指标/异常消息。
- 新增测试约 25–30 条；全量通过。

## 7. 风险

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| 异步等待队列复杂度 | 许可释放唤醒排队者 | 复用回调策略的 SerialCallbackExecutor 模式经验；队列严格有界 |
| 限流等待占用调用线程（同步） | 调用方线程 park | 有界 waitMs + OVERLOAD 语义明确；默认关闭 |
| 熔断误开（网络抖动） | 窗口样本不足时误判 | minimum-calls 门槛 + 半开探测快速恢复 |
| 多 key 轮换与重试叠加 | 轮换后旧 key 请求仍在途 | 轮换只影响后续 resolve，在途请求自然终结 |
| 环形窗口内存 | 每 provider 6 片 × 2 计数 | 固定小对象，无增长 |

## 8. 与既有计划的关系

- 失败分类复用《夯实oneWithRetry》的 Kind 体系；OVERLOAD 语义对齐《夯实回调执行策略》。
- 指标挂接模式复用《夯实可观测性》装饰器经验（GuardMetrics no-op 接口）。
- 传输层错误路径改动涉及《夯实HTTP传输并发》的四个执行器——只加"错误响应头带出"，不改连接/流逻辑。
