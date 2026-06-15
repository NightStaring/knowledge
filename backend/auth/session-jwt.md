# Session 与 JWT

> 两种主流的登录保持方案：Session-Cookie 与 JWT（JSON Web Token）。

---

## 1. 方案总览

| 维度 | Session-Cookie | JWT |
|------|---------------|-----|
| **存储位置** | 服务端内存/Redis | 客户端（Token 自包含） |
| **扩展性** | 差（需要共享 Session） | 好（无状态） |
| **跨域** | 受限（Cookie） | 好（Header 携带） |
| **实时吊销** | 立即生效 | 困难（需黑名单） |
| **安全性** | 依赖 Cookie 安全 | 依赖签名算法 |
| **性能** | 每次查询存储 | 无需查询，但 Token 大 |
| **适合场景** | 传统 Web、服务端渲染 | 前后端分离、移动端、微服务 |

---

## 2. Session-Cookie 方案

### 2.1 工作原理

```
客户端                         服务端
  |                              |
  |── POST /login (账号密码) ──→ |
  |                              |── 验证身份
  |                              |── 创建 Session（存 Redis）
  |←── Set-Cookie: session=id ── |
  |                              |
  |── GET /profile (Cookie 自动带)─→|
  |                              |── 从 Cookie 取 sessionId
  |                              |── 从 Redis 查 Session 数据
  |←── 返回用户资料 ──────────── |
```

### 2.2 关键配置

```java
// Spring Boot Session 配置
server.servlet.session:
  timeout: 30m           # Session 超时时间
  cookie:
    http-only: true      # 禁止 JS 读取 Cookie（防 XSS）
    secure: true         # 仅 HTTPS 传输
    same-site: strict    # 防 CSRF
```

```python
# Flask Session 配置
app.config.update(
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SECURE=True,
    SESSION_COOKIE_SAMESITE='Strict',
    PERMANENT_SESSION_LIFETIME=timedelta(hours=2)
)
```

### 2.3 分布式 Session 共享

单机部署没问题，多机部署时 Session 需要共享：

```
❌ 方案1: Session 黏性（Sticky Session）
   负载均衡将同一用户的请求固定发到同一台机器
   问题：机器宕机 → Session 丢失

✅ 方案2: Redis 集中存储
   Session 统一存 Redis，所有机器从 Redis 读取
   推荐方案
```

```yaml
# Spring Session + Redis
spring:
  session:
    store-type: redis
  redis:
    host: redis-server
```

### 2.4 Session 方案优缺点

**优点：**
- 服务端完全掌控，可随时吊销
- Cookie 浏览器自动管理，开发简单
- Session 数据灵活（可存任何对象）

**缺点：**
- 需要共享存储（Redis），增加架构复杂度
- 不适合跨域场景（Cookie 受同源策略限制）
- 移动端原生 App 对 Cookie 支持不友好

---

## 3. JWT 方案

### 3.1 JWT 结构

```
Header     ──→ {"alg":"HS256","typ":"JWT"}
Payload    ──→ {"userId":1,"role":"admin","exp":1700000000}
Signature  ──→ HMACSHA256(base64(Header) + "." + base64(Payload), secret)
```

三段式：`xxxxx.yyyyy.zzzzz`

### 3.2 JWT 签发与验证

```python
# Python 示例
import jwt
import datetime

# 签发
payload = {
    "user_id": 1001,
    "role": "admin",
    "exp": datetime.datetime.utcnow() + datetime.timedelta(hours=2),
    "iat": datetime.datetime.utcnow()       # 签发时间
}
token = jwt.encode(payload, SECRET_KEY, algorithm="HS256")

# 验证
try:
    data = jwt.decode(token, SECRET_KEY, algorithms=["HS256"])
except jwt.ExpiredSignatureError:
    # Token 过期
except jwt.InvalidTokenError:
    # Token 无效
```

```java
// Java Spring Boot 示例
// 依赖: io.jsonwebtoken:jjwt

// 签发
String token = Jwts.builder()
    .setSubject(userId.toString())
    .claim("role", role)
    .setIssuedAt(new Date())
    .setExpiration(new Date(System.currentTimeMillis() + 7200000))
    .signWith(SignatureAlgorithm.HS256, secretKey)
    .compact();

// 验证
Claims claims = Jwts.parser()
    .setSigningKey(secretKey)
    .parseClaimsJws(token)
    .getBody();
```

### 3.3 Access Token + Refresh Token

这是生产环境的标准做法：

```
Access Token（短效）:
  - 有效期：15~30 分钟
  - 用途：访问受保护的 API
  - 每次请求携带

Refresh Token（长效）:
  - 有效期：7~30 天
  - 用途：换取新的 Access Token
  - 存储更安全（HttpOnly Cookie 或安全存储）
```

