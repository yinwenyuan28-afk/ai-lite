# 夯实 Workflow 异步执行器

**状态（2026-10-23）：已实施并通过回归。** 同步版见
[夯实 Workflow 顺序执行器](夯实Workflow顺序执行器-plan.md)；执行记录见第 13 节。

一句话总纲：

> **`runAsync` 是“非阻塞的顺序执行器”，不是并行执行器。每一步用 `SyncStep` / `AsyncStep`
> 显式标注；每步使用独立取消句柄；异步主循环不内置重试。**

---

## 1. 定位与范围

### 1.1 是什么

`Workflow.runAsync` 按给定顺序执行异步步骤，返回 `CompletableFuture<List<LlmResult>>`。
等待模型响应时不占调用线程；步骤之间仍然严格串行，第 i 步完成后才开始第 i+1 步。

### 1.2 本次范围

- 把同步步骤契约从嵌套 `Workflow.Step` 提取为顶层 `SyncStep`。
- 新增顶层 `AsyncStep`。
- 新增 `runAsync` / `executeAsync`。
- 新增异步步骤工厂：`oneStepAsync`、`toolLoopStepAsync`、`oneStepWithRetryAsync`。
- 新增显式同步步骤适配器 `Workflow.adapt(SyncStep)`。
- 取消、终态、异常、不可变结果、并发与回归测试。
- 文档与测试统计同步。

### 1.3 明确不在本次范围

- 并行步骤、DAG、条件分支、循环步骤。
- `runAsync` 主循环内置自动重试。
- 流式异步步骤工厂（`oneByStreamStepAsync` 等）。
- 步骤间数据流、跨步骤自动传值。
- 新的线程池；内置异步步骤只使用网关已有的异步传输和重试调度器。
- `WorkflowSpec` 运行时引擎化。
- `AgentLoop` / `AgentSessionLoop` 的异步改造。

---

## 2. 目标契约

### 2.1 类型模型：显式标注同步/异步

当前 `Step` 是 `Workflow` 的嵌套接口，列表元素无法在类型上区分同步/异步。改为两个顶层接口：

```java
// io.github.yinwenyuan28.ailite.assembly.SyncStep
public interface SyncStep {
    LlmResult execute(LlmGateway gateway);

    default LlmResult execute(LlmGateway gateway, LlmCancelHandle cancel) {
        return execute(gateway);
    }
}

// io.github.yinwenyuan28.ailite.assembly.AsyncStep
public interface AsyncStep {
    CompletableFuture<LlmResult> executeAsync(LlmGateway gateway, LlmCancelHandle cancel);
}
```

决策：

- **删除嵌套 `Workflow.Step`**，全部改用 `SyncStep`。当前是 `0.1.0-SNAPSHOT`，以清晰类型优先；
  如后续出现外部兼容诉求，再补 `@Deprecated interface Step extends SyncStep` 别名。
- `SyncStep` / `AsyncStep` 不互相继承。一个对象可以同时实现两者，但 `run` / `runAsync` 分别只认自己的类型。
- 自定义异步步骤必须实现 `AsyncStep`；`runAsync` 不接受同步步骤，避免隐藏阻塞。

### 2.2 API

```java
// 同步（现有 API，返回类型改为 SyncStep）
List<LlmResult> run(List<?> steps);
List<LlmResult> run(List<?> steps, LlmCancelHandle cancel);
LlmResult execute(Object step);
LlmResult execute(Object step, LlmCancelHandle cancel);

// 异步（新增）
CompletableFuture<List<LlmResult>> runAsync(List<? extends AsyncStep> steps);
CompletableFuture<List<LlmResult>> runAsync(List<? extends AsyncStep> steps,
                                            LlmCancelHandle cancel);
CompletableFuture<LlmResult> executeAsync(AsyncStep step);
CompletableFuture<LlmResult> executeAsync(AsyncStep step, LlmCancelHandle cancel);
```

步骤工厂：

