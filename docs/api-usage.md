# 上层调用 API 使用指南

> 适用版本：0.1.0-SNAPSHOT
> 目标读者：业务开发、平台接入方、需要替换 SPI 实现的团队
> 配套文档：[配置参考](configuration.md)、[用户级线程实现](user-level-concurrency.md)

本文按"业务真正会调用的 API"组织，覆盖网关、Agent 编排、会话、提示词、并发执行器和扩展点。内部工具类（`TransportSupport`、`AgentSessionStoreSupport`、`AsyncLlmCallLogger` 等）不在本文范围内。

---

## 1. 稳定性约定

| 类别 | 包/类型 | 约定 |
| --- | --- | --- |
| 公开 API | `LlmGateway`、`LlmCall`、`LlmMessage`、`LlmResult`、`LlmTool`、`Workflow`、`AgentLoop`、`AgentLifecycle`、`AgentSessionLoop`、`PromptManager`、`RenderedPrompt` | 业务直接使用；尽量保持二进制兼容 |
| 扩展 SPI | `LlmTransport`、`HttpExecutor`、`AsyncHttpExecutor`、`ExecutorProvider`、`ToolExecutor`、`LlmCallStore`、`AgentSessionStore`、`SessionCache`、`PromptStore` | 允许自定义实现；接口方法变更会慎重 |
| 可选模块 | `ai-lite-executor-coroutines`、`ai-lite-executor-jdk21`、`ai-lite-session-redis`、`ai-lite-session-jdbc`、`ai-lite-store-jdbc`、`ai-lite-prompt-jdbc` | 按需引入，不引入不影响核心 |
| 内部实现 | `TransportSupport`、`AgentSessionStoreSupport`、`AsyncLlmCallLogger`、各协议的私有状态类 | 不保证兼容，不要直接依赖 |

持久化值以 `wire()` 为准（如 `LlmStopReason.wire()`），不要依赖枚举名。

---

## 2. 快速开始

### 2.1 依赖

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Starter 默认带入：`ai-lite-core`、`ai-lite-prompt`、`ai-lite-http-okhttp`。

### 2.2 最小配置

```yaml
ai-lite:
  llm:
    biz-ids: [my-biz]
    default-profile: main
    providers:
      my-provider:
        api: openai-completions
        base-url: ${AI_LITE_BASE_URL}
        api-key: ${AI_LITE_API_KEY}
        models:
          - id: ${AI_LITE_MODEL}
            reasoning: false
            input: [text]
            max-tokens: 4096
    profiles:
      main:
        provider: my-provider
        model: ${AI_LITE_MODEL}
        thinking-level: "off"
```

字段细节见 [configuration.md](configuration.md)。

### 2.3 第一次调用

```java
@Service
public class ChatService {

    private final LlmGateway gateway;

    public ChatService(LlmGateway gateway) {
        this.gateway = gateway;
    }

    public String chat(String text) {
        LlmCall call = LlmCall.profile("my-biz", "main",
                Collections.singletonList(LlmMessage.user(text)));
        LlmResult result = gateway.one(call);
        return result.getContent();
    }
}
```

`biz-id` 必须先登记在 `ai-lite.llm.biz-ids`，否则请求不会发出。

### 2.4 常用 API 速查

> ⭐ = 日常业务最常用；其余是进阶、扩展或运维 API。

| 任务 | 推荐 API |
| --- | --- |
| 单次模型调用入口（同步） | ⭐ `gateway.one(LlmCall.profile(...))` |
| 流式对话 | ⭐ `gateway.oneByStream(...)` |
| 工具循环 | ⭐ `gateway.toolLoop(call, tools, maxRounds)` |
| 单次模型调用入口（异步） | ⭐ `gateway.oneAsync(call)` |
| 高并发工具循环 | ⭐ `gateway.toolLoopAsync(call, tools, maxRounds)` |
| Agent + 生命周期 | ⭐ `agentLoop.run(...)` / `agentLoop.runAsync(...)` |
| 多步骤编排 | ⭐ `workflow.run(Arrays.asList(Workflow.oneStep(...), ...))` |
| 续聊 | ⭐ `sessionLoop.ensureSession(...)` + `sessionLoop.resume(...)` |
| 异步续聊 | ⭐ `sessionLoop.resumeAsync(...)` |
| 提示词渲染 | ⭐ `promptManager.render(key, vars)` |
| 提示词发布 | ⭐ `createDraft(...)` → `saveDraft(...)` → `publish(...)` |
| 取消调用 | ⭐ `LlmCancelHandle.cancel()` 或 `future.cancel(...)` |
| 单次模型调用入口（Kotlin 挂起） | ⭐ `LlmCoroutines.one(...)` |
| 调用记录落库 | `JdbcLlmCallStore` |
| 会话落库 | `JdbcAgentSessionStore` + `ai-lite.session.jdbc.table-name` |
| 自定义协议 | `LlmTransport` |
| 自定义 HTTP | `HttpExecutor` / `AsyncHttpExecutor` |

---

## 3. 网关 API：`LlmGateway`

### 3.1 方法总览

方法分三档：`one` 系列（`gateway.one` / `oneAsync` / `LlmCoroutines.one` / `oneAsync`）统称**「单次模型调用入口」**，是基石（单次请求，不涉及工具）；`round` 系列只把工具声明发给模型、不执行；`toolLoop` 系列才真正执行工具并续轮。

#### 3.1.1 ⭐ 单次模型调用入口（`one` 系列）

| 方法 | 说明 | 返回 | 常用 |
| --- | --- | --- | --- |
| `one(LlmCall)` | 单次调用，不带工具，阻塞当前线程直到返回 | `LlmResult` | ⭐ |
| `one(LlmCall, LlmCancelHandle)` | 同步 + 外部取消句柄，在途请求可被其他线程中止 | `LlmResult` |  |
| `oneByStream(LlmCall, Consumer<String>, LlmCancelHandle)` | 同一次调用，边生成边回调增量文本 | `LlmResult` | ⭐ |
| `oneAsync(LlmCall)` | 非阻塞单次调用，等待响应时不占调用线程 | `CompletableFuture<LlmResult>` | ⭐ |
| `oneAsync(LlmCall, LlmCancelHandle)` | 非阻塞 + 外部取消句柄 | `CompletableFuture<LlmResult>` |  |
| `oneByStreamAsync(LlmCall, Consumer<String>, LlmCancelHandle)` | 非阻塞 + 流式回调 | `CompletableFuture<LlmResult>` |  |

语义：

- 不向模型声明任何工具，因此不存在工具执行；正常结束时 `stopReason` 只会是 `STOP` 或 `LENGTH`，`getToolCalls()` 为空列表。
- `oneByStream` / `oneByStreamAsync` 与 `one` / `oneAsync` 是同一件事，只多一个增量回调，最终仍返回完整 `LlmResult`。
- 这是上层所有编排的落点：`Workflow.oneStep`、`AgentLoop` 的普通轮次、`AgentSessionLoop` 的续聊，最终都汇到这一档（带工具的轮次走 `round`）。
- 调用参数（biz-id 登记、模型解析、超时、取消）的校验与错误语义见 3.10，这一档不额外放宽。
- 所有入口都保证 L2（取消后不再推进）；L1（在途请求中止）取决于传输层：OkHttp 全阶段有效，JDK 8 `HttpURLConnection` 在正文读取阶段可能等到读超时。同步入口必须传 `LlmCancelHandle` 才具备在途取消；`one(call)` 只受超时约束。
- 网关不做自动重试，重试策略由调用方包在入口外面。完整矩阵见 [单次模型调用入口](entry-points.md) 与 [重试边界](retry.md)。

