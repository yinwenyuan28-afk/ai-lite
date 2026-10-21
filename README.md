# AI Lite

Lightweight in footprint, not in capability.

AI Lite 是面向 **Spring Boot 2.x / JDK 8** 存量系统的 AI 接入层，提供四块能力：

- **统一网关**：多 provider / 多协议、流式、取消、工具声明、用量与调用记录。
- **Agent 编排**：`Workflow`、`AgentLoop`、7 个生命周期时机，以及 `AgentSpec` / `Assembly`。
- **会话运行时**：跨请求续聊，内存 / Redis / JDBC 多种存储。
- **提示词中心**：草稿、发布、回滚、审计与占位符渲染，存储可换。

不包含 Dify 调用，不绑定任何业务模型。提示词既可以用内置的版本中心，也可以继续用你现有的方案，把渲染结果作为消息传给网关。

## 轻量并发

JDK 8 没有虚拟线程，但等待模型响应的大部分时间并不需要占住线程：

- 网关提供 `oneAsync` / `roundAsync` / `toolLoopAsync`，返回 `CompletableFuture`。
- OkHttp 适配器用 `enqueue` 做真正的非阻塞请求；Responses 和 Anthropic 的 SSE 也在回调线程解析。
- `ai-lite-executor-coroutines` 提供 Kotlin 协程调度器和 suspend 桥接，挂起点不占载体线程。
- JDK 21 下可切换到 `ai-lite-executor-jdk21` 的真虚拟线程，上层 API 不变。

这不是"自己造一个假虚拟线程"：JDK 8 的 stackful fiber 需要字节码增强，代价和维护风险都不适合作为 Starter 的基础设施。这里选择的是**异步 I/O + 协作式协程**，并用测试证明少量载体线程可以承载大量并发调用。

实测（3000 次调用、50ms 延迟、8 载体）：协程路径约 3.4 万 QPS、26 个线程；1000 线程池约 1.5 万 QPS、1017 个线程；纯 Java 异步约 4.2 万 QPS、18 个线程。实现细节见 [docs/user-level-concurrency.md](docs/user-level-concurrency.md)，可复现的 QPS 对比见 [benchmark/qps-benchmark](benchmark/qps-benchmark/README.md)。

## 状态

核心功能已可用：网关、三种协议适配器、Agent 循环、会话续聊、提示词中心、异步调用和自动装配都已落地。当前 404 个测试用例通过（Redis 不可用时 18 个 Redis 集成用例跳过），包含针对空响应、损坏 JSON、SSE 分片、并发写、发布回滚、异步取消等场景的对抗性测试。虚拟模型路由不在范围内。完整计划见 [PLAN.md](PLAN.md)，配置字段见 [docs/configuration.md](docs/configuration.md)。

## 环境要求

- JDK 8+
- Spring Boot 2.1 ~ 2.7
- 协程执行器需要 `ai-lite-executor-coroutines`（JDK 8 可用，可选）
- 虚拟线程执行器需要 JDK 21+（独立模块，可选）

## 模块

| 模块 | 作用 |
| --- | --- |
| `ai-lite-core` | 纯 Java：模型、网关、编排、会话抽象 |
| `ai-lite-prompt` | 提示词目录、版本、发布回滚、审计与渲染 |
| `ai-lite-prompt-jdbc` | 提示词 JDBC 存储 |
| `ai-lite-http-okhttp` | 默认 HTTP 实现（OkHttp 4.12，支持非阻塞） |
| `ai-lite-http-jdk8` | 零依赖 HTTP 实现（HttpURLConnection） |
| `ai-lite-session-redis` | Redis 会话缓存 |
| `ai-lite-session-jdbc` | JDBC 会话存储 |
| `ai-lite-store-jdbc` | JDBC 调用记录 |
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
    <version>0.1.0-SNAPSHOT</version>
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

最小配置：

```yaml
ai-lite:
  llm:
    biz-ids: [my-biz]
    default-profile: main
    providers:
      my-provider:
        api: openai-completions          # 或 openai-responses / anthropic-messages
        base-url: ${AI_LITE_BASE_URL}
        api-key: ${AI_LITE_API_KEY}
        models:
          - id: ${AI_LITE_MODEL}
            reasoning: false
            input: [text]
            context-window: 32768
            max-tokens: 4096
    profiles:
      main:
        provider: my-provider
        model: ${AI_LITE_MODEL}
        thinking-level: "off"
```

> `thinking-level` 的 `off` / `on` 建议加引号，否则 YAML 会把它解析成布尔值。
> 库已兼容 `"false"` → off、`"true"` → high，但显式引号更清楚。

### 3. 调用

```java
LlmResult result = gateway.one(LlmCall.profile("my-biz", "main",
        Collections.singletonList(LlmMessage.user("你好"))));
System.out.println(result.getContent());
```

`biz-id` 必须先登记，否则请求不会发出。这是故意的治理约束。

### 4. 异步调用

```java
CompletableFuture<LlmResult> future = gateway.oneAsync(call);
future.thenAccept(result -> System.out.println(result.getContent()));
```

`toolLoopAsync` 与 `roundAsync` 的语义和阻塞版本一致。取消时创建 `LlmCancelHandle` 并传给 `oneByStreamAsync` / `toolLoopByStreamAsync`，或者直接取消返回的 future。

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
    <version>0.1.0-SNAPSHOT</version>
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

- [上层调用 API 使用指南](docs/api-usage.md)：网关、编排、会话、提示词、执行器与扩展点。
- [Agent Loop 发给 LLM 的上下文](docs/llm-context.md)：每轮请求组成、thinking 字段和协议差异。
- [配置参考](docs/configuration.md)：所有 `ai-lite.*` 字段。
- [用户级线程实现](docs/user-level-concurrency.md)：JDK 21 虚拟线程、Kotlin 协程、取消链和实测数据。
- [QPS 基准](benchmark/qps-benchmark/README.md)：平台线程池 vs Java 异步 vs Kotlin 协程。

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

## 设计原则

1. **只做接入层。** 业务模型和 Dify 不进来；提示词中心是可选能力，也可以完全不用。
2. **依赖可替换。** HTTP、线程池、会话存储、提示词存储都是 SPI，默认实现之外都能换。
3. **配置驱动。** 新增 OpenAI 兼容供应商只改 YAML；新增协议才加适配器。
4. **治理优先。** 未登记的 `biz-id` 不发请求、不记记录；调用记录不含提示词和密钥。
5. **老系统友好。** JDK 8 字节码，Boot 2.1 可用，不使用 `@AutoConfiguration` 等新 API。
6. **并发不靠堆线程。** JDK 8 用异步 I/O 和协程，JDK 21 无缝切到虚拟线程。

## 许可证

[Apache License 2.0](LICENSE)
