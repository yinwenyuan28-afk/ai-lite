# 单次模型调用入口

> **本文在哪一层**：网关层（`LlmGateway` 的单次调用入口，主线故事）。
> **讲什么**：一次 `oneWithRetryAsync` 从发出到拿结果的完整旅程——出发准备、HTTP 收发、重试环、线程接力、出口保护。
> **不讲什么**：HTTP 客户端怎么配怎么换 → [HTTP客户端使用指南](guide-HTTP客户端.md)；重试策略参数细节 → [重试边界](desc-重试.md)；编排层 → [Workflow异步执行器使用指南](guide-Workflow异步执行器.md)。生词查 [术语表](ref-术语表.md)。

「单次模型调用」= 一次请求、一次响应、不执行工具。它是网关最薄的出口，也是所有编排逻辑（`AgentLoop`、`Workflow` 等）的地基。本文只讲这些入口；编排层另见对应文档。

**本文按渐进式组织，主线只有一条**：跟着一次 `gateway.oneWithRetryAsync(call, policy, cancel)` 从发出走到拿到结果。先给全景图，再逐站放大，最后才是参考手册。

| 读到哪 | 讲什么 |
| --- | --- |
| 第 1 节 | 全景：一次调用的一生（总图 + 名词白话表） |
| 第 2–4 节 | 沿主线逐站放大：出发准备 → HTTP 收发 → 失败后的重试环 |
| 第 5–6 节 | 换视角与对照：线程速查表；同步入口、协程入口长什么样 |
| 第 7–8 节 | 两个可选增强：出口保护（并发上限/熔断/多 key）、流式回调 |
| 第 9–11 节 | 参考手册：取消能保证什么、错误分类、调用记录、入口大全 |

只想选个入口用的，直接跳第 11 节。

---

## 1. 全景：一次 oneWithRetryAsync 的一生

```text
你的代码
  future = gateway.oneWithRetryAsync(call, policy, cancel)
     │
     ▼
【第一站｜你的线程】出发前的准备（几微秒，做完立刻返回）
     ① 查参数   ── biz 没登记、消息为空、模型不支持图片……→ 当场抛异常，不发请求
     ② 选模型   ── 按 profile 定供应商、模型、api key（配了多 key 时按游标取）
     ③ 领名额   ── 仅当配了并发上限才有这步（第 7 节）；没配 = 跳过
     ④ 递交请求 ── 把 HTTP 请求交给客户端，不等结果；方法返回 future
     │
     ▼
【第二站｜HTTP 客户端线程】真正的网络收发与响应解析
     │   响应一回来，先做两件微秒级小事：记熔断账（若启用）、还并发名额（若领过）
     │
     ├── 成功 → future 完成 → 你拿到 LlmResult
     │
     └── 失败 → 进入重试环（第 4 节）
                   还能重试？还有次数？没超总时限？
                   ├── 否 → future 异常完成（历次失败都附在异常上）
                   └── 是 → 按退避时间定个闹钟
                               │
                               ▼
                  【第三站｜重试线程 ai-lite-retry-*】闹钟响了发下一发
                      └── 发一个全新请求：重新 ①②③④
                            └── 又回到第二站……直到成功或放弃
```

三句话总结这张图：

- **你的线程只做几微秒的判断和递交**，拿到 future 就返回了，从不等待。
- **网络收发、解析都在 HTTP 客户端自己的线程上**；成功后 future 完成，你的线程被唤醒。
- **失败了由重试线程隔一阵子（退避）再发一次全新的请求**，直到成功、次数用尽或超过总时限。

`one` / `oneAsync` 看到的图没有那个环（只试一次）；流式入口多一条增量回调的岔路（第 8 节）；其余完全一致。

### 先把几个词说成人话

| 文中的词 | 意思 |
| --- | --- |
| `future`（`CompletableFuture<LlmResult>`） | 结果的占位凭据：方法立刻返回它，结果好了它被「完成」，失败也通过它交付 |
| 你的线程（提交线程） | 调用入口的那条线程，比如 Tomcat 的工作线程 |
| HTTP 客户端线程（传输线程） | HTTP 客户端自带的线程池（如 OkHttp Dispatcher），负责真正的网络收发 |
| 重试线程 `ai-lite-retry-*` | 框架的定时调度线程，负责「退避时间到，发下一发」 |
| 框架线程池 `ai-lite-*` | 框架自备的后台线程池，承接排队转接、调用记录落盘等杂活 |
| 名额（permit） | 并发上限的通行证：一次尝试领一张，响应回来就还；只在配了 `guard.concurrency` 时存在 |
| 退避（backoff） | 两次尝试之间的等待时间，默认指数增长并带随机抖动 |
| future 异常完成 | 失败的交付方式：`get()` 抛出异常，根因逐层解包保留在最里层 |

