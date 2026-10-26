# 夯实对外契约冻结（生产上线前）

**状态（2026-10-26）：讨论中，未定稿。** 本文只是把现状与选项摊开，供决策使用；**未获批准前不据此动代码**。已明确的两条设计前提见下方"不可动摇的前提"。

**触发**：准备投入生产，希望「内部实现可以变，对外契约稳定下来」。
**结论先行**：现在还不能直接承诺冻结；建议先发 `1.0.0-rc1`，清掉 5 个阻断项、装上一道自动门禁，灰度一个周期后再发 `1.0.0`。

### 不可动摇的前提（用户已明确）

1. **`AgentLoopContext` 可修改是设计目标，不是缺陷。** `getMessages()` 返回活列表、`setTools()` 就地替换，都是为了让钩子能在 `beforeModel` / `beforeRun` 改消息与工具名单。不改成只读视图，不引入不可变包装。
2. **提示词中心不做环境隔离。** 业务是"一套配置跑所有环境"，`activate()` 的全局唯一生效版本语义即终态。
3. **提示词中心不做 assistant 消息模板。** assistant 预填充只在 Anthropic 协议语义明确，为它给纯 Java 的 prompt 模块引入协议条件会破坏分层。

**曾讨论、已定方向（细节仍在评估）**：

| 编号 | 方向 | 定稿程度 |
| --- | --- | --- |
| Q1 | SPI 分两组：`PromptStore` / `SessionCache` / `AgentSessionStore` 承诺"只以 default 方法新增"；`HttpExecutor` / `AsyncHttpExecutor` / `ExecutorProvider` / `LlmTool` / `LlmCallStore` 承诺方法集终态 | 方向已定，落地形式待评估 |
| Q2 | `.internal` 包迁移放在 `1.0.0-rc1` 之前完成 | 方向已定，范围待评估 |
| Q3 | `store-jdbc` 补自动装配：`enabled` 默认 `true`，`table-name` 强制显式配置，缺失则启动失败 | 方向已定，细节待评估 |

一句话总纲：

> **冻结不是「写一句承诺」，而是「划清边界 + 装上会红的检查」。边界不清时承诺没有意义，没有门禁时承诺没有约束力。**

---

## 0. 先说人话（术语对照）

上一轮沟通里术语太多，这里先把词翻译成人话。后面章节直接用这些词。

| 术语 | 人话 |
| --- | --- |
| **对外契约** | 别人（业务方、二次开发者）能看见、能调用、能依赖的东西。包括：类名、方法签名、配置项名字、Bean 名字、数据库表结构、写进 Redis/数据库的 JSON 格式。 |
| **内部实现** | 别人看不见或不该依赖的部分。你随便改，只要对外表现不变。 |
| **SPI** | 预留的"插槽"。你定义接口，别人写实现类插进来（比如自定义 HTTP 客户端、自定义会话存储）。插槽的形状一改，别人的实现就编译不过。 |
| **二进制兼容** | 别人的代码**不重新编译**、直接换新 jar 也能跑。 |
| **源码兼容** | 别人的代码**重新编译一次**能过。二进制兼容通常也意味着源码兼容，反过来不一定。 |
| **冻结** | 公开承诺：这个版本之后，这类东西只会增加，不会删除、不会改签名、不会改含义。 |
| **门禁** | CI 里的一道自动检查。有人不小心删了个 public 方法，构建直接变红，而不是等用户升级后报 `NoSuchMethodError`。 |
| **default 方法** | Java 8 接口里带默认实现的方法。别人已经实现的接口，你**新增**一个 default 方法，别人的代码不用改；新增一个普通（抽象）方法，别人的代码立刻编译失败。这是"接口以后能不能安全长东西"的关键。 |
| **rc 版本** | Release Candidate，候选发布版。功能齐了但还没最终承诺，用来让真实业务先跑一段时间。 |

**一句话理解本次任务**：把"别人能碰的"和"别人不该碰的"分开，把"以后还想长的插槽"做成可扩展的形状，再加一道自动检查防止误伤。做完这些，"冻结"这两个字才有实际意义。

---

## 1. 现状结论：为什么现在不能宣布冻结

### 1.1 一个可验证的事实

用 `v0.1.0` 到当前 HEAD 做了逐签名对比：

| 对比项 | 结果 |
| --- | --- |
| 主源码 Java 文件数 | 两个版本都是 128 个 |
| 公开签名集合 | 两个版本都是 869 条，**完全相同** |
| 被删除的 public 行 | **0** |
| `v0.1.0` → HEAD 变动过的 Java 文件 | **1 个，且是测试文件**（`SerialCallbackExecutorTest`） |
| `v0.1.1` ~ `v0.1.7` 七个版本各自的主源码改动 | 全部 **0 增 0 删** |

### 1.2 这个事实说明什么

七个版本发下来，代码一个字节没变，改的全是 pom、CI 流水线、发布配置和文档。

这有两层含义，第二层才是问题：

1. **好消息**：没有反复横跳，API 形状是经过深思熟虑一次定下来的，不是拍脑袋迭代出来的。
2. **坏消息**：这套 API **从来没有被真实需求改造过**。你现在感觉它"稳定"，很可能只是因为它"没动过"。等第一个业务方提出「我要一个带超时的批量调用」「我要按用户维度统计 token」，你才会发现当初的形状哪里别扭——而那时候已经冻结了。

