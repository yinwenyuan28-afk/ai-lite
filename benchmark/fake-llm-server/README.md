# 本机假模型服务与栈/内存探针

不连真实供应商、不需要密钥，就能压测 ai-lite 的传输层与网关。

回答这类问题：**「一个 4MB 的流式响应到底要多少堆？」「某个改动之后内存降了没有？」「错误路径会不会让线程或堆偷偷涨？」**

配套的分析结论见 [栈与内存风险实测报告](../../docs/plan/022-栈与内存风险实测报告.md)。

## 1. 它是什么

两部分：

| 部分 | 作用 |
| --- | --- |
| `FakeLlmMain` | 一个可独立启动的 HTTP 服务，按请求体里的标记返回不同响应（正常、SSE 分片、429、断开、深嵌套……） |
| 探针 | 用**真实的** `LlmGateway` + `JettyHttpExecutor` 打上面那个服务，测量堆与线程 |

关键点：探针用的是生产的传输实现和网关，不是模拟对象。所以测出来的数字能反映真实行为。

## 2. 启动假服务

```bash
cd ai-lite-spring-boot-starter
mvn -o -f benchmark/fake-llm-server/pom.xml compile

# 默认端口 18181，线程池按需调整
mvn -o -f benchmark/fake-llm-server/pom.xml exec:java -Dexec.args="--port=18181 --threads=256"
```

启动后把 `http://127.0.0.1:18181/v1` 当作供应商的 `base-url`。

支持四条路径，按后缀匹配（`base-url` 前面挂多少段都行）：

| 路径 | 协议 |
| --- | --- |
| `POST .../chat/completions` | OpenAI Chat Completions |
| `POST .../responses` | OpenAI Responses |
| `POST .../v1/messages` | Anthropic Messages |
| `GET .../models` | 模型同步探测 |

## 3. 故障注入标记

写在请求体里就行，格式 `bench-键=值`。服务端不解析 JSON（假的请求体也可能不合法），只做字符串匹配。

| 标记 | 含义 |
| --- | --- |
| `bench-hold=N` | 首字节前挂 N 秒 |
| `bench-chunks=N` | SSE 事件条数 |
| `bench-chunk-size=N` | 每个事件 content 的字符数 |
| `bench-stream=true` | 强制流式（也认 JSON 里的 `"stream":true`） |
| `bench-status=429` | 返回指定状态码 |
| `bench-retry-after=N` | 带 `Retry-After` 头 |
| `bench-deep=N` | JSON 嵌套深度（放在真实结构位置，不是 content 字符串里） |
| `bench-tool-calls=N` | 发出 N 个 tool_calls |
| `bench-disconnect=true` | 发完响应头就断开 |
| `bench-hang-headers=true` | 只建立连接、不发响应头（复现读超时） |
| `bench-no-usage=true` | 省略 usage 字段 |
| `bench-never-end=true` | 只发 `data:` 行、**永不发空行**（压 `SseCollector` 的单事件累积） |
| `bench-line-delay=N` | 每行之间等待 N 毫秒，控制累积速率 |

示例：

```bash
# 4MB 的流式响应（64 条 × 64KB）
curl -X POST http://127.0.0.1:18181/v1/chat/completions \
  -d "bench-chunks=64 bench-chunk-size=65536"

# 429 带 5 秒 Retry-After
curl -X POST http://127.0.0.1:18181/v1/chat/completions \
  -d "bench-status=429 bench-retry-after=5"
```

> 一个坑：`bench-deep` 必须放在 JSON 的**结构位置**。早先版本把它塞进 `content` 字符串里，那只是一段普通文本，解析器根本不递归，测出来是假象。

## 4. 四个探针

都需要先启动假服务。

### 4.1 `MemoryProbeMain` —— 多场景堆与线程

```bash
mvn -o -f benchmark/fake-llm-server/pom.xml exec:java \
    -Dexec.mainClass=io.github.yinwenyuan28.ailite.benchmark.fake.MemoryProbeMain \
    -Dexec.args="--rounds=200 --concurrency=32"
```

依次跑 10 个场景：正常非流式、流式分片、大分片、单个 4MB、深嵌套、429（不重试 / 走重试）、断开、SSE 事件永不结束、稳态复跑。

**每个场景连跑两遍**，这是为了区分两种增长：

- 第二遍增量 ≈ 0 → 一次性扩容（JIT、类加载、连接池首次扩到并发数）
- 两遍增量一样大 → 真的每次调用都漏

输出含：调用成功/失败数、GC 后堆留存、老年代、线程数增量、堆峰值、线程名前缀分布。

### 4.2 `HeapCeilingProbeMain` —— 单个响应需要多少堆

```bash
# 4MB 流式响应
set MAVEN_OPTS=-Xmx48m
mvn -o -f benchmark/fake-llm-server/pom.xml exec:java \
    -Dexec.mainClass=io.github.yinwenyuan28.ailite.benchmark.fake.HeapCeilingProbeMain \
    -Dexec.args="--mb=4"

# 同样 4MB，但走非流式
mvn ... -Dexec.args="--mb=4 --stream=false"
```

把堆压到很小、只发一个响应，看成功还是失败。得到的是**硬下界**，与 GC 时机无关。

为什么需要它：`MemoryProbeMain` 的峰值采样在 3.4GB 大堆下会虚高（堆越大 GC 越懒，峰值里混着「本可回收但还没回收」的垃圾）。判断「最小需要多少内存」要用这个探针。

### 4.3 `StackDepthProbeMain` —— JSON 嵌套深度上限

```bash
mvn -o -f benchmark/fake-llm-server/pom.xml exec:java \
    -Dexec.mainClass=io.github.yinwenyuan28.ailite.benchmark.fake.StackDepthProbeMain
```

直接调用 transport 实际使用的 `AiLiteJson.readTree`，从 100 层试到 100000 层，找出第一个 `StackOverflowError`。

结论（Jackson 2.9.10.1）：2000 层能过，**5000 层栈溢出**。`StreamReadConstraints`（默认上限 1000）是 Jackson 2.15 才有的，Spring Boot 2.1 管理的这版没有。

### 4.4 `CancelHandleRetentionProbeMain` —— 取消句柄是否拖住对象

```bash
mvn -o -f benchmark/fake-llm-server/pom.xml exec:java \
    -Dexec.mainClass=io.github.yinwenyuan28.ailite.benchmark.fake.CancelHandleRetentionProbeMain \
    -Dexec.args="--calls=300"
```

A/B 对照：同样跑 N 次成功调用，一组把 `LlmCancelHandle` 存下来不清空，一组不存。GC 后比堆占用，差值就是被句柄拖住的部分。

用来验证「句柄长期持有时会不会把整条调用链拖住」这类静态推断。实测结论是**不会**——静态分析曾误判过这条。

## 5. 注意

- **不入 reactor**：这个目录和 `benchmark/` 下其他两个一样独立构建，`ai-lite-parent` 的 `<modules>` 里没有它。所以 `mvn verify` 不会跑它。
- **JDK 8 可用**：探针用的都是 JDK 8 API，没有 `orTimeout`、`List.of` 之类。
- **`MAVEN_OPTS` 设堆**：在 Windows CMD 里用 `set MAVEN_OPTS=-Xmx48m`，Git Bash 用 `export MAVEN_OPTS=-Xmx48m`。测出来的是 exec 插件启动的那个 JVM 的堆上限。
