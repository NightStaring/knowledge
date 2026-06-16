# JVM 内存与调优

> JVM 内存模型、GC 算法、常用调优参数、OOM 排查思路。

---

## 1. JVM 内存区域（JDK 8+）

```
┌─────────────────────────────────────┐
│           堆 (Heap)                  │
│  ┌──────────┬──────────┬──────────┐ │
│  │ Young    │          │ Old      │ │
│  │ Eden:S0:S1 │ →       │          │ │
│  │ (8:1:1)  │          │          │ │
│  └──────────┴──────────┴──────────┘ │
├─────────────────────────────────────┤
│     元空间 (Metaspace)               │
│  （取代 JDK 7 的永久代）              │
├─────────────────────────────────────┤
│     虚拟机栈 (VM Stack)              │
│  （每个线程一个栈，存栈帧）            │
├─────────────────────────────────────┤
│     本地方法栈 (Native Stack)        │
├─────────────────────────────────────┤
│     程序计数器 (PC Register)         │
└─────────────────────────────────────┘
```

### 1.1 堆（Heap）

| 区域 | 说明 | 默认占比 |
|------|------|----------|
| **Eden** | 新对象分配区 | Young 的 80% |
| **Survivor 0 (S0)** | 存活对象 | Young 的 10% |
| **Survivor 1 (S1)** | 存活对象 | Young 的 10% |
| **Old Gen** | 长期存活的对象 | 堆的 2/3 |

**对象分配流程：**
```
new Object()
    ↓
Eden 区分配
    ↓
Minor GC → 存活 → S0/S1（年龄 +1）
    ↓
年龄 > 阈值（默认 15）→ 晋升 Old
    ↓
Full GC → 清理 Old 区
```

### 1.2 栈（Stack）

```java
// 每个方法调用创建一个栈帧
public int add(int a, int b) {
    int result = a + b;    // 局部变量表
    return result;         // 操作数栈
}
// 方法结束 → 栈帧弹出
```

```java
// 🔴 栈溢出
public void infiniteRecursion() {
    infiniteRecursion();  // StackOverflowError
}
// -Xss256k 可调栈大小（默认 1MB，Linux amd64）
```

### 1.3 元空间（Metaspace）

- 存储类的元数据（类名、方法信息、字节码等）
- **不**在堆中，使用本地内存（OS 内存）
- 默认无上限（受 OS 内存限制）

```bash
# 建议设置上限，防止类加载泄漏
-XX:MaxMetaspaceSize=256m
```

---

## 2. GC 算法

### 2.1 算法概览

| 算法 | 原理 | 适用 |
|------|------|------|
| **标记-清除** | 标记存活 → 清除死亡对象 | 老年代（CMS） |
| **标记-复制** | 将存活对象复制到另一块区域 | 新生代 |
| **标记-整理** | 标记存活 → 压缩到一端 | 老年代（G1） |

### 2.2 垃圾收集器

| 收集器 | 适用区域 | 特点 | 暂停时间 |
|--------|----------|------|----------|
| **Serial** | Young | 单线程，Client 模式 | 长 |
| **Parallel** | Young | 多线程，高吞吐 | 长 |
| **CMS** | Old | 并发标记，低延迟 | 短（但可能碎片化） |
| **G1** (JDK 9+) | Young + Old | 分区式，可预测暂停 | 可控 |
| **ZGC** (JDK 17+) | 全部 | 亚毫秒暂停 | <1ms |

### 2.3 G1 收集器（推荐）

```bash
# JDK 9+ 默认 GC，适合 4GB+ 堆
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200       # 目标最大暂停 200ms
-XX:InitiatingHeapOccupancyPercent=45  # 触发并发标记的堆占用率
-XX:G1HeapRegionSize=4m        # Region 大小（1~32MB）
```

**G1 工作原理：**
```
堆被划分为 2048 个 Region
每个 Region 可以是 Eden/Survivor/Old/Humongous

1. Young GC: 复制 Eden → Survivor（快速）
2. Concurrent Marking: 找出 Old 区中可回收的对象
3. Mixed GC: 同时回收 Young + 部分 Old（达到暂停目标）
```

### 2.4 ZGC（JDK 17+）

```bash
# 适合超大堆（几百 GB），暂停 < 1ms
-XX:+UseZGC
-XX:ParallelGCThreads=8
```

---

## 3. 常用调优参数

### 3.1 堆内存

```bash
# 堆大小
-Xms4g              # 初始堆（建议与服务端一致，避免扩容开销）
-Xmx4g              # 最大堆（建议与 -Xms 相同）

# 新生代
-Xmn1g              # 新生代大小（建议堆的 1/3 ~ 1/2）
-XX:SurvivorRatio=8  # Eden:S0:S1 = 8:1:1

# 元空间
-XX:MetaspaceSize=256m
-XX:MaxMetaspaceSize=256m
```

### 3.2 GC 日志