所以「能不能冻结」的真正答案不是看代码整不整齐，而是看：**那 5 个会让未来改动变得昂贵的结构问题，现在清掉没有**。

### 1.3 顺带发现的一个文档问题

`CHANGELOG.md` 的 0.1.1 条目描述了「`ModelResolver` 20 处异常归位 `Kind.CONFIG`」这项修复，但该修复在 `v0.1.0` 的代码里就已经存在（`grep` 计数为 20）。也就是说这七个版本更像是同一份代码的**重复打包发布**，而非功能迭代。

**影响**：不影响运行，但会让「1.0 已灰度一个版本周期」这个门槛失去意义——灰度的是同一份代码。需要在发布前把 CHANGELOG 与实际差异对齐（见 Phase 5）。

---

## 2. 核心决策：三层划分

冻结不是一个开关，是三档。先把每个公开类型归到对应档位，再谈承诺。

### 2.1 第一层：冻结（承诺不改）

**判据**：业务代码直接 `new` / 直接调用、且改动成本会传导到用户业务逻辑。

- `LlmCall`、`LlmMessage`、`LlmResult`、`LlmUsage`、`LlmTool`、`LlmImage`、`LlmThinkingLevel`
- `LlmStopReason`（真正冻结的是 `wire()` 的字符串值，不是枚举名）
- `RetryPolicy` 及其 `Builder`
- `LlmCallException`（`Kind` 枚举只能追加，不能重排或改名）
- `LlmGateway`、`Workflow`、`AgentLoop` 的公开方法
- `AgentSession`、`AgentSessionMessage`、`AgentSessionCodec` 的 JSON 字段名
- `PromptManager`、`RenderedPrompt`、`PromptVersion`、`PromptKey`
- 全部 `ai-lite.*` 配置键名与语义
- 三份 DDL（`AI_LITE_SESSION`、`AI_LITE_PROMPT_*`、`AI_LITE_LLM_CALL`）
- Bean 名（`aiLiteRetryScheduler`、`aiLiteCallbackExecutor` 等）与 `@ConditionalOnMissingBean` 的覆盖语义

**为什么这批安全**：这些类基本是「不可变对象 + 静态工厂 + 私有构造器」（`LlmCall.java:22` 是 private 构造器，只能走 `explicit()` / `profile()`），未来加字段不会破坏二进制兼容；枚举已经把持久化值抽到 `wire()`；配置类是纯 setter，加字段天然向后兼容。

**唯一的例外，且是设计目标而非缺陷**：`AgentLoopContext.getMessages()` 返回**活的 `ArrayList`**、`setTools()` 直接 `clear()` + `addAll()`（`AgentLoopContext.java:41-62`）。这是**有意为之**——钩子必须能就地改消息和工具名单，`beforeModel` 才有意义。冻结时只需把"可修改上下文是钩子契约的一部分"写进文档，**不改实现、也不考虑改成只读视图**。

### 2.2 第二层：半冻结（只增不改，且新增必须是 default 方法）

**判据**：第三方会写实现类插进来的接口（SPI）。这类接口的**方法集**是硬契约。

| 接口 | 现有抽象方法数 | 现有 default 数 | 未来能否安全加方法 |
| --- | --- | --- | --- |
| `PromptStore` | 8 | 0 | **不能**，加了就炸所有实现 |
| `LlmTool` | 4 | 0 | **不能** |
| `HttpExecutor` | 3 | 0 | **不能** |
| `SessionCache` | 3 | 0 | **不能** |
| `AsyncHttpExecutor` | 2 | 0 | **不能** |
| `ExecutorProvider` | 2 | 0 | **不能** |
| `LlmCallStore` | 1 | 0 | **不能**（但只有 1 个，风险低） |
| `LlmTransport` | 3 | 1 | 勉强可以 |
| `ToolExecutor` | 2 | 1 | 勉强可以 |
| `AgentSessionStore` | 8 | 4 | 可以 |
| `AgentLifecycle` | 3 | 8 | 可以（设计得最好） |
| `SyncStep` | 2 | 1 | 可以 |

**已定方案（Q1）**：按"未来会不会长"分两组，分别给出不同的冻结承诺。

**A 组——承诺「只以 default 方法新增」**（未来允许加能力，但必须带默认实现）：

| 接口 | 现有抽象方法 | 现有 default | 承诺 |
| --- | --- | --- | --- |
| `PromptStore` | 8 | 0 | 新增一律 default，默认抛 `UnsupportedOperationException` |
| `SessionCache` | 3 | 0 | 同上 |
| `AgentSessionStore` | 8 | 4 | 同上（已经是这个模式） |

**B 组——承诺「方法集终态」**（1.0 后不再新增方法，新能力走新接口）：

| 接口 | 现有抽象方法 | 现有 default | 承诺 |
| --- | --- | --- | --- |
| `HttpExecutor` | 3 | 0 | sealed，方法集不动 |
| `AsyncHttpExecutor` | 2 | 0 | sealed |
| `ExecutorProvider` | 2 | 0 | sealed |
| `LlmTool` | 4 | 0 | sealed |
| `LlmCallStore` | 1 | 0 | sealed |

