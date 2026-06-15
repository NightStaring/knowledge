/**
 * 07 可靠投递 — Publisher Confirm + 手动 Ack + 死信
 * ====================================================
 *
 * 教学目的：演示生产环境中保证消息不丢的完整方案
 *
 * 新概念：
 *   - Publisher Confirm:     生产者确认，保证消息成功到达 Broker
 *   - 死信队列 (DLQ):         多次处理失败的消息的最终归宿
 *   - 死信交换器 (DLX):       消息过期/被拒后的路由目标
 *   - TTL:                   消息存活时间
 *
 * Java 中 Publisher Confirm 的实现方式：
 *   1. channel.confirmSelect() 开启 Confirm 模式
 *   2. channel.waitForConfirms() 同步等待（推荐批量模式）
 *   3. channel.addConfirmListener() 异步监听
 *
 * 运行方式（开三个终端）：
 *   mvn exec:java -Dexec.mainClass="ReliableDelivery" -Dexec.args="producer"
 *   mvn exec:java -Dexec.mainClass="ReliableDelivery" -Dexec.args="consumer"
 *   mvn exec:java -Dexec.mainClass="ReliableDelivery" -Dexec.args="inspectDlq"
 */

import com.rabbitmq.client.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class ReliableDelivery {

    static final String MAIN_QUEUE = "reliable_queue";
    static final String DLX_EXCHANGE = "dlx_exchange";
    static final String DLQ = "dead_letter_queue";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法:");
            System.out.println("  mvn exec:java -Dexec.mainClass=\"ReliableDelivery\" -Dexec.args=\"producer\"");
            System.out.println("  mvn exec:java -Dexec.mainClass=\"ReliableDelivery\" -Dexec.args=\"consumer\"");
            System.out.println("  mvn exec:java -Dexec.mainClass=\"ReliableDelivery\" -Dexec.args=\"inspectDlq\"");
            System.exit(1);
        }

        switch (args[0]) {
            case "producer" -> runProducer();
            case "consumer" -> runConsumer();
            case "inspectDlq" -> runInspectDlq();
            default -> {
                System.out.println("未知命令: " + args[0]);
                System.exit(1);
            }
        }
    }

    /** 初始化基础设施：主队列 + 死信队列 + 死信交换器 */
    static void setupInfrastructure(Channel channel) throws Exception {
        // 1. 声明死信交换器
        channel.exchangeDeclare(DLX_EXCHANGE, "direct", true);

        // 2. 声明死信队列并绑定
        channel.queueDeclare(DLQ, true, false, false, null);
        channel.queueBind(DLQ, DLX_EXCHANGE, "dead");

        // ============================================================
        // 3. 声明主队列，配置死信属性和 TTL
        //
        // x-dead-letter-exchange:    消息被拒/过期后发到这个 Exchange
        // x-dead-letter-routing-key: 死信的路由键
        // x-message-ttl:            消息最大存活时间（毫秒）
        // x-max-length:             队列最大消息数
        //
        // Java 中用 Map<String, Object> 传递参数
        // ============================================================
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", "dead");
        args.put("x-message-ttl", 60000);     // 60 秒超时
        args.put("x-max-length", 1000);        // 最多 1000 条

        channel.queueDeclare(MAIN_QUEUE, true, false, false, args);

        System.out.println(" [*] 基础设施已就绪:");
        System.out.println("     主队列: " + MAIN_QUEUE);
        System.out.println("     死信交换器: " + DLX_EXCHANGE);
        System.out.println("     死信队列: " + DLQ);
    }

    // ================================================================
    // 生产者：带 Publisher Confirm 的可靠发送
    // ================================================================
    static void runProducer() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            setupInfrastructure(channel);

            // ============================================================
            // 开启 Publisher Confirm 模式
            //
            // 在 Confirm 模式下，每条消息发送后，
            // RabbitMQ 会异步通知生产者"已收到并处理"
            // 如果消息无法路由或 Broker 内部错误，会通知 Nack
            // ============================================================
            channel.confirmSelect();

            // ============================================================
            // 用于跟踪未确认消息的并发 Map
            //
            // ConcurrentSkipListMap 是线程安全的有序 Map
            // key: deliveryTag（消息的序列号）
            // value: 消息体（用于重试时重新发送）
            //
            // 当收到 ACK 时，从 Map 中移除
            // 当收到 NACK 时，从 Map 中取出重试
            // ============================================================
            ConcurrentNavigableMap<Long, String> outstandingConfirms =
                new ConcurrentSkipListMap<>();

            // ============================================================
            // 异步 Confirm 监听器
            //
            // handleAck:  Broker 确认了消息
            //   - deliveryTag: 确认的序列号
            //   - multiple:    true=确认所有 <= deliveryTag 的消息
            //
            // handleNack: Broker 拒绝了消息（需要重试）
            // ============================================================
            channel.addConfirmListener(new ConfirmListener() {
                @Override
                public void handleAck(long deliveryTag, boolean multiple) {
                    // 从待确认 Map 中移除已确认的消息
                    if (multiple) {
                        // 批量确认：移除所有 <= deliveryTag 的条目
                        outstandingConfirms.headMap(deliveryTag, true).clear();
                    } else {
                        outstandingConfirms.remove(deliveryTag);
                    }
                }

                @Override
                public void handleNack(long deliveryTag, boolean multiple) {
                    // Broker 拒绝了消息！需要重试
                    System.err.println(" [✗] Broker 返回 NACK: deliveryTag=" + deliveryTag);

                    // 取出被拒绝的消息
                    String failedMessage = outstandingConfirms.get(deliveryTag);
                    if (failedMessage != null) {
                        System.err.println("     需要重试: " + failedMessage);
                        // 生产环境：放入重试队列或记录日志
                    }
                }
            });

            System.out.println("输入消息，输入 quit 退出:");
            System.out.println("（输入 fail 模拟消费失败场景）");

            Scanner scanner = new Scanner(System.in);
            while (true) {
                String message = scanner.nextLine();
                if (message.equalsIgnoreCase("quit")) break;

                // ============================================================
                // 发送消息并获取序列号
                //
                // getNextPublishSeqNo() 返回下一条消息的序列号
                // 用于在 Confirm 回调中匹配确认
                // ============================================================
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .deliveryMode(2)  // 持久化
                    .messageId(UUID.randomUUID().toString())
                    .build();

                // 记录未确认的消息
                long seqNo = channel.getNextPublishSeqNo();
                outstandingConfirms.put(seqNo, message);

                channel.basicPublish(
                    "",
                    MAIN_QUEUE,
                    true,   // mandatory: 消息无法路由时返回给生产者
                    props,
                    message.getBytes(StandardCharsets.UTF_8)
                );

                System.out.println(" [x] 已发送: " + message + " (seqNo=" + seqNo + ")");
            }

            // ============================================================
            // 等待所有未确认的消息完成确认
            //
            // waitForConfirms(timeout):
            //   阻塞等待所有未确认的消息被 ACK 或 NACK
            //   超时抛异常，需要处理
            // ============================================================
            if (!outstandingConfirms.isEmpty()) {
                channel.waitForConfirms(5000);
                System.out.println(" [*] 所有消息已确认");
            }
        }
    }

    // ================================================================
    // 消费者：带手动 Ack 和死信的可靠消费
    // ================================================================
    static void runConsumer() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        setupInfrastructure(channel);

        // 公平分发
        channel.basicQos(1);

        System.out.println("\n [*] 等待消息（输入 'fail' 模拟失败，'crash' 模拟超时）...");
        System.out.println("     按 Ctrl+C 退出\n");

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            String messageId = delivery.getProperties().getMessageId();

            System.out.println("\n [←] 收到消息: " + message);
            System.out.println("    消息 ID: " + messageId);

            try {
                if (message.equals("fail")) {
                    throw new RuntimeException("模拟业务处理失败!");
                }

                if (message.equals("crash")) {
                    System.out.println("    模拟处理超时...");
                    Thread.sleep(10000);
                    System.out.println("    恢复");
                }

                // 业务处理成功
                System.out.println(" [✓] 处理成功");

                // ============================================================
                // basicAck: 告诉 RabbitMQ 消息已成功处理
                //
                // deliveryTag: 消息的投递编号
                // multiple: false=只确认当前这条
                // ============================================================
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                System.out.println("    已发送 Ack");

            } catch (Exception e) {
                System.out.println(" [✗] 处理失败: " + e.getMessage());

                // ============================================================
                // basicNack: 告诉 RabbitMQ 处理失败
                //
                // deliveryTag: 同上
                // multiple: false=只拒绝当前这条
                // requeue: false=不重新入队 → 进入死信队列
                //
                // requeue=true: 重新放回主队列尾部
                //   ⚠️ 如果消费逻辑有问题，会无限重试！
                //   所以一般 requeue=false，让死信队列兜底
                // ============================================================
                channel.basicNack(
                    delivery.getEnvelope().getDeliveryTag(),
                    false,     // multiple
                    false      // requeue → 进入死信队列
                );
                System.out.println("    已发送 Nack（消息进入死信队列）");
            }
        };

        channel.basicConsume(MAIN_QUEUE, false, deliverCallback, consumerTag -> {});

        Thread.sleep(Long.MAX_VALUE);
    }

    // ================================================================
    // 检查死信队列
    // ================================================================
    static void runInspectDlq() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            // passive=true: 只检查队列是否存在，不创建
            try {
                channel.queueDeclarePassive(DLQ);
            } catch (Exception e) {
                System.out.println("死信队列不存在，请先启动消费者");
                return;
            }

            com.rabbitmq.client.GetResponse response;
            System.out.println("死信队列 [" + DLQ + "] 中的消息:\n");

            int count = 0;
            while ((response = channel.basicGet(DLQ, false)) != null) {
                String message = new String(response.getBody(), StandardCharsets.UTF_8);
                System.out.println("  " + (++count) + ". " + message);
                System.out.println("    消息 ID: " + response.getProps().getMessageId());
                System.out.println();
            }

            if (count == 0) {
                System.out.println("死信队列为空");
            }
        }
    }
}
