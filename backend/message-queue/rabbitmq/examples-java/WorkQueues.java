/**
 * 02 工作队列 — 公平分发 / 任务队列
 * ==================================
 *
 * 教学目的：演示如何将耗时任务分发给多个 Worker
 *
 * 相比 01 的新概念：
 *   - 消息持久化：MessageProperties.PERSISTENT_TEXT_PLAIN
 *   - 公平分发：basicQos(1)
 *   - 手动 Ack：basicAck()
 *
 * 场景：
 *   假设有一个发送邮件的任务，每个邮件发送需要几秒钟
 *   多个 Worker 同时处理，谁空闲谁处理
 *
 * 运行方式（开三个终端）：
 *   mvn exec:java -Dexec.mainClass="WorkQueues" -Dexec.args="worker"
 *   mvn exec:java -Dexec.mainClass="WorkQueues" -Dexec.args="worker"
 *   mvn exec:java -Dexec.mainClass="WorkQueues" -Dexec.args="producer"
 */

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.MessageProperties;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class WorkQueues {

    private static final String QUEUE_NAME = "task_queue";

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || (!args[0].equals("producer") && !args[0].equals("worker"))) {
            System.out.println("用法: mvn exec:java -Dexec.mainClass=\"WorkQueues\" -Dexec.args=\"[producer|worker]\"");
            System.exit(1);
        }

        if (args[0].equals("producer")) {
            runProducer();
        } else {
            runWorker();
        }
    }

    static void runProducer() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            // durable=true: 队列持久化
            // RabbitMQ 重启后，这个队列仍然存在
            channel.queueDeclare(QUEUE_NAME, true, false, false, null);

            System.out.println("请输入任务消息（每行一个），输入 quit 退出:");
            Scanner scanner = new Scanner(System.in);

            while (true) {
                String message = scanner.nextLine();
                if (message.equalsIgnoreCase("quit")) break;

                // ============================================================
                // 消息持久化：MessageProperties.PERSISTENT_TEXT_PLAIN
                //
                // 告诉 RabbitMQ 把这个消息存到磁盘上
                // 即使 RabbitMQ 重启，消息也不会丢失
                //
                // 注意：这只保证"尽力持久化"，在消息刚收到但还没落盘时宕机仍可能丢失
                // 完整的可靠性需要配合 Publisher Confirm
                // ============================================================
                channel.basicPublish(
                    "",
                    QUEUE_NAME,
                    MessageProperties.PERSISTENT_TEXT_PLAIN,  // 消息持久化属性
                    message.getBytes(StandardCharsets.UTF_8)
                );
                System.out.println(" [x] 已发送: " + message);
            }
        }
    }

    static void runWorker() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        channel.queueDeclare(QUEUE_NAME, true, false, false, null);

        // ============================================================
        // 公平分发 (Fair Dispatch)
        //
        // basicQos(prefetchCount):
        //   在 Worker 处理完当前消息并确认之前，
        //   RabbitMQ 不会再给这个 Worker 派发新消息
        //
        // 没有这行的话，RabbitMQ 会一股脑把所有消息推给一个 Worker，
        // 导致"忙的忙死，闲的闲死"
        //
        // Java 中还有一个重载：
        //   basicQos(prefetchCount, global)
        //   global=true: 对整个 Connection 生效
        //   global=false: 只对当前 Channel 生效（默认）
        // ============================================================
        channel.basicQos(1);

        System.out.println(" [*] 等待任务，按 Ctrl+C 退出...");

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            System.out.println(" [x] 收到: " + message);

            // ============================================================
            // 模拟耗时任务：消息中有几个 '.' 就 sleep 几秒
            //
            // Java 的 Thread.sleep 需要处理 InterruptedException
            // 这里用 try-catch 包装
            // ============================================================
            try {
                int dots = (int) message.chars().filter(ch -> ch == '.').count();
                for (int i = 0; i < dots; i++) {
                    Thread.sleep(1000);
                    System.out.println("    处理中... " + (i + 1) + "/" + dots);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println(" [x] 处理完成");

            // ============================================================
            // 手动确认 (Manual Ack)
            //
            // basicAck(deliveryTag, multiple):
            //   deliveryTag: 消息的投递编号（递增计数器）
            //   multiple: true=确认所有 <= deliveryTag 的消息
            //
            // 只有调用了 basicAck，RabbitMQ 才会认为消息已处理完成并删除它
            // 如果 Worker 在处理过程中崩溃了（没有发 Ack），
            // RabbitMQ 会把这条消息重新投递给其他 Worker
            // ============================================================
            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
        };

        // autoAck=false: 手动 Ack！
        channel.basicConsume(QUEUE_NAME, false, deliverCallback, consumerTag -> {});

        Thread.sleep(Long.MAX_VALUE);
    }
}
