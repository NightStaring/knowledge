/**
 * 集合 + Stream + 并发 综合示例
 * ==============================
 *
 * 演示：
 *   1. HashMap 工作原理（put/get/扩容）
 *   2. ConcurrentHashMap 线程安全操作
 *   3. Stream 链式处理
 *   4. Optional 安全取值
 */

package com.example.basics;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

// ================================================================
// 1. HashMap 工作原理演示
// ================================================================
class HashMapDemo {

    /**
     * 演示 HashMap 的核心行为
     *
     * 容量计算：传入 7，实际容量是 8（2 的幂）
     * 扩容时机：size > capacity × 0.75
     * 红黑树：链表长度 >= 8 且数组长度 >= 64
     */
    static void demonstrateHashMapBehavior() {
        // 🟢 预分配容量：预估数量 / 0.75 + 1
        // 要存 100 个元素，预分配 135
        Map<String, Integer> map = new HashMap<>(135);

        // put 流程：
        //   1. hash(key) = key.hashCode() ^ (key >>> 16)
        //   2. index = hash & (capacity - 1)
        //   3. 该位置为空 → 直接放入
        //   4. 有数据 → 判断 key.equals()
        //      相等 → 覆盖
        //      不等 → 链表/红黑树追加
        map.put("apple", 1);
        map.put("banana", 2);

        // get 流程：
        //   1. 计算 hash
        //   2. 定位数组位置
        //   3. 比较 key（先 == 再 equals）
        Integer value = map.get("apple");  // 1

        // 🔴 常见坑：用可变对象做 key
        StringBuilder sb = new StringBuilder("key");
        Map<StringBuilder, String> badMap = new HashMap<>();
        badMap.put(sb, "value");
        sb.append("-changed");  // hashCode 变了！
        // badMap.get(sb) → null！找不到了

        // 🟢 用不可变对象做 key
        Map<String, String> goodMap = new HashMap<>();
        goodMap.put("key", "value");
    }

    /**
     * 演示 ConcurrentHashMap 的线程安全操作
     */
    static void demonstrateConcurrentMap() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();

        // JDK 8 实现：CAS + synchronized(单个 slot)
        // 不像 Hashtable 锁整个表

        // ✅ 原子操作
        map.putIfAbsent("key", "value");        // 不存在才放
        map.computeIfAbsent("key", k -> {       // 不存在则计算
            return fetchFromDB(k);
        });
        map.merge("key", "new", (old, v) -> old + "," + v);

        // ❌ 非原子检查-设置（可能被其他线程覆盖）
        if (!map.containsKey("key")) {
            map.put("key", "value");
        }
    }

    private static String fetchFromDB(String key) {
        return "db_value";
    }

    /**
     * 实现 LRU 缓存
     */
    static class LRUCache<K, V> extends LinkedHashMap<K, V> {
        private final int maxCapacity;

        public LRUCache(int maxCapacity) {
            super(maxCapacity, 0.75f, true);  // accessOrder=true
            this.maxCapacity = maxCapacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxCapacity;
        }
    }
}

// ================================================================
// 2. Stream 链式处理
// ================================================================
class StreamDemo {

    static record Order(Long id, String userId, String status, BigDecimal amount) {}

    static void demonstrateStreamOperations() {
        List<Order> orders = List.of(
            new Order(1L, "user1", "PAID", new BigDecimal("100")),
            new Order(2L, "user2", "PENDING", new BigDecimal("200")),
            new Order(3L, "user1", "PAID", new BigDecimal("300"))
        );

        // 典型链式处理
        Map<String, List<Order>> paidOrdersByUser = orders.stream()
            .filter(o -> "PAID".equals(o.status()))          // 过滤
            .collect(Collectors.groupingBy(Order::userId));   // 分组

        // 分组聚合
        Map<String, Long> countByStatus = orders.stream()
            .collect(Collectors.groupingBy(
                Order::status,
                Collectors.counting()
            ));

        // 分组求和
        Map<String, BigDecimal> sumByUser = orders.stream()
            .collect(Collectors.groupingBy(
                Order::userId,
                Collectors.mapping(
                    Order::amount,
                    Collectors.reducing(BigDecimal.ZERO, BigDecimal::add)
                )
            ));

        // flatMap 展平
        List<List<Integer>> nested = List.of(List.of(1, 2), List.of(3, 4));
        List<Integer> flat = nested.stream()
            .flatMap(Collection::stream)
            .toList();  // [1, 2, 3, 4]
    }

