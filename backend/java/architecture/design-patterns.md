# 设计模式

> 企业级 Java 开发中最常用的设计模式，聚焦实战场景，不讲理论定义。

---

## 1. 策略模式（Strategy）

### 1.1 场景

```java
// ❌ 一堆 if-else
public BigDecimal calculatePrice(String userLevel, BigDecimal amount) {
    if ("NORMAL".equals(userLevel)) {
        return amount;
    } else if ("VIP".equals(userLevel)) {
        return amount.multiply(new BigDecimal("0.9"));
    } else if ("SVIP".equals(userLevel)) {
        return amount.multiply(new BigDecimal("0.8"));
    }
    // 加一个新等级就要改这里 → 违反开闭原则
}
```

### 1.2 实现

```java
// 策略接口
public interface PriceStrategy {
    BigDecimal calculate(BigDecimal amount);
    String getLevel();  // 标识
}

// 具体策略
@Component
public class NormalPriceStrategy implements PriceStrategy {
    @Override
    public BigDecimal calculate(BigDecimal amount) {
        return amount;
    }
    @Override
    public String getLevel() { return "NORMAL"; }
}

@Component
public class VipPriceStrategy implements PriceStrategy {
    @Override
    public BigDecimal calculate(BigDecimal amount) {
        return amount.multiply(new BigDecimal("0.9"));
    }
    @Override
    public String getLevel() { return "VIP"; }
}

@Component
public class SvipPriceStrategy implements PriceStrategy {
    @Override
    public BigDecimal calculate(BigDecimal amount) {
        return amount.multiply(new BigDecimal("0.8"));
    }
    @Override
    public String getLevel() { return "SVIP"; }
}

// 策略上下文
@Component
public class PriceStrategyContext {

    private final Map<String, PriceStrategy> strategyMap;

    @Autowired
    public PriceStrategyContext(List<PriceStrategy> strategies) {
        // Spring 自动收集所有 PriceStrategy 实现
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(PriceStrategy::getLevel, Function.identity()));
    }

    public BigDecimal calculate(String level, BigDecimal amount) {
        PriceStrategy strategy = strategyMap.get(level);
        if (strategy == null) {
            throw new IllegalArgumentException("未知等级: " + level);
        }
        return strategy.calculate(amount);
    }
}

// 使用
@Service
public class OrderService {
    @Autowired
    private PriceStrategyContext priceStrategy;

    public void createOrder(Order order) {
        BigDecimal price = priceStrategy.calculate(
            order.getUserLevel(), order.getAmount());
        order.setActualAmount(price);
    }
}
```

---

## 2. 模板方法模式（Template Method）

### 2.1 场景

```java
// 多个 Service 有相同的流程骨架，细节不同
// 下单、退款、发货都要：校验 → 执行业务 → 记录日志 → 发送通知
```

### 2.2 实现

```java
// 抽象模板
public abstract class AbstractBizService<T, R> {

    // 模板方法：定义流程骨架
    public R execute(T request) {
        // 1. 参数校验
        validate(request);

        // 2. 业务校验（子类实现）
        checkBiz(request);

        // 3. 执行业务（子类实现）
        R result = doBiz(request);

        // 4. 后处理
        postProcess(request, result);

        // 5. 记录日志
        logBiz(request, result);

        return result;
    }

    protected void validate(T request) {
        // 通用校验
        if (request == null) {
            throw new IllegalArgumentException("参数不能为空");
        }
    }

    protected abstract void checkBiz(T request);     // 子类实现
    protected abstract R doBiz(T request);           // 子类实现

    protected void postProcess(T request, R result) {
        // 可选钩子
    }

    private void logBiz(T request, R result) {
        log.info("业务处理: request={}, result={}", request, result);
    }
}

// 具体实现：创建订单
@Component
public class CreateOrderService extends AbstractBizService<CreateOrderRequest, OrderResponse> {

    @Override
    protected void checkBiz(CreateOrderRequest request) {
        if (request.getItems().isEmpty()) {
            throw new BusinessException("订单不能为空");
        }
    }

    @Override
    protected OrderResponse doBiz(CreateOrderRequest request) {
        // 创建订单逻辑
        return new OrderResponse();
    }
}

// 具体实现：退款
@Component
public class RefundService extends AbstractBizService<RefundRequest, RefundResponse> {

    @Override
    protected void checkBiz(RefundRequest request) {
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("退款金额必须大于 0");
        }
    }

    @Override
    protected RefundResponse doBiz(RefundRequest request) {
        // 退款逻辑
        return new RefundResponse();
    }
}
```

---

## 3. 工厂模式（Factory）

### 3.1 场景

