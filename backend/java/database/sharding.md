# 分库分表

> ShardingSphere 分库分表、读写分离、分布式主键、分页查询。

---

## 1. 为什么需要分库分表

### 1.1 单库瓶颈

```
数据量增长路径：

单表 < 500 万行
  → 加索引、SQL 优化

单表 > 500 万行
  → 分表（水平拆分，把数据分散到多张表）

单库 QPS > 2000
  → 分库（水平拆分，把请求分散到多个库）

单库存储 > 1TB
  → 分库（拆分存储）
```

### 1.2 拆分方式

```
垂直拆分（按业务）：
  订单库 → 订单表、订单项表
  用户库 → 用户表
  库存库 → 商品表

水平拆分（按数据）：
  order_db_0 → orders_0, orders_1
  order_db_1 → orders_2, orders_3
  根据 order_id % 4 路由
```

---

## 2. ShardingSphere

### 2.1 简介

```
ShardingSphere-JDBC：
  轻量级 Java 框架，在 JDBC 层增强
  应用直接连接数据库，无额外中间件
  适合对性能要求高的场景

ShardingSphere-Proxy：
  独立部署的代理服务
  应用连接 Proxy 即可
  适合异构系统
```

### 2.2 依赖

```xml
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc-core-spring-boot-starter</artifactId>
    <version>5.5.0</version>
</dependency>
```

---

## 3. 分表配置

### 3.1 水平分表

```yaml
spring:
  shardingsphere:
    datasource:
      names: ds
      ds:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://localhost:3306/order_db
        username: root
        password: password

    rules:
      sharding:
        tables:
          # 订单表分片规则
          orders:
            actual-data-nodes: ds.orders_$->{0..1}   # 两张表
            table-strategy:
              standard:
                sharding-column: order_id            # 分片键
                sharding-algorithm-name: orders-inline
            key-generate-strategy:
              column: order_id
              key-generator-name: snowflake

        sharding-algorithms:
          orders-inline:
            type: INLINE
            props:
              algorithm-expression: orders_$->{order_id % 2}  # order_id 奇偶分表

        key-generators:
          snowflake:
            type: SNOWFLAKE  # 雪花算法分布式 ID

    props:
      sql-show: true  # 显示真实 SQL
```

```java
// 插入：自动计算分表
orderService.save(order);
// → INSERT INTO orders_0 / orders_1（根据 order_id 取模）

// 查询带分片键：直接定位
orderService.getById(1L);
// → SELECT * FROM orders_1 WHERE order_id = 1

// 查询不带分片键：广播到所有表
orderService.list();
// → SELECT * FROM orders_0 UNION ALL SELECT * FROM orders_1
```

### 3.2 分库分表

```yaml
spring:
  shardingsphere:
    datasource:
      names: ds0, ds1
      ds0:
        jdbc-url: jdbc:mysql://localhost:3306/order_db_0
      ds1:
        jdbc-url: jdbc:mysql://localhost:3306/order_db_1

    rules:
      sharding:
        tables:
          orders:
            actual-data-nodes: ds$->{0..1}.orders_$->{0..1}
            database-strategy:       # 分库策略
              standard:
                sharding-column: user_id
                sharding-algorithm-name: database-inline
            table-strategy:          # 分表策略
              standard:
                sharding-column: order_id
                sharding-algorithm-name: table-inline

        sharding-algorithms:
          database-inline:
            type: INLINE
            props:
              algorithm-expression: ds$->{user_id % 2}  # user_id 分库
          table-inline:
            type: INLINE
            props:
              algorithm-expression: orders_$->{order_id % 2}  # order_id 分表
```

---

## 4. 分片策略

### 4.1 内置分片算法

| 算法 | 说明 | 适用 |
|------|------|------|
| `MOD` | 取模 | 简单均匀 |
| `HASH_MOD` | 哈希取模 | 字符串字段 |
| `INLINE` | 行表达式 | 灵活配置 |
| `INTERVAL` | 按时间区间 | 时间字段 |
| `BOUNDARY_RANGE` | 按范围 | 数值区间 |

### 4.2 时间分片

```yaml
sharding-algorithms:
  create-time-interval:
    type: INTERVAL
    props:
      datetime-pattern: yyyy-MM-dd HH:mm:ss  # 时间格式
      datetime-lower: 2024-01-01 00:00:00     # 起始时间
      datetime-upper: 2025-12-31 23:59:59     # 结束时间
      sharding-seconds: 2592000                # 30 天一个分片
      datetime-interval-unit: SECONDS
```

### 4.3 强制分片

```java
// 某些查询必须带分片键，否则性能差
// 例如按用户查询订单，必须带 user_id
public List<Order> findByUserId(Long userId) {
    // ✅ 带了分片键 user_id，直接定位到对应库
    return lambdaQuery()
        .eq(Order::getUserId, userId)
        .list();
}

public List<Order> findByOrderNo(String orderNo) {
    // ❌ 没带分片键，广播到所有表
    // 🟢 建立 order_no → order_id 的索引表
}
```

