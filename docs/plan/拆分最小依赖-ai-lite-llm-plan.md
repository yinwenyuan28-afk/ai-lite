# 拆分最小依赖（方案 B'）：网关模块与会话编排模块

**状态（2026-10-27）：待批准，未动代码。**
**目标**：让「单次调用（`one` / `oneWithRetry` / `oneAsync` / `oneWithRetryAsync`）+ 一个 HTTP 客户端」不再需要拖入 `AgentLoop`、`Workflow`、会话与压缩等整套编排代码。

**与并行工作的边界（重要）**

本计划**只改**这些文件：

- 新增 `ai-lite-llm/pom.xml`、`ai-lite-agent/pom.xml`
- `ai-lite-core/pom.xml`（改为空 jar 型：保留 `packaging=jar`、无源码、传递依赖两个新模块）
- 三个下游 pom 的依赖声明：`ai-lite-session-jdbc/pom.xml`、`ai-lite-session-redis/pom.xml`、`ai-lite-spring-boot-starter/pom.xml`
- 根 `pom.xml` 的 `<modules>` 与 `<dependencyManagement>`（**只增两行模块、两条管理条目，不碰 admin 相关行**）

本计划**绝不改**：

- `ai-lite-admin-api/**`、`ai-lite-admin-ui/**`（另一 agent 正在做）
- 根 pom 里 `ai-lite-admin-api` / `ai-lite-admin-ui` 已有的 modules 与 dependencyManagement 条目
- `ai-lite-demo/**`、`CHANGELOG.md`、`README.md`、`docs/ref-*.md`（另一 agent 正在改）
- 任何 `.java` 文件（除移动目录外，不修改内容）

冲突规避：`ai-lite-admin-api` 只依赖 `catalog` / `config` / `llm` / `util` / `json` / `prompt`，**完全不引用 `assembly` / `session`**（已 grep 验证 0 处），因此拆分后它继续依赖 `ai-lite-llm` 即可，无需另一 agent 配合。

---

## 1. 为什么拆、拆成什么样

### 1.1 现状测量

`ai-lite-core` 当前 104 个文件、16,434 行。单次调用路径的类闭包只有 40 个类、7,602 行（含三个协议适配器），但用户必须拖进整个 core。

按包统计（2026-10-27 实测）：

| 包 | 文件 | 行数 | 归属 |
| --- | --- | --- | --- |
| `llm` | 30 | 4,183 | 网关 |
| `config` | 2 | 1,606 | 网关 |
| `guard` | 2 | 638 | 网关 |
| `transport` | 4 | 1,737 | 网关 |
| `http` | 6 | 171 | 网关 |
| `catalog` | 5 | 254 | 网关 |
| `json` | 2 | 121 | 网关 |
| `util` | 3 | 180 | 网关 |
| `executor` | 2 | 25 | 网关 |
| `internal/llm` | 5 | 337 | 网关 |
| `internal/executor` | 2 | 105 | 网关 |
| `internal/http` | 1 | 90 | 网关 |
| **小计（保留）** | **64** | **9,447** | **`ai-lite-llm`** |
| `assembly` | 18 | 3,027 | 编排 |
| `session` | 17 | 3,107 | 会话 |
| `internal/assembly` | 1 | 80 | 编排 |
| `internal/session` | 3 | 769 | 会话 |
| **小计（剥离）** | **39** | **6,983** | **`ai-lite-agent`** |

加上根目录 `package-info.java`（1 文件 4 行）后：保留 65 文件 9,451 行，剥离 39 文件 6,983 行，合计 104 文件 16,434 行，与 `find` 实测一致。

### 1.2 依赖方向已验证

脚本逐 import 校验：保留组 import 被剥离组的类 **0 处**。反向则大量存在（`assembly` / `session` 引用 `llm` / `config` / `util`），完全符合单向分层。

`internal` 子目录的归属也是干净的：

