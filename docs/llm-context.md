# Agent Loop 发给 LLM 的上下文

> 适用版本：0.1.0-SNAPSHOT
> 相关代码：`AgentLoop`、`LlmGateway`、三个协议适配器、`LlmMessage`、`LlmResult`
> 配套文档：[API 使用指南](api-usage.md)、[用户级线程实现](user-level-concurrency.md)

## 0. 一句话结论

Agent Loop 每一轮发给 LLM 的是：

```text
模型/生成参数
+ 合并后的 system
+ 完整对话转录（USER / ASSISTANT / TOOL_RESULT）
+ 工具声明
```

每轮全量发送，不是增量。

从当前版本开始，**ASSISTANT 的可见正文和 thinking 是两个独立字段**：

- `LlmMessage.getText()`：可见正文
- `LlmMessage.getThinkingText()`：thinking 原文
- `LlmMessage.getThinkingSignature()`：thinking 签名（Anthropic 回传时需要）

协议适配器按各自协议决定怎么把 thinking 发回去；不再把 thinking 拼进正文。

---

## 1. 从运行时上下文到请求体

```text
AgentLoopContext.messages（本次循环的活列表）
        │
        │ LlmTexts.fold
        │  · 所有 SYSTEM 合并成一条，放最前
        │  · OTHER 转成 USER
        │  · USER / ASSISTANT / TOOL_RESULT 保持原顺序
        ▼
transcript: List<LlmMessage>
        │
        │ + ctx.tools
        │ + LlmCall（bizId / profile / 超时）
        │ + ResolvedModel（provider / model / 采样 / 思考参数）
        ▼
LlmRequest
        │
        │ transport.complete / completeAsync
        ▼
HTTP body（按协议序列化）
```

代码位置：

- `AgentLoop.java:76`：`gateway.round(call.withMessages(ctx.getMessages()), ctx.getTools())`
- `LlmGateway.java:249`：`LlmTexts.fold(call.getMessages())`
- `LlmGateway.java:326`：构造 `LlmRequest`
- `LlmTexts.java:75`：`fold` 规则

---

## 2. 出站上下文组成

```text
┌─────────────────────────────────────────────────────────────────────┐
│ 1. 模型与生成参数                                                    │
│    model                                                            │
│    max_tokens / max_output_tokens                                   │
│    stream                                                           │
│    samplerParams（temperature、top_p 等，按 thinking level 覆盖）    │
│    thinking / reasoning 参数（按 thinking-format）                   │
│    extraBody（provider → model → 本次调用 深合并）                   │
├─────────────────────────────────────────────────────────────────────┤
│ 2. 系统 / 指令                                                       │
│    system = 所有 SYSTEM 消息按顺序用 "\n\n" 合并                     │
├─────────────────────────────────────────────────────────────────────┤
│ 3. 完整对话转录                                                      │
│                                                                     │
│    USER                                                              │
│      ├─ text                                                        │
│      └─ images（仅模型 input 含 image）                              │
│                                                                     │
│    ASSISTANT                                                         │
│      ├─ text                 可见正文，不含 thinking                 │
│      ├─ thinkingText         thinking 原文，可为 null                │
│      ├─ thinkingSignature    thinking 签名，可为 null                │
│      └─ toolCalls            [{id, name, argumentsJson}]             │
│                                                                     │
│    TOOL_RESULT                                                       │
│      ├─ toolCallId           对应 assistant 的 toolCall              │
│      └─ text                 工具结果 / 拒绝原因                     │
├─────────────────────────────────────────────────────────────────────┤
│ 4. 工具声明（仅 round / toolLoop；one 不带）                         │
│    [{ name, description, parameters: JSON Schema }]                  │
├─────────────────────────────────────────────────────────────────────┤
│ 5. 传输层附加（不进入模型上下文）                                     │
│    headers（Authorization / x-api-key）                              │
│    connectTimeoutMs / readTimeoutMs                                  │
│    LlmCancelHandle                                                   │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.1 `LlmMessage` 结构

```text
LlmMessage
├─ role: SYSTEM / USER / ASSISTANT / TOOL_RESULT / OTHER
├─ text: String                 ← 可见正文
├─ thinkingText: String?        ← thinking 原文
├─ thinkingSignature: String?   ← Anthropic thinking 签名
├─ images: List<LlmImage>
├─ toolCalls: List<LlmToolCall>
└─ toolCallId: String?          ← 仅 TOOL_RESULT
```

Factory：

```java
// 不含 thinking
LlmMessage.assistant("可见正文", toolCalls);

