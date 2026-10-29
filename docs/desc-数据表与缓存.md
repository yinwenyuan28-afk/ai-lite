# 数据表与读取缓存

> **本文在哪一层**：横切层（数据库与缓存都属于横切关注点，不属于某一条调用链）。
> **讲什么**：库里一共有哪些表、每张表能不能改名、怎么配；以及哪些"常读数据"有本地缓存、缓存多久、怎么失效。
> **不讲什么**：配置键的完整清单（见 [配置参考](ref-配置.md)）；管理 API 的形状（见 [管理接口参考](ref-管理API.md)）。

## 1. 表的整体分布

库里的数据分成四组，分别由四个模块的自动装配负责。这四组是独立的：只引 `ai-lite-store-jdbc` 就只会有调用记录那两张表，不会连带建目录表。

| 组 | 张数 | 所属模块 | 建表脚本 |
| --- | --- | --- | --- |
| 运行时目录 | 4 | `ai-lite-admin-api` | `ai-lite-catalog.sql` |
| 提示词 | 3 | `ai-lite-prompt-jdbc` | `ai-lite-prompt.sql` |
| 调用记录 | 2 | `ai-lite-store-jdbc` | `ai-lite-llm-call.sql` |
| 会话 | 1 | `ai-lite-session-jdbc` | `ai-lite-session.sql` |

合计 10 张。`ai-lite-demo` 下的 `schema.sql` 是这 10 张的合并版，只给 demo 用。

### 1.1 运行时目录（4 张）

目录的作用是让"改供应商、改模型、改业务绑定"不用重启；这些内容写在库里，配置文件的同名项是兜底。

| 表 | 内容 | 读写方 |
| --- | --- | --- |
| `AI_LITE_PROVIDER_OVERLAY` | 供应商覆盖：地址、密钥、两个超时 | 管理页写；网关每次解析模型时读 |
| `AI_LITE_MODEL` | 模型清单与能力参数（上下文窗口、输出上限、思考格式等） | 管理页写（含从 `/models` 同步）；网关读 |
| `AI_LITE_BIZ_BINDING` | 业务 id → 供应商 + 模型 + 思考档 | 管理页写；网关读 |
| `AI_LITE_ADMIN_SETTING` | 管理端运行时开关，目前只存 `call-body` 档位一行 | 管理页读写；网关读档位 |

`AI_LITE_ADMIN_SETTING` 是键值表，不是"每个开关一列"。新增开关只需加一行，不用改表结构。

### 1.2 提示词（3 张）

| 表 | 内容 |
| --- | --- |
| `AI_LITE_PROMPT_VERSION` | 版本与模板正文（系统模板、用户模板、输出 schema） |
| `AI_LITE_PROMPT_ACTIVE` | 每个 key 当前生效的版本指针 + revision |
| `AI_LITE_PROMPT_AUDIT` | 操作审计，不记录模板正文 |

三张表是一个整体：发布动作要依次写"退役旧版本 → 发布目标版本 → 更新生效指针 → 记审计"，只改其中一两张的名字会得到语义不完整的状态。

`AI_LITE_PROMPT_VERSION` 上的 `(PROMPT_KEY, VERSION_NO)` 唯一索引是多节点并发起草的最后防线，建表时不要省。

### 1.3 调用记录（2 张）

| 表 | 内容 |
| --- | --- |
| `AI_LITE_LLM_CALL` | 摘要：业务 id、供应商、模型、token、延迟、停止原因、错误摘要 |
| `AI_LITE_LLM_CALL_BODY` | 正文：输入输出文本，只在开启记录时写入 |

两张表分开是因为正文可能很大，而摘要要被频繁翻页查询。摘要写入与正文写入是两条独立语句——正文失败不影响摘要，这个语义是刻意保留的（有测试固化），排查"有记录但没正文"时先看正文表名。

### 1.4 会话（1 张）

`AI_LITE_SESSION` 存 Agent 会话：消息条目 JSON、压缩结果、版本号、压缩租约。

## 2. 表名能不能改

**10 张全部能改，没有例外。** 四组各有一组配置键，都是"不配就走原表名"的形态。

### 2.1 配置键总表

