package io.threadforge;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded blocking channel inspired by Go channels.
 * Thread-safe for multiple producers and consumers.
 */
public final class Channel<T> implements Iterable<T> {

    private final int capacity;
    private final Deque<T> queue;
    private final ReentrantLock lock;
    private final Condition notEmpty;
    private final Condition notFull;
    private volatile boolean closed;

    private Channel(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.queue = new ArrayDeque<T>(capacity);
        this.lock = new ReentrantLock();
        this.notEmpty = lock.newCondition();
        this.notFull = lock.newCondition();
    }

    public static <T> Channel<T> bounded(int capacity) {
        return new Channel<T>(capacity);
    }

    /**
     * Sends one value into the channel, blocking while the buffer is full.
     * Throws {@link ChannelClosedException} if the channel has been closed.
     */
    public void send(T value) {
        lock.lock();
        try {
            while (queue.size() >= capacity && !closed) {
                awaitUninterruptibly(notFull);
            }
            if (closed) {
                throw new ChannelClosedException("Channel is closed");
            }
            queue.addLast(value);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Receives one value from the channel, blocking while empty.
     * When channel is closed and drained, throws {@link ChannelClosedException}.
     */
    public T receive() {
        lock.lock();
        try {
            while (queue.isEmpty() && !closed) {
                awaitUninterruptibly(notEmpty);
            }
            if (queue.isEmpty() && closed) {
                throw new ChannelClosedException("Channel is closed and drained");
            }
            T value = queue.removeFirst();
            notFull.signal();
            return value;
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
            closed = true;
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {
            private T next;
            private boolean loaded;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                if (exhausted) {
                    return false;
                }
                if (loaded) {
                    return true;
                }
                try {
                    next = receive();
                    loaded = true;
                    return true;
                } catch (ChannelClosedException e) {
                    exhausted = true;
                    return false;
                }
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                loaded = false;
                return next;
            }
        };
    }

    private static void awaitUninterruptibly(Condition condition) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    condition.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
