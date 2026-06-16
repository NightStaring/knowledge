# 领域驱动设计

> DDD 核心概念、分层架构、战术模式——从理论到落地。

---

## 1. 为什么需要 DDD

### 1.1 传统 CRUD 的问题

```
传统方式：
  UserService + UserDao + UserController
  所有逻辑在 Service 层
  业务逻辑散落在各种 Service 中
  没有清晰的业务边界

问题：
  1. 业务逻辑贫血（只有 getter/setter）
  2. 业务规则散落（校验在不同 Service 中重复）
  3. 难以理解（新人不看文档看不懂业务）
  4. 难以变更（改一个需求动多个 Service）
```

### 1.2 DDD 的解决思路

```
DDD 方式：
  按业务领域划分模块
  每个领域有明确的边界
  业务逻辑封装在领域模型中
  技术细节（数据库、缓存）在基础设施层
```

---

## 2. 核心概念

### 2.1 战略设计

**限界上下文（Bounded Context）：**

```
每个限界上下文是一个独立的业务领域
有自己的通用语言（Ubiquitous Language）

订单上下文：
  Order（订单）、OrderItem（订单项）、Payment（支付）

库存上下文：
  Product（商品）、Stock（库存）、Warehouse（仓库）

用户上下文：
  User（用户）、Address（地址）、Permission（权限）
```

**上下文映射：**

| 关系 | 说明 |
|------|------|
| 合作关系 | 两个上下文协同完成业务 |
| 共享内核 | 共享部分领域模型 |
| 客户-供应商 | 上游提供能力，下游使用 |
| 防腐层 | 隔离外部上下文的影响 |
| 开放主机服务 | 提供 API 给其他上下文 |

### 2.2 战术设计

```
实体（Entity）：
  有唯一标识，可变
  User、Order、Product

值对象（Value Object）：
  无唯一标识，不可变，通过属性比较
  Address、Money、PhoneNumber

聚合（Aggregate）：
  一组实体和值对象的组合
  聚合根是外部访问的唯一入口
  Order（聚合根）→ OrderItem（实体）

领域服务（Domain Service）：
  跨多个实体/值对象的业务逻辑
  没有状态

仓储（Repository）：
  领域对象的存储和检索
  接口在领域层，实现在基础设施层

工厂（Factory）：
  复杂对象的创建
```

---

## 3. 分层架构

### 3.1 四层架构

```
┌─────────────────────────────────────┐
│         接口层（Interfaces）          │
│  Controller、DTO、View               │
├─────────────────────────────────────┤
│         应用层（Application）          │
│  ApplicationService、DTO 转换        │
├─────────────────────────────────────┤
│         领域层（Domain）               │
│  Entity、ValueObject、Repository 接口 │
│  DomainService、DomainEvent          │
├─────────────────────────────────────┤
│       基础设施层（Infrastructure）     │
│  Repository 实现、JPA、MQ、Redis      │
└─────────────────────────────────────┘
```

### 3.2 代码结构

```
com.example.order
├── interfaces
│   ├── controller
│   │   └── OrderController.java
│   └── dto
│       ├── CreateOrderRequest.java
│       └── OrderResponse.java
│
├── application
│   ├── OrderApplicationService.java    # 应用服务
│   └── assembler
│       └── OrderAssembler.java         # DTO 转换
│
├── domain
│   ├── entity
│   │   └── Order.java                  # 聚合根
│   ├── valueobject
│   │   ├── Money.java
│   │   └── OrderStatus.java
│   ├── service
│   │   └── OrderDomainService.java     # 领域服务
│   ├── repository
│   │   └── OrderRepository.java        # 仓储接口
│   └── event
│       └── OrderCreatedEvent.java
│
└── infrastructure
    ├── persistence
    │   ├── OrderJpaRepository.java     # Spring Data JPA
    │   └── OrderRepositoryImpl.java    # 仓储实现
    └── messaging
        └── OrderEventPublisher.java
```

---

## 4. 战术模式示例

### 4.1 实体 + 值对象

```java
// 聚合根：Order
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private String orderId;          // 业务主键，不是自增 ID

    private String userId;

    @Embedded
    private Money totalAmount;       // 值对象

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @OneToMany(cascade = ALL, mappedBy = "order")
    private List<OrderItem> items;

    // 业务行为，不是 getter/setter

    public void addItem(Product product, int quantity) {
        // 业务规则封装在实体中
        if (this.status != OrderStatus.PENDING) {
            throw new IllegalStateException("只能修改待支付的订单");
        }

        OrderItem item = new OrderItem(this, product, quantity);
        this.items.add(item);
        recalculateTotal();
    }

    public void submit() {
        if (this.items.isEmpty()) {
            throw new IllegalStateException("订单不能为空");
        }
        this.status = OrderStatus.SUBMITTED;
    }

    public void pay() {
        if (this.status != OrderStatus.SUBMITTED) {
            throw new IllegalStateException("订单状态不正确");
        }
        this.status = OrderStatus.PAID;
    }

    private void recalculateTotal() {
        this.totalAmount = Money.of(items.stream()
            .map(OrderItem::getSubtotal)
            .reduce(Money.ZERO, Money::add));
    }
}

// 值对象：Money
@Embeddable
public class Money {

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "currency")
    private String currency;

    protected Money() { }  // JPA

    private Money(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount, "CNY");
    }

    public Money add(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("货币不一致");
        }
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money multiply(int quantity) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(quantity)), this.currency);
    }

    // 只有 getter，没有 setter（不可变）
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
}
```

