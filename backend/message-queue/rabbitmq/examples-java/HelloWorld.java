/**
 * 01 Hello World — 基础收发
 * ==========================
 *
 * 教学目的：演示 RabbitMQ 最基础的消息发送和接收流程
 *
 * 关键概念：
 *   - Connection: TCP 连接（应用和 RabbitMQ 之间的长连接）
 *   - Channel:    在 Connection 上创建的虚拟通道，所有操作都在 Channel 上执行
 *   - Queue:      消息存储的队列
 *
 * 与 Python 版本的主要差异：
 *   - Java 的 Connection 和 Channel 需要手动关闭（try-with-resources）
 *   - Java 的 Consumer 是接口实现，不是回调函数
 *   - Java 用 DefaultConsumer 或 DeliverCallback 处理消息
 *
 * 运行方式（开两个终端）：
 *   mvn exec:java -Dexec.mainClass="HelloWorld" -Dexec.args="consumer"
 *   mvn exec:java -Dexec.mainClass="HelloWorld" -Dexec.args="producer"
 */

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import java.nio.charset.StandardCharsets;

public class HelloWorld {

    // 队列名称常量
    private static final String QUEUE_NAME = "hello";

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || (!args[0].equals("producer") && !args[0].equals("consumer"))) {
            System.out.println("用法: mvn exec:java -Dexec.mainClass=\"HelloWorld\" -Dexec.args=\"[producer|consumer]\"");
            System.exit(1);
        }

        if (args[0].equals("producer")) {
            runProducer();
        } else {
            runConsumer();
        }
    }

    // ================================================================
    // 生产者：发送消息到队列
    // ================================================================
    static void runProducer() throws Exception {
        // ============================================================
        // 1. 创建连接工厂
        //
        // ConnectionFactory 是 Java 客户端的入口
        // 默认连接 localhost:5672，默认用户 guest/guest
        // ============================================================
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        // ============================================================
        // 2. 建立连接 + 创建通道
        //
        // try-with-resources: Java 7 的自动资源管理
        // Connection 和 Channel 都实现了 Closeable，退出 try 块自动关闭
        //
        // Connection:     TCP 连接（重量级，应复用）
        // Channel:        虚拟通道（轻量级，一个 Connection 可开多个）
        // ============================================================
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            // ============================================================
            // 3. 声明队列
            //
            // queueDeclare(队列名, 持久化, 独占, 自动删除, 参数)
            //   durable=false: 非持久化（重启后丢失）
            //   exclusive=false: 不独占（其他连接可访问）
            //   autoDelete=false: 不自动删除
            // ============================================================
            channel.queueDeclare(QUEUE_NAME, false, false, false, null);

            // ============================================================
            // 4. 发送消息
            //
            // basicPublish(Exchange名, RoutingKey, 属性, 消息体)
            //   exchange="": 使用默认 Direct Exchange，按队列名路由
            //   routingKey=QUEUE_NAME: 路由到同名队列
            //   body: 消息体（byte[]）
            // ============================================================
            String message = "Hello World!";
            channel.basicPublish("", QUEUE_NAME, null, message.getBytes(StandardCharsets.UTF_8));
            System.out.println(" [x] 已发送: " + message);

        } // 自动关闭 connection 和 channel
    }

    // ================================================================
    // 消费者：从队列接收消息
    // ================================================================
    static void runConsumer() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        // ============================================================
        // 消费者需要保持连接，所以不能 try-with-resources
        // Connection 和 Channel 要在外部声明，最后手动关闭
        // ============================================================
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        // 声明队列（跟生产者保持一致，如果消费者先启动，队列还不存在）
        channel.queueDeclare(QUEUE_NAME, false, false, false, null);

        System.out.println(" [*] 等待消息，按 Ctrl+C 退出...");

        // ============================================================
        // 定义 DeliverCallback（消息投递回调）
        //
        // Java 中消息处理有两种方式：
        //   1. DeliverCallback（函数式接口，推荐）
        //   2. DefaultConsumer（继承类，传统方式）
        //
        // 参数说明：
        //   consumerTag: 消费者标签（自动生成或指定）
        //   delivery:    消息投递信息（包含 envelope、properties、body）
        //
        // envelope 包含：
        //   getDeliveryTag(): 投递编号（用于 Ack）
        //   getRoutingKey():  路由键
        //   getExchange():    来源 Exchange
        // ============================================================
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            System.out.println(" [x] 收到: " + message);
        };

        // ============================================================
        // 开始消费
        //
        // basicConsume(队列名, 自动Ack, 投递回调, 取消回调)
        //   autoAck=true: 自动确认（收到即确认，不关心处理结果）
        //   生产环境应使用手动 Ack（见 ReliableDelivery.java）
        // ============================================================
        channel.basicConsume(QUEUE_NAME, true, deliverCallback, consumerTag -> {});

        // 保持主线程运行（否则 JVM 退出，收不到消息）
        Thread.sleep(Long.MAX_VALUE);
    }
}
