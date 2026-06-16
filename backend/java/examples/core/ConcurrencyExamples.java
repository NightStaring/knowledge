/**
 * 并发编程示例
 * ==============
 *
 * 演示：
 *   1. 线程池配置与优雅关闭
 *   2. CompletableFuture 异步编排
 *   3. Lock + Condition 生产者消费者
 *   4. CountDownLatch / CyclicBarrier / Semaphore
 */

package com.example.core;

import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.*;

// ================================================================
// 1. 线程池工厂
// ================================================================
class ThreadPoolFactory {

    /**
     * CPU 密集型线程池
     * 线程数 = CPU 核数 + 1
     */
    static ThreadPoolExecutor cpuBoundPool() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            cores + 1,
            cores + 1,
            0L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadFactory("cpu-pool")
        );
    }

    /**
     * IO 密集型线程池
     * 线程数 = CPU 核数 × 2
     */
    static ThreadPoolExecutor ioBoundPool() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            cores * 2,
            cores * 4,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000),
            new ThreadFactory("io-pool"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Spring 风格的线程池工厂
     */
    static class ThreadFactory implements java.util.concurrent.ThreadFactory {
        private final String namePrefix;
        private final AtomicLong count = new AtomicLong(1);

        ThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName(namePrefix + "-" + count.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}

// ================================================================
// 2. Lock + Condition 生产者消费者
// ================================================================
class BoundedBuffer<T> {

    private final Queue<T> queue = new LinkedList<>();
    private final int capacity;
    private final Lock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public BoundedBuffer(int capacity) {
        this.capacity = capacity;
    }

    /**
     * 生产者：放入数据
     */
    void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (queue.size() == capacity) {
                notFull.await();  // 队列满，等待
            }
            queue.add(item);
            notEmpty.signal();  // 通知消费者
        } finally {
            lock.unlock();
        }
    }

    /**
     * 消费者：取出数据
     */
    T take() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty()) {
                notEmpty.await();  // 队列空，等待
            }
            T item = queue.poll();
            notFull.signal();  // 通知生产者
            return item;
        } finally {
            lock.unlock();
        }
    }
}

// ================================================================
// 3. CompletableFuture 异步编排
// ================================================================
class AsyncService {

    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    /**
     * 并行查询多个服务，全部完成返回
     */
    <T> CompletableFuture<List<T>> parallelQuery(List<CompletableFuture<T>> futures) {
        return CompletableFuture
            .allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .toList())
            .orTimeout(10, TimeUnit.SECONDS)
            .exceptionally(ex -> List.of());
    }

    /**
     * 有依赖的异步编排
     *
     * 场景：先查用户信息 → 再查用户订单 → 最后格式化
     */
    CompletableFuture<String> getOrderSummary(Long userId) {
        return CompletableFuture
            .supplyAsync(() -> getUser(userId), executor)
            .thenComposeAsync(user -> getOrders(user.id()), executor)
            .thenApplyAsync(orders -> formatSummary(orders), executor)
            .exceptionally(ex -> "获取失败: " + ex.getMessage());
    }

    /**
     * 超时控制
     */
    <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future,
                                          long timeout, TimeUnit unit) {
        return future
            .orTimeout(timeout, unit)
            .exceptionally(ex -> null);
    }

    private User getUser(Long id) { return new User(id, "alice"); }
    private CompletableFuture<List<Order>> getOrders(Long userId) {
        return CompletableFuture.completedFuture(List.of());
    }
    private String formatSummary(List<Order> orders) { return "summary"; }

    record User(Long id, String name) {}
    record Order(Long id, BigDecimal amount) {}
}

// ================================================================
// 4. 并发工具类
// ================================================================
class ConcurrencyToolsDemo {

    /**
     * CountDownLatch：等待 N 个线程完成
     */
    static void demonstrateCountDownLatch() throws InterruptedException {
        int taskCount = 5;
        CountDownLatch latch = new CountDownLatch(taskCount);
        ExecutorService executor = Executors.newFixedThreadPool(5);

        for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> {
                try {
                    // 执行任务
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();  // 任务完成
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);  // 等待所有任务完成
        System.out.println("所有任务完成");
        executor.shutdown();
    }

    /**
     * Semaphore：限流
     */
    static void demonstrateSemaphore() {
        int maxConcurrent = 5;
        Semaphore semaphore = new Semaphore(maxConcurrent);
        ExecutorService executor = Executors.newFixedThreadPool(10);

        for (int i = 0; i < 20; i++) {
            executor.submit(() -> {
                try {
                    semaphore.acquire();
                    // 最多 5 个线程同时访问
                    accessResource();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    semaphore.release();
                }
            });
        }
        executor.shutdown();
    }

    /**
     * ReadWriteLock：读多写少场景
     */
    static class Cache<K, V> {
        private final Map<K, V> map = new HashMap<>();
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        V get(K key) {
            lock.readLock().lock();
            try {
                return map.get(key);
            } finally {
                lock.readLock().unlock();
            }
        }

        void put(K key, V value) {
            lock.writeLock().lock();
            try {
                map.put(key, value);
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    private static void accessResource() {
        // 模拟访问资源
    }
}
