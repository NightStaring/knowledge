"""
02 JWT 认证 + Refresh Token
============================

教学目的：演示 JWT 的签发、验证、刷新完整流程

涵盖知识点：
  1. Access Token（短效）+ Refresh Token（长效）双 Token 机制
  2. JWT 签发（含自定义 claims）
  3. JWT 验证 + 过期处理
  4. Refresh Token 轮换（Refresh Token Rotation）
  5. 无状态认证（不依赖 Session）

运行方式：
  python 02_jwt_auth.py

端口：5002
"""

from flask import Flask, request, jsonify, g
from flask_cors import CORS
import jwt
import bcrypt
import uuid
from datetime import datetime, timedelta
from functools import wraps

app = Flask(__name__)
CORS(app)

# ============================================================
# JWT 配置
#
# 生产环境密钥应从环境变量获取，不要硬编码！
# 建议用 RS256（非对称）代替 HS256（对称）
# ============================================================
ACCESS_SECRET = 'your-access-secret-key-change-in-production'
REFRESH_SECRET = 'your-refresh-secret-key-change-in-production'
ACCESS_EXPIRATION = timedelta(minutes=30)    # Access Token 有效期
REFRESH_EXPIRATION = timedelta(days=7)       # Refresh Token 有效期

# 已使用的 Refresh Token 黑名单（防重放）
# 生产环境用 Redis，设置 TTL 与 REFRESH_EXPIRATION 一致
used_refresh_tokens = set()

# ============================================================
# 模拟数据库
# ============================================================
users_db = {}
next_id = 1


def create_user(username, password_hash, role='user'):
    global next_id
    user = {
        'id': next_id,
        'username': username,
        'password_hash': password_hash,
        'role': role,
    }
    users_db[username] = user
    next_id += 1
    return user


def init_users():
    """初始化测试用户"""
    create_user('admin', bcrypt.hashpw('Admin123'.encode(), bcrypt.gensalt(12)), role='admin')
    create_user('alice', bcrypt.hashpw('Hello123'.encode(), bcrypt.gensalt(12)), role='user')


init_users()


# ============================================================
# JWT 工具函数
# ============================================================

def create_access_token(user_id, username, role):
    """
    生成 Access Token（短效）

    JWT 结构：header.payload.signature

    claims 说明：
      sub (Subject):      用户 ID
      username/role:      自定义字段
      iat (Issued At):    签发时间
      exp (Expiration):   过期时间

    注意：payload 只是 Base64 编码，不是加密！
    不要在 payload 中放密码等敏感信息。
    """
    payload = {
        'sub': user_id,
        'username': username,
        'role': role,
        'iat': datetime.utcnow(),
        'exp': datetime.utcnow() + ACCESS_EXPIRATION,
    }
    return jwt.encode(payload, ACCESS_SECRET, algorithm='HS256')


def create_refresh_token(user_id):
    """
    生成 Refresh Token（长效）

    包含 jti（JWT ID），用于实现黑名单和轮换。
    """
    payload = {
        'sub': user_id,
        'jti': str(uuid.uuid4()),     # 唯一 ID，用于黑名单
        'iat': datetime.utcnow(),
        'exp': datetime.utcnow() + REFRESH_EXPIRATION,
    }
    return jwt.encode(payload, REFRESH_SECRET, algorithm='HS256')


def verify_access_token(token):
    """
    验证 Access Token

    验证流程：
      1. 检查签名是否有效
      2. 检查是否过期
      3. 返回 payload

    可能抛出的异常：
      jwt.ExpiredSignatureError: Token 已过期
      jwt.InvalidTokenError:     Token 无效（签名错误、格式错误等）
    """
    try:
        payload = jwt.decode(token, ACCESS_SECRET, algorithms=['HS256'])
        return payload
    except jwt.ExpiredSignatureError:
        raise PermissionError('Token 已过期')
    except jwt.InvalidTokenError:
        raise PermissionError('Token 无效')


def verify_refresh_token(token):
    """
    验证 Refresh Token + 轮换

    Refresh Token Rotation（轮换）:
      每次使用 Refresh Token 后，旧 Token 立即失效。
      即使 Refresh Token 被盗，攻击者也只能用一次。
    """
    try:
        payload = jwt.decode(token, REFRESH_SECRET, algorithms=['HS256'])
        jti = payload.get('jti')

        # 检查是否已被使用（防重放）
        if jti in used_refresh_tokens:
            raise PermissionError('Refresh Token 已被使用，可能被盗用')

        # 标记为已使用
        used_refresh_tokens.add(jti)

        return payload

    except jwt.ExpiredSignatureError:
        raise PermissionError('Refresh Token 已过期，请重新登录')
    except jwt.InvalidTokenError:
        raise PermissionError('Refresh Token 无效')


