# 更新日志

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
版本策略与发布清单见 [docs/ref-发布.md](docs/ref-发布.md)。

## [0.1.9] - 2026-10-28

### 修复

- **Jetty 传输在 https 下完全不可用**：`createClient` 用裸 `new HttpClient()`，没有配 `SslContextFactory`。任何 `https://` 供应商都会在 `send()` 阶段抛 `NullPointerException: Missing SslContextFactory`，且该异常不属于 `transport` 类失败、不会被重试策略接住，表现为"直接失败、无从下手"。现改为 `new HttpClient(new SslContextFactory.Client())`（默认排除 SSLv2/v3、启用 hostname 校验、使用 JVM 默认 truststore）。0.1.8 发布包受影响。
- **Jetty 把供应商的 401 变成无法分类的传输错误**：Jetty 内置的 `www-authenticate` / `proxy-authenticate` 协议处理器要求 401/407 响应必须带 challenge 头，而 LLM 供应商一律用 `Authorization: Bearer`，401 时不带该头，于是抛出 `HttpResponseException: HTTP protocol violation: Authentication challenge without WWW-Authenticate header`。现在在 `start()` 之后移除这两个处理器（`start()` 会重新注册，提前删无效），401 作为普通响应交给网关分类成 `Kind.HTTP`，多 key 轮换的 401 冷却逻辑也才真正生效。
- **`ai-lite.http.jetty.threads` 配小后首次调用即失败且提示难懂**：Jetty 用 NIO selector 驱动连接，固定大小线程池必须大于 selector 占用的线程数，否则 `ThreadPoolBudget` 在 `start()` 拒绝，报 `Insufficient configured threads: required=8 < max=1`。由于客户端是懒创建的，Spring 容器能正常启动，用户看到的却是"服务起来了、第一次调模型就挂"。现在在创建客户端前校验，直接抛出说明性错误（含本机核数、所需最小值与"或删掉该项用默认池"的出路）。实测下界为 `max(1, CPU/2) + 2`：2 核 → 3、4/5 核 → 4、8 核 → 6、16 核 → 10、32 核 → 18，9.4.22 与 9.4.53 一致。
- **管理台的"试跑/测试连通"绕开了配置启用的传输**：`ModelSyncClient` 直接用 `HttpURLConnection`，与 `ai-lite.http.transport` 选的客户端无关。后果是同一份配置下，管理台显示"调用成功"而业务调用全挂（例如网关配了 https 的 Jetty、而诊断走的是另一条没配 TLS 的链路）。现在诊断统一经 `HttpExecutor` 发出，与真实模型调用同连接池、同 TLS、同超时、同代理配置。仅在没有该 Bean 时（单独引入 `ai-lite-admin-api`）才退化为 `HttpURLConnection` 并打 WARN。

### 新增

- **`RawHttpExecutor.executeRaw`**（`ai-lite-llm`）：与 `HttpExecutor` 共用同一出站链路，但**不因状态码非 2xx 抛异常**，把状态码与完整正文交回调用方。原来的 `HttpExecutor.execute` 为控制错误消息体积会把正文截到 300 字符，管理台试跑看不到供应商返回的完整错误体（401 的 message、400 的字段校验、网关的 HTML 错误页），诊断价值大打折扣。按既有 SPI 演化承诺（B 组方法集终态、新能力走新接口）单独成接口，不动 `HttpExecutor`。OkHttp / okhttp-virtual / Jetty / WebClient / Ktor CIO / JDK8 六种实现全部支持。
- **`HttpRequest` 支持 GET**：新增带 `method` 的构造器与 `HttpRequest.GET` / `POST` 常量，用于 `GET {baseUrl}/models` 这样的只读探测。原 6 参构造器语义不变（仍为 POST），不需要改动任何既有调用方。
- **`ModelSyncClient` 接受 `HttpExecutor`**：`sync` / `listRemote` / `tryChat` / `probe` 均新增首参为执行器的重载；旧签名保留并转调（退化为 `HttpURLConnection`），仅用于脱离 Starter 的场景。
- **`AdminController` 新增带 `HttpExecutor` 的构造器**，自动装配经 `ObjectProvider` 注入；取不到时打 WARN 说明诊断与业务不是同一条链路。

### 工程