---

## 2. 第一站：你的线程——出发前的四步

```text
你的线程
   ① 查参数   LlmCall 里 biz 登记了吗？消息空吗？模型支持图片吗？工具列表合法吗？
              任何问题 → 当场抛 LlmCallException
   ② 选模型   按 profile 定：哪个供应商、哪个模型、哪把 api key
              （配了 api-keys 多 key 时，取当前游标指向的那把）
   ③ 领名额   只在配了 guard.concurrency 时存在（第 7 节展开）；没配 = 跳过
   ④ 递交请求 把请求交给 HTTP 客户端（非阻塞：只递交，不等待）
   返回 future ── 你的线程到此为止，立刻去干别的
```

三个要点：

- **校验失败是当场抛的**。即使是 `oneAsync` 这样的异步入口，校验异常也不会塞进 future，而是直接从方法调用里抛出来——一个 HTTP 请求都不会发。
- **② 是多 key 轮换发生的地方**。挨了 429/401 的 key 会被冷却并推进游标；每次尝试（含重试）都重新走 ②，所以重试自动换 key（第 7.4 节）。
- **④ 只是递交**。真正发请求、等响应是 HTTP 客户端线程的事（第 3 节）。从此刻起，你的线程与这次调用的关系只剩手里那个 future。

---

## 3. 第二站：HTTP 客户端线程——收发、记账、交付

```text
HTTP 客户端线程（如 OkHttp Dispatcher）
   │ 真正的 socket 收发、响应解析都在这条线程上
   │ （流式 inline 模式的 onDelta 回调也在这里，见第 8 节）
   ▼
响应回来了，最先做两件微秒级小事（比定结果还早）：
   ① 记熔断账（配了熔断才有）：把结局记进最近时间窗
        什么算「失败」与重试口径是同一份：传输错误、408/429/5xx、超时
        解析错、参数错不算失败（只计一次调用数，不增加失败数）
   ② 还并发名额（领过才有）：先来先到，直接留给队首；
        已取消的排队者自动跳过
   ▼
然后定结果：
   ├── 成功 → future 完成，你拿到 LlmResult
   └── 失败 → 进重试环（第 4 节）
```

- **等待响应期间，没有任何业务线程被占用**——这就是「Web 请求想释放 Tomcat 线程就换 `oneAsync`」的原理。
- **记账和还名额排在定结果之前**：无论成功、失败还是被取消，名额一定还。顺序反了的话，「取消先到、响应后到」的竞态会把名额永久占住。
- **调用记录的落盘不经过这条线程**：框架用后台线程异步写，写失败只记日志，不影响调用本身（第 10.2 节）。

---

## 4. 失败之后：重试环

`one` / `oneAsync` 默认只尝试一次，失败即终。需要重试用显式变体 `oneWithRetry` / `oneWithRetryAsync`（Kotlin 用 `LlmCoroutines.oneWithRetry`），三者共享同一套 `RetryPolicy` 配置。

### 4.1 环的完整一圈

```text
第 n 次尝试失败了
   │
   │ 失败当场先记两笔账（与要不要重试无关）：
   │    429/401 → 把当前这把 key 冷却 key-cooldown-seconds（下次 ② 自动换下一把）
   │    结局    → 记熔断账、还名额（第 3 节）
   ▼
还能重试吗？
   ├── 不能：400/401/403/404、参数/协议错、解析错、被取消、OVERLOAD、CIRCUIT_OPEN
   ├── 次数用尽：已达 policy.maxAttempts（默认 3）
   └── 超总时限：超过 policy.totalDeadlineMs（没配则不限制）
   ── 任一成立 ──→ 定下最终结果：future 异常完成，
                    历次失败都作为附属异常（suppressed）挂在最终异常上，排查不丢线索
   ▼ 还能
等多久？（退避）
   ├── 供应商回了 Retry-After（仅 429/503、正整数秒才认）→ 用它，但不超过 max-backoff
   └── 没回 → 指数退避 initial × multiplier^(n-1)，默认再乘 0.8~1.2 随机抖动防扎堆
   最后再对齐总时限：这次等待不会越过 deadline
   ▼
等（随时可被取消打断）
   ├── 同步版：你的线程分段睡（每 50ms 醒来看一次有没有取消）
   └── 异步版：注册定时器，没有任何线程在干等
   ▼
通知 onRetry 监听器（你在 policy 里配的回调，它抛异常也不影响重试）
   ▼
第 n+1 次尝试 = 一个全新请求：重新 ①②③④
   （重新选 key——刚冷却的自动跳过；重新领名额；单独再写一条调用记录）
```

