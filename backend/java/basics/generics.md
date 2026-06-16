# 泛型

> 聚焦类型擦除、通配符、PECS 原则——面试和踩坑的重灾区。

---

## 1. 类型擦除

Java 泛型是**编译期**的，运行时泛型信息被擦除。

```java
// 编译时：List<String>
// 运行时：List（没有 String 信息）
List<String> strings = new ArrayList<>();
List<Integer> integers = new ArrayList<>();

System.out.println(strings.getClass() == integers.getClass());
// true！运行时都是 ArrayList.class
```

### 1.1 擦除规则

```java
// 无界泛型 → 擦除为 Object
public class Box<T> {
    private T value;  // 擦除后: private Object value
}

// 有界泛型 → 擦除为边界类型
public class Box<T extends Comparable<T>> {
    private T value;  // 擦除后: private Comparable value
}
```

### 1.2 擦除带来的限制

```java
// ❌ 不能 new T()
public <T> T create() {
    return new T();  // 编译错误
}
// 🟢 通过 Class 反射创建
public <T> T create(Class<T> clazz) throws Exception {
    return clazz.getDeclaredConstructor().newInstance();
}

// ❌ 不能 instanceof
if (obj instanceof List<String>) { }  // 编译错误
// 🟢 只能检查原始类型
if (obj instanceof List) { }

// ❌ 不能创建泛型数组
List<String>[] array = new List<String>[10];  // 编译错误
// 🟢 通配符方式
List<?>[] array = new List<?>[10];

// ❌ 不能有重载（擦除后签名相同）
void process(List<String> list) { }
void process(List<Integer> list) { }  // 编译错误：方法签名相同
```

---

## 2. 通配符

### 2.1 无界通配符 `?`

```java
// 表示"未知类型"
public void printList(List<?> list) {
    for (Object item : list) {
        System.out.println(item);
    }
}

// 🟢 用 ? 而不是原始类型
public void printList(List list) { }       // ❌ 原始类型，不安全
public void printList(List<?> list) { }    // ✅ 类型安全
```

### 2.2 上界通配符 `? extends T`

```java
// 可以读，不能写（除了 null）
List<? extends Number> list = new ArrayList<Integer>();
list.add(123);              // ❌ 编译错误
list.add(null);             // ✅ 只能加 null
Number n = list.get(0);     // ✅ 可以读，用 Number 接

// 原理：编译器不知道具体是 List<Integer> 还是 List<Double>
// 所以不允许写入（怕放错类型）
```

### 2.3 下界通配符 `? super T`

```java
// 可以写，读时只能用 Object
List<? super Integer> list = new ArrayList<Number>();
list.add(123);              // ✅ 可以写 Integer
Object obj = list.get(0);   // ✅ 可以读，只能用 Object

// 原理：list 可能是 List<Integer>、List<Number>、List<Object>
// 所以只能保证读出来是 Object
```

### 2.4 对比

| | `? extends T` | `? super T` | `?` |
|--|---------------|-------------|-----|
| 读 | ✅ `T` | ✅ `Object` | ✅ `Object` |
| 写 | ❌（null 除外） | ✅ `T` | ❌（null 除外） |
| 记忆 | 生产者（Producer） | 消费者（Consumer） | — |

---

## 3. PECS 原则

**Producer Extends, Consumer Super**——生产者用 extends，消费者用 super。

### 3.1 生产者用 extends

```java
// 从集合中取数据 → 集合是生产者 → extends
public double sum(Collection<? extends Number> numbers) {
    double total = 0;
    for (Number n : numbers) {
        total += n.doubleValue();
    }
    return total;
}

// 可以用 List<Integer>、List<Double>、List<BigDecimal>
sum(new ArrayList<Integer>());
sum(new ArrayList<Double>());
```

### 3.2 消费者用 super

```java
// 向集合中放数据 → 集合是消费者 → super
public void addNumbers(Collection<? super Integer> collection) {
    for (int i = 0; i < 10; i++) {
        collection.add(i);  // 可以放 Integer
    }
}

// 可以用 List<Integer>、List<Number>、List<Object>
addNumbers(new ArrayList<Integer>());
addNumbers(new ArrayList<Number>());
addNumbers(new ArrayList<Object>());
```

### 3.3 经典应用：Collections.copy

```java
// 从 src 读（生产者 → extends），往 dest 写（消费者 → super）
public static <T> void copy(
        List<? super T> dest,    // 消费者
        List<? extends T> src    // 生产者
) {
    for (int i = 0; i < src.size(); i++) {
        dest.set(i, src.get(i));
    }
}
```

### 3.4 实际项目例子

```java
// 🟢 用 PECS 设计 API
public class EventBus {
    // 发布事件：事件是生产者
    public <T> void publish(List<? extends T> events, Class<T> type) {
        for (T event : events) {
            dispatch(event);
        }
    }

    // 注册监听器：监听器列表是消费者
    public <T> void registerListeners(List<? super T> listeners, T handler) {
        for (Object listener : listeners) {
            // ...
        }
    }
}
```

---

## 4. 泛型方法

### 4.1 定义

```java
// 泛型方法：<T> 在返回值之前
public static <T> T getFirst(List<T> list) {
    return list.isEmpty() ? null : list.get(0);
}

// 多个类型参数
public static <K, V> V getOrDefault(Map<K, V> map, K key, V defaultValue) {
    return map.getOrDefault(key, defaultValue);
}

// 有界类型参数
public static <T extends Comparable<T>> T max(List<T> list) {
    return list.stream().max(Comparable::compareTo).orElse(null);
}
```

### 4.2 泛型方法与通配符的选择

```java
// 参数间无依赖 → 通配符
public void printList(List<?> list) { }           // ✅ 简洁

// 参数间有依赖 → 泛型方法
public <T> T getFirst(List<T> list) { }           // ✅ 返回值依赖参数
public <T> void copy(List<T> src, List<T> dest) { }  // ✅ 参数间关联
```

---

## 5. 🔴 常见坑

```java
// 坑 1：静态变量不能用泛型
public class Box<T> {
    private static T value;  // ❌ 编译错误
    // 所有 Box<String>、Box<Integer> 共享同一个 T？说不通
}

// 坑 2：泛型不能用于异常
public class MyException<T> extends Exception { }  // ❌ 编译错误

// 坑 3：泛型数组创建
// ❌
T[] array = new T[10];
// 🟢
T[] array = (T[]) new Object[10];

// 坑 4：原始类型的使用
List<String> strings = new ArrayList<>();
List raw = strings;               // 可以（为了兼容旧代码）
raw.add(123);                     // 可以！插入了 Integer
String s = strings.get(0);        // ❌ ClassCastException
```

---

## 6. API 速查

```java
// 泛型类
public class Box<T> { }
public class Pair<K, V> { }

// 泛型接口
public interface Comparable<T> { }

// 泛型方法
public static <T> T identity(T t) { return t; }

// 类型边界
<T extends Number>                  // 上界
<T extends Comparable<T>>           // 上界（递归）
<T extends Number & Comparable<T>>  // 多个上界（类在前，接口在后）
<T super Integer>                   // ❌ 下界只能用于通配符

// 通配符
List<?>                  // 无界
List<? extends Number>   // 上界
List<? super Integer>    // 下界
```
