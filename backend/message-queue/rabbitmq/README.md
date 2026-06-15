# RabbitMQ

> RabbitMQ 是一个开源的消息代理（Message Broker），基于 AMQP 0-9-1 协议实现，支持多种消息协议（AMQP、MQTT、STOMP），以可靠性高、路由灵活、易于运维著称。

---

## 目录

1. [概述](#1-概述)
2. [核心概念](#2-核心概念)
3. [Exchange 类型](#3-exchange-类型)
4. [安装与配置](#4-安装与配置)
5. [消息确认机制](#5-消息确认机制)
6. [常见使用模式](#6-常见使用模式)
7. [Spring Boot 集成](#7-spring-boot-集成)
8. [集群与高可用](#8-集群与高可用)
9. [监控与运维](#9-监控与运维)
10. [最佳实践](#10-最佳实践)
11. [常见问题](#11-常见问题)

---

## 1. 概述

### 1.1 RabbitMQ 的特点

| 特性 | 说明 |
|------|------|
| **可靠性** | 持久化、Confirm、Publisher Confirm、消费确认 |
| **路由灵活** | 多种 Exchange 类型（Direct、Fanout、Topic、Headers） |
| **多协议** | 原生 AMQP 0-9-1，支持 MQTT、STOMP（通过插件） |
| **多语言** | Java、.NET、Node.js、Python、Go 等主流语言 |
| **管理界面** | 内置 Web UI（15672 端口），管理方便 |
| **插件机制** | 延迟消息、Shovel、Federation 等插件生态 |
| **集群** | 原生支持集群、镜像队列、Quorum Queue |
| **轻量** | Erlang 实现，资源占用低，适合中小规模场景 |

### 1.2 适用场景

- 微服务间异步解耦
- 任务队列 / 工作队列
- 日志收集（配合 Fanout Exchange）
- 事件驱动架构
- RPC 调用

---

## 2. 核心概念

```
                     ┌─────────────┐
Producer ──→ Exchange ── Binding ──→ Queue ──→ Consumer
                     │ (路由规则)   │
                     └─────────────┘
```

### 2.1 Producer（生产者）
发送消息的应用程序。

### 2.2 Consumer（消费者）
接收并处理消息的应用程序。

### 2.3 Connection（连接）
一个 TCP 连接。建议应用程序使用长连接，避免频繁创建销毁。

### 2.4 Channel（通道）
在 Connection 内创建的虚拟连接。每个 Channel 代表一个会话，**一个 Connection 可创建多个 Channel**，这是 RabbitMQ 实现多路复用的方式。

```
┌─────────────────────────────────┐
│        TCP Connection           │
│  ┌──────────┐  ┌──────────┐    │
│  │ Channel 1│  │ Channel 2│    │
│  └──────────┘  └──────────┘    │
│  ┌──────────┐  ┌──────────┐    │
│  │ Channel 3│  │ Channel 4│    │
│  └──────────┘  └──────────┘    │
└─────────────────────────────────┘
```

### 2.5 Exchange（交换器）
接收生产者发送的消息，根据路由规则将消息分发到一个或多个队列。

### 2.6 Queue（队列）
存储消息的缓冲区。消息在队列中等待消费者消费。

### 2.7 Binding（绑定）
Exchange 和 Queue 之间的关联关系，定义了路由规则。

### 2.8 Routing Key（路由键）
Exchange 根据 Routing Key 将消息路由到匹配的队列。

### 2.9 Virtual Host（虚拟主机 / vhost）
逻辑隔离单元。一个 RabbitMQ 实例可以有多个 vhost，每个 vhost 拥有独立的 Exchange、Queue、Binding，权限互不干扰。

---

## 3. Exchange 类型

### 3.1 Direct Exchange（直连交换器）

```
Producer ──→ Direct Exchange ──rk="error"──→ Error Queue
                          └──rk="info" ──→ Info Queue
                          └──rk="warn" ──→ Warn Queue
```

- **路由规则**：Routing Key 精确匹配 Binding Key
- **适用**：精准路由，如日志级别分发
- **默认 Exchange**：RabbitMQ 内置一个名为空字符串的 Direct Exchange，所有队列默认绑定到此 Exchange，Routing Key 为队列名

```java
// 声明 Direct Exchange
channel.exchangeDeclare("direct-logs", BuiltinExchangeType.DIRECT);
// 发送消息
channel.basicPublish("direct-logs", "error", null, message.getBytes());
```

### 3.2 Fanout Exchange（扇出交换器）

```
Producer ──→ Fanout Exchange ──→ Queue A
                           └──→ Queue B
                           └──→ Queue C
```

- **路由规则**：忽略 Routing Key，广播到所有绑定的队列
- **适用**：广播场景，如全局通知、日志广播

```java
channel.exchangeDeclare("fanout-logs", BuiltinExchangeType.FANOUT);
channel.basicPublish("fanout-logs", "", null, message.getBytes());
```

### 3.3 Topic Exchange（主题交换器）

```
Producer ──→ Topic Exchange ──binding "*.error"──→ Error Queue
                        └──binding "#.log"  ──→ Log Queue
                        └──binding "auth.*" ──→ Auth Queue
```

- **路由规则**：Routing Key 按通配符匹配 Binding Key
  - `*` 匹配一个单词（以 `.` 分隔）
  - `#` 匹配零个或多个单词
- **适用**：灵活的多维度路由

**示例**：Routing Key 为 `auth.error.login`
- `*.error.*` → ✅ 匹配
- `#.login` → ✅ 匹配
- `auth.*` → ❌ 不匹配（`*` 只匹配一个单词）
- `#` → ✅ 匹配（匹配所有）

```java
channel.exchangeDeclare("topic-logs", BuiltinExchangeType.TOPIC);
channel.basicPublish("topic-logs", "auth.error.login", null, message.getBytes());
```

### 3.4 Headers Exchange（头交换器）

- **路由规则**：不依赖 Routing Key，根据消息 Headers 属性匹配
- **适用**：需要多条件匹配的复杂路由场景
- **匹配方式**：
  - `x-match: all` — 所有 header 都匹配才路由
  - `x-match: any` — 任意一个 header 匹配就路由

```java
Map<String, Object> headers = new HashMap<>();
headers.put("x-match", "all");
headers.put("format", "json");
headers.put("type", "report");
channel.queueBind(queueName, "headers-exchange", "", headers);
```

### 3.5 对比总结

| 类型 | 路由依据 | 通配符 | 性能 | 典型场景 |
|------|----------|--------|------|----------|
| Direct | Routing Key 精确匹配 | 无 | 最高 | 任务分发 |
| Fanout | 无（广播） | 无 | 高 | 广播通知 |
| Topic | Routing Key 通配符 | `*` `#` | 中 | 多维路由 |
| Headers | 消息 Header | 无 | 较低 | 复杂条件路由 |

---

## 4. 安装与配置

### 4.1 Docker 安装（推荐）

```yaml
# docker-compose.yml
version: '3.8'
services:
  rabbitmq:
    image: rabbitmq:3.13-management
    container_name: rabbitmq
    ports:
      - "5672:5672"   # AMQP 协议端口
      - "15672:15672" # 管理界面
      - "1883:1883"   # MQTT 端口（需启用插件）
      - "61613:61613" # STOMP 端口（需启用插件）
    environment:
      RABBITMQ_DEFAULT_USER: admin
      RABBITMQ_DEFAULT_PASS: admin123
      RABBITMQ_DEFAULT_VHOST: /
    volumes:
      - rabbitmq-data:/var/lib/rabbitmq
      - rabbitmq-log:/var/log/rabbitmq
    restart: unless-stopped

volumes:
  rabbitmq-data:
  rabbitmq-log:
```

### 4.2 原生安装（Windows）

```bash
# 需要先安装 Erlang
# 下载地址：https://www.erlang.org/downloads

# 下载 RabbitMQ 安装包
# https://github.com/rabbitmq/rabbitmq-server/releases

# 安装后启用管理插件
rabbitmq-plugins enable rabbitmq_management

# 启动服务（以管理员身份）
net start RabbitMQ
```

### 4.3 启用插件

```bash
# 查看已启用插件
rabbitmq-plugins list

# 启用管理界面
rabbitmq-plugins enable rabbitmq_management

# 启用延迟消息插件
rabbitmq-plugins enable rabbitmq_delayed_message_exchange

# 启用 MQTT
rabbitmq-plugins enable rabbitmq_mqtt

# 启用 STOMP
rabbitmq-plugins enable rabbitmq_stomp

# 启用 Shovel（跨集群消息迁移）
rabbitmq-plugins enable rabbitmq_shovel
rabbitmq-plugins enable rabbitmq_shovel_management
```

### 4.4 管理界面

- **地址**：http://localhost:15672
- **默认账号**：guest / guest（仅限 localhost 登录）
- **功能**：查看队列、Exchange、连接、通道，发送/消费消息，监控指标

---

## 5. 消息确认机制

### 5.1 生产者确认（Publisher Confirm）

保证消息从生产者成功到达 Broker。

```java
// 开启 Confirm 模式
channel.confirmSelect();

// 发送消息
channel.basicPublish("exchange", "routingKey", null, message.getBytes());

// 同步等待确认
if (channel.waitForConfirms()) {
    System.out.println("消息已确认");
} else {
    System.out.println("消息未被确认");
}

// 异步 Confirm（推荐用于高吞吐）
channel.addConfirmListener((deliveryTag, multiple) -> {
    System.out.println("ACK: " + deliveryTag);
}, (deliveryTag, multiple) -> {
    System.out.println("NACK: " + deliveryTag);
});
```

**三种确认模式：**

| 模式 | 说明 | 性能 | 适用 |
|------|------|------|------|
| 同步单条 | 发一条等一条 | 低 | 测试/低吞吐 |
| 同步批量 | 批量发送后统一确认 | 中 | 批量任务 |
| 异步监听 | 回调方式处理确认 | 高 | 生产环境推荐 |

### 5.2 消费者确认（Consumer Ack）

保证消息被消费者成功处理。

```java
// 关闭自动 Ack
boolean autoAck = false;
channel.basicConsume(queueName, autoAck, consumer);

// 处理成功后手动确认
channel.basicAck(envelope.getDeliveryTag(), false);

// 处理失败 - 重新入队
channel.basicNack(envelope.getDeliveryTag(), false, true);

// 处理失败 - 丢弃/进入死信
channel.basicNack(envelope.getDeliveryTag(), false, false);
// 或
channel.basicReject(envelope.getDeliveryTag(), false);
```

| 方法 | 说明 | requeue=false 时 |
|------|------|-----------------|
| `basicAck(tag, multiple)` | 确认消息处理成功 | — |
| `basicNack(tag, multiple, requeue)` | 拒绝消息，可批量 | 进入 DLQ 或丢弃 |
| `basicReject(tag, requeue)` | 拒绝单条消息 | 进入 DLQ 或丢弃 |

### 5.3 Prefetch（QoS）

控制消费者预取消息的数量，防止消费者内存暴涨。

```java
// 每次只推送 1 条消息，处理完再推送下一条
channel.basicQos(1);

// 按消息总大小限制（单位：字节）
channel.basicQos(0, 1024 * 100, false);

// 参数：prefetchSize, prefetchCount, global
// global=true 对 Connection 内所有 Channel 生效
// global=false 仅对当前 Channel 生效
```

**Prefetch 设置建议：**
- **自动 Ack**：Prefetch 不生效
- **手动 Ack + 耗时短的任务**：Prefetch 可设大一些（50~300）
- **手动 Ack + 耗时长/IO 密集型**：Prefetch 设小（1~10）
- **不同消费者的消费速度差异大**：Prefetch 设小，避免积压

---

## 6. 常见使用模式

### 6.1 工作队列（Work Queue）

```
                    ┌── Worker 1
Producer ──→ Queue ── Worker 2
                    └── Worker 3
```

```java
// 生产者
channel.queueDeclare("task_queue", true, false, false, null);
channel.basicPublish("", "task_queue",
    MessageProperties.PERSISTENT_TEXT_PLAIN,
    message.getBytes());

// 消费者
channel.basicQos(1); // 公平分发
channel.basicConsume("task_queue", false, consumer);
```

**关键点：**
- 队列持久化 + 消息持久化
- `basicQos(1)` 公平分发
- 手动 Ack

### 6.2 发布/订阅（Pub/Sub）

```
Producer ──→ Fanout Exchange ──→ Queue A ──→ Consumer A
                            └── Queue B ──→ Consumer B
```

```java
// 生产者
channel.exchangeDeclare("logs", BuiltinExchangeType.FANOUT);
channel.basicPublish("logs", "", null, message.getBytes());

// 消费者 - 每个消费者创建临时队列
String queueName = channel.queueDeclare().getQueue();
channel.queueBind(queueName, "logs", "");
```

### 6.3 路由模式（Routing）

```
Producer ──→ Direct Exchange ──rk="error"──→ Error Queue
                          └──rk="warn" ──→ Warn Queue
```

```java
// 生产者
channel.basicPublish("direct-logs", severity, null, message.getBytes());

// 消费者
channel.queueBind(queueName, "direct-logs", "error");
channel.queueBind(queueName, "direct-logs", "warn");
```

### 6.4 RPC（请求/应答）

```
Client ──→ Request Queue ──→ Server
   ←── Callback Queue ←──────
```

```java
// 客户端设置回调队列
String corrId = UUID.randomUUID().toString();
String replyQueue = channel.queueDeclare().getQueue();

AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
    .correlationId(corrId)
    .replyTo(replyQueue)
    .build();

channel.basicPublish("", "rpc_queue", props, message.getBytes());
```

### 6.5 延迟队列（Delayed Message）

```java
// 方式一：通过 DLX + TTL 实现（无需插件）
Map<String, Object> args = new HashMap<>();
args.put("x-dead-letter-exchange", "delayed-exchange");
args.put("x-message-ttl", 60000); // 60 秒
channel.queueDeclare("delayed-queue", true, false, false, args);

// 方式二：延迟消息插件（rabbitmq_delayed_message_exchange）
Map<String, Object> args = new HashMap<>();
args.put("x-delayed-type", "direct");
channel.exchangeDeclare("delayed-exchange",
    "x-delayed-message", true, false, args);

// 发送延迟消息
AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
    .headers(Map.of("x-delay", 60000)) // 延迟 60 秒
    .build();
channel.basicPublish("delayed-exchange", "routingKey", props, message.getBytes());
```

---

## 7. Spring Boot 集成

### 7.1 依赖

```gradle
implementation 'org.springframework.boot:spring-boot-starter-amqp'
```

### 7.2 配置

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: admin
    password: admin123
    virtual-host: /
    # 生产者确认
    publisher-confirm-type: correlated  # CORRELATED / SIMPLE / NONE
    publisher-returns: true
    # 消费者
    listener:
      simple:
        acknowledge-mode: manual      # 手动 Ack
        prefetch: 1
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000ms
          multiplier: 2.0
          max-interval: 10000ms
```

### 7.3 配置类

```java
@Configuration
public class RabbitConfig {

    // 声明队列
    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable("order.queue")
            .deadLetterExchange("order.dlx.exchange")
            .deadLetterRoutingKey("order.dlx")
            .ttl(30000)
            .maxLength(100000)
            .build();
    }

    // 声明 Direct Exchange
    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange("order.exchange");
    }

    // 绑定
    @Bean
    public Binding orderBinding(Queue orderQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(orderQueue)
            .to(orderExchange)
            .with("order.create");
    }

    // 死信队列
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable("order.dlq").build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange("order.dlx.exchange");
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
            .to(deadLetterExchange())
            .with("order.dlx");
    }
}
```

### 7.4 生产者

```java
@Component
@Slf4j
public class OrderProducer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // 发送消息
    public void sendOrder(Order order) {
        // Confirm 回调
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                log.info("消息发送成功: {}", correlationData.getId());
            } else {
                log.error("消息发送失败: {}, cause: {}", correlationData.getId(), cause);
            }
        });

        // Return 回调（消息无法路由时触发）
        rabbitTemplate.setReturnsCallback(returned -> {
            log.warn("消息无法路由: exchange={}, routingKey={}, replyText={}",
                returned.getExchange(), returned.getRoutingKey(),
                returned.getReplyText());
        });

        CorrelationData cd = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend("order.exchange", "order.create", order, cd);
    }
}
```

### 7.5 消费者

```java
@Component
@Slf4j
public class OrderConsumer {

    @RabbitListener(queues = "order.queue")
    public void handleOrder(Order order, Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            log.info("收到订单: {}", order);
            processOrder(order);
            // 手动确认
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("订单处理失败", e);
            // 拒绝并重新入队（或进入死信）
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
```

### 7.6 重试配置

```java
// 配置 RetryTemplate
@Bean
public RetryTemplate retryTemplate() {
    RetryTemplate retry = new RetryTemplate();
    // 指数退避
    ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
    backOff.setInitialInterval(1000);
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(10000);
    retry.setBackOffPolicy(backOff);
    // 重试 3 次
    retry.setRetryOperations(new SimpleRetryOperations(3));
    return retry;
}
```

---

## 8. 集群与高可用

### 8.1 普通集群（Clustering）

```
┌─────────┐    ┌─────────┐    ┌─────────┐
│ Node 1  │◄──►│ Node 2  │◄──►│ Node 3  │
│ (Disk)  │    │ (Disk)  │    │ (RAM)   │
└─────────┘    └─────────┘    └─────────┘
     │              │              │
  Queue A        Queue A        Queue A
  (Master)      (Mirror)       (Mirror)
```

**特点：**
- 节点间共享 Exchange、Binding、用户等元数据
- 队列数据只存在于一个节点（主节点），其他节点只有元数据
- 主节点宕机 → 队列不可用（除非使用镜像队列）

### 8.2 镜像队列（Mirrored Queues）

```bash
# 通过策略配置镜像队列
rabbitmqctl set_policy ha-all "^ha\." '{"ha-mode":"all"}'

# 指定节点数量
rabbitmqctl set_policy ha-two "^ha\." '{"ha-mode":"exactly","ha-params":2}'
```

**策略参数：**

| 参数 | 值 | 说明 |
|------|-----|------|
| `ha-mode` | `all` / `exactly` / `nodes` | 镜像模式 |
| `ha-params` | 数字 / 节点列表 | 镜像数量或节点 |
| `ha-sync-mode` | `automatic` / `manual` | 同步模式 |

### 8.3 Quorum Queue（仲裁队列，推荐）

RabbitMQ 3.8+ 引入，替代镜像队列的现代方案。

```java
// 声明仲裁队列
Map<String, Object> args = new HashMap<>();
args.put("x-queue-type", "quorum");
channel.queueDeclare("quorum-queue", true, false, false, args);
```

**仲裁队列特性：**
- 基于 Raft 协议，自动选主
- 数据安全（写入多数节点才确认）
- 自动故障转移
- 支持 FIFO 顺序
- **不支持**：临时队列、独占队列、全局 QoS

### 8.4 Docker Compose 集群示例

```yaml
version: '3.8'
services:
  rabbitmq-1:
    image: rabbitmq:3.13-management
    hostname: rabbitmq-1
    environment:
      RABBITMQ_ERLANG_COOKIE: SECRET_COOKIE
      RABBITMQ_NODENAME: rabbit@rabbitmq-1
    ports:
      - "5672:5672"
      - "15672:15672"
    volumes:
      - rabbitmq-1-data:/var/lib/rabbitmq

  rabbitmq-2:
    image: rabbitmq:3.13-management
    hostname: rabbitmq-2
    environment:
      RABBITMQ_ERLANG_COOKIE: SECRET_COOKIE
      RABBITMQ_NODENAME: rabbit@rabbitmq-2
    depends_on:
      - rabbitmq-1
    ports:
      - "5673:5672"
      - "15673:15672"
    volumes:
      - rabbitmq-2-data:/var/lib/rabbitmq

  rabbitmq-3:
    image: rabbitmq:3.13-management
    hostname: rabbitmq-3
    environment:
      RABBITMQ_ERLANG_COOKIE: SECRET_COOKIE
      RABBITMQ_NODENAME: rabbit@rabbitmq-3
    depends_on:
      - rabbitmq-1
    ports:
      - "5674:5672"
      - "15674:15672"
    volumes:
      - rabbitmq-3-data:/var/lib/rabbitmq

volumes:
  rabbitmq-1-data:
  rabbitmq-2-data:
  rabbitmq-3-data:
```

```bash
# 加入集群
docker exec rabbitmq-2 rabbitmqctl stop_app
docker exec rabbitmq-2 rabbitmqctl join_cluster rabbit@rabbitmq-1
docker exec rabbitmq-2 rabbitmqctl start_app

docker exec rabbitmq-3 rabbitmqctl stop_app
docker exec rabbitmq-3 rabbitmqctl join_cluster rabbit@rabbitmq-1
docker exec rabbitmq-3 rabbitmqctl start_app

# 查看集群状态
docker exec rabbitmq-1 rabbitmqctl cluster_status

# 设置镜像策略
docker exec rabbitmq-1 rabbitmqctl set_policy ha-all "^" '{"ha-mode":"all","ha-sync-mode":"automatic"}'
```

### 8.5 Federation（联邦）

用于跨数据中心、跨网络的消息复制：

```bash
# 启用 Federation 插件
rabbitmq-plugins enable rabbitmq_federation
rabbitmq-plugins enable rabbitmq_federation_management

# 配置上游（Web UI 或 CLI）
rabbitmqctl set_parameter federation-upstream my-upstream \
  '{"uri":"amqp://user:pass@remote-host:5672","max-hops":1}'

# 配置策略
rabbitmqctl set_policy federate-me "^federated\." \
  '{"federation-upstream-set":"all"}'
```

---

## 9. 监控与运维

### 9.1 关键监控指标

| 指标 | 说明 | 告警阈值 |
|------|------|----------|
| **队列深度（Ready）** | 待消费消息数 | > 1000 |
| **未确认消息（Unacked）** | 已投递但未确认的消息 | > 100 |
| **消费速率** | 每秒消费消息数 | 突降 50% |
| **生产速率** | 每秒生产消息数 | 突增 50% |
| **连接数** | TCP 连接数 | > 500 |
| **通道数** | 总通道数 | > 1000 |
| **内存使用** | Erlang VM 内存 | > 80% |
| **磁盘空间** | 消息持久化磁盘 | > 70% |
| **文件描述符** | 已打开文件数 | > 80% |
| **GC 时间** | 垃圾回收耗时 | 持续升高 |

### 9.2 常用运维命令

```bash
# 查看状态
rabbitmqctl status
rabbitmqctl cluster_status
rabbitmqctl list_queues
rabbitmqctl list_connections
rabbitmqctl list_channels

# 查看队列详情
rabbitmqctl list_queues name messages consumers memory

# 查看 Exchange
rabbitmqctl list_exchanges name type durable

# 查看绑定
rabbitmqctl list_bindings source_name destination_name routing_key

# 用户管理
rabbitmqctl add_user admin admin123
rabbitmqctl set_user_tags admin administrator
rabbitmqctl set_permissions -p / admin ".*" ".*" ".*"

# 策略管理
rabbitmqctl set_policy ha-all "^" '{"ha-mode":"all"}'
rabbitmqctl clear_policy ha-all
rabbitmqctl list_policies

# 清空队列
rabbitmqctl purge_queue queue_name

# 删除队列
rabbitmqctl delete_queue queue_name
```

### 9.3 管理 API（HTTP）

```bash
# 获取所有队列信息
curl -u admin:admin123 http://localhost:15672/api/queues

# 获取指定队列
curl -u admin:admin123 http://localhost:15672/api/queues/%2F/my-queue

# 获取连接
curl -u admin:admin123 http://localhost:15672/api/connections

# 获取节点信息
curl -u admin:admin123 http://localhost:15672/api/nodes
```

### 9.4 性能调优

```bash
# 设置内存上限（默认为 RAM 的 40%）
rabbitmqctl set_vm_memory_high_watermark 0.6

# 设置磁盘可用空间限制（剩余少于 1GB 时阻止生产）
rabbitmqctl set_disk_free_limit 1GB

# 设置心跳超时（秒）
rabbitmqctl eval 'application:set_env(rabbit, heartbeat, 60).'
```

---

## 10. 最佳实践

### 10.1 架构设计

| 实践 | 说明 |
|------|------|
| **使用 vhost 隔离环境** | 不同环境（dev/staging/prod）用不同 vhost |
| **合理命名规范** | `业务.场景.类型`，如 `order.create.queue` |
| **Exchange + Binding 解耦** | 生产者只发 Exchange，消费者自己 Bind |
| **优先 Topic Exchange** | 灵活度高，便于后续扩展 |
| **消息体不要过大** | 建议 < 64KB，大消息走对象存储 + 引用 |

### 10.2 可靠性

| 实践 | 说明 |
|------|------|
| **队列持久化** | `durable=true` |
| **消息持久化** | `MessageProperties.PERSISTENT_TEXT_PLAIN` |
| **生产者 Confirm** | 生产环境必须开启 |
| **消费者手动 Ack** | 处理完成再确认 |
| **死信队列** | 每个重要业务队列配置 DLQ |
| **幂等消费** | 消息 ID + 状态表去重 |

### 10.3 性能

| 实践 | 说明 |
|------|------|
| **合理设置 Prefetch** | 根据消费耗时调整 |
| **批量发送** | 减少网络交互 |
| **消息压缩** | 大消息用 gzip / snappy |
| **连接复用** | 一个 Connection 多 Channel |
| **使用 lazy 队列** | 大积压场景减少内存压力 |

```java
// 声明 Lazy 队列（消息尽量存磁盘）
Map<String, Object> args = new HashMap<>();
args.put("x-queue-mode", "lazy");
channel.queueDeclare("lazy-queue", true, false, false, args);
```

### 10.4 安全

| 实践 | 说明 |
|------|------|
| **修改默认密码** | 删除 guest 或限制登录来源 |
| **TLS 加密** | 生产环境使用 SSL/TLS |
| **最小权限原则** | 按需配置 vhost 权限 |
| **防火墙限制端口** | 只开放必要端口给信任 IP |
| **日志审计** | 开启连接和操作日志 |

### 10.5 版本建议

| 版本 | 状态 | 说明 |
|------|------|------|
| 3.13.x | 最新稳定 | 推荐新项目使用 |
| 3.12.x | 维护中 | 已有项目建议升级 |
| 3.8.x | 已 EOL | 建议迁移 |
| < 3.7 | 已停止 | 必须升级 |

---

## 11. 常见问题

### 11.1 消息丢失

```
症状：消费者没收到消息，业务数据不一致
原因：
  - 生产者未开启 Confirm
  - 队列/消息未持久化
  - 消费者自动 Ack，处理异常时消息已确认
  - Exchange 路由不到队列（无绑定）
  
解决方案：
  - 开启 Confirm + 持久化 + 手动 Ack
  - 配置 Mandatory / Return Callback
```

### 11.2 消息堆积

```
症状：队列深度持续增长，消费延迟增大
原因：
  - 消费者处理速度慢
  - Prefetch 设置过大，消息堆积在消费者内存
  - 消费者异常（卡死、OOM、死循环）
  - 消费者数量不足
  
解决方案：
  - 增加消费者数量
  - 优化消费逻辑
  - 检查 Prefetch 设置
  - 使用 Lazy 队列减少内存压力
```

### 11.3 重复消费

```
症状：同一条消息被处理多次
原因：
  - 消费者处理超时，RabbitMQ 重新投递
  - 消费者处理成功但 Ack 未到达 Broker
  - 网络闪断导致连接重建
  
解决方案：
  - 消费端实现幂等（业务键 + 去重表）
  - 调整心跳超时时间
```

### 11.4 连接断开

```
症状：消费者频繁断开重连
原因：
  - 心跳超时
  - 网络不稳定
  - 消费者长时间不活跃
  
解决方案：
  - 配置合理的心跳间隔（30~60 秒）
  - 客户端实现自动重连
  - 检查网络
```

### 11.5 内存告警

```
症状：生产者被阻塞（Blocked），无法发送消息
原因：
  - 消息堆积严重
  - 队列未设置 max-length
  - 内存上限配置太低
  
解决方案：
  - 设置队列 max-length / max-length-bytes
  - 提高内存上限
  - 使用 Lazy 队列
```

---

## 参考资源

- [RabbitMQ 官方文档](https://www.rabbitmq.com/documentation.html)
- [RabbitMQ Java 客户端](https://www.rabbitmq.com/client-libraries/java-api-guide)
- [Spring AMQP 官方文档](https://docs.spring.io/spring-amqp/reference/)
- [RabbitMQ 最佳实践](https://www.rabbitmq.com/topics/production-checklist)
- [RabbitMQ 模式指南](https://www.rabbitmq.com/getstarted.html)
