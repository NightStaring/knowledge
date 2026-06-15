# Apache Kafka

> Apache Kafka 是一个分布式流处理平台，由 LinkedIn 开发并于 2011 年开源。它以高吞吐、可持久化、可水平扩展著称，广泛应用于日志收集、事件流、数据管道和流式处理场景。

---

## 目录

1. [概述](#1-概述)
2. [核心概念](#2-核心概念)
3. [架构设计](#3-架构设计)
4. [安装与配置](#4-安装与配置)
5. [生产者](#5-生产者)
6. [消费者](#6-消费者)
7. [主题与分区](#7-主题与分区)
8. [消息可靠性](#8-消息可靠性)
9. [Kafka Streams](#9-kafka-streams)
10. [Spring Boot 集成](#10-spring-boot-集成)
11. [集群与运维](#11-集群与运维)
12. [监控](#12-监控)
13. [最佳实践](#13-最佳实践)
14. [常见问题](#14-常见问题)

---

## 1. 概述

### 1.1 什么是 Kafka

Kafka 是一个**分布式、持久化、多副本**的流式消息平台，设计目标：

- **高吞吐**：单机可达每秒百万级消息
- **可持久化**：消息持久化到磁盘，支持回溯消费
- **水平扩展**：通过增加分区和 Broker 线性扩展
- **容错性**：多副本机制，节点故障不影响服务

### 1.2 Kafka 与 RabbitMQ 的定位差异

| 维度 | Kafka | RabbitMQ |
|------|-------|----------|
| **设计哲学** | 分布式日志/流 | 通用消息代理 |
| **吞吐量** | 百万级/秒 | 万级/秒 |
| **消息模型** | Pull（消费者主动拉取） | Push + Pull（Broker 推送/消费者拉取） |
| **顺序保证** | 分区内有序 | 单队列有序 |
| **消息删除** | 基于时间/大小策略 | 消费后删除 |
| **回溯消费** | 天然支持（Offset） | 有限支持 |
| **路由灵活性** | 弱（基于 Topic） | 强（多种 Exchange） |
| **典型场景** | 日志、事件流、数据管道 | 任务队列、微服务解耦 |

### 1.3 核心应用场景

- **日志收集**：统一收集各服务的日志
- **事件流**：用户行为追踪、点击流
- **数据管道**：数据库 CDC（变更数据捕获）、ETL
- **指标监控**：聚合系统指标和告警
- **流式处理**：实时计算、聚合、Join
- **事件溯源**：Event Sourcing / CQRS

---

## 2. 核心概念

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ Producer │───→│  Broker  │───→│  Topic   │───→│ Consumer │
│ (生产者)  │    │  (代理)   │    │  (主题)   │    │ (消费者)  │
└──────────┘    └──────────┘    └──────────┘    └──────────┘
                                      │
                                 ┌────┴────┐
                                 │ Partition│
                                 │ (分区)   │
                                 └─────────┘
```

### 2.1 Broker（代理节点）

Kafka 集群中的每一台服务器称为一个 Broker。一个集群由多个 Broker 组成，每个 Broker 有一个唯一 ID。

### 2.2 Topic（主题）

消息的逻辑分类。生产者发送消息到 Topic，消费者订阅 Topic 消费消息。

### 2.3 Partition（分区）

Topic 的物理分片，是实现并行和扩展的核心机制：

```
Topic: "orders"
├── Partition 0 (Broker 1)
├── Partition 1 (Broker 2)
├── Partition 2 (Broker 3)
└── Partition 3 (Broker 1)  ← 同一个 Broker 可以有多个分区
```

**分区特性：**
- 每个分区是一个有序的、不可变的日志（Log）
- 分区内消息顺序保证，分区之间不保证
- 每个分区可以在不同的 Broker 上，实现并行读写
- 分区数量决定最大并行度

### 2.4 Offset（偏移量）

分区内每条消息的唯一序列号，从 0 开始递增。消费者通过 Offset 记录消费位置。

```
Partition 0:
┌──────┬──────┬──────┬──────┬──────┬──────┐
│ msg0 │ msg1 │ msg2 │ msg3 │ msg4 │ msg5 │ ...
├──────┼──────┼──────┼──────┼──────┼──────┤
│ off0 │ off1 │ off2 │ off3 │ off4 │ off5 │
└──────┴──────┴──────┴──────┴──────┴──────┘
                                   ↑
                              Consumer Offset=5
```

### 2.5 Consumer Group（消费者组）

一组消费者共同消费一个或多个 Topic：

```
                  ┌──────────────┐
Topic: "orders"   │  Consumer    │
├── Partition 0───┤  Group "g1"  │
├── Partition 1───┤├── Consumer A│
├── Partition 2───┤├── Consumer B│
└── Partition 3───┤└── Consumer C│
                  └──────────────┘
```

- 组内每个分区只被一个消费者消费（一对一映射）
- 组间独立，一条消息可以被多个组消费
- 消费者数不能超过分区数，否则有消费者闲置

### 2.6 Replica（副本）

每个分区有多个副本，分布在不同的 Broker 上：

```
Partition 0:
├── Leader (Broker 1)  ← 读写入口
├── Follower (Broker 2) ← 同步复制
└── Follower (Broker 3) ← 同步复制
```

- **Leader**：处理所有读写请求
- **Follower**：从 Leader 同步数据，Leader 宕机时接替
- **ISR（In-Sync Replica）**：与 Leader 保持同步的副本集合

### 2.7 Record（消息记录）

Kafka 消息的基本单元：

```json
{
  "key": "user-123",
  "value": "{\"name\": \"Alice\", \"action\": \"login\"}",
  "timestamp": 1700000000000,
  "headers": {
    "trace-id": "abc-123",
    "version": "1.0"
  }
}
```

- **Key**：决定消息写入哪个分区（相同 Key → 相同分区 → 有序）
- **Value**：消息体
- **Timestamp**：消息时间戳
- **Headers**：可选元数据

---

## 3. 架构设计

### 3.1 整体架构

```
                     ┌──────────────────────┐
                     │      ZooKeeper        │
                     │  (元数据 / 协调服务)    │
                     └──────────┬───────────┘
                                │
┌──────────┐    ┌───────────────┴───────────────┐    ┌──────────┐
│ Producer │───→│         Kafka Cluster         │───→│ Consumer │
│          │    │  ┌──────┐ ┌──────┐ ┌──────┐  │    │ (Group)  │
│  App A   │    │  │Broker│ │Broker│ │Broker│  │    │  App B   │
│          │    │  │   1  │ │   2  │ │   3  │  │    │          │
└──────────┘    │  └──────┘ └──────┘ └──────┘  │    └──────────┘
                └───────────────────────────────┘
```

**Kafka 3.x 后（KRaft 模式）：**

```
┌──────────────────────────────────┐
│         Kafka Cluster            │
│  ┌──────┐ ┌──────┐ ┌──────┐     │
│  │Broker│ │Broker│ │Broker│     │
│  │Contr.│ │      │ │      │     │
│  │  (1) │ │  (2) │ │  (3) │     │
│  └──────┘ └──────┘ └──────┘     │
│  (内置 Controller - Raft)        │
└──────────────────────────────────┘
```

Kafka 3.x 开始支持 **KRaft 模式**（Kafka Raft Metadata），逐步替代 ZooKeeper 的依赖。

### 3.2 分区 Leader 选举

```
Broker 1 (Controller)     Broker 2          Broker 3
┌─────────────────┐    ┌────────────────┐  ┌────────────────┐
│ Partition 0     │    │ Partition 0    │  │ Partition 0    │
│   Leader        │    │   Follower     │  │   Follower     │
│ Partition 1     │    │ Partition 1    │  │ Partition 1    │
│   Follower      │    │   Leader       │  │   Follower     │
└─────────────────┘    └────────────────┘  └────────────────┘
```

- **Controller**：集群中一个 Broker 担任，负责管理分区 Leader 选举
- 当 Leader 宕机时，Controller 从 ISR 中选择一个新的 Leader
- 优先选择 ISR 中保持同步的副本

### 3.3 日志存储结构

```
/tmp/kafka-logs/
├── orders-0/                  ← Topic "orders", Partition 0
│   ├── 00000000000000000000.log   ← 消息数据文件
│   ├── 00000000000000000000.index ← 偏移量索引
│   ├── 00000000000000000000.timeindex ← 时间戳索引
│   └── leader-epoch-checkpoint
├── orders-1/
│   └── ...
└── __consumer_offsets/        ← 消费者偏移量内部主题
    └── ...
```

**Segment（段）**：每个分区由多个 Segment 组成，每个 Segment 包含：
- `.log` — 消息数据
- `.index` — 偏移量到物理位置的映射
- `.timeindex` — 时间戳到偏移量的映射

Segment 达到一定大小（默认 1GB）或时间（默认 7 天）后滚动新文件。

---

## 4. 安装与配置

### 4.1 Docker 安装（推荐）

```yaml
# docker-compose.yml - KRaft 模式（无需 ZooKeeper）
version: '3.8'
services:
  kafka:
    image: confluentinc/cp-kafka:7.7.0
    container_name: kafka
    hostname: kafka
    ports:
      - "9092:9092"
      - "9101:9101"  # JMX 端口
    environment:
      CLUSTER_ID: "MkU3OEVBNTcwNTJENDM2Qk"
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@localhost:9093"
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_LOG_RETENTION_HOURS: 168
      KAFKA_LOG_SEGMENT_BYTES: 1073741824
      KAFKA_NUM_PARTITIONS: 3
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    volumes:
      - kafka-data:/var/lib/kafka/data
    restart: unless-stopped

volumes:
  kafka-data:
```

```yaml
# docker-compose.yml - ZooKeeper 模式（传统）
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.7.0
    container_name: zookeeper
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000

  kafka:
    image: confluentinc/cp-kafka:7.7.0
    container_name: kafka
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_LOG_RETENTION_HOURS: 168
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    volumes:
      - kafka-data:/var/lib/kafka/data

volumes:
  kafka-data:
```

### 4.2 基本操作命令

```bash
# 进入容器
docker exec -it kafka bash

# 创建主题
kafka-topics --create \
  --topic my-topic \
  --partitions 3 \
  --replication-factor 1 \
  --bootstrap-server localhost:9092

# 查看所有主题
kafka-topics --list --bootstrap-server localhost:9092

# 查看主题详情
kafka-topics --describe --topic my-topic --bootstrap-server localhost:9092

# 发送消息（控制台生产者）
kafka-console-producer --topic my-topic --bootstrap-server localhost:9092

# 消费消息（从最新开始）
kafka-console-consumer --topic my-topic --bootstrap-server localhost:9092

# 从头开始消费
kafka-console-consumer --topic my-topic --bootstrap-server localhost:9092 \
  --from-beginning

# 查看消费者组
kafka-consumer-groups --list --bootstrap-server localhost:9092

# 查看消费者组详情
kafka-consumer-groups --describe --group my-group \
  --bootstrap-server localhost:9092

# 重置消费者 Offset
kafka-consumer-groups --reset-offsets --group my-group \
  --topic my-topic --to-earliest --execute \
  --bootstrap-server localhost:9092

# 修改分区数
kafka-topics --alter --topic my-topic --partitions 6 \
  --bootstrap-server localhost:9092

# 查看分区水位（High Watermark）
kafka-run-class kafka.tools.GetOffsetShell \
  --topic my-topic --broker-list localhost:9092 --time -1
```

---

## 5. 生产者

### 5.1 生产者核心流程

```
Producer
┌──────────────────────────────────────┐
│  1. ProducerRecord (Topic, Key, Val) │
│                  │                   │
│  2. Serializer (Key/Value)          │
│                  │                   │
│  3. Partitioner (决定写入哪个分区)    │
│                  │                   │
│  4. RecordAccumulator (批量缓存)     │
│                  │                   │
│  5. Sender (后台发送线程)            │
│                  │                   │
│  6. Network Client (发送到 Broker)   │
└──────────────────────────────────────┘
                    │
               Kafka Cluster
```

### 5.2 Java 生产者示例

```java
// 生产者配置
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
    StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
    StringSerializer.class.getName());

// 可靠性配置
props.put(ProducerConfig.ACKS_CONFIG, "all");           // 等待所有副本确认
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // 幂等生产者
props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

// 性能配置
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);     // 16KB 批量
props.put(ProducerConfig.LINGER_MS_CONFIG, 5);          // 延迟 5ms 发送
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy"); // 压缩
props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);    // 32MB 缓冲区

// 重试配置
props.put(ProducerConfig.RETRIES_CONFIG, 3);
props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 1000);

KafkaProducer<String, String> producer = new KafkaProducer<>(props);

// 发送消息（同步）
ProducerRecord<String, String> record =
    new ProducerRecord<>("orders", "user-123", "{\"orderId\":\"ord-001\"}");
producer.send(record).get(); // 同步等待

// 发送消息（异步 + 回调）
producer.send(record, (metadata, exception) -> {
    if (exception == null) {
        System.out.printf("发送成功: topic=%s, partition=%d, offset=%d%n",
            metadata.topic(), metadata.partition(), metadata.offset());
    } else {
        System.err.println("发送失败: " + exception.getMessage());
    }
});

// 批量发送（自动）
for (int i = 0; i < 1000; i++) {
    producer.send(new ProducerRecord<>("orders", "key-" + i, "value-" + i));
}
// 刷新并关闭
producer.flush();
producer.close();
```

### 5.3 关键参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `acks` | `1` | `0`=不等待确认，`1`=Leader 确认，`all`=所有副本确认 |
| `enable.idempotence` | `false` | 幂等生产者，防止重复 |
| `batch.size` | `16384` | 批次大小（字节），增大提升吞吐 |
| `linger.ms` | `0` | 批次发送前等待时间，适当增大提升批处理率 |
| `compression.type` | `none` | `gzip`/`snappy`/`lz4`/`zstd`，压缩减小网络开销 |
| `max.in.flight.requests.per.connection` | `5` | 未确认请求数，=1 可严格有序 |
| `retries` | `2147483647` | 重试次数（幂等模式下默认为无限） |
| `buffer.memory` | `33554432` | 生产者缓冲区大小 |
| `max.request.size` | `1048576` | 最大请求大小（1MB） |

### 5.4 分区策略

```java
// 1. 默认分区器 - 按 Key 哈希
// 相同 Key → 相同分区（有序）
ProducerRecord<>("orders", "user-123", value);

// 2. 指定分区
ProducerRecord<>("orders", null, null, "user-123", value);

// 3. 无 Key - 轮询（Round-Robin）
ProducerRecord<>("orders", value);  // key=null

// 4. 自定义分区器
props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG,
    MyCustomPartitioner.class.getName());
```

```java
// 自定义分区器示例
public class OrderPartitioner implements Partitioner {
    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {
        List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
        // 按订单类型分发到不同分区
        if (key != null) {
            String orderType = extractOrderType(key.toString());
            return Math.abs(orderType.hashCode()) % partitions.size();
        }
        return ThreadLocalRandom.current().nextInt(partitions.size());
    }
}
```

---

## 6. 消费者

### 6.1 消费者核心流程

```
Kafka Cluster  ──→  Consumer
                     ┌────────────────────────┐
                     │  1. 订阅 Topic          │
                     │  2. 加入 Group (Rebalance)│
                     │  3. 分配分区             │
                     │  4. 拉取消息 (Poll)      │
                     │  5. 处理消息             │
                     │  6. 提交 Offset         │
                     └────────────────────────┘
```

### 6.2 Java 消费者示例

```java
// 消费者配置
Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(ConsumerConfig.GROUP_ID_CONFIG, "order-service");
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
    StringDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
    StringDeserializer.class.getName());

// 消费位置
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); // 从头消费

// Offset 提交
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // 手动提交

// 性能配置
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);     // 每次拉取 500 条
props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000); // 5 分钟超时
props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 52428800); // 50MB
props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);
props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45000);

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);

// 订阅 Topic
consumer.subscribe(Arrays.asList("orders"));

// 消费循环
try {
    while (true) {
        ConsumerRecords<String, String> records =
            consumer.poll(Duration.ofMillis(1000));

        for (ConsumerRecord<String, String> record : records) {
            System.out.printf(
                "partition=%d, offset=%d, key=%s, value=%s%n",
                record.partition(), record.offset(),
                record.key(), record.value());

            // 业务处理
            processOrder(record.value());
        }

        // 手动同步提交 Offset
        consumer.commitSync();

        // 或异步提交
        consumer.commitAsync((offsets, exception) -> {
            if (exception != null) {
                log.error("Offset 提交失败", exception);
            }
        });
    }
} catch (Exception e) {
    log.error("消费异常", e);
} finally {
    // 关闭前最终提交
    try {
        consumer.commitSync();
    } finally {
        consumer.close();
    }
}
```

### 6.3 关键参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `group.id` | `null` | 消费者组 ID（必填） |
| `enable.auto.commit` | `true` | 自动提交 Offset |
| `auto.commit.interval.ms` | `5000` | 自动提交间隔 |
| `auto.offset.reset` | `latest` | 无初始 Offset 时的行为：`earliest`/`latest`/`none` |
| `max.poll.records` | `500` | 每次 Poll 最大记录数 |
| `max.poll.interval.ms` | `300000` | 两次 Poll 最大间隔，超时视为宕机 |
| `session.timeout.ms` | `45000` | 与 Broker 的会话超时 |
| `heartbeat.interval.ms` | `3000` | 心跳间隔（应 < session.timeout/3） |
| `fetch.max.bytes` | `52428800` | 每次拉取最大字节数 |
| `isolation.level` | `read_uncommitted` | 事务隔离级别 |

### 6.4 Offset 提交策略

| 策略 | 方式 | 优点 | 缺点 | 适用场景 |
|------|------|------|------|----------|
| **自动提交** | `enable.auto.commit=true` | 简单 | 可能丢消息或重复消费 | 允许少量丢失的场景 |
| **手动同步提交** | `commitSync()` | 可靠 | 阻塞，吞吐低 | 可靠性优先 |
| **手动异步提交** | `commitAsync()` | 高吞吐 | 可能丢失回调异常 | 高性能场景 |
| **按分区提交** | `commitSync(map)` | 精细控制 | 代码复杂 | 需要精确管理 Offset |

### 6.5 Rebalance（再均衡）

当消费者加入/退出组时，触发 Rebalance，重新分配分区。

**Rebalance 监听器：**

```java
consumer.subscribe(Arrays.asList("orders"), new ConsumerRebalanceListener() {
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        // 分区被撤销前提交 Offset
        consumer.commitSync(currentOffsets);
        System.out.println("分区被撤销: " + partitions);
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        // 新分区分配后
        System.out.println("分配了新分区: " + partitions);
    }
});
```

**协作式 Rebalance（Kafka 2.4+，推荐）：**

```java
// 使用协作式 Rebalance 减少 Stop-The-World
props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
    CooperativeStickyAssignor.class.getName());
