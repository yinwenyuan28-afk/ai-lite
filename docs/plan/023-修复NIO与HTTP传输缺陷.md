---
status: 已实施（F-17 除外）
created: "2026-11-11 10:30"
updated: "2026-11-11 15:40"
type: 修复计划
source: "017-基础能力代码审查.md §3.2"
scope: "F-17 ~ F-30（NIO 与 HTTP 传输 14 条）"
result: "13 条已修，F-17 单列后续版本；mvn verify 1207 用例全绿"
---

# 修复 NIO 与 HTTP 传输的 14 条缺陷（3.2 章节）

> **本文在哪一层**：HTTP 传输层（`ai-lite-http-*` 六个模块 + `ai-lite-llm` 的 SPI 与工具类）与运行时配置层（`ModelResolver` 的密钥轮换、`LlmCallException` 的 Retry-After 解析）。
>
> **输入**：[017-基础能力代码审查](017-基础能力代码审查.md) §3.2 的 F-17 ~ F-30。
> **本文做什么**：先对每条结论给出核实结果（真实 / 部分成立 / 描述有偏差），再定修复范围、顺序、验收标准。
> **不做什么**：不重设计 SPI 方法集（`HttpExecutor` / `AsyncHttpExecutor` 的演化承诺是「1.0 后不再新增方法」，本计划所有改动都在实现内部与共用工具里）；不碰 §3.1 异步编排、§3.3 运行时目录（F-29/F-30 虽然也在 3.2 表里，但归属配置层，本文一并处理）。

---

## 0. 核实结论先行

对着当前源码逐行核对后：**11 条真实存在，2 条部分成立，1 条描述有偏差**。下面每条都给了当前行号与关键证据，行号以 2026-11-11 工作区为准（会漂移，引用时以类名 + 方法名为准）。

| 编号 | 结论 | 一句话事实 | 当前定位 |
| --- | --- | --- | --- |
| F-17 | ✅ 真实 | Jetty / WebClient 在 selector / event loop 线程上直接做行解码、Jackson 解析和用户回调 | `JettyHttpExecutor.onContent`、`WebClientHttpExecutor.executeStreamingAsync` 的 `concatMap` |
| F-18 | ✅ 真实 | `timeouts(0,0)` 在四个实现里四种语义：OkHttp/Ktor/Jdk8 无限、Jetty 客户端 idle 无限、WebClient 靠 Netty 默认 | 四个 `buildCall`/`newRequest`/`statement` |
| F-19 | ✅ 真实 | WebClient 只给 `exchange()` 包了 timeout，非流式 `bodyToMono(String)` 与 `errorBody` 都没有 | `WebClientHttpExecutor.requestMono` |
| F-20 | ✅ 真实 | Jetty 异步 `onContent` 只判 `failed` 不判取消；WebClient 取消路径不置 `done`；Ktor 同步入口无 `bind` | 三处流式循环 |
| F-21 | ✅ 真实 | Jdk8 `executeAsync` 就是 `supplyAsync(() -> execute(request))`，future 与 connection 无绑定，`cancel(true)` 不 disconnect | `Jdk8HttpExecutor.executeAsync` |
| F-22 | ✅ 真实 | OkHttp/Jdk8 无 `closed` 标志、无 `openOrFail`；OkHttp 单参构造器硬编码 `ownsDispatcher=true` | `OkHttpExecutor` / `Jdk8HttpExecutor` |
| F-23 | ✅ 真实 | 只有 Jetty 与 Jdk8 设 `Accept-Encoding: identity`，OkHttp/WebClient/Ktor 没设 | 见 §2.5 |
| F-24 | ✅ 真实 | 同步 `readLine()` / `readUtf8Line()` 三条路径无行上限 | Jetty `:162`、OkHttp `:127/:209`、Jdk8 `:126` |
| F-25 | ✅ 真实 | WebClient 流式 `Flux<DataBuffer>` 缺 `doOnDiscard`，取消/超时时预取队列的 buffer 不释放 | `WebClientHttpExecutor.executeStreamingAsync` |
| F-26 | ✅ 真实 | Jetty 成功体 `append(content, body, 0)` → `allowed = remaining` 无界；OkHttp `body.string()` 全量读入后才截 300 字符 | Jetty `:219`、OkHttp `ensureSuccessful` |
| F-27 | ✅ 真实 | `client.setIdleTimeout(0)` 主动关掉客户端级兜底；`request.idleTimeout` 只在 `>0` 时设 | `JettyHttpExecutor.createClient` / `newRequest` |
| F-28 | ✅ 真实 | Jdk8 没有 `setInstanceFollowRedirects(false)`，`executeRaw` 会跟随 3xx | `Jdk8HttpExecutor.open` |
| F-29 | ⚠️ 部分成立 | `rotationFor` 确实是 `get` → `putIfAbsent` → 无条件 `put`；但「两线程各自 new 并覆盖」只在两线程读到不同 size（热更新窗口）时发生，不是任何并发都触发 | `ModelResolver.rotationFor:648-661` |
| F-30 | ✅ 真实（效果描述需修正） | `seconds * 1000L` 无溢出检查。`Long.MAX_VALUE` 秒溢出为 **-1000**，再被 `Math.max(0L, delay)` 钳成 **0**，效果是 429 立刻重打，不是"负退避" | `LlmCallException:152-161` |

**核实中发现的两处审查文档偏差**（实施时一并更新 017 文档）：

1. **F-29 行号已漂移**：审查写 `:627-639` / `:637`，实际在 `:648-661`，无条件 `put` 在 `:659`。
2. **F-30 后果描述不准**：审查写「退避可能当作 0」是对的，但说「溢出为负」会让人以为有负 sleep；实际被 `RetrySupport.backoffMs` 的 `Math.max(0L, delay)` 钳住。危害表述应改为「本该长时间等待的 429 变成 0ms 立即重试，可能持续打爆供应商限流」。
3. **F-29 还有一个审查没提的同源问题**：`rotations` 只按 `providerId` 建键。`api-keys` 条数不变但内容/顺序变了时，旧索引的 `cooldownUntil` 会落到新 key 上，冷却张冠李戴。本计划一并修。

