# AI Lite

Lightweight in footprint, not in capability.

AI Lite 是面向 **Spring Boot 2.x / JDK 8** 存量系统的 AI 接入层，提供四块能力：

- **统一网关**：多 provider / 多协议、流式、取消、工具声明、用量与调用记录。
- **Agent 编排**：`Workflow`、`AgentLoop`、7 个生命周期时机，以及 `AgentSpec` / `Assembly`。
- **会话运行时**：跨请求续聊，内存 / Redis / JDBC 多种存储。
- **提示词中心**：草稿、发布、回滚、审计与占位符渲染，存储可换。

不包含 Dify 调用，不绑定任何业务模型。提示词既可以用内置的版本中心，也可以继续用你现有的方案，把渲染结果作为消息传给网关。

## 轻量并发

JDK 8 没有虚拟线程，但等待模型响应时不必占住**调用线程**；传输层占不占线程取决于所选 HTTP 客户端。

- 单次模型调用入口：`gateway.one`（同步）、`gateway.oneAsync`（future）、`LlmCoroutines.one`（suspend）、`LlmCoroutines.oneAsync`（Java 别名）。
- 显式重试：`gateway.oneWithRetry`（同步）、`gateway.oneWithRetryAsync`（future）、`LlmCoroutines.oneWithRetry`（suspend）。异步版的退避在专用 `ai-lite-retry-*` 调度器上，不占 HTTP 客户端线程。
- 网关还提供 `roundAsync` / `toolLoopAsync`，返回 `CompletableFuture`。
- OkHttp 适配器用 `enqueue` **让调用线程不阻塞**；实际 socket I/O 和响应解析仍在 OkHttp Dispatcher 线程上执行，每个在途请求占一个 Dispatcher 线程，流式请求会占到流结束。
- `ai-lite-executor-coroutines` 提供 Kotlin 协程调度器和 suspend 桥接，等待时协程挂起、不占线程。
- JDK 21 下可切换到 `ai-lite-executor-jdk21` 的真虚拟线程，上层 API 不变；它接管的是 `io()`，**不改变 OkHttp Dispatcher 的线程模型**。

这不是"自己造一个假虚拟线程"：JDK 8 的 stackful fiber 需要字节码增强，代价和维护风险都不适合作为 Starter 的基础设施。这里选择的是**异步回调 + 协作式协程**：调用线程和协程运行线程都被释放，传输层仍按所选 HTTP 客户端的模型占线程。

实测（3000 次调用、50ms 延迟、8 条协程线程）：协程路径约 3.4 万 QPS、26 个线程；1000 线程池约 1.5 万 QPS、1017 个线程；纯 Java 异步约 4.2 万 QPS、18 个线程。该基准使用独立调度器模拟延迟的 fake transport，测的是业务侧线程占用，**不包含 OkHttp Dispatcher 线程**；真实 OkHttp 的并发上限见 [docs/desc-用户级线程.md](docs/desc-用户级线程.md) 第 10.1 节。实现细节见 [docs/desc-用户级线程.md](docs/desc-用户级线程.md)，可复现的 QPS 对比见 [benchmark/qps-benchmark](benchmark/qps-benchmark/README.md)。

## 状态

核心功能已可用：网关、三种协议适配器、Agent 循环（同步 + 异步泵结构、并行工具、整轮超时、回调执行策略、**同步流式**）、会话续聊（**typed entries、上下文预算与自动压缩、用量记账、会话列举**）、提示词中心、异步调用、显式重试、LLM 调用指标（Micrometer）、出口自我保护（按供应商并发限流、熔断、`Retry-After` 尊重、多 key 轮换，默认全关）、Workflow 同步/异步顺序执行和自动装配都已落地。当前 975 个测试用例、0 失败（JDK 8 全量构建；Redis 集成用例在无 Redis 环境时跳过，可用时随构建运行；JDK 21+ 额外运行 5 个虚拟线程用例）。覆盖空响应、损坏 JSON、SSE 分片、并发写、发布回滚、异步取消、重试竞态、Workflow 取消/失败定位与蹦床（循环代替递归）推进、AgentLoop 异步推进循环的收尾与终态竞态、取消时不会立刻停的传输的兜底、回调策略线程隔离、并发闸门排队、熔断状态机、半开探测、key 轮换、**压缩条目的多路径持久化往返**、**四张目录表的自定义表名**、**提示词三表与调用正文表的自定义表名**等对抗性场景。虚拟模型路由不在范围内。配置字段见 [docs/ref-配置.md](docs/ref-配置.md)。

