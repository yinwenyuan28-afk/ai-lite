# 重试边界

本文说明 `ai-lite` 在哪一层做重试、哪些错误可以重试、以及为什么网关本身不自动重试。

---

## 1. 结论

- **`LlmGateway` 不做自动重试。** 每一轮模型请求只调用一次 transport，失败就结束这一轮。
- **`OkHttpExecutor` 关闭了 OkHttp 的连接重试**（`retryOnConnectionFailure(false)`），避免和上层策略叠加。
- **重试属于调用方策略。** 需要重试时，在单次入口外面包一层 `RetryPolicy`，而不是修改网关。
- **编排层的重试另立计划。** `AgentLoop` 每轮、`AgentSessionLoop` 整体、`Workflow` 步骤的重试不在本文范围内。

---

## 2. 当前行为

`LlmGateway.one` / `oneAsync` 对一次调用只做一件事：发出请求，等待一次响应。

| 情况 | 网关行为 |
| --- | --- |
| transport 抛 `LlmCallException` | 同步入口直接抛；异步入口 future 异常完成 |
| transport 抛其他 `RuntimeException` | 包装成 `LlmCallException("模型调用失败", cause)` |
| HTTP 5xx / 429 | transport 抛 `LlmCallException`，网关不重试 |
| 连接或读取超时 | transport 抛 `LlmCallException`，网关不重试 |
| 调用被取消 | 返回 `ABORTED`，**绝不重试** |
| 流式中途断开 | 已经产生的增量保留，本轮以异常结束，不自动续传 |

`Attempt` 只是 usage / latency 累加器，不承担重试。调用记录里每一轮只对应一次实际请求。

---

## 3. 推荐的重试结构

```text
业务调用方
  └─ RetryPolicy（次数、退避、分类、总 deadline）
       └─ LlmGateway.oneAsync(call, cancel)
            └─ 每次尝试是一条独立的 LlmCallRecord
```

一个最小实现应包含：

1. **最大尝试次数**，而不是无限重试。
2. **指数退避 + 抖动**，避免上游恢复瞬间被打满。
3. **总 deadline**，包含所有尝试和退避时间；超过就放弃。
4. **取消贯通**：同一个 `LlmCancelHandle` 传给每次尝试；用户取消后立即停止退避和下一次尝试。
5. **每次尝试独立记录**，不要把多次尝试的 usage 混进一条记录。

参考伪代码：

```java
LlmResult callWithRetry(LlmGateway gateway, LlmCall call, int maxAttempts,
                        LlmCancelHandle cancel) {
    LlmCallException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        if (cancel.isCancelled()) {
            throw new LlmCallException("调用已取消");
        }
        try {
            return gateway.one(call, cancel);
        } catch (LlmCallException ex) {
            if (!retryable(ex) || attempt == maxAttempts) {
                throw ex;
            }
            last = ex;
            sleepBackoff(attempt, cancel);
        }
    }
    throw last;
}
```

---

## 4. 可重试分类

### 4.1 可重试

| 情况 | 说明 |
| --- | --- |
| HTTP 429 | 遵守 `Retry-After`；没有该头时用指数退避 |
| HTTP 500 / 502 / 503 / 504 | 上游临时故障 |
| 连接超时、读取超时 | 网络抖动或上游过载 |
| 连接被重置、DNS 临时失败 | 可识别的瞬时 IO 错误 |
| 流式尚未收到首个 delta | 此时没有已提交的部分输出，重试安全 |

### 4.2 不可重试

| 情况 | 原因 |
| --- | --- |
| HTTP 400 / 401 / 403 / 404 / 422 | 参数、鉴权或资源问题，重试不会变好 |
| 模型不存在、协议不支持、配置缺失 | 配置错误 |
| 消息/图片/工具参数校验失败 | 请求本身非法 |
| 内容审核拒绝 | 重试只会再次被拒绝 |
| 上下文超长 | 需要截断或换模型，不是重试 |
| 取消 | 用户意图优先，L2 明确禁止再推进 |
| 流式已经收到 delta | 重试会产生重复输出，调用方需自己决定是否续写 |

---

## 5. 幂等与副作用

单次入口只读模型输出，本身没有业务副作用，所以重试是安全的。但调用方要注意：

- **流式回调**可能已经推给前端；重试前要由业务决定是否清空已展示内容。
- **调用记录**会按尝试次数写多条，统计 QPS 和成功率时应按 `callId` 或时间窗口聚合。
- **计费**：失败的尝试也可能已被供应商计费，重试不会退款。

---

## 6. 为什么不在网关里重试

1. **取消语义会变复杂**：L2 要求取消后不再推进，网关内部重试很容易在取消竞态里多打一次请求。
2. **重试策略因业务而异**：实时对话可能一次都不重试，离线批处理可以重试五次。
3. **deadline 属于调用方**：只有调用方知道整个请求还剩多少时间。
4. **可观测性**：调用方重试可以按尝试记录，网关内部重试容易把多次请求混成一条。

因此网关只承诺“一次尝试的契约”，重试是上层组合。

---

## 7. 相关文档

- [单次模型调用入口](entry-points.md)
- [架构说明](architecture.md)
- [用户级并发与取消](user-level-concurrency.md)
- [夯实单次模型调用入口](夯实单次模型调用入口-plan.md)
