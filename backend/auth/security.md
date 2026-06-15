# 安全实践

> 认证与鉴权系统中的安全防护措施。

---

## 1. 密码安全

### 1.1 密码哈希

```python
# ✅ 正确做法：bcrypt
import bcrypt
salt = bcrypt.gensalt(rounds=12)  # rounds=12 约 250ms
hash = bcrypt.hashpw(password.encode(), salt)

# ❌ 错误做法：MD5 直接哈希
hash = md5(password)  # 彩虹表秒破

# ❌ 错误做法：SHA-256 直接哈希
hash = sha256(password)  # GPU 暴力破解很快

# ❌ 错误做法：自定义加密算法
hash = my_custom_hash(password)  # 未经密码学家审查，不安全
```

### 1.2 密码策略

| 策略 | 建议 | 说明 |
|------|------|------|
| 最小长度 | 8 位（建议 12+） | 越长越安全 |
| 复杂度 | 大写 + 小写 + 数字 | 提高破解难度 |
| 常见密码 | 黑名单 | 禁止 password、123456 |
| 历史密码 | 记录最近 5 次 | 防重复使用 |
| 修改频率 | 90 天或按需 | 不强制定期改（NIST 已不推荐） |

### 1.3 NIST 最新建议

美国 NIST SP 800-63B 密码指南：

```
✅ 支持长密码（最少 64 位）
✅ 允许空格和特殊字符
✅ 检查常见密码黑名单
✅ 定期检查已知泄露密码
❌ 不强制定期更换密码
❌ 不强制特殊字符组合（长度比复杂度更重要）
❌ 不使用密码提示问题
```

---

## 2. CSRF 防护

### 2.1 什么是 CSRF

**跨站请求伪造（Cross-Site Request Forgery）**：

```
攻击流程：
  1. 用户登录了 bank.com
  2. 用户访问了恶意网站 attacker.com
  3. 恶意网站自动提交表单到 bank.com/transfer?to=attacker&amount=10000
  4. 由于用户已登录，浏览器自动带上 Cookie
  5. bank.com 以为是用户本人在操作 → 转账成功

问题：Cookie 是浏览器自动带的，恶意网站利用了这个特性
```

### 2.2 CSRF 防护方案

**方案一：CSRF Token（最常用）**

```html
<!-- 后端在页面中嵌入 Token -->
<form action="/transfer" method="POST">
    <input type="hidden" name="_csrf_token" value="随机Token">
    <input type="text" name="amount">
    <button type="submit">转账</button>
</form>
```

```python
# 后端验证
@app.post('/transfer')
def transfer():
    token = request.form.get('_csrf_token')
    if token != session['csrf_token']:
        abort(403, "CSRF 验证失败")
    # 继续处理
```

**方案二：SameSite Cookie（现代浏览器推荐）**

```python
# 设置 Cookie 的 SameSite 属性
response.set_cookie(
    "session_id", session_id,
    samesite="Strict",  # 或 "Lax"
    secure=True,
    httponly=True
)
```

| SameSite 值 | 行为 |
|-------------|------|
| `Strict` | 任何跨站请求都不带 Cookie |
| `Lax` | 导航到目标网站的 GET 请求带 Cookie |
| `None` | 所有跨站请求都带 Cookie（需 Secure） |

**方案三：Origin / Referer 验证**

```python
@app.before_request
def check_origin():
    if request.method == "POST":
        origin = request.headers.get("Origin")
        if origin and not origin.startswith("https://your-site.com"):
            abort(403)
```

**方案四：自定义请求头**

```javascript
// 前端在所有请求中加自定义头
fetch('/api/transfer', {
    headers: { 'X-Requested-With': 'XMLHttpRequest' }
})
```

**推荐组合：SameSite=Lax + CSRF Token（重要接口）**

---

## 3. XSS 防护

### 3.1 什么是 XSS

**跨站脚本攻击（Cross-Site Scripting）**：攻击者在网页中注入恶意脚本，窃取用户信息。

### 3.2 XSS 类型

| 类型 | 说明 | 示例 |
|------|------|------|
| **存储型** | 恶意代码存数据库，每次访问触发 | 评论区的 `<script>` |
| **反射型** | 恶意代码在 URL 参数中，一次点击触发 | `?q=<script>alert(1)</script>` |
| **DOM 型** | 通过 JS 动态修改 DOM 触发 | innerHTML 插入恶意内容 |

### 3.3 XSS 防护措施

```python
# 1. 输出转义（最基础也最重要）
# 模板引擎自动转义（Jinja2、Thymeleaf 默认开启）
{{ user_input }}          # 自动转义 HTML 特殊字符

# 2. Content Security Policy
# HTTP 响应头
Content-Security-Policy: default-src 'self'; script-src 'self' cdn.example.com

# 3. HttpOnly Cookie（防 XSS 窃取 Cookie）
Set-Cookie: session=xxx; HttpOnly; Secure; SameSite=Lax

# 4. 输入过滤（避免存恶意内容）
import bleach
clean_html = bleach.clean(user_input, tags=['p', 'b', 'i'], strip=True)
```

