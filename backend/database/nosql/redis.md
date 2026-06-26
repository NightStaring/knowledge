# Redis

> 缓存策略、数据结构、分布式锁、过期策略、常见坑。

---

## 1. Redis 数据结构

### 1.1 五种基础类型

| 类型 | 说明 | 典型场景 |
|------|------|----------|
| **String** | 字符串/数值 | 缓存、计数器、分布式锁 |
| **Hash** | 键值对集合 | 对象缓存、用户信息 |
| **List** | 双向链表 | 消息队列、最新消息 |
| **Set** | 无序集合 | 去重、标签、关注关系 |
| **Sorted Set** | 有序集合 | 排行榜、延迟队列 |

```bash
# String
SET user:1:name "Alice"
GET user:1:name
INCR page:views          # 计数器
SETEX token:abc 3600 "value"  # 带过期时间

# Hash
HSET user:1 name "Alice" age 30
HGET user:1 name
HGETALL user:1

# List
LPUSH messages "msg1"   # 左侧推入
RPUSH messages "msg2"   # 右侧推入
LPOP messages           # 左侧弹出
BRPOP messages 0        # 阻塞弹出

# Set
SADD user:1:tags "java" "redis"
SMEMBERS user:1:tags
SISMEMBER user:1:tags "java"

# Sorted Set
ZADD leaderboard 100 "user1" 200 "user2"
ZREVRANGE leaderboard 0 9 WITHSCORES  # 前十名
ZSCORE leaderboard "user1"
```

### 1.2 高级类型

| 类型 | 说明 | 场景 |
|------|------|------|
| **Bitmap** | 位图 | 签到、统计 |
| **HyperLogLog** | 基数统计 | UV 统计（有误差） |
| **GEO** | 地理位置 | 附近的人 |
| **Stream** | 消息流 | 消息队列、事件溯源 |

---

## 2. 缓存策略

### 2.1 缓存模式

```java
// Cache Aside（旁路缓存）— 最常用
// 读：先查缓存 → 没有则查 DB → 写入缓存
// 写：先更新 DB → 删除缓存

// 读流程
public User getUser(Long id) {
    String key = "user:" + id;

    // 1. 查缓存
    User user = redis.get(key);
    if (user != null) return user;

    // 2. 缓存没有，查数据库
    user = userDao.findById(id);

    // 3. 写入缓存（设置过期时间）
    if (user != null) {
        redis.setex(key, 3600, user);
    }
    return user;
}

// 写流程（更新 DB + 删除缓存）
public void updateUser(User user) {
    userDao.update(user);           // 1. 更新 DB
    redis.del("user:" + user.getId());  // 2. 删除缓存
    // 为什么不更新缓存？延迟加载，下次读时自动写入
}
```

### 2.2 过期策略

| 策略 | 说明 |
|------|------|
| **定期删除** | 默认 100ms 随机抽查过期 key 删除 |
| **惰性删除** | 访问 key 时检查是否过期，过期则删 |
| **内存淘汰** | 内存满时按策略淘汰 |

```ini
# 内存淘汰策略
maxmemory-policy allkeys-lru

# 可选策略
noeviction        # 不淘汰，写操作报错（默认）
allkeys-lru       # 淘汰最近最少使用的 key（推荐）
allkeys-lfu       # 淘汰最不经常使用的 key
volatile-lru      # 只在设置了过期时间的 key 中淘汰
volatile-ttl      # 淘汰即将过期的 key
```

### 2.3 🔴 缓存三大问题

