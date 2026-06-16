/**
 * Spring 核心示例
 * ===============
 *
 * 演示：
 *   1. 策略模式 + Spring 自动注入
 *   2. @Transactional 传播行为
 *   3. 事件驱动
 *   4. @ConfigurationProperties
 */

package com.example.spring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

// ================================================================
// 1. 策略模式 + Spring 自动收集
// ================================================================

/** 价格策略接口 */
interface PriceStrategy {
    String getLevel();           // 标识
    BigDecimal calculate(BigDecimal amount);
}

@Component
class NormalPriceStrategy implements PriceStrategy {
    @Override
    public String getLevel() { return "NORMAL"; }

    @Override
    public BigDecimal calculate(BigDecimal amount) {
        return amount;
    }
}

@Component
class VipPriceStrategy implements PriceStrategy {
    @Override
    public String getLevel() { return "VIP"; }

    @Override
    public BigDecimal calculate(BigDecimal amount) {
        return amount.multiply(new BigDecimal("0.9"));  // 9 折
    }
}

@Component
class SvipPriceStrategy implements PriceStrategy {
    @Override
    public String getLevel() { return "SVIP"; }

    @Override
    public BigDecimal calculate(BigDecimal amount) {
        return amount.multiply(new BigDecimal("0.8"));  // 8 折
    }
}

/**
 * 策略上下文
 *
 * Spring 自动收集所有 PriceStrategy 实现
 * 加新等级只需加实现类，不用改任何代码
 */
@Component
class PriceContext {

    private final Map<String, PriceStrategy> strategyMap;

    @Autowired
    PriceContext(List<PriceStrategy> strategies) {
        this.strategyMap = strategies.stream()
            .collect(Collectors.toMap(PriceStrategy::getLevel, Function.identity()));
    }

    BigDecimal calculate(String level, BigDecimal amount) {
        PriceStrategy strategy = strategyMap.get(level);
        if (strategy == null) {
            throw new IllegalArgumentException("未知等级: " + level);
        }
        return strategy.calculate(amount);
    }
}

// ================================================================
// 2. @Transactional 传播行为演示
// ================================================================

@Service
class OrderService {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private LogService logService;

    /**
     * REQUIRED（默认）：内外方法在同一事务
     *
     * 支付失败 → 订单创建也回滚
     */
    @Transactional
    public void createOrder(Order order) {
        saveOrder(order);                        // 事务 T1
        paymentService.pay(order.getId());       // 也在 T1 中
        // pay 抛异常 → saveOrder 也回滚
    }

    /**
     * REQUIRES_NEW：独立事务
     *
     * 日志记录失败不影响主流程
     */
    @Transactional
    public void processWithLog(Order order) {
        saveOrder(order);                        // 事务 T1
        try {
            logService.log("处理订单", order.getId());  // 独立事务 T2
        } catch (Exception e) {
            // T2 失败不影响 T1
        }
    }

    /**
     * NESTED：嵌套事务（Savepoint）
     *
     * 单个商品处理失败只回滚该商品
     */
    @Transactional
    public void batchProcess(List<OrderItem> items) {
        for (OrderItem item : items) {
            try {
                processItem(item);  // 每个商品独立 Savepoint
            } catch (Exception e) {
                // 单个失败不影响其他
            }
        }
    }

    @Transactional(propagation = Propagation.NESTED)
    public void processItem(OrderItem item) {
        // 处理单个商品
    }

    /**
     * 事务后置操作
     *
     * 事务提交后才执行（发消息、写日志等）
     */
    @Transactional
    public void createAndNotify(Order order) {
        saveOrder(order);

        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 事务提交后才执行
                    sendNotification(order);
                }
            }
        );
    }

    private void saveOrder(Order order) { /* ... */ }
    private void sendNotification(Order order) { /* ... */ }
}

@Service
class PaymentService {
    @Transactional(propagation = Propagation.REQUIRED)
    public void pay(Long orderId) { /* ... */ }
}

