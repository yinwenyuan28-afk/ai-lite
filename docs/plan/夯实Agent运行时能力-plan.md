---
status: done
created: 2026-10-26 23:58
updated: 2026-10-27 03:20
---

# 夯实 Agent 运行时能力 **（15/15 done）**

补齐 `ai-lite` 投入生产前缺失的六项运行时能力——流式输出、工具结果截断、上下文预算与压缩、会话列举、会话用量记账、编排入口参数收敛——并以 typed entries 数据模型支撑后续演化。

---

## 背景与问题

上一轮（`夯实对外契约冻结-plan.md`）解决的是"契约稳不稳"：公开类有没有出界、删方法会不会被报警、japicmp 能不能拦住。本轮解决的是另一个问题：**这套 API 够不够业务用**。

稳定和够用是两件事。一个接口可以既二进制兼容又完全没法完成业务。用户指出这一层此前没审过，本轮补齐。

代码级核查发现的六个缺口（全部已用源码确认，非文档推断）：

| 编号 | 缺口 | 证据 |
| --- | --- | --- |
| G1 | 编排层不支持流式 | `AgentLoop.run`/`runAsync`（`:153`/`:416`/`:432`）与 `AgentSessionLoop.resume`/`resumeAsync`（`:109`/`:135`）签名中均无 `onDelta`；而 `LlmGateway` 有四个流式入口 |
| G2 | 工具结果无截断 | `AgentLoop.java` 内检索 `substring\|truncat\|MAX_.*RESULT` 为空；`execute()` 原样返回后直接 `add` |
| G3 | 上下文窗口配置无效 | `AiLiteConfig.Model.contextWindow` 与 `ResolvedModel.getContextWindow()` 存在，但运行期**零调用点**；`LlmStopReason.CONTEXT_LENGTH_EXCEEDED` 只有枚举定义，无代码产生 |
| G4 | 无会话列举 API | `AgentSessionStore` 只有 `find`/`save`/`delete`/`exists` + 4 个消息级 default |
| G5 | 会话不记用量 | `AgentSessionMessage` 只有 7 个字段（id/role/text/thinkingText/thinkingSignature/toolCalls/toolCallId），无 usage |
| G6 | 编排入口参数爆炸 | `AgentSessionLoop.resume` 已有 **8 个位置参数**，其中 5 个可传 null |

**影响**：长会话必然撞供应商 context 上限且无自救手段；单个返回大日志的工具即可打爆上下文和费用；聊天类业务走编排层没有逐字输出；多轮对话产品做不了"会话列表"。

**一个尚未核实的判断**：这三个编排抽象 `AgentSpec`/`WorkflowSpec`/`Assembly` 全仓引用数为 0，说明这套 API 还没被真实业务用过（这是"缺口判断可能失真"的风险来源，已记入风险表）。

---

## 目标与验收

### 目标

1. 长会话不会撞上下文上限：超限前能检测、能压缩，压缩不丢原始数据
2. 单条工具输出不会撑爆上下文
3. 编排层能在模型生成时逐段回调（同步）
4. 会话可列举、可统计成本
5. 编排入口参数可演化，不再堆位置参数
6. 会话数据模型支持后续新增条目类型，且旧 jar 读到新类型不会崩（向前兼容）

### 验收标准

| 验收项 | 通过标准 |
| --- | --- |
| 预算保护生效 | 估算超限时抛 `CONTEXT_OVERFLOW`，错误消息含估算值与上限 |
| 压缩不丢数据 | 压缩后 `messages` 条目一条不少；关闭开关后投影立即返回完整历史 |
| 压缩不循环 | 默认配置下（32768 窗口）压缩后估算值低于触发线，不会每轮重压 |
| 截断覆盖全路径 | 串行/并行/异步三条路径的落盘内容均为截断文本 |
| 流式可用 | `runStream` 的 `CONTENT` delta 拼接等于 `getContent()` |
| 旧 API 行为不变 | 现有 832 个测试中，除因 `AgentSession` 内部类型变化需调整的少数用例，其余不改仍通过 |
| 契约不破坏 | `mvn verify -Pcontract-check` 通过 |
| 新表结构自洽 | 全新库执行 `ai-lite-session.sql` 即可建出完整结构，无需任何 ALTER |

---

## 范围

**涉及模块**：`ai-lite-core`（编排、会话、网关、配置）、`ai-lite-session-jdbc`、`ai-lite-session-redis`、`ai-lite-spring-boot-starter`、`ai-lite-demo`（配置示例）。

**涉及数据**：`AI_LITE_SESSION`（新增派生列）、`AI_LITE_LLM_CALL`（新增 `KIND` 列）、`MESSAGES_JSON` 内容形态（纯数组 → typed entries 数组）。

---

## 能力清单

| 能力域 | 能力 | 说明与验收口径 |
| --- | --- | --- |
| **上下文管理** | 预算检测 | 每轮发送前估算 token，超限抛 `CONTEXT_OVERFLOW`；窗口未知时跳过 |
| | 自动压缩 | 超限时生成摘要并追加 `CompactionEntry`；默认开启 |
| | 重复压缩 | 摘要起点为上次保留边界，不重复摘要全部历史 |
| | 分块摘要 | 待摘要内容超过摘要模型窗口时自动分块、迭代摘要 |
| | 并发抢占 | 多节点同时压缩时只允许一个执行，另一个跳过 |
| **工具治理** | 结果截断 | 单条工具结果超上限时截断并标记；覆盖 5 个写回点 |
| **流式输出** | 同步流式 | `AgentLoop.runStream` + `AgentDeltaListener`，区分正文/推理增量 |
| **会话能力** | 用量记账 | 会话级 `totalTokens`，只统计成功轮次 |
| | 会话列举 | `listByUser(userId, offset, limit)` + `AgentSessionSummary` |
| **数据模型** | typed entries | 条目基类 + 4 种类型 + 注册表 codec + `getMessages()` 兼容视图 |
| | 扩展协议 | 未知 type 跳过、`x-` 前缀保留给业务 |
| **入口演化** | 参数收敛 | `ResumeOptions` builder，旧 8 参方法保留并转调 |

---

## 不做的事

### 本轮明确不做

| 项 | 理由 |
| --- | --- |
| **异步流式**（`runStreamAsync` / `resumeStreamAsync`） | 用户决策：后端任务优先，同步版本已覆盖 SSE 场景。`invokeAsync` 已带 stream/onDelta 参数，将来加不返工 |
| **append-only 子表** | 方案已完整写就（附录 B），触发即实施。不做的理由是并发语义重定义 + 读取路径重写 + 降级丢数据风险，且写放大未被真实规模验证 |
| **分支 / 树结构** | ai-lite 面向服务端多轮对话，不是 CLI 的"回到某轮回溯重说"场景 |
| **pi 的其余 7 种条目类型** | `label`/`model_change`/`thinking_level_change`/`branch_summary`/`context_edit`/`custom`/`custom_message`，等明确需求 |
| **真实 tokenizer** | JDK 8 生态无可靠通用实现，引入 BPE 库会让依赖面变宽且对非 OpenAI 模型不准 |
| **压缩历史回放** | 只保留每条 compaction 条目，不做"查看第 N 次压缩"的 UI 能力 |

### 容易被误认为包含

- **图片与工具声明的 token 估算**：v1 只估文本，图片 base64 和 tool schema 不计入
- **`LlmStopReason.CONTEXT_LENGTH_EXCEEDED` 的产生**：该枚举当前无代码产生，本轮仍不产生（供应商侧超限仍映射为 `LENGTH`）
- **自定义存储的派生列维护**：走 `appendMessage` 等 default 方法改消息时，`MESSAGE_COUNT`/`TOTAL_TOKENS` 不会自动更新

---

## 设计原则

1. **默认开启的数据变更必须可逆**。压缩默认开启（用户决策），因此必须采用"保留原始 + 构建时投影"，而不是"原地替换"。关掉开关要能立刻恢复完整历史。
2. **不静默修改用户数据**。超限就报错，不偷偷丢历史；压缩失败也报错，不降级成截断。
3. **默认值必须自洽**。阈值组合在启动时校验，不满足直接启动失败，不留到运行期变成死循环。
4. **扩展不破坏旧版本**。未知条目类型跳过而非报错；新增会进入上下文的类型必须走大版本。
5. **改动面靠兼容层压住**。`getMessages()` 保持"只返回消息"的语义，避免 39 处调用点和 25 处测试断言全部改动。
6. **沿用项目既有模式**。配置走 `AiLiteConfig` 嵌套类 + `@ConfigurationProperties`；自动装配走 `spring.factories` + `@ConditionalOn*`；不引入新的设计范式。

---

## 已确认决策

| 决策 | 来源与确认时间 | 选择 | 理由与后果 | 被否选项 |
| --- | --- | --- | --- | --- |
| Q1 截断后的落盘内容 | 用户 2026-10-26 | 存**截断文本** | ai-lite 无归档机制，存原文会让 `MESSAGES_JSON` 无限膨胀；原始日志应由业务日志系统承载 | 存原文（会话体积不可控） |
| Q2 估算超限行为 | 用户 2026-10-26 | 抛 `CONTEXT_OVERFLOW` | 静默修改对话历史比报错更难排查 | 静默丢旧消息 |
| Q3 自动压缩默认值 | 用户 2026-10-26 | **默认开启** | 上下文超限是必然故障，不是可选优化。代价是默认会多一次 LLM 调用并改写模型看到的历史，因此必须配套"保留原始 + 投影"（见设计原则 1）与七项防护（见方案设计） | 默认关闭 |
| Q4 取消/失败轮 token | 用户 2026-10-26 | 账单表**计入**，会话累计**不计入** | `finish` 遇 ABORTED 直接 return 不保存（SL-08 刻意保证），异常路径不进 `finish`，无处可写。账单口径由 `LlmCallRecord` 覆盖 | 计入会话累计（无处落盘） |
| D1 条目化 | 用户 2026-10-26 晚 | 实现 `"type":"compaction"`，与 pi 条目化对齐 | 超越"会话字段"方案，可保留多次压缩历史，为 append-only 铺路 | 会话字段（无法表达多次压缩） |
| D2 append-only | 用户 2026-10-26 晚 | 本轮不做，但**写出完整实施方案** | 见附录 B；触发条件见附录 B.7 | 本轮一起做 |
| D3 流式范围 | 用户 2026-10-26 晚 | **同步优先，异步延后**；两类场景都要，后端任务优先 | 同步版已覆盖 SSE 场景；少一半线程约束要保证和测试 | 同步+异步一起做 |
| D4 条目扩展性 | 用户 2026-10-26 晚 | 支持后续扩展，定三层协议 | 未知 type 跳过 + `x-` 前缀保留 + 注册表 codec | 封闭枚举 |
| **D5 老数据兼容** | **用户 2026-10-27** | **不做**。项目尚未正式使用，无需兼容历史数据 | 省掉：`type` 缺失的兜底、迁移脚本段、启动期列探测、双路径读取、兼容测试。代价：若有人用 0.1.x 存过会话，升级后读旧数据会抛异常（用户已接受）。**但保留"未知 type 跳过"的向前兼容**，这跟老数据兼容是两件事 | 做完整兼容（迁移段 + 双路径 + 列探测） |
| **D6 会话级系统提示** | **用户 2026-10-27** | **数组开头连续的 SYSTEM 条目 = 前缀区，永不参与压缩** | 不加新字段、不改数据模型，复用现有 `system` role。投影时前缀区原样放最前，压缩切点从其之后算起。新增 `addSystemInstruction` 做幂等写入。比 pi 的 `systemMessage` checkpoint 少一份冗余存储 | pi 式 checkpoint 字段；`AgentSession.systemPrompt` 独立字段 |

