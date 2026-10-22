# Workflow 异步执行器使用指南

本文面向业务调用方，说明 `Workflow.runAsync` 的用法、类型模型、取消、重试与性能边界。

适用范围：`ai-lite` 0.1.0-SNAPSHOT；JDK 8+；Spring Boot 2.1 ~ 2.7。
设计背景见 [夯实 Workflow 异步执行器](夯实Workflow异步执行器-plan.md)。

一句话理解：

> **`runAsync` 是“非阻塞的顺序执行器”，不是并行执行器。每一步用 `SyncStep` / `AsyncStep`
> 显式声明同步或异步；异步主循环不自动重试。**

---

## 1. 两个步骤类型

```java
// 同步步骤：会阻塞执行它的线程
public interface SyncStep {
    LlmResult execute(LlmGateway gateway);

    default LlmResult execute(LlmGateway gateway, LlmCancelHandle cancel) {
        return execute(gateway);
    }
}

// 异步步骤：返回 future，不应阻塞调用线程
public interface AsyncStep {
    CompletableFuture<LlmResult> executeAsync(LlmGateway gateway, LlmCancelHandle cancel);
}
```

- `run(List<?>)` 只接受 `SyncStep`。
- `runAsync(List<? extends AsyncStep>)` 只接受 `AsyncStep`，同步步骤在编译期就传不进去。
- 两者不互相继承；一个对象可以同时实现两者，但执行入口分别只认自己的类型。

---

## 2. 快速开始

假设配置里有三个 profile：

```yaml
ai-lite:
  llm:
    biz-ids: [doc-biz]
    providers:
      my-provider:
        api: openai-completions
        base-url: ${AI_BASE_URL}
        api-key: ${AI_API_KEY}
        models:
          - id: ${AI_MODEL}
            input: [text]
            max-tokens: 4096
    profiles:
      extract:
        provider: my-provider
        model: ${AI_MODEL}
      enrich:
        provider: my-provider
        model: ${AI_MODEL}
      summarize:
        provider: my-provider
        model: ${AI_MODEL}
```

业务代码：

```java
@Service
public class DocumentWorkflowService {

    private final Workflow workflow;

    public DocumentWorkflowService(Workflow workflow) {
        this.workflow = workflow;
    }

    public CompletableFuture<List<LlmResult>> process(String document,
                                                      LlmCancelHandle cancel) {
        List<LlmMessage> messages = Collections.singletonList(LlmMessage.user(document));

        LlmCall extract = LlmCall.profile("doc-biz", "extract", messages);
        LlmCall enrich = LlmCall.profile("doc-biz", "enrich", messages);
        LlmCall summarize = LlmCall.profile("doc-biz", "summarize", messages);

        List<AsyncStep> steps = Arrays.asList(
                Workflow.oneStepAsync(extract),
                Workflow.toolLoopStepAsync(enrich, tools(), 4),
                Workflow.oneStepAsync(summarize));

        return workflow.runAsync(steps, cancel);
    }
}
```

执行保证：

- 严格按列表顺序执行；`enrich` 一定在 `extract` 完成后开始。
- 不并行、不合并、不跳过。
- 返回列表顺序与输入一致，不可变。
- 等待模型时不占调用线程；内置异步步骤直接使用网关的 `oneAsync` / `toolLoopAsync`。

---

## 3. API 一览

### 3.1 执行

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `run(List<?> steps)` | `List<LlmResult>` | 同步顺序执行 |
| `run(List<?> steps, LlmCancelHandle cancel)` | `List<LlmResult>` | 同步 + 整体取消 |
| `runAsync(List<? extends AsyncStep> steps)` | `CompletableFuture<List<LlmResult>>` | 异步顺序执行 |
| `runAsync(List<? extends AsyncStep> steps, LlmCancelHandle cancel)` | `CompletableFuture<List<LlmResult>>` | 异步 + 整体取消 |
| `execute(Object step)` | `LlmResult` | 执行单个同步步骤 |
| `execute(Object step, LlmCancelHandle cancel)` | `LlmResult` | 同步 + 取消 |
| `executeAsync(AsyncStep step)` | `CompletableFuture<LlmResult>` | 执行单个异步步骤 |
| `executeAsync(AsyncStep step, LlmCancelHandle cancel)` | `CompletableFuture<LlmResult>` | 异步 + 取消 |

