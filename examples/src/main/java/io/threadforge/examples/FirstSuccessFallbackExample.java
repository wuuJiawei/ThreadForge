package io.threadforge.examples;

import io.threadforge.ThreadScope;

public final class FirstSuccessFallbackExample {

    private FirstSuccessFallbackExample() {
    }

    public static void main(String[] args) {
        try (ThreadScope scope = ThreadScope.open()) {
            String winner = scope.joiner().firstSuccess(
                () -> {
                    Thread.sleep(120L);
                    return "provider-a";
                },
                () -> {
                    Thread.sleep(40L);
                    throw new IllegalStateException("provider-b failed");
                },
                () -> {
                    Thread.sleep(70L);
                    return "provider-c";
                }
            );

            System.out.println("winner=" + winner);
        } catch (Exception exception) {
            throw new RuntimeException("First-success example failed", exception);
        }
    }
}
