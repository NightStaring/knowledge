"""
05 事务消息
============

教学目的：演示 Kafka 事务——保证"发消息"和"本地操作"原子完成

关键概念：
  - 事务性生产者:   初始化事务 → 发送消息 → 提交/回滚事务
  - 事务性消费:     消费消息时，Offset 提交和业务操作在同一事务中
  - Exactly-Once:   事务 + 幂等 = 恰好一次语义
  - 隔离级别:       read_committed 只读取已提交的事务消息

场景：
  银行转账——扣款消息和记录更新要么都成功，要么都失败

运行方式（开两个终端）：
  终端 1: python 05_transactions.py consumer
  终端 2: python 05_transactions.py producer

前置条件：docker-compose up -d
"""

import sys
import time
from kafka import KafkaProducer, KafkaConsumer
from kafka.admin import KafkaAdminClient, NewTopic
from kafka.errors import TopicAlreadyExistsError, NoBrokersAvailable
from kafka.coordinator.assignors.roundrobin import RoundRobinPartitionAssignor


def ensure_topics():
    """
    创建所需 topic
    事务需要内部 topic __transaction_state（Kafka 自动管理）
    """
    try:
        admin = KafkaAdminClient(bootstrap_servers='localhost:9092')
        for topic_name in ['transfer_topic', 'transfer_result']:
            try:
                topic = NewTopic(name=topic_name, num_partitions=2, replication_factor=1)
                admin.create_topics([topic])
            except TopicAlreadyExistsError:
                pass
        admin.close()
    except NoBrokersAvailable:
        print(" [✗] Kafka 不可用")
        sys.exit(1)


def run_producer():
    """
    生产者：使用事务发送消息

    事务流程：
      1. 设置 transactional_id（唯一标识生产者，重启后保持）
      2. init_transactions() 初始化事务
      3. begin_transaction() 开始事务
      4. send() 发送消息（多条）
      5. commit_transaction() 提交 → 全部可见
         或 abort_transaction() 回滚 → 全部不可见
    """
    ensure_topics()

    # ============================================================
    # 事务性生产者配置
    #
    # transactional_id:
    #   唯一的事务 ID，用于恢复未完成的事务
    #   即使生产者重启，Broker 也能识别并处理未完成的事务
    #
    # enable_idempotence=True:
    #   事务必须开启幂等
    #
    # 注意：同一个 transactional_id 只能有一个活跃生产者
    #       第二个启动会强制关闭第一个（僵尸防护）
    # ============================================================
    producer = KafkaProducer(
        bootstrap_servers='localhost:9092',
        value_serializer=lambda v: v.encode('utf-8'),
        key_serializer=lambda k: k.encode('utf-8'),
        acks='all',
        enable_idempotence=True,
        transactional_id='transfer-txn-producer',  # 事务 ID
        max_in_flight_requests_per_connection=5,
    )

    # 初始化事务（与 Kafka 协调器建立事务关系）
    producer.init_transactions()

    print("转账事务演示")
    print("=" * 50)
    print("输入格式: 用户ID 金额，如: user1 100")
    print("  - 正数表示转入，负数表示转出")
    print("  - 输入 'abort' 模拟事务回滚")
    print("  - 输入 quit 退出\n")

    while True:
        text = input(" > ")
        if text.lower() == 'quit':
            break

        try:
            # ============================================================
            # 开始事务
            # ============================================================
            producer.begin_transaction()

            if text.lower() == 'abort':
                print(" [*] 模拟事务回滚...")
                producer.abort_transaction()
                print(" [✗] 事务已回滚（消息不会被消费者看到）")
                continue

            # 解析输入
            user_id, amount = text.split()
            amount = float(amount)

            if amount > 0:
                action = "转入"
            else:
                action = "转出"

            # ============================================================
            # 在事务中发送多条消息
            #
            # 这些消息在事务提交前，设置了 isolation.level=read_committed
            # 的消费者是看不到的
            # ============================================================
            # 发送转账记录
            transfer_msg = f"{action}: {user_id} 金额 {abs(amount)}"
            producer.send('transfer_topic', key=user_id, value=transfer_msg)

            # 发送结果通知（模拟后续流程）
            result_msg = f"完成{action}: {user_id} 余额变动 {amount}"
            producer.send('transfer_result', key=user_id, value=result_msg)

            # ============================================================
            # 提交事务
            #
            # 提交后，所有消息同时变为可见
            # 如果提交前崩溃，事务会被标记为"中止"
            # ============================================================
            producer.commit_transaction()
            print(f" [✓] 事务已提交: {transfer_msg}")

        except Exception as e:
            print(f" [✗] 事务失败: {e}")
            try:
                producer.abort_transaction()
            except Exception:
                pass

    producer.close()


def run_consumer():
    """
    消费者：使用 read_committed 隔离级别消费事务消息

    关键配置：
      isolation_level='read_committed':
        只读取已提交事务的消息
        未提交或已回滚的事务消息不可见
    """
    ensure_topics()

    # ============================================================
    # read_committed 消费者
    #
    # 默认是 read_uncommitted：能看到所有消息（包括未提交的）
    # read_committed：只看到已提交事务的消息
    #
    # 在金融、支付等场景，必须使用 read_committed
    # ============================================================
    consumer = KafkaConsumer(
        'transfer_topic', 'transfer_result',
        bootstrap_servers='localhost:9092',
        group_id='transfer_group',
        auto_offset_reset='earliest',
        enable_auto_commit=True,
        isolation_level='read_committed',   # 只读已提交
        value_deserializer=lambda v: v.decode('utf-8') if v else None,
        key_deserializer=lambda k: k.decode('utf-8') if k else None,
    )

    print(" [*] 等待转账消息...")
    print("     隔离级别: read_committed（只显示已提交的事务）")
    print("     先启动生产者执行转账，观察效果")
    print("     按 Ctrl+C 退出\n")

    try:
        for message in consumer:
            print(
                f" [x] [{message.topic}] "
                f"key={message.key}, "
                f"value={message.value}, "
                f"partition={message.partition}, "
                f"offset={message.offset}"
            )

    except KeyboardInterrupt:
        print("\n [*] 退出")
    finally:
        consumer.close()


if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('producer', 'consumer'):
        print("用法: python 05_transactions.py [producer|consumer]")
        sys.exit(1)

    if sys.argv[1] == 'producer':
        run_producer()
    else:
        run_consumer()
