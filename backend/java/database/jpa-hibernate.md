# JPA / Hibernate

> 核心原理、N+1 问题、懒加载陷阱、一级/二级缓存、常用 API 速查。

---

## 1. JPA vs Hibernate

```
JPA（Jakarta Persistence）— 规范
  ↑
Hibernate — 实现（最流行）
  ↑
Spring Data JPA — 封装（进一步简化）
```

| 概念 | JPA 注解 | Hibernate 特有 |
|------|----------|----------------|
| 实体 | `@Entity` | — |
| 主键 | `@Id` | — |
| 关联 | `@OneToMany` | — |
| 缓存 | — | `@Cacheable` |
| 批量操作 | — | `@BatchSize` |

---

## 2. 实体映射

### 2.1 基础映射

```java
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", unique = true, nullable = false, length = 32)
    private String orderNo;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)   // 存枚举名，而不是 ordinal
    private OrderStatus status;

    @Column(updatable = false)     // 创建后不可修改
    private LocalDateTime createdAt;

    @Column(insertable = false)    // 由数据库自动维护
    @UpdateTimestamp               // 自动更新
    private LocalDateTime updatedAt;

    // getter/setter ...
}
```

### 2.2 关联映射

```java
@Entity
public class Order {

    // 多对一（多个订单属于同一个用户）
    @ManyToOne(fetch = FetchType.LAZY)  // 延迟加载！
    @JoinColumn(name = "user_id")
    private User user;

    // 一对多（一个订单有多个商品）
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    // 一对一
    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL)
    private Payment payment;

    // 多对多（不推荐，用两个 OneToMany 替代）
    @ManyToMany
    @JoinTable(name = "order_tags",
        joinColumns = @JoinColumn(name = "order_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private List<Tag> tags;
}
```

**Cascade 类型：**

| 类型 | 说明 |
|------|------|
| `PERSIST` | 保存父时同时保存子 |
| `MERGE` | 合并父时同时合并子 |
| `REMOVE` | 删除父时同时删除子 |
| `ALL` | 以上全部 |
| `DETACH` | 分离父时同时分离子 |

**Fetch 策略：**

| 策略 | 说明 | 默认 |
|------|------|------|
| `FetchType.LAZY` | 延迟加载（使用时才查） | `@OneToMany`、`@ManyToMany` |
| `FetchType.EAGER` | 立即加载（查父同时查子） | `@ManyToOne`、`@OneToOne` |

---

## 3. N+1 问题（高频面试）

### 3.1 问题演示

```java
// 1 条 SQL 查所有订单
List<Order> orders = orderRepository.findAll();
// → SELECT * FROM orders

// 遍历时，每个订单查一次用户（N 条 SQL）
for (Order order : orders) {
    System.out.println(order.getUser().getName());
    // → SELECT * FROM users WHERE id = ?
    // → SELECT * FROM users WHERE id = ?
    // → SELECT * FROM users WHERE id = ?  ...
}
// 总共 1 + N 条 SQL → N+1 问题
```

### 3.2 解决方案

```java
// 方案 1：JOIN FETCH（推荐）
@Query("SELECT o FROM Order o JOIN FETCH o.user")
List<Order> findAllWithUser();

// 方案 2：@EntityGraph
@EntityGraph(attributePaths = {"user", "items"})
@Query("SELECT o FROM Order o")
List<Order> findAllWithAll();

// 方案 3：@BatchSize（Hibernate 特有）
@Entity
public class Order {
    @ManyToOne(fetch = FetchType.LAZY)
    @BatchSize(size = 10)  // 批量加载，10 个用户一次查
    private User user;
}

// 方案 4：Specification + JOIN
public static Specification<Order> withUser() {
    return (root, query, cb) -> {
        root.fetch("user", JoinType.INNER);
        return null;
    };
}
```

### 3.3 检查 N+1

```yaml
# 开启 SQL 日志，观察是否有多余查询
spring:
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true
```

```properties
# 或者配置慢查询告警
# 超过 3 条 SQL 就告警（Hibernate 6+）
hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS=100
```

---

## 4. 懒加载陷阱

### 4.1 LazyInitializationException

```java
// ❌ 在事务外访问懒加载属性
@GetMapping("/api/orders/{id}")
public OrderDto getOrder(@PathVariable Long id) {
    Order order = orderRepository.findById(id).orElseThrow();
    // 事务已结束！

    // ❌ LazyInitializationException!
    String userName = order.getUser().getName();
    return new OrderDto(order);
}

// 🟢 方案 1：在事务内完成
@Transactional
public OrderDto getOrder(Long id) {
    Order order = orderRepository.findById(id).orElseThrow();
    String userName = order.getUser().getName();  // 还在事务中
    return new OrderDto(order);
}

// 🟢 方案 2：JOIN FETCH 提前加载
@Query("SELECT o FROM Order o JOIN FETCH o.user WHERE o.id = :id")
Optional<Order> findByIdWithUser(@Param("id") Long id);

// 🟢 方案 3：DTO 投影（最佳，只查需要的字段）
public interface OrderView {
    Long getId();
    String getOrderNo();
    String getUserName();  // 自动关联

    // JPQL
    @Query("SELECT o.id AS id, o.orderNo AS orderNo, u.name AS userName " +
           "FROM Order o JOIN o.user u WHERE o.id = :id")
    Optional<OrderView> findOrderView(@Param("id") Long id);
}
```

### 4.2 open-in-view 的坑

```yaml
# Spring Boot 默认开启（OSIV = Open Session In View）
spring:
  jpa:
    open-in-view: true   # 默认 true
```

