# 事务管理

> 事务传播行为、隔离级别、@Transactional 的常见陷阱——企业级事务实践。

---

## 1. 事务核心

### 1.1 ACID

| 特性 | 说明 | 含义 |
|------|------|------|
| **Atomicity** | 原子性 | 全部成功或全部回滚 |
| **Consistency** | 一致性 | 数据总是满足约束 |
| **Isolation** | 隔离性 | 并发事务互不干扰 |
| **Durability** | 持久性 | 提交后永久保存 |

### 1.2 声明式事务

```java
// 最简单的用法
@Transactional
public void createOrder(Order order) {
    orderDao.insert(order);
    inventoryDao.deduct(order.getProductId(), order.getQuantity());
    // 任何异常 → 全部回滚
}
```

---

## 2. 事务传播行为（高频面试）

### 2.1 7 种传播行为

```java
@Transactional(propagation = Propagation.REQUIRED)  // 默认
```

| 传播行为 | 说明 |
|----------|------|
| **REQUIRED**（默认） | 有事务就用，没有就新建 |
| **REQUIRES_NEW** | 必须新建，挂起当前事务 |
| **SUPPORTS** | 有事务就用，没有就不用 |
| **NOT_SUPPORTED** | 非事务执行，挂起当前事务 |
| **MANDATORY** | 必须已有事务，否则抛异常 |
| **NEVER** | 必须没有事务，否则抛异常 |
| **NESTED** | 嵌套事务（JDBC Savepoint） |

### 2.2 核心场景

```java
// REQUIRED（默认）：内外方法在同一事务中
@Service
public class OrderService {

    @Transactional
    public void createOrder(Order order) {
        orderDao.insert(order);           // 事务 T1
        paymentService.pay(order.getId()); // 也在 T1 中！
        // pay 失败 → orderDao.insert 也回滚
    }
}

@Service
public class PaymentService {
    @Transactional(propagation = Propagation.REQUIRED)
    public void pay(Long orderId) {
        paymentDao.pay(orderId);
    }
}
```

```java
// REQUIRES_NEW：内外方法在不同事务中
@Service
public class OrderService {

    @Transactional
    public void createOrder(Order order) {
        orderDao.insert(order);           // 事务 T1
        try {
            logService.log("创建订单");    // 独立事务 T2
        } catch (Exception e) {
            // T2 失败不影响 T1！
        }
    }
}

@Service
public class LogService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String message) {
        logDao.insert(message);  // 独立事务，独立提交/回滚
    }
}
```

```java
// NESTED：嵌套事务（利用 Savepoint）
// 内部回滚只回滚到 Savepoint，外部可继续
@Transactional
public void batchProcess(List<Order> orders) {
    for (Order order : orders) {
        try {
            processOne(order);  // 每个订单独立 Savepoint
        } catch (Exception e) {
            // 单个订单失败，不影响其他订单
        }
    }
}

@Transactional(propagation = Propagation.NESTED)
public void processOne(Order order) {
    // ...
}
```

---

## 3. 隔离级别

### 3.1 并发问题

| 问题 | 说明 |
|------|------|
| **脏读** | 读到其他事务未提交的数据 |
| **不可重复读** | 同一事务内两次读取同一数据，结果不同 |
| **幻读** | 同一事务内两次查询，结果集不同（多了/少了行） |

### 3.2 隔离级别

```java
@Transactional(isolation = Isolation.READ_COMMITTED)  // 默认（多数数据库）
```

| 级别 | 脏读 | 不可重复读 | 幻读 | 性能 |
|------|------|-----------|------|------|
| READ_UNCOMMITTED | ❌ | ❌ | ❌ | 最高 |
| **READ_COMMITTED** | ✅ | ❌ | ❌ | 高 |
| REPEATABLE_READ | ✅ | ✅ | ❌ | 中 |
| SERIALIZABLE | ✅ | ✅ | ✅ | 低 |

```java
// 各数据库默认隔离级别
// MySQL:    REPEATABLE_READ
// PostgreSQL, Oracle, SQL Server: READ_COMMITTED
```

---

## 4. @Transactional 详解

### 4.1 常用参数

```java
@Transactional(
    propagation = Propagation.REQUIRED,   // 传播行为
    isolation = Isolation.READ_COMMITTED,  // 隔离级别
    timeout = 30,                          // 超时（秒）
    readOnly = false,                      // 是否只读
    rollbackFor = Exception.class,         // 触发回滚的异常
    noRollbackFor = BusinessException.class // 不触发回滚的异常
)
```

### 4.2 readOnly 优化

```java
// 只读事务：告诉数据库这是一个只读操作
// MySQL/PostgreSQL 会进行一些优化（不需要加锁）
@Transactional(readOnly = true)
public Order findById(Long id) {
    return orderDao.findById(id);
}

// 写操作必须设置 readOnly = false（默认）
@Transactional
public void updateOrder(Order order) {
    orderDao.update(order);
}
```