**未列入两组的其余 SPI**（`LlmTransport`、`ToolExecutor`、`SyncStep`、`AsyncStep`、`AgentLifecycle`）按 A 组处理：它们已经有 default 方法的先例，保持"新增走 default"即可。

**为什么这样分**：A 组是业务扩展点，需求可预期（"按人查版本""批量归档""按时间清理"这类），必须留生长空间；B 组是技术底层，形状由协议和线程模型决定，本身已经够用，再长东西说明设计出问题了，应当开新接口而不是往老接口里塞。

**落地要求**：这条承诺必须同时出现在三处，否则不算数——
1. `guide-上层调用API.md` 的稳定性表逐接口标注 A/B 组；
2. 每个接口的 javadoc 顶部写明所属组别；
3. Phase 2 的 japicmp 对 A 组做"只允许新增 default 方法"的特化检查，对 B 组做"禁止任何方法变更"的严格检查。

### 2.3 第三层：自由（内部实现，随时可改，但需要先清边界）

这批是文档里已经写了「不保证兼容」，但代码上仍然是 `public` 的类：

`ModelResolver`、`ProviderGuard`、`RetrySupport`、`SerialCallbackExecutor`、`AsyncLlmCallLogger`、`AgentSessionStoreSupport`、`Utf8LineBuffer`、`SequentialToolExecutor`、`PlatformExecutorProvider`、`DirectExecutorProvider`、`NoopLlmCallStore`、`RetryScheduler`

**问题**：只要它们是 `public`，一旦宣布冻结，用户就会理直气壮地依赖它们；而且 `AiLiteAutoConfiguration` 自己的 `@Bean` 方法签名里就用了这些类型（`aiLiteModelResolver` 返回 `ModelResolver`、`aiLiteRetryScheduler` 返回 `ScheduledExecutorService`），等于已经把它们变成了事实契约。

**一个必须先纠正的判断**：我上一轮说"收成包内可见"是错的。实测这 12 个类里只有 `SerialCallbackExecutor` 只在 core 内部使用，其余都被其他模块引用：

| 类 | 还被谁用 |
| --- | --- |
| `RetrySupport` | `ai-lite-executor-coroutines` |
| `AgentSessionStoreSupport` | `ai-lite-session-jdbc`、`ai-lite-session-redis` |
| `Utf8LineBuffer` | `ai-lite-http-jetty`、`ai-lite-http-webclient` |
| `NoopLlmCallStore` / `SequentialToolExecutor` / `RetryScheduler` / `ProviderGuard` / `ModelResolver` / `PlatformExecutorProvider` / `DirectExecutorProvider` / `AsyncLlmCallLogger` | `ai-lite-spring-boot-starter` |

所以 `package-private` 走不通。正确做法是**加 `.internal` 包名**作为边界标记（见 Phase 0）。

---

## 3. 五个阻断项（不解决就不能宣布冻结）

### B1. 内部实现类没有物理边界

**现状**：12 个类文档说不保证兼容、代码是 `public`、还被跨模块引用，且出现在自动装配的 `@Bean` 签名里。

**为什么阻断**：冻结后无法区分"用户依赖了公开 API"和"用户误依赖了内部类"，兼容性检查也没法排除它们，只能被迫为内部实现背书。

**执行期修正（2026-10-26，逐签名扫描后）**：原计划"12 个全迁"是错的。其中 **3 个已经泄漏进公开签名**，迁移会直接破坏公开 API：

| 类 | 泄漏位置 | 处置 |
| --- | --- | --- |
| `ModelResolver` | `LlmGateway` 两个 public 构造器 | **不迁**，承认其为公开契约 |
| `ProviderGuard` | `LlmGateway.setGuards(Map<String, ProviderGuard>)` | **不迁**，承认其为公开契约 |
| `SerialCallbackExecutor` | `CallbackPolicy.newSerialExecutor()` 返回类型 | **不迁**，承认其为公开契约 |

**实际迁移 9 个**，迁到 `io.github.yinwenyuan28.ailite.internal.<层>`：

| 原包 | 新包 | 类 |
| --- | --- | --- |
| `llm` | `internal.llm` | `RetrySupport`、`AsyncLlmCallLogger`、`SequentialToolExecutor`、`NoopLlmCallStore`、`RetryScheduler` |
| `session` | `internal.session` | `AgentSessionStoreSupport` |
| `http` | `internal.http` | `Utf8LineBuffer` |
| `executor` | `internal.executor` | `PlatformExecutorProvider`、`DirectExecutorProvider` |

**这个修正反而让边界更诚实**：迁移完成后，"公开包里的一切都是公开契约，`.internal` 里的一切都不是"这条不变式才真正成立。留在公开包的 3 个类本来就应该被 japicmp 检查，不该被排除。

### B2. SPI 缺少明确的演化承诺

**现状**：见 §2.2 表格，A 组三个接口 0 个 default 方法（`AgentSessionStore` 例外，已有 4 个），B 组五个接口 0 个。

**为什么阻断**：冻结意味着承诺"只增不改"。对没有 default 方法的接口，"增"就是破坏。