```java
// 同步
Workflow.oneStep(call);                     // -> SyncStep
Workflow.oneStep(call, cancel);
Workflow.toolLoopStep(call, tools, maxRounds);
Workflow.toolLoopStep(call, tools, maxRounds, cancel);

// 异步（不接收默认 cancel：每次 run 的每一步都会创建新句柄）
Workflow.oneStepAsync(call);                // -> AsyncStep, gateway.oneAsync
Workflow.toolLoopStepAsync(call, tools, maxRounds);
Workflow.oneStepWithRetryAsync(call, policy);          // gateway.oneWithRetryAsync

// 显式把同步步骤适配为异步步骤（会占用 ExecutorProvider.io()）
Workflow.adapt(SyncStep step);
```

说明：

- `run(List<?>)` 保持 `List<?>` 是为了兼容现有调用和运行时预校验；元素必须是 `SyncStep`。
- `runAsync` 使用 `List<? extends AsyncStep>`，让编译器直接拒绝同步步骤。
- `executeAsync` 直接接收 `AsyncStep`，不提供 `Object` 重载。
- 异步工厂不捕获默认取消句柄：`runAsync` 每一步创建 fresh 句柄；直接调用 `executeAsync` 时
  没有句柄可传，最终由 `future.cancel(true)` / 步骤内部自行处理。
- `oneStepWithRetryAsync` 是 `oneWithRetryAsync` 的唯一落点；`runAsync` 本身不做重试。

### 2.3 执行契约

- 严格按列表顺序执行；第 i 步完成前不启动第 i+1 步。
- 不并行、不合并、不跳过、不去重。
- 空列表：返回已完成的不可变空列表，不检查取消。
- 返回列表长度等于步骤数，顺序一致，不可变。
- `runAsync` 不新增线程池；等待由步骤自己的异步实现承担。
- 如果某步 `executeAsync` 同步抛异常，转为 `WorkflowStepException`，不中断引擎。

### 2.4 取消契约（关键修正）

**不要在整个 run 内复用同一个 `LlmCancelHandle`。** `LlmCancelHandle` 是一次性的，且只有一个
`bindAbort` 槽位；传输层可能在 future 完成后才 `unbind`（OkHttp 就是先 complete 再 unbind），
复用会让下一步的 abort 绑定被上一步的晚到 `unbind` 清空，导致取消失效、重试继续发请求。

正确模型：

- **每一步一个 fresh `LlmCancelHandle`**，只传给该步。
- 调用方传入的句柄只作为 **run 级 forwarder**：
  ```java
  callerCancel.bindAbort(run::cancelFromCaller);
  ```
  由 `cancelFromCaller()` 去 cancel 当前步的句柄；终态解绑。
- 调用方未传句柄时，不需要额外创建：`future.cancel(true)` 直接取消当前步骤句柄。
- `future.cancel(true)`：
  - 外层 future 保持 JDK cancelled 语义；
  - 置 `cancelRequested` / `futureCancelled`；
  - 取消当前步骤句柄，并 `currentStepFuture.cancel(true)`；
  - 不再 complete 外层 future。
- 句柄取消（非 `future.cancel`）：
  - 置 `cancelRequested`；
  - 取消当前步骤句柄和步骤 future；
  - 有在途步骤时，等待其回调收敛；无在途步骤时立即以 `WorkflowCancelledException` 收敛。
- 某步返回 `ABORTED` → 后续不执行；`ABORTED` 结果进入 `completedResults`（与同步版一致）。
- 自定义 `AsyncStep` 若忽略句柄，框架通过 `currentStepFuture.cancel(true)` 至少结束当前 future；
  但不会强制中断自定义步骤内部的阻塞调用。文档必须写明这是协作式限制。
- 并发 `runAsync` 必须各用各的调用方句柄；步骤句柄永远是内部新建的。

### 2.5 失败与结果契约

- 输入错误（null 列表、null 元素、非 `AsyncStep`）在返回 future 前**同步抛出**。
- 预取消：返回异常完成的 future（`WorkflowCancelledException`），不同步抛出。
- 步骤抛异常：future 以 `WorkflowStepException` 异常完成，保留 `stepIndex`、
  不可变 `completedResults` 和原始 cause；消息不复制 cause 内容。