> **会话数据格式变更**：0.1.8 起 `MESSAGES_JSON` 为"带 `type` 的条目数组"，不兼容 0.1.x
> 的纯消息数组。项目尚未正式使用，故未提供迁移；详见 [CHANGELOG](CHANGELOG.md)。

## 环境要求

- JDK 8+
- Spring Boot 2.1 ~ 2.7
- 协程执行器需要 `ai-lite-executor-coroutines`（JDK 8 可用，可选）
- 虚拟线程执行器需要 JDK 21+（独立模块，可选）
- NIO 传输需要 `ai-lite-http-jetty`（跨 Boot 2.1/2.7，可选）
- 虚拟线程 HTTP 传输需要 JDK 21+ 和 `ai-lite-http-okhttp-jdk21`（可选）
- 响应式传输需要 `ai-lite-http-webclient` + `spring-webflux`（可选）
- Ktor CIO 传输需要 `ai-lite-http-ktor-cio`（Kotlin 项目，可选）

## 模块

| 模块 | 作用 |
| --- | --- |
| `ai-lite-core` | 纯 Java：模型、网关、编排、会话抽象 |
| `ai-lite-prompt` | 提示词目录、版本、发布回滚、审计与渲染 |
| `ai-lite-prompt-jdbc` | 提示词 JDBC 存储 |
| `ai-lite-http-okhttp` | 默认 HTTP 实现（编译依赖 OkHttp 4.12，调用线程异步；底层仍占 Dispatcher 线程）。调用只使用 3.8 和 4.12 都有的方法，宿主 classpath 上已有 OkHttp 3.8 时可以跑 |
| `ai-lite-http-okhttp-jdk21` | OkHttp 虚拟线程 Dispatcher（JDK 21 才编译，可选） |
| `ai-lite-http-jetty` | Jetty 9.4 NIO 传输（跨 Boot 2.1/2.7，可选） |
| `ai-lite-http-webclient` | Spring WebClient 5.1 API 响应式传输（可选） |
| `ai-lite-http-ktor-cio` | Ktor 2.3 CIO 传输（Kotlin，可选） |
| `ai-lite-http-jdk8` | 零依赖 HTTP 实现（HttpURLConnection） |
| `ai-lite-session-redis` | Redis 会话缓存 |
| `ai-lite-session-jdbc` | JDBC 会话存储 |
| `ai-lite-store-jdbc` | JDBC 调用记录 |
| `ai-lite-admin-api` | 运行时目录与管理 REST：模型、提示词、调用摘要 |
| `ai-lite-admin-ui` | 可选自带管理页（依赖 admin-api） |
| `ai-lite-executor-coroutines` | Kotlin 协程执行器与 suspend 桥接 |
| `ai-lite-spring-boot-starter` | 自动装配与默认 Bean |
| `ai-lite-executor-jdk21` | 虚拟线程执行器（JDK 21 才编译） |
| `ai-lite-demo` | 可运行示例 |

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-spring-boot-starter</artifactId>
    <version>0.1.12</version>
</dependency>
```

### 2. 填配置

推荐用环境变量，密钥不落文件：

```bash
export AI_LITE_DEMO_API_KEY=your-key
export AI_LITE_DEMO_BASE_URL=https://your-endpoint/v1
export AI_LITE_DEMO_MODEL=your-model
```

也可以复制 `ai-lite-demo/src/main/resources/application-local.yml.example` 为
`application-local.yml` 再填。该文件已在 `.gitignore` 中，**不要提交，也不要发给别人**。

最小配置（目录模式，模型和提示词在数据库里维护）：

```yaml
ai-lite:
  admin:
    enabled: false
  llm:
    biz-ids: [my-biz]
    providers:
      my-provider:
        api: openai-completions          # 或 openai-responses / anthropic-messages
        base-url: ${AI_LITE_BASE_URL}
        api-key: ${AI_LITE_API_KEY}
```

不引入 `ai-lite-admin-api` 时，旧路径仍然可用：在供应商下写 `models`，用 `profiles` 配合 `LlmCall.profile`。

目录模式还要引入 `ai-lite-admin-api`、`ai-lite-prompt-jdbc`、`ai-lite-store-jdbc` 和一个 JDBC 数据源，并执行 `ai-lite-catalog.sql`、`ai-lite-prompt.sql`、`ai-lite-llm-call.sql`。业务 id 同时是提示词 key。管理 REST 默认关闭；调试时把 `ai-lite.admin.enabled` 设为 `true`。若要自带页面，再引入 `ai-lite-admin-ui` 并设 `ai-lite.admin.page-enabled: true`，浏览器打开 `http://localhost:8080/ai-lite/admin`。**所有表名都能改**：目录四张用 `ai-lite.catalog.*-table`，提示词三张用 `ai-lite.prompt.jdbc.*-table`，调用记录与正文用 `ai-lite.store.jdbc.table-name` / `body-table-name`（正文不配时跟随主表），详见 [配置参考](docs/ref-配置.md)。

