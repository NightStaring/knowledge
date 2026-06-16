# 服务发现

> Nacos 注册中心、服务注册与发现、健康检查、配置管理。

---

## 1. 为什么需要服务发现

### 1.1 传统调用 vs 服务发现

```
传统方式（硬编码）:
  OrderService → http://192.168.1.10:8081/api/inventory
                 如果 IP 变了 → 改配置重启

服务发现:
  OrderService → http://inventory-service/api/inventory
                 由注册中心解析为实际 IP:Port
```

**服务发现解决了：**
- **动态扩缩容**：服务实例增加/减少，调用方无感
- **健康检查**：自动剔除不健康的实例
- **负载均衡**：多个实例间自动分发请求

### 1.2 主流方案

| 方案 | 语言 | CAP | 适用 |
|------|------|-----|------|
| **Nacos** | Java | AP+CP | 国内主流，功能全面 |
| Eureka | Java | AP | Netflix 旧项目 |
| Consul | Go | CP | 多语言 |
| Zookeeper | Java | CP | 分布式协调，非专业注册中心 |
| Kubernetes | — | — | K8s 环境自带 |

---

## 2. Nacos 入门

### 2.1 启动 Nacos

```bash
# Docker 启动
docker run -d \
  --name nacos \
  -p 8848:8848 \
  -p 9848:9848 \
  -e MODE=standalone \
  nacos/nacos-server:2.3.2

# 访问控制台：http://localhost:8848/nacos
# 默认账号：nacos / nacos
```

### 2.2 Spring Cloud 集成

```xml
<!-- 依赖 -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
    <version>2023.0.1.0</version>
</dependency>
```

```yaml
# application.yml
spring:
  application:
    name: order-service          # 服务名（注册到 Nacos 的名称）

  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848  # Nacos 地址
        namespace: public            # 命名空间（环境隔离）
        group: DEFAULT_GROUP         # 分组
        weight: 1                    # 权重（负载均衡）
        ephemeral: true              # 临时实例（默认，心跳剔除）
```

### 2.3 服务间调用

```java
// 方式 1：RestTemplate + @LoadBalanced（推荐）
@Configuration
public class BeanConfig {

    @Bean
    @LoadBalanced  // 开启负载均衡，将服务名解析为实际地址
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

@Service
public class OrderService {

    @Autowired
    private RestTemplate restTemplate;

    public InventoryDto getInventory(String productId) {
        // 直接用服务名调用，不用关心实际 IP
        String url = "http://inventory-service/api/inventory/" + productId;
        return restTemplate.getForObject(url, InventoryDto.class);
    }
}

// 方式 2：Feign（声明式 HTTP 客户端，推荐）
@FeignClient(name = "inventory-service")  // 服务名
public interface InventoryClient {

    @GetMapping("/api/inventory/{productId}")
    InventoryDto getInventory(@PathVariable("productId") String productId);
}

@Service
public class OrderService {

    @Autowired
    private InventoryClient inventoryClient;

    public void createOrder(Order order) {
        InventoryDto inventory = inventoryClient.getInventory(order.getProductId());
        // ...
    }
}
```

---

## 3. Nacos 核心概念

### 3.1 命名空间（Namespace）

```
用于多环境隔离：

Namespace: dev
  └── order-service
  └── inventory-service

Namespace: prod
  └── order-service
  └── inventory-service
```

```yaml
spring:
  cloud:
    nacos:
      discovery:
        namespace: dev-xxxx-xxxx-xxxx  # 命名空间 ID
```

### 3.2 分组（Group）

```
用于同一环境内的逻辑隔离：

Group: BIZ-A
  └── order-service

Group: BIZ-B
  └── order-service
```

### 3.3 保护阈值

```yaml
spring:
  cloud:
    nacos:
      discovery:
        protect-threshold: 0.8  # 健康实例占比低于 80% 时，直接返回所有实例
```

**作用：** 防止雪崩。当大量服务同时不健康时，不再剔除不健康实例，而是把所有实例（包括不健康的）返回给调用方，让调用方自己重试。

---

## 4. 健康检查

### 4.1 Nacos 健康检查机制

```
临时实例（ephemeral=true，默认）:
  客户端每隔 5 秒发送心跳
  服务端超过 15 秒没收到心跳 → 标记为不健康
  超过 30 秒没收到心跳 → 剔除实例

持久实例（ephemeral=false）:
  服务端主动探测客户端健康状态
  TCP 探测 / HTTP 探测
```

### 4.2 自定义健康检查

```yaml
# 调整心跳参数
spring:
  cloud:
    nacos:
      discovery:
        heart-beat-interval: 5       # 心跳间隔（秒）
        heart-beat-timeout: 15       # 心跳超时（秒）
        ip-delete-timeout: 30        # 删除实例超时（秒）
```

### 4.3 Spring Boot 健康检查联动

```yaml
# Spring Boot 健康检查状态会同步到 Nacos
management:
  endpoint:
    health:
      show-details: always
  health:
    defaults:
      enabled: true
```