**测试缺口核实**（决定用例怎么写）：

- 六个模块**没有**共享契约测试基类，没有 `@ParameterizedTest`，Jetty/WebClient/Ktor 三份测试是复制粘贴关系。
- 六项关键行为**零覆盖**：`timeout=0`、UTF-8 BOM、`executeRaw` 跟随 3xx、`close()` 后调异步入口、流式 `Accept-Encoding: identity`、非流式「200 头已回、body 停发」超时（只有 OkHttp 有）。
- 只有 OkHttp 断言了「回调内取消后不再交付后续行」的行数；Jetty/WebClient/Ktor 只断言异常类，不断言行数。

---

## 1. 修复范围与优先级

审查把 3.2 整体放在「批次 2（超时与取消 parity）」和「批次 3（NIO 线程模型）」。本计划按**契约破坏程度**重排，分成四批，每批可独立合并、独立回滚。

| 批次 | 内容 | 为什么这个顺序 | 预估改动 |
| --- | --- | --- | --- |
| **A（P0）** | F-21 取消失效、F-22 close 门禁、F-30 溢出 | 三条都会让调用方拿到错误结果或让资源裸奔，且改动局部、无跨实现耦合 | 3 个文件 + 3 组用例 |
| **B（P1）** | F-18 超时归一、F-19 非流式超时、F-20 取消收敛、F-23 identity、F-27 Jetty 兜底、F-28 重定向 | 六条都是「同一份配置在不同实现下语义不同」，必须一次对齐，否则契约测试写不出来 | 5 个实现 + 共用工具 |
| **C（P2）** | F-24 同步读行上限、F-26 响应体上限、F-25 DataBuffer 释放 | 内存安全，触发条件是异常/恶意响应，不影响正常路径 | 4 个实现 + 1 个新工具类 |
| **D（P2）** | F-17 NIO 线程模型、F-29 轮换重建 | 两条都需要改线程/并发结构，风险最高，且 F-17 会改变回调线程归属（对外可见） | 2 个实现 + `ModelResolver` |

**F-17 单独说明**：把解析与用户回调移出 event loop 是对外可见的行为变更（`onDelta` 线程名变了）。实施时必须同步更新 `desc-HTTP传输.md` §2 的线程模型表，并在 `CHANGELOG.md` 标注。如果这一批时间不够，**宁可只做 A/B/C，把 F-17 留到独立版本**——不要为了凑数把线程模型改一半。

---

## 2. 逐条修复方案

### 2.1 F-21：Jdk8 的 future 取消传不到 socket（P0）

**现状**

```java
// Jdk8HttpExecutor:57-60
public CompletableFuture<HttpResult> executeAsync(HttpRequest request) {
    return CompletableFuture.supplyAsync(() -> execute(request), executor);
}
```

`execute` 内部把 `disconnect()` 绑在 `request.getCancel()` 上，但这条链只覆盖网关的取消句柄；调用方直接 `future.cancel(true)` 时，`supplyAsync` 的 future 与 `HttpURLConnection` 之间没有任何联系，连接继续跑到读超时。

**修法**

1. 让 `open()` 把 `HttpURLConnection` 交回给一个可取消的载体，而不是只绑在 `LlmCancelHandle` 上：

```java
/** 异步执行期间持有在途连接，供 future.cancel 时断开。 */
private static final class InFlight {
    private final AtomicReference<HttpURLConnection> connection =
            new AtomicReference<HttpURLConnection>();
    void attach(HttpURLConnection c) { connection.set(c); }
    void detach() { connection.set(null); }
    void abort() {
        HttpURLConnection c = connection.getAndSet(null);
        if (c != null) { c.disconnect(); }
    }
}
```

2. `executeAsync` / `executeStreamingAsync` 改为显式 `CompletableFuture` + `executor.execute(...)`，并在 `future.whenComplete` 里判 `isCancelled()` 调 `inFlight.abort()`：

```java
public CompletableFuture<HttpResult> executeAsync(final HttpRequest request) {
    final CompletableFuture<HttpResult> future = new CompletableFuture<HttpResult>();
    final InFlight inFlight = new InFlight();
    try {
        executor.execute(new Runnable() {
            public void run() {
                try {
                    future.complete(execute(request, inFlight));
                } catch (Throwable t) {
                    future.completeExceptionally(mapError(t, request.getCancel()));
                }
            }
        });
    } catch (RuntimeException ex) {
        future.completeExceptionally(ex);
        return future;
    }
    future.whenComplete((v, e) -> {
        if (future.isCancelled()) { inFlight.abort(); }
    });
    return future;
}
```

3. `execute(request)` 保留为公开同步入口，内部委托 `execute(request, null)`；`open()` 多一个可空参数，非 null 时 `attach`，`finally` 里 `detach`。

**注意**：`disconnect()` 在「等响应头」阶段能立刻唤醒阻塞读；「读正文」阶段 JDK 实现不保证（`desc-HTTP传输.md` §2 已写明）。修复后语义变成「尽力而为，但不再完全无效」，文档表述保持不变。

**验收**：新增 `Jdk8HttpExecutorTest.cancellingFutureDisconnectsInFlightRequest`——服务端 `setBodyDelay(2s)` + `readTimeout=5s`，`future.cancel(true)` 后 500ms 内读到服务端连接断开（用 `AtomicBoolean` 在 handler 的 `IOException` 分支置位）。

---

### 2.2 F-22：close 后仍可提交，且单参构造器会关掉用户连接池（P0）

**现状**

