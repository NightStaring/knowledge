# Spring Boot

> 自动配置原理、启动流程、Actuator、配置管理——不只是"开箱即用"。

---

## 1. 自动配置原理

### 1.1 @SpringBootApplication

```java
@SpringBootApplication  // 组合了以下三个注解
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

```java
@SpringBootApplication = 
    @SpringBootConfiguration   // 标识为配置类
  + @EnableAutoConfiguration   // 开启自动配置（核心）
  + @ComponentScan             // 扫描当前包及其子包
```

### 1.2 自动配置流程

```
启动时：
1. 读取 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    ↓
2. 加载所有自动配置类（如 RedisAutoConfiguration、DataSourceAutoConfiguration）
    ↓
3. 按 @Conditional 条件判断是否生效
    ↓
4. 生效 → 创建对应的 Bean

例如 RedisAutoConfiguration：
  条件：RedisTemplate 类存在 && 项目中配置了 Redis 连接
  生效后：创建 RedisTemplate、StringRedisTemplate 等 Bean
```

### 1.3 条件注解

```java
// 类路径条件
@ConditionalOnClass(RedisTemplate.class)        // 类存在时
@ConditionalOnMissingClass("com.example.SomeClass")  // 类不存在时

// Bean 条件
@ConditionalOnBean(DataSource.class)             // 存在 DataSource 时
@ConditionalOnMissingBean(RedisTemplate.class)   // 不存在时

// 配置条件
@ConditionalOnProperty(name = "cache.enabled", havingValue = "true")
@ConditionalOnResource(resources = "config.properties")

// 表达式条件
@ConditionalOnExpression("${cache.type} == 'redis'")

// Web 应用条件
@ConditionalOnWebApplication
@ConditionalOnNotWebApplication
```

### 1.4 查看生效的自动配置

```yaml
# application.yml
debug: true
```

```bash
# 启动时输出：
Positive matches:    # 已生效的自动配置
-----------------
RedisAutoConfiguration
  - @ConditionalOnClass found required class 'org.springframework.data.redis.core.RedisOperations'
  - @ConditionalOnMissingBean (types: org.springframework.data.redis.core.RedisTemplate) did not find any beans

Negative matches:    # 未生效的（含原因）
-----------------
DataSourceAutoConfiguration
  - @ConditionalOnClass did not find required class 'javax.sql.DataSource'
```

---

## 2. 启动流程

### 2.1 SpringApplication.run()

```
1. 获取 SpringApplicationRunListener（事件监听）
2. 加载配置（application.yml）
3. 准备 Environment
4. 打印 Banner
5. 创建 ApplicationContext
6. 注册 BeanFactoryPostProcessor
7. 注册 BeanPostProcessor
8. 刷新上下文（创建 Bean）
9. 执行 CommandLineRunner / ApplicationRunner
10. 发布 ApplicationReadyEvent
```

### 2.2 启动后执行

```java
// 方式 1：ApplicationRunner（推荐）
@Component
public class StartupRunner implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        System.out.println("应用启动完成，参数: " + args.getOptionNames());
    }
}

// 方式 2：CommandLineRunner
@Component
public class StartupRunner implements CommandLineRunner {
    @Override
    public void run(String... args) {
        System.out.println("应用启动完成");
    }
}

// 方式 3：@PostConstruct（⚠️ 在 Bean 初始化时执行，非启动完成时）
@Component
public class InitService {
    @PostConstruct
    public void init() {
        // 此时 ApplicationContext 还没完全就绪
    }
}

// 方式 4：@EventListener(ApplicationReadyEvent.class)
@Component
public class StartupListener {
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        // 此时应用已完全就绪
    }
}
```

### 2.3 🔴 启动坑

```java
// 坑：@PostConstruct 中访问远程资源
@Component
public class CacheWarmer {
    @Autowired
    private RedisTemplate redisTemplate;

    @PostConstruct
    public void warmUp() {
        // ❌ 可能失败！此时 Bean 刚初始化完，但容器未完全就绪
        redisTemplate.opsForValue().get("key");
    }
}

// ✅ 用 ApplicationReadyEvent
@Component
public class CacheWarmer {
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        // ✅ 应用已完全就绪
        redisTemplate.opsForValue().get("key");
    }
}
```

---

## 3. 配置管理

### 3.1 配置优先级

```
命令行参数 > 操作系统环境变量 > application-{profile}.yml > application.yml > @PropertySource
```

```bash
# 命令行参数（最高优先级）
java -jar app.jar --server.port=8081 --spring.profiles.active=prod

# 环境变量
export SERVER_PORT=8081
java -jar app.jar
```

### 3.2 多环境配置

```yaml
# application.yml（通用配置）
server:
  port: 8080

