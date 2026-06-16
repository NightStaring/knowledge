# 网关

> Spring Cloud Gateway——路由、过滤器、限流、鉴权。

---

## 1. 为什么需要网关

```
没有网关：
  客户端 → order-service
         → inventory-service
         → user-service
  问题：每个服务都要处理鉴权、日志、限流，重复代码

有网关：
  客户端 → Gateway（统一处理鉴权、日志、限流、路由）
         → order-service
         → inventory-service
         → user-service
```

**网关的核心职责：**
- **路由转发**：根据路径分发到不同服务
- **统一鉴权**：所有请求先经过网关认证
- **限流熔断**：防止流量冲垮后端
- **日志监控**：统一记录请求日志
- **协议转换**：内外协议不一致时转换

---

## 2. 快速开始

### 2.1 依赖

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
```

### 2.2 路由配置

```yaml
spring:
  cloud:
    gateway:
      routes:
        # 路由 1：订单服务
        - id: order-service               # 路由 ID
          uri: lb://order-service         # 目标服务（lb=负载均衡）
          predicates:
            - Path=/api/orders/**         # 匹配路径
          filters:
            - StripPrefix=1               # 去掉第一级路径
            - name: RequestRateLimiter    # 限流
              args:
                key-resolver: "#{@ipKeyResolver}"
                redis-rate-limiter.replenishRate: 100
                redis-rate-limiter.burstCapacity: 200

        # 路由 2：库存服务
        - id: inventory-service
          uri: lb://inventory-service
          predicates:
            - Path=/api/inventory/**
          filters:
            - StripPrefix=1

      # 全局过滤器（所有路由生效）
      default-filters:
        - AddResponseHeader=X-Response-Foo, Bar
```

### 2.3 启动类

```java
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

---

## 3. Predicate（路由断言）

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://user-service
          predicates:
            - Path=/api/users/**              # 路径匹配
            - Method=GET,POST                  # HTTP 方法
            - Header=X-Auth-Token, \d+         # 请求头存在且匹配正则
            - Query=page, \d+                  # 查询参数
            - Cookie=session, [a-z]+           # Cookie
            - RemoteAddr=192.168.1.1/24        # IP 地址
            - Weight=group1, 80                # 权重路由（灰度发布）
```

### 3.1 时间路由

```yaml
predicates:
  - After=2025-01-01T00:00:00+08:00[Asia/Shanghai]  # 指定时间后
  - Before=2025-12-31T23:59:59+08:00[Asia/Shanghai] # 指定时间前
  - Between=...                                        # 时间区间
```

### 3.2 灰度发布

```yaml
spring:
  cloud:
    gateway:
      routes:
        # 10% 流量到新版
        - id: user-service-v2
          uri: lb://user-service-v2
          predicates:
            - Path=/api/users/**
            - Weight=gray, 10

        # 90% 流量到旧版
        - id: user-service-v1
          uri: lb://user-service-v1
          predicates:
            - Path=/api/users/**
            - Weight=gray, 90
```

---

## 4. Filter（过滤器）

### 4.1 内置过滤器

```yaml
filters:
  - StripPrefix=1              # 去掉路径前缀
  - PrefixPath=/api            # 添加路径前缀
  - AddRequestHeader=X-Req, Foo  # 添加请求头
  - AddResponseHeader=X-Res, Bar # 添加响应头
  - RemoveRequestHeader=Secret   # 移除请求头
  - SetStatus=400               # 设置响应状态码
  - SetPath=/new/{segment}      # 重写路径
  - RedirectTo=302, https://new.com  # 重定向
  - Retry=3                     # 重试
  - CircuitBreaker=myCB         # 熔断
```

### 4.2 自定义全局过滤器

```java
/**
 * 统一鉴权过滤器
 *
 * 所有请求先经过这里检查 Token
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Set<String> WHITE_LIST = Set.of(
        "/api/auth/login",
        "/api/auth/register"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().toString();

        // 白名单放行
        if (WHITE_LIST.contains(path)) {
            return chain.filter(exchange);
        }

        // 检查 Token
        String token = request.getHeaders().getFirst("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // 验证 JWT（省略具体实现）
        try {
            Claims claims = JwtUtil.parseToken(token.substring(7));
            // 把用户信息传给下游服务
            exchange = exchange.mutate()
                .request(r -> r.header("X-User-Id", claims.getSubject()))
                .build();
        } catch (Exception e) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -100;  // 优先级最高
    }
}
```

### 4.3 自定义路由过滤器

```java
/**
 * 请求耗时记录过滤器
 */
