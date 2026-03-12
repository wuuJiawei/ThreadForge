# ThreadForge 项目分析报告

## 📋 项目概述

**ThreadForge** 是一个 Java 结构化并发框架，让并发代码像 Go 一样简单。

> **核心理念**：先降低并发代码的认知成本，再追求吞吐与性能。

| 属性 | 值 |
|------|-----|
| **语言** | Java 8+ |
| **版本** | 1.1.2 |
| **坐标** | `pub.lighting:threadforge-core` |
| **License** | MIT |
| **状态** | 已发布到 Maven Central |

---

## ✅ 已实现功能

| 模块 | 功能 | 说明 |
|------|------|------|
| **ThreadScope** | 结构化作用域 | 统一管理任务生命周期、失败策略、超时、清理 |
| **Task** | 任务句柄 | 状态追踪 (PENDING→RUNNING→SUCCESS/FAILED/CANCELLED) |
| **FailurePolicy** | 失败策略 | FAIL_FAST, COLLECT_ALL, SUPERVISOR, CANCEL_OTHERS, IGNORE_ALL |
| **CancellationToken** | 协作式取消 | 支持主动检查取消状态 |
| **Channel** | 有界通道 | Go-style 阻塞队列，支持生产者-消费者模式 |
| **Scheduler** | 执行策略 | 自动检测虚拟线程 (JDK 21+)，兼容旧版本 |
| **DelayScheduler** | 延迟/周期任务 | schedule, scheduleAtFixedRate, scheduleWithFixedDelay |
| **ThreadHook** | 生命周期观测 | onStart/onSuccess/onFailure/onCancel 回调 |
| **ScopeMetricsSnapshot** | 内置指标 | started/succeeded/failed/cancelled 计数 + 耗时统计 |
| **组合式 API** | 链式调用 | thenApply / thenCompose / exceptionally |

---

## 🎯 可实现的 Feature 建议

> **设计原则**：所有新 Feature 必须保持简洁，确保易用性是最优先的。  
> 框架的目标是让 Java 能写出 Go 一样简单的多线程并发代码。

### 高优先级

| # | Feature | 理由 | 完成状态 |
|---|---------|------|------|
| 1 | **Retry Policy** | 失败重试是常见需求，当前需手动实现 | [x]  |
| 2 | **Per-Task Timeout** | 目前只有 scope 级别 deadline，需要任务级别超时 | [x]  |
| 3 | **Context Propagation** | 虚拟线程下更好的上下文传递机制 | [x]  |
| 4 | **OpenTelemetry 集成** | 可观测性增强，兼容分布式追踪 | [x]  |

### 中优先级

| # | Feature | 理由 | 完成状态 |
|---|---------|------|------|
| 5 | **Circuit Breaker** | 熔断器模式，防止故障级联 | [ ]  |
| 6 | **Stream 集成** | `TaskStream` 支持并行流式处理 | [ ]  |
| 7 | **Bulkhead Isolation** | 舱壁模式，资源隔离增强 | [ ]  |
| 8 | **Task Priority** | 优先级队列，重要任务优先执行 | [x]  |

### 低优先级 / 实验性

| # | Feature | 理由 |
|---|---------|------|
| 9 | **Async/Await 风格 API** | 类似 C# 的 async/await 语法糖 |
| 10 | **Coroutine-style Suspend** | Kotlin coroutine 风格的 suspend 函数 |
| 11 | **Reactive Streams (Flow)** | 响应式流兼容 |
| 12 | **Checkpoint/Resume** | 长任务状态持久化 |

---

## 🏗️ 技术债务 / 改进点

1. **测试覆盖** - JaCoCo 要求 80% 覆盖率，可增加更多边界测试
2. **Benchmark** - 缺少性能基准测试
3. **示例项目** - 缺少完整的使用示例项目
