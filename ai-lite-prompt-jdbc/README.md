# ai-lite-prompt-jdbc

基于 `JdbcTemplate` 的提示词存储。表结构见
[`ai-lite-prompt.sql`](src/main/resources/ai-lite-prompt.sql)。

## 接入

```xml
<dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-prompt-jdbc</artifactId>
    <version>0.1.7</version>
</dependency>
```

模块自带 `spring.factories`，只要类路径上有 `JdbcTemplate` 就会注册
`JdbcPromptStore`，并让 Starter 的内存存储让位。

## 事务

```java
PromptStore store = new JdbcPromptStore(jdbcTemplate, transactionManager);
```

传入 `PlatformTransactionManager` 时，发布/回滚会在一个事务里完成。
不传则依赖连接的自动提交；生产环境建议传入。

## 表

- `AI_LITE_PROMPT_VERSION`：版本与模板正文
- `AI_LITE_PROMPT_ACTIVE`：每个 key 的生效指针和 revision
- `AI_LITE_PROMPT_AUDIT`：操作审计，不记录模板正文

`(PROMPT_KEY, VERSION_NO)` 上有唯一索引，多节点并发起草时作为最后防线。
