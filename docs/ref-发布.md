# 发布参考

> **本文类型**：速查（ref-）。**讲什么**：版本策略、发布清单、CI 与 Maven Central 的配置。**不讲什么**：功能行为。

## 1. 版本策略

- 遵循[语义化版本](https://semver.org/lang/zh-CN/)：`主.次.修订`。
- **0.x 阶段 API 未冻结**：0.1.x 内以不破坏为主，跨次版本（0.1 → 0.2）允许破坏性调整，破坏点写进 CHANGELOG 的迁移说明。
- **1.0 的门槛**：API 冻结承诺 + 至少一个真实业务灰度运行一个版本周期。冻结前公开承诺以「0.1.0 已发布能力清单」为准。
- 主干开发用 `-SNAPSHOT`（如 `0.1.1-SNAPSHOT`），发布时翻正并打 `vX.Y.Z` tag。

## 2. 发布清单（checklist）

1. **验证**：`mvn -B verify` 全绿（本地或 CI）；有条件时跑 `ai-lite-external-test` 真实供应商冒烟。
2. **CHANGELOG**：`CHANGELOG.md` 顶部加新版本段（新增 / 修复 / 已知限制），日期用发布日。
3. **翻版本**：全部 `pom.xml` 的 `X.Y.Z-SNAPSHOT` → `X.Y.Z`（跨模块依赖同样替换），提交 `release: X.Y.Z`。
4. **打 tag**：`git tag vX.Y.Z`。
5. **恢复开发版**：翻到 `X.Y.(Z+1)-SNAPSHOT`，提交 `chore: 开始 X.Y.(Z+1)-SNAPSHOT`。
6. **推送**：`git push origin main vX.Y.Z`——CI 全绿跑矩阵，release 工作流收集制品并（在配置了凭证时）部署 Central。
7. **GitHub Release**：以 tag 为基础建 Release，描述贴 CHANGELOG 该版本段。
8. **README**：状态节的测试数量与版本号如有变化同步更新。

## 3. CI（.github/workflows/）

| 工作流 | 触发 | 内容 |
| --- | --- | --- |
| ci.yml | push main / PR | JDK 8×(Boot 2.1, 2.7)、JDK 17×Boot 2.7、JDK 21（含虚拟线程模块）全量 `verify`，每个 job 都带 Redis 7 service（集成用例真实执行）；gitleaks 全历史密钥扫描 |
| release.yml | push `v*` tag | 全量 verify（含 Redis）→ 收集全部模块 jar 为 workflow artifact → 配置了凭证时部署 Maven Central（`-Prelease`：sources / javadoc / GPG 签名 / Central Portal 上传，`ai-lite-demo` 排除） |
| dependabot.yml | 每周 | Maven 依赖与 GitHub Actions 版本更新 PR（上限 5 个/生态） |

## 4. Maven Central 前置条件（一次性配置）

发布到 Central 走 [Sonatype Central Portal](https://central.sonatype.com/) 的 `central-publishing-maven-plugin`（已在根 pom 的 `release` profile 里）。需要到 GitHub 仓库 Settings → Secrets and variables → Actions 配置 4 个 secret：

| Secret | 含义 |
| --- | --- |
| `CENTRAL_USERNAME` | Central Portal 生成的 user token 用户名 |
| `CENTRAL_PASSWORD` | Central Portal 生成的 user token 密码 |
| `GPG_PRIVATE_KEY` | 签名用 GPG 私钥（ASCII armor 全文，`gpg --armor --export-secret-keys` 导出） |
| `GPG_PASSPHRASE` | 该私钥的口令 |

namespace 要求：Central 的 `io.github.yinwenyuan28-afk` namespace 需在 Portal 完成一次性的归属验证（按 GitHub 用户名验证）。未配置这些 secret 时，release 工作流会打 warning 并跳过部署——tag 和制品 artifact 不受影响。

发布方式：`autoPublish=false`，部署后到 Portal 的 Publish 阶段人工确认再上线（可发现签名/元数据问题后重试）。

## 4.1 发布额度与文件数（重要）

Maven Central 自 2026-10-01 起对发布量设硬阈值，**按组织、按自然月独立计算**（不滚动）。三个指标都算：文件数、单次发布体积、发布次数。具体阈值只在该组织的 [Usage Center](https://central.sonatype.com/publishing/usage)（Publishing → Usage Center）里可见，文档只给了全站分布：**第 90 百分位是 1,167 文件/月、78 MB/次、7 次/月**，限额就设在 90 百分位。

### 这个库的消耗

发布 17 个模块 + parent pom，共 **68 个产物**。每个产物贡献的文件数取决于校验和档位：

| 组成 | 每产物文件数 |
| --- | --- |
| 本体 | 1 |
| `.asc` GPG 签名 | 1 |
| `.md5` + `.sha1`（必需） | 2 |
| `.sha256` + `.sha512`（可选） | 2 |

- 默认 `checksums=all`：6 个/产物 → **单次发版 408 个文件**
- 已配置 `checksums=required`：4 个/产物 → **单次发版 272 个文件**

`ai-lite-llm` 比其他模块多 6 个（有一个对外发布的 `tests.jar`）；`ai-lite-core` 是空 jar 聚合坐标，只有 2 个产物。

**272 个仍占月度限额的 23%，一个月最多发 4 次。** 所以发版节奏要克制：文档、注释、纯测试改动不值得单独发版；日常改动用 SNAPSHOT 或 GitHub Packages 验证。

### 为什么设 `required` 而不是 `none`

Central 的强制要求只有 `.md5` 与 `.sha1`（官方 Requirements「Provide File Checksums」：`.md5` 和 `.sha1` required，`.sha256`/`.sha512` supported but not mandatory）。设 `none` 会在校验阶段因缺少必需校验和而失败。所以 `required` 是合法的下限。

> 该参数由 `central-publishing-maven-plugin` 0.12.0 读取（`PublishMojo` 里对 `checksums` 做 `ChecksumRequest.isValidValue` 校验），`REQUIRED` 分支只生成 MD5/SHA1，`NONE` 分支一个都不生成——这是反编译插件 jar 确认的行为，不是文档推断。

### 超限了怎么办

1. **30 天宽限期**：超限当月不立即拦截，给 30 天处理。宽限期结束后，**该指标**的新发布会被拒（不是全面封禁）。
2. **宽限期重置条件**：连续 2 个月低于限额后重置。这意味着超限后即便下个月合规，宽限期也不会立刻消失。
3. **申请提高限额或豁免**：写 `central-support@sonatype.com`，注明 namespace、组织、项目说明、发布模式说明、以及用量是持续性还是偶发。官方明确欢迎按需申请，合法社区开源项目不受影响。

发邮件时可以直接引用这个事实：单次 272 个文件来自 17 个模块的刻意分层（每种 HTTP 传输、每种存储后端独立坐标，供用户按需引入），不是刷包。

## 4.2 排障笔记：Central 返回 401 时怎么判断

排查发布失败时很重要的一点：**不要用「无上传体的 POST」去验证 token**。Central 的 `/api/v1/publisher/upload` 只接受 `multipart/form-data`，任何缺少 bundle 的请求都会返回 401 或 500，**与凭证好坏无关**。曾据此误判「token 失效」，白花了半天。

可靠的判断顺序：

1. **看 Maven 日志里的名字**：`Using Usertoken auth, with namecode: xxx` —— 这里的 namecode 就是 `CENTRAL_USERNAME` 的值，能直接确认 secret 有没有传错。
2. **造一个最小 bundle 做真实上传**（唯一可靠判据）：
   ```bash
   U='你的Username'; P='你的Password'
   TOKEN=$(printf '%s:%s' "$U" "$P" | base64 | tr -d '\n')
   printf 'x\n' > /tmp/x && (cd /tmp && jar cf bundle.zip x)
   curl -s -w '\nHTTP %{http_code}\n' -H "Authorization: Bearer $TOKEN" \
     -F "bundle=@/tmp/bundle.zip;type=application/octet-stream" \
     "https://central.sonatype.com/api/v1/publisher/upload?name=probe"
   ```
   **201/400/422 都表示认证通过**（400/422 只是 bundle 内容不合规）；只有 401 才是凭证问题。
3. **Portal 上的 Accepted 认证方式**：`Authorization: Bearer <base64(user:pass)>` 是官方推荐；插件 0.6.0 用的 `UserToken <base64(...)>` 目前仍被接受。

其他常见失败：

| 现象 | 原因与处理 |
| --- | --- |
| `gpg: signing failed: Inappropriate ioctl for device` | CI 无 TTY。`gpgArguments` 需同时带 `--batch` 与 `--pinentry-mode loopback`，口令经 `gpg.passphrase` 属性传入 |
| `setup-java` 步骤失败 | `GPG_PRIVATE_KEY` 内容不完整（缺 BEGIN/END 行、CRLF、末尾无换行） |
| `Bundle has content that does NOT have a .pom file` | `central-publishing-maven-plugin` **0.6.0 在 Maven 3.10 下**把 `maven-metadata-local.xml` / `_remote.repositories` 写进 staging 目录且打包前不清理，bundle 缺 pom。**升级到 0.12.0**（2026-10 修复；仍支持 JDK 8，要求 Maven 3.9+） |
| 部署校验 `Failed to get coordinates from pom file` | 上传了 SNAPSHOT 版本；Central 只收正式版 |
| 构建 success 但 Portal 上没有任何部署 | 可能给 reactor **最后一个模块**配了 `skipPublishing`——该插件以聚合模式工作，只在最后一个模块执行一次 publish（打包全部模块），跳过它等于跳过整批上传 |
| 只有部分模块进 bundle（缺 parent/依赖） | 用 `-pl` 单独发布某模块时拿不到父 POM 继承的 url/licenses/scm/developers，必须整批 reactor 发布 |
| `Javadocs must be provided but not found in entries` | 纯 Kotlin 模块（`sourceDirectory=src/main/kotlin`）产不出 javadoc jar。用 `prepare-package` 把源码复制到 `target/javadoc-src`，再由 `maven-jar-plugin` 打 `-javadoc` 分类制品；**必须显式 `<include>**/*.kt</include>`**，否则该插件按 class 规则过滤，产出空 jar |

### 4.3 首次发布踩坑复盘（2026-10-26，0.1.0 → 0.1.7）

从 0.1.0 到真正通过 Portal 校验，连续失败 5 轮。值得记录的不是每个错误本身，而是**判断方法上的失误**——它们让排查多花了好几倍时间。

**失误一：用一个不会区分对错的探针去验证假设**

当时想确认 Central token 是否有效，用了 `curl -X POST .../publisher/upload`（无请求体）。它返回 401，于是判定「token 失效」，反复让用户重新生成 token、重配 secret。

真相是：该端点只接受 `multipart/form-data`，**任何**缺 bundle 的请求都返 401——好凭证和坏凭证的返回值完全相同。这个探针根本没有区分能力。

> **原则**：验证手段必须先自证能区分。方法是用**已知错误的输入**跑一次，确认它给出「失败」；再用**已知正确的输入**跑一次，确认它给出「成功」。两次结果相同，就说明这个探针无效，不能用来下结论。

**失误二：修复一个现象时，没有确认修复本身没有引入新问题**

v0.1.2 失败时看到 demo 模块报错，判断是「demo 不该发布」，于是给它加了 `skipPublishing=true`。构建从此变成 success——**看起来修好了**。

实际上该插件以聚合模式工作，只在 reactor 最后一个模块上执行一次 publish、打包**全部**模块。demo 正好是最后一个，跳过它等于跳过整批上传，所以 v0.1.4「成功」却什么都没发到 Portal。

> **原则**：「从失败变成功」不等于修好了。发布类操作要核对**外部系统的实际状态**（Portal 上有没有部署、版本对不对），而不是只看命令退出码。

**失误三：把旧记录的结论套到新版本上**

查 GitHub Actions 状态时读到了缓存的旧运行（v0.1.4 的 success），误以为 v0.1.5 成功了，白等一轮。

> **原则**：按 sha 或 tag 校验查询结果，不要只按「最新的那条」取。

**失误四：在错误的前提下加诊断代码，越修越远**

为了定位 401，我在工作流里加了「凭证自检」步骤，用的还是失误一那个无效探针。它给不出有用信息，反而占了两个版本的时间。

> **原则**：诊断代码本身要有明确的判定语义（什么结果代表什么），否则删掉比留着好。

**最终定位到的四个真实原因**（都不是最初怀疑的凭证问题）：

| 版本 | 真实原因 |
| --- | --- |
| 0.1.4 | 自检用了无区分能力的探针（伪问题） |
| 0.1.5 | demo 的 `skipPublishing` 关掉了唯一的聚合上传点 |
| 0.1.6 | `central-publishing-maven-plugin` 0.6.0 在 Maven 3.10 下 bundle 缺 pom（官方已知，0.12.0 修） |
| 0.1.7 | 两个纯 Kotlin 模块产不出 javadoc jar |

**下次发布前值得先做的三件事**：

1. 本地跑一遍 `mvn -Prelease verify -Dgpg.skip=true`，确认 javadoc/sources 制品齐全（`target/*-javadoc.jar` 里要有文件）。
2. 确认 `central-publishing-maven-plugin` 版本 ≥ 0.12.0（Maven 3.10 runner）。
3. 发布后在 Portal 的 Deployments 页面核对版本号与组件数，而不是只看 CI 绿灯。

## 5. 签名密钥的生成（本地一次性）

```bash
gpg --full-generate-key          # RSA 4096，邮箱用 GitHub 提交邮箱
gpg --keyserver keyserver.ubuntu.com --send-keys <指纹>   # 公钥上传供校验
gpg --armor --export-secret-keys <指纹>                   # 私钥导出给 GPG_PRIVATE_KEY
```

## 6. 相关文档

- [CHANGELOG](../CHANGELOG.md)——每个版本改了什么
- [架构总览](desc-架构总览.md)——分层与模块拓扑
