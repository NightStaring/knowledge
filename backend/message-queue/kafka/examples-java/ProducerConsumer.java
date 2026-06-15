/**
 * 01 基础生产/消费
 * ================
 *
 * 教学目的：演示 Kafka 最核心的生产者-消费者模型
 *
 * 与 Python 版本的主要差异：
 *   - Java 的 KafkaProducer/KafkaConsumer 配置通过 Properties 对象传递
 *   - Java 中 ProducerRecord 和 ConsumerRecords 是核心数据结构
 *   - Java 用 Duration 替代 int 作为超时参数
 *   - KafkaConsumer 的 poll() 返回 ConsumerRecords 对象
 *
 * 运行方式（开两个终端）：
 *   mvn exec:java -Dexec.mainClass="ProducerConsumer" -Dexec.args="consumer"
 *   mvn exec:java -Dexec.mainClass="ProducerConsumer" -Dexec.args="producer"
 */

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
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;

public class ProducerConsumer {

    private static final String TOPIC = "hello_topic";

    public static void main(String[] args) {
        if (args.length != 1 || (!args[0].equals("producer") && !args[0].equals("consumer"))) {
            System.out.println("用法: mvn exec:java -Dexec.mainClass=\"ProducerConsumer\" -Dexec.args=\"[producer|consumer]\"");
            System.exit(1);
        }

        if (args[0].equals("producer")) {
            runProducer();
        } else {
            runConsumer();
        }
    }

    // ================================================================
    // 生产者
    // ================================================================
    static void runProducer() {
        // ============================================================
        // 1. 配置生产者
        //
        // Java 中所有配置通过 Properties 对象传递
        // 可以使用字符串常量（ProducerConfig.*）或直接写字符串
        //
        // 必须配置的 3 项：
        //   bootstrap.servers:  Kafka 集群地址
        //   key.serializer:     key 的序列化类
        //   value.serializer:   value 的序列化类
        // ============================================================
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);

        // ============================================================
        // 2. 创建 KafkaProducer
        //
        // KafkaProducer 是线程安全的，可以在多线程中共享
        // 但建议一个应用只创建少数实例
        // ============================================================
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {

            System.out.println("输入消息，输入 quit 退出:");

            Scanner scanner = new Scanner(System.in);
            while (true) {
                String message = scanner.nextLine();
                if (message.equalsIgnoreCase("quit")) break;

                // ============================================================
                // 3. 创建 ProducerRecord
                //
                // ProducerRecord(主题名, key, value)
                //   如果不指定 key，消息轮询分配到各分区
                //   如果指定 key，相同 key 到同一分区
                //
                // send() 是异步的，返回 Future<RecordMetadata>
                // get() 阻塞等待发送结果
                // ============================================================
                ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC,
                    "demo",     // key
                    message     // value
                );

                try {
                    // 同步等待发送结果
                    RecordMetadata metadata = producer.send(record).get();
                    System.out.printf(
                        " [x] 已发送: topic=%s, partition=%d, offset=%d%n",
                        metadata.topic(),
                        metadata.partition(),
                        metadata.offset()
                    );
                } catch (InterruptedException | ExecutionException e) {
                    System.err.println(" [✗] 发送失败: " + e.getMessage());
                }
            }
        } // try-with-resources 自动关闭 producer
    }

    // ================================================================
    // 消费者
    // ================================================================
    static void runConsumer() {
        // ============================================================
        // 1. 配置消费者
        //
        // 必须配置的 4 项：
        //   bootstrap.servers:    Kafka 地址
        //   group.id:             消费者组 ID
        //   key.deserializer:     key 的反序列化类
        //   value.deserializer:   value 的反序列化类
        //
        // auto.offset.reset:     无初始 Offset 时的行为
        //   earliest: 从头消费
        //   latest:   只消费新消息（默认）
        //
        // enable.auto.commit:    自动提交 Offset
        // ============================================================
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "hello_group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        // ============================================================
        // 2. 创建 KafkaConsumer
        //
        // KafkaConsumer 不是线程安全的！
        // 每个消费线程应该有自己的 KafkaConsumer 实例
        // ============================================================
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {

            // 订阅 Topic
            consumer.subscribe(List.of(TOPIC));

            System.out.println(" [*] 等待消息，按 Ctrl+C 退出...");

            // ============================================================
            // 3. 轮询拉取消息
            //
            // Kafka 消费者不是"等"消息，而是主动"拉"消息
            // poll(Duration) 每隔指定时间拉取一次
            //
            // 返回 ConsumerRecords 包含所有分区的消息
            // 遍历获取每条消息的详细信息
            // ============================================================
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));

                for (ConsumerRecord<String, String> record : records) {
                    System.out.printf(
                        " [x] 收到: key=%s, value=%s, partition=%d, offset=%d%n",
                        record.key(),
                        record.value(),
                        record.partition(),
                        record.offset()
                    );
                }
            }
        }
    }
}
