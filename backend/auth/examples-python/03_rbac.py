"""
03 RBAC 权限控制
=================

教学目的：演示基于角色的访问控制（RBAC）完整实现

涵盖知识点：
  1. RBAC 角色权限模型：用户 ↔ 角色 ↔ 权限
  2. 角色继承（admin 继承 manager，manager 继承 user）
  3. 接口级权限检查（装饰器）
  4. 数据级权限过滤（不同角色看到不同数据范围）
  5. 权限缓存

运行方式：
  python 03_rbac.py

端口：5003

测试用户：
  admin/Admin123  → 管理员（全部权限，看所有数据）
  alice/Hello123  → 经理（技术部，看本部门数据）
  bob/Hello123    → 普通用户（技术部，只看自己）
  viewer1/Hello123 → 只读用户
"""

from flask import Flask, request, jsonify, g
from flask_cors import CORS
import bcrypt
from functools import wraps

app = Flask(__name__)
CORS(app)

# ================================================================
# RBAC 核心数据
# ================================================================

# 1. 权限定义
# 格式: "资源:操作"
PERMISSIONS = {
    'order:view': '查看订单',
    'order:create': '创建订单',
    'order:edit': '编辑订单',
    'order:delete': '删除订单',
    'order:approve': '审批订单',
    'user:view': '查看用户',
    'user:create': '创建用户',
    'user:delete': '删除用户',
    'report:view': '查看报表',
    'report:export': '导出报表',
    'system:config': '系统配置',
    'system:log': '查看日志',
}

# 2. 角色定义（含角色继承和权限映射）
#
# 角色继承链：
#   viewer (只读) → user (基本操作) → manager (管理) → admin (全部)
#
# 子角色自动继承父角色的所有权限
ROLES = {
    'viewer': {
        'inherit': [],  # 不继承任何角色
        'permissions': {'order:view', 'user:view'},
    },
    'user': {
        'inherit': ['viewer'],  # 继承 viewer 的所有权限
        'permissions': {'order:create', 'order:edit'},
    },
    'manager': {
        'inherit': ['user'],
        'permissions': {'order:delete', 'order:approve', 'user:create',
                        'report:view', 'report:export'},
    },
    'admin': {
        'inherit': ['manager'],
        'permissions': {'user:delete', 'system:config', 'system:log'},
    },
}


def get_role_permissions(role_name, visited=None):
    """
    获取角色的所有权限（含继承）

    递归收集：
      1. 用户直接角色的权限
      2. 继承角色的权限
      3. 继承角色的继承角色的权限...

    缓存优化：
      生产环境应将结果缓存到 Redis，TTL 设置为 1 小时。
    """
    if visited is None:
        visited = set()
    if role_name in visited:
        return set()
    visited.add(role_name)

    role = ROLES.get(role_name)
    if not role:
        return set()

    permissions = set(role['permissions'])

    # 递归收集继承角色的权限
    for inherited_role in role['inherit']:
        permissions |= get_role_permissions(inherited_role, visited)

    return permissions


def has_permission(role_name, permission_code):
    """检查角色是否有指定权限"""
    return permission_code in get_role_permissions(role_name)


# ================================================================
# 模拟数据
# ================================================================

# 用户数据
users_db = {}
next_user_id = 1


def create_user(username, password_hash, role, department):
    global next_user_id
    user = {
        'id': next_user_id,
        'username': username,
        'password_hash': password_hash,
        'role': role,
        'department': department,
    }
    users_db[username] = user
    next_user_id += 1
    return user


# 订单数据（用于演示数据级权限）
orders_db = []
next_order_id = 1


def create_order(title, owner_username, department, amount):
    global next_order_id
    order = {
        'id': next_order_id,
        'title': title,
        'owner_username': owner_username,
        'department': department,
        'amount': amount,
    }
    orders_db.append(order)
    next_order_id += 1
    return order


def init_data():
    """初始化测试数据"""
    # 用户
    create_user('admin', bcrypt.hashpw('Admin123'.encode(), bcrypt.gensalt(12)),
                'admin', '技术部')
    create_user('alice', bcrypt.hashpw('Hello123'.encode(), bcrypt.gensalt(12)),
                'manager', '技术部')
    create_user('bob', bcrypt.hashpw('Hello123'.encode(), bcrypt.gensalt(12)),
                'user', '技术部')
    create_user('charlie', bcrypt.hashpw('Hello123'.encode(), bcrypt.gensalt(12)),
                'user', '市场部')
    create_user('viewer1', bcrypt.hashpw('Hello123'.encode(), bcrypt.gensalt(12)),
                'viewer', '技术部')

    # 订单
    create_order('技术部-订单A', 'alice', '技术部', 1000)
    create_order('技术部-订单B', 'bob', '技术部', 2000)
    create_order('市场部-订单C', 'charlie', '市场部', 3000)
    create_order('技术部-订单D', 'alice', '技术部', 4000)


init_data()


# ================================================================
# 数据级权限过滤
# ================================================================

def filter_orders(user, orders):
    """
    根据用户角色过滤订单列表

    不同角色能看到的数据范围不同：
      admin:    所有订单
      manager:  本部门的订单
      user:     自己的订单
      viewer:   自己的订单（只读）
    """
    if user['role'] == 'admin':
        return orders  # 管理员看所有

    if user['role'] == 'manager':
        return [o for o in orders if o['department'] == user['department']]

    # user / viewer：只看自己的
    return [o for o in orders if o['owner_username'] == user['username']]


# ================================================================
# 认证与鉴权装饰器
# ================================================================

