# 夯实限流熔断（出口自我保护）

**状态（2026-10-25）：已按 G0–G5 全部落地，全仓回归绿（JDK 8 全量构建 805 个测试、0 失败，新增 60 个）。** 本计划为网关增加出口保护：并发限流、熔断、`Retry-After` 尊重与多 key 轮换。全部能力**默认关闭**，未配置时为 NOOP 直通，行为与现状零差异。执行记录见第 10 节。

一句话总纲：

> **保护的是自己的配额与调用方线程：按供应商限制并发、连续失败快速失败、尊重对方的退避指令、耗尽时换钥匙——降级与备用路由留给业务层。**

---

## 1. 调研结论（2026-10-25，源码级；经审查核实）

- `invoke` / `invokeAsync` 是全部入口的漏斗，`transport.complete/completeAsync` 全仓仅两处调用（同步循环内、`AsyncRun.next()`）——**按轮挂接一次覆盖**，AgentLoop/Workflow/SessionLoop 零改动。
- 错误分类已结构化：`LlmCallException.Kind` + `statusCode`；`RetryPolicy.defaultRetryable`（public static）是失败口径的单一事实来源。
- `Retry-After` 目前完全未处理；非 2xx → `Kind.HTTP` 的转换点在 **5 个 HTTP 执行器模块**（okhttp / jetty / webclient / jdk8 / ktor-cio；okhttp-jdk21 复用 okhttp 自动覆盖），协议适配器零改动；各点位响应头均在手，`LlmCallException` 加 `retryAfterMs` 字段（5 参构造器 additive）兼容成立。
- `AiLiteConfig.Provider` 只有单 key；`ResolvedModel.getApiKey()` 是传输层取 key 唯一出口，无索引字段（轮换按 key **值**匹配，不改 17 参构造器）。
- JDK `Semaphore` **没有** release 监听 API——异步排队须自研闸门（§3.1）。

## 2. 范围

### 2.1 本次范围

- 配置：顶级 `ai-lite.guard.*`（等待/队列/熔断/key 冷却）+ provider 级 `ai-lite.llm.providers.<id>.guard.concurrency`（两个配置根，见 §3.0）。
- core：`ProviderGuard`（每 provider 一个：并发闸门 + 熔断状态机），纯 JDK 无依赖。
- 网关按轮接入（同步 `try/finally`、异步 `whenComplete` 头部，见 §3.1）。
- `Retry-After` 尊重：5 个执行器带出头值 → 异常字段 → `RetryRun` 采用。
- 多 key 轮换：`api-keys` 列表 + 429/401 失败标记触发。
- 熔断/限流指标（Micrometer，复用可观测性模式）。
- 测试与文档。

### 2.2 明确不做

- 自动降级到备用模型/provider（业务语义，调用方自行处理）。
- QPS 令牌桶（速率维度另立计划）。
- 按模型/按 biz 粒度限流。
- 熔断期间的请求排队/缓冲（OPEN 即快速失败）。

## 3. 设计

### 3.0 配置（两个根，"guard"只在这两处出现）

```yaml
ai-lite:
  llm:
    providers:
      demo:
        guard:
          concurrency: 8        # 该 provider 并发上限；0=不限（默认，NOOP 直通）
  guard:                        # 顶级：跨 provider 的通用参数
    concurrency-wait-ms: 2000   # 拿许可的有界等待（同步等待与异步排队共用）
    async-queue-capacity: 100   # 异步排队容量；超出即拒绝
    circuit:
      enabled: false
      failure-rate: 50          # 窗口失败率阈值（%）
      window-seconds: 60
      minimum-calls: 20
      open-seconds: 30          # 冷却满 30s 进 HALF_OPEN
      probe-timeout-ms: 30000   # 半开探测超时，超时按探测失败回 OPEN
    key-cooldown-seconds: 60
```

### 3.1 并发限流（provider 粒度，按轮）