    /**
     * 安全取值：Optional 链式调用
     */
    static void demonstrateOptional() {
        // 多层 null 检查
        String city = Optional.ofNullable(getUser())
            .map(User::getAddress)
            .map(Address::getCity)
            .filter(c -> c.length() > 2)
            .orElse("unknown");

        // orElse 与 orElseGet 的区别
        String val1 = Optional.of("hello")
            .orElse(computeDefault());      // computeDefault() 总是执行
        String val2 = Optional.of("hello")
            .orElseGet(() -> computeDefault());  // 只有为空时才执行
    }

    static User getUser() { return null; }
    static String computeDefault() { return "default"; }
    static class User { Address getAddress() { return null; } }
    static class Address { String getCity() { return null; } }
}

// ================================================================
// 3. CompletableFuture 异步编排
// ================================================================
class CompletableFutureDemo {

    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    /**
     * 并行调用多个服务，等待全部完成
     */
    List<String> parallelQuery() {
        List<CompletableFuture<String>> futures = List.of(
            CompletableFuture.supplyAsync(() -> callServiceA(), executor),
            CompletableFuture.supplyAsync(() -> callServiceB(), executor),
            CompletableFuture.supplyAsync(() -> callServiceC(), executor)
        );

        return CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        ).thenApply(v ->
            futures.stream()
                .map(CompletableFuture::join)
                .toList()
        ).orTimeout(5, TimeUnit.SECONDS)  // 5 秒超时
         .exceptionally(ex -> List.of("fallback"))
         .join();
    }

    /**
     * 有依赖的异步调用
     */
    CompletableFuture<String> chainedQuery() {
        return CompletableFuture
            .supplyAsync(() -> getUser(1L), executor)     // 1. 先查用户
            .thenComposeAsync(user -> getOrders(user), executor)  // 2. 再查订单
            .thenApplyAsync(orders -> formatResult(orders), executor)  // 3. 格式化
            .exceptionally(ex -> "处理失败: " + ex.getMessage());
    }

    private String callServiceA() { return "A"; }
    private String callServiceB() { return "B"; }
    private String callServiceC() { return "C"; }
    private String getUser(Long id) { return "user"; }
    private CompletableFuture<String> getOrders(String user) {
        return CompletableFuture.completedFuture("orders");
    }
    private String formatResult(String orders) { return orders; }
}

// ================================================================
// 4. 线程池配置
// ================================================================
class ThreadPoolConfig {

    /**
     * 生产环境线程池配置
     *
     * 参数关系：
     *   corePoolSize → 常驻线程
     *   maxPoolSize  → 最大线程（队列满时创建）
     *   workQueue    → 等待队列
     *   handler      → 拒绝策略
     */
    static ThreadPoolExecutor createBizThreadPool() {
        int cpuCount = Runtime.getRuntime().availableProcessors();

        return new ThreadPoolExecutor(
            cpuCount * 2,               // corePoolSize：IO 密集型
            cpuCount * 4,               // maxPoolSize
            60L, TimeUnit.SECONDS,      // 非核心线程空闲存活
            new ArrayBlockingQueue<>(1000),  // 有界队列！
            r -> {
                Thread t = new Thread(r);
                t.setName("biz-pool-" + t.getId());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()  // 调用者线程执行
        );
    }

    /**
     * 优雅关闭
     */
    static void gracefulShutdown(ExecutorService executor) {
        executor.shutdown();  // 不再接收新任务
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();  // 强制停止
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
