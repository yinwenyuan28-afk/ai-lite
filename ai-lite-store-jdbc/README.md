# ai-lite-store-jdbc

JDBC 版 `LlmCallStore`：把每次模型调用的账单摘要写进数据库。

## 能力

- 一次调用一行：业务 id、供应商、模型、协议、轮次、token、耗时、停止原因、错误摘要
- 引入模块 + 容器有 `JdbcTemplate` 即自动装配，不需要手写 `@Bean`
- 表名可配；未配置则启动失败（见下）

## 边界

这张表是**电表，不是行车记录仪**：只记"花了多少"，不记"说了什么"。

不含提示词、模型回复、图片和密钥。要做内容级审计，请用业务自己的日志。

另外，它**只有写入、没有查询接口**。怎么查是你自己的 SQL 的事。

## 用法

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-store-jdbc</artifactId>
    <version>0.1.7</version>
</dependency>
```

先建表：

```sql
-- 见 src/main/resources/ai-lite-llm-call.sql
CREATE TABLE AI_LITE_LLM_CALL ( ... );
```

再配置表名：

```yaml
ai-lite:
  store:
    jdbc:
      enabled: true
      table-name: AI_LITE_LLM_CALL
```

## 为什么表名必须显式配置

`table-name` 没有默认值。引入模块但忘了配，**应用会启动失败**，错误消息会同时提示
执行建表脚本。

理由：调用记录写失败会被 `AsyncLlmCallLogger` 静默吞掉（只留一行 warn），
用户可能几周后才发现账单表一直是空的。对一个静默失败的功能，宁可启动就炸。

不需要这个功能时显式关掉：

```yaml
ai-lite:
  store:
    jdbc:
      enabled: false
```

## 手动装配

不用自动装配（比如容器里没有 Boot 自动配置）时：

```java
@Bean
public LlmCallStore llmCallStore(JdbcTemplate jdbcTemplate) {
    return new JdbcLlmCallStore(jdbcTemplate, "AI_LITE_LLM_CALL");
}
```

构造器：`(JdbcTemplate)` 使用默认表名，`(JdbcTemplate, String tableName)` 指定表名。
表名只允许字母、数字和下划线。

## 一个统计口径提醒

一次 `gateway.one()` 不一定只产生一行：工具循环跑 3 轮就是 3 行；重试 2 次就是 2 组行。
每行分配独立的 `callId`，没有跨行关联 ID。

所以按 `BIZ_ID` + 时间窗口统计调用次数时，要留意会把重试和工具轮重复计数。