- `internal/llm`、`internal/executor`、`internal/http` → 网关
- `internal/assembly`（仅 `ToolResultTruncator`）→ 编排
- `internal/session`（`AgentSessionStoreSupport` / `ContextBudget` / `SessionCompactor`）→ 会话

### 1.3 关键设计决定：包名一律不变

新模块沿用现有包名（`io.github.yinwenyuan28.ailite.llm`、`.assembly`、`.session` 等），**只换 artifact**。好处：

1. 用户 `import` 一行都不用改；
2. `ai-lite-admin-api`、`ai-lite-http-*`、`ai-lite-session-*` 的源码零改动，只改 pom；
3. japicmp 看到的仍是同名类，不产生"类已删除"误报。

代价：两个 jar 都含 `io.github.yinwenyuan28.ailite` 前缀，但**包集合完全不重叠**——llm 侧是 `.llm/.config/.guard/.transport/.http/.catalog/.json/.util/.executor/.internal.{llm,executor,http}`，agent 侧是 `.assembly/.session/.internal.{assembly,session}`。根 `package-info.java` 只归 llm。因此并不存在同名包被拆到两个 jar，也不是 JPMS split package。

> 原稿把这里写成「split package 风险」，是不准确的；实际情况没有这个风险。

---

## 2. 目标结构

```
ai-lite-llm        网关 + HTTP SPI + 协议适配器 + 配置 + 目录   （新）
ai-lite-agent      AgentLoop / Workflow / 会话 / 压缩          （新）
ai-lite-core       聚合 pom：依赖上面两个，packaging=pom      （改）
```

**`ai-lite-core` 保留坐标、改为空 jar** 是保持兼容的关键：现在有 15 个 pom 以默认 jar 类型依赖它（且全部未写 `<type>`），外部用户也是同样写法。只要 core 继续产出 jar（哪怕不含源码），并把它对 `ai-lite-llm` / `ai-lite-agent` 的依赖传递出去，所有人的依赖声明都不用改。

> 注意：这里**不能**改成 `packaging=pom`。实测以默认 jar 类型依赖 pom packaging 模块会 `Could not resolve dependencies`；详见 Phase 4 的实测记录。

> 风险点：若外部有人直接 `Class.forName` 或按 jar 名做扫描，会感知不到。0.x 阶段可接受，写进 CHANGELOG 迁移说明即可。

---

## 3. 实施步骤

### Phase 1：新建两个模块骨架（不改任何现有代码）

1. 创建 `ai-lite-llm/pom.xml`：

   ```xml
   <artifactId>ai-lite-llm</artifactId>
   <packaging>jar</packaging>
   <!-- 依赖：slf4j-api、jackson-databind -->
   <!-- test：junit、assertj-core -->
   ```

2. 创建 `ai-lite-agent/pom.xml`：

   ```xml
   <artifactId>ai-lite-agent</artifactId>
   <packaging>jar</packaging>
   <!-- 依赖：ai-lite-llm -->
   <!-- test：junit、assertj-core -->
   ```

3. 根 `pom.xml`：
   - `<modules>` 在 `ai-lite-core` **之前**插入 `ai-lite-llm`、`ai-lite-agent`（保证构建顺序）；
   - `<dependencyManagement>` 增加两条内部模块版本声明。
   - **不触碰** admin 相关行。

### Phase 2：机械移动源码（不修改内容）

用 `git mv` 保留历史（工作区当前是 git 仓库，`git status` 可用）。

**迁往 `ai-lite-llm/src/main/java/`：**

```
config/  guard/  transport/  http/  catalog/  json/  util/  executor/  llm/
internal/llm/  internal/executor/  internal/http/  package-info.java
```

**迁往 `ai-lite-agent/src/main/java/`：**

```
assembly/  session/  internal/assembly/  internal/session/
```

**测试同步迁移（按包归属，共 55 个文件）：**

