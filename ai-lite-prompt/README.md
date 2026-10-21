# ai-lite-prompt

版本化提示词中心。纯 Java，不依赖 Spring、MyBatis 或具体数据库。

## 能力

- 目录登记：key、标题、占位符、兜底模板
- 版本流转：草稿 → 发布 → 回滚
- 发布前占位符校验
- 操作审计（起草、修改、发布、回滚）
- 占位符渲染，直接产出 `LlmMessage`
- 生效版本短 TTL 缓存，发布/回滚立即失效
- 存储故障时回退到配置默认模板，不阻断业务

## 用法

```java
PromptKey key = new PromptKey("resume-parse", "简历解析",
        Arrays.asList("resume", "job"),
        "请解析这份简历：{{resume}}", "目标岗位：{{job}}");
PromptManager manager = new PromptManager(
        new PromptCatalog(Collections.singletonList(key)),
        new InMemoryPromptStore(), 60L);

PromptVersion draft = manager.createDraft("resume-parse", null, null, null, "alice");
manager.publish(draft.getVersionId(), "alice");

RenderedPrompt prompt = manager.render("resume-parse", vars);
List<LlmMessage> messages = prompt.getMessages();
```

## 存储 SPI

实现 `PromptStore` 即可替换存储。`activate` 必须保证"退役旧版本、发布目标版本、
更新生效指针"三步原子完成。JDBC 实现见 `ai-lite-prompt-jdbc`。

## 语义

- 只有草稿可以修改和发布。
- 只有发布过的版本可以回滚；当前生效版本不能再次回滚。
- 同一 key 的版本号从 1 递增。
- 存储不可用时 `current` 返回空，`render` 使用默认模板。
- 内容摘要为 system + user + schema 的 SHA-256。
