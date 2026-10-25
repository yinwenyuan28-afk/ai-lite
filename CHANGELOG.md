# 更新日志

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
版本策略与发布清单见 [docs/ref-发布.md](docs/ref-发布.md)。

## [0.1.0] - 2026-10-26

首个公开版本。面向 Spring Boot 2.x / JDK 8 存量系统的轻量 LLM 接入层。

### 新增

**网关与协议**

- `LlmGateway` 单次模型调用入口全家：`one` / `oneAsync` / `oneByStream*` 同步异步流式矩阵，L1–L4 分层取消契约。
- 显式重试 `oneWithRetry` / `oneWithRetryAsync` / `LlmCoroutines.oneWithRetry`：指数退避 + 抖动、总时限、Retry-After 采纳（仅 429/503 正整数秒）、结构化错误分类（`LlmCallException.Kind`）。
- 三种协议适配器：OpenAI Chat Completions、OpenAI Responses、Anthropic Messages；多模态图片输入。

**HTTP 传输层**

- 六种可选传输：OkHttp（默认）、OkHttp + 虚拟线程（JDK 21）、Jetty 9.4 NIO、WebClient、Ktor CIO、JDK 8；`ai-lite.http.transport` 配置切换，未引模块启动即失败（fail-fast）。

**出口保护（默认全关，纯配置启用）**

- 按供应商并发限流（同步等待 / 异步排队交接，零线程占用）。
- 六片环形窗口熔断状态机（CLOSED / OPEN / HALF_OPEN，探测超时判定、迟到结论作废）。
- 多 api key 轮换：429/401 冷却当前 key、游标推进、全冷却兜底。
- Micrometer 指标：`ailite.llm.guard.*`。

**编排层**

- `AgentLoop`：同步 `run` + 异步 `runAsync`（泵结构：future 永不永久挂起）、并行工具执行、整轮超时、生命周期钩子、上下文组装与三种协议序列化。
- `Workflow`：`SyncStep` / `AsyncStep` 顺序执行器，`runAsync` 蹦床推进、显式步骤重试、取消与失败定位。
- `AgentSessionLoop` 会话续聊（内存 / Redis / JDBC 存储）与提示词中心（草稿 / 发布 / 回滚，内存 / JDBC）。

**可观测性**

- `LlmCallRecord` 全量结构化调用记录（API key 遮蔽、异步落盘不阻塞主调用）。
- Micrometer 指标出口 `ailite.llm.*`（调用数、耗时、token、轮次），classpath 无 micrometer 时零影响。

**执行器**

- 四种执行器模式：platform / direct / virtual（JDK 21）/ coroutines（JDK 8 可用的 Kotlin 协程桥接，`LlmCoroutines` suspend 入口）。
- 回调执行策略（`ai-lite.callback.mode`）：inline / io（per-call 串行回调池，慢回调不拖累共享线程）。

**工程**

- 818 项测试 + Redis 集成 18 项（真实环境验证）；网关 / AgentLoop / SessionLoop 对抗性竞态测试设计（docs/testing/）。
- 外部消费者工程验证发布形态（独立 reactor、无 micrometer classpath）对真实供应商端点冒烟通过。
- CI：JDK 8/17/21 × Boot 2.1/2.7 矩阵 + Redis service + gitleaks 密钥扫描 + Dependabot。
- 全套中文文档：分层定位框、术语表、`desc-/guide-/ref-` 前缀命名规范。

### 已知限制

- 0.x 阶段 API 未冻结（升级 0.1.x 可能有破坏性调整；1.0 起按语义化版本承诺）。
- 虚拟模型路由 / 供应商故障转移不在范围内。
- Jetty 9.4 已 EOL，安全敏感场景见 [HTTP 传输并发选型评估](docs/plan/HTTP传输并发选型评估.md) 的替代方案讨论。

[0.1.0]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.0
