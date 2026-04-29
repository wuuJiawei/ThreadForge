package io.threadforge.benchmarks;

import io.threadforge.Task;
import io.threadforge.ThreadScope;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class FanOutAggregationBenchmark {

    private ExecutorService executor;
    private List<Integer> inputs;

    @Setup
    public void setup() {
        this.executor = Executors.newFixedThreadPool(8);
        this.inputs = new ArrayList<Integer>();
        for (int index = 0; index < 8; index++) {
            inputs.add(Integer.valueOf(index + 1));
        }
    }

    @TearDown
    public void tearDown() {
        executor.shutdownNow();
    }

    @Benchmark
    public int threadForgeAwaitAll() {
        try (ThreadScope scope = ThreadScope.open()) {
            List<Task<Integer>> tasks = new ArrayList<Task<Integer>>();
            for (final Integer input : inputs) {
                tasks.add(scope.submit(() -> compute(input.intValue())));
            }
            List<Integer> results = scope.awaitAll(tasks);
            return sum(results);
        }
    }

    @Benchmark
    public int executorInvokeAll() throws Exception {
        List<java.util.concurrent.Callable<Integer>> callables = new ArrayList<java.util.concurrent.Callable<Integer>>();
        for (final Integer input : inputs) {
            callables.add(() -> compute(input.intValue()));
        }
        List<Future<Integer>> futures = executor.invokeAll(callables);
        int total = 0;
        for (Future<Integer> future : futures) {
            total += future.get().intValue();
        }
        return total;
    }

    @Benchmark
    public int completableFutureAllOf() {
        List<CompletableFuture<Integer>> futures = new ArrayList<CompletableFuture<Integer>>();
        for (final Integer input : inputs) {
            futures.add(CompletableFuture.supplyAsync(() -> Integer.valueOf(compute(input.intValue())), executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        int total = 0;
        for (CompletableFuture<Integer> future : futures) {
            total += future.join().intValue();
        }
        return total;
    }

    private static int compute(int input) {
        int result = input;
        for (int i = 0; i < 256; i++) {
            result = (result * 31) ^ i;
        }
        return result;
    }

    private static int sum(List<Integer> values) {
        int total = 0;
        for (Integer value : values) {
            total += value.intValue();
        }
        return total;
    }
}