**已定方案（Q1）**：见 §2.2 的 A/B 分组。A 组承诺"新增只走 default"，B 组承诺"方法集终态"。

**注意**：A 组这条承诺不要求现在就往接口里塞预留方法。真正的约束是**将来新增时必须是 default**，而不是"必须现在预留"。所以本阶段的工作量主要在文档与门禁，不在改代码。

### B3. 没有兼容性门禁

**现状**：全仓库搜索不到 `japicmp` / `revapi` / `clirr`。承诺只存在于文档里。

**为什么阻断**：没有门禁的冻结，等于没有冻结。一次误删 public 方法，要到用户升级后 `NoSuchMethodError` 才暴露。

**解决方向**：接 `japicmp-maven-plugin`，基线固定为上一个 release tag，破坏性变更让 `mvn verify` 直接失败。同时把 `.internal` 包排除出检查范围。

### B4. 三个 JDBC 存储模块的装配体验不一致

**现状**：

| 模块 | 有 `spring.factories` 自动装配 | 用户引入后 |
| --- | --- | --- |
| `ai-lite-session-jdbc` | 有 | 自动生效 |
| `ai-lite-prompt-jdbc` | 有 | 自动生效 |
| `ai-lite-store-jdbc` | **没有** | 还得手写 `@Bean JdbcLlmCallStore` |

**为什么阻断**：这是"行为契约"，一旦冻结就很难改——补齐会改变现有用户的装配结果，不补则要永远解释"为什么这一个不一样"。

**已定方案（Q3）**：补齐自动装配，`enabled` 默认 `true`，`table-name` 必须显式配置，缺失即启动失败。

#### B4.1 目标形状

新增 `AiLiteStoreJdbcProperties`：

```java
@ConfigurationProperties(prefix = "ai-lite.store.jdbc")
public class AiLiteStoreJdbcProperties {

    /** 是否启用 JDBC 调用记录。默认 true：引入模块即生效。 */
    private boolean enabled = true;

    /** 调用记录表名。无默认值——必须显式配置。 */
    private String tableName;
}
```

新增 `AiLiteStoreJdbcAutoConfiguration`，条件与 `session-jdbc` 对齐：

```
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration")
@AutoConfigureBefore(name = "io.github.yinwenyuan28.ailite.autoconfigure.AiLiteAutoConfiguration")
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "ai-lite", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "ai-lite.store.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
```

Bean 方法：

```
@Bean
@ConditionalOnMissingBean(LlmCallStore.class)
@ConditionalOnBean(JdbcTemplate.class)
public LlmCallStore aiLiteJdbcCallStore(JdbcTemplate jdbcTemplate, AiLiteStoreJdbcProperties properties)
```

`@AutoConfigureBefore` 是必须的：starter 的 `aiLiteCallStore()` 返回 `NoopLlmCallStore` 且带 `@ConditionalOnMissingBean`，若它先执行会把空实现注册上，JDBC 实现反而被挤掉。

#### B4.2 关于"编译时提示"——需要澄清一个技术边界

**做不到的事**：Java 编译期看不到运行时的 `application.yml`。用户配不配 `table-name` 是运行时信息，编译器和 Maven 插件都无从得知。所以"编译时检查用户有没有配表名"在技术上不成立。

**能做到的三层，按有效性排序**：

| 层次 | 手段 | 何时暴露 | 效果 |
| --- | --- | --- | --- |
| **启动期（推荐，必做）** | `@Validated` + `@NotBlank` 校验 `tableName` | 应用启动时 | 启动直接失败，消息明确指出缺哪个配置 |
| **IDE 编写期** | `additional-spring-configuration-metadata.json` 里给 `ai-lite.store.jdbc.table-name` 标 `"required": true` | 写 YAML 时 | IDEA 黄色波浪线提示，但用户可能忽略 |
| **构建期（另一次机会）** | `spring-boot-configuration-processor` 生成元数据 | 打包时 | 只影响元数据完整性，不校验取值 |

所以落地的是**启动期 fail-fast + IDE 元数据提示**这两个。启动期那条是硬保证，IDE 那条是体验优化。

**实现方式**：`tableName` 字段不加默认值，配 `@NotBlank`（需在 starter 侧 pom 加 `spring-boot-starter-validation`，或在 `@Bean` 方法里手写校验以避免新增依赖——倾向后者，理由见 §8 的"不引入新依赖"原则）。

手写校验的形状：

```java
@Bean
@ConditionalOnMissingBean(LlmCallStore.class)
@ConditionalOnBean(JdbcTemplate.class)
public LlmCallStore aiLiteJdbcCallStore(JdbcTemplate jdbcTemplate,
                                        AiLiteStoreJdbcProperties properties) {
    String table = properties.getTableName();
    if (table == null || table.trim().isEmpty()) {
        throw new IllegalStateException(
                "ai-lite.store.jdbc.table-name 必须显式配置（调用记录表名），"
                + "并确认已执行 ai-lite-llm-call.sql 建表");
    }
    return new JdbcLlmCallStore(jdbcTemplate, table);
}
```

这条错误消息同时覆盖了"没配表名"和"忘了建表"两个最常见的坑。

#### B4.3 一个需要留意的行为变化