#### 3.1.2 工具与多轮方法

| 方法 | 说明 | 返回 | 常用 |
| --- | --- | --- | --- |
| `round(LlmCall, List<LlmTool>)` | 只发一轮并声明工具，不执行工具 | `LlmResult` |  |
| `roundAsync(LlmCall, List<LlmTool>)` | 非阻塞单轮工具声明 | `CompletableFuture<LlmResult>` |  |
| `roundAsync(LlmCall, List<LlmTool>, LlmCancelHandle)` | 同上 + 取消 | `CompletableFuture<LlmResult>` |  |
| `toolLoop(LlmCall, List<LlmTool>, Integer)` | 工具循环，最多 N 轮 | `LlmResult` | ⭐ |
| `toolLoop(LlmCall, List<LlmTool>)` | 同上，默认 8 轮 | `LlmResult` |  |
| `toolLoopByStream(...)` | 流式工具循环 | `LlmResult` |  |
| `toolLoopAsync(LlmCall, List<LlmTool>, Integer)` | 非阻塞工具循环 | `CompletableFuture<LlmResult>` | ⭐ |
| `toolLoopAsync(LlmCall, List<LlmTool>)` | 默认 8 轮 | `CompletableFuture<LlmResult>` |  |
| `toolLoopAsync(LlmCall, List<LlmTool>, Integer, LlmCancelHandle)` | 非阻塞 + 取消 | `CompletableFuture<LlmResult>` |  |
| `toolLoopByStreamAsync(...)` | 非阻塞流式工具循环 | `CompletableFuture<LlmResult>` |  |

默认最大轮数：`LlmGateway.DEFAULT_MAX_ROUNDS = 8`。

语义要点：

- `round` 会把工具声明发给模型，模型可能返回 `toolCalls`，但网关不执行；需要自己处理时可以据 `getStopReason() == TOOL_USE` 分支。
- `toolLoop` 在模型要求工具时执行工具，把结果写回上下文，再请求下一轮。
- 最后一轮仍需工具时，停止原因返回 `MAX_ROUNDS`。
- 未登记 `biz-id`、未配置模型、未知协议、工具名为空或重复，都会在发请求前抛 `LlmCallException`。
- 同步方法在调用线程上阻塞；异步方法不占调用线程等待响应。

### 3.2 `LlmCall`

```java
// 使用配置里的预置组合
LlmCall call = LlmCall.profile("my-biz", "main", messages);

// 显式指定供应商、模型和思考档
LlmCall call = LlmCall.explicit("my-biz", "my-provider", "my-model", "high", messages);

// 本次调用的超时（覆盖供应商配置；null 表示沿用配置）
call.timeouts(5000, 60000);

// 只替换消息，保留模型选择、思考档和超时
LlmCall next = call.withMessages(newMessages);
```

| 方法/字段 | 说明 | 常用 |
| --- | --- | --- |
| `profile(bizId, profile, messages)` | 使用 `ai-lite.llm.profiles` 中的组合 | ⭐ |
| `explicit(bizId, provider, modelId, thinkingLevel, messages)` | 直接指定供应商和模型 | ⭐ |
| `timeouts(connectMs, readMs)` | 修改当前实例的超时并返回 `this` |  |
| `withMessages(messages)` | 返回新实例，消息替换，其余保持 | ⭐ |
| `getBizId()`、`getProvider()`、`getModelId()`、`getThinkingLevel()`、`getProfile()`、`getMessages()` | 只读访问 |  |
| `getConnectTimeoutMs()`、`getReadTimeoutMs()` | 本次覆盖值，可能为 `null` |  |

`profile` 的优先级高于 `explicit`。如果两者都为空，网关会使用 `default-profile`。

### 3.3 `LlmMessage`

| 工厂方法 | 用途 | 常用 |
| --- | --- | --- |
| `LlmMessage.system(text)` | 系统提示 | ⭐ |
| `LlmMessage.user(text)` | 纯文本用户消息 | ⭐ |
| `LlmMessage.user(text, images)` | 图文用户消息 |  |
| `LlmMessage.assistant(text, toolCalls)` | 助手消息，不含推理 |  |
| `LlmMessage.assistant(text, thinkingText, toolCalls)` | 助手消息，正文和推理分开 |  |
| `LlmMessage.assistant(text, thinkingText, thinkingSignature, toolCalls)` | 助手消息，带 Anthropic 推理签名 |  |
| `LlmMessage.toolResult(toolCallId, text)` | 工具结果；`toolCallId` 必须对应助手的工具调用 |  |
| `LlmMessage.other(text)` | 其他角色；出站前折叠成用户文本 |  |
| `LlmMessage.other(text, images)` | 其他角色 + 图片 |  |

`LlmMessage` 的读取方法：`getText()`（可见正文）、`getThinkingText()`（推理原文）、`getThinkingSignature()`（推理签名，可为 null）、`getToolCalls()`、`getToolCallId()`、`getImages()`。

图片：

```java
LlmImage image = new LlmImage("image/png", base64WithoutDataUrlPrefix);
// 也接受完整的 data URL，构造时会去掉前缀
LlmImage fromDataUrl = new LlmImage("image/png", "data:image/png;base64,AAAA");
LlmMessage message = LlmMessage.user("描述这张图", Collections.singletonList(image));
```

模型配置的 `input` 必须包含 `image`，否则网关会在发请求前拒绝。`mime` 必须非空且以 `image/` 开头（大小写不敏感），`data` 必须非空；同一消息可以放多张图片，顺序保持不变。支持裸 base64 和 `data:<mime>;base64,<data>`，非 base64 的 data URL 会被拒绝。网关不压缩、不缩放、不校验大小，调用方负责遵守供应商的尺寸和字节限制。

**图片不进入会话存储**：`AgentSessionMessage` 不保存图片，`AgentSessionLoop` 的首轮 `call.getMessages()` 也会被会话历史覆盖。`LlmGateway.one/round/toolLoop` 和 `AgentLoop.run` 支持图片输入，`AgentSessionLoop` 不支持；需要多轮携带图片时，使用单次模型调用入口自行维护消息。

`LlmRole`：`SYSTEM`、`USER`、`ASSISTANT`、`TOOL_RESULT`、`OTHER`。`OTHER` 在出站前折叠成用户文本。

### 3.4 `LlmResult`