- `OkHttpExecutor` 没有 `closed` 字段；`close()` 只 `shutdown()` dispatcher。close 后调 `executeAsync`，`call.enqueue` 会因 dispatcher 已 shutdown 抛 `RejectedExecutionException`——**同步抛给调用方**，不是 failed future，违反异步入口契约。
- `Jdk8HttpExecutor.close()` 只 `shutdownNow()` 自建池；之后 `supplyAsync` 同样同步抛 `RejectedExecutionException`。
- `OkHttpExecutor(OkHttpClient shared)` 委托成 `this(shared, true)`，注释（`:56`）却要求用户传入的客户端传 `false`。用户按单参构造器接入，`close()` 会关掉用户自管的 dispatcher 和连接池。

**修法**

1. 两个实现都补 `private final AtomicBoolean closed`，`close()` 里 `compareAndSet` 后置位。
2. 补与 Jetty/WebClient 同形的门禁：

```java
/** close 之后拒绝新请求：异步入口返回 failed future，同步入口抛 CONFIG。 */
private void ensureOpen() {
    if (closed.get()) {
        throw new LlmCallException("OkHttp HTTP 执行器已关闭", null,
                LlmCallException.Kind.CONFIG);
    }
}

private boolean openOrFail(CompletableFuture<?> future) {
    if (!closed.get()) { return true; }
    future.completeExceptionally(new LlmCallException("OkHttp HTTP 执行器已关闭", null,
            LlmCallException.Kind.CONFIG));
    return false;
}
```

3. 所有入口开头门禁：`execute`/`executeRaw`/`executeStreaming` 调 `ensureOpen()`；`executeAsync`/`executeStreamingAsync` 调 `openOrFail(future)`。
4. `OkHttpExecutor(OkHttpClient shared)` 改为 `this(shared, false)`，并把 Javadoc 从「调用方负责其连接池配置」补成「本执行器不会关闭调用方的 dispatcher 与连接池」。`OkHttpClientFactory.executor(config)` 仍显式传 `true`（它自己 new 的客户端）。
5. `Jdk8HttpExecutor` 的 `executeAsync` 里 `executor.execute` 被拒时返回 failed future（与 2.1 的改法合并，不重复写）。

**兼容性**：这是行为变更（close 后由「抛 RejectedExecutionException」变成「failed future」）。按 `HttpExecutor` 契约「异步入口返回 future」这是修正，不是破坏；`CHANGELOG` 记一行。

**验收**：新增 `closeAfterAsyncEntryReturnsFailedFuture`（OkHttp + Jdk8 各一条），断言 `close()` 后 `executeAsync` 返回的 future 已异常完成且 `Kind.CONFIG`，而不是抛出来。新增 `singleArgConstructorDoesNotShutdownUserDispatcher`：传入自建 client + 外部 dispatcher，`close()` 后 dispatcher 仍能执行任务。

---

### 2.3 F-30：Retry-After 秒数溢出（P0）

**现状**

```java
// LlmCallException:152-161
long seconds;
try { seconds = Long.parseLong(text); } catch (NumberFormatException ex) { return null; }
if (seconds <= 0L) { return null; }
return Long.valueOf(seconds * 1000L);   // 溢出
```

`9223372036854775807 * 1000` 回绕成 `-1000`，`RetrySupport.backoffMs` 里 `Math.min(-1000, maxBackoffMs)` = `-1000`，再 `Math.max(0L, -1000)` = `0`。429 立刻重打。

**修法**

用一个上限（24 小时）把异常值收住，而不是只防溢出——供应商写一个 `999999999` 秒同样是不可用的：

```java
/** Retry-After 的换算上限：24 小时。超过按上限处理，避免溢出与病态长退避。 */
private static final long MAX_RETRY_AFTER_MS = 24L * 60L * 60L * 1000L;

...
if (seconds <= 0L) { return null; }
long millis;
try {
    millis = Math.multiplyExact(seconds, 1000L);
} catch (ArithmeticException ex) {
    return Long.valueOf(MAX_RETRY_AFTER_MS);
}
return Long.valueOf(Math.min(millis, MAX_RETRY_AFTER_MS));
```

**为什么不返回 null**：返回 null 表示「服务端没给指令」，会退回指数退避（默认秒级）。供应商明确说了「很久以后再来」，钳到 24h 比退回指数退避更接近原意，也不会把请求打成 0ms 循环。

**验收**：`RetryAfterTest` 新增三条——`Long.MAX_VALUE` 秒 → 24h；`Long.MAX_VALUE / 1000 + 1` → 24h；正常 `60` 秒 → 60000。再加一条端到端：`RetrySupport.backoffMs` 对溢出值返回 24h，不是 0。

---

### 2.4 F-18：`≤0` 超时归一化（P1）

**现状四种语义**

| 实现 | `readTimeout=0` 实际行为 |
| --- | --- |
| OkHttp | `readTimeout(0)` = 无限等待 |
| Jetty | `client.setIdleTimeout(0)` = 无限；`request.idleTimeout` 不设 |
| WebClient | 流式不设 timeout（靠 Netty 默认）；非流式完全不设 |
| Ktor | `socketTimeoutMillis` 不设 = 无限 |
| Jdk8 | `setReadTimeout(0)` = 无限 |

**修法：在 SPI 边界归一，而不是六个实现各写一遍**

新增共用工具（`ai-lite-llm` 的 `internal.http` 包，与 `Utf8LineBuffer` 同层）：

```java
/**
 * 把「非正值表示未配置」归一成具体毫秒数。
 *
 * <p>HTTP 客户端的 0 语义各不相同（有的无限、有的取默认），如果让每个实现自己解释，
 * 同一份配置会得到四种行为。这里统一成：≤0 一律取框架默认值。</p>
 */
public final class Timeouts {

    /** 默认连接超时：10 秒。 */
    public static final int DEFAULT_CONNECT_MS = 10_000;
    /** 默认读取超时：120 秒。 */
    public static final int DEFAULT_READ_MS = 120_000;

    public static int connect(HttpRequest request) {
        int value = request.getConnectTimeoutMs();
        return value > 0 ? value : DEFAULT_CONNECT_MS;
    }

    public static int read(HttpRequest request) {
        int value = request.getReadTimeoutMs();
        return value > 0 ? value : DEFAULT_READ_MS;
    }
}
```