- 步骤返回 null future / null result：按步骤失败处理，不能挂起。
- 步骤 future 已被取消：按取消处理（`WorkflowCancelledException`）。
- `CompletionException`：先 unwrap；只有 `LlmCallException` 保留原类型，其他包装为
  `LlmCallException("模型调用失败", cause)`。
- `Error`：不包装，future 以该 `Error` 异常完成。
- 其他停止原因（`STOP` / `LENGTH` / `MAX_ROUNDS` / `CONTEXT_LENGTH_EXCEEDED`）继续执行下一步。
- 结果列表和异常中的已完成结果都是不可变快照；流式步骤的部分输出不进入已完成结果。
- `executeAsync` 单步失败与 `runAsync` 一致，使用 index=0 包装。
- 外层 future 可能在步骤完成的 HTTP 回调线程上完成；用户 `thenAccept` 的线程归属
  属于回调执行策略，不在本层解决。

### 2.6 重试边界

- `runAsync` 不自动重试；请求数 = Σ 每步入站请求数。
- `oneStepWithRetryAsync` 的请求数为 `1..policy.maxAttempts`；每次尝试独立写调用记录，
  重试会重复计费。
- `policy` 为 null 在工厂期拒绝。
- `toolLoopStepAsync` 默认不提供重试；工具可能有副作用，整步重试需要业务自己保证幂等。
- 流式步骤不做“收到 delta 之后”的重试；流式异步步骤工厂不在本轮范围。

---

## 3. 引擎设计

### 3.1 状态模型

每次 `runAsync` 创建一个 `AsyncWorkflowRun`：

```text
AsyncWorkflowRun
  ├─ List<AsyncStep> steps              // 不可变快照
  ├─ LlmGateway gateway
  ├─ LlmCancelHandle callerCancel       // 可空，只作为 forwarder
  ├─ CompletableFuture<List<LlmResult>> result
  ├─ Object lock                        // 保护以下可变状态
  ├─ List<LlmResult> completed
  ├─ int index
  ├─ CompletableFuture<LlmResult> currentStepFuture
  ├─ LlmCancelHandle currentStepCancel  // 每步 fresh
  ├─ StepOutcome pendingOutcome
  ├─ boolean cancelRequested
  ├─ boolean futureCancelled
  ├─ AtomicBoolean terminal             // 终态裁判
  └─ AtomicInteger wip                  // 蹦床
```

`StepOutcome` 只在完成回调里写入，由 `pump()` 消费：

```java
private static final class StepOutcome {
    final int stepIndex;
    final LlmResult value;
    final Throwable error;
    final boolean cancelled;
}
```

写者模型：

- `currentStepFuture` / `currentStepCancel` / `index` / `completed` / `pendingOutcome` 全部在
  `synchronized (lock)` 内修改。
- `cancelRequested` / `futureCancelled` 也在锁内修改；`terminal` 用 CAS 作为终态裁判。
- 完成回调只写 `pendingOutcome`，不直接推进 `index`；推进统一由 `pump()` 完成。

### 3.2 蹦床与 pending outcome

不能用简单 `thenCompose` 链，也不能让回调直接递归启动下一步。使用
work-in-progress 蹦床 + pending outcome：

