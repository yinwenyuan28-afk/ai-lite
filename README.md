# AI Lite

Lightweight in footprint, not in capability.

AI Lite 是面向 **Spring Boot 2.x / JDK 8** 存量系统的 AI 接入层，提供三块能力：

- **统一网关**：多 provider / 多协议、流式、取消、工具声明、用量与调用记录。
- **Agent 编排**：`Workflow`、`AgentLoop`、7 个生命周期时机，以及 `AgentSpec` / `Assembly`。
- **会话运行时**：跨请求续聊，内存 / Redis / JDBC 多种存储。

不包含 Dify 调用，不包含提示词中心，不绑定任何业务模型。提示词可以继续用你现有的方案，把渲染结果作为消息传给网关。

## 状态

核心功能已可用：网关、三种协议适配器、Agent 循环、会话续聊和自动装配都已落地。当前 233 个测试用例全部通过，包含针对空响应、损坏 JSON、SSE 分片、并发写等场景的对抗性测试。Dify、提示词中心、虚拟模型路由不在范围内。完整计划见 [PLAN.md](PLAN.md)，配置字段见 [docs/configuration.md](docs/configuration.md)。

## 环境要求

- JDK 8+
- Spring Boot 2.1 ~ 2.7
- 虚拟线程执行器需要 JDK 21+（独立模块，可选）

## 模块

| 模块 | 作用 |
| --- | --- |
| `ai-lite-core` | 纯 Java：模型、网关、编排、会话抽象 |
| `ai-lite-http-okhttp` | 默认 HTTP 实现（OkHttp 4.12） |
| `ai-lite-http-jdk8` | 零依赖 HTTP 实现（HttpURLConnection） |
| `ai-lite-session-redis` | Redis 会话缓存 |
| `ai-lite-session-jdbc` | JDBC 会话存储 |
| `ai-lite-store-jdbc` | JDBC 调用记录 |
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

1. **只做接入层。** 业务模型、Dify、提示词中心都不进来。
2. **依赖可替换。** HTTP、线程池、存储都是 SPI，默认实现之外都能换。
3. **配置驱动。** 新增 OpenAI 兼容供应商只改 YAML；新增协议才加适配器。
4. **治理优先。** 未登记的 `biz-id` 不发请求、不记记录；调用记录不含提示词和密钥。
5. **老系统友好。** JDK 8 字节码，Boot 2.1 可用，不使用 `@AutoConfiguration` 等新 API。

## 许可证

[Apache License 2.0](LICENSE)