> `thinking-level` 的 `off` / `on` 建议加引号，否则 YAML 会把它解析成布尔值。
> 库已兼容 `"false"` → off、`"true"` → high，但显式引号更清楚。

### 3. 调用

```java
LlmResult result = gateway.one(LlmCall.biz("my-biz",
        Collections.singletonList(LlmMessage.user("你好"))));
System.out.println(result.getContent());
```

`biz-id` 必须先登记，否则请求不会发出。这是故意的治理约束。

### 4. 异步调用

```java
CompletableFuture<LlmResult> future = gateway.oneAsync(call);
future.thenAccept(result -> System.out.println(result.getContent()));
```

> **提示：把异步结果直接返回给 Spring MVC 时，必须确认整条过滤器链都支持 async。**
> 详见 [异步支持排查与修复](docs/guide-异步支持排查.md)，starter 提供启动期自动体检。

### 4.1 异步返回值与 Servlet 异步

ai-lite 自身的流式走回调（`AgentStreamListener`），**不依赖 Servlet 异步**。只有当你把 `CompletableFuture` / `SseEmitter` 直接作为 Controller 返回值时，才会走 Servlet 异步机制，此时必须满足一个约束：

> Servlet 规范要求 **servlet 和参与异步处理的每一个过滤器** 都支持异步。这是全链路的与运算，任何一环不支持，整个异步机制就不可用。

违反时会抛 `Async support must be enabled on a servlet and for all filters involved in async request processing`，而且是「**响应失败、业务却已成功**」的迷惑现象——接口返回 200 但前端没数据，日志里却有成功的记录。

#### 内置体检

引入 `ai-lite-admin-api` 后启动时自动检查调用方的过滤器链：

```yaml
ai-lite:
  admin:
    async-check:
      enabled: true      # 默认开启
      verbose: false     # 打开后打印每个过滤器的 async 状态与来源
```

只打日志、不阻断启动。排查时把 `verbose` 打开，一次就能看清整条链——包括**依赖 jar 带进来的过滤器**，而这类过滤器靠 grep 源码是搜不到的。

完整说明见 [异步支持排查与修复](docs/guide-异步支持排查.md)：症状识别、为什么会「成功又失败」、三种注册方式各自的修法，以及为什么改依赖 jar 里的过滤器必须用 `BeanPostProcessor` 而不是定义同名 Bean。

### 4.2 选择 HTTP 传输

默认 `okhttp`，现有应用无需改配置。需要减少传输层平台线程时，显式选择 B/C 路径：

```yaml
ai-lite:
  http:
    transport: jetty          # okhttp | okhttp-virtual | jetty | webclient | ktor-cio | jdk8
```

| transport | 依赖 | 适用 |
| --- | --- | --- |
| `okhttp` | 默认 | 通用；等待时占 Dispatcher 平台线程 |
| `okhttp-virtual` | `ai-lite-http-okhttp-jdk21` | JDK 21+，改动最小 |
| `jetty` | `ai-lite-http-jetty` | JDK 8 + Boot 2.1/2.7，真 NIO 主路径 |
| `webclient` / `ktor-cio` | 可选模块 | 已使用 WebFlux / Kotlin 的项目 |

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-http-jetty</artifactId>
    <version>0.1.12</version>
</dependency>
<!-- 或者： -->
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-http-webclient</artifactId>
    <version>0.1.12</version>
</dependency>
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-http-ktor-cio</artifactId>
    <version>0.1.12</version>
</dependency>
```

选择未引入的模块或未知值会在启动时失败，并给出缺少的依赖或允许值。完整字段、兼容矩阵和线程模型见 [配置参考](docs/ref-配置.md) 第 4 节；六种传输的实测对比见 [HTTP 传输并发基准](benchmark/http-transport-benchmark/README.md)。

### 5. 提示词中心

```yaml
ai-lite:
  prompt:
    cache-seconds: 60
    keys:
      - key: resume-parse
        title: 简历解析
        placeholders: [resume, job]
        default-system-template: "请解析这份简历：{{resume}}"
        default-user-template: "目标岗位：{{job}}"
