"""
03 消费者组 + Rebalance
========================

教学目的：演示消费者组的核心行为——分区分配和再均衡

关键概念：
  - Consumer Group: 一组消费者协作消费 Topic 的消息
  - 分区分配:       每个分区只能被组内一个消费者消费
  - Rebalance:      消费者加入/退出时触发分区重分配
  - 闲置消费者:     消费者数 > 分区数时，部分消费者闲置

规则：
  - 1 个消费者 → 消费所有分区
  - 2 个消费者 → 平分分区（3 个分区：一个 2 个，一个 1 个）
  - 3 个消费者 → 各 1 个分区
  - 4 个消费者 → 3 个干活，1 个闲置

运行方式（开多个终端）：
  终端 1: python 03_consumer_groups.py consumer A   # 消费者 A
  终端 2: python 03_consumer_groups.py consumer B   # 消费者 B（加进来触发 Rebalance）
  终端 3: python 03_consumer_groups.py consumer C   # 消费者 C（再加一个）
  终端 4: python 03_consumer_groups.py producer     # 发消息，观察谁收到了

前置条件：docker-compose up -d
"""

import sys
import time
from kafka import KafkaProducer, KafkaConsumer
from kafka.admin import KafkaAdminClient, NewTopic
from kafka.errors import TopicAlreadyExistsError, NoBrokersAvailable
from kafka.consumer.group import ConsumerRebalanceListener


def ensure_topic():
    """确保有 3 个分区的 topic"""
    try:
        admin = KafkaAdminClient(bootstrap_servers='localhost:9092')
        topic = NewTopic(
            name='group_demo',
            num_partitions=3,
            replication_factor=1
        )
        admin.create_topics([topic])
        admin.close()
    except TopicAlreadyExistsError:
        pass
    except NoBrokersAvailable:
        print(" [✗] Kafka 不可用，请先执行 docker-compose up -d")
        sys.exit(1)


class RebalanceLogger(ConsumerRebalanceListener):
    """
    Rebalance 监听器

    当消费者加入/退出组触发 Rebalance 时，会回调这个监听器。
    生产环境中，你应该在这里做：
      - on_partitions_revoked:  提交 Offset、释放资源
      - on_partitions_assigned: 初始化状态、预热缓存
    """

    def __init__(self, consumer_id):
        self.consumer_id = consumer_id

    def on_partitions_revoked(self, partitions):
        """
        分区被撤销时调用
        原因：有消费者加入或退出，需要重新分配分区
        """
        print(f"\n ⚠️ [{self.consumer_id}] 分区被撤销: {[p.partition for p in partitions]}")
        print(f"    有消费者加入/退出，正在 Rebalance...\n")

    def on_partitions_assigned(self, partitions):
        """
        分配了新分区时调用
        这是 Rebalance 完成后的回调
        """
        print(f" ✅ [{self.consumer_id}] 分配了新分区: {[p.partition for p in partitions]}")


def run_producer():
    """生产者"""
    ensure_topic()

    producer = KafkaProducer(
        bootstrap_servers='localhost:9092',
        value_serializer=lambda v: v.encode('utf-8'),
    )

    print("输入消息，输入 quit 退出:")
    print("提示：可以开多个消费者终端，观察消息分布")

    index = 0
    while True:
        message = input(" > ")
        if message.lower() == 'quit':
            break

        # 给消息编号，方便追踪
        value = f"msg-{index}: {message}"
        future = producer.send('group_demo', value=value)
        metadata = future.get(timeout=10)
        print(f" [x] 已发送: {value}, partition={metadata.partition}")
        index += 1

    producer.close()


def run_consumer(consumer_id):
    """
    消费者：加入消费者组

    每个消费者指定一个唯一 ID，在同一个 group_id 下协作消费

    关键的消费组行为：
      1. 首次启动：加入组 → 分配分区
      2. 第二个消费者启动：触发 Rebalance → 重新分配
      3. 某个消费者退出：再次触发 Rebalance → 剩余消费者接管
    """
    ensure_topic()

    consumer = KafkaConsumer(
        'group_demo',
        bootstrap_servers='localhost:9092',
        group_id='demo_group',                    # 同一个组！
        auto_offset_reset='earliest',
        enable_auto_commit=True,
        value_deserializer=lambda v: v.decode('utf-8') if v else None,
    )

    # ============================================================
    # 注册 Rebalance 监听器
    #
    # 这样就能看到分区分配和撤销的日志
    # 生产环境中，on_partitions_revoked 里要提交 Offset
    # ============================================================
    consumer.subscribe(
        ['group_demo'],
        listener=RebalanceLogger(consumer_id)
    )

    print(f"\n [*] 消费者 [{consumer_id}] 启动，等待消息...")
    print("     打开新终端再启动一个消费者，观察 Rebalance")

    # 获取初始分配
    time.sleep(1)
    assignment = consumer.assignment()
    print(f"    当前分配的分区: {[p.partition for p in assignment]}")

    try:
        for message in consumer:
            print(
                f" [{consumer_id}] 收到: "
                f"value={message.value}, "
                f"partition={message.partition}, "
                f"offset={message.offset}"
            )

    except KeyboardInterrupt:
        print(f"\n [{consumer_id}] 退出...")
    finally:
        consumer.close()


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("用法:")
        print("  python 03_consumer_groups.py producer")
        print("  python 03_consumer_groups.py consumer <消费者ID>")
        print("示例:")
        print("  python 03_consumer_groups.py consumer A")
        print("  python 03_consumer_groups.py consumer B")
        sys.exit(1)

    if sys.argv[1] == 'producer':
        run_producer()
    elif sys.argv[1] == 'consumer':
        if len(sys.argv) != 3:
            print("请指定消费者 ID，如: consumer A")
            sys.exit(1)
        run_consumer(sys.argv[2])
    else:
        print(f"未知命令: {sys.argv[1]}")
        sys.exit(1)