### 3.2 步骤工厂

| 同步 | 异步 |
| --- | --- |
| `Workflow.oneStep(call)` | `Workflow.oneStepAsync(call)` |
| `Workflow.oneStep(call, cancel)` | — |
| `Workflow.toolLoopStep(call, tools, maxRounds)` | `Workflow.toolLoopStepAsync(call, tools, maxRounds)` |
| `Workflow.toolLoopStep(call, tools, maxRounds, cancel)` | — |
| — | `Workflow.oneStepWithRetryAsync(call, policy)` |

工厂会在创建时对 `LlmCall` 和工具列表做快照；调用方后续修改原对象不会影响已构造的步骤。
异步工厂不接收默认取消句柄：`runAsync` 每一步都会创建独立的步骤句柄；取消通过
`runAsync(..., cancel)` 的调用方句柄或 `future.cancel(true)` 表达。

---

## 4. 同步与异步混用

`runAsync` 不接受同步步骤。需要复用旧 `SyncStep` 时，必须显式适配：

```java
AsyncStep adapted = Workflow.adapt(Workflow.oneStep(legacyCall));

workflow.runAsync(Arrays.asList(
        Workflow.oneStepAsync(extract),
        adapted));
```

`adapt` 会把同步步骤放到 `ExecutorProvider.io()` 上执行，**会占住一个 io 线程直到返回**。
它只适合迁移和低并发场景；高并发路径应直接实现 `AsyncStep`。

---

## 5. 取消

```java
LlmCancelHandle cancel = new LlmCancelHandle();

CompletableFuture<List<LlmResult>> future =
        workflow.runAsync(steps, cancel);

// 其他线程、超时任务、断连回调：
cancel.cancel();
```

| 操作 | 结果 |
| --- | --- |
| 进入 `runAsync` 前已取消 | future 异常完成 `WorkflowCancelledException`；`stepIndex=0`；零请求 |
| 步骤间取消 | 不再启动下一步；已完成结果在异常里 |
| 在途句柄取消 | 当前步骤由传输层协作中断；future 以 `WorkflowCancelledException` 完成 |
| 某步返回 `ABORTED` | 即使没有句柄，也视为取消，后续不执行 |
| `future.cancel(true)` | future 进入 JDK cancelled；当前步骤 future 被取消；句柄被 cancel |

`executeAsync` 返回的单步 future 被 `cancel(true)` 时，会传播到内部 run，取消当前步骤并阻止
后续推进。

注意：

- 每次 `runAsync` 使用自己的调用方句柄；并发 run 不要共享。框架内部每一步还会创建
  独立的步骤句柄，调用方句柄只作为“取消整个 run”的转发器。
- 取消是协作式的。自定义 `AsyncStep` 若不使用 `cancel`，框架会取消当前步骤 future，
  但不会强制中断自定义步骤内部的阻塞调用；步骤边界不再推进仍然保证。
- 取消时仍可能产生一条 `aborted` 调用记录。
- 正常完成不会取消调用方句柄，也不会产生假的 `aborted` 记录。

---

## 6. 错误处理

```java
workflow.runAsync(steps, cancel).whenComplete((results, error) -> {
    if (error == null) {
        // results 不可变
        return;
    }
    Throwable cause = error instanceof CompletionException && error.getCause() != null
            ? error.getCause() : error;
    if (cause instanceof WorkflowCancelledException) {
        WorkflowCancelledException cancelled = (WorkflowCancelledException) cause;
        log.info("取消于第 {} 步，已完成 {} 步",
                cancelled.getStepIndex(), cancelled.getCompletedResults().size());
    } else if (cause instanceof WorkflowStepException) {
        WorkflowStepException failed = (WorkflowStepException) cause;
        log.warn("第 {} 步失败", failed.getStepIndex(), failed.getCause());
    } else {
        log.error("Workflow 失败", cause);
    }
});
```