```java
// 1. 缓存穿透：查一个不存在的数据
// 每次请求都穿过缓存打到数据库
// 🟢 缓存空值（短期，如 60 秒）
redis.setex("user:" + id, 60, null);

// 🟢 布隆过滤器（判断 key 是否可能存在）
BloomFilter<String> bloom = BloomFilter.create(Funnels.stringFunnel(), 100000);
if (!bloom.mightContain(id)) {
    return null;  // 一定不存在
}

// 2. 缓存击穿：热点 key 过期，大量请求同时打 DB
// 🟢 互斥锁（只让一个请求查 DB，其他等待）
String lockKey = "lock:user:" + id;
if (redis.setnx(lockKey, "1", 10)) {  // 获取锁
    try {
        data = db.query(id);
        redis.setex(key, 3600, data);
    } finally {
        redis.del(lockKey);
    }
} else {
    Thread.sleep(100);
    // 重试或返回旧缓存
}

// 🟢 热点数据不过期（后台异步刷新）
// 缓存永不过期，后台线程定期刷新

// 3. 缓存雪崩：大量 key 同时过期
// 🟢 过期时间加随机值
redis.setex(key, 3600 + random.nextInt(600), value);

// 🟢 多级缓存（本地缓存 + Redis）
// 🟢 限流降级，保护 DB
```

---

## 3. 分布式锁

### 3.1 基本实现

```java
// 获取锁
public boolean tryLock(String key, String requestId, int expireSeconds) {
    // SET NX = 不存在才设置（原子操作）
    // SET EX = 过期时间
    return "OK".equals(redis.call(
        "SET", key, requestId, "NX", "EX", expireSeconds
    ));
}

// 释放锁（用 Lua 保证原子性）
public boolean releaseLock(String key, String requestId) {
    String script = """
        if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('del', KEYS[1])
        else
            return 0
        end
        """;
    return redis.eval(script, List.of(key), List.of(requestId));
}
```

### 3.2 RedLock（Redisson）

```java
// Redisson 自动处理了锁续期、重试等
@Bean
public RedissonClient redissonClient() {
    Config config = new Config();
    config.useSingleServer().setAddress("redis://localhost:6379");
    return Redisson.create(config);
}

// 使用
public void processOrder(Long orderId) {
    RLock lock = redissonClient.getLock("lock:order:" + orderId);
    try {
        // 尝试加锁，最多等 10 秒，锁 30 秒自动释放
        if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {
            process(orderId);
        }
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

---

## 4. 🔴 常见坑

```java
// 坑 1：大 key
// 一个 key 存了 100MB 数据
// → 查询慢、阻塞其他命令、网络传输慢
// 🟢 拆分大 key / 用 Hash 分片

// 坑 2：热 key
// 一个 key 每秒被访问 10 万次
// → 单线程处理不过来
// 🟢 本地缓存 + 读写分离 + 热点 key 复制

// 坑 3：KEYS 命令
KEYS user:*       // ❌ 全量扫描，阻塞 Redis！
SCAN 0 MATCH user:*  // ✅ 游标式扫描

// 坑 4：事务
// Redis 事务不支持回滚！
MULTI
SET a 1
SET b 2
EXEC
// 中间某条命令失败，其他命令继续执行

// 坑 5：AOF 文件过大
// 🟢 定期 BGREWRITEAOF
```

---

## 5. 命令速查

```bash
# 通用
KEYS pattern        # 查找 key（生产环境禁止！用 SCAN）
SCAN cursor MATCH pattern
EXISTS key
DEL key
EXPIRE key seconds
TTL key
TYPE key
RENAME key newkey

# String
SET key value
GET key
SETEX key seconds value   # 设置+过期
SETNX key value            # 不存在才设置
INCR key
INCRBY key increment
MSET key1 val1 key2 val2
MGET key1 key2

# Hash
HSET key field value
HGET key field
HDEL key field
HEXISTS key field
HGETALL key
HINCRBY key field increment

# List
LPUSH/RPUSH key value
LPOP/RPOP key
LLEN key
LRANGE key start stop   # 分页取列表

# Set
SADD key member
SREM key member
SMEMBERS key
SISMEMBER key member
SINTER key1 key2       # 交集
SUNION key1 key2       # 并集

# Sorted Set
ZADD key score member
ZREM key member
ZRANGE key start stop [WITHSCORES]
ZREVRANGE key start stop [WITHSCORES]
ZSCORE key member
ZINCRBY key increment member

# 管理
SELECT db              # 切换数据库（0~15）
FLUSHDB                # 清空当前库
FLUSHALL               # 清空所有库
INFO                   # 查看状态
MONITOR                # 监控命令（生产慎用）
```
