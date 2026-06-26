# SQL 基础

> 聚焦高频误区和实战易错点，不讲 SELECT 怎么写。

---

## 1. JOIN 详解

### 1.1 JOIN 类型

```sql
-- INNER JOIN：两边都有才返回
SELECT * FROM orders o
INNER JOIN users u ON o.user_id = u.id;

-- LEFT JOIN：左表全返回，右表没有为 NULL
SELECT * FROM orders o
LEFT JOIN users u ON o.user_id = u.id;

-- RIGHT JOIN：右表全返回（很少用，改成 LEFT JOIN 更直观）
SELECT * FROM orders o
RIGHT JOIN users u ON o.user_id = u.id;

-- FULL JOIN：两边全返回（MySQL 不支持，用 UNION 模拟）
```

### 1.2 🔴 JOIN 常见坑

```sql
-- 坑 1：JOIN 条件写 WHERE 里（隐式 INNER JOIN）
-- ❌ 不推荐
SELECT * FROM orders o, users u WHERE o.user_id = u.id;

-- ✅ 显式 JOIN
SELECT * FROM orders o INNER JOIN users u ON o.user_id = u.id;

-- 坑 2：LEFT JOIN 的条件放 WHERE 会变成 INNER JOIN
-- ❌ 想查所有订单和用户信息，但只筛选已支付的
SELECT * FROM orders o
LEFT JOIN users u ON o.user_id = u.id
WHERE o.status = 'PAID';    -- LEFT JOIN 变 INNER JOIN 了

-- ✅ 筛选条件放 ON 里
SELECT * FROM orders o
LEFT JOIN users u ON o.user_id = u.id AND o.status = 'PAID';

-- 坑 3：多表 JOIN 不指定别名
-- ❌ 字段来源不清
SELECT id, name FROM orders JOIN users ON user_id = id;
-- ✅ 加别名
SELECT o.id, u.name FROM orders o JOIN users u ON o.user_id = u.id;
```

---

## 2. GROUP BY 与聚合

### 2.1 GROUP BY 规则

```sql
-- SELECT 中的非聚合列必须出现在 GROUP BY 中
-- ❌ MySQL 5.7 之前允许，5.7+ 默认禁止（ONLY_FULL_GROUP_BY）
SELECT user_id, status, COUNT(*)  -- status 不在 GROUP BY 中！
FROM orders
GROUP BY user_id;

-- ✅
SELECT user_id, COUNT(*) as order_count
FROM orders
GROUP BY user_id;
```

### 2.2 HAVING vs WHERE

```sql
-- WHERE：聚合前过滤（行级）
-- HAVING：聚合后过滤（组级）

-- 查订单数大于 5 的用户
SELECT user_id, COUNT(*) as cnt
FROM orders
WHERE status = 'PAID'           -- 先过滤已支付的
GROUP BY user_id
HAVING cnt > 5;                 -- 再过滤订单数 > 5 的
```

### 2.3 🔴 GROUP BY 坑

```sql
-- 坑 1：SELECT 了不在 GROUP BY 中的列
-- MySQL 5.7+ 默认报错

-- 坑 2：HAVING 可以用别名，WHERE 不行
SELECT user_id, COUNT(*) as cnt
FROM orders
WHERE cnt > 5      -- ❌ WHERE 不能识别别名
GROUP BY user_id;

-- ✅
SELECT user_id, COUNT(*) as cnt
FROM orders
GROUP BY user_id
HAVING cnt > 5;    -- ✅ HAVING 可以

-- 坑 3：GROUP BY 消耗大
-- 大表 GROUP BY 很慢，考虑加索引或走汇总表
```

---

## 3. NULL 三值逻辑

### 3.1 NULL 的比较

```sql
-- NULL 不是值，是"未知"
-- NULL = NULL 的结果是 NULL（不是 TRUE！）

SELECT * FROM users WHERE deleted_at = NULL;    -- ❌ 永远查不到
SELECT * FROM users WHERE deleted_at IS NULL;   -- ✅

SELECT * FROM users WHERE deleted_at <> NULL;   -- ❌
SELECT * FROM users WHERE deleted_at IS NOT NULL; -- ✅
```

### 3.2 NULL 与聚合函数

```sql
-- COUNT(*) 包含 NULL 行
-- COUNT(列名) 不包含 NULL 行
SELECT
    COUNT(*),           -- 所有行
    COUNT(deleted_at),  -- 不包含 NULL 的行
    AVG(amount)         -- NULL 不参与计算
FROM orders;
```

