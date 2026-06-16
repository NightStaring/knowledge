# 并发编程

> 锁、线程池、CompletableFuture、并发容器——企业级并发实践。

---

## 1. 线程基础

### 1.1 线程状态

```
NEW → RUNNABLE → BLOCKED → WAITING → TIMED_WAITING → TERMINATED
```

| 状态 | 说明 | 触发 |
|------|------|------|
| NEW | 创建未启动 | `new Thread()` |
| RUNNABLE | 运行中 / 可运行 | `start()` |
| BLOCKED | 等待锁 | `synchronized` 未获取到 |
| WAITING | 等待通知 | `wait()`、`join()`、`park()` |
| TIMED_WAITING | 超时等待 | `sleep(ms)`、`wait(ms)` |
| TERMINATED | 已结束 | run() 完成 |

### 1.2 线程创建方式

```java
// 1. 继承 Thread（不推荐，Java 单继承限制）
class MyThread extends Thread {
    @Override
    public void run() { /* ... */ }
}
new MyThread().start();

// 2. 实现 Runnable（推荐）
Thread thread = new Thread(() -> System.out.println("run"));
thread.start();

// 3. Callable + Future（能返回结果）
FutureTask<String> task = new FutureTask<>(() -> "result");
new Thread(task).start();
String result = task.get();  // 阻塞等待结果

// 4. ExecutorService（生产环境，最推荐）
ExecutorService executor = Executors.newFixedThreadPool(10);
Future<String> future = executor.submit(() -> "result");
```

### 1.3 🔴 线程常用方法

```java
// sleep：让出 CPU，不释放锁
Thread.sleep(1000);  // 等待 1 秒

// wait：释放锁，等待 notify
synchronized (lock) {
    lock.wait();        // 释放 lock 锁，进入 WAITING
    lock.wait(1000);    // 超时等待
}

// notify：唤醒一个等待线程
synchronized (lock) {
    lock.notify();      // 唤醒一个
    lock.notifyAll();   // 唤醒所有
}

// yield：让出 CPU（不一定生效）
Thread.yield();  // 建议调度器让出 CPU

// join：等待线程结束
thread.join();       // 等待 thread 执行完
thread.join(1000);  // 最多等 1 秒
```

---

## 2. 锁

### 2.1 synchronized

```java
// 1. 实例方法锁（锁的是 this）
public synchronized void method() { }

// 2. 静态方法锁（锁的是 Class 对象）
public static synchronized void staticMethod() { }

// 3. 代码块锁
public void method() {
    synchronized (this) { }      // 锁对象
    synchronized (lockObj) { }   // 锁指定对象
    synchronized (Foo.class) { } // 锁 Class
}
```

### 2.2 synchronized 原理

```
JDK 6 优化后（锁升级，单向不可逆）：
无锁 → 偏向锁 → 轻量级锁（自旋锁） → 重量级锁（OS 互斥量）

偏向锁：    同一个线程重复获取，无 CAS 开销
轻量级锁：   少量线程竞争，自旋等待
重量级锁：   大量线程竞争，线程挂起（上下文切换开销大）
```

### 2.3 Lock 接口

```java
Lock lock = new ReentrantLock();
Lock lock = new ReentrantLock(true);  // 公平锁

// 基本用法
lock.lock();
try {
    // 临界区
} finally {
    lock.unlock();  // 必须在 finally 中释放！
}

// tryLock：尝试获取锁（不阻塞）
if (lock.tryLock()) {
    try {
        // 获取成功
    } finally {
        lock.unlock();
    }
} else {
    // 获取失败，做其他事
}

// tryLock 带超时
if (lock.tryLock(1, TimeUnit.SECONDS)) {
    try {
        // 1 秒内获取到锁
    } finally {
        lock.unlock();
    }
}

// lockInterruptibly：可中断的锁
lock.lockInterruptibly();  // 其他线程可中断此线程的等待
```

### 2.4 ReadWriteLock

