package io.threadforge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务执行策略抽象。
 *
 * <p>{@code Scheduler} 封装了底层 {@link ExecutorService} 的来源与生命周期归属，
 * 让 {@link ThreadScope} 能在不同 JDK/场景下统一调度行为。
 *
 * <p>该类型为线程安全且不可变对象。
 */
public final class Scheduler {

    private static final Scheduler COMMON_POOL = new Scheduler(ForkJoinPool.commonPool(), false, "commonPool", false);
    private static volatile Scheduler SHARED_VIRTUAL_THREADS;

    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final String name;
    private final boolean virtualThreadMode;

    /**
     * 私有构造函数，按“执行器 + 归属 + 标识”构建调度器实例。
     */
    private Scheduler(ExecutorService executor, boolean ownsExecutor, String name, boolean virtualThreadMode) {
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.name = name;
        this.virtualThreadMode = virtualThreadMode;
    }

    /**
     * 返回公共 ForkJoin 调度器包装。
     *
     * <p>该调度器不由 scope 关闭。
     */
    public static Scheduler commonPool() {
        return COMMON_POOL;
    }

    /**
     * 创建固定大小线程池调度器。
     *
     * <p>返回的调度器拥有执行器所有权，scope 关闭时会一并关闭。
     *
     * <p>示例：
     * <pre>{@code
     * Scheduler scheduler = Scheduler.fixed(8);
     * ThreadScope scope = ThreadScope.open().withScheduler(scheduler);
     * }</pre>
     */
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

    /**
     * 创建支持优先级队列的固定大小线程池调度器。
     *
     * <p>仅当任务以 {@link PrioritizedRunnable} 提交时生效（ThreadScope 会自动处理）。
     */
    public static Scheduler priority(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            size,
            size,
            60L,
            TimeUnit.SECONDS,
            new PriorityBlockingQueue<Runnable>(),
            new NamedThreadFactory("threadforge-priority"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return new Scheduler(executor, true, "priority(" + size + ")", false);
    }

    /**
     * 基于外部执行器创建调度器包装。
     *
     * <p>生命周期由调用方管理，scope 不会关闭该执行器。
     */
    public static Scheduler from(ExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        return new Scheduler(executor, false, "external", false);
    }

    /**
     * 获取共享虚拟线程调度器。
     *
     * <p>若当前 JDK 不支持虚拟线程，则自动回退到 {@link #commonPool()}。
     */
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

    /**
     * 自动检测并返回当前环境推荐调度器。
     *
     * <p>优先虚拟线程，不支持时回退 commonPool。
     */
    public static Scheduler detect() {
        if (isVirtualThreadSupported()) {
            return virtualThreads();
        }
        return commonPool();
    }

    /**
     * 判断当前 JDK 是否支持虚拟线程执行器。
     *
     * <p>通过反射探测 {@code Executors.newVirtualThreadPerTaskExecutor()}。
     */
    public static boolean isVirtualThreadSupported() {
        try {
            Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return method != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * 调度器名称（用于日志/观测）。
     */
    public String name() {
        return name;
    }

    /**
     * 是否运行在虚拟线程模式。
     */
    public boolean isVirtualThreadMode() {
        return virtualThreadMode;
    }

    /**
     * 返回底层执行器（包级，供框架内部调度使用）。
     */
    ExecutorService executor() {
        return executor;
    }

    /**
     * 当调度器拥有执行器所有权时，关闭执行器。
     */
    void shutdownIfOwned() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    static Runnable prioritized(Runnable runnable, TaskPriority taskPriority, long sequence) {
        return new PrioritizedRunnable(runnable, taskPriority, sequence);
    }

    /**
     * 尝试通过反射创建虚拟线程执行器。
     */
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

    /**
     * 固定线程池线程命名工厂，便于排查线程来源。
     */
    private static final class NamedThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicInteger id;

        /**
         * 使用给定前缀构造工厂。
         */
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

    private static final class PrioritizedRunnable implements Runnable, Comparable<PrioritizedRunnable> {
        private final Runnable delegate;
        private final TaskPriority taskPriority;
        private final long sequence;

        private PrioritizedRunnable(Runnable delegate, TaskPriority taskPriority, long sequence) {
            this.delegate = delegate;
            this.taskPriority = taskPriority;
            this.sequence = sequence;
        }

        @Override
        public void run() {
            delegate.run();
        }

        @Override
        public int compareTo(PrioritizedRunnable other) {
            int byPriority = Integer.compare(this.taskPriority.rank(), other.taskPriority.rank());
            if (byPriority != 0) {
                return byPriority;
            }
            return Long.compare(this.sequence, other.sequence);
        }
    }
}