```java
// open-in-view = true 的问题：
// 1. 数据库连接在 HTTP 请求期间一直保持
// 2. 高并发时连接池容易耗尽
// 3. 延迟加载可能发生在 View 层，难以追踪

// 🟢 生产环境建议关闭
spring:
  jpa:
    open-in-view: false  # 强制在 Service 层完成所有查询
```

---

## 5. 一级缓存与二级缓存

### 5.1 一级缓存（Session 级别）

```java
// 同一个 Session 中，多次查同一条数据只发一次 SQL
@Transactional
public void process(Long orderId) {
    // 第一次：发 SQL
    Order order1 = orderRepository.findById(orderId).orElseThrow();

    // 第二次：从一级缓存取，不发 SQL
    Order order2 = orderRepository.findById(orderId).orElseThrow();

    System.out.println(order1 == order2);  // true（同一个对象）
}
```

**一级缓存生命周期：** 事务开始 → 事务结束（提交/回滚）

### 5.2 二级缓存（SessionFactory 级别）

```yaml
spring:
  jpa:
    properties:
      hibernate:
        cache:
          use_second_level_cache: true
          region:
            factory_class: org.hibernate.cache.jcache.JCacheRegionFactory
```

```xml
<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-jcache</artifactId>
</dependency>
<dependency>
    <groupId>org.ehcache</groupId>
    <artifactId>ehcache</artifactId>
</dependency>
```

```java
@Entity
@Cacheable  // 开启二级缓存
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class User {
    // ...
}
```

**缓存策略：**

| 策略 | 说明 | 适用 |
|------|------|------|
| `READ_ONLY` | 只读，性能最好 | 字典表、配置 |
| `READ_WRITE` | 读写，更新时失效 | 常用但更新少的实体 |
| `NONSTRICT_READ_WRITE` | 弱一致性 | 几乎不更新的数据 |
| `TRANSACTIONAL` | 事务级，最强一致 | 关键数据 |

---

## 6. 常用 API 速查

### 6.1 Spring Data JPA 方法命名

```java
public interface OrderRepository extends JpaRepository<Order, Long> {

    // 查询
    List<Order> findByStatus(String status);
    List<Order> findByStatusIn(List<String> statuses);
    Optional<Order> findByOrderNo(String orderNo);
    List<Order> findByAmountGreaterThan(BigDecimal amount);
    List<Order> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    // 排序
    List<Order> findByStatusOrderByCreatedAtDesc(String status);
    List<Order> findByStatus(String status, Sort sort);

    // 分页
    Page<Order> findByStatus(String status, Pageable pageable);

    // 统计
    long countByStatus(String status);
    long deleteByStatus(String status);

    // 判断
    boolean existsByOrderNo(String orderNo);
}
```

### 6.2 @Query 自定义查询

```java
public interface OrderRepository extends JpaRepository<Order, Long> {

    // JPQL
    @Query("SELECT o FROM Order o WHERE o.amount > :minAmount")
    List<Order> findExpensiveOrders(@Param("minAmount") BigDecimal minAmount);

    // 更新
    @Modifying
    @Query("UPDATE Order o SET o.status = :status WHERE o.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    // 原生 SQL
    @Query(value = "SELECT * FROM orders WHERE DATE(created_at) = :date",
           nativeQuery = true)
    List<Order> findByDate(@Param("date") LocalDate date);
}
```

### 6.3 审计

```java
// 自动填充创建时间、更新时间
@EntityListeners(AuditingEntityListener.class)
@Entity
public class Order {

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @CreatedBy
    private String createdBy;

    @LastModifiedBy
    private String updatedBy;
}

// 启用审计
@EnableJpaAuditing
@SpringBootApplication
public class Application { }
```

### 6.4 乐观锁

```java
@Entity
public class Product {

    @Version  // 乐观锁版本号
    private Long version;
}

// 并发更新时，版本号不一致抛 OptimisticLockException
// 🟢 重试
@Retryable(value = OptimisticLockException.class, maxAttempts = 3)
public void updateStock(Long productId, int quantity) {
    Product product = productRepository.findById(productId).orElseThrow();
    product.setStock(product.getStock() - quantity);
}
```

---

## 7. 🔴 常见坑

```java
// 坑 1：save() 的返回值
Order saved = orderRepository.save(order);
System.out.println(saved == order);  // true（同一个对象）
// 但 saved 是持久化后的对象（ID 已生成）

// 坑 2：批量操作不要用 saveAll
// ❌ 循环 save，逐条 INSERT
for (Order order : orders) {
    orderRepository.save(order);
}

// ✅ 批量插入
@Modifying
@Query(value = "INSERT INTO orders(order_no, amount) VALUES (:orderNo, :amount)",
       nativeQuery = true)
int batchInsert(@Param("orders") List<Order> orders);

// 或配置批量大小
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50  # 批量提交

// 坑 3：remove 前要先查
// ❌
orderRepository.deleteById(1L);  // 先查再删（2 条 SQL）
// ✅ 如果确定存在，用 @Modifying + JPQL
@Modifying
@Query("DELETE FROM Order o WHERE o.id = :id")
int deleteByIdDirectly(@Param("id") Long id);

// 坑 4：在循环中修改集合
// ❌ ConcurrentModificationException
for (OrderItem item : order.getItems()) {
    if (item.getQuantity() == 0) {
        order.getItems().remove(item);  // 不能在遍历中删！
    }
}
// ✅ 用 removeIf 或 Iterator
order.getItems().removeIf(item -> item.getQuantity() == 0);
```