```java
// 读读不互斥，读写互斥，写写互斥
ReadWriteLock rwLock = new ReentrantReadWriteLock();

// 读锁（多个线程可同时持有）
rwLock.readLock().lock();
try {
    cache.get(key);
} finally {
    rwLock.readLock().unlock();
}

// 写锁（独占）
rwLock.writeLock().lock();
try {
    cache.put(key, value);
} finally {
    rwLock.writeLock().unlock();
}
```

### 2.5 synchronized vs Lock

| | synchronized | Lock |
|--|-------------|------|
| 释放 | 自动（代码块结束） | 手动（finally 中 unlock） |
| 超时 | 不支持 | `tryLock(timeout)` |
| 中断 | 不支持 | `lockInterruptibly()` |
| 公平 | 非公平 | 可配置 |
| 条件 | 单个 `wait/notify` | 多个 `Condition` |
| 性能 | JDK 6+ 优化后与 Lock 相近 | 高 |
| **推荐** | 简单场景 | 复杂场景 |

### 2.6 Condition

```java
// 一个 Lock 可以有多个 Condition（比 wait/notify 灵活）
ReentrantLock lock = new ReentrantLock();
Condition notFull = lock.newCondition();
Condition notEmpty = lock.newCondition();

// 生产者
lock.lock();
try {
    while (queue.isFull()) {
        notFull.await();  // 队列满则等待
    }
    queue.add(item);
    notEmpty.signal();   // 通知消费者
} finally {
    lock.unlock();
}

// 消费者
lock.lock();
try {
    while (queue.isEmpty()) {
        notEmpty.await();  // 队列空则等待
    }
    item = queue.poll();
    notFull.signal();      // 通知生产者
} finally {
    lock.unlock();
}
```

---

## 3. volatile

### 3.1 volatile 语义

```java
// 1. 可见性：一个线程修改，其他线程立即可见
// 2. 禁止指令重排序
// ❌ 不保证原子性

// ✅ 适用：状态标志
volatile boolean running = true;

// 线程 A
running = false;  // 线程 B 立即可见

// 线程 B
while (running) {  // 能立即看到 running 的变化
    // ...
}

// ❌ 不适用：复合操作
volatile int count = 0;
count++;  // 不是原子的！相当于 read → modify → write
// 🟢 用 AtomicInteger
```

### 3.2 happens-before 规则

```
volatile 变量的写 happens-before 后续对这个变量的读
synchronized 的解锁 happens-before 后续对这个锁的加锁
线程 start() happens-before 该线程的任何操作
线程 join() happens-before 后续操作
```

---

## 4. 线程池

### 4.1 核心参数

```java
public ThreadPoolExecutor(
    int corePoolSize,      // 核心线程数（常驻）
    int maximumPoolSize,   // 最大线程数
    long keepAliveTime,    // 非核心线程空闲存活时间
    TimeUnit unit,         // 时间单位
    BlockingQueue<Runnable> workQueue,  // 任务队列
    ThreadFactory threadFactory,         // 线程工厂
    RejectedExecutionHandler handler     // 拒绝策略
);
```

### 4.2 执行流程

```
提交任务
    ↓
corePoolSize 满了吗？
  ├── 否 → 创建核心线程执行
  └── 是 → 入队
              ↓
        workQueue 满了吗？
          ├── 否 → 排队等待
          └── 是 → 创建新线程
                      ↓
                 maximumPoolSize 到了吗？
                   ├── 否 → 创建临时线程执行
                   └── 是 → 执行拒绝策略
```

### 4.3 拒绝策略

| 策略 | 说明 | 适用 |
|------|------|------|
| `AbortPolicy` | 抛 RejectedExecutionException（默认） | 关键任务 |
| `CallerRunsPolicy` | 调用者线程自己执行 | 压测/降级 |
| `DiscardPolicy` | 静默丢弃 | 不重要的任务 |
| `DiscardOldestPolicy` | 丢弃队列中最旧的任务 | 消息推送 |

### 4.4 线程池创建