```java
private void advance() {
    if (wip.getAndIncrement() != 0) {
        return;
    }
    int missed = 1;
    try {
        for (;;) {
            pump();
            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                break;
            }
        }
    } catch (Throwable ex) {
        // pump 自身已捕获 Throwable；这里是最后兜底，保证 future 不悬挂。
        convergeUnexpected(ex);
    }
}

private void pump() {
    synchronized (lock) {
        for (;;) {
            if (terminal.get() || futureCancelled) {
                return;
            }
            if (cancelRequested) {
                convergeCancelledLocked(null);
                return;
            }
            if (pendingOutcome != null) {
                StepOutcome outcome = pendingOutcome;
                pendingOutcome = null;
                currentStepFuture = null;
                currentStepCancel = null;
                if (outcome.cancelled) {
                    convergeCancelledLocked(outcome.error);
                    return;
                }
                if (outcome.error != null) {
                    handleStepErrorLocked(outcome.error);
                    return;
                }
                if (outcome.value == null) {
                    convergeStepFailureLocked(new LlmCallException("步骤返回了空结果"));
                    return;
                }
                completed.add(outcome.value);
                index++;
                if (outcome.value.getStopReason() == LlmStopReason.ABORTED) {
                    convergeCancelledLocked(null);
                    return;
                }
                continue;
            }
            if (index >= steps.size()) {
                convergeSuccessLocked();
                return;
            }
            if (currentStepFuture != null) {
                return;                 // 在途，等回调写 pendingOutcome
            }
            startNextLocked();          // 同步完成时会在锁内写入 pendingOutcome
        }
    }
}
```

`startNextLocked()`：

```java
private void startNextLocked() {
    final int currentIndex = index;
    final AsyncStep step = steps.get(currentIndex);
    final LlmCancelHandle stepCancel = new LlmCancelHandle();
    currentStepCancel = stepCancel;
    CompletableFuture<LlmResult> future;
    try {
        future = step.executeAsync(gateway, stepCancel);
    } catch (Throwable ex) {
        pendingOutcome = new StepOutcome(currentIndex, null, ex, false);
        return;
    }
    if (future == null) {
        pendingOutcome = new StepOutcome(currentIndex, null,
                new LlmCallException("步骤返回了空 future"), false);
        return;
    }
    currentStepFuture = future;
    future.whenComplete((value, error) -> {
        boolean cancelled = future.isCancelled();
        onStepDone(currentIndex, value, error, cancelled);
    });
    // 已同步完成时，whenComplete 已在锁内写入 pendingOutcome；
    // pump 的下一轮循环会消费它。
}
```

`onStepDone`：

```java
private void onStepDone(int stepIndex, LlmResult value, Throwable error, boolean cancelled) {
    synchronized (lock) {
        if (terminal.get() || futureCancelled) {
            return;
        }
        if (stepIndex != index) {
            return;                     // 晚到或重复回调
        }
        pendingOutcome = new StepOutcome(stepIndex, value, error, cancelled);
    }
    advance();
}
```

关键点：

- 已完成的 future 通过 `pendingOutcome` 在当前 `pump()` 循环内消费，不递归。
- 未完成的 future 注册回调后 `pump()` 返回；回调写 `pendingOutcome` 并再次 `advance()`。
- `pump()` 全部在锁内，保证 `index` / `completed` / `pendingOutcome` 单写者。
- `startNextLocked()` 在锁内调用 `executeAsync`；`AsyncStep` 契约要求快速返回。
- `advance()` 的 `try/catch Throwable` 只作为最后兜底，保证 `wip` 不悬挂、future 不永久挂起。

### 3.3 终态收敛

```java
private void convergeSuccessLocked() {
    if (!terminal.compareAndSet(false, true)) return;
    unbindCaller();
    result.complete(immutable(completed));
}

private void convergeCancelledLocked(Throwable cause) {
    if (!terminal.compareAndSet(false, true)) return;
    unbindCaller();
    result.completeExceptionally(
            new WorkflowCancelledException(index, immutable(completed), cause));
}

private void convergeStepFailureLocked(Throwable cause) {
    if (!terminal.compareAndSet(false, true)) return;
    unbindCaller();
    result.completeExceptionally(
            new WorkflowStepException(index, immutable(completed), cause));
}
```

- 成功 / 取消 / 失败都只完成一次。
- `completed` 在收敛时复制成不可变快照。
- `ABORTED` 结果先加入 `completed`，再以取消收敛，和同步版一致。
- `unbindCaller()` 只在终态调用，避免调用方句柄继续持有 run 状态。

### 3.4 取消实现

