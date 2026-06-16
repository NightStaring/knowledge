/**
 * 架构设计模式示例
 * ================
 *
 * 演示：
 *   1. 策略模式（价格计算）
 *   2. 模板方法（业务处理骨架）
 *   3. 观察者模式（事件驱动）
 *   4. 责任链模式（校验链）
 *   5. 适配器模式（第三方支付）
 *   6. 建造者模式（复杂对象构建）
 */

package com.example.architecture;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

// ================================================================
// 1. 策略模式
// ================================================================

interface NotificationStrategy {
    void send(String userId, String message);
    String getType();
}

class SmsNotification implements NotificationStrategy {
    @Override
    public String getType() { return "SMS"; }

    @Override
    public void send(String userId, String message) {
        // 调用短信 SDK
        System.out.println("发送短信给 " + userId + ": " + message);
    }
}

class EmailNotification implements NotificationStrategy {
    @Override
    public String getType() { return "EMAIL"; }

    @Override
    public void send(String userId, String message) {
        // 调用邮件 SDK
        System.out.println("发送邮件给 " + userId + ": " + message);
    }
}

class PushNotification implements NotificationStrategy {
    @Override
    public String getType() { return "PUSH"; }

    @Override
    public void send(String userId, String message) {
        // 调用推送 SDK
        System.out.println("推送消息给 " + userId + ": " + message);
    }
}

class NotificationContext {

    private final Map<String, NotificationStrategy> strategyMap = new HashMap<>();

    NotificationContext(List<NotificationStrategy> strategies) {
        strategies.forEach(s -> strategyMap.put(s.getType(), s));
    }

    void send(String type, String userId, String message) {
        NotificationStrategy strategy = strategyMap.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的通知类型: " + type);
        }
        strategy.send(userId, message);
    }
}

// ================================================================
// 2. 模板方法
// ================================================================

abstract class AbstractPaymentHandler {

    /**
     * 模板方法：定义支付流程骨架
     */
    final PaymentResult handle(PaymentRequest request) {
        // 1. 参数校验
        validate(request);

        // 2. 业务校验（子类实现）
        checkBiz(request);

        // 3. 执行支付（子类实现）
        PaymentResult result = doPay(request);

        // 4. 后处理
        postProcess(request, result);

        // 5. 记录
        logPayment(request, result);

        return result;
    }

    protected void validate(PaymentRequest request) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("金额必须大于 0");
        }
    }

    protected abstract void checkBiz(PaymentRequest request);
    protected abstract PaymentResult doPay(PaymentRequest request);

    protected void postProcess(PaymentRequest request, PaymentResult result) {
        // 可选钩子
    }

    private void logPayment(PaymentRequest request, PaymentResult result) {
        System.out.println("支付记录: " + request.getOrderNo() + " → " + result.isSuccess());
    }
}

class WechatPaymentHandler extends AbstractPaymentHandler {
    @Override
    protected void checkBiz(PaymentRequest request) {
        if (request.getAmount().compareTo(new BigDecimal("50000")) > 0) {
            throw new IllegalArgumentException("微信单笔限额 50000");
        }
    }

    @Override
    protected PaymentResult doPay(PaymentRequest request) {
        // 调用微信 SDK
        return new PaymentResult(true, "支付成功");
    }
}

class AlipayPaymentHandler extends AbstractPaymentHandler {
    @Override
    protected void checkBiz(PaymentRequest request) {
        // 支付宝无需特殊校验
    }

    @Override
    protected PaymentResult doPay(PaymentRequest request) {
        // 调用支付宝 SDK
        return new PaymentResult(true, "支付成功");
    }
}

// ================================================================
// 3. 责任链模式（校验链）
// ================================================================

interface OrderValidator {
    void validate(Order order, ValidationContext context);
}

class ParamValidator implements OrderValidator {
    @Override
    public void validate(Order order, ValidationContext context) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            context.setFailed(true);
            context.setMessage("订单项不能为空");
        }
        if (order.getAmount() == null || order.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            context.setFailed(true);
            context.setMessage("金额必须大于 0");
        }
    }
}

class StockValidator implements OrderValidator {
    private final Map<String, Integer> stockDb = Map.of("P001", 100, "P002", 50);

    @Override
    public void validate(Order order, ValidationContext context) {
        if (context.isFailed()) return;

        for (OrderItem item : order.getItems()) {
            int available = stockDb.getOrDefault(item.getProductId(), 0);
            if (available < item.getQuantity()) {
                context.setFailed(true);
                context.setMessage("库存不足: " + item.getProductId());
                return;
            }
        }
    }
}