- 新增回归测试：Jetty 的 https 不得出现 `Missing SslContextFactory`、401 必须原样返回、`executeRaw` 保留完整错误正文、`GET` 探测不带请求体、`threads` 过小时给出可操作提示；管理台的试跑必须经过配置的执行器（用带标记头的执行器证明），且能展示供应商返回的完整错误正文。

## [0.1.8] - 2026-10-28（对外契约冻结 + Agent 运行时能力）

### 新增

- **管理页与业务热配置**：新增可选模块 `ai-lite-admin-api`（JDBC 目录 + 管理 REST）与 `ai-lite-admin-ui`（自带控制台）。`ai-lite.admin.enabled` 打开管理 API，供自有前端接入；自带页面需额外引入 ui 模块并由 `ai-lite.admin.page-enabled` 打开，默认关闭。同步模型只列出远端 id，用户勾选后才加入，删除只能由用户发起。调用记录增加输入输出档位：不记、只记失败、全部记录。
- 目录模式下，配置文件只声明供应商和 `biz-ids`。一个业务 id 对应一份提示词（key 就是业务 id）和一条模型绑定，存在数据库里，写入后清本机缓存。同步只插入新模型 id（未就绪），不覆盖已有能力参数，也不删除接口里已经消失的 id。
- **`LlmCall.biz(bizId, messages)`**：目录生效且这次调用没有指定 profile 和模型时，按绑定解析。没有绑定时报「业务未配置模型」，不再回退 `default-profile`。目录模式下未发布提示词时报「提示词未发布」。不引入该模块时，原有 `profiles` / `models` 路径不变。
- **API 兼容性门禁**：接入 `japicmp-maven-plugin`，`mvn verify -Pcontract-check` 会拿当前产物与上一个发布版（`ai-lite.properties` 里的 `ai-lite.release.version`）做二进制签名比对。删除 public 方法、修改参数类型、把类降级为包内可见都会让构建失败。`.internal` 包已排除，内部实现可自由重构。已做反向验证：删掉 `LlmResult.getRawContent()` 后构建变红并准确报出 `REMOVED METHOD`。
- **提示词审计 detail**：`createDraft` / `saveDraft` 新增带 `message` 参数的重载，把改动说明写进审计表 `DETAIL` 列（此前四处审计调用全部传 `null`，该列从未被写入过）。不传 `message` 时自动生成摘要：来源（`从第 N 版继承` / `手动填写模板`）、改动字段（`系统模板、用户模板` / `内容未变化`）、接替关系（`接替第 N 版生效`）。超 1000 字符截断，不阻断主操作。
- **`ai-lite-store-jdbc` 自动装配**：引入模块并在容器中有 `JdbcTemplate` 时自动注册 `JdbcLlmCallStore`，与 `session-jdbc` / `prompt-jdbc` 保持一致，不必再手写 `@Bean`。

#### Agent 运行时能力

