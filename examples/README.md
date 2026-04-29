# ThreadForge Examples

This directory contains runnable, business-style examples for common ThreadForge usage patterns.

## Prerequisite

Because the repository `main` branch is ahead of Maven Central `1.1.2`, install the local core artifact first:

```bash
mvn -B -ntp -DskipTests install
```

Run that command from the repository root.

## Compile All Examples

```bash
mvn -B -ntp -f examples/pom.xml compile
```

## Run Examples

Parallel fan-out aggregation:

```bash
mvn -B -ntp -f examples/pom.xml exec:java -Dexec.mainClass=io.threadforge.examples.ParallelAggregationExample
```

First-success fallback across providers:

```bash
mvn -B -ntp -f examples/pom.xml exec:java -Dexec.mainClass=io.threadforge.examples.FirstSuccessFallbackExample
```

Quorum voting across replicas:

```bash
mvn -B -ntp -f examples/pom.xml exec:java -Dexec.mainClass=io.threadforge.examples.QuorumVoteExample
```

Channel-based producer/consumer pipeline:

```bash
mvn -B -ntp -f examples/pom.xml exec:java -Dexec.mainClass=io.threadforge.examples.ChannelPipelineExample
```

## Coverage Map

- `ParallelAggregationExample`: fan-out / fan-in with deadline and metrics
- `FirstSuccessFallbackExample`: `ScopeJoiner.firstSuccess(...)`
- `QuorumVoteExample`: `ScopeJoiner.quorum(...)`
- `ChannelPipelineExample`: bounded `Channel<T>` with producer and consumer tasks
