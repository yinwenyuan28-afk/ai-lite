# 更新日志

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
版本策略与发布清单见 [docs/ref-发布.md](docs/ref-发布.md)。

## [0.1.3] - 2026-10-26

### 修复

- **demo 模块不再参与 Central 发布**：`excludeArtifacts` 只排除制品，模块仍会执行 `central-publishing` 的 publish goal。作为 reactor 最后一个模块，它触发整批上传并在认证失败时中断构建。给 demo 单独配 `skipPublishing=true`。
- **pom XML 注释非法字符**：注释中出现连续两个减号导致 POM 无法解析，构建直接读不了 pom。
- **发布前凭证自检**：release 工作流在部署前对 Central upload 端点做一次 Basic Auth 探测，401/403 立即失败并给出可操作提示；同时去除 secret 中可能混入的空白字符。

## [0.1.2] - 2026-10-26

### 修复

- **CI 发布流水线签名失败（`gpg: signing failed: Inappropriate ioctl for device`）**：`maven-gpg-plugin` 在无终端的 CI 环境会尝试交互式 pinentry。加 `--pinentry-mode loopback`，口令经 `-Dgpg.passphrase` 传入。
- **Central 凭证传递方式**：`central-publishing-maven-plugin` 按 `publishingServerId` 从 `settings.xml` 的 `<servers>` 读凭证，不认 `-Dcentral.username` 系统属性。release 工作流改为在 runner 上生成 `~/.m2/settings.xml`。

### 工程

- release 工作流缺少发布凭证时打 warning 并跳过部署（而不是像先前那样因 `setup-java` 导入空密钥而整体失败）。

## [0.1.1] - 2026-10-26

### 修复

- **`SerialCallbackExecutorTest` 调度竞态导致 CI 在慢机器上稳定失败。** 两个用例隐含假设「worker 已异步取走第一个任务」，但 `submit` 只入队并唤醒 worker，取走前该任务仍占队列位——容量 2 时高负载 runner 上第三次提交即被拒（`expected:<true> but was:<false>`）。改为用 `started` 栅栏等待任务真正进入执行态再断言，不再依赖调度速度。仅测试代码变更，生产行为无影响。
- **无 micrometer 依赖的宿主应用装配崩溃**（`ClassNotFoundException: MeterRegistry`）：`MeterRegistry` 相关 Bean 移入嵌套 `MicrometerMetricsConfiguration`（`@ConditionalOnClass` 用 ASM 评估，不触发类加载），类内保留 `ObjectProvider` 容忍「有 micrometer 但无 registry Bean」（无 actuator 的应用）。
- **`ModelResolver` 20 处解析/校验异常由 `UNKNOWN` 归位 `Kind.CONFIG`**：未发出 HTTP、默认不可重试，调用方按 `Kind` 区分「我方配置错 vs 供应商故障」不再误判。

### 工程

- CI 失败时输出失败用例名并上传 surefire 报告，缩短排障路径。
- Dependabot 禁止 `semver-major` 自动升级（OkHttp 5 / Kotlin 2.x / Actions v6-v7 会打破 JDK 8 + Boot 2.1/2.7 基线），Spring 上游由 BOM 管理不单独升级。

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

[0.1.3]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.3
[0.1.2]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.2
[0.1.1]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.1
[0.1.0]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.0
