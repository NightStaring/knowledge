"""
01 登录注册 + BCrypt 密码哈希 + Session 认证
=============================================

教学目的：演示最基础的登录注册流程

涵盖知识点：
  1. BCrypt 密码哈希（不存明文！）
  2. 注册校验（用户名唯一、密码强度）
  3. 登录验证 + 统一错误提示
  4. Flask Session 登录保持
  5. 登录状态拦截器

运行方式：
  python 01_login_register.py

端口：5001
"""

from flask import Flask, request, session, jsonify, g
from flask_cors import CORS
import bcrypt
import re
from datetime import timedelta

app = Flask(__name__)

# ============================================================
# Session 配置
#
# SECRET_KEY: 用于加密 Session Cookie
#   生产环境应从环境变量获取，不要硬编码！
#
# PERMANENT_SESSION_LIFETIME: Session 有效期
# SESSION_COOKIE_HTTPONLY: 禁止 JS 读取 Cookie（防 XSS）
# SESSION_COOKIE_SAMESITE: 防 CSRF
# ============================================================
app.config['SECRET_KEY'] = 'your-secret-key-change-in-production'
app.config['PERMANENT_SESSION_LIFETIME'] = timedelta(hours=2)
app.config['SESSION_COOKIE_HTTPONLY'] = True
app.config['SESSION_COOKIE_SAMESITE'] = 'Lax'

CORS(app, supports_credentials=True)

# ============================================================
# 模拟数据库
# ============================================================
users_db = {}  # {username: user_dict}
next_id = 1


def find_user_by_username(username):
    """根据用户名查找用户"""
    return users_db.get(username)


def create_user(username, password_hash):
    """创建新用户"""
    global next_id
    user = {
        'id': next_id,
        'username': username,
        'password_hash': password_hash,
        'created_at': None,  # 生产环境用 datetime.now()
    }
    users_db[username] = user
    next_id += 1
    return user


# ============================================================
# 密码工具函数
# ============================================================

def hash_password(password):
    """
    BCrypt 哈希密码

    BCrypt 的特点：
      - 自动生成随机 salt（每次哈希结果不同）
      - rounds=12 约 250ms 哈希时间
      - 抗彩虹表攻击
      - 抗 GPU/ASIC 暴力破解

    即使两个用户密码相同，哈希结果也不同。
    即使数据库泄露，攻击者也很难还原密码。
    """
    # gensalt(rounds=12): rounds 越大越安全，但也越慢
    # 12 是安全与性能的良好平衡点
    salt = bcrypt.gensalt(rounds=12)
    return bcrypt.hashpw(password.encode('utf-8'), salt)


def verify_password(password, password_hash):
    """
    验证密码

    bcrypt.checkpw() 会从 password_hash 中提取 salt，
    然后用它对输入的密码做同样的哈希，最后比较结果。
    """
    return bcrypt.checkpw(
        password.encode('utf-8'),
        password_hash.encode('utf-8')
    )


def validate_password_strength(password):
    """
    密码强度校验

    返回 (是否通过, 错误信息)
    """
    if len(password) < 8:
        return False, '密码至少 8 位'
    if not re.search(r'[A-Z]', password):
        return False, '密码需包含大写字母'
    if not re.search(r'[a-z]', password):
        return False, '密码需包含小写字母'
    if not re.search(r'\d', password):
        return False, '密码需包含数字'
    # 常见密码黑名单
    common_passwords = {'password', '12345678', 'qwerty123'}
    if password.lower() in common_passwords:
        return False, '密码过于常见，请更换'
    return True, ''


# ============================================================
# 登录拦截器
# ============================================================

@app.before_request
def check_login():
    """
    请求前拦截器

    在每个请求处理前执行，检查是否需要登录。

    /api/register 和 /api/login 是公开接口，不需要登录。
    其他 /api/* 接口需要登录。
    """
    # 放行公开接口
    if request.path in ('/api/register', '/api/login'):
        return None

    # 只拦截 /api/* 路径
    if not request.path.startswith('/api/'):
        return None

    # ============================================================
    # 检查 Session 中是否有用户信息
    #
    # Flask Session 默认存储在浏览器 Cookie 中（加密后）
    # 生产环境建议用 Redis 存储 Session（flask-session）
    # ============================================================
    if 'user_id' not in session:
        return jsonify({'code': 401, 'message': '未登录，请先登录'}), 401

    # 把用户信息存入 g 对象，供视图函数使用
    g.user_id = session['user_id']
    g.username = session['username']


