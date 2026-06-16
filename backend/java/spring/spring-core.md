# Spring Core

> IoC 容器原理、Bean 生命周期、依赖注入——不只是"怎么用"。

---

## 1. IoC 容器原理

### 1.1 什么是 IoC

```
传统方式（自己控制）:
  UserService userService = new UserService();      // 自己创建
  userService.setUserDao(new UserDao());            // 自己组装

Spring IoC（控制反转）:
  @Autowired
  UserService userService;  // 容器注入，我不负责创建
```

**控制反转**：创建对象和组装依赖的控制权从"应用程序代码"反转到了"容器"。

### 1.2 容器体系

```
BeanFactory（顶层接口）
  ↑
ApplicationContext（最常用）
  ├── ClassPathXmlApplicationContext  (XML 配置)
  ├── AnnotationConfigApplicationContext (注解配置)
  └── GenericApplicationContext
```

| 特性 | BeanFactory | ApplicationContext |
|------|-------------|-------------------|
| 延迟加载 | ✅ 默认 | ❌ 默认立即加载 |
| AOP | ❌ | ✅ |
| 事件 | ❌ | ✅ |
| 国际化 | ❌ | ✅ |
| **推荐** | 资源受限环境 | 绝大多数场景 |

### 1.3 核心流程

```
启动过程：
1. 读取配置（注解/XML/Java Config）
2. 扫描包，找到所有 @Component
3. 解析依赖关系
4. 创建 Bean 实例
5. 注入依赖
6. 执行初始化
7. 返回准备好的 Bean
```

```java
// 手工启动容器（理解原理）
AnnotationConfigApplicationContext context =
    new AnnotationConfigApplicationContext(AppConfig.class);

// 从容器获取 Bean
UserService service = context.getBean(UserService.class);

// 关闭
context.close();
```

---

## 2. Bean 生命周期

### 2.1 完整生命周期

```
1. 实例化（new）
    ↓
2. 设置属性值（依赖注入）
    ↓
3. 设置 Bean Name（setBeanName）
    ↓
4. 设置 Bean Factory（setBeanFactory）
    ↓
5. 设置 ApplicationContext（setApplicationContext）
    ↓
6. @PostConstruct / afterPropertiesSet()
    ↓
7. 自定义 init-method
    ↓
8. Bean 就绪 → 可供使用
    ↓
9. @PreDestroy / destroy()
    ↓
10. 自定义 destroy-method
    ↓
11. Bean 销毁
```

### 2.2 介入点

```java
@Component
public class UserService {

    // 方式 1：@PostConstruct（最常用）
    @PostConstruct
    public void init() {
        System.out.println("Bean 初始化完成，依赖已注入");
    }

    // 方式 2：@PreDestroy
    @PreDestroy
    public void destroy() {
        System.out.println("Bean 即将销毁");
    }
}

// 方式 3：实现 InitializingBean / DisposableBean（不推荐，耦合 Spring API）
@Component
public class UserService implements InitializingBean, DisposableBean {
    @Override
    public void afterPropertiesSet() { }

    @Override
    public void destroy() { }
}

// 方式 4：@Bean 的 initMethod / destroyMethod（@Configuration 类中）
@Bean(initMethod = "init", destroyMethod = "destroy")
public UserService userService() {
    return new UserService();
}
```

### 2.3 🔴 常见坑

```java
// 坑 1：构造方法中访问依赖
@Component
public class UserService {

    @Autowired
    private UserDao userDao;

    public UserService() {
        // ❌ userDao 是 null！依赖还没注入
        userDao.findById(1);
    }

    @PostConstruct
    public void init() {
        // ✅ 此时依赖已注入
        userDao.findById(1);
    }
}

// 坑 2：Bean 的循环依赖
// A → 依赖 B，B → 依赖 A
@Component
public class A {
    @Autowired
    private B b;  // 构造注入会报错！
}

// 🟢 解决方案：
//   1. 用 @Lazy 打破循环
//   2. 用 setter 注入（三级缓存可解决）
//   3. 重构代码，消除循环依赖
```

---

## 3. 依赖注入

### 3.1 注入方式

```java
// 1. 字段注入（最简洁，但不推荐）
@Autowired
private UserDao userDao;

// 2. 构造注入（推荐，Spring 团队官方推荐）
@Component
public class UserService {
    private final UserDao userDao;

    public UserService(UserDao userDao) {  // 单构造器可省略 @Autowired
        this.userDao = userDao;
    }
}

// 3. Setter 注入
@Component
public class UserService {
    private UserDao userDao;

    @Autowired
    public void setUserDao(UserDao userDao) {
        this.userDao = userDao;
    }
}
```

**推荐：构造注入**

| 维度 | 字段注入 | 构造注入 |
|------|----------|----------|
| 不可变性 | ❌ 字段可被修改 | ✅ final 字段 |
| 测试 | 需反射注入 | ✅ 直接 new 传参 |
| 循环依赖检测 | 运行时发现 | ✅ 启动时发现 |
| 依赖明确性 | 隐藏 | ✅ 显式 |

### 3.2 @Autowired 详解

```java
// 按类型注入（默认）
@Autowired
private UserDao userDao;  // 找 UserDao 类型的 Bean

// 按名称注入
@Autowired
@Qualifier("userDaoImpl")
private UserDao userDao;

// required=false：非必须（找不到不报错）
@Autowired(required = false)
private UserDao userDao;  // 没有 UserDao 时，注入 null

// 注入到数组/List：收集所有实现
@Autowired
private List<Validator> validators;  // 收集所有 Validator 实现

// 注入到 Map：Bean name 做 key
@Autowired
private Map<String, Validator> validatorMap;  // { "userValidator": ..., "orderValidator": ... }
```

