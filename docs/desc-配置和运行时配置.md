# 配置和运行时配置

> **本文在哪一层**：横切层（配置与缓存跨越网关、管理页、提示词三个模块）。
> **讲什么**：一个配置项要么写在 YAML、要么写在数据库——哪些属于哪一边；写进库的落到哪张表；改完之后本机缓存怎么失效、别的机器怎么跟上；以及最后业务调用到底用没用上这份配置。
> **不讲什么**：每个配置键的取值和默认值（见 [配置参考](ref-配置.md)）；管理接口的请求与响应形状（见 [管理接口参考](ref-管理API.md)）；表的建表语句细节（见 [数据表与缓存](desc-数据表与缓存.md)）。

## 1. 两种配置来源

AI Lite 的配置分两半，界线是**「改了要不要重启」**。

|         | 写在 YAML                         | 写在数据库        |
| ------- | ------------------------------- | ------------ |
| 位置      | `application.yml` 的 `ai-lite.*` | 目录表、提示词表、设置表 |
| 谁写      | 人（改完重启）                         | 管理页（保存即写库）   |
| 生效时机    | 应用启动时读入内存                       | 下一次解析时读库或读缓存 |
| 能否在页面上改 | 不能                              | 能（少数只读项除外）   |

这个划分不是为了好看，是因为有些东西必须在启动时定下来：**供应商有哪些、业务 id 有哪些**这两个集合决定了 Bean 的装配和拦截，改它们等于换一套结构，没法在运行中热换。

## 2. 哪些配置在 YAML，改完必须重启

| 配置                                                | 为什么不能热改                                                                       |
| ------------------------------------------------- | ----------------------------------------------------------------------------- |
| `ai-lite.llm.providers` 的**键**（供应商 id 列表）         | 管理页只能给已存在的供应商写覆盖值；`saveOverlay` 第一步就校验供应商在配置里存在，不在就报错                         |
| `ai-lite.llm.biz-ids`                             | 网关用它做白名单校验（`resolver.allows(bizId)`），集合在 `ModelResolver` 构造时固化。页面上也没有「新建业务」入口 |
| `ai-lite.llm.profiles` / `default-profile`        | 预置组合只在 YAML 里求值，不查库                                                           |
| `ai-lite.guard.*`（出口保护）                           | 熔断、限流、key 冷却都是进程内状态机，随 Bean 创建                                                |
| `ai-lite.http.*`（传输选择与并发）                         | 传输实例在启动时装配                                                                    |
| `ai-lite.executor.*` / `callback.*` / `metrics.*` | 线程池与策略在启动时创建                                                                  |
| `ai-lite.session.*` / `context.*`                 | 会话存储与上下文预算在启动时创建                                                              |
| 所有 `*-table` 表名                                   | 表名进入 SQL 拼接，构造时校验                                                             |
| `*.cache-seconds` 三个缓存时长                          | 缓存在构造时按秒数换算成毫秒字段                                                              |

一句话：**模块级、连接级、线程级的东西在 YAML；数据级的东西在数据库。**

## 3. 哪些配置写进库，写到哪张表

| 管理页操作                              | 表                                                  | 主键 / 逻辑键                                     |
| ---------------------------------- | -------------------------------------------------- | -------------------------------------------- |
| 供应商覆盖：地址、密钥、协议、连接超时、读超时            | `AI_LITE_PROVIDER_OVERLAY`                         | `PROVIDER_ID`                                |
| 模型同步与手工添加                          | `AI_LITE_MODEL`                                    | `MODEL_ROW_ID`；`PROVIDER_ID + MODEL_ID` 是逻辑键 |
| 模型就绪开关                             | `AI_LITE_MODEL.READY`                              | 同上                                           |
| 模型能力参数：推理、入参类型、上下文窗口、输出上限、思考格式、显示名 | `AI_LITE_MODEL` 对应列                                | 同上                                           |
| 业务绑定：供应商 + 模型 + 思考档                | `AI_LITE_BIZ_BINDING`                              | `BIZ_ID`                                     |
| 调用日志的「记录方式」档位                      | `AI_LITE_ADMIN_SETTING`                            | `SETTING_KEY = 'call-body'`                  |
| 提示词新建草稿、修改草稿                       | `AI_LITE_PROMPT_VERSION`                           | `VERSION_ID`                                 |
| 提示词发布与回滚                           | `AI_LITE_PROMPT_VERSION` + `AI_LITE_PROMPT_ACTIVE` | `PROMPT_KEY` 上的生效指针                          |
| 提示词操作记录                            | `AI_LITE_PROMPT_AUDIT`                             | `AUDIT_ID`                                   |

