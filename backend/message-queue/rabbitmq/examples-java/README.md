# RabbitMQ Java 教学示例

## 前置条件

```bash
# 1. 启动 RabbitMQ
docker-compose -f ../examples-python/docker-compose.yml up -d

# 2. 编译
mvn compile

# 3. 运行示例（每个文件两种角色）
mvn exec:java -Dexec.mainClass="HelloWorld" -Dexec.args="consumer"
mvn exec:java -Dexec.mainClass="HelloWorld" -Dexec.args="producer"
```

## 文件清单

| 文件 | 说明 | 对应 Python 示例 |
|------|------|-----------------|
| `HelloWorld.java` | 基础收发 | 01 |
| `WorkQueues.java` | 工作队列 + 公平分发 | 02 |
| `PubSub.java` | 发布/订阅 (Fanout) | 03 |
| `Routing.java` | 路由模式 (Direct) | 04 |
| `Topics.java` | 主题模式 (Topic) | 05 |
| `RpcServer.java` + `RpcClient.java` | RPC 请求/应答 | 06 |
| `ReliableDelivery.java` | 可靠投递 + 死信队列 | 07 |

## 直接编译运行（不用 Maven）

```bash
# 下载 amqp-client 和 slf4j jar 包后
javac -cp "amqp-client-5.22.0.jar;slf4j-api-2.0.13.jar;slf4j-simple-2.0.13.jar" *.java
java -cp ".;amqp-client-5.22.0.jar;slf4j-api-2.0.13.jar;slf4j-simple-2.0.13.jar" HelloWorld producer
```