- **typed entries 会话模型**：会话内部改为"带类型的条目流"，已冻结四种类型 `message` / `compaction` / `session_info` / `usage`。`AgentSession.getEntries()` 返回全量条目，`getMessages()` 保持原有"只返回消息"语义（签名不变，二进制兼容）。编解码用注册表按 `type` 分发；**未知 type 跳过并打 DEBUG 日志**（向前兼容），**缺 type 视为非法数据抛异常**（不兼容 0.1.x 的纯消息数组格式）。
- **上下文预算检测**：每轮发送前按字符类型加权估算 token（ASCII 0.25、CJK 0.6 tokens/字符），超过触发线（`contextWindow - reserveTokens`）时抛 `LlmCallException.Kind.CONTEXT_OVERFLOW`，错误消息含估算值与上限。窗口未配置（null/0）或小于等于 `reserveTokens` 时视为"窗口未知"，跳过检查。
- **自动压缩（默认开启）**：超限时调用 LLM 生成摘要并追加 `CompactionEntry`，**原始消息一条不删**。投影时按"前缀区 + [最后一条压缩的摘要] + firstKeptEntryId 之后的消息"现场计算，不缓存、不落盘；关掉开关立刻恢复完整历史。摘要按消息边界分块迭代，避免摘要请求自身超窗口。
- **会话级系统提示（前缀区）**：数组开头连续的 SYSTEM 条目被视为前缀区，永不参与压缩。投影时原样放最前；摘要输入不含前缀区内容。
- **工具结果截断**：单条工具结果超过上限时截断并追加标记 `…[输出过长已截断，原始 N 字符，保留前 M]`。覆盖串行、并行、异步三条写回路径；**截断发生在 `afterTool` 钩子之前**，因此落盘内容也是截断文本。
- **同步流式**：`AgentLoop.runStream` + `AgentDeltaListener` + `DeltaKind`（CONTENT / REASONING）。CONTENT 拼接结果恒等于 `getContent()`，REASONING 恒等于 `getThinkingText()`，两者都不含 `"\n\n"` 分隔符。增量回调恒在调用方线程。会话层通过 `ResumeOptions.deltaListener` 使用，未新增 `resumeStream` 方法。
- **会话用量记账**：每轮成功调用追加 `UsageEntry`，会话级 `totalTokens` 由条目汇总。取消/失败轮不产生条目（账单口径由 `LlmCallRecord` 覆盖）。
- **会话列举**：`AgentSessionStore.listByUser(userId, offset, limit)` + `AgentSessionSummary`，按 `updatedAt DESC, sessionId DESC` 稳定排序。`CachingAgentSessionStore` 已显式委托；JDBC 与内存实现均支持。
- **压缩并发租约**：`AgentSessionStore.tryAcquireCompaction` / `releaseCompaction`，多节点同时判定超限时只有一个执行摘要。租约过期可抢占；抢不到的一方跳过压缩继续，不报错。
- **入口参数收敛**：新增 `ResumeOptions` builder。旧 8 参 `resume` / `resumeAsync` 永久保留并转调，等价值由 `ResumeOptionsTest` 逐字段守门。
- **`LlmCall.maxTokens(Integer)`**：单次调用覆盖输出上限，供摘要调用使用。
- **`LlmGateway.resolveFor(LlmCall)`**：只解析模型元数据，不发请求、不占 guard、不写记录。

### 修复

- **压缩条目会被静默丢弃（三处）**。这是本轮最关键的修复，缺任何一处压缩都会"每轮算、每轮花钱、每轮被丢掉"且不报任何错：
  1. `AgentSessionStoreSupport` 的 `appendMessage` / `replaceMessage` / `deleteMessage` 只写回消息列表；
  2. `AgentSessionCodec.encodeSession` 只序列化消息（Redis 缓存命中一次再写回就丢）；
  3. **`AgentSessionLoop.finish` 用 `new AgentSession()` 重建对象**，只复制消息——而 `finish` 是每轮续聊的必经路径。

  修法：`AgentSession.mergeMessages` 按 id 对齐合并，非消息条目保持原位；`encodeSession` 改为写全量条目；`finish` 改为 `copyOf` + `mergeMessages`。
- **JDBC 会话读写不对称**：`save` 写全量条目但 `ROW_MAPPER` 用 `decodeMessages` 读，非消息条目读回即消失。已改为 `decodeEntries`。
- **压缩对本次请求无效**：`prepare` 里先算好发送用消息列表再做预算检查，导致压缩改的是历史、发出去的是压缩前的列表。现在先跑预算检查（可能压缩），再生成消息列表。
- **压缩切点越界**：切点可能落在本轮尚未落盘的新消息上，`firstKeptEntryId` 指向不存在的 id，投影退化成"压缩条目之后空历史"。现在切点上限被限制在已落盘历史内。
- **三个配置项写了但没生效**（补文档时对照代码发现）：
  - `ai-lite.context.estimator`：此前从未被读取。现在启动期校验取值，未知取值直接失败——静默回退默认实现会让调用方误以为换了算法。
  - `ai-lite.context.compaction.max-per-run`：此前只做了取值校验，没有参与循环。现在超限后最多压这么多次；若某次压缩后 token 不降反升，立即报错而不是继续烧钱。
  - `ai-lite.context.compaction.timeout-ms`：此前只存了值，摘要调用仍用主调用的超时。现在用独立取消句柄 + 调度器到点 cancel 施加超时，且**不绑主 run 的取消**（用户取消主请求不该连带中止已花掉一半钱的摘要）。

### 变更（破坏性，升级前务必阅读）