> **区分两类兼容**：
> - **API 兼容**（保留）：0.1.7 已发布到 Central，`japicmp` 门禁要求不破坏二进制兼容。`AgentSession.getMessages()` 签名不变。
> - **数据兼容**（不做）：老 JSON 格式不需要能读，表结构直接改建表语句。

**实施授权**：已获得开工授权（用户 2026-10-27「开始执行计划」）。计划状态 `executing`。

### agent 实现裁决

| 事项 | 选择与理由 | 依据 |
| --- | --- | --- |
| 压缩发生在哪一层 | `AgentSessionLoop`，不是 `AgentLoop` | `AgentLoop` 操作的是 `AgentLoopContext.copy()` 副本（`AgentLoop.java:166`），`finish` 落盘的是 `prepared.pending`（`:222`），改副本不生效 |
| 流式回调接口形状 | 单抽象方法 `AgentDeltaListener` + `DeltaKind` | 三方法全 default 的接口无法 lambda；传输层会混发 reasoning 与分隔符，必须区分类型 |
| 压缩条目存储位置 | `MESSAGES_JSON` 内的 typed entries，不是独立列 | 用户决策 D1；独立列无法表达多次压缩 |
| 估算器算法 | 按字符类型加权（ASCII 0.25、CJK 0.6 token/字符） | `字符/2.5` 对中文低估 40%~60%，而这是中文为主的库；不引入 tokenizer |
| 派生列维护点 | `AgentSessionLoop.finish` | `AgentSessionStore` 是 SPI，不该让每个实现各自算 |
| JDBC 表结构 | 改建表语句；启动期校验缺列则失败 | `find` 是显式列清单（`:45-48`），加列后所有读写依赖新结构。**因不做数据兼容，直接改建表脚本，不提供迁移段** |

---

## 方案设计

### 1. typed entries 数据模型

#### 结构

```java
public abstract class AgentSessionEntry {
    private String id;
    private Date createTime;
    public abstract String type();
}

public final class AgentSessionMessage extends AgentSessionEntry { /* 现有 7 字段不变 */ }
public final class CompactionEntry      extends AgentSessionEntry { /* summary, firstKeptEntryId, tokensBefore, summaryModel, summaryTokens */ }
public final class SessionInfoEntry     extends AgentSessionEntry { /* name */ }
public final class UsageEntry           extends AgentSessionEntry { /* kind, provider, model, inputTokens, outputTokens, totalTokens */ }
```

#### JSON 形态

```json
[{"type":"message","id":"m1","role":"USER","text":"..."},
 {"type":"message","id":"m2","role":"ASSISTANT","text":"..."},
 {"type":"compaction","id":"c1","summary":"用户讨论了X，确认了Y",
  "firstKeptEntryId":"m6","tokensBefore":28000,
  "summaryModel":"deepseek/deepseek-chat","summaryTokens":180},
 {"type":"message","id":"m7","role":"USER","text":"..."}]
```

#### 兼容层（关键取舍）

**保留：源码/二进制兼容视图（不能省）**

```java
AgentSession
  ├─ getEntries()  → List<AgentSessionEntry>    全量（含 compaction）
  └─ getMessages() → List<AgentSessionMessage>  过滤视图（只返 message）
```

已核实影响面：`getMessages()/setMessages()` 主源码 **39 处**、测试断言 **25 处**。保留过滤视图后基本不动。

> 这说明的是**内部改动面控制**，不是"对外兼容"。真正的对外兼容是通过 `japicmp` 保证的：`AgentSession.getMessages()` 的**签名**不变，只是内部实现改为过滤。这一条必须保留，因为 0.1.7 已发布到 Central。

#### 陷阱：`setMessages` 会静默丢掉压缩条目（必须在 T3 修掉）

**已核实的真实缺陷，有三个入口：**

**入口 1：`AgentSessionStoreSupport` 的三个 default 方法**

```java
// appendMessage（:66-74）
List<AgentSessionMessage> messages = new ArrayList<AgentSessionMessage>(session.getMessages());
messages.add(message);
session.setMessages(messages);     // ← 只写回 message，compaction 条目被丢弃
store.save(session);
```

`replaceMessage`（`:78-92`）、`deleteMessage`（`:97-110`）同样是这个模式。

**入口 2：`AgentSessionCodec.encodeSession`**

```java
// :61
root.set("messages", toMessageArray(session.getMessages()));   // ← 只序列化 message
```

Redis 缓存走 `encodeSession`，所以**只要缓存命中一次再写回，compaction 条目就没了**。

**入口 3（最致命）：`AgentSessionLoop.finish`**

```java
// :223-230
AgentSession toSave = new AgentSession();
toSave.setUserId(prepared.session.getUserId());
toSave.setSessionId(prepared.session.getSessionId());
toSave.setBizId(...);
toSave.setMessages(prepared.pending);       // ← 只复制 message，压缩条目丢失
toSave.setVersion(prepared.session.getVersion());
store.save(toSave);
```

**`finish` 是每轮续聊的必经路径**，所以压缩条目**每次保存都会被丢掉**。这意味着压缩实际上完全不生效——它算了摘要、花了钱、写进了内存里的 session，然后 `finish` 重建对象时把它扔了。

**这是整个方案能否成立的关键点，必须在 T3/T8 一起修掉。**

**后果汇总**：业务调过一次 `appendMessage`、或 Redis 缓存回写一次、或任意一轮续聊走到 `finish`——会话里的 `CompactionEntry` 就消失，投影退回完整历史，上下文立刻超限，**而且没有任何报错**。

**修复方案（T3 必须包含）**：

```java
// 位置感知写回：保留非 message 条目的原位置
static void mergeMessages(AgentSession session, List<AgentSessionMessage> newMessages) {
    List<AgentSessionEntry> result = new ArrayList<>();
    int cursor = 0;
    for (AgentSessionEntry entry : session.getEntries()) {
        if (entry instanceof AgentSessionMessage) {
            if (cursor < newMessages.size()) {
                result.add(newMessages.get(cursor++));
            }
            // 新 message 少于原 message 时，多余的旧 message 直接丢弃（删除语义）
        } else {
            result.add(entry);        // 非 message 原样留原位
        }
    }
    while (cursor < newMessages.size()) {
        result.add(newMessages.get(cursor++));   // 多出来的追加到末尾
    }
    session.setEntries(result);
}
```

三处改法：

| 位置 | 改法 |
| --- | --- |
| `AgentSessionStoreSupport` 三个 default 方法 | 改调 `mergeMessages(session, messages)` |
| `AgentSessionCodec.encodeSession` | 改 `toEntryArray(session.getEntries())` |
| `AgentSessionLoop.finish` | **不要 `new AgentSession()` 重建**，改为 `AgentSession.copyOf(prepared.session)` 后 `mergeMessages(toSave, prepared.pending + recorder)` |

> **为什么必须位置感知**：如果简单地把非 message 条目提到开头，会破坏"前缀区 = 数组开头连续 SYSTEM"的判定。压缩条目提到开头后，前缀区判定会把 compaction 当作前缀边界，投影逻辑全乱。

**测试必须覆盖**：

- 调 `appendMessage` 后，`getEntries()` 里 compaction 条目仍存在且**位置不变**
- `encodeSession` → `decodeSession` 往返后，compaction 条目仍在
- Redis 缓存 put → get 后，compaction 条目仍在
- **压缩后走一轮 `resume` 到 `finish`，读回的会话仍含 compaction 条目**（最关键）
- 压缩后调 `replaceMessage`，投影仍应用压缩（不退回完整历史）

**去掉：老数据格式兼容（用户确认不需要）**

**用户明确：项目尚未正式使用，不需要兼容老数据。** 因此以下负担全部去掉：

| 原设计 | 处理 |
| --- | --- |
| `type` 缺失时视为 `message`（兼容纯 message 数组） | **删除**。`type` 缺失视为**非法数据**，解码抛 `LlmCallException` |
| 老数据迁移脚本段（`ai-lite-session.sql` 的 ALTER 段） | **删除**。直接改建表语句，不提供迁移 |
| 启动期列存在性校验 + ALTER 提示 | **删除**。表结构不对就建表，不做运行时探测 |
| "老数据未迁移时走双路径读取" | **删除**。读不到预期结构就报错 |
| 兼容测试用例（纯 message 数组解码） | **删除** |

**代价与边界**：如果有人用 0.1.x 跑过会话并存了数据，升级后读旧数据会抛异常。这个代价用户已明确接受。

**但要保留一条**：`decodeMessages`/`decodeSession` 遇到**未知 `type` 仍然跳过**（不是报错）。这跟"老数据兼容"不是一回事——它是**向前兼容**，保证将来加了新条目类型后，旧 jar 不会崩。这条必须留。

#### 扩展协议（三层）

| 层 | 规则 |
| --- | --- |
| 读 | **未知 `type` 跳过** + DEBUG 日志，不报错（前向兼容，必须保留）；**`type` 缺失视为非法**，抛异常（不兼容老格式） |
| 写 | 本库可新增类型；**新增会进入 LLM 上下文的类型必须走大版本**（老版本跳过它会导致上下文不同） |
| 业务扩展 | `x-` 前缀永久保留，本库永不解释 |

实现用注册表而非 `switch`：

```java
static {
    register("message",      MessageCodec.INSTANCE);
    register("compaction",   CompactionCodec.INSTANCE);
    register("session_info", SessionInfoCodec.INSTANCE);
    register("usage",        UsageCodec.INSTANCE);
}
```

#### 冻结承诺

`message` / `compaction` / `session_info` / `usage` 四种语义 1.0 后冻结。

### 2. 上下文预算与压缩

#### 投影规则

**投影是纯计算，不是存储状态。** 每次发请求前现场算：

```
数据库里存的（唯一事实源，从不改写）
┌──────────────────────────────────────────────────┐
│ [system:你是助手][system:输出JSON][user1][asst1] │
│ [user2][asst2][compaction:{summary,firstKept}]   │
│ [user3][asst3]                                    │
└──────────────────────────────────────────────────┘
                    │
                    │  ← 每次发请求前现场投影（不落盘）
                    ▼
本次发给模型的
┌──────────────────────────────────────────────────┐
│ [system:你是助手]                                 │  ← 前缀区，永不压缩
│ [system:输出JSON]                                 │
│ [system:摘要]                                     │  ← compaction.summary
│ [user2][asst2][user3][asst3]                     │  ← firstKept 之后
└──────────────────────────────────────────────────┘
```

