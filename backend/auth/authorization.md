# 鉴权模型

> 认证之后做什么——权限控制的核心模型与实现。

---

## 1. 核心概念

```
认证（Authentication）:
  "你是谁？" → 用户登录

鉴权（Authorization）:
  "你能做什么？" → 权限判断

鉴权三要素：
  主体（Subject）   → 谁？   用户 / 角色
  资源（Resource）  → 什么？  页面 / API / 数据
  操作（Action）    → 怎么做？ 增 / 删 / 改 / 查
```

---

## 2. 常见鉴权模型

### 2.1 ACL（访问控制列表）

```
文件: report.pdf
  用户A: 读权限
  用户B: 读写权限
  用户C: 无权限
```

**特点：**
- 每个资源维护一个"谁可以做什么"的列表
- 粒度最细
- **问题**：用户和资源多了以后难以维护

**适用：** 文件系统、小型系统

### 2.2 RBAC（基于角色的访问控制）— 最常用

```
用户 ──→ 角色 ──→ 权限
                  ├── 创建订单
                  ├── 查看订单
                  └── 删除订单
```

**核心思想：** 用户不直接关联权限，而是通过角色间接关联。

```sql
-- 四张核心表
CREATE TABLE users (
    id BIGINT PRIMARY KEY,
    username VARCHAR(50)
);

CREATE TABLE roles (
    id BIGINT PRIMARY KEY,
    name VARCHAR(50)      -- admin, editor, viewer
);

CREATE TABLE permissions (
    id BIGINT PRIMARY KEY,
    code VARCHAR(100),     -- order:create, order:view
    name VARCHAR(100)
);

-- 关联表
CREATE TABLE user_roles (
    user_id BIGINT,
    role_id BIGINT
);

CREATE TABLE role_permissions (
    role_id BIGINT,
    permission_id BIGINT
);
```

**RBAC 实现示例：**

```python
# 权限检查装饰器
def require_permission(permission_code):
    def decorator(f):
        @wraps(f)
        def wrapper(*args, **kwargs):
            user = get_current_user()
            if not user.has_permission(permission_code):
                abort(403, "权限不足")
            return f(*args, **kwargs)
        return wrapper
    return decorator

@app.get('/api/orders')
@require_permission('order:view')
def list_orders():
    return OrderService.get_all()

@app.post('/api/orders')
@require_permission('order:create')
def create_order():
    return OrderService.create()
```

```java
// Spring Security RBAC
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping
    @PreAuthorize("hasAuthority('order:view')")
    public List<Order> list() { ... }

    @PostMapping
    @PreAuthorize("hasAuthority('order:create')")
    public Order create(@RequestBody Order order) { ... }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('order:delete')")
    public void delete(@PathVariable Long id) { ... }
}
```

**RBAC 角色设计示例：**

| 角色 | 权限 |
|------|------|
| **admin** | 全部权限 |
| **manager** | 订单 CRUD、用户查看、报表查看 |
| **editor** | 订单创建、查看、编辑 |
| **viewer** | 只读权限 |
| **custom_role** | 自定义组合 |

### 2.3 ABAC（基于属性的访问控制）

```python
# ABAC: 根据用户、资源、环境属性动态判断
def can_access(user, resource, action, context):
    rules = [
        # 用户自己的资料可以编辑
        (user.id == resource.owner_id, "edit"),

        # 管理员可以编辑所有
        (user.role == "admin", "edit"),

        # 工作时间外只读
        (not is_business_hours(context.now), "read_only"),

        # 同一个部门的可以查看
        (user.department == resource.department, "view"),
    ]
    return any(allowed for condition, allowed in rules if condition)
```

**ABAC vs RBAC：**

| 维度 | RBAC | ABAC |
|------|------|------|
| 模型复杂度 | 低 | 高 |
| 灵活性 | 中 | 高 |
| 维护成本 | 低 | 高 |
| 性能 | 高 | 较低 |
| 适用 | 大部分业务系统 | 复杂权限场景 |

### 2.4 数据级权限