> 默认值取自 `AiLiteConfig.Provider` 的既有默认（`connectTimeoutMs=10000`、`readTimeoutMs=120000`），不是新拍的数字。这样「供应商没配」与「显式配 0」得到同一个结果，符合 `HttpRequest` Javadoc 「非正值由传输实现取默认」的原意。

然后逐个实现改成调用 `Timeouts.connect(request)` / `Timeouts.read(request)`，删掉各自的 `>0 ?` 分支：

- OkHttp `buildCall`：`.connectTimeout(Timeouts.connect(request), MS).readTimeout(Timeouts.read(request), MS)`
- Jetty `newRequest`：`idleTimeout(Timeouts.read(request))`（无条件设）
- WebClient `requestMono` / 流式 body：无条件 `.timeout(Duration.ofMillis(...))`
- Ktor `statement`：无条件设 `connectTimeoutMillis` / `socketTimeoutMillis`
- Jdk8 `open`：`setConnectTimeout(Timeouts.connect(request))` / `setReadTimeout(Timeouts.read(request))`

**风险**：把「0 = 无限」改成「0 = 120 秒」会让极少数依赖无限超时的长连接场景提前失败。这是**有意的契约统一**——无限等待的请求正是 F-18 要消除的问题。`CHANGELOG` 必须写明，并在 `ref-配置.md` 的 `read-timeout-ms` 条目加一句「非正值按默认 120 秒」。

**验收**：六个实现各加一条 `zeroTimeoutUsesDefaultRatherThanInfinite`，用「服务端 sleep 2s、请求传 `0,0`」验证请求在默认值内完成（不是永久挂起）；另加一条「传 `-1` 与传 `0` 行为一致」。

---

### 2.5 F-23：流式 `Accept-Encoding: identity`（P1）

**现状**：Jetty（`newRequest`）与 Jdk8（`open`）设置了；OkHttp 走 `transparentGzip` 默认自动加 gzip 并解压；WebClient、Ktor 不设。

**修法**：这本来就是 `StreamListener` 的契约（`desc-HTTP传输.md` §4 三条之一），下沉到共用的请求头归一工具：

```java
/** 流式请求必须禁用压缩：gzip 解压缓冲会破坏「字节到达即逐行交付」的语义。 */
public static void applyStreamingHeaders(HttpRequest request, Map<String, String> target) {
    // 调用方显式配了就不覆盖，否则统一 identity。
    if (!request.getHeaders().containsKey("Accept-Encoding")) {
        target.put("Accept-Encoding", "identity");
    }
}
```

各实现接入点：

- OkHttp：`buildCall` 增加 `boolean streaming` 参数；流式时 `builder.header("Accept-Encoding", "identity")`，并对 client 关掉 `transparentGzip`（`newBuilder()` 无法关，需在 `OkHttpClientFactory.create` 里 `.proxy(Proxy.NO_PROXY)` 之外显式设 `Accept-Encoding` 即可——OkHttp 见已有该头就不再自动加 gzip）。
- WebClient：`requestMono` 增加 streaming 标志，流式时 `spec.header("Accept-Encoding", "identity")`。
- Ktor：`statement` 增加 streaming 标志，流式时 `header("Accept-Encoding", "identity")`。
- Jetty / Jdk8：已有，改为调用共用工具以消除重复。

**注意 OkHttp 的坑**：`transparentGzip` 判断的是「调用方是否已经显式设置了 `Accept-Encoding`」。只要我们在 `Request.Builder` 上设了 `identity`，OkHttp 就不会再加 `gzip` 也不会解压。这一点要用真实服务端（断言收到的请求头）验证，不能只信文档。

**验收**：六个模块各加一条 `streamingRequestSendsIdentityEncoding`，用 `HttpServer` 的 handler 记录收到的 `Accept-Encoding` 头并断言等于 `identity`。

---

### 2.6 F-19：WebClient 非流式 body 无超时（P1）

**现状**

```java
// requestMono:252-254 —— 只包到响应头
if (request.getConnectTimeoutMs() > 0) {
    mono = mono.timeout(Duration.ofMillis(request.getConnectTimeoutMs()));
}
// executeAsync:165 —— body 读取完全没有超时
return response.bodyToMono(String.class).defaultIfEmpty("")
```

服务端先回 200 头、再停发 body 时永久挂起。

**修法**：给 `bodyToMono` 与 `errorBody` 都加读超时，与流式分支对齐：

```java
long readMs = Timeouts.read(request);
return response.bodyToMono(String.class)
        .timeout(Duration.ofMillis(readMs))
        .defaultIfEmpty("")
        .map(body -> new HttpResult(status, body));
```

`errorBody(response)` 改成 `errorBody(response, readMs)`，内部 `.timeout(...)`。

**同时修 `execute()` 的同步兜底**：现在 `future.get()` 无超时参数，上游 future 若因任何原因不完成（例如 Reactor 侧 bug），同步入口永久阻塞。改成 `future.get(connectMs + readMs, MILLISECONDS)` 并捕获 `TimeoutException` 转 `Kind.TRANSPORT`。这个总时长是上界估计，不作为精确超时用。

**验收**：`WebClientHttpExecutorTest.nonStreamingBodyStallTimesOut`——`HttpServer` 设 `setResponseCode(200)` + `setBodyDelay(2s)`，`readTimeout=150ms`，断言 1 秒内抛 `Kind.TRANSPORT`（不是挂死）。

---

### 2.7 F-20：取消后的残余交付（P1）

三条独立缺陷，分别修。

**① Jetty 异步 `onContent` 不查取消**

```java
// 现状：只判 failed，取消后同一 chunk 内的后续行继续交付
for (String line : lines.accept(bytes)) {
    if (failed.get()) { return; }
    listener.onLine(line);
}
```

改成循环前与循环内都查取消，并统一走一个 `abortIfCancelled`：

