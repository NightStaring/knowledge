"""
07 可靠投递 — Publisher Confirm + 手动 Ack + 死信
===================================================

教学目的：演示生产环境中保证消息不丢的完整方案

新概念：
  - Publisher Confirm: 生产者确认，保证消息成功到达 Broker
  - 死信队列 (DLQ):     多次处理失败的消息的最终归宿
  - 死信交换器 (DLX):   消息过期/被拒后的路由目标
  - TTL:               消息存活时间

流程：
  生产者 (Confirm) → 主队列 (TTL) → 消费失败 → 死信交换器 → 死信队列

运行方式（开两个终端）：
  终端 1: python 07_confirm_ack.py consumer
  终端 2: python 07_confirm_ack.py producer
        输入消息后看终端 1 收到，然后按 Ctrl+C 模拟崩溃看消息重投

前置条件：docker-compose up -d
"""

import sys
import time
import pika

# 队列名称常量
MAIN_QUEUE = 'reliable_queue'
DLX_EXCHANGE = 'dlx_exchange'
DLQ = 'dead_letter_queue'


def setup_infrastructure(channel):
    """
    初始化基础设施：主队列 + 死信队列 + 死信交换器

    这是生产环境的标准模式：
      1. 主队列配置死信交换器
      2. 消息消费失败 → 进入死信交换器 → 死信队列
      3. 可以从死信队列捞出来分析或重试
    """
    # ============================================================
    # 1. 声明死信交换器（Direct 类型）
    # ============================================================
    channel.exchange_declare(exchange=DLX_EXCHANGE, exchange_type='direct', durable=True)

    # ============================================================
    # 2. 声明死信队列并绑定到死信交换器
    # ============================================================
    channel.queue_declare(queue=DLQ, durable=True)
    channel.queue_bind(exchange=DLX_EXCHANGE, queue=DLQ, routing_key='dead')

    # ============================================================
    # 3. 声明主队列，配置死信属性和 TTL
    #
    # x-dead-letter-exchange:  消息被拒/过期后发到这个 Exchange
    # x-dead-letter-routing-key: 死信的路由键
    # x-message-ttl:           消息最大存活时间（毫秒）
    #                          超时未消费自动进入死信
    # x-max-length:            队列最大消息数
    # ============================================================
    args = {
        'x-dead-letter-exchange': DLX_EXCHANGE,
        'x-dead-letter-routing-key': 'dead',
        'x-message-ttl': 60000,       # 60 秒超时
        'x-max-length': 1000,         # 最多 1000 条
    }
    channel.queue_declare(queue=MAIN_QUEUE, durable=True, arguments=args)

    print(" [*] 基础设施已就绪:")
    print(f"     主队列: {MAIN_QUEUE}")
    print(f"     死信交换器: {DLX_EXCHANGE}")
    print(f"     死信队列: {DLQ}")


def run_producer():
    """生产者：带 Publisher Confirm 的可靠发送"""
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )
    channel = connection.channel()

    setup_infrastructure(channel)

    # ============================================================
    # 开启 Publisher Confirm 模式
    #
    # 在 Confirm 模式下，每条消息发送后，
    # RabbitMQ 会异步通知生产者"已收到并处理"
    # 如果消息无法路由或 Broker 内部错误，会通知 Nack
    #
    # 这解决了"消息发出去了但不知道 Broker 收到没有"的问题
    # ============================================================
    channel.confirm_delivery()

    print("输入消息，输入 quit 退出:")
    print("（输入 fail 模拟发送失败场景）")

    while True:
        message = input(" > ")
        if message.lower() == 'quit':
            break

        try:
            # ============================================================
            # mandatory=True 的作用：
            #   如果消息无法路由到任何队列（比如没有匹配的 binding），
            #   RabbitMQ 会回调 BasicReturn，而不是静默丢弃
            # ============================================================
            channel.basic_publish(
                exchange='',
                routing_key=MAIN_QUEUE,
                body=message.encode('utf-8'),
                properties=pika.BasicProperties(
                    delivery_mode=pika.DeliveryMode.Persistent,
                    # 给消息一个唯一 ID，用于幂等消费
                    message_id=f"msg-{time.time_ns()}",
                ),
                mandatory=True
            )
            # ============================================================
            # wait_for_confirmation() 阻塞等待 Broker 确认
            #
            # 如果 Broker 确认（ACK），说明消息已安全到达
            # 如果 Broker 拒绝（Nack），说明发送失败，需要重试
            #
            # 生产环境建议用异步 Confirm + 回调
            # ============================================================
            print(f" [✓] 发送成功 (Broker 已确认): {message}")

        except pika.exceptions.UnroutableError:
            # mandatory=True 时，消息无法路由会抛这个异常
            print(f" [✗] 发送失败: 消息无法路由")
        except pika.exceptions.NackError:
            # Broker 拒绝了消息（Nack）
            print(f" [✗] 发送失败: Broker 返回 Nack，需要重试")

    connection.close()


