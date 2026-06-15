# OAuth 与第三方登录

> OAuth 2.0 授权流程、第三方社交登录、单点登录（SSO）。

---

## 1. OAuth 2.0 概述

### 1.1 什么是 OAuth

OAuth 2.0 是一个**授权协议**，允许第三方应用获取用户资源的有限访问权限，而无需暴露用户密码。

**生活类比：**
> 你去酒店前台（用户），前台给了一张房卡（Token），你用房卡去开门（访问资源）。房卡有权限限制——只能开自己的房间，不能开总统套房。

### 1.2 角色

```
┌──────────┐       ┌──────────────┐       ┌──────────┐
│ Resource  │       │  Authorization│       │   Client │
│ Owner     │◄─────►│   Server     │◄─────►│   (App)  │
│ (用户)    │       │  (认证服务器) │       │          │
└──────────┘       └──────┬───────┘       └──────────┘
                          │
                    ┌─────┴─────┐
                    │ Resource  │
                    │ Server    │
                    │ (API服务) │
                    └───────────┘
```

| 角色 | 说明 | 示例 |
|------|------|------|
| **Resource Owner** | 资源所有者 | 用户 |
| **Client** | 第三方应用 | 你的 App |
| **Authorization Server** | 认证服务器 | 微信/Google OAuth |
| **Resource Server** | 资源服务器 | API 服务 |

---

## 2. OAuth 2.0 授权模式

### 2.1 授权码模式（Authorization Code）— 最常用

```
用户（浏览器）         前端 App         后端服务器         微信/Google
     │                 │                 │                 │
     │── 点击"微信登录"─→│                 │                 │
     │                 │── 跳转微信授权页 ──────────────────→│
     │                 │   (client_id, redirect_uri, scope) │
     │◄── 微信授权页 ───│                 │                 │
     │                 │                 │                 │
     │── 用户同意授权 ───│                 │                 │
     │                 │                 │                 │
     │◄── 回调 redirect_uri?code=xxx ────│                 │
     │                 │                 │                 │
     │                 │── POST code ────→│                 │
     │                 │                 │── POST code ────→│
     │                 │                 │   + client_secret │
     │                 │                 │◄── access_token ─│
     │                 │                 │◄── refresh_token─│
     │                 │                 │                  │
     │                 │                 │── 用 access_token →│ (获取用户信息)
     │                 │                 │◄── 用户信息 ──────│
     │                 │                 │                  │
     │                 │◄── 登录成功 ─────│                  │
     │◄── 跳转首页 ────│                 │                  │
```

**关键点：**
- `client_secret` 只在后端服务器使用，**绝不暴露到前端**
- `code` 是一次性的，有效期很短（通常 5 分钟）
- 前端得到 `code` 后传给后端，后端去换 `access_token`

### 2.2 隐式模式（Implicit）— 不推荐

```
用户 → 前端 App → 认证服务器
               ← access_token (直接返回)
```

- 前端直接拿到 Token
- **安全问题**：Token 暴露在 URL 中
- **已废弃**：OAuth 2.1 已移除该模式

### 2.3 密码模式（Resource Owner Password Credentials）

```
用户 → App → 认证服务器
           ← access_token
```

- 直接拿用户名密码换 Token
- **仅限**高度信任的第一方应用
- 不适用于第三方登录

### 2.4 客户端模式（Client Credentials）

```
App → 认证服务器 → access_token
```

- 无用户参与的机器对机器认证
- 适合服务间调用、定时任务

---

## 3. 第三方社交登录实现

### 3.1 微信登录

```python
# 1. 前端调起微信授权
# 前端跳转:
WEIXIN_AUTH_URL = "https://open.weixin.qq.com/connect/oauth2/authorize"
params = {
    "appid": APP_ID,
    "redirect_uri": "https://your-app.com/auth/weixin/callback",
    "response_type": "code",
    "scope": "snsapi_userinfo",
    "state": csrf_token  # 防 CSRF
}

# 2. 后端回调接口
@app.get('/auth/weixin/callback')
def weixin_callback(code: str, state: str):
    # 验证 state（防 CSRF）

    # 用 code 换 access_token
    resp = requests.post("https://api.weixin.qq.com/sns/oauth2/access_token", params={
        "appid": APP_ID,
        "secret": APP_SECRET,
        "code": code,
        "grant_type": "authorization_code"
    })
    data = resp.json()
    access_token = data["access_token"]
    openid = data["openid"]

    # 获取用户信息
    user_resp = requests.get("https://api.weixin.qq.com/sns/userinfo", params={
        "access_token": access_token,
        "openid": openid
    })
    user_info = user_resp.json()

    # 查找或创建用户
    user = find_or_create_user(openid, platform="weixin", user_info=user_info)

    # 生成你的应用 Token
    return create_login_token(user)
```

### 3.2 GitHub 登录

```python
# 1. 跳转 GitHub 授权
GITHUB_AUTH_URL = "https://github.com/login/oauth/authorize"
params = {
    "client_id": CLIENT_ID,
    "redirect_uri": "https://your-app.com/auth/github/callback",
    "scope": "user:email",
    "state": csrf_token
}

# 2. 回调处理
@app.get('/auth/github/callback')
def github_callback(code: str, state: str):
    # 用 code 换 access_token
    resp = requests.post("https://github.com/login/oauth/access_token", data={
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "code": code
    }, headers={"Accept": "application/json"})
    token = resp.json()["access_token"]

    # 获取用户信息
    user_resp = requests.get("https://api.github.com/user", headers={
        "Authorization": f"Bearer {token}"
    })
    user_info = user_resp.json()

    user = find_or_create_user(user_info["id"], platform="github", user_info=user_info)
    return create_login_token(user)
```

