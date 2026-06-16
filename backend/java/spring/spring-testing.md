# 测试

> 单元测试、集成测试、Mock——企业级 Java 测试策略。

---

## 1. 测试金字塔

```
     /\
    /  \          E2E 测试（少量）
   /    \
  /------\       集成测试（适量）
 /        \
/----------\     单元测试（大量，核心）
```

| 层 | 速度 | 数量 | 覆盖 |
|----|------|------|------|
| 单元测试 | 毫秒级 | 多 | 方法级 |
| 集成测试 | 秒级 | 中 | 模块交互 |
| E2E 测试 | 分钟级 | 少 | 完整流程 |

**原则：** 80% 单元测试 + 15% 集成测试 + 5% E2E 测试

---

## 2. 单元测试

### 2.1 基础

```java
// 依赖
testImplementation 'org.springframework.boot:spring-boot-starter-test'

// @SpringBootTest 太慢，纯单元测试不要用
// 纯 JUnit + Mockito 即可
```

```java
class CalculatorTest {

    private final Calculator calculator = new Calculator();

    @Test
    void shouldAddTwoNumbers() {
        // given（准备）
        int a = 1, b = 2;

        // when（执行）
        int result = calculator.add(a, b);

        // then（验证）
        assertEquals(3, result);
    }

    @Test
    void shouldThrowExceptionWhenDividingByZero() {
        assertThrows(ArithmeticException.class,
            () -> calculator.divide(1, 0));
    }
}
```

### 2.2 Mockito

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderDao orderDao;          // 模拟 DAO

    @Mock
    private InventoryService inventoryService;  // 模拟远程服务

    @InjectMocks
    private OrderService orderService;  // 自动注入 mock

    @Test
    void shouldCreateOrderSuccessfully() {
        // given
        Order order = new Order("product-1", 2);
        when(inventoryService.checkStock("product-1", 2))
            .thenReturn(true);
        when(orderDao.insert(any(Order.class)))
            .thenReturn(1L);

        // when
        Long orderId = orderService.createOrder(order);

        // then
        assertEquals(1L, orderId);
        verify(orderDao).insert(order);           // 验证 insert 被调用
        verify(inventoryService).checkStock("product-1", 2);  // 验证检查库存
    }

    @Test
    void shouldFailWhenStockInsufficient() {
        // given
        Order order = new Order("product-1", 100);
        when(inventoryService.checkStock("product-1", 100))
            .thenReturn(false);  // 库存不足

        // when & then
        assertThrows(InsufficientStockException.class,
            () -> orderService.createOrder(order));
        verify(orderDao, never()).insert(any());  // 验证 insert 没有被调用
    }
}
```

### 2.3 Mockito 常用 API

```java
// 创建 Mock
@Mock
private UserDao userDao;

// 创建 Spy（部分 mock，真实对象的方法默认执行）
@Spy
private UserService userService = new UserService();

// 设置行为
when(mock.method()).thenReturn(value);              // 返回固定值
when(mock.method()).thenReturn(value1, value2);     // 多次调用不同返回值
when(mock.method()).thenThrow(new RuntimeException());  // 抛异常
when(mock.method()).thenAnswer(invocation -> {      // 自定义逻辑
    return invocation.getArgument(0) + " processed";
});

// 参数匹配
any()                  // 任意值
anyString()            // 任意字符串
anyLong()              // 任意 Long
eq(100)                // 等于 100
argThat(x -> x > 0)    // 自定义匹配

// 验证
verify(mock).method();                          // 验证被调用
verify(mock, times(3)).method();                // 调用 3 次
verify(mock, never()).method();                 // 从未调用
verify(mock, atLeast(1)).method();              // 至少 1 次
verify(mock, timeout(100)).method();            // 100ms 内被调用
verifyNoInteractions(mock);                     // 无交互

// 捕获参数
ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
verify(mock).send(captor.capture());
assertEquals("expected", captor.getValue());
```

---

## 3. 集成测试

### 3.1 Spring Boot 测试

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldCreateOrder() {
        // 发送真实 HTTP 请求
        OrderRequest request = new OrderRequest("product-1", 2);

        ResponseEntity<OrderResponse> response = restTemplate.postForEntity(
            "/api/orders", request, OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getOrderId()).isNotNull();
    }
}
```

### 3.2 切片测试

```java
// 只加载 Controller 层，不加载 Service/Repository
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @Test
    void shouldReturnOrders() throws Exception {
        // given
        when(orderService.findAll()).thenReturn(List.of(
            new Order(1L, "product-1", 100.0)
        ));

        // when & then
        mockMvc.perform(get("/api/orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(1))
            .andExpect(jsonPath("$.data[0].productName").value("product-1"));
    }
}
```