- **`ai-lite-core` 拆分为 `ai-lite-llm` 与 `ai-lite-agent`**。单次调用（`one` / `oneWithRetry` / `oneAsync` / `oneWithRetryAsync`）此前必须拖入 `AgentLoop`、`Workflow`、会话与压缩等整套编排代码。

  | 模块 | 内容 | 依赖 |
  | --- | --- | --- |
  | `ai-lite-llm` | `LlmGateway`、协议适配器、HTTP SPI、`config`、`catalog`、`guard` | slf4j + jackson |
  | `ai-lite-agent` | `AgentLoop`、`Workflow`、`AgentSessionLoop`、压缩与工具截断 | `ai-lite-llm` |
  | `ai-lite-core` | **空 jar**，只做传递依赖的聚合坐标 | 上面两个 |

  **包名全部不变**，因此源码一行都不用改；`ai-lite-core` 仍产出 jar（约 4 KB 的空包），现有依赖声明继续有效。想缩小依赖面，把 `ai-lite-core` 换成 `ai-lite-llm` 即可：

  ```xml
  <!-- 只要单次调用：网关 + 一个 HTTP 实现，不再拖入编排层 -->
  <dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-llm</artifactId>
    <version>0.1.8</version>
  </dependency>
  <dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-http-jdk8</artifactId>
    <version>0.1.8</version>
  </dependency>
  ```

  此时额外第三方依赖只有 `slf4j-api` + `jackson-databind`（后者再传递 `jackson-core` / `jackson-annotations`）。

- **API 兼容门禁在 0.1.8 空转一个版本**。`japicmp` 比对的是上一个发布版（`ai-lite.release.version = 0.1.7`），而 0.1.7 里只有 `ai-lite-core` 这一个坐标、且含源码。拆分后：

  | 模块 | 本轮 japicmp | 原因 |
  | --- | --- | --- |
  | `ai-lite-core` | 跳过 | 已无源码，旧基线里的类全部被判为"已删除" |
  | `ai-lite-llm` | 跳过 | Central 上无 0.1.7 基线制品，拉 oldVersion 直接失败 |
  | `ai-lite-agent` | 跳过 | 同上 |

  **这意味着 0.1.8 没有 API 兼容性保护**。0.1.8 发布后需把 `ai-lite.release.version` 前移到 `0.1.8`，从 0.1.9 起门禁恢复生效。

- **`ai-lite-store-jdbc` 现在要求显式配置表名**。`ai-lite.store.jdbc.table-name` 无默认值；引入模块但未配置会导致**应用启动失败**，错误消息会提示执行 `ai-lite-llm-call.sql` 建表。不需要 JDBC 调用记录的请设置 `ai-lite.store.jdbc.enabled=false`。

  ```yaml
  ai-lite:
    store:
      jdbc:
        enabled: true                    # 默认 true
        table-name: AI_LITE_LLM_CALL     # 必填
  ```

- **会话数据格式不向后兼容**：`MESSAGES_JSON` 从"纯消息数组"改为"带 `type` 的条目数组"。**0.1.x 存过的会话在升级后读取会抛异常**。本轮明确不做老数据兼容（项目尚未正式使用）；如需保留历史会话，请在升级前导出或清空 `AI_LITE_SESSION`。
- **`AI_LITE_SESSION` 表结构变更**：新增 `TITLE`、`MESSAGE_COUNT`、`TOTAL_TOKENS`、`COMPACTION_LEASE_UNTIL` 四列与索引 `IDX_AI_LITE_SESSION_USER_UPDATED`。只提供完整建表语句，**不提供 ALTER 迁移段**。
- **9 个内部实现类迁出公开包**，统一到 `io.github.yinwenyuan28.ailite.internal.{llm,session,http,executor}`：

  | 旧坐标 | 新坐标 |
  | --- | --- |
  | `ailite.llm.RetrySupport` | `ailite.internal.llm.RetrySupport` |
  | `ailite.llm.AsyncLlmCallLogger` | `ailite.internal.llm.AsyncLlmCallLogger` |
  | `ailite.llm.SequentialToolExecutor` | `ailite.internal.llm.SequentialToolExecutor` |
  | `ailite.llm.NoopLlmCallStore` | `ailite.internal.llm.NoopLlmCallStore` |
  | `ailite.llm.RetryScheduler` | `ailite.internal.llm.RetryScheduler` |
  | `ailite.session.AgentSessionStoreSupport` | `ailite.internal.session.AgentSessionStoreSupport` |
  | `ailite.http.Utf8LineBuffer` | `ailite.internal.http.Utf8LineBuffer` |
  | `ailite.executor.PlatformExecutorProvider` | `ailite.internal.executor.PlatformExecutorProvider` |
  | `ailite.executor.DirectExecutorProvider` | `ailite.internal.executor.DirectExecutorProvider` |

  这些类从未被文档承诺为公开 API。若你的代码直接引用了它们，请改 import；`.internal` 包不保证跨版本兼容。

  以下 3 个类**留在原包**：`ModelResolver`、`ProviderGuard`、`SerialCallbackExecutor` —— 它们出现在 `LlmGateway` 构造器、`setGuards`、`CallbackPolicy.newSerialExecutor` 的公开签名里，属于事实契约。