| 表 | 配置键 | 默认值 |
| --- | --- | --- |
| `AI_LITE_PROVIDER_OVERLAY` | `ai-lite.catalog.provider-table` | 同左 |
| `AI_LITE_MODEL` | `ai-lite.catalog.model-table` | 同左 |
| `AI_LITE_BIZ_BINDING` | `ai-lite.catalog.biz-table` | 同左 |
| `AI_LITE_ADMIN_SETTING` | `ai-lite.catalog.setting-table` | 同左 |
| `AI_LITE_PROMPT_VERSION` | `ai-lite.prompt.jdbc.version-table` | 同左 |
| `AI_LITE_PROMPT_ACTIVE` | `ai-lite.prompt.jdbc.active-table` | 同左 |
| `AI_LITE_PROMPT_AUDIT` | `ai-lite.prompt.jdbc.audit-table` | 同左 |
| `AI_LITE_LLM_CALL` | `ai-lite.store.jdbc.table-name` | **无默认值，必填** |
| `AI_LITE_LLM_CALL_BODY` | `ai-lite.store.jdbc.body-table-name` | `<table-name>_BODY` |
| `AI_LITE_SESSION` | `ai-lite.session.jdbc.table-name` | 同左 |

### 2.2 完整配置示例

```yaml
ai-lite:
  catalog:
    provider-table: MY_PROVIDER_OVERLAY
    model-table: MY_MODEL
    biz-table: MY_BIZ_BINDING
    setting-table: MY_ADMIN_SETTING
  prompt:
    jdbc:
      version-table: MY_PROMPT_VERSION
      active-table: MY_PROMPT_ACTIVE
      audit-table: MY_PROMPT_AUDIT
  store:
    jdbc:
      table-name: MY_CALL              # 必填
      body-table-name: MY_CALL_BODY    # 不配则自动推导
  session:
    jdbc:
      table-name: MY_SESSION
```

### 2.3 三个需要注意的点

**只有调用记录表必填。** `ai-lite.store.jdbc.table-name` 没默认值，不配就启动失败。原因是写入失败会被异步日志吞掉，宁可启动就炸，也不要几周后才发现账单表是空的。其余九张都有默认值。

**正文表默认跟随主表。** 把 `table-name` 改成 `MY_CALL`，正文表自动变 `MY_CALL_BODY`。反向也成立：默认表名推导出来正好是 `AI_LITE_LLM_CALL_BODY`，所以不改名的人行为不变。想用别的名字再显式配 `body-table-name`，管理页读正文用的也是同一个值。

**表名有白名单。** 只允许字母、数字和下划线（正则 `[A-Za-z0-9_]+`）。非法名字在启动期抛 `IllegalArgumentException`，不会拼进 SQL。

### 2.4 改名的连带动作

改名不只是改配置——数据库里也得有那张表：

- 目录四张：把 `ai-lite-catalog.sql` 里的表名替换后执行。
- 提示词三张：替换 `ai-lite-prompt.sql` 里的表名（含两个索引名，索引名不影响功能）。
- 调用记录两张：替换 `ai-lite-llm-call.sql` 里的表名。如果只改主表名，正文表要按推导规则建成 `<主表>_BODY`。
- 会话一张：替换 `ai-lite-session.sql` 里的表名。

目录模式启动时会探测提示词版本表和指针表，探不到就报错，错误消息里带实际使用的表名——看到报错说某张表不存在时，先核对是不是配置和 DDL 对不上。

## 3. 常读数据有没有缓存

结论分三档：**目录有缓存、提示词有缓存、调用正文档位和会话缓存这两处没有（一个是每次都查库，一个是类有但没自动装配）**。

### 3.1 一览

| 数据 | 读取频率 | 有缓存 | 缓存位置 | 默认 TTL | 配置键 |
| --- | --- | --- | --- | --- | --- |
| 供应商覆盖 | 每次模型调用 | 有（与目录共享快照） | 进程内 `volatile Snapshot` | 5 秒 | `ai-lite.catalog.cache-seconds` |
| 模型清单 | 每次模型调用 | 有（同上，一次读全表） | 同上 | 5 秒 | `ai-lite.catalog.cache-seconds` |
| 业务绑定 | 每次模型调用 | 有（同上） | 同上 | 5 秒 | `ai-lite.catalog.cache-seconds` |
| 提示词生效版本 | 每次渲染 | 有 | `ConcurrentHashMap` | 60 秒 | `ai-lite.prompt.cache-seconds` |
| 提示词目录（key 列表） | 每次渲染 | 不需要 | 启动时构建的 `LinkedHashMap` | 进程生命周期 | - |
| 调用正文档位 | **每次调用记录** | **无** | - | - | - |
| 会话（JDBC） | 每次续聊 | **无**（类存在但未装配） | - | - | 见 3.4 |
| 远端模型列表 | 手工触发同步时 | 无 | - | - | - |

