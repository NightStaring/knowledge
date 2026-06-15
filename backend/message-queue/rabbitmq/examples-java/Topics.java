/**
 * 05 主题模式 — Topic Exchange
 * ============================
 *
 * 教学目的：演示通配符路由，这是 RabbitMQ 最灵活的路由方式
 *
 * 相比 04 的新概念：
 *   - Topic Exchange: routingKey 支持通配符
 *   - * 匹配一个单词（由 . 分隔）
 *   - # 匹配零个或多个单词
 *
 * 场景：
 *   一个灵活的日志系统，支持按"来源.级别"过滤
 *   比如 "auth.error"、"auth.info"、"web.error"、"web.info"
 *
 * 运行方式（开三个终端）：
 *   mvn exec:java -Dexec.mainClass="Topics" -Dexec.args="consumer \"*.error\""
 *   mvn exec:java -Dexec.mainClass="Topics" -Dexec.args="consumer \"auth.#\""
 *   mvn exec:java -Dexec.mainClass="Topics" -Dexec.args="producer"
 */

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Scanner;

public class Topics {

    private static final String EXCHANGE_NAME = "topic_logs";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法:");
            System.out.println("  producer: mvn exec:java -Dexec.mainClass=\"Topics\" -Dexec.args=\"producer\"");
            System.out.println("  consumer: mvn exec:java -Dexec.mainClass=\"Topics\" -Dexec.args=\"consumer [模式1] [模式2] ...\"");
            System.out.println("示例:");
            System.out.println("  mvn exec:java -Dexec.mainClass=\"Topics\" -Dexec.args=\"consumer \\\"*.error\\\"\"");
            System.out.println("  mvn exec:java -Dexec.mainClass=\"Topics\" -Dexec.args=\"consumer \\\"auth.#\\\"\"");
            System.exit(1);
        }

        if (args[0].equals("producer")) {
            runProducer();
        } else if (args[0].equals("consumer")) {
            if (args.length < 2) {
                System.out.println("请指定绑定模式，如: consumer \"*.error\"");
                System.exit(1);
            }
            String[] bindingKeys = Arrays.copyOfRange(args, 1, args.length);
            runConsumer(bindingKeys);
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

            channel.exchangeDeclare(EXCHANGE_NAME, "topic");

            System.out.println("输入格式: 来源.级别 消息，如: auth.error 登录失败");
            System.out.println("来源: auth, web, db, cron, ...");
            System.out.println("级别: debug, info, warning, error");
            System.out.println("输入 quit 退出");

            Scanner scanner = new Scanner(System.in);
            while (true) {
                String text = scanner.nextLine();
                if (text.equalsIgnoreCase("quit")) break;

                String[] parts = text.split(" ", 2);
                if (parts.length != 2) {
                    System.out.println("格式错误，请用: 来源.级别 消息");
                    continue;
                }

                String routingKey = parts[0];
                String message = parts[1];

                channel.basicPublish(EXCHANGE_NAME, routingKey, null,
                    message.getBytes(StandardCharsets.UTF_8));
                System.out.println(" [x] 已发送: [" + routingKey + "] " + message);
            }
        }
    }

    static void runConsumer(String[] bindingKeys) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        channel.exchangeDeclare(EXCHANGE_NAME, "topic");

        String queueName = channel.queueDeclare().getQueue();

        // ============================================================
        // 绑定多个通配符模式
        //
        // 通配符规则：
        //   * (星号)  匹配一个单词
        //     例如: *.error 匹配 auth.error，但不匹配 auth.web.error
        //   # (井号)  匹配零个或多个单词
        //     例如: auth.# 匹配 auth、auth.error、auth.web.info
        //
        // 常见模式示例：
        //   "auth.#"      → 所有 auth 相关的消息
        //   "*.error"     → 所有来源的 error 消息
        //   "#"           → 所有消息（类似 Fanout）
        //   "web.*"       → web 来源的所有级别
        // ============================================================
        for (String bindingKey : bindingKeys) {
            channel.queueBind(queueName, EXCHANGE_NAME, bindingKey);
            System.out.println(" [*] 绑定了: " + bindingKey);
        }

        System.out.println(" [*] 等待消息（模式: " + String.join(", ", bindingKeys) + "），按 Ctrl+C 退出...");

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            System.out.println(" [x] [" + delivery.getEnvelope().getRoutingKey() + "] " + message);
        };

        channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {});

        Thread.sleep(Long.MAX_VALUE);
    }
}
