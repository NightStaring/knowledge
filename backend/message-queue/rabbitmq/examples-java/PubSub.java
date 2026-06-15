/**
 * 03 发布/订阅 — Fanout Exchange
 * ==============================
 *
 * 教学目的：演示广播模式——一条消息发给所有消费者
 *
 * 相比 02 的新概念：
 *   - Exchange:    消息路由器，生产者不直接发到队列，而是发到 Exchange
 *   - Fanout:     广播类型，把消息发给所有绑定的队列
 *   - 临时队列:     queueDeclare() 不传名，RabbitMQ 自动生成
 *   - exclusive:   队列只对当前连接可见，断开即删
 *
 * 场景：
 *   日志系统——一条日志消息，所有订阅者都能收到
 *
 * 运行方式（开三个终端）：
 *   mvn exec:java -Dexec.mainClass="PubSub" -Dexec.args="consumer"
 *   mvn exec:java -Dexec.mainClass="PubSub" -Dexec.args="consumer"
 *   mvn exec:java -Dexec.mainClass="PubSub" -Dexec.args="producer"
 */

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class PubSub {

    private static final String EXCHANGE_NAME = "logs";

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || (!args[0].equals("producer") && !args[0].equals("consumer"))) {
            System.out.println("用法: mvn exec:java -Dexec.mainClass=\"PubSub\" -Dexec.args=\"[producer|consumer]\"");
            System.exit(1);
        }

        if (args[0].equals("producer")) {
            runProducer();
        } else {
            runConsumer();
        }
    }

    static void runProducer() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            // ============================================================
            // 声明 Exchange
            //
            // exchangeDeclare(Exchange名, 类型, 持久化)
            //
            // 之前我们用默认 Exchange（exchange=""），它按队列名精确路由
            // 现在显式声明一个 Fanout Exchange
            //
            // 注意：exchangeDeclare 有多个重载：
            //   exchangeDeclare(String name, String type)
            //   exchangeDeclare(String name, BuiltinExchangeType type)
            //   exchangeDeclare(String name, String type, boolean durable)
            // ============================================================
            channel.exchangeDeclare(EXCHANGE_NAME, "fanout");

            System.out.println("请输入日志消息（每行一个），输入 quit 退出:");
            Scanner scanner = new Scanner(System.in);

            while (true) {
                String message = scanner.nextLine();
                if (message.equalsIgnoreCase("quit")) break;

                // ============================================================
                // 发到 Exchange，而不是直接发到 Queue
                // routingKey 在 Fanout 模式下会被忽略（因为广播给所有人）
                // ============================================================
                channel.basicPublish(EXCHANGE_NAME, "", null, message.getBytes(StandardCharsets.UTF_8));
                System.out.println(" [x] 已发送: " + message);
            }
        }
    }

    static void runConsumer() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        // 声明 Exchange（消费者也需要，因为可能比生产者先启动）
        channel.exchangeDeclare(EXCHANGE_NAME, "fanout");

        // ============================================================
        // 创建临时队列
        //
        // queueDeclare() 不传队列名 → RabbitMQ 自动生成一个随机名称
        // 比如 "amq.gen-JzTY20BRgKO-HjmUJj0wLg"
        //
        // exclusive=true 的队列特点：
        //   - 只对当前连接可见
        //   - 连接断开时自动删除
        //   - 完美适合"每个消费者一个专属队列"的场景
        //
        // queueDeclare().getQueue() 获取自动生成的队列名
        // ============================================================
        String queueName = channel.queueDeclare().getQueue();
        System.out.println(" [*] 临时队列名: " + queueName);

        // ============================================================
        // 绑定队列到 Exchange
        //
        // queueBind(队列名, Exchange名, RoutingKey)
        // 告诉 RabbitMQ：这个队列想从这个 Exchange 接收消息
        // 对于 Fanout 类型，routingKey 不起作用
        // ============================================================
        channel.queueBind(queueName, EXCHANGE_NAME, "");

        System.out.println(" [*] 等待日志消息，按 Ctrl+C 退出...");

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            System.out.println(" [x] 收到日志: " + message);
        };

        channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {});

        Thread.sleep(Long.MAX_VALUE);
    }
}