```java
// 根据不同类型创建不同策略或处理器的场景
```

### 3.2 实现

```java
// 简单工厂
@Component
public class PaymentFactory {

    @Autowired
    private List<PaymentHandler> handlers;

    private final Map<String, PaymentHandler> handlerMap = new HashMap<>();

    @PostConstruct
    public void init() {
        for (PaymentHandler handler : handlers) {
            handlerMap.put(handler.getType(), handler);
        }
    }

    public PaymentHandler getHandler(String type) {
        PaymentHandler handler = handlerMap.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("不支持的支付类型: " + type);
        }
        return handler;
    }
}

// 使用
@RestController
public class PaymentController {

    @Autowired
    private PaymentFactory paymentFactory;

    @PostMapping("/api/payments")
    public void pay(@RequestBody PaymentRequest request) {
        PaymentHandler handler = paymentFactory.getHandler(request.getType());
        handler.pay(request);
    }
}
```

---

## 4. 建造者模式（Builder）

### 4.1 场景

```java
// 对象构造参数多，且部分可选
```

### 4.2 实现

```java
// 方式 1：Lombok @Builder
@Data
@Builder
public class SearchRequest {
    private String keyword;
    private String status;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer page;
    private Integer size;
    private String sortBy;
    private String sortOrder;
}

// 使用
SearchRequest request = SearchRequest.builder()
    .keyword("手机")
    .status("PAID")
    .minAmount(new BigDecimal("100"))
    .page(1)
    .size(20)
    .build();

// 方式 2：链式调用（不用 Lombok）
public class QueryBuilder {
    private final StringBuilder sql = new StringBuilder();
    private final List<Object> params = new ArrayList<>();

    public QueryBuilder select(String... fields) {
        sql.append("SELECT ");
        sql.append(String.join(", ", fields));
        return this;
    }

    public QueryBuilder from(String table) {
        sql.append(" FROM ").append(table);
        return this;
    }

    public QueryBuilder where(String condition, Object value) {
        sql.append(" WHERE ").append(condition);
        params.add(value);
        return this;
    }

    public QueryBuilder and(String condition, Object value) {
        sql.append(" AND ").append(condition);
        params.add(value);
        return this;
    }

    public QueryBuilder orderBy(String field, boolean asc) {
        sql.append(" ORDER BY ").append(field)
           .append(asc ? " ASC" : " DESC");
        return this;
    }

    public BuiltQuery build() {
        return new BuiltQuery(sql.toString(), params);
    }
}

// 使用
BuiltQuery query = new QueryBuilder()
    .select("id", "name", "amount")
    .from("orders")
    .where("status = ?", "PAID")
    .and("amount > ?", new BigDecimal("100"))
    .orderBy("created_at", false)
    .build();
```

---

## 5. 观察者模式（Observer）/ 事件

### 5.1 场景

```java
// 一个业务操作触发多个后续动作
// 下单后：发短信、减库存、加积分、推送消息
```

### 5.2 实现

```java
// 事件
public class OrderCreatedEvent extends ApplicationEvent {
    private final Order order;

    public OrderCreatedEvent(Order order) {
        super(order);
        this.order = order;
    }
    public Order getOrder() { return order; }
}

// 发布事件
@Service
public class OrderService {

    @Autowired
    private ApplicationEventPublisher publisher;

    @Transactional
    public void createOrder(Order order) {
        // 保存订单
        save(order);

        // 发布事件
        publisher.publishEvent(new OrderCreatedEvent(order));
    }
}

// 监听事件（解耦）
@Component
public class SmsListener {
    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        // 发送短信通知
        smsService.send(event.getOrder().getUserId(), "订单创建成功");
    }
}

@Component
public class InventoryListener {
    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        // 扣减库存
        inventoryService.deduct(event.getOrder().getItems());
    }
}

@Component
public class PointsListener {
    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        // 增加积分
        pointsService.add(event.getOrder().getUserId(),
            event.getOrder().getAmount().intValue());
    }
}
```

---

## 6. 适配器模式（Adapter）

### 6.1 场景

```java
// 统一外部接口的调用方式
// 对接多个第三方支付：微信、支付宝、银联
```

### 6.2 实现

