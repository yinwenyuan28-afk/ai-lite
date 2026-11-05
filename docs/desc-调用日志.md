# 调用日志是怎么记下来的

> **本文在哪一层**：网关层的旁路（`LlmGateway.record` → `AsyncLlmCallLogger` → `LlmCallStore`）。
> **讲什么**：一次单次调用如何变成一行记录——记录点在哪条线程、字段怎么填、什么时候写正文、落到哪两张表、以及哪些调用根本不留记录。
> **不讲什么**：调用本身的旅程（发出、重试、取消）见 [单次模型调用入口](desc-单次调用.md)；表名与缓存的完整清单见 [数据表与缓存](desc-数据表与缓存.md)；指标口径见 [配置参考](ref-配置.md) §3.2。生词查 [术语表](ref-术语表.md)。

一句话结论：**调用日志是网关的一条旁路。它不参与调用的成败，也不在调用线程上写库。记录点落在「本轮请求结束的那条线程」上，真正写库交给框架线程池，任何写库异常只留一行 warn。**

---

## 1. 全景：一次调用如何变成一行记录

```text
业务线程（Tomcat 工作线程 / 你的 main）
   │
   │  gateway.one(...) 或 gateway.oneAsync(...) 等入口
   ▼
【第一段｜校验与解析】LlmGateway.invoke / invokeAsync
   │   参数、bizId、协议、图片能力都在这段检查
   │   任何一项不过 → 当场抛 LlmCallException，一条记录都不写
   ▼
【第二段｜发请求并等结果】
   │   同步入口：transport.complete(...) 就地阻塞
   │   异步入口：transport.completeAsync(...) 递交后立刻返回
   ▼
【第三段｜记录点】LlmGateway.record(...)   ← 本轮请求刚结束的那条线程
   │   ① 填 LlmCallRecord（摘要字段，十几项赋值）
   │   ② 问 CallBodyPolicy：这一行要不要带输入输出？
   │   ③ 要 → CallBodyText 拼文本（遮密钥、截 8000 字）
   │   ④ 交给 AsyncLlmCallLogger.record(row)
   └───────────────────────────────────────────────┐
                                                     │
                                    AsyncLlmCallLogger 只做两件快事：
                                      ⑤ 生成 CALL_ID、CREATE_TIME（就在当前线程）
                                      ⑥ executor.io().execute(任务)  ← 递交即返回
                                                     │
                                                     ▼
【第四段｜落库】框架线程池 ai-lite-N
   │   ⑦ log.info("llm call bizId=... provider=... stop=...")
   │   ⑧ 配了 Micrometer → 指标 +1（先于落库）
   │   ⑨ store.insert(row)
   │        ├─ 可用 → INSERT 摘要表
   │        └─ 行里带了正文 → 再 INSERT 正文表
   └── 任何异常（含线程池拒绝）→ log.warn，绝不抛回业务
```

三句话总结：

- **记录点跟着「谁把这一轮跑完」走**：同步调用在业务线程记录，异步调用在传输层的回调线程记录。
- **写库一定不在记录点上**：`AsyncLlmCallLogger` 只生成 id 和时间戳，然后把任务丢进 `ai-lite-*` 线程池。
- **日志写在记录点、表写在框架线程**：这条 INFO 日志和 INSERT 都在同一个池任务里，所以它俩的先后是固定的（先日志后落库）。

---

## 2. 记录点：`LlmGateway.record`

整个框架只有这一个地方造 `LlmCallRecord`。所有入口（`one`、`round`、`toolLoop`、各种异步和流式变体）最终都收敛到它。

