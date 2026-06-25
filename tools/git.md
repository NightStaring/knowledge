# Git

> 进阶操作、工作流、问题排查。

---

## 1. 常用命令速查

### 1.1 日常操作

```bash
git status                    # 查看状态
git add -p                    # 交互式添加（逐个 hunk 确认）
git commit --amend            # 修改上次 commit
git commit --amend --no-edit  # 不改信息，追加到上次 commit
git log --oneline --graph     # 图形化日志
git log --oneline -10         # 最近 10 条
git log --author="name"       # 按作者过滤
```

### 1.2 分支操作

```bash
git branch -a                 # 查看所有分支（含远程）
git branch -d <name>          # 删除本地分支
git push origin --delete <name>  # 删除远程分支
git checkout -b <name>        # 创建并切换分支
git branch --merged           # 查看已合并的分支（可清理）
```

### 1.3 暂存

```bash
git stash                     # 暂存当前修改
git stash list                # 查看暂存列表
git stash pop                 # 恢复并删除
git stash apply stash@{2}     # 恢复指定暂存
git stash drop stash@{0}      # 删除指定暂存
git stash -u                  # 暂存包括未跟踪文件
```

---

## 2. 进阶操作

### 2.1 交互式 Rebase

```bash
# 合并/修改/重排 commit
git rebase -i HEAD~5           # 修改最近 5 个 commit
git rebase -i main             # 将当前分支的 commit 变基到 main
```

```
pick abc123  feat: 功能A        # 保留
squash def456  feat: 功能A修复   # 合并到上一个
reword 789ghi  fix: Bug修复     # 修改 commit 信息
drop 012jkl  debug: 测试        # 删除
```

### 2.2 查找 Bug（bisect）

```bash
# 二分查找引入 Bug 的 commit
git bisect start
git bisect bad                 # 当前版本有 Bug
git bisect good v1.0.0         # 标记一个好的版本
# Git 会自动切到中间的 commit，测试后标记
git bisect good                # 这个版本没问题
git bisect bad                 # 这个版本有问题
# 重复直到找到第一个有问题的 commit
git bisect reset               # 结束
```

### 2.3 Cherry-Pick

```bash
# 只挑某个 commit 到当前分支
git cherry-pick <commit-hash>

# 挑多个
git cherry-pick hash1 hash2 hash3

# 挑一段范围
git cherry-pick hash1..hash3   # hash1 之后的到 hash3
```

### 2.4 Reflog（后悔药）

```bash
git reflog                     # 查看所有操作历史
git reset --hard HEAD@{2}      # 恢复到两步前的状态
# 即使 rebase 搞砸了也能恢复！
```

---

## 3. 工作流

### 3.1 常见场景

```bash
# 场景：开发到一半，需要切分支修 Bug
git stash -u                   # 暂存当前工作
git checkout -b hotfix         # 切到 hotfix 分支
# 修 Bug、commit、push
git checkout dev               # 切回来
git stash pop                  # 恢复工作

# 场景：合并分支
git checkout main
git pull --rebase              # 拉取最新
git merge feature/xxx          # 合并
# 或
git rebase main                # 变基（保持历史线性）

# 场景：撤销
git reset --soft HEAD~1        # 撤销 commit，保留修改
git reset --mixed HEAD~1       # 撤销 commit，取消暂存
git reset --hard HEAD~1        # 彻底撤销（慎用！）
git checkout -- <file>         # 撤销文件修改
```

### 3.2 commit 规范

```
格式: <type>: <subject>

type:
  feat    新功能
  fix     Bug 修复
  refactor 重构
  docs    文档
  style   格式调整
  test    测试
  chore   构建/工具

示例:
  feat: 添加订单导出功能
  fix: 修复并发下单库存超卖问题
  refactor: 抽取支付策略模式
```

---

## 4. 🔴 常见问题

### 4.1 合并冲突

```bash
# 冲突标记：
<<<<<<< HEAD
当前分支的内容
=======
合并进来的内容
>>>>>>> feature/xxx

# 解决后
git add .
git commit        # 或 git rebase --continue
```

### 4.2 不小心 commit 到错误分支

```bash
# 把 commit 移到正确分支
git branch correct-branch       # 在错误分支创建一个分支指向当前
git reset --hard HEAD~1         # 撤销错误分支的 commit
git checkout correct-branch     # 切到正确分支
git push origin correct-branch  # 推送
```

### 4.3 修改历史 commit 的作者

```bash
git commit --amend --author="New Name <email@example.com>"
```

### 4.4 删除远程分支后本地残留

```bash
git remote prune origin        # 清理远程已删除的分支
# 或
git fetch --prune
```