```

```java
RenderedPrompt prompt = promptManager.render("resume-parse", vars);
LlmResult result = gateway.one(LlmCall.profile("my-biz", "main", prompt.getMessages()));
```

版本流转：

```java
PromptVersion draft = promptManager.createDraft("resume-parse", null, null, null, "alice");
promptManager.publish(draft.getVersionId(), "alice");
promptManager.rollback(oldVersionId, "alice");
```

- 只有草稿能改、能发布；只有发布过的版本能回滚。
- 发布前检查配置声明的占位符。
- 数据库不可用时 `render` 回退到 `default-*` 模板，不阻断业务。
- 默认内存存储；引入 `ai-lite-prompt-jdbc` 后自动换成共享表存储。

### 6. Kotlin / 协程

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-executor-coroutines</artifactId>
    <version>0.1.12</version>
</dependency>
```

```kotlin
val provider = CoroutineExecutorProvider(parallelism = 8)
runBlocking(provider.dispatcher()) {
    val results = calls.map { async { LlmCoroutines.one(gateway, it) } }.awaitAll()
}
```

Java 侧也可以把 `ai-lite.executor.mode` 设为 `coroutines`，由 Starter 反射装配这个执行器。

## 文档

**命名规范**：`docs/` 下文件名前缀即类型——`desc-` 原理说明（讲为什么这样运作）、`guide-` 使用指南（讲怎么用）、`ref-` 速查工具书，文件列表按前缀自动聚类；`docs/plan/`（计划与调研留档，命名 `夯实X-plan`）与 `docs/testing/`（测试设计）是内部记录。每篇开头都有定位框：属于哪一层、讲什么、不讲什么。生词查 [术语表](docs/ref-术语表.md)；想知道每层看哪些文档，《架构总览》开头有「按层找文档」导航表。

**全景门户**

- [架构说明](docs/desc-架构总览.md)：分层、模块拓扑、调用链、线程与取消模型——每层设计的「为什么」。

**使用指南（怎么用）**

- [上层调用 API 使用指南](docs/guide-上层调用API.md)：入门（五分钟接入）+ 全层 API 字典；日常查 API 从这里走。
- [HTTP 客户端使用指南](docs/guide-HTTP客户端.md)：六种传输怎么选怎么切、调优、自定义 `HttpExecutor`，附单次调用 API 速查与常见问题。
- [Workflow 异步执行器使用指南](docs/guide-Workflow异步执行器.md)：多步骤流程的 `SyncStep` / `AsyncStep`、取消、重试与 Spring MVC 集成。
- [异步支持排查与修复](docs/guide-异步支持排查.md)：接口「200 但无数据、业务却成功」的排查与修复；含启动期体检、Servlet 异步的过滤器链约束、依赖 jar 里过滤器的补开方法。

**原理说明（为什么这样运作）**

- [单次模型调用入口](docs/desc-单次调用.md)：一次 `oneWithRetryAsync` 的完整旅程——从发出到结果，含重试环、线程接力、出口保护。想理解内部发生什么，从这读。
- [HTTP 传输层原理](docs/desc-HTTP传输.md)：三层职责切分、六种传输的线程模型、SSE 逐行解析、取消怎么贯穿到 socket、并发上限的真正决定者。
- [重试边界](docs/desc-重试.md)：重试口径的唯一权威——可重试分类、策略参数、异步重试的线程与取消、自定义重试怎么接。
- [用户级线程实现](docs/desc-用户级线程.md)：JDK 21 虚拟线程与 Kotlin 协程桥接的实现原理、ThreadLocal 传递、取消链。
- [Agent Loop 发给 LLM 的上下文](docs/desc-AgentLoop上下文.md)：Agent 循环每轮实际发出去的请求体——上下文组成、协议差异、工具结果截断、流式增量分流、什么不进上下文。
- [上下文治理](docs/desc-上下文治理.md)：会话历史怎么变成"这一轮真正发出去的消息"——投影、预算、自动压缩，以及为什么保留原始而不原地替换。

**速查（跨类工具书）**

- [术语表](docs/ref-术语表.md)：全部术语的人话解释，按层分组。
- [配置参考](docs/ref-配置.md)：所有 `ai-lite.*` 配置键，含出口保护 `guard` 的完整字段。
- [数据表与缓存](docs/desc-数据表与缓存.md)：10 张表的用途、能否改名、怎么配；哪些常读数据有缓存、多久、怎么失效。
- [发布参考](docs/ref-发布.md)：版本策略、发布清单、CI 矩阵、Maven Central 配置。

