# 开发方法论

> 开发思想、实践原则、编码哲学。

---

## 1. TDD（测试驱动开发）

### 1.1 核心流程

```
红（Red）— 写一个会失败的测试
  ↓
绿（Green）— 写刚好能让测试通过的代码
  ↓
重构（Refactor）— 优化代码，保持测试通过
```

### 1.2 为什么用 TDD

```
✅ 好处：
  1. 强制你思考接口设计（先写测试 = 先定义行为）
  2. 代码天然可测试（不可测试的代码会被发现）
  3. 测试覆盖率高
  4. 重构有信心

❌ 误区：
  不是"先写测试再写代码"这么简单
  核心是"通过测试驱动设计"
```

### 1.3 什么时候用

| 场景 | 推荐 |
|------|------|
| 核心业务逻辑 | ✅ TDD |
| 算法/工具类 | ✅ TDD |
| CRUD 接口 | ⚠️ 可选 |
| UI 层 | ❌ 不适合 |

---

## 2. Clean Code

### 2.1 命名

```java
// ❌ 坏命名
int d;                    // 模糊
List<String> list;        // 无意义
void process() { }        // 太宽泛
public int calc(int a, int b, int c) { }  // 参数含义不明

// ✅ 好命名
int daysUntilExpiry;
List<String> activeUserNames;
void sendVerificationEmail() { }
public BigDecimal calculateDiscount(BigDecimal amount, int userLevel) { }
```

### 2.2 函数

```java
// ❌ 太长、做太多事
public void createOrder(Order order) {
    // 校验
    // 计算价格
    // 保存数据库
    // 发短信
    // 扣库存
    // 加积分
    // 记录日志
}

// ✅ 单一职责
public void createOrder(Order order) {
    validateOrder(order);
    BigDecimal total = calculatePrice(order);
    orderRepository.save(order);
    eventPublisher.publish(new OrderCreatedEvent(order));
}
```

### 2.3 原则

| 原则 | 说明 |
|------|------|
| **KISS** | Keep It Simple, Stupid |
| **DRY** | Don't Repeat Yourself |
| **YAGNI** | You Ain't Gonna Need It |
| **单一职责** | 一个类/方法只做一件事 |
| **开闭原则** | 对扩展开放，对修改关闭 |

---

## 3. 约定优于配置

### 3.1 是什么

```
框架/工具提供合理的默认值
开发者只需配置与默认不同的部分

Spring Boot 的例子：
  约定：resources/static/ 放静态文件
  约定：resources/templates/ 放模板
  约定：resources/application.yml 放配置

你不需要告诉 Spring Boot"静态文件放哪"
因为它已经有了约定
```

### 3.2 好处

```
✅ 减少决策疲劳
✅ 代码更一致
✅ 新项目上手快
✅ 跨项目切换成本低
```

---

## 4. SOLID 原则

| 原则 | 说明 | 违反的例子 |
|------|------|-----------|
| **S** 单一职责 | 一个类只有一个变化原因 | UserService 既管登录又管发邮件 |
| **O** 开闭原则 | 对扩展开放，对修改关闭 | 加新类型要改 if-else |
| **L** 里氏替换 | 子类必须能替换父类 | Square 继承 Rectangle 改变行为 |
| **I** 接口隔离 | 接口要小而专 | 一个接口有 10 个方法，实现类只用 2 个 |
| **D** 依赖反转 | 依赖抽象不依赖实现 | new XXXDao() 而不是注入 |

---

## 5. 🔴 常见误区

### 5.1 过度设计

```
❌ "这个功能以后可能需要，先做个抽象"
→ YAGNI：你现在不需要它

❌ "用 DDD 写一个 CRUD"
→ 工具要适合场景，不是场景适合工具

✅ "先写简单实现，需要时再重构"
```

### 5.2 完美主义

```
❌ "代码不够完美，不能提交"
→ 可工作的代码 > 完美的代码

✅ "先提交，逐步改进"
→ 迭代式开发
```

### 5.3 方法论原教旨

```
❌ "没按 TDD 写就不算好代码"
→ 方法论是工具，不是教条

✅ "理解原则，灵活应用"
→ 知道什么时候该用，什么时候不该用
```