```java
// 自定义健康检查：当数据库不可用时，服务标记为不健康
@Component
public class DatabaseHealthIndicator implements HealthIndicator {

    @Autowired
    private DataSource dataSource;

    @Override
    public Health health() {
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(1000)) {
                return Health.up().build();
            }
        } catch (Exception e) {
            // 数据库不可用 → 服务标记为不健康
            // Nacos 会从可用列表中移除
        }
        return Health.down().build();
    }
}
```

---

## 5. 负载均衡

### 5.1 Ribbon（已进入维护）

```yaml
# Spring Cloud 2020+ 已移除 Ribbon，使用 Spring Cloud LoadBalancer
spring:
  cloud:
    loadbalancer:
      enabled: true
```

### 5.2 Spring Cloud LoadBalancer

```yaml
spring:
  cloud:
    loadbalancer:
      retry:
        enabled: true               # 开启重试
      cache:
        ttl: 5s                     # 服务列表缓存时间
```

**负载均衡策略：**

| 策略 | 说明 |
|------|------|
| RoundRobin（默认） | 轮询 |
| Random | 随机 |
| Weighted | 按权重（Nacos 控制台可配权重） |

```java
// 自定义负载均衡策略（同机房优先）
public class SameZoneLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        // 从请求上下文获取当前机房
        String currentZone = getCurrentZone();

        // 过滤出同机房的实例
        List<ServiceInstance> sameZoneInstances = instances.stream()
            .filter(instance -> currentZone.equals(instance.getMetadata().get("zone")))
            .collect(Collectors.toList());

        if (!sameZoneInstances.isEmpty()) {
            return Mono.just(new Response<>(sameZoneInstances.get(0)));
        }
        // 没有同机房实例，跨机房调用
        return Mono.just(new Response<>(instances.get(0)));
    }
}
```

---

## 6. Nacos 配置管理

### 6.1 配置中心

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
```

```yaml
# bootstrap.yml（必须用 bootstrap，优先级最高）
spring:
  application:
    name: order-service

  cloud:
    nacos:
      config:
        server-addr: localhost:8848
        namespace: public
        group: DEFAULT_GROUP
        file-extension: yaml           # 配置文件格式
        refresh-enabled: true          # 开启配置自动刷新
```

### 6.2 配置动态刷新

```java
// 方式 1：@RefreshScope + @Value
@RestController
@RefreshScope  // 配置变化时刷新 Bean
public class ConfigController {

    @Value("${order.timeout:30}")
    private int orderTimeout;

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        return Map.of("orderTimeout", orderTimeout);
    }
}

// 方式 2：@ConfigurationProperties（自动刷新）
@Component
@ConfigurationProperties(prefix = "order")
@RefreshScope
public class OrderProperties {
    private int timeout;
    private int maxQuantity;
    // getter/setter ...
}

// Nacos 控制台修改配置后，应用自动感知
```

### 6.3 配置优先级

```
Nacos 配置 > 本地 application.yml > 本地 bootstrap.yml

Data ID 匹配规则：
  {prefix}-{spring.profiles.active}.{file-extension}

例如：
  order-service-dev.yaml    # dev 环境
  order-service-prod.yaml   # prod 环境
  order-service.yaml        # 通用配置
```

---

## 7. 🔴 常见坑

```java
// 坑 1：服务名大小写问题
// Nacos 默认服务名转大写，但 Spring Cloud 调用时区分大小写
spring.application.name: order-service  // 统一小写

// 坑 2：namespace 不一致
// 调用方和提供方必须在同一个 namespace
// 否则找不到服务

// 坑 3：Feign 调用超时
@FeignClient(name = "inventory-service")
public interface InventoryClient {
    @GetMapping("/api/inventory/{productId}")
    InventoryDto getInventory(@PathVariable("productId") String productId);
}
// 如果 inventory-service 响应慢，默认 1 秒超时
// 🟢 配置超时
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connect-timeout: 5000     # 连接超时（毫秒）
            read-timeout: 10000       # 读取超时（毫秒）

// 坑 4：Nacos 多网络接口
// 服务器有多个网卡时，注册的 IP 可能不对
spring:
  cloud:
    nacos:
      discovery:
        ip: 192.168.1.10       # 指定注册 IP
        network-interface: eth0  # 或指定网卡

// 坑 5：配置变更未生效
// 1. 检查 @RefreshScope
// 2. 检查 bootstrap.yml（不是 application.yml）
// 3. 检查 refresh-enabled: true
```

---

## 8. API 速查

```yaml
# Nacos 发现配置
spring:
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
        namespace: public
        group: DEFAULT_GROUP
        weight: 1
        ephemeral: true
        protect-threshold: 0.8
        heart-beat-interval: 5
        heart-beat-timeout: 15

# Nacos 配置中心配置
spring:
  cloud:
    nacos:
      config:
        server-addr: localhost:8848
        namespace: public
        group: DEFAULT_GROUP
        file-extension: yaml
        refresh-enabled: true
```

```bash
# Nacos API
# 查看服务列表
curl http://localhost:8848/nacos/v1/ns/service/list

# 查看服务实例
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=order-service

# 发布配置
curl -X POST http://localhost:8848/nacos/v1/cs/configs \
  -d "dataId=order-service.yaml&group=DEFAULT_GROUP&content=order.timeout: 60"
```