```java
if (isCancelled(request.getCancel())) {
    failed.set(true);
    future.completeExceptionally(cancelled(null));
    jettyRequest.abort(new CancellationException("调用已取消"));
    return;
}
for (String line : lines.accept(bytes)) {
    if (failed.get() || isCancelled(request.getCancel())) { return; }
    listener.onLine(line);
}
```

**② WebClient 取消路径不置 `done`**

`afterSubscribe` 里 `bindAbort` 的 `Runnable` 已经 `dispose()` 并 `completeExceptionally`，但没有 `done.compareAndSet`，于是 `subscribeFlux` 的 `onError`/`onComplete` 回调仍可能进来执行 `listener.onLine`。修法：把 `done` 传进 `afterSubscribe`，取消路径先 CAS 再 dispose：

```java
private static void afterSubscribe(final CompletableFuture<?> future,
                                   final AtomicReference<Disposable> ref,
                                   final AtomicBoolean done,
                                   final LlmCancelHandle cancel, Disposable disposable) {
    ...
    cancel.bindAbort(() -> {
        if (done.compareAndSet(false, true)) {
            Disposable current = ref.get();
            if (current != null) { current.dispose(); }
            future.completeExceptionally(cancelled(null));
        }
    });
}
```

**③ Ktor 同步入口完全不 `bind`**

`executeInternal` / `executeStreamingInternal` 只 `checkCancelled` 一次（进入时），之后不再检查，也不把 job 绑到句柄。`execute`/`executeStreaming` 用 `runBlocking`，若要可中断必须把阻塞代码放进可取消的 job：

```java
override fun execute(request: HttpRequest): HttpResult {
    ensureOpen()
    return try {
        runBlocking {
            val job = coroutineContext[Job]
            bindSync(job, request.cancel)   // 绑定 + 进入前检查
            executeInternal(request)
        }
    } catch (ex: InterruptedException) { ... }
}
```

`bindSync` 在 `finally` 里解绑，避免句柄残留。这样 `cancel()` 会取消 `runBlocking` 的 job，`CancellationException` 沿协程传出来。

**验收**：三条各一组用例，断言「取消后 `onLine` 调用次数不再增加」——Jetty/WebClient 用同 chunk 内 3 行 + 首发后取消；Ktor 同步用服务端慢速多行。

---

### 2.8 F-27：Jetty 客户端级 idleTimeout 兜底（P1）

**现状**：`createClient` 里 `client.setIdleTimeout(0)` 主动关掉 Jetty 默认的 30 秒 idle 超时，`newRequest` 又只在 `readTimeout > 0` 时设请求级 `idleTimeout`，于是「读超时配 0」= 完全无超时。

**修法**：

1. `createClient` 的 `client.setIdleTimeout(0)` 改为不设（删除该行），让 Jetty 用默认 30 秒作为**连接空闲**兜底。
2. `newRequest` 无条件 `jettyRequest.idleTimeout(Timeouts.read(request), MILLISECONDS)`——F-18 归一后 `read(request)` 恒为正值。

**注意**：Jetty 的 `client.idleTimeout` 是「连接空闲多久回收」，`request.idleTimeout` 是「这个请求多久没数据算超时」。两者语义不同，前者不能替代后者。删掉 `setIdleTimeout(0)` 只是恢复默认，主兜底仍是请求级的无条件设置。

**验收**：`JettyHttpExecutorTest.zeroReadTimeoutStillTimesOutOnStalledBody`——服务端回 200 头后停发，请求传 `readTimeout=0`，断言在默认 120 秒内失败（用 `readTimeout=-1` + 缩短默认值的方式做成秒级用例，见 §4）。

---

### 2.9 F-28：Jdk8 的 `executeRaw` 跟随重定向（P1）

**现状**：`open()` 没有 `setInstanceFollowRedirects(false)`。`HttpURLConnection` 默认 `true`，管理台试跑看到的是最终 2xx 而不是原始 3xx，违反 `RawHttpExecutor`「3xx 原样返回」的契约。

**修法**

```java
connection.setInstanceFollowRedirects(false);
```

放在 `setRequestMethod` 之后、`getResponseCode` 之前即可。

**验收**：`Jdk8HttpExecutorTest.executeRawDoesNotFollowRedirects`——`HttpServer` 对 `/raw` 回 302 + `Location`，断言 `executeRaw` 拿到 302；`execute` 拿到 `LlmCallException` 且 `status=302`。**同时核实其他五个实现**（审查 V9 疑点）：Jetty 已 `followRedirects(false)`；OkHttp 走 `newBuilder()` 继承 client 默认（OkHttp 默认 `true`）；WebClient 走 Reactor Netty 默认（`false`）；Ktor 默认 `false`。因此 **OkHttp 也要显式关**——这一条比审查写得更广，实施时按「六实现全部显式关」处理。

---

### 2.10 F-24 / F-26：同步读行与响应体上限（P2）

**F-24 现状**：三条同步路径用 JDK `readLine()`，无上限。

**修法**：新增共用有界逐行读取，放进 `internal.http`：

```java
/**
 * 有上限的 UTF-8 逐行读取，供阻塞式传输共用。
 *
 * <p>JDK 的 {@code BufferedReader.readLine()} 没有长度限制，损坏或恶意响应可以
 * 用一条无换行的超长「行」把堆撑爆。这里按字节累计，超过上限抛 PARSE。</p>
 */
public final class BoundedLineReader implements Closeable {

    private final InputStream input;
    private final int maxBytes;
    /** 复用的读缓冲，避免每行都新建。 */
    private final byte[] buffer = new byte[8192];
    ...

    /**
     * @return 下一行（不含换行符）；流结束返回 null
     * @throws LlmCallException 单行超过上限
     */
    public String next() throws IOException { ... }
}
```

内部按字节扫 `\n` / `\r`，与 `Utf8LineBuffer` 同一套行切分规则（顺带解决 F-24 提的「三套行切分规则」）。Jetty / OkHttp / Jdk8 三处同步流式循环改用它。

**同时修 BOM**（F-41 的关联项，审查在 §6）：`BoundedLineReader` 与 `Utf8LineBuffer` 在首个字节为 `EF BB BF` 时跳过。`Utf8LineBuffer` 只需加一个 `boolean first = true` 的状态位。

