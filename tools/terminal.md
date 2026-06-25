# 终端

> Shell 配置、CLI 工具、效率技巧。

---

## 1. Shell 配置

### 1.1 推荐 Shell

| Shell | 说明 | 推荐 |
|-------|------|------|
| **bash** | 默认，最通用 | 服务器 |
| **zsh** | 兼容 bash，更强大 | 开发机推荐 |
| **fish** | 开箱即用，语法友好 | 新手友好 |

### 1.2 Oh My Zsh 推荐插件

```bash
# .zshrc 配置
plugins=(
    git             # Git 别名
    z               # 快速跳转目录
    autojump        # 目录跳转
    extract         # 一键解压
    docker          # Docker 补全
    docker-compose  # Docker Compose 补全
    mvn             # Maven 补全
    gradle          # Gradle 补全
    history         # 历史记录搜索
)
```

### 1.3 常用 alias

```bash
# 基础
alias ..='cd ..'
alias ...='cd ../..'
alias ll='ls -lh'
alias la='ls -lah'
alias l='ls -CF'

# Git
alias gs='git status'
alias gl='git log --oneline --graph'
alias ga='git add'
alias gc='git commit'
alias gp='git push'
alias gpl='git pull --rebase'
alias gd='git diff'
alias gco='git checkout'
alias gb='git branch'

# 便捷操作
alias path='echo $PATH | tr ":" "\n"'
alias ports='netstat -tlnp'
alias myip='curl ifconfig.me'
```

---

## 2. 现代 CLI 工具

### 2.1 替代方案

| 传统命令 | 现代替代 | 说明 |
|----------|----------|------|
| `find` | **fd** | 更快的文件查找 |
| `grep` | **rg** (ripgrep) | 更快的搜索 |
| `cat` | **bat** | 带语法高亮的 cat |
| `ls` | **exa** / **eza** | 带图标和颜色 |
| `top` | **htop** | 更直观的进程查看 |
| `du` | **ncdu** | 磁盘空间分析 |
| `cut/sort` | **jq** | JSON 处理 |
| `sed` | **sd** | 更直观的查找替换 |

### 2.2 安装方式

```bash
# Windows (Git Bash / Scoop)
scoop install fd ripgrep bat eza htop ncdu jq

# macOS
brew install fd ripgrep bat eza htop ncdu jq

# Linux
apt install fd-find ripgrep bat htop ncdu jq
```

---

## 3. 日常高效操作

### 3.1 搜索

```bash
# 文件内容搜索（比 grep 快 10 倍）
rg "error" --type java
rg "TODO" --type py
rg "class User" -g "*.java"

# 文件名搜索
fd "OrderService"
fd -e java "Repository"

# 历史命令搜索
# Ctrl+R 然后输入关键字
```

### 3.2 文件操作

```bash
# 快速跳转（z / autojump）
z proj          # 跳转到 ~/projects/xxx
z down          # 跳转到 ~/Downloads

# 文件预览
bat src/main/java/OrderService.java  # 带行号和高亮

# 目录树
tree -L 2       # 2 层深度
```

### 3.3 管道技巧

```bash
# JSON 处理
curl api.example.com/users | jq '.data[] | {id, name}'

# 统计
cat access.log | awk '{print $1}' | sort | uniq -c | sort -rn | head -10

# 查找最大文件
find . -type f -exec du -sh {} \; | sort -rh | head -10
# 或用 ncdu
ncdu
```

---

## 4. 终端复用

### 4.1 tmux 常用操作

```
# 会话管理
tmux new -s <name>      # 创建新会话
tmux ls                 # 列出会话
tmux attach -t <name>   # 附加到会话
tmux kill-session -t <name>  # 删除会话

# 窗口操作（prefix 默认 Ctrl+B）
prefix + c              # 创建窗口
prefix + n/p            # 下一个/上一个窗口
prefix + w              # 窗口列表
prefix + ,              # 重命名窗口

# 面板操作
prefix + "              # 水平分割
prefix + %              # 垂直分割
prefix + 方向键          # 切换面板
prefix + z              # 全屏/恢复面板
```

---

## 5. 一键脚本示例

```bash
# 查找并杀死 Java 进程
alias killjava='ps aux | grep java | grep -v grep | awk "{print \$2}" | xargs kill -9'

# 快速启动项目
alias dev-up='docker-compose up -d && mvn spring-boot:run'

# 清理日志
alias clean-logs='find . -name "*.log" -type f -delete'

# 创建备份
backup() {
    tar -czf "backup-$(date +%Y%m%d_%H%M%S).tar.gz" "$1"
}
```
