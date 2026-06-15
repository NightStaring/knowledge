/**
 * 02 JWT 认证 + Refresh Token
 * ============================
 *
 * 教学目的：演示 JWT 的签发、验证、刷新完整流程
 *
 * 涵盖知识点：
 *   1. Access Token（短效）+ Refresh Token（长效）双 Token 机制
 *   2. JWT 签发（含自定义 claims）
 *   3. JWT 验证 + 过期处理
 *   4. Refresh Token 轮换（Refresh Token Rotation）
 *   5. 无状态认证（不依赖 Session）
 *
 * 运行方式：
 *   mvn spring-boot:run -Dspring-boot.run.mainClass=JwtAuthApplication
 *
 * 端口：8082
 */

package com.example.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootApplication
public class JwtAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(JwtAuthApplication.class, args);
    }
}

// ================================================================
// JWT 服务
// ================================================================

/**
 * JWT 令牌服务
 *
 * 双 Token 机制：
 *   Access Token（短效）:
 *     - 有效期 30 分钟
 *     - 每次请求通过 Authorization Header 携带
 *     - 用于访问受保护的 API
 *
 *   Refresh Token（长效）:
 *     - 有效期 7 天
 *     - 存储在 HttpOnly Cookie 中
 *     - 用于获取新的 Access Token
 *     - 每次刷新后轮换（旧 Token 失效）
 */
@Service
class JwtTokenService {

    // ============================================================
    // 密钥管理
    //
    // RS256（非对称）比 HS256（对称）更安全：
    //   - 私钥（SecretKey）: 签发 Token，仅认证服务持有
    //   - 公钥: 验证 Token，可分发到各个微服务
    //
    // 生产环境应从配置中心/密钥管理服务获取
    // ============================================================
    private final SecretKey accessKey = Keys.hmacShaKeyFor(
        "Your-Access-Secret-Key-Must-Be-At-Least-256-Bits-Long!!".getBytes(StandardCharsets.UTF_8)
    );
    private final SecretKey refreshKey = Keys.hmacShaKeyFor(
        "Your-Refresh-Secret-Key-Must-Be-At-Least-256-Bits-Long!!".getBytes(StandardCharsets.UTF_8)
    );

    /** Access Token 有效期：30 分钟 */
    private static final long ACCESS_EXPIRATION = 30 * 60 * 1000L;

    /** Refresh Token 有效期：7 天 */
    private static final long REFRESH_EXPIRATION = 7 * 24 * 60 * 60 * 1000L;

    /** 已使用的 Refresh Token 黑名单（防重放） */
    private final Set<String> usedRefreshTokens = ConcurrentHashMap.newKeySet();

    /**
     * 生成 Access Token
     *
     * JWT 结构：Header.Payload.Signature
     *
     * Payload 中的标准 claims：
     *   sub (Subject):  用户标识
     *   iat (Issued At): 签发时间
     *   exp (Expiration): 过期时间
     *
     * 自定义 claims：
     *   username: 用户名
     *   role:     角色
     */
    public String generateAccessToken(Long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
            .subject(userId.toString())                     // sub: 用户 ID
            .claim("username", username)                     // 自定义 claim
            .claim("role", role)                            // 自定义 claim
            .issuedAt(now)                                  // iat: 签发时间
            .expiration(new Date(now.getTime() + ACCESS_EXPIRATION))  // exp: 过期时间
            .signWith(accessKey)                            // 用私钥签名
            .compact();
    }

    /**
     * 生成 Refresh Token
     *
     * Refresh Token 的 Payload 中包含了 jti（JWT ID），
     * 用于实现 Refresh Token 轮换和黑名单。
     */
    public String generateRefreshToken(Long userId) {
        Date now = new Date();
        return Jwts.builder()
            .subject(userId.toString())
            .id(UUID.randomUUID().toString())               // jti: 唯一 ID，用于黑名单
            .issuedAt(now)
            .expiration(new Date(now.getTime() + REFRESH_EXPIRATION))
            .signWith(refreshKey)
            .compact();
    }