| 字段 | 说明 |
| --- | --- |
| `getContent()` | 可见正文，不含推理 |
| `getThinkingText()` | 推理原文；不支持推理的模型为 `null` |
| `getThinkingSignature()` | 推理签名；Anthropic 回传 thinking block 时需要，其他协议通常为 `null` |
| `getRawContent()` | 调试/兼容：按 `thinking + "\n\n" + content` 拼接后的文本 |
| `getStopReason()` | `LlmStopReason` |
| `getUsage()` | `LlmUsage`（输入/输出/总 token） |
| `getProvider()`、`getModelId()`、`getThinkingLevel()` | 实际生效的模型信息 |
| `getLatencyMs()` | 本次调用累计耗时 |
| `getRounds()` | 实际完成的轮数 |
| `getToolCalls()` | 最后一轮返回的工具调用；没有则为空列表 |

`getContent()` 和 `getThinkingText()` 是独立的两个字段：正文不再包含推理前缀。需要旧行为时用 `getRawContent()`。

相关值对象：

- `LlmStopReason`：`STOP`、`LENGTH`、`TOOL_USE`、`ERROR`、`ABORTED`、`MAX_ROUNDS`、`CONTEXT_LENGTH_EXCEEDED`；`wire()` 是稳定持久化值。
- `LlmUsage`：`zero()`、`of(input, output, total)`、`getInputTokens()`、`getOutputTokens()`、`getTotalTokens()`。
- `LlmThinkingLevel`：`OFF`、`MINIMAL`、`LOW`、`MEDIUM`、`HIGH`、`XHIGH`、`MAX`；`parse(String)` 大小写不敏感，并兼容 YAML 把 `off/on/yes/no` 解析成布尔值后的 `"false"/"true"`。

### 3.5 `LlmTexts`

| 方法 | 说明 |
| --- | --- |
| `splice(reasoning, content)` | 按 `reasoning + "\n\n" + content` 拼接，任一为空时返回另一个 |
| `fold(List<LlmMessage>)` | 出站前折叠：所有 system 合并成一条放最前，`OTHER` 转成 user，其余保持顺序 |
| `validate(List<LlmMessage>, boolean acceptsImage)` | 校验消息；模型不接受图片时拒绝图片消息 |

直接使用 `LlmGateway` 时一般不需要调用 `fold`/`validate`（网关内部会做）；`splice` 现在主要用于调试或还原旧版拼接文本。

### 3.6 ⭐ `LlmTool`

```java
LlmTool weather = new LlmTool() {
    @Override public String name() { return "get_weather"; }
    @Override public String description() { return "查询城市天气"; }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("city", Collections.singletonMap("type", "string"));
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("city"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return "晴，25°C";
    }
};
```

约定：

- `name()` 非空且在单次调用内唯一。
- `parameters()` 是 JSON Schema 的 `Map` 表示；没有参数返回空 Map。
- `execute()` 的返回值是交给模型的文本；`null` 按空串处理。
- 工具抛 `RuntimeException` 时，结果文本为“工具执行失败”。
- 模型调用了不存在的工具时，结果文本为“未找到工具: name”。
- 工具参数不是合法 JSON 时，结果文本为“工具参数不是合法 JSON”。

### 3.7 ⭐ 取消：`LlmCancelHandle`

```java
LlmCancelHandle cancel = new LlmCancelHandle();
CompletableFuture<LlmResult> future = gateway.oneByStreamAsync(call, onDelta, cancel);

// 任意线程
cancel.cancel();
```

| 方法 | 说明 |
| --- | --- |
| `cancel()` | 幂等；触发已绑定的中断动作，abort 最多执行一次 |
| `isCancelled()` | 是否已取消 |
| `bindAbort(Runnable)` | 传输层绑定 `Call.cancel()` 等动作；传 `null` 解绑 |

一个句柄只服务一次调用：第一次 abort 后进入终态，不复位。重试或发起新请求时请创建新句柄。

取消语义：

- 已经取消的调用在发请求前返回 `ABORTED`。
- 进行中的请求会通过 `Call.cancel()` 中断。
- `CompletableFuture.cancel(...)` 也会触发同样的中断；即使没有显式传句柄，网关内部也会创建。
- `AgentLoop.runAsync` 和 `AgentSessionLoop.resumeAsync` 同样把返回 future 的取消接到内部句柄，会话层的取消会穿过 `thenCompose` 传到内层循环。
- 取消是协作式的：忽略中断的自定义工具/传输可能继续执行。

### 3.8 流式调用

```java
StringBuilder answer = new StringBuilder();
LlmResult result = gateway.oneByStream(call, new Consumer<String>() {
    @Override
    public void accept(String delta) {
        answer.append(delta);
    }
}, null);
```

`onDelta` 收到的是增量文本（可能包含推理增量，取决于模型和协议）。回调可能在不同线程上执行，不要在里面做长时间阻塞操作，也不要依赖请求线程的 `ThreadLocal`。

### 3.9 同步 vs 异步

```java
// 同步：占用调用线程
LlmResult result = gateway.one(call);

// 异步：不占调用线程
CompletableFuture<LlmResult> future = gateway.oneAsync(call);
future.thenAccept(r -> System.out.println(r.getContent()));
```

详细并发模型见 [user-level-concurrency.md](user-level-concurrency.md)。高并发场景优先使用异步方法；协程桥接见第 7 节。

### 3.10 错误处理

| 情况 | 表现 |
| --- | --- |
| 参数/配置错误（未登记 biz-id、未配置密钥、未知协议等） | 同步抛 `LlmCallException`；异步在调用 `oneAsync` 时也可能同步抛出 |
| 网络/协议错误 | 同步抛 `LlmCallException`；异步 future 异常完成 |
| 调用被取消 | 返回 `LlmResult`，`stopReason = ABORTED` |
| 达到最大轮数 | 返回 `LlmResult`，`stopReason = MAX_ROUNDS` |
| 模型正常结束 | `STOP` / `LENGTH` / `TOOL_USE`（单轮） |

异步异常在 Java 侧通常是 `ExecutionException` 包着 `LlmCallException`；Kotlin `await()` 可能包一层 `CompletionException`，需要时解包。

---

## 4. Agent 编排 API

### 4.1 ⭐ `Workflow`

`Workflow` 是"单次调用 + 简单工具循环"的组合，不允许把 `AgentLoop` 当成步骤。

| 方法 | 说明 | 常用 |
| --- | --- | --- |
| `one(LlmCall)` | 单次调用 | ⭐ |
| `oneByStream(...)` | 流式单次调用 |  |
| `toolLoop(call, tools, maxRounds)` | 工具循环 | ⭐ |
| `toolLoop(call, tools)` | 默认 8 轮 |  |
| `toolLoopByStream(...)` | 流式工具循环 |  |
| `run(List<?> steps)` | 按顺序执行步骤，返回每步结果 | ⭐ |
| `execute(Object step)` | 执行单个步骤 |  |
| `Workflow.oneStep(call)` | 构造单次调用步骤 | ⭐ |
| `Workflow.toolLoopStep(call, tools, maxRounds)` | 构造工具循环步骤 | ⭐ |

```java
List<LlmResult> results = workflow.run(Arrays.asList(
        Workflow.oneStep(extractCall),
        Workflow.toolLoopStep(enrichCall, tools, 4)));
```

`execute` 只接受实现了 `Workflow.Step` 的对象；传入 `AgentLoop` 会抛 `LlmCallException`。