### 3.2 目录缓存：一次读全表，5 秒 TTL

`JdbcRuntimeCatalog` 不按行缓存，而是把三张表**整表读进一个内存快照**，`findProvider` / `findModel` / `findBinding` 都查这个快照。

```java
private Snapshot load() {
    long now = System.currentTimeMillis();
    Snapshot current = snapshot;
    if (cacheMillis > 0L && current != null && now < expiresAt) {
        return current;
    }
    synchronized (this) {           // 双重检查，只让一个线程回源
        if (cacheMillis > 0L && snapshot != null && System.currentTimeMillis() < expiresAt) {
            return snapshot;
        }
        Snapshot loaded = read();    // 三条 SELECT，各读全表
        snapshot = loaded;
        expiresAt = cacheMillis <= 0L ? 0L : System.currentTimeMillis() + cacheMillis;
        return loaded;
    }
}
```

这带来两个特性：

- **调用路径上没有单行查询。** 缓存命中时零 SQL；未命中时一次读三张全表，之后所有 key 都命中。表小的时候这个策略最划算。
- **表变大要当心。** 目录表如果有几十万行模型，每 5 秒一次全表扫描会很明显。这个规模下应该把 `cache-seconds` 调大，或者换掉 `RuntimeCatalog` 实现。

**写后立刻失效。** 管理页的每次写操作（保存供应商覆盖、增删模型、改绑定、删绑定）都会在成功后调 `invalidate()`，把快照置空。所以本机改完立刻生效，不用等 5 秒。

**多节点有最长 5 秒的窗口。** `invalidate()` 只清本机。A 节点改了配置，B 节点要等自己的缓存过期才看到，最长 5 秒。这是可接受的取舍——目录数据不要求强一致，把 TTL 配成 0 可以退化成每次读库，但那样就失去了缓存的意义。

`cache-seconds` 配 0 或负数表示不缓存，每次都读库。

### 3.3 提示词缓存：按 key 缓存，60 秒 TTL

`PromptManager` 用 `ConcurrentHashMap<String, CacheEntry>` 按 key 缓存生效版本。

```java
CacheEntry cached = cache.get(key);
if (cached != null && !cached.expired()) {
    return cached.value == null
            ? Optional.<PromptVersion>empty() : Optional.of(cached.value.copy());
}
```

几点值得注意：

- **"没有生效版本"也会被缓存。** 用 `null` 值占位，避免"未发布"的 key 每次都打库。所以发布一个新版本后，如果没走 `invalidate`，最长要等 60 秒才看得到。
- **返回的是副本。** `cached.value.copy()`，防止调用方改到缓存里的对象。
- **发布/回滚自动失效。** `publish` 和 `rollback` 成功后都会调 `invalidate(key)`，本机立刻生效。
- **多节点最长 60 秒窗口。** 和目录同理，`invalidate` 只清本机。多节点场景可以显式调 `PromptManager.invalidate(key)` 或 `invalidateAll()` 做广播。
- **渲染走同一条缓存。** 非目录模式下 `render` 调 `current(key)`（带缓存）；目录模式下 `renderCatalog` 直接查同一个 map。两条路径都不会每次打库。

`ai-lite.prompt.cache-seconds` 配 0 表示每次都读存储。默认 60，比目录的 5 秒长——提示词变更不像目录那么频繁，而且发布本身会失效缓存。

提示词目录（key 列表、占位符定义）在启动时由 `PromptCatalog` 构建成内存 `LinkedHashMap`，不过期，也不查库。目录模式下这份 key 列表来自 `ai-lite.llm.biz-ids`。

### 3.4 没有缓存的两处

**调用正文档位：每次调用记录都查一次库。**

`LlmGateway.record` 里直接调 `callBodyPolicy.level()`：

```java
String level = callBodyPolicy == null ? CallBodyPolicy.NONE : callBodyPolicy.level();
if (CallBodyPolicy.store(level, success)) {
    row.setInputBody(...);
    row.setOutputBody(...);
}
```