```

| 策略 | 说明 |
|------|------|
| `RangeAssignor` | 按主题范围分配（默认） |
| `RoundRobinAssignor` | 轮询分配 |
| `StickyAssignor` | 尽量保持现有分配 |
| `CooperativeStickyAssignor` | 协作式，逐步重分配（推荐） |

---

## 7. 主题与分区

### 7.1 分区数确定

```bash
# 创建主题时指定分区数
kafka-topics --create --topic orders \
  --partitions 6 --replication-factor 3 \
  --bootstrap-server localhost:9092

# 修改分区数（只能增加，不能减少）
kafka-topics --alter --topic orders \
  --partitions 12 --bootstrap-server localhost:9092
```

**分区数选择公式：**

```
分区数 ≈ max(预期吞吐 / 单分区吞吐, 消费者数)
```

**建议：**
- 吞吐量优先：分区数 = 消费者数 × 2~3（留有余量）
- 单分区吞吐约 10~20MB/s（视硬件和配置）
- 分区数不宜过多（< 1000），过多影响 Leader 选举和文件句柄

### 7.2 日志保留策略

```bash
# 按时间保留（默认 168 小时 = 7 天）
kafka-topics --alter --topic orders \
  --config retention.ms=86400000 --bootstrap-server localhost:9092