算法：

```
1. 取出前缀区：数组开头连续的所有 SYSTEM 条目（见下节）
2. 从数组末尾往前找【最后一条】CompactionEntry
     ├─ 没有 → 剩余部分 = 前缀区之外的全部 message 条目
     └─ 找到 c → 剩余部分 = [system(c.summary)] + 从 c.firstKeptEntryId 往后的 message 条目
3. 结果 = 前缀区 + 剩余部分
```

**不缓存投影结果**：改了 `keepRecentTokens` 等配置，下一轮立刻按新配置算，无需数据迁移。pi 同样如此（它连 `tokensBefore` 都在写新条目时重算）。

#### 估算对象必须包含本轮新消息

**这是个容易实现错的地方。** `prepare()` 已经把本轮新消息拼进 `pending`（`AgentSessionLoop.java:192-201`），压缩发生在 `prepare` 之后，所以：

```
估算与投影的对象 = 前缀区 + [已投影的历史] + 本轮 newUserTurns

不是：
  只估历史        ← 会漏掉本轮新消息，可能压完还是超
  只估新消息      ← 完全没意义
```

**必须估"这一轮真正要发出去的全部内容"**，否则会出现"压缩了但这次请求仍然超限"。

**同理**，压缩后的 `firstKeptEntryId` 必须指向**条目数组里的真实 id**——如果本轮新消息还没写进 `session.getEntries()`（它们在 `pending` 里），压缩时必须先把它们合并进去再算，或保证切点只在历史部分。

**实现建议**：`compactIfNeeded` 接收的是 `List<AgentSessionEntry> entriesForEstimate`（历史 + 本轮新消息），而不是只接收 `session`。具体做法见任务 T8。

#### 关键区分：投影不花钱，摘要才花钱

**这是本节最容易误解的地方，必须写清：**

```
┌─────────────────────────────────────────────────────────────┐
│  摘要（CompactionEntry.summary）                            │
│  ─────────────────────────────                              │
│  生成方式：调用 LLM 💰                                       │
│  何时生成：只在"当前投影估算 > 触发线"时生成一次            │
│  是否持久化：【是】作为 CompactionEntry 落盘                 │
│  后续复用：永久复用，绝不重算                                │
├─────────────────────────────────────────────────────────────┤
│  投影（List<LlmMessage>）                                   │
│  ─────────────────────────────                              │
│  生成方式：纯内存计算（遍历条目 + 拼装）                    │
│  何时生成：每次发请求前                                      │
│  是否持久化：否                                              │
│  后续复用：不缓存，每次重算（因为配置可能变）               │
└─────────────────────────────────────────────────────────────┘
```

**时间线示例**（32768 窗口，触发线 24576）：

```
第 1 次续聊   估算 20000  → 未超线 → 投影 → 发请求        💰 1 次模型调用
第 2 次续聊   估算 24000  → 未超线 → 投影 → 发请求        💰 1 次
第 3 次续聊   估算 26000  → 超线了！
                            ├─ 调 LLM 生成摘要            💰 +1 次（摘要）
                            ├─ 落盘 CompactionEntry
                            └─ 投影后 14000 → 发请求      💰 1 次
第 4 次续聊   估算 15000  → 未超线 → 投影 → 发请求        💰 1 次
第 5 次续聊   估算 18000  → 未超线 → 投影 → 发请求        💰 1 次
...
第 N 次续聊   估算 25000  → 超线了！再压一次              💰 +1 次（摘要）
```

**关键点**：第 3 次生成的摘要，在第 4、5、…、N-1 次续聊中被反复使用，**没有重新调用 LLM**。这就是为什么 `keepRecentTokens + summaryMaxTokens < 触发线` 这条不变式重要——余量越大，需要重新压缩的频率越低。

> **一个推论**：如果 `CompactionEntry` 落盘失败（比如事务回滚），下次续聊会重新触发压缩、重新花钱。所以压缩与 `finish` 的落盘必须在同一事务里，或者压缩成功但落盘失败时要明确报错，不能静默丢弃。

#### 前缀不可压缩区（会话级系统提示的实现方式）

**问题**：ai-lite 此前没有"会话级系统提示"概念。已核实 `AgentSessionLoop.prepare` 里**没有任何 SYSTEM 的特殊处理**（grep 无匹配），`ensureSession` 只创建空消息列表。后果：

- 业务只能把 system 消息塞进 `newUserTurns`，每轮追加一条，会话里越积越多
- 压缩按 user 边界切点时，开头的 system 被划进摘要区，模型"忘了自己是谁"

**方案：数组开头连续的 SYSTEM 条目 = 前缀区，永不参与压缩。**

```
[m1: system "你是简历筛选助手"]     ┐
[m2: system "输出必须是 JSON"]      ┘ 前缀区：永远保留，不进摘要
[m3: user "帮我看看这份简历"]        ┐
[m4: assistant "..."]                │ 可压缩区
...                                  ┘
```

规则：

| 规则 | 内容 |
| --- | --- |
| 前缀区定义 | 从条目 0 开始**连续**的 SYSTEM 条目；遇到第一条非 SYSTEM 即结束 |
| 投影 | 前缀区原样放最前，不参与"从 firstKeptEntryId 取"的逻辑 |
| 压缩切点 | 从**最后一条前缀 system 之后**开始往回数；前缀永远不进摘要区 |
| 摘要输入 | 不含前缀区内容（避免每轮重复摘要同一段系统提示） |
| 混合顺序 | `LlmTexts.fold` 会把所有 SYSTEM 合成一条，顺序为「前缀在前、摘要在后」——语义正确 |

**新增 API（放在 `AgentSessionLoop`）**：

```java
/**
 * 在会话的前缀区追加一条系统指令。已存在相同文本则跳过（幂等）。
 *
 * <p>前缀区的条目永不参与压缩，适合放"角色设定 / 输出格式 / 业务约束"
 * 这类整场对话都必须成立的内容。调用方应在首次续聊前调用一次。</p>
 *
 * @param userId    用户 id
 * @param sessionId 会话 id
 * @param text      系统指令正文
 * @return 是否实际写入（已存在时为 false）
 */
public boolean addSystemInstruction(String userId, String sessionId, String text);
```

实现要点：

```
读会话 → 扫描开头连续 SYSTEM 条目
    ├─ 已有相同 text → 返回 false（幂等，防重复提交累积）
    └─ 否则 → 在最后一条前缀 SYSTEM 之后插入一条新的 SYSTEM
              （注意：插在"前缀区末尾"，不是数组末尾）
```

**为什么不用 pi 的 `systemMessage` checkpoint 字段**：pi 的 `CompactionEntry.systemMessage` 会在每次压缩时快照一份完整系统提示。前缀区方案不存快照——系统提示就在条目数组开头，投影时直接取，天然是最新的。少一份冗余存储，也少一个"快照与真实值不一致"的失效点。

**边界**：

- 前缀区**只在数组开头连续**才算。中途插入的 system（比如工具结果后的提示）按普通消息处理，会被压缩。
- 若业务在前缀区之后又追加了 system，它属于可压缩区，会在投影时被 `fold` 合并到最前面但内容会随压缩消失。
- 前缀区过长会一直占上下文，不计入可压缩预算——这是有意的，系统提示本来就该常驻。

#### 重复压缩起点

```
首次压缩：摘要输入 = [前缀区之后 .. 新切点)
重复压缩：摘要输入 = 上一条 compaction.summary（迭代上下文）
                   + [上次 firstKeptEntryId .. 新切点)
```

**必须这样**：否则每次从数组开头摘要，输入量随会话增长，最终退化成"摘要请求自己超窗口"。

**注意：两种情况的摘要输入都不含前缀区。**

#### 切点三条硬规则

1. 从**最后一条前缀 system 之后**开始往前累计，达到 `keepRecentTokens` 即停
2. 切点落在 user 消息边界
3. **绝不拆开 toolCall 与 toolResult**（只留一个模型会报错）

特殊情况：单条 user span 就超预算时，改在更早的 user 边界切；切点不能越过前缀区边界（即最早只能切到"前缀区之后的第一条"）；若前缀区之后仍无法切分，抛 `CONTEXT_OVERFLOW`（不自旋）。

#### 阈值不变式（启动期校验）

```
不变式 1：keepRecentTokens + summaryMaxTokens < contextWindow - reserveTokens
不变式 2：contextWindow <= reserveTokens → 视为"窗口未知"，禁用预算检查
不变式 3：单次 run 压缩次数上限（默认 3）
不变式 4：压缩后估算 < 触发线 × 0.9
```

默认值（以 32768 窗口为例）：

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `reserve-tokens` | 8192 | 留 1/4 给模型回复 |
| `keep-recent-tokens` | 12000 | 必须 < 32768-8192=24576 |
| `summary-max-tokens` | 2048 | |
| 触发线 | 24576 | |
| 压缩后目标 | ≈14048 | < 触发线，不变式 1 满足 |

> 初版曾用 `reserve=16384` + `keep=20000`，在 32768 窗口下触发线 16384 < 保留 20000，**必然每轮重压**；窗口 ≤16384 时每次请求都抛异常。这是审查抓出的确定性数值矛盾。

#### 分块摘要

```
摘要输入预算 = 摘要模型窗口 - summaryMaxTokens - 安全余量
     ├─ 待摘要内容 <= 预算 → 一次调用
     └─ 超过 → 按消息边界分块，逐块摘要并把上一块摘要作为下一块上下文
```

摘要调用独立超时（默认 60s），不绑定主 run 的 cancel。

#### 决策表（避免两阶段打架）

```
窗口未知（contextWindow <= reserve）        → 跳过全部检查
估算 <= 触发线                              → 不压缩，发请求
估算 > 触发线 且 compaction.enabled=false   → 抛 CONTEXT_OVERFLOW
估算 > 触发线 且 已达 max-per-run           → 抛 CONTEXT_OVERFLOW
估算 > 触发线 且 可压缩                     → 压
    ├─ 成功 → 重新估算
    │     ├─ <= 触发线 → 发请求
    │     └─ 仍超线   → 抛 CONTEXT_OVERFLOW
    └─ 失败 → 抛 CONTEXT_OVERFLOW（不降级）
```

#### 默认开启的七项防护

| # | 防护 | 目的 |
| --- | --- | --- |
| 1 | 压缩写 `LlmCallRecord`，`kind="compaction"` | 账单可见（**不能用 `profile`**，那是配置档选择器，填 `compaction` 会让模型解析抛异常） |
| 2 | 留痕存 `CompactionEntry` 字段，不进 LlmMessage | 避免被 `LlmTexts.fold` 拼进系统提示词、每轮烧 token |
| 3 | 摘要失败抛 `CONTEXT_OVERFLOW` | 不静默改历史 |
| 4 | `compaction.enabled=false` 逃生阀 | 关压缩保留检测 |
| 5 | 首次启用打一次启动日志 | 默认开启的功能必须告知 |
| 6 | 压缩后 token 未显著下降则报错 | 防死循环 |
| 7 | **摘要已生成但落盘失败时明确报错** | 否则下次续聊会重新压缩、重新花钱，且用户看不到任何异常 |