```java
private void cancelFromCaller() {
    boolean convergeNow = false;
    LlmCancelHandle stepCancel;
    CompletableFuture<LlmResult> stepFuture;
    synchronized (lock) {
        if (terminal.get() || futureCancelled || cancelRequested) {
            return;
        }
        cancelRequested = true;
        stepCancel = currentStepCancel;
        stepFuture = currentStepFuture;
        convergeNow = stepCancel == null && stepFuture == null && pendingOutcome == null;
    }
    if (stepCancel != null) stepCancel.cancel();
    if (stepFuture != null) stepFuture.cancel(true);
    if (convergeNow) {
        synchronized (lock) {
            convergeCancelledLocked(null);
        }
    } else {
        advance();
    }
}
```

- 先置 `cancelRequested`，再取消步骤句柄/future；顺序不能反。
- 在途步骤：取消会触发步骤 future 完成（可能是 `CancellationException`），回调写
  `pendingOutcome`，`pump()` 优先看到 `cancelRequested` 并以取消收敛。
- 无在途步骤：立即收敛，`stepIndex` 是下一步的索引。

`future.cancel(true)`：

```java
result.whenComplete((value, error) -> {
    if (result.isCancelled()) {
        cancelFromFuture();
    }
});

private void cancelFromFuture() {
    LlmCancelHandle stepCancel;
    CompletableFuture<LlmResult> stepFuture;
    synchronized (lock) {
        if (terminal.get()) {
            return;
        }
        futureCancelled = true;
        cancelRequested = true;
        terminal.set(true);
        stepCancel = currentStepCancel;
        stepFuture = currentStepFuture;
        currentStepCancel = null;
        currentStepFuture = null;
        unbindCaller();
    }
    if (stepCancel != null) stepCancel.cancel();
    if (stepFuture != null) stepFuture.cancel(true);
}
```

- `whenComplete` 必须检查 `result.isCancelled()`，否则正常完成也会取消调用方句柄。
- 外层 future 已取消，不再 `completeExceptionally`。

---

## 4. 线程模型

```text
调用线程
  └─ runAsync：校验、快照、创建状态、启动第一步

HTTP 回调线程 / 自定义步骤完成线程
  └─ onStepDone 写 pendingOutcome → advance
       └─ pump：记录结果、启动下一步（不 sleep、不阻塞）

ai-lite-retry-*（仅 oneStepWithRetryAsync）
  └─ 重试退避结束后的下一次尝试
```

- 不新增线程池。
- 不把内置异步步骤放进 `ExecutorProvider.io()`；它们直接使用 `oneAsync` / `toolLoopAsync`。
- `Workflow.adapt(SyncStep)` 显式使用 `io()`，会阻塞该线程直到同步步骤返回；文档必须提示。
  - `DirectExecutorProvider` 会让它在当前推进线程上执行；
  - `CallerRunsPolicy` 可能在 HTTP 回调线程上执行；
  - `future.cancel(true)` 只 interrupt 线程，不保证同步步骤停止。
- **commonPool 边界**：内置异步 HTTP transport 覆盖了 `completeAsync`，不落 commonPool；
  但 `ToolExecutor.executeAsync` 的默认实现和未覆盖 `completeAsync` 的自定义 transport
  仍可能使用 `ForkJoinPool.commonPool`。生产环境应提供受管的 async `ToolExecutor` /
  transport。本计划不把“全链路不落 commonPool”作为验收项。

---

## 5. 兼容与迁移

### 5.1 同步 API

- `Workflow.Step` 删除，改为 `SyncStep`。
- `Workflow.oneStep` / `toolLoopStep` 返回 `SyncStep`。
- `run(List<?>)` / `execute(Object)` 签名保持不变，运行时校验 `SyncStep`。
- 现有自定义 Step 需要把 `implements Workflow.Step` 改为 `implements SyncStep`；
  这是 0.1.0-SNAPSHOT 阶段有意接受的一次性迁移。
- 现有同步测试只改类型引用，不改断言语义。

### 5.2 异步 API

- 全部是新增，不影响现有调用方。
- `runAsync` 只接受 `AsyncStep`；同步步骤通过 `Workflow.adapt(SyncStep)` 显式适配。
- `oneStepWithRetryAsync` 依赖已实现的 `oneWithRetryAsync` 和 `aiLiteRetryScheduler`。
- 异步工厂不捕获默认 cancel；`runAsync` 每一步创建 fresh 句柄。