**粒度与挂点（审查 F1/F22 修正）**：许可按**单次模型调用**（即每次 transport 调用，D4 拍板）：

- **同步**：`invoke` 校验段全部抛出点**之后** acquire；工具循环内每次 `transport.complete` 外层 `try/finally` acquire/release。
- **异步**：`AsyncRun.next()` 每轮 `completeAsync` 前 acquire；transport future 的 `whenComplete` **头部**（`result.isDone()` 早退判断之前）release——否则"取消监听先收敛、在途轮次后完成"会漏释放。MODE_ONE/ROUND 下每轮=每次调用，与重试"一次 attempt 一个许可"天然一致（RetryRun 每 attempt 独立走 `oneAsync`）。
- **释放锚点不用 `recordTerminal`**（审查 F2）：回调策略的 `failBarrier` 路径（onDelta 异常）不经过它，挂那里会永久泄漏许可。

**闸门实现（审查 F5 修正）**：`Semaphore` 无 release 监听，自研闸门：

```text
ReentrantLock + Condition + ArrayDeque<Waiter>
  ├─ acquire：锁内 tryPermit() → 有则返回；无则按入口分两路：
  │    同步：Condition.awaitNanos(waitMs)（超时 → OVERLOAD 拒绝）
  │    异步：入等待队列（带存活标记），容量满 → OVERLOAD 拒绝
  ├─ release：锁内 tryPermit 交接给队头存活 waiter（不做真 release，
  │    天然无丢唤醒、无双发、FIFO 公平）；队列空才真正归还许可
  └─ 被唤醒的异步 waiter：把续跑（run.next()）投递到网关 ExecutorProvider，
     绝不占用释放方的传输回调线程（线程纪律，回调策略已固化）
```

同步等待与异步排队**共用同一闸门**（两套入口会互相饿死）。

**排队语义（审查 F6/F7 补全）**：

- 异步排队同样受 `concurrency-wait-ms` 约束（provider 挂死时排队者有界退出，OVERLOAD）。
- waiter 带存活标记：排队期间句柄取消 / `future.cancel` → 现有取消管道完成 result → 交接时跳过死 waiter；交接后发现 result 已完成 → 许可立刻交还。
- 交接时刻**重查熔断状态**：已 OPEN 则不放行、按熔断拒绝（排队中熔断翻开的漏放口）。

### 3.2 熔断器（每 provider 状态机）

```text
CLOSED（放行）
  │ 窗口内样本 ≥ minimum-calls 且失败率 ≥ failure-rate
  ▼
OPEN（拒绝）─── guard 合成的拒绝不计窗口样本、不计 minimum-calls（防自我强化）
  │ 冷却满 open-seconds
  ▼
HALF_OPEN（只放 1 个探测，CAS）─── 探测有超时（probe-timeout-ms），超时按失败回 OPEN
  ├ 探测成功 → CLOSED
  └ 探测失败 → OPEN（重新计时）
```

- **失败口径（审查 F4 修正）**：`ProviderGuard` **直接调用** `RetryPolicy.defaultRetryable(ex)`（public static、无状态），禁止复述或复制判定逻辑；用户自定义 `retryable` 谓词不影响熔断口径（熔断只认默认口径）。
- **计数挂点（审查 F2b 修正）**：异步挂 `AsyncRun.fail()`（cause 可解包出 Kind/statusCode），同步挂 invoke 的两个 catch——`recordTerminal` 签名没有异常对象，不作为锚点。每次 attempt（=每次 transport 调用）各计一个窗口样本（与 D4 粒度一致，审查 F21）。
- 429 计入失败率（D3），同时单独计 `rate-limited` 指标；多 key 轮换兜底。
- `PARSE`/`CONFIG`/`CANCELLED`/`OVERLOAD` 不计失败。
- **OPEN 快速失败的异常（审查 F3 修正）**：新增 `Kind.CIRCUIT_OPEN`（additive 枚举，`defaultRetryable` switch 落 default → 不可重试）——语义精确、与"框架主动保护"一致，不再用可重试的 HTTP/503 让重试策略空转。
- **窗口实现（审查 F9）**：6 片 × 10s，每片带 epoch 标签、写时 CAS 换片（避免滚动清零与并发自增的竞态丢计数）；读为近似值（切片边界撕裂为计数级误差，可接受）。

