package io.threadforge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Execution strategy abstraction.
 * This type is thread-safe and immutable.
 */
public final class Scheduler {

    private static final Scheduler COMMON_POOL = new Scheduler(ForkJoinPool.commonPool(), false, "commonPool", false);
    private static volatile Scheduler SHARED_VIRTUAL_THREADS;

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final String name;
    private final boolean virtualThreadMode;

    private Scheduler(ExecutorService executor, boolean ownsExecutor, String name, boolean virtualThreadMode) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.name = name;
        this.virtualThreadMode = virtualThreadMode;
    }

    public static Scheduler commonPool() {
        return COMMON_POOL;
    }

    public static Scheduler fixed(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        int queueCapacity = Math.max(256, size * 100);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            size,
            size,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(queueCapacity),
            new NamedThreadFactory("threadforge-fixed"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return new Scheduler(executor, true, "fixed(" + size + ")", false);
    }

    public static Scheduler from(ExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        return new Scheduler(executor, false, "external", false);
    }

    public static Scheduler virtualThreads() {
        Scheduler scheduler = SHARED_VIRTUAL_THREADS;
        if (scheduler != null) {
            return scheduler;
        }

        synchronized (Scheduler.class) {
            scheduler = SHARED_VIRTUAL_THREADS;
            if (scheduler != null) {
                return scheduler;
            }

            ExecutorService executor = tryCreateVirtualThreadExecutor();
            if (executor == null) {
                return commonPool();
            }

            scheduler = new Scheduler(executor, false, "virtualThreads", true);
            SHARED_VIRTUAL_THREADS = scheduler;
            return scheduler;
        }
    }

    public static Scheduler detect() {
        if (isVirtualThreadSupported()) {
            return virtualThreads();
        }
        return commonPool();
    }

    public static boolean isVirtualThreadSupported() {
        try {
            Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return method != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    public String name() {
        return name;
    }

    public boolean isVirtualThreadMode() {
        return virtualThreadMode;
    }

    ExecutorService executor() {
        return executor;
    }

    void shutdownIfOwned() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private static ExecutorService tryCreateVirtualThreadExecutor() {
        try {
            Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            Object result = method.invoke(null);
            if (result instanceof ExecutorService) {
                return (ExecutorService) result;
            }
        } catch (NoSuchMethodException e) {
            return null;
        } catch (IllegalAccessException e) {
            return null;
        } catch (InvocationTargetException e) {
            return null;
        }
        return null;
    }

    private static final class NamedThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger id;

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
            this.id = new AtomicInteger(1);
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + id.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
