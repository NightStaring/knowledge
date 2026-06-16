# IO / NIO

> 核心 API 速查与选型，不讲"什么是流"。

---

## 1. IO 体系

### 1.1 字节流 vs 字符流

```
字节流（处理二进制）
├── InputStream
│   ├── FileInputStream
│   ├── BufferedInputStream
│   ├── ByteArrayInputStream
│   └── ObjectInputStream
└── OutputStream
    ├── FileOutputStream
    ├── BufferedOutputStream
    ├── ByteArrayOutputStream
    └── ObjectOutputStream

字符流（处理文本）
├── Reader
│   ├── FileReader
│   ├── BufferedReader
│   ├── InputStreamReader
│   └── StringReader
└── Writer
    ├── FileWriter
    ├── BufferedWriter
    ├── OutputStreamWriter
    └── StringWriter
```

### 1.2 选型

```java
// 处理二进制 → 字节流
byte[] data = Files.readAllBytes(path);          // 小文件
Files.write(path, data);                         // 写

// 处理文本 → 字符流
List<String> lines = Files.readAllLines(path);   // 小文件
Files.write(path, lines);                        // 写

// 🔴 用 Files API 代替传统 FileInputStream
// Java 7+ Files 类封装了常用操作，更简洁
```

---

## 2. NIO 核心

### 2.1 三大核心

| 组件 | 说明 | 类比 |
|------|------|------|
| **Channel** | 通道（读写通道） | 铁路 |
| **Buffer** | 缓冲区（数据容器） | 火车 |
| **Selector** | 选择器（多路复用） | 调度中心 |

### 2.2 Channel 类型

```java
// 文件
FileChannel      // 文件读写（零拷贝）
// 网络
SocketChannel    // TCP 客户端
ServerSocketChannel  // TCP 服务端
DatagramChannel   // UDP
```

### 2.3 Buffer 操作

```java
// 创建
ByteBuffer buf = ByteBuffer.allocate(1024);      // 堆内
ByteBuffer buf = ByteBuffer.allocateDirect(1024); // 堆外（零拷贝用）

// 写数据
buf.put((byte) 1);         // 写入一个字节
buf.putInt(123);           // 写入 int
buf.put("hello".getBytes());

// 切换读模式
buf.flip();                // limit = position, position = 0

// 读数据
byte b = buf.get();        // 读一个字节
int i = buf.getInt();      // 读 int

// 重复读
buf.rewind();              // position = 0

// 清空
buf.clear();               // 切换到写模式

// 标记
buf.position(0).limit(buf.capacity());  // 等价于 clear
```

### 2.4 Selector 多路复用

```java
// 服务端 NIO 核心模式
Selector selector = Selector.open();
ServerSocketChannel ssc = ServerSocketChannel.open();
ssc.configureBlocking(false);              // 非阻塞
ssc.bind(new InetSocketAddress(8080));
ssc.register(selector, SelectionKey.OP_ACCEPT);  // 注册 accept 事件

while (true) {
    selector.select();                     // 阻塞直到有事件
    Set<SelectionKey> keys = selector.selectedKeys();
    for (SelectionKey key : keys) {
        if (key.isAcceptable()) {
            // 新连接
            SocketChannel sc = ssc.accept();
            sc.configureBlocking(false);
            sc.register(selector, SelectionKey.OP_READ);
        }
        if (key.isReadable()) {
            // 可读
            SocketChannel sc = (SocketChannel) key.channel();
            ByteBuffer buf = ByteBuffer.allocate(1024);
            sc.read(buf);
        }
    }
    keys.clear();
}
```

---

## 3. 常用 API 速查

### 3.1 Files 工具类（Java 7+）