### 3.3 @Resource vs @Inject vs @Autowired

| | @Autowired | @Resource (JSR-250) | @Inject (JSR-330) |
|--|-----------|-------------------|-----------------|
| 来源 | Spring | Java EE | Java EE |
| 按类型 | ✅ 默认 | ❌ 按名称 | ✅ 默认 |
| 按名称 | @Qualifier | @Resource(name=...) | @Named |
| required | ✅ | ❌ | ❌ |
| **推荐** | ✅ | 可选 | 可选 |

---

## 4. Bean 作用域

```java
@Component
@Scope("singleton")  // 默认：单例
public class UserService { }

@Component
@Scope("prototype")   // 每次获取都创建新实例
public class TaskRunner { }

@Component
@Scope("request")     // 每个 HTTP 请求一个实例
public class RequestContext { }

@Component
@Scope("session")     // 每个 HTTP Session 一个实例
public class SessionContext { }
```

| 作用域 | 说明 |
|--------|------|
| singleton | 默认，整个容器一个实例 |
| prototype | 每次 getBean 或注入时创建新实例 |
| request | Web 环境，每个请求一个 |
| session | Web 环境，每个会话一个 |
| application | Web 环境，整个 ServletContext 一个 |

### 🔴 prototype 的陷阱

```java
// 单例 Bean 注入 prototype Bean
// prototype 会退化为单例！
@Component
@Scope("singleton")
public class SingletonBean {
    @Autowired
    private PrototypeBean prototypeBean;  // 注入一次后不再变化！
}

// 🟢 用 @Lookup 或 ObjectFactory
@Component
public class SingletonBean {
    @Autowired
    private ObjectFactory<PrototypeBean> prototypeBeanFactory;

    public void usePrototype() {
        PrototypeBean bean = prototypeBeanFactory.getObject();  // 每次新的
    }
}
```

---

## 5. Bean 配置方式

### 5.1 注解配置（主流）

```java
@Configuration
@ComponentScan("com.example")
public class AppConfig {
    // 业务逻辑在 @Component 类中
}
```

### 5.2 Java Config（第三方类）

```java
@Configuration
public class DataSourceConfig {

    // 创建第三方 Bean
    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://localhost:3306/db");
        config.setUsername("root");
        config.setPassword("password");
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
```

### 5.3 条件装配

```java
@Configuration
public class AppConfig {

    // 某个类存在时才创建 Bean
    @Bean
    @ConditionalOnClass(name = "redis.clients.jedis.Jedis")
    public CacheService redisCache() { }

    // 某个属性配置时才创建
    @Bean
    @ConditionalOnProperty(name = "cache.type", havingValue = "redis")
    public CacheService redisCache() { }

    // 开发/生产环境不同实现
    @Bean
    @Profile("dev")
    public DataSource devDataSource() { }

    @Bean
    @Profile("prod")
    public DataSource prodDataSource() { }
}
```

---

## 6. AOP

### 6.1 核心概念

```
@Aspect（切面）
├── @Before    目标方法执行前
├── @After     目标方法执行后（无论成功/异常）
├── @AfterReturning  目标方法正常返回后
├── @AfterThrowing   目标方法抛出异常后
└── @Around    环绕通知（最强）
```

### 6.2 示例

```java
@Aspect
@Component
public class LoggingAspect {

    // 切点表达式：execution(返回值 包.类.方法(参数))
    @Around("execution(* com.example.service.*.*(..))")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        Object result = joinPoint.proceed();  // 执行目标方法

        long elapsed = System.currentTimeMillis() - start;
        log.info("{}.{} 耗时: {}ms",
            joinPoint.getTarget().getClass().getSimpleName(),
            joinPoint.getSignature().getName(),
            elapsed);

        return result;
    }
}
```

### 6.3 🔴 常见坑

```java
// 坑 1：同类中方法调用，AOP 不生效
@Service
public class UserService {

    public void methodA() {
        this.methodB();  // ❌ 内部调用，不是通过代理
    }

    @Transactional
    public void methodB() { }
}

// 🟢 注入自身代理
@Service
public class UserService {
    @Autowired
    private UserService self;  // 注入代理对象

    public void methodA() {
        self.methodB();  // ✅ 通过代理调用
    }
}
```

---

## 7. API 速查

```java
// 获取容器
ApplicationContext context = SpringApplication.run(App.class, args);
context.getBean(UserService.class);
context.getBean("userService");
context.getBeansOfType(UserService.class);

// 获取环境配置
context.getEnvironment().getProperty("spring.datasource.url");

// 发布事件
context.publishEvent(new OrderCreatedEvent(order));

// 监听事件
@Component
public class OrderEventListener {
    @EventListener
    public void handleOrderCreated(OrderCreatedEvent event) { }
}

// 常用注解
@Component            // 通用组件
@Service              // 服务层
@Repository           // 数据层
@Controller           // 控制器
@Configuration        // 配置类
@Bean                 // 声明 Bean
@Autowired            // 依赖注入
@Qualifier            // 指定名称
@Value("${prop}")     // 注入配置值
@Scope                // 作用域
@Lazy                 // 延迟加载
@Primary              // 优先注入
```