class RiskValidator implements OrderValidator {
    @Override
    public void validate(Order order, ValidationContext context) {
        if (context.isFailed()) return;

        // 模拟风控规则
        if (order.getAmount().compareTo(new BigDecimal("100000")) > 0) {
            context.setFailed(true);
            context.setMessage("大额订单触发风控");
        }
    }
}

class ValidationChain {

    private final List<OrderValidator> validators = List.of(
        new ParamValidator(),
        new StockValidator(),
        new RiskValidator()
    );

    ValidationContext execute(Order order) {
        ValidationContext context = new ValidationContext();
        for (OrderValidator validator : validators) {
            validator.validate(order, context);
            if (context.isFailed()) {
                break;
            }
        }
        return context;
    }
}

// ================================================================
// 4. 适配器模式（第三方支付）
// ================================================================

interface PaymentAdapter {
    boolean pay(PaymentRequest request);
    boolean refund(String orderNo, BigDecimal amount);
    String query(String orderNo);
}

class WechatPayAdapter implements PaymentAdapter {
    @Override
    public boolean pay(PaymentRequest request) {
        // 适配微信 API
        System.out.println("微信支付: " + request.getOrderNo());
        return true;
    }

    @Override
    public boolean refund(String orderNo, BigDecimal amount) {
        System.out.println("微信退款: " + orderNo);
        return true;
    }

    @Override
    public String query(String orderNo) {
        return "SUCCESS";
    }
}

class AlipayAdapter implements PaymentAdapter {
    @Override
    public boolean pay(PaymentRequest request) {
        // 适配支付宝 API
        System.out.println("支付宝支付: " + request.getOrderNo());
        return true;
    }

    @Override
    public boolean refund(String orderNo, BigDecimal amount) {
        System.out.println("支付宝退款: " + orderNo);
        return true;
    }

    @Override
    public String query(String orderNo) {
        return "TRADE_SUCCESS";
    }
}

class PaymentFactory {
    private final Map<String, PaymentAdapter> adapters = Map.of(
        "WECHAT", new WechatPayAdapter(),
        "ALIPAY", new AlipayAdapter()
    );

    PaymentAdapter getAdapter(String type) {
        PaymentAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException("不支持的支付方式: " + type);
        }
        return adapter;
    }
}

// ================================================================
// 5. 建造者模式
// ================================================================

class SearchRequest {
    private final String keyword;
    private final String status;
    private final BigDecimal minAmount;
    private final BigDecimal maxAmount;
    private final LocalDateTime startDate;
    private final LocalDateTime endDate;
    private final int page;
    private final int size;
    private final String sortBy;
    private final boolean asc;

    private SearchRequest(Builder builder) {
        this.keyword = builder.keyword;
        this.status = builder.status;
        this.minAmount = builder.minAmount;
        this.maxAmount = builder.maxAmount;
        this.startDate = builder.startDate;
        this.endDate = builder.endDate;
        this.page = builder.page;
        this.size = builder.size;
        this.sortBy = builder.sortBy;
        this.asc = builder.asc;
    }

    static Builder builder() { return new Builder(); }

    static class Builder {
        private String keyword;
        private String status;
        private BigDecimal minAmount;
        private BigDecimal maxAmount;
        private LocalDateTime startDate;
        private LocalDateTime endDate;
        private int page = 1;
        private int size = 20;
        private String sortBy = "createdAt";
        private boolean asc = false;

        Builder keyword(String keyword) { this.keyword = keyword; return this; }
        Builder status(String status) { this.status = status; return this; }
        Builder minAmount(BigDecimal minAmount) { this.minAmount = minAmount; return this; }
        Builder maxAmount(BigDecimal maxAmount) { this.maxAmount = maxAmount; return this; }
        Builder dateRange(LocalDateTime start, LocalDateTime end) {
            this.startDate = start; this.endDate = end; return this;
        }
        Builder page(int page) { this.page = page; return this; }
        Builder size(int size) { this.size = size; return this; }
        Builder sortBy(String sortBy, boolean asc) { this.sortBy = sortBy; this.asc = asc; return this; }

        SearchRequest build() {
            if (page < 1) throw new IllegalArgumentException("page 必须 >= 1");
            if (size < 1 || size > 100) throw new IllegalArgumentException("size 范围 1~100");
            return new SearchRequest(this);
        }
    }
}

// ================================================================
// 6. 数据模型
// ================================================================

class PaymentRequest {
    private String orderNo;
    private BigDecimal amount;
    private String subject;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
}

class PaymentResult {
    private final boolean success;
    private final String message;

    PaymentResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
}

class Order {
    private String id;
    private BigDecimal amount;
    private List<OrderItem> items;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }
}

class OrderItem {
    private String productId;
    private int quantity;

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}

class ValidationContext {
    private boolean failed;
    private String message;

    public boolean isFailed() { return failed; }
    public void setFailed(boolean failed) { this.failed = failed; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
