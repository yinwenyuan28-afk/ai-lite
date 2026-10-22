# oneWithRetryAsync 测试用例

**状态：已实现（2026-10-22）。** 设计见 [夯实 oneWithRetry](../夯实oneWithRetry-plan.md) 第 9 节。

| 规格分组 | 测试类 | 用例数 |
| --- | --- | --- |
| A 基本结果、B deadline、C 取消、D 调度 | `LlmGatewayAsyncRetryTest` | 42 |
| E Starter 装配 | `AsyncRetryAssemblyTest` | 7 |
| F 同步/异步 parity | `LlmGatewayAsyncRetryParityTest` + `LlmCoroutinesRetryTest` | 6 + 2 |

下文保留原始 55 条规格作为验收清单；实际方法名以 `a01…`、`b02…` 等前缀对应编号。

---

## 1. 测试替身

### 1.1 ScriptedTransport

实现 `LlmTransport`，按脚本返回结果，并记录每次尝试：

```java
final class ScriptedTransport implements LlmTransport {
    final AtomicInteger attempts = new AtomicInteger();
    final List<String> threads = new CopyOnWriteArrayList<>();
    final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    final List<CompletableFuture<LlmCompletion>> pending = new CopyOnWriteArrayList<>();
    final List<Outcome> script;

    @Override
    public CompletableFuture<LlmCompletion> completeAsync(LlmRequest request) {
        int index = attempts.getAndIncrement();
        threads.add(Thread.currentThread().getName());
        requests.add(request);
        Outcome outcome = script.get(Math.min(index, script.size() - 1));
        if (outcome.pending) {
            CompletableFuture<LlmCompletion> future = new CompletableFuture<>();
            LlmCancelHandle cancel = request.getCancel();
            if (cancel != null) {
                // 模拟 oneAsync 协作取消：abort 时正常完成 ABORTED，而不是异常。
                cancel.bindAbort(() -> future.complete(abortedCompletion()));
                if (cancel.isCancelled()) {
                    future.complete(abortedCompletion());
                }
            }
            pending.add(future);
            return future;
        }
        if (outcome.error != null) {
            CompletableFuture<LlmCompletion> failed = new CompletableFuture<>();
            failed.completeExceptionally(outcome.error);
            return failed;
        }
        return CompletableFuture.completedFuture(outcome.completion);
    }

    @Override
    public String api() { return "openai-completions"; }

    @Override
    public LlmCompletion complete(LlmRequest request) { ... }
}
```

`Outcome` 支持：

- 立即成功；
- 立即异常（`LlmCallException`）；
- 手动 `pending`，由测试决定何时完成/失败；
- `complete` 同步抛出（用于首次/后续尝试的同步错误）。

### 1.2 RecordingCallStore

记录每次 `LlmCallRecord`：

```java
final class RecordingCallStore implements LlmCallStore {
    final List<LlmCallRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void insert(LlmCallRecord record) { records.add(record); }
}
```

### 1.3 独立 TestScheduler

每个测试注入自己的调度器，避免共享静态调度器污染队列断言：

```java
ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "ai-lite-retry-test-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
scheduler.setRemoveOnCancelPolicy(true);
```

### 1.4 配置与调用

测试统一使用一个最小可用模型配置（`biz-id=demo`、`profile=main`、`model=m`），
通过 `ModelResolver` 正常解析。`LlmCall.explicit("demo", "demo", "m", ...)` 或
`LlmCall.profile("demo", "main", ...)`。

### 1.5 错误工厂

```java
static LlmCallException http(int status) {
    return new LlmCallException("HTTP " + status, null,
            LlmCallException.Kind.HTTP, Integer.valueOf(status));
}
static LlmCallException transport(String message) {
    return new LlmCallException(message, new IOException(message),
            LlmCallException.Kind.TRANSPORT);
}
```

---

## 2. `LlmGatewayAsyncRetryTest`：基本结果与分类

| ID | 用例 | 脚本 | 断言 |
| --- | --- | --- | --- |
| A-01 | 第一次成功 | success | attempts=1；future 成功；records=1；scheduler 队列空 |
| A-02 | 瞬时错误后成功 | TRANSPORT, success | attempts=2；第 2 次线程以 `ai-lite-retry-` 开头；records=2 |
| A-03 | `maxAttempts=1` | HTTP 500 | attempts=1；future 失败；records=1；无调度 |
| A-04 | HTTP 400 | HTTP 400 | attempts=1；Kind.HTTP/400；不重试 |
| A-05 | HTTP 401/404 | 参数化 | attempts=1；不重试 |
| A-06 | PARSE | `Kind.PARSE` | attempts=1；不重试 |
| A-07 | CONFIG | `Kind.CONFIG` | attempts=1；不重试 |
| A-08 | 返回 ABORTED 结果 | `LlmCompletion` ABORTED | attempts=1；不重试；future 完成 ABORTED |
| A-09 | CANCELLED 异常 | `Kind.CANCELLED` | attempts=1；不重试；future 异常 |
| A-10 | 次数耗尽 | 500, 503, 502，max=3 | attempts=3；主异常 502；suppressed=2；records=3 |
| A-11 | 每次尝试独立记录 | 同 A-10 | records.size()==3；每条记录都有独立 latency/usage |
| A-12 | 自定义 retryable=true | 500 + 自定义谓词 true | 重试直到成功/耗尽 |
| A-13 | 自定义 retryable=false | 500 + 谓词 false | attempts=1 |
| A-14 | policy=null | 任意 | 方法同步抛 `Kind.CONFIG`；attempts=0；records=0 |
| A-15 | 首次尝试同步抛 | transport 首次 `completeAsync` 抛 CONFIG | 方法同步抛；attempts 已计入或为 0 按 `oneAsync` 现有语义；不能返回半成品 future |

