# SQL 优化

> EXPLAIN 解读、慢查询分析、索引优化、分页优化。

---

## 1. EXPLAIN 解读

### 1.1 输出字段

```sql
EXPLAIN SELECT * FROM orders WHERE user_id = 1\G
```

| 字段 | 说明 | 好 | 需要优化 |
|------|------|----|----------|
| **type** | 访问类型 | const/ref/range | ALL |
| **possible_keys** | 可能用的索引 | 有值 | NULL |
| **key** | 实际用的索引 | 有值 | NULL |
| **rows** | 扫描行数 | 小 | 大 |
| **Extra** | 额外信息 | Using index | Using filesort |
| **filtered** | 过滤比例(%) | 高 | 低 |

### 1.2 type 详解

```
从好到差：
system    → 系统表（几乎见不到）
const     → 主键/唯一索引等值查询（最快）
eq_ref    → JOIN 时主键/唯一索引关联
ref       → 普通索引等值查询
range     → 索引范围查询（>、<、BETWEEN、IN）
index     → 扫描整个索引树
ALL       → 全表扫描（最慢，要优化！）
```

```sql
-- const：主键查询
EXPLAIN SELECT * FROM users WHERE id = 1;       -- type: const

-- ref：普通索引等值
EXPLAIN SELECT * FROM users WHERE email = 'a@b.com';  -- type: ref

-- range：范围查询
EXPLAIN SELECT * FROM orders WHERE created_at > '2025-01-01';  -- type: range

-- ALL：全表扫描（要优化）
EXPLAIN SELECT * FROM orders WHERE status = 'PAID';  -- type: ALL
```

### 1.3 Extra 解读

| Extra | 含义 | 优化方向 |
|-------|------|----------|
| **Using index** | 覆盖索引，好 | 保持 |
| **Using where** | 用 WHERE 过滤 | 可能需索引 |
| **Using index condition** | 索引下推，好 | 保持 |
| **Using filesort** | 需要排序，不在索引中 | 加索引包含排序列 |
| **Using temporary** | 用了临时表（GROUP BY） | 加索引 |
| **Using MRR** | 多范围读取 | 好 |

---

## 2. 索引优化

### 2.1 联合索引设计

```sql
-- 联合索引的列顺序：等值条件放前面，范围条件放后面
-- 常用查询：
WHERE status = 'PAID' AND created_at > '2025-01-01'

-- ✅ 好：等值列在前
CREATE INDEX idx_status_time ON orders(status, created_at);

-- ❌ 差：范围列在前（范围后的列不走索引）
CREATE INDEX idx_time_status ON orders(created_at, status);
```

### 2.2 冗余索引

```sql
-- ❌ 冗余索引
KEY idx_status (status)
KEY idx_status_time (status, created_at)  -- 包含 idx_status 的功能
-- idx_status 是冗余的，可以删掉

-- ❌ 多余索引
KEY idx_a (a)
KEY idx_ab (a, b)
KEY idx_abc (a, b, c)  -- 三个索引功能重叠
```

### 2.3 索引维护

```sql
-- 查看索引使用情况
SELECT
    index_name,
    stat_value as usage_count
FROM performance_schema.index_statistics
WHERE table_name = 'orders'
ORDER BY usage_count;

-- 删除从未使用的索引
DROP INDEX idx_unused ON orders;

-- 重建索引（减少碎片）
ALTER TABLE orders DROP INDEX idx_name, ADD INDEX idx_name(column);
-- MySQL 8.0 支持在线重建
ALTER TABLE orders DROP INDEX idx_name, ADD INDEX idx_name(column), ALGORITHM=INPLACE, LOCK=NONE;
```

---

## 3. 查询优化

### 3.1 分页优化

```sql
-- ❌ 传统分页（越往后越慢）
SELECT * FROM orders ORDER BY id LIMIT 20 OFFSET 100000;
-- 先查 100020 行再丢掉前 100000 行

-- ✅ 游标分页（推荐）
-- 基于上一页最后一条的 ID
SELECT * FROM orders
WHERE id > :last_id       -- 上一页最后一条的 id
ORDER BY id
LIMIT 20;

-- ✅ 子查询分页
SELECT * FROM orders
WHERE id > (
    SELECT id FROM orders ORDER BY id LIMIT 1 OFFSET 100000
)
ORDER BY id
LIMIT 20;
```

