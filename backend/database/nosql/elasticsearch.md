# Elasticsearch

> 全文搜索、ELK 栈、索引管理、查询 DSL、Spring Data 集成。

---

## 1. 核心概念

### 1.1 ES 与传统数据库对比

```
MySQL:     Database → Table → Row     → Column
ES:        Index    → Type  → Document → Field（已废弃 Type）
```

| ES 概念 | 类比 | 说明 |
|---------|------|------|
| **Index** | 数据库 | 索引（逻辑命名空间） |
| **Document** | 行 | JSON 文档 |
| **Field** | 列 | 字段 |
| **Mapping** | 表结构 | 字段类型定义 |
| **Shard** | 分片 | 数据分片（默认 1 主片 + 1 副本） |
| **Node** | 节点 | 一台 ES 服务器 |
| **Cluster** | 集群 | 多台 Node 组成 |

### 1.2 为什么用 ES

```
✅ 适合的场景：
  - 全文搜索（电商搜索、站内搜索）
  - 日志分析（ELK：Elasticsearch + Logstash + Kibana）
  - APM 链路追踪
  - 任何需要快速模糊搜索的场景

❌ 不适合：
  - 事务性操作
  - 复杂关联查询
  - 数据一致性要求高的场景（ES 是最终一致性）
```

---

## 2. 索引与映射

### 2.1 创建索引

```json
PUT /products
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1,
    "analysis": {
      "analyzer": {
        "ik_smart": {
          "type": "custom",
          "tokenizer": "ik_smart"
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "analyzer": "ik_max_word",     // 中文分词
        "fields": {
          "keyword": {
            "type": "keyword"           // 精确匹配
          }
        }
      },
      "price": { "type": "double" },
      "status": { "type": "keyword" },
      "created_at": { "type": "date" },
      "tags": { "type": "keyword" },   // 数组
      "description": { "type": "text" },
      "stock": { "type": "integer" }
    }
  }
}
```

### 2.2 字段类型

| 类型 | 说明 | 场景 |
|------|------|------|
| **text** | 全文搜索（分词） | 标题、描述 |
| **keyword** | 精确匹配（不分词） | 状态、标签 |
| **integer/long** | 整数 | 数量 |
| **double/float** | 浮点 | 价格 |
| **boolean** | 布尔 | 开关 |
| **date** | 日期 | 时间 |
| **nested** | 嵌套对象 | 数组对象 |
| **geo_point** | 地理位置 | 坐标 |

---

## 3. 查询 DSL

### 3.1 基本查询

```json
// 全文搜索
GET /products/_search
{
  "query": {
    "match": {
      "title": "手机"
    }
  }
}

// 精确匹配
GET /products/_search
{
  "query": {
    "term": {
      "status": "ACTIVE"
    }
  }
}

// 多条件
GET /products/_search
{
  "query": {
    "bool": {
      "must": [
        { "match": { "title": "手机" } }
      ],
      "filter": [
        { "term": { "status": "ACTIVE" } },
        { "range": { "price": { "gte": 1000, "lte": 5000 } } }
      ]
    }
  }
}
```

### 3.2 聚合

```json
// 统计每个状态的文档数
GET /products/_search
{
  "size": 0,
  "aggs": {
    "status_count": {
      "terms": {
        "field": "status"
      }
    },
    "price_stats": {
      "stats": {
        "field": "price"
      }
    }
  }
}

// 返回：
"aggregations": {
  "status_count": {
    "buckets": [
      { "key": "ACTIVE", "doc_count": 100 },
      { "key": "INACTIVE", "doc_count": 20 }
    ]
  },
  "price_stats": {
    "count": 120,
    "min": 10.0,
    "max": 9999.0,
    "avg": 500.0,
    "sum": 60000.0
  }
}
```

---

## 4. Spring Data ES

### 4.1 配置

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>
```

```yaml
spring:
  elasticsearch:
    uris: http://localhost:9200
```

### 4.2 实体

```java
@Document(indexName = "products")
public class Product {

    @Id
    private String id;

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String title;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Double)
    private Double price;

    @Field(type = FieldType.Date)
    private LocalDateTime createdAt;
}
```

### 4.3 Repository

```java
public interface ProductRepository
        extends ElasticsearchRepository<Product, String> {

    // 方法命名查询
    List<Product> findByTitle(String title);

    // 自定义查询
    @Query("{\"match\": {\"title\": \"?0\"}}")
    List<Product> searchByTitle(String keyword);

    // 多条件
    @Query("""
        {"bool": {
            "must": { "match": { "title": "?0" } },
            "filter": { "term": { "status": "?1" } }
        }}
        """)
    List<Product> searchProducts(String keyword, String status);
}
```

---

## 5. ELK 日志收集

### 5.1 架构

```
Application → Logstash → Elasticsearch → Kibana
    ↓                      ↓              ↓
 日志文件               存储/索引      可视化查询
```

### 5.2 Spring Boot 日志输出

```yaml
# logback-spring.xml
<appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
    <destination>localhost:5000</destination>
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
</appender>

<root level="INFO">
    <appender-ref ref="LOGSTASH"/>
</root>
```

---

## 6. 🔴 常见坑

```json
// 坑 1：分页深度问题
// ES 默认最多查 10000 条（index.max_result_window）
// 🟢 用 search_after 代替 from+size

// 坑 2：text 字段不能用于聚合
POST /products/_search
{
  "aggs": {
    "by_title": { "terms": { "field": "title" } }  // ❌ text 字段不支持
  }
}
// 🟢 用 title.keyword 子字段

// 坑 3：mapping 不能修改已存在的字段类型
// 🟢 创建新的索引，reindex

// 坑 4：集群脑裂
// 奇数个节点 + discovery.zen.minimum_master_nodes = N/2+1

// 坑 5：分片过多
// 每个分片都有开销，不要盲目设多
// 建议：单分片大小 20~50GB
```

---

## 7. 命令速查

```bash
# 索引管理
PUT /index_name            # 创建索引
DELETE /index_name         # 删除索引
GET /index_name            # 查看索引
GET /index_name/_mapping   # 查看映射
GET _cat/indices           # 查看所有索引

# 文档操作
POST /index/_doc           # 创建文档（自动生成 ID）
PUT /index/_doc/1          # 创建/更新文档
GET /index/_doc/1          # 获取文档
DELETE /index/_doc/1       # 删除文档
POST /index/_update/1      # 更新文档
{
  "doc": { "price": 2000 }
}

# 查询
GET /index/_search
{
  "query": { "match_all": {} },
  "size": 20,
  "from": 0,
  "sort": [{ "created_at": "desc" }]
}

# 批量操作
POST /_bulk
{"index": {"_index": "products", "_id": "1"}}
{"title": "手机", "price": 3000}
{"index": {"_index": "products", "_id": "2"}}
{"title": "电脑", "price": 5000}

# 管理
GET _cluster/health        # 集群健康
GET _cat/nodes             # 节点信息
POST /index/_reindex       # 重建索引
{
  "source": { "index": "old_index" },
  "dest": { "index": "new_index" }
}
```