### 3.3 Retry-After 尊重

- 采集点：5 个执行器的非 2xx 错误路径（okhttp `ensureSuccessful`、jetty 4 处调用点——`toResult`/`httpError` 签名扩参、webclient、**jdk8 `error()`**、ktor-cio）；仅 `Kind.HTTP` 且 429/503 时读取头值（秒数形式；日期形式与非数字/≤0 一律忽略）。
- `LlmCallException` 新增 `retryAfterMs`（`Integer`，5 参构造器 additive）。
- `RetryRun.onAttemptComplete` 采用：头值有效时该次退避取 `min(retryAfterMs, maxDelayMs)`（clamp 复用 `RetrySupport.clampDelayMs`），否则原指数退避；deadline 逻辑不变（等待消耗 deadline，超了按 deadline 收敛）。

### 3.4 多 key 轮换

```yaml
providers.demo:
  api-keys: [k1, k2, k3]    # 元素支持与 api-key 相同的 ${ENV}/$ENV 占位符；
                             # 配置了 api-keys 时优先于 api-key/api-key-env
```

- 状态放 `ModelResolver`（starter 单例，resolve 并发调用 → 游标/冷却时间戳用原子类型）：每 provider 维护轮转游标 + 各 key 冷却时间戳（惰性到期判定，时间戳比较，无定时器）。
- **游标只在失败标记时推进**（审查 F10）：resolve 本身不轮转——否则工具循环每轮换 key、记录错乱。触发：`AsyncRun.fail()`/同步 catch 拿到 `Kind.HTTP` 且 429/401 → 按 `ResolvedModel.getApiKey()` 的**值**匹配索引标记冷却 → 游标切到下一个未冷却 key；全部冷却则回到第一个（等价单 key）。
- 协同：RetryRun 下次 attempt 重新 resolve，天然拿到新 key（审查 F23）。
- 脱敏：冷却标记只有索引；key 值永不进日志/记录/指标/异常消息。

### 3.5 指标（联动可观测性）

| 指标 | 类型 | 标签 |
| --- | --- | --- |
| `ailite.llm.guard.rejected` | Counter | `provider`,`reason`（concurrency/circuit） |
| `ailite.llm.guard.circuit-state` | Gauge | `provider`（0/1/2）——状态对象须被 starter 强持有（Micrometer 弱引用陷阱，审查 F20） |
| `ailite.llm.guard.rate-limited` | Counter | `provider` |

core 内部只暴露 `GuardMetrics` 回调接口（默认 no-op），starter 挂 Micrometer。

### 3.6 设计决策（2026-10-25 用户拍板；审查确认全部成立，其中两项修正实施位置）

- **D1 异步排队**：成立；机制按审查 F5/F6/F7 重写为自研闸门 + 容量/时限 + 取消与熔断竞态处理。
- **D2 provider 级熔断**：成立（`ResolvedModel.getProvider()` 在 acquire 前可得）。
- **D3 429 计入失败率**：成立；前置为排除 guard 合成异常回灌窗口（F3）与失败口径直调 `defaultRetryable`（F4）。
- **D4 许可按单次模型调用**：决策成立；挂接位置按审查 F1 改为每轮 acquire/release（原"入口一次挂接 + recordTerminal 释放"与之矛盾且有泄漏路径）。

### 3.7 ProviderGuard 归属（审查 F15）

