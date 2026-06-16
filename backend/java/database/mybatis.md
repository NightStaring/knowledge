# MyBatis / MyBatis-Plus

> 企业级 MyBatis 实践——从原生到 MyBatis-Plus，从 XML 到 Lambda。

---

## 1. MyBatis vs JPA

| 维度 | MyBatis | JPA/Hibernate |
|------|---------|---------------|
| **SQL 控制** | 完全手动，优化灵活 | 自动生成，难优化 |
| **复杂 SQL** | 天然适合 | 困难（JPQL 复杂） |
| **动态 SQL** | `<if>`、`<foreach>` 强大 | `@Query` + `Specification` |
| **学习曲线** | 低（会 SQL 就会） | 中（需要理解 JPA 原理） |
| **缓存** | 一级 + 二级 | 一级 + 二级 |
| **批量操作** | 原生支持 | 需配置 |
| **国内流行度** | 高 | 中 |

**选型建议：**
- 复杂 SQL 多、需要精细优化 → **MyBatis**
- 简单 CRUD、标准 ORM → **JPA**
- **最佳实践**：MyBatis-Plus（CRUD 自动）+ 手写复杂 SQL

---

## 2. 快速开始

### 2.1 依赖

```xml
<dependency>
    <groupId>org.mybatis.spring.boot</groupId>
    <artifactId>mybatis-spring-boot-starter</artifactId>
    <version>3.0.3</version>
</dependency>
```

### 2.2 配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/db
    username: root
    password: password
    driver-class-name: com.mysql.cj.jdbc.Driver

