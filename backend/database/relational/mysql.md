# MySQL 深度

> InnoDB 引擎、索引原理、事务隔离、锁机制、参数调优。

---

## 1. InnoDB 存储引擎

### 1.1 核心特性

| 特性 | 说明 |
|------|------|
| **事务** | 支持 ACID，REDO/UNDO 日志 |
| **行级锁** | 不是表锁，支持并发写入 |
| **外键** | 支持外键约束 |
| **聚簇索引** | 数据和索引在一起 |
| **MVCC** | 多版本并发控制 |
| **缓冲池** | Buffer Pool 缓存数据和索引 |

### 1.2 InnoDB 内存架构

```
┌─────────────────────────────┐
│      Buffer Pool            │ ← 数据和索引缓存（最重要的内存区域）
│  ┌──────┬──────┬──────┐    │
│  │ Page │ Page │ Page │    │
│  └──────┴──────┴──────┘    │
├─────────────────────────────┤
│      Change Buffer          │ ← 二级索引变更缓冲
├─────────────────────────────┤
│      Log Buffer             │ ← REDO 日志缓冲
└─────────────────────────────┘
```

```ini
# Buffer Pool 建议设为物理内存的 60%~80%
innodb_buffer_pool_size = 4G

# 日志缓冲
innodb_log_buffer_size = 64M

# REDO 日志文件大小
innodb_log_file_size = 512M
```

---

## 2. 索引原理

### 2.1 B+Tree 索引

```
InnoDB 使用 B+Tree，特点：
  - 非叶子节点只存键值（不存数据）
  - 叶子节点存数据（聚簇索引）或主键值（二级索引）
  - 叶子节点有双向链表（范围查询快）
  - 树高度一般 3~4 层（百万级数据）
```

### 2.2 聚簇索引 vs 二级索引

```sql
-- 聚簇索引（主键索引）
-- 叶子节点直接存整行数据
-- 每个表只有一个
CREATE TABLE users (
    id BIGINT PRIMARY KEY,     -- 聚簇索引
    name VARCHAR(50)
);

-- 二级索引（普通索引）
-- 叶子节点存主键值
-- 查询需要回表（先查二级索引，再回聚簇索引查数据）
CREATE INDEX idx_name ON users(name);

-- 覆盖索引：索引包含所有需要查询的列，不需要回表
CREATE INDEX idx_name_age ON users(name, age);
SELECT name, age FROM users WHERE name = 'Alice';  -- 只用索引就够了！
```

### 2.3 索引设计原则

```sql
-- ✅ 适合建索引
-- 1. WHERE 条件中的列
-- 2. JOIN 的关联列
-- 3. ORDER BY 的列
-- 4. 区分度高的列（性别区分度低，不适合）

-- ❌ 不适合建索引
-- 1. 频繁更新的列（维护索引开销大）
-- 2. 值很少的列（性别、状态）
-- 3. 大文本字段（除非前缀索引）

-- 联合索引最左前缀原则
CREATE INDEX idx_status_time ON orders(status, created_at);
-- 能用到索引的查询：
WHERE status = 'PAID'
WHERE status = 'PAID' AND created_at > '2025-01-01'
-- 用不到索引的查询：
WHERE created_at > '2025-01-01'  -- 跳过了最左列
```

### 2.4 🔴 索引失效场景

```sql
-- 1. 对索引列使用了函数
WHERE DATE(created_at) = '2025-01-01';     -- ❌ 不走索引
WHERE created_at >= '2025-01-01' AND created_at < '2025-01-02';  -- ✅

-- 2. 隐式类型转换
WHERE phone = 13800138000;     -- ❌ phone 是 varchar
WHERE phone = '13800138000';   -- ✅

-- 3. LIKE 以 % 开头
WHERE name LIKE '%张%';         -- ❌ 不走索引
WHERE name LIKE '张%';          -- ✅ 走索引

-- 4. OR 条件中有非索引列
WHERE id = 1 OR status = 'PAID';  -- ❌ status 无索引
-- 改成 UNION
WHERE id = 1 UNION WHERE status = 'PAID';  -- ✅

-- 5. 不等于、NOT IN
WHERE status != 'DELETED';      -- ❌ 不走索引
```

---

## 3. 事务与隔离级别

### 3.1 事务隔离级别

```sql
-- 查看当前隔离级别
SELECT @@transaction_isolation;
-- MySQL 默认：REPEATABLE READ
```

| 级别 | 脏读 | 不可重复读 | 幻读 |
|------|------|-----------|------|
| READ UNCOMMITTED | ❌ | ❌ | ❌ |
| READ COMMITTED | ✅ | ❌ | ❌ |
| REPEATABLE READ（默认） | ✅ | ✅ | ❌ |
| SERIALIZABLE | ✅ | ✅ | ✅ |

### 3.2 MVCC 原理