spring:
  profiles:
    active: dev

---
# application-dev.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/dev_db

---
# application-prod.yml
spring:
  datasource:
    url: jdbc:mysql://prod-host:3306/prod_db
```

```yaml
# 或在一个文件中用 --- 分隔（推荐）
spring:
  config:
    activate:
      on-profile: dev
---
spring:
  config:
    activate:
      on-profile: prod
```

### 3.3 配置注入

```java
// 方式 1：@Value
@Component
public class AppConfig {
    @Value("${app.name:default-app}")    // 带默认值
    private String appName;

    @Value("${app.version}")
    private String appVersion;
}

// 方式 2：@ConfigurationProperties（推荐，类型安全）
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String name;
    private String version;
    private List<String> servers = new ArrayList<>();
    private Map<String, String> metadata = new HashMap<>();
    private Security security = new Security();  // 嵌套类

    // getter/setter ...
    public static class Security {
        private boolean enabled;
        private String tokenSecret;
        // getter/setter ...
    }
}

// application.yml
app:
  name: my-app
  version: 1.0.0
  servers:
    - server1.example.com
    - server2.example.com
  security:
    enabled: true
    token-secret: abc123  # 自动将 kebab-case 转为 camelCase
```

---

## 4. Actuator

### 4.1 配置

```yaml
# 启用 Actuator
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,env,loggers,threaddump,heapdump
      # 或 include: "*"（暴露所有，生产环境不推荐）
  endpoint:
    health:
      show-details: always  # 显示详细健康信息
```

### 4.2 常用端点

| 端点 | 路径 | 说明 |
|------|------|------|
| health | `/actuator/health` | 健康检查 |
| info | `/actuator/info` | 应用信息 |
| metrics | `/actuator/metrics` | 指标（JVM、内存、GC） |
| env | `/actuator/env` | 环境配置 |
| loggers | `/actuator/loggers` | 日志级别（可动态修改） |
| threaddump | `/actuator/threaddump` | 线程栈 |
| heapdump | `/actuator/heapdump` | Heap Dump |
| prometheus | `/actuator/prometheus` | Prometheus 格式指标 |

```bash
# 动态修改日志级别（POST，无需重启！）
curl -X POST http://localhost:8080/actuator/loggers/com.example.service \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "DEBUG"}'

# 健康检查
curl http://localhost:8080/actuator/health
# {"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}

# 查看 JVM 内存
curl http://localhost:8080/actuator/metrics/jvm.memory.used
```

### 4.3 自定义 Health Indicator

```java
@Component
public class CustomHealthIndicator implements HealthIndicator {

    @Autowired
    private SomeService someService;

    @Override
    public Health health() {
        try {
            someService.check();
            return Health.up()
                .withDetail("message", "服务正常")
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
```

---

## 5. 常用 Starter

```xml
<!-- Web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- JPA -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- 安全 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- 验证 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- 测试 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>

<!-- Actuator -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

---

## 6. 🔴 常见坑

```java
// 坑 1：@ComponentScan 范围问题
@SpringBootApplication
// 默认扫描 Application 所在包及其子包
// 如果第三方包不在扫描路径下，不会被扫描到

// 坑 2：多数据源事务问题
// 默认 PlatformTransactionManager 只能管理一个数据源
// 🟢 用 @Transactional(transactionManager = "xxxTransactionManager")

// 坑 3：配置文件编码
// application.yml 默认 UTF-8，但 properties 文件默认 ISO-8859-1
// 🟢 统一用 YAML 格式

// 坑 4：DevTools 导致的 ClassCastException
// DevTools 使用不同 ClassLoader 加载类
// 类型转换时可能失败
// 🟢 生产环境关闭 DevTools
```

---

## 7. API 速查

```java
// 启动
SpringApplication.run(Application.class, args);

// 启动参数
SpringApplication app = new SpringApplication(Application.class);
app.setBannerMode(Banner.Mode.OFF);         // 关掉 Banner
app.setAdditionalProfiles("dev");            // 指定 profile
app.setDefaultProperties(Map.of("server.port", "8081"));
app.run(args);

// 获取配置
@Value("${prop}")
Environment env;
env.getProperty("prop");

// 配置绑定
@ConfigurationProperties(prefix = "app")

// 应用事件
ApplicationStartedEvent     // 启动开始
ApplicationEnvironmentPreparedEvent  // Environment 就绪
ApplicationPreparedEvent    // ApplicationContext 就绪
ApplicationReadyEvent       // 应用就绪（推荐监听时机）
ApplicationFailedEvent      // 启动失败
```
