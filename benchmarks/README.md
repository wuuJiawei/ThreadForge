# ThreadForge Benchmarks

This directory contains JMH benchmarks for comparing ThreadForge with baseline Java concurrency patterns.

## Prerequisite

Install the local core artifact from the repository root first:

```bash
mvn -B -ntp -DskipTests install
```

## Build the Benchmark Jar

```bash
mvn -B -ntp -f benchmarks/pom.xml -DskipTests package
```

This produces:

```bash
benchmarks/target/benchmarks.jar
```

## Run a Quick Smoke Benchmark

```bash
java -jar benchmarks/target/benchmarks.jar ".*FanOut.*" -wi 1 -i 1 -f 1 -r 1s -w 1s
```

## Included Benchmarks

- `FanOutAggregationBenchmark`
  - `threadForgeAwaitAll`
  - `executorInvokeAll`
  - `completableFutureAllOf`
- `FirstSuccessBenchmark`
  - `threadForgeFirstSuccess`
  - `executorInvokeAny`

## What These Benchmarks Measure

- fan-out / fan-in overhead
- joiner-based first-success orchestration
- comparison against plain `ExecutorService` and `CompletableFuture` flows

These benchmarks are for relative comparison inside the same machine profile. Do not compare raw numbers across different laptops or CI runners without normalization.
