# 异常体系

> 异常的最佳实践与易错点，不讲"什么是 try-catch"。

---

## 1. 异常体系结构

```
Throwable
├── Error（不可处理）
│   ├── OutOfMemoryError
│   ├── StackOverflowError
│   └── NoClassDefFoundError
│
└── Exception（可处理）
    ├── RuntimeException（非受检）
    │   ├── NullPointerException
    │   ├── IllegalArgumentException
    │   ├── IllegalStateException
    │   └── IndexOutOfBoundsException
    │
    └── 受检异常（Checked Exception）
        ├── IOException
        ├── SQLException
        └── InterruptedException
```

---

## 2. 受检 vs 非受检

| | 受检异常（Checked） | 非受检异常（Unchecked） |
|--|-------------------|---------------------|
| 继承 | Exception（不含 RuntimeException） | RuntimeException |
| 编译器 | **必须**处理或声明 | 可处理可不处理 |
| 典型 | IOException、SQLException | NPE、IllegalArgumentException |
| 使用场景 | 可预见的、调用方可恢复的 | 程序 bug、不可恢复的 |

### 2.1 选型原则

```java
// 🟢 调用方能合理恢复 → 受检异常
public void transfer(Account from, Account to, BigDecimal amount)
        throws InsufficientBalanceException {
    if (from.getBalance().compareTo(amount) < 0) {
        throw new InsufficientBalanceException("余额不足");
    }
    // ...
}

// 🟢 程序 bug、调用方无法恢复 → 非受检异常
public void setAge(int age) {
    if (age < 0 || age > 150) {
        throw new IllegalArgumentException("年龄不合法: " + age);
    }
}

// 🔴 不要滥用受检异常
// 让调用方被迫写空的 catch 块
try {
    someMethod();
} catch (IOException e) {
    // 不可能发生，但不得不写
}
```

---

## 3. 最佳实践

### 3.1 异常信息要有意义

```java
// ❌ 无意义的异常信息
throw new RuntimeException("错误");
throw new RuntimeException("系统异常");

// ✅ 包含上下文信息
throw new OrderNotFoundException(
    String.format("订单不存在: orderId=%s, userId=%s", orderId, userId)
);
```

### 3.2 异常不要吞掉

```java
// ❌ 吞掉异常
try {
    process();
} catch (Exception e) {
    // 什么都不做 ← 最糟糕的做法
}

// ❌ 只打印不处理
try {
    process();
} catch (Exception e) {
    e.printStackTrace();  // 没人看控制台
}

// ✅ 要么记录日志，要么抛出去
try {
    process();
} catch (Exception e) {
    log.error("处理失败", e);  // 记录完整堆栈
    throw e;                   // 或抛出自定义异常
}
```

### 3.3 使用自定义异常

```java
// 🟢 为业务定义专属异常
public class InsufficientBalanceException extends RuntimeException {
    private final String accountId;
    private final BigDecimal balance;
    private final BigDecimal required;

    public InsufficientBalanceException(String accountId,
                                        BigDecimal balance,
                                        BigDecimal required) {
        super(String.format("账户 %s 余额不足: 当前 %.2f, 需要 %.2f",
              accountId, balance, required));
        this.accountId = accountId;
        this.balance = balance;
        this.required = required;
    }

    // getter...
}
```

### 3.4 异常链不丢失

```java
// ❌ 丢失原始异常
try {
    process();
} catch (SQLException e) {
    throw new BusinessException("处理失败");  // 原始异常丢了！
}

// ✅ 保留原始异常
try {
    process();
} catch (SQLException e) {
    throw new BusinessException("处理失败", e);  // cause = e
}
```

### 3.5 finally 中的 return

```java
// 🔴 finally 中的 return 会覆盖 try 中的 return 和异常
public int calc() {
    try {
        return 1;
    } finally {
        return 2;  // 返回 2！而且 try 中的异常被吞掉了！
    }
}

// 🟢 finally 中不要 return
public int calc() {
    try {
        return 1;
    } finally {
        close();  // 只做清理
    }
}
```

### 3.6 try-with-resources

```java
// ❌ 手动关闭（易忘、易错）
BufferedReader reader = null;
try {
    reader = new BufferedReader(new FileReader("file.txt"));
    // ...
} finally {
    if (reader != null) {
        reader.close();  // close 本身也抛异常！
    }
}

// ✅ try-with-resources（Java 7+）
// 自动调用 Closeable.close()，先关闭的异常会被抑制
try (BufferedReader reader = new BufferedReader(new FileReader("file.txt"))) {
    // ...
}  // 自动关闭

// 多个资源
try (Connection conn = getConnection();
     PreparedStatement stmt = conn.prepareStatement(sql);
     ResultSet rs = stmt.executeQuery()) {
    // ...
}
```

---

## 4. 全局异常处理

### 4.1 Spring Boot 统一处理

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ResponseEntity
            .badRequest()
            .body(new ErrorResponse(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
            .body(new ErrorResponse(400, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        log.error("未知异常", e);  // 记录完整堆栈
        return ResponseEntity.status(500)
            .body(new ErrorResponse(500, "服务器内部错误"));
    }
}
```

### 4.2 异常响应格式

```json
{
    "code": 400,
    "message": "订单不存在: orderId=123",
    "timestamp": "2025-01-01T10:00:00Z",
    "traceId": "abc-123"
}
```

---

## 5. 🔴 常见坑汇总

```java
// 1. 在循环中捕获异常
// ❌ 循环外 try-catch → 一个异常终止全部
try {
    for (Order order : orders) {
        process(order);  // 一个失败，全部回滚
    }
} catch (Exception e) {
    log.error("处理失败", e);
}

// ✅ 循环内 try-catch → 失败跳过，继续处理
for (Order order : orders) {
    try {
        process(order);
    } catch (Exception e) {
        log.error("订单处理失败: {}", order.getId(), e);
        // 继续下一个
    }
}

// 2. 不要用异常控制流程
// ❌ 异常做流程控制（性能极差）
try {
    Integer.parseInt(input);
    return "是数字";
} catch (NumberFormatException e) {
    return "不是数字";
}

// ✅ 用普通判断
return input.matches("\\d+") ? "是数字" : "不是数字";

// 3. 线程中异常需要特殊处理
ExecutorService executor = Executors.newFixedThreadPool(10);
executor.submit(() -> {
    throw new RuntimeException("线程异常");
    // 主线程感知不到！
});
// 🟢 用 Callable 或 Future.get() 获取异常
Future<?> future = executor.submit(() -> {
    throw new RuntimeException("线程异常");
});
try {
    future.get();  // 这里能捕获到 ExecutionException
} catch (ExecutionException e) {
    log.error("线程执行异常", e.getCause());
}
```

---

## 6. 异常 API 速查

```java
// 构造
throw new IllegalArgumentException("message");
throw new IllegalArgumentException("message", cause);

// 异常方法
e.getMessage();          // 异常描述
e.getCause();            // 原始异常（异常链）
e.getStackTrace();       // 堆栈
e.printStackTrace();     // 打印堆栈（仅调试用）
e.fillInStackTrace();    // 重置堆栈（减少性能开销，但会丢失原始堆栈）

// 工具类
Objects.requireNonNull(obj, "obj 不能为空");      // 为 null 抛 NPE
Preconditions.checkArgument(condition, "参数错误");  // Guava
Assert.notNull(obj, "obj 不能为空");               // Spring
```