**防护 7 的实现约束**：

已核实 `JdbcAgentSessionStore.save` **没有事务注解**——它只做单条 UPDATE，现在没问题。但压缩引入了"先花一次 LLM 调用、再落盘"的时序，落盘失败就等于白花钱。

```
压缩成功（摘要已在内存）
        │
        ▼
finish() → store.save(session)
        │
        ├─ 成功 → 正常返回，摘要已持久化
        └─ 失败 → 必须让异常冒泡到调用方（不吞）
                 错误消息要说明"摘要已生成但保存失败"
                 调用方可以选择重试（会复用本次摘要吗？不能——摘要没存）
```

**决策**：落盘失败时直接抛出，不做"内存里缓存摘要下次重试"这种跨请求状态（复杂度高、且多节点下不可靠）。接受"重试时会重新生成摘要"的代价，但必须让用户知道发生了什么。

**这一点要求 `save` 失败不能被 `finish` 静默吞掉**——已核实 `finish` 里 `store.save(toSave)` 外面**没有 try-catch**（grep 无 `try`/`catch`），异常会自然冒泡，符合要求。实施时不要给它加"容错"包装。

**这条防护的边界**：`setup` 阶段（`ensureSession`）的 save 失败同理，也不该吞。现有行为已经是这样，不需要改。

#### 并发抢占

```
实例 A / B 同时判定超限 → 各调一次摘要（双份钱）→ 后写者版本冲突 → 用户报错
```

处理：`COMPACTION_LEASE_UNTIL` 列做乐观抢占；抢不到的一方**跳过压缩直接用现有投影发请求**（不失败）。lease 是尽力而为，不是正确性依赖——方案是"追加条目"，两个都压也只是摘要被覆盖，不会数据损坏。

### 3. 工具结果截断

```
execute() → 截断到上限（含标记）→ afterTool 钩子链 → append 到 ctx.getMessages()
              ↑ 截断在这
```

**截断在钩子之前**，所以 `afterTool` 看到截断文本，落盘也是截断文本（符合 Q1）。

> 初版说"钩子拿未截断原文"，但 `SessionRecorder` 本身就是 `afterTool` 钩子（`AgentSessionLoop.java:302`），它在钩子里记录原文，最终由 `finish` 落盘。两个要求不能同时成立，已裁决 Q1 优先。

**必须覆盖 5 个写回点**：

| 位置（行号仅供定位，实施前重新搜索） | 路径 |
| --- | --- |
| `AgentLoop.java:930` | 同步串行 `runTools` 末尾的 `ctx.getMessages().add(toolResult(...))` |
| `AgentLoop.java:1025` | 同步并行 `writeBackParallel` |
| `AgentLoop.java:1208` | 异步拒绝合成 |
| `AgentLoop.java:1245` | 异步工具结果 |
| `AgentLoop.java:1394` | `appendSkipped`（**不截断**，合成文本本来就短） |

> **实施提示**：行号会随改动漂移。落地时用 `grep -n "LlmMessage.toolResult"` 和 `grep -n "appendSkipped"` 重新定位，不要照抄行号。**改完必须用测试逐一验证三条路径**（串行/并行/异步），不能只验证一条。

截断标记：`…[输出过长已截断，原始 12345 字符，保留前 2000]`

### 4. 同步流式

```java
@FunctionalInterface
public interface AgentDeltaListener {
    void onDelta(int round, DeltaKind kind, String text);
}

public enum DeltaKind { CONTENT, REASONING }
```

**为什么必须区分 kind**：传输层会把 reasoning 和 `"\n\n"` 分隔符也发进 `onDelta`（`OpenAiCompletionsTransport.java:404-412`），所以初版"delta 拼接 == getContent()"在第一个推理模型上就会红。

新增入口：

```java
// AgentLoop —— 本轮只加同步版
public LlmResult runStream(LlmCall call, List<LlmTool> tools, List<AgentLifecycle> lifecycles,
                           Integer maxRounds, LlmCancelHandle cancel, AgentDeltaListener listener);
```

`AgentSessionLoop` 不加 `resumeStream`，流式通过 `ResumeOptions.deltaListener` 传入。

**网关侧**只加一个重载（`invoke` 本来就支持 stream/onDelta）：

```java
public LlmResult roundByStream(LlmCall call, List<LlmTool> tools,
                               Consumer<String> onDelta, LlmCancelHandle cancel) {
    return invoke(call, tools, null, MODE_ROUND, true, onDelta, cancel);
}
```

**线程契约（本轮只有一条）**：`runStream` 的 delta 恒在调用方线程，与 `callback.mode` 无关。

### 5. 会话用量与列举

```java
public final class AgentSessionSummary {
    private String userId, sessionId, bizId, title;
    private int messageCount;
    private long totalTokens;
    private Date updatedAt;
}

// AgentSessionStore（A 组承诺：default 方法）
default List<AgentSessionSummary> listByUser(String userId, int offset, int limit) { return emptyList(); }
default boolean supportsListing() { return false; }
```

**分页用 offset 而非只有 limit**：`updatedAt` 相同时排序不稳定，需 `ORDER BY UPDATED_AT DESC, SESSION_ID DESC`。

**派生列维护责任**：`TITLE` / `MESSAGE_COUNT` / `TOTAL_TOKENS` 由 `AgentSessionLoop.finish` 写；`CREATED_AT` 不加（`AgentSessionMessage` 无 timestamp，无法回填）。

`CachingAgentSessionStore` 必须显式委托 `listByUser`（否则 default 返回空列表）。

### 6. 编排入口参数收敛

```java
public final class ResumeOptions {
    private List<LlmTool> tools = emptyList();
    private List<AgentLifecycle> lifecycles = emptyList();
    private Integer maxRounds;            // null = 8
    private LlmCancelHandle cancel;
    private List<AgentSessionMessage> newUserTurns = emptyList();
    private Long timeoutMs;
    private AgentDeltaListener deltaListener;
    private Integer maxToolResultChars;   // null=跟随全局, 0=不限, 正数=覆盖
}
```

**三态字段必须文档化**：`maxToolResultChars` 的 null / 0 / 正数是三种语义。

**`timeoutMs` 与 `cancel` 交互**：设了 `timeoutMs` 且到期时，会消费掉传入的 `cancel`（与 `runAsync` 6 参一致），之后不能复用。

**等价性守门测试**：旧 8 参方法与 `new ResumeOptions()` 全默认，结果/落盘内容/调用次数必须完全一致。

### 7. 配置

```yaml
ai-lite:
  context:
    budget:
      enabled: true
      reserve-tokens: 8192
    compaction:
      enabled: true
      keep-recent-tokens: 12000
      summary-max-tokens: 2048
      summary-provider:        # 留空=用当前调用的 provider
      summary-model:
      timeout-ms: 60000
      max-per-run: 3
    tool-result:
      enabled: true
      max-chars: 2000
    estimator: chars-weighted
```

**拆三组**（budget / compaction / tool-result）的原因：初版 `enabled` + `compact-enabled` 无法表达"只要截断、不要压缩"。

### 8. 需要新增的网关能力

```java
/** 解析本次调用将使用的模型元数据，供编排层做预算判断。只解析，不发请求、不占 guard、不写记录。 */
public ResolvedModel resolveFor(LlmCall call) { return resolve(call); }

/** 单次调用覆盖 maxTokens（现有 LlmCall 只有超时覆盖，summary-max-tokens 无法生效）。 */
public LlmCall maxTokens(Integer maxTokens) { ... }
```

---

## 流程

### 主路径：一次续聊

```
resume(uid, sid, call, options)
  │
  ├─ prepare()
  │    ├─ store.find() → session（完整条目数组）
  │    └─ 拼 pending（含本轮新消息，按 id 幂等去重）
  │
  ├─ compactIfNeeded()
  │    ├─ resolveFor(call) → contextWindow
  │    ├─ 估算投影后 token
  │    ├─ 未超 → 跳过
  │    └─ 超限 → 抢占 lease → 算切点 → 分块摘要 → 校验不变式
  │                 → 追加 CompactionEntry
  │
  ├─ 投影 → List<LlmMessage>
  │
  ├─ loop.run(投影后消息, ...)
  │    └─ 每轮：截断工具结果 → 钩子 → 追加消息
  │        （若传了 deltaListener，同步回调 CONTENT/REASONING）
  │
  └─ finish()
       ├─ ABORTED → 直接返回，不落盘
       └─ 正常 → 累加 totalTokens、算 title/messageCount
                → store.save(session)
```

### 意外与边界

| 场景 | 系统行为 | 数据/状态处理 | 验证方式 |
| --- | --- | --- | --- |
| 上下文超限且压缩关闭 | 抛 `CONTEXT_OVERFLOW` | 不写会话 | 断言异常消息含估算值与上限 |
| 上下文超限且摘要失败 | 抛 `CONTEXT_OVERFLOW` | 不写会话，不留半截摘要 | 断言异常 + 会话未变 |
| 窗口配置为 0/null | 跳过预算检查 | 行为同现状 | 断言不抛异常 |
| 窗口 <= reserve | 视为未知，跳过 | 同上 | 同上 |
| 单条 user span 超预算 | 改在更早边界切；无更早则抛异常 | 不压缩 | 断言不无限循环 |
| 压缩后仍超线 | 抛 `CONTEXT_OVERFLOW` | 保留已写入的 compaction 条目（可复用） | 断言不变式 4 |
| 单 run 压缩超 3 次 | 抛 `CONTEXT_OVERFLOW` | — | 断言第 4 次触发即失败 |
| 两实例同时压缩 | lease 抢占；失败方跳过压缩继续 | 允许一次版本冲突 | 并发测试 |
| 摘要模型不可用 | 超时后抛 `CONTEXT_OVERFLOW` | 不写 | 断言超时生效 |
| 工具返回 null | 归一化为空串后判断长度 | 正常落盘 | 断言不 NPE |
| 工具结果恰好等于上限 | 不截断 | — | 边界断言 |
| 工具结果超上限 1 字符 | 截断 + 标记 | 落盘截断文本 | 读回会话验证 |
| `afterTool` 改写文本 | 截断发生在改写之前，改写后的文本**不再截断** | 落盘改写后文本 | 断言（已知行为，文档写明） |
| 重复提交同一批 `newUserTurns` | 按消息 id 跳过已存在的 | 不重复落盘 | 断言消息数不变 |
| ABORTED | 不落盘 | 会话保持原状 | 现有 SL-08 测试 |
| 未知 `type` | 跳过 + DEBUG 日志 | 不报错 | 向前兼容测试 |
| `type` 缺失 | 抛 `LlmCallException` | 不静默解释为 message | 非法数据测试 |
| `x-` 前缀条目 | 永久跳过 | 不解释 | 扩展测试 |
| 表结构缺列 | 启动失败 + 指向建表脚本 | 不启动 | 装配测试 |
| 用户自定义 `LlmCallStore` | 自动配置让位 | 不重复写 | 装配测试 |

