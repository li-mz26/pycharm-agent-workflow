package com.limz26.workflow.model

import com.google.gson.GsonBuilder
import java.io.File

/**
 * 工作流导出器 - 生成 JSON + 代码文件 + 提示词文件到项目 workflows 目录
 */
class WorkflowExporter(private val projectBasePath: String) {
    
    private val gson = GsonBuilder().setPrettyPrinting().create()
    
    /**
     * 导出完整工作流到项目 workflows 目录
     * 一个工作流一个文件夹
     */
    fun export(workflow: Workflow): String {
        // 工作流目录: project/workflows/workflow_name/
        val workflowsDir = File(projectBasePath, "workflows")
        workflowsDir.mkdirs()
        
        val workflowDirName = workflow.name.replace(Regex("[^a-zA-Z0-9_\u4e00-\u9fa5]"), "_")
        val workflowDir = File(workflowsDir, workflowDirName)
        workflowDir.mkdirs()
        
        val nodesDir = File(workflowDir, "nodes")
        nodesDir.mkdirs()
        
        // 1. 导出各个节点的代码/提示词文件（先导出，以便在 JSON 中引用）
        workflow.nodes.forEach { node ->
            exportNodeFiles(node, nodesDir)
        }
        
        // 2. 导出 workflow.json（代码文件路径引用外部文件）
        val workflowDef = WorkflowDefinition(
            id = workflow.id,
            name = workflow.name,
            description = workflow.description,
            nodes = workflow.nodes.map { node ->
                // 根据节点类型确定文件引用
                val (codeRef, codeFileRef) = when (node.type) {
                    NodeType.CODE -> {
                        // 代码在外部文件，JSON 中只保留文件路径引用
                        val fileRef = "nodes/${node.id}.py"
                        Pair(null, fileRef)
                    }
                    else -> Pair(node.config.code, null)
                }
                
                val (promptRef, promptFileRef) = when (node.type) {
                    NodeType.AGENT -> {
                        // 提示词在外部文件
                        val fileRef = "nodes/${node.id}_prompt.md"
                        Pair(null, fileRef)
                    }
                    else -> Pair(node.config.prompt, null)
                }
                
                NodeDefinition(
                    id = node.id,
                    type = node.type.value,
                    name = node.name,
                    position = PositionDefinition(node.position.x, node.position.y),
                    config = NodeConfigDefinition(
                        code = codeRef,
                        codeFile = codeFileRef,
                        prompt = promptRef,
                        promptFile = promptFileRef,
                        model = node.config.model,
                        condition = node.config.condition,
                        method = node.config.method,
                        url = node.config.url,
                        headers = node.config.headers,
                        value = node.config.value,
                        inputs = node.config.inputs,
                        outputs = node.config.outputs
                    )
                )
            },
            edges = workflow.edges.map { edge ->
                EdgeDefinition(
                    id = edge.id,
                    source = edge.source,
                    target = edge.target,
                    condition = edge.condition
                )
            },
            variables = workflow.variables.mapValues { (_, v) ->
                VariableDefinition(type = v.type, default = v.defaultValue)
            }
        )
        
        File(workflowDir, "workflow.json").writeText(gson.toJson(workflowDef))
        
        // 3. 导出 README
        File(workflowDir, "README.md").writeText(generateReadme(workflow))
        
        return workflowDir.absolutePath
    }
    
    private fun exportNodeFiles(node: WorkflowNode, nodesDir: File) {
        when (node.type) {
            NodeType.CODE -> {
                // 导出 Python 文件
                val code = node.config.code ?: generateDefaultCode(node.name)
                File(nodesDir, "${node.id}.py").writeText(code)
            }
            NodeType.AGENT -> {
                // 导出提示词文件
                val prompt = node.config.prompt ?: "# TODO: Define prompt for ${node.name}\n\nDescribe the agent's task here..."
                File(nodesDir, "${node.id}_prompt.md").writeText(prompt)
                
                // 导出配置
                val config = AgentConfig(
                    model = node.config.model ?: "gpt-4",
                    temperature = 0.7,
                    inputs = node.config.inputs,
                    outputs = node.config.outputs
                )
                File(nodesDir, "${node.id}_config.json").writeText(gson.toJson(config))
            }
            else -> {
                // 其他节点类型，如果有特殊配置也导出
                if (node.config.code != null || node.config.prompt != null || 
                    node.config.condition != null) {
                    File(nodesDir, "${node.id}_config.json").writeText(gson.toJson(node.config))
                }
            }
        }
    }
    
    private fun generateDefaultCode(nodeName: String): String {
        return """# $nodeName
# Generated by Agent Workflow Plugin

def main(inputs: dict) -> dict:
    \"\"\"
    Process inputs and return outputs.
    
    Args:
        inputs: Dictionary containing input values
        
    Returns:
        Dictionary containing output values
    \"\"\"
    # TODO: Implement your logic here
    result = {}
    
    # Example: process each input
    for key, value in inputs.items():
        # Process value...
        result[key] = value
    
    return result


if __name__ == "__main__":
    # Test the function
    test_inputs = {}
    print(main(test_inputs))
""".trimIndent()
    }
    
    private fun generateReadme(workflow: Workflow): String {
        val nodeTable = workflow.nodes.joinToString("\n") { node ->
            val fileInfo = when (node.type) {
                NodeType.CODE -> "→ `${node.id}.py`"
                NodeType.AGENT -> "→ `${node.id}_prompt.md`, `${node.id}_config.json`"
                else -> ""
            }
            "| ${node.id} | ${node.type.value} | ${node.name} | $fileInfo |"
        }
        
        return """# ${workflow.name}

${workflow.description}

## 节点列表

| ID | 类型 | 名称 | 文件 |
|----|------|------|------|
$nodeTable

## 变量

${if (workflow.variables.isEmpty()) "_无_" else workflow.variables.entries.joinToString("\n") { "- **${it.key}** (`${it.value.type}`)" }}

## 文件结构

```
${workflow.name.replace(Regex("[^a-zA-Z0-9_\u4e00-\u9fa5]"), "_")}/
├── workflow.json
├── nodes/
${workflow.nodes.filter { it.type == NodeType.CODE || it.type == NodeType.AGENT }.joinToString("\n") { 
    when (it.type) {
        NodeType.CODE -> "│   ├── ${it.id}.py"
        NodeType.AGENT -> "│   ├── ${it.id}_prompt.md\n│   ├── ${it.id}_config.json"
        else -> ""
    }
}}
└── README.md
```

## 使用方式

1. 在代码中加载 `workflow.json` 解析 DAG 结构
2. 根据节点类型执行对应逻辑：
   - `code` 节点：执行 `nodes/{node_id}.py`
   - `agent` 节点：读取 `nodes/{node_id}_prompt.md` 和 `_config.json`，调用 LLM
   - `condition` 节点：评估条件表达式决定分支
3. 按照 `edges` 定义的顺序执行
""".trimIndent()
    }
    
    private data class AgentConfig(
        val model: String,
        val temperature: Double,
        val inputs: Map<String, String>,
        val outputs: Map<String, String>
    )
}