```java
private void record(Attempt attempt, int round, LlmStopReason stop, int success,
                    long latencyMs, String error) {
    LlmCallRecord row = new LlmCallRecord();
    row.setBizId(attempt.bizId);
    row.setProvider(attempt.model.getProvider());
    row.setModelId(attempt.model.getModelId());
    row.setApi(attempt.model.getApi());
    row.setProfile(attempt.profile);
    row.setThinkingLevel(attempt.model.getThinkingLevel().wire());
    row.setRoundNo(Integer.valueOf(round));
    row.setInputTokens(Integer.valueOf(attempt.input));
    row.setOutputTokens(Integer.valueOf(attempt.output));
    row.setTotalTokens(Integer.valueOf(attempt.total));
    row.setLatencyMs(Long.valueOf(latencyMs));
    row.setStopReason(stop.wire());
    row.setSuccess(Integer.valueOf(success));
    row.setErrorBrief(brief(error, attempt.model.getApiKey()));
    String level = callBodyPolicy == null ? CallBodyPolicy.NONE : callBodyPolicy.level();
    if (CallBodyPolicy.store(level, success)) {
        row.setInputBody(CallBodyText.input(attempt.transcript, attempt.model.getApiKey()));
        row.setOutputBody(CallBodyText.output(attempt.completion, attempt.model.getApiKey()));
    }
    logger.record(row);
}
```

这段代码有四条约束值得记住：

- **一行对应「一轮模型请求」**，不是一次业务调用。重试的每一次尝试、工具循环的每一轮，各写一行。
- **模型信息取自解析结果**（`attempt.model`），不是取原始 `LlmCall`。所以目录模式下改的模型 id、实际生效的思考档，记的是真值。
- **档位是一次记录问一次**，不是一次调用问一次。它的读库由 `JdbcCallBodyPolicy` 的 10 秒缓存挡着（[数据表与缓存](desc-数据表与缓存.md) §3.4）。
- **这里是「当前线程」**。`record` 本身没有任何异步，异步只发生在 `logger.record(row)` 内部。

### 2.1 谁在哪条线程上触发记录

| 入口 | 记录点所在线程 |
| --- | --- |
| `one` / `oneWithRetry` / `oneByStream` / `round` / `toolLoop` | 业务线程（`transport.complete` 就地返回后紧接着记） |
| `oneAsync` / `roundAsync` / `toolLoopAsync` / `oneByStreamAsync` | 传输层的完成回调线程：OkHttp 是 Dispatcher 线程，JDK 8 传输是它内部的阻塞线程池，默认 `completeAsync` 是 ForkJoinPool |
| `oneWithRetryAsync` | 同上——它的每次尝试都委托给一次 `oneAsync`，外层 `RetryRun` **不额外写记录** |
| 取消收敛（`cancelledByListener` / `cancelledByFuture` / `failOverload`） | 取消发生的线程（业务线程、定时器线程或传输线程） |
| guard 排队放行（`acquireAsync` 的 `onGranted`） | 有空位时在递交线程同步回调；排队时在 `executor.io()` 的池线程上 |

注意最后两行：**取消和出口保护拒绝也会留记录**，即使一个 HTTP 请求都没发出去。所以「表里有几行」不等于「供应商收到几个请求」。

---

## 3. 落库：`AsyncLlmCallLogger`

```java
public void record(final LlmCallRecord row) {
    if (row.getCallId() == null) {
        row.setCallId(UUID.randomUUID().toString().replace("-", ""));
    }
    if (row.getCreateTime() == null) {
        row.setCreateTime(new Date());          // 时间戳是「这轮结束时」，不是 INSERT 那一刻
    }
    try {
        executor.io().execute(new Runnable() {
            public void run() {
                try {
                    log.info("llm call bizId={} provider={} model={} api={} round={} "
                                    + "stop={} success={} tokens={} latencyMs={}", ...);
                    store.insert(row);
                } catch (Throwable ex) {
                    log.warn("llm call record failed bizId={} {}", row.getBizId(), ex.toString());
                }
            }
        });
    } catch (RuntimeException ex) {
        log.warn("llm call record rejected bizId={} {}", row.getBizId(), ex.toString());
    }
}
```

四个设计点：

- **CALL_ID 与 CREATE_TIME 在递交前就定好**，在记录点所在线程上生成。所以时间戳反映的是这一轮结束的时刻，即使线程池排了几百毫秒队。
- **`execute` 的外层 try/catch 是给线程池拒绝用的**。默认拒绝策略是 `abort`，队列（默认 1000）满了会抛 `RejectedExecutionException`，这里吞掉并 warn。配 `caller-runs` 时会退化成在调用线程上同步执行。
- **任务内层的 `catch (Throwable)`** 覆盖落库失败、JSON 解析失败、表不存在等一切情况。写不进去只影响可观测性，不影响调用结果。
- **一条 INFO 日志 + 一次 INSERT 是同一个任务**。日志先出，落库后到；两者不会一条有一条没有（除非落库那步抛异常）。

