package io.threadforge;

/**
 * Raised when operating on a closed channel that has no remaining items.
 */
public class ChannelClosedException extends RuntimeException {

    public ChannelClosedException(String message) {
        super(message);
    }
}
