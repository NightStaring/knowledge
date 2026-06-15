"""
04 路由模式 — Direct Exchange
=============================

教学目的：演示按路由键精确分发消息

相比 03 的新概念：
  - Direct Exchange: 根据 routing_key 精确匹配 binding_key
  - 消费者可以按需订阅特定级别的消息

场景：
  日志分级——error 日志发给错误处理器，info 日志发给普通记录器

运行方式（开三个终端）：
  终端 1: python 04_routing.py consumer error   # 只收 error
  终端 2: python 04_routing.py consumer info warning  # 收 info 和 warning
  终端 3: python 04_routing.py producer
        输入 "error 数据库连接失败"
        输入 "info 用户登录成功"

前置条件：docker-compose up -d
"""

import sys
import pika


def run_producer():
    """生产者：发送带严重级别的日志"""
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )
    channel = connection.channel()

    # 声明 Direct Exchange
    channel.exchange_declare(exchange='direct_logs', exchange_type='direct')

    print("输入格式: 级别 消息，如: error 数据库挂了")
    print("级别可选: debug, info, warning, error")
    print("输入 quit 退出")

    while True:
        text = input(" > ")
        if text.lower() == 'quit':
            break

        # 解析 "error 数据库挂了" → severity="error", message="数据库挂了"
        parts = text.split(' ', 1)
        if len(parts) != 2:
            print("格式错误，请用: 级别 消息")
            continue

        severity = parts[0]
        message = parts[1]

        # ============================================================
        # Direct Exchange：routing_key 精确匹配
        # 消息的 routing_key = "error"
        # 只有绑定了 "error" 的队列才能收到
        # ============================================================
        channel.basic_publish(
            exchange='direct_logs',
            routing_key=severity,  # 路由键就是严重级别
            body=message.encode('utf-8')
        )
        print(f" [x] 已发送: [{severity}] {message}")

    connection.close()


def run_consumer(severities):
    """
    消费者：订阅指定级别的日志

    severities: 列表，如 ['error'] 或 ['info', 'warning']
    """
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )
    channel = connection.channel()

    channel.exchange_declare(exchange='direct_logs', exchange_type='direct')

    # 创建临时队列
    result = channel.queue_declare(queue='', exclusive=True)
    queue_name = result.method.queue

    # ============================================================
    # 绑定多个路由键
    #
    # 如果消费者订阅 ['error', 'warning']，
    # 就绑定两次，队列会同时收到 error 和 warning 消息
    #
    # 这实现了"选择性订阅"
    # ============================================================
    for severity in severities:
        channel.queue_bind(
            exchange='direct_logs',
            queue=queue_name,
            routing_key=severity  # 只接收这个 routing_key 的消息
        )
        print(f" [*] 绑定了: {severity}")

    print(f" [*] 等待日志消息（级别: {', '.join(severities)}），按 Ctrl+C 退出...")

    def callback(ch, method, properties, body):
        print(f" [x] [{method.routing_key}] {body.decode('utf-8')}")

    channel.basic_consume(
        queue=queue_name,
        on_message_callback=callback,
        auto_ack=True
    )

    channel.start_consuming()


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("用法:")
        print("  python 04_routing.py producer")
        print("  python 04_routing.py consumer [级别1] [级别2] ...")
        print("示例:")
        print("  python 04_routing.py consumer error")
        print("  python 04_routing.py consumer info warning error")
        sys.exit(1)

    if sys.argv[1] == 'producer':
        run_producer()
    elif sys.argv[1] == 'consumer':
        if len(sys.argv) < 3:
            print("请指定要订阅的级别，如: consumer error warning")
            sys.exit(1)
        run_consumer(sys.argv[2:])
    else:
        print(f"未知命令: {sys.argv[1]}")
        sys.exit(1)