### 5.3 文档

- `docs/api-usage.md` §4.1：增加异步方法、类型模型、取消与重试边界；删除“`runAsync` 尚未提供”
  和 `Workflow.Step` 的表述。
- `docs/testing/README.md`：新增异步测试类与用例数。
- 本计划回填执行记录。
- README 更新测试统计。
- `docs/Workflow异步执行器使用指南.md` 与实现保持一致（尤其取消、`adapt`、commonPool）。

---

## 6. 实施阶段

### Phase 0：同步契约提取（纯重构）

- 新增顶层 `SyncStep`，删除嵌套 `Workflow.Step`。
- `Workflow`、`WorkflowTest`、`WorkflowHardeningTest` 改用 `SyncStep`。
- `run` / `execute` 行为、异常、消息保持不变。
- 验收：现有同步测试全绿。

### Phase 1：AsyncStep 与工厂

- 新增 `AsyncStep`。
- 新增 `oneStepAsync`、`toolLoopStepAsync`、`oneStepWithRetryAsync`。
- 工厂期对 `LlmCall` / 工具列表做快照；null call / null policy 立即拒绝。
- 验收：工厂返回的步骤能在网关层完成一次真实异步调用（fake transport）。

### Phase 2：异步引擎主路径

- `runAsync` / `executeAsync`。
- 快照、预校验、空列表、顺序推进、pending outcome、蹦床。
- 成功、失败、`ABORTED`、null future/result、`Error`。
- 验收：顺序、结果、异常索引与同步版一致；10,000 个同步完成步骤不爆栈。

### Phase 3：取消与竞态

- 每步 fresh 句柄 + 调用方 forwarder。
- 预取消、步骤间取消、在途句柄取消、`future.cancel(true)`。
- 晚到结果丢弃、终态只一次。
- 验收：竞态测试 100 次稳定；OkHttp 式“先 complete 再 unbind”不破坏下一步取消。

### Phase 4：重试步骤与适配器

- `oneStepWithRetryAsync` 接入 `oneWithRetryAsync`。
- `Workflow.adapt(SyncStep)` 使用 `gateway.getExecutor().io()`。
- 验收：请求数 = `1..maxAttempts`；记录数与网关一致；适配器阻塞/拒绝/取消行为有测试。

### Phase 5：测试与文档

- 新增 `WorkflowAsyncTest`（覆盖取消用例）。
- 更新 `docs/api-usage.md`、`docs/testing/README.md`、README、本计划执行记录。
- 验收：全量测试通过；JDK 8 构建无新增线程池依赖。

---

## 7. 测试矩阵

