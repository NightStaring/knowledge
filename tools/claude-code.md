# Claude Code

> 常用命令、Skill 推荐、配置技巧。

---

## 1. 常用命令

### 1.1 会话管理

| 命令 | 说明 |
|------|------|
| `/compact` | 压缩上下文，清理早期对话历史 |
| `/clear` | 清空当前对话，重新开始 |
| `/rename <name>` | 重命名当前会话 |
| `/tutorial` | 打开交互式教程 |

### 1.2 配置与查看

| 命令 | 说明 |
|------|------|
| `/config` | 打开设置界面 |
| `/config get <key>` | 查看配置项 |
| `/config set <key> <value>` | 设置配置项 |
| `/fast` | 切换快速模式（Opus 快速输出） |

### 1.3 项目管理

| 命令 | 说明 |
|------|------|
| `/init` | 初始化 CLAUDE.md 项目文档 |
| `/plan` | 进入计划模式 |
| `/review` | 审查代码变更 |
| `/code-review [--fix]` | 代码审查并可选应用修复 |

### 1.4 高级功能

| 命令 | 说明 |
|------|------|
| `/loop <interval> <prompt>` | 定时循环执行任务 |
| `/background <prompt>` | 后台执行任务 |
| `/workflows` | 查看工作流状态 |
| `/tasks` | 查看任务列表 |

---

## 2. 常用 Skill

### 2.1 开发流程

| Skill | 用途 |
|-------|------|
| **brainstorming** | 创意/功能实现前必须先执行，探索需求与设计 |
| **writing-plans** | 编写实施计划 |
| **executing-plans** | 按计划分步执行 |
| **requesting-code-review** | 完成实现后请求代码审查 |
| **receiving-code-review** | 接收审查反馈并验证 |

### 2.2 质量保障

| Skill | 用途 |
|-------|------|
| **test-driven-development** | TDD 流程 |
| **systematic-debugging** | 遇到 Bug 时的系统化排查 |
| **verification-before-completion** | 完成前验证 |

### 2.3 效率工具

| Skill | 用途 |
|-------|------|
| **loop** | 定时执行任务 |
| **simplify** | 代码简化与重构 |
| **fewer-permission-prompts** | 减少权限提示 |
| **using-git-worktrees** | 隔离工作区 |

---

## 3. 配置技巧

### 3.1 settings.json

```json
{
  "theme": "light-daltonized",
  "editorMode": "vim",
  "verbose": true,
  "autoCompactEnabled": true,
  "effortLevel": "high"
}
```

### 3.2 常用配置项

| 配置 | 说明 |
|------|------|
| `theme` | 主题（dark/light/light-daltonized） |
| `editorMode` | 编辑模式（normal/vim） |
| `verbose` | 显示完整工具输出 |
| `autoCompactEnabled` | 自动压缩上下文 |
| `effortLevel` | 推理深度（low/medium/high/xhigh） |
| `model` | 指定模型 |

### 3.3 环境变量

```bash
# 自定义 API 端点（如国内代理）
ANTHROPIC_BASE_URL=https://ark.cn-beijing.volces.com/api/coding
ANTHROPIC_AUTH_TOKEN=your-token
ANTHROPIC_MODEL=ark-code-latest
```

---

## 4. 最佳实践

### 4.1 任务拆分

```
❌ 一次性大任务：
  "帮我写一个完整的电商系统"

✅ 分步：
  1. "/plan 设计订单模块架构"
  2. "实现订单实体和仓储层"
  3. "/code-review" 审查代码
```

### 4.2 善用 Skill

```
每次做重要操作前，先问自己：
"有没有 Skill 可以用？"

开发新功能 → brainstorming
发现 Bug → systematic-debugging
写完代码 → requesting-code-review
```

### 4.3 上下文管理

- 长对话及时 `/compact`
- 无关话题开新会话
- 项目根目录放 CLAUDE.md 提供上下文