```
MVCC = Multi-Version Concurrency Control

每行数据有隐藏字段：
  DB_TRX_ID：最近修改此行的 transaction ID
  DB_ROLL_PTR：回滚指针（指向 UNDO LOG）

通过 UNDO LOG 实现多版本：
  - 事务开始时生成一个 ReadView
  - 查询时只读 ReadView 可见的版本
  - 实现了"读不阻塞写，写不阻塞读"
```

### 3.3 当前读 vs 快照读

```sql
-- 快照读（普通的 SELECT）
SELECT * FROM users WHERE id = 1;
-- 读的是快照版本，不加锁

-- 当前读（加锁的 SELECT）
SELECT * FROM users WHERE id = 1 FOR UPDATE;    -- 排他锁
SELECT * FROM users WHERE id = 1 LOCK IN SHARE MODE;  -- 共享锁
UPDATE users SET name = 'new' WHERE id = 1;     -- 也是当前读
-- 读的是最新版本，加锁
```

---

## 4. 锁机制

### 4.1 行锁类型

| 锁类型 | 说明 |
|--------|------|
| **Record Lock** | 单行记录锁 |
| **Gap Lock** | 间隙锁（锁住一个范围，防止幻读） |
| **Next-Key Lock** | Record Lock + Gap Lock（InnoDB 默认） |

```sql
-- Next-Key Lock 示例
-- 表中有 id: 1, 3, 5
SELECT * FROM users WHERE id > 2 FOR UPDATE;
-- 锁住：3, 5 的行锁 + (2,3), (3,5), (5, +∞) 的间隙锁
-- 其他事务不能插入 id=2,4,6 的数据
```

### 4.2 死锁

```sql
-- 事务 A：
UPDATE orders SET status = 'PAID' WHERE id = 1;
UPDATE orders SET status = 'PAID' WHERE id = 2;

-- 事务 B：
UPDATE orders SET status = 'PAID' WHERE id = 2;
UPDATE orders SET status = 'PAID' WHERE id = 1;
-- 互相等待 → 死锁

-- 🟢 预防：固定加锁顺序
-- 所有事务都按 id 从小到大更新
```

### 4.3 🔴 锁常见坑

```sql
-- 1. 没走索引的行锁升级为表锁
UPDATE users SET name = 'new' WHERE phone = '138xxx';  -- phone 无索引
-- 实际锁全表！

-- 2.  INSERT ... ON DUPLICATE KEY UPDATE 可能产生间隙锁
-- 3.  外键约束需要检查父表，可能产生额外的锁
```

---

## 5. 性能调优

### 5.1 参数配置

```ini
[mysqld]
# 内存
innodb_buffer_pool_size = 4G           # 最重要！设为内存 60~80%
innodb_buffer_pool_instances = 4       # 减少锁竞争

# 日志
innodb_log_file_size = 512M            # REDO 日志大小
innodb_log_buffer_size = 64M
innodb_flush_log_at_trx_commit = 1     # 安全（每次提交都刷盘）
                                       # =2 性能好但可能丢 1 秒数据

# 连接
max_connections = 500
wait_timeout = 600                     # 连接超时

# 其他
innodb_flush_method = O_DIRECT         # 绕过 OS 缓存
innodb_file_per_table = 1              # 每个表独立文件空间
```

### 5.2 慢查询配置

```ini
slow_query_log = ON
slow_query_log_file = /var/log/mysql/slow.log
long_query_time = 1                    # 超过 1 秒记录
log_queries_not_using_indexes = ON     # 没走索引的也记录
```

---

## 6. EXPLAIN 解读

```sql
EXPLAIN SELECT * FROM orders WHERE user_id = 1\G
```

| 字段 | 说明 | 好 | 坏 |
|------|------|----|----|
| **type** | 访问类型 | const/ref/range | ALL（全表扫描） |
| **key** | 使用的索引 | 有值 | NULL |
| **rows** | 扫描行数 | 小 | 大 |
| **Extra** | 额外信息 | Using index | Using filesort |
| **filtered** | 过滤比例 | 高 | 低 |

**type 好坏排序：**
```
system > const > eq_ref > ref > range > index > ALL
                                    ↑         ↑
                              范围查询   全表扫描（要优化）
```

---

## 7. 🔴 常见坑速查

| 坑 | 后果 | 对策 |
|----|------|------|
| 索引列用函数 | 不走索引 | 改写为范围查询 |
| 隐式类型转换 | 不走索引 | 类型保持一致 |
| OR 条件 | 可能不走索引 | 用 UNION |
| SELECT * | 不能覆盖索引 | 只查需要的列 |
| 大表 LIMIT 分页 | 越往后越慢 | 用游标分页 |
| 长事务 | 锁不释放、UNDO 膨胀 | 控制事务大小 |
| 缺少联合索引 | 多个单列索引浪费 | 建联合索引 |