**完整流程：**

```
登录
  ↓
返回 { accessToken, refreshToken }
  ↓
请求 API 时带 accessToken
  ↓
accessToken 过期 → 返回 401
  ↓
客户端用 refreshToken 请求新 accessToken
  ↓
服务端验证 refreshToken → 返回新的 accessToken
  ↓
继续请求
```

```python
# Refresh Token 接口示例
@app.post('/refresh')
def refresh_token(refresh_token: str):
    # 验证 refresh token
    payload = verify_refresh_token(refresh_token)

    # 生成新的 access token
    new_access = create_access_token({"user_id": payload["user_id"]})

    # 可选：轮换 refresh token（Refresh Token Rotation）
    new_refresh = create_refresh_token({"user_id": payload["user_id"]})

    return {"access_token": new_access, "refresh_token": new_refresh}
```

### 3.4 JWT 吊销方案

JWT 一旦签发，在过期前无法主动失效。解决方案：

| 方案 | 实现 | 适用 |
|------|------|------|
| **黑名单** | Redis 缓存被吊销的 JWT | 单点吊销 |
| **短有效期** | Access Token 15 分钟，减少吊销窗口 | 通用 |
| **Token 版本号** | 用户表中的 `token_version` 加入 JWT | 强制下线 |
| **Refresh Token 轮换** | 每次刷新生成新的 Refresh Token | 安全敏感场景 |

```python
# Token 版本号方案
# 用户表增加 token_version 字段
payload = {
    "user_id": 1001,
    "token_version": user.token_version  # 用户当前版本
}
# 修改密码/退出登录时递增版本号
user.token_version += 1
# 旧 JWT 的版本号不匹配 → 无效
```

### 3.5 JWT 安全注意事项

```
✅ 用 RS256（非对称）代替 HS256（对称）
   HS256: 签发和验证用同一个密钥
   RS256: 私钥签发，公钥验证（微服务场景优势明显）

✅ 设置合理的 exp（过期时间）
   Access Token: 15 分钟
   Refresh Token: 7 天

✅ 将 jti（JWT ID）设为随机 UUID，加入黑名单支持

❌ 不要在 Payload 放密码等敏感信息（仅 Base64 编码！）

❌ 不要用太长的有效期（超过 24 小时的 Access Token 不合适）
```

---

## 4. 方案对比与选型

### 4.1 选择 Session 的场景

```
传统 Web 应用（服务端渲染）
  Spring MVC、Thymeleaf、JSP 等
  → Session-Cookie 天然适合

企业内部系统
  同域部署，无需跨域
  → Session 足够

需要实时吊销
  管理员封号后立即生效
  → Session 胜出
```

### 4.2 选择 JWT 的场景

```
前后端分离（SPA）
  React / Vue 前端 + 后端 API
  → JWT 的跨域优势明显

移动端 App
  iOS / Android 原生 App
  → JWT 比 Cookie 更适合

微服务架构
  多个服务独立部署
  → JWT 无状态，服务间直接验证

第三方 API
  为开发者提供 API 接口
  → JWT 作为 API Token
```

### 4.3 混合方案

```python
# 一个实际的折中方案：
# Session 存储 + JWT 传输

登录时：
  1. 验证身份
  2. 生成 Session（存 Redis）
  3. 把 sessionId 编码进 JWT 返回

请求时：
  1. 客户端带 JWT
  2. 服务端验证 JWT → 取出 sessionId
  3. 从 Redis 查 Session 数据

优点：
  - 结合两者优势
  - 可随时吊销（删除 Redis 即可）
  - 跨域友好（JWT 携带）
```

---

## 5. 常见问题

### 5.1 "JWT 更安全吗？"

**不一定。** JWT 本身没有让系统更安全或更不安全。安全问题取决于：

- 密钥强度和保护
- Token 存储方式（localStorage vs HttpOnly Cookie）
- 传输协议（HTTPS vs HTTP）
- 是否实现了 Refresh Token 轮换

### 5.2 "为什么我的 JWT 不能立即失效？"

这是 JWT 的设计特性——它是**自包含**的。服务端没有保存它，所以无法主动使其失效。

**解决方案：** 使用黑名单（Redis）或短有效期。

### 5.3 "Token 放哪里？"

| 位置 | 优点 | 缺点 |
|------|------|------|
| localStorage | 跨标签页共享，API 友好 | XSS 可窃取 |
| sessionStorage | 只当前标签页 | 刷新页面就丢 |
| HttpOnly Cookie | 防 XSS 窃取 | 需额外防 CSRF |
| 内存（变量） | 最安全 | 刷新页面就丢 |

**推荐：** Access Token 放内存（或 sessionStorage），Refresh Token 放 HttpOnly Cookie。