```java
// 统一接口
public interface PaymentAdapter {
    boolean pay(PaymentRequest request);
    boolean refund(RefundRequest request);
    PaymentStatus query(String orderNo);
}

// 微信适配器
@Component
public class WechatPaymentAdapter implements PaymentAdapter {

    @Autowired
    private WechatPayClient wechatPayClient;  // 微信 SDK

    @Override
    public boolean pay(PaymentRequest request) {
        // 适配微信的 API
        WechatPayRequest wechatReq = new WechatPayRequest();
        wechatReq.setOutTradeNo(request.getOrderNo());
        wechatReq.setTotalFee(request.getAmount().multiply(new BigDecimal("100")).intValue());
        wechatReq.setBody(request.getSubject());

        WechatPayResponse response = wechatPayClient.pay(wechatReq);
        return response.isSuccess();
    }

    @Override
    public boolean refund(RefundRequest request) {
        // 适配微信退款
    }

    @Override
    public PaymentStatus query(String orderNo) {
        // 适配微信查询
    }
}

// 支付宝适配器
@Component
public class AlipayPaymentAdapter implements PaymentAdapter {

    @Autowired
    private AlipayClient alipayClient;  // 支付宝 SDK

    @Override
    public boolean pay(PaymentRequest request) {
        // 适配支付宝的 API
        AlipayRequest alipayReq = new AlipayRequest();
        alipayReq.setOutTradeNo(request.getOrderNo());
        alipayReq.setTotalAmount(request.getAmount().toString());
        alipayReq.setSubject(request.getSubject());

        AlipayResponse response = alipayClient.pay(alipayReq);
        return response.isSuccess();
    }

    // ...
}
```

---

## 7. 责任链模式（Chain of Responsibility）

### 7.1 场景

```java
// 请求经过多个处理环节，每个环节决定是否继续
// 订单校验：参数校验 → 权限校验 → 库存校验 → 风控校验
```

### 7.2 实现

```java
// 处理器接口
public interface OrderValidator {
    void validate(Order order, ValidationContext context);
}

// 参数校验
@Component
public class ParamValidator implements OrderValidator {
    @Override
    public void validate(Order order, ValidationContext context) {
        if (order.getItems().isEmpty()) {
            context.setFailed(true);
            context.setMessage("订单项不能为空");
        }
    }
}

// 库存校验
@Component
public class StockValidator implements OrderValidator {
    @Override
    public void validate(Order order, ValidationContext context) {
        if (context.isFailed()) return;  // 前面已失败

        for (OrderItem item : order.getItems()) {
            if (inventoryService.getStock(item.getProductId()) < item.getQuantity()) {
                context.setFailed(true);
                context.setMessage("库存不足: " + item.getProductId());
                return;
            }
        }
    }
}

// 风控校验
@Component
public class RiskValidator implements OrderValidator {
    @Override
    public void validate(Order order, ValidationContext context) {
        if (context.isFailed()) return;

        if (riskService.isHighRisk(order.getUserId(), order.getAmount())) {
            context.setFailed(true);
            context.setMessage("订单触发风控");
        }
    }
}

// 校验链
@Component
public class OrderValidationChain {

    @Autowired
    private List<OrderValidator> validators;  // Spring 自动注入所有实现

    public ValidationContext execute(Order order) {
        ValidationContext context = new ValidationContext();
        for (OrderValidator validator : validators) {
            validator.validate(order, context);
            if (context.isFailed()) {
                break;  // 校验失败，中断链
            }
        }
        return context;
    }
}
```

---

## 8. 🔴 常见坑

```java
// 坑 1：过度设计
// ❌ 只有一种实现也用策略模式
// ❌ 只有两个步骤也用模板方法

// 🟢 原则：什么时候重构？
// 1. 第三次出现相似的代码
// 2. 新增功能需要修改多个地方
// 3. 代码难以测试

// 坑 2：模式与框架混用
// Spring 本身就是各种模式的集大成者：
//   IoC → 工厂模式
//   AOP → 代理模式
//   Template → 模板方法
//   Listener → 观察者模式
// 🟢 尽量利用框架已有的能力

// 坑 3：Spring 事件是同步的
@EventListener
public void handle(OrderCreatedEvent event) {
    // 默认同步执行！监听器抛异常会影响主流程
}
// 🟢 异步
@Async
@EventListener
public void handle(OrderCreatedEvent event) {
    // 异步执行
}
```

---

## 9. 模式速查

| 模式 | 场景 | 核心 |
|------|------|------|
| 策略模式 | 多种算法/行为选择 | 接口 + 实现 + 上下文 |
| 模板方法 | 流程骨架固定，细节可变 | 抽象类 + 钩子方法 |
| 工厂模式 | 创建对象逻辑复杂 | 工厂类集中创建 |
| 建造者模式 | 构造参数多 | 链式调用 |
| 观察者模式 | 一对多通知 | 事件 + 监听器 |
| 适配器模式 | 统一外部接口 | 接口适配 |
| 责任链模式 | 请求多环节处理 | 链式校验 |
| 单例模式 | 全局唯一 | Spring Bean 默认 |
| 代理模式 | 增强方法 | AOP 代理 |