def require_auth(f):
    """认证装饰器：验证用户登录"""

    @wraps(f)
    def decorated(*args, **kwargs):
        username = request.headers.get('X-Username')
        if not username:
            return jsonify({'code': 401, 'message': '缺少用户标识'}), 401

        user = users_db.get(username)
        if not user:
            return jsonify({'code': 401, 'message': '用户不存在'}), 401

        g.current_user = user
        return f(*args, **kwargs)

    return decorated


def require_permission(permission_code):
    """
    权限检查装饰器

    用法：
      @app.route('/api/orders')
      @require_permission('order:view')
      def get_orders():
          ...

    原理：
      1. 先通过 require_auth 获取用户
      2. 再检查用户角色是否有指定权限
      3. 没有权限返回 403
    """

    def decorator(f):
        @wraps(f)
        @require_auth
        def decorated(*args, **kwargs):
            user = g.current_user
            if not has_permission(user['role'], permission_code):
                return jsonify({
                    'code': 403,
                    'message': f'权限不足，需要 {permission_code} 权限'
                }), 403
            return f(*args, **kwargs)

        return decorated

    return decorator


# ================================================================
# API 接口
# ================================================================

@app.route('/api/login', methods=['POST'])
def login():
    """登录"""
    data = request.get_json()
    username = data.get('username', '')
    password = data.get('password', '')

    user = users_db.get(username)
    if not user or not bcrypt.checkpw(password.encode(), user['password_hash'].encode()):
        return jsonify({'code': 401, 'message': '账号或密码错误'}), 401

    return jsonify({
        'code': 200,
        'data': {
            'userId': user['id'],
            'username': user['username'],
            'role': user['role'],
            'department': user['department'],
        }
    })


@app.route('/api/orders', methods=['GET'])
@require_permission('order:view')
def get_orders():
    """
    获取订单列表（带数据权限过滤）

    不同角色看到的数据不同：
      admin → 所有订单
      manager → 本部门订单
      user/viewer → 自己的订单
    """
    user = g.current_user
    filtered = filter_orders(user, orders_db)

    return jsonify({
        'code': 200,
        'data': [{
            'id': o['id'],
            'title': o['title'],
            'owner': o['owner_username'],
            'department': o['department'],
            'amount': o['amount'],
        } for o in filtered]
    })


@app.route('/api/orders/<int:order_id>', methods=['DELETE'])
@require_permission('order:delete')
def delete_order(order_id):
    """
    删除订单（需要 order:delete 权限）

    额外的数据级权限：
      - admin: 可删任何订单
      - manager: 只能删本部门的订单
    """
    user = g.current_user
    order = next((o for o in orders_db if o['id'] == order_id), None)

    if not order:
        return jsonify({'code': 404, 'message': '订单不存在'}), 404

    # 数据级权限检查
    if user['role'] == 'manager' and order['department'] != user['department']:
        return jsonify({'code': 403, 'message': '不能删除其他部门的订单'}), 403

    orders_db.remove(order)
    return jsonify({'code': 200, 'message': '删除成功'})


@app.route('/api/orders', methods=['POST'])
@require_permission('order:create')
def create_order():
    """创建订单（需要 order:create 权限）"""
    user = g.current_user
    data = request.get_json()

    order = create_order(
        title=data.get('title', ''),
        owner_username=user['username'],
        department=user['department'],
        amount=data.get('amount', 0),
    )

    return jsonify({
        'code': 200,
        'data': {
            'id': order['id'],
            'title': order['title'],
        }
    })


@app.route('/api/orders/<int:order_id>/approve', methods=['POST'])
@require_permission('order:approve')
def approve_order(order_id):
    """审批订单（需要 order:approve 权限）"""
    return jsonify({'code': 200, 'message': '订单已审批'})


@app.route('/api/my-permissions', methods=['GET'])
@require_auth
def my_permissions():
    """查看当前用户的权限列表"""
    user = g.current_user
    permissions = get_role_permissions(user['role'])
    return jsonify({
        'code': 200,
        'data': {
            'role': user['role'],
            'permissions': sorted(permissions),
        }
    })


@app.route('/api/report', methods=['GET'])
@require_permission('report:view')
def view_report():
    """查看报表"""
    return jsonify({
        'code': 200,
        'data': {'message': '这是报表数据，仅供有权限的用户查看'}
    })


@app.route('/api/admin/config', methods=['GET'])
@require_permission('system:config')
def system_config():
    """系统配置（仅 admin）"""
    return jsonify({
        'code': 200,
        'data': {'message': '系统配置，仅 admin 可访问'}
    })


if __name__ == '__main__':
    print(" [*] RBAC 权限示例启动: http://localhost:5003")
    print()
    print(" 测试用户:")
    print("   admin/Admin123  → 管理员")
    print("   alice/Hello123  → 经理（技术部）")
    print("   bob/Hello123    → 普通用户（技术部）")
    print("   viewer1/Hello123 → 只读用户")
    print()
    print(" 测试接口:")
    print("   POST /api/login    登录")
    print("   GET  /api/orders   查看订单（数据权限过滤）")
    print("   POST /api/orders   创建订单")
    print("   DELETE /api/orders/1  删除订单")
    print("   GET  /api/my-permissions  查看权限")
    print()
    print(" 测试命令:")
    print("   # 登录")
    print("   curl -X POST http://localhost:5003/api/login \\")
    print("     -H 'Content-Type: application/json' \\")
    print("     -d '{\"username\":\"alice\",\"password\":\"Hello123\"}'")
    print()
    print("   # 查看订单（alice 能看到技术部所有订单）")
    print("   curl http://localhost:5003/api/orders \\")
    print("     -H 'X-Username: alice'")
    app.run(port=5003, debug=True)