线程名取决于 `ai-lite.executor.mode`：

| `mode` | 写库线程 | 备注 |
| --- | --- | --- |
| `platform`（默认） | `ai-lite-1`、`ai-lite-2`…（核心数起步，最大 2×核数） | 队列默认 1000，满了按 `rejection-policy` 处理 |
| `direct` | **就在记录点那条线程上直接执行** | 会阻塞当前线程；只适合测试或明确不需要异步的场景 |
| `virtual` | 虚拟线程 | JDK 21 模块 |
| `coroutines` | 协程调度器线程 | 需要 `ai-lite-executor-coroutines` |

`ai-lite.executor.mode=direct` 时这条旁路不再异步：`store.insert` 变成同步 JDBC，落库延迟会直接加在调用线程上。生产环境不建议这么配。

---

## 4. 线程模型：三条线程各管一段

以异步入口为主线（同步入口把「传输线程」换成业务线程即可）：

```text
① 业务线程
   gateway.oneAsync(call)
     ├─ 校验参数、解析模型                  ← 不过就抛，零记录
     ├─ 递交 transport.completeAsync()      ← 不等待
     └─ 返回 future

② 传输回调线程（OkHttp Dispatcher / JDK8 池 / ForkJoinPool）
   响应回来 → 网关的 whenComplete 回调
     ├─ 记熔断账、还并发名额
     ├─ LlmGateway.record(row)              ← 记录点在这里
     │    ├─ 填摘要字段
     │    ├─ 问档位（10 秒缓存，命中零 SQL）
     │    └─ 生成 CALL_ID / CREATE_TIME
     ├─ executor.io().execute(...)          ← 只递交，不写库
     └─ future.complete(result)             ← 业务线程被唤醒

③ 框架线程池 ai-lite-N
   ├─ log.info("llm call ...")
   ├─ Micrometer 指标（若启用）
   └─ JdbcLlmCallStore.insert(row)          ← 真正的 JDBC 往返
        ├─ INSERT 摘要表（充分必要）
        └─ 有正文 → INSERT 正文表（可选）
```

| 环节 | 哪条线程 | 干什么 | 能不能慢 |
| --- | --- | --- | --- |
| 校验 + 解析 | 业务线程 | 查 bizId、选模型、查目录缓存 | 不能，几微秒 |
| 网络收发 | 传输线程 | socket 读写、SSE 解析 | 本来就在等 |
| 填记录对象 | 记录点线程（业务/传输） | 十几项赋值 + 一次档位读取 | 不能，必须快 |
| 写库 | `ai-lite-N` | 一条 INFO + 两条 INSERT | 可以慢，最多排队 1000 |

**为什么记录对象要在记录点当场填完**：`attempt` 里的累积用量和会话消息会被后续轮次继续改写。留到线程池里再读，拿到的是「已经变了的样子」。所以拷贝动作必须在记录点同步做完，异步只负责搬运。

**和 `ai-lite-retry-*` 的关系**：重试调度线程只负责「到点再发一次」。它自己不发请求、也不写记录；每次尝试的记录仍是那次 `oneAsync` 在传输线程上写的。

**和 `ai-lite-callback-*` 的关系**：那是流式 `onDelta` 的独立回调池（`ai-lite.callback.mode=io` 时才有）。它和调用日志无关——日志不走那个池。

---

## 5. 一行记录写了什么

### 5.1 摘要字段