def run_consumer():
    """消费者：带手动 Ack 和死信的可靠消费"""
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )
    channel = connection.channel()

    setup_infrastructure(channel)

    # 公平分发
    channel.basic_qos(prefetch_count=1)

    def callback(ch, method, properties, body):
        message = body.decode('utf-8')
        print(f"\n [←] 收到消息: {message}")
        print(f"    消息 ID: {properties.message_id}")

        # ============================================================
        # 模拟业务处理
        #
        # 如果消息内容是 "fail"，模拟处理失败
        # 如果消息内容是 "crash"，模拟处理超时
        # ============================================================
        try:
            if message == 'fail':
                raise RuntimeError("模拟业务处理失败!")

            if message == 'crash':
                print("    模拟处理超时...")
                time.sleep(10)  # 假装卡住了
                print("    恢复")

            # 业务处理成功
            print(f" [✓] 处理成功")

            # ============================================================
            # basic_ack: 告诉 RabbitMQ 消息已成功处理
            #
            # delivery_tag: 消息的投递编号（递增计数器）
            # multiple=False: 只确认当前这条
            #
            # 只有确认后，RabbitMQ 才会删除这条消息
            # ============================================================
            ch.basic_ack(delivery_tag=method.delivery_tag)
            print(f"    已发送 Ack")

        except Exception as e:
            print(f" [✗] 处理失败: {e}")

            # ============================================================
            # basic_nack: 告诉 RabbitMQ 处理失败
            #
            # delivery_tag: 同上
            # multiple=False: 只拒绝当前这条
            # requeue=False:  不重新入队 → 进入死信队列
            #
            # requeue=True:  重新放回主队列尾部
            #   ⚠️ 如果消费逻辑有问题，会无限重试！
            #   所以一般 requeue=False，让死信队列兜底
            # ============================================================
            ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
            print(f"    已发送 Nack（消息进入死信队列）")

    channel.basic_consume(
        queue=MAIN_QUEUE,
        on_message_callback=callback,
        auto_ack=False  # 手动 Ack，必须的
    )

    print("\n [*] 等待消息（输入 'fail' 模拟失败，'crash' 模拟超时）...")
    print("     按 Ctrl+C 退出\n")
    channel.start_consuming()


def run_inspect_dlq():
    """
    检查死信队列中的消息

    用来查看哪些消息处理失败了，方便排查问题或重新处理
    """
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )
    channel = connection.channel()

    # 获取死信队列状态
    queue_info = channel.queue_declare(queue=DLQ, durable=True, passive=True)
    message_count = queue_info.method.message_count

    print(f"死信队列 [{DLQ}] 中有 {message_count} 条消息\n")

    if message_count > 0:

        def callback(ch, method, properties, body):
            print(f"  消息: {body.decode('utf-8')}")
            print(f"  消息 ID: {properties.message_id}")
            print(f"  进入死信时间: {time.strftime('%Y-%m-%d %H:%M:%S')}")
            print()

        channel.basic_consume(
            queue=DLQ,
            on_message_callback=callback,
            auto_ack=False
        )
        channel.start_consuming()
    else:
        print("死信队列为空")


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("用法:")
        print("  python 07_confirm_ack.py producer         # 生产者")
        print("  python 07_confirm_ack.py consumer         # 消费者")
        print("  python 07_confirm_ack.py inspect_dlq      # 查看死信队列")
        sys.exit(1)

    if sys.argv[1] == 'producer':
        run_producer()
    elif sys.argv[1] == 'consumer':
        run_consumer()
    elif sys.argv[1] == 'inspect_dlq':
        run_inspect_dlq()
    else:
        print(f"未知命令: {sys.argv[1]}")
        sys.exit(1)