### 工程

- 明确 SPI 演化承诺：A 组（`PromptStore` / `SessionCache` / `AgentSessionStore`）未来只以 default 方法新增；B 组（`HttpExecutor` / `AsyncHttpExecutor` / `ExecutorProvider` / `LlmTool` / `LlmCallStore`）方法集终态。约定写进各接口 javadoc 与 [上层调用API](docs/guide-上层调用API.md) 的稳定性表。
- 用户可见文档中的依赖示例版本号由过期的 `0.1.0-SNAPSHOT` 更新为 `0.1.7`（21 处）。
- `release.yml` 的构建步骤改为 `mvn -B verify -Pcontract-check`。

## [0.1.7] - 2026-10-26

### 修复

- **两个纯 Kotlin 模块缺 `-javadoc` 制品导致 Central 校验失败**（`Javadocs must be provided but not found in entries`）：`ai-lite-http-ktor-cio` 与 `ai-lite-executor-coroutines` 的源码只在 `src/main/kotlin`，而 `maven-javadoc-plugin` 只扫描 `src/main/java`，产不出文档 jar。改为 `prepare-package` 阶段把 Kotlin 源码复制到 `target/javadoc-src`，再由 `maven-jar-plugin` 打成 `-javadoc` 分类制品（注意 jar 插件默认只收 class，需显式 `<include>**/*.kt</include>`，否则产出空 jar）。

## [0.1.6] - 2026-10-26

### 修复

- **Central 拒收 bundle（`Bundle has content that does NOT have a .pom file`）**：`central-publishing-maven-plugin` 0.6.0 在 Maven 3.10 下会把 `maven-metadata-local.xml` / `_remote.repositories` 写进 staging 目录且打包前不清理，导致 bundle 缺 pom 被拒。升级到 **0.12.0**（官方在该版本修复；仍以 source/target 8 编译，JDK 8 可用，要求 Maven 3.9+，发布由 CI 执行）。

## [0.1.5] - 2026-10-26

### 修复

- **发布静默失效**：0.1.4 的 release 工作流显示 success，但 Central Portal 上没有任何部署。原因是我在 0.1.3 给 demo 模块加的 `skipPublishing=true`——`central-publishing-maven-plugin` 以聚合模式工作，只在 **reactor 最后一个模块**上执行一次 publish goal（从 `target/central-staging` 打包含全部模块的 bundle），而 demo 正是最后一个模块，跳过它等于跳过整批上传，构建照样返回成功。已移除该配置，demo 制品仍由父 pom 的 `excludeArtifacts` 排除。

## [0.1.4] - 2026-10-26

### 修复

- **发布流水线的凭证自检改为连通性检查**：此前用「无上传体的 POST」探测凭证，而 Central 的 upload 端点只接受 multipart —— 任何缺 bundle 的请求都返回 401，导致误判 token 失效、白跑数轮。凭证与上传链路的实测方法记入 [发布参考](docs/ref-发布.md) §4.1。

### 工程

- 已用真实凭证在本地跑通完整发布链路：GPG 签名 → 打包 → 上传（`Uploaded bundle successfully`）。仅因测试用的是 SNAPSHOT 版本未过 Portal 校验（Central 只收正式版，属预期）。

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

[0.1.8]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.8
[0.1.7]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.7
[0.1.6]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.6
[0.1.5]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.5
[0.1.4]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.4
[0.1.3]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.3
[0.1.2]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.2
[0.1.1]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.1
[0.1.0]: https://github.com/yinwenyuan28-afk/ai-lite/releases/tag/v0.1.0