# 按大小保留
kafka-topics --alter --topic orders \
  --config retention.bytes=10737418240 --bootstrap-server localhost:9092

# 紧凑策略（Compact）- 保留每个 Key 的最新值
kafka-topics --create --topic user-profiles \
  --config cleanup.policy=compact --bootstrap-server localhost:9092
```

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `retention.ms` | `604800000`（7 天） | 消息保留时间 |
| `retention.bytes` | `-1`（无限） | 分区最大保留大小 |
| `cleanup.policy` | `delete` | `delete` / `compact` / `compact,delete` |
| `segment.bytes` | `1073741824`（1GB） | Segment 文件大小 |
| `segment.ms` | `604800000`（7 天） | Segment 滚动时间 |

### 7.3 压缩策略（Log Compaction）

```
Topic "user-profiles" (cleanup.policy=compact)

消息日志:                       紧凑后:
key=user1, value={name: A}     key=user1, value={name: C}  ← 最新
key=user2, value={name: B}     key=user2, value={name: B}
key=user1, value={name: C}     key=user3, value={name: D}
key=user3, value={name: D}
```

- 保留每个 Key 的最新值
- 适合状态快照场景（用户画像、配置信息）
- 旧版本消息在后台异步清理

---

## 8. 消息可靠性

### 8.1 三种保证级别

```java
// 1. At-most-once - 可能丢消息
props.put(ProducerConfig.ACKS_CONFIG, "0");
// 消费者自动提交 Offset