---

## 3. `AsyncRetryDeadlineTest`

| ID | 用例 | 脚本/参数 | 断言 |
| --- | --- | --- | --- |
| B-01 | 无 deadline | `totalDeadlineMs=null` | 按 maxAttempts 重试 |
| B-02 | deadline=0 | 未取消 | 零尝试；future 失败“重试超过总时限”；records=0 |
| B-03 | 首次失败后剩余时间不足 | 500；remaining=50ms；delay=200ms | attempts=1；不再发第二次；deadline 异常 |
| B-04 | 退避被 clamp 到 remaining | delay=200ms，remaining=100ms | 调度延迟约 100ms；到点后 deadline 收敛 |
| B-05 | 在途请求不被 deadline 打断 | transport pending；deadline=50ms；100ms 后手动成功 | attempts=1；future 成功；不额外取消 |
| B-06 | deadline 异常保留最后分类 | 500 后 deadline | Kind/statusCode 与最后一次失败一致；前次 suppressed |
| B-07 | deadline=0 且已取消 | handle 已取消 | 取消优先：future ABORTED；records=1；零 HTTP |

> B-05 是契约测试：deadline 只阻止新尝试，不打断在途请求。

---

## 4. `AsyncRetryCancellationTest`

| ID | 用例 | 操作 | 断言 |
| --- | --- | --- | --- |
| C-01 | 预取消 | 调用前 `handle.cancel()` | attempts=0；future 正常完成 ABORTED；records=1（`oneAsync` 的 aborted 记录） |
| C-02 | 首次尝试在途取消 | pending；`handle.cancel()` | attempts=1；future ABORTED；无第二次；队列空 |
| C-03 | 退避中取消 | 首次 500；schedule 后 `handle.cancel()` | attempts=1；future ABORTED；scheduledRetry 取消；队列空；records=1（不补未发生尝试） |
| C-04 | 尝试中 `future.cancel(true)` | pending；cancel outer | outer `isCancelled()`；attempt handle 被取消；无第二次 |
| C-05 | 退避中 `future.cancel(true)` | schedule 后 cancel outer | outer cancelled；队列空；无第二次 |
| C-06 | 重复取消 | cancel 两次 | 幂等；终态/记录不重复 |
| C-07 | 取消后完成回调到达 | cancel 后让 pending 成功 | outer 保持 ABORTED；不被成功覆盖 |
| C-08 | 完成后取消 | success 后 `handle.cancel()` | outer 保持成功；无副作用；代理已解绑 |
| C-09 | 取消与重试任务竞态 | 100 次，锁存器交错 | 终态只一次；attempts ∈ {1,2} 且不出现 attempt3；队列空 |
| C-10 | 取消边界 | 在 `startAttempt` 锁内/锁外交错 | 要么第二次未发，要么发出后立即取消；绝不出现第三次 |

### C-09 竞态测试骨架

```java
for (int i = 0; i < 100; i++) {
    CountDownLatch attempt2Started = new CountDownLatch(1);
    CountDownLatch allowCancel = new CountDownLatch(1);
    // script: 500, pending
    CompletableFuture<LlmResult> future = gateway.oneWithRetryAsync(call, policy, handle);
    awaitFirstRetryScheduled();
    Thread canceller = new Thread(() -> {
        await(allowCancel);
        handle.cancel();
    });
    canceller.start();
    allowCancel.countDown();
    attempt2Started.await(1, SECONDS);
    // 断言：future 终态只完成一次；attempts<=2；scheduler queue 空
}
```

---

## 5. `AsyncRetrySchedulingTest`

| ID | 用例 | 断言 |
| --- | --- | --- |
| D-01 | 零延迟不递归 | 同步失败 transport + `maxAttempts=1000` + delay=0；不 `StackOverflowError`；attempts=1000；第 2 次起线程名 `ai-lite-retry-*` |
| D-02 | 终态后队列空 | 成功/失败/取消后 `scheduler.getQueue().isEmpty()` |
| D-03 | 调度器已关闭 | `shutdownNow` 后触发重试；future 异常完成（不能挂起）；attempts 符合预期 |
| D-04 | 第 2 次尝试同步抛 | 第 2 次 `completeAsync` 抛 CONFIG；future 异常完成；无挂起 |
| D-05 | 重试线程名 | 在 transport `completeAsync` 入口记录线程；第 2 次及以后以 `ai-lite-retry-` 开头 |
| D-06 | 不使用 io() | 所有 transport 调用线程不来自 `ExecutorProvider.io()`（用命名前缀断言） |
| D-07 | 监听器抛 RuntimeException | 重试继续；future 正常收敛；日志有记录（若实现接入 logger） |
| D-08 | 监听器抛 Error | 重试继续；worker 不死亡；future 正常收敛 |
| D-09 | 监听器 delay 一致 | 监听器收到的 delay 与调度任务 delay 相同；jitter 下也只计算一次 |
| D-10 | 退避 jitter 范围 | 关闭 jitter 精确断言；开启 jitter 在 `[base*0.5, base]` 或策略定义范围内 |