| 列 | 来源 | 说明 |
| --- | --- | --- |
| `CALL_ID` | `AsyncLlmCallLogger` 生成 | 32 位无横线 UUID。**每行独立**，重试的各次尝试不共享 |
| `BIZ_ID` | `LlmCall.getBizId()` | 非空，表上是 `NOT NULL` |
| `PROVIDER` / `MODEL_ID` / `API` | 解析后的 `ResolvedModel` | 目录模式下是最终生效值 |
| `PROFILE` | `LlmCall.getProfile()` | 只有用 profile 入口才有值 |
| `THINKING_LEVEL` | 模型解析后的档位 | 写 wire 值（`off`/`low`/`high`…） |
| `ROUND_NO` | 本轮序号 | 语义见 §5.4，**是最容易看错的一列** |
| `INPUT_TOKENS` / `OUTPUT_TOKENS` / `TOTAL_TOKENS` | `Attempt` 的累积值 | 见 §5.3 |
| `LATENCY_MS` | 本轮耗时 | 见 §5.3 |
| `STOP_REASON` | `LlmStopReason.wire()` | `stop`/`length`/`toolUse`/`error`/`aborted`/`maxRounds`/`contextLengthExceeded` |
| `SUCCESS` | `success(stop)` | 见 §5.2 |
| `ERROR_BRIEF` | 异常消息 | 换行压成空格、密钥替换成 `***`、截 200 字 |
| `CREATE_TIME` | 记录点时刻 | 不是 INSERT 时刻 |

**不含**：提示词原文、图片、完整请求体、完整响应体、api key。要这些只能开正文档位，且正文里也只有消息文本（图片只留 `[图片]` 占位）。

### 5.2 `SUCCESS` 的口径

```text
stop = error / aborted / maxRounds   → SUCCESS = 0
stop = stop / length / toolUse       → SUCCESS = 1
```

两条反直觉的地方：

- **`length` 算成功**。被输出上限截断的调用仍然是「供应商正常返回了」，只是没写完。要看是不是截断，得看 `STOP_REASON = 'length'`。
- **`toolUse` 算成功**。工具循环的中间轮是正常推进，不算失败。

补充一点：枚举里还有一个 `contextLengthExceeded`，`success()` 也把它算成功，但**当前三个内置协议适配器都不会产生这个值**——上下文超限目前表现为供应商返回的 HTTP 4xx（记成 `error`）。这一列留给以后的细分。

所以「管理页上成功率」不等于「回复质量正常率」。要抓截断，按 `STOP_REASON` 过滤。

### 5.3 token 与耗时的口径不一样

- **token 是累积的**。`Attempt.add()` 每轮把 usage 累加进同一个 `Attempt`，`record` 读的是累积值。所以工具循环第 2 轮的记录里，`TOTAL_TOKENS` 是第 1 轮 + 第 2 轮的和。测试 `LlmGatewayToolLoopRecordTest#syncToolLoopRecordsEachRoundWithCumulativeUsage` 把这个行为钉住了（第 1 行 3、第 2 行 12）。
- **耗时是本轮的**。`record` 收的是 `latencyMs` 参数，不是 `attempt.latencyMs`（那个才是累积值，只进 `LlmResult`）。
- **同步入口的耗时含排队**。`started` 打在 `guard.acquireSync()` 之前，所以并发满时原地等的 2 秒会算进 `LATENCY_MS`。异步入口的 `started` 打在 `completeAsync()` 之前，但排队等待发生在 guard 的 `acquireAsync` 里、早于这行，所以排队的时长不进这轮耗时；异步排队超时走 `fail` 路径，`latencyMs` 传 0。

想把「业务觉得慢」和「供应商慢」分开，就要拿 `LATENCY_MS` 对比供应商侧日志；含排队的那些行会明显偏高。

### 5.4 `ROUND_NO` 的坑

同一列在不同入口下含义不同：

| 入口 | 行数 | `ROUND_NO` |
| --- | --- | --- |
| `one` / `oneAsync` | 1 | 正常完成时为 1 |
| `round` / `roundAsync` | 1 | 正常完成时为 1 |
| `toolLoop` / `toolLoopAsync` | 内部每轮一行 | 1、2、3…递增 |
| `oneWithRetry` / `oneWithRetryAsync` | 每次尝试一行 | **每次尝试都从 1 重新开始** |
| `AgentLoop` 的一次 run | 它自己每轮调一次 `round`/`roundAsync` | **每行都是 1** |
| 任何入口在发请求前就取消 | 1 | **0**（一轮都没跑完） |

由此带来两个排查陷阱：

