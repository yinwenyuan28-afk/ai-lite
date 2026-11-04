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

## 管理页

三个页签：供应商与模型、业务与提示词、调用日志。页面只用自己渲染的控件（下拉、开关、勾选、弹窗都是自定义的），
不出现浏览器原生 `select` / `checkbox` / `alert`。

- **供应商与模型**：供应商默认收起，点标题展开；可以改协议、地址、密钥和超时，测连通，同步远端模型。协议下拉旁边会直接显示「实际请求地址」，换协议时能看清 `base-url` 后面接了什么。模型表每行有
  就绪开关——它是唯一的启用入口，点了立刻写库；还有「编辑 / 测试 / 删除」。
- **业务与提示词**：每个业务一张折叠卡片，默认收起。展开后可绑定已就绪模型、编辑系统/用户模板、预览、保存草稿、发布、回滚，并查看版本记录与审计。
- **调用日志**：设置输入输出记录档位，按业务/供应商/模型/结果筛选与分页；每条可看详情。

「就绪」与「能力参数」是两件事：填完参数不会自动启用，要单独打开就绪开关。输入类型里勾了「图片」，
这个模型才允许接收图片消息。

### 请求参数（0.1.15 起）

供应商弹窗里有「供应商请求头」和「供应商附加请求体」，模型弹窗里有「思考档映射」「模型请求头」
「采样参数」「分档采样参数」「附加请求体」。这些值写进数据库，走 `LlmCall.biz` 时进请求。

三条规则：

- 留空表示「沿用配置文件」，`{}` 表示「明确清空」。
- 两级同名请求头（忽略大小写）由模型级覆盖供应商级。
- `Authorization` 与 `Content-Type` 由库管理，不能在这两处配。

请求体的附加字段是**递归合并**的，三层优先级：单次调用 > 模型级 > 供应商级。嵌套对象按子键合并，
所以模型级只写一个子键时，供应商级同父键下的其它子键会保留。

单次调用的临时字段用 `LlmCall.extraBody`：

```java
LlmCall call = LlmCall.biz("demo-chat", messages)
        .extraBody(Collections.singletonMap("trace_id", traceId));
```

模型弹窗底部的「这次会发什么」会列出合并后的请求头键名（标注来自哪一级）与请求体附加字段名，
不显示具体值——用来确认配置有没有生效，不泄露内容。

### 改管理页样式

`admin.css` 是 Tailwind 的编译产物，已提交并打进 jar，运行时不依赖 Node。改样式要改源文件后重新构建：

```bash
cd ai-lite-admin-ui/frontend
npm install          # 首次
npm run build        # 生成 ../src/main/resources/ai-lite-admin/admin.css
```

源文件是 `ai-lite-admin-ui/frontend/admin.src.css`，页面结构在 `ai-lite-admin-ui/src/main/resources/ai-lite-admin/`
下的 `index.html` 与 `admin.js`。Tailwind 按这两个文件里出现的类名生成样式，所以新增类名后必须重新构建。

## 配置字段

| 字段 | 说明 |
| --- | --- |
| `ai-lite.admin.enabled` | 管理页开关。demo 里为 `true`，库默认 `false` |
| `ai-lite.llm.biz-ids` | 允许调用的业务 id。目录模式下它也是提示词 key |
| `ai-lite.llm.providers.<id>.base-url` | 端点根地址，不要带 `/chat/completions` |
| `ai-lite.store.jdbc.table-name` | 调用摘要表，demo 使用 `AI_LITE_LLM_CALL` |

模型列表不写在配置文件里。在管理页同步 `GET {baseUrl}/models`，或手工添加后再补齐能力参数。
