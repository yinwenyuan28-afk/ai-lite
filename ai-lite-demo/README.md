# Demo 配置说明

## 1. 最省事的方式：环境变量

```bash
export AI_LITE_DEMO_BASE_URL=https://your-endpoint/v1
export AI_LITE_DEMO_API_KEY=your-key
export AI_LITE_DEMO_MODEL=your-model
java -jar ai-lite-demo-0.1.7.jar --ai-lite.demo.run=true
```

密钥只存在于当前进程环境，不落文件。

## 2. 本地文件方式

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
# 编辑 application-local.yml，填入 base-url / api-key / model
mvn spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--ai-lite.demo.run=true
```

`application-local.yml` 已被 `.gitignore` 忽略。**填好之后不要提交，也不要让其他人读取。**

## 3. 不联网只验证装配

```bash
java -jar ai-lite-demo-0.1.7.jar
```

默认 `ai-lite.demo.run=false`，只打印已加载的 providers 和 profiles，不发真实请求。

## 配置字段

| 字段 | 说明 |
| --- | --- |
| `ai-lite.llm.biz-ids` | 允许调用的业务 id，未登记会被拒绝 |
| `ai-lite.llm.providers.<id>.api` | 协议：`openai-completions` / `openai-responses` / `anthropic-messages` |
| `base-url` | 端点根地址，不要带 `/chat/completions` |
| `api-key` | 优先用 `${ENV}` 占位符 |
| `models[].thinking-format` | `reasoning-effort` / `chat-template` / `deepseek` / `none` |
| `profiles.<id>` | 业务使用的预置组合 |
