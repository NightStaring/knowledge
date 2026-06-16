# 容错

> Sentinel 熔断限流、Resilience4j、服务容错最佳实践。

---

## 1. 为什么需要容错

### 1.1 雪崩效应

```
正常情况：
  客户端 → 订单服务 (50ms) → 库存服务 (50ms) → 200ms 返回

异常情况：
  客户端 → 订单服务 (50ms) → 库存服务 (卡住 30s)
                            → 订单服务线程被占满
                            → 其他请求排队
                            → 订单服务雪崩
                            → 上游服务也雪崩
```

**容错三板斧：**
| 手段 | 说明 |
|------|------|
| **限流** | 控制流量速率，防止过载 |
| **熔断** | 检测到故障，快速失败，防止级联 |
| **降级** | 故障时提供降级方案，返回默认值 |

---

## 2. Sentinel

### 2.1 Sentinel vs Hystrix

| 维度 | Sentinel | Hystrix（已停更） |
|------|----------|------------------|
| 限流 | 支持（多种模式） | 不支持 |
| 熔断 | 支持 | 支持 |
| 降级 | 支持 | 支持 |
| 实时监控 | 控制台 | 需集成 |
| 配置持久化 | 支持 | 不支持 |
| 适配 Spring Cloud | ✅ | 维护模式 |

### 2.2 快速开始

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
```

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8080   # Sentinel 控制台地址
      eager: true                    # 立即注册（默认懒加载）
```

```bash
# 启动 Sentinel 控制台
docker run -d --name sentinel -p 8080:8080 bladex/sentinel-dashboard:1.8.8
# 访问 http://localhost:8080  sentinel/sentinel
```

### 2.3 流控规则

```java
@RestController
public class OrderController {

    /**
     * 按 QPS 限流
     * 单机 QPS 超过 100 时，触发限流
     */
    @GetMapping("/api/orders")
    @SentinelResource(value = "getOrders", blockHandler = "getOrdersBlock")
    public List<Order> getOrders() {
        return orderService.findAll();
    }

    /**
     * 限流后的降级方法
     * 返回值类型和参数要与原方法一致，多一个 BlockException
     */
    public List<Order> getOrdersBlock(BlockException e) {
        log.warn("触发限流", e);
        return Collections.emptyList();
    }
}
```

```yaml
# 或在配置文件中定义规则
spring:
  cloud:
    sentinel:
      datasource:
        flow:
          nacos:
            server-addr: localhost:8848
            data-id: ${spring.application.name}-flow-rules
            group-id: SENTINEL_GROUP
            rule-type: flow
```

**限流模式：**

| 模式 | 说明 |
|------|------|
| QPS（直接） | 每秒请求数超过阈值 → 限流 |
| 并发线程数 | 并发线程数超过阈值 → 限流 |
| 关联 | 关联资源达到阈值 → 当前资源限流 |
| 链路 | 指定入口的调用达到阈值 → 限流 |

### 2.4 熔断规则

```java
@RestController
public class OrderController {

    /**
     * 熔断规则：慢调用比例
     * 1000ms 内没返回算慢调用
     * 比例超过 50% → 熔断
     * 熔断后 10 秒进入半开状态尝试恢复
     */
    @GetMapping("/api/orders/detail")
    @SentinelResource(value = "getOrderDetail",
        fallback = "getOrderDetailFallback")
    public Order getOrderDetail(@RequestParam Long id) {
        return orderService.getDetail(id);
    }

    /**
     * 熔断降级方法
     * 熔断时调用此方法
     */
    public Order getOrderDetailFallback(Long id, Throwable t) {
        log.warn("触发熔断降级", t);
        return new Order();  // 返回默认值
    }
}
```

**熔断策略：**

| 策略 | 条件 | 适用 |
|------|------|------|
| 慢调用比例 | RT > 阈值 且 比例 > 阈值 | 响应慢的服务 |
| 异常比例 | 异常数 / 总调用 > 阈值 | 频繁报错的服务 |
| 异常数 | 异常数 > 阈值 | 错误波动大的服务 |

### 2.5 热点参数限流

```java
@GetMapping("/api/orders")
@SentinelResource(value = "getOrders",
    blockHandler = "getOrdersBlock")
public List<Order> getOrders(
    @RequestParam(defaultValue = "1") int page,
    @RequestParam(defaultValue = "20") int size
) {
    return orderService.findAll();
}
```

```yaml
# 热点规则：针对特定参数值限流
# 例如：参数 index=0（page）超过 100 QPS 限流
```

### 2.6 系统自适应保护

```yaml
spring:
  cloud:
    sentinel:
      datasource:
        system:
          nacos:
            data-id: ${spring.application.name}-system-rules
            rule-type: system
```

| 规则 | 说明 |
|------|------|
| Load | 系统负载 > 阈值 → 限流 |
| CPU | CPU 使用率 > 阈值 → 限流 |
| RT | 所有入口 RT > 阈值 → 限流 |
| QPS | 所有入口 QPS > 阈值 → 限流 |
| 线程数 | 所有入口线程数 > 阈值 → 限流 |

---

## 3. Resilience4j

### 3.1 依赖

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

### 3.2 熔断

```yaml
resilience4j:
  circuitbreaker:
    instances:
      inventoryService:
        register-health-indicator: true
        sliding-window-size: 10           # 滑动窗口大小
        minimum-number-of-calls: 5        # 最少调用次数（之后才开始判断）
        permitted-number-of-calls-in-half-open-state: 3  # 半开状态允许的调用数
        automatic-transition-from-open-to-half-open-enabled: true
        wait-duration-in-open-state: 10s  # 熔断持续时间
        failure-rate-threshold: 50        # 失败率阈值（%）
        slow-call-rate-threshold: 50      # 慢调用率阈值（%）
        slow-call-duration-threshold: 2s  # 慢调用定义（>2s）
```

