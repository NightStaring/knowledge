# Stream + Lambda + Optional

> 函数式编程核心用法与 API 速查。

---

## 1. Lambda 表达式

### 1.1 语法

```java
// 完整形式
(参数列表) -> { 方法体; return 返回值; }

// 简化规则
// 1. 参数类型可省略（编译器推断）
// 2. 只有一个参数时可省略 ()
// 3. 方法体只有一行时可省略 {} 和 return
```

```java
// 逐步简化
// 原始匿名类
list.sort(new Comparator<String>() {
    @Override
    public int compare(String a, String b) {
        return a.length() - b.length();
    }
});

// Lambda
list.sort((String a, String b) -> {
    return a.length() - b.length();
});

// 简化参数类型
list.sort((a, b) -> {
    return a.length() - b.length();
});

// 简化方法体
list.sort((a, b) -> a.length() - b.length());

// 方法引用（最简）
list.sort(Comparator.comparingInt(String::length));
```

### 1.2 方法引用

```java
// 静态方法
Integer::parseInt         // x -> Integer.parseInt(x)

// 实例方法（特定对象）
System.out::println       // x -> System.out.println(x)

// 实例方法（任意对象）
String::length            // x -> x.length()
String::toLowerCase       // x -> x.toLowerCase()

// 构造方法
ArrayList::new            // () -> new ArrayList()
User::new                 // (name, age) -> new User(name, age)

// 数组构造
int[]::new                // len -> new int[len]
```

### 1.3 🔴 常见坑

```java
// 坑 1：Lambda 中使用的局部变量必须是 final 或 effectively final
int x = 10;
// x = 20;  // 如果取消注释，下面会编译错误
Runnable r = () -> System.out.println(x);

// 坑 2：this 指向不同
// 匿名类中 this 指向匿名类本身
// Lambda 中 this 指向外部类

// 坑 3：Lambda 序列化
// 如果 lambda 要序列化，需要显式指定类型
Runnable r = (Runnable & Serializable) () -> System.out.println("ok");
```

---

## 2. Stream 核心操作

### 2.1 操作分类

```
创建 → 中间操作（惰性） → 终端操作（触发）
                ↓
        一个 Stream 只能用一次！
```

| 类别 | 方法 | 说明 |
|------|------|------|
| 创建 | `stream()` / `parallelStream()` | 从集合创建 |
| 创建 | `Stream.of()` | 从值创建 |
| 创建 | `Arrays.stream()` | 从数组创建 |
| 创建 | `Stream.iterate()` / `Stream.generate()` | 无限流 |
| 中间 | `filter()` | 过滤 |
| 中间 | `map()` / `flatMap()` | 转换 |
| 中间 | `distinct()` | 去重 |
| 中间 | `sorted()` | 排序 |
| 中间 | `peek()` | 调试（一般不用） |
| 中间 | `limit()` / `skip()` | 截取 |
| 终端 | `collect()` | 收集（最常用） |
| 终端 | `forEach()` | 遍历 |
| 终端 | `count()` | 计数 |
| 终端 | `anyMatch()` / `allMatch()` / `noneMatch()` | 匹配 |
| 终端 | `findFirst()` / `findAny()` | 查找 |
| 终端 | `reduce()` | 归约 |
| 终端 | `toList()` (Java 16+) | 转 List |

### 2.2 常用示例

```java
List<Order> orders = getOrders();

// filter + map + collect（最常用组合）
List<String> userNames = orders.stream()
    .filter(o -> o.getAmount() > 100)
    .map(Order::getUserName)
    .distinct()
    .collect(Collectors.toList());

// flatMap：展平嵌套
List<List<Integer>> nested = List.of(List.of(1,2), List.of(3,4));
List<Integer> flat = nested.stream()
    .flatMap(Collection::stream)       // 展平
    .collect(Collectors.toList());     // [1, 2, 3, 4]

// sorted：排序
orders.stream()
    .sorted(Comparator.comparing(Order::getAmount))
    .sorted(Comparator.comparing(Order::getDate).reversed())
    .collect(Collectors.toList());

// limit + skip：分页
orders.stream()
    .skip(page * size)
    .limit(size)
    .collect(Collectors.toList());

// anyMatch：是否存在
boolean hasExpensive = orders.stream()
    .anyMatch(o -> o.getAmount() > 10000);

// findFirst：取第一个（返回 Optional）
Optional<Order> first = orders.stream()
    .filter(o -> o.getStatus() == "PAID")
    .findFirst();
```

### 2.3 Collectors 大全