// 2. At-least-once - 可能重复（推荐）
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
// 消费者手动提交 Offset（处理完再提交）

// 3. Exactly-once - 恰好一次
// 生产者幂等 + 事务
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "txn-order-service");
producer.initTransactions();
```

### 8.2 生产者幂等

```java
// 开启幂等后，Kafka 自动去重
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
// 自动设置 acks=all 和 retries=MAX
```

**幂等原理：** 每个生产者有一个 Producer ID（PID），每条消息有序列号，Broker 通过 (PID, 序列号) 去重。

**限制：** 单会话内幂等，重启后 PID 变化无法跨会话去重。

### 8.3 事务消息

```java
// 初始化事务
producer.initTransactions();

try {
    // 开始事务
    producer.beginTransaction();

    // 发送消息
    producer.send(new ProducerRecord<>("orders", key, value));

    // 发送消费者 Offset 到事务
    producer.sendOffsetsToTransaction(offsets, consumerGroup);

    // 提交事务
    producer.commitTransaction();
} catch (Exception e) {
    // 回滚事务
    producer.abortTransaction();
}
```

### 8.4 最小 ISR（min.insync.replicas）

```java
// Broker 或 Topic 级别配置
props.put("min.insync.replicas", 2);
```

- 生产者 `acks=all` 时，等待至少 `min.insync.replicas` 个副本确认
- 如果 ISR 数量 < min.insync.replicas，生产者会收到异常
- 建议 `min.insync.replicas` = `replication.factor - 1`

### 8.5 消费者端幂等

```java
// 利用消息 ID 去重
Set<String> processedIds = redisCache.getProcessedIds();

