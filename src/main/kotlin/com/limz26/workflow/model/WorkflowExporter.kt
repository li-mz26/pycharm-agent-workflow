package com.limz26.workflow.model

import java.io.File

/**
 * 工作流导出器 - 生成 JSON + 代码文件 + 提示词文件
 */
class WorkflowExporter(private val basePath: String) {

    /**
     * 导出完整工作流到目录
     */
    fun export(workflow: Workflow): String {
        val workflowDir = File(basePath, workflow.name.replace(" ", "_"))
        workflowDir.mkdirs()
        
        val nodesDir = File(workflowDir, "nodes")
        nodesDir.mkdirs()
        
        // 1. 导出 workflow.json
        File(workflowDir, "workflow.json").writeText(workflow.toJson())
        
        // 2. 导出各个节点的代码/提示词文件
        workflow.nodes.forEach { node ->
            exportNode(node, nodesDir)
        }
        
        // 3. 导出 README
        File(workflowDir, "README.md").writeText(generateReadme(workflow))
        
        return workflowDir.absolutePath
    }
    
    private fun exportNode(node: WorkflowNode, nodesDir: File) {
        when (node.type) {
            NodeType.CODE -> {
                // 导出 Python 文件
                val code = node.config.code ?: "# TODO: Implement ${node.name}\n\ndef main(inputs):\n    return {}"
                File(nodesDir, "${node.id}.py").writeText(code)
            }
            NodeType.AGENT -> {
                // 导出提示词文件
                val prompt = node.config.prompt ?: "# TODO: Define prompt for ${node.name}"
                File(nodesDir, "${node.id}_prompt.md").writeText(prompt)
                
                // 导出配置
                val config = """
                    {
                      "model": "${node.config.model ?: "gpt-4"}",
                      "temperature": 0.7,
                      "inputs": ${node.config.inputs.toJson()},
                      "outputs": ${node.config.outputs.toJson()}
                    }
                """.trimIndent()
                File(nodesDir, "${node.id}_config.json").writeText(config)
            }
            else -> {
                // 其他节点类型，导出配置即可
                if (node.config.code != null || node.config.prompt != null) {
                    File(nodesDir, "${node.id}_config.json").writeText(node.config.toJson())
                }
            }
        }
    }
    
    private fun generateReadme(workflow: Workflow): String {
        return """
            # ${workflow.name}
            
            ${workflow.description}
            
            ## 节点列表
            
            | ID | 类型 | 名称 |
            |----|------|------|
            ${workflow.nodes.joinToString("\n") { "| ${it.id} | ${it.type.value} | ${it.name} |" }}
            
            ## 变量
            
            ${if (workflow.variables.isEmpty()) "无" else workflow.variables.entries.joinToString("\n") { "- **${it.key}**: ${it.value.type}" }}
            
            ## 文件结构
            
            ```
            ${workflow.name.replace(" ", "_")}/
            ├── workflow.json
            ├── nodes/
            ${workflow.nodes.filter { it.type == NodeType.CODE || it.type == NodeType.AGENT }.joinToString("\n") { "│   ├── ${it.id}${if (it.type == NodeType.CODE) ".py" else "_prompt.md"}" }}
            └── README.md
            ```
        """.trimIndent()
    }
    
    private fun Map<String, String>.toJson(): String {
        if (isEmpty()) return "{}"
        return "{" + entries.joinToString(", ") { "\"${it.key}\": \"${it.value}\"" } + "}"
    }
}
