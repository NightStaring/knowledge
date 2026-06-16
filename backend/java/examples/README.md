# Java 教学示例

> 与 `backend/java/` 文档配套的示例代码。

---

## 目录

| 文件 | 对应文档 | 内容 |
|------|----------|------|
| `basics/CollectionsAndStream.java` | collections.md / stream.md | HashMap 原理、Stream 链式、CompletableFuture |
| `basics/GenericsExceptionIO.java` | generics.md / exception.md / io-nio.md | PECS、自定义异常、Files API |
| `core/ConcurrencyExamples.java` | concurrency.md | 线程池、Lock/Condition、并发工具 |
| `spring/SpringExamples.java` | spring-core / spring-transaction | 策略模式、事务传播、事件驱动 |
| `database/JpaAndMyBatisPlus.java` | jpa-hibernate.md / mybatis.md | JPA 映射、MyBatis-Plus CRUD |
| `architecture/DesignPatterns.java` | design-patterns.md | 策略/模板/责任链/适配器/建造者 |

## 说明

- 代码中包含大量教学注释（`// 🟢` 推荐做法、`// ❌` 常见坑）
- 文件内 `package` 路径仅为示意，直接复制使用时需调整
- Spring 相关示例中的注解（`@Service`、`@Component` 等）已注释，示意用法
