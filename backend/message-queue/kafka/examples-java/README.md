# Kafka Java 教学示例

## 前置条件

```bash
# 1. 启动 Kafka
docker-compose -f ../examples-python/docker-compose.yml up -d

# 2. 编译
mvn compile

# 3. 运行示例
# 直接运行（需要先编译）
mvn exec:java -Dexec.mainClass="ProducerConsumer" -Dexec.args="producer"
mvn exec:java -Dexec.mainClass="ProducerConsumer" -Dexec.args="consumer"
```

## 文件清单

| 文件 | 说明 | 对应 Python 示例 |
|------|------|-----------------|
| `ProducerConsumer.java` | 基础生产/消费 | 01 |
| `Partitions.java` | 分区策略演示 | 02 |
| `ConsumerGroups.java` | 消费者组 + Rebalance | 03 |
| `Reliability.java` | 幂等生产者 + 可靠性 | 04 |
| `Transactions.java` | 事务消息 | 05 |

## 直接编译运行（不用 Maven）

```bash
javac -cp "kafka-clients-3.7.0.jar;slf4j-api-2.0.13.jar;slf4j-simple-2.0.13.jar" *.java
java -cp ".;kafka-clients-3.7.0.jar;slf4j-api-2.0.13.jar;slf4j-simple-2.0.13.jar" ProducerConsumer producer
```