```bash
# JDK 8 格式
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-Xloggc:gc.log

# JDK 11+ 统一日志
-Xlog:gc*:file=gc.log:time,uptime,pid
-Xlog:gc*:file=gc.log:time,level,tags
```

### 3.3 OOM 处理

```bash
# 堆溢出时自动 dump 堆快照
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/path/to/dumps/

# OOM 时执行脚本（如重启）
-XX:OnOutOfMemoryError="kill -9 %p"
```

### 3.4 生产环境推荐配置

```bash
# 4GB 堆，G1，JDK 17
-Xms4g -Xmx4g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:ParallelGCThreads=4
-XX:ConcGCThreads=2
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/app/dumps/
-Xlog:gc*:file=/var/log/app/gc.log:time,level,tags
-Djava.security.egd=file:/dev/./urandom  # 避免 Tomcat 启动慢
```

---

## 4. OOM 排查

### 4.1 异常类型

| 异常 | 原因 | 排查方向 |
|------|------|----------|
| `Java heap space` | 堆内存不足 | 内存泄漏 / 堆太小 |
| `Metaspace` | 类元数据太多 | 类加载泄漏 / CGLIB 代理过多 |
| `GC overhead limit exceeded` | GC 占用 > 98% 且回收 < 2% | 堆太小 / 内存泄漏 |
| `Unable to create new native thread` | 线程数超 OS 限制 | 线程泄漏 / 线程池无限 |
| `Direct buffer memory` | 堆外内存不足 | NIO 未释放 Buffer |

### 4.2 排查步骤

```
1. 发现 OOM（告警 / 日志）
    ↓
2. 获取 Heap Dump（已有 -XX:+HeapDumpOnOutOfMemoryError）
    ↓
3. 用 MAT 或 VisualVM 分析 Dump
    ↓
4. 找可疑对象：
   - 大对象（Biggest Objects）
   - GC Root 引用链（找泄漏点）
   - 线程栈（看哪个线程分配了大量对象）
    ↓
5. 修复代码 / 调整参数
```

### 4.3 MAT 分析要点

```
Histogram 视图：
  - 按实例数排序 → 看哪个类实例异常多
  - 按 Shallow Heap 排序 → 看哪个对象占用最多

Dominator Tree 视图：
  - 找出最大的对象及其 GC Root 路径

Leak Suspects Report：
  - MAT 自动分析泄漏嫌疑
  - 看"Shortest Paths To GC Roots"

常见泄漏模式：
  - HashMap 不断 put 不 remove（缓存）
  - ThreadLocal 未 remove（线程池复用）
  - 静态集合持有对象引用
  - 未关闭的 InputStream/Connection
```

---

## 5. CPU 100% 排查

```bash
# 1. 找 CPU 最高的线程
top -Hp <pid>
# 或
ps -mp <pid> -o THREAD,tid,time

# 2. 将线程 ID 转十六进制
printf "%x\n" <tid>

# 3. 查看线程栈
jstack <pid> | grep -A 30 <nid_hex>

# 4. 常见原因
#    - 频繁 GC（GC 线程 CPU 高 → 看 GC 日志）
#    - 死循环（看业务代码）
#    - 正则回溯（看 Pattern 匹配）
#    - 序列化/反序列化（看 JSON 解析）
```

---

## 6. 🔴 常见坑

```java
// 坑 1：ThreadLocal 内存泄漏
// ThreadLocal 的 value 被线程池中的线程持有
// 线程复用 → value 不会被回收
ThreadLocal<byte[]> tl = new ThreadLocal<>();
tl.set(new byte[1024 * 1024]);  // 1MB
// 如果不 remove，线程池中的线程一直持有这个引用

// 🟢 用完后 remove
try {
    tl.set(data);
    // ...
} finally {
    tl.remove();  // 必须！
}

// 坑 2：字符串 intern 导致 OOM
String.valueOf(i).intern();  // 内部字符串不断进入常量池

// 坑 3：大对象直接进入老年代
byte[] buf = new byte[10 * 1024 * 1024];  // > PretenureSizeThreshold
// -XX:PretenureSizeThreshold=5m  # 大于 5MB 直接进 Old
```

---

## 7. 调优参数速查

```bash
# 堆
-Xms                # 初始堆
-Xmx                # 最大堆
-Xmn                # 新生代大小
-XX:SurvivorRatio   # Eden:S0:S1 比例

# GC
-XX:+UseG1GC        # 使用 G1
-XX:+UseZGC         # 使用 ZGC
-XX:MaxGCPauseMillis  # GC 暂停目标

# 日志
-Xlog:gc*           # JDK 11+ GC 日志
-XX:+PrintGCDetails # JDK 8 GC 日志

# OOM
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath

# 调试
-XX:+PrintCommandLineFlags  # 打印最终生效的 JVM 参数
-XX:+PrintFlagsFinal        # 打印所有 JVM 参数
-javaagent:agent.jar        # 附加 agent
```