# ============================================================
# 注册接口
# ============================================================

@app.route('/api/register', methods=['POST'])
def register():
    """
    注册接口

    流程：
      1. 参数校验
      2. 密码强度检查
      3. 用户名唯一性检查
      4. BCrypt 哈希密码
      5. 保存用户
      6. 返回成功
    """
    data = request.get_json()
    if not data:
        return jsonify({'code': 400, 'message': '请求体不能为空'}), 400

    username = data.get('username', '').strip()
    password = data.get('password', '')

    # 参数校验
    if not username:
        return jsonify({'code': 400, 'message': '用户名不能为空'}), 400
    if not password:
        return jsonify({'code': 400, 'message': '密码不能为空'}), 400

    # 用户名长度校验
    if len(username) < 3 or len(username) > 20:
        return jsonify({'code': 400, 'message': '用户名长度 3~20 位'}), 400

    # 密码强度校验
    valid, error_msg = validate_password_strength(password)
    if not valid:
        return jsonify({'code': 400, 'message': error_msg}), 400

    # 检查用户名是否已存在
    if find_user_by_username(username):
        return jsonify({'code': 400, 'message': '用户名已存在'}), 400

    # BCrypt 哈希密码
    password_hash = hash_password(password)

    # 保存用户
    user = create_user(username, password_hash)

    print(f" [✓] 注册成功: {username}")
    return jsonify({
        'code': 200,
        'message': 'ok',
        'data': {'userId': user['id'], 'username': user['username']}
    })


# ============================================================
# 登录接口
# ============================================================

@app.route('/api/login', methods=['POST'])
def login():
    """
    登录接口

    流程：
      1. 参数校验
      2. 验证账号密码
      3. 统一错误提示（不区分"用户不存在"和"密码错误"）
      4. 创建 Session
      5. 返回用户信息
    """
    data = request.get_json()
    if not data:
        return jsonify({'code': 400, 'message': '请求体不能为空'}), 400

    username = data.get('username', '').strip()
    password = data.get('password', '')

    if not username or not password:
        return jsonify({'code': 400, 'message': '用户名和密码不能为空'}), 400

    user = find_user_by_username(username)

    # ============================================================
    # 统一错误提示
    #
    # 不要区分"用户不存在"和"密码错误"
    # 否则攻击者可以枚举有效账号
    # ============================================================
    if not user or not verify_password(password, user['password_hash']):
        return jsonify({'code': 401, 'message': '账号或密码错误'}), 401

    # ============================================================
    # 创建 Session
    #
    # session['user_id'] = user['id']
    #   把用户信息存入 Session
    #   Flask 默认把 Session 加密后存入 Cookie
    #   客户端无法篡改内容
    #
    # session.permanent = True
    #   使 Session 使用 PERMANENT_SESSION_LIFETIME 配置的超时时间
    # ============================================================
    session['user_id'] = user['id']
    session['username'] = user['username']
    session.permanent = True

    print(f" [✓] 登录成功: {username}")
    return jsonify({
        'code': 200,
        'message': 'ok',
        'data': {'userId': user['id'], 'username': user['username']}
    })


# ============================================================
# 获取用户信息
# ============================================================

@app.route('/api/profile', methods=['GET'])
def profile():
    """
    获取用户信息

    需要登录（由拦截器保证）
    拦截器已经把 user_id 和 username 放入了 g 对象
    """
    return jsonify({
        'code': 200,
        'message': 'ok',
        'data': {
            'userId': g.user_id,
            'username': g.username,
        }
    })


# ============================================================
# 退出登录
# ============================================================

@app.route('/api/logout', methods=['POST'])
def logout():
    """
    退出登录

    清除 Session，下次请求需要重新登录
    """
    session.clear()
    return jsonify({'code': 200, 'message': 'ok'})


if __name__ == '__main__':
    print(" [*] 登录注册示例启动: http://localhost:5001")
    print("     POST /api/register  注册")
    print("     POST /api/login     登录")
    print("     GET  /api/profile   获取用户信息")
    print("     POST /api/logout    退出")
    app.run(port=5001, debug=True)