| 类别 | 用例 | 断言 |
| --- | --- | --- |
| 类型 | `runAsync` 传 raw list 中的同步步骤 | 运行期拒绝，零请求；编译期由泛型拒绝 |
| 预校验 | null 列表 / null 元素 / 非 AsyncStep | 返回 future 前同步抛出 |
| 空列表 | 空列表 | 已完成 future，空不可变列表 |
| 顺序 | 3 个异步步骤 | 第 2 步在第 1 步完成后才开始；`executeAsync` 调用次数 = 步骤数 |
| 成功 | 全部成功 | 结果顺序、字段与网关一致；结果不可变 |
| 停止原因 | `LENGTH` / `MAX_ROUNDS` | 继续下一步 |
| 取消 | 预取消 | 失败 future，`stepIndex=0`，零请求 |
| 取消 | 步骤间取消 | 不再启动下一步，已完成结果正确 |
| 取消 | 在途 handle 取消 | 传输 abort 可观察，future `WorkflowCancelledException` |
| 取消 | 在途 `future.cancel(true)` | future cancelled；当前步骤 future 被取消；无下一步 |
| 取消 | 晚到完成 | 不覆盖取消终态 |
| 取消 | 正常成功 | 不取消调用方句柄，不产生假 aborted 记录 |
| 竞态 | 完成与取消同时到达 | 终态只一次；后续步骤不启动 |
| 取消回归 | OkHttp 式先 complete 后 unbind | 下一步在途取消仍能 abort |
| 蹦床 | 10,000 个已完成的 AsyncStep | 不 `StackOverflowError`，结果数量正确 |
| 蹦床 | 已完成 future 异常完成 / 已取消 | 5 秒内收敛，不悬挂 |
| 失败 | 第 1/第 2 步抛异常 | `WorkflowStepException` 的 index/cause/completed 正确 |
| 失败 | 空 future / 空结果 | 步骤失败，不挂起 |
| 失败 | `CompletionException` | 正确解包 |
| 失败 | `Error`（同步抛 / future 异常完成） | 原样进入 future，不包装 |
| 重试步骤 | 瞬时错误后成功 | 请求数 2；记录数 2；结果正确 |
| 重试步骤 | 退避期间取消 | 不再发请求；retry 的 abort 绑定未被上一步清空 |
| 适配器 | `adapt(SyncStep)` | 在 `io()` 上执行；拒绝策略转为步骤失败 |
| 并发 | 同一 Workflow 并发 `runAsync` | 结果互不串扰；各自句柄；取消互不影响 |
| 线程 | 内置异步 transport | 断言不落 commonPool（fake transport 覆盖 `completeAsync`） |
| 兼容 | 同步 `run` | 现有 31 条 Workflow 测试不改断言通过 |

所有异步断言带 5 秒超时；竞态测试用 latch 或可控 transport，禁止无界等待。

---

## 8. 验收标准

- `SyncStep` / `AsyncStep` 在类型上显式区分；`runAsync` 不接受同步步骤。
- `runAsync` 严格顺序、非阻塞；不新增线程池。
- 10,000 个同步完成步骤不爆栈；任何 Throwable 都不会让 future 永久挂起。
- 取消：预取消、步骤间、在途 handle、`future.cancel(true)` 都能停止后续步骤。
- 每步使用 fresh 句柄；调用方句柄只做 forwarder；正常完成不取消调用方句柄。
- 终态只发生一次；晚到完成不覆盖；`ABORTED` 进入已完成结果。
- 失败异常包含步骤序号、已完成结果和 cause；消息不泄露提示词/图片/密钥。
- 结果与已完成结果不可变。
- `oneStepWithRetryAsync` 是唯一重试入口；请求数与记录数可预测。
- 同步 `run` / `Step` 迁移后行为不变，现有测试全绿。
- JDK 8 下全量测试通过；不引入 JDK 9+ API。

---

## 9. 风险与对策

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| 共享句柄晚到 unbind | 上一步 complete 后 unbind 清掉下一步 abort | 每步 fresh 句柄 + 调用方 forwarder |
| 同步完成深递归 | 自定义 AsyncStep 返回已完成 future | `wip` 蹦床 + pending outcome + 10k 测试 |
| 蹦床异常悬挂 | `join`/回调/收敛抛 Throwable | `pump` 内 catch Throwable；`advance` 外层兜底；测试异常完成/已取消 |
| 取消竞态 | 完成、取消、下一步同时发生 | `lock` + `terminal` + `cancelRequested`；晚到丢弃 |
| 同步步骤迁移 | 删除 `Workflow.Step` 是源码破坏 | 0.1.0 阶段一次性迁移；必要时补 deprecated 别名 |
| 自定义步骤阻塞 | `AsyncStep` 实际阻塞调用线程 | 文档明确契约；测试不依赖线程数 |
| 适配器占用 io | `adapt(SyncStep)` 阻塞 io 线程 | 显式 API + 文档警示；检查取消；拒绝转步骤失败 |
| 句柄复用 | 并发 run 共享调用方句柄会串扰 | 每 run 独立 forwarder；步骤句柄内部新建 |
| 重试计费 | `oneStepWithRetryAsync` 每次尝试真实请求 | 文档明确；调用记录可审计 |
| commonPool | 默认 ToolExecutor / 自定义 transport 可能落 commonPool | 文档明确边界；生产提供受管实现；不作为全链路验收项 |
| 回调线程 | future 可能在 HTTP 线程完成 | 不在本层解决；回调执行策略独立 |

