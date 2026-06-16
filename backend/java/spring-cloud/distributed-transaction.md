# 分布式事务

> Seata 分布式事务、TCC、Saga——微服务中的事务难题。

---

## 1. 为什么需要分布式事务

### 1.1 问题

```java
// 单机事务（本地事务）
@Transactional
public void createOrder(Order order) {
    orderDao.insert(order);              // 本库
    inventoryDao.deduct(productId, qty); // 同库
    // 全部成功或全部回滚 ✅
}

// 微服务事务（跨服务调用）
public void createOrder(Order order) {
    orderService.create(order);          // 订单服务
    inventoryService.deduct(productId);  // 库存服务
    paymentService.pay(order);           // 支付服务
    // 订单成功、库存扣减成功、支付失败 → 数据不一致 ❌
}
```

**分布式事务要解决的问题：** 跨多个数据库/服务的操作要么全部成功，要么全部回滚。

### 1.2 理论：CAP 与 BASE

```
CAP 定理：
  一致性（Consistency）
  可用性（Availability）
  分区容错性（Partition Tolerance）
  三者不可兼得，最多满足两个

BASE 理论（最终一致性）：
  Basically Available（基本可用）
  Soft state（软状态）
  Eventually consistent（最终一致）

在微服务中，我们通常选择 AP + 最终一致性
```

---

## 2. Seata 概述

### 2.1 Seata 角色

```
Transaction Coordinator (TC) — 事务协调器（独立部署）
  │
Transaction Manager (TM) — 事务管理器（嵌入应用）
  │
Resource Manager (RM) — 资源管理器（嵌入应用）
```

### 2.2 启动 Seata

```bash
# Docker 启动 Seata Server
docker run -d \
  --name seata-server \
  -p 8091:8091 \
  -e SEATA_IP=192.168.1.10 \
  -e SEATA_PORT=8091 \
  seataio/seata-server:2.0.0
```

---

## 3. AT 模式（自动事务）

### 3.1 原理

```
AT 模式是 Seata 的核心模式，对代码侵入最小

流程：
1. TM 向 TC 申请开启全局事务 → 获得 XID
2. RM 执行本地事务（INSERT/UPDATE/DELETE）
   - 同时记录"前镜像"和"后镜像"到 undo_log 表
3. TM 向 TC 提交/回滚
   - 提交：删除 undo_log
   - 回滚：根据 undo_log 的前镜像恢复数据
```

### 3.2 实现

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-seata</artifactId>
</dependency>
```

```yaml
# application.yml
seata:
  enabled: true
  application-id: ${spring.application.name}
  tx-service-group: my_tx_group
  registry:
    type: nacos
    nacos:
      server-addr: localhost:8848
      namespace: public
  config:
    type: nacos
    nacos:
      server-addr: localhost:8848
```

```java
// 使用：在入口方法加 @GlobalTransactional
@Service
public class OrderService {