public void processMessage(ConsumerRecord<String, String> record) {
    String msgId = extractMessageId(record.headers());

    // 判断是否已处理
    if (processedIds.contains(msgId)) {
        log.info("消息已处理，跳过: {}", msgId);
        return;
    }

    // 业务处理 + 记录 ID（同一事务）
    try {
        processBusinessLogic(record.value());
        saveProcessedId(msgId); // 与业务操作在同一事务中
        consumer.commitSync();
    } catch (Exception e) {
        // 处理失败，不提交 Offset
        log.error("处理失败", e);
    }
}
```

---

## 9. Kafka Streams

### 9.1 概述

Kafka Streams 是一个轻量级的流处理库，无需独立的流处理集群（如 Flink/Spark）。

**特点：**
- 嵌入应用内运行，无独立集群
- 支持 Exactly-once 语义
- 支持状态存储（RocksDB）
- 支持窗口操作、Join、聚合

### 9.2 依赖

```gradle
implementation 'org.apache.kafka:kafka-streams:3.7.0'
```

### 9.3 Word Count 示例

```java
Properties props = new Properties();
props.put(StreamsConfig.APPLICATION_ID_CONFIG, "word-count-app");
props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
    Serdes.String().getClass());
props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
    Serdes.String().getClass());
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,
    StreamsConfig.EXACTLY_ONCE_V2);

StreamsBuilder builder = new StreamsBuilder();

KStream<String, String> textLines =
    builder.stream("text-input");

KTable<String, Long> wordCounts = textLines
    .flatMapValues(line -> Arrays.asList(line.toLowerCase().split("\\W+")))
    .groupBy((key, word) -> word)
    .count(Materialized.as("counts-store"));

wordCounts.toStream().to("word-count-output",
    Produced.with(Serdes.String(), Serdes.Long()));

KafkaStreams streams = new KafkaStreams(builder.build(), props);
streams.start();
```

### 9.4 窗口操作

```java
// 滚动窗口（Tumbling Window）- 每 5 分钟统计一次
KTable<Windowed<String>, Long> tumblingCount = events
    .groupByKey()
    .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(5)))
    .count();

// 滑动窗口（Sliding Window）- 每 1 分钟滑动一次，窗口 5 分钟
KTable<Windowed<String>, Long> slidingCount = events
    .groupByKey()
    .windowedBy(SlidingWindows.ofTimeDifferenceWithNoGrace(
        Duration.ofMinutes(5)))
    .count();

// 跳跃窗口（Hopping Window）
KTable<Windowed<String>, Long> hoppingCount = events
    .groupByKey()
    .windowedBy(TimeWindows.ofSizeAndAdvance(
        Duration.ofMinutes(5), Duration.ofMinutes(1)))
    .count();

// 会话窗口（Session Window）
KTable<Windowed<String>, Long> sessionCount = events
    .groupByKey()
    .windowedBy(SessionWindows.ofInactivityGapWithNoGrace(
        Duration.ofMinutes(5)))
    .count();
```

### 9.5 Stream-Table Join

```java
// 流与表 Join（流式数据关联维表）
KStream<String, Order> orders = builder.stream("orders");
KTable<String, User> users = builder.table("users");

KStream<String, EnrichedOrder> enriched = orders.join(
    users,
    (order, user) -> new EnrichedOrder(order, user),
    Joined.with(Serdes.String(), orderSerde, userSerde)
);

// 流与流 Join
KStream<String, ClickEvent> clicks = builder.stream("clicks");
KStream<String, PurchaseEvent> purchases = builder.stream("purchases");

KStream<String, String> joined = clicks.join(
    purchases,
    (click, purchase) -> click.getUserId() + " made a purchase",
    JoinWindows.ofTimeDifferenceWithNoGrace(Duration.ofMinutes(5))
);
```

---

## 10. Spring Boot 集成

### 10.1 依赖

```gradle
implementation 'org.springframework.boot:spring-boot-starter'
implementation 'org.springframework.kafka:spring-kafka'
```

### 10.2 配置

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    # 生产者
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      compression-type: snappy
      properties:
        enable.idempotence: true
    # 消费者
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      group-id: order-service
      enable-auto-commit: false
      auto-offset-reset: earliest
      max-poll-records: 500
    listener:
      ack-mode: manual  # 手动 Ack
      concurrency: 3    # 并发消费者数
      missing-topics-fatal: false
```

