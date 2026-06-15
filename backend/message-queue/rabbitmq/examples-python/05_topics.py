"""
05 主题模式 — Topic Exchange
============================

教学目的：演示通配符路由，这是 RabbitMQ 最灵活的路由方式

相比 04 的新概念：
  - Topic Exchange: routing_key 支持通配符
  - * 匹配一个单词（由 . 分隔）
  - # 匹配零个或多个单词

场景：
  一个灵活的日志系统，支持按"来源.级别"过滤
  比如 "auth.error"、"auth.info"、"web.error"、"web.info"

运行方式（开三个终端）：
  终端 1: python 05_topics.py consumer "*.error"        # 收所有 error
  终端 2: python 05_topics.py consumer "auth.#"          # 收 auth 相关所有
  终端 3: python 05_topics.py producer
        输入 "auth.error 登录失败"
        输入 "web.info 页面加载完成"
        输入 "auth.warn 密码尝试过多"

前置条件：docker-compose up -d
"""

import sys
import pika


def run_producer():
    """
    生产者：发送带来源.级别的日志

    routing_key 格式: 来源.级别
    例如: auth.error, web.info, db.warning, cron.debug
    """
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )
    channel = connection.channel()

    # 声明 Topic Exchange
    channel.exchange_declare(exchange='topic_logs', exchange_type='topic')

    print("输入格式: 来源.级别 消息，如: auth.error 登录失败")
    print("来源: auth, web, db, cron, ...")
    print("级别: debug, info, warning, error")
    print("输入 quit 退出")

    while True:
        text = input(" > ")
        if text.lower() == 'quit':
            break

        parts = text.split(' ', 1)
        if len(parts) != 2:
            print("格式错误，请用: 来源.级别 消息")
            continue

        routing_key = parts[0]
        message = parts[1]

        channel.basic_publish(
            exchange='topic_logs',
            routing_key=routing_key,
            body=message.encode('utf-8')
        )
        print(f" [x] 已发送: [{routing_key}] {message}")

    connection.close()


def run_consumer(binding_keys):
    """
    消费者：使用通配符订阅日志

    binding_keys: 列表，如 ['*.error'] 或 ['auth.#']

    通配符规则：
      * (星号)  匹配一个单词
        例如: *.error 匹配 auth.error、web.error，但不匹配 auth.web.error
      # (井号)  匹配零个或多个单词
        例如: auth.# 匹配 auth、auth.error、auth.web.info

    常见模式示例：
      "auth.#"      → 所有 auth 相关的消息
      "*.error"     → 所有来源的 error 消息
      "#"           → 所有消息（类似 Fanout）
      "web.*"       → web 来源的所有级别
    """
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )
    channel = connection.channel()

    channel.exchange_declare(exchange='topic_logs', exchange_type='topic')

    result = channel.queue_declare(queue='', exclusive=True)
    queue_name = result.method.queue

    # 绑定多个通配符模式
    for binding_key in binding_keys:
        channel.queue_bind(
            exchange='topic_logs',
            queue=queue_name,
            routing_key=binding_key
        )
        print(f" [*] 绑定了: {binding_key}")

    print(f" [*] 等待消息（模式: {', '.join(binding_keys)}），按 Ctrl+C 退出...")

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
        print("  python 05_topics.py producer")
        print("  python 05_topics.py consumer [绑定模式1] [绑定模式2] ...")
        print("示例:")
        print("  python 05_topics.py consumer *.error")
        print("  python 05_topics.py consumer auth.# web.#")
        print("  python 05_topics.py consumer '#')       # 注意引号，防止 shell 展开")
        sys.exit(1)

    if sys.argv[1] == 'producer':
        run_producer()
    elif sys.argv[1] == 'consumer':
        if len(sys.argv) < 3:
            print("请指定绑定模式，如: consumer *.error")
            sys.exit(1)
        run_consumer(sys.argv[2:])
    else:
        print(f"未知命令: {sys.argv[1]}")
        sys.exit(1)
