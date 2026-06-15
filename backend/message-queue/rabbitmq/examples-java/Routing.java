/**
 * 04 路由模式 — Direct Exchange
 * =============================
 *
 * 教学目的：演示按路由键精确分发消息
 *
 * 相比 03 的新概念：
 *   - Direct Exchange: 根据 routingKey 精确匹配 bindingKey
 *   - 消费者可以按需订阅特定级别的消息
 *   - 一个队列可以绑定多个 routingKey
 *
 * 场景：
 *   日志分级——error 日志发给错误处理器，info 日志发给普通记录器
 *
 * 运行方式（开三个终端）：
 *   mvn exec:java -Dexec.mainClass="Routing" -Dexec.args="consumer error"
 *   mvn exec:java -Dexec.mainClass="Routing" -Dexec.args="consumer info warning"
 *   mvn exec:java -Dexec.mainClass="Routing" -Dexec.args="producer"
 */

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Scanner;

public class Routing {

    private static final String EXCHANGE_NAME = "direct_logs";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法:");
            System.out.println("  producer: mvn exec:java -Dexec.mainClass=\"Routing\" -Dexec.args=\"producer\"");
            System.out.println("  consumer: mvn exec:java -Dexec.mainClass=\"Routing\" -Dexec.args=\"consumer [级别1] [级别2] ...\"");
            System.out.println("示例:");
            System.out.println("  mvn exec:java -Dexec.mainClass=\"Routing\" -Dexec.args=\"consumer error\"");
            System.out.println("  mvn exec:java -Dexec.mainClass=\"Routing\" -Dexec.args=\"consumer info warning\"");
            System.exit(1);
        }

        if (args[0].equals("producer")) {
            runProducer();
        } else if (args[0].equals("consumer")) {
            if (args.length < 2) {
                System.out.println("请指定要订阅的级别，如: consumer error warning");
                System.exit(1);
            }
            // 把剩余参数作为要订阅的级别列表
            String[] severities = Arrays.copyOfRange(args, 1, args.length);
            runConsumer(severities);
        } else {
            System.out.println("未知命令: " + args[0]);
            System.exit(1);
        }
    }

    static void runProducer() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            channel.exchangeDeclare(EXCHANGE_NAME, "direct");

            System.out.println("输入格式: 级别 消息，如: error 数据库挂了");
            System.out.println("级别可选: debug, info, warning, error");
            System.out.println("输入 quit 退出");

            Scanner scanner = new Scanner(System.in);
            while (true) {
                String text = scanner.nextLine();
                if (text.equalsIgnoreCase("quit")) break;

                String[] parts = text.split(" ", 2);
                if (parts.length != 2) {
                    System.out.println("格式错误，请用: 级别 消息");
                    continue;
                }

                String severity = parts[0];
                String message = parts[1];

                // ============================================================
                // Direct Exchange：routingKey 精确匹配
                // 消息的 routingKey = "error"
                // 只有绑定了 "error" 的队列才能收到
                // ============================================================
                channel.basicPublish(EXCHANGE_NAME, severity, null,
                    message.getBytes(StandardCharsets.UTF_8));
                System.out.println(" [x] 已发送: [" + severity + "] " + message);
            }
        }
    }

    static void runConsumer(String[] severities) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        channel.exchangeDeclare(EXCHANGE_NAME, "direct");

        // 创建临时队列
        String queueName = channel.queueDeclare().getQueue();

        // ============================================================
        // 绑定多个路由键
        //
        // 如果消费者订阅 ["error", "warning"]，
        // 就绑定两次，队列会同时收到 error 和 warning 消息
        //
        // 这实现了"选择性订阅"
        // ============================================================
        for (String severity : severities) {
            channel.queueBind(queueName, EXCHANGE_NAME, severity);
            System.out.println(" [*] 绑定了: " + severity);
        }

        System.out.println(" [*] 等待日志消息（级别: " + String.join(", ", severities) + "），按 Ctrl+C 退出...");

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            System.out.println(" [x] [" + delivery.getEnvelope().getRoutingKey() + "] " + message);
        };

        channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {});

        Thread.sleep(Long.MAX_VALUE);
    }
}