@Component
public class TimingGatewayFilterFactory
        extends AbstractGatewayFilterFactory<TimingGatewayFilterFactory.Config> {

    public TimingGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            long start = System.currentTimeMillis();

            return chain.filter(exchange).then(Mono.fromRunnable(() -> {
                long elapsed = System.currentTimeMillis() - start;
                log.info("{} {} 耗时: {}ms",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(),
                    elapsed);
            }));
        };
    }

    @Data
    public static class Config {
        private boolean enabled;
    }
}
```

---

## 5. 限流

### 5.1 令牌桶限流

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>
```

```java
/**
 * 按 IP 限流的 Key 解析器
 */
@Bean
public KeyResolver ipKeyResolver() {
    return exchange -> {
        String ip = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        return Mono.just(ip);
    };
}

/**
 * 按用户限流
 */
@Bean
public KeyResolver userKeyResolver() {
    return exchange -> {
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (userId == null) {
            return Mono.just("anonymous");
        }
        return Mono.just(userId);
    };
}

/**
 * 按路径限流
 */
@Bean
public KeyResolver pathKeyResolver() {
    return exchange -> Mono.just(exchange.getRequest().getPath().value());
}
```

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: order-service
          uri: lb://order-service
          predicates:
            - Path=/api/orders/**
          filters:
            - name: RequestRateLimiter
              args:
                key-resolver: "#{@ipKeyResolver}"   # 限流维度
                redis-rate-limiter.replenishRate: 100  # 每秒补充令牌数
                redis-rate-limiter.burstCapacity: 200  # 最大突发流量
```

---

## 6. 跨域配置

```yaml
spring:
  cloud:
    gateway:
      globalcors:
        cors-configurations:
          '[/**]':
            allowed-origins:
              - "http://localhost:3000"
              - "https://admin.example.com"
            allowed-methods:
              - GET
              - POST
              - PUT
              - DELETE
              - OPTIONS
            allowed-headers: "*"
            allow-credentials: true
            max-age: 3600
```

---

## 7. 🔴 常见坑

```java
// 坑 1：web 依赖冲突
// Gateway 基于 WebFlux（Reactive），不能和 spring-boot-starter-web 共存！
// ❌ 加了 spring-boot-starter-web → 启动失败

// 坑 2：Feign 在网关中不可用
// Gateway 用的是 WebFlux，Feign 基于 Servlet，不兼容
// 🟢 网关中请用 WebClient

// 坑 3：负载均衡不生效
// uri 必须是 lb://service-name 格式
// ❌ uri: http://order-service
// ✅ uri: lb://order-service

// 坑 4：路由顺序问题
// Gateway 按路由配置顺序匹配
// 精确路径放前面，通配路径放后面
- id: user-detail
  uri: lb://user-service
  predicates:
    - Path=/api/users/detail    # 放前面
- id: user-all
  uri: lb://user-service
  predicates:
    - Path=/api/users/**        # 放后面
```

---

## 8. API 速查

```yaml
# 路由配置
spring:
  cloud:
    gateway:
      routes:
        - id: service-name
          uri: lb://service-name   # 或 http://host:port
          predicates:
            - Path=/api/**
            - Method=GET
          filters:
            - StripPrefix=1
            - name: RequestRateLimiter

      default-filters:
        - AddResponseHeader=X-Gateway, true

      globalcors:
        cors-configurations:
          '[/**]':
            allowed-origins: "*"
```

```java
// 关键类
GlobalFilter          // 全局过滤器
GatewayFilter         // 路由过滤器
AbstractGatewayFilterFactory  // 自定义过滤器工厂
KeyResolver           // 限流 Key 解析器
ServerWebExchange     // 请求/响应上下文
```
