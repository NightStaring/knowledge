# 集合框架

> 聚焦高频误区和原理，不赘述基础 API。

---

## 1. 集合体系速览

```
Collection (接口)
├── List    有序可重复
│   ├── ArrayList    数组实现，查快改慢
│   ├── LinkedList   链表实现，插删快
│   └── Vector       线程安全（已过时）
├── Set     不可重复
│   ├── HashSet      哈希表，无序
│   ├── LinkedHashSet 维护插入顺序
│   └── TreeSet      红黑树，可排序
└── Queue   队列
    ├── LinkedList   双端队列
    ├── PriorityQueue 优先队列
    └── ArrayDeque    数组双端队列

Map (接口)
├── HashMap          哈希表，最常用
├── LinkedHashMap    维护插入/访问顺序
├── TreeMap          红黑树，Key 排序
├── ConcurrentHashMap 线程安全
└── Hashtable        线程安全（已过时）
```

---

## 2. ArrayList 深度

### 2.1 扩容机制

```java
// 无参构造：初始容量 0，首次 add 时扩容到 10
ArrayList<Integer> list = new ArrayList<>();  // elementData = {}

// 扩容公式：newCapacity = oldCapacity + (oldCapacity >> 1)
// 即 1.5 倍
// 10 → 15 → 22 → 33 → 49 → 73 ...
```

```java
// 🔴 常见坑：大量添加时反复扩容
List<String> list = new ArrayList<>();
for (int i = 0; i < 100000; i++) {
    list.add("item" + i);
}
// 扩容约 20 次，每次都要 Arrays.copyOf！

// 🟢 预分配容量
List<String> list = new ArrayList<>(100000);  // 一次性分配
```

### 2.2 与 LinkedList 选型

| 操作 | ArrayList | LinkedList |
|------|-----------|------------|
| 尾部追加 | O(1) 均摊 | O(1) |
| 指定位置插入 | O(n) | O(n) |
| 按下标访问 | **O(1)** | O(n) |
| 按值查找 | O(n) | O(n) |
| 内存 | 连续内存，紧凑 | Node 对象开销大 |

```java
// 🟢 绝大多数场景用 ArrayList
List<User> users = new ArrayList<>();

// 只有在频繁队首操作时才考虑 LinkedList
Queue<String> queue = new LinkedList<>();  // 当队列用
Deque<String> stack = new ArrayDeque<>();  // 当栈用，比 LinkedList 快
```

---

## 3. HashMap 深度（高频面试）

### 3.1 数据结构

```
JDK 7：数组 + 链表（头插法）
JDK 8+：数组 + 链表 + 红黑树（尾插法）
```

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `capacity` | 数组容量 | 16 |
| `loadFactor` | 负载因子 | 0.75 |
| `threshold` | 扩容阈值 = capacity × loadFactor | 12 |
| `TREEIFY_THRESHOLD` | 链表转红黑树 | 8 |
| `UNTREEIFY_THRESHOLD` | 红黑树转链表 | 6 |

### 3.2 put 流程

```java
// 1. 计算 hash：key.hashCode() 高 16 位异或低 16 位
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}

// 2. 计算下标：hash & (capacity - 1)  // 等价于取模，但更快
// 3. 如果该位置为空 → 直接放入
// 4. 如果该位置有数据 → 判断 key 是否相等
//    - 相等 → 覆盖
//    - 不等 → 链表/红黑树追加
// 5. 链表长度 >= 8 → 转红黑树
// 6. size > threshold → resize() 扩容为 2 倍
```

### 3.3 扩容原理

```java
// 扩容为 2 倍：newCap = oldCap << 1
// 元素重新分布：要么在原位置，要么在原位置 + oldCap

// 例：容量从 16 扩到 32
// hash & 15 = 3  →  扩容后 hash & 31 = 3  （在原位）
// hash & 15 = 3  →  扩容后 hash & 31 = 19 （在原位+16）
```

### 3.4 🔴 常见坑

```java
// 坑 1：HashMap 线程不安全
Map<String, String> map = new HashMap<>();
// 并发 put 可能死循环（JDK 7 头插法）或丢失数据（JDK 8）
// 🟢 用 ConcurrentHashMap

// 坑 2：自定义 Key 不重写 hashCode
class User {
    String name;
    // ❌ 没重写 hashCode → 每次 put/get 都用不同 hash → 永远取不到
}

// 坑 3：Key 的 hashCode 可变
StringBuilder sb = new StringBuilder("hello");
map.put(sb, 1);
sb.append(" world");  // hashCode 变了！
map.get(sb);          // null → 找不到了！

// 🟢 用不可变对象做 Key（String、Integer）

// 坑 4：初始化容量不是传多少就用多少
Map<String, String> map = new HashMap<>(7);
// 实际容量：8（向上取最近的 2 的幂）
Map<String, String> map = new HashMap<>(9);
// 实际容量：16

// 🟢 预估值计算：expectedSize / 0.75 + 1
// 要存 100 个 → 100/0.75 + 1 ≈ 135
Map<String, String> map = new HashMap<>(135);
```

### 3.5 LinkedHashMap — 实现 LRU 缓存

