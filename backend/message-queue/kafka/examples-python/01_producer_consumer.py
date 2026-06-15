"""
01 基础生产/消费
================

教学目的：演示 Kafka 最核心的生产者-消费者模型

关键概念：
  - Producer:   发送消息到 Topic
  - Consumer:   从 Topic 拉取消息
  - Topic:      消息的逻辑分类
  - Partition:  Topic 的分片，Kafka 并行处理的基本单位
  - Offset:     分区内消息的序号

与 RabbitMQ 的关键区别：
  - Kafka 是 Pull 模式（消费者主动拉取），RabbitMQ 是 Push（Broker 推送）
  - Kafka 消息不消费即删除，而是按时间/大小保留（可回溯消费）
  - Kafka 天然支持消息持久化

运行方式（开两个终端）：
  终端 1: python 01_producer_consumer.py consumer
  终端 2: python 01_producer_consumer.py producer

前置条件：docker-compose up -d
"""

import sys
import time
from kafka import KafkaProducer, KafkaConsumer
from kafka.errors import NoBrokersAvailable


def wait_for_kafka():
    """等待 Kafka 就绪（容器启动需要时间）"""
    print(" [*] 等待 Kafka 就绪...")
    for i in range(30):
        try:
            consumer = KafkaConsumer(
                bootstrap_servers='localhost:9092',
                request_timeout_ms=2000
            )
            consumer.close()
            print(" [✓] Kafka 已就绪")
            return True
        except NoBrokersAvailable:
            time.sleep(2)
            print(f"    等待中... ({i+1}/30)")
    print(" [✗] Kafka 未就绪，请检查 docker-compose up -d")
    return False


def run_producer():
    """生产者：发送消息到 Kafka Topic"""
    if not wait_for_kafka():
        return

    # ============================================================
    # KafkaProducer 配置说明
    #
    # bootstrap_servers:  Kafka 集群地址
    # value_serializer:   消息体序列化函数（Kafka 要求 bytes）
    # key_serializer:     键序列化（可选，用于分区路由）
    # acks:               'all' 表示等待所有副本确认
    # batch_size:         批量发送大小，增大提升吞吐
    # linger_ms:          批量发送等待时间，适当增大提升批处理率
    # ============================================================
    producer = KafkaProducer(
        bootstrap_servers='localhost:9092',
        value_serializer=lambda v: v.encode('utf-8'),
        key_serializer=lambda k: k.encode('utf-8'),
        acks='all',
        batch_size=16384,
        linger_ms=5,
    )

    topic = 'hello_topic'

    print(f"输入消息，输入 quit 退出:")

    while True:
        message = input(" > ")
        if message.lower() == 'quit':
            break

        # ============================================================
        # 发送消息到 Topic
        #
        # topic: 目标主题（如果不存在且 auto.create.topics.enable=true，
        #         Kafka 会自动创建，默认 3 个分区）
        # key:   可选，用于决定写入哪个分区（相同 key → 相同分区）
        # value: 消息体
        #
        # send() 是异步的，返回 Future
        # .get() 可以同步等待结果
        # ============================================================
        future = producer.send(
            topic,
            key='demo',        # 相同 key 会路由到同一分区
            value=message,
        )

        # 同步等待发送结果（生产环境建议异步回调）
        metadata = future.get(timeout=10)
        print(
            f" [x] 已发送: topic={metadata.topic}, "
            f"partition={metadata.partition}, "
            f"offset={metadata.offset}"
        )

    # 关闭生产者（会 flush 缓冲区中的剩余消息）
    producer.close()


def run_consumer():
    """消费者：从 Topic 拉取消息"""
    if not wait_for_kafka():
        return

    # ============================================================
    # KafkaConsumer 配置说明
    #
    # bootstrap_servers:   Kafka 地址
    # group_id:            消费者组 ID（重要！）
    # auto_offset_reset:   'earliest' 从头消费，'latest' 只消费新消息
    # enable_auto_commit:  自动提交 Offset（生产环境建议手动）
    # value_deserializer:  反序列化函数
    #
    # 关于 group_id:
    #   同一个 group 内的消费者分摊分区（每条消息只被组内一个消费者处理）
    #   不同 group 独立消费（一条消息可以被多个组消费）
    # ============================================================
    consumer = KafkaConsumer(
        'hello_topic',
        bootstrap_servers='localhost:9092',
        group_id='hello_group',
        auto_offset_reset='earliest',    # 从头开始消费
        enable_auto_commit=True,
        auto_commit_interval_ms=5000,    # 每 5 秒自动提交
        value_deserializer=lambda v: v.decode('utf-8') if v else None,
        key_deserializer=lambda k: k.decode('utf-8') if k else None,
    )

    print(" [*] 等待消息，按 Ctrl+C 退出...")
    print("     （首次启动可能会先输出历史消息）")

    try:
        # ============================================================
        # poll() 轮询拉取消息
        #
        # Kafka 消费者不是"等"消息，而是主动"拉"消息
        # 参数是超时时间（毫秒）
        #
        # 返回: {TopicPartition: [ConsumerRecord, ...]}
        # ============================================================
        for message in consumer:
            print(
                f" [x] 收到: "
                f"key={message.key}, "
                f"value={message.value}, "
                f"partition={message.partition}, "
                f"offset={message.offset}"
            )

    except KeyboardInterrupt:
        print("\n [*] 退出...")
    finally:
        consumer.close()


if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('producer', 'consumer'):
        print("用法: python 01_producer_consumer.py [producer|consumer]")
        sys.exit(1)

    if sys.argv[1] == 'producer':
        run_producer()
    else:
        run_consumer()