`enabled` 默认 `true` + `table-name` 必填，组合结果是：**用户引入 `ai-lite-store-jdbc` 但没配 `table-name` → 应用启动失败**。

这是有意的（fail-fast 优于悄悄不生效），但属于破坏性变更，必须写进 `1.0.0-rc1` 的 CHANGELOG 迁移说明：

> 升级后若引入了 `ai-lite-store-jdbc` 但未配置 `ai-lite.store.jdbc.table-name`，应用将启动失败。请补充该配置；不需要 JDBC 调用记录的用户可设 `ai-lite.store.jdbc.enabled=false`。

#### B4.4 `table-name` 不给默认值的理由

`session-jdbc` 的 `tableName` 有默认值 `AI_LITE_SESSION`，为什么这里不给？

因为两者的失败模式不同：
- 会话存储挂了，用户**立刻**发现（聊不了天）。
- 调用记录写失败被 `AsyncLlmCallLogger` 吞掉，只留一行 warn（`AsyncLlmCallLogger.java:49`），用户可能几周都不知道。

对一个静默失败的功能，强制显式配置是让用户确认"我知道我在写这张表"的唯一机会。

### B5. 文档里的版本号会把生产用户带进坑

**现状**：11 个文档里共 23 处写着 `0.1.0-SNAPSHOT`，包括 `README.md` 的"快速开始"和 `docs/guide-上层调用API.md` 的依赖示例。

**为什么阻断**：这是给生产用户看的复制粘贴入口。照着写会解析不到（SNAPSHOT 未发布到 Central）。

**解决方向**：发布前统一替换为正式版本号，并考虑把示例版本抽成 `<version>${ai-lite.version}</version>` 提示读者替换。

---

## 4. 四个非阻断但建议同批处理的问题

### N1. `providers` / `profiles` 没有配置元数据

`spring-configuration-metadata.json` 只有 42 个扁平属性，最复杂的 `ai-lite.llm.providers.<id>.models[]` 完全没有嵌套提示。

**影响**：IDE 里写供应商配置没有任何补全和校验，全靠查文档。生产接入体验差。

**方向**：在 starter 里加 `additional-spring-configuration-metadata.json`，把嵌套结构手写进去（`Map<String, Provider>` 这种 Boot 自动生成不出来）。

### N2. 配置错误不在启动时报

**现状**：传输名写错是 fail-fast（`AiLiteAutoConfiguration.createHttpExecutor` 最后抛 `IllegalStateException`）；但供应商、模型、profile 配错要等第一次调用，由 `ModelResolver` 的 20 处校验抛出。

**影响**：生产环境常见故障是"上线后第一次真实请求才炸"。

**方向**：加一个 `SmartInitializingSingleton`（或 `ApplicationRunner`）在启动末尾对 `ModelResolver` 做一次自检，把"模型配错"提前到启动期。注意不要引入 bean validation 依赖。

### N3. `thinking-format` 文档与代码轻微漂移

- 代码支持 `qwen`（`OpenAiCompletionsTransport.java:266`），文档没写。
- `AiLiteConfig.java:282` 的注释列了 `anthropic-budget` 却漏了 `anthropic-adaptive`；而 `AnthropicMessagesTransport.java:179` 实际判断的是 `anthropic-adaptive`。

**影响**：用户按注释配 `anthropic-budget` 会走到 else 分支（默认 budget 模式），恰好能用，所以一直没被发现。属于"歪打正着"，冻结前应统一口径。

### N4. `CAHNGELOG` 与实际差异不符

见 §1.3。

---

## 5. 实施阶段

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| **Phase 0（P0）** | 内部类迁 `.internal` 包：12 个类，其中 11 个被 6 个模块跨模块引用（`executor-coroutines`、`session-jdbc`、`session-redis`、`http-jetty`、`http-webclient`、`spring-boot-starter`），需同步改 import；`package-info.java` 标注"不保证兼容" | `mvn -B verify` 全绿；`grep public.*internal` 确认无内部类残留在公开包；japicmp 排除规则生效 |
| **Phase 1（P0）** | SPI 演化承诺：按 Q1 的 A/B 分组，在 `guide-上层调用API.md` 稳定性表标注、每个接口 javadoc 顶部标注组别；A 组不做代码改动（承诺约束的是未来新增方式，非现在预留方法） | 稳定性表与实际接口方法集一致；A/B 分组在文档与 javadoc 两处对得上 |
| **Phase 2（P0）** | 接 `japicmp-maven-plugin`：基线 = 上一个 release tag，排除 `.internal` 包与 `*-internal` 类型；对 B 组做严格检查（禁止任何方法变更），对 A 组允许新增 default 方法；接入 `ci.yml` 的 verify 阶段 | 故意删一个 public 方法能让构建变红（反向验证）；故意给 B 组加方法也能变红；正常构建保持绿 |
| **Phase 3（P0）** | `ai-lite-store-jdbc` 补自动装配：`spring.factories` + `AiLiteStoreJdbcProperties`（`enabled` 默认 true、`tableName` 无默认值）+ 启动期必填校验 + `@AutoConfigureBefore` 防让位失效；补装配测试 | 配了 `table-name` → 自动出现 `JdbcLlmCallStore`；没配 → 启动失败且消息含"table-name"与建表提示；`enabled=false` → 无 Bean；用户自定义 `LlmCallStore` → 自动配置让位 |
| **Phase 4（P1）** | 文档与元数据：23 处版本号替换；`additional-spring-configuration-metadata.json`；`thinking-format` 口径统一；`AgentLoopContext` 可变性写进文档；CHANGELOG 对齐 | 文档内 `0.1.0-SNAPSHOT` 计数为 0；IDE 能补全 `providers` 嵌套字段 |
| **Phase 5（P1）** | 发布 `1.0.0-rc1`：翻版本、打 tag、更新 CHANGELOG 的"1.0 冻结范围"章节 | Central 上出现 `1.0.0-rc1`；`docs/ref-发布.md` 的 1.0 门槛条目更新为"已满足 X / 待满足 Y" |
| **Phase 6（P2）** | 真实业务灰度一个版本周期后发 `1.0.0` | 至少有 1 个真实业务在 rc1 上跑满一个周期且无契约类问题 |

