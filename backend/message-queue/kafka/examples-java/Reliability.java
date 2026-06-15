/**
 * 04 幂等生产者 + 可靠性
 * ======================
 *
 * 教学目的：演示 Kafka 生产者的可靠性配置
 *
 * 关键概念：
 *   - acks=all:             等待所有副本确认，保证不丢
 *   - enable.idempotence:   幂等生产者，防止重复
 *   - 手动提交 Offset:      处理完再确认
 *   - 消费者幂等:           业务键去重
 *
 * 运行方式（开两个终端）：
 *   mvn exec:java -Dexec.mainClass="Reliability" -Dexec.args="consumer"
 *   mvn exec:java -Dexec.mainClass="Reliability" -Dexec.args="producer"
 */

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class Reliability {

    private static final String TOPIC = "reliable_topic";

    public static void main(String[] args) {
        if (args.length != 1 || (!args[0].equals("producer") && !args[0].equals("consumer"))) {
            System.out.println("用法: mvn exec:java -Dexec.mainClass=\"Reliability\" -Dexec.args=\"[producer|consumer]\"");
            System.exit(1);
        }

        ensureTopic();

        if (args[0].equals("producer")) {
            runProducer();
        } else {
            runConsumer();
        }
    }

    static void ensureTopic() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        try (AdminClient admin = AdminClient.create(props)) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get();
        } catch (Exception ignored) {}
    }

    // ================================================================
    // 可靠生产者
    // ================================================================
    static void runProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // ============================================================
        // 可靠生产者配置
        //
        // 1. acks=all:
        //    生产者等待所有 ISR 副本都写入成功才返回确认
        //    如果 Leader 宕机，消息不会丢失（因为 Follower 也有）
        //
        // 2. enable.idempotence=true:
        //    开启幂等后，Kafka 自动去重
        //    原理：每条消息有 (ProducerID, 序列号)，
        //          Broker 根据 (PID, 序列号) 去重
        //    自动设置 acks=all 和 retries=MAX
        //
        // 3. max.in.flight.requests.per.connection=5:
        //    幂等模式下的安全值，=1 可严格有序但降低吞吐
        //
        // 4. compression.type=snappy:
        //    压缩消息体，减小网络开销
        // ============================================================
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        props.put(ProducerConfig.RETRIES_CONFIG, 5);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 1000);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {

            System.out.println("可靠生产者已启动");
            System.out.println("  - acks=all: 等待所有副本确认");
            System.out.println("  - 幂等: 防止重复消息");
            System.out.println("  - 压缩: snappy\n");

            System.out.println("输入消息，输入 quit 退出:");
            System.out.println("（每条消息会自动带唯一 ID，用于消费者去重）");

            Scanner scanner = new Scanner(System.in);
            int msgId = 0;

            while (true) {
                String message = scanner.nextLine();
                if (message.equalsIgnoreCase("quit")) break;

                // ============================================================
                // 生成唯一 ID（生产环境用 UUID）
                // 消息格式: "唯一ID|消息内容"
                // ============================================================
                String uniqueId = "msg-" + UUID.randomUUID().toString().substring(0, 8);
                String value = uniqueId + "|" + message;

                try {
                    RecordMetadata metadata = producer.send(
                        new ProducerRecord<>(TOPIC, uniqueId, value)
                    ).get();

                    System.out.printf(
                        " [x] 已发送: id=%s, partition=%d, offset=%d%n",
                        uniqueId, metadata.partition(), metadata.offset()
                    );
                } catch (InterruptedException | ExecutionException e) {
                    System.err.println(" [✗] 发送失败: " + e.getMessage());
                }
                msgId++;
            }
        }
    }

    // ================================================================
    // 消费者：手动提交 Offset + 幂等去重
    // ================================================================
    static void runConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "reliable_group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");  // 手动提交！
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        // ============================================================
        // 模拟已处理消息的缓存（生产环境用 Redis/DB）
        //
        // 这是消费端幂等的关键：
        //   处理前先检查消息 ID 是否已处理过
        //   已处理 → 跳过（但还是要提交 Offset）
        //   未处理 → 处理并记录
        // ============================================================
        Set<String> processedIds = new HashSet<>();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));

            System.out.println(" [*] 等待消息...");
            System.out.println("     手动提交 Offset，处理完再确认");
            System.out.println("     已处理的消息 ID 会被记录，防止重复消费\n");

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));

                for (ConsumerRecord<String, String> record : records) {
                    // 解析消息中的唯一 ID
                    String fullValue = record.value();
                    String uniqueId = "unknown";
                    String actualMessage = fullValue;

                    if (fullValue != null && fullValue.contains("|")) {
                        String[] parts = fullValue.split("\\|", 2);
                        uniqueId = parts[0];
                        actualMessage = parts[1];
                    }

                    System.out.printf("\n [←] 收到: %s%n", actualMessage);
                    System.out.printf("     ID: %s, partition=%d, offset=%d%n",
                        uniqueId, record.partition(), record.offset());

                    // ============================================================
                    // 幂等检查
                    // ============================================================
                    if (processedIds.contains(uniqueId)) {
                        System.out.println("     ⚠️ 消息已处理过，跳过");
                    } else {
                        System.out.println("     ✓ 处理中...");
                        processedIds.add(uniqueId);
                        System.out.printf("     ✓ 已记录，当前去重缓存大小: %d%n", processedIds.size());
                    }

                    // ============================================================
                    // 手动提交 Offset
                    //
                    // commitSync(): 同步提交，阻塞直到完成
                    // commitAsync(): 异步提交，不阻塞
                    //
                    // 生产环境建议：commitAsync() 正常情况 + commitSync() 关闭前
                    // ============================================================
                    consumer.commitSync();
                    System.out.println("     ✓ Offset 已提交");
                }
            }
        }
    }
}