# ============================================================
# JWT 认证装饰器
# ============================================================

def require_auth(f):
    """
    JWT 认证装饰器

    从 Authorization Header 提取 Bearer Token，
    验证后将用户信息存入 g 对象。

    用法：
      @app.route('/api/profile')
      @require_auth
      def profile():
          # g.user_id, g.username 可用
    """

    @wraps(f)
    def decorated(*args, **kwargs):
        # ============================================================
        # 从 Authorization Header 提取 Token
        #
        # 格式: Authorization: Bearer <token>
        #
        # 为什么用 Header 而不是 Cookie？
        #   - 跨域友好（Cookie 受同源策略限制）
        #   - 移动端原生 App 更容易处理
        #   - 可防范 CSRF（不是自动携带的）
        # ============================================================
        auth_header = request.headers.get('Authorization', '')

        if not auth_header.startswith('Bearer '):
            return jsonify({'code': 401, 'message': '缺少 Token'}), 401

        token = auth_header[7:]  # 去掉 "Bearer "

        try:
            payload = verify_access_token(token)
            g.user_id = payload['sub']
            g.username = payload['username']
            g.role = payload.get('role', 'user')
            return f(*args, **kwargs)

        except PermissionError as e:
            return jsonify({'code': 401, 'message': str(e)}), 401

    return decorated


# ============================================================
# API 接口
# ============================================================

@app.route('/api/login', methods=['POST'])
def login():
    """
    登录：返回 Access Token + Refresh Token

    客户端收到 Token 后：
      - Access Token 存在内存（或 sessionStorage）
      - Refresh Token 存在 HttpOnly Cookie 或安全存储
    """
    data = request.get_json()
    username = data.get('username', '')
    password = data.get('password', '')

    user = users_db.get(username)
    if not user or not bcrypt.checkpw(password.encode(), user['password_hash'].encode()):
        return jsonify({'code': 401, 'message': '账号或密码错误'}), 401

    # 签发 Token 对
    access_token = create_access_token(user['id'], user['username'], user['role'])
    refresh_token = create_refresh_token(user['id'])

    print(f" [✓] 登录成功: {username}")
    return jsonify({
        'code': 200,
        'data': {
            'accessToken': access_token,
            'refreshToken': refresh_token,
            'expiresIn': 1800,           # Access Token 有效期（秒）
            'tokenType': 'Bearer',
        }
    })


@app.route('/api/refresh', methods=['POST'])
def refresh():
    """
    刷新 Token

    客户端在 Access Token 过期时调用此接口。
    传入 Refresh Token，返回新的 Token 对。
    """
    data = request.get_json()
    refresh_token = data.get('refreshToken', '')

    if not refresh_token:
        return jsonify({'code': 400, 'message': 'Refresh Token 不能为空'}), 400

    try:
        payload = verify_refresh_token(refresh_token)

        # 查询用户信息
        user = next(
            (u for u in users_db.values() if u['id'] == payload['sub']),
            None
        )
        if not user:
            return jsonify({'code': 401, 'message': '用户不存在'}), 401

        # 签发新的 Token 对
        new_access = create_access_token(user['id'], user['username'], user['role'])
        new_refresh = create_refresh_token(user['id'])

        return jsonify({
            'code': 200,
            'data': {
                'accessToken': new_access,
                'refreshToken': new_refresh,
                'expiresIn': 1800,
            }
        })

    except PermissionError as e:
        return jsonify({'code': 401, 'message': str(e)}), 401


@app.route('/api/profile', methods=['GET'])
@require_auth
def profile():
    """获取用户信息（需要有效的 Access Token）"""
    return jsonify({
        'code': 200,
        'data': {
            'userId': g.user_id,
            'username': g.username,
            'role': g.role,
        }
    })


@app.route('/api/protected', methods=['GET'])
@require_auth
def protected():
    """受保护的接口示例"""
    return jsonify({
        'code': 200,
        'data': {
            'message': f'你好 {g.username}，你访问了受保护的接口',
            'yourRole': g.role,
        }
    })


if __name__ == '__main__':
    print(" [*] JWT 认证示例启动: http://localhost:5002")
    print("     POST /api/login      登录获取 Token")
    print("     POST /api/refresh    刷新 Token")
    print("     GET  /api/profile    获取用户信息（需 Bearer Token）")
    print("     GET  /api/protected  受保护接口")
    print()
    print(" 测试方法:")
    print("   curl -X POST http://localhost:5002/api/login \\")
    print("     -H 'Content-Type: application/json' \\")
    print("     -d '{\"username\":\"alice\",\"password\":\"Hello123\"}'")
    print()
    print("   curl http://localhost:5002/api/profile \\")
    print("     -H 'Authorization: Bearer <your-token>'")
    app.run(port=5002, debug=True)
