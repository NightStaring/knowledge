# PostgreSQL

> PostgreSQL 核心特性、与 MySQL 的对比、迁移注意事项。

---

## 1. PostgreSQL 核心特性

### 1.1 概述

```
PostgreSQL 是最先进的开源关系型数据库
- 完全支持 ACID
- 扩展性极强（自定义类型、函数、索引）
- 丰富的扩展生态（PostGIS、TimescaleDB 等）
- 开源协议友好（类似 MIT）
```

### 1.2 独有特性

| 特性 | 说明 | 示例 |
|------|------|------|
| **JSONB** | 二进制 JSON，支持索引 | `data->>'name'` |
| **数组** | 原生数组类型 | `TEXT[]`, `INTEGER[]` |
| **CTE 递归** | WITH RECURSIVE | 树结构查询 |
| **窗口函数** | 支持全面 | ROW_NUMBER, LAG, LEAD |
| **部分索引** | 只索引部分行 | `WHERE status = 'ACTIVE'` |
| **覆盖索引** | INCLUDE 额外列 | `CREATE INDEX ... INCLUDE (name)` |
| **表继承** | 表可继承 | 分区表 |
| **FDW** | 外部数据包装器 | 跨库查询 |
| **扩展** | 插件生态 | PostGIS（地理空间） |

### 1.3 JSONB 示例

```sql
-- JSONB 列
CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,
    data JSONB
);

-- 插入
INSERT INTO events (data) VALUES
    ('{"type": "click", "user_id": 1, "page": "/home"}'),
    ('{"type": "view", "user_id": 2, "page": "/about"}');

-- JSON 查询（有索引！）
CREATE INDEX idx_events_type ON events USING gin (data jsonb_path_ops);
SELECT * FROM events WHERE data @> '{"type": "click"}';
SELECT data->>'page' as page, COUNT(*) FROM events GROUP BY data->>'page';
```

---

## 2. MySQL vs PostgreSQL 对比

### 2.1 核心差异

| 维度 | MySQL | PostgreSQL |
|------|-------|-----------|
| **ACID 实现** | InnoDB | 原生支持 |
| **SQL 标准** | 部分支持 | 更严格遵循 |
| **索引类型** | B+Tree | B+Tree、Hash、GiST、GIN、BRIN |
| **JSON 支持** | JSON（文本存储） | JSONB（二进制，有索引） |
| **全文搜索** | 基础支持 | 更好（tsvector） |
| **并发控制** | MVCC（UNDO 日志） | MVCC（更高效） |
| **复制** | 异步/半同步 | 流复制（同步/异步） |
| **VACUUM** | 不需要 | 需要（自动或手动） |
| **扩展性** | 有限 | 强（扩展、FDW） |
| **运维** | 简单 | 较复杂 |

### 2.2 语法差异

```sql
-- 自增主键
-- MySQL
id BIGINT AUTO_INCREMENT PRIMARY KEY

-- PostgreSQL（序列）
id BIGSERIAL PRIMARY KEY
-- 或标准方式
id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY

-- 分页
-- 都一样
LIMIT 10 OFFSET 20;

-- 字符串拼接
-- MySQL
CONCAT(first, ' ', last)
-- PostgreSQL
first || ' ' || last

-- 当前时间
-- MySQL
NOW()
-- PostgreSQL
NOW()           -- 支持
CURRENT_TIMESTAMP

-- 类型转换
-- MySQL
CAST(amount AS DECIMAL(10,2))
-- PostgreSQL
amount::DECIMAL(10,2)    -- :: 语法更简洁

-- UPSERT
-- MySQL
INSERT INTO users (id, name) VALUES (1, 'Alice')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- PostgreSQL
INSERT INTO users (id, name) VALUES (1, 'Alice')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;
```

### 2.3 🔴 迁移注意

```
从 MySQL 迁移到 PostgreSQL 的常见问题：

1. 自增主键
   MySQL 的 AUTO_INCREMENT → PostgreSQL 的 SERIAL 或 IDENTITY

2. 字符串比较
   MySQL: 'a' = 'a ' → TRUE（自动补空格）
   PG:    'a' = 'a ' → FALSE（严格比较）

3. GROUP BY
   MySQL 5.7 之前宽松
   PG 一直严格（所有非聚合列必须在 GROUP BY 中）

4. 默认排序
   MySQL: GROUP BY 隐式排序
   PG:    GROUP BY 不排序，需要显式 ORDER BY

5. 大小写
   MySQL: 表名大小写敏感（取决于系统）
   PG:    表名自动转小写（除非加引号）

6. 布尔值
   MySQL: TRUE/FALSE 也是 1/0
   PG:    TRUE/FALSE 不能用 1/0 代替
```