### 10.3 生产者

```java
@Component
@Slf4j
public class OrderProducer {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    public void sendOrder(Order order) {
        // 异步发送
        CompletableFuture<SendResult<String, String>> future =
            kafkaTemplate.send("orders", order.getUserId(), order.toJson());

        // 成功/失败回调
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("消息发送成功: topic={}, partition={}, offset={}",
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            } else {
                log.error("消息发送失败", ex);
            }
        });
    }

    // 带事务发送
    @Transactional
    public void sendWithTransaction(List<Order> orders) {
        orders.forEach(order ->
            kafkaTemplate.send("orders", order.getUserId(), order.toJson()));
    }
}
```

### 10.4 消费者

```java
@Component
@Slf4j
public class OrderConsumer {

    @KafkaListener(topics = "orders", groupId = "order-service")
    public void handleOrder(ConsumerRecord<String, String> record,
                            Acknowledgment ack) {
        try {
            log.info("收到订单: key={}, partition={}, offset={}",
                record.key(), record.partition(), record.offset());

            processOrder(record.value());

            // 手动提交 Offset
            ack.acknowledge();
        } catch (Exception e) {
            log.error("订单处理失败", e);
            // 不提交 Offset，消息会重新投递
        }
    }

    // 批量消费
    @KafkaListener(topics = "orders", groupId = "order-batch",
        containerFactory = "batchFactory")
    public void handleBatch(List<ConsumerRecord<String, String>> records,
                            Acknowledgment ack) {
        try {
            for (ConsumerRecord<String, String> record : records) {
                processOrder(record.value());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("批量处理失败", e);
        }
    }

    // 指定分区消费
    @KafkaListener(topicPartitions = {
        @TopicPartition(topic = "orders", partitions = {"0", "1"}),
        @TopicPartition(topic = "orders", partitionOffsets = {
            @PartitionOffset(partition = "2", initialOffset = "100")
        })
    })
    public void handleSpecificPartition(ConsumerRecord<String, String> record,
                                        Acknowledgment ack) {
        // ...
    }
}
```

### 10.5 配置类

```java
@Configuration
public class KafkaConfig {

    // JSON 序列化
    @Bean
    public ProducerFactory<String, Object> jsonProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> jsonKafkaTemplate() {
        return new KafkaTemplate<>(jsonProducerFactory());
    }

    // 批量消费者工厂
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
            batchFactory(ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);  // 开启批量消费
        factory.setConcurrency(3);
        return factory;
    }
}
```

### 10.6 错误处理

```java
@Component
@Slf4j
public class KafkaErrorHandler {

    // 全局异常处理
    @Bean
    public DefaultErrorHandler errorHandler() {
        // 重试策略：最多重试 3 次，间隔 1s/2s/4s
        FixedBackOff backOff = new FixedBackOff(1000L, 3);
        DefaultErrorHandler handler = new DefaultErrorHandler(
            (record, exception) -> {
                log.error("消息处理失败，进入死信: topic={}, offset={}",
                    record.topic(), record.offset(), exception);
            },
            backOff
        );
        // 不重试的异常
        handler.addNotRetryableExceptions(ValidationException.class);
        return handler;
    }

    // 死信主题（DLT）
    @Bean
    public DeadLetterPublishingRecoverer recoverer(KafkaTemplate<?, ?> template) {
        return new DeadLetterPublishingRecoverer(template,
            (record, ex) -> new TopicPartition(
                record.topic() + ".DLT", record.partition()));
    }
}
```

---

## 11. 集群与运维

### 11.1 集群规划

| 规模 | Broker 数 | 副本因子 | 分区数 | 磁盘 |
|------|----------|----------|--------|------|
| 开发/测试 | 1~3 | 1 | 3 | SSD 100GB |
| 小型生产 | 3~5 | 3 | 6~12 | SSD 500GB |
| 中型生产 | 5~10 | 3 | 24~48 | SSD 2TB+ |
| 大型生产 | 10+ | 3 | 分区 ≤ 1000 | 多盘 RAID |

### 11.2 多 Broker 集群

```yaml
# docker-compose.yml - 3 节点集群
version: '3.8'
services:
  kafka-1:
    image: confluentinc/cp-kafka:7.7.0
    container_name: kafka-1
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_NODE_ID: 1
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093"
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 3
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 2
    volumes:
      - kafka-1-data:/var/lib/kafka/data

  kafka-2:
    image: confluentinc/cp-kafka:7.7.0
    container_name: kafka-2
    ports:
      - "9093:9092"
    environment:
      KAFKA_BROKER_ID: 2
      KAFKA_NODE_ID: 2
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093"
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3
    volumes:
      - kafka-2-data:/var/lib/kafka/data

  kafka-3:
    image: confluentinc/cp-kafka:7.7.0
    container_name: kafka-3
    ports:
      - "9094:9092"
    environment:
      KAFKA_BROKER_ID: 3
      KAFKA_NODE_ID: 3
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093"
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9094
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 3
    volumes:
      - kafka-3-data:/var/lib/kafka/data

volumes:
  kafka-1-data:
  kafka-2-data:
  kafka-3-data:
```

### 11.3 常用运维命令