三个容易忽略的点：

- **总时限有三道关卡**：出发前查、失败后查、等待前对齐——到点必停，等待永远不会悄悄吃掉总时限。
- **每次尝试的名额和 key 都是新的**：失败那次的名额在它失败时就还了；退避回来重新选 key，刚挨罚的那把被跳过。
- **重试会重复计费**：每次尝试都是真实请求、各写一条调用记录，token 按实际发生计。

### 4.2 第二发请求是谁发的

环图里「回到顶部重新 ①②③④」这一步，同步和异步的答案完全不同——这也是两种入口唯一的本质区别。

**同步 `oneWithRetry`：你的线程自己睡完、自己发。**

```text
你的线程（从头到尾就这一条）
   one(call) 失败 → 判断 → 算等待时间
      → 本线程分段睡眠（每 50ms 醒一次看有没有取消）
      → 睡醒 → 再次 one(call)：第二发就是你的线程自己发的
```

**异步 `oneWithRetryAsync`：HTTP 客户端线程只「定闹钟」，重试线程发第二发。**

```text
HTTP 客户端线程（第 1 次尝试的响应刚回来）
   │ 只做几步判断：能重试吗？等多久？
   └─ 把「到点发下一发」注册成定时器，本线程立即返回
              │
              │  等待期间：没有任何线程在干等
              ▼  退避时间到
重试线程 ai-lite-retry-*（第二发的发起者）
   ├─ 复查一遍：结果定了没？取消了没？超总时限没？
   ├─ 通知 onRetry 监听器
   └─ 发下一发：重新走 ①②③④，把请求递给 HTTP 客户端
        ├─ 有空闲名额 → 就在重试线程上递交（递交本身不等待）
        └─ 名额满 → 排队，轮到时由框架线程池 ai-lite-* 接力递交
              │
              ▼
HTTP 客户端线程 ── 第二次真正的网络收发在这里
   └─ 回来后再走一遍上面的循环……直到成功或放弃
```

三个要点：

- **为什么不让 HTTP 客户端线程直接发第二发？** 那是所有调用共享的线程池（OkHttp Dispatcher 默认 64 条），在上面等退避会把整个客户端的吞吐拖死。所以「何时发」交给定时器、「谁发」交给重试线程——**退避期间没有任何线程被占用**。
- **第一发是你的线程发的**。`oneWithRetryAsync` 的首次尝试就在你的线程上走完 ①–④ 并递交；只有第二发及以后才从重试线程起跑。
- **每一发都是全新请求**。不是「复用旧连接续跑」，而是一次全新的调用：重新校验、重新选 key（所以 key 轮换能生效）、重新领名额（所以并发上限按次计数）。真正复用的只有 HTTP 连接池，那是传输层的事。

### 4.3 等多久、什么时候停

- **可重试**（默认口径）：429、408、5xx、连接/读写超时、可识别的瞬时 IO 错误。
- **不可重试**：400/401/403/404、参数/协议错误、解析错误、内容审核拒绝、取消、出口保护拒绝（`OVERLOAD` / `CIRCUIT_OPEN`）。
- **Retry-After 的口径**：5 个 HTTP 执行器都会在非 2xx 的 429/503 响应上读这个头；只认**正整数秒**，HTTP 日期形式、0、负数、非数字一律忽略（退回指数退避）。值挂在 `LlmCallException.getRetryAfterMs()` 上，自定义重试策略同样拿得到。
- **覆盖范围**：非流式由 `oneWithRetry` / `oneWithRetryAsync` 覆盖；流式由 `oneByStreamWithRetry` 覆盖，默认**可重试窗口只到首个增量为止**——一旦 `onDelta` 收到过内容，即使失败本身属于可重试类别也立即停止，因为模型流式没有断点续传，重发会让客户端看到「前段 + 重发全文」的重复输出。若确实需要无视交付状态整段重发，显式传 `StreamRetryMode.ALWAYS`，并在 `onAttemptDiscarded` 里清掉已展示内容（详见 [重试边界](desc-重试.md) §4.3）。工具循环、编排层想重试就在外面自己包一层策略（每次尝试独立调用、独立记录）。
- **可定制项**（`RetryPolicy.builder()`）：次数、退避参数、抖动、总时限、自定义可重试判定、onRetry 监听器。