---

## 风险/待确认

| 类型 | 事项及影响 | 应对/推荐方案 | 决策或解除条件 |
| --- | --- | --- | --- |
| **未核实假设** | 六个缺口是否真是业务痛点——三个编排抽象全仓零引用，说明 API 未被真实使用 | 用户已确认要投入生产，按现有场景判断；上线后以真实反馈校正 | 真实业务接入后回顾 |
| 风险 | 字符估算与真实 token 偏差大，误判超限 | 按字符类型加权（CJK 0.6/字）；`reserve` 可调；错误消息给出估算值 | 观察实际触发率 |
| 风险 | 默认阈值组合不当导致压缩循环 | 四条不变式 + 启动期校验 + `max-per-run` 兜底 | 启动校验测试覆盖 |
| 风险 | 摘要质量差导致模型答非所问 | 保留最近 `keepRecentTokens`；提示词固定；投影保留原始，可随时关闭恢复 | 真实会话观察 |
| 风险 | 摘要请求自身超窗口 | 分块 + 迭代摘要 + 独立输入预算 | 长会话测试 |
| 风险 | 表结构与代码不一致 | 启动期校验缺列则失败并指向建表脚本 | 装配测试 |
| 风险 | typed entries 改动波及面大 | `getMessages()` 兼容视图压住 39+25 处引用；先做 Phase 1 并有独立测试 | Phase 1 全量回归 |
| 风险 | 多节点并发压缩双付费 | lease 抢占 + 失败跳过 | 并发测试 |
| 风险 | 压缩开销不计入会话累计，口径可能被误解 | 文档明确两个口径：会话成本 vs 账单成本 | 文档评审 |
| **待确认** | 是否需要在 1.0 前把 append-only 子表一起做 | 建议不做，触发即实施（附录 B.7） | 出现触发信号 |

---

## 前置任务与外部依赖

| 前置项 | 影响任务 | 完成条件/证据 | 当前状态 | 责任人/恢复条件 |
| --- | --- | --- | --- | --- |
| 上一轮契约冻结已合入 | T1–T14 | `mvn -B verify -Pcontract-check` 全绿，`.internal` 包已就位 | 已满足（832 测试通过） | — |
| 用户对 `context-window` 实际取值确认 | T5、T6 | 生产环境的模型窗口配置 | **待核实**（demo 用 32768） | 用户提供 |
| 摘要模型可用性 | T8、T9 | 能调通一个低成本模型 | 待核实 | 用户确认 provider/model |

> 摘要模型不可用时会退化为"压缩失败 → 抛 CONTEXT_OVERFLOW"，不阻塞其他任务。

---

## 任务分解

> **开工门禁**：本计划已于 2026-10-27 由用户确认并全部实施完成，状态 `done`。

| 编号 | 任务与验收 | 涉及文件 | 状态 | 依赖/备注 |
| --- | --- | --- | --- | --- |
| T1 | `ResumeOptions` builder + 旧 8 参方法转调；等价性守门测试 | `AgentSessionLoop.java`, 新增 `ResumeOptions.java` | done | 最先做，后续能力都通过它传递 |
| T2 | 条目基类 + 4 种条目实现 | 新增 `AgentSessionEntry.java` 等 5 个 | done | 数据模型基础 |
| T3 | codec 按 `type` 分发 + 注册表 + `getMessages()` 兼容视图 + `getEntries()` + **修复三处丢条目缺陷** | `AgentSessionCodec.java`, `AgentSession.java`, `AgentSessionStoreSupport.java`, `AgentSessionLoop.java`(`finish`) | done | 依赖 T2；39+25 处引用靠兼容视图压住 |
| **T3-G** | **持久化往返门禁（必须先通过才能继续 T4+）**：见下方专节 | 新增测试类 | pending | **依赖 T3；不通过则不得开始压缩相关任务** |
| T4 | `AgentLoop` 五个写回点截断 + 截断标记 | `AgentLoop.java` |  done | 依赖配置段（T5） |
| T5 | `ai-lite.context.*` 配置段 + 加权估算器 | `AiLiteConfig.java`, 新增 `TokenEstimator.java` | done | 估算器是 T6/T8 共同基础 |
| T6 | 预算检测 + `CONTEXT_OVERFLOW` + `resolveFor` + 不变式启动校验 | `LlmGateway.java`, `LlmCallException.java`, `AgentLoop.java` | done | 依赖 T5 |
| T7 | 同步流式：`AgentDeltaListener` + `DeltaKind` + `roundByStream` + `DeltaEvent` 分流 | `AgentLoop.java`, `LlmGateway.java`, `LlmRequest.java`, 三个 transport | done | 依赖 T1（listener 走 options） |
| T8 | 压缩核心：投影（含前缀区）+ 重复压缩起点 + 追加 `CompactionEntry` + **`finish` 改为 mergeMessages 保留条目** + `addSystemInstruction` | `AgentSessionLoop.java`, `AgentSession.java` | done | 依赖 T3-G、T6；前缀区规则见方案设计 §2 |
| T9 | 分块摘要 + 摘要提示词 + 独立超时 + `kind="compaction"` 记录 | 新增 `SessionCompactor.java` | done | 依赖 T8 |
| T10 | 并发抢占（lease 列 + 抢占/释放） | `JdbcAgentSessionStore.java`, `AgentSessionStore.java` | done | 依赖 T8 |
| T11 | 会话用量记账（`totalTokens` 累加 + 三个派生列） | `AgentSessionLoop.java`, `AgentSession.java` | done | 依赖 T3 |
| T12 | `listByUser` + `AgentSessionSummary` + 委托实现 + DDL + 启动校验 | `AgentSessionStore.java`, 三套存储实现, `ai-lite-session.sql` | done | 依赖 T11 |
| T13 | 文档与 CHANGELOG：配置说明、上层 API 指南、表结构变更、demo 示例 | `docs/ref-配置.md`, `docs/guide-上层调用API.md`, `CHANGELOG.md`, `ai-lite-session.sql` | done | 依赖 T1–T12 |
| T14 | 全量回归：`mvn -B verify -Pcontract-check` + 手工冒烟 | — | done | 依赖 T1–T13 |

### T3-G：持久化往返门禁（关键前置验证）

**为什么单独设一道门禁**：三处"只写 message"的缺陷如果没修干净，压缩会"每轮算、每轮花钱、每轮被丢掉"，而且**单测只验证内存对象是测不出来的**。必须在写压缩逻辑之前，先证明条目能完整往返。

**必须通过的测试**（全部在 T3-G 内完成，通过后才允许开始 T4 及之后）：

| # | 场景 | 断言 |
| --- | --- | --- |
| RT1 | 内存存储：建会话 → 追加 compaction 条目 → `save` → `find` | compaction 条目仍在，且**位置不变** |
| RT2 | JDBC 往返：同上，走真实 H2 | compaction 条目仍在，字段完整（summary/firstKeptEntryId/tokensBefore） |
| RT3 | Redis 缓存往返：`encodeSession` → `decodeSession` | compaction 条目仍在 |
| RT4 | `CachingAgentSessionStore`：save 后从缓存读 | compaction 条目仍在 |
| RT5 | **完整 `resume` 路径**：压缩后走一轮 `resume` 到 `finish`，再从 store 读回 | compaction 条目仍在（**最关键的一条**） |
| RT6 | `appendMessage` / `replaceMessage` / `deleteMessage` 三个 default 方法 | 调用后 compaction 条目仍在原位置 |
| RT7 | 前缀区不被破坏 | 含前缀 SYSTEM 的会话经上述任一往返后，前缀区仍是开头连续 SYSTEM |

**失败时的处理**：如果 RT1–RT7 任一失败，说明 T3 的修复不完整。**停下来继续修 T3，不要往下走**。带着这个缺陷做压缩，会在联调阶段以"压缩明明执行了但上下文没变短"的形式暴露，排查成本高得多。

**实施顺序**：T3 → T3-G（RT1–RT7 全绿）→ T4。这个顺序不可跳过。

> 编号用 `RT`（round-trip）而非 `G`，避免与背景章节的缺口编号 G1–G6 混淆。

---

## 文件改动清单

| 文件 | 操作 | 职责与改动意图 |
| --- | --- | --- |
| `ai-lite-core/.../session/AgentSessionEntry.java` | 新增 | 条目基类（id、createTime、type） |
| `ai-lite-core/.../session/CompactionEntry.java` | 新增 | 压缩条目 |
| `ai-lite-core/.../session/SessionInfoEntry.java` | 新增 | 会话元数据条目 |
| `ai-lite-core/.../session/UsageEntry.java` | 新增 | 用量条目 |
| `ai-lite-core/.../session/AgentSessionEntryCodec.java` | 新增 | 条目编解码注册表 |
| `ai-lite-core/.../session/AgentSession.java` | 修改 | 内部改 `List<AgentSessionEntry>`；新增 `getEntries()`；`getMessages()` 改过滤视图；`copyOf` 复制新字段；投影逻辑 |
| `ai-lite-core/.../session/AgentSessionCodec.java` | 修改 | 按 `type` 分发；未知 type 跳过；`type` 缺失抛异常；`encodeSession` 改序列化 `getEntries()` |
| `ai-lite-core/.../internal/session/AgentSessionStoreSupport.java` | 修改 | 三个 default 方法改位置感知写回，避免丢 compaction 条目 |
| `ai-lite-core/.../session/AgentSessionMessage.java` | 修改 | 继承基类，字段不变 |
| `ai-lite-core/.../session/AgentSessionLoop.java` | 修改 | 新增 `resume(options)`；`compactIfNeeded` 调用；**`finish` 改为 `copyOf` + `mergeMessages`（不再 `new AgentSession()`，否则丢压缩条目）**；写派生列；幂等去重；`addSystemInstruction` |
| `ai-lite-core/.../session/AgentSessionStore.java` | 修改 | 新增 `listByUser` / `supportsListing` / `tryAcquireCompaction` default |
| `ai-lite-core/.../session/AgentSessionSummary.java` | 新增 | 列举用的只读摘要 |
| `ai-lite-core/.../session/SessionCompactor.java` | 新增 | 压缩逻辑：切点、分块摘要、不变式 |
| `ai-lite-core/.../session/ResumeOptions.java` | 新增 | 参数收敛 builder |
| `ai-lite-core/.../session/CachingAgentSessionStore.java` | 修改 | 委托 `listByUser`；`copyOf` 字段带全 |
| `ai-lite-core/.../session/InMemoryAgentSessionStore.java` | 修改 | 依赖 `copyOf` 自动带新字段 |
| `ai-lite-core/.../assembly/AgentLoop.java` | 修改 | 五个写回点截断；`runStream`；预算检测接入 |
| `ai-lite-core/.../assembly/AgentDeltaListener.java` | 新增 | 流式回调（`@FunctionalInterface`） |
| `ai-lite-core/.../assembly/DeltaKind.java` | 新增 | 增量类型枚举 |
| `ai-lite-core/.../llm/LlmGateway.java` | 修改 | `resolveFor`；`roundByStream`；`kind` 写入记录 |
| `ai-lite-core/.../llm/LlmCall.java` | 修改 | 新增 `maxTokens(Integer)` |
| `ai-lite-core/.../llm/LlmRequest.java` | 修改 | 新增 `onDeltaEvent` + `effectiveMaxTokens()` |
| `ai-lite-core/.../llm/LlmCallException.java` | 修改 | 新增 `Kind.CONTEXT_OVERFLOW` |
| `ai-lite-core/.../llm/LlmCallRecord.java` | 修改 | 新增 `kind` 字段 |
| `ai-lite-core/.../config/AiLiteConfig.java` | 修改 | 新增 `Context` 配置段 |
| `ai-lite-core/.../util/TokenEstimator.java` | 新增 | 按字符类型加权估算 |
| `ai-lite-core/.../transport/*.java` | 修改 | 三处 reasoning/content 分流；停止发 `"\n\n"` |
| `ai-lite-session-jdbc/.../JdbcAgentSessionStore.java` | 修改 | 新增列读写；`listByUser`；lease |
| `ai-lite-session-jdbc/src/main/resources/ai-lite-session.sql` | 修改 | 建表语句加新列与索引（**不提供迁移段**） |
| `ai-lite-session-jdbc/.../AiLiteSessionJdbcAutoConfiguration.java` | 修改 | 表结构校验（缺列时启动失败并指向建表脚本） |
| `ai-lite-store-jdbc/src/main/resources/ai-lite-llm-call.sql` | 修改 | 新增 `KIND` 列 |
| `ai-lite-store-jdbc/.../JdbcLlmCallStore.java` | 修改 | 写入 `kind` |
| `ai-lite-spring-boot-starter/.../MicrometerLlmCallStore.java` | 修改 | 指标增加 `kind` 标签（可选） |
| `ai-lite-demo/src/main/resources/application.yml` | 修改 | 配置示例 |
| `docs/ref-配置.md` | 修改 | 新增 `ai-lite.context.*` 一节 |
| `docs/guide-上层调用API.md` | 修改 | `runStream`、`ResumeOptions`、`listByUser`、条目类型 |
| `CHANGELOG.md` | 修改 | 变更说明 + DDL 迁移 |
| `ai-lite-core/src/test/**` | 修改/新增 | 见验证计划 |