### 4.2 领域服务

```java
/**
 * 领域服务：跨多个聚合的业务逻辑
 *
 * 例如：下单涉及 Order（订单聚合）和 Product（商品聚合）
 * 放在领域服务中，不在任何一个实体中
 */
@Service
public class OrderDomainService {

    @Transactional
    public Order createOrder(String userId, List<OrderItemParam> items) {
        // 1. 创建订单聚合
        Order order = new Order(userId);

        for (OrderItemParam param : items) {
            // 2. 获取商品信息（调用商品仓储）
            Product product = productRepository.findById(param.getProductId());

            // 3. 检查库存
            if (product.getStock() < param.getQuantity()) {
                throw new InsufficientStockException(product.getId());
            }

            // 4. 添加到订单
            order.addItem(product, param.getQuantity());
        }

        // 5. 提交订单
        order.submit();

        // 6. 保存
        orderRepository.save(order);

        // 7. 发布事件
        eventPublisher.publish(new OrderCreatedEvent(order.getOrderId()));

        return order;
    }
}
```

### 4.3 仓储

```java
// 领域层：仓储接口
public interface OrderRepository {
    Order findById(String orderId);
    void save(Order order);
    void delete(String orderId);
}

// 基础设施层：仓储实现
@Repository
public class OrderRepositoryImpl implements OrderRepository {

    @Autowired
    private OrderJpaRepository jpaRepository;

    @Override
    public Order findById(String orderId) {
        return jpaRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Override
    public void save(Order order) {
        jpaRepository.save(order);
    }

    @Override
    public void delete(String orderId) {
        jpaRepository.deleteById(orderId);
    }
}

// JPA 接口
public interface OrderJpaRepository extends JpaRepository<Order, String> {
    // JPA 特有的查询
    List<Order> findByUserId(String userId);
}
```

### 4.4 应用服务

```java
/**
 * 应用服务：协调领域模型完成业务
 *
 * 职责：
 *   1. 事务控制
 *   2. 权限检查
 *   3. DTO 转换
 *   4. 调用领域服务
 *
 * 应用服务不应该包含业务逻辑！
 */
@Service
public class OrderApplicationService {

    @Autowired
    private OrderDomainService orderDomainService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderAssembler orderAssembler;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request, String currentUserId) {
        // 权限检查
        // ...

        // 调用领域服务
        Order order = orderDomainService.createOrder(currentUserId, request.getItems());

        // 返回 DTO
        return orderAssembler.toResponse(order);
    }

    public OrderResponse getOrder(String orderId, String currentUserId) {
        Order order = orderRepository.findById(orderId);

        // 数据权限检查
        if (!order.getUserId().equals(currentUserId)) {
            throw new AccessDeniedException("无权访问此订单");
        }

        return orderAssembler.toResponse(order);
    }
}
```

---

## 5. 防腐层（Anti-Corruption Layer）

```java
/**
 * 防腐层：隔离外部上下文对本领域的影响
 *
 * 例如：用户上下文需要从外部订单上下文获取数据
 * 但不想直接依赖订单上下文的模型
 */
@Service
public class UserOrderAclService {

    @Autowired
    private OrderFeignClient orderFeignClient;

    @Autowired
    private UserOrderConverter converter;

    /**
     * 防腐层：将外部订单上下文的数据转换为本上下文的模型
     */
    public List<UserOrder> getUserOrders(String userId) {
        // 1. 调用外部服务的 API
        OrderResponseDTO[] orderDTOs = orderFeignClient.getOrdersByUserId(userId);

        // 2. 转换为本上下文的模型
        return converter.toUserOrders(orderDTOs);
    }
}
```

---

## 6. 🔴 常见坑

```java
// 坑 1：将 DDD 做成 CRUD
// ❌ 实体只有 getter/setter，没有业务方法
@Data
@Entity
public class Order {
    private String status;

    // ❌ 在 Service 中修改状态
}

// ✅ 实体封装业务行为
public class Order {
    private String status;

    public void pay() {
        if (!"SUBMITTED".equals(this.status)) {
            throw new IllegalStateException("...");
        }
        this.status = "PAID";
    }
}

// 坑 2：聚合过大
// ❌ 一个聚合包含所有关联
public class Order {
    private User user;           // ❌ 不应在订单聚合中
    private List<Payment> payments;  // ❌ 支付应独立
    private List<Logistics> logistics; // ❌ 物流应独立
}

// ✅ 聚合尽量小
public class Order {
    private String userId;       // 只存 ID
    private List<OrderItem> items;
}

// 坑 3：过度设计
// 小项目强行 DDD，复杂度远大于收益
// 🟢 根据项目规模选择合适的方式
```

---

## 7. DDD 适用场景

| 适合 DDD | 不适合 DDD |
|----------|------------|
| 业务逻辑复杂 | 简单 CRUD |
| 多人协作 | 个人项目 |
| 长期演进 | 一次性原型 |
| 核心业务域 | 通用子域 |

**建议：** 核心业务用 DDD 建模，简单 CRUD 用传统方式。