### 3.2 COUNT 优化

```sql
-- ❌ 大表 COUNT(*) 很慢
SELECT COUNT(*) FROM orders;  -- 需要扫描所有行

-- ✅ 用估算值（MySQL）
SHOW TABLE STATUS LIKE 'orders';   -- 看 Rows 字段（估算）

-- ✅ 用单独的计数表
-- 在单独的计数表中维护行数，增删时更新

-- ✅ 用缓存
-- Redis incr/decr 维护行数
```

### 3.3 分批处理

```sql
-- ❌ 一次更新 100 万行
UPDATE orders SET status = 'ARCHIVED' WHERE created_at < '2024-01-01';
-- 大事务、锁大量行、同步复制延迟

-- ✅ 分批处理
DO $$
DECLARE
    affected INTEGER;
BEGIN
    LOOP
        UPDATE orders SET status = 'ARCHIVED'
        WHERE id IN (
            SELECT id FROM orders
            WHERE created_at < '2024-01-01' AND status != 'ARCHIVED'
            LIMIT 1000
        );
        GET DIAGNOSTICS affected = ROW_COUNT;
        EXIT WHEN affected = 0;
        COMMIT;  -- 每批提交
    END LOOP;
END $$;
```

---

## 4. 慢查询排查

### 4.1 开启慢查询日志

```ini
# my.cnf
slow_query_log = ON
slow_query_log_file = /var/log/mysql/slow.log
long_query_time = 1                    # 超过 1 秒
log_queries_not_using_indexes = ON     # 没走索引的也记
```

### 4.2 分析慢查询

```bash
# 查看慢查询数量
SHOW GLOBAL STATUS LIKE 'Slow_queries';

# 用 pt-query-digest 分析（Percona Toolkit）
pt-query-digest /var/log/mysql/slow.log

# 或 mysqldumpslow
mysqldumpslow -t 10 /var/log/mysql/slow.log  # 前 10 条
```

### 4.3 优化流程

```
1. 发现慢查询（告警/日志）
    ↓
2. EXPLAIN 分析执行计划
    ↓
3. 检查是否走了索引（type、key、rows）
    ↓
4. 没走索引 → 加索引 / 改写 SQL
    ↓
5. 走了索引但慢 → 索引选择性不好 / 扫描行数多
    ↓
6. 考虑：联合索引 / 覆盖索引 / 改写 SQL
    ↓
7. 还是慢 → 业务层面优化（缓存、汇总表、分库分表）
```

---

## 5. 🔴 常见优化场景

| 场景 | 问题 | 方案 |
|------|------|------|
| 分页越翻越慢 | OFFSET 大 | 游标分页 |
| COUNT 慢 | 大表统计 | 计数表 / 缓存 |
| 大表 JOIN | 内存/时间消耗大 | 加索引 / 冗余字段 |
| OR 条件 | 不走索引 | UNION |
| ORDER BY 慢 | 文件排序 | 索引包含排序列 |
| GROUP BY 慢 | 临时表 | 索引包含分组列 |
| 批量 UPDATE | 大事务 | 分批处理 |
| 子查询慢 | 重复执行 | 改 JOIN 或 CTE |

---

## 6. SQL 优化速查

```sql
-- 检查索引使用
EXPLAIN SELECT ...\G
SHOW INDEX FROM orders;
SHOW TABLE STATUS LIKE 'orders';

-- 查看进程
SHOW FULL PROCESSLIST;
SHOW OPEN TABLES WHERE in_use > 0;

-- 查看锁
SELECT * FROM performance_schema.data_locks;
SHOW ENGINE INNODB STATUS\G

-- 分析表
ANALYZE TABLE orders;
OPTIMIZE TABLE orders;    -- 重建表（会锁表）

-- 查看表大小
SELECT
    table_name,
    ROUND(data_length / 1024 / 1024, 2) as data_mb,
    ROUND(index_length / 1024 / 1024, 2) as index_mb
FROM information_schema.tables
WHERE table_schema = 'db_name';
```
