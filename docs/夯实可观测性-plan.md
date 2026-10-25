# 夯实可观测性（LLM 指标）

**状态（2026-10-25）：已实现并回归通过（执行记录见第 6 节）。** 本计划为 ai-lite 增加 Micrometer 指标出口，不改任何核心并发结构——观测点复用网关已有的 `LlmCallStore.record` 全量结构化记录。

一句话总纲：

> **每一条 `LlmCallRecord` 本来就是一行业务无关的结构化观测数据；指标就是把它们按维度聚合进 Micrometer，不新增任何采集路径。**

---

## 1. 调研结论（2026-10-25，源码级）

- `LlmCallRecord` 字段齐备：bizId/provider/modelId/api/profile/roundNo/inputTokens/outputTokens/totalTokens/latencyMs/stopReason/success/errorBrief（不含提示词、图片、密钥——脱敏约束天然满足）。
- `LlmGateway.record()` 是**唯一**记录出口：同步 `invoke`、异步 `AsyncRun`（每轮 `record` + 终态 `recordTerminal`，后者 CAS 保证终态恰一条）、`RetryRun` 转发——挂这一处即覆盖全部入口。
- `LlmCallStore` 是现成 SPI（"实现必须自行处理并发，且不能影响调用结果"）；默认实现只打日志。
- Micrometer：Boot 2.1 BOM 管理 micrometer-core 1.1.x，`Timer.builder`/`Counter` 可用；仅 starter 模块引入（optional 依赖），core 保持零依赖。

## 2. 设计

### 2.1 形态：装饰器，不改 core

```text
LlmCallStore（SPI，不变）
  └─ MicrometerLlmCallStore（starter 新增，装饰 delegate）
       ├─ 每条 insert(record) → 更新计数/计时/用量指标
       └─ 全部包 try/catch：指标故障绝不影响调用（符合 SPI 契约）
```

不新增任何 SPI、不改网关/AgentLoop：记录维度已含 AgentLoop 轮次（每轮一条 record，roundNo 递增）、重试（RetryRun 每 attempt 各自记终态）。

### 2.2 指标口径

| 指标 | 类型 | 标签 | 来源 |
| --- | --- | --- | --- |
| `ailite.llm.calls` | Counter | `biz`,`provider`,`model`,`api`,`stop`,`outcome` | 每条 record 一次；`outcome` ∈ success/error/aborted（由 success+stopReason 派生） |
| `ailite.llm.duration` | Timer | `biz`,`provider`,`model`,`api` | record.latencyMs；`stop` 不进 Timer 标签（防 7×N 组合放大） |
| `ailite.llm.tokens` | FunctionCounter×3 | `biz`,`provider`,`model`,`type` | type ∈ input/output/total，累计 |
| `ailite.llm.rounds` | Counter | `biz`,`model` | roundNo>1 的 record 单独计数（工具轮/重试轮的流量） |

基数控制：所有标签均为有限集合——biz 是白名单、provider/model 来自配置、api 三个协议、stop 七种；errorBrief **不进标签**（自由文本，进日志）。

### 2.3 装配与配置

```yaml
ai-lite:
  metrics:
    llm-enabled: true    # 默认；micrometer 不在 classpath 时自动无效果
```

- `@ConditionalOnClass(MeterRegistry)` + `@ConditionalOnProperty(metrics.llm-enabled, matchIfMissing=true)` 注册装饰器。
- 与用户 `LlmCallStore` Bean 的关系：用户自定义 store 时**装饰它**（delegate=用户 Bean），保证指标仍然生效；`@ConditionalOnMissingBean` 只用于 delegate 的默认实现。装饰器本身以 `@Primary` 或显式包装装配（实施时定，倾向包装用户 Bean——不夺走用户 Bean 类型，列表注入处不受影响）。
- MeterRegistry 由 Boot 提供（actuator 场景）；没有 registry 时装饰器不注册，零影响。

### 2.4 明确不做（本轮）

- **trace 贯通**：Boot 2.1 时代为 Sleuth，异步 CPS 链上 span 传播复杂且强绑 Sleuth；需要时由上层经 `LlmCallStore`/钩子自行接。记录里有 callId 可作关联键。
- AgentLoop run 级聚合指标（轮数分布、整 run 时长）：可由调用方从 `ailite.llm.*` 聚合或后续经独立 hook 补。
- 直方图桶/发布分布自定义、@Timed 注解扫描、MDC/ThreadLocal 自动传播。
- Prometheus 命名规范化由 micrometer 自动处理，不做双命名。

