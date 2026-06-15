/**
 * 03 消费者组 + Rebalance
 * ========================
 *
 * 教学目的：演示消费者组的核心行为——分区分配和再均衡
 *
 * 关键概念：
 *   - Consumer Group: 一组消费者协作消费 Topic 的消息
 *   - 分区分配:       每个分区只能被组内一个消费者消费
 *   - Rebalance:      消费者加入/退出时触发分区重分配
 *   - ConsumerRebalanceListener: 监听 Rebalance 事件
 *
 * Java 与 Python 的差异：
 *   - Java 的 ConsumerRebalanceListener 是接口
 *   - subscribe() 时传入 Listener
 *   - 分区信息通过 TopicPartition 对象传递
 *
 * 规则：
 *   - 1 个消费者 → 消费所有分区
 *   - 2 个消费者 → 平分分区
 *   - 4 个消费者 → 3 个干活，1 个闲置
 *
 * 运行方式（开多个终端）：
 *   mvn exec:java -Dexec.mainClass="ConsumerGroups" -Dexec.args="consumer A"
 *   mvn exec:java -Dexec.mainClass="ConsumerGroups" -Dexec.args="consumer B"
 *   mvn exec:java -Dexec.mainClass="ConsumerGroups" -Dexec.args="producer"
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

public class ConsumerGroups {

    private static final String TOPIC = "group_demo";

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("用法:");
            System.out.println("  producer: mvn exec:java -Dexec.mainClass=\"ConsumerGroups\" -Dexec.args=\"producer\"");
            System.out.println("  consumer: mvn exec:java -Dexec.mainClass=\"ConsumerGroups\" -Dexec.args=\"consumer <ID>\"");
            System.exit(1);
        }

        ensureTopic();

        if (args[0].equals("producer")) {
            runProducer();
        } else if (args[0].equals("consumer") && args.length >= 2) {
            runConsumer(args[1]);
        } else {
            System.out.println("用法: ... consumer <消费者ID>");
            System.exit(1);
        }
    }

    static void ensureTopic() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        try (AdminClient admin = AdminClient.create(props)) {
            admin.createTopics(List.of(
                new NewTopic(TOPIC, 3, (short) 1)
            )).all().get();
        } catch (Exception ignored) {}
    }

    static void runProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            System.out.println("输入消息，输入 quit 退出:");
            int index = 0;

            Scanner scanner = new Scanner(System.in);
            while (true) {
                String message = scanner.nextLine();
                if (message.equalsIgnoreCase("quit")) break;

                String value = "msg-" + (index++) + ": " + message;
                try {
                    RecordMetadata metadata = producer.send(
                        new ProducerRecord<>(TOPIC, value)
                    ).get();
                    System.out.printf(" [x] 已发送: %s, partition=%d%n", value, metadata.partition());
                } catch (InterruptedException | ExecutionException e) {
                    System.err.println(" [✗] 发送失败: " + e.getMessage());
                }
            }
        }
    }

    // ================================================================
    // 消费者组 + Rebalance 监听器
    // ================================================================
    static void runConsumer(String consumerId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "demo_group");     // 同一个组！
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {

            // ============================================================
            // 注册 Rebalance 监听器
            //
            // ConsumerRebalanceListener 有两个回调：
            //
            // 1. onPartitionsRevoked(Collection<TopicPartition>)
            //    分区被撤销时调用
            //    原因：有消费者加入/退出，需要重新分配分区
            //    应在此时提交 Offset
            //
            // 2. onPartitionsAssigned(Collection<TopicPartition>)
            //    分配了新分区时调用
            //    Rebalance 完成后的回调
            //    可在此初始化状态、预热缓存
            // ============================================================
            consumer.subscribe(List.of(TOPIC), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    System.out.printf("\n ⚠️ [%s] 分区被撤销: %s%n", consumerId,
                        partitions.stream().map(TopicPartition::partition).toList());
                    System.out.println("    有消费者加入/退出，正在 Rebalance...\n");
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    System.out.printf(" ✅ [%s] 分配了新分区: %s%n", consumerId,
                        partitions.stream().map(TopicPartition::partition).toList());
                }
            });

            System.out.printf("\n [*] 消费者 [%s] 启动，等待消息...%n", consumerId);
            System.out.println("     打开新终端再启动一个消费者，观察 Rebalance\n");

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));

                for (ConsumerRecord<String, String> record : records) {
                    System.out.printf(
                        " [%s] 收到: value=%s, partition=%d, offset=%d%n",
                        consumerId, record.value(), record.partition(), record.offset()
                    );
                }
            }
        }
    }
}
