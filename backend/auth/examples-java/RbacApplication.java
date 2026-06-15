/**
 * 03 RBAC 权限控制
 * ==================
 *
 * 教学目的：演示基于角色的访问控制（RBAC）完整实现
 *
 * 涵盖知识点：
 *   1. RBAC 四张核心表设计：用户 ↔ 角色 ↔ 权限
 *   2. 角色继承（admin 继承 manager，manager 继承 user）
 *   3. 接口级权限检查（@PreAuthorize）
 *   4. 数据级权限过滤
 *   5. 权限缓存
 *
 * 运行方式：
 *   mvn spring-boot:run -Dspring-boot.run.mainClass=RbacApplication
 *
 * 端口：8083
 */

package com.example.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootApplication
public class RbacApplication {
    public static void main(String[] args) {
        SpringApplication.run(RbacApplication.class, args);
    }
}

// ================================================================
// RBAC 数据模型
// ================================================================

/**
 * 权限
 *
 * 格式: "资源:操作"
 * 例如: order:view, order:create, order:delete
 */
class Permission {
    Long id;
    String code;       // order:view
    String name;       // 查看订单

    public Permission(Long id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
    }
}

/**
 * 角色
 *
 * 一个角色包含多个权限
 * 支持角色继承（如 admin 继承 manager 的权限）
 */
class Role {
    Long id;
    String name;            // admin, manager, user
    Set<String> permissions = new HashSet<>();   // 直接权限
    Set<String> inheritedRoles = new HashSet<>();  // 继承的角色

    public Role(Long id, String name) {
        this.id = id;
        this.name = name;
    }
}

/**
 * 用户
 */
class User {
    Long id;
    String username;
    String passwordHash;
    String role;            // 角色名
    String department;      // 部门（用于数据级权限）

    public User(Long id, String username, String passwordHash, String role, String department) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.department = department;
    }
}

/**
 * 订单（用于演示数据级权限）
 */
class Order {
    Long id;
    String title;
    String ownerUsername;   // 订单所属人
    String department;      // 部门
    double amount;

    public Order(Long id, String title, String ownerUsername, String department, double amount) {
        this.id = id;
        this.title = title;
        this.ownerUsername = ownerUsername;
        this.department = department;
        this.amount = amount;
    }
}

// ================================================================
// RBAC 服务
// ================================================================

/**
 * RBAC 权限服务
 *
 * 核心方法：
 *   hasPermission(user, permissionCode) — 检查用户是否有某权限
 *   filterByDataPermission(user, orders) — 数据级权限过滤
 */
@Service
class RbacService {

    // ============================================================
    // 权限定义
    // ============================================================
    private final Map<String, Permission> allPermissions = new LinkedHashMap<>();

    // ============================================================
    // 角色定义（包含角色继承和权限映射）
    // ============================================================
    private final Map<String, Role> roles = new LinkedHashMap<>();

    public RbacService() {
        initPermissions();
        initRoles();
    }

    private void initPermissions() {
        long id = 1;
        // 订单权限
        addPermission(id++, "order:view", "查看订单");
        addPermission(id++, "order:create", "创建订单");
        addPermission(id++, "order:edit", "编辑订单");
        addPermission(id++, "order:delete", "删除订单");
        addPermission(id++, "order:approve", "审批订单");
        // 用户权限
        addPermission(id++, "user:view", "查看用户");
        addPermission(id++, "user:create", "创建用户");
        addPermission(id++, "user:delete", "删除用户");
        // 报表权限
        addPermission(id++, "report:view", "查看报表");
        addPermission(id++, "report:export", "导出报表");
        // 系统权限
        addPermission(id++, "system:config", "系统配置");
        addPermission(id++, "system:log", "查看日志");
    }

    private void addPermission(long id, String code, String name) {
        allPermissions.put(code, new Permission(id, code, name));
    }