**XSS 防御优先级：**
1. **输出转义** — 所有用户输入在输出时转义
2. **CSP 头** — 限制脚本来源
3. **输入过滤** — 存储时清洗（防御深度）

---

## 4. 暴力破解防护

### 4.1 防护策略

| 策略 | 实现 | 效果 |
|------|------|------|
| **登录限速** | 同一 IP 每分钟最多 5 次 | 降低攻击速度 |
| **账号锁定** | 连续失败 5 次锁定 15 分钟 | 防单个账号 |
| **验证码** | 失败 3 次后要求验证码 | 防自动化工具 |
| **双因素认证** | 密码 + 短信验证码 | 最高安全性 |
| **设备指纹** | 识别异常设备登录 | 防凭据泄露 |

### 4.2 实现示例

```python
from redis import Redis
redis = Redis()

def check_login_rate(ip, username):
    # 按 IP 限速
    ip_key = f"login:ip:{ip}"
    count = redis.incr(ip_key)
    if count == 1:
        redis.expire(ip_key, 60)  # 60 秒窗口
    if count > 5:
        return False, "请求太频繁，请稍后再试"

    # 按账号锁定
    lock_key = f"login:lock:{username}"
    if redis.exists(lock_key):
        ttl = redis.ttl(lock_key)
        return False, f"账号已锁定，请 {ttl} 秒后再试"

    return True, None

def login_failed(username):
    # 记录失败次数
    fail_key = f"login:fail:{username}"
    count = redis.incr(fail_key)
    if count == 1:
        redis.expire(fail_key, 900)  # 15 分钟窗口
    if count >= 5:
        # 锁定账号
        lock_key = f"login:lock:{username}"
        redis.setex(lock_key, 900, 1)
        # 清除失败计数
        redis.delete(fail_key)
```

---

## 5. HTTPS / TLS

### 5.1 为什么 HTTPS 是必须的

```
HTTP 明文传输:
  用户 → [路由器] → [ISP] → [WiFi热点] → 服务器
         ↓ 任何中间节点都可以看到密码原文！

HTTPS 加密传输:
  用户 → [加密通道] → 服务器
         ↓ 中间节点只能看到加密数据
```

### 5.2 TLS 配置要点

```nginx
# Nginx TLS 配置
server {
    listen 443 ssl;
    ssl_protocols TLSv1.2 TLSv1.3;        # 禁用 TLSv1.0/1.1
    ssl_ciphers 'ECDHE+AESGCM:ECDHE+CHACHA20';  # 强加密套件
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;
    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;
}
```

### 5.3 HSTS（HTTP Strict Transport Security）

```nginx
# 告诉浏览器：以后只用 HTTPS 访问我
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
```

---

## 6. 其他安全措施

### 6.1 JWT 存储安全

```javascript
// ❌ 不安全：localStorage 存储 Token
localStorage.setItem('token', jwt);

// ✅ 较安全：HttpOnly Cookie 存 Refresh Token
// Access Token 存内存变量（刷新页面后由 Refresh Token 重新获取）
```

### 6.2 敏感信息日志脱敏

```python
import re

def mask_sensitive(data):
    """日志脱敏处理"""
    # 手机号: 138****1234
    data = re.sub(r'(1[3-9]\d)\d{4}(\d{4})', r'\1****\2', str(data))
    # 身份证: 110101****1234
    data = re.sub(r'(\d{6})\d{8}(\d{4})', r'\1********\2', data)
    # 密码
    data = re.sub(r'"password":\s*"[^"]*"', '"password":"***"', data)
    return data
```

### 6.3 接口限流

```python
from flask_limiter import Limiter

limiter = Limiter(key_func=lambda: request.remote_addr)

@app.route('/api/login', methods=['POST'])
@limiter.limit("5/minute")       # 每分钟 5 次
@limiter.limit("20/hour")        # 每小时 20 次
def login():
    ...

@app.route('/api/register', methods=['POST'])
@limiter.limit("3/hour")         # 每小时 3 次
def register():
    ...
```

### 6.4 安全 Headers

```nginx
# 推荐的安全响应头
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header X-XSS-Protection "0" always;  # 现代浏览器已废弃此头
add_header Content-Security-Policy "default-src 'self'" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;
```

---

## 7. 安全 CheckList

### 传输层
- [ ] HTTPS（必须）
- [ ] HSTS
- [ ] TLS 1.2+
- [ ] 安全响应头

### 认证层
- [ ] bcrypt/scrypt/argon2 密码哈希
- [ ] 登录限速
- [ ] 账号锁定
- [ ] 验证码机制
- [ ] 统一错误提示
- [ ] 密码强度校验
- [ ] 双因素认证（可选）

### 存储层
- [ ] 密码非明文存储
- [ ] Token 非明文存储
- [ ] 日志脱敏
- [ ] 敏感数据加密（手机号、身份证等）

### 代码层
- [ ] 输出转义（防 XSS）
- [ ] CSRF 防护
- [ ] SQL 注入防护（参数化查询）
- [ ] 接口限流
- [ ] CORS 配置

### 运维层
- [ ] 密钥轮换
- [ ] 安全审计日志
- [ ] 异常登录告警
- [ ] 依赖安全扫描
