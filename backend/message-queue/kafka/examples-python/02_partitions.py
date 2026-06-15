"""
02 分区策略演示
================

教学目的：演示 Kafka 的分区机制——消息如何写入不同分区

关键概念：
  - 有 Key 的消息：相同 Key → 同一分区（哈希取模），保证分区内有序
  - 无 Key 的消息：轮询（Round-Robin），均匀分布到各分区
  - 分区内有序：同一分区内消息顺序与发送顺序一致
  - 分区间无序：不同分区的消息顺序无法保证

场景：
  订单系统：同一用户的订单必须有序，不同用户可并行处理

运行方式：
  开两个终端：
    终端 1: python 02_partitions.py consumer
    终端 2: python 02_partitions.py producer
          输入 "user1 下单"  → 所有 user1 的消息进同一分区
          输入 "user2 下单"  → 所有 user2 的消息进另一分区

前置条件：docker-compose up -d
"""

import sys
import time
from kafka import KafkaProducer, KafkaConsumer
from kafka.admin import KafkaAdminClient, NewTopic
from kafka.errors import TopicAlreadyExistsError, NoBrokersAvailable


def ensure_topic():
    """
    确保 topic 存在并指定分区数

    Kafka 自动创建 topic 时默认 3 个分区，
    这里显式创建 3 个分区以便观察分区行为
    """
    try:
        admin = KafkaAdminClient(bootstrap_servers='localhost:9092')
        topic = NewTopic(
            name='partition_demo',
            num_partitions=3,       # 3 个分区
            replication_factor=1
        )
        admin.create_topics([topic])
        print(" [✓] 已创建 topic 'partition_demo'，3 个分区")
        admin.close()
    except TopicAlreadyExistsError:
        print(" [*] Topic 已存在")
    except NoBrokersAvailable:
        print(" [✗] Kafka 不可用，请先执行 docker-compose up -d")
        sys.exit(1)


def run_producer():
    """生产者：演示分区策略"""
    ensure_topic()

    producer = KafkaProducer(
        bootstrap_servers='localhost:9092',
        value_serializer=lambda v: v.encode('utf-8'),
        key_serializer=lambda k: k.encode('utf-8'),
        acks='all',
    )

    print("输入格式: 用户ID 消息，如: user1 下单")
    print("  - 相同 user ID 会进入同一分区（保证有序）")
    print("  - 不指定用户 ID，只输消息 → 轮询分配到各分区")
    print("输入 quit 退出\n")

    while True:
        text = input(" > ")
        if text.lower() == 'quit':
            break

        # ============================================================
        # 分区策略演示
        #
        # 1. 有 key（如 "user1"）：
        #    partition = hash(key) % num_partitions
        #    相同 key → 相同分区 → 顺序保证
        #
        # 2. 无 key（key=None）：
        #    轮询（Round-Robin）或黏性分区（Sticky Partitioner）
        #    均匀分布 → 无顺序保证
        # ============================================================
        if ' ' in text:
            # 格式: "user1 下单" → key="user1", value="user1: 下单"
            user_id, action = text.split(' ', 1)
            key = user_id
            value = f"{user_id}: {action}"
        else:
            # 无 key：只输消息
            key = None
            value = text

        future = producer.send(
            'partition_demo',
            key=key,
            value=value,
        )
        metadata = future.get(timeout=10)

        key_info = key if key else "(无 key, 轮询)"
        print(
            f" [x] 已发送: key={key_info}, "
            f"partition={metadata.partition}, "
            f"offset={metadata.offset}"
        )

    producer.close()


def run_consumer():
    """消费者：观察消息如何分布到各分区"""
    ensure_topic()

    consumer = KafkaConsumer(
        'partition_demo',
        bootstrap_servers='localhost:9092',
        group_id='partition_demo_group',
        auto_offset_reset='earliest',
        enable_auto_commit=True,
        value_deserializer=lambda v: v.decode('utf-8') if v else None,
        key_deserializer=lambda k: k.decode('utf-8') if k else None,
    )

    print(" [*] 等待消息，观察分区分布...")
    print("     注意：相同 key 的消息会出现在同一分区")
    print("     按 Ctrl+C 退出\n")

    # 用字典统计每个分区的消息数
    partition_counts = {0: 0, 1: 0, 2: 0}

    try:
        for message in consumer:
            partition = message.partition
            partition_counts[partition] = partition_counts.get(partition, 0) + 1

            print(
                f" [x] [分区 {partition}] "
                f"key={message.key}, "
                f"value={message.value}, "
                f"offset={message.offset}"
            )
            print(
                f"     累计: 分区0={partition_counts.get(0, 0)}条, "
                f"分区1={partition_counts.get(1, 0)}条, "
                f"分区2={partition_counts.get(2, 0)}条"
            )

    except KeyboardInterrupt:
        print("\n [*] 退出")
    finally:
        consumer.close()


if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('producer', 'consumer'):
        print("用法: python 02_partitions.py [producer|consumer]")
        sys.exit(1)

    if sys.argv[1] == 'producer':
        run_producer()
    else:
        run_consumer()
