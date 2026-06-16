# 常见陷阱

> Java 日常编码中高频踩坑点汇总。

---

## 1. String 相关

### 1.1 字符串比较

```java
// ❌ 用 == 比较字符串
String a = "hello";
String b = "hello";
String c = new String("hello");

System.out.println(a == b);        // true（字符串常量池）
System.out.println(a == c);        // false（new 创建了新对象）

// ✅ 用 equals
System.out.println(a.equals(c));   // true

// ❌ 常量调 equals 可能 NPE
String input = null;
input.equals("hello");             // NullPointerException

// ✅ 常量放前面
"hello".equals(input);             // false，不抛异常
Objects.equals(input, "hello");    // ✅ 最安全
```

### 1.2 字符串拼接

```java
// ❌ 循环中用 + 拼接
String result = "";
for (int i = 0; i < 1000; i++) {
    result += i;  // 每次创建新的 StringBuilder + String
}

// ✅ 用 StringBuilder
StringBuilder sb = new StringBuilder(1000);
for (int i = 0; i < 1000; i++) {
    sb.append(i);
}
String result = sb.toString();

// ✅ 用 String.join
String joined = String.join(",", "a", "b", "c");  // a,b,c

// ✅ 用 Collectors.joining
List<String> list = List.of("a", "b", "c");
String result = list.stream().collect(Collectors.joining(","));
```

### 1.3 substring 内存泄漏（JDK 6）

```java
// 🔴 JDK 6：substring 会持有原始字符串的引用
String huge = readHugeFile();  // 100MB
String small = huge.substring(0, 10);  // 只取 10 个字符
// JDK 6 中，small 仍然持有 100MB 的 char[]
// 导致 GC 无法回收 huge 的原始数组

// ✅ JDK 7+：substring 会创建新的 char[]，已修复
// ✅ 如果不放心：new String(small)
```

### 1.4 split 的特殊处理

```java
// split 参数是正则表达式，特殊字符需要转义
"a.b.c".split(".");     // [] 不是期望的 [a, b, c]
"a.b.c".split("\\.");   // [a, b, c] ✅

"a|b|c".split("|");     // 不是期望的结果
"a|b|c".split("\\|");   // [a, b, c] ✅
```

---

## 2. Integer 缓存

```java
// Integer 缓存范围：-128 ~ 127
Integer a = 100;
Integer b = 100;
System.out.println(a == b);    // true（缓存命中）

Integer c = 200;
Integer d = 200;
System.out.println(c == d);    // false（超出缓存范围）

// 🔴 自动装箱同样适用
Integer e = 127;
Integer f = 127;
System.out.println(e == f);    // true

Integer g = 128;
Integer h = 128;
System.out.println(g == h);    // false

// 🟢 始终用 equals 比较包装类
System.out.println(g.equals(h));  // true

// 🟢 缓存范围可调（JVM 参数）
// -Djava.lang.Integer.IntegerCache.high=1000
```

---

## 3. 浮点运算

```java
// ❌ 用 float/double 做金额计算
double a = 0.1;
double b = 0.2;
System.out.println(a + b);      // 0.30000000000000004

// ✅ 用 BigDecimal
BigDecimal a = new BigDecimal("0.1");
BigDecimal b = new BigDecimal("0.2");
System.out.println(a.add(b));   // 0.3

// 🔴 BigDecimal 构造要用 String
new BigDecimal(0.1);            // ❌ 0.10000000000000000555
new BigDecimal("0.1");          // ✅ 0.1

// 🔴 BigDecimal 比较用 compareTo
new BigDecimal("1.0").equals(new BigDecimal("1"));      // false（scale 不同）
new BigDecimal("1.0").compareTo(new BigDecimal("1"));   // 0（相等）
```

---

## 4. 数组与 List

### 4.1 Arrays.asList 的坑

```java
// 🔴 Arrays.asList 返回的是固定大小列表
List<String> list = Arrays.asList("a", "b", "c");
list.add("d");     // ❌ UnsupportedOperationException
list.remove(0);    // ❌ UnsupportedOperationException
list.set(0, "x");  // ✅ 可以修改

// 🟢 要可变列表
List<String> list = new ArrayList<>(Arrays.asList("a", "b", "c"));

// 🔴 Arrays.asList 是视图，修改会影响原数组
String[] arr = {"a", "b"};
List<String> list = Arrays.asList(arr);
list.set(0, "x");
System.out.println(arr[0]);  // "x"
```