- **重试的多次尝试没法用 `ROUND_NO` 区分**，也没法用 `CALL_ID` 串起来（每行一个新 id）。要还原「一次业务调用打了几发」，只能按 `BIZ_ID` + 时间连续去人工归组，或者自己给 `LlmCall` 打业务侧的追踪号。
- **`AgentLoop` 的多轮在表里全是 `ROUND_NO=1`**，因为编排层是「每轮调一次网关」，网关每次只看到一轮。它和 `toolLoop` 的 `1..N` 不是一回事。

另外，`ROUND_NO = 0` 几乎总是「预取消」：句柄在进入循环前就已经取消，所以一轮都没发。看到 0 就先怀疑调用方把句柄复用到了第二次调用上（一个句柄只服务一次调用）。

`ailite.llm.rounds` 这个指标（只统计 `roundNo > 1`）因此只对 `toolLoop` 有意义，对 `AgentLoop` 和重试都是 0。

---

## 6. 正文：什么时候写、写什么

三档，存在设置表 `AI_LITE_ADMIN_SETTING` 的 `call-body` 键里，管理页「调用日志」顶部切换：

| 档位 | 写正文的时机 | 典型用途 |
| --- | --- | --- |
| `none`（默认） | 永不写 | 只要计数和耗时 |
| `failure` | 只有 `SUCCESS = 0` 的行 | 抓故障现场，不带日常成本 |
| `all` | 每一行都写 | 短期排障、效果评估 |

注意 `failure` 判的是 `SUCCESS = 0`，不是「HTTP 失败」。所以**取消（`aborted`）、轮数用尽（`maxRounds`）、出口保护拒绝也都会被当作"失败"写正文**。取消那些行的正文里可能是半截回复，看的时候别当成完整结果。

写进正文表的内容由 `CallBodyText` 拼：

- **输入**：把这一轮实际发给模型的消息按 `role: 文本` 拼起来，图片只写成 `[图片]` 占位。
- **输出**：正文 + 换行 + `thinking: 思考内容`（有思考才有）。
- **两条硬约束**：出现 api key 就替换成 `***`；超过 8000 字直接截断（`CallBodyText.LIMIT`）。

所以正文也不是「原始报文」：它是可读文本，不是能重放的 JSON。要复现请求形状，用管理页「测试调用」里的「这次实际发了什么」。

**档位读失败按 `none` 处理**，并且只 warn 一次（消息里带表名）。这是刻意的：这张表是可选功能，读不到不该把应用搞挂；但也不能一声不响，否则管理员会以为开了全文记录、实际一条没记。

---

## 7. 两张表

| 表 | 默认名 | 改名配置 | 内容 |
| --- | --- | --- | --- |
| 摘要表 | `AI_LITE_LLM_CALL` | `ai-lite.store.jdbc.table-name`，**无默认值，必填** | 一行一轮的计数、耗时、停止原因、错误摘要 |
| 正文表 | `<摘要表>_BODY` | `ai-lite.store.jdbc.body-table-name`，可不配 | 输入输出文本，只在开档位时写 |

`JdbcLlmCallStore.insert` 的顺序是**先摘要、后正文**，两条独立的 `jdbc.update`，没有事务：

```java
jdbc.update("INSERT INTO " + table + " (...16 列...) VALUES (...)");
if (record.getInputBody() == null && record.getOutputBody() == null) {
    return;                                  // 没正文就到此为止，一条 INSERT
}
jdbc.update("INSERT INTO " + bodyTable + " (CALL_ID, INPUT_TEXT, OUTPUT_TEXT) VALUES (?, ?, ?)");
```

后果要说清楚：**摘要写成功、正文写失败时，摘要行会留在表里，正文永远是空的**。这类失败只会表现为一行 `llm call record failed` 的 warn，界面上看不到任何异常。所以「有记录但正文一直空」的第一嫌疑是正文表不存在或表名不一致。

正文表名跟随摘要表名推导，正是为了消灭这种半改名状态（`CallLogTables`）。写侧（`ai-lite-store-jdbc`）和读侧（`CallLogQuery`）都调同一个推导方法，不会各拼一份 `+ "_BODY"`。

建表脚本：`ai-lite-store-jdbc/src/main/resources/ai-lite-llm-call.sql`（demo 里对应 `schema.sql`）。

---

## 8. 开关与装配

### 8.1 `LlmCallStore` 是谁