**内部留档（docs/plan/ 设计审查与调研 + docs/testing/ 测试设计）**

- [夯实单次模型调用入口](docs/plan/夯实单次模型调用入口-plan.md)：入口 parity、L1/L2 取消、异常与重试边界。
- [夯实 oneWithRetry](docs/plan/夯实oneWithRetry-plan.md)：显式重试、错误分类、退避与取消的实现记录。
- [夯实 Workflow 顺序执行器](docs/plan/夯实Workflow顺序执行器-plan.md) / [夯实 Workflow 异步执行器](docs/plan/夯实Workflow异步执行器-plan.md)：顺序与异步执行器的原则、蹦床（循环代替递归）推进、取消与失败定位。
- [夯实 AgentLoop 一次性执行](docs/plan/夯实AgentLoop一次性执行-plan.md) / [夯实 AgentLoop 异步执行器](docs/plan/夯实AgentLoop异步执行器-plan.md)：Agent 循环的终态、钩子、异步推进循环（泵）结构与异步加固。
- [夯实 HTTP 传输并发](docs/plan/夯实HTTP传输并发-plan.md)：传输模块设计与验收；其立项前的[选型评估](docs/plan/HTTP传输并发选型评估.md)（Reactor Netty 为什么不可用、Jetty 9.4 怎么胜出）同目录留档。
- [夯实回调执行策略](docs/plan/夯实回调执行策略-plan.md)：用户回调与 Agent 钩子的串行执行、队列/取消。
- [夯实可观测性](docs/plan/夯实可观测性-plan.md)：Micrometer 指标出口。
- [夯实限流熔断](docs/plan/夯实限流熔断-plan.md)：出口保护（并发限流、熔断、Retry-After、多 key 轮换）的设计与审查记录。
- [夯实 Agent 运行时能力](docs/plan/夯实Agent运行时能力-plan.md)：typed entries、上下文预算与自动压缩、工具结果截断、同步流式、用量记账、会话列举的实现记录——含三处"静默丢压缩条目"缺陷的定位、T3-G 往返门禁，以及实施中另外发现的四个缺陷。
- [夯实对外契约冻结](docs/plan/夯实对外契约冻结-plan.md)：API 稳定性审计、`.internal` 包边界、SPI 演化承诺与 japicmp 门禁。
- [测试索引](docs/testing/测试索引.md)：按主题索引全部对抗性测试设计；另有[异步重试测试用例](docs/testing/异步重试测试用例.md)（oneWithRetryAsync 的 55 条验收清单）与[网关](docs/testing/网关异步对抗性测试设计.md)、[AgentLoop](docs/testing/AgentLoop异步对抗性测试设计.md)、[SessionLoop](docs/testing/SessionLoop异步对抗性测试设计.md)三篇对抗性测试设计。

**基准**

- [QPS 基准](benchmark/qps-benchmark/README.md)：平台线程池 vs Java 异步 vs Kotlin 协程。
- [HTTP 传输并发基准](benchmark/http-transport-benchmark/README.md)：六种 HTTP 传输在 100/500/1000 并发下的吞吐、延迟与线程数。

## 构建

```bash
mvn -B verify
```

用 Boot 2.7 验证兼容性：

```bash
mvn -B -Dspring-boot.version=2.7.18 verify
```

JDK 21 下会自动加入虚拟线程模块：

```bash
mvn -B verify
```

版本发布按 [docs/ref-发布.md](docs/ref-发布.md) 的清单执行（翻版本 → tag → release 工作流自动验证、收集制品、部署 Maven Central）；每个版本的行为变化见 [CHANGELOG.md](CHANGELOG.md)。

## 设计原则

1. **只做接入层。** 业务模型和 Dify 不进来；提示词中心是可选能力，也可以完全不用。
2. **依赖可替换。** HTTP、线程池、会话存储、提示词存储都是 SPI，默认实现之外都能换。
3. **配置驱动。** 新增 OpenAI 兼容供应商只改 YAML；新增协议才加适配器。
4. **治理优先。** 未登记的 `biz-id` 不发请求、不记记录；调用记录不含提示词和密钥。
5. **老系统友好。** JDK 8 字节码，Boot 2.1 可用，不使用 `@AutoConfiguration` 等新 API。
6. **并发不靠堆线程。** JDK 8 用异步回调 + 协程释放调用线程和协程运行线程；JDK 21 可切到虚拟线程；传输层线程模型取决于 HTTP 客户端。

## 许可证

[Apache License 2.0](LICENSE)