契约：

- **输入错误同步抛出**：null 列表、null 元素、非 `AsyncStep` 等，在返回 future 前失败。
- **运行时失败通过 future 表达**：步骤异常包装为 `WorkflowStepException`，保留
  `stepIndex`、不可变 `completedResults` 和原始 cause。
- **`Error` 不包装**：future 直接以该 `Error` 异常完成。
- **空 future / 空结果**：按步骤失败处理，不会挂起。
- **异常消息**只包含步骤序号，不复制提示词、图片或密钥。
- **流式步骤**已产生的部分增量不会进入 `completedResults`。
- **某步返回 `ABORTED`**：该结果会进入 `completedResults`，随后以 `WorkflowCancelledException`
  收敛，与同步版一致。

`getCompletedResults()` 只用于诊断和部分结果展示，不是断点续跑入口；重跑 `runAsync`
会重新执行所有步骤并重复计费。

---

## 7. 重试

`runAsync` 主循环**不自动重试**。需要重试时，只给特定步骤使用显式工厂：

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(3)
        .initialBackoffMs(200)
        .maxBackoffMs(2000)
        .jitter(true)
        .totalDeadlineMs(10_000L)
        .build();

List<AsyncStep> steps = Arrays.asList(
        Workflow.oneStepAsync(extract),
        Workflow.oneStepWithRetryAsync(summarize, policy));
```

语义：

- 只重试瞬时错误：连接/读超时、408、429、500/502/503/504 等；
- 请求数为 `1..maxAttempts`；每次尝试独立写调用记录，重试会重复计费；
- 退避在 `ai-lite-retry-*` 线程上，不占 HTTP 回调线程；
- 取消会停止后续尝试；
- 总 deadline 只阻止新尝试，不打断在途请求。

`toolLoopStepAsync` 默认不提供重试：工具可能有副作用，整步重试需要业务自己保证幂等。

---

## 8. 自定义 AsyncStep

`AsyncStep` 是函数式接口，可以直接用 lambda：

```java
AsyncStep custom = (gateway, cancelHandle) ->
        gateway.oneAsync(customCall, cancelHandle)
                .thenApply(result -> postProcess(result));

workflow.runAsync(Arrays.asList(
        Workflow.oneStepAsync(extract),
        custom), cancel);
```

实现要求：

- **不要阻塞**：不要在 `executeAsync` 里调用 `gateway.one`、`Thread.sleep` 或阻塞 IO；
- **尊重取消**：把 `cancelHandle` 传给底层异步调用；
- **快速返回 future**：所有等待都通过 future 表达；
- **不要在 future 回调里做重活**：必要时切到业务线程池；
- **不要使用 `ForkJoinPool.commonPool`**：用网关的异步能力或项目自己的受管线程池。

反例：

```java
// 错误：名字是异步，实际仍然阻塞
AsyncStep bad = (gateway, cancelHandle) -> {
    LlmResult result = gateway.one(call);
    return CompletableFuture.completedFuture(result);
};
```

---

## 9. Spring MVC 集成

直接返回 future 可以释放请求线程：

```java
@GetMapping("/documents/{id}/process")
public CompletableFuture<List<LlmResult>> process(@PathVariable String id) {
    return service.process(loadDocument(id), new LlmCancelHandle());
}
```

如果需要在客户端断连时真正取消底层请求，用 `DeferredResult`：

```java
@GetMapping("/documents/{id}/process")
public DeferredResult<List<LlmResult>> process(@PathVariable String id) {
    DeferredResult<List<LlmResult>> out = new DeferredResult<>(30_000L);
    LlmCancelHandle cancel = new LlmCancelHandle();

    out.onTimeout(cancel::cancel);
    out.onCompletion(cancel::cancel);   // 幂等；正常完成后再 cancel 不会产生副作用

    service.process(loadDocument(id), cancel).whenComplete((results, error) -> {
        if (error == null) {
            out.setResult(results);
        } else {
            Throwable cause = error instanceof CompletionException
                    && error.getCause() != null ? error.getCause() : error;
            out.setErrorResult(cause);
        }
    });

    return out;
}
```

直接返回 `CompletableFuture` 时，Servlet 容器不保证断连会取消它；需要可靠取消就用
`DeferredResult` / `SseEmitter`。

---

## 10. 线程与性能

```text
调用线程
  └─ runAsync：校验、快照、启动第一步