### 3.3 数据库设计

```sql
-- 用户主表
CREATE TABLE users (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    username    VARCHAR(50),
    password    VARCHAR(255),       -- 可为空（第三方注册用户）
    email       VARCHAR(100),
    avatar      VARCHAR(500),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 第三方账号关联表
CREATE TABLE user_social_accounts (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    platform        VARCHAR(20) NOT NULL,  -- weixin, github, google
    platform_uid    VARCHAR(100) NOT NULL,  -- 第三方平台用户 ID
    platform_info   JSON,                   -- 第三方返回的原始信息
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_platform_uid (platform, platform_uid),
    FOREIGN KEY (user_id) REFERENCES users(id)
);
```

### 3.4 绑定与解绑

```python
# 绑定第三方账号到已有账号
@app.post('/bind/{platform}')
def bind_social(user_id: int, platform: str, code: str):
    # 用 code 获取第三方用户信息
    platform_uid = get_platform_uid(platform, code)

    # 检查该第三方账号是否已被其他用户绑定
    existing = SocialAccount.query.filter_by(
        platform=platform, platform_uid=platform_uid
    ).first()
    if existing:
        return {"error": "该第三方账号已被绑定"}

    # 绑定
    social = SocialAccount(user_id=user_id, platform=platform, platform_uid=platform_uid)
    db.session.add(social)
    db.session.commit()
```

---

## 4. 单点登录（SSO）

### 4.1 什么是 SSO

**单点登录（Single Sign-On）**：用户登录一次，即可访问所有相互信任的系统。

```
┌─────────────────────────────────────────┐
│            SSO 认证中心                   │
│         (auth.company.com)               │
└──────────┬──────────────┬───────────────┘
           │              │
     ┌─────┴─────┐  ┌────┴─────┐
     │ 系统 A    │  │ 系统 B    │
     │ appA.com  │  │ appB.com │
     └───────────┘  └──────────┘
```

### 4.2 SSO 登录流程

```
用户访问系统 A（未登录）
    ↓
系统 A 重定向到 SSO 认证中心
    ↓
SSO 认证中心检查是否已登录
  ├─ 未登录 → 显示登录页
  │           ↓
  │          用户输入凭证
  │           ↓
  │          认证中心创建全局 Session
  │           ↓
  └─ 已登录 ──┘
    ↓
认证中心生成授权票据（Ticket）
    ↓
重定向回系统 A（带 Ticket）
    ↓
系统 A 用 Ticket 到认证中心验证
    ↓
系统 A 创建本地 Session，用户登录成功
    ↓
用户访问系统 B（未登录）
    ↓
系统 B 重定向到 SSO 认证中心
    ↓
认证中心已登录 → 直接发 Ticket
    ↓
系统 B 验证 Ticket → 登录成功（无需再次输入密码）
```

### 4.3 SSO 实现方案

| 方案 | 原理 | 复杂度 | 适用 |
|------|------|--------|------|
| **CAS** | Central Authentication Service | 高 | 企业级 |
| **OAuth 2.0** | 用 OAuth 模拟 SSO | 中 | 常见 |
| **SAML** | 基于 XML 的安全断言 | 高 | 企业 |
| **LDAP/AD** | 统一用户目录 | 中 | 企业内部 |

### 4.4 SSO 退出

```
用户退出系统 A
    ↓
系统 A 清除本地 Session
    ↓
系统 A 通知 SSO 认证中心（退出请求）
    ↓
认证中心清除全局 Session
    ↓
认证中心通知所有关联系统（SLO - Single Logout）
    ↓
系统 B、系统 C 清除本地 Session
```

---

## 5. OAuth 2.1 的变化

OAuth 2.1 简化了 2.0，主要变化：

| 变化 | 说明 |
|------|------|
| **移除隐式模式** | 已废弃 |
| **移除密码模式** | 已废弃 |
| **PKCE 成为授权码模式的必选项** | 防拦截 |
| **Refresh Token 必须一次性** | 轮换机制 |
| **Redirect URI 必须精确匹配** | 防重定向攻击 |

---

## 6. 常见问题

### 6.1 "为什么要有 state 参数？"

`state` 参数用于**防止 CSRF 攻击**。

```
攻击流程（无 state）：
  1. 攻击者获取一个授权链接
  2. 诱导用户点击
  3. 用户的账号绑定了攻击者的第三方账号

防御（有 state）：
  1. 前端生成随机 state，存在 sessionStorage
  2. 跳转授权时带上 state
  3. 回调时验证 state 是否匹配
```

### 6.2 "为什么第三方登录也要绑定手机号？"

- 实名认证要求（中国的网络安全法）
- 账号找回：第三方平台可能封禁你的 App
- 用户识别：同一个人可能用微信、GitHub 各注册一次，需要合并

### 6.3 "access_token 和 API Token 有什么区别？"

| | OAuth access_token | 传统 API Token |
|--|-------------------|----------------|
| **用途** | 代表用户授权第三方访问 | 标识 API 调用者身份 |
| **获取** | OAuth 流程获得 | 手动生成 / 登录获得 |
| **范围** | 可限制 scope | 通常全权限 |
| **有效期** | 短（通常 2 小时） | 长（可永久） |
