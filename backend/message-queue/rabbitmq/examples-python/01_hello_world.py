"""
01 Hello World — 基础收发
=========================

教学目的：演示 RabbitMQ 最基础的消息发送和接收流程

关键概念：
  - Connection: TCP 连接（应用和 RabbitMQ 之间的长连接）
  - Channel:    在 Connection 上创建的虚拟通道，所有操作都在 Channel 上执行
  - Queue:      消息存储的队列

流程：
  生产者 → 队列 → 消费者

运行方式（开两个终端）：
  终端 1: python 01_hello_world.py consumer
  终端 2: python 01_hello_world.py producer

前置条件：docker-compose up -d 已执行
"""

import sys
import pika


def run_producer():
    """
    生产者：发送消息到队列
    """
    # ============================================================
    # 1. 建立连接
    # ============================================================
    # ConnectionParameters 指定 RabbitMQ 服务器地址
    # RabbitMQ 默认端口是 5672
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )

    # Channel 是真正干活的"通道"
    # 一个 Connection 可以创建多个 Channel（多路复用）
    channel = connection.channel()

    # ============================================================
    # 2. 声明队列
    # ============================================================
    # queue_declare 的作用：
    #   - 如果队列不存在，创建它
    #   - 如果已存在，什么都不做（幂等）
    # durable=True 表示队列持久化（RabbitMQ 重启后队列不丢失）
    channel.queue_declare(queue='hello', durable=False)

    # ============================================================
    # 3. 发送消息
    # ============================================================
    # exchange=''    使用默认交换器（Direct 类型）
    # routing_key='hello' 路由到同名队列
    # body          消息体（必须是 bytes）
    message = 'Hello World!'
    channel.basic_publish(
        exchange='',
        routing_key='hello',
        body=message.encode('utf-8')
    )

    print(f" [x] 已发送: {message}")

    # ============================================================
    # 4. 关闭连接
    # ============================================================
    connection.close()


def run_consumer():
    """
    消费者：从队列接收消息
    """
    # 1. 建立连接
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )
    channel = connection.channel()

    # 2. 声明队列（和生产者的声明保持一致）
    # 注意：如果只运行消费者而不运行生产者，也要声明队列
    # 因为消费者是先启动的，此时队列可能还不存在
    channel.queue_declare(queue='hello', durable=False)

    # ============================================================
    # 3. 定义回调函数
    # ============================================================
    # 当消费者收到消息时，RabbitMQ 会调用这个函数
    # ch:        Channel 对象
    # method:    包含路由信息（如 delivery_tag、routing_key 等）
    # properties: 消息属性
    # body:      消息体（bytes）
    def callback(ch, method, properties, body):
        print(f" [x] 收到: {body.decode('utf-8')}")

    # ============================================================
    # 4. 开始消费
    # ============================================================
    # queue='hello'  消费哪个队列
    # on_message_callback=callback  消息到来时调用的函数
    # auto_ack=True  自动确认（收到即确认，不关心处理结果）
    #                生产环境应使用手动 Ack（见 07_confirm_ack.py）
    channel.basic_consume(
        queue='hello',
        on_message_callback=callback,
        auto_ack=True
    )

    print(' [*] 等待消息，按 Ctrl+C 退出...')

    # 5. 进入阻塞等待（不断拉取消息）
    channel.start_consuming()


if __name__ == '__main__':
    # 通过命令行参数决定角色
    if len(sys.argv) != 2 or sys.argv[1] not in ('producer', 'consumer'):
        print("用法: python 01_hello_world.py [producer|consumer]")
        sys.exit(1)

    if sys.argv[1] == 'producer':
        run_producer()
    else:
        run_consumer()
