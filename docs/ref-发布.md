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

## 4.1 排障笔记：Central 返回 401 时怎么判断

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

## 5. 签名密钥的生成（本地一次性）

```bash
gpg --full-generate-key          # RSA 4096，邮箱用 GitHub 提交邮箱
gpg --keyserver keyserver.ubuntu.com --send-keys <指纹>   # 公钥上传供校验
gpg --armor --export-secret-keys <指纹>                   # 私钥导出给 GPG_PRIVATE_KEY
```

## 6. 相关文档

- [CHANGELOG](../CHANGELOG.md)——每个版本改了什么
- [架构总览](desc-架构总览.md)——分层与模块拓扑