而 `JdbcCallBodyPolicy.level()` 就是一条 `SELECT SETTING_VALUE FROM ... WHERE SETTING_KEY = ?`。**每次 LLM 调用（每轮都会 record）都会多一次数据库往返。**

这条 SQL 走的是一次主键/唯一键命中（`SETTING_KEY` 是主键），成本不高，但在高 QPS 下是纯浪费——这个值几乎不变，几小时才被管理页改一次。当前没有缓存，只能靠把档位设为 `none`（默认）来避免正文写入，但**查库这一步省不掉**，因为它要先读档位才知道要不要写。

如果这条库往返在实际压测里成了瓶颈，加一个短 TTL（比如 10 秒）的缓存即可，语义上也安全：档位改了最多 10 秒才生效，不影响正确性。

**会话缓存：类有，但没有自动装配。**

`CachingAgentSessionStore`（持久层 + 热缓存组合）和 `RedisSessionCache` / `InMemorySessionCache` 都实现了，但**starter 不会自动用它们**。默认装配的只有：

- 没有 `ai-lite-session-jdbc` 时：`InMemoryAgentSessionStore`（本身是内存实现，自带 `max-entries` 上限和 `ttl-seconds` 过期，但那是"存储"不是"缓存"）。
- 引入 `ai-lite-session-jdbc` 时：`JdbcAgentSessionStore`，**直接读库，没有中间缓存**。

要用缓存得自己写装配：

```java
@Bean
public AgentSessionStore agentSessionStore(JdbcTemplate jdbc, StringRedisTemplate redis) {
    AgentSessionStore durable = new JdbcAgentSessionStore(jdbc, "AI_LITE_SESSION");
    SessionCache cache = new RedisSessionCache(redis, "ai-lite:session:", false);
    return new CachingAgentSessionStore(durable, cache, 600L);   // TTL 600 秒
}
```

`CachingAgentSessionStore` 的语义是"先写库后写缓存，缓存故障只降级不失败"，可以放心包。`RedisSessionCache` 的第三个参数 `requireRedis` 决定 Redis 不可用时报错还是降级——生产环境建议 `false`，让它退化成直读库。

### 3.5 顺带说明：会话的"过期"不是缓存

`ai-lite.session.max-entries` 和 `ttl-seconds` 只作用于 `InMemoryAgentSessionStore`（默认实现），它们是内存存储的容量与过期策略，不是缓存策略。切到 JDBC 后这两个配置不再起作用，`ai-lite-session.sql` 里也没有对应的 TTL 列——过期要靠外部清理任务或业务自己处理。

### 3.6 密钥轮换与熔断状态：内存，不落库

这两份状态在本进程内存里，不查库也不持久化：

- **key 轮换**：`ModelResolver` 用 `ConcurrentHashMap<providerId, KeyRotation>` 记游标和冷却截止时间，冷却惰性到期（时间戳比较），无定时器。
- **熔断**：`ProviderGuard` 用普通字段 + 同步块维护 `CLOSED / OPEN / HALF_OPEN` 状态机。

两者都是"本机视角"的状态：多节点各自独立判断，不共享。这是刻意的取舍——共享熔断需要额外的协调通道，而每节点独立熔断已经能挡住大部分雪崩。

## 4. 排查清单

遇到"改了配置不生效"，按这个顺序看：

| 现象 | 先查什么 |
| --- | --- |
| 管理页改了供应商/模型/绑定，业务侧没变 | 等 5 秒再看。跨节点要等各自 TTL 过期 |
| 发布了提示词，业务侧还用旧模板 | 等 60 秒；跨节点同上 |
| 开了正文记录，正文表里没数据 | 先确认 `<主表>_BODY` 这张表真的存在；再看日志有没有 `llm call record failed` 的 warn |
| 启动报某张表不存在 | 核对配置的表名和 DDL 里的表名是否一致，报错消息里给的是实际使用的名字 |
| 启动报 `必须显式配置` | 没配 `ai-lite.store.jdbc.table-name` |
| 会话读取很慢 | 引入 session-jdbc 后是直读库，没有缓存；必要时按 3.4 自己包 `CachingAgentSessionStore` |

## 5. 相关文档

- 配置键完整清单：[配置参考](ref-配置.md)
- 管理接口形状：[管理接口参考](ref-管理API.md)
- 目录的运行时行为：[架构总览](desc-架构总览.md)
- 会话与上下文治理：[上下文治理](desc-上下文治理.md)