几个容易忽略的落库细节：

- **`AI_LITE_MODEL` 没有唯一索引，判重靠应用层加锁。** 「加入模型」是「先查再插」，两个请求同时处理同一对 `PROVIDER_ID + MODEL_ID` 时会插出两行。单节点在进程内加锁即可；多节点必须设 `ai-lite.catalog.multi-node=true` 改用 Redis 锁，否则两个节点各锁各的、等于没锁。锁只拦得住走管理页的写入，直连 SQL 或历史数据仍可能有重复行，读取时取 `UPDATE_TIME` 更新的那一行（同一毫秒再按 `MODEL_ROW_ID` 排序，结果确定）。
- **就绪与能力参数是两件事，但启用要求参数齐全。** 保存能力参数不会把 `READY` 置 1，启用只由单独的 ready 接口改变。这样"参数填了但供应商还没开通"不会误把模型放给业务。反过来，参数为空的模型也不能被启用——否则会出现「已启用、窗口 0、只收文本」的模型，上下文预算与自动压缩整条跳过，而页面上看不出来。停用不做这个检查。
- **停用不解除绑定。** `READY=0` 时绑定行仍然存在，下一次调用会在解析阶段报「模型未就绪」。这是刻意的，避免业务在无人察觉时失去模型。
- **旧库会自动补 `API` 列。** 0.1.14 之前 `AI_LITE_PROVIDER_OVERLAY` 没有 `API`，`JdbcRuntimeCatalog` 构造时会检测并执行 `ALTER TABLE ... ADD COLUMN API VARCHAR(64)`；补不上才报错并给出 SQL。
- **`AI_LITE_ADMIN_SETTING` 是键值表。** 新增开关加一行即可，不用改表结构。

## 4. 有哪几处缓存

三处，都是**进程内**的。

| 数据              | 缓存位置                                                      | 默认时长 | 配置键                                     |
| --------------- | --------------------------------------------------------- | ---- | --------------------------------------- |
| 目录三张表（覆盖、模型、绑定） | `JdbcRuntimeCatalog` 的 `volatile Snapshot`                | 5 秒  | `ai-lite.catalog.cache-seconds`         |
| 提示词生效版本         | `PromptManager` 的 `ConcurrentHashMap<String, CacheEntry>` | 60 秒 | `ai-lite.prompt.cache-seconds`          |
| 调用正文档位          | `JdbcCallBodyPolicy` 的 `volatile cachedLevel`             | 10 秒 | `ai-lite.catalog.setting-cache-seconds` |

三处都支持把时长配成 0 或负数，表示每次都读库。

### 4.1 目录缓存是「整表快照」

`JdbcRuntimeCatalog` 不按行缓存，而是一次把三张表全读进内存（`JdbcRuntimeCatalog.java:388` 的 `read()`），之后 `findProvider` / `findModel` / `findBinding` 全部查这个快照。

好处是调用路径上缓存命中时零 SQL；代价是表很大时每次未命中都是三张全表扫描。模型表到几十万行的规模，应该把 `cache-seconds` 调大，或者换掉 `RuntimeCatalog` 的实现。

### 4.2 提示词缓存连「没有生效版本」也一起缓存