@Service
class LogService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, Long orderId) { /* ... */ }
}

// ================================================================
// 3. 事件驱动
// ================================================================

/** 订单创建事件 */
record OrderCreatedEvent(Long orderId, Long userId, BigDecimal amount) {}

/**
 * 事件发布者
 */
@Component
class OrderEventPublisher {

    @Autowired
    private ApplicationEventPublisher publisher;

    public void publishOrderCreated(Order order) {
        publisher.publishEvent(
            new OrderCreatedEvent(order.getId(), order.getUserId(), order.getAmount())
        );
    }
}

/**
 * 事件监听器
 *
 * 解耦：下单后发短信、减库存、加积分
 */
@Component
class OrderEventListeners {

    @Async          // 异步执行
    @EventListener
    public void handleSms(OrderCreatedEvent event) {
        // 发送短信通知
        System.out.println("发送短信: 订单 " + event.orderId() + " 创建成功");
    }

    @Async
    @EventListener
    public void handleInventory(OrderCreatedEvent event) {
        // 扣减库存
        System.out.println("扣减库存: " + event.orderId());
    }

    @Async
    @EventListener
    public void handlePoints(OrderCreatedEvent event) {
        // 增加积分
        System.out.println("增加积分: " + event.amount());
    }
}

// ================================================================
// 4. @ConfigurationProperties
// ================================================================

@ConfigurationProperties(prefix = "app.order")
@Component
class OrderProperties {
    private int timeout = 30;
    private int maxQuantity = 99;
    private List<String> supportedPayments = new ArrayList<>();
    private Security security = new Security();

    // getter/setter ...
    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }
    public int getMaxQuantity() { return maxQuantity; }
    public void setMaxQuantity(int maxQuantity) { this.maxQuantity = maxQuantity; }
    public List<String> getSupportedPayments() { return supportedPayments; }
    public void setSupportedPayments(List<String> supportedPayments) { this.supportedPayments = supportedPayments; }
    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }

    public static class Security {
        private boolean enabled = true;
        private String tokenSecret;
        // getter/setter ...
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTokenSecret() { return tokenSecret; }
        public void setTokenSecret(String tokenSecret) { this.tokenSecret = tokenSecret; }
    }
}

// ================================================================
// 5. 实体 + 值对象（DDD 风格）
// ================================================================

record Money(BigDecimal amount, String currency) {
    static Money of(BigDecimal amount) {
        return new Money(amount, "CNY");
    }

    Money add(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("货币不一致");
        }
        return new Money(this.amount.add(other.amount), this.currency);
    }
}

class Order {
    private Long id;
    private Long userId;
    private String status;
    private Money totalAmount;
    private LocalDateTime createdAt;

    // 业务行为
    void submit() {
        if ("PENDING".equals(status)) {
            this.status = "SUBMITTED";
        } else {
            throw new IllegalStateException("状态不正确: " + status);
        }
    }

    void pay() {
        if (!"SUBMITTED".equals(status)) {
            throw new IllegalStateException("只能支付已提交的订单");
        }
        this.status = "PAID";
    }

    // getter/setter ...
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Money getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Money totalAmount) { this.totalAmount = totalAmount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

// ================================================================
// 6. AOP 切面
// ================================================================

// @Aspect
// @Component
class LoggingAspect {
    // @Around("execution(* com.example.service.*.*(..))")
    // public Object logExecutionTime(ProceedingJoinPoint pjp) throws Throwable {
    //     long start = System.currentTimeMillis();
    //     try {
    //         return pjp.proceed();
    //     } finally {
    //         long elapsed = System.currentTimeMillis() - start;
    //         System.out.println(pjp.getSignature() + " 耗时: " + elapsed + "ms");
    //     }
    // }
}

// ================================================================
// 7. 数据模型
// ================================================================

class OrderItem {
    private Long id;
    private Long orderId;
    private String productId;
    private int quantity;
    private BigDecimal price;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
}