### 4.2 ⭐ `AgentLoop`

```java
LlmResult result = agentLoop.run(call, tools, lifecycles, maxRounds, cancel);

CompletableFuture<LlmResult> future =
        agentLoop.runAsync(call, tools, lifecycles, maxRounds, cancel);
```

| 参数 | 说明 |
| --- | --- |
| `call` | 调用参数；消息会被上下文副本替换 |
| `tools` | 可用工具；生命周期可以修改 |
| `lifecycles` | 钩子列表，按顺序执行；可为 `null` |
| `maxRounds` | 最大轮数；`null` 表示 8；必须大于 0 |
| `cancel` | 取消句柄；可为 `null`（内部会创建） |

行为：

- 每一轮调用 `gateway.round` / `roundAsync`，不直接用 `one`。
- 模型要求工具且不是最后一轮时，执行工具并把结果写回消息。
- 最后一轮仍要求工具时，`stopReason = MAX_ROUNDS`。
- 钩子抛异常时：同步版本向上抛；异步版本 future 异常完成。
- 取消：同步版本在轮次之间检查；异步版本会取消进行中的请求（通过 `ctx.getCancel()`）。
- 取消或异常时不会产生"半截"结果；`afterRun` 在取消时仍会调用（同步/异步的实现一致）。

### 4.3 生命周期：`AgentLifecycle`

| 钩子 | 调用时机 | 能做什么 | 常用 |
| --- | --- | --- | --- |
| `beforeRun(ctx)` | 整个循环开始，只一次 | 改消息列表、替换工具列表 |  |
| `beforeModel(ctx)` | 每一轮发请求前 | 改消息、改工具 | ⭐ |
| `afterModel(ctx, output)` | 模型返回后、工具执行前 | 改助手正文（`output.setContent`） | ⭐ |
| `beforeTool(ctx, call)` | 每个工具执行前 | 返回 `allow()`、`allow(newArgs)` 或 `deny(reason)` | ⭐ |
| `afterTool(ctx, call, resultText)` | 工具执行后 | 返回替换文本；`null` 表示沿用 | ⭐ |
| `afterTurn(ctx, roundResult)` | 一轮工具处理完 | 返回 `stop()` / `continueRound()` / `modelDecides()` | ⭐ |
| `afterRun(ctx, lastResult)` | 循环停止后，只一次 | 记录日志/指标；不能再影响请求 |  |

共识规则：

- **拒绝优先**：任何一个 `beforeTool` 返回 `deny`，该工具不执行；后续钩子的 `beforeTool` 不再调用。拒绝原因作为工具结果进入下一轮。
- **停止优先**：任何一个 `afterTurn` 返回 `stop()`，本轮结束后停止；同时有人要求 `continueRound()` 也以停止为准。
- 没有人停止、但有人要求继续或本轮有工具调用时，进入下一轮。
- `afterModel` 改写的正文会用于工具消息和最终结果。

上下文：

```java
public final class AgentLoopContext {
    String getBizId();
    List<LlmMessage> getMessages();   // 活列表，可直接改
    List<LlmTool> getTools();         // 活列表
    void setTools(List<LlmTool>);      // 替换本次可用工具
    LlmCancelHandle getCancel();
    boolean isCancelled();
}
```

`AgentModelOutput.getContent()` / `setContent()` 用于改写助手正文；`AgentToolCall.getArgumentsJson()` / `setArgumentsJson()` 用于改写工具参数。

### 4.4 `WorkflowSpec`、`AgentSpec`、`Assembly`

这三个是给业务组织代码的接口，不是运行时框架：

```java
public interface WorkflowSpec<I, O> {
    O run(Workflow workflow, I input);
}

public interface AgentSpec<I, O> {
    O run(AgentLoop agentLoop, I input);
}

public interface Assembly<I, O> {
    O run(I input);
}
```

典型用法：一个 `Assembly` 实现里组合 `WorkflowSpec` 和 `AgentSpec`，由 Spring 注入 `Workflow` / `AgentLoop`。

---

## 5. 会话 API

### 5.1 ⭐ `AgentSessionLoop`

| 方法 | 说明 | 常用 |
| --- | --- | --- |
| `ensureSession(userId, sessionId, bizId)` | 不存在则创建空会话并返回；已存在直接返回 | ⭐ |
| `resume(userId, sessionId, call, tools, lifecycles, maxRounds, cancel, newUserTurns)` | 同步续聊一轮 | ⭐ |
| `resumeAsync(...)` | 异步续聊一轮，返回 `CompletableFuture<ResumeResult>` | ⭐ |

`resumeAsync` 的会话加载和落盘都在注入的 `ExecutorProvider.io()` 上执行；取消返回的
future 会同时取消内层 `AgentLoop` 和正在进行的 HTTP 请求。<code>AgentSessionLoop</code>
默认复用 `AgentLoop` 的执行器，也可以构造时显式传入。

```java
AgentSession session = sessionLoop.ensureSession(userId, sessionId, "my-biz");

AgentSessionLoop.ResumeResult result = sessionLoop.resume(
        userId, sessionId, call, tools, null, 8, null,
        Collections.singletonList(AgentSessionMessage.user("继续")));

String answer = result.getResult().getContent();
AgentSession saved = result.getSession();
```

规则：

- `call.getMessages()` 会被会话历史替换；本轮新增消息放在 `newUserTurns`。
- `resume` 要求会话已存在；不存在抛 `LlmCallException`。
- 结果 `ABORTED` 时不落盘。
- 循环抛异常时不落盘。
- 保存使用读到的 `version`；并发写冲突抛 `LlmCallException("会话版本冲突")`。
- `ResumeResult.getSession()` 是保存后重新读取的快照。

### 5.2 `AgentSession`

| 成员 | 说明 |
| --- | --- |
| `userId`、`sessionId`、`bizId` | 会话标识 |
| `messages` | `List<AgentSessionMessage>` |
| `version` | 乐观锁版本；成功 `save` 后存储侧为 `version + 1` |
| `updatedAt` | 更新时间 |
| `toLlmMessages()` | 转成网关消息；默认丢弃 `thinkingText` 和消息 id |
| `toLlmMessages(includeThinking)` | true 时把 `thinkingText` / `thinkingSignature` 放进独立字段，不拼回正文 |
| `messagesToLlm(source, includeThinking)` | 静态版本 |
| `copyOf(session)` | 浅拷贝消息列表的副本 |

`AgentSession` 是可变对象，不是线程安全的；把它当快照使用。

### 5.3 `AgentSessionMessage`

| 工厂 | 说明 |
| --- | --- |
| `system(text)` | 系统消息 |
| `user(text)` | 用户消息 |
| `assistant(text, thinkingText, toolCalls)` | 助手消息，正文和推理分开 |
| `assistant(text, thinkingText, thinkingSignature, toolCalls)` | 助手消息，带推理签名 |
| `toolResult(toolCallId, text)` | 工具结果 |
| `fromResult(thinkingText, hookedContent, toolCalls)` | 从一轮结果构造助手消息 |
| `fromResult(thinkingText, thinkingSignature, hookedContent, toolCalls)` | 从一轮结果构造助手消息，保留签名 |
| `of(id, role, text, thinkingText, toolCalls, toolCallId)` | 指定 id，反序列化/测试用 |
| `of(id, role, text, thinkingText, thinkingSignature, toolCalls, toolCallId)` | 指定 id 和签名 |

