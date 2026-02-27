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

## 工作流文件结构

```
workflow/
├── workflow.json          # DAG 结构定义
├── nodes/
│   ├── node_001.py        # 代码执行节点
│   ├── node_002_prompt.md # Agent 节点提示词
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

## 开发

```bash
./gradlew runIde
```

## License

Private
