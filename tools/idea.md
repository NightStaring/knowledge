# IDEA

> 高效操作、插件、调试技巧。

---

## 1. 高效快捷键

### 1.1 导航（Windows）

| 快捷键 | 说明 |
|--------|------|
| `Ctrl+N` | 查找类 |
| `Ctrl+Shift+N` | 查找文件 |
| `Ctrl+E` | 最近文件 |
| `Ctrl+B` | 跳转到声明 |
| `Ctrl+Alt+B` | 跳转到实现 |
| `Ctrl+U` | 跳转到父类/接口 |
| `Ctrl+F12` | 文件结构 |
| `Ctrl+H` | 类型层次 |
| `Alt+F7` | 查找用法 |
| `Ctrl+Shift+F7` | 高亮用法 |
| `Ctrl+G` | 跳转到行 |
| `Ctrl+Alt+←/→` | 前进/后退导航 |

### 1.2 编辑

| 快捷键 | 说明 |
|--------|------|
| `Ctrl+W` | 递进选择 |
| `Ctrl+Shift+W` | 递减退选 |
| `Ctrl+D` | 复制行 |
| `Ctrl+Y` | 删除行 |
| `Alt+↑/↓` | 移动行 |
| `Ctrl+Shift+Enter` | 补全当前语句 |
| `Ctrl+Alt+L` | 格式化代码 |
| `Ctrl+Alt+O` | 优化导入 |
| `Ctrl+/` | 注释/取消注释 |
| `Ctrl+Shift+/` | 块注释 |
| `Ctrl+Alt+T` | 包裹（try/catch/if 等） |
| `Shift+F6` | 重命名 |

### 1.3 重构

| 快捷键 | 说明 |
|--------|------|
| `Ctrl+Alt+M` | 提取方法 |
| `Ctrl+Alt+V` | 提取变量 |
| `Ctrl+Alt+F` | 提取字段 |
| `Ctrl+Alt+P` | 提取参数 |
| `Ctrl+Alt+N` | 内联 |
| `F6` | 移动类 |

### 1.4 调试

| 快捷键 | 说明 |
|--------|------|
| `F8` | 单步跳过 |
| `F7` | 单步进入 |
| `Shift+F8` | 单步跳出 |
| `F9` | 继续运行 |
| `Ctrl+F8` | 切换断点 |
| `Alt+F8` | 表达式求值 |
| `Ctrl+Shift+F8` | 查看断点 |

---

## 2. 必装插件

| 插件 | 说明 |
|------|------|
| **Lombok** | 减少样板代码 |
| **MyBatisX** | MyBatis 跳转、自动生成 |
| **Alibaba Java Coding Guidelines** | 代码规范检查 |
| **Rainbow Brackets** | 彩色括号 |
| **GitToolBox** | Git 信息展示 |
| **Maven Helper** | 依赖冲突分析 |
| **Grep Console** | 控制台高亮 |
| **Translation** | 翻译插件 |

---

## 3. 调试技巧

### 3.1 条件断点

```
右键断点 → 输入条件表达式
例如：在循环中只对特定值断点
  order.getAmount() > 10000
```

### 3.2 表达式求值（Alt+F8）

```
断住后按 Alt+F8，可以执行任意代码：
  orderService.getOrderDetail(orderId)
  userDao.findById(1L)
```

### 3.3 日志断点

```
右键断点 → 取消 Suspend → 勾选 Log message
相当于在代码中加了一行 System.out.println
不需要改代码、不需要重启
```

### 3.4 远程调试

```bash
# 应用启动参数
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -jar app.jar

# IDEA 配置 Run → Edit Configurations → + → Remote JVM Debug
```

---

## 4. 常用 Live Template

| 缩写 | 展开 |
|------|------|
| `psfs` | `public static final String` |
| `sout` | `System.out.println()` |
| `soutm` | `System.out.println("方法名")` |
| `soutp` | `System.out.println(参数)` |
| `serr` | `System.err.println()` |
| `fori` | for 循环 |
| `iter` | for-each 循环 |
| `ifn` | `if (xxx == null)` |
| `inn` | `if (xxx != null)` |
| `thr` | `throw new` |
| `return` | 补全返回值 |
