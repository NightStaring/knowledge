# 数据建模

> 范式与反范式、ER 设计、字段类型选型、索引设计原则。

---

## 1. 范式化

### 1.1 三范式

| 范式 | 规则 | 违反的例子 |
|------|------|-----------|
| **1NF** | 每列不可再分 | `phone: "138xxxx, 139xxxx"`（一个字段存两个手机号） |
| **2NF** | 非主键列必须完全依赖主键 | 订单表里有`用户姓名`（只依赖用户ID，不依赖订单ID） |
| **3NF** | 非主键列不能传递依赖主键 | 订单表里有`用户等级`（用户ID → 用户等级，传递依赖） |

### 1.2 反范式化

```sql
-- 范式化设计
-- orders: id, user_id, amount
-- users:  id, name, level
-- 查订单时需要 JOIN users

-- 反范式化设计（冗余用户名字段）
-- orders: id, user_id, user_name, amount
-- 不用 JOIN，但更新用户名字时多个表都要改
```

**什么时候该反范式：**

```
✅ 适合反范式：
  - 高频查询需要 JOIN
  - 冗余字段很少变化
  - 读远多于写

❌ 不要反范式：
  - 冗余字段频繁更新
  - 数据一致性要求极高
  - 冗余字段很大（如大文本）
```

---

## 2. 字段选型

### 2.1 数值类型

```sql
-- 整数
TINYINT     1字节   (-128~127)               -- 状态码、年龄
SMALLINT    2字节   (-32768~32767)            -- 枚举值
INT         4字节   (-21亿~21亿)              -- 主键、普通计数
BIGINT      8字节   (-9.22e18~9.22e18)        -- 大表主键、订单号

-- 小数
DECIMAL(10,2)   -- 金额（精确！不要用 float/double）
FLOAT/DOUBLE    -- 科学计算（有精度误差）

-- 🟢 金额永远用 DECIMAL
```

### 2.2 字符串

```sql
CHAR(32)      -- 定长（手机号、身份证）
VARCHAR(255)  -- 变长（名字、邮箱）
TEXT          -- 大文本（文章内容，不能建索引全文）
JSON          -- JSON 数据（MySQL 5.7+）

-- 🟢 固定长度用 CHAR，可变用 VARCHAR
-- 🟢 VARCHAR 不是越大越好（临时表用最大长度分配内存）
-- ❌ 不要用 VARCHAR(10000)，够用就行
```

### 2.3 日期时间

```sql
DATETIME      -- 8字节，'1000-01-01' ~ '9999-12-31'，不受时区影响
TIMESTAMP     -- 4字节，'1970-01-01' ~ '2038-01-19'，受时区影响
DATE          -- 3字节，只存日期

-- 🟢 推荐用 DATETIME（范围大，无 2038 年问题）
-- 🟢 创建时间设 DEFAULT CURRENT_TIMESTAMP
-- 🟢 更新时间设 ON UPDATE CURRENT_TIMESTAMP
```

### 2.4 布尔值

```sql
-- MySQL 没有 BOOLEAN 类型，用 TINYINT(1) 替代
is_active TINYINT(1) DEFAULT 1

-- PostgreSQL 有原生 BOOLEAN
is_active BOOLEAN DEFAULT TRUE
```

---

## 3. 主键设计

### 3.1 自增主键 vs 业务主键

```sql
-- 自增主键（推荐）
id BIGINT AUTO_INCREMENT PRIMARY KEY

-- 业务主键（订单号做主键）
order_no VARCHAR(32) PRIMARY KEY

-- UUID 主键（不推荐，随机 IO + 索引大）
id VARCHAR(36) PRIMARY KEY
```

| 主键类型 | 优点 | 缺点 |
|----------|------|------|
| **自增 BIGINT** | 顺序写入、索引小 | 分库分表需改造 |
| **雪花算法** | 分布式唯一、趋势递增 | 依赖时钟 |
| **UUID** | 完全唯一 | 随机 IO、索引大 |
| **业务主键** | 天然有意义 | 长度大、变更困难 |

### 3.2 联合主键

```sql
-- 多对多关系
CREATE TABLE order_items (
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (order_id, product_id)
);
```

---

## 4. ER 设计

### 4.1 关系类型

```
一对一：user ↔ user_profile（用户和详情）
一对多：order → order_items（订单和订单项）
多对多：student ↔ course（学生和课程，通过中间表）
```

### 4.2 多对多设计

```sql
-- 学生表
CREATE TABLE students (
    id BIGINT PRIMARY KEY,
    name VARCHAR(50)
);

-- 课程表
CREATE TABLE courses (
    id BIGINT PRIMARY KEY,
    title VARCHAR(100)
);

-- 中间表
CREATE TABLE student_courses (
    student_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    enrolled_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (student_id, course_id),
    FOREIGN KEY (student_id) REFERENCES students(id),
    FOREIGN KEY (course_id) REFERENCES courses(id)
);
```

---

## 5. 索引设计原则

### 5.1 建索引的时机

```sql
-- ✅ 应该建索引
-- 1. WHERE 条件中的列
-- 2. JOIN 的关联列
-- 3. ORDER BY 的列
-- 4. GROUP BY 的列
-- 5. 唯一约束的列（UNIQUE 自动建索引）

-- ❌ 不要建索引
-- 1. 频繁更新的列
-- 2. 值很少的列（性别：男/女）
-- 3. 大文本/大二进制列
-- 4. 表数据很少（< 1000 行）
```

### 5.2 索引数量控制

```sql
-- 单表索引数量建议不超过 5~8 个
-- 索引不是越多越好：
--   1. 写入变慢（每次 INSERT/UPDATE 都要维护索引）
--   2. 查询优化器选择困难
--   3. 占用磁盘空间
```

---

## 6. 命名规范

```sql
-- 表名：业务_功能（小写+下划线）
orders
order_items
user_login_log

-- 字段：小写+下划线
user_id
created_at
is_active

-- 索引：idx_表名_字段
idx_orders_status
idx_orders_user_id
uk_users_email     -- 唯一索引

-- 主键：id
-- 外键：关联表名_id
```

---

## 7. 🔴 常见坑

```sql
-- 坑 1：字段类型用错
-- 存储 IP 地址用 VARCHAR，不要用 INT
-- 手机号用 VARCHAR，不用 BIGINT（前导 0 问题）

-- 坑 2：NULL 的误用
-- 能用 NOT NULL + 默认值，就不要用 NULL
-- NULL 占用额外空间，索引更大，查询更慢

-- 坑 3：大字段放主表
-- 文章内容、图片 URL 等大字段放单独的表
-- 主表只存 id 和常用字段

-- 坑 4：冗余字段不同步
-- 反范式化后，更新时忘了同步冗余字段
-- 🟢 用触发器或应用层保证一致性

-- 坑 5：字段设计不考虑未来
-- status TINYINT 存状态，写死 0=待支付 1=已支付
-- 后来加了退款状态 → 改代码+数据迁移
-- 🟢 一开始就考虑扩展性
```