详见 [重试边界](desc-重试.md)。

---

## 5. 线程速查：谁在哪条线程上干什么

| 环节 | 异步（主线 `oneWithRetryAsync`） | 同步（`one` / `oneWithRetry`） |
| --- | --- | --- |
| ①② 查参数、选模型 | 你的线程（微秒级） | 你的线程 |
| ③ 领名额 | 你的线程判一下：有空位就领；没空位排队（不占线程） | 你的线程原地等，至多 concurrency-wait-ms（默认 2s） |
| ④ 递交请求 | 首次：你的线程；重试后：重试线程——递交不等待 | 你的线程（递交后原地开始收发） |
| 网络收发 + 解析 | HTTP 客户端线程 | 你的线程（阻塞式 I/O） |
| 记熔断账 + 还名额 | HTTP 客户端线程（响应回来的头几行） | 你的线程 |
| 重试的等待 | 定时器（不占任何线程） | 你的线程分段睡眠 |
| 第二发及以后的发起 | 重试线程 `ai-lite-retry-*` | 你的线程 |
| 排队转接、调用记录落盘 | 框架线程池 `ai-lite-*`（后台） | 框架线程池 `ai-lite-*`（仅落盘） |
| 流式 onDelta | inline：HTTP 客户端线程；io：专用回调线程（第 8 节） | 同左 |

三条线程纪律（框架内部的铁律，也是排查线程问题时的对表依据）：

1. **你的线程只做快事**：只判断和递交，从不等待——这是异步入口存在的意义。
2. **HTTP 客户端线程只做判断和转交**：记账、还名额、派活都是几步固定动作，绝不在上面睡眠或等待——它是所有调用共享的线程。
3. **等待不占线程**：重试退避靠定时器，协程版靠挂起（`delay()`）。

---

## 6. 对照：同步入口与协程入口

**同步 `one` / `oneWithRetry`：一条线程走到底。**

```text
你的线程（全程就这一条）
   ①②③ （名额不够时原地等待 ≤ concurrency-wait-ms，等不到抛 OVERLOAD）
   ④ transport.complete()：就在你的线程上发请求、等响应、收数据、解析
   ⑤ 记账还名额 → ⑥ 返回结果，线程继续干业务
   （oneWithRetry 再往外套第 4 节的环：等待和下一发也都在你的线程上）
```

代价说得直白些：**等响应期间，你的线程被占用——I/O 就发生在这条线程上**。Web 场景想尽快释放 Tomcat 线程，就用异步入口（controller 返回异步类型）。

**协程 `LlmCoroutines.one` / `oneWithRetry`：挂起，不占线程。**

- `one` 挂起协程而不是阻塞线程，等待期间载体线程被释放去跑别的协程。
- `oneWithRetry` 的退避用 `delay()` 挂起实现，同样不占任何线程。
- 取消语义与 Java 入口对齐：取消 `Job`、`withTimeout`、`handle.cancel()` 都有效。

---

## 7. 出口保护：并发上限、熔断、多 key（纯配置，默认全关）

出口保护 = 不改一行代码、纯靠配置，在网关出口替你守住供应商的三道闸。**默认全部关闭**：不配置时主线图里的 ③ 不存在，行为与旧版完全一致。

### 7.1 三件事各一句话

| 能力 | 一句话 | 默认 |
| --- | --- | --- |
| 并发上限 `guard.concurrency` | 同一供应商同时在途的请求最多 N 个，超了就等待/排队，等不到就拒绝 | 关（直通） |
| 熔断 `guard.circuit.*` | 最近时间窗失败率过高就拉闸一段时间，期间毫秒级拒绝、不发出站；冷却后放一个试探请求 | 关 |
| 多 key 轮换 `provider.api-keys` | 配多把 api key，挨了 429/401 的那把冷却几分钟，下次自动换下一把 | 关（单 key） |

（`Retry-After` 不用配置，一直生效，见 4.3。）

### 7.2 主线图里的「③ 领名额」展开看

