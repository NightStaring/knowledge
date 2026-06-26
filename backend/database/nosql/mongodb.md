# MongoDB

> 文档数据库、聚合管道、索引、Spring Data 集成。

---

## 1. 核心概念

### 1.1 MongoDB vs MySQL

```
MySQL:     Database → Table → Row → Column
MongoDB:   Database → Collection → Document → Field
```

| 概念 | 对比 |
|------|------|
| **Document** | JSON 文档，Schema 灵活（同集合可不同结构） |
| **Collection** | 类似表，但不需要预先定义字段 |
| **\_id** | 默认主键（ObjectId，12 字节） |
| **Embedded** | 关联数据直接嵌套，避免 JOIN |

### 1.2 为什么用 MongoDB

```
✅ 适合的场景：
  - Schema 灵活（埋点数据、配置、日志）
  - 无需复杂 JOIN
  - 读写频繁，需要高吞吐
  - 快速迭代开发

❌ 不适合：
  - 强事务要求（MongoDB 4.0+ 支持事务，但不如 MySQL）
  - 复杂关联查询
  - 数据结构稳定不变
```

---

## 2. CRUD 操作

### 2.1 查询

```javascript
// 基本查询
db.users.find({ status: "ACTIVE" })
db.users.find({ age: { $gte: 18 } })
db.users.find({ name: /^张/ })            // 正则

// 多条件
db.orders.find({
    status: "PAID",
    amount: { $gte: 100, $lte: 1000 },
    createdAt: { $gte: ISODate("2025-01-01") }
})

// 排序分页
db.orders.find({ status: "PAID" })
    .sort({ createdAt: -1 })
    .skip(0)
    .limit(20)

// 只查需要的字段
db.users.find({}, { name: 1, email: 1, _id: 0 })  // 1=包含, 0=排除
```

### 2.2 更新

```javascript
// 更新
db.orders.updateOne(
    { _id: ObjectId("...") },
    { $set: { status: "PAID", paidAt: new Date() } }
)

// 原子递增
db.products.updateOne(
    { _id: ObjectId("...") },
    { $inc: { stock: -1 } }     // 原子减 1
)

// 数组操作
db.articles.updateOne(
    { _id: ObjectId("...") },
    {
        $push: { tags: "java" },        // 追加
        $pull: { tags: "obsolete" },     // 删除
        $addToSet: { tags: "new" }       // 不重复追加
    }
)
```

### 2.3 聚合管道

```javascript
// 聚合管道（类似 GROUP BY + 多阶段处理）
db.orders.aggregate([
    // 阶段 1：过滤
    { $match: { status: "PAID" } },

    // 阶段 2：分组
    { $group: {
        _id: "$userId",
        totalAmount: { $sum: "$amount" },
        orderCount: { $sum: 1 },
        avgAmount: { $avg: "$amount" }
    }},

    // 阶段 3：排序
    { $sort: { totalAmount: -1 } },

    // 阶段 4：限制
    { $limit: 10 }
])
```

---

## 3. 索引

### 3.1 索引类型

```javascript
// 单字段索引
db.users.createIndex({ email: 1 })           // 1=升序, -1=降序

// 复合索引
db.orders.createIndex({ status: 1, createdAt: -1 })

// 唯一索引
db.users.createIndex({ email: 1 }, { unique: true })

// 文本索引
db.articles.createIndex({ title: "text", content: "text" })

// TTL 索引（自动过期）
db.logs.createIndex({ createdAt: 1 }, { expireAfterSeconds: 86400 })  // 7 天

// 稀疏索引（只索引有该字段的文档）
db.users.createIndex({ email: 1 }, { sparse: true })
```

### 3.2 索引优化

```javascript
// 查看查询是否走索引
db.orders.find({ status: "PAID" }).explain("executionStats")

// 查看索引使用
db.orders.aggregate([ { $indexStats: {} } ])

// 删除无用索引
db.orders.dropIndex("index_name")
```

---

## 4. Spring Data MongoDB

### 4.1 配置

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
```

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/mydb
```

### 4.2 实体映射

```java
@Document(collection = "orders")  // 集合名
public class Order {

    @Id
    private String id;             // MongoDB 的 _id

    private String orderNo;

    private BigDecimal amount;

    private String status;

    @Field("created_at")
    private LocalDateTime createdAt;

    // 嵌套文档
    private List<OrderItem> items;

    // 引用其他文档
    @DBRef
    private User user;
}
```

### 4.3 Repository

```java
public interface OrderRepository extends MongoRepository<Order, String> {

    // 方法命名查询
    List<Order> findByStatus(String status);
    List<Order> findByAmountGreaterThan(BigDecimal amount);

    // 复杂查询用 @Query
    @Query("{ 'status': ?0, 'amount': { $gte: ?1 } }")
    List<Order> findPaidOrders(String status, BigDecimal minAmount);

    // 聚合
    @Aggregation(pipeline = {
        "{ $match: { status: ?0 } }",
        "{ $group: { _id: '$userId', total: { $sum: '$amount' } } }",
        "{ $sort: { total: -1 } }"
    })
    List<UserTotal> aggregateByUser(String status);
}
```

---

## 5. 🔴 常见坑

```javascript
// 坑 1：没有索引的查询
db.orders.find({ status: "PAID" }).sort({ createdAt: -1 })
// 全表扫描 + 内存排序 → 慢
// 🟢 建复合索引 { status: 1, createdAt: -1 }

// 坑 2：数组太大
// 内嵌数组无限增长
// 🟢 限制数组大小 / 拆到另一个集合

// 坑 3：没有用 $slice 限制返回
// 内嵌数组有 10000 个元素，全返回
db.orders.find({}, { items: { $slice: 10 } })  // 只返回前 10 个

// 坑 4：文档太大（> 16MB）
// MongoDB 单文档限制 16MB
// 🟢 用 GridFS 存大文件

// 坑 5：$lookup 性能
// MongoDB 的 JOIN（$lookup）性能差
// 🟢 尽量用嵌套文档，不用 JOIN
```

---

## 6. 命令速查

```javascript
// 数据库
show dbs
use mydb
db.dropDatabase()

// 集合
show collections
db.createCollection("orders")
db.orders.drop()

// CRUD
db.collection.find(query, projection)
db.collection.findOne(query)
db.collection.insertOne(doc)
db.collection.insertMany([doc1, doc2])
db.collection.updateOne(filter, update)
db.collection.updateMany(filter, update)
db.collection.deleteOne(filter)
db.collection.deleteMany(filter)

// 聚合
db.collection.aggregate([pipeline])

// 索引
db.collection.createIndex(keys, options)
db.collection.getIndexes()
db.collection.dropIndex(name)
db.collection.dropIndexes()

// 管理
db.collection.stats()              // 集合统计
db.collection.estimatedDocumentCount()  // 估算文档数
db.collection.countDocuments(query)     // 精确计数
```