未发布的 key 会存一个 `null` 占位，避免「没发布」的业务每次都打库。所以第一次发布之后，如果没走失效，最长要等 60 秒才看得到。

### 4.3 调用正文档位的缓存写入是「直接改值」

`JdbcCallBodyPolicy.save()` 写库成功后直接把缓存改成新值，不是清空缓存。所以本机改完下一次读就是新档位。

## 5. 本机怎么刷新

**写操作成功之后由代码自己调，不需要你额外做什么。**

| 写操作                 | 失效动作                                     |
| ------------------- | ---------------------------------------- |
| 保存/清除供应商覆盖          | `JdbcRuntimeCatalog.invalidate()`        |
| 新增/删除模型、保存能力参数、切换就绪 | 同上                                       |
| 保存/清除业务绑定           | 同上                                       |
| 提示词发布/回滚            | `PromptManager.invalidate(key)`，只清这个 key |
| 保存调用正文档位            | 直接把缓存改成新值                                |

`invalidate()` 的实现只是把快照置空、过期时间归零（`JdbcRuntimeCatalog.java:143`），下一次读自然回源。

实测确认过：保存覆盖后立刻读页面拿到的是新值，切换正文档位后立刻读也是新值，都没有等 TTL。

## 6. 别的机器怎么刷新

**代码里没有任何跨节点通知机制。** 没有 Redis 发布订阅、没有消息队列、没有 Spring 事件广播。`invalidate()` 只清当前 JVM 的缓存。

自己接广播时可以用的入口（都是 public 方法）：

| 入口                                    | 作用范围    |
| ------------------------------------- | ------- |
| `RuntimeCatalog.invalidate()`         | 整个目录快照  |
| `PromptManager.invalidate(promptKey)` | 单个业务 id |
| `PromptManager.invalidateAll()`       | 全部提示词   |
| `JdbcCallBodyPolicy.invalidate()`     | 正文档位    |

不接广播时的默认行为就是各节点等自己的 TTL 过期：

| 数据   | 多节点最长延迟 |
| ---- | ------- |
| 目录   | 5 秒     |
| 正文档位 | 10 秒    |
| 提示词  | 60 秒    |

`AI_LITE_PROMPT_ACTIVE.REVISION` 每次发布加 1，但目前运行时没有任何代码读它。如果你要做广播，可以用它做版本比对——各节点定时读一下 `REVISION`，变了就清缓存，比轮询整表便宜。

## 7. 刷新之后，业务调用会不会用上

**取决于这次调用走哪条解析路径。** `LlmGateway.resolve()` 有三个分支（`LlmGateway.java:1713`）：

```java
if (profile 非空) {
    return resolver.resolveProfile(call.getProfile());          // 只查 YAML
}
if (provider 和 modelId 都为空 && 目录已启用) {
    return resolver.resolveBiz(call.getBizId());                // 查数据库
}
return resolver.resolveExplicit(call.getProvider(), call.getModelId(), ...);  // 只查 YAML
```

| 调用方式                               | 是否使用管理页配置                                 |
| ---------------------------------- | ----------------------------------------- |
| `LlmCall.biz(bizId, messages)`     | **是**。依次查绑定、查模型并校验就绪、套用供应商覆盖              |
| `LlmCall.provider(...).model(...)` | **否**。只在 YAML 的 `providers` 里找，不读覆盖表、不查就绪 |
| `LlmCall.profile(name)`            | **否**。同上                                  |

`resolveBiz` 里做的三件事（`ModelResolver.java:176`）：

1. `catalog.findBinding(bizId)` → 用页面保存的绑定
2. `catalog.findModel(providerId, modelId)` 且检查 `isReady()` → 就绪开关和能力参数生效
3. `applyOverlay(...)` → 地址、密钥、两个超时、**协议**全部生效

所以「页面上改了却没生效」，先确认业务代码用的是 `LlmCall.biz`，而不是显式指定了供应商和模型。

## 8. 提示词不会自动进入请求