### Phase 0 的额外约束（重要）

迁移包名本身是破坏性变更。必须满足两条才能动：

1. 在 `CHANGELOG.md` 的 `1.0.0-rc1` 段落里单独一节「包名迁移说明」，列出新旧对应表。
2. 迁移**只搬内部类**，公开 API 的包名一律不动（`io.github.yinwenyuan28.ailite.llm` 等保持原样）。否则迁移范围会失控。

---

## 6. 测试矩阵

| 层级 | 用例 | 断言 |
| --- | --- | --- |
| 签名 | `japicmp` 对比上一个 release | 破坏性变更 = 构建失败 |
| 签名 | `.internal` 包排除验证 | 内部类改动不触发失败 |
| 装配 | `store-jdbc` 条件矩阵 | ① 有 `JdbcTemplate` + 配了 `table-name` → 出现 `JdbcLlmCallStore`；② 有 `JdbcTemplate` + 未配 `table-name` → 启动失败，消息含 `table-name` 与建表提示；③ `enabled=false` → 无 `LlmCallStore` Bean；④ 用户自定义 `LlmCallStore` → 自动配置让位，不出现两个实现；⑤ 无 `JdbcTemplate` → 自动配置整体跳过，不报错 |
| 装配 | `store-jdbc` 执行顺序 | starter 的 `NoopLlmCallStore` 不得抢先注册（验证 `@AutoConfigureBefore` 生效） |
| 装配 | 现有 `AiLiteAutoConfigurationTest` 等 8 个装配测试 | 迁移包名后仍全绿 |
| 持久化 | `AgentSessionTest` 里的精确 JSON 断言（`AgentSessionTest.java:31`） | 冻结后字段名/时间戳格式不变 |
| 持久化 | 三份 DDL 建表 + 现有 JDBC 存储测试 | 表结构不改 |
| 配置 | 启动期自检（N2 若做） | 模型配错在启动时抛，错误消息含 provider/model 名 |
| 反向 | Phase 2 的"故意删方法"验证 | 门禁真的会红，不是摆设 |

---

## 7. 已定问题（2026-10-26 拍板）

三个待决问题已全部确定，原始选项保留供追溯。

**Q1（对应 B2）：七个 SPI 如何演化？→ 已定：按 A/B 两组分别承诺。**

- A 组（`PromptStore` / `SessionCache` / `AgentSessionStore`）：承诺"新增一律走 default 方法"。
- B 组（`HttpExecutor` / `AsyncHttpExecutor` / `ExecutorProvider` / `LlmTool` / `LlmCallStore`）：承诺"方法集终态"，新能力开新接口。

落地细节见 §2.2。注意 A 组**不需要**现在补预留方法——承诺约束的是未来新增的方式。

**Q2（对应 B1）：`.internal` 包迁移放在 rc1 前还是 1.0？→ 已定：rc1 前完成。**

理由：迁移成本随外部依赖数量单调上升，越晚做越贵；且 rc1 若带着待迁移状态，灰度结果参考价值会打折扣。

**Q3（对应 B4）：`store-jdbc` 补齐自动装配还是文档说明？→ 已定：补齐，`enabled` 默认 true，`table-name` 强制显式配置。**

- 引入模块即生效（`enabled` 默认 `true`）；
- 但 `table-name` 无默认值，未配置则**启动失败**；
- "编译时提示"技术边界与替代方案见 §B4.2；
- 这是一处破坏性变更，需写进 rc1 的迁移说明（§B4.3）。

---

## 8. 明确不做

- 不做「把所有 public 类都改一遍」的洁癖式重构。只动 `.internal` 那 12 个。
- 不做 JPMS（Java 9 模块化）。基线是 JDK 8，用包名约定足够。
- 不做「1.0 前把所有 API 重新设计一遍」。目标是把现有形状**固定下来**，不是推倒重来。
- 不引入 `revapi`（与 japicmp 功能重叠，选一个即可）。
- **不为 `table-name` 校验引入 `spring-boot-starter-validation`**：这是一个字段的非空校验，加一整条 validation 依赖链（含 Hibernate Validator、EL 实现）不划算，且会让 starter 的依赖面变宽。手写 `if` 校验即可，错误消息还能同时提示建表。
- 不实现「编译期检查 YAML 配置」——技术上不成立（见 §B4.2），不要为不可能的目标设计机制。
- 不把 `JdbcLlmCallStore` 改造成带查询 API 的完整 DAO。它是"电表"，只负责写入；查询由用户自己的 SQL 完成。