mybatis:
  mapper-locations: classpath:mapper/*.xml    # XML 映射文件
  type-aliases-package: com.example.entity     # 别名包
  configuration:
    map-underscore-to-camel-case: true         # user_name → userName
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl  # SQL 日志
    cache-enabled: true                        # 二级缓存
```

### 2.3 Mapper

```java
@Mapper
public interface OrderMapper {

    @Select("SELECT * FROM orders WHERE id = #{id}")
    Order findById(@Param("id") Long id);

    @Select("SELECT * FROM orders WHERE status = #{status} ORDER BY created_at DESC")
    List<Order> findByStatus(@Param("status") String status);

    @Insert("INSERT INTO orders(order_no, amount, status) VALUES(#{orderNo}, #{amount}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")  // 返回自增 ID
    int insert(Order order);

    @Update("UPDATE orders SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Delete("DELETE FROM orders WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
```

### 2.4 XML 映射

```xml
<!-- resources/mapper/OrderMapper.xml -->
<mapper namespace="com.example.mapper.OrderMapper">

    <!-- 结果映射 -->
    <resultMap id="orderMap" type="Order">
        <id property="id" column="id"/>
        <result property="orderNo" column="order_no"/>
        <result property="amount" column="amount"/>
        <!-- 关联用户 -->
        <association property="user" column="user_id"
                     select="com.example.mapper.UserMapper.findById"
                     fetchType="lazy"/>
        <!-- 关联订单项 -->
        <collection property="items" column="id"
                    select="com.example.mapper.OrderItemMapper.findByOrderId"
                    fetchType="lazy"/>
    </resultMap>

    <!-- 动态 SQL -->
    <select id="findByCondition" resultMap="orderMap">
        SELECT * FROM orders
        <where>
            <if test="status != null">
                AND status = #{status}
            </if>
            <if test="minAmount != null">
                AND amount >= #{minAmount}
            </if>
            <if test="startDate != null">
                AND created_at >= #{startDate}
            </if>
        </where>
        ORDER BY created_at DESC
    </select>

    <!-- 批量插入 -->
    <insert id="batchInsert">
        INSERT INTO orders(order_no, amount, status) VALUES
        <foreach collection="list" item="order" separator=",">
            (#{order.orderNo}, #{order.amount}, #{order.status})
        </foreach>
    </insert>

</mapper>
```

---

## 3. MyBatis-Plus（推荐）

### 3.1 依赖

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.7</version>
</dependency>
```

### 3.2 基础 CRUD

```java
// 实体类
@Data
@TableName("orders")  // 表名映射
public class Order {

    @TableId(type = IdType.AUTO)  // 自增主键
    private Long id;

    private String orderNo;

    private BigDecimal amount;

    @TableField(fill = FieldFill.INSERT)  // 自动填充
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic  // 逻辑删除
    private Integer deleted;
}

// Mapper（继承 BaseMapper，CRUD 方法全有了！）
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
    // 不需要写任何方法！
}

// Service（继承 IService，更多高级功能）
@Service
public class OrderService extends ServiceImpl<OrderMapper, Order> {
    // 直接用 this.save()、this.list()、this.page() ...
}
```

### 3.3 常用操作

```java
@Service
public class OrderService extends ServiceImpl<OrderMapper, Order> {

    // 条件查询
    public List<Order> findPaidOrders() {
        return lambdaQuery()
            .eq(Order::getStatus, "PAID")
            .ge(Order::getAmount, new BigDecimal("100"))
            .orderByDesc(Order::getCreatedAt)
            .list();
    }

    // 分页
    public Page<Order> pageQuery(int page, int size) {
        return lambdaQuery()
            .eq(Order::getStatus, "PAID")
            .page(new Page<>(page, size));
    }

    // 更新
    public boolean updateStatus(Long id, String status) {
        return lambdaUpdate()
            .eq(Order::getId, id)
            .set(Order::getStatus, status)
            .update();
    }

    // 删除
    public boolean removeByStatus(String status) {
        return lambdaUpdate()
            .eq(Order::getStatus, status)
            .remove();
    }
}
```

### 3.4 Wrapper 大全

```java
// QueryWrapper（字符串形式，不推荐）
QueryWrapper<Order> wrapper = new QueryWrapper<>();
wrapper.eq("status", "PAID")
       .ge("amount", 100)
       .orderByDesc("created_at");

// LambdaQueryWrapper（类型安全，推荐）
LambdaQueryWrapper<Order> wrapper = Wrappers.lambdaQuery();
wrapper.eq(Order::getStatus, "PAID")
       .ge(Order::getAmount, new BigDecimal("100"))
       .orderByDesc(Order::getCreatedAt);

// 复杂条件
wrapper.and(w -> w.eq(Order::getStatus, "PAID")
                   .or().eq(Order::getStatus, "SHIPPED"))
       .in(Order::getUserId, List.of(1L, 2L, 3L))
       .like(Order::getOrderNo, "ORD")
       .between(Order::getCreatedAt, start, end)
       .isNotNull(Order::getPaymentId);

// 只查指定字段
wrapper.select(Order::getId, Order::getOrderNo, Order::getAmount);
```

---

## 4. 分页

### 4.1 MyBatis-Plus 分页

```java
@Configuration
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

```java
// 使用
@Service
public class OrderService extends ServiceImpl<OrderMapper, Order> {

    public PageResult<Order> pageQuery(int page, int size, String status) {
        Page<Order> page = lambdaQuery()
            .eq(Order::getStatus, status)
            .page(new Page<>(page, size));

        return PageResult.of(page.getRecords(), page.getTotal(), page.getPages());
    }
}
```

### 4.2 原生 MyBatis 分页

```xml
<select id="findByPage" resultType="Order">
    SELECT * FROM orders
    <where>
        <if test="status != null">
            AND status = #{status}
        </if>
    </where>
    ORDER BY created_at DESC
</select>
```

```java
// 使用 PageHelper
<dependency>
    <groupId>com.github.pagehelper</groupId>
    <artifactId>pagehelper-spring-boot-starter</artifactId>
</dependency>

PageHelper.startPage(page, size);
List<Order> list = orderMapper.findByStatus(status);
PageInfo<Order> pageInfo = new PageInfo<>(list);
```

---

## 5. 自动填充

```java
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime::now, LocalDateTime.class);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime::now, LocalDateTime.class);
        this.strictInsertFill(metaObject, "deleted", () -> 0, Integer.class);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime::now, LocalDateTime.class);
    }
}
```

```java
// 实体类加上注解
@Data
public class Order {

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

---

## 6. 逻辑删除

```yaml
mybatis-plus:
  global-config:
    db-config:
      logic-delete-field: deleted   # 逻辑删除字段
      logic-delete-value: 1          # 已删除值
      logic-not-delete-value: 0      # 未删除值
```

```java
// 实体类
@Data
public class Order {
    @TableLogic
    private Integer deleted;
}

// 之后 delete 操作变为 update
orderService.removeById(1L);
// → UPDATE orders SET deleted=1 WHERE id=1

// 查询自动追加条件
orderService.list();
// → SELECT * FROM orders WHERE deleted=0
```

---

## 7. 🔴 常见坑

```java
// 坑 1：MyBatis-Plus 字段名映射
// order_no → orderNo（map-underscore-to-camel-case）
// 但有些字段如 orderNo → 映射为 order_no ✅
// 如果字段名是 deleteFlag → delete_flag ❌（需手动 @TableField）

// 坑 2：批量操作性能
// ❌ 循环逐条操作
for (Order order : orders) {
    orderService.save(order);
}
// ✅ 批量
orderService.saveBatch(orders, 1000);

// 坑 3：Wrapper 重用
// ❌ Wrapper 用过之后状态改变
LambdaQueryWrapper<Order> wrapper = Wrappers.lambdaQuery();
wrapper.eq(Order::getStatus, "PAID");
orderService.list(wrapper);           // ✅
orderService.count(wrapper);          // ❌ wrapper 状态已变

// ✅ 每次用新的
orderService.list(Wrappers.lambdaQuery<Order>().eq(Order::getStatus, "PAID"));
orderService.count(Wrappers.lambdaQuery<Order>().eq(Order::getStatus, "PAID"));

// 坑 4：逻辑删除和唯一索引冲突
// 逻辑删除的 deleted=1，但唯一索引仍然存在
// 🟢 联合唯一索引 (order_no, deleted)
// 🟢 或删除时在 order_no 后加随机后缀
```

---

## 8. API 速查

```java
// BaseMapper 方法
insert(T entity)                       // 插入
deleteById(Serializable id)            // 按 ID 删除
deleteByMap(Map<String, Object>)       // 按条件删除
delete(Wrapper<T> wrapper)             // 按条件删除
updateById(T entity)                   // 按 ID 更新
update(T entity, Wrapper<T> wrapper)   // 按条件更新
selectById(Serializable id)            // 按 ID 查
selectByIds(Collection<?> idList)      // 批量查
selectByMap(Map<String, Object>)       // 按条件查
selectOne(Wrapper<T> wrapper)          // 查一条
selectCount(Wrapper<T> wrapper)        // 计数
selectList(Wrapper<T> wrapper)         // 查列表
selectPage(Page<T> page, Wrapper<T>)   // 分页

// IService 方法（ServiceImpl 实现）
save(T entity)
saveBatch(Collection<T> list)
saveOrUpdate(T entity)
removeById(Serializable id)
remove(Wrapper<T> wrapper)
updateById(T entity)
update(Wrapper<T> wrapper)
getById(Serializable id)
list()
list(Wrapper<T> wrapper)
page(Page<T> page, Wrapper<T> wrapper)
count(Wrapper<T> wrapper)
lambdaQuery()         // 链式查询
lambdaUpdate()        // 链式更新
```