```text
③ 领名额（配了 guard.concurrency 才有这步；没配直通）
   │
   ├─ 熔断器先过：拉闸中 → 当场拒绝 CIRCUIT_OPEN，不发请求
   │
   ├─ 有空闲名额 → 领一张，直通 ④
   │
   └─ 名额满：
        同步入口 → 你的线程原地等待，至多 concurrency-wait-ms（默认 2s）
                    等到了 → 领走；等不到 → 抛 OVERLOAD
        异步入口 → 调用进等待队列（不占任何线程，你的线程照常返回）
                    队列满（默认容量 100）或排队超时 → future 以 OVERLOAD 结束

名额什么时候还：响应一回来就还（第 3 节两件小事之一），
先来先到直接留给队首；已取消的排队者自动跳过
```

两条关键规则：

- **名额按尝试计，不按调用计**。重试一次就再领一次；工具循环的每一轮各领各还（工具执行期间不占名额）。所以「并发 = 8」限的是对供应商的在途**请求数**，不是业务调用数。
- **同步等待和异步排队共用一个闸门**，先来先到、互不插队；谁也不会饿死谁。

### 7.3 熔断器的三个状态

```text
正常（CLOSED，全部放行）
   │ 窗口内样本 ≥ minimum-calls 且失败率 ≥ failure-rate
   ▼
拉闸（OPEN，毫秒级拒绝一切，持续 open-seconds）
   │ 冷却时间到
   ▼
试探（HALF_OPEN，只放行 1 个请求，其余仍拒绝）
   ├── 试探成功 → 回到 正常，窗口清零重新累计
   └── 试探失败或试探超时 → 回到 拉闸，重新计时
```

「失败」的判定与重试口径是同一份（传输错误、408/429/5xx、超时）；解析错、参数错不算——熔断只关心「像供应商抖了」的失败。

### 7.4 多 key 轮换怎么转

```text
配了 api-keys: [k1, k2, k3]
   某次尝试用 k1 挨了 429/401（失败当场就标记，先于重试判定）
      → k1 冷却 key-cooldown-seconds，游标推进到 k2
      → 下次尝试（含重试）重新走 ②，自动拿到 k2
   全部 key 都在冷却 → 用第一把兜底（冷却到期自动恢复）
   没挨罚的 key 永远不会被轮走——轮换只在失败时发生，不是轮流用
```

对调用方完全透明：不用改代码，异常里也看不出换了 key，只有调用记录里每次尝试用的 key 不同。

### 7.5 被拒绝时你看到什么

- `Kind.OVERLOAD`：并发满且等不到（没发请求）。
- `Kind.CIRCUIT_OPEN`：拉闸中（没发请求，毫秒级返回）。
- 两者默认**不可重试**：重试它们只会空转（名额还是满的、闸还是拉着的）。
- 它们也**不计入熔断失败率**——否则「拉闸 → 拒绝 → 更像失败 → 更拉闸」会自我强化。
- 配了 Micrometer 后有对应指标：`ailite.llm.guard.rejected`（按原因）、`ailite.llm.guard.rate-limited`（429）、`ailite.llm.guard.circuit-state`（当前状态）。

| 能力 | 触发条件 | 入口看到的现象 | 默认 |
| --- | --- | --- | --- |
| 并发限流 | 供应商在途请求 ≥ `guard.concurrency` | 同步等待/异步排队；拿不到抛 `OVERLOAD` | 关（直通） |
| 熔断 | 窗口样本 ≥ `minimum-calls` 且失败率 ≥ `failure-rate` | 毫秒级 `CIRCUIT_OPEN` 拒绝，一个请求都不发 | 关 |
| Retry-After | 供应商 429/503 且带有效头值 | 重试退避改用头值（clamp 到 max-backoff 与 deadline） | 始终生效 |
| 多 key 轮换 | 配置 `api-keys` 且发生 429/401 | 下次解析自动换 key，调用方无感 | 关（单 key） |

字段含义与完整配置见 [配置参考](ref-配置.md) §3.3；设计取舍与审查记录见 [夯实限流熔断](plan/夯实限流熔断-plan.md)。

---

## 8. 流式入口：oneByStream / oneByStreamAsync

`oneByStream(call, onDelta, cancel)` / `oneByStreamAsync(call, onDelta, cancel)` 和 `one` 是同一个家族：①–⑤ 主线完全一致，结果字段、校验、异常和取消语义也完全一致，唯一区别是**边生成边回调 `onDelta`**。

增量回调走哪条路，由回调执行策略（`ai-lite.callback.mode`）决定：