这一条值得单独说，因为它和目录的行为不一样。

发布提示词做的事只有三件：写版本表、切生效指针、写审计，然后清本机缓存。**它不会改变任何请求的内容。**

网关 `LlmGateway` 里没有 `PromptManager` 的引用，`LlmCall.biz` 的 bizId 只用于三处：白名单校验、查模型、写日志的 `BIZ_ID` 列。请求里的 `messages` 是调用方传进来的原样数据。

要让提示词参与，业务代码得自己取：

```java
RenderedPrompt prompt = promptManager.render("demo-chat",
        Collections.singletonMap("question", question));
gateway.one(LlmCall.biz("demo-chat", prompt.getMessages()));
```

`getMessages()` 已经包含 system 和 user 两条消息。渲染本身不查库时是纯字符串替换，只有缓存过期才有一条 `SELECT`。

实测：发布带标记的提示词后，用 `LlmCall.biz` 发一次请求，抓到的请求体里只有调用方自己拼的那条用户消息，标记文本一个字都没出现。

反过来说，这样设计的好处是**引入这个库不会偷偷改掉别人已经拼好的消息**。你写死 `LlmMessage.user("你好")`，那发出去的一定是「你好」。

## 9. 已知限制

以下几条会在排查时误导人，值得先知道。

**目录模式下哪些字段来自数据库、哪些回落到 YAML。** 详见第 9.1 节——规则是「数据库有值用数据库，`NULL` 沿用 YAML，两边都没有才用内置默认」。

**没有绑定就不回退默认配置档。** 目录模式下，业务没有绑定时报「业务未配置模型」，不会退到 `default-profile`。

**管理页的「测试调用」不走网关，但走真实协议适配器。** 0.1.15 起，`tryModel` 用 `ModelResolver.resolveExplicit(..., useCatalog=true)` 解析出与业务调用相同的 `ResolvedModel`，再交给 `LlmTransport` 发请求——请求体、请求头、URL 的拼装规则完全一致，三种协议都支持。

它仍然与业务调用有四处不同，都是刻意的：不写调用记录、不过出口保护（不占并发许可、不会被熔断拒绝）、没有重试、不检查业务绑定与就绪状态。

所以「页面上测通了」的正确读法是：**请求内容和地址是对的**。业务调用还要额外满足就绪、绑定、出口保护这些条件。

取不到 `ModelResolver` 或协议适配器时（单独引入 admin-api 的场景），退回手写实现：只支持 OpenAI 兼容协议，请求拼装可能与业务调用不一致，启动日志里会打 WARN。

**解析阶段失败不留调用记录。** 业务 id 未登记、模型未就绪、协议不支持，这些在发请求之前就抛错，不会产生 `AI_LITE_LLM_CALL` 行。反过来，**没发出请求也可能留记录**：并发上限拒绝、熔断拉闸、发请求前取消都会写一行（`stop=error` 或 `aborted`，`latency=0`）。所以行数不等于供应商实际收到的请求数。详细口径见 [调用日志](desc-调用日志.md)。

**业务 id 同时是提示词 key。** 目录模式下 `ai-lite.llm.biz-ids` 里的每个 id 必须符合提示词 key 的编码规则（小写字母、数字、短横线，最长 64），否则启动直接失败。一个业务只有一份提示词。

### 9.1 目录模式下哪些字段来自数据库，哪些回落到 YAML

这一节单独展开，因为它决定「管理页改了一项、请求体到底变不变」。

**规则一句话**：数据库列有值就用数据库，`NULL` 表示这一项没填、沿用 YAML 里的同名模型，
两边都没有才用内置默认。