```bash
# 查看集群状态
kafka-broker-api-versions --bootstrap-server localhost:9092

# 查看 Broker 信息
kafka-broker-info --bootstrap-server localhost:9092

# 修改主题配置
kafka-configs --alter --entity-type topics --entity-name orders \
  --add-config retention.ms=86400000,min.insync.replicas=2 \
  --bootstrap-server localhost:9092

# 查看所有配置
kafka-configs --describe --entity-type topics --entity-name orders \
  --bootstrap-server localhost:9092

# 迁移分区（重新分配副本）
kafka-reassign-partitions --generate \
  --topics-to-move-json-file topics.json \
  --broker-list "1,2,3" --bootstrap-server localhost:9092

# 查看日志大小
kafka-log-dirs --describe --bootstrap-server localhost:9092

# 删除主题
kafka-topics --delete --topic obsolete-topic \
  --bootstrap-server localhost:9092

# 查看消费者组 Lag
kafka-consumer-groups --describe --group my-group \
  --bootstrap-server localhost:9092
```

### 11.4 分区重分配

```bash
# 1. 生成重分配方案
cat > topics.json <<EOF
{"topics": [{"topic": "orders"}], "version": 1}
EOF

kafka-reassign-partitions --generate \
  --topics-to-move-json-file topics.json \
  --broker-list "1,2,3" \
  --bootstrap-server localhost:9092 > reassign.json

# 2. 执行重分配
kafka-reassign-partitions --execute \
  --reassignment-json-file reassign.json \
  --bootstrap-server localhost:9092

# 3. 检查进度
kafka-reassign-partitions --verify \
  --reassignment-json-file reassign.json \
  --bootstrap-server localhost:9092
```

### 11.5 性能调优

| 参数 | 默认值 | 调优建议 |
|------|--------|----------|
| `num.network.threads` | `3` | 增加到 CPU 核数（网络密集型） |
| `num.io.threads` | `8` | 增加到 CPU 核数 × 2（磁盘密集型） |
| `log.flush.interval.messages` | `9223372036854775807` | 消息数刷盘阈值 |
| `log.flush.interval.ms` | `9223372036854775807` | 时间刷盘阈值 |
| `log.segment.bytes` | `1073741824` | 减小可加快清理速度 |
| `log.retention.check.interval.ms` | `300000` | 日志清理检查间隔 |
| `log.cleaner.threads` | `1` | 日志压缩线程数 |
| `compression.type` | `producer` | Broker 端压缩，`producer` 保留生产者压缩 |

**操作系统调优：**
```bash
# 调整页面缓存刷新
sysctl -w vm.dirty_ratio=20
sysctl -w vm.dirty_background_ratio=5

# 调整网络缓冲区
sysctl -w net.core.rmem_max=16777216
sysctl -w net.core.wmem_max=16777216

# 文件句柄限制
ulimit -n 100000

# 挂载选项（XFS 推荐）
mount -o noatime,nodiratime,noexec /dev/sdb /data/kafka
```

---

## 12. 监控

### 12.1 关键监控指标

| 指标 | JMX MBean | 说明 | 告警条件 |
|------|-----------|------|----------|
| **消息入站速率** | `BytesInPerSec` | 每秒写入字节数 | 突增/突降 |
| **消息出站速率** | `BytesOutPerSec` | 每秒读取字节数 | 突降 |
| **请求速率** | `RequestsPerSec` | 每秒请求数 | 突增 50%+ |
| **分区数量** | `PartitionCount` | 总分区数 | > 推荐上限 |
| **Leader 数** | `LeaderCount` | 当前 Leader 分区数 | 不均匀分布 |
| **ISR 收缩** | `UnderReplicatedPartitions` | 未充分复制的分区 | > 0 |
| **离线分区** | `OfflinePartitions` | 无 Leader 的分区 | > 0 |
| **消费者 Lag** | 自定义计算 | 生产与消费的 Offset 差 | > 10000 |
| **请求队列大小** | `RequestQueueSize` | 等待处理的请求数 | > 1000 |
| **磁盘使用率** | 系统指标 | 日志存储使用率 | > 80% |

### 12.2 消费者 Lag 监控

```bash
# 命令行查看 Lag
kafka-consumer-groups --describe --group order-service \
  --bootstrap-server localhost:9092

# 输出示例：
# GROUP           TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# order-service   orders  0          1500            2000            500
# order-service   orders  1          2800            3000            200
# order-service   orders  2          3200            3500            300
# 总 Lag = 1000
```

### 12.3 工具推荐

| 工具 | 说明 | 类型 |
|------|------|------|
| **Kafka Manager (CMA)** | Yahoo 开源的集群管理工具 | Web UI |
| **AKHQ** | Kafka 集群管理 GUI | Web UI |
| **Confluent Control Center** | Confluent 商业版监控 | Web UI |
| **Burrow** | LinkedIn 开源的 Lag 监控 | 服务 |
| **Prometheus + JMX Exporter** | 指标采集 | 开源 |
| **Grafana** | 可视化仪表盘 | 开源 |
| **Cruise Control** | 自动分区均衡 | 服务 |

---

## 13. 最佳实践

### 13.1 主题设计

| 实践 | 说明 |
|------|------|
| **按业务域分 Topic** | 如 `orders`、`payments`、`notifications` |
| **合理设置分区数** | 留有余量，但不过多 |
| **副本因子 ≥ 3** | 生产环境至少 3 副本 |
| **命名规范** | `业务.事件类型`，如 `order.created` |
| **配置保留策略** | 根据业务需要设置保留时间 |

### 13.2 生产者

