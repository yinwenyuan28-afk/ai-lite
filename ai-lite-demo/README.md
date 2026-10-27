# Demo 配置说明

Demo 是一个带管理页的 Spring Boot 进程。数据库是 SQLite 文件 `data/ai-lite-demo.db`，相对启动时的工作目录。首次启动会执行 `schema.sql` 建表。

管理页开关在 `application.yml` 里是打开的（调试用）：

```yaml
ai-lite:
  admin:
    enabled: true
```

启动后打开 `http://localhost:8080/ai-lite/admin`。供应商默认收起，点名字再展开。同步模型只列出远端结果，勾选后才加入；删除只能在页面上做。调用日志可以选择不记输入输出、只记失败，或全部记录。

## 1. 填密钥

`application.yml` 已经写了供应商地址，密钥放在本地文件里，不要提交。

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
# 编辑 application-local.yml，填入 api-key
mvn spring-boot:run
```

`application.yml` 会带上 `local` 配置。`application-local.yml` 已在 `.gitignore` 中。

## 2. 不发真实请求，只看页面

默认 `ai-lite.demo.run=false`，启动后不调用模型。页面仍可打开，用来同步模型、补齐能力参数、发布提示词。

业务 id 是 `demo-chat`。在页面上把它绑定到一个已就绪的模型并发布提示词之后，可以加 `--ai-lite.demo.run=true` 发一次 `LlmCall.biz`。

## 配置字段

| 字段 | 说明 |
| --- | --- |
| `ai-lite.admin.enabled` | 管理页开关。demo 里为 `true`，库默认 `false` |
| `ai-lite.llm.biz-ids` | 允许调用的业务 id。目录模式下它也是提示词 key |
| `ai-lite.llm.providers.<id>.base-url` | 端点根地址，不要带 `/chat/completions` |
| `ai-lite.store.jdbc.table-name` | 调用摘要表，demo 使用 `AI_LITE_LLM_CALL` |

模型列表不写在配置文件里。在管理页同步 `GET {baseUrl}/models`，或手工添加后再补齐能力参数。
