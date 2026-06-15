# Java 认证鉴权教学示例

## 前置条件

```bash
# 需要 JDK 17+ 和 Maven

# 编译
mvn compile

# 运行示例（每个文件都是一个独立的 Spring Boot 应用）
# 01 - 登录注册 + BCrypt 密码哈希
mvn spring-boot:run -Dspring-boot.run.mainClass=LoginRegisterApplication

# 02 - JWT + Refresh Token
mvn spring-boot:run -Dspring-boot.run.mainClass=JwtAuthApplication

# 03 - RBAC 权限控制
mvn spring-boot:run -Dspring-boot.run.mainClass=RbacApplication
```

## 文件清单

| 文件 | 说明 | 端口 |
|------|------|------|
| `LoginRegisterApplication.java` | 登录注册 + BCrypt + Session | 8081 |
| `JwtAuthApplication.java` | JWT 签发/验证 + Refresh Token | 8082 |
| `RbacApplication.java` | RBAC 角色权限控制 | 8083 |

## 测试 API

```bash
# 注册
curl -X POST http://localhost:8081/api/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"Hello123"}'

# 登录
curl -X POST http://localhost:8081/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"Hello123"}'
```