**F-26 现状**：Jetty 成功体 `append(content, body, 0)` → 无界；OkHttp `body.string()` 全量读入。

**修法**：
- Jetty：`append(content, body, MAX_SUCCESS_BYTES)`，新增常量 `MAX_SUCCESS_BYTES = 8 * 1024 * 1024`（模型响应体合理上界）。超限后丢弃剩余字节但**继续读完**（`onContent` 空转），保证连接可复用；`onComplete` 时若 `body.size() == MAX_SUCCESS_BYTES` 且已标记截断，返回 PARSE 异常「响应体超过 8MB」。
- OkHttp：非流式 `body.string()` 改为 `body.byteStream()` + `readBounded(input, MAX_SUCCESS_BYTES)`；错误体同样走有界读（现在 `ensureSuccessful` 里 `body.string()` 无界）。
- Jdk8：`readAll` 已有 `ByteArrayOutputStream` 但无上限，加 `maxBytes` 参数。
- WebClient：`bodyToMono(String)` 有内存上限风险（Reactor 会全量聚合），改为 `body(BodyExtractors.toDataBuffers())` + 有界聚合，或接受现状并记录——**这一条建议先用文档记录，不改代码**，因为 `bodyToMono(String)` 的聚合上限由 Reactor 的 `maxInMemorySize` 控制，改起来要绕开 `WebClient` 默认 256KB 限制，收益不抵风险。

**验收**：`oversizedSuccessfulBodyIsRejectedWithoutOom`（Jetty / OkHttp / Jdk8 各一条，服务端回 10MB 单行，断言 PARSE 而不是 OOM）；`streamingBomIsStripped`（`Utf8LineBufferTest` + `BoundedLineReaderTest`）。

---

### 2.11 F-25：WebClient 的 DataBuffer 泄漏（P2）

**现状**：`concatMap` 的预取队列在取消/超时/提前终止时，尚未投递给 mapper 的 `DataBuffer` 不走 `finally` 释放。

**修法**：在 `toDataBuffers()` 的 Flux 上加 `doOnDiscard`，并给整个流加 `usingWhen` 兜底：

```java
Flux<DataBuffer> body = response.body(BodyExtractors.toDataBuffers())
        .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
```

`concatMap` 的 `finally` 已释放当前元素；`doOnDiscard` 覆盖预取队列里被丢弃的那些。

**验收**：用 `PooledDataBuffer` 计数（`NettyDataBufferFactory` 的 `PooledByteBufAllocator` 加 leak detector `paranoid`），取消后断言 `refCnt` 归零。若测试环境太脆弱，退化为「取消后 `doOnDiscard` 回调计数 > 0」的单元测试。

---

### 2.12 F-17：把解析与回调移出 event loop（P2，独立版本）

**现状**：Jetty `onContent`（selector 线程）与 WebClient `concatMap`（Reactor event loop 线程）里同步做 `Utf8LineBuffer.accept` → `SseCollector` → Jackson → `onDelta`。用户回调慢一点就卡住同一 loop 上的所有连接。

**修法（最小可用版本，不引入新线程池）**

1. `onContent` / `onNext` 只做**字节拷贝**（`ByteBuffer` → `byte[]`，立刻 `release`/不持有），把 `byte[]` 投递到一个独立的有界单线程执行器。
2. 解析 + `listener.onLine` 在解析线程执行。
3. 执行器来源：复用 `CallbackPolicy` 的共享回调池（`ai-lite-callback-*`），但**必须**用每流一个的串行执行器保证行顺序——`CallbackPolicy.newSerialExecutor()` 正好是这个语义。

**接入点**：不在传输实现里 new，而是由协议适配器层包一层（`StreamListener` 的装饰器），这样自定义传输也自动受益：

```java
/** 把逐行解析与用户回调移出传输线程的 StreamListener 装饰器。 */
public final class OffloadingStreamListener implements StreamListener {
    private final SerialCallbackExecutor executor;
    private final StreamListener delegate;
    /** 解析线程上的异常要通过它回传给传输层，触发 abort。 */
    private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

    public void onLine(String line) {
        if (failure.get() != null) { throw ...; }
        boolean accepted = executor.submit(() -> {
            try { delegate.onLine(line); }
            catch (Throwable t) { failure.set(t); }
        });
        if (!accepted) { throw new LlmCallException(..., OVERLOAD); }
    }
}
```

但要注意：`StreamListener.onLine` 的返回语义是「这一行已处理完」，异步投递后传输层会在回调真正跑完前就 `future.complete(null)`。因此**必须在流结束时等待队列排空**——这正是 `SerialCallbackExecutor` 的完成屏障语义，但当前它没有「等待排空」的 API。需要给 `SerialCallbackExecutor` 加一个 `drainAndAwait(timeout)`，或让传输层在 `onComplete` 时提交一个屏障任务并用 `CountDownLatch` 等待。

**风险评估**：这一条会改变 `onDelta` 的执行线程（从 OkHttp Dispatcher / Jetty selector / Reactor loop 统一变成 `ai-lite-callback-*`），是对外可见变更。同时引入「投递失败 → OVERLOAD」的新失败模式。**建议单独发版本**，并在 `desc-HTTP传输.md` §2 表格里加一列「onDelta 线程」。

**本计划的处理**：A/B/C 三批先合，F-17 单列为后续计划（本文档只保留设计与风险，不实施）。若用户要求本次一并做，按上面设计实施，并接受「`SerialCallbackExecutor` 需要新增排空 API」的连带改动。

---

### 2.13 F-29：密钥轮换状态的非原子重建（P2）

**现状**

```java
// ModelResolver:648-661
private KeyRotation rotationFor(String providerId, int size) {
    KeyRotation existing = rotations.get(providerId);
    if (existing != null && existing.size == size) { return existing; }
    KeyRotation created = new KeyRotation(size);
    KeyRotation winner = rotations.putIfAbsent(providerId, created);
    if (winner != null && winner.size == size) { return winner; }
    rotations.put(providerId, created);   // 非 CAS
    return created;
}
```