```java
// ❌ Executors 的坑
Executors.newCachedThreadPool();   // 最大线程 Integer.MAX_VALUE → OOM
Executors.newFixedThreadPool(10);  // 队列无界 → OOM
Executors.newScheduledThreadPool(10);  // 同上

// 🟢 手动创建，明确参数
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    10,                 // core
    20,                 // max
    60L, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(1000),      // 有界队列！
    new ThreadFactoryBuilder().setNameFormat("biz-pool-%d").build(),
    new ThreadPoolExecutor.CallerRunsPolicy()
);

// 🟢 或使用 ThreadPoolTaskExecutor（Spring）
@Bean
public ThreadPoolTaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(10);
    executor.setMaxPoolSize(20);
    executor.setQueueCapacity(1000);
    executor.setThreadNamePrefix("biz-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);  // 优雅关闭
    executor.setAwaitTerminationSeconds(60);
    return executor;
}
```

### 4.5 参数设置建议

```java
// CPU 密集型任务：线程数 = CPU 核数 + 1
int cpuCount = Runtime.getRuntime().availableProcessors();
new ThreadPoolExecutor(
    cpuCount + 1,
    cpuCount * 2,
    60L, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(1000)
);

// IO 密集型任务：线程数 = CPU 核数 × 2
new ThreadPoolExecutor(
    cpuCount * 2,
    cpuCount * 4,
    60L, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(1000)
);

// 实际最佳线程数需要通过压测确定
// 公式：最佳线程数 = CPU 核数 × (1 + 等待时间 / 计算时间)
```

### 4.6 优雅关闭

```java
// 两步关闭
executor.shutdown();  // 不再接收新任务，等待已提交任务完成
try {
    if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
        executor.shutdownNow();  // 超时则强制停止
    }
} catch (InterruptedException e) {
    executor.shutdownNow();
}
```

---

## 5. CompletableFuture

### 5.1 基本用法

```java
// 异步执行
CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
    System.out.println("异步执行");
}, executor);

// 异步执行并返回结果
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
    return "result";
}, executor);
String result = future.get();  // 阻塞获取
```

### 5.2 异步编排

```java
// thenApply：转换结果
CompletableFuture<Integer> future = CompletableFuture
    .supplyAsync(() -> "100")
    .thenApply(Integer::parseInt);  // "100" → 100

// thenCompose：组合依赖的异步任务
CompletableFuture<String> future = CompletableFuture
    .supplyAsync(() -> getUser(1))
    .thenCompose(user -> getOrders(user));  // 先拿用户，再拿订单

// thenCombine：组合两个独立任务的结果
CompletableFuture<String> future = CompletableFuture
    .supplyAsync(() -> "Hello")
    .thenCombine(
        CompletableFuture.supplyAsync(() -> "World"),
        (a, b) -> a + " " + b
    );  // "Hello World"

// thenAccept：消费结果
future.thenAccept(System.out::println);

// exceptionally：异常处理
CompletableFuture<String> future = CompletableFuture
    .supplyAsync(() -> { throw new RuntimeException("error"); })
    .exceptionally(ex -> "fallback");

// handle：无论成功失败都处理
CompletableFuture<String> future = CompletableFuture
    .supplyAsync(() -> { if (ok) return "ok"; else throw ...; })
    .handle((result, ex) -> {
        if (ex != null) return "fallback";
        return result;
    });
```

### 5.3 合并多个

```java
// allOf：等待所有完成
List<CompletableFuture<String>> futures = tasks.stream()
    .map(task -> CompletableFuture.supplyAsync(() -> process(task), executor))
    .toList();

CompletableFuture<Void> all = CompletableFuture.allOf(
    futures.toArray(new CompletableFuture[0])
);
// 等待全部完成，然后收集结果
List<String> results = all.thenApply(v ->
    futures.stream().map(CompletableFuture::join).toList()
).join();

// anyOf：任意一个完成即返回
CompletableFuture<Object> first = CompletableFuture.anyOf(
    CompletableFuture.supplyAsync(() -> callServiceA()),
    CompletableFuture.supplyAsync(() -> callServiceB())
);
```

### 5.4 超时控制

```java
// orTimeout：超时处理（Java 9+）
CompletableFuture<String> future = CompletableFuture
    .supplyAsync(() -> callSlowService())
    .orTimeout(2, TimeUnit.SECONDS)       // 2 秒超时
    .exceptionally(ex -> "timeout");

// completeOnTimeout：超时返回默认值
CompletableFuture<String> future = CompletableFuture
    .supplyAsync(() -> callSlowService())
    .completeOnTimeout("default", 2, TimeUnit.SECONDS);
```

