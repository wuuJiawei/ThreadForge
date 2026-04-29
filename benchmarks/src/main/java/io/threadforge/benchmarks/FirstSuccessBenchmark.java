package io.threadforge.benchmarks;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class FirstSuccessBenchmark {

    private ExecutorService executor;

    @Setup
    public void setup() {
        this.executor = Executors.newFixedThreadPool(4);
    }

    @TearDown
    public void tearDown() {
        executor.shutdownNow();
    }

    @Benchmark
    public String threadForgeFirstSuccess() {
        try (ThreadScope scope = ThreadScope.open()) {
            return scope.joiner().firstSuccess(
                () -> slowCompute("primary", 1200),
                () -> {
                    throw new IllegalStateException("transient failure");
                },
                () -> slowCompute("backup", 64)
            );
        }
    }

    @Benchmark
    public String executorInvokeAny() throws Exception {
        List<java.util.concurrent.Callable<String>> callables = new ArrayList<java.util.concurrent.Callable<String>>();
        callables.add(() -> slowCompute("primary", 1200));
        callables.add(() -> {
            throw new IllegalStateException("transient failure");
        });
        callables.add(() -> slowCompute("backup", 64));
        return executor.invokeAny(callables);
    }

    private static String slowCompute(String value, int rounds) {
        int state = value.length();
        for (int i = 0; i < rounds; i++) {
            state = (state * 33) ^ i;
        }
        return value + ":" + state;
    }
}