按优先级从高到低：

1. **你自己定义的 `LlmCallStore` Bean** —— `@ConditionalOnMissingBean` 让位
2. **`ai-lite-store-jdbc` 的 `JdbcLlmCallStore`** —— 引入该模块且容器里有 `JdbcTemplate` 时注册
3. **`NoopLlmCallStore`** —— starter 的兜底，只写 INFO 日志不落库

装配顺序有硬约束：`AiLiteStoreJdbcAutoConfiguration` 必须排在 `AiLiteAutoConfiguration` 之前，否则兜底的 Noop 先占位，JDBC 实现永远不生效。这个顺序写在注解上，见源码注释。

`ai-lite.store.jdbc.enabled=false` 可以彻底关掉 JDBC 记录，回到只有 INFO 日志的状态。

`table-name` 没配会**启动失败**，不是静默降级。理由：写入失败会被异步日志吞掉，宁可启动就炸，也不要几周后才发现账单表一直是空的。

### 8.2 指标是「装饰」不是「替换」

`MicrometerLlmCallStore` 包在真实 store 外面，网关拿到的引用是被装饰过的那个：

```text
LlmGateway → MicrometerLlmCallStore → JdbcLlmCallStore（或你自定义的）
                    │
                    └─ 先 observe（calls / duration / tokens / rounds）
                       再 delegate.insert（还是原来那条落库路径）
```

它**不夺 Bean**：容器里的 `LlmCallStore` 仍然是你或 `ai-lite-store-jdbc` 注册的那个，装饰只发生在传给网关的那一次引用上。指标更新全部包在 try/catch 里，指标故障不影响记录写入。

### 8.3 正文档位是怎么进网关的

`AiLiteAutoConfiguration` 用 `beanFactory.getBeanProvider(CallBodyPolicy.class).getIfAvailable()` 取容器里的实现，`gateway.setCallBodyPolicy(policy)`。取不到就是 `null`，等价于 `none`。

`JdbcCallBodyPolicy` 自己带 10 秒 TTL 缓存，`save()` 写完直接把缓存改成新值，所以本机改档位立刻生效。跨节点最长 10 秒窗口，需要立即同步就调 `invalidate()` 做广播。

---

## 9. 怎么读

- **管理页**：左侧「调用日志」标签。顶部是档位切换，下面是按业务 / 供应商 / 模型 / 结果过滤的分页表（每页上限 200）。点「详情」看单条，开了正文才有输入输出两块。
- **HTTP 接口**：`GET /ai-lite/admin/api/calls`（分页查询）、`GET /ai-lite/admin/api/calls/{callId}`（单条）、`GET|PUT /ai-lite/admin/api/calls/body-level`（档位）。完整形状见 [管理接口参考](ref-管理API.md)。
- **SQL**：直接查摘要表。按 `BIZ_ID` + `CREATE_TIME` 排序，是还原一次多轮调用最省事的办法。

`CallLogQuery` 的读侧有一个细节：正文是**整页一次 `IN` 查询**取回来的，不是每行查一次。表不存在时先探一次然后静默跳过，不会让整页查询失败。

---

## 10. 哪些调用不留记录

| 场景 | 有没有记录 | 原因 |
| --- | --- | --- |
| 参数 / 配置校验失败（bizId 未登记、消息为空、图片模型不支持、协议不存在） | **没有** | 在 `Attempt` 创建之前就抛了 |
| 发请求前取消（句柄已取消） | **有**，`stop=aborted`、`success=0` | 取消是合法终态，要留痕 |
| 并发上限拒绝、熔断拉闸（`OVERLOAD` / `CIRCUIT_OPEN`） | **有**，`stop=error`、`success=0` | 一个 HTTP 都没发，但仍然是一轮尝试。同步入口的 `LATENCY_MS` 是排队等待的时长，异步入口是 0；熔断立即拒绝时两者都是 0 |
| 正常完成 / 失败 / 超时 | 有 | — |
| 工具循环的每一轮中间态 | **有**，`stop=toolUse`、`success=1` | 每轮独立一行 |
| 管理页「测试调用」（`POST /ai-lite/admin/api/providers/{name}/models/{modelId}/try`） | **没有** | 它直接调 `LlmTransport`，完全绕开 `LlmGateway`，不经过记录旁路 |
| 管理页「测试连通」（同步远端模型列表） | **没有** | 同上，走的是 `ModelSyncClient` |
| 提示词渲染 | 没有 | 渲染发生在调用方，网关不知道有提示词这件事 |
| `AgentLoop` / `Workflow` / `SessionCompactor` | 有，但都是它们内部调网关产生的 | 编排层自己不加记录，只复用同一旁路 |