网关持有 `Map<providerId, ProviderGuard>`，构造时按配置建；未配置任何 guard 的 provider **不建实例**（NOOP 直通，acquire 调用都不发生，零开销）。

## 4. 实施阶段

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| G0 | 配置（§3.0 两个根）+ `ProviderGuard` 骨架（闸门 + 状态机 + epoch 窗口 + `Kind.CIRCUIT_OPEN`） | 单测：状态机全转换（含探测超时）、窗口滑动与 epoch 换片、闸门同步等待/超时/交接 FIFO |
| G1 | 网关按轮接入（同步 try/finally、异步 whenComplete 头部；校验后 acquire）；异步排队与取消/熔断竞态 | 单测：并发=2 第 3 个同步等待后成功/超时；异步排队不阻塞提交线程、容量满 OVERLOAD、排队时限、取消退出队列、排队中 OPEN 拒绝；onDelta 异常路径许可不泄漏 |
| G2 | 熔断接入：失败计数挂 fail()/同步 catch（直调 defaultRetryable）、OPEN 拒绝（CIRCUIT_OPEN 不可重试）、半开单探测+超时 | 单测：失败率触发 OPEN、OPEN 零出站、半开探测成败/超时三路、样本不足不触发、PARSE/取消不计、guard 拒绝不计窗口、两 provider 隔离 |
| G3 | Retry-After：5 个执行器带出 → 异常字段 → RetryRun 采用与 clamp | 单测：头值优先、clamp 上限、deadline 消耗、无头/非法值退化指数退避 |
| G4 | key 轮换：resolver 游标与冷却、失败标记触发、占位符、优先级 | 单测：轮转顺序、仅失败推进、全冷却回退、401 同触发、脱敏 |
| G5 | 指标挂钩（starter，gauge 强持有）+ 全量回归 + 文档（配置说明 §guard、README、testing）+ 执行记录 | 全量绿（既有测试零修改）；NOOP 直通专项断言 |

## 5. 测试矩阵

| 类别 | 用例 | 断言 |
| --- | --- | --- |
| 限流 | 并发=2，第 3 个同步请求 | 有界等待后成功 |
| 限流 | 等待超时 / 异步排队超时 | OVERLOAD、不发出请求 |
| 限流 | 异步排队 | 提交线程不阻塞；许可交接后续跑在载体池 |
| 限流 | 排队容量满 | OVERLOAD |
| 限流 | 取消排队中请求 | 跳过死 waiter、许可/队列位归还、future cancelled |
| 限流 | 排队中熔断翻 OPEN | 交接时刻重查，拒绝不放行 |
| 限流 | **D4 粒度**：concurrency=1 + toolLoop | 工具执行期间（无模型请求在途）第二个调用者可进 |
| 限流 | onDelta 异常（回调策略路径） | 许可不泄漏（后续请求正常） |
| 熔断 | 窗口 ≥minimum-calls 且失败率超阈 | OPEN；后续毫秒级 CIRCUIT_OPEN 拒绝、零 HTTP |
| 熔断 | 半开探测成功/失败/超时 | CLOSED / 回 OPEN / 回 OPEN |
| 熔断 | 样本不足 / PARSE / 取消 / guard 自身拒绝 | 不计失败、不触发、不回灌窗口 |
| 熔断 | 两 provider 独立 | 一个 OPEN 不影响另一个 |
| Retry-After | 429 带 Retry-After: 3 | 下次退避 3s |
| Retry-After | 头值超 maxDelay / deadline 剩余不足 | clamp / 按 deadline 收敛 |
| Retry-After | 无头 / 非数字 / 0 / 负数 | 原指数退避 |
| 轮换 | 3 key、429 | 失败标记后下次 resolve 返回下一 key；冷却期跳过 |
| 轮换 | 无失败时不轮换 / 全冷却 / 401 | 游标不动 / 回退第一个 / 同 429 |
| 隔离 | 全部默认关闭 | 既有全量测试零修改通过 + 未配置 provider 无 guard 实例（NOOP 直通断言） |
| 指标 | rejected / circuit-state / rate-limited | 计数与状态正确，gauge 不归零 |