---

## 6. 并发工具类

### 6.1 CountDownLatch（一次性门闩）

```java
// 等待 N 个线程执行完毕
CountDownLatch latch = new CountDownLatch(3);

for (int i = 0; i < 3; i++) {
    executor.submit(() -> {
        try {
            // 执行任务
        } finally {
            latch.countDown();  // 任务完成
        }
    });
}

latch.await();  // 主线程等待 3 个任务都完成
System.out.println("全部完成");
```

### 6.2 CyclicBarrier（可循环屏障）

```java
// N 个线程互相等待，全部到达后再一起继续
CyclicBarrier barrier = new CyclicBarrier(3, () -> {
    System.out.println("全部到达，开始下一阶段");
});

for (int i = 0; i < 3; i++) {
    executor.submit(() -> {
        stage1();
        barrier.await();  // 等其他人
        stage2();
        barrier.await();  // 再等
        stage3();
    });
}
// CyclicBarrier 可重复使用（CountDownLatch 不能）
```

### 6.3 Semaphore（信号量）

```java
// 限制并发数（限流）
Semaphore semaphore = new Semaphore(5);  // 最多 5 个同时访问

for (int i = 0; i < 100; i++) {
    executor.submit(() -> {
        try {
            semaphore.acquire();  // 获取许可
            accessResource();
        } finally {
            semaphore.release();  // 释放许可
        }
    });
}
```

---

## 7. 🔴 常见坑

```java
// 坑 1：线程池中的 ThreadLocal 未清理
ThreadLocal<Context> ctx = new ThreadLocal<>();
executor.submit(() -> {
    ctx.set(context);
    process();
    // ❌ 没 remove！线程复用后，下一个任务拿到脏数据
    ctx.remove();  // ✅
});

// 坑 2：future.get() 无限阻塞
Future<String> future = executor.submit(callable);
String result = future.get();  // 如果任务卡住，永远阻塞
// 🟢 带超时
String result = future.get(5, TimeUnit.SECONDS);

// 坑 3：synchronized 锁的是对象，不是代码
synchronized (Integer.valueOf(100)) { }  // ❌ Integer 缓存！
// 不同地方用相同的缓存值，锁的是同一个对象

// 坑 4：字符串常量池导致的锁问题
synchronized ("LOCK") { }  // ❌ 所有用 "LOCK" 字符串的地方共享一把锁

// 坑 5：死锁
// 线程 A 持有 lock1 等待 lock2
// 线程 B 持有 lock2 等待 lock1
// 🟢 固定加锁顺序 / 用 tryLock

// 坑 6：通知丢失
synchronized (lock) {
    lock.notify();  // 如果此时没有线程在 wait，通知就丢了
}
// 🟢 用 Lock + Condition
```

---

## 8. 并发 API 速查

```java
// 锁
Lock lock = new ReentrantLock();
ReadWriteLock rw = new ReentrantReadWriteLock();
StampedLock sl = new StampedLock();  // JDK 8，乐观读

// 原子类
AtomicInteger
AtomicLong
AtomicBoolean
AtomicReference<V>
AtomicIntegerArray
AtomicLongFieldUpdater
LongAdder        // 高并发计数（比 AtomicLong 快）

// 并发容器
ConcurrentHashMap        // 线程安全 HashMap
CopyOnWriteArrayList     // 读多写少的 List
ConcurrentLinkedQueue    // 无锁队列
ConcurrentSkipListMap    // 有序并发 Map
BlockingQueue            // 阻塞队列（Array / Linked / Priority）
LinkedBlockingDeque      // 双端阻塞队列
DelayQueue               // 延迟队列
SynchronousQueue         // 直接传递（不存元素）

// 并发工具
CountDownLatch    // 等待 N 个线程完成
CyclicBarrier     // N 个线程互相等待
Semaphore         // 信号量限流
Exchanger         // 两个线程交换数据
Phaser            // 阶段同步（CountDownLatch + CyclicBarrier）
```