```text
HTTP 客户端线程收到一段增量
   ├── inline（默认）：当场调用 onDelta —— 回调跑在 HTTP 客户端线程上，
   │                   你的回调要快，别在里面做阻塞操作
   └── io：转交专用回调线程（每个调用一个串行队列）
            保证顺序、且一个调用回调慢不拖累其他调用
            └── 你消费不过来把队列塞满 → future 以 OVERLOAD 结束并中止传输
                （保证你拿到的是「被限流」，不会被取消语义吃掉）
```

详见 [夯实回调执行策略](plan/夯实回调执行策略-plan.md)。

---

## 9. 取消：能保证什么

「支持取消」必须分层承诺，否则无法验收。

### 9.1 四层承诺 L1–L4

| 层级 | 含义 | 是否保证 |
| --- | --- | --- |
| L1 请求中止 | 在途 HTTP 请求被 abort，不再等待响应 | **保证（OkHttp）；JDK 8 传输见 9.2** |
| L2 不再推进 | 取消后不再发新请求、不重试、不产生新的业务推进 | **保证** |
| L3 无副作用 | 取消后不写任何业务数据 | 不保证；调用记录日志仍可能落库 |
| L4 供应商停止计费 | 服务端立即停止生成和计费 | 不保证；客户端无法控制 |

准确表述是：

> **所有单次模型调用入口都保证 L2；L1 取决于传输层实现。内置 OkHttp 传输保证 L1，JDK 8 传输在正文读取阶段是 best-effort。L3 / L4 不承诺。**

### 9.2 传输层差异（L1 的真相）

L1 不是协议层能单方面保证的能力，最终取决于 HTTP 客户端能否打断阻塞读：

| 传输 | 等响应头阶段 | 读正文 / SSE 阶段 |
| --- | --- | --- |
| OkHttp（推荐） | `Call.cancel()` 立即生效 | `Call.cancel()` 立即生效 |
| JDK 8 `HttpURLConnection` | `disconnect()` 通常能解除阻塞 | `disconnect()` 不保证唤醒正在阻塞的 `read()`，可能等到读超时或下一次数据到达 |

所以在 JDK 8 传输上，同步 `one(call, cancel)` 取消后最坏要等到读超时才返回。**对取消时延敏感的同步调用请使用 OkHttp 传输。** 网关的 L2 不受影响：一旦观察到取消，就不会再发新请求。

### 9.3 各种取消方式得到什么结果

| 取消方式 | 同步入口 | 异步入口 | 协程入口 |
| --- | --- | --- | --- |
| `LlmCancelHandle.cancel()` | 返回 `LlmResult(stopReason=ABORTED)` | future 正常完成，结果 `ABORTED` | `one` 正常返回 `ABORTED` |
| `future.cancel(true)` | — | future 进入 cancelled，`get()` 抛 `CancellationException`；底层 HTTP 仍被 abort | `await()` 抛 `CancellationException` |
| 协程 `Job.cancel()` | — | 底层 future 被取消，HTTP abort | `await()` 抛 `CancellationException` |

`oneWithRetryAsync` 复用同一套终态：handle 取消 → `ABORTED`；`future.cancel(true)` → JDK cancelled。区别是它还会撤掉已排队的退避定时任务，并阻止后续尝试。

### 9.4 几条细则

- **发请求前取消**：不发 HTTP，直接得到 `ABORTED`。
- **重复取消**：只有一个最终结果，abort 动作最多执行一次。
- **取消与完成的竞态**：要么正常结果，要么 `ABORTED` / `CancellationException`，不会同时出现两个结果。网关在正常完成分支也会再检查一次取消状态，不依赖传输层是否遵守 abort 约定。
- **取消后不重试**：`oneWithRetry` / `oneWithRetryAsync` 在观察到取消后不会再发起新尝试。
- **一个句柄只服务一次调用**：`LlmCancelHandle` 第一次 abort 后进入终态，不复位。重试内部每一发用的是全新句柄（对外仍由你传入的那个句柄统一取消）；你自己发起下一次调用时请创建新句柄。
- **`future.cancel(true)` 为什么能中断 HTTP**：`oneAsync` 即使没传句柄也会内部创建一个，把 future 取消转成 handle 取消——而不是只把 future 标记成已取消、连接继续跑到超时。

---

## 10. 错误分类与调用记录

### 10.1 错误分类

所有入口使用同一套校验和异常映射。

