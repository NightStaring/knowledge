"""
04 幂等生产者 + 可靠性
======================

教学目的：演示 Kafka 生产者的可靠性配置

关键概念：
  - acks=all:             等待所有副本确认，保证不丢
  - 幂等生产者:           防止网络重试导致消息重复
  - 手动提交 Offset:      消费者处理完再提交，防止处理失败时丢消息
  - 消费者幂等:           消费端去重，防止重复处理

场景：
  支付系统——消息不能丢，也不能重复处理

运行方式（开两个终端）：
  终端 1: python 04_reliability.py consumer
  终端 2: python 04_reliability.py producer

前置条件：docker-compose up -d
"""

import sys
import hashlib
from kafka import KafkaProducer, KafkaConsumer
from kafka.admin import KafkaAdminClient, NewTopic
from kafka.errors import TopicAlreadyExistsError, NoBrokersAvailable
from kafka.structs import OffsetAndMetadata, TopicPartition


def ensure_topic():
    try:
        admin = KafkaAdminClient(bootstrap_servers='localhost:9092')
        topic = NewTopic(name='reliable_topic', num_partitions=2, replication_factor=1)
        admin.create_topics([topic])
        admin.close()
    except TopicAlreadyExistsError:
        pass
    except NoBrokersAvailable:
        print(" [✗] Kafka 不可用")
        sys.exit(1)


def run_producer():
    """生产者：开启幂等和 acks=all"""
    ensure_topic()

    # ============================================================
    # 可靠生产者配置
    #
    # 1. acks='all':
    #    生产者等待所有 ISR 副本都写入成功才返回确认
    #    如果 Leader 宕机，消息不会丢失（因为 Follower 也有）
    #
    # 2. enable_idempotence=True:
    #    开启幂等后，Kafka 自动去重
    #    原理：每条消息有 (ProducerID, 序列号)，
    #          Broker 根据 (PID, 序列号) 去重
    #    自动设置 acks=all 和 retries=MAX
    #
    # 3. max_in_flight_requests_per_connection=5:
    #    未确认请求数，=5 是幂等模式下的安全值
    #    如果 =1，则严格有序但吞吐降低
    # ============================================================
    producer = KafkaProducer(
        bootstrap_servers='localhost:9092',
        value_serializer=lambda v: v.encode('utf-8'),
        key_serializer=lambda k: k.encode('utf-8'),
        acks='all',                          # 等待所有副本确认
        enable_idempotence=True,             # 幂等生产者
        max_in_flight_requests_per_connection=5,  # 幂等模式最大为 5
        retries=5,                           # 重试次数
        retry_backoff_ms=1000,               # 重试间隔
        compression_type='snappy',           # 压缩
        batch_size=32768,
        linger_ms=10,
    )

    print("可靠生产者已启动")
    print("  - acks=all: 等待所有副本确认")
    print("  - 幂等: 防止重复消息")
    print("  - 压缩: snappy")
    print()
    print("输入消息，输入 quit 退出:")
    print("（每条消息会自动带唯一 ID，用于消费者去重）")

    msg_id = 0
    while True:
        message = input(" > ")
        if message.lower() == 'quit':
            break

        # ============================================================
        # 消息中嵌入唯一 ID，供消费者去重
        #
        # 即使 Kafka 幂等保证了"不重复投递"，
        # 消费者端仍可能因业务逻辑异常导致重复处理，
        # 所以消费端也要幂等
        # ============================================================
        unique_id = f"msg-{hashlib.md5(f'{msg_id}:{message}'.encode()).hexdigest()[:8]}"
        value = f"{unique_id}|{message}"

        future = producer.send(
            'reliable_topic',
            key=unique_id,     # 用唯一 ID 作为 key，确保相同 key 到同一分区
            value=value,
        )
        metadata = future.get(timeout=10)

        print(
            f" [x] 已发送: id={unique_id}, "
            f"partition={metadata.partition}, "
            f"offset={metadata.offset}"
        )
        msg_id += 1

    producer.close()


def run_consumer():
    """消费者：手动提交 Offset + 幂等去重"""
    ensure_topic()

    consumer = KafkaConsumer(
        'reliable_topic',
        bootstrap_servers='localhost:9092',
        group_id='reliable_group',
        auto_offset_reset='earliest',
        enable_auto_commit=False,    # 手动提交 Offset！
        value_deserializer=lambda v: v.decode('utf-8') if v else None,
        key_deserializer=lambda k: k.decode('utf-8') if k else None,
    )

    # ============================================================
    # 模拟已处理消息的缓存（生产环境用 Redis/DB）
    #
    # 这是消费端幂等的关键：
    #   处理前先检查消息 ID 是否已处理过
    #   已处理 → 跳过（但还是要提交 Offset，防止一直重试）
    #   未处理 → 处理并记录
    # ============================================================
    processed_ids = set()

    print(" [*] 等待消息...")
    print("     手动提交 Offset，处理完再确认")
    print("     已处理的消息 ID 会被记录，防止重复消费\n")

    try:
        for message in consumer:
            # 解析消息中的唯一 ID
            full_value = message.value
            if full_value and '|' in full_value:
                unique_id, actual_message = full_value.split('|', 1)
            else:
                unique_id = "unknown"
                actual_message = full_value

            print(f"\n [←] 收到: {actual_message}")
            print(f"     ID: {unique_id}, partition={message.partition}, offset={message.offset}")

            # ============================================================
            # 幂等检查
            # ============================================================
            if unique_id in processed_ids:
                print(f"     ⚠️ 消息已处理过，跳过（但仍提交 Offset）")
            else:
                # 模拟业务处理
                print(f"     ✓ 处理中...")

                # 记录已处理（模拟）
                processed_ids.add(unique_id)
                print(f"     ✓ 已记录，当前去重缓存大小: {len(processed_ids)}")

            # ============================================================
            # 手动提交 Offset
            #
            # 确保"业务处理 + Offset 提交"要么都成功，要么都失败
            # 如果这里崩溃了，重启后会从上次提交的位置重新消费
            # ============================================================
            consumer.commit()
            print(f"     ✓ Offset 已提交")

    except KeyboardInterrupt:
        print("\n [*] 退出")
    finally:
        consumer.close()


if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('producer', 'consumer'):
        print("用法: python 04_reliability.py [producer|consumer]")
        sys.exit(1)

    if sys.argv[1] == 'producer':
        run_producer()
    else:
        run_consumer()
