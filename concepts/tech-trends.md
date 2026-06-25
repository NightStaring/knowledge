# 技术趋势

> 新兴的、值得关注的技术和概念。

---

## 1. Virtual Threads（虚拟线程）

### 1.1 是什么

JDK 21 正式版引入，轻量级线程，由 JVM 管理而非 OS。

```java
// 传统线程池
ExecutorService executor = Executors.newFixedThreadPool(200);
// 200 个 OS 线程，上下文切换开销大

// 虚拟线程（JDK 21+）
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    // 可以创建上百万个虚拟线程
    // 阻塞时自动让出载体线程，开销极小
}
```

### 1.2 为什么重要

```
传统线程问题：
  OS 线程 ≈ 1MB 栈内存
  2000 个线程 ≈ 2GB
  上下文切换开销大

虚拟线程优势：
  栈可以动态扩展（几百字节起步）
  可以创建数百万个
  阻塞时自动切换，几乎没有上下文切换开销
```

### 1.3 适用场景

| 场景 | 推荐 |
|------|------|
| IO 密集型（HTTP 调用、DB 查询） | ✅ 非常适合 |
| CPU 密集型 | ❌ 不适用 |
| 高并发 Web 服务 | ✅ 推荐 |

```java
// Spring Boot 3.2+ 配置虚拟线程
spring:
  threads:
    virtual:
      enabled: true
```

---

## 2. GraalVM Native Image

### 2.1 是什么

将 Java 应用编译为**原生可执行文件**，启动毫秒级、内存占用大幅降低。

```bash
# 传统 Java 应用
java -jar app.jar
# 启动时间: 2~5 秒
# 内存: ~200MB

# GraalVM 原生镜像
./app
# 启动时间: < 100ms
# 内存: ~50MB
```

### 2.2 适用场景

| 场景 | 推荐 |
|------|------|
| Serverless / FaaS | ✅ 非常适合（快速冷启动） |
| CLI 工具 | ✅ 推荐 |
| 微服务 | ✅ 合适 |
| 长时间运行的服务 | ❌ 优势不大 |

### 2.3 局限性

```
❌ 不支持动态类加载（反射需配置）
❌ 构建时间较长（分钟级）
❌ 调试信息较少
❌ 一些库不兼容（需确认）
```

---

## 3. Spring Boot 3.x 新特性

| 特性 | 说明 |
|------|------|
| **Jakarta EE 9+** | javax.* → jakarta.* |
| **Virtual Threads** | 内置支持 |
| **GraalVM Native** | AOT 编译支持 |
| **Problem Details** | RFC 7807 错误响应 |
| **Observability** | Micrometer + OTEL 内置 |

---

## 4. 值得关注的技术

| 技术 | 说明 | 状态 |
|------|------|------|
| **Project Loom** | 虚拟线程（JDK 21 GA） | ✅ 已发布 |
| **Project Valhalla** | 值类型 | ⏳ 开发中 |
| **Project Panama** | 外部函数与内存 API | ✅ JDK 22 |
| **Spring Modulith** | 模块化单体 | ✅ 可用 |
| **Spring AI** | AI 应用框架 | ✅ 可用 |
| **Kotlin Multiplatform** | 跨平台开发 | ⏳ 发展中 |
| **WebAssembly** | 浏览器外运行 | ⏳ 发展中 |
