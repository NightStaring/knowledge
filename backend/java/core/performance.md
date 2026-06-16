# 性能分析

> 性能分析工具与方法论，Profiling、GC 日志分析、OOM 排查。

---

## 1. 性能分析工具

### 1.1 JDK 内置工具

| 工具 | 用途 | 命令 |
|------|------|------|
| **jps** | 查看 Java 进程 | `jps -l` |
| **jstack** | 查看线程栈 | `jstack <pid>` |
| **jmap** | 堆内存快照 | `jmap -heap <pid>` / `jmap -dump:format=b,file=heap.hprof <pid>` |
| **jstat** | GC 统计 | `jstat -gc <pid> 1s` |
| **jcmd** | 全能诊断 | `jcmd <pid> help` |
| **jinfo** | JVM 参数 | `jinfo -flags <pid>` |

```bash
# 常用命令
# 查看 GC 情况（每秒打印一次）
jstat -gcutil <pid> 1s

# 查看线程状态
jstack <pid> | grep -E "java.lang.Thread.State" | sort | uniq -c

# 堆内存概览
jmap -heap <pid>

# 生成 Heap Dump（不暂停应用）
jmap -dump:live,format=b,file=/tmp/heap.hprof <pid>
```

### 1.2 可视化工具

| 工具 | 说明 |
|------|------|
| **VisualVM** | 全能监控（CPU、内存、线程、GC） |
| **JMC (Java Mission Control)** | Oracle 官方，含飞行记录器 JFR |
| **MAT (Memory Analyzer)** | Heap Dump 分析 |
| **Arthas** | 阿里在线诊断工具（推荐） |
| **async-profiler** | 低开销采样 Profiler |

### 1.3 Arthas（推荐）

```bash
# 在线诊断，无需重启应用
# 安装
curl -O https://arthas.aliyun.com/arthas-boot.jar
java -jar arthas-boot.jar

# 常用命令
dashboard           # 实时面板（线程、内存、GC）
thread              # 查看线程，定位 CPU 最高线程
thread -b           # 查看死锁
sc -d *Controller   # 查看类信息
jad com.example.MyController  # 反编译
watch com.example.MyController methodName "{params,returnObj}"  # 观测方法调用
trace com.example.MyController methodName  # 追踪方法调用链
monitor -c 5 com.example.MyController methodName  # 监控方法调用
```

---

## 2. GC 日志分析

### 2.1 GC 日志解读

```bash
# 开启 GC 日志
-Xlog:gc*:file=gc.log:time,level,tags
```

```log
// Young GC 日志
[gc,info] GC(4) Pause Young (G1 Evacuation Pause) 2.0G->1.5G(4.0G) 50.123ms
//         GC 次数 暂停类型             堆用量            耗时

// Full GC 日志
[gc,info] GC(5) Pause Full (G1 Compaction Pause) 3.8G->1.2G(4.0G) 1200.456ms
```

**关键指标：**
- **GC 频率**：Young GC 间隔（正常几秒到几分钟一次）
- **GC 耗时**：Young GC < 100ms，Full GC < 1s
- **晋升大小**：每次 Young GC 后有多少对象进入 Old
- **堆占用趋势**：Full GC 后堆占用是否持续上升

### 2.2 GC 分析工具

```bash
# 在线工具
# GCeasy (gceasy.io)       — 上传 GC 日志，自动分析
# GCViewer                  — 开源桌面工具

# 关键指标解读
# 1. Throughput（吞吐量）= 业务时间 / (业务时间 + GC 时间)
#    正常 > 99%
#
# 2. Pause Time（暂停时间）
#    平均 < 100ms，最大 < 1s
#
# 3. Allocation Rate（分配速率）
#    过高说明频繁创建对象
#
# 4. Promotion Rate（晋升速率）
#    过高说明对象过早进入 Old 区
```

---

## 3. OOM 排查

### 3.1 排查流程

```
1. 确认 OOM 类型（看日志中的异常信息）
    ↓
2. 获取 Heap Dump（jmap 或 -XX:+HeapDumpOnOutOfMemoryError）
    ↓
3. 用 MAT 分析：
   - 打开 Leak Suspects Report
   - 看 Biggest Objects
   - 找 GC Root 引用路径
    ↓
4. 修复代码 / 调整参数
    ↓
5. 验证修复
```

### 3.2 常见 OOM 模式

```java
// 模式 1：Map 不断增长（缓存未设上限）
static Map<String, Object> cache = new HashMap<>();
// 🟢 用 Guava Cache / Caffeine / 设置最大容量

// 模式 2：ThreadLocal 未 remove
ThreadLocal<byte[]> tl = new ThreadLocal<>();
tl.set(new byte[10 * 1024 * 1024]);
// 🟢 finally 中 remove

// 模式 3：字符串拼接 / SQL 拼接
String sql = "select * from user where id in (";
for (Long id : ids) {
    sql += id + ",";  // 不断创建新的 String
}
// 🟢 用 StringBuilder / 批量查询

// 模式 4：未关闭的流
while (true) {
    InputStream is = new FileInputStream("/dev/random");  // 文件描述符泄漏
    // 🟢 try-with-resources
}
```

---

## 4. 性能调优步骤

```
1. 建立基线
   - 当前 QPS、响应时间、CPU、内存、GC 频率
   - 用 JMH 做微基准测试

2. 定位瓶颈
   - CPU 高 → 看线程栈 / Arthas thread
   - 内存高 → 看 Heap Dump / GC 日志
   - 响应慢 → 看调用链 / 慢 SQL

3. 针对性优化
   - CPU 高 → 减少对象创建、优化算法、减少锁竞争
   - 内存高 → 检查泄漏、调小缓存、优化数据结构
   - GC 频繁 → 调堆大小、改 GC 算法、减少对象分配

4. 验证
   - 对比优化前后的指标
   - 压测确认无副作用
```

---

## 5. 🔴 常见坑

```java
// 1. 字符串 split 或正则导致的 CPU 100%
// 🔴 正则回溯陷阱
Pattern pattern = Pattern.compile("(a+)+b");
pattern.matcher("aaaaaaaaaaaaaaaaac").matches();  // 回溯耗时指数级增长

// 2. 循环内创建大量临时对象
for (int i = 0; i < 1000000; i++) {
    String s = new String("data" + i);  // 每次创建新对象
}
// 🟢 对象池 / 复用对象

// 3. 大数组 / 大 List
List<byte[]> list = new ArrayList<>();
while (true) {
    list.add(new byte[1024 * 1024]);  // 每次 1MB
}
// 🟢 设置上限 / 流式处理

// 4. 线程池无限增长
Executors.newCachedThreadPool();  // 线程数无上限
// 🟢 用固定大小线程池 + 有界队列
```
