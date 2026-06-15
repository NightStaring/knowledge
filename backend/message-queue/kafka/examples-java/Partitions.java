/**
 * 02 分区策略演示
 * ================
 *
 * 教学目的：演示 Kafka 的分区机制——消息如何写入不同分区
 *
 * 关键概念：
 *   - 有 Key 的消息：相同 Key → 同一分区（哈希取模），保证分区内有序
 *   - 无 Key 的消息：轮询（Round-Robin），均匀分布到各分区
 *   - 分区内有序：同一分区内消息顺序与发送顺序一致
 *   - 分区间无序：不同分区的消息顺序无法保证
 *
 * 运行方式（开两个终端）：
 *   mvn exec:java -Dexec.mainClass="Partitions" -Dexec.args="consumer"
 *   mvn exec:java -Dexec.mainClass="Partitions" -Dexec.args="producer"
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
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class Partitions {

    private static final String TOPIC = "partition_demo";
    private static final int PARTITION_COUNT = 3;

    public static void main(String[] args) {
        if (args.length != 1 || (!args[0].equals("producer") && !args[0].equals("consumer"))) {
            System.out.println("用法: mvn exec:java -Dexec.mainClass=\"Partitions\" -Dexec.args=\"[producer|consumer]\"");
            System.exit(1);
        }

        ensureTopic();

        if (args[0].equals("producer")) {
            runProducer();
        } else {
            runConsumer();
        }
    }

    /** 确保 topic 存在并指定分区数 */
    static void ensureTopic() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");

        try (AdminClient admin = AdminClient.create(props)) {
            NewTopic topic = new NewTopic(TOPIC, PARTITION_COUNT, (short) 1);
            admin.createTopics(List.of(topic)).all().get();
            System.out.println(" [✓] Topic '" + TOPIC + "' 已就绪，" + PARTITION_COUNT + " 个分区");
        } catch (Exception e) {
            // Topic 可能已存在，忽略
        }
    }

    static void runProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {

            System.out.println("输入格式: 用户ID 消息，如: user1 下单");
            System.out.println("  - 相同 user ID 会进入同一分区（保证有序）");
            System.out.println("  - 不指定用户 ID，只输消息 → 轮询分配到各分区");
            System.out.println("输入 quit 退出\n");

            Scanner scanner = new Scanner(System.in);
            while (true) {
                String text = scanner.nextLine();
                if (text.equalsIgnoreCase("quit")) break;

                String key;
                String value;

                if (text.contains(" ")) {
                    String[] parts = text.split(" ", 2);
                    key = parts[0];
                    value = text;  // 完整内容作为 value
                } else {
                    key = null;    // 无 key，轮询分配
                    value = text;
                }

                ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC, key, value
                );

                try {
                    RecordMetadata metadata = producer.send(record).get();
                    String keyInfo = (key != null) ? key : "(无 key, 轮询)";
                    System.out.printf(
                        " [x] 已发送: key=%s, partition=%d, offset=%d%n",
                        keyInfo, metadata.partition(), metadata.offset()
                    );
                } catch (InterruptedException | ExecutionException e) {
                    System.err.println(" [✗] 发送失败: " + e.getMessage());
                }
            }
        }
    }

    static void runConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "partition_demo_group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));

            System.out.println(" [*] 等待消息，观察分区分布...");
            System.out.println("     注意：相同 key 的消息会出现在同一分区\n");

            // 用 Map 统计每个分区的消息数
            Map<Integer, Integer> partitionCounts = new HashMap<>();

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));

                for (ConsumerRecord<String, String> record : records) {
                    int partition = record.partition();
                    partitionCounts.merge(partition, 1, Integer::sum);

                    System.out.printf(
                        " [x] [分区 %d] key=%s, value=%s, offset=%d%n",
                        partition, record.key(), record.value(), record.offset()
                    );

                    // 打印累计统计
                    StringBuilder stats = new StringBuilder("     累计: ");
                    for (int p = 0; p < PARTITION_COUNT; p++) {
                        stats.append(String.format("分区%d=%d条 ",
                            p, partitionCounts.getOrDefault(p, 0)));
                    }
                    System.out.println(stats);
                }
            }
        }
    }
}