```java
// toList
List<String> list = stream.collect(Collectors.toList());

// toSet
Set<String> set = stream.collect(Collectors.toSet());

// toMap（🔴 key 不能重复，否则抛异常）
Map<Long, Order> map = orders.stream()
    .collect(Collectors.toMap(Order::getId, Function.identity()));
// 重复 key 处理
Map<String, String> map = list.stream()
    .collect(Collectors.toMap(
        k -> k,
        v -> v,
        (existing, replacement) -> existing  // 冲突保留旧的
    ));

// groupingBy：分组（最常用）
Map<String, List<Order>> byStatus = orders.stream()
    .collect(Collectors.groupingBy(Order::getStatus));

// 分组后计数
Map<String, Long> countByStatus = orders.stream()
    .collect(Collectors.groupingBy(Order::getStatus, Collectors.counting()));

// 分组后聚合
Map<String, Double> avgByStatus = orders.stream()
    .collect(Collectors.groupingBy(
        Order::getStatus,
        Collectors.averagingDouble(Order::getAmount)
    ));

// 多级分组
Map<String, Map<String, List<Order>>> multi = orders.stream()
    .collect(Collectors.groupingBy(
        Order::getStatus,
        Collectors.groupingBy(o -> o.getAmount() > 1000 ? "high" : "low")
    ));

// partitioningBy：分区（true/false 两组）
Map<Boolean, List<Order>> partition = orders.stream()
    .collect(Collectors.partitioningBy(o -> o.getAmount() > 1000));

// joining：拼接
String names = users.stream()
    .map(User::getName)
    .collect(Collectors.joining(", ", "[", "]"));  // [Alice, Bob, Charlie]

// summarizing：统计
DoubleSummaryStatistics stats = orders.stream()
    .collect(Collectors.summarizingDouble(Order::getAmount));
stats.getSum();
stats.getAverage();
stats.getMax();
stats.getMin();
stats.getCount();

// reducing：自定义归约
Optional<Order> maxOrder = orders.stream()
    .collect(Collectors.maxBy(Comparator.comparing(Order::getAmount)));
```

### 2.4 🔴 常见坑

```java
// 坑 1：Stream 只能用一次
Stream<String> stream = list.stream();
stream.forEach(System.out::println);
stream.count();  // ❌ IllegalStateException: stream has already been operated upon or closed

// 坑 2：无限流要配合 limit
Stream.generate(Math::random)   // 无限流
    .limit(10)                  // 必须限制
    .forEach(System.out::println);

// 坑 3：并行流线程安全
List<Integer> result = new ArrayList<>();
IntStream.range(0, 1000).parallel()
    .forEach(i -> result.add(i));   // ❌ 线程不安全
// 🟢 用 collect 或 toList
List<Integer> result = IntStream.range(0, 1000)
    .parallel()
    .boxed()
    .collect(Collectors.toList());

// 坑 4：toMap 的 key 不可重复
List<String> items = List.of("a", "b", "a");
Map<String, String> map = items.stream()
    .collect(Collectors.toMap(Function.identity(), Function.identity()));
// ❌ IllegalStateException: duplicate key

// 坑 5：在 forEach 中修改外部变量（编译错误）
// ❌ 不能使用非 final 的外部变量
int sum = 0;
list.stream().forEach(x -> sum += x);  // 编译错误
// 🟢 用 reduce 或 collect
int sum = list.stream().reduce(0, Integer::sum);
```

---

## 3. Optional

### 3.1 设计目的

```java
// ❌ 不优雅的 null 检查
public String getCity(User user) {
    if (user != null) {
        Address address = user.getAddress();
        if (address != null) {
            return address.getCity();
        }
    }
    return "unknown";
}

// ✅ Optional 链式调用
public String getCity(User user) {
    return Optional.ofNullable(user)
        .map(User::getAddress)
        .map(Address::getCity)
        .orElse("unknown");
}
```

### 3.2 常用方法

```java
// 创建
Optional<String> empty = Optional.empty();
Optional<String> of = Optional.of("value");       // value 不能为 null
Optional<String> ofNullable = Optional.ofNullable(null);  // 可为 null

// 安全取值
String val = opt.orElse("default");           // 有值返回值，无值返回默认
String val = opt.orElseGet(() -> compute());   // 延迟计算默认值（推荐）
String val = opt.orElseThrow();               // 无值抛异常（Java 10+）
String val = opt.orElseThrow(RuntimeException::new);  // 自定义异常

// 判断
opt.isPresent();     // 是否存在
opt.isEmpty();       // 是否为空（Java 11+）

// 转换
opt.map(String::length);           // 有值则转换
opt.flatMap(s -> Optional.of(s));  // 转换结果已是 Optional

// 过滤
opt.filter(s -> s.length() > 5);

// 消费
opt.ifPresent(v -> System.out.println(v));
opt.ifPresentOrElse(
    v -> System.out.println(v),
    () -> System.out.println("empty")
);
```

### 3.3 🔴 常见坑

```java
// 坑 1：Optional 不能序列化
// Optional 没有实现 Serializable
class User implements Serializable {
    private Optional<String> name;  // ❌ NotSerializableException
    private String name;            // ✅ 直接用原始类型
}

// 坑 2：不要把 Optional 用作方法参数
public void process(Optional<String> param) { }  // ❌ 不良设计
public void process(String param) { }             // ✅

// 坑 3：不要把 Optional 作为字段
class User {
    private Optional<String> name;  // ❌
}

// 坑 4：isPresent-get 不如用 orElse
if (opt.isPresent()) {
    return opt.get();
}
// 🟢
return opt.orElse("default");

// 坑 5：of 传入 null 直接抛异常
Optional.of(null);  // ❌ NullPointerException
Optional.ofNullable(null);  // ✅
```

### 3.4 最佳实践

```java
// ✅ 作为返回值，表示"可能没有结果"
public Optional<User> findByUsername(String username) {
    return Optional.ofNullable(users.get(username));
}

// ✅ 链式处理 null 安全
String result = Optional.ofNullable(user)
    .map(User::getAddress)
    .map(Address::getCity)
    .filter(city -> city.length() > 2)
    .orElse("unknown");

// ✅ 集合不要包装 Optional
List<Optional<String>> list;  // ❌ 不要
List<String> list;            // ✅ 直接存值，用 Collections.emptyList() 表示空
```