---

## 9. 提示词中心：调研结论与演进取舍（2026-10-26）

调研了 Langfuse、Pezzo、Spring AI 三个成熟项目的提示词模型，对照本库现状后形成以下结论。

### 9.1 现状对比

| 能力 | 本库 | Langfuse | Pezzo |
| --- | --- | --- | --- |
| 版本号递增 | 有 | 有 | 有 |
| 草稿 / 发布 / 回滚 | 有 | 有（label 语义） | 有（environment 语义） |
| 审计 | 有（但 detail 恒空，见 12.3） | 有 | 有 |
| 输出 schema | 有 | 有（config） | 有（settings） |
| 环境隔离 / 多 label | **无** | label 数组 | environment 表 |
| 多消息对话模板 | **无** | `type=chat` | `content` JSON |
| 模板组合引用 | 无 | `@@@langfusePrompt:...@@@` | 无 |
| commit message | **有字段但从未写入** | 有 | 无 |
| 客户端缓存 | 有（TTL） | 有（cache_ttl_seconds） | 无 |
| 兜底模板 | 有 | 有（fallback） | 无 |

结论：版本流转与兜底比多数开源项目严谨（`outputSchema`、双层兜底是优势）；差距集中在**环境隔离**和**多消息模板**两个维度。

### 9.2 环境隔离：决定不做（用户已确认）

**已定**：业务是"一套配置跑所有环境"，不做 label / environment 隔离。

**这意味着什么**：

- `PromptStore.activate()` 的"全局唯一生效版本"语义**就是终态**，不是过渡形态。
- 不需要引入 `findActive(key, label)` / `setLabel()` / `removeLabel()` / `labels()` 这组方法。
- `PromptStore` 从"确定会长的接口"降级为"基本定型"——它的 8 个方法覆盖了完整生命周期，剩下的生长空间主要是查询类方法（见 12.4）。

**但仍然保留 A 组承诺**：即使现在判断"不需要 label"，将来仍可能出现"按操作人查版本""按时间范围查审计"这类纯查询需求。所以 `PromptStore` 依然承诺"新增只走 default 方法"，成本为零，收益是保留退路。

**必须在 javadoc 写明的边界**（否则将来引入 label 时无法解释）：
> 当前语义为全局唯一生效版本，不支持环境隔离。若未来引入 label，`activate` 语义将扩展为"给目标版本打标签"而非"退役其他版本"；自定义实现需在此变更时适配。

### 9.3 缺口三：commit message —— 判断为"修复缺陷"而非"新增功能"

**关键事实（已核实）**：`PromptManager` 的四处 `store.audit(...)` 调用（`:167`、`:201`、`:227`、`:257`）全部传入 `null` 作为 `detail`。而 `PromptAudit.detail` 字段、JDBC 的 `DETAIL` 列（`JdbcPromptStore.java:194`）都是齐全的。

也就是说：**数据库里有这一列，代码里永远写 null**。

这不是"缺少功能"，是**半成品**。三种处理方式：

| 方案 | 成本 | 效果 |
| --- | --- | --- |
| 补上 detail 写入（记录变更摘要） | 低 | 审计表真正可读，能看出"这次改了什么" |
| 删除 detail 字段 | 中（破坏 DDL 契约） | 表变干净，但失去扩展位 |
| 保持现状 | 零 | 留一个永远为空的列，误导使用者 |

**建议**：补上写入。`createDraft` / `saveDraft` 各加一个 `String message` 参数会产生**新的重载**（不破坏现有签名），把 message 写进 `detail`；未传时写入自动生成的摘要（如"从 v3 继承，修改了 user 模板"）。

### 9.4 缺口二：多消息模板 —— 建议不做（待确认）

**原拟用途**：给 `PromptVersion` 增加 `List<PromptTemplateMessage> messages`，让提示词能渲染出超过 system+user 两条消息。

**修正一：few-shot 不是理由。** 早前草案举的例子（把筛选示例写成 user/assistant 轮次）是错的——判断标准与 few-shot 示例本来就应当放在 system 里，OpenAI 官方指引也如此；把示例做成假对话轮次反倒会让模型误以为是真实历史。**该例子作废。**

**真正的用途只有两个，且都受限**：

| 用途 | 实际约束 |
| --- | --- |
| assistant 预填充（让模型从指定开头续写，如强制输出 `{`） | **只有 Anthropic 协议语义明确**。OpenAI Chat Completions 允许末条 assistant，但服务端行为不稳定；Responses 协议把 assistant 当作历史消息处理，不构成预填充。 |
| 真实对话续写 | 这是 `AgentSessionLoop` 的职责，不属于提示词中心。 |

**用户给出的两个选项**：(a) 只在 `anthropic-messages` 下允许 assistant 模板；(b) 干脆不做。

**建议选 (b)，理由三条**：

