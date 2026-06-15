"""
06 RPC — 请求/应答模式
=======================

教学目的：演示如何使用消息队列实现远程过程调用

相比之前的新概念：
  - reply_to:     告诉 RPC 服务器把响应发到哪个回调队列
  - correlation_id: 关联请求和响应（客户端发请求时生成唯一 ID）
  - 回调队列:     客户端用来接收响应的队列

场景：
  客户端发送一个数字，RPC 服务器计算 Fibonacci 并返回结果

流程：
  1. 客户端发送请求（带回调队列和关联 ID）
  2. RPC 服务器处理请求
  3. RPC 服务器把结果发到回调队列
  4. 客户端从回调队列收到结果

运行方式（开两个终端）：
  终端 1: python 06_rpc.py server
  终端 2: python 06_rpc.py client 10
        输出: [x] 收到响应: 55

前置条件：docker-compose up -d
"""

import sys
import uuid
import pika


# ============================================================
# RPC 服务器
# ============================================================
class FibonacciRpcServer:
    """
    RPC 服务器：计算 Fibonacci 数列

    工作流程：
      1. 监听 rpc_queue
      2. 收到请求后计算
      3. 把结果发到请求中指定的回调队列（reply_to）
      4. 请求和响应通过 correlation_id 关联
    """

    def __init__(self):
        self.connection = pika.BlockingConnection(
            pika.ConnectionParameters(host='localhost')
        )
        self.channel = self.connection.channel()

        # 声明 RPC 请求队列
        # 客户端把请求发到这个队列，服务器从这里取
        self.channel.queue_declare(queue='rpc_queue', durable=True)

        # 公平分发：一次只处理一个请求
        self.channel.basic_qos(prefetch_count=1)

        # 开始消费
        self.channel.basic_consume(
            queue='rpc_queue',
            on_message_callback=self.on_request,
            auto_ack=False
        )

    @staticmethod
    def fib(n):
        """计算 Fibonacci 数（递归，演示用）"""
        if n < 0:
            raise ValueError("n 必须是非负整数")
        if n in (0, 1):
            return n
        return FibonacciRpcServer.fib(n - 1) + FibonacciRpcServer.fib(n - 2)

    def on_request(self, ch, method, properties, body):
        """
        处理 RPC 请求

        properties 中关键字段：
          reply_to:       客户端指定的回调队列名
          correlation_id: 关联 ID，用于匹配请求和响应
        """
        n = int(body.decode('utf-8'))
        print(f" [.] 计算 fib({n})...")

        result = self.fib(n)

        # ============================================================
        # 把结果发回客户端的回调队列
        #
        # exchange=''  使用默认 Exchange
        # routing_key=properties.reply_to  路由到客户端的回调队列
        #
        # correlation_id 原样返回，客户端用它匹配请求和响应
        # ============================================================
        self.channel.basic_publish(
            exchange='',
            routing_key=properties.reply_to,
            properties=pika.BasicProperties(
                correlation_id=properties.correlation_id
            ),
            body=str(result).encode('utf-8')
        )

        # 确认请求已处理
        ch.basic_ack(delivery_tag=method.delivery_tag)
        print(f" [.] 结果 {result} 已返回")

    def start(self):
        print(" [x] RPC 服务器已启动，等待请求...")
        self.channel.start_consuming()


# ============================================================
# RPC 客户端
# ============================================================
class FibonacciRpcClient:
    """
    RPC 客户端：发送请求并等待结果

    关键设计：
      - 每个客户端创建一个唯一的回调队列
      - 每次请求生成唯一的 correlation_id
      - 用字典缓存等待中的响应：{correlation_id: 响应数据}
    """

    def __init__(self):
        self.connection = pika.BlockingConnection(
            pika.ConnectionParameters(host='localhost')
        )
        self.channel = self.connection.channel()

        # ============================================================
        # 创建回调队列
        #
        # 这个队列用来接收 RPC 服务器的响应
        # exclusive=True 表示只此连接可用，断开即删
        # ============================================================
        result = self.channel.queue_declare(queue='', exclusive=True)
        self.callback_queue = result.method.queue

        # 订阅回调队列
        self.channel.basic_consume(
            queue=self.callback_queue,
            on_message_callback=self.on_response,
            auto_ack=True
        )

        # 用来匹配请求和响应的字典
        self.response = None
        self.corr_id = None

    def on_response(self, ch, method, properties, body):
        """
        收到 RPC 响应时的回调

        检查 correlation_id 是否匹配当前请求
        匹配 → 保存响应（不匹配 → 忽略，可能是旧的/其他的请求）
        """
        if self.corr_id == properties.correlation_id:
            self.response = body.decode('utf-8')

    def call(self, n):
        """发送 RPC 请求并等待结果"""
        # 生成唯一关联 ID
        self.corr_id = str(uuid.uuid4())
        self.response = None

        # ============================================================
        # 发送 RPC 请求
        #
        # reply_to:       告诉服务器把结果发到这个队列
        # correlation_id: 关联 ID，防止响应乱序
        #
        # 如果没有 correlation_id，多个请求同时发送时，
        # 客户端无法知道收到的响应是哪个请求的
        # ============================================================
        self.channel.basic_publish(
            exchange='',
            routing_key='rpc_queue',
            properties=pika.BasicProperties(
                reply_to=self.callback_queue,
                correlation_id=self.corr_id,
            ),
            body=str(n).encode('utf-8')
        )

        # ============================================================
        # 等待响应（轮询）
        #
        # 不断处理消息（包括回调队列的消息），直到收到匹配的响应
        # 这是一个简单的同步等待模式
        #
        # 生产环境建议使用异步 + Future/Promise 模式
        # ============================================================
        print(f" [.] 发送请求 fib({n})，等待响应...")
        while self.response is None:
            self.connection.process_data_events()  # 非阻塞处理消息
        return int(self.response)


def run_server():
    server = FibonacciRpcServer()
    server.start()


def run_client(n):
    client = FibonacciRpcClient()
    result = client.call(n)
    print(f" [x] 收到响应: fib({n}) = {result}")


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("用法:")
        print("  python 06_rpc.py server")
        print("  python 06_rpc.py client <数字>")
        sys.exit(1)

    if sys.argv[1] == 'server':
        run_server()
    elif sys.argv[1] == 'client':
        if len(sys.argv) != 3:
            print("请指定数字: python 06_rpc.py client 10")
            sys.exit(1)
        try:
            n = int(sys.argv[2])
            run_client(n)
        except ValueError:
            print("参数必须是整数")
            sys.exit(1)
    else:
        print(f"未知命令: {sys.argv[1]}")
        sys.exit(1)