除了接口级的"能不能访问这个 API"，还有数据级的"能看到哪些数据"：

```python
# 行级权限：只能看自己的数据
@app.get('/api/orders')
def list_orders():
    user = get_current_user()
    if user.role == 'admin':
        # 管理员看所有
        orders = Order.query.all()
    elif user.role == 'manager':
        # 经理看自己部门的
        orders = Order.query.filter_by(department=user.department)
    else:
        # 普通用户只看自己的
        orders = Order.query.filter_by(user_id=user.id)
    return orders
```

```java
// Spring Security 数据权限
// 方案1: 注解 + SpEL
@PostFilter("filterObject.ownerId == authentication.principal.id")
public List<Order> getOrders() { ... }

// 方案2: MyBatis 拦截器注入部门过滤条件
// 所有查询自动追加 "AND department_id = :currentDept"
```

---

## 3. 权限设计模式

### 3.1 白名单模式

```python
# 默认全部拒绝，只有明确允许的才能访问
WHITELIST = {
    "/api/public/login":       ANYONE,
    "/api/public/register":    ANYONE,
    "/api/orders":            ["order:view"],
    "/api/orders/create":     ["order:create"],
}
```

### 3.2 角色继承

```python
ROLE_HIERARCHY = {
    "viewer":    [],
    "editor":    ["viewer"],     # editor 继承 viewer 的所有权限
    "manager":   ["editor"],     # manager 继承 editor + viewer
    "admin":     ["manager"],    # admin 继承所有下级
}

def has_permission(user, permission):
    # 获取用户角色及所有继承的角色
    roles = get_role_chain(user.role)
    # 检查任一角色是否有此权限
    return any(role_has_permission(role, permission) for role in roles)
```

### 3.3 权限缓存

```python
# 用户权限不常变，缓存到 Redis
def get_user_permissions(user_id):
    cache_key = f"user_permissions:{user_id}"
    permissions = redis.get(cache_key)
    if not permissions:
        permissions = db.query_user_permissions(user_id)
        redis.setex(cache_key, 3600, permissions)  # 缓存 1 小时
    return permissions
```

---

## 4. 前后端鉴权

### 4.1 前端权限控制

```javascript
// 前端路由级权限（Vue Router 示例）
const routes = [
  { path: '/dashboard',  component: Dashboard, meta: { requiresAuth: true } },
  { path: '/admin',      component: Admin,     meta: { role: 'admin' } },
  { path: '/orders',     component: Orders,    meta: { permission: 'order:view' } },
];

router.beforeEach((to, from, next) => {
  const user = store.state.user;
  if (to.meta.requiresAuth && !user.isLoggedIn) {
    next('/login');
  } else if (to.meta.role && user.role !== to.meta.role) {
    next('/403');
  } else {
    next();
  }
});
```

```javascript
// 前端按钮级权限
<template>
  <button v-if="hasPermission('order:delete')" @click="deleteOrder">
    删除订单
  </button>
</template>

<script>
export default {
  methods: {
    hasPermission(code) {
      return this.$store.state.user.permissions.includes(code);
    }
  }
}
</script>
```

### 4.2 前后端权限同步

```
登录时后端返回用户权限列表
    ↓
前端存储权限到 Store / localStorage
    ↓
前端根据权限控制菜单、按钮显示
    ↓
请求后端 API 时，后端再次验证权限
    ↓
（前端控制只是体验优化，后端才是真正的安全屏障）
```

**⚠️ 重要原则：** 前端权限控制只影响"用户体验"，真正的权限检查**必须在后端**执行。

---

## 5. 权限设计 CheckList

- [ ] 选择鉴权模型（RBAC / ABAC / 混合）
- [ ] 定义角色体系（admin / manager / editor / viewer）
- [ ] 定义权限粒度（接口级 / 数据级 / 字段级）
- [ ] 实现权限注解/装饰器
- [ ] 前后端权限同步机制
- [ ] 权限缓存策略
- [ ] 默认拒绝（白名单模式）
- [ ] 权限变更实时生效
- [ ] 审计日志（谁在什么时候做了什么）