| 方法 | 说明 |
| --- | --- |
| `getId()` | 稳定消息 id |
| `getRole()` | `LlmRole` |
| `getText()` | 可见正文 |
| `getThinkingText()` | 推理文本，可为 `null` |
| `getThinkingSignature()` | 推理签名，可为 `null` |
| `getToolCalls()` | 工具调用列表 |
| `getToolCallId()` | 工具结果对应的调用 id |
| `splicedText()` | 调试用：把思考文本和正文拼回 `thinking + "\n\n" + text` |

### 5.4 `AgentSessionStore`

```java
public interface AgentSessionStore {
    Optional<AgentSession> find(String userId, String sessionId);
    void save(AgentSession session);
    void delete(String userId, String sessionId);
    boolean exists(String userId, String sessionId);

    default Optional<AgentSessionMessage> findMessage(String userId, String sessionId, String messageId);
    default void appendMessage(String userId, String sessionId, AgentSessionMessage message);
    default void replaceMessage(String userId, String sessionId, AgentSessionMessage message);
    default void deleteMessage(String userId, String sessionId, String messageId);
}
```

`save` 契约：

- 入参 `version` 是调用方读到的当前版本；成功后存储侧为 `version + 1`。
- `version = 0` 且没有记录时创建；已有记录但版本不匹配时抛冲突。
- 消息 id 必须非空且在同一会话内唯一。

消息级方法默认是"读出整包 → 改列表 → 带版本写回"，并发写会触发版本冲突。

### 5.5 内置实现

| 实现 | 构造 | 说明 |
| --- | --- | --- |
| `InMemoryAgentSessionStore` | `(int maxEntries, long ttlSeconds)` | 有界内存；默认 Starter 使用 |
| `CachingAgentSessionStore` | `(AgentSessionStore durable, SessionCache cache, long ttlSeconds)` | 持久层 + 热缓存；写库成功才更新缓存 |
| `InMemorySessionCache` | `(int maxEntries)` | 内存热缓存 |
| `JdbcAgentSessionStore` | `(JdbcTemplate)` 或 `(JdbcTemplate, table)` | 表 `AI_LITE_SESSION`；乐观锁更新；表名可通过配置指定 |

表名配置：

```yaml
ai-lite:
  session:
    jdbc:
      table-name: AI_LITE_SESSION
```

引入 `ai-lite-session-jdbc` 后，只要容器里有 `JdbcTemplate`，自动装配会优先注册
`JdbcAgentSessionStore`，Starter 的内存存储自动让位；没有 `JdbcTemplate` 时回退到内存存储。
表名只允许字母、数字和下划线，非法值会在启动时抛 `IllegalArgumentException`。
| `RedisSessionCache` | `(StringRedisTemplate, keyPrefix, requireRedis)` | Redis 热缓存；`available()` 可探测 |

`SessionCache` SPI：

```java
Optional<AgentSession> get(String userId, String sessionId);
void put(AgentSession session, long ttlSeconds);
void delete(String userId, String sessionId);
```

### 5.6 `AgentSessionCodec`

| 方法 | 说明 |
| --- | --- |
| `encodeMessages(List<AgentSessionMessage>)` | 消息列表转 JSON |
| `decodeMessages(String)` | JSON 转消息列表 |
| `encodeSession(AgentSession)` | 会话转 JSON |
| `decodeSession(String)` | JSON 转会话 |
| `validateMessageIds(List<AgentSessionMessage>)` | 校验空消息、空 id、重复 id |

兼容性：`updatedAt` 使用毫秒时间戳，角色使用枚举名，工具参数保留为字符串。自定义存储直接复用这个 codec 即可保持兼容。

---

## 6. 提示词 API

### 6.1 登记提示词

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

| `PromptKey` 字段 | 说明 |
| --- | --- |
| `key` | 稳定编码；小写字母、数字、短横线，最长 64 |
| `title` | 展示名 |
| `placeholders` | 发布前必须出现在模板里的占位符 |
| `bizId`、`profile` | 业务元数据，库本身不强制使用 |
| `defaultSystemTemplate` | 没有生效版本或存储不可用时的系统模板 |
| `defaultUserTemplate` | 兜底用户模板 |

`PromptCatalog`：

```java
List<PromptKey> keys = catalog.keys();     // 副本
PromptKey key = catalog.find("resume-parse");
PromptKey required = catalog.require("resume-parse"); // 未登记抛 PromptException
boolean valid = PromptCatalog.validKey("resume-parse");
```

### 6.2 ⭐ `PromptManager`

| 方法 | 说明 | 常用 |
| --- | --- | --- |
| `keys()` | 已登记提示词副本 |  |
| `current(key)` | 当前生效版本；没有或存储不可用时返回 `Optional.empty()` | ⭐ |
| `versions(key)` | 版本列表，版本号倒序，最多 50 条 |  |
| `audits(key)` | 审计列表，时间正序，最多 50 条 |  |
| `createDraft(key, system, user, schema, actor)` | 创建草稿；系统模板为空时继承生效版本或配置默认值 | ⭐ |
| `saveDraft(versionId, system, user, schema, actor)` | 只修改草稿 | ⭐ |
| `publish(versionId, actor)` | 发布草稿；校验占位符 | ⭐ |
| `rollback(versionId, actor)` | 回滚到已发布过的版本 | ⭐ |
| `render(key, vars)` | 渲染；优先级：生效版本 → 配置默认模板 | ⭐ |
| `render(key, vars, fallbackSystem, fallbackUser)` | 渲染；优先级：生效版本 → 调用方兜底 → 配置默认 |  |
| `invalidate(key)` | 清除本地生效版本缓存 |  |
| `invalidateAll()` | 清空本地缓存 |  |
| `nextVersionNo(key)` | 下一个版本号 |  |
| `apply(template, vars)` | 静态：替换 `{{name}}` |  |
| `requirePlaceholders(system, user, placeholders)` | 静态：校验占位符 |  |
| `hash(system, user, schema)` | 静态：SHA-256 摘要 |  |

状态机：

```text
createDraft → DRAFT
saveDraft   → 只能改 DRAFT
publish     → DRAFT → PUBLISHED；同 key 其他 PUBLISHED 变 RETIRED
rollback    → 非 DRAFT 且非当前生效 → PUBLISHED；当前生效变 RETIRED
```

常量：`PromptManager.LIST_LIMIT = 50`，`PromptManager.TEXT_LIMIT = 100000`。

`render` 规则：

- 生效版本存在时用它的 system/user；即使 user 为空也保持为空。
- 没有生效版本时用调用方兜底；兜底为空再用配置默认值。
- 系统模板最终为空时抛 `PromptException`。
- 未知占位符原样保留；变量值为 `null` 按空串处理。

