# 容器化部署

> Docker 镜像构建、Kubernetes 部署、Java 应用容器化最佳实践。

---

## 1. Docker 基础

### 1.1 Dockerfile 最佳实践

```dockerfile
# ============================================================
# 多阶段构建：构建阶段 + 运行阶段分离
# 最终镜像只包含运行所需，不包含编译工具
# ============================================================

# 阶段 1：构建
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B          # 先下载依赖（利用缓存）
COPY src ./src
RUN mvn package -DskipTests -B            # 打包

# 阶段 2：运行
FROM eclipse-temurin:17-jre-alpine        # 最小化 JRE 镜像
WORKDIR /app

# 添加非 root 用户（安全）
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/target/*.jar app.jar

# JVM 参数优化
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx512m"

EXPOSE 8080
USER appuser                              # 以非 root 运行
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### 1.2 常用命令

```bash
# 构建
docker build -t order-service:1.0.0 .
docker build -t order-service:1.0.0 --platform linux/amd64 .  # 指定平台

# 运行
docker run -d --name order-service -p 8080:8080 order-service:1.0.0

# 环境变量
docker run -d \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL=jdbc:mysql://host:3306/db \
  -p 8080:8080 \
  order-service:1.0.0

# 网络
docker network create my-network
docker run --network my-network --name order-service order-service:1.0.0

# 调试
docker logs -f order-service
docker exec -it order-service sh
docker stats order-service
```

### 1.3 Java 容器化 JVM 优化

```dockerfile
# 给容器正确设置 JVM 内存
ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \        # 感知容器内存限制
  -XX:InitialRAMPercentage=50.0 \   # 初始堆 = 容器内存 50%
  -XX:MaxRAMPercentage=75.0 \       # 最大堆 = 容器内存 75%
  -XX:+PrintFlagsFinal \
  "
```

```yaml
# docker-compose.yml
version: '3.8'
services:
  order-service:
    image: order-service:1.0.0
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 1G
    environment:
      - JAVA_OPTS=-XX:+UseContainerSupport
        -XX:InitialRAMPercentage=50.0
        -XX:MaxRAMPercentage=75.0
```

---

## 2. Kubernetes 部署

### 2.1 Deployment

```yaml
# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
  labels:
    app: order-service
spec:
  replicas: 3                     # 3 个副本
  selector:
    matchLabels:
      app: order-service
  template:
    metadata:
      labels:
        app: order-service
    spec:
      containers:
        - name: order-service
          image: registry.example.com/order-service:1.0.0
          ports:
            - containerPort: 8080
          # 资源限制
          resources:
            requests:
              cpu: "500m"         # 请求 0.5 核
              memory: "512Mi"     # 请求 512MB
            limits:
              cpu: "2"            # 最多 2 核
              memory: "1Gi"       # 最多 1GB
          # 健康检查
          livenessProbe:           # 存活检查（失败重启容器）
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:          # 就绪检查（失败停止接收流量）
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 5
          # 启动探针（给慢启动应用更多时间）
          startupProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 10
            failureThreshold: 30   # 最多等 30 × 10 = 300 秒
            periodSeconds: 10
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "prod"
            - name: DB_URL
              valueFrom:
                secretKeyRef:
                  name: db-secret
                  key: url
      # 优雅关闭
      terminationGracePeriodSeconds: 60  # 给 JVM 60 秒优雅关闭
```

### 2.2 Service

```yaml
# service.yaml
apiVersion: v1
kind: Service
metadata:
  name: order-service
spec:
  selector:
    app: order-service
  ports:
    - port: 80               # 集群内访问端口
      targetPort: 8080        # 容器端口
  type: ClusterIP            # 集群内可访问
```

### 2.3 ConfigMap & Secret

```yaml
# configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
data:
  application.yml: |
    server:
      port: 8080
    spring:
      datasource:
        url: jdbc:mysql://mysql-service:3306/db
---
# secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: db-secret
type: Opaque
data:
  username: cm9vdA==          # base64("root")
  password: cGFzc3dvcmQ=      # base64("password")
---
# deployment 中引用
spec:
  containers:
    - name: order-service
      volumeMounts:
        - name: config
          mountPath: /app/config
      env:
        - name: DB_USERNAME
          valueFrom:
            secretKeyRef:
              name: db-secret
              key: username
  volumes:
    - name: config
      configMap:
        name: app-config
```

### 2.4 Ingress

```yaml
# ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: api-gateway
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
spec:
  ingressClassName: nginx
  rules:
    - host: api.example.com
      http:
        paths:
          - path: /orders
            pathType: Prefix
            backend:
              service:
                name: order-service
                port:
                  number: 80
          - path: /users
            pathType: Prefix
            backend:
              service:
                name: user-service
                port:
                  number: 80
```

### 2.5 HPA（自动扩缩容）

```yaml
# hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: order-service-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: order-service
  minReplicas: 2              # 最少 2 个
  maxReplicas: 10             # 最多 10 个
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70  # CPU > 70% 扩容
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80  # 内存 > 80% 扩容
```

---

## 3. 优雅关闭

### 3.1 Spring Boot 配置

```yaml
# application.yml
server:
  shutdown: graceful           # 优雅关闭

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # 最多等 30 秒
```

### 3.2 Kubernetes 配合

```yaml
# preStop hook：收到 SIGTERM 后延迟关闭
spec:
  containers:
    - name: order-service
      lifecycle:
        preStop:
          exec:
            command:
              - sh
              - -c
              - "sleep 10"    # 等 10 秒再退出（给负载均衡器时间摘除节点）
```

---

## 4. 🔴 常见坑

```dockerfile
// 坑 1：JVM 不认识容器内存限制（JDK 8 早期版本）
// 🟢 JDK 10+ 默认支持，JDK 8 需要手动开启
-XX:+UnlockExperimentalVMOptions
-XX:+UseCGroupMemoryLimitForHeap

// 坑 2：镜像太大
// ❌ 基础镜像用 openjdk:17（~400MB）
// ✅ 用 eclipse-temurin:17-jre-alpine（~150MB）
// ✅ 用多阶段构建

// 坑 3：Pod 频繁重启
// 可能原因：资源限制太小、健康检查配置不当
// 🟢 加大 startupProbe 的 failureThreshold
// 🟢 用初始延迟 initialDelaySeconds

// 坑 4：日志未输出到标准输出
// Spring Boot 默认输出到 stdout ✅
// 🟢 确保 logback 配置输出到 console

// 坑 5：配置中心不可用导致启动失败
// 🟢 配置 spring.cloud.nacos.config.import-check.enabled=false
```

---

## 5. 常用命令速查

```bash
# Docker
docker build -t name:tag .
docker run -d --name name -p 8080:8080 image
docker logs -f name
docker exec -it name sh
docker stats
docker system prune -a        # 清理

# kubectl
kubectl get pods
kubectl get deployments
kubectl get services
kubectl get nodes
kubectl logs -f pod-name
kubectl exec -it pod-name -- sh
kubectl apply -f deployment.yaml
kubectl delete pod pod-name
kubectl describe pod pod-name  # 排查问题
kubectl top pod                # 资源使用

# 调试
kubectl port-forward pod-name 8080:8080  # 本地转发
kubectl get events --sort-by='.lastTimestamp'  # 查看事件
```