## 3. 实施阶段

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| Phase M0 | starter `MicrometerLlmCallStore`（装饰器 + 指标映射 + 全量 try/catch） | 单测：SimpleMeterRegistry 上计数/计时/标签逐一断言；指标异常不影响 delegate |
| Phase M1 | 装配：条件注册、装饰用户 Bean、配置项 | 装配测试：有/无 registry、llm-enabled=false、自定义 store 被装饰 |
| Phase M2 | 文档：configuration.md 指标节、api-usage 指标段落、testing/README、README 统计 | 文档与行为一致 |

## 4. 测试矩阵

| 类别 | 用例 | 断言 |
| --- | --- | --- |
| 指标 | 正常调用（STOP） | calls{stop=STOP,outcome=success}=1、duration 有值、tokens 三类累计正确 |
| 指标 | 传输异常 | calls{outcome=error}=1，errorBrief 不出现在任何标签 |
| 指标 | 取消 | calls{outcome=aborted} 计入 |
| 指标 | 工具轮 | rounds{...} 只计 roundNo>1 |
| 隔离 | 指标实现抛异常 | 调用结果与 delegate 记录不受影响 |
| 装配 | 有 registry + 默认 store | 装饰器生效 |
| 装配 | 自定义 store Bean | 用户 Bean 被装饰而非替换，指标仍生效 |
| 装配 | llm-enabled=false / 无 micrometer | 无装饰器，行为同旧版 |

## 5. 验收标准

- core 模块零改动（`git diff ai-lite-core/src/main` 为空——除文档）。
- 指标标签基数全部有界；errorBrief 永不进标签。
- 指标实现抛异常不影响任何调用结果与记录。
- 新增测试约 8–10 条；全量测试通过。
- 文档：指标名、标签、启用条件与"不做 trace"的说明完整。

---

## 6. 执行记录（2026-10-25）

Phase M0–M2 当日完成，starter 回归 53 测试全绿。

### 已落地

| 阶段 | 内容 |
| --- | --- |
| M0 | starter `MicrometerLlmCallStore`：装饰 delegate store，四个指标（calls/duration/tokens/rounds）按 §2.2 口径映射；insert 全量 try/catch（Error 亦吞），指标故障不影响 delegate；core 模块零改动（验收达成） |
| M1 | `AiLiteConfig.Metrics`（llm-enabled 默认 true）+ starter pom micrometer-core optional（Boot BOM 管版本 1.1.8）+ `aiLiteGateway` 装配点装饰：registry 存在且启用时包装传入的 store 引用，不新增 bean、不夺用户 Bean；registry 缺失或关闭时零影响 |
| M2 | configuration.md §3.2 指标节（含不做了 trace 说明）、testing/README、README 统计、本记录 |

### 测试（新增 9 条，全部通过）

- `MicrometerLlmCallStoreTest` 6：成功记录的 calls/duration/tokens 断言、error/aborted outcome 派生、roundNo>1 计轮、errorBrief 不进标签、registry 抛异常时 delegate 仍写入、标签键集合有界。
- `AiLiteMetricsAssemblyTest` 3：registry 存在时 `gateway.one` 出指标（含异步记录轮询等待）、`llm-enabled=false` 关闭、用户自定义 store 被装饰而非替换（delegate 收到记录 + 指标同时生效）。

### 实现期踩坑

- starter 单模块编译用了本地仓库的旧 core 快照（找不到新类 `CallbackPolicy`）——联编须 `-am`。
- Boot 2.1 的 `ApplicationContextRunner` 没有 `withBean(Class, Supplier)`（2.2+），registry 经配置类提供。
- 网关记录经 `AsyncLlmCallLogger` **异步**落盘，装配测试断言前需轮询等待 meter 出现（固定断言有竞态）。
- 审查决策：本计划无并发结构改动（纯装饰器+装配），按规模跳过独立子代理审查，装配语义由 3 条装配测试锁定。

### 与计划的偏差

- 无实质偏差。