```java
// 读写文件
byte[] data = Files.readAllBytes(path);          // 读全部字节
List<String> lines = Files.readAllLines(path);    // 读全部行
Files.write(path, data);                          // 写字节
Files.write(path, lines);                         // 写行

// 流式读取
try (Stream<String> stream = Files.lines(path)) {
    stream.filter(line -> line.contains("ERROR"))
          .forEach(System.out::println);
}

// 文件操作
Files.exists(path);
Files.isDirectory(path);
Files.size(path);
Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
Files.move(src, dest);
Files.delete(path);
Files.createDirectories(path);   // 创建目录（含父目录）
Files.createTempFile("prefix", ".tmp");

// 文件属性
Files.getLastModifiedTime(path);
Files.getAttribute(path, "size");
Files.probeContentType(path);    // 获取 MIME 类型

// 遍历目录
try (Stream<Path> paths = Files.walk(root)) {      // 递归遍历
    paths.filter(Files::isRegularFile)
         .forEach(System.out::println);
}
try (Stream<Path> paths = Files.list(dir)) {       // 只遍历一级
    paths.forEach(System.out::println);
}
```

### 3.2 经典读写模式

```java
// 小文件（< 2MB）— 一次性读写
byte[] data = Files.readAllBytes(Path.of("file.bin"));
Files.write(Path.of("out.bin"), data);

// 大文件（> 2MB）— 缓冲流
try (InputStream is = new BufferedInputStream(new FileInputStream("large.bin"));
     OutputStream os = new BufferedOutputStream(new FileOutputStream("out.bin"))) {
    byte[] buf = new byte[8192];
    int len;
    while ((len = is.read(buf)) != -1) {
        os.write(buf, 0, len);
    }
}

// 文本文件行处理
try (Stream<String> lines = Files.lines(Path.of("log.txt"))) {
    lines.filter(l -> l.contains("ERROR"))
         .limit(100)
         .forEach(System.out::println);
}

// 对象序列化
try (ObjectOutputStream oos = new ObjectOutputStream(
        new FileOutputStream("user.ser"))) {
    oos.writeObject(user);
}
try (ObjectInputStream ois = new ObjectInputStream(
        new FileInputStream("user.ser"))) {
    User user = (User) ois.readObject();
}
```

### 3.3 NIO 文件通道（零拷贝）

```java
// 零拷贝：数据不经过用户空间，直接在内核空间传输
// 适合大文件传输（如文件服务器）
try (FileChannel in = FileChannel.open(Path.of("source.bin"), StandardOpenOption.READ);
     FileChannel out = FileChannel.open(Path.of("dest.bin"),
         StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
    // transferTo：从 in 直接传到 out，不经过应用内存
    in.transferTo(0, in.size(), out);
}
```

---

## 4. BIO vs NIO vs AIO

| | BIO | NIO | AIO |
|--|-----|-----|-----|
| 模型 | 同步阻塞 | 同步非阻塞 | 异步非阻塞 |
| 线程 | 一个连接一个线程 | 一个 Selector 处理多个连接 | 回调/通知 |
| 适用 | 连接数少、低并发 | 连接数多、短连接 | 连接数多、长连接 |
| 编程 | 简单 | 中等 | 复杂 |
| Java 支持 | 原始 IO | Java 4+ | Java 7+（实际用得少） |

```java
// 🟢 选型建议
// 文件 IO → Files API / BufferedInputStream
// 网络 IO（高并发）→ Netty（基于 NIO）
// 不要直接写 NIO 网络编程，用 Netty
```

---

## 5. 🔴 常见坑

```java
// 坑 1：流没有关闭 → 资源泄漏
// 🟢 用 try-with-resources
try (InputStream is = new FileInputStream("file")) {
    // ...
}  // 自动关闭

// 坑 2：读取文本时没指定编码
// ❌ 依赖平台默认编码（Windows 是 GBK，Linux 是 UTF-8）
new InputStreamReader(new FileInputStream("file"));  // 危险！
// ✅ 显式指定
new InputStreamReader(new FileInputStream("file"), StandardCharsets.UTF_8);
Files.readString(path, StandardCharsets.UTF_8);

// 坑 3：read() 返回值不是 char
// ❌
int b = inputStream.read();  // 返回 int（0~255），不是 byte
// ✅ 判断文件结束
while ((b = inputStream.read()) != -1) { }  // -1 表示结束

// 坑 4：大文件一次性读入内存
byte[] all = Files.readAllBytes(Path.of("huge.bin"));  // OOM!
// 🟢 流式处理

// 坑 5：FileChannel 的 position 问题
// read() 和 write() 都会移动 position
// 并发读写同一个 FileChannel 要注意
```