- `ai-lite-core/src/test/.../llm`（26）、`config`（4）、`guard`（1）、`http`（1）、`json`（1）、`util`（1）、`executor`（1，`PlatformExecutorProviderTest`）→ `ai-lite-llm/src/test/`
- `ai-lite-core/src/test/.../assembly`（10）、`session`（10）→ `ai-lite-agent/src/test/`

注意 `GatewayTestSupport` 在 `llm` 包下，被 agent 侧 **16 个**测试引用（9 个 assembly + 7 个 session），不是 10 个。它是 `public` 类，跨模块可见性没问题，但**测试代码不能跨模块复用**。处理方式见 Phase 3。

### Phase 3：解决测试跨模块引用

`ai-lite-agent` 的测试依赖 `ai-lite-llm` 测试里的 `GatewayTestSupport`（16 个文件引用）。三个选项：

| 选项 | 做法 | 评价 |
| --- | --- | --- |
| **3a（推荐）** | `ai-lite-agent/pom.xml` 声明 `ai-lite-llm` 的 `test-jar` 依赖，`ai-lite-llm` 用 `maven-jar-plugin` 产出 test-jar | 标准做法，零源码改动 |
| 3b | 复制一份 `GatewayTestSupport` 到 agent 测试目录 | 两份漂移，否决 |
| 3c | 建独立 `ai-lite-llm-test-support` 模块 | 多一个 jar，过重 |

选 3a，在 `ai-lite-llm/pom.xml` 加：

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-jar-plugin</artifactId>
  <executions>
    <execution>
      <id>test-jar</id>
      <goals><goal>test-jar</goal></goals>
    </execution>
  </executions>
</plugin>
```

`ai-lite-agent/pom.xml` 加：

```xml
<dependency>
  <groupId>io.github.yinwenyuan28-afk</groupId>
  <artifactId>ai-lite-llm</artifactId>
  <version>${project.version}</version>
  <classifier>tests</classifier>
  <scope>test</scope>
</dependency>
```

### Phase 4：`ai-lite-core` 保留为**空 jar**（关键修订）

> **2026-10-28 修订**：本节原方案是 `packaging=pom`，实测**不可行**，已改为空 jar。
>
> 实测证据（Maven 3.6.2，独立临时工程）：consumer 以默认 `<type>jar</type>` 依赖一个 `packaging=pom` 的模块，报
> `Could not resolve dependencies ... Could not find artifact t:pommod:jar:1.0`。
> 原因是 Maven 不会因为被依赖方是 pom packaging 就自动改写依赖类型；jar 类型依赖请求的是 `group:artifact:jar:`，
> 而 pom 项目产物是 `group:artifact:pom:`，两者 managementKey 不匹配。
>
> 本仓库有 **15 个 pom** 以默认 jar 类型声明了 `ai-lite-core`（全部未写 `<type>`），
> 一旦 core 改成 pom packaging，这些模块在干净环境全部解析失败。
> 更隐蔽的是：本机 `~/.m2` 里残留着 `ai-lite-core-0.1.8-SNAPSHOT.jar`，会让本地构建**假通过**。

改为**保留 `packaging=jar`，但不再含源码**：

```xml
<artifactId>ai-lite-core</artifactId>
<packaging>jar</packaging>
<dependencies>
  <dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-llm</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.yinwenyuan28-afk</groupId>
    <artifactId>ai-lite-agent</artifactId>
  </dependency>