    /**
     * 验证 Access Token
     *
     * 验证流程：
     *   1. 检查签名是否有效（用公钥）
     *   2. 检查是否过期
     *   3. 返回 Payload 中的 claims
     */
    public Claims validateAccessToken(String token) {
        try {
            return Jwts.parser()
                .verifyWith(accessKey)                      // 用公钥验证签名
                .build()
                .parseSignedClaims(token)
                .getPayload();

        } catch (ExpiredJwtException e) {
            throw new TokenException(401, "Token 已过期");
        } catch (SecurityException | MalformedJwtException e) {
            throw new TokenException(401, "Token 无效");
        } catch (Exception e) {
            throw new TokenException(401, "Token 验证失败");
        }
    }

    /**
     * 验证 Refresh Token + 轮换
     *
     * Refresh Token Rotation（轮换）:
     *   每次使用 Refresh Token 后，旧 Token 立即失效
     *   即使 Refresh Token 被盗，攻击者也只能用一次
     */
    public TokenPair refreshAccessToken(String refreshToken) {
        try {
            // 1. 验证 Refresh Token 签名
            Claims claims = Jwts.parser()
                .verifyWith(refreshKey)
                .build()
                .parseSignedClaims(refreshToken)
                .getPayload();

            String jti = claims.getId();

            // 2. 检查是否已被使用（防重放）
            if (usedRefreshTokens.contains(jti)) {
                throw new TokenException(401, "Refresh Token 已被使用，可能被盗用");
            }

            // 3. 标记为已使用
            usedRefreshTokens.add(jti);

            // 4. 签发新的 Token 对
            Long userId = Long.parseLong(claims.getSubject());
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);

            String newAccessToken = generateAccessToken(userId, username, role);
            String newRefreshToken = generateRefreshToken(userId);

            return new TokenPair(newAccessToken, newRefreshToken);

        } catch (ExpiredJwtException e) {
            throw new TokenException(401, "Refresh Token 已过期，请重新登录");
        } catch (TokenException e) {
            throw e;
        } catch (Exception e) {
            throw new TokenException(401, "Refresh Token 无效");
        }
    }

    /**
     * 使所有 Refresh Token 失效（修改密码/退出时调用）
     */
    public void invalidateAllTokens(Long userId) {
        // 生产环境：在 Redis 中记录用户的黑名单版本号
        // JWT 中携带版本号，版本号不匹配即失效
    }
}

/** Token 对 */
class TokenPair {
    public String accessToken;
    public String refreshToken;

    public TokenPair(String accessToken, String refreshToken) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }
}

class TokenException extends RuntimeException {
    int code;
    TokenException(int code, String message) {
        super(message);
        this.code = code;
    }
}

// ================================================================
// 用户服务
// ================================================================

@Service
class UserService {
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);
    private final PasswordEncoder passwordEncoder;

    public UserService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        // 初始化测试用户
        User admin = new User(idGen.getAndIncrement(), "admin",
            passwordEncoder.encode("Admin123"));
        admin.role = "admin";
        users.put("admin", admin);

        User alice = new User(idGen.getAndIncrement(), "alice",
            passwordEncoder.encode("Hello123"));
        alice.role = "user";
        users.put("alice", alice);
    }

    public User login(String username, String password) {
        User user = users.get(username);
        if (user == null || !passwordEncoder.matches(password, user.passwordHash)) {
            throw new RuntimeException("账号或密码错误");
        }
        return user;
    }

    public User findByUsername(String username) {
        return users.get(username);
    }
}

// ================================================================
// 控制器
// ================================================================

