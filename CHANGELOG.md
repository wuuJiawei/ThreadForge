# 更新日志

本文件记录项目的所有重要变更。

规则：按时间倒序记录（最新版本在最上方）。

## [Unreleased]

1. 重写 `docs/ROADMAP.md`，按 `feature/*` 分支拆分后续工作线，并明确 docs/onboarding、joiners、examples/benchmarks、observability 的推进顺序。
2. 新增人类开发者快速上手文档，补齐安装、首个示例、何时使用 ThreadForge 以及默认失败/超时/取消语义说明。
3. 重写 AI 安装说明，明确 Codex / Claude Code / Cursor / Copilot / Windsurf 该使用哪种集成文件以及如何安装。
4. 对齐 `README.md`、`docs/api/README.md`、`docs/FEATURE.md` 的产品现状和 roadmap 口径，避免把未来能力写成已发布能力。
5. 新增 `JoinStrategy` 与 `ScopeJoiner`，提供 `firstSuccess`、`quorum(n)` 与 `hedged(delay)` 高阶编排能力。
6. 补充 joiner API 的测试、API 文档和 AI 集成规则说明。
7. 新增 `examples/` 目录，提供并行聚合、first-success fallback、quorum voting、channel pipeline 等可运行示例。
8. 新增 `benchmarks/` 目录，提供 JMH benchmark 与对 `ExecutorService` / `CompletableFuture` 的基线对比。
9. README 补充 examples / benchmarks 入口和本地运行说明。

## [1.1.2] - 2026-03-11

1. 新增 tag 驱动的 GitHub Actions `release.yml`，在 `vX.Y.Z` tag 推送后自动执行构建、测试、GitHub Release 创建与 changelog 注入。
2. 新增 `scripts/generate-release-notes.sh`，基于 git log 自动生成 release notes，并按 `Features / Improvements / Fixes / Docs / Build / CI` 分类整理。
3. README 与维护者文档补充 release 流程、tag 规则、本地 Central 发布说明以及 commit message 分类约定。
4. 统一仓库内公开文档版本标识到 `1.1.2`，为后续 tag 发版做准备。

## [1.1.1] - 2026-03-11

1. 升级 `maven-compiler-plugin`、`maven-surefire-plugin`、`maven-javadoc-plugin`、`maven-gpg-plugin` 与 `central-publishing-maven-plugin`，并将 `junit-jupiter` 升级到更稳妥的小版本带。
2. Maven 编译配置收口到 Java 8 `release` 目标，在较新 JDK 上构建时保持 Java 8 兼容产物。
3. 新增 GitHub Actions CI matrix，覆盖 JDK `8 / 11 / 17 / 21`，并在矩阵中执行 `clean verify` 与覆盖率检查。
4. README 补充兼容性矩阵、本地构建说明与最低支持 Java 版本说明。

## [1.1.0] - 2026-02-19

1. 新增 `RetryPolicy`：支持 scope 默认重试与任务级重试覆盖，可直接通过 `ThreadScope.submit(...)` 配置失败重试。
2. 新增任务级超时：提供 `Per-Task Timeout` 能力（`TaskTimeoutException`），与 scope 级 deadline 形成互补。
3. 新增上下文传播：平台线程与虚拟线程均支持自动上下文传递，覆盖调度任务与嵌套提交流程。
4. 新增 OpenTelemetry 集成：支持 `withOpenTelemetry(...)` 任务生命周期追踪，同时保持 OTel 依赖为可选。
5. 新增任务优先级调度：引入 `TaskPriority` 与 `Scheduler.priority(...)`，并完成 `ThreadScope` 精简与 internal 分包重构（保持 public API 兼容）。

## [1.0.2] - 2026-02-13

- `1.0.2` 维护发布。

## [1.0.1] - 2026-02-10

- `1.0.1` 维护发布。
