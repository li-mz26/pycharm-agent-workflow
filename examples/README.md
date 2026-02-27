# 示例工作流

## 工作流结构

```
示例数据处理工作流/
├── workflow.json          # DAG 定义
├── nodes/
│   ├── code_001.py        # 数据清洗代码
│   ├── agent_001_prompt.md # 数据分析提示词
│   ├── agent_001_config.json # Agent 配置
│   └── code_002.py        # 空数据处理代码
└── README.md
```

## 节点说明

| 节点 | 类型 | 说明 |
|------|------|------|
| start_001 | start | 工作流入口，接收 raw_data |
| code_001 | code | Python 代码清洗数据 |
| condition_001 | condition | 判断是否有数据 |
| agent_001 | agent | LLM 分析数据（有数据分支）|
| code_002 | code | 处理空数据情况 |
| end_001 | end | 工作流结束 |

## 执行流程

1. 接收输入数据
2. 执行数据清洗（过滤 None 值）
3. 条件判断：是否有数据？
   - 是 → 调用 Agent 分析 → 结束
   - 否 → 返回空消息 → 结束