| 类别 | 典型原因 | 表现 | 是否发出 HTTP |
| --- | --- | --- | --- |
| 配置/校验错误 | 配置缺失、bizId 未登记、模型不存在、协议不支持、消息为空、图片模型不支持、工具列表非法 | 同步抛 `LlmCallException` | 否 |
| 传输错误 | 连接失败、读写超时、DNS、TLS | 同步入口抛；异步入口 future 异常完成 | 是 |
| 协议解析错误 | 响应不是合法 JSON、SSE 结构异常、字段缺失 | `LlmCallException`，保留根因 | 是 |
| 业务拒绝 | 400/401/403/404、内容审核拒绝 | `LlmCallException`，错误摘要已遮蔽 api key | 是 |
| 取消 | handle / future / Job 取消 | 见第 9 节 | 可能已发出 |
| 超时 | 连接或读取超时 | `LlmCallException`，根因是 `SocketTimeoutException` | 是 |
| 出口保护拒绝（并发） | 并发等待超时、异步排队超时、队列满 | `Kind.OVERLOAD`，**未发出 HTTP** | 否 |
| 出口保护拒绝（熔断） | 供应商连续失败拉闸 | `Kind.CIRCUIT_OPEN`，**未发出 HTTP**，毫秒级返回 | 否 |
| 回调队列溢出 | `io` 回调策略下 onDelta 消费不过来 | `Kind.OVERLOAD`，future 异常完成并中止传输 | 可能已发出 |

约定：

- 参数校验在**发请求前**完成，`oneAsync` 的校验异常也是**同步抛出**（第 2 节 ①）。
- 异步入口的失败统一通过 future 异常完成表达，根因逐层解包保留在最里层。
- 错误摘要写入调用记录前会遮蔽 api key，并截断到 200 字符。
- `OVERLOAD` 与 `CIRCUIT_OPEN` 都是**框架主动保护**，默认重试口径不认它们（第 7.5 节）；要区分「供应商故障」和「我方被限流」，看 `Kind` 即可。

### 10.2 调用记录与指标

每次单次调用写一条 `LlmCallRecord`（重试的每次尝试各写一条；`future.cancel(true)` 也会补一条 `aborted` 记录）。

| 字段 | 含义 |
| --- | --- |
| `stopReason` | 归一化停止原因；取消为 `aborted` |
| `success` | 正常停止记 1；`error` / `aborted` 记 0 |
| `inputTokens` / `outputTokens` / `totalTokens` | 各次尝试 usage 之和 |
| `latencyMs` | 各次尝试耗时之和 |
| `errorBrief` | 错误摘要，已遮蔽 api key，最多 200 字符 |

记录通过后台线程异步写入，写失败只记日志，不影响主调用。**取消后仍可能写一条 `aborted` 记录**——这是 L3 不保证的原因，属于预期行为。

starter 环境且 micrometer 在 classpath 时，这些记录同时聚合进 `ailite.llm.*` 指标（调用数、耗时、token），详见 [配置参考](ref-配置.md) §3.2；出口保护事件另有 `ailite.llm.guard.*` 指标（§3.3）。

---

## 11. 入口大全与选择建议

### 11.1 入口大全

| 入口 | 返回 | 是否阻塞 | 取消方式 | 适用场景 |
| --- | --- | --- | --- | --- |
| `LlmGateway.one(LlmCall)` | `LlmResult` | 阻塞调用线程直到响应或超时 | 只能靠超时 | 脚本、CLI、请求线程本来就该等待的同步业务 |
| `LlmGateway.one(LlmCall, LlmCancelHandle)` | `LlmResult` | 同上，但支持其他线程中止 | `handle.cancel()` | 同步代码里需要「用户点了停止」 |
| `LlmGateway.oneWithRetry(LlmCall, RetryPolicy, LlmCancelHandle)` | `LlmResult` | 阻塞，包含退避等待 | handle；退避可被打断 | 网络波动、需要显式重试的同步调用 |
| `LlmGateway.oneAsync(LlmCall)` | `CompletableFuture<LlmResult>` | 不阻塞，提交后立即返回 | `future.cancel(true)` | Web 请求线程、批量并发 |
| `LlmGateway.oneAsync(LlmCall, LlmCancelHandle)` | `CompletableFuture<LlmResult>` | 同上 | handle 或 `future.cancel(true)` | 需要把取消句柄传给上层（超时、断连、业务取消） |
| `LlmGateway.oneWithRetryAsync(LlmCall, RetryPolicy)` | `CompletableFuture<LlmResult>` | 不阻塞；退避在专用调度线程 | `future.cancel(true)` | 非阻塞接入 + 显式重试 |
| `LlmGateway.oneWithRetryAsync(LlmCall, RetryPolicy, LlmCancelHandle)` | `CompletableFuture<LlmResult>` | 同上 | handle 或 `future.cancel(true)` | Web 请求、批量并发下的显式重试 |
| `LlmCoroutines.one(gateway, call, cancel?)` | `LlmResult`（suspend） | 挂起协程，不占线程 | 取消 `Job` 或 `handle.cancel()` | Kotlin 协程代码 |
| `LlmCoroutines.oneWithRetry(gateway, call, policy, cancel?)` | `LlmResult`（suspend） | 挂起；退避用 `delay()` | 取消 `Job` / `withTimeout` / handle | Kotlin 协程里的显式重试 |
| `LlmCoroutines.oneAsync(gateway, call, cancel?)` | `CompletableFuture<LlmResult>` | 不阻塞 | 同 `oneAsync` | 给 Java 侧保留的静态方法形式 |