// 正文和 thinking 分开
LlmMessage.assistant("可见正文", "thinking 原文", toolCalls);

// Anthropic 回传 thinking block 时需要签名
LlmMessage.assistant("可见正文", "thinking 原文", "signature", toolCalls);
```

### 2.2 `LlmResult` 结构

```text
LlmResult
├─ content: String              ← 可见正文，不含 thinking
├─ thinkingText: String?        ← thinking 原文
├─ thinkingSignature: String?   ← thinking 签名
├─ stopReason
├─ usage
├─ provider / modelId / thinkingLevel
├─ latencyMs / rounds
└─ toolCalls
```

`getRawContent()` 可以还原旧版拼接文本：

```java
String raw = result.getRawContent(); // thinking + "\n\n" + content
```

---

## 3. 三种协议的实际序列化

### 3.1 OpenAI Chat Completions

```json
{
  "model": "deepseek-reasoner",
  "max_tokens": 4096,
  "stream": true,
  "messages": [
    { "role": "system", "content": "规则" },
    { "role": "user", "content": "北京天气怎么样？" },
    {
      "role": "assistant",
      "content": "我查一下",
      "reasoning_content": "用户想知道北京天气，需要调用工具",
      "tool_calls": [
        {
          "id": "call_1",
          "type": "function",
          "function": { "name": "get_weather", "arguments": "{\"city\":\"北京\"}" }
        }
      ]
    },
    { "role": "tool", "tool_call_id": "call_1", "content": "晴，25°C" }
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "查询城市天气",
        "parameters": { "type": "object", "properties": { "city": { "type": "string" } } }
      }
    }
  ]
}
```

thinking 回传规则：

- `content` 只放可见正文。
- `thinkingText` 非空且 `thinking-format` 为 `deepseek`、`qwen` 或 `chat-template` 时，写入 `reasoning_content`。
- `thinking-format = reasoning-effort`（OpenAI 官方推理模型）时不回传 `reasoning_content`，避免发送不支持的字段。

### 3.2 OpenAI Responses

```json
{
  "model": "gpt-5",
  "instructions": "规则",
  "input": [
    {
      "role": "user",
      "content": [{ "type": "input_text", "text": "北京天气怎么样？" }]
    },
    {
      "type": "message",
      "role": "assistant",
      "status": "completed",
      "content": [{ "type": "output_text", "text": "我查一下", "annotations": [] }]
    },
    {
      "type": "function_call",
      "call_id": "call_1",
      "name": "get_weather",
      "arguments": "{\"city\":\"北京\"}"
    },
    {
      "type": "function_call_output",
      "call_id": "call_1",
      "output": "晴，25°C"
    }
  ],
  "tools": [
    {
      "type": "function",
      "name": "get_weather",
      "description": "查询城市天气",
      "parameters": { "type": "object", "properties": { "city": { "type": "string" } } }
    }
  ],
  "max_output_tokens": 4096,
  "stream": true,
  "store": false
}
```

说明：

- system 走 `instructions`，不在 `input` 里。
- assistant 的 thinking **不回传**。Responses 的 reasoning item 需要 `id` / `encrypted_content` 或 `previous_response_id`，当前无状态实现没有保存这些信息；`thinkingText` 仍保留在 `LlmResult` / 会话中，但不进入下一次 `input`。
- 图片是 `input_image`，文本是 `input_text`。

### 3.3 Anthropic Messages

```json
{
  "model": "claude-sonnet-4-5",
  "max_tokens": 8192,
  "stream": true,
  "system": "规则",
  "messages": [
    {
      "role": "user",
      "content": [{ "type": "text", "text": "北京天气怎么样？" }]
    },
    {
      "role": "assistant",
      "content": [
        {
          "type": "thinking",
          "thinking": "用户想知道北京天气，需要调用工具",
          "signature": "EqQBCgIYAh..."
        },
        { "type": "text", "text": "我查一下" },
        {
          "type": "tool_use",
          "id": "tool_1",
          "name": "get_weather",
          "input": { "city": "北京" }
        }
      ]
    },
    {
      "role": "user",
      "content": [
        { "type": "tool_result", "tool_use_id": "tool_1", "content": "晴，25°C" }
      ]
    }
  ],
  "tools": [
    {
      "name": "get_weather",
      "description": "查询城市天气",
      "input_schema": { "type": "object", "properties": { "city": { "type": "string" } } }
    }
  ],
  "thinking": { "type": "enabled", "budget_tokens": 16384 }
}
```

thinking 回传规则：

- assistant 的 `thinking` block 放在 `text` block 前面。
- 只有 `thinkingText` 和 `thinkingSignature` 都存在时才发送 thinking block；Anthropic 要求回传的 thinking block 带签名。
- 多个连续工具结果会被合并成同一条 `user` 消息里的多个 `tool_result` block。

### 3.4 图片消息

图片只允许出现在 `USER` / `OTHER` 消息里，且模型配置的 `input` 必须包含 `image`。`LlmImage` 接受裸 base64 或 `data:<mime>;base64,<data>`，后者会在构造时去掉前缀。

同一个 `LlmMessage` 在三种协议里的形状：

```json
// OpenAI Chat Completions
{"role":"user","content":[
  {"type":"text","text":"描述这张图"},
  {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}
]}