```java
RenderedPrompt prompt = promptManager.render("resume-parse", vars);
LlmResult result = gateway.one(call.withMessages(prompt.getMessages()));
```

`RenderedPrompt`：`getSystem()`、`getUser()`、`getMessages()`（不可变列表，最多 system + user 两条）。

### 6.3 `PromptStore`

```java
Optional<PromptVersion> findActive(String promptKey);
Optional<PromptVersion> findVersion(String versionId);
List<PromptVersion> versions(String promptKey, int limit);
List<PromptAudit> audits(String promptKey, int limit);
int nextVersionNo(String promptKey);
void saveVersion(PromptVersion version);
void activate(String promptKey, String versionId, String actor, Date publishTime);
void audit(PromptAudit audit);
```

`activate` 必须保证原子性：退役同 key 其他已发布版本、发布目标版本、更新生效指针。`JdbcPromptStore` 在传入 `PlatformTransactionManager` 时使用事务。

内置实现：`InMemoryPromptStore`、`JdbcPromptStore`（构造：`(JdbcTemplate)`、`(JdbcTemplate, PlatformTransactionManager)`、`(JdbcTemplate, versionTable, activeTable, auditTable, txManager)`）。

### 6.4 版本与审计对象

`PromptVersion`：`versionId`、`promptKey`、`versionNo`、`status`、`systemTemplate`、`userTemplate`、`outputSchema`、`contentHash`、`createdBy`、`createTime`；`copy()`、`toTemplate()`。

`PromptTemplate`：`getSystemTemplate()`、`getUserTemplate()`、`getOutputSchema()`。

`PromptAudit`：`auditId`、`promptKey`、`action`、`versionId`、`actor`、`detail`、`createTime`。

`PromptStatus`：`DRAFT`、`PUBLISHED`、`RETIRED`；`wire()`、`parse(String)`。

`PromptActions` 常量：`DRAFT`、`UPDATE`、`PUBLISH`、`ROLLBACK`。

`PromptException`：提示词相关错误，和 `LlmCallException` 分开。

---

## 7. 并发与执行器 API

### 7.1 `ExecutorProvider`

```java
public interface ExecutorProvider extends Closeable {
    Executor io();
    void close();
}
```

`io()` 用于**阻塞副作用任务**：异步调用日志、`AgentLoop` 的工具执行、会话加载/落盘，以及业务自己的阻塞 SDK。模型网络等待由异步 transport 和 HTTP 回调承担，不占这个池。四种模式的完整对比见 [architecture.md](architecture.md) 的 5.2 节。

| 实现 | 模块 | 构造/说明 |
| --- | --- | --- |
| `PlatformExecutorProvider` | core | `(AiLiteConfig.Executor)`；有界 `ThreadPoolExecutor`，默认核心 `max(2, CPU)`、最大 `max(4, CPU×2)` |
| `DirectExecutorProvider` | core | 调用线程内联执行，测试用 |
| `CoroutineExecutorProvider` | `ai-lite-executor-coroutines` | `(parallelism = max(4, CPU*2), threadNamePrefix)`；通过 Starter 时由 `core-size` 指定载体数 |
| `VirtualThreadExecutorProvider` | `ai-lite-executor-jdk21` | 每任务一个虚拟线程，JDK 21+；忽略池字段 |

配置：

```yaml
ai-lite:
  executor:
    mode: platform   # platform / direct / coroutines / virtual
    core-size: 4
    max-size: 16
    queue-capacity: 1000
    keep-alive-seconds: 60
    thread-name-prefix: ai-lite-
    rejection-policy: abort   # abort / caller-runs
```

不同模式对字段的生效范围不同：`direct` / `virtual` 忽略全部池字段；`coroutines` 只用 `core-size`（载体数）和 `thread-name-prefix`；`platform` 使用全部字段。字段默认值和选型建议见 [configuration.md](configuration.md) 第 3 节。

### 7.2 Kotlin 协程桥接

```kotlin
val provider = CoroutineExecutorProvider(parallelism = 8)

runBlocking(provider.dispatcher()) {
    val results = calls.map { call ->
        async { LlmCoroutines.one(gateway, call) }
    }.awaitAll()
}
```

`LlmCoroutines.one` 是单次模型调用入口的 Kotlin suspend 形式；`LlmCoroutines.oneAsync` 是同一个入口的 Java future 别名。

| API | 说明 |
| --- | --- |
| `CoroutineExecutorProvider.dispatcher()` | 协程调度器，挂起点不占载体 |
| `CoroutineExecutorProvider.io()` | 同一个池的阻塞视图 |
| `CoroutineExecutorProvider.close()` | 关闭载体池 |
| `LlmCoroutines.one(gateway, call, cancel?)` | 单次模型调用入口（suspend） |
| `LlmCoroutines.round(gateway, call, tools, cancel?)` | 单轮工具声明 |
| `LlmCoroutines.toolLoop(gateway, call, tools, maxRounds?, cancel?)` | 工具循环 |
| `LlmCoroutines.oneAsync(gateway, call, cancel?)` | 单次模型调用入口的 Java 别名，等价于 `gateway.oneAsync` |

Java 不能直接调用 `suspend` 函数；Java 用 `gateway.oneAsync(...)` 或 `LlmCoroutines.oneAsync(...)`。

### 7.3 JDK 21 虚拟线程

```yaml
ai-lite:
  executor:
    mode: virtual
```

需要引入 `ai-lite-executor-jdk21`。Starter 通过反射加载，JDK 8 运行时不会解析虚拟线程类。`mode=virtual` 只改变 `io()` 的实现，不会自动把 `gateway.one()` 变成异步；把阻塞调用提交到 `io()` 才会跑在虚拟线程上。

详细原理、取消链和限制见 [user-level-concurrency.md](user-level-concurrency.md)。

---

## 8. HTTP 与传输 SPI

### 8.1 `HttpExecutor` / `AsyncHttpExecutor`

```java
public interface HttpExecutor extends Closeable {
    HttpResult execute(HttpRequest request);
    void executeStreaming(HttpRequest request, StreamListener listener);
    void close();
}

public interface AsyncHttpExecutor extends HttpExecutor {
    CompletableFuture<HttpResult> executeAsync(HttpRequest request);
    CompletableFuture<Void> executeStreamingAsync(HttpRequest request, StreamListener listener);
}
```

`HttpRequest`：

```java
new HttpRequest(String url, Map<String, String> headers, String body,
        int connectTimeoutMs, int readTimeoutMs, LlmCancelHandle cancel);
```

`HttpResult`：`getStatus()`、`getBody()`。

`StreamListener`：`onLine(String line) throws IOException`，SSE 每行原样回调。

实现：`OkHttpExecutor`（默认，支持真异步）、`Jdk8HttpExecutor`（零依赖，异步退化为线程池）。

### 8.2 `LlmTransport`

```java
public interface LlmTransport {
    String api();
    LlmCompletion complete(LlmRequest request);

    default CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
        return CompletableFuture.supplyAsync(() -> complete(request));
    }
}
```

自定义传输：

