/**
 * JPA + MyBatis-Plus 示例
 * ========================
 *
 * 演示：
 *   1. JPA 实体映射 + 关联 + N+1 解决
 *   2. MyBatis-Plus CRUD + Wrapper
 *   3. 事务传播行为
 *   4. 乐观锁
 */

package com.example.database;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.*;

// ================================================================
// 1. JPA 实体映射
// ================================================================

// @Entity
// @Table(name = "orders")
class JpaOrder {

    // @Id
    // @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @Column(unique = true, nullable = false, length = 32)
    private String orderNo;

    // @Column(nullable = false)
    private BigDecimal amount;

    // @Enumerated(EnumType.STRING)
    private String status;

    // @ManyToOne(fetch = FetchType.LAZY)
    // @JoinColumn(name = "user_id")
    private JpaUser user;

    // @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<JpaOrderItem> items = new ArrayList<>();

    // @Version  // 乐观锁
    private Long version;

    // @Column(updatable = false)
    private LocalDateTime createdAt;

    // getter/setter ...
}

class JpaUser {
    private Long id;
    private String name;
}

class JpaOrderItem {
    private Long id;
    private JpaOrder order;
    private String productId;
    private int quantity;
}

// ================================================================
// 2. Repository 层
// ================================================================

// public interface OrderRepository extends JpaRepository<JpaOrder, Long> {
//
//     // 方法命名查询
//     List<JpaOrder> findByStatus(String status);
//
//     List<JpaOrder> findByAmountGreaterThanEqual(BigDecimal amount);
//
//     // JOIN FETCH 解决 N+1
//     @Query("SELECT o FROM JpaOrder o JOIN FETCH o.user WHERE o.status = :status")
//     List<JpaOrder> findByStatusWithUser(@Param("status") String status);
//
//     // @EntityGraph
//     @EntityGraph(attributePaths = {"user", "items"})
//     @Query("SELECT o FROM JpaOrder o")
//     List<JpaOrder> findAllWithAll();
//
//     // 更新（@Modifying）
//     @Modifying
//     @Query("UPDATE JpaOrder o SET o.status = :status WHERE o.id = :id")
//     int updateStatus(@Param("id") Long id, @Param("status") String status);
//
//     // 统计
//     long countByStatus(String status);
// }

// ================================================================
// 3. MyBatis-Plus 风格
// ================================================================

// 实体
// @Data
// @TableName("orders")
class MpOrder {
    // @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private BigDecimal amount;
    private String status;

    // @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    // @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // @TableLogic
    private Integer deleted;
}

// Mapper
// public interface MpOrderMapper extends BaseMapper<MpOrder> {
//     // 继承 BaseMapper 后自带 CRUD
//     // insert / deleteById / updateById / selectById ...
//
//     // 自定义复杂查询
//     @Select("SELECT * FROM orders WHERE amount > #{amount}")
//     List<MpOrder> findExpensiveOrders(@Param("amount") BigDecimal amount);
// }

// Service
// @Service
// public class MpOrderService extends ServiceImpl<MpOrderMapper, MpOrder> {
//
//     // Lambda 链式查询
//     public List<MpOrder> findPaidOrders() {
//         return lambdaQuery()
//             .eq(MpOrder::getStatus, "PAID")
//             .ge(MpOrder::getAmount, new BigDecimal("100"))
//             .orderByDesc(MpOrder::getCreatedAt)
//             .list();
//     }
//
//     // 分页
//     public Page<MpOrder> pageQuery(int page, int size) {
//         return lambdaQuery()
//             .page(new Page<>(page, size));
//     }
//
//     // 更新
//     public boolean updateStatus(Long id, String status) {
//         return lambdaUpdate()
//             .eq(MpOrder::getId, id)
//             .set(MpOrder::getStatus, status)
//             .update();
//     }
//
//     // 批量
//     public void batchSave(List<MpOrder> orders) {
//         saveBatch(orders, 1000);
//     }
// }

// ================================================================
// 4. 自动填充
// ================================================================

// @Component
// public class MyMetaObjectHandler implements MetaObjectHandler {
//     @Override
//     public void insertFill(MetaObject metaObject) {
//         this.strictInsertFill(metaObject, "createdAt", LocalDateTime::now, LocalDateTime.class);
//         this.strictInsertFill(metaObject, "updatedAt", LocalDateTime::now, LocalDateTime.class);
//         this.strictInsertFill(metaObject, "deleted", () -> 0, Integer.class);
//     }
//
//     @Override
//     public void updateFill(MetaObject metaObject) {
//         this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime::now, LocalDateTime.class);
//     }
// }

// ================================================================
// 5. Service 层（事务控制）
// ================================================================

// @Service
// @Transactional(rollbackFor = Exception.class)
// public class OrderBizService {
//
//     @Autowired
//     private MpOrderService orderService;
//
//     @Autowired
//     private InventoryService inventoryService;
//
//     /**
//      * 创建订单 + 扣减库存（同一事务）
//      */
//     public void createOrder(MpOrder order, String productId, int quantity) {
//         orderService.save(order);
//         inventoryService.deduct(productId, quantity);
//     }
//
//     /**
//      * 只读事务优化
//      */
//     @Transactional(readOnly = true)
//     public MpOrder getOrder(Long id) {
//         return orderService.getById(id);
//     }
// }

// ================================================================
// 6. 乐观锁重试
// ================================================================

// @Service
// public class ProductService {
//
//     @Retryable(value = OptimisticLockException.class, maxAttempts = 3)
//     public void updateStock(Long productId, int quantity) {
//         Product product = productRepository.findById(productId).orElseThrow();
//         product.setStock(product.getStock() - quantity);
//         // version 字段自动递增
//         // 并发更新时版本号不一致 → OptimisticLockException → 重试
//     }
// }