// OpenAI Responses
{"role":"user","content":[
  {"type":"input_text","text":"描述这张图"},
  {"type":"input_image","detail":"auto","image_url":"data:image/png;base64,AAAA"}
]}

// Anthropic Messages
{"role":"user","content":[
  {"type":"text","text":"描述这张图"},
  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}
]}
```

说明：

- 文本为空时只发送图片分片；多张图片按调用方顺序追加。
- Anthropic 会合并相邻同角色消息，合并后按各源消息的块顺序拼接。
- 非 base64 data URL、空 MIME、非 `image/*`、空数据在网关发请求前被拒绝。
- 网关不做压缩/缩放/大小校验，也不估算图片 token；usage 以供应商返回为准。
- **图片不进入会话持久化**：`AgentSessionMessage` 不保存图片，`AgentSessionLoop` 首轮也会用会话历史覆盖 `call.getMessages()`。图片输入请走 `LlmGateway.one/round/toolLoop` 或 `AgentLoop.run`。

---

## 4. 一轮工具循环后，上下文怎么增长

```text
第 1 轮发送：
  system
  user: "北京天气怎么样？"

模型返回：
  content = "我查一下"
  thinkingText = "用户想知道北京天气，需要调用工具"
  toolCalls = [get_weather(city=北京)]

第 2 轮发送（全量）：
  system
  user: "北京天气怎么样？"
  assistant:
    text = "我查一下"
    thinkingText = "用户想知道北京天气，需要调用工具"
    toolCalls = [get_weather(city=北京)]
  tool_result: call_id=c1, text="晴，25°C"

模型返回：
  content = "北京今天晴，25°C"
  thinkingText = "工具返回了天气，直接回答"

第 3 轮发送（如果还需要继续）：
  system
  user: ...
  assistant: ...
  tool_result: ...
  assistant: "北京今天晴，25°C" + thinkingText
```

每轮发送的是**完整转录**。LLM API 是无状态的，不能只发增量。

---

## 5. 哪些东西不会进入 LLM 上下文

| 内容 | 原因 |
| --- | --- |
| `LlmCall.bizId` | 只用于 `biz-id` 治理校验 |
| `provider` / `profile` 名称 | 只用于解析，最终只发 `model id` |
| `apiKey` | 只放在 HTTP header |
| `connectTimeoutMs` / `readTimeoutMs` | HTTP 层 |
| `LlmCancelHandle` | HTTP 层 |
| `LlmResult.usage` / `stopReason` / `latencyMs` / `rounds` | 结果元数据 |
| `AgentSession.userId` / `sessionId` / `version` / `updatedAt` | 持久化字段 |
| `AgentSessionMessage.id` | 持久化字段 |
| `thinkingSignature` | 只在 Anthropic 的 thinking block 中使用；其他协议不发 |
| `tools` | `one` / `oneByStream` 不带工具；只有 `round` / `toolLoop` 发 |

---

## 6. 生命周期对出站上下文的影响

| 钩子 | 对发给 LLM 的上下文的影响 |
| --- | --- |
| `beforeRun` | 可替换 `tools`、修改 `ctx.messages`，影响本轮所有请求 |
| `beforeModel` | 每轮发请求前可再改消息和工具 |
| `afterModel` | 改写 assistant 可见正文；`thinkingText` 不变 |
| `beforeTool` | 改写工具参数；拒绝时拒绝原因作为 tool result 进入下一轮 |
| `afterTool` | 改写工具返回文本；下一轮发送改写后的结果 |
| `afterTurn` | 只决定 stop / continue / modelDecides，本身不改消息 |
| `afterRun` | 循环已结束，不影响任何请求 |

注意：`afterModel` 只改可见正文。如果 hook 改了正文但希望 thinking 也变化，需要显式构造新的 `LlmResult` 或修改消息，当前 `AgentModelOutput` 只暴露 `content`。

---

## 7. 会话持久化

`AgentSessionMessage` 与 `LlmMessage` 的字段对应：

| 运行时 `LlmMessage` | 持久化 `AgentSessionMessage` |
| --- | --- |
| `text` | `text` |
| `thinkingText` | `thinkingText` |
| `thinkingSignature` | `thinkingSignature` |
| `toolCalls` | `toolCalls` |
| `toolCallId` | `toolCallId` |

`AgentSessionCodec` 会序列化 `thinkingSignature`。续聊时：

- `AgentSession.messagesToLlm(pending, false)`：默认丢弃 thinking。
- `AgentSession.messagesToLlm(pending, true)`：把 thinking 和签名放进独立字段，**不再拼回正文**。

---

## 8. 行为变化与迁移

如果你从旧版本升级：

1. `LlmResult.getContent()` 现在只返回可见正文，不再包含 `thinking + "\n\n"` 前缀。
   - 需要旧文本：用 `getRawContent()`。
   - 需要 thinking：用 `getThinkingText()`。
2. `LlmMessage.assistant(text, toolCalls)` 仍然可用，但不带 thinking。
   - 需要回传 thinking：用 `assistant(text, thinkingText, toolCalls)` 或带签名的四参版本。
3. 协议回传是**按能力**的：
   - OpenAI Completions：兼容 `reasoning_content` 的 `thinking-format` 才回传。
   - Anthropic：只有同时有 thinking 和 signature 才回传 thinking block。
   - Responses：不回传 thinking。
4. 会话 JSON 新增可选字段 `thinkingSignature`，旧数据不受影响。

---

## 9. 代码地图

| 能力 | 文件 |
| --- | --- |
| 循环构建本轮消息 | `ai-lite-core/src/main/java/.../assembly/AgentLoop.java` |
| 消息折叠与校验 | `ai-lite-core/src/main/java/.../llm/LlmTexts.java` |
| 请求构造 | `ai-lite-core/src/main/java/.../llm/LlmGateway.java` |
| 消息模型 | `ai-lite-core/src/main/java/.../llm/LlmMessage.java` |
| 结果模型 | `ai-lite-core/src/main/java/.../llm/LlmResult.java` |
| OpenAI Completions 序列化 | `ai-lite-core/src/main/java/.../transport/OpenAiCompletionsTransport.java` |
| OpenAI Responses 序列化 | `ai-lite-core/src/main/java/.../transport/OpenAiResponsesTransport.java` |
| Anthropic 序列化 | `ai-lite-core/src/main/java/.../transport/AnthropicMessagesTransport.java` |
| 会话消息模型 | `ai-lite-core/src/main/java/.../session/AgentSessionMessage.java` |
| 会话编解码 | `ai-lite-core/src/main/java/.../session/AgentSessionCodec.java` |
