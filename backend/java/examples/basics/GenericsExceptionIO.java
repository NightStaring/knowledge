/**
 * 泛型 + 异常 + IO 示例
 * =======================
 *
 * 演示：
 *   1. 泛型 PECS 原则
 *   2. 自定义异常体系
 *   3. Files API 文件操作
 *   4. 日期时间 API
 */

package com.example.basics;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.*;

// ================================================================
// 1. 泛型 PECS 原则
// ================================================================
class GenericsDemo {

    /**
     * PECS: Producer Extends, Consumer Super
     *
     * 生产者（读数据）用 extends
     * 消费者（写数据）用 super
     */

    /**
     * 生产者：从集合中读取 Number
     * 集合只负责提供数据 → extends
     */
    static double sum(Collection<? extends Number> numbers) {
        // ? extends Number 表示：
        // 可以是 List<Integer>、List<Double>、List<BigDecimal>
        // 但只能读，不能写（不能 add）
        return numbers.stream()
            .mapToDouble(Number::doubleValue)
            .sum();
    }

    /**
     * 消费者：向集合中写入 Integer
     * 集合只负责接收数据 → super
     */
    static void addNumbers(Collection<? super Integer> collection, int n) {
        // ? super Integer 表示：
        // 可以是 List<Integer>、List<Number>、List<Object>
        // 可以写 Integer，读出来是 Object
        for (int i = 0; i < n; i++) {
            collection.add(i);
        }
    }

    /**
     * PECS 经典应用：Collections.copy
     */
    static <T> void copy(
            List<? super T> dest,     // 消费者：写目标
            List<? extends T> src      // 生产者：读来源
    ) {
        for (int i = 0; i < src.size(); i++) {
            dest.set(i, src.get(i));
        }
    }

    /**
     * 类型擦除演示
     */
    static void demonstrateTypeErasure() {
        List<String> strings = new ArrayList<>();
        List<Integer> integers = new ArrayList<>();

        // 运行时都是 ArrayList，泛型信息被擦除了
        System.out.println(strings.getClass() == integers.getClass());
        // → true
    }
}

// ================================================================
// 2. 自定义异常体系
// ================================================================

/**
 * 业务异常基类
 */
class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);  // 保留原始异常链
        this.code = code;
    }

    public int getCode() { return code; }
}

/**
 * 具体业务异常
 */
class InsufficientBalanceException extends BusinessException {
    private final String accountId;
    private final BigDecimal balance;
    private final BigDecimal required;

    public InsufficientBalanceException(
            String accountId, BigDecimal balance, BigDecimal required) {
        super(400, String.format(
            "账户 %s 余额不足: 当前 %.2f, 需要 %.2f",
            accountId, balance, required));
        this.accountId = accountId;
        this.balance = balance;
        this.required = required;
    }
}

class OrderNotFoundException extends BusinessException {
    public OrderNotFoundException(Long orderId) {
        super(404, "订单不存在: " + orderId);
    }
}

/**
 * 全局异常处理（Spring Boot 风格）
 */
// @RestControllerAdvice
class GlobalExceptionHandler {
    // @ExceptionHandler(BusinessException.class)
    public ErrorResponse handleBusiness(BusinessException e) {
        // log.warn("业务异常: {}", e.getMessage());
        return new ErrorResponse(e.getCode(), e.getMessage());
    }

    // @ExceptionHandler(Exception.class)
    public ErrorResponse handleUnknown(Exception e) {
        // log.error("未知异常", e);
        return new ErrorResponse(500, "服务器内部错误");
    }

    record ErrorResponse(int code, String message) {}
}

// ================================================================
// 3. Files API 示例
// ================================================================
class FileOperationsDemo {

    /**
     * 小文件读写（< 2MB）
     */
    static void readWriteSmallFile() throws IOException {
        Path path = Path.of("example.txt");

        // 写
        Files.writeString(path, "Hello World");

        // 读
        String content = Files.readString(path);

        // 行处理
        try (Stream<String> lines = Files.lines(path)) {
            lines.filter(l -> l.contains("ERROR"))
                 .limit(100)
                 .forEach(System.out::println);
        }
    }

    /**
     * 大文件复制（缓冲流）
     */
    static void copyLargeFile(Path source, Path target) throws IOException {
        try (InputStream is = new BufferedInputStream(new FileInputStream(source.toFile()));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(target.toFile()))) {

            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                os.write(buf, 0, len);
            }
        }
    }

    /**
     * 零拷贝（NIO FileChannel）
     */
    static void zeroCopy(Path source, Path target) throws IOException {
        try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(target,
                 StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            in.transferTo(0, in.size(), out);
        }
    }

    /**
     * 目录遍历
     */
    static void walkDirectory(Path dir) throws IOException {
        // 递归遍历所有文件
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .forEach(System.out::println);
        }
    }
}

// ================================================================
// 4. 日期时间 API
// ================================================================
class DateTimeDemo {

    static void demonstrateDateTime() {
        // 创建
        LocalDate date = LocalDate.of(2025, Month.JANUARY, 1);
        LocalTime time = LocalTime.of(10, 30, 0);
        LocalDateTime dt = LocalDateTime.of(date, time);
        Instant now = Instant.now();  // UTC 时间戳

        // 格式化
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String formatted = dt.format(formatter);
        LocalDateTime parsed = LocalDateTime.parse("2025-01-01 10:30:00", formatter);

        // 计算
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDate lastMonth = LocalDate.now().minusMonths(1);
        long days = ChronoUnit.DAYS.between(LocalDate.of(2025, 1, 1), LocalDate.now());

        // 时区
        ZonedDateTime zdt = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
    }
}