@RestController
@RequestMapping("/api")
class JwtController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtTokenService tokenService;

    /**
     * 登录：返回 Access Token + Refresh Token
     *
     * 客户端收到 Token 后：
     *   - Access Token 存在内存（或 sessionStorage）
     *   - Refresh Token 存在 HttpOnly Cookie
     */
    @PostMapping("/login")
    public ApiResult<Map<String, Object>> login(@RequestBody LoginRequest req) {
        User user = userService.login(req.username, req.password);

        // 签发 Token 对
        String accessToken = tokenService.generateAccessToken(
            user.id, user.username, user.role);
        String refreshToken = tokenService.generateRefreshToken(user.id);

        return ApiResult.success(Map.of(
            "accessToken", accessToken,
            "refreshToken", refreshToken,
            "expiresIn", 1800,        // Access Token 有效期（秒）
            "tokenType", "Bearer"
        ));
    }

    /**
     * 刷新 Token
     *
     * 客户端在 Access Token 过期时调用此接口
     * 传入 Refresh Token，返回新的 Token 对
     */
    @PostMapping("/refresh")
    public ApiResult<Map<String, Object>> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isEmpty()) {
            return ApiResult.error(400, "Refresh Token 不能为空");
        }

        TokenPair pair = tokenService.refreshAccessToken(refreshToken);
        return ApiResult.success(Map.of(
            "accessToken", pair.accessToken,
            "refreshToken", pair.refreshToken,
            "expiresIn", 1800
        ));
    }

    /**
     * 获取用户信息（需要有效的 Access Token）
     */
    @GetMapping("/profile")
    public ApiResult<Map<String, Object>> profile(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String username = (String) request.getAttribute("username");
        String role = (String) request.getAttribute("role");

        return ApiResult.success(Map.of(
            "userId", userId,
            "username", username,
            "role", role
        ));
    }
}

// ================================================================
// JWT 拦截器
// ================================================================

/**
 * JWT 认证拦截器
 *
 * 从请求头中提取 Bearer Token，验证后把用户信息放入 Request 属性
 * 后续 Controller 可以从 Request 中获取用户信息
 */
@Component
class JwtInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/api/login", "/api/refresh"
    );

    @Autowired
    private JwtTokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        if (PUBLIC_PATHS.contains(request.getRequestURI())) {
            return true;
        }

        // ============================================================
        // 从 Authorization Header 提取 Token
        //
        // 格式: Authorization: Bearer <token>
        //
        // 为什么用 Header 而不是 Cookie？
        //   - 跨域友好（Cookie 受同源策略限制）
        //   - 移动端原生 App 更容易处理
        //   - 可防范 CSRF（不是自动携带的）
        // ============================================================
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":401,\"message\":\"缺少 Token\"}");
            return false;
        }

        String token = authHeader.substring(7);

        try {
            Claims claims = tokenService.validateAccessToken(token);

            // 把用户信息放入 Request 属性，供 Controller 使用
            request.setAttribute("userId", Long.parseLong(claims.getSubject()));
            request.setAttribute("username", claims.get("username", String.class));
            request.setAttribute("role", claims.get("role", String.class));

            return true;

        } catch (TokenException e) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"code\":401,\"message\":\"" + e.getMessage() + "\"}"
            );
            return false;
        }
    }
}

@Configuration
class JwtConfig implements WebMvcConfigurer {
    @Autowired
    private JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor).addPathPatterns("/api/**");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}

// 复用 LoginRegisterApplication 中的 User 和 LoginRequest 类
// 为独立运行，在此重新定义
class User {
    Long id;
    String username;
    String passwordHash;
    String role = "user";

    public User(Long id, String username, String passwordHash) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
    }
}

class LoginRequest {
    public String username;
    public String password;
}

class ApiResult<T> {
    int code;
    String message;
    T data;

    static <T> ApiResult<T> success(T data) {
        ApiResult<T> r = new ApiResult<>();
        r.code = 200;
        r.message = "ok";
        r.data = data;
        return r;
    }

    static <T> ApiResult<T> error(int code, String message) {
        ApiResult<T> r = new ApiResult<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
