/**
 * 06 RPC — 请求/应答模式
 * ======================
 *
 * 教学目的：演示如何使用消息队列实现远程过程调用
 *
 * 关键概念：
 *   - replyTo:        告诉 RPC 服务器把响应发到哪个回调队列
 *   - correlationId:  关联请求和响应（客户端发请求时生成唯一 ID）
 *   - 回调队列:        客户端用来接收响应的队列
 *
 * 与 Python 版本的主要差异：
 *   - Java 用两个类：RpcServer + RpcClient
 *   - Java 客户端用 BlockingRpcClient 简化了实现
 *     （但这里用原生方式，便于理解原理）
 *   - Java 中 AMQP.BasicProperties 用 Builder 模式构造
 *
 * 运行方式（开两个终端）：
 *   mvn exec:java -Dexec.mainClass="RpcServer"
 *   mvn exec:java -Dexec.mainClass="RpcClient" -Dexec.args="10"
 *
 * 流程：
 *   1. 客户端发送请求（带回调队列和关联 ID）到 rpc_queue
 *   2. RPC 服务器处理请求
 *   3. RPC 服务器把结果发到回调队列
 *   4. 客户端从回调队列收到结果
 */

import com.rabbitmq.client.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;

// ================================================================
// RPC 服务器
// ================================================================
class RpcServer {

    private static final String RPC_QUEUE_NAME = "rpc_queue";

    public static void main(String[] args) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {

            // 声明 RPC 请求队列（持久化）
            channel.queueDeclare(RPC_QUEUE_NAME, true, false, false, null);

            // 公平分发：一次只处理一个请求
            channel.basicQos(1);

            System.out.println(" [x] RPC 服务器已启动，等待请求...");

            // ============================================================
            // 处理 RPC 请求的回调
            //
            // AMQP.BasicProperties 包含：
            //   getReplyTo():       客户端指定的回调队列名
            //   getCorrelationId(): 关联 ID，用于匹配请求和响应
            // ============================================================
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                AMQP.BasicProperties replyProps = new AMQP.BasicProperties.Builder()
                    .correlationId(delivery.getProperties().getCorrelationId())
                    .build();

                String response = "";
                try {
                    String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                    int n = Integer.parseInt(message);

                    System.out.println(" [.] 计算 fib(" + n + ")...");
                    response = String.valueOf(fib(n));

                } catch (NumberFormatException e) {
                    System.out.println(" [.] 参数错误: " + e.getMessage());
                } finally {
                    // ============================================================
                    // 把结果发回客户端的回调队列
                    //
                    // 用请求中的 replyTo 作为 routingKey
                    // correlationId 原样返回，客户端用它匹配请求和响应
                    // ============================================================
                    channel.basicPublish(
                        "",
                        delivery.getProperties().getReplyTo(),
                        replyProps,
                        response.getBytes(StandardCharsets.UTF_8)
                    );

                    // 确认请求已处理
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    System.out.println(" [.] 结果 " + response + " 已返回");
                }
            };

            channel.basicConsume(RPC_QUEUE_NAME, false, deliverCallback, consumerTag -> {});

            // 保持服务器运行
            Thread.sleep(Long.MAX_VALUE);
        }
    }

    /** 计算 Fibonacci 数 */
    private static int fib(int n) {
        if (n < 0) throw new IllegalArgumentException("n 必须是非负整数");
        if (n == 0 || n == 1) return n;
        return fib(n - 1) + fib(n - 2);
    }
}

// ================================================================
// RPC 客户端
// ================================================================
class RpcClient {

    private static final String RPC_QUEUE_NAME = "rpc_queue";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("用法: mvn exec:java -Dexec.mainClass=\"RpcClient\" -Dexec.args=\"<数字>\"");
            System.out.println("示例: mvn exec:java -Dexec.mainClass=\"RpcClient\" -Dexec.args=\"10\"");
            System.exit(1);
        }

        int n = Integer.parseInt(args[0]);
        RpcClient client = new RpcClient();
        int result = client.call(n);
        System.out.println(" [x] 收到响应: fib(" + n + ") = " + result);
        client.close();
    }

    private Connection connection;
    private Channel channel;
    private String callbackQueue;

    public RpcClient() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");

        connection = factory.newConnection();
        channel = connection.createChannel();

        // ============================================================
        // 创建回调队列
        //
        // 这个队列用来接收 RPC 服务器的响应
        // exclusive=true 表示只此连接可用，断开即删
        // ============================================================
        callbackQueue = channel.queueDeclare().getQueue();
    }

    /**
     * 发送 RPC 请求并等待结果
     *
     * 关键设计：
     *   1. 每个请求生成唯一的 correlationId
     *   2. 用 BlockingQueue 同步等待响应
     *   3. 通过 correlationId 匹配请求和响应
     *
     * 如果没有 correlationId，多个请求同时发送时，
     * 客户端无法知道收到的响应是哪个请求的
     */
    public int call(int n) throws Exception {
        String corrId = UUID.randomUUID().toString();

        // ============================================================
        // 发送 RPC 请求
        //
        // replyTo:       告诉服务器把结果发到这个队列
        // correlationId: 关联 ID，防止响应乱序
        // ============================================================
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
            .correlationId(corrId)
            .replyTo(callbackQueue)
            .build();

        channel.basicPublish("", RPC_QUEUE_NAME, props,
            String.valueOf(n).getBytes(StandardCharsets.UTF_8));

        // ============================================================
        // 用 BlockingQueue 等待响应
        //
        // BlockingQueue 是 Java 并发包中的线程安全队列
        // take() 方法会阻塞直到有元素可用
        //
        // DeliverCallback 在 RabbitMQ 的线程中执行，
        // 把结果放入 BlockingQueue，主线程从中取出
        // ============================================================
        BlockingQueue<String> responseQueue = new ArrayBlockingQueue<>(1);

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            // 只处理匹配当前请求的响应
            if (delivery.getProperties().getCorrelationId().equals(corrId)) {
                responseQueue.offer(new String(delivery.getBody(), StandardCharsets.UTF_8));
            }
        };

        channel.basicConsume(callbackQueue, true, deliverCallback, consumerTag -> {});

        // 阻塞等待响应
        String response = responseQueue.take();
        return Integer.parseInt(response);
    }

    public void close() throws Exception {
        channel.close();
        connection.close();
    }
}