| 实践 | 说明 |
|------|------|
| **开启幂等** | `enable.idempotence=true` |
| **acks=all** | 等待所有副本确认 |
| **异步发送 + 回调** | 不要阻塞主线程 |
| **合理设置 batch.size** | 16KB~64KB，过大增加延迟 |
| **开启压缩** | `snappy` 或 `zstd` 效果较好 |
| **关闭自动创建 Topic** | 避免 Topic 命名混乱 |
| **使用 Error Handler** | 重试、死信、告警 |

### 13.3 消费者

| 实践 | 说明 |
|------|------|
| **手动提交 Offset** | 处理完再提交 |
| **消费幂等** | 业务键去重 |
| **设置 group.id** | 每个消费者必须属于一个 Group |
| **合理设置 max.poll.records** | 保证在 max.poll.interval.ms 内处理完 |
| **实现 Rebalance 监听** | 优雅处理分区重分配 |
| **使用协作式 Rebalance** | 减少 Stop-The-World |
| **监控 Lag** | 及时告警 |

### 13.4 消息设计

| 实践 | 说明 |
|------|------|
| **包含消息 ID** | 用于追踪和去重 |
| **使用 Avro / Protobuf** | Schema 兼容性管理 |
| **Key 设计** | 相同 Key 保证分区内有序 |
| **消息体适度** | 建议 < 1MB |
| **包含时间戳** | 业务时间 vs 系统时间 |

### 13.5 Schema 管理

```java
// 使用 Confluent Schema Registry + Avro
// 生产者
props.put("schema.registry.url", "http://schema-registry:8081");

KafkaAvroSerializer serializer = new KafkaAvroSerializer();
// 发送 Avro 消息
GenericRecord record = new GenericRecordBuilder(schema)
    .set("order_id", "ord-001")
    .set("user_id", "user-123")
    .set("amount", 99.99)
    .build();
producer.send(new ProducerRecord<>("orders", record));
```

### 13.6 安全

| 实践 | 说明 |
|------|------|
| **开启 SASL/SSL** | 生产环境必须认证 |
| **最小权限** | ACL 控制读写权限 |
| **加密传输** | TLS 1.2+ |
| **审计日志** | 记录操作行为 |
| **定期轮换密钥** | 证书和密码定期更新 |

### 13.7 版本建议

| 版本 | 说明 |
|------|------|
| **3.7.x / 3.8.x** | 最新稳定版，推荐新项目 |
| **3.6.x** | 次新版，稳定 |
| **3.5.x** | 维护中 |
| **3.4.x 以下** | 建议升级 |
| **2.8.x** | 最后一个支持 ZooKeeper 模式的过渡版 |

---

## 14. 常见问题

### 14.1 消息丢失

```
症状：消费者没收到已发送的消息
原因：
  - acks=0 或 acks=1，Leader 宕机时丢失
  - 生产者未开启幂等，网络重试导致乱序丢失
  - 消费者自动提交 Offset，处理失败时已提交

解决方案：
  - acks=all + 幂等生产者
  - 手动提交 Offset
  - min.insync.replicas=2
```

### 14.2 消息重复

```
症状：同一条消息被消费多次
原因：
  - 生产者重试导致重复发送
  - 消费者处理超时触发 Rebalance，消息重新投递
  - 消费者处理完但 Offset 提交失败

解决方案：
  - 开启幂等生产者
  - 消费端幂等（业务键去重）
  - 使用事务
```

### 14.3 消息堆积（高 Lag）

```
症状：消费者 Lag 持续增长
原因：
  - 消费者处理速度慢
  - 消费者数 < 分区数
  - 消费者宕机
  - 处理逻辑中有慢操作（DB 查询、外部 API）

解决方案：
  - 增加分区数 + 消费者数
  - 优化消费逻辑（异步批处理）
  - 增加消费者（分区数也要相应增加）
  - 检查处理中的瓶颈
```

### 14.4 分区 Leader 选举慢

```
症状：故障恢复后长时间不可用
原因：
  - Controller 负载高
  - 分区数过多
  - 网络分区

解决方案：
  - 控制分区总数
  - 配置 controller.quorum.election.timeout.ms
  - 使用 KRaft 模式替代 ZooKeeper
```

### 14.5 磁盘空间满

```
症状：Broker 无法写入新消息
原因：
  - 消息保留时间过长
  - 消息量突增
  - 未设置 retention.bytes 限制

解决方案：
  - 调整 retention.ms 和 retention.bytes
  - 添加磁盘或清理旧日志
  - 设置磁盘使用率告警
```

### 14.6 网络超时

```
症状：生产者/消费者频繁超时
原因：
  - 网络延迟高
  - Broker 负载高
  - 请求超时配置不合理

解决方案：
  - 调整 request.timeout.ms
  - 增加 Broker 节点
  - 检查网络状况
```

### 14.7 Rebalance 频繁

```
症状：消费者频繁触发 Rebalance，导致消费停滞
原因：
  - 消费者处理时间 > max.poll.interval.ms
  - 网络不稳定导致心跳超时
  - 消费者数频繁变化

解决方案：
  - 增大 max.poll.interval.ms
  - 减少 max.poll.records
  - 使用协作式 Rebalance
  - 增加 heartbeat.interval.ms
```

---

## 参考资源

- [Kafka 官方文档](https://kafka.apache.org/documentation/)
- [Kafka Java Client API](https://kafka.apache.org/37/javadoc/)
- [Spring Kafka 官方文档](https://docs.spring.io/spring-kafka/reference/)
- [Kafka Streams 文档](https://kafka.apache.org/documentation/streams/)
- [Confluent 文档](https://docs.confluent.io/)
- [Kafka 配置详解](https://kafka.apache.org/documentation/#configuration)
