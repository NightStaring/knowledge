/**
 * 01 登录注册 + BCrypt 密码哈希 + Session 认证
 * =============================================
 *
 * 教学目的：演示最基础的登录注册流程
 *
 * 涵盖知识点：
 *   1. BCrypt 密码哈希（不存明文！）
 *   2. 注册校验（用户名唯一、密码强度）
 *   3. 登录验证 + 统一错误提示
 *   4. HttpSession 登录保持
 *   5. 登录状态拦截器
 *
 * 运行方式：
 *   mvn spring-boot:run -Dspring-boot.run.mainClass=LoginRegisterApplication
 *
 * 端口：8081
 */

package com.example.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootApplication
public class LoginRegisterApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoginRegisterApplication.class, args);
    }
}

// ================================================================
// 数据模型
// ================================================================

class User {
    Long id;
    String username;
    String passwordHash;
    LocalDateTime createdAt;
    LocalDateTime lastLoginAt;

    public User(Long id, String username, String passwordHash) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.createdAt = LocalDateTime.now();
    }
}

class RegisterRequest {
    public String username;
    public String password;
    public String email;
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
    }

    /**
     * 注册新用户
     */
    public User register(String username, String password) {
        if (users.containsKey(username)) {
            throw new BusinessException(400, "用户名已存在");
        }
        validatePassword(password);

        // ============================================================
        // BCrypt 哈希密码
        //
        // BCrypt 的特点：
        //   - 自动生成随机 salt（每次哈希结果不同）
        //   - 可配置 cost 因子（控制哈希速度）
        //   - 抗彩虹表攻击
        //   - 抗 GPU/ASIC 暴力破解
        //
        // 即使两个用户密码相同，哈希结果也不同
        // 即使数据库泄露，攻击者也很难还原密码
        // ============================================================
        String passwordHash = passwordEncoder.encode(password);

        User user = new User(idGen.getAndIncrement(), username, passwordHash);
        users.put(username, user);
        return user;
    }

    /**
     * 登录验证
     *
     * 关键安全点：
     *   1. 统一错误提示（不透露是用户不存在还是密码错误）
     *   2. BCrypt 密码比对
     */
    public User login(String username, String password) {
        User user = users.get(username);

        // ============================================================
        // 统一错误提示
        //
        // 不要区分"用户不存在"和"密码错误"
        // 否则攻击者可以枚举有效账号
        // ============================================================
        if (user == null || !passwordEncoder.matches(password, user.passwordHash)) {
            throw new BusinessException(401, "账号或密码错误");
        }

        user.lastLoginAt = LocalDateTime.now();
        return user;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new BusinessException(400, "密码至少 8 位");
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new BusinessException(400, "密码需包含大写字母");
        }
        if (!password.matches(".*[a-z].*")) {
            throw new BusinessException(400, "密码需包含小写字母");
        }
        if (!password.matches(".*\\d.*")) {
            throw new BusinessException(400, "密码需包含数字");
        }
        if (password.equalsIgnoreCase("password") || password.equals("12345678")) {
            throw new BusinessException(400, "密码过于常见，请更换");
        }
    }
}

class BusinessException extends RuntimeException {
    int code;
    BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}

// ================================================================
// 控制器
// ================================================================

@RestController
@RequestMapping("/api")
class AuthController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public ApiResult<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        if (req.username == null || req.username.trim().isEmpty()) {
            return ApiResult.error(400, "用户名不能为空");
        }
        if (req.password == null || req.password.isEmpty()) {
            return ApiResult.error(400, "密码不能为空");
        }

        User user = userService.register(req.username.trim(), req.password);
        return ApiResult.success(Map.of("userId", user.id, "username", user.username));
    }

    /**
     * 登录接口
     *
     * HttpSession 的工作原理：
     *   - 登录成功后，服务端创建 Session
     *   - 通过 Set-Cookie 把 sessionId 发给浏览器
     *   - 浏览器后续请求自动带上 Cookie
     *   - 服务端根据 sessionId 找到 Session 数据
     */
    @PostMapping("/login")
    public ApiResult<Map<String, Object>> login(
            @RequestBody LoginRequest req, HttpSession session) {

        if (req.username == null || req.password == null) {
            return ApiResult.error(400, "用户名和密码不能为空");
        }

        User user = userService.login(req.username.trim(), req.password);

        // ============================================================
        // 创建 Session，存储用户信息
        //
        // session.setAttribute() 把用户信息存到服务端 Session 中
        // 后续请求可以从 Session 中取出
        // ============================================================
        session.setAttribute("userId", user.id);
        session.setAttribute("username", user.username);

        return ApiResult.success(Map.of("userId", user.id, "username", user.username));
    }

    @GetMapping("/profile")
    public ApiResult<Map<String, Object>> profile(HttpSession session) {
        return ApiResult.success(Map.of(
            "userId", session.getAttribute("userId"),
            "username", session.getAttribute("username"),
            "loginTime", session.getCreationTime()
        ));
    }

    @PostMapping("/logout")
    public ApiResult<Void> logout(HttpSession session) {
        session.invalidate();  // 使 Session 失效
        return ApiResult.success(null);
    }
}

// ================================================================
// 登录拦截器
// ================================================================

@Component
class LoginInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC_PATHS = Set.of("/api/register", "/api/login");

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        if (PUBLIC_PATHS.contains(request.getRequestURI())) {
            return true;
        }

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":401,\"message\":\"未登录，请先登录\"}");
            return false;
        }
        return true;
    }
}

// ================================================================
// 配置
// ================================================================

@Configuration
class AppConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor).addPathPatterns("/api/**");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}

// ================================================================
// 全局异常处理
// ================================================================

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ApiResult<Void> handleBusiness(BusinessException e) {
        return ApiResult.error(e.code, e.getMessage());
    }
}