    /**
     * 初始化角色体系
     *
     * 角色继承链：
     *   viewer (只读) → user (基本操作) → manager (管理) → admin (全部)
     *
     * 子角色自动继承父角色的所有权限
     */
    private void initRoles() {
        // 1. viewer — 只读角色
        Role viewer = new Role(1L, "viewer");
        viewer.permissions.addAll(Set.of("order:view", "user:view"));
        roles.put("viewer", viewer);

        // 2. user — 普通用户，继承 viewer
        Role user = new Role(2L, "user");
        user.inheritedRoles.add("viewer");
        user.permissions.addAll(Set.of("order:create", "order:edit"));
        roles.put("user", user);

        // 3. manager — 经理，继承 user
        Role manager = new Role(3L, "manager");
        manager.inheritedRoles.add("user");
        manager.permissions.addAll(Set.of("order:delete", "order:approve",
            "user:create", "report:view", "report:export"));
        roles.put("manager", manager);

        // 4. admin — 管理员，继承 manager，拥有全部权限
        Role admin = new Role(4L, "admin");
        admin.inheritedRoles.add("manager");
        admin.permissions.addAll(Set.of("user:delete", "system:config", "system:log"));
        roles.put("admin", admin);
    }

    /**
     * 获取用户拥有的所有权限（含继承）
     *
     * 递归收集：
     *   1. 用户直接角色的权限
     *   2. 继承角色的权限
     *   3. 继承角色的继承角色的权限...
     */
    public Set<String> getUserPermissions(String roleName) {
        Set<String> result = new HashSet<>();
        collectPermissions(roleName, result, new HashSet<>());
        return result;
    }

    private void collectPermissions(String roleName, Set<String> result, Set<String> visited) {
        if (roleName == null || visited.contains(roleName)) return;
        visited.add(roleName);

        Role role = roles.get(roleName);
        if (role == null) return;

        // 添加直接权限
        result.addAll(role.permissions);

        // 递归收集继承角色的权限
        for (String inherited : role.inheritedRoles) {
            collectPermissions(inherited, result, visited);
        }
    }

    /**
     * 检查用户是否有指定权限
     *
     * 这是鉴权的核心方法
     * 在真实项目中，这里会查缓存（Redis）而不是每次都重新计算
     */
    public boolean hasPermission(String roleName, String permissionCode) {
        return getUserPermissions(roleName).contains(permissionCode);
    }

    // ============================================================
    // 数据级权限过滤
    // ============================================================

