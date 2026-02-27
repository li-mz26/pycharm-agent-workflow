# 示例工作流

这个目录包含一个示例工作流，展示代码分离的格式。

## 文件结构

```
examples/
├── workflow_example.json    # 工作流定义，引用外部代码文件
├── nodes/
│   ├── code_001.py          # 数据清洗代码
│   ├── code_002.py          # 空数据处理代码
│   └── agent_001_prompt.md  # Agent 提示词
└── README.md                # 本文件
```

## 工作流说明

**示例数据处理工作流** - 展示如何读取数据、清洗、分析。

### 节点流程

```
开始 → 数据清洗 → 数据量检查 → [有数据] → 数据分析 → 结束
                      ↓
                   [无数据] → 空数据处理 → 结束
```

### 代码分离格式

- **code 节点**: 代码存储在 `nodes/{node_id}.py`，JSON 中使用 `codeFile` 引用
- **agent 节点**: 提示词存储在 `nodes/{node_id}_prompt.md`，JSON 中使用 `promptFile` 引用

### 示例代码片段

**nodes/code_001.py** (数据清洗):
```python
def main(inputs):
    data = inputs.get('raw_data', [])
    cleaned = [x for x in data if x is not None]
    return {'cleaned_data': cleaned}
```

**nodes/agent_001_prompt.md** (数据分析提示词):
```markdown
分析以下数据并给出统计摘要:

{{cleaned_data}}

请提供：
1. 数据总量
2. 平均值（如果是数值）
3. 数据类型分布
4. 简要分析结论
```

## 向后兼容

插件也支持旧格式的内联代码（`code` 字段直接包含代码），但推荐使用新的文件分离格式。