```java
// accessOrder=true：按访问顺序排序（最近访问的放最后）
// 重写 removeEldestEntry() 实现自动淘汰
class LRUCache<K, V> extends LinkedHashMap<K, V> {
    private final int maxCapacity;

    public LRUCache(int maxCapacity) {
        super(maxCapacity, 0.75f, true);  // accessOrder=true
        this.maxCapacity = maxCapacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxCapacity;
    }
}

// 使用
LRUCache<String, Object> cache = new LRUCache<>(100);
```

---

## 4. ConcurrentHashMap 深度

### 4.1 演进历史

| 版本 | 实现 | 并发粒度 |
|------|------|----------|
| JDK 7 | Segment 数组 + HashEntry | 默认 16 个 Segment |
| JDK 8+ | **Node 数组 + CAS + synchronized** | 单个数组元素 |

### 4.2 JDK 8 实现原理

```java
// put 流程
final V putVal(K key, V value, boolean onlyIfAbsent) {
    // 1. 计算 hash
    // 2. 数组为空 → initTable()（CAS 初始化）
    // 3. 当前 slot 为空 → CAS 直接放入（无锁！）
    // 4. 当前 slot 有数据 → synchronized(slot) 加锁
    //    遍历链表/红黑树，插入或覆盖
    // 5. 链表长度 >= 8 → 转红黑树
    // 6. 超过阈值 → transfer() 扩容（多线程协助）
}
```

### 4.3 常用 API 差异

```java
// ConcurrentHashMap 不允许多线程遍历时修改
ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();

// ✅ 安全：putIfAbsent
map.putIfAbsent("key", "value");  // 不存在才放

// ✅ 安全：computeIfAbsent（推荐，原子操作）
map.computeIfAbsent("key", k -> computeExpensiveValue(k));

// ❌ 不安全：先检查再 put（不是原子的）
if (!map.containsKey("key")) {
    map.put("key", "value");  // 这里可能被其他线程覆盖
}

// ✅ 安全：merge
map.merge("key", "newVal", (old, newVal) -> old + "," + newVal);
```

### 4.4 HashMap vs ConcurrentHashMap vs Hashtable

| | HashMap | ConcurrentHashMap | Hashtable |
|--|---------|-----------------|-----------|
| 线程安全 | ❌ | ✅ | ✅ |
| 性能 | 最高 | 高 | 低（全表锁） |
| null key | ✅ 允许 | ❌ 不允许 | ❌ 不允许 |
| null value | ✅ 允许 | ❌ 不允许 | ❌ 不允许 |

---

## 5. 集合 API 速查

### 5.1 List 常用操作

```java
// 创建
List<String> list = new ArrayList<>();
List<String> list = new ArrayList<>(100);
List<String> list = List.of("a", "b", "c");      // 不可变
List<String> list = Arrays.asList("a", "b");      // 长度固定

// 增删改
list.add("x");                 // 尾部追加
list.add(0, "y");              // 指定位置插入
list.remove(0);                // 按下标删除
list.remove("x");              // 按对象删除
list.set(0, "z");              // 修改

// 遍历（🔴 删除时用 Iterator）
list.forEach(System.out::println);
list.removeIf(s -> s.length() == 0);  // 条件删除
```

### 5.2 Map 常用操作

```java
// 创建
Map<String, String> map = new HashMap<>();
Map<String, String> map = new HashMap<>(32);    // 预分配
Map<String, String> map = Map.of("k1", "v1");   // 不可变

// 常用方法
map.getOrDefault("key", "default");
map.putIfAbsent("key", "value");
map.computeIfAbsent("key", k -> compute(k));
map.computeIfPresent("key", (k, v) -> v + "_new");
map.merge("key", "newVal", String::concat);

// 遍历
map.forEach((k, v) -> System.out.println(k + "=" + v));
```

### 5.3 Set 常用操作

```java
// HashSet 去重
Set<String> set = new HashSet<>(list);  // List → Set 去重

// TreeSet 排序
Set<String> sorted = new TreeSet<>(list);  // 自然排序
Set<String> sorted = new TreeSet<>(Comparator.comparingInt(String::length));

// LinkedHashSet 保持插入顺序
Set<String> ordered = new LinkedHashSet<>(list);
```

### 5.4 集合工具类

```java
// Collections 工具类
Collections.sort(list);
Collections.reverse(list);
Collections.shuffle(list);           // 打乱
Collections.unmodifiableList(list);  // 转不可变
Collections.singletonList("x");      // 单元素列表
Collections.emptyList();             // 空列表

// Stream 转换（详见 stream.md）
list.stream().filter(x -> x > 0).collect(Collectors.toList());
```

### 5.5 🟢 选型速查

| 需求 | 推荐 |
|------|------|
| 绝大多数场景 | `ArrayList` / `HashMap` |
| 线程安全 | `ConcurrentHashMap` / `CopyOnWriteArrayList` |
| LRU 缓存 | `LinkedHashMap` (accessOrder=true) |
| 队列 | `ArrayDeque` / `LinkedList` |
| 排序 | `TreeMap` / `TreeSet` |
| 不可变 | `List.of()` / `Map.of()` / `Collections.unmodifiable*` |