1. **与传输层解耦是硬约束。** `ai-lite-prompt` 是纯 Java 模块，不依赖任何协议。让"是否允许 assistant 消息模板"取决于当前 provider 的 `api`，等于把协议知识塞进提示词中心，或要求它运行期查询 `ModelResolver` 判断——两种都会破坏分层。
2. **只在单一协议下可用的数据模型是陷阱。** 若 `PromptVersion` 允许存 assistant 模板，业务后来把 provider 从 Anthropic 换成 OpenAI 时，提示词会静默失效或行为改变。这种"配置合法但换供应商就坏"的设计，比不支持更难排查。
3. **有完整绕法且成本低。** 需要预填充的用户可在渲染后自己拼消息：

   ```java
   RenderedPrompt rendered = promptManager.render("json-extract", vars);
   List<LlmMessage> messages = new ArrayList<>(rendered.getMessages());
   messages.add(LlmMessage.assistant("{", null));   // 预填充
   gateway.one(LlmCall.profile("biz", "main", messages));
   ```

   代价是这几条消息脱离提示词中心的版本管理与审计；但预填充本身只是几个字符，风险极低。

**若最终选 (a) 而非 (b)**，实现约束是：`PromptVersion` 不存 assistant 模板，改由 `PromptKey` 层声明该提示词只允许在 anthropic 协议下使用，并在 `PromptManager.render` 时校验当前 provider 的 `api`；一旦不匹配直接抛 `PromptException`，绝不静默降级。

**无论选哪个，都应在 javadoc 写明的边界**：
> 提示词模板只产出 system 与 user 两段文本。assistant 预填、多轮历史、多模态图片均不在提示词中心职责内；需要时请自行构造 `LlmMessage` 列表，或用 `AgentSessionLoop` 管理会话。

### 9.5 明确不做

- **模板组合**（Langfuse 的 `@@@langfusePrompt:...@@@`）：需要依赖解析、循环检测、嵌套深度限制，复杂度高；业务用字符串拼接或 Java 侧组装即可。
- **A/B 测试框架**：属于上层实验平台，不属于存储层。用户可用不同的 prompt key 自行实现。
- **多语言变体**：用 key 命名区分（`resume-parse-zh`）即可。
- **推送通知**：外围能力。
- 不动 `ai-lite-executor-jdk21` / `ai-lite-http-okhttp-jdk21` 的 JDK 21 profile 结构。
- 不为并行工具、虚拟模型路由等未实现能力预留 API——冻结点只覆盖已经存在的东西。

---

## 10. 风险与回滚

| 风险 | 概率 | 影响 | 应对 |
| --- | --- | --- | --- |
| `.internal` 迁移漏改某个模块的 import | 中 | 编译失败（可立即发现） | 全量 `mvn -B verify`；CI 矩阵 JDK 8/17/21 × Boot 2.1/2.7 会覆盖 |
| japicmp 接入后历史 tag 不可解析（本地无 tag / 浅克隆） | 中 | CI 报错但不是真问题 | 基线用 `maven-metadata` 或固定版本号而非 git tag；CI 里 `fetch-depth: 0` |
| 补 default 方法时改变了已有实现的行为 | 低 | 隐性回归 | 新增方法默认实现必须是"抛 `UnsupportedOperationException`"或"委托旧方法"，不能有真实逻辑 |
| rc1 灰度期间发现契约要改 | 中 | 要发 rc2 | 这是 rc 的正常用法，`1.0.0-rc2` 不丢人；比直接发 1.0 后改强得多 |
| CHANGELOG 对齐时发现历史版本内容有误 | 高 | 需要改历史文档 | 不改历史 tag 的内容，只在 1.0.0-rc1 段落里加一条"历史版本差异说明" |

**回滚**：Phase 0–4 全部是源码与构建改动，未发布前 `git revert` 即可。Phase 5 一旦发布到 Central，版本号不能被覆盖，只能发 rc2。

---

## 11. 时间估算

| 阶段 | 估算 | 说明 |
| --- | --- | --- |
| Phase 0 | 1–2 天 | 机械改动为主，风险在漏改 |
| Phase 1 | 0.5 天（路线 B）/ 1 天（路线 A） | 取决于 Q1 |
| Phase 2 | 0.5–1 天 | japicmp 配置 + 反向验证 |
| Phase 3 | 0.5 天 | 三条件 + 装配测试 |
| Phase 4 | 0.5 天 | 文档批量替换 + 元数据手写 |
| Phase 5 | 0.5 天 | 走既有发布清单 |
| **合计** | **约 4–6 个工作日** | 不含灰度周期 |

---

## 12. 如果时间紧、必须先上生产

不做冻结，但把边界说清楚。具体动作：

1. 只把 §2.1 列的第一层类型写进 `guide-上层调用API.md` 的稳定性表，标注「1.0 起冻结」。
2. §2.2 的 SPI 全部标注「1.0 前仍可能新增方法，自定义实现请做好适配准备」。
3. `.internal` 那 12 个类在文档里逐个点名：「不要依赖，随时会改」。
4. `CHANGELOG` 里写一段「0.1.x 的稳定性边界」，说明哪些能放心用、哪些不能。
5. Phase 4 的文档版本号修正**必须做**（这是唯一会直接坑到用户的项）。

这样用户知道哪些能依赖、哪些不能，你也不用为一个尚未被真实需求验证过的设计背书。