## 6. 验收标准

- 未配置任何 guard：既有全量测试**零修改**通过；专项断言未配置 provider 无 ProviderGuard 实例（直通，acquire 不发生）。
- 异步入口任何路径不阻塞提交/传输线程；许可交接后续跑在载体池。
- 熔断 OPEN 期间零出站请求；HALF_OPEN 单探测且有超时；guard 拒绝不回灌窗口。
- 失败判定直调 `RetryPolicy.defaultRetryable`（单一事实来源，测试互证）。
- Retry-After 永不突破 maxDelay 与 deadline。
- key 值永不进日志/记录/指标/异常消息。
- 新增测试约 28–34 条；全量通过。

## 7. 风险

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| 异步等待队列复杂度 | 交接/唤醒/取消竞态 | 锁内直接交接（无真 release）+ 存活标记 + 交接重查熔断 |
| 限流等待占用调用线程（同步） | 调用方线程 park | 有界 waitMs + OVERLOAD 语义明确；默认关闭 |
| 熔断误开 | 网络抖动 | minimum-calls 门槛 + 半开探测 + 探测超时 |
| 窗口计数精度 | 切片边界撕裂、epoch 换片残余 | 计数级误差可接受，文档写明"近似" |
| gauge 弱引用归零 | Micrometer 陷阱 | starter 强持有状态对象 |
| 多 key 与重试叠加 | 旧 key 请求在途 | 轮换只影响后续 resolve；RetryRun 下次 attempt 天然新 key |

## 8. 与既有计划的关系

- 失败分类复用《夯实oneWithRetry》的 Kind 体系（新增 `CIRCUIT_OPEN`，additive）；OVERLOAD 语义对齐《夯实回调执行策略》。
- 指标挂接复用《夯实可观测性》模式（GuardMetrics no-op + starter 装饰）。
- 传输层改动是 5 个 HTTP 执行器的错误路径带出响应头（不改连接/流逻辑）；协议适配器零改动。

## 9. 审查记录（2026-10-25，独立子代理）

- **总体**：四项拍板全部成立；调研结论逐条核实为真（漏斗、Kind 体系、key 出口、Retry-After 零引用）；需先修订 4 处硬伤后实施。
- **设计缺陷 4（已修）**：F1 D4 粒度与挂点矛盾→按轮 acquire/release（异步在 whenComplete 头部）；F2 recordTerminal 锚点两处不成立（onDelta 异常泄漏许可、拿不到异常分类）→失败计数挂 fail()/同步 catch、释放挂每轮；F3 OPEN 用可重试的 503→新增 Kind.CIRCUIT_OPEN（不可重试）+ guard 拒绝不回灌窗口；F4 失败口径复述分叉→直调 defaultRetryable。
- **遗漏 11（已补）**：F5 自研闸门（Semaphore 无监听）；F6 队列容量/排队时限；F7 排队取消与熔断竞态；F8 探测超时；F9 epoch 换片；F10 轮换四细节（值匹配、仅失败推进、占位符、惰性冷却）；F11 执行器 5 模块（漏 jdk8）；F12 Retry-After 解析健壮性；F13 测试矩阵 4 缺口；F14 回归锁定改为"零修改+NOOP 断言"；F15 guard 归属。
- **表述 4（已改）**：F16 配置双根统一；F17"过半"歧义；F18 执行器与适配器混称；F19 语病。
- **建议 4（已采纳）**：F20 gauge 强持有；F21 每 attempt 一样本；F22 校验后 acquire；F23 轮换与重试协同说明。

## 10. 执行记录（2026-10-25，G0–G5 全部完成）

