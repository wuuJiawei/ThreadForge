# 更新日志

本文件记录项目的所有重要变更。

规则：按时间倒序记录（最新版本在最上方）。

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
