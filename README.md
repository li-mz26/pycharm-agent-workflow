# PyCharm Agent Workflow Plugin

通过自然语言对话生成 DAG 工作流的 PyCharm 插件。

## 核心功能

- **对话式工作流生成** - 与 Agent 对话描述需求，自动生成工作流
- **DAG 可视化编辑** - 类似 Dify 的节点编辑器
- **多节点类型支持**:
  - 开始/结束节点
  - 条件分支节点
  - 代码执行节点（Python）
  - Agent 节点（LLM 调用）
- **导出完整工作流** - JSON DAG + 代码文件 + 提示词文件
- **加载预览** - 从工作流文件夹加载并可视化

## 工作流文件结构

```
project/
└── workflows/
    └── my_workflow/                 # 一个工作流一个文件夹
        ├── workflow.json            # DAG 结构定义
        ├── nodes/
        │   ├── node_001.py          # 代码执行节点 → Python 文件
        │   ├── node_002_prompt.md   # Agent 节点 → 提示词
        │   ├── node_002_config.json # Agent 节点 → 配置
        │   └── ...
        └── README.md
```

## 节点类型

| 类型 | 说明 | 输出文件 |
|------|------|----------|
| `start` | 工作流入口 | - |
| `end` | 工作流出口 | - |
| `condition` | 条件分支 | - |
| `code` | Python 代码执行 | `.py` 文件 |
| `agent` | LLM Agent 调用 | `_prompt.md` + `_config.json` |
| `http` | HTTP 请求 | - |
| `variable` | 变量赋值 | - |

## 使用方式

1. **生成工作流**: 打开 Agent Workflow 工具窗口，在左侧对话面板描述需求
2. **可视化编辑**: 右侧画布查看/调整 DAG 结构
3. **导出**: 点击导出按钮，工作流文件夹生成在当前项目中
4. **预览加载**: 右键工作流文件夹 → "Preview Workflow"

## 开发

```bash
./gradlew runIde
```

## 受限网络环境（如 Codex 云）说明

如果运行测试时出现 `pycharmPC:2024.1` 下载 `403`，通常是网络出口拦截了 JetBrains 仓库或 cache-redirector，并非业务代码问题。

可用方案：

1. 使用本机已安装的 PyCharm 作为 SDK（避免下载）

```bash
PYCHARM_HOME="/path/to/pycharm" gradle test --tests com.limz26.workflow.mcp.WorkflowMcpAlarmWorkflowIntegrationTest
```

或：

```bash
gradle test -Pintellij.localPath=/path/to/pycharm --tests com.limz26.workflow.mcp.WorkflowMcpAlarmWorkflowIntegrationTest
```

2. 已在 `gradle.properties` 关闭 JetBrains cache-redirector，减少部分网络环境下的 403。

## License

Private