    /**
     * 根据数据权限过滤订单列表
     *
     * 不同角色能看到的数据范围不同：
     *   admin:    所有订单
     *   manager:  本部门的订单
     *   user:     自己的订单
     *   viewer:   自己的订单（只读）
     */
    public List<Order> filterOrders(User user, List<Order> orders) {
        return switch (user.role) {
            case "admin" -> orders;                                    // 管理员看所有
            case "manager" -> orders.stream()                          // 经理看本部门
                .filter(o -> o.department.equals(user.department))
                .toList();
            default -> orders.stream()                                // 普通用户看自己的
                .filter(o -> o.ownerUsername.equals(user.username))
                .toList();
        };
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
        users.put("admin", new User(idGen.getAndIncrement(), "admin",
            passwordEncoder.encode("Admin123"), "admin", "技术部"));
        users.put("alice", new User(idGen.getAndIncrement(), "alice",
            passwordEncoder.encode("Hello123"), "manager", "技术部"));
        users.put("bob", new User(idGen.getAndIncrement(), "bob",
            passwordEncoder.encode("Hello123"), "user", "技术部"));
        users.put("charlie", new User(idGen.getAndIncrement(), "charlie",
            passwordEncoder.encode("Hello123"), "user", "市场部"));
        users.put("viewer1", new User(idGen.getAndIncrement(), "viewer1",
            passwordEncoder.encode("Hello123"), "viewer", "技术部"));
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
// 订单服务
// ================================================================

@Service
class OrderService {
    private final List<Order> orders = new ArrayList<>();

    public OrderService() {
        orders.add(new Order(1L, "技术部-订单A", "alice", "技术部", 1000));
        orders.add(new Order(2L, "技术部-订单B", "bob", "技术部", 2000));
        orders.add(new Order(3L, "市场部-订单C", "charlie", "市场部", 3000));
        orders.add(new Order(4L, "技术部-订单D", "alice", "技术部", 4000));
    }

    public List<Order> getAll() {
        return orders;
    }

    public Optional<Order> findById(Long id) {
        return orders.stream().filter(o -> o.id.equals(id)).findFirst();
    }
}

// ================================================================
// 控制器
// ================================================================

@RestController
@RequestMapping("/api")
class RbacController {

    @Autowired
    private UserService userService;

    @Autowired
    private RbacService rbacService;

    @Autowired
    private OrderService orderService;

    @PostMapping("/login")
    public ApiResult<Map<String, Object>> login(@RequestBody LoginRequest req) {
        User user = userService.login(req.username, req.password);
        return ApiResult.success(Map.of(
            "userId", user.id,
            "username", user.username,
            "role", user.role,
            "department", user.department
        ));
    }

    /**
     * 查看订单列表（带数据权限过滤）
     *
     * 不同角色看到的数据范围不同：
     *   admin → 所有订单
     *   manager → 本部门订单
     *   user/viewer → 自己的订单
     */
    @GetMapping("/orders")
    public ApiResult<List<Map<String, Object>>> getOrders(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");

        // 检查是否有查看订单的权限
        if (!rbacService.hasPermission(user.role, "order:view")) {
            return ApiResult.error(403, "权限不足");
        }

        // 数据级权限过滤
        List<Order> filtered = rbacService.filterOrders(user, orderService.getAll());

        return ApiResult.success(filtered.stream().map(o -> Map.of(
            "id", o.id,
            "title", o.title,
            "owner", o.ownerUsername,
            "department", o.department,
            "amount", o.amount
        )).toList());
    }

    /**
     * 删除订单（需 order:delete 权限）
     */
    @DeleteMapping("/orders/{id}")
    public ApiResult<Void> deleteOrder(@PathVariable Long id, HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");

        if (!rbacService.hasPermission(user.role, "order:delete")) {
            return ApiResult.error(403, "权限不足，需要 order:delete 权限");
        }

        Optional<Order> order = orderService.findById(id);
        if (order.isEmpty()) {
            return ApiResult.error(404, "订单不存在");
        }

        // 数据级权限：manager 只能删本部门的订单
        if (user.role.equals("manager") && !order.get().department.equals(user.department)) {
            return ApiResult.error(403, "不能删除其他部门的订单");
        }

        return ApiResult.success(null);
    }

    /**
     * 查看我的权限
     */
    @GetMapping("/my-permissions")
    public ApiResult<Set<String>> getMyPermissions(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        Set<String> permissions = rbacService.getUserPermissions(user.role);
        return ApiResult.success(permissions);
    }
}

// ================================================================
// RBAC 拦截器
// ================================================================

@Component
class RbacInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC_PATHS = Set.of("/api/login");

    @Autowired
    private UserService userService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        if (PUBLIC_PATHS.contains(request.getRequestURI())) {
            return true;
        }

        // 从请求头获取用户名（实际项目中从 JWT 解析）
        String username = request.getHeader("X-Username");
        if (username == null) {
            response.setStatus(401);
            response.getWriter().write("{\"code\":401,\"message\":\"缺少用户标识\"}");
            return false;
        }

        User user = userService.findByUsername(username);
        if (user == null) {
            response.setStatus(401);
            response.getWriter().write("{\"code\":401,\"message\":\"用户不存在\"}");
            return false;
        }

        request.setAttribute("currentUser", user);
        return true;
    }
}

@Configuration
class RbacConfig implements WebMvcConfigurer {
    @Autowired
    private RbacInterceptor rbacInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rbacInterceptor).addPathPatterns("/api/**");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
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
