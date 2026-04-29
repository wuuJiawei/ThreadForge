package io.threadforge.examples;

import io.threadforge.ThreadScope;

import java.util.List;

public final class QuorumVoteExample {

    private QuorumVoteExample() {
    }

    public static void main(String[] args) {
        try (ThreadScope scope = ThreadScope.open()) {
            List<String> quorum = scope.joiner().quorum(
                2,
                () -> {
                    Thread.sleep(30L);
                    return "replica-a:ok";
                },
                () -> {
                    Thread.sleep(45L);
                    return "replica-b:ok";
                },
                () -> {
                    Thread.sleep(60L);
                    throw new IllegalStateException("replica-c stale");
                }
            );

            System.out.println("quorum=" + quorum);
        } catch (Exception exception) {
            throw new RuntimeException("Quorum example failed", exception);
        }
    }
}