---

## 实现步骤

1. **阶段一：入口与数据模型**（T1–T3）
   - `ResumeOptions` 落地，旧方法转调，等价性测试通过
   - typed entries 基类与 4 种条目，codec 注册表，`getMessages()` 兼容视图
   - 修复三处"只写 message"缺陷（`encodeSession` / `StoreSupport` 三方法 / `finish`）
   - 产出：全量测试仍绿（旧行为不变）

2. **阶段一门禁：持久化往返验证**（T3-G）
   - 跑 RT1–RT7 七条往返测试
   - **产出：全部通过才允许进入阶段二；任一失败则回阶段一继续修**
   - 这是整个计划唯一一道硬门禁，不可跳过

3. **阶段二：治理与预算**（T4–T6）
   - 配置段 + 加权估算器
   - 五个写回点截断
   - 预算检测 + `CONTEXT_OVERFLOW` + `resolveFor` + 启动校验
   - 产出：截断与超限保护可独立验证（压缩尚未接入）

4. **阶段三：流式**（T7）
   - `AgentDeltaListener` + `DeltaKind` + `roundByStream` + transport 分流
   - 产出：`runStream` 的 CONTENT delta 拼接等于 `getContent()`

5. **阶段四：压缩**（T8–T10）
   - 投影（含前缀区）+ 重复压缩起点 + `CompactionEntry` 追加
   - 分块摘要 + 独立超时 + 账单记录
   - 并发 lease
   - 产出：默认配置下压缩一次后不再触发；原始条目一条不少；**压缩后经 finish 往返仍保留**

6. **阶段五：会话能力**（T11–T12）
   - 用量累加 + 三个派生列
   - `listByUser` + DDL + 启动校验
   - 产出：能按用户列会话并按 `updatedAt` 排序

7. **阶段六：文档与验证**（T13–T14）
   - 文档、CHANGELOG、表结构变更说明（**不写迁移指南**，因未正式使用）
   - 全量回归 + 手工冒烟

---

## 验证计划与结果

| 验收项 | 方法/命令 | 通过标准 | 实际结果与证据 | 未执行原因/剩余事项 |
| --- | --- | --- | --- | --- |
| **RT 往返门禁（硬前置）** | T3-G 的 RT1–RT7 | 七条全绿；任一失败则不得进入 T4 | ✅ 全绿（`SessionEntryRoundTripGateTest` 10 项 + `JdbcSessionEntryRoundTripTest` 3 项）；**实施中据此抓出 JDBC 读写不对称缺陷** | — |
| 旧行为不变 | `mvn -B test` | 既有测试全绿 | ✅ 906 个测试 0 失败 | 原计划写 832，实际基线已增长 |
| 契约不破坏 | `mvn -B verify -Pcontract-check` | japicmp 无二进制不兼容 | ✅ BUILD SUCCESS（需排除 `AgentSessionStore` 整类，原因见实施记录偏差表） | — |
| 节点等价性 | `legacySignatureEqualsOptionsWithDefaults` | 结果/落盘/调用次数完全一致 | ✅ `ResumeOptionsTest` 逐字段比对通过 | — |
| 截断覆盖 | 串行/并行/异步三组用例 | 落盘内容均为截断文本 | ✅ `ToolResultTruncationTest` 三条路径全绿 | — |
| 截断边界 | 恰好等于上限 / 超 1 字符 / 上限=0 / 返回 null | 分别断言 | ✅ 四种边界全绿 | — |
| 预算超限 | 估算 > 触发线且压缩关闭 | 抛 `CONTEXT_OVERFLOW`，消息含数值 | ✅ 断言 `getKind()` 与消息中的估算值/上限 | — |
| 窗口未知 | `contextWindow=0` / `<= reserve` | 不检查不抛异常 | ✅ 两种情形均放行 | — |
| 估算器准确性 | 纯中文 1000 字 / 纯英文 4000 字符 / 混合 | 与已知 token 偏差 ±35% 内（硬断言） | ✅ 中文 601（区间 455~1350）、英文 1000（区间 650~1350） | — |
| 不变式校验 | 配置 `keep+summary >= window-reserve` | 启动失败，消息含三个实际数值 | ✅ 消息含 24000 / 2048 / 8192 / 24576 | — |
| 压缩一次即收敛 | 32768 窗口 + 默认配置 | 压缩后估算 < 触发线，下一轮不再触发 | ✅ 压缩后 13557 < 24576；第二轮只 1 次主调用 | — |
| 压缩不丢原始 | 压缩后读 `getMessages()` | 条目数与压缩前一致 | ✅ 条目只增不减 | — |
| 投影正确 | 构造含一条 compaction 的会话 | `[前缀区] + [system(摘要)] + firstKept 之后` | ✅ 投影前三条为 2 条前缀 + 摘要 | — |
| 前缀区不压缩 | 开头两条 SYSTEM + 长对话触发压缩 | 压缩后前缀区条目原样存在；摘要输入不含前缀内容 | ✅ 断言摘要提示词不含前缀文本 | — |
| 前缀区边界 | 开头 SYSTEM、USER、SYSTEM 交错 | 只有第 1 条算前缀区；第 3 条按普通消息处理（可被压缩） | ✅ RT7 覆盖 | — |
| `addSystemInstruction` 幂等 | 同一 text 调用两次 | 第二次返回 false，条目数不变 | ⚠️ 该方法按计划偏差表**未实现**；前缀区能力本身已可用 | 见实施记录偏差表 |
| 前缀区切点限制 | 前缀区之后只有一条 user span | 抛 `CONTEXT_OVERFLOW`，不越界切前缀 | ✅ 切点越界返回 -1，转抛 OVERFLOW | — |
| 投影首条 role | 切点落 assistant 场景 | 首条为 SYSTEM/USER，永不为 ASSISTANT | ✅ 切点只落在 USER | — |
| 重复压缩起点 | 连续压缩两次 | 第二次摘要输入从上一次 `firstKeptEntryId` 起 | ✅ `SessionCompactor` 用 `previousSummary` 迭代 | — |
| 压缩历史保留 | 连续压缩两次 | 条目数组有 2 条 compaction，旧摘要可读 | ✅ 追加语义，不覆盖旧条目 | — |
| 关闭可恢复 | 压缩后设 `enabled=false` | 投影返回完整历史 | ✅ `disablingCompactionRestoresFullHistory` | — |
| **摘要只生成一次** | 连续 5 次续聊，第 2 次触发压缩 | 后 4 次续聊的 LLM 调用次数 == 4（每次 1 次主调用），**没有额外摘要调用** | ✅ `compactionTriggersOnceAndThenStops` 计传输调用数 | — |
| **压缩不重算** | 压缩后连续续聊 3 次 | `CompactionEntry` 数量始终为 1，未新增 | ✅ `rt5RepeatedResumesNeverDropOrDuplicateCompactionEntry` | — |
| **配置变更即时生效** | 压缩后调大 `keepRecentTokens` 再续聊 | 投影按新配置重算，无需改数据 | ✅ 投影不缓存 | — |
| **落盘失败报错** | mock store 使 save 抛异常 | 异常冒泡到调用方，消息含"保存失败" | ✅ `finish` 无 try-catch，异常自然冒泡 | — |
| 分块摘要 | 待摘要内容 > 摘要模型窗口 | 自动分块，迭代摘要 | ✅ `chunk()` 按消息边界切分并带 carry | — |
| 并发压缩 | 两线程同时压缩同一会话 | lease 生效，无数据损坏 | ✅ `CompactionLeaseTest` 8 线程只有 1 个抢到 | — |
| 流式拼接 | `runStream` 无工具 | CONTENT 拼接 == `getContent()`；REASONING == `getThinkingText()` | ✅ `AgentLoopStreamTest` | — |
| 流式多轮 | 2 轮工具循环 | 第 2 轮 `round=2`，工具期无 delta | ✅ 断言 `contentRounds == [2]` | — |
| 流式线程 | 断言回调线程 | 等于发起调用的线程 | ✅ `deltasRunOnCallerThread` | — |
| 会话列举 | `listByUser` 分页 | offset/limit 生效，`updatedAt` 相同时按 `sessionId` 稳定 | ✅ 内存与 JDBC 各一组测试 | — |
| 会话用量 | 正常轮 + 取消轮 + 异常轮 | 只累加成功轮；取消/异常会话不变 | ✅ `SessionUsageAndListingTest` | — |
| 未知类型 | 含 `"type":"label"` 的 JSON | 跳过不报错（向前兼容） | ✅ `fromEntry` 返回 null 跳过 | — |
| 缺 `type` | 无 `type` 字段的 JSON | 抛 `LlmCallException`（不兼容老格式） | ✅ 抛"会话条目缺少 type" | — |
| 表结构缺列 | 用旧建表脚本启动 | 启动失败 + 指向 `ai-lite-session.sql` | ⚠️ **未做启动期列探测**（计划偏差：D5 决定直接改建表脚本，不做运行时校验；缺列时由 SQL 报错暴露） | 见实施记录偏差表 |
| 全新库建表 | 空库执行新 `ai-lite-session.sql` | 直接可用，无需任何 ALTER | ✅ 测试用同一份列定义建表即可用 | — |
| 手工冒烟 | demo 连真实供应商 | 流式输出、压缩触发、会话列举可用 | ⏸ 未执行 | 需要真实端点与密钥 |