最后两行是理解「记录粒度」的关键：**记录粒度永远是一轮模型请求**，编排层再复杂也是拆成一次次网关调用。

管理页测试调用不留记录这点值得单独强调：在页面上点「发起调用」验证模型通不通，**不会**在调用日志里留下任何一行。想看它，只能看服务端日志或响应里的「这次实际发了什么」。

---

## 11. 排查清单

| 现象 | 先查什么 |
| --- | --- |
| 表里一行都没有 | ① 有没有引入 `ai-lite-store-jdbc`；② `ai-lite.store.jdbc.enabled` 是不是 false；③ `ai-lite.store.jdbc.table-name` 配了没（没配会启动失败，所以能启动说明配了）；④ 搜日志 `llm call record failed` / `llm call record rejected` |
| 调用成功了，但没有对应行 | 确认走的是 `LlmGateway` 入口。管理页「测试调用」不写记录 |
| 有摘要没正文 | ① 档位是不是 `none`；② 正文表 `<主表>_BODY` 是否真存在；③ 日志里有没有 `llm call record failed` |
| 正文只有前 8000 字 | 正常，`CallBodyText.LIMIT` 截断。不是丢数据 |
| `SUCCESS=1` 但回复明显不完整 | 看 `STOP_REASON` 是不是 `length`。`length` 记成功 |
| 一次调用出现好几行 | ① 用了 `oneWithRetry*`（每次尝试一行）；② 用了 `toolLoop`（每轮一行）；③ 用了 `AgentLoop`（每轮一行） |
| `ROUND_NO` 全是 1 | 用 `AgentLoop` 或重试入口时是正常的，见 §5.4 |
| 耗时明显偏高 | 同步入口的 `LATENCY_MS` 含并发排队等待，见 §5.3 |
| 改档位后另一个节点没变 | 10 秒 TTL，调 `invalidate()` 可立即同步 |
| 想知道某次调用具体发了什么 | 网关表里没有报文，只有文本正文。用管理页「测试调用」的请求预览，或抓传输层日志 |

### 已知缺口

- **没有清理任务**。摘要表和正文表都只增不删，没有 TTL、没有删除接口。长期运行要自己在库里做归档或分区。
- **没有跨尝试的关联 id**。`CALL_ID` 每行独立，重试 / 多轮无法用一列串起来。需要关联时按 `BIZ_ID` + 时间归组，或在业务侧自己生成追踪号塞进 `bizId`。
- **落库失败不重试**。写失败就是丢了，只有一行 warn。对账单完整性有要求时，要么自己实现 `LlmCallStore` 加队列和重试，要么直接落消息队列。
- **正文不是原始报文**。合成文本，不能重放。
- **自定义 `CallBodyPolicy` 不要抛异常**。`record` 里这一句没有 try/catch，抛出会打断「先记录再完成 future」的顺序。内置的 `JdbcCallBodyPolicy` 已经把所有失败收在内部，返回 `none`。

---

## 12. 相关文档

- [单次模型调用入口](desc-单次调用.md)：调用本身怎么走、重试环、线程接力、取消承诺
- [数据表与缓存](desc-数据表与缓存.md)：10 张表的用途与改名规则、档位缓存
- [HTTP 传输层原理](desc-HTTP传输.md)：记录点所在的那条传输线程是谁
- [配置参考](ref-配置.md)：`ai-lite.store.jdbc.*`、`ai-lite.executor.*`、`ai-lite.metrics.llm-enabled`
- [管理接口参考](ref-管理API.md)：调用记录的三个查询接口
- [架构说明](desc-架构总览.md)：模块拓扑与分层
- [可观测性（LLM 指标）](plan/010-可观测性LLM指标.md)：指标出口的设计记录
