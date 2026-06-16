# Java 知识体系

> 从基础进阶到企业级实战，涵盖集合、并发、JVM、Spring、微服务、架构设计。

---

## 目录

### 基础进阶（精炼·易错点）
| 文档 | 内容 |
|------|------|
| [集合框架](./basics/collections.md) | HashMap 原理、ConcurrentHashMap、List/Set/Map 选型 |
| [Stream + Lambda + Optional](./basics/stream.md) | 函数式编程、常用 API 速查表 |
| [异常体系](./basics/exception.md) | 受检/非受检异常、最佳实践 |
| [泛型](./basics/generics.md) | PECS、类型擦除、通配符 |
| [IO / NIO](./basics/io-nio.md) | BIO/NIO/AIO、核心 API |
| [常见陷阱](./basics/common-pitfalls.md) | String、Integer 缓存、浮点运算等 |

### 核心进阶
| 文档 | 内容 |
|------|------|
| [JVM 内存与调优](./core/jvm-memory.md) | 内存模型、GC 算法、调优参数 |
| [并发编程](./core/concurrency.md) | 锁、线程池、CompletableFuture |
| [性能分析](./core/performance.md) | Profiling、GC 日志分析、OOM 排查 |

### Spring 深度
| 文档 | 内容 |
|------|------|
| [Spring Core](./spring/spring-core.md) | IoC 原理、Bean 生命周期 |
| [Spring Boot](./spring/spring-boot.md) | 自动配置、Actuator |
| [事务管理](./spring/spring-transaction.md) | 传播行为 + 常见陷阱 |
| [测试](./spring/spring-testing.md) | 单元/集成/契约测试 |

### 微服务（Spring Cloud）
| 文档 | 内容 |
|------|------|
| [服务发现](./spring-cloud/service-discovery.md) | Nacos |
| [网关](./spring-cloud/gateway.md) | Spring Cloud Gateway |
| [分布式事务](./spring-cloud/distributed-transaction.md) | Seata |
| [容错](./spring-cloud/resilience.md) | Sentinel / Resilience4j |

### 数据库与 ORM
| 文档 | 内容 |
|------|------|
| [JPA / Hibernate](./database/jpa-hibernate.md) | N+1、懒加载、一级/二级缓存 |
| [MyBatis](./database/mybatis.md) | MyBatis-Plus 企业实践 |
| [分库分表](./database/sharding.md) | ShardingSphere |

### 架构设计
| 文档 | 内容 |
|------|------|
| [DDD](./architecture/ddd.md) | 领域驱动设计 |
| [设计模式](./architecture/design-patterns.md) | 企业级模式 |

### 工程化
| 文档 | 内容 |
|------|------|
| [Maven / Gradle](./devops/maven-gradle.md) | 构建工具进阶 |
| [容器化](./devops/docker-k8s.md) | Docker + K8s 部署 |

---

## 学习路径

```
初学者 → basics/ → core/ → spring/
                                       ↘
有经验者 → core/ → spring/ → spring-cloud/ → database/
                                       ↗
架构师 → database/ → architecture/
```

## 约定

- 每篇末尾附 **常用 API 速查表**
- 文档配套示例见 `examples/` 目录
- 示例标记 `🔴` 为常见坑，`🟢` 为推荐做法