### 4.2 List 转数组

```java
// 🟢 带类型参数的 toArray
List<String> list = new ArrayList<>();
// ❌
String[] arr = (String[]) list.toArray();  // ClassCastException
// ✅
String[] arr = list.toArray(new String[0]);    // 经典写法
String[] arr = list.toArray(String[]::new);    // Java 11+ 推荐
```

### 4.3 数组协变 vs 泛型不变

```java
// 数组是协变的（covariant）
Object[] arr = new String[10];     // ✅ 编译通过
arr[0] = 123;                      // ✅ 编译通过，运行时 ArrayStoreException

// 泛型是不变的（invariant）
List<Object> list = new ArrayList<String>();  // ❌ 编译错误
```

---

## 5. switch 相关

```java
// ❌ switch 忘记 break
switch (status) {
    case 1:
        doSomething();
    case 2:           // status=1 也会执行这里！
        doOther();
        break;
}

// ✅ Java 14+ switch 表达式
String result = switch (status) {
    case 1 -> "one";
    case 2 -> "two";
    default -> "unknown";
};

// ✅ 箭头语法自动 break，不需要写 break
```

---

## 6. 方法重载与重写

```java
// 🔴 重载的选择是编译期决定的
class Parent {
    void process(String s) { System.out.println("Parent String"); }
}

class Child extends Parent {
    void process(Object o) { System.out.println("Child Object"); }
}

Child c = new Child();
c.process("hello");  // "Child Object" —— 不是"Parent String"！
// 因为 Child.process(Object) 重载了，不是重写
```

---

## 7. 值传递

```java
// Java 永远是值传递

// 例 1：基本类型
int x = 10;
change(x);
System.out.println(x);  // 10，没变

// 例 2：对象引用
User u = new User("alice");
changeRef(u);
System.out.println(u.getName());  // 改了

// 例 3：修改引用指向（没用的）
User u = new User("alice");
changeAssign(u);
System.out.println(u.getName());  // "alice"，没变！

void changeAssign(User user) {
    user = new User("bob");  // 只修改了局部变量的指向
}
```

---

## 8. 日期时间（Java 8+）

```java
// ❌ 用 Date 和 Calendar（可变、线程不安全、设计混乱）
Date date = new Date(125, 0, 1);   // 2025-01-01？不对，是 1900+125
Calendar cal = Calendar.getInstance();
cal.set(2025, Calendar.JANUARY, 1);  // month 从 0 开始

// ✅ 用 java.time（不可变、线程安全）
LocalDate date = LocalDate.of(2025, Month.JANUARY, 1);
LocalTime time = LocalTime.of(10, 30);
LocalDateTime dt = LocalDateTime.of(2025, 1, 1, 10, 30);

// 格式化
DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
String formatted = dt.format(formatter);              // 2025-01-01 10:30:00
LocalDateTime parsed = LocalDateTime.parse("2025-01-01 10:30:00", formatter);

// 时间戳
Instant now = Instant.now();                          // 当前 UTC 时间戳
long epoch = System.currentTimeMillis();              // 老方式

// 计算
LocalDate tomorrow = LocalDate.now().plusDays(1);
LocalDate lastMonth = LocalDate.now().minusMonths(1);
long days = ChronoUnit.DAYS.between(start, end);

// 时区
ZonedDateTime zdt = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
```

---

## 9. 🔴 陷阱速查表

| 陷阱 | 表现 | 对策 |
|------|------|------|
| `String ==` 比较 | 有时 true 有时 false | 用 `equals()` |
| `Integer 127 ==` | 缓存内 true，外 false | 用 `equals()` |
| `float/double` 金额 | 精度丢失 | 用 `BigDecimal(String)` |
| `Arrays.asList` 增删 | UnsupportedOperationException | `new ArrayList<>(Arrays.asList(...))` |
| `substring` 内存泄漏 | JDK 6 持有大数组引用 | JDK 7+ 已修复 |
| `split` 特殊字符 | 非预期结果 | 转义 `\\.`、`\\|` |
| `switch` 无 break | 穿透 | 用 `->` 语法或加 break |
| `SimpleDateFormat` | 线程不安全 | 用 `DateTimeFormatter` |
| `toArray()` 无参 | ClassCastException | `toArray(new T[0])` |
| 循环拼接 String | 性能差 | 用 `StringBuilder` |
| try-finally return | 覆盖 try 的 return/异常 | finally 中不 return |