- `api()` 返回配置里的 `api` 值，例如 `openai-completions`。
- `complete` 同步执行；流式时调用 `request.getOnDelta().accept(...)`。
- 需要高并发时覆盖 `completeAsync`，不要默认落到 ForkJoinPool。
- `LlmRequest` 提供模型、消息、工具、流式开关、取消句柄、超时和 `extraBody`。

`LlmCompletion` 是传输层归一化结果：`getContent()`、`getThinkingText()`、`getStop()`、`getUsage()`、`getToolCalls()`。

---

## 9. 存储与扩展点

### 9.1 `LlmCallStore`

```java
public interface LlmCallStore {
    void insert(LlmCallRecord record);
}
```

默认 `NoopLlmCallStore` 不落库。`JdbcLlmCallStore` 构造：`(JdbcTemplate)` 或 `(JdbcTemplate, table)`，表 `AI_LITE_LLM_CALL`。

`LlmCallRecord` 字段：`callId`、`bizId`、`provider`、`modelId`、`api`、`profile`、`thinkingLevel`、`roundNo`、`inputTokens`、`outputTokens`、`totalTokens`、`latencyMs`、`stopReason`、`success`、`errorBrief`、`createTime`。记录不含提示词、响应体和密钥。

```java
@Bean
public LlmCallStore llmCallStore(JdbcTemplate jdbcTemplate) {
    return new JdbcLlmCallStore(jdbcTemplate);
}
```

### 9.2 `ToolExecutor`

```java
List<String> execute(List<LlmTool> tools, List<LlmToolCall> calls);
CompletableFuture<List<String>> executeAsync(List<LlmTool> tools, List<LlmToolCall> calls);
```

返回列表必须与 `calls` 等长且顺序一致。默认 `SequentialToolExecutor` 串行执行，单个工具异常转成文本。

### 9.3 自定义 Bean 覆盖

Starter 的默认 Bean 都带 `@ConditionalOnMissingBean`。定义同类型 Bean 即可覆盖：

```java
@Bean
public HttpExecutor httpExecutor() {
    return new OkHttpExecutor(myClient);
}

@Bean
public LlmCallStore llmCallStore(JdbcTemplate jdbcTemplate) {
    return new JdbcLlmCallStore(jdbcTemplate);
}

@Bean
public AgentSessionStore agentSessionStore(JdbcTemplate jdbcTemplate) {
    return new JdbcAgentSessionStore(jdbcTemplate);
}
```

协议适配器按 Bean 名覆盖：

- `openAiCompletionsTransport`
- `openAiResponsesTransport`
- `anthropicMessagesTransport`

如果两个传输返回相同的 `api()`，`LlmGateway` 构造时会抛“协议适配器重复”。

---

## 10. Spring Boot 自动装配

### 10.1 自动配置类

| 类 | 条件 | 主要 Bean |
| --- | --- | --- |
| `AiLiteAutoConfiguration` | `ai-lite.enabled=true`（默认） | `AiLiteConfig`、`ExecutorProvider`、`HttpExecutor`、`ModelResolver`、三个 `LlmTransport`、`LlmCallStore`、`ToolExecutor`、`LlmGateway`、`Workflow`、`AgentLoop` |
| `AiLiteSessionAutoConfiguration` | `ai-lite.enabled=true` 且 `ai-lite.session.enabled=true` | `AgentSessionStore`（默认内存）、`AgentSessionLoop` |
| `AiLiteSessionJdbcAutoConfiguration`（session-jdbc 模块） | `ai-lite.enabled=true`、`ai-lite.session.enabled=true`、容器里有 `JdbcTemplate` | `AgentSessionStore`（JDBC，优先于默认内存） |
| `AiLitePromptAutoConfiguration` | `ai-lite.enabled=true` 且 `ai-lite.prompt.enabled=true`（默认） | `PromptCatalog`、`PromptStore`、`PromptManager` |
| `AiLitePromptJdbcAutoConfiguration`（prompt-jdbc 模块） | `ai-lite.enabled=true`、`ai-lite.prompt.enabled=true`、类路径有 `JdbcTemplate` 且容器里有 `JdbcTemplate` Bean | `PromptStore`（JDBC） |

`spring.factories`：

```text
ai-lite-spring-boot-starter:
  AiLiteAutoConfiguration
  AiLiteSessionAutoConfiguration
  AiLitePromptAutoConfiguration

ai-lite-session-jdbc:
  AiLiteSessionJdbcAutoConfiguration

ai-lite-prompt-jdbc:
  AiLitePromptJdbcAutoConfiguration
```

### 10.2 开关

```yaml
ai-lite:
  enabled: true          # 总开关
  session:
    enabled: true        # 会话 Bean
  prompt:
    enabled: true        # 提示词 Bean
```

关闭总开关后，不装配任何 AI Lite Bean。

### 10.3 手动构造（不用 Spring）

```java
AiLiteConfig.Llm llmConfig = ...;
ModelResolver resolver = new ModelResolver(llmConfig);
LlmGateway gateway = new LlmGateway(
        resolver,
        Arrays.asList(completions, responses, anthropic), // List<LlmTransport>
        new NoopLlmCallStore(),
        new PlatformExecutorProvider(new AiLiteConfig.Executor()),
        new SequentialToolExecutor());
```

核心模块不依赖 Spring；Starter 只是把这些对象装配起来。

---

## 11. 异常与错误处理

| 异常 | 来源 | 处理建议 |
| --- | --- | --- |
| `LlmCallException` | 网关、传输、会话存储 | 业务可恢复错误；展示简要信息，记录日志 |
| `PromptException` | 提示词目录、版本、渲染 | 配置/操作错误；检查 key、草稿状态、占位符 |
| `IllegalArgumentException` | 少数构造参数（如表名、空依赖） | 启动期错误；修正配置 |
| `ExecutionException` | Java 异步 `future.get()` | 取 `cause`，通常是 `LlmCallException` |
| `CompletionException` | Kotlin `await()` 可能包装 | 需要时解包 `cause` |
| `CancellationException` | future 被取消 | 按取消处理，不要当业务失败 |

停止原因不是异常：`ABORTED`、`MAX_ROUNDS`、`LENGTH` 都通过 `LlmResult.getStopReason()` 返回。

---

## 12. 线程安全与生命周期

| 对象 | 线程安全 | 说明 |
| --- | --- | --- |
| `LlmGateway` | 是 | 构造后不再修改；传输实现需自身线程安全（OkHttp 是） |
| `Workflow`、`AgentLoop` | 是 | 无状态包装 |
| `AgentSessionLoop` | 取决于存储 | 同一会话并发 `resume` 可能版本冲突 |
| `AgentSession` | 否 | 可变快照，不要跨线程共享修改 |
| `AgentSessionMessage` | 是 | 不可变 |
| `PromptManager` | 是 | 内部缓存并发安全；版本操作依赖 `PromptStore` |
| `PromptKey`、`PromptVersion`、`PromptAudit` | 否 | 配置/传输对象；`PromptManager` 返回副本 |
| `InMemoryAgentSessionStore` | 是 | `save` 同步 + 版本校验 |
| `CachingAgentSessionStore` | 取决于两层 | 缓存故障降级，不影响持久层 |
| `OkHttpExecutor`、`Jdk8HttpExecutor` | 是 | `close()` 释放资源 |
| `PlatformExecutorProvider`、`CoroutineExecutorProvider` | 是 | `close()` 关闭池；关闭后拒绝新任务 |