HTTP 回调线程 / 自定义步骤完成线程
  └─ 记录结果、启动下一步（不 sleep、不阻塞）

ai-lite-retry-*（仅 oneStepWithRetryAsync）
  └─ 重试退避结束后的下一次尝试
```

要让异步真正转化为性能，还需要：

- **选对 HTTP 传输**：默认 OkHttp 每个在途请求占一个 Dispatcher 线程；高并发考虑
  `jetty`、`webclient`、`ktor-cio` 或 `okhttp-virtual`。
- **工具执行**：`toolLoopStepAsync` 的工具有没有异步化取决于你的 `ToolExecutor`。
  默认 `SequentialToolExecutor` 的 `executeAsync` 会走 `ForkJoinPool.commonPool`；
  高并发或工具阻塞时，请提供受管的异步 `ToolExecutor`。
- **自定义 transport**：未覆盖 `completeAsync` 的 transport 也会退化为 commonPool；
  内置 HTTP transport 都覆盖了异步路径。
- **回调轻量**：`thenAccept` / `whenComplete` 可能运行在 HTTP I/O 线程上，不要做重活。
- **外部背压**：框架没有内置限流；按供应商配额做并发上限或队列控制。
- **慎用 `adapt`**：同步步骤会占 io 线程（`DirectExecutorProvider` 下则占推进线程），
  高并发下会成为瓶颈。

类型显式只能保证“阻塞不会被藏起来”；真正的吞吐还取决于传输、执行器和上游配额。

---

## 11. 常见坑

1. **`runAsync` 传同步步骤**：编译期就失败；raw list 会在运行期拒绝。请用 `AsyncStep` 或显式 `adapt`。
2. **在 `AsyncStep` 里调 `gateway.one`**：看起来异步，实际阻塞，且可能占住 HTTP 回调线程。
3. **共享取消句柄**：并发 `runAsync` 必须各用各的 `LlmCancelHandle`。
4. **以为 `runAsync` 会自动重试**：不会；只有 `oneStepWithRetryAsync` 会。
5. **以为 `runAsync` 会并行**：不会；它严格顺序执行。
6. **在回调里写库**：可能拖住 HTTP 线程；转交业务线程池。
7. **用 `adapt` 承载高并发**：会耗尽 `io()` 线程；改用原生 `AsyncStep`。
8. **重试工具循环**：默认没有；工具副作用需要业务自己保证幂等。

---

## 12. 不在本文范围

- 并行步骤、DAG、条件分支、循环步骤；
- 流式异步步骤工厂；
- `runAsync` 内置重试；
- 步骤间数据流、跨步骤自动传值；
- 回滚、补偿、幂等键、断点续跑；
- 模型路由、限流、熔断、总预算。

---

## 13. 相关文档

- [夯实 Workflow 异步执行器](夯实Workflow异步执行器-plan.md)
- [夯实 Workflow 顺序执行器](夯实Workflow顺序执行器-plan.md)
- [API 使用指南](api-usage.md)
- [单次模型调用入口](entry-points.md)
- [重试边界](retry.md)
- [架构说明](architecture.md)