- **G0 骨架**：`guard/ProviderGuard`（自研闸门：`ReentrantLock + Condition + ArrayDeque`，release 锁内直接交接给队头存活 waiter，不做真 release；熔断状态机 + 6 片 epoch 环形窗口；探测超时在准入时判定，迟到探测结论作废）、`guard/GuardMetrics`（core 回调接口，NOOP 默认）、配置双根（`AiLiteConfig.Guard` 顶级 + `Provider.GuardCfg`）、`Kind.CIRCUIT_OPEN`（不可重试）。单测 25。
- **G1 网关按轮接入**：同步 `invoke` 在校验抛出点之后 acquire、`try/catch/finally` 归还；异步 `AsyncRun.next()` 拆 `acquireAsync`（快速路径同步回调，排队经载体池）+ `startRound`，transport future 的 `whenComplete` **头部**采样并归还（F1）；另修复一处审查未覆盖的泄漏口：不合作传输下 run 已收敛但 future 永不完成时 `whenComplete` 等不到——新增 `inFlightPermit` 引用 CAS + `abandonInFlightPermit`，挂 `failOverload`/`cancelledByListener`/`cancelledByFuture` 三条兜底路径。单测 8。
- **G2 熔断接入**：`RoundPermit.verdict` 直调 `RetryPolicy.defaultRetryable`（单一事实来源），429 单独出 `rate-limited` 回调；guard 拒绝（OVERLOAD/CIRCUIT_OPEN）不采样不回灌。单测 7。
- **G3 Retry-After**：`LlmCallException` 加 `retryAfterMs` 字段（5 参构造器 additive）与统一工厂 `httpError(message, status, retryAfterHeader)`（仅 429/503、仅正整数秒）；5 个执行器错误路径接线（okhttp / jetty×4 调用点（两处异步段补 `onHeaders` 捕获）/ webclient / jdk8 / ktor-cio）；同步 `oneWithRetry` 与异步 `RetryRun.onAttemptComplete` 均优先采用头值退避（min 头值/maxBackoff，再被 deadline 收口）。单测 7，5 个传输模块既有测试零回归。
- **G4 多 key 轮换**：`Provider.apiKeys` 列表（占位符与单 key 同口径，`${ENV:default}` 兜底）；`ModelResolver` 新增轮换状态（`AtomicInteger` 游标 + `AtomicLong[]` 冷却时间戳，惰性到期）、`apiKeyFor`（api-keys 优先）、`markKeyFailure`（值匹配索引、仅失败推进、全冷却回第一把）；网关失败标记挂异步 `fail()` 与同步 catch（只认 HTTP 429/401，guard 合成异常不标记）；key 冷却时长经 starter 从 `guard.key-cooldown-seconds` 传入。单测 9（含网关 429 → 重试 attempt 用新 key 的端到端）。
- **G5 装配与指标**：`MicrometerGuardMetrics`（rejected/circuit-state/rate-limited；gauge 状态对象由实现内 map 强持有，F20）；`AiLiteAutoConfiguration` 构建 guard 表（`buildProviderGuards`，只为配置了 concurrency 或熔断开启的 provider 建实例，排队超时定时器复用重试调度器）。**实施差异**：guard 表不做独立 Bean——Spring 对 `Map<K,V>` 参数注入的是"所有 V 类型 bean 收集的 map"而非 Map bean 本身，独立 Bean 会让网关拿到空表；改为 gateway 方法内构建，用户覆盖途径仍是自定义 LlmGateway Bean。装配测试 4（行为断言：全默认 NOOP 直通、OPEN 快速失败+指标、429 计数、并发限流 OVERLOAD）。
- **回归**：core 534（+60），全仓 805 执行 / 0 失败 / 1 个 Redis 集成用例无环境跳过。文档同步：README 状态段、`docs/配置说明.md`（§2.1 api-keys/guard 字段 + 新增 §3.3 出口保护）、`docs/testing/测试索引.md` 出口保护小节。
