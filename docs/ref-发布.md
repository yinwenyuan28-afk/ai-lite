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

## 5. 签名密钥的生成（本地一次性）

```bash
gpg --full-generate-key          # RSA 4096，邮箱用 GitHub 提交邮箱
gpg --keyserver keyserver.ubuntu.com --send-keys <指纹>   # 公钥上传供校验
gpg --armor --export-secret-keys <指纹>                   # 私钥导出给 GPG_PRIVATE_KEY
```

## 6. 相关文档

- [CHANGELOG](../CHANGELOG.md)——每个版本改了什么
- [架构总览](desc-架构总览.md)——分层与模块拓扑