| YAML 字段                            | 数据库列              | 数据库为 NULL 时 |
| ---------------------------------- | ----------------- | ---------- |
| `id`                               | `MODEL_ID`        | 必须有值       |
| `name`                             | `DISPLAY_NAME`    | 用模型 id     |
| `reasoning`                        | `REASONING`       | 回落 YAML    |
| `input`                            | `INPUT_MODES`     | 回落 YAML    |
| `context-window`                   | `CONTEXT_WINDOW`  | 回落 YAML    |
| `max-tokens`                       | `MAX_TOKENS`      | 回落 YAML    |
| `thinking-format`                  | `THINKING_FORMAT` | 回落 YAML    |
| `thinking-level-map`               | `THINKING_LEVEL_MAP` | 回落 YAML |
| `sampler-params`                   | `SAMPLER_PARAMS`  | 回落 YAML    |
| `sampler-params-by-thinking-level` | `SAMPLER_PARAMS_BY_LEVEL` | 回落 YAML |
| `headers`                          | `MODEL_HEADERS`   | 回落 YAML    |
| `extra-body`                       | `EXTRA_BODY`      | 回落 YAML    |

空 Map 与 `NULL` 是两件事：`NULL` 表示没配（沿用 YAML），`{}` 表示明确清空
（即使 YAML 里有也不用了）。

**为什么能力参数也要回落。** 管理页「加入模型」建的行，所有能力参数都是 `NULL`。
如果只从数据库取，就会出现「已启用、上下文窗口 0、入参只剩 text」的模型：
上下文预算与自动压缩整条跳过，图片消息被拒，而页面上一切正常。回落之后 YAML 里
写过的能力参数仍然生效。

**为什么还要拦「参数不全的启用」。** 回落只在 YAML 里有同名模型时有用。YAML 里
没有、数据库里也没填，就只剩内置默认值（窗口 0、只收文本）——这仍然是坏的。
所以 `setReady(true)` 现在要求五项能力参数齐全，缺了就报错并列出缺哪几列。
停用不做这个检查，已有模型要能随时下线。

**避开的办法（如果确实需要每个模型一套参数）**：
1. 直接写 YAML，用 `LlmCall.explicit` 或 `profile` 调用。代价是失去管理页的热改能力。
2. 用管理页的模型级请求参数（`THINKING_LEVEL_MAP`、`SAMPLER_PARAMS`、
   `SAMPLER_PARAMS_BY_LEVEL`、`MODEL_HEADERS`、`EXTRA_BODY` 五列），这些都能在页面上编辑。
3. 供应商级只放所有模型都一样的部分，模型级放差异。

## 10. 排查顺序

遇到「改了配置没生效」，按这个顺序看：

| 现象               | 先查什么                                                                                     |
| ---------------- | ---------------------------------------------------------------------------------------- |
| 页面上改了，业务侧没变      | 业务代码是否用 `LlmCall.biz`；显式 `provider + model` 和 `profile` 不读数据库                            |
| 改了供应商地址，业务还在打旧地址 | 该 JVM 是否已过 5 秒 TTL；跨节点要等各自 TTL，或自己接广播                                                    |
| 发布了提示词，输出没变      | 业务代码有没有调 `promptManager.render`；调了的话是否已过 60 秒                                            |
| 模型参数改了不生效        | 确认调用走的是 biz 路径，且模型 `READY=1`                                                             |
| 某个模型在目录模式下行为变了   | 检查是否依赖 YAML 的 `thinking-level-map`、`sampler-params`、模型级 `headers` 或 `extra-body`（见 §9.1） |
| 「页面上测通了」但业务失败    | 测试调用不走网关，也可能是业务代码绕开了目录                                                                   |
| 改了 YAML 不生效      | YAML 里的供应商 id、业务 id、表名、缓存时长都需要重启                                                         |

## 11. 相关文档

- 每个配置键的取值与默认值：[配置参考](ref-配置.md)
- 10 张表的用途与改名方法：[数据表与缓存](desc-数据表与缓存.md)
- 管理接口的请求响应形状：[管理接口参考](ref-管理API.md)
- 提示词的版本流转与渲染规则：[上层调用 API 使用指南](guide-上层调用API.md) 第 6 节
