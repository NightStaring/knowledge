"""
02 工作队列 — 公平分发 / 任务队列
==================================

教学目的：演示如何将耗时任务分发给多个 Worker

相比 01 的新概念：
  - 消息持久化：保证 RabbitMQ 重启后消息不丢失
  - 公平分发（prefetch_count=1）：不会让某个 Worker 撑死，另一个饿死
  - 手动 Ack：处理完再确认，防止处理过程中崩溃导致消息丢失

场景：
  假设有一个发送邮件的任务，每个邮件发送需要几秒钟
  多个 Worker 同时处理，谁空闲谁处理

运行方式（开三个终端）：
  终端 1: python 02_work_queues.py worker
  终端 2: python 02_work_queues.py worker
  终端 3: python 02_work_queues.py producer
        然后连续输入 task 1, task 2, task 3 ...

前置条件：docker-compose up -d
"""

import sys
import time
import pika


def run_producer():
    """生产者：发送模拟的耗时任务"""
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )
    channel = connection.channel()

    # durable=True: 队列持久化
    # RabbitMQ 重启后，这个队列仍然存在
    channel.queue_declare(queue='task_queue', durable=True)

    print("请输入任务消息（每行一个），输入 quit 退出:")
    while True:
        message = input(" > ")
        if message.lower() == 'quit':
            break

        # ============================================================
        # 消息持久化：MessageProperties.PERSISTENT_TEXT_PLAIN
        # 告诉 RabbitMQ 把这个消息存到磁盘上
        # 注意：即使如此，在消息刚收到但还没落盘时宕机仍可能丢失
        # 完整的可靠性需要配合 Publisher Confirm（见 07 示例）
        # ============================================================
        channel.basic_publish(
            exchange='',
            routing_key='task_queue',
            body=message.encode('utf-8'),
            properties=pika.BasicProperties(
                delivery_mode=pika.DeliveryMode.Persistent,  # 消息持久化
            )
        )
        print(f" [x] 已发送: {message}")

    connection.close()


def run_worker():
    """消费者：模拟处理耗时任务"""
    connection = pika.BlockingConnection(
        pika.ConnectionParameters(host='localhost')
    )
    channel = connection.channel()

    channel.queue_declare(queue='task_queue', durable=True)

    # ============================================================
    # 公平分发 (Fair Dispatch)
    #
    # prefetch_count=1 的含义：
    #   在 Worker 处理完当前消息并确认之前，
    #   RabbitMQ 不会再给这个 Worker 派发新消息
    #
    # 没有这行的话，RabbitMQ 会一股脑把所有消息推给一个 Worker，
    # 导致"忙的忙死，闲的闲死"
    # ============================================================
    channel.basic_qos(prefetch_count=1)

    def callback(ch, method, properties, body):
        """处理任务"""
        message = body.decode('utf-8')
        print(f" [x] 收到: {message}")

        # 模拟耗时：消息中有几个 '.' 就 sleep 几秒
        # 比如 "task..." 会处理 3 秒
        dots = message.count('.')
        for i in range(dots):
            time.sleep(1)
            print(f"    处理中... {i+1}/{dots}")

        print(f" [x] 处理完成")

        # ============================================================
        # 手动确认 (Manual Ack)
        #
        # 只有调用了 basic_ack，RabbitMQ 才会认为消息已处理完成并删除它
        # 如果 Worker 在处理过程中崩溃了（没有发 Ack），
        # RabbitMQ 会把这条消息重新投递给其他 Worker
        #
        # 如果 auto_ack=True，消息一收到就确认，
        # 此时 Worker 崩溃会导致消息丢失
        # ============================================================
        ch.basic_ack(delivery_tag=method.delivery_tag)

    channel.basic_consume(
        queue='task_queue',
        on_message_callback=callback,
        auto_ack=False  # 手动 Ack！
    )

    print(' [*] 等待任务，按 Ctrl+C 退出...')
    channel.start_consuming()


if __name__ == '__main__':
    if len(sys.argv) != 2 or sys.argv[1] not in ('producer', 'worker'):
        print("用法: python 02_work_queues.py [producer|worker]")
        sys.exit(1)

    if sys.argv[1] == 'producer':
        run_producer()
    else:
        run_worker()