---

## 5. 🔴 常见陷阱（高频踩坑）

### 5.1 事务不生效

```java
// 陷阱 1：同类中方法调用
@Service
public class OrderService {

    public void methodA() {
        this.methodB();  // ❌ 内部调用，AOP 不生效
    }

    @Transactional
    public void methodB() {
        // 事务不会生效！
    }
}

// 🟢 注入自身
@Service
public class OrderService {
    @Autowired
    private OrderService self;

    public void methodA() {
        self.methodB();  // ✅ 通过代理调用
    }

    @Transactional
    public void methodB() { }
}
```

```java
// 陷阱 2：非 public 方法
@Service
public class OrderService {

    @Transactional
    protected void doProcess() { }  // ❌ 不生效！@Transactional 只支持 public
}
```

```java
// 陷阱 3：异常被 catch
@Service
public class OrderService {

    @Transactional
    public void create() {
        try {
            orderDao.insert(order);
            throw new RuntimeException("出错");  // 抛异常
        } catch (Exception e) {
            // ❌ catch 住了，事务不会回滚！
            log.error("错误", e);
        }
    }
}

// 🟢 在 catch 中手动回滚
@Transactional
public void create() {
    try {
        orderDao.insert(order);
        throw new RuntimeException("出错");
    } catch (Exception e) {
        // 手动回滚
        TransactionAspectSupport.currentTransactionStatus()
            .setRollbackOnly();
    }
}
```

```java
// 陷阱 4：rollbackFor 默认只回滚 RuntimeException 和 Error
@Transactional
public void create() throws SQLException {
    orderDao.insert(order);
    throw new SQLException("数据库错误");  // ❌ 受检异常，默认不回滚！
}

// 🟢 显式指定
@Transactional(rollbackFor = Exception.class)
public void create() throws SQLException {
    orderDao.insert(order);
    throw new SQLException("数据库错误");  // ✅ 这回滚了
}
```

### 5.2 事务超时

```java
// 陷阱 5：事务超时未生效
@Transactional(timeout = 1)  // 1 秒超时
public void slowMethod() {
    // ❌ 如果这里调了远程服务，超时可能不准确
    Thread.sleep(5000);  // 事务超时不生效！
}
// 🟢 数据库层面的超时（jdbcTemplate.setQueryTimeout()）
```

### 5.3 大事务

```java
// 陷阱 6：大事务问题
@Transactional
public void batchProcess(List<Order> orders) {
    for (Order order : orders) {
        processOne(order);   // 循环 10000 次
    }
    // 问题：
    // 1. 长事务 → 锁持有时间长 → 并发低
    // 2. 大量数据在事务上下文中 → 内存大
    // 3. 一个失败全部回滚 → 浪费
}

// 🟢 拆分事务
public void batchProcess(List<Order> orders) {
    for (Order order : orders) {
        processOneInTransaction(order);
    }
}

@Transactional
public void processOneInTransaction(Order order) {
    processOne(order);  // 每个订单独立事务
}
```

### 5.4 事务与锁

```java
// 陷阱 7：事务中加锁顺序
// 线程 A: 事务 T1 → 锁 A → 锁 B → 提交
// 线程 B: 事务 T2 → 锁 B → 锁 A → 提交
// → 死锁！

// 🟢 固定加锁顺序 / 使用超时
```

---

## 6. 事务监听

```java
// 事务提交后执行（发消息、写日志等）
@Component
public class OrderEventListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        // 事务提交后发消息
        // 如果事务回滚，这个方法不会执行
        messageService.send(event.getOrderId());
    }
}
```

| phase | 说明 |
|-------|------|
| AFTER_COMMIT（默认） | 事务提交后执行 |
| AFTER_ROLLBACK | 事务回滚后执行 |
| AFTER_COMPLETION | 事务完成后执行（提交或回滚） |
| BEFORE_COMMIT | 事务提交前执行 |

---

## 7. API 速查

```java
// 编程式事务（声明式不够用时）
@Service
public class OrderService {

    @Autowired
    private TransactionTemplate transactionTemplate;

    public void process() {
        transactionTemplate.execute(status -> {
            try {
                orderDao.insert(order);
                inventoryDao.deduct(productId, quantity);
                return true;
            } catch (Exception e) {
                status.setRollbackOnly();  // 手动回滚
                return false;
            }
        });
    }
}

// 获取当前事务状态
TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

// 事务传播常量
Propagation.REQUIRED
Propagation.REQUIRES_NEW
Propagation.NESTED

// 隔离级别常量
Isolation.DEFAULT         // 使用数据库默认
Isolation.READ_COMMITTED
Isolation.REPEATABLE_READ
```
