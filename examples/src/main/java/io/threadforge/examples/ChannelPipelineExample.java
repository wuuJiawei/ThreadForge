package io.threadforge.examples;

import io.threadforge.Channel;
import io.threadforge.Task;
import io.threadforge.ThreadScope;

public final class ChannelPipelineExample {

    private ChannelPipelineExample() {
    }

    public static void main(String[] args) {
        try (ThreadScope scope = ThreadScope.open()) {
            final Channel<String> channel = Channel.bounded(4);

            scope.submit("producer", () -> {
                channel.send("alpha");
                channel.send("beta");
                channel.send("gamma");
                channel.close();
                return null;
            });

            Task<Integer> totalLength = scope.submit("consumer", () -> {
                int total = 0;
                for (String value : channel) {
                    total += value.length();
                }
                return total;
            });

            System.out.println("totalLength=" + totalLength.await());
        } catch (Exception exception) {
            throw new RuntimeException("Channel pipeline example failed", exception);
        }
    }
}