### 3.3 NULL 处理函数

```sql
-- MySQL
SELECT COALESCE(phone, email, '无联系方式') FROM users;
SELECT IFNULL(phone, '无') FROM users;
SELECT NULLIF(a, b);  -- a = b 返回 NULL，否则返回 a

-- PostgreSQL
SELECT COALESCE(phone, email, '无联系方式') FROM users;  -- 同 MySQL
```

---

## 4. 窗口函数

### 4.1 常用窗口函数

```sql
-- ROW_NUMBER：行号（每组内唯一）
SELECT
    user_id,
    amount,
    ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY amount DESC) as rn
FROM orders;
-- 取每组第一条 → WHERE rn = 1

-- RANK / DENSE_RANK：排名（并列处理不同）
RANK()        -- 1, 1, 3（跳过 2）
DENSE_RANK()  -- 1, 1, 2（不跳过）

-- SUM/AVG OVER：移动计算
SELECT
    created_at,
    amount,
    SUM(amount) OVER (ORDER BY created_at) as running_total  -- 累计
FROM orders;
```

### 4.2 窗口函数 vs GROUP BY

```sql
-- GROUP BY：折叠行
SELECT user_id, SUM(amount) FROM orders GROUP BY user_id;  -- 每个用户一行

-- 窗口函数：不折叠行
SELECT
    user_id,
    amount,
    SUM(amount) OVER (PARTITION BY user_id) as user_total  -- 每行都有用户总额
FROM orders;
```

---

## 5. 子查询与 CTE

### 5.1 CTE（WITH 子句）

```sql
-- ❌ 嵌套子查询可读性差
SELECT * FROM (
    SELECT user_id, COUNT(*) as cnt
    FROM orders
    GROUP BY user_id
) t WHERE cnt > 5;

-- ✅ CTE 更清晰
WITH order_stats AS (
    SELECT user_id, COUNT(*) as cnt
    FROM orders
    GROUP BY user_id
)
SELECT * FROM order_stats WHERE cnt > 5;
```

### 5.2 EXISTS vs IN

```sql
-- IN：查出所有值再比较
-- EXISTS：找到第一个就停，通常更快
SELECT * FROM users u
WHERE u.id IN (SELECT user_id FROM orders);     -- IN

SELECT * FROM users u
WHERE EXISTS (SELECT 1 FROM orders WHERE user_id = u.id);  -- EXISTS（推荐）
```

---

## 6. 🔴 常见坑速查

| 坑 | 说明 | 正确做法 |
|----|------|----------|
| `= NULL` | 永远不成立 | `IS NULL` |
| `NULL <> 1` | 结果不是 TRUE | 用 `IS NOT NULL` |
| WHERE 中用别名 | 语法错误 | 用 HAVING 或子查询 |
| GROUP BY 少列 | MySQL 5.7+ 报错 | 非聚合列都在 GROUP BY 中 |
| LEFT JOIN 条件放 WHERE | 变 INNER JOIN | 条件放 ON |
| 子查询不命名 | 语法错误 | 加别名 |
| `SELECT DISTINCT *` | 可能不需要去重 | 明确查需要的列 |
| 字符串和数字比较 | 隐式转换，不走索引 | 类型保持一致 |

---

## 7. SQL 速查

```sql
-- 条件
CASE WHEN status = 'PAID' THEN '已支付'
     WHEN status = 'PENDING' THEN '待支付'
     ELSE '未知' END

-- 字符串
CONCAT(first, ' ', last)   -- MySQL
first || ' ' || last        -- PostgreSQL
SUBSTRING(name, 1, 10)
LENGTH(name)
LOWER(name) / UPPER(name)
TRIM(name)

-- 日期
NOW() / CURRENT_TIMESTAMP
DATE(created_at)               -- 取日期部分
DATE_FORMAT(created_at, '%Y-%m-%d')  -- MySQL 格式化
TO_CHAR(created_at, 'YYYY-MM-DD')    -- PostgreSQL 格式化
DATEDIFF(end, start)           -- 日期差

-- 类型转换
CAST(amount AS DECIMAL(10,2))
amount::DECIMAL(10,2)          -- PostgreSQL

-- 去重保留一条
DELETE FROM orders
WHERE id NOT IN (
    SELECT MIN(id) FROM orders GROUP BY order_no
);
```
