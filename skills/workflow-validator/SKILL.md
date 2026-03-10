---
name: workflow-validator
description: 工作流开发与验证技能。当用户需要创建、修改、测试或调试工作流时触发。提供标准化的“开发→验证→测试→调试→迭代”工作流程，确保每次修改后都执行 workflow_valid 校验和 workflow_run 测试，支持单节点调试和错误日志分析。
---

# Workflow Validator - 工作流开发与验证

## 何时使用
当任务涉及以下场景时使用本技能：
- 创建新工作流；
- 修改已有工作流节点/连线/配置；
- 运行失败后的定位与修复；
- 稳定性回归验证。

## 强制闭环
每次修改后必须执行：

`修改 → workflow_valid → workflow_run → 日志分析 → (必要时单节点调试) → 继续迭代`

## 配套脚本
优先使用脚本自动执行上述闭环：

- `scripts/workflow_validation_loop.py`

### 典型用法

```bash
python skills/workflow-validator/scripts/workflow_validation_loop.py \
  --workflow workflows/workflows_dev/dynamic_workflow.py \
  --max-iterations 5 \
  --stable-runs 2
```

带期望输出检查：

```bash
python skills/workflow-validator/scripts/workflow_validation_loop.py \
  --workflow workflows/workflows_dev/dynamic_workflow.py \
  --expect "25"
```

## 脚本行为
- 每轮先执行 `workflow_valid`，失败则停止并输出修复建议。
- `workflow_valid` 通过后执行 `workflow_run`。
- 自动保存每轮验证/运行日志到 `.workflow-validator-logs/<timestamp>/`。
- 运行失败时尝试从日志中提取首个失败节点线索。
- 仅当连续 N 轮（默认 2）成功才判定稳定。