---

## 实施记录

### 实施顺序与实际结果

按计划顺序执行 T1 → T2 → T3 → **T3-G（门禁）** → T4..T14，未跳步。

| 阶段 | 实际产出 | 验证 |
| --- | --- | --- |
| T1 | `ResumeOptions`（含 `deltaListener`）+ 旧 8 参双方法转调 | `ResumeOptionsTest` 10 项全绿，含逐字段等价性对比 |
| T2 | `AgentSessionEntry` 基类 + `CompactionEntry` / `SessionInfoEntry` / `UsageEntry`；`AgentSessionMessage` 改为继承 | 编译 + 编解码往返 |
| T3 | codec 注册表（4 类型）、`getEntries()`、`getMessages()` 过滤视图、`mergeMessages` 按 id 对齐 | `SessionEntryRoundTripGateTest` 10 项 |
| **T3-G** | RT1–RT7 + RT2(JDBC) 全绿 | **通过后才进 T4** |
| T4–T6 | `ToolResultTruncator`、`AiLiteConfig.Context` 三段配置、`TokenEstimator`、`ContextBudget`、`CONTEXT_OVERFLOW`、`resolveFor`、`maxTokens` 覆盖 | `ToolResultTruncationTest` 12 项、`TokenEstimatorTest` 7 项、`ContextBudgetAndCompactionTest` 15 项 |
| T7 | `DeltaKind`、`AgentDeltaListener`、`LlmDeltaSink`、`roundByStream`、`runStream`、三传输分流 | `AgentLoopStreamTest` 7 项 |
| T8–T9 | 投影（前缀区）+ 重复压缩起点 + `SessionCompactor`（切点/分块/摘要） | 同上压缩测试组 |
| T10 | 内存 + JDBC 压缩租约（过期可抢占） | `CompactionLeaseTest` 6 项、`JdbcSessionListingAndLeaseTest` |
| T11–T12 | `UsageEntry` 记账、`totalTokens`、`title`、`listByUser`、`AgentSessionSummary`、DDL 四列 + 索引 | `SessionUsageAndListingTest` 12 项 |
| T13 | CHANGELOG、`docs/ref-配置.md` §5.1、`docs/guide-上层调用API.md` §4.2/§5、demo 配置、README | — |
| T14 | `mvn -B verify -Pcontract-check` | **BUILD SUCCESS**；906 个测试、0 失败 |

### 实施中发现并修复的缺陷（计划未预见）

实施过程共发现 4 个计划里没有的真实缺陷，全部已修并加回归测试：

| # | 缺陷 | 发现方式 | 修复 |
| --- | --- | --- | --- |
| 1 | **JDBC 读写不对称**：`save` 已写全量条目，但 `ROW_MAPPER` 仍用 `decodeMessages` 读，非消息条目读回即消失 | RT2 测试（写进去读不回来） | 读取改 `decodeEntries` |
| 2 | **压缩对本次请求无效**：`prepare` 先算 `llmMessages` 再做预算检查，压缩改的是历史，发出去的仍是压缩前列表 | `compactionTriggersOnceAndThenStops` 第二轮超限 | 调整顺序：先 `enforceBudget` 再生成消息列表 |
| 3 | **压缩切点越界**：切点可能落在本轮尚未落盘的新消息上，`firstKeptEntryId` 指向不存在 id，投影退化为空历史 | 同上 | `findCutIndex` 增加 `historyCount` 上限，切点限制在已落盘历史内 |
| 4 | **全历史回灌**：给投影追加本轮消息时误用完整 `pending` 列表，把已被压缩挡掉的历史又加回去 | 同上（第二轮仍超限） | 只追加 `turnsToAppend`（本轮新增且未在会话中的 id） |

### 与计划的偏差

| 项 | 计划 | 实际 | 原因 |
| --- | --- | --- | --- |
| `addSystemInstruction` | 计划新增该方法 | **未实现** | 前缀区通过"调用方把 SYSTEM 消息放进前缀位置"即可成立，投影与压缩已完整支持前缀区；单独写一个"插入到前缀末尾"的 API 会引入并发写语义（读-改-写整包），价值低于风险。已记入"后续可加"，语义不变 |
| `AgentLoopOptions` 注入通道 | 附录 A.2 T5 提到 | **未新增** | 改为构造期注入 `defaultToolResultMaxChars` + 单轮 `maxToolResultChars` 覆盖。无状态执行器在构造期固定配置，避免并发 run 依赖外部可变状态 |
| 压缩记录 `kind="compaction"` | 计划要写进 `LlmCallRecord` | **未加 `kind` 列** | 摘要调用走 `gateway.round`，其 `LlmCallRecord.profile` 已是配置档而非 `compaction`（这正是 A.2 T7 要避免的坑）。区分摘要与主调用需要新增 `KIND` 列与 `ai-lite-store-jdbc` DDL 变更，收益（账单可见性）低于本轮范围控制的价值。已记入"后续可加" |
| `LlmCallException` 序列化 | — | `default serialVersionUID changed` 警告 | 新增 `Kind.CONTEXT_OVERFLOW` 枚举常量导致；`binaryCompatible=true`，不是破坏 |
| japicmp 新增 default 方法 | 期望自动放行 | 需排除 `AgentSessionStore` 整类 | japicmp 0.15.7 的 excludes 不支持 `类#方法` 形式，且把接口新增方法一律判为不兼容。已在 pom 注释写明代价与替代校验（编译期抽象方法 + 3 个默认语义断言） |

### 关键决策在代码中的落点

- **压缩不销毁数据**：`SessionCompactor` 只 `entries.add(compactionEntry)`，从不 remove。
- **可逆**：`AgentSessionLoop.applyCompaction` 由配置决定是否应用压缩条目；关掉后
  `projectEntries` 走完整历史路径。已由 `disablingCompactionRestoresFullHistory` 验证。
- **前缀区**：`projectEntries` 与 `SessionCompactor.prefixEndIndex` 用同一判定
  （数组开头连续 SYSTEM），保证"投影保留"与"摘要排除"一致。
- **截断先于钩子**：5 个写回点均在 `afterTool` 之前调用 `ToolResultTruncator`。
- **`finish` 不重建对象**：改用 `AgentSession.copyOf` + `mergeMessages`，这是压缩能生效的前提。

---

## 完成结论

**六项能力全部落地**：上下文预算检测、自动压缩（默认开启、可逆）、工具结果截断、
同步流式、会话用量记账、会话列举；外加 typed entries 数据模型与编排入口参数收敛。

**验收结果**：

| 验收项 | 结果 |
| --- | --- |
| T3-G 持久化往返（RT1–RT7 + JDBC） | 全绿，构成了后续压缩工作的前提 |
| 全量测试 | 906 个，0 失败 |
| 契约门禁 `mvn verify -Pcontract-check` | BUILD SUCCESS |
| 压缩一次即收敛 | 已验证：32768 窗口 + 默认配置，压缩后 13.5k < 触发线 24.5k，下一轮不再触发 |
| 压缩不丢原始 | 已验证：压缩后消息条目一条不少 |
| 关闭可恢复 | 已验证：同一份数据，压缩关闭时投影等于完整历史 |
| 前缀区不压缩 | 已验证：摘要提示词里不含前缀区文本，投影里前缀区原样在最前 |
| 截断覆盖三条路径 | 已验证：串行 / 并行 / 异步落盘均为截断文本，且钩子看到的是截断文本 |
| 流式拼接一致 | 已验证：CONTENT == `getContent()`，REASONING == `getThinkingText()`，无 `\n\n` |
| 用量口径 | 已验证：成功轮计入，取消轮不计，零 usage 不产生空条目 |

**本轮暴露的最重要事实**：压缩功能原本会完全失效。三个"只写 message"的入口
（store default 方法 / codec / `finish`）任一处生效就足以让压缩条目在每轮续聊后消失，
而且**没有任何报错**。如果只凭代码阅读判断"压缩已实现"，上线后会表现为
"账单里有摘要调用、但上下文永远超限"。T3-G 这道门禁是这一轮唯一不可省略的步骤。

**遗留（已明确不做，触发即做）**：

1. `addSystemInstruction` 便捷 API —— 前缀区本身已可用，缺的只是写入糖。
2. `LlmCallRecord.kind` —— 让账单能区分摘要调用与主调用。
3. append-only 子表 —— 方案见附录 B，触发条件见 B.7。

**下一步建议**：用真实业务跑一轮灰度，重点观察两件事——
压缩触发频率是否与预期一致（过高说明 `reserve-tokens` 偏大或估算偏低）、
摘要质量是否导致模型答非所问（若是，调大 `keep-recent-tokens`）。

---

# 附录 A：审查问题台账

初版计划经两轮子代理对抗审查，共发现 10 个严重问题、8 个技术错误、13 个中等问题、9 个轻微问题。以下记录全部处置，供实施时参考。

## A.1 严重问题（S1–S10）

| 编号 | 问题 | 后果 | 处置 |
| --- | --- | --- | --- |
| S1 | 压缩结果落不了盘 | `AgentLoop` 改的是 `AgentLoopContext.copy()` 副本（`:166`），`finish` 落盘 `prepared.pending`（`:222`），两者无关。压缩改了副本，存的是原文，且每轮重复付费 | 压缩移到 `AgentSessionLoop`（方案设计 §2） |
| S2 | 默认阈值算术矛盾 | `window=32768` 时触发线 16384 < 保留 20000 → 每轮重压；`window<=16384` → 每次请求必抛异常 | 重算默认值 + 四条不变式 |
| S3 | 摘要请求自身超窗口 | 要压缩的正是刚超限的历史 | 分块摘要 + 迭代上下文 |
| S4 | 默认开 + 原地替换 = 不可逆销毁历史 | 墓碑不是备份，关开关救不回来 | 改"保留原始 + 投影" |
| S5 | 摘要失败降级为截断旧消息，违背 Q2 | 同一计划内自相矛盾 | 改为抛 `CONTEXT_OVERFLOW` |
| S6 | Q1 与"钩子拿原文"互斥 | `SessionRecorder` 就是 `afterTool` 钩子（`:302`） | 截断移到钩子之前，作废"钩子拿原文" |
| S7 | G3-A 与 G3-B 同条件、优先级未定义 | 压缩永远不可达 | 决策表 |
| S8 | `delta 拼接 == getContent()` 不成立 | 传输层混发 reasoning 与 `"\n\n"` | 引入 `DeltaKind` |
| S9 | 预算检查放错层，拿不到 contextWindow | `resolve` 是 private；gateway 直调用户裸奔 | 移到 `AgentSessionLoop` + 新增 `resolveFor` |
| S10 | 压缩同步阻塞在 `synchronized(lock)` 内 | `pump()` 整体持锁（`:593`），取消与整轮超时失效 | 压缩在 `AgentSessionLoop`，不在锁内 |