> D-05 的线程断言必须放在 transport 入口，不能放在 `whenComplete`：
> `whenComplete` 跑在完成 future 的线程（可能是 OkHttp/Jetty/Netty），不是重试启动线程。

---

## 6. `AsyncRetryAssemblyTest`（Starter）

| ID | 用例 | 断言 |
| --- | --- | --- |
| E-01 | 默认装配 | 存在名为 `aiLiteRetryScheduler` 的 `ScheduledExecutorService`；线程 daemon |
| E-02 | 自定义同名 Bean | 用户 Bean 优先；默认 Bean 不创建 |
| E-03 | 另一个 `ScheduledExecutorService` | 同时存在两个同类型 Bean 时，Gateway 仍按 `@Qualifier` 注入，不 `NoUniqueBeanDefinitionException` |
| E-04 | 手工构造兼容 | 旧 5 参 `LlmGateway` 构造器仍可用；异步重试使用默认静态 daemon 调度器 |
| E-05 | 注入调度器 | 新构造器使用注入的 scheduler；不关闭用户 scheduler |
| E-06 | 关闭语义 | Starter 创建的 scheduler 在上下文关闭时 `shutdownNow`；用户 Bean 不被关闭 |
| E-07 | 队列清理 | 上下文关闭后调度线程退出；不残留非 daemon 线程 |

---

## 7. `AsyncRetryParityTest`

| ID | 用例 | 断言 |
| --- | --- | --- |
| F-01 | 同步 vs 异步：成功 | attempts 相同；结果字段相同；records 数相同 |
| F-02 | 同步 vs 异步：次数耗尽 | attempts 相同；最终 Kind/status 相同；suppressed 数相同；records 数相同 |
| F-03 | 同步 vs 异步：非重试错误 | attempts=1；最终异常相同 |
| F-04 | `oneAsync` vs `oneWithRetryAsync` 取消 | 两者 handle 取消都得到 ABORTED；预取消记录数相同 |
| F-05 | 退避中取消 | **只比较 attempts 与终态**，不比较 records：同步版多一条历史 aborted 记录 |
| F-06 | Kotlin `oneWithRetry` vs 异步 | 次数/终态一致；协程版 delay 不占载体 |

---

## 8. 断言工具

```java
static <T> T await(CompletableFuture<T> future, long seconds) {
    return future.get(seconds, TimeUnit.SECONDS);
}

static LlmCallException failure(CompletableFuture<?> future, long seconds) {
    try {
        future.get(seconds, TimeUnit.SECONDS);
        fail("expected failure");
        return null;
    } catch (ExecutionException ex) {
        assertThat(ex.getCause()).isInstanceOf(LlmCallException.class);
        return (LlmCallException) ex.getCause();
    } catch (Exception ex) {
        throw new AssertionError(ex);
    }
}

static void awaitQueueEmpty(ScheduledThreadPoolExecutor scheduler)
        throws InterruptedException {
    // 取消/终态后给调度线程一个很小的窗口处理 removeOnCancel。
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (!scheduler.getQueue().isEmpty() && System.nanoTime() < end) {
        Thread.sleep(10L);
    }
    assertThat(scheduler.getQueue()).isEmpty();
}
```

---

## 9. 验收映射

| 计划验收项 | 覆盖用例 |
| --- | --- |
| I/O 线程不做退避 | D-05、D-06 |
| 零延迟也走调度器 | D-01 |
| 每次尝试新 handle | C-02、C-04、A-02 |
| handle 取消 ABORTED | C-01、C-02、C-03、F-04 |
| future.cancel 硬取消 | C-04、C-05 |
| terminal 只一次 | C-07、C-09、C-10 |
| deadline 只阻止新尝试 | B-02、B-03、B-05 |
| 独立记录 + suppressed | A-10、A-11、B-06、F-02 |
| 调度器装配 | E-01–E-07 |
| 不修改 oneAsync/编排层 | 全量回归 |

---

## 10. 实施顺序

1. 先写 D-01、A-01、A-02、A-10：覆盖状态机主路径。
2. 再写 C-01–C-07：覆盖取消与 ABORTED 契约。
3. 再写 C-09、C-10：竞态与锁协议。
4. 再写 B-02–B-06：deadline 边界。
5. 最后写 D-02–D-10、E、F：调度器、装配与 parity。

测试与实现同批提交；不要在 API 尚未存在时把编译不过的测试留在主分支。
