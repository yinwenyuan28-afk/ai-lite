# ai-lite-session-jdbc

基于 `JdbcTemplate` 的 `AgentSessionStore`。表结构见
[`ai-lite-session.sql`](src/main/resources/ai-lite-session.sql)。

## 接入

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-session-jdbc</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

模块自带 `spring.factories`。只要容器里有 `JdbcTemplate`，就会注册
`JdbcAgentSessionStore`，并优先于 Starter 的默认内存存储；没有 `JdbcTemplate`
时回退到内存存储。

## 表名

默认 `AI_LITE_SESSION`，可以配置：

```yaml
ai-lite:
  session:
    jdbc:
      table-name: MY_AGENT_SESSION
```

表名只允许字母、数字和下划线；非法值会在启动时抛 `IllegalArgumentException`。
建表 SQL 里的表名也要同步修改。

## 并发

`save` 使用 `VERSION` 条件更新，版本不匹配时抛“会话版本冲突”。同一会话的并发
`resume` 需要调用方处理这个冲突（重试或让用户重发）。