    @GlobalTransactional(name = "create-order", rollbackFor = Exception.class)
    public void createOrder(Order order) {
        // 1. 创建订单（本地事务）
        orderDao.insert(order);

        // 2. 扣减库存（远程调用）
        inventoryService.deduct(order.getProductId(), order.getQuantity());

        // 3. 扣减余额（远程调用）
        accountService.debit(order.getUserId(), order.getAmount());

        // 任何一步失败 → 全部回滚
    }
}
```

### 3.3 AT 模式要求

```sql
-- 每个涉及分布式事务的数据库表必须有 undo_log 表
CREATE TABLE `undo_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `branch_id` BIGINT NOT NULL,
    `xid` VARCHAR(128) NOT NULL,
    `context` VARCHAR(128) NOT NULL,
    `rollback_info` LONGBLOB NOT NULL,
    `log_status` INT NOT NULL,
    `log_created` DATETIME NOT NULL,
    `log_modified` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_unionkey` (`xid`, `branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## 4. TCC 模式

### 4.1 原理

```
TCC = Try + Confirm + Cancel

Try:    预留资源（冻结库存）
Confirm: 确认使用（扣减冻结库存）
Cancel:  取消（释放冻结库存）

示例（库存）：
  Try:     冻结 1 件商品（库存 -1，冻结库存 +1）
  Confirm: 扣减冻结库存（冻结库存 -1）
  Cancel:  释放冻结库存（库存 +1，冻结库存 -1）
```

### 4.2 实现

```java
@LocalTCC
public interface InventoryTccService {

    @TwoPhaseBusinessAction(
        name = "deduct",
        commitMethod = "confirm",
        rollbackMethod = "cancel"
    )
    boolean deduct(
        @BusinessActionContextParameter(paramName = "productId") String productId,
        @BusinessActionContextParameter(paramName = "quantity") int quantity
    );

    boolean confirm(BusinessActionContext context);

    boolean cancel(BusinessActionContext context);
}
```

```java
@Service
public class InventoryTccServiceImpl implements InventoryTccService {

    @Autowired
    private InventoryMapper inventoryMapper;

    @Override
    public boolean deduct(String productId, int quantity) {
        // Try：冻结库存
        inventoryMapper.freezeStock(productId, quantity);
        return true;
    }

    @Override
    public boolean confirm(BusinessActionContext context) {
        // Confirm：确认扣减
        String productId = (String) context.getActionContext("productId");
        int quantity = (int) context.getActionContext("quantity");
        inventoryMapper.confirmDeduct(productId, quantity);
        return true;
    }

    @Override
    public boolean cancel(BusinessActionContext context) {
        // Cancel：释放冻结
        String productId = (String) context.getActionContext("productId");
        int quantity = (int) context.getActionContext("quantity");
        inventoryMapper.cancelDeduct(productId, quantity);
        return true;
    }
}
```

---

## 5. 非 Seata 方案

### 5.1 本地消息表

```
流程：
1. 订单服务：在本地事务中插入订单 + 发送消息记录
2. 定时任务扫描未发送的消息，发送到 MQ
3. 库存服务消费 MQ 消息，扣减库存
4. 库存服务处理成功后，标记消息为已处理

优点：不依赖特定中间件
缺点：需要建消息表、定时任务
```

```java
@Transactional
public void createOrder(Order order) {
    // 1. 插入订单
    orderDao.insert(order);

    // 2. 插入消息记录（同一事务）
    messageDao.insert(new Message("inventory.deduct",
        order.getProductId() + ":" + order.getQuantity()));
}

// 定时任务发送消息
@Scheduled(fixedDelay = 5000)
public void sendPendingMessages() {
    List<Message> messages = messageDao.findPending();
    for (Message msg : messages) {
        try {
            mqSender.send(msg.getTopic(), msg.getPayload());
            messageDao.markSent(msg.getId());
        } catch (Exception e) {
            log.error("发送失败，下次重试", e);
        }
    }
}
```

### 5.2 事务消息（RocketMQ）

```java
// RocketMQ 事务消息
// 1. 发送半消息（prepare）
// 2. 执行本地事务
// 3. 提交/回滚消息
// 4. 如果步骤 2 超时，RocketMQ 回查事务状态

@Transactional
public void createOrder(Order order) {
    // 事务消息 + 本地事务在同一事务中
    rocketMQTemplate.sendMessageInTransaction(
        "order-tx-group",
        "order-create",
        MessageBuilder.withPayload(order).build(),
        order
    );
}
```

### 5.3 Saga 模式

```
适用于长事务、有补偿逻辑的场景

流程：
  1. 订单服务创建订单
  2. 库存服务扣减库存
  3. 支付服务扣款
  4. 如果 3 失败，依次执行补偿：
     - 调用库存服务的补偿（库存回滚）
     - 调用订单服务的补偿（订单取消）

实现方式：
  - 编排模式（Orchestration）：一个协调器负责调度
  -  choreography（ choreography）：各服务通过事件驱动
```

---

## 6. 方案选型

| 方案 | 一致性 | 侵入性 | 性能 | 适用场景 |
|------|--------|--------|------|----------|
| **AT 模式** | 强一致 | 低 | 中 | 通用，推荐首选 |
| **TCC** | 强一致 | 高 | 高 | 对性能要求高 |
| **本地消息表** | 最终一致 | 中 | 高 | 可接受延迟 |
| **事务消息** | 最终一致 | 低 | 高 | 已有 RocketMQ |
| **Saga** | 最终一致 | 中 | 高 | 长事务 |

**建议：**
- 核心链路（订单、支付）→ Seata AT
- 高并发场景 → TCC 或事务消息
- 非核心链路（积分、日志）→ 最终一致性

---

## 7. 🔴 常见坑

```java
// 坑 1：AT 模式不支持所有 SQL
// AT 模式通过解析 SQL 生成镜像
// INSERT/UPDATE/DELETE 支持
// SELECT ... FOR UPDATE 支持
// 复杂的 SQL（如 JOIN 更新、存储过程）不支持

// 坑 2：TCC 的幂等
// Confirm 和 Cancel 可能被调用多次
// 🟢 实现幂等：用状态字段判断

// 坑 3：事务超时
@GlobalTransactional(timeoutMills = 60000)
// 超时后 Seata 会自动回滚
// 但业务可能还在执行，导致数据不一致

// 坑 4：AT 模式的脏写
// 两个全局事务同时修改同一行数据
// 后提交的事务会检测到"前镜像"不一致 → 抛异常
// 🟢 乐观锁 + 重试
```

---

## 8. API 速查

```java
// Seata AT
@GlobalTransactional(name = "biz-name", rollbackFor = Exception.class)
@GlobalTransactional(timeoutMills = 60000)  // 60 秒超时

// 获取当前 XID
String xid = RootContext.getXID();

// 手动绑定 XID
RootContext.bind(xid);

// TCC
@LocalTCC
@TwoPhaseBusinessAction(name = "action", commitMethod = "confirm", rollbackMethod = "cancel")
@BusinessActionContextParameter(paramName = "param")

// 配置
seata:
  enabled: true
  tx-service-group: my_tx_group
  service:
    vgroup-mapping:
      my_tx_group: default
    grouplist:
      default: 127.0.0.1:8091
```
