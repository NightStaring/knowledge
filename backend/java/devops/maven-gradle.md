# Maven / Gradle

> 构建工具进阶——依赖管理、多模块、生命周期、性能优化。

---

## 1. Maven vs Gradle

| 维度 | Maven | Gradle |
|------|-------|--------|
| 构建文件 | pom.xml（XML） | build.gradle（Groovy/Kotlin） |
| 性能 | 较慢 | 快（增量构建） |
| 依赖管理 | 成熟 | 成熟 |
| 灵活性 | 固定生命周期 | 高度可定制 |
| 学习曲线 | 低 | 中 |
| 国内流行 | 高（传统项目） | 增长中（新项目） |
| 推荐 | 传统项目 | 新项目、Android |

---

## 2. Maven 核心

### 2.1 pom.xml 结构

```xml
<project>
    <modelVersion>4.0.0</modelVersion>

    <!-- 坐标（唯一标识） -->
    <groupId>com.example</groupId>
    <artifactId>order-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>  <!-- jar/war/pom -->

    <!-- 属性 -->
    <properties>
        <java.version>17</java.version>
        <spring-boot.version>3.3.4</spring-boot.version>
    </properties>

    <!-- 依赖 -->
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <version>${spring-boot.version}</version>
        </dependency>
    </dependencies>
</project>
```

### 2.2 依赖范围

```xml
<scope>compile</scope>    <!-- 默认，所有阶段可用 -->
<scope>provided</scope>   <!-- 编译时需要，运行时由容器提供（如 Servlet API） -->
<scope>runtime</scope>    <!-- 运行时需要，编译不需要（如 JDBC 驱动） -->
<scope>test</scope>       <!-- 仅测试 -->
<scope>system</scope>     <!-- 本地 jar（不推荐） -->
```

### 2.3 依赖冲突

```xml
<!-- 排除传递依赖 -->
<dependency>
    <groupId>com.example</groupId>
    <artifactId>some-lib</artifactId>
    <exclusions>
        <exclusion>
            <groupId>commons-logging</groupId>
            <artifactId>commons-logging</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<!-- 强制指定版本 -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>conflict-lib</artifactId>
            <version>2.0</version>  <!-- 统一版本 -->
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 2.4 多模块

```xml
<!-- 父 pom -->
<groupId>com.example</groupId>
<artifactId>parent</artifactId>
<version>1.0.0</version>
<packaging>pom</packaging>  <!-- pom 类型 -->

<modules>
    <module>common</module>
    <module>order-service</module>
    <module>user-service</module>
    <module>gateway</module>
</modules>

<!-- 统一版本管理 -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>3.3.4</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

```xml
<!-- 子模块 pom -->
<parent>
    <groupId>com.example</groupId>
    <artifactId>parent</artifactId>
    <version>1.0.0</version>
</parent>

<artifactId>order-service</artifactId>

<dependencies>
    <!-- 引用兄弟模块 -->
    <dependency>
        <groupId>com.example</groupId>
        <artifactId>common</artifactId>
    </dependency>
</dependencies>
```

### 2.5 常用命令

```bash
# 清理
mvn clean

# 编译
mvn compile
mvn test-compile

# 测试
mvn test
mvn test -Dtest=OrderServiceTest  # 指定测试类
mvn test -DskipTests=false        # 运行测试
mvn test -Dmaven.test.skip=true   # 跳过测试

# 打包
mvn package
mvn package -DskipTests

# 安装到本地仓库
mvn install

# 发布到远程仓库
mvn deploy

# 多模块
mvn clean install -pl order-service -am  # 只构建 order-service 及其依赖
mvn clean install -rf order-service      # 从 order-service 开始构建

# 依赖分析
mvn dependency:tree              # 依赖树
mvn dependency:analyze           # 分析未使用的依赖
mvn help:effective-pom           # 查看生效的 pom

# 运行 Spring Boot
mvn spring-boot:run
```

---

## 3. Gradle 核心

### 3.1 build.gradle.kts（Kotlin DSL）

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.example"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.aliyun.com/repository/public") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

### 3.2 多模块

```kotlin
// settings.gradle.kts
rootProject.name = "parent"
include("common", "order-service", "user-service")
```

```kotlin
// order-service/build.gradle.kts
dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
}
```

### 3.3 常用命令

```bash
# 清理
./gradlew clean

# 编译
./gradlew build

# 测试
./gradlew test
./gradlew test --tests *OrderServiceTest

# 打包（跳过测试）
./gradlew build -x test

# 运行
./gradlew bootRun
```

---

## 4. 性能优化

### 4.1 Maven 优化

```xml
<!-- 多线程编译 -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <fork>true</fork>
        <compilerArgs>
            <arg>-parameters</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

```bash
# 多线程构建
mvn -T 4 clean install           # 4 线程
mvn -T 1C clean install          # 每核 1 线程

# 离线模式（不检查远程更新）
mvn -o clean install

# 跳过测试和文档
mvn clean install -DskipTests -Dmaven.javadoc.skip=true
```

### 4.2 Gradle 优化

```kotlin
// 开启并行和缓存
// gradle.properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.daemon=true
org.gradle.jvmargs=-Xmx2048m
```

---

## 5. 🔴 常见坑

```xml
<!-- 坑 1：依赖冲突 -->
<!-- 同一个依赖不同版本 → 取最近路径的版本 -->
<!-- 🟢 dependency:tree 查看，用 exclusions 排除 -->

<!-- 坑 2：Maven 下载慢 -->
<!-- 🟢 配置阿里云镜像 -->
<mirrors>
    <mirror>
        <id>aliyun</id>
        <mirrorOf>central</mirrorOf>
        <name>阿里云公共仓库</name>
        <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
</mirrors>

<!-- 坑 3：本地仓库占用空间大 -->
<!-- 🟢 定期清理：rm -rf ~/.m2/repository 重新下载 -->

<!-- 坑 4：Maven 和 IDE 版本不一致 -->
<!-- IDE 内嵌 Maven 可能与命令行版本不同 -->
<!-- 🟢 IDE 中配置使用本地 Maven -->
```

---

## 6. API 速查

```xml
<!-- Maven 命令 -->
mvn clean                    # 清理
mvn compile                  # 编译
mvn test                     # 测试
mvn package                  # 打包
mvn install                  # 安装到本地
mvn deploy                   # 部署到远程
mvn dependency:tree          # 依赖树
mvn help:effective-pom       # 生效配置
mvn spring-boot:run          # 运行

<!-- Maven 参数 -->
-DskipTests                  # 跳过测试
-Dmaven.test.skip=true       # 跳过测试编译
-Plocal                      # 激活 profile
-T 4                         # 4 线程构建
-o                           # 离线模式
-U                           # 强制更新快照
```

```kotlin
// Gradle 命令
./gradlew build               # 构建
./gradlew clean               # 清理
./gradlew test                # 测试
./gradlew bootRun             # 运行
./gradlew dependencies        # 依赖树
./gradlew --scan              # 构建分析
```