```java
@RestController
public class OrderController {

    @GetMapping("/api/orders/inventory/{productId}")
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "getStockFallback")
    public InventoryDto getStock(@PathVariable String productId) {
        return inventoryClient.getInventory(productId);
    }

    public InventoryDto getStockFallback(String productId, Throwable t) {
        log.warn("库存服务不可用，返回默认值", t);
        return new InventoryDto(productId, 0);  // 返回默认库存
    }
}
```

### 3.3 重试

```yaml
resilience4j:
  retry:
    instances:
      inventoryService:
        max-retry-attempts: 3           # 最大重试次数
        wait-duration: 1s               # 重试间隔
        retry-exceptions:
          - org.springframework.web.client.HttpServerErrorException  # 5xx 重试
        ignore-exceptions:
          - org.springframework.web.client.HttpClientErrorException  # 4xx 不重试
```

```java
@GetMapping("/api/orders/inventory/{productId}")
@Retry(name = "inventoryService", fallbackMethod = "getStockFallback")
public InventoryDto getStock(@PathVariable String productId) {
    return inventoryClient.getInventory(productId);
}
```

### 3.4 限流

```yaml
resilience4j:
  ratelimiter:
    instances:
      orderService:
        limit-for-period: 100           # 每个周期允许的请求数
        limit-refresh-period: 1s        # 周期时间
        timeout-duration: 500ms         # 等待许可的超时时间
```

```java
@GetMapping("/api/orders")
@RateLimiter(name = "orderService", fallbackMethod = "getOrdersFallback")
public List<Order> getOrders() {
    return orderService.findAll();
}
```

---

## 4. 降级方案设计

### 4.1 降级等级

```java
/**
 * 降级分级处理
 */
@Service
public class InventoryService {

    public InventoryDto getInventory(String productId) {
        try {
            // 一级降级：尝试远程调用
            return inventoryClient.getInventory(productId);
        } catch (Exception e) {
            log.warn("远程调用失败，尝试本地缓存");
        }

        try {
            // 二级降级：读本地缓存
            InventoryDto cached = localCache.get(productId);
            if (cached != null) {
                return cached;
            }
        } catch (Exception e) {
            log.warn("缓存读取失败");
        }

        // 三级降级：返回默认值
        log.warn("所有降级方案失败，返回默认值");
        return new InventoryDto(productId, 0);
    }
}
```

### 4.2 缓存预热

```java
/**
 * 降级时用的本地缓存
 */
@Component
public class InventoryLocalCache {

    private final Cache<String, InventoryDto> cache = Caffeine.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build();

    /**
     * 定时刷新本地缓存
     */
    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        // 从数据库或远程批量拉取热点数据
        List<InventoryDto> hotData = inventoryClient.getHotProducts();
        hotData.forEach(dto -> cache.put(dto.getProductId(), dto));
    }
}
```

---

## 5. 隔离

### 5.1 线程池隔离

```yaml
# Sentinel 线程池隔离
spring:
  cloud:
    sentinel:
      datasource:
        degraderule:
          nacos:
            data-id: ${spring.application.name}-degrade-rules
            rule-type: degrade
```

### 5.2 信号量隔离

```yaml
resilience4j:
  bulkhead:
    instances:
      inventoryService:
        max-concurrent-calls: 10        # 最大并发数
        max-wait-duration: 500ms        # 等待超时
```

```java
@GetMapping("/api/orders/inventory/{productId}")
@Bulkhead(name = "inventoryService", fallbackMethod = "getStockFallback")
public InventoryDto getStock(@PathVariable String productId) {
    return inventoryClient.getInventory(productId);
}
```

---

## 6. 🔴 常见坑

```java
// 坑 1：@SentinelResource 的 blockHandler 和 fallback 区别
// blockHandler: 限流/熔断触发时调用
// fallback: 业务异常时调用
@SentinelResource(value = "getOrders",
    blockHandler = "blockHandler",   // 限流/熔断
    fallback = "fallback"            // 业务异常
)

// 坑 2：降级方法必须和原方法在同一类中
// 否则不生效（AOP 代理限制）

// 坑 3：熔断后的恢复
// Sentinel 熔断后，默认不会自动恢复
// 🟢 配置半开状态
// spring.cloud.sentinel.circuit-breaker.half-open-enabled=true

// 坑 4：Sentinel 不兼容 RestTemplate
// 🟢 用 Feign 或 WebClient

// 坑 5：重试幂等
// 重试可能导致重复操作
// 🟢 确保被重试的接口是幂等的
```

---

## 7. API 速查

```yaml
# Sentinel
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8080
      eager: true

# Resilience4j
resilience4j:
  circuitbreaker:
    instances:
      serviceName:
        sliding-window-size: 10
        failure-rate-threshold: 50
  retry:
    instances:
      serviceName:
        max-retry-attempts: 3
  ratelimiter:
    instances:
      serviceName:
        limit-for-period: 100
  bulkhead:
    instances:
      serviceName:
        max-concurrent-calls: 10
```

```java
// Sentinel 注解
@SentinelResource(value = "resourceName",
    blockHandler = "blockMethod",
    fallback = "fallbackMethod",
    exceptionsToIgnore = {BusinessException.class}
)

// Resilience4j 注解
@CircuitBreaker(name = "serviceName", fallbackMethod = "fallback")
@Retry(name = "serviceName", fallbackMethod = "fallback")
@RateLimiter(name = "serviceName", fallbackMethod = "fallback")
@Bulkhead(name = "serviceName", fallbackMethod = "fallback")
@TimeLimiter(name = "serviceName")  // 超时控制
```
