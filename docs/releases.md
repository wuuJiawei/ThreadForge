# Release Guide

本文档描述 ThreadForge 从 `1.1.2` 开始使用的自动发版流程。

## 触发规则

- tag 命名必须为 `vX.Y.Z`
- tag 对应的 commit 必须可从 `main` 或 `master` 到达
- `pom.xml` 中的 `project.version` 必须与 tag 去掉 `v` 之后的版本完全一致

## 自动执行内容

当维护者 push 一个符合规则的 tag 后，`.github/workflows/release.yml` 会自动执行：

1. checkout 全量历史与 tags
2. 使用 JDK 21 执行 `mvn -B -ntp clean verify`
3. 使用 `release` profile 执行 `mvn -B -ntp -P release -DskipTests deploy`
4. 依据上一条 semver tag 到当前 tag 的 git commit 历史生成 release notes
5. 创建 GitHub Release，并上传生成的 jar / sources jar / javadoc jar 等资产

## 必需 Secrets

- `CENTRAL_TOKEN_USERNAME`: Sonatype Central user token username
- `CENTRAL_TOKEN_PASSWORD`: Sonatype Central user token password
- `GPG_PRIVATE_KEY`: ASCII-armored private key
- `GPG_PASSPHRASE`: 对应私钥的 passphrase

## Release Notes 分类规则

release notes 由 [`scripts/generate-release-notes.sh`](../scripts/generate-release-notes.sh) 生成，按 commit subject 前缀归类：

- `feat:` -> `Features`
- `fix:` -> `Fixes`
- `docs:` -> `Docs`
- `build:` / `ci:` / `chore(build):` / `chore(ci):` / `chore(deps):` / `chore(release):` -> `Build / CI`
- 其他提交 -> `Improvements`

如果提交信息不符合以上约定，仍会被收进 `Improvements`，但 release notes 的可读性会下降。

## 建议的提交前缀

- `feat`: 新功能或对外能力增强
- `fix`: bug 修复
- `docs`: 文档更新
- `refactor`: 结构调整但不改变对外行为
- `build`: Maven、依赖、发布链调整
- `ci`: GitHub Actions、校验流程、缓存策略调整
- `chore(release)`: 版本号、release 准备工作

## 维护者发版步骤

1. 在目标 release commit 中更新 `pom.xml`、`README.md`、`CHANGELOG.md` 等版本信息
2. 将变更合入 `main`
3. 创建并推送 tag，例如 `git tag v1.1.2 && git push origin v1.1.2`
4. 等待 GitHub Actions `Release` workflow 完成
5. 检查 GitHub Release 正文、附件和 Maven Central 发布结果

## 失败排查

- tag 校验失败：检查 tag 命名和 `pom.xml` 版本是否一致
- branch 校验失败：确认 tag 指向的 commit 已经在 `main` 或 `master` 历史中
- Central 发布失败：优先检查 token、GPG key、POM 元数据和 Sonatype Portal 返回日志
- Release notes 分类不理想：统一后续提交前缀，必要时重新打一个修正后的 release commit