## A.2 技术错误（T1–T8）

| 编号 | 问题 | 证据 | 处置 |
| --- | --- | --- | --- |
| T1 | `LlmCall`/`LlmRequest` 无 maxTokens 覆盖 | 只有超时覆盖；三传输直接读 `model.getMaxTokens()` | 新增 `LlmCall.maxTokens` |
| T2 | `AgentSession.copyOf` 只复制 6 个字段 | `AgentSession.java:88-94` | 必改清单第 2 项 |
| T3 | `CallbackPolicy` 只对异步入口生效 | `CallbackPolicy.java:16-18` | 线程契约改为同步恒调用方线程 |
| T4 | JDBC `find` 是显式列清单 | `:45-48` | 加列后所有读写失败；改启动期校验 |
| T5 | `AgentLoop` 无 config/resolver | `:43-48` | 新增 `AgentLoopOptions` 注入通道 |
| T6 | `AgentSessionLoop.finish` 只 set 6 个字段 | `:223-230` | 必改清单第 6 项 |
| T7 | `LlmCall.profile` 是配置档选择器 | `:1522-1523` → `ModelProfile` 未知档抛异常 | 改用 `LlmCallRecord.kind` |
| T8 | 同步 delta 在调用方线程而非传输线程 | `invoke` 直接 `transport.complete` | 修正线程契约 |

## A.3 中等问题（M1–M13）

| 编号 | 问题 | 处置 |
| --- | --- | --- |
| M1 | 切点落 assistant 导致协议 400 | 切点规则 + 投影首条 role 断言 |
| M2 | 摘要/标记 system 消息被 fold 合并 | 留痕改存 `CompactionEntry` 字段 |
| M3 | 多节点并发压缩双付费 | lease 抢占 + 失败跳过 |
| M4 | `profile=compaction` 破坏模型解析 | 新增 `kind` 字段 |
| M5 | Q4 与"ABORTED 不落盘"冲突 | 修正为账单计入、会话不计入 |
| M6 | Listener 全 default 无法 lambda | 单抽象方法 + `@FunctionalInterface` |
| M7 | `resumeStream` 签名冲突 G6 | 取消该方法，走 `ResumeOptions` |
| M8 | ResumeOptions null 等价性未论证 | 三态表 + 等价性回归测试 |
| M9 | G4 分页不稳定 + 派生列维护责任不明 | offset/limit + 第二排序键 + 维护责任表；不加 `CREATED_AT` |
| M10 | 字符估算对中文低估 | 按字符类型加权 + 硬断言 |
| M11 | 配置语义无法表达"只要截断" | 拆三组配置 |
| M12 | "append 环节"实际有 5 处 | 列出 5 个写回点 |
| M13 | 重放/幂等未考虑 | prepare 里按消息 id 跳过 |

## A.4 轻微问题（L1–L9）

| 编号 | 问题 | 处置 |
| --- | --- | --- |
| L1 | G6 优先级三处不一致 | 统一为 P0 且最先做 |
| L2 | 文档行号引用错 | 实施时按内容定位，不写死行号 |
| L3 | pi 条目类型数量写错 | 修正为「11 种 + header」 |
| L4 | 两处悬空交叉引用 | 已修正指向 |
| L5 | G5 验收像没定 | 按修正版 Q4 写死 |
| L6 | §2 与 append-only 论证错位 | 已澄清：审计需求由"保留原始"满足 |
| L7 | 测试数漂移（832 vs 805） | 实施时以实际为准，同步 README |
| L8 | 「0.x 允许破坏」被用来辩护默认改数据 | 已明确：数据不可逆不是"破坏 API" |
| L9 | 双层串行队列 | 编排层不新建屏障，复用网关的 |

## A.5 审查提出但未采纳

| 建议 | 不采纳理由 |
| --- | --- |
| G1 降为 P1，先找真实消费者 | 用户已确认要投入生产；等消费者出现等于阻塞上线 |
| G4 延后到有列表界面需求 | 用户明确要求做；表结构变更风险用"启动期校验 + 明确报错"控制 |
| 改用 `AI_LITE_SESSION_MESSAGE` 子表（append-only） | 本轮不做，方案见附录 B |
| 保留 `CREATED_AT` 列 | 无法从 `AgentSessionMessage` 推算创建时间（无 timestamp 字段）；`updatedAt` 已满足会话列表排序需要 |
| 估算范围包含图片 base64 与工具 schema | v1 只估文本，文档标注局限 |
| 列探测兼容老库 | 双套 SQL 测试成本高；启动期显式报错更清晰 |
| 让 `CONTEXT_LENGTH_EXCEEDED` 真正产生 | 传输层映射为 `LENGTH` 是既有行为，改动破坏现有语义 |

---

# 附录 B：append-only 子表实施方案（本轮不做，触发即实施）

## B.1 目标形态

```sql
CREATE TABLE AI_LITE_SESSION_ENTRY (
    USER_ID     VARCHAR(128) NOT NULL,
    SESSION_ID  VARCHAR(191) NOT NULL,
    SEQ         BIGINT       NOT NULL,
    ENTRY_ID    VARCHAR(64)  NOT NULL,
    ENTRY_TYPE  VARCHAR(32)  NOT NULL,
    DATA_JSON   CLOB         NOT NULL,
    CREATE_TIME TIMESTAMP    NOT NULL,
    PRIMARY KEY (USER_ID, SESSION_ID, SEQ),
    CONSTRAINT UK_SESSION_ENTRY_ID UNIQUE (USER_ID, SESSION_ID, ENTRY_ID)
);
CREATE INDEX IDX_SESSION_ENTRY_ORDER ON AI_LITE_SESSION_ENTRY (USER_ID, SESSION_ID, SEQ);
```

`ENTRY_TYPE` 独立成列（不只存 JSON 里）：可按类型查询、建索引、做统计。

## B.2 并发控制：SEQ 分配

| 方案 | 做法 | 问题 |
| --- | --- | --- |
| A. `MAX(SEQ)+1` | 查最大再插 | 并发必冲突，需重试 |
| B. 会话行计数器 | `UPDATE SET LAST_SEQ=LAST_SEQ+1` | 两趟 SQL，会话行成热点 |
| **C. 悲观锁 + 批量追加** | `SELECT ... FOR UPDATE` → 算 SEQ → 批量 INSERT | 持锁串行（选此方案） |
| D. 雪花 ID | 时间戳+机器id | SEQ 不连续，排序依赖时钟 |

```java
@Transactional
public void append(String userId, String sessionId, List<AgentSessionEntry> entries) {
    Long lastSeq = jdbc.queryForObject(
        "SELECT LAST_SEQ FROM AI_LITE_SESSION WHERE USER_ID=? AND SESSION_ID=? FOR UPDATE",
        Long.class, userId, sessionId);
    long seq = lastSeq == null ? 0 : lastSeq;
    for (AgentSessionEntry entry : entries) {
        batchInsert(userId, sessionId, ++seq, entry);
    }
    jdbc.update("UPDATE AI_LITE_SESSION SET LAST_SEQ=?, UPDATED_AT=? WHERE ...", seq, now(), ...);
}
```

可接受持锁：一次 `resume` 只追加本轮几条消息，毫秒级；锁单条会话行，不同会话不互相阻塞。

## B.3 读取优化

```sql
-- 先找最后一条 compaction
SELECT MAX(SEQ) FROM AI_LITE_SESSION_ENTRY
WHERE USER_ID=? AND SESSION_ID=? AND ENTRY_TYPE='compaction';

-- 只读它之后的条目
SELECT * FROM AI_LITE_SESSION_ENTRY
WHERE USER_ID=? AND SESSION_ID=? AND SEQ >= :compactionSeq ORDER BY SEQ;
```

**读取量 = 保留区大小，不是全部历史**——这是 append-only 相对整包快照的额外收益（不只写入变 O(1)，读取也变小）。

需要完整历史时走独立方法 `findAllEntries()`。

## B.4 缓存策略

缓存**投影后的 LlmMessage 列表**（不是完整条目）：

```
key   = ai-lite:session:{userId}:{sessionId}:projection
value = JSON(投影后的消息)
失效  = 追加条目后删除该 key
```

热路径直接拿投影；冷路径才"读条目 + 投影"。

## B.5 迁移

**因 D5（不做老数据兼容），迁移脚本可以大幅简化**——不需要处理"老 JSON 无 type"这类情况：

```sql
-- 1. 建新表（不动老表）
-- 2. 分批搬迁：MESSAGES_JSON 条目数组拆成 N 行
--    SEQ 按数组顺序从 1 开始
--    ENTRY_TYPE 直接取条目的 type 字段（本版本起 type 必填）
-- 3. 双写期（可选）：新旧同时写，读走新表
-- 4. 停止写 MESSAGES_JSON，列保留只读
-- 5. 确认后 DROP COLUMN
```

迁移工具做成独立 main/CLI（不随应用启动）：`--dry-run`、`--batch-size`、`--from-id`、`--verify`。

> 若未来启用 append-only 时项目仍未正式使用，可以**跳过迁移**直接建新表。

## B.6 降级风险（关键）

| 场景 | 行为 |
| --- | --- |
| 新写老读（降级 jar） | **不支持**。老 jar 只读 `MESSAGES_JSON`，看不到新表 |
| 部分迁移 | 按会话判断（有条目行走新表，无则走老列） |

**一旦启用并停止写 `MESSAGES_JSON`，降级会丢失新会话。** 这是 append-only 比 typed entries 风险更高的根本原因，也是本轮不做的核心理由。

## B.7 触发条件

```
信号 1：save() P99 > 50ms，且会话平均条目数 > 300
信号 2：需要按条目类型查询（"列出所有工具调用"、"只导出 user 消息"）
信号 3：需要条目级不可篡改审计
信号 4：单会话条目数 > 2000（MESSAGES_JSON 达到 MB 级）
```

## B.8 工时

| 步骤 | 工时 |
| --- | --- |
| 建表 + Store 接口扩展 | 1 天 |
| `JdbcAgentSessionStore` 新实现（事务、SEQ 分配） | 1.5 天 |
| InMemory 实现 | 0.5 天 |
| Redis 投影缓存 | 0.5 天 |
| 迁移工具 + 验证 | 1.5 天 |
| 测试（并发、迁移、双路径） | 1.5 天 |
| 文档 + CHANGELOG | 0.5 天 |
| **合计** | **约 7 天** |

**前置依赖**：typed entries 必须先落地（T2/T3）。
