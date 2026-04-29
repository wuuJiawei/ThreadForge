package io.threadforge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeJoinerTest {

    @Test
    void firstSuccessReturnsFastestSuccessfulResultAndCancelsLosers() throws Exception {
        final CountDownLatch slowStarted = new CountDownLatch(1);
        final CountDownLatch slowCancelled = new CountDownLatch(1);

        try (ThreadScope scope = ThreadScope.open()) {
            String result = scope.joiner().firstSuccess(
                new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        slowStarted.countDown();
                        try {
                            Thread.sleep(1000L);
                        } catch (InterruptedException interruptedException) {
                            slowCancelled.countDown();
                            throw interruptedException;
                        }
                        return "slow";
                    }
                },
                new Callable<String>() {
                    @Override
                    public String call() {
                        return "fast";
                    }
                }
            );

            assertEquals("fast", result);
            assertTrue(slowStarted.await(1L, TimeUnit.SECONDS));
            assertTrue(slowCancelled.await(1L, TimeUnit.SECONDS));
        }
    }

    @Test
    void quorumReturnsRequiredSuccessfulResults() {
        try (ThreadScope scope = ThreadScope.open()) {
            List<Integer> result = scope.join(
                JoinStrategy.<Integer>quorum(2),
                Arrays.<Callable<Integer>>asList(
                    new Callable<Integer>() {
                        @Override
                        public Integer call() {
                            return 1;
                        }
                    },
                    new Callable<Integer>() {
                        @Override
                        public Integer call() {
                            return 2;
                        }
                    },
                    new Callable<Integer>() {
                        @Override
                        public Integer call() throws Exception {
                            Thread.sleep(500L);
                            return 3;
                        }
                    }
                )
            );

            List<Integer> sorted = Arrays.asList(result.get(0), result.get(1));
            Collections.sort(sorted);
            assertEquals(Arrays.asList(1, 2), sorted);
        }
    }

    @Test
    void quorumFailsWhenSuccessThresholdBecomesImpossible() {
        try (ThreadScope scope = ThreadScope.open()) {
            AggregateException error = assertThrows(AggregateException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    scope.joiner().quorum(
                        2,
                        new Callable<Integer>() {
                            @Override
                            public Integer call() {
                                return 1;
                            }
                        },
                        new Callable<Integer>() {
                            @Override
                            public Integer call() {
                                throw new IllegalStateException("boom-a");
                            }
                        },
                        new Callable<Integer>() {
                            @Override
                            public Integer call() {
                                throw new IllegalStateException("boom-b");
                            }
                        }
                    );
                }
            });

            assertTrue(error.getMessage().contains("Quorum not reached"));
            assertEquals(2, error.failures().size());
        }
    }

    @Test
    void hedgedStartsBackupAfterDelayAndUsesFirstSuccessfulResult() throws Exception {
        final AtomicLong primaryStartedAt = new AtomicLong();
        final AtomicLong backupStartedAt = new AtomicLong();
        final AtomicBoolean primaryInterrupted = new AtomicBoolean(false);
        final CountDownLatch primaryCancelled = new CountDownLatch(1);

        try (ThreadScope scope = ThreadScope.open()) {
            String result = scope.joiner().hedged(
                Duration.ofMillis(80),
                new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        primaryStartedAt.set(System.nanoTime());
                        try {
                            Thread.sleep(1000L);
                        } catch (InterruptedException interruptedException) {
                            primaryInterrupted.set(true);
                            primaryCancelled.countDown();
                            throw interruptedException;
                        }
                        return "primary";
                    }
                },
                new Callable<String>() {
                    @Override
                    public String call() {
                        backupStartedAt.set(System.nanoTime());
                        return "backup";
                    }
                }
            );

            assertEquals("backup", result);
            assertTrue(primaryCancelled.await(1L, TimeUnit.SECONDS));
            assertTrue(primaryInterrupted.get());
            long delayMillis = TimeUnit.NANOSECONDS.toMillis(backupStartedAt.get() - primaryStartedAt.get());
            assertTrue(delayMillis >= 50L, "expected hedge delay before backup launch, got " + delayMillis + " ms");
        }
    }

    @Test
    void joinStrategyValidatesArguments() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                JoinStrategy.quorum(0);
            }
        });

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                JoinStrategy.hedged(Duration.ZERO);
            }
        });

        try (ThreadScope scope = ThreadScope.open()) {
            assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    scope.joiner().hedged(Duration.ofMillis(10), new Callable<String>() {
                        @Override
                        public String call() {
                            return "only-one";
                        }
                    });
                }
            });
        }
    }
}