**修法**：改成 `compute` 一次原子完成「按 size 重建」，并把「size 相同但内容变了」也纳入判断：

```java
/**
 * 取（或建）provider 的轮换状态。
 *
 * <p>用 {@code compute} 而不是 get/putIfAbsent/put 三段：热更新改变 api-keys 条数或
 * 内容时，多个线程可能同时判定「需要重建」，最后写入者会覆盖别人刚建好的实例，
 * 先返回者在孤儿实例上写 cooldownUntil，冷却就丢了。{@code compute} 保证同一
 * provider 只有一个实例对外可见，且重建与读取在同一个原子操作里。</p>
 */
private KeyRotation rotationFor(String providerId, List<String> keys) {
    final int size = keys.size();
    return rotations.compute(providerId, (id, existing) ->
            existing != null && existing.matches(keys) ? existing : new KeyRotation(keys));
}
```

`KeyRotation` 增加一个 `matches(List<String> keys)`：比 size + 逐个 key 内容。这样内容变化也会重建，顺带修掉「冷却张冠李戴」。

`markKeyFailure` 里的 `rotation.cooldownUntil[matched].set(...)` 与 `cursor.set(...)` 仍写在 `compute` 返回的实例上——因为实例一旦装入就不会被替换（除非 keys 又变了），孤儿窗口消失。

**注意**：`compute` 的 lambda 里**不能**再碰 `rotations`（会死锁在 `ConcurrentHashMap` 的分段锁上），当前实现没有这个用法，保持即可。

**验收**：`ModelResolverKeyRotationTest` 新增并发用例——16 个线程同时用不同 size 调用 `markKeyFailure`，断言最终 `rotations` 里只有一个实例，且失败 key 的冷却落在该实例上。加一条「size 不变、内容变化」用例断言重建。

---

## 3. 跨实现一致性用例（本计划的长期防线）

六份复制粘贴的测试是这些缺陷长期存在的原因。本计划**不再**给六个模块各写一套，而是建立一个共享的抽象基类：

```
ai-lite-llm/src/test/java/io/github/yinwenyuan28/ailite/http/
    HttpExecutorContractTest.java      ← 抽象基类，定义全部契约用例
    TestHttpServer.java                ← 共用 HttpServer 固件（jdk.httpserver）
```

基类声明 `protected abstract HttpExecutor executor();` 与 `protected abstract boolean supportsStreaming();`，每条用例不带 `@Test`（JUnit 5 里抽象方法不会被当测试跑），子类继承后自动获得全部契约。

**放入基类的用例**（每条对应本计划的一条修复）：

| 用例 | 覆盖 |
| --- | --- |
| `zeroTimeoutUsesDefaultRatherThanInfinite` | F-18 |
| `streamingSendsIdentityEncoding` | F-23 |
| `non2xxCarriesStatusAndRetryAfter` | 既有 |
| `cancelDuringStreamingStopsFurtherLines` | F-20 |
| `closeAfterAsyncEntryReturnsFailedFuture` | F-22 |
| `executeRawDoesNotFollowRedirect` | F-28 |
| `oversizedLineIsRejected` | F-24 |
| `bomPrefixedFirstLineIsParsed` | F-24 关联 |
| `preCancelledAsyncReturnsCancelledKind` | 既有 |

**关键约束**：JUnit 版本要支持抽象基类继承。项目当前用 JUnit 5（`org.junit.jupiter`），抽象基类 + 子类 `extends` 是标准做法，不需要 `@Test` 继承技巧。

**迁移策略**：先建基类与 `TestHttpServer`，把 Jetty/WebClient/Ktor 三份重复用例搬进基类；OkHttp/Jdk8 的独有用例保留在各自类里。**不删除**现有用例直到基类版本全绿。

---

## 4. 实施顺序与验收

| 步骤 | 内容 | 完成标志 |
| --- | --- | --- |
| 1 | 写 `Timeouts`、`BoundedLineReader`、`TestHttpServer`、`HttpExecutorContractTest` 骨架 | 新工具类单测绿 |
| 2 | 批次 A：F-21 / F-22 / F-30 | 三条 P0 用例红转绿 |
| 3 | 批次 B：F-18 / F-19 / F-20 / F-23 / F-27 / F-28 | 六个实现全部接入 `Timeouts`；基类 B 批用例全绿 |
| 4 | 批次 C：F-24 / F-26 / F-25 | 超限与释放用例绿 |
| 5 | 批次 D：F-29 | 并发重建用例绿 |
| 6 | 回填文档：017 §3.2 状态列、`desc-HTTP传输.md`、`ref-配置.md`、`CHANGELOG.md` | 文档与代码一致 |
| 7 | `mvn -B verify`（JDK 8） | BUILD SUCCESS，用例数 ≥ 1074 + 新增 |
| 8 | `mvn -B -Dspring-boot.version=2.7.18 verify` | BUILD SUCCESS |

**F-17 不在步骤 1-7 内**，单列后续计划。

**缩短默认超时做秒级用例的办法**：`Timeouts` 的默认值写成 `static int` 而非 `static final`，测试里用包级可见的 setter 临时改成 150ms（`@AfterEach` 恢复）。这是测试专用的入口，生产代码不调。若不想引入可变静态，则用例改用 `readTimeout=150` 并只验证「归一生效」而非「默认值生效」——两种取舍见实施时决定，**优先第一种**，否则默认值本身的正确性没有被测到。

**回归基线**：017 文档记录修复前是 1074 个用例（§11.6）。修复后应只增不减；任何删除现有用例都要在计划里写明理由。

---

## 5. 明确不修与理由