```java
// 只加载 JPA 层
@DataJpaTest
class OrderRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void shouldFindByStatus() {
        // given
        entityManager.persist(new Order("PENDING"));
        entityManager.persist(new Order("PAID"));

        // when
        List<Order> orders = orderRepository.findByStatus("PENDING");

        // then
        assertThat(orders).hasSize(1);
    }
}
```

| 切片注解 | 加载范围 |
|----------|----------|
| @WebMvcTest | Controller 层 |
| @DataJpaTest | JPA Repository 层 |
| @JsonTest | JSON 序列化 |
| @RestClientTest | REST 客户端 |
| @JdbcTest | JDBC 操作 |

### 3.3 测试数据库

```java
// 使用 H2 内存数据库测试
// 依赖
testImplementation 'com.h2database:h2'

// application-test.yml
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop  # 自动创建表

// 测试
@SpringBootTest
@ActiveProfiles("test")
class UserRepositoryTest {
    // 使用 H2 内存数据库，测试完自动销毁
}
```

---

## 4. 测试最佳实践

### 4.1 命名规范

```java
// 方法名：should_预期行为_when_条件
@Test
void should_returnUser_when_findByUsername() { }

@Test
void should_throwException_when_orderNotFound() { }

@Test
void should_notUpdateStock_when_paymentFails() { }
```

### 4.2 AAA 模式

```java
@Test
void shouldXXX() {
    // Arrange（准备）
    // Act（执行）
    // Assert（验证）
}
```

### 4.3 不要测什么

```java
// ❌ 不要测框架行为
// Spring 的 @Autowired 是否注入正确
// JPA 的 save 是否生成 SQL
// 这些都是框架的责任，不是你的

// ❌ 不要测 getter/setter
@Test
void testGetter() {
    User user = new User();
    user.setName("test");
    assertEquals("test", user.getName());  // 毫无意义
}
```

### 4.4 测试数据管理

```java
// 用测试工厂方法，避免硬编码
class TestDataFactory {
    static Order createOrder() {
        return createOrder("product-1", 2, 100.0);
    }

    static Order createOrder(String productId, int quantity, double amount) {
        Order order = new Order();
        order.setProductId(productId);
        order.setQuantity(quantity);
        order.setAmount(amount);
        return order;
    }
}

// 测试中
@Test
void shouldCalculateTotal() {
    Order order = TestDataFactory.createOrder("product-1", 3, 50.0);
    assertEquals(150.0, orderService.calculateTotal(order));
}
```

---

## 5. 🔴 常见坑

```java
// 坑 1：@SpringBootTest 太慢
// 每个 @SpringBootTest 都会启动完整容器
// 解决方案：用切片测试 / 纯单元测试

// 坑 2：测试间共享状态
static List<Order> sharedList = new ArrayList<>();  // ❌
// 测试 A 改了，测试 B 受影响
// 🟢 每个测试独立准备数据

// 坑 3：Mock 忘记设置行为
@Test
void shouldFail() {
    when(orderDao.findById(1L)).thenReturn(null);  // 忘了 mock
    orderService.processOrder(1L);  // NPE！因为 orderDao 返回 null
}

// 坑 4：用 Thread.sleep 等待异步
// ❌
@Test
void testAsync() throws Exception {
    asyncService.process();
    Thread.sleep(1000);  // 脆弱，浪费时间
    verify(mock).callback();
}

// ✅ 用 Awaitility
@Test
void testAsync() {
    asyncService.process();
    await().atMost(5, SECONDS)
        .until(() -> {
            verify(mock).callback();
            return true;
        });
}
```

---

## 6. API 速查

```java
// JUnit 5
@Test                              // 测试方法
@BeforeEach / @AfterEach           // 每个测试前后执行
@BeforeAll / @AfterAll             // 所有测试前后执行（static）
@DisplayName("描述")               // 测试显示名
@Disabled                          // 禁用测试
@Tag("fast")                       // 测试标签
@Nested                            // 嵌套测试
@RepeatedTest(10)                  // 重复执行
@ParameterizedTest                 // 参数化测试
@CsvSource({"1,2,3", "4,5,9"})    // CSV 参数

// AssertJ（链式断言，推荐）
assertThat(result)
    .isEqualTo(expected)
    .isNotNull()
    .isIn(list)
    .extracting(User::getName)
    .contains("Alice");

// Mockito
@Mock / @Spy / @InjectMocks / @Captor
when().thenReturn() / thenThrow() / thenAnswer()
verify().method() / times() / never()

// Spring Test
@SpringBootTest
@WebMvcTest / @DataJpaTest / @JsonTest
@TestRestTemplate / MockMvc / TestEntityManager
@ActiveProfiles("test")
@Sql("classpath:test-data.sql")    // 初始化 SQL
```