</dependencies>
```

删除 `ai-lite-core/src/`（源码已全部迁走）。Maven 仍会产出一个约 1.2 KB 的空 jar。

实测验证（同一临时工程）：consumer 以默认 jar 类型依赖"空 jar + 传递依赖 lib"，构建 **BUILD SUCCESS**，且能正常使用 lib 的类。

这样：

- 所有 15 个现有依赖声明**继续有效**，`ai-lite-admin-api` 等模块零改动；
- 外部用户现有 `<dependency>ai-lite-core</dependency>` 继续可解析；
- `ai-lite-llm` / `ai-lite-agent` 的类通过传递依赖进入 classpath。

代价只是一个空 jar 制品，远小于 pom packaging 的破坏面。

### Phase 5：下游 pom 调整

**因为 core 保留了 jar 产物，本轮可以只改必须改的，其余保持不动。**

第一轮必改（3 个，因为要独立使用 `ai-lite-llm` / `ai-lite-agent` 的坐标）：

| 模块 | 当前依赖 | 改为 | 原因 |
| --- | --- | --- | --- |
| `ai-lite-session-jdbc` | `ai-lite-core` | `ai-lite-llm` + `ai-lite-agent` | 用 `session` + `internal.session` |
| `ai-lite-session-redis` | `ai-lite-core` | `ai-lite-llm` + `ai-lite-agent` | 用 `session` + `internal.session` |
| `ai-lite-spring-boot-starter` | `ai-lite-core` | `ai-lite-llm` + `ai-lite-agent` | 装配两个模块的 Bean |

第二轮回收敛（可选，不影响本轮正确性）：

| 模块 | 可改为 | 依据（已 grep 验证） |
| --- | --- | --- |
| `ai-lite-prompt` | `ai-lite-llm` | 只 import `util.Strings`、`llm.LlmMessage` |
| `ai-lite-store-jdbc` | `ai-lite-llm` | 只 import `llm.LlmCallRecord` / `LlmCallStore` |
| `ai-lite-http-okhttp` / `-jetty` / `-webclient` / `-jdk8` / `-ktor-cio` | `ai-lite-llm` | 只用 `http` / `llm` / `config` / `json` / `internal.http` |
| `ai-lite-executor-coroutines` | `ai-lite-llm` | 只用 `llm` / `config` / `executor` / `internal`，0 处 `assembly`/`session` |
| `ai-lite-executor-jdk21`（jdk21 profile） | `ai-lite-llm` | 只 import `executor.ExecutorProvider` |
| `benchmark/http-transport-benchmark`、`benchmark/qps-benchmark` | `ai-lite-llm` | 只 import `config` / `http` / `llm` / `internal.executor`；注意这两个 pom **没有 parent**，版本是硬编码的 `0.1.8-SNAPSHOT` |

**保持不动**：

| 模块 | 理由 |
| --- | --- |
| `ai-lite-admin-api` | 只 import `admin`/`prompt`/`catalog`/`llm`/`config`/`util`/`json`，0 处 `assembly`/`session`；依赖空 jar 型 core 仍能拿到全部类。**另一 agent 正在改，不碰。** |
| `ai-lite-admin-ui` | 只依赖 `ai-lite-admin-api` |
| `ai-lite-prompt-jdbc` | 只依赖 `ai-lite-prompt` |
| `ai-lite-http-okhttp-jdk21` | 只依赖 `ai-lite-http-okhttp` |
| `ai-lite-demo` | 依赖 starter / admin-api / admin-ui，无直接 core 依赖 |

> 上表所有「只 import …」都经过逐模块 grep 核验（2026-10-28），不是推断。

### Phase 6：验证

1. `mvn -B clean install -DskipTests` 全模块编译通过；
2. `mvn -B test` 全量测试通过。**验收基线：`@Test` 总数 928，迁移前后不得减少。** 其中 core 原有 55 个测试文件（llm 26、config 4、guard 1、http 1、json 1、util 1、executor 1、assembly 10、session 10），迁移后 llm + agent 合计仍应为 55；
3. **体积验收**：新建临时工程只依赖 `ai-lite-llm` + `ai-lite-http-jdk8`，执行 `mvn dependency:tree`。预期可见 4 个 jar：`ai-lite-llm`、`ai-lite-http-jdk8`、`slf4j-api`、`jackson-databind`（`jackson-databind` 自身还会传递 `jackson-core` + `jackson-annotations`，属正常）。并确认 `LlmGateway.one(...)` 可正常调用；
4. **必须用干净本地仓库验证**，否则 `~/.m2` 里残留的旧 `ai-lite-core-0.1.8-SNAPSHOT.jar` 会掩盖问题：

   ```bash
   mvn -Dmaven.repo.local=/c/temp/ai-lite-empty-repo clean verify
   ```

5. `mvn -B verify -Pcontract-check`（发布前跑）。`ai-lite-llm` / `ai-lite-agent` 没有 0.1.7 基线，会抛
   `Could not find previous version for artifact: ...`。**两个新模块都必须 `<skip>true</skip>`**
   （或设 `ignoreMissingOldVersion`），只给 core 加 skip 不够。`release.yml` 跑的正是这条命令，不做会阻断发布。

---

## 4. 需要你决策的两点

### 4.1 `ai-lite-core` 的坐标策略

- **空 jar（推荐，已实测）**：`packaging=jar` + 无源码 + 依赖 `ai-lite-llm` / `ai-lite-agent`。15 个现有依赖声明全部继续有效，admin agent 零影响，外部用户零改动。代价是多一个 1.2 KB 空 jar。
- ~~聚合 pom（`packaging=pom`）~~：**已实测不可行**。以默认 jar 类型依赖 pom 模块会 `Could not resolve dependencies`，本仓库有 15 个 pom 属于这种情况。若强行使用，必须把这 15 个 pom 全部改成 `<type>pom</type>`，并同步修改根 dependencyManagement 的 core 条目（加 `<type>pom</type>`，否则 managementKey 不匹配导致版本缺失），同时破坏所有外部用户的 jar 依赖。

选空 jar。

### 4.2 japicmp 基线怎么处理

0.1.7 发布的是 `ai-lite-core` 单 jar；0.1.8 之后变成 `ai-lite-llm` + `ai-lite-agent` + 空 jar 的 `ai-lite-core`。

- `ai-lite-core`（空 jar）→ 0.1.7 基线里的类全部"消失"，japicmp 会报大量类删除。**必须跳过**（照抄 `ai-lite-admin-api/pom.xml` 的 `<skip>true</skip>`）。
- `ai-lite-llm` / `ai-lite-agent` → 无 0.1.7 基线，会抛 `Could not find previous version`，**也必须跳过**；从 0.1.8 起把 `ai-lite.release.version` 前移到 0.1.8，之后才有基线。

**这意味着本轮拆分后，API 兼容门禁会空转一个版本。** 必须在 CHANGELOG 写明，否则等于悄悄丢了安全网。这是 B' 的真实代价，请确认接受。

> 补充：根 pom 第 261 行的 `<packaging>jar</packaging>` 在 japicmp 0.15.7 里**不是有效参数**（插件无此配置项），不会起到"只检查 jar 模块"的作用。pom packaging 模块是因插件默认 `skipPomModules=true` 自动跳过的。建议顺手删掉这行无效配置，避免误导。

---

## 5. 风险与回滚

| 风险 | 概率 | 影响 | 应对 |
| --- | --- | --- | --- |
| 某处漏迁导致编译失败 | 中 | 立即发现 | 编译器逐模块报错，按报错补迁移 |
| 测试 test-jar 配置不当 | 中 | agent 模块测试编译失败 | 按 Phase 3 标准写法；引用方 16 个文件，不是 10 个 |
| 空 jar 方案被误写成 pom packaging | 中 | **15 个模块解析失败** | 已实测；坚持 `packaging=jar` |
| `~/.m2` 残留旧 jar 掩盖问题 | 高 | 本地假通过 | 用 `-Dmaven.repo.local` 干净仓库验证 |
| 与 admin agent 在根 pom 冲突 | 低 | merge 冲突 | 只增不改 admin 行；不同时改同一行 |
| japicmp 门禁空转 | 高 | 一个版本无保护 | CHANGELOG 写明，0.1.8 发布后前移基线 |

**回滚**：全部改动集中在 pom 与目录移动，`git revert` 单个提交即可；Phase 2 用 `git mv`，历史可追。

---

## 6. 工作量

| 阶段 | 内容 | 预计 |
| --- | --- | --- |
| Phase 1 | 两个 pom + 根 pom 模块 | 0.5 h |
| Phase 2 | 目录迁移（main 104 文件 + test 55 文件，纯 `git mv`） | 1 h |
| Phase 3 | test-jar 配置（16 个引用文件） | 0.5 h |
| Phase 4 | core 改为空 jar 型 | 0.5 h |
| Phase 5 | 3 个下游 pom（第二轮再收敛其余） | 0.5 h |
| Phase 6 | 编译 + 全量测试（928 用例）+ 体积验收 | 1–2 h |
| **合计** | | **半天** |

---

## 6.1 独立审查记录（2026-10-28）

本计划经一个只读子代理独立核验，下列问题已在正文修正：

| 原稿问题 | 严重度 | 处置 |
| --- | --- | --- |
| `packaging=pom` 会让 15 个默认 jar 依赖的模块解析失败 | **构建失败** | Phase 4 改为空 jar，已实测 BUILD SUCCESS |
| 新模块无 0.1.7 基线，japicmp 会失败；只给 core skip 不够 | **阻断发布** | Phase 6 第 5 条明确两个新模块都要 skip |
| `executor-jdk21`、benchmark 两模块漏在清单外 | 文档遗漏 | 已补入 Phase 5 第二轮表 |
| `GatewayTestSupport` 引用数：原写 10，实际 16（9 assembly + 7 session） | 测试失败 | 已改，Phase 3/风险表同步 |
| 测试迁移漏 `executor/PlatformExecutorProviderTest`（6 个用例） | 测试丢失 | 已补入 Phase 2 |
| `@Test` 总数原写「570+」，实际 928 | 文档不准确 | 已改为 928 作为验收基线 |
| 「传递依赖只剩 2 个 jar」不准确 | 文档不准确 | 已注明 jackson 会带 core/annotations |
| 「split package 风险」不存在 | 文档不准确 | 已改为包集合不重叠的说明 |
| 根 pom `<packaging>jar</packaging>` 是 japicmp 无效参数 | 误导 | 已在 §4.2 补充说明，建议删除 |

核验确认无误的部分：全部文件/行数统计、保留组到剥离组 **0 处反向依赖**、admin 两模块不引用 `assembly`/`session`、`ai-lite-agent` 只需依赖 `ai-lite-llm`、main 代码可零 `.java` 改动（core 无 resources、无 `getResource`/`Class.forName`/`ServiceLoader`、无 `module-info`）。

---

## 6.2 顺带需要同步的文档（本轮不做，拆分提交后单独处理）

拆分会让下列文档失真，但因另一 agent 正在改 README/CHANGELOG，本轮不碰，需在其后单独修正：

- `README.md` 模块表（`ai-lite-core` 的描述）
- `docs/guide-上层调用API.md`、`docs/desc-架构总览.md`、`docs/desc-AgentLoop上下文.md`、`docs/desc-用户级线程.md` 中的 core 路径引用（共约 39 处）
- `PLAN.md` 的模块树
- `CHANGELOG.md` 需补迁移说明（含 japicmp 空窗的告知）

---

## 7. 拆分后的收益（验收标准）

用户只依赖两个坐标即可完成单次调用：

```xml
<dependency>
  <groupId>io.github.yinwenyuan28-afk</groupId>
  <artifactId>ai-lite-llm</artifactId>
  <version>0.1.8</version>
</dependency>
<dependency>
  <groupId>io.github.yinwenyuan28-afk</groupId>
  <artifactId>ai-lite-http-jdk8</artifactId>
  <version>0.1.8</version>
</dependency>
```

传递依赖：`slf4j-api` + `jackson-databind`（后者自身再传递 `jackson-core`、`jackson-annotations`）。不含 `AgentLoop`、`Workflow`、`AgentSessionLoop`、压缩器、会话存储。

代码量对比：从 16,434 行降到 9,451 行（-42%），文件数从 104 降到 65（含根 `package-info.java`）。
