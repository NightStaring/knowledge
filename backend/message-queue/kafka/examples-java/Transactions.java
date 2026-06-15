/**
 * 05 事务消息
 * ============
 *
 * 教学目的：演示 Kafka 事务——保证"发消息"和"本地操作"原子完成
 *
 * 关键概念：
 *   - 事务性生产者:   initTransactions → beginTransaction → send → commit/abort
 *   - transactional.id: 事务 ID，唯一标识生产者，重启后保持
 *   - 僵尸防护:       同一个 transactional.id 只能有一个活跃生产者
 *   - isolation.level: read_committed 只读取已提交的事务消息
 *
 * Java 与 Python 的差异：
 *   - Java 使用 initTransactions() / beginTransaction() / commitTransaction()
 *   - 消费者通过 isolation.level 配置隔离级别
 *
 * 运行方式（开两个终端）：
 *   mvn exec:java -Dexec.mainClass="Transactions" -Dexec.args="consumer"
 *   mvn exec:java -Dexec.mainClass="Transactions" -Dexec.args="producer"
 */

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

public class Transactions {

    private static final String TRANSFER_TOPIC = "transfer_topic";
    private static final String RESULT_TOPIC = "transfer_result";

    public static void main(String[] args) {
        if (args.length != 1 || (!args[0].equals("producer") && !args[0].equals("consumer"))) {
            System.out.println("用法: mvn exec:java -Dexec.mainClass=\"Transactions\" -Dexec.args=\"[producer|consumer]\"");
            System.exit(1);
        }

        ensureTopics();

        if (args[0].equals("producer")) {
            runProducer();
        } else {
            runConsumer();
        }
    }

    static void ensureTopics() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        try (AdminClient admin = AdminClient.create(props)) {
            admin.createTopics(List.of(
                new NewTopic(TRANSFER_TOPIC, 2, (short) 1),
                new NewTopic(RESULT_TOPIC, 2, (short) 1)
            )).all().get();
        } catch (Exception ignored) {}
    }

    // ================================================================
    // 事务性生产者
    // ================================================================
    static void runProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // ============================================================
        // transactional.id: 事务 ID
        //
        // 这是事务性生产者的核心配置！
        //   1. 唯一标识生产者，即使重启也能恢复未完成的事务
        //   2. 僵尸防护：同一个 ID 只能有一个活跃生产者
        //   3. 同一个 ID 的第二个生产者启动，会强制关闭第一个
        // ============================================================
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "transfer-txn-producer");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {

            // 初始化事务（与 Kafka 协调器建立事务关系）
            producer.initTransactions();

            System.out.println("转账事务演示");
            System.out.println("=".repeat(50));
            System.out.println("输入格式: 用户ID 金额，如: user1 100");
            System.out.println("  - 正数表示转入，负数表示转出");
            System.out.println("  - 输入 'abort' 模拟事务回滚");
            System.out.println("  - 输入 quit 退出\n");

            Scanner scanner = new Scanner(System.in);
            while (true) {
                String text = scanner.nextLine();
                if (text.equalsIgnoreCase("quit")) break;

                try {
                    // ============================================================
                    // 开始事务
                    // ============================================================
                    producer.beginTransaction();

                    if (text.equalsIgnoreCase("abort")) {
                        System.out.println(" [*] 模拟事务回滚...");
                        producer.abortTransaction();
                        System.out.println(" [✗] 事务已回滚（消息不会被消费者看到）");
                        continue;
                    }

                    // 解析输入: "user1 100"
                    String[] parts = text.split(" ");
                    if (parts.length != 2) {
                        System.out.println("格式错误，请用: 用户ID 金额");
                        producer.abortTransaction();
                        continue;
                    }

                    String userId = parts[0];
                    double amount = Double.parseDouble(parts[1]);
                    String action = amount > 0 ? "转入" : "转出";

                    // ============================================================
                    // 在事务中发送多条消息
                    //
                    // 这些消息在事务提交前，
                    // 设置了 isolation.level=read_committed 的消费者是看不到的
                    // ============================================================
                    // 发送转账记录
                    String transferMsg = action + ": " + userId + " 金额 " + Math.abs(amount);
                    producer.send(new ProducerRecord<>(TRANSFER_TOPIC, userId, transferMsg));

                    // 发送结果通知
                    String resultMsg = "完成" + action + ": " + userId + " 余额变动 " + amount;
                    producer.send(new ProducerRecord<>(RESULT_TOPIC, userId, resultMsg));

                    // ============================================================
                    // 提交事务
                    //
                    // 提交后，所有消息同时变为可见
                    // 如果提交前崩溃，事务会被标记为"中止"
                    // ============================================================
                    producer.commitTransaction();
                    System.out.println(" [✓] 事务已提交: " + transferMsg);

                } catch (Exception e) {
                    System.err.println(" [✗] 事务失败: " + e.getMessage());
                    try {
                        producer.abortTransaction();
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    // ================================================================
    // 消费者：read_committed 隔离级别
    // ================================================================
    static void runConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "transfer_group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        // ============================================================
        // isolation.level=read_committed
        //
        // 只读取已提交事务的消息
        // 未提交或已回滚的事务消息不可见
        //
        // 默认是 read_uncommitted：能看到所有消息（包括未提交的）
        // 在金融、支付等场景，必须使用 read_committed
        // ============================================================
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TRANSFER_TOPIC, RESULT_TOPIC));

            System.out.println(" [*] 等待转账消息...");
            System.out.println("     隔离级别: read_committed（只显示已提交的事务）");
            System.out.println("     先启动生产者执行转账，观察效果\n");

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));

                for (ConsumerRecord<String, String> record : records) {
                    System.out.printf(
                        " [x] [%s] key=%s, value=%s, partition=%d, offset=%d%n",
                        record.topic(), record.key(), record.value(),
                        record.partition(), record.offset()
                    );
                }
            }
        }
    }
}