---

## 10. 明确不做

- 并行步骤、DAG、条件分支、循环步骤。
- `runAsync` 内置重试、退避、熔断。
- 流式异步步骤工厂。
- 同步步骤自动适配（必须显式 `adapt`）。
- 新线程池、新调度器。
- 步骤间数据流、跨步骤自动传值。
- `WorkflowSpec` 运行时引擎化。
- 回滚、补偿、幂等键、断点续跑。

---

## 11. 与其他层的关系

- `LlmGateway.oneWithRetryAsync`：只被 `oneStepWithRetryAsync` 使用，不进入 `runAsync` 主循环。
- `AgentLoop.runAsync`：模型驱动的循环，不是顺序执行器；两者不互相嵌入。
- `AgentSessionLoop.resumeAsync`：带会话持久化的续聊，不经过 Workflow。
- `WorkflowSpec` / `Assembly`：业务组织方式，不是运行时。

---

## 12. 相关文档

- [夯实 Workflow 顺序执行器](夯实Workflow顺序执行器-plan.md)
- [Workflow 异步执行器使用指南](Workflow异步执行器使用指南.md)
- [夯实 oneWithRetry](夯实oneWithRetry-plan.md)
- [单次模型调用入口](entry-points.md)
- [API 使用指南](api-usage.md)
- [架构说明](architecture.md)

---

## 13. 执行记录（2026-10-23）

Phase 0–5 已完成：

| 阶段 | 落地内容 |
| --- | --- |
| 0 | 新增顶层 `SyncStep`；删除嵌套 `Workflow.Step`；同步 `WorkflowTest` / `WorkflowHardeningTest` 改用 `SyncStep`，行为不变。 |
| 1 | 新增 `AsyncStep`；新增 `oneStepAsync`、`toolLoopStepAsync`、`oneStepWithRetryAsync`；工厂期快照 `LlmCall` / 工具列表，null call / null policy 立即拒绝。 |
| 2 | 新增 `runAsync` / `executeAsync`；状态机 + `pendingOutcome` + `wip` 蹦床；10,000 个同步完成步骤不爆栈。 |
| 3 | 每步 fresh 句柄 + 调用方 forwarder；`future.cancel(true)`、handle 取消、晚到结果丢弃、终态 CAS。新增 OkHttp 式“先 complete 后 unbind”回归测试。 |
| 4 | `oneStepWithRetryAsync` 接入 `oneWithRetryAsync`；`Workflow.adapt(SyncStep)` 使用 `gateway.getExecutor().io()`，拒绝转步骤失败。 |
| 5 | 新增 `WorkflowAsyncTest`（31 条）；更新 `api-usage`、`testing/README`、README 统计与使用指南。 |

实现中的关键修正（来自独立审查）：

1. **不复用 run 级句柄**：每步 fresh 句柄，调用方句柄只做 forwarder，避免 OkHttp “先 complete 后 unbind”
   清掉下一步 abort 绑定。
2. **pending outcome + 单写者**：完成回调只写 `StepOutcome`，`pump()` 在锁内推进 `index/completed`，
   避免重复执行和晚到覆盖。
3. **蹦床兜底**：`advance()` 外层 `catch Throwable`，`pump()` 内所有路径都收敛终态，避免 `wip`
   悬挂导致 future 永不完成。
4. **取消顺序**：先置 `cancelRequested`，再取消步骤句柄/future；`future.cancel` 的 `whenComplete`
   必须检查 `isCancelled()`，正常完成不取消调用方句柄。
5. **步骤间取消的索引**：先消费已完成步骤的结果，再以“下一步”索引收敛；`ABORTED` 先加入
   `completed` 再收敛，和同步版一致。
6. **commonPool 边界**：内置 async transport 覆盖 `completeAsync`；默认 `ToolExecutor.executeAsync`
   和未覆盖 `completeAsync` 的自定义 transport 仍可能使用 commonPool，已在文档中明确。