---

## 3. PostgreSQL 特有功能

### 3.1 递归 CTE

```sql
-- 树结构查询（比如组织架构、分类树）
WITH RECURSIVE org_tree AS (
    -- 根节点
    SELECT id, name, parent_id, 1 as level
    FROM organizations
    WHERE parent_id IS NULL

    UNION ALL

    -- 递归子节点
    SELECT o.id, o.name, o.parent_id, t.level + 1
    FROM organizations o
    INNER JOIN org_tree t ON o.parent_id = t.id
)
SELECT * FROM org_tree ORDER BY level, id;
```

### 3.2 部分索引

```sql
-- 只索引活跃用户（节省空间）
CREATE INDEX idx_active_users ON users(email)
WHERE status = 'ACTIVE';

-- 查询时自动使用
SELECT * FROM users WHERE status = 'ACTIVE' AND email = 'test@example.com';
```

### 3.3 覆盖索引（INCLUDE）

```sql
-- 索引中包含额外列，避免回表
CREATE INDEX idx_users_name ON users(name) INCLUDE (email, age);

-- 查询只用到索引
SELECT name, email, age FROM users WHERE name = 'Alice';
```

### 3.4 全文搜索

```sql
-- 创建全文搜索向量
ALTER TABLE articles ADD COLUMN tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', title || ' ' || content)) STORED;

CREATE INDEX idx_articles_tsv ON articles USING gin(tsv);

-- 搜索
SELECT title FROM articles
WHERE tsv @@ to_tsquery('simple', 'postgresql & tutorial');
```

---

## 4. 性能调优

### 4.1 核心参数

```ini
# postgresql.conf
# 内存
shared_buffers = 1G               # 物理内存的 25%
effective_cache_size = 3G         # OS 缓存 + shared_buffers
work_mem = 64MB                   # 每个查询的排序内存
maintenance_work_mem = 256MB      # 维护操作（VACUUM、索引）

# 日志
log_min_duration_statement = 1000  # 慢查询（毫秒）
log_checkpoints = on
log_connections = on
log_disconnections = on

# 检查点
checkpoint_completion_target = 0.9
max_wal_size = 2GB
```

### 4.2 VACUUM

```sql
-- PostgreSQL 需要 VACUUM（清理过期数据）
-- 自动 VACUUM 默认开启，但大表可能需要手动

-- 查看是否需要 VACUUM
SELECT
    schemaname, tablename,
    n_dead_tup,           -- 死元组数
    n_live_tup,           -- 活元组数
    round(n_dead_tup * 100.0 / (n_live_tup + 1), 2) as dead_ratio
FROM pg_stat_user_tables
ORDER BY dead_ratio DESC;

-- 手动 VACUUM
VACUUM ANALYZE orders;              -- 清理 + 更新统计信息
VACUUM FULL orders;                 -- 紧缩表空间（会锁表）
```

---

## 5. 🔴 常见坑

```sql
-- 坑 1：VACUUM 不及时
-- 死元组堆积 → 性能下降、磁盘膨胀
-- 🟢 配置 autovacuum，监控 dead_ratio

-- 坑 2：事务 ID 回卷
-- 2^31 个事务后可能回卷，需要冻结
-- 🟢 定期 VACUUM FREEZE

-- 坑 3：序列跳跃
-- 回滚后自增序列不回退
-- 这是设计如此（避免并发问题）

-- 坑 4：大小写问题
CREATE TABLE "MyTable" (id INT);  -- 引号内保持大小写
SELECT * FROM MyTable;            -- ❌ 找不到（自动转小写）
SELECT * FROM "MyTable";          -- ✅

-- 坑 5：COUNT(*) 慢
-- PostgreSQL 没有 MySQL 的"行数缓存"
-- COUNT(*) 需要全表扫描
-- 🟢 用估算值
SELECT reltuples::bigint FROM pg_class WHERE relname = 'orders';
```

---

## 6. 命令速查

```sql
-- 数据库管理
\l                              -- 查看数据库
\c dbname                       -- 切换数据库
\d tablename                    -- 查看表结构
\d+ tablename                   -- 查看表详情
\di                             -- 查看索引
\du                             -- 查看用户
\dt                             -- 查看所有表

-- 分析
EXPLAIN ANALYZE SELECT * FROM orders;   -- 执行计划 + 实际执行

-- 查看正在运行的查询
SELECT * FROM pg_stat_activity;

-- 终止查询
SELECT pg_cancel_backend(pid);          -- 取消查询
SELECT pg_terminate_backend(pid);       -- 终止连接

-- 配置
SHOW all;                               -- 查看所有参数
SHOW shared_buffers;
```