| 条目 | 不修理由 |
| --- | --- |
| F-17 NIO 线程模型 | 改变 `onDelta` 线程归属，是对外可见变更，需独立版本 + 文档同步；且要连带给 `SerialCallbackExecutor` 加排空 API，风险与 A/B/C 不在一个量级 |
| F-26 的 WebClient 部分 | `bodyToMono(String)` 的聚合上限由 Reactor `maxInMemorySize` 控制，改成手动 `DataBuffer` 聚合要绕开 `WebClient` 默认限制，收益不抵风险；改为在 `ref-配置.md` 记录该默认值 |
| F-27 的 `headerTimeout` 纳秒换算 | 审查 V2 疑点，需要 Jetty 9.4 实测。本计划把 `headerTimeout` 改成 `Timeouts.read(request)` 后返回恒正的长整型，`get(timeout, MILLISECONDS)` 的换算风险随之消失，无需单独处理 |
| F-29 的 `markKeyFailure` 锁 | `compute` 已让实例稳定，`cooldownUntil` 是 `AtomicLong[]`，多线程写不同索引无冲突，不加锁 |

---

## 6. 与既有计划的关系

- **017 §11（P0 三条）**：本计划不改那三条的修复，但 F-22 的 `openOrFail` 与 F-21 的 `mapError` 要复用 `FutureSupport` 的既有风格（`internal/llm/FutureSupport.java`）。
- **018（异步挂起）**：F-20 的 Jetty/WebClient 取消路径与 018 的「取消必收敛」是同一契约的传输层落实，不要重复实现收敛逻辑，只补传输层缺失的检查。
- **021（异步与取消剩余）**：F-21 与 F-07/F-19 的修复互相独立；若 021 已改过 `Jdk8HttpExecutor`，以工作区当前版本为准重新核对行号。
- **019（运行时配置）**：F-29/F-30 属配置层，与 019 的 F-31/F-32 无重叠（那两条是缓存与事务），可以并行。

---

## 7. 风险与回滚

| 风险 | 影响 | 处置 |
| --- | --- | --- |
| `timeout=0` 从无限变 120 秒 | 极少数依赖无限等待的长连接场景提前失败 | `CHANGELOG` 明示；`ref-配置.md` 记录；如需无限等待，显式配一个很大的正数 |
| OkHttp 单参构造器语义变化 | 之前 close 会关用户 dispatcher，现在不关 | 修正而非破坏；`CHANGELOG` 记一行 |
| `rotationFor` 改 `compute` | 热更新时重建更频繁（内容比对） | 重建成本是一次小数组分配，可接受 |
| F-24 行上限误杀合法长行 | 单行 > 1MB 的合法 SSE 极少 | 上限做成常量并在异常消息里给出实际值与上限值 |
| `BoundedLineReader` 替换 `BufferedReader` | 行切分规则可能与 `readLine` 有细微差异（`\r` 单独出现） | 用 `Utf8LineBufferTest` 的同一套用例覆盖 |

**回滚单位**：批次 A/B/C/D 各为独立提交，任一批次出问题可单独 revert，不影响其他批次。

---

## 8. 实施记录（2026-11-11）

实际落地 13 条，F-17 按 §2.12 的结论单列后续版本。详细逐条改动见 [017 §12](017-基础能力代码审查.md)，这里只记与计划的偏离。

### 8.1 计划外的发现

| 发现 | 影响 | 处理 |
| --- | --- | --- |
| Jetty 同步 `execute` 用 `ContentResponse`，返回前已全量读入 body | 只改异步 `onContent` 的上限并不能解决 F-26 的同步路径 | 同步 `execute`/`executeRaw` 改用 `InputStreamResponseListener` + 有界读 |
| WebClient 的 `bindAbort` 在 `subscribe` 之后注册 | 同步完成的流里，第一行回调中取消时 abort 还没绑定，后续行照常交付 | 把 abort 注册提到订阅之前，`onNext` 直接查 `cancel.isCancelled()` |
| OkHttp `executeRaw` 也跟随 3xx | 审查只确认了 Jdk8（V9 疑点在这里得到确认） | 两实现都显式关重定向 |
| 错误体超限若按 PARSE 处理，会盖掉 HTTP 状态分类 | 「500 + 超长错误体」变成「响应损坏」 | 错误体截断不失败；只有成功体超限才报 PARSE |

### 8.2 未按计划做的两处

1. **F-18 没有「保留 client 级默认」**。计划 §2.4 的备选是「OkHttp 不无条件覆盖 client 级超时」，实际改成 `≤0` 一律取框架默认。理由：前者只解决「client 有默认值」的情况，「供应商完全没配」仍是 0=无限。代价是显式配 0 不再表示无限，已在 `CHANGELOG` 与 `ref-配置.md` 写明。
2. **F-26 的 WebClient 部分未改**。`bodyToMono(String)` 的聚合上限由 Reactor `maxInMemorySize` 控制，绕开它要手动聚合 DataBuffer，收益不抵风险。改为在 `ref-配置.md` 记录默认值。

### 8.3 未做的长期改进

**跨实现一致性测试基类**（计划 §3）没有做。六个实现的行为已经对齐，但测试仍是六份各自维护的文件；下次改传输行为仍可能漏掉某个实现。建议单列一次重构。

### 8.4 门禁结果

| 门禁 | 结果 |
| --- | --- |
| `mvn -B verify`（JDK 8） | **BUILD SUCCESS**，19 个模块，**1207 个用例**（含 3 个按条件跳过） |
| 修复前基线 | 1074 个用例 |
| 新增用例 | 133 个，分布在 6 个模块 |
| `mvn -B -Dspring-boot.version=2.7.18 verify` | 未执行（本次只跑默认 profile） |
| JDK 21 虚拟线程模块 | 本机 JDK 8，profile 未激活，**未执行** |

### 8.5 仍待办

- **F-17**：NIO 线程模型，设计与风险见 §2.12 与 017 §12.7。
- **跨实现一致性测试基类**：见 §8.3。
- **`spring-boot.version=2.7.18`** 与 **JDK 21** 两套环境需在 CI 补跑。
- **基准**：`SerialCallbackExecutor`（018 改为一任务一提交）与本次超时改动对吞吐的影响需基准确认。