---

## 5. 读写分离

```yaml
spring:
  shardingsphere:
    datasource:
      names: write-ds, read-ds-0, read-ds-1
      write-ds:
        jdbc-url: jdbc:mysql://master:3306/db
      read-ds-0:
        jdbc-url: jdbc:mysql://slave0:3306/db
      read-ds-1:
        jdbc-url: jdbc:mysql://slave1:3306/db

    rules:
      readwrite-splitting:
        data-sources:
          rw-ds:
            type: Static
            props:
              write-data-source-name: write-ds
              read-data-source-names: read-ds-0, read-ds-1
            load-balancer-name: round-robin

        load-balancers:
          round-robin:
            type: ROUND_ROBIN
```

```java
// 写操作 → 主库
orderService.save(order);
// → INSERT INTO write-ds.orders

// 读操作 → 从库
orderService.list();
// → SELECT FROM read-ds-0 / read-ds-1（轮询）

// 强制读主库（需要实时一致性）
@Transactional  // 事务内强制走主库
public Order getOrder(Long id) {
    return orderService.getById(id);
}
```

---

## 6. 分布式主键

### 6.1 雪花算法

```yaml
key-generators:
  snowflake:
    type: SNOWFLAKE
    props:
      worker-id: 1  # 机器 ID（分布式环境必须不同）
```

**雪花算法结构：**
```
1 bit 符号位（0）
41 bit 时间戳（毫秒）
10 bit 机器 ID
12 bit 序列号
────────────────
64 bit long
```

### 6.2 其他主键方案

| 方案 | 说明 | 优缺点 |
|------|------|--------|
| 雪花算法 | 趋势递增，分布式唯一 | 依赖时钟 |
| UUID | 完全无序 | 索引性能差 |
| Redis incr | 递增 | 依赖 Redis |
| 数据库自增步长 | 递增 | 扩容麻烦 |

---

## 7. 分页与排序

### 7.1 分页问题

```java
// 分页查询（带分片键）
Page<Order> page = lambdaQuery()
    .eq(Order::getUserId, userId)    // ✅ 带分片键
    .page(new Page<>(1, 10));

// 全局分页（不带分片键）
Page<Order> page = lambdaQuery()
    .orderByDesc(Order::getCreatedAt) // ❌ 每张表各自排序
    .page(new Page<>(1, 10));
    // 实际：每张表取前 10 条，内存中合并再排序
    // 结果可能不准确！
```

### 7.2 解决方案

```java
// 方案 1：建立索引表（order_no → 分片键）
// 先查索引表定位分片，再查目标表

// 方案 2：Elasticsearch 做全局排序
// 订单数据同步到 ES，分页查 ES

// 方案 3：限制查询范围
// 按时间范围查询，减少跨分片
```

---

## 8. 分布式事务

```yaml
# ShardingSphere 集成 Seata
spring:
  shardingsphere:
    rules:
      sharding:
        # ... 分片规则

    props:
      xa-transaction-manager-type: Seata  # 或 Atomikos
```

---

## 9. 🔴 常见坑

```java
// 坑 1：分片键不允许修改
Order order = orderService.getById(1L);
order.setUserId(2L);          // ❌ 修改了分片键！
orderService.updateById(order); // 数据路由到错误的分片

// 坑 2：跨分片 JOIN
// ❌ 不同分片间的 JOIN 不支持（或性能极差）
// 🟢 在应用层组装，或用 ES

// 坑 3：分布式主键长度
// 雪花算法生成的是 long（19 位）
// 数据库主键字段必须用 BIGINT，不能用 INT

// 坑 4：扩容
// 从 2 个分片扩到 4 个分片
// 取模算法变化 → 数据需要迁移
// 🟢 用一致性哈希 / 范围分片

// 坑 5：分布式 ID 的排序
// 雪花算法是趋势递增的，但：
// INSERT 顺序和时间戳不完全一致（序列号影响）
// 🟢 排序字段用 created_at，不用主键
```

---

## 10. API 速查

```yaml
# 分片配置
spring:
  shardingsphere:
    datasource:
      names: ds0, ds1
    rules:
      sharding:
        tables:
          table_name:
            actual-data-nodes: ds$->{0..1}.table_$->{0..1}
            database-strategy:
              standard:
                sharding-column: user_id
                sharding-algorithm-name: hash
            table-strategy:
              standard:
                sharding-column: id
                sharding-algorithm-name: mod

# 读写分离配置
    rules:
      readwrite-splitting:
        data-sources:
          rw-ds:
            type: Static
            props:
              write-data-source-name: write-ds
              read-data-source-names: read-ds-0, read-ds-1
```

```java
// 强制路由（指定走主库或从库）
@Transactional        // 事务内走主库
HintManager hintManager = HintManager.getInstance();
hintManager.setWriteRouteOnly();  // 强制走主库
hintManager.close();

// 分布式事务
@ShardingSphereTransactionType(TransactionType.XA)
@GlobalTransactional
public void createOrder(Order order) { }
```
