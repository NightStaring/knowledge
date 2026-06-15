# Python 认证鉴权教学示例

## 前置条件

```bash
pip install flask flask-cors bcrypt pyjwt
```

## 运行方式

```bash
# 01 - 登录注册 + BCrypt 密码哈希 + Session
python 01_login_register.py

# 02 - JWT + Refresh Token
python 02_jwt_auth.py

# 03 - RBAC 权限控制
python 03_rbac.py
```

## 文件清单

| 文件 | 说明 | 端口 |
|------|------|------|
| `01_login_register.py` | 登录注册 + BCrypt + Session | 5001 |
| `02_jwt_auth.py` | JWT 签发/验证 + Refresh Token | 5002 |
| `03_rbac.py` | RBAC 角色权限控制 | 5003 |

## 测试 API

```bash
# 注册
curl -X POST http://localhost:5001/api/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"Hello123"}'

# 登录
curl -X POST http://localhost:5001/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"Hello123"}'

# 获取用户信息（需带 Cookie）
curl http://localhost:5001/api/profile --cookie "session=..."
```