流式变体 `oneByStream` / `oneByStreamAsync` 见第 8 节。

### 11.2 怎么选

| 场景 | 选择 |
| --- | --- |
| 普通 Web 请求，业务线程可以等待 | `one` |
| Web 请求需要尽快释放 Tomcat 线程 | `oneAsync`，controller 返回异步类型 |
| 需要用户主动停止 | `one(call, handle)` 或 `oneAsync(call, handle)` |
| 网络波动、需要显式重试（同步） | `oneWithRetry(call, policy, handle)` |
| 网络波动、需要显式重试（非阻塞） | `oneWithRetryAsync(call, policy, handle)` |
| 供应商限流/抖动，想守住配额、快速失败 | 不改代码，配置出口保护（[配置参考](ref-配置.md) §3.3）；重试变体自动配合 Retry-After 与 key 轮换 |
| Kotlin 协程 | `LlmCoroutines.one` |
| 需要边生成边推给前端 | `oneByStream` / `oneByStreamAsync`（第 8 节） |
| 需要工具调用 | 不属于单次入口，用 `round` / `toolLoop` 或编排层 |

### 11.3 图片消息（base64）

单次入口支持多模态图片，不需要额外 API：

```java
LlmImage image = new LlmImage("image/png", base64); // 裸 base64 或 data URL 均可
LlmCall call = LlmCall.profile("biz", "main",
        Collections.singletonList(LlmMessage.user("描述这张图",
                Collections.singletonList(image))));
LlmResult result = gateway.one(call);
```

- 模型配置的 `input` 必须包含 `image`，否则发请求前抛 `LlmCallException`（第 2 节 ①）。
- 图片只允许出现在 `USER` / `OTHER` 消息；同一消息可带多张，顺序保持不变。
- 支持裸 base64 和 `data:<mime>;base64,<data>`；非 base64 的 data URL、空 MIME、非 `image/*`、空数据都会被拒绝。
- 三个协议分别序列化为 OpenAI `image_url`、Responses `input_image`、Anthropic `base64` source。
- 网关不压缩、不缩放、不校验大小；供应商的尺寸和字节限制由调用方负责。
- 图片不进入会话存储（`AgentSessionMessage` 不保存图片）；`AgentSessionLoop` 的首轮消息会被会话历史覆盖。需要多轮携带图片时，用单次入口自行维护消息。

### 11.4 两个补充定位

- `LlmCoroutines.oneAsync` 不是 suspend 函数，只是 `gateway.oneAsync(call, cancel)` 的静态转发，方便 Java 调用方统一从 `LlmCoroutines` 取值。0.x 保留，1.0 再评估是否移除；Kotlin 调用方应优先用 `LlmCoroutines.one`。
- `one` / `oneAsync` 默认只尝试一次；重试变体的边界见第 4 节。

---

## 12. 相关文档

- [API 使用指南](guide-上层调用API.md)
- [HTTP 客户端使用指南](guide-HTTP客户端.md)
- [架构说明](desc-架构总览.md)
- [用户级并发与取消](desc-用户级线程.md)
- [重试边界](desc-重试.md)
- [夯实单次模型调用入口](plan/夯实单次模型调用入口-plan.md)
- [夯实回调执行策略](plan/夯实回调执行策略-plan.md)（流式 onDelta 的回调池与完成屏障）
- [夯实限流熔断](plan/夯实限流熔断-plan.md)（出口保护的设计与审查记录）
