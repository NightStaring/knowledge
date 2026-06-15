"""
03 发布/订阅 — Fanout Exchange
==============================

教学目的：演示广播模式——一条消息发给所有消费者

相比 02 的新概念：
  - Exchange: 消息路由器，生产者不直接发到队列，而是发到 Exchange
  - Fanout Exchange: 广播类型，把消息发给所有绑定的队列
  - 临时队列 + 自动删除：每个消费者创建自己的专属队列

场景：
  日志系统——一条日志消息，所有订阅者都能收到

运行方式（开三个终端）：
  终端 1: python 03_pub_sub.py consumer  # 第一个订阅者
  终端 2: python 03_pub_sub.py consumer  # 第二个订阅者
  终端 3: python 03_pub_sub.py producer
        输入消息后，两个终端应该都能收到

前置条件：docker-compose up -d
"""

import sys
import pika


def run_producer():
    """生产者：发布消息到 Fanout Exchange"""
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )
    channel = connection.channel()

    # ============================================================
    # 声明 Exchange
    #
    # 之前我们用默认 Exchange（exchange=''），它按队列名精确路由
    # 现在显式声明一个 Fanout Exchange
    #
    # exchange='logs'   Exchange 名称
    # exchange_type='fanout'  广播模式
    # ============================================================
    channel.exchange_declare(
        exchange='logs',
        exchange_type='fanout'
    )

    print("请输入日志消息（每行一个），输入 quit 退出:")
    while True:
        message = input(" > ")
        if message.lower() == 'quit':
            break

        # ============================================================
        # 发到 Exchange，而不是直接发到 Queue
        # routing_key 在 Fanout 模式下会被忽略（因为广播给所有人）
        # ============================================================
        channel.basic_publish(
            exchange='logs',
            routing_key='',    # Fanout 模式下 routing_key 不起作用
            body=message.encode('utf-8')
        )
        print(f" [x] 已发送: {message}")

    connection.close()


def run_consumer():
    """消费者：订阅日志"""
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )
    channel = connection.channel()

    # 声明 Exchange（消费者也需要，因为可能比生产者先启动）
    channel.exchange_declare(exchange='logs', exchange_type='fanout')

    # ============================================================
    # 创建临时队列
    #
    # queue_declare() 不传队列名 → RabbitMQ 自动生成一个随机名称
    # 比如 'amq.gen-JzTY20BRgKO-HjmUJj0wLg'
    #
    # exclusive=True 的队列特点：
    #   - 只对当前连接可见
    #   - 连接断开时自动删除
    #   - 完美适合"每个消费者一个专属队列"的场景
    # ============================================================
    result = channel.queue_declare(queue='', exclusive=True)
    queue_name = result.method.queue
    print(f" [*] 临时队列名: {queue_name}")

    # ============================================================
    # 绑定队列到 Exchange
    #
    # 告诉 RabbitMQ：这个队列想从这个 Exchange 接收消息
    # 对于 Fanout 类型，routing_key 不起作用
    # ============================================================
    channel.queue_bind(exchange='logs', queue=queue_name)

    print(' [*] 等待日志消息，按 Ctrl+C 退出...')

    def callback(ch, method, properties, body):
        print(f" [x] 收到日志: {body.decode('utf-8')}")

    channel.basic_consume(
        queue=queue_name,
        on_message_callback=callback,
        auto_ack=True
    )

    channel.start_consuming()


if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('producer', 'consumer'):
        print("用法: python 03_pub_sub.py [producer|consumer]")
        sys.exit(1)

    if sys.argv[1] == 'producer':
        run_producer()
    else:
        run_consumer()