生命周期：

- Starter 创建的 `ExecutorProvider`、`HttpExecutor` 带 `destroyMethod="close"`，容器关闭时释放。
- 手动创建时记得调用 `close()`。
- `LlmGateway` 不需要关闭；它不持有自己的线程池。

---

## 13. 完整示例

### 13.1 Spring Boot + 工具循环 + 会话

```java
@Service
public class AssistantService {

    private final LlmGateway gateway;
    private final AgentSessionLoop sessionLoop;
    private final PromptManager promptManager;

    public AssistantService(LlmGateway gateway, AgentSessionLoop sessionLoop,
                            PromptManager promptManager) {
        this.gateway = gateway;
        this.sessionLoop = sessionLoop;
        this.promptManager = promptManager;
    }

    public String chat(String userId, String sessionId, String question) {
        sessionLoop.ensureSession(userId, sessionId, "my-biz");

        RenderedPrompt prompt = promptManager.render("assistant-chat",
                Collections.singletonMap("question", question));

        LlmCall call = LlmCall.profile("my-biz", "main", prompt.getMessages());
        AgentSessionLoop.ResumeResult result = sessionLoop.resume(
                userId, sessionId, call, tools(), null, 8, null,
                Collections.singletonList(AgentSessionMessage.user(question)));

        return result.getResult().getContent();
    }

    private List<LlmTool> tools() {
        return Collections.singletonList(weatherTool);
    }
}
```

### 13.2 Java 异步批量调用

```java
List<CompletableFuture<LlmResult>> futures = new ArrayList<CompletableFuture<LlmResult>>();
for (String text : texts) {
    futures.add(gateway.oneAsync(LlmCall.profile("my-biz", "main",
            Collections.singletonList(LlmMessage.user(text)))));
}

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
for (CompletableFuture<LlmResult> future : futures) {
    System.out.println(future.join().getContent());
}
```

### 13.3 Kotlin 协程

```kotlin
val provider = CoroutineExecutorProvider(parallelism = 8)

suspend fun batch(gateway: LlmGateway, texts: List<String>): List<String> =
    coroutineScope {
        texts.map { text ->
            async {
                val call = LlmCall.profile("my-biz", "main",
                    listOf(LlmMessage.user(text)))
                LlmCoroutines.one(gateway, call).content
            }
        }.awaitAll()
    }

fun main() = runBlocking(provider.dispatcher()) {
    val answers = batch(gateway, texts)
    println(answers)
}
```

### 13.4 提示词发布

```java
PromptVersion draft = promptManager.createDraft(
        "assistant-chat", null, null, null, "alice"); // 继承当前生效模板
promptManager.saveDraft(draft.getVersionId(),
        "你是助手，请回答：{{question}}", null, null, "alice");
promptManager.publish(draft.getVersionId(), "alice");

// 回滚
promptManager.rollback(oldVersionId, "alice");
```

### 13.5 自定义传输（高级）

```java
public final class MyTransport implements LlmTransport {

    @Override
    public String api() {
        return "my-api";
    }

    @Override
    public LlmCompletion complete(LlmRequest request) {
        // 构造请求、发 HTTP、解析成 LlmCompletion
        return new LlmCompletion(content, thinking, stop, usage, toolCalls);
    }

    @Override
    public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
        // 建议用真正的非阻塞 I/O；不要默认落到 ForkJoinPool
        return myAsyncClient.call(request);
    }
}
```

配置里把 `provider.api` 设为 `my-api`，并通过 `@Bean` 注册传输。

---

## 14. API 索引

| 类型 | 模块 | 用途 |
| --- | --- | --- |
| `LlmGateway` | core | 模型调用入口 |
| `LlmCall` | core | 调用参数 |
| `LlmMessage` / `LlmImage` / `LlmRole` | core | 消息模型 |
| `LlmResult` / `LlmUsage` / `LlmStopReason` / `LlmThinkingLevel` | core | 结果与枚举 |
| `LlmTool` / `LlmToolCall` | core | 工具声明与调用 |
| `LlmCancelHandle` | core | 协作式取消 |
| `LlmCallException` | core | 调用异常 |
| `Workflow` / `WorkflowSpec` | core | 简单步骤组合 |
| `AgentLoop` / `AgentLifecycle` / `AgentLoopContext` | core | 完整工具循环与生命周期 |
| `AgentModelOutput` / `AgentToolCall` / `ToolDecision` / `TurnDecision` | core | 生命周期入参/决策 |
| `AgentSpec` / `Assembly` | core | 业务组织接口 |
| `AgentSession` / `AgentSessionMessage` / `AgentSessionCodec` | core | 会话模型 |
| `AgentSessionStore` / `SessionCache` | core | 会话存储 SPI |
| `AgentSessionLoop` | core | 续聊入口 |
| `InMemoryAgentSessionStore` / `InMemorySessionCache` / `CachingAgentSessionStore` | core | 内置会话存储 |
| `PromptManager` / `PromptCatalog` / `PromptKey` / `PromptVersion` / `PromptTemplate` / `RenderedPrompt` | prompt | 提示词中心 |
| `PromptStore` / `InMemoryPromptStore` | prompt | 提示词存储 SPI/默认实现 |
| `PromptStatus` / `PromptAudit` / `PromptActions` / `PromptException` | prompt | 提示词状态与异常 |
| `ExecutorProvider` / `PlatformExecutorProvider` / `DirectExecutorProvider` | core | 执行器 SPI/默认实现 |
| `CoroutineExecutorProvider` / `LlmCoroutines` | executor-coroutines | Kotlin 协程桥接 |
| `VirtualThreadExecutorProvider` | executor-jdk21 | JDK 21 虚拟线程 |
| `HttpExecutor` / `AsyncHttpExecutor` / `HttpRequest` / `HttpResult` / `StreamListener` | core | HTTP SPI/模型 |
| `LlmTransport` / `LlmRequest` / `LlmCompletion` | core | 协议适配 SPI |
| `LlmCallStore` / `LlmCallRecord` / `NoopLlmCallStore` | core | 调用记录 SPI |
| `JdbcLlmCallStore` | store-jdbc | JDBC 调用记录 |
| `JdbcAgentSessionStore` | session-jdbc | JDBC 会话 |
| `RedisSessionCache` | session-redis | Redis 会话缓存 |
| `JdbcPromptStore` | prompt-jdbc | JDBC 提示词 |

---

## 15. 相关文档

- [配置参考](configuration.md)：所有 `ai-lite.*` 字段。
- [Agent Loop 发给 LLM 的上下文](llm-context.md)：每轮请求组成、thinking 字段与协议差异。
- [用户级线程实现](user-level-concurrency.md)：JDK 21 虚拟线程、Kotlin 协程、取消链和实测数据。
- [QPS 基准](../benchmark/qps-benchmark/README.md)：平台线程池 vs Java 异步 vs Kotlin 协程。
- [README](../README.md)：模块列表和快速开始。
