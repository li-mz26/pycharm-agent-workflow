package com.limz26.workflow.agent

import com.intellij.openapi.components.service
import com.limz26.workflow.llm.LLMClient
import com.limz26.workflow.model.*
import com.limz26.workflow.settings.AppSettings
import kotlinx.serialization.json.*

/**
 * Agent 对话处理器 - 将自然语言转换为工作流
 */
class WorkflowAgent {

    private val settings by lazy { service<AppSettings>() }
    private val llmClient by lazy { LLMClient() }

    /**
     * 解析用户输入，生成工作流
     */
    fun generateWorkflow(userInput: String, context: WorkflowContext? = null): Workflow {
        // 检查 API 配置
        if (settings.apiKey.isBlank()) {
            return createErrorWorkflow("请先在 Settings → Other Settings → Agent Workflow (LLM 配置) 中配置 API Key")
        }

        return try {
            // 调用 LLM 生成工作流
            val dsl = llmClient.generateWorkflowDSL(userInput)
            parseWorkflowFromJson(dsl, userInput)
        } catch (e: Exception) {
            createErrorWorkflow("生成工作流失败: ${e.message}")
        }
    }

    /**
     * 继续对话，修改工作流
     */
    fun modifyWorkflow(workflow: Workflow, userInput: String): Workflow {
        // TODO: 调用 LLM API 理解修改意图
        return workflow
    }

    /**
     * 验证工作流是否有效（无环、有开始结束等）
     */
    fun validateWorkflow(workflow: Workflow): ValidationResult {
        val errors = mutableListOf<String>()

        // 检查是否有开始节点
        val hasStart = workflow.nodes.any { it.type == NodeType.START }
        if (!hasStart) errors.add("缺少开始节点")

        // 检查是否有结束节点
        val hasEnd = workflow.nodes.any { it.type == NodeType.END }
        if (!hasEnd) errors.add("缺少结束节点")

        // 检查是否有环
        if (hasCycle(workflow)) errors.add("工作流存在循环")

        // 检查孤立节点
        val connectedNodes = workflow.edges.flatMap { listOf(it.source, it.target) }.toSet()
        val isolatedNodes = workflow.nodes.filter { it.id !in connectedNodes && it.type !in listOf(NodeType.START, NodeType.END) }
        if (isolatedNodes.isNotEmpty()) errors.add("存在孤立节点: ${isolatedNodes.map { it.name }}")

        return ValidationResult(errors.isEmpty(), errors)
    }

    private fun hasCycle(workflow: Workflow): Boolean {
        val adj = workflow.edges.groupBy { it.source }.mapValues { it.value.map { e -> e.target } }
        val visited = mutableSetOf<String>()
        val recStack = mutableSetOf<String>()

        fun dfs(node: String): Boolean {
            visited.add(node)
            recStack.add(node)

            for (neighbor in adj[node] ?: emptyList()) {
                if (!visited.contains(neighbor)) {
                    if (dfs(neighbor)) return true
                } else if (recStack.contains(neighbor)) {
                    return true
                }
            }

            recStack.remove(node)
            return false
        }

        for (node in workflow.nodes.map { it.id }) {
            if (!visited.contains(node)) {
                if (dfs(node)) return true
            }
        }
        return false
    }

    private fun createErrorWorkflow(errorMessage: String): Workflow {
        return Workflow(
            name = "错误",
            description = errorMessage,
            nodes = listOf(
                WorkflowNode(
                    type = NodeType.START,
                    name = "开始",
                    position = Position(100, 100)
                ),
                WorkflowNode(
                    type = NodeType.CODE,
                    name = "错误信息",
                    position = Position(300, 100),
                    config = NodeConfig(
                        code = "# $errorMessage"
                    )
                ),
                WorkflowNode(
                    type = NodeType.END,
                    name = "结束",
                    position = Position(500, 100)
                )
            ),
            edges = listOf(
                WorkflowEdge(source = "node_1", target = "node_2"),
                WorkflowEdge(source = "node_2", target = "node_3")
            ),
            variables = emptyMap()
        )
    }

    private fun parseWorkflowFromJson(json: String, userInput: String): Workflow {
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject.let { obj ->
                val name = obj["name"]?.jsonPrimitive?.content ?: "未命名工作流"
                val description = obj["description"]?.jsonPrimitive?.content ?: userInput

                val nodes = obj["nodes"]?.jsonArray?.mapIndexed { index, nodeElement ->
                    val nodeObj = nodeElement.jsonObject
                    WorkflowNode(
                        id = nodeObj["id"]?.jsonPrimitive?.content ?: "node_${index + 1}",
                        type = NodeType.valueOf(nodeObj["type"]?.jsonPrimitive?.content?.uppercase() ?: "CODE"),
                        name = nodeObj["name"]?.jsonPrimitive?.content ?: "节点${index + 1}",
                        position = Position(
                            x = nodeObj["x"]?.jsonPrimitive?.intOrNull ?: (100 + index * 200),
                            y = nodeObj["y"]?.jsonPrimitive?.intOrNull ?: 100
                        ),
                        config = NodeConfig(
                            code = nodeObj["code"]?.jsonPrimitive?.content,
                            prompt = nodeObj["prompt"]?.jsonPrimitive?.content,
                            model = nodeObj["model"]?.jsonPrimitive?.content
                        )
                    )
                } ?: emptyList()

                val edges = obj["edges"]?.jsonArray?.map { edgeElement ->
                    val edgeObj = edgeElement.jsonObject
                    WorkflowEdge(
                        source = edgeObj["source"]?.jsonPrimitive?.content ?: "",
                        target = edgeObj["target"]?.jsonPrimitive?.content ?: ""
                    )
                } ?: emptyList()

                Workflow(
                    name = name,
                    description = description,
                    nodes = nodes,
                    edges = edges,
                    variables = emptyMap()
                )
            }
        } catch (e: Exception) {
            createExampleWorkflow(userInput)
        }
    }

    private fun createExampleWorkflow(description: String): Workflow {
        // 示例：创建一个简单的数据处理工作流
        val startNode = WorkflowNode(
            type = NodeType.START,
            name = "开始",
            position = Position(100, 100)
        )

        val codeNode = WorkflowNode(
            type = NodeType.CODE,
            name = "数据预处理",
            position = Position(300, 100),
            config = NodeConfig(
                code = """# 数据预处理
def main(inputs):
    data = inputs.get('raw_data', [])
    processed = [x.strip() for x in data if x]
    return {'processed_data': processed}
""",
                inputs = mapOf("raw_data" to "start.data"),
                outputs = mapOf("processed_data" to "list")
            )
        )

        val agentNode = WorkflowNode(
            type = NodeType.AGENT,
            name = "数据分析",
            position = Position(500, 100),
            config = NodeConfig(
                prompt = """你是一位数据分析师。请分析以下数据并给出见解：

数据：{{processed_data}}

请提供：
1. 数据概览
2. 关键发现
3. 建议
""",
                model = "gpt-4",
                inputs = mapOf("processed_data" to "codeNode.processed_data"),
                outputs = mapOf("analysis" to "string", "insights" to "list")
            )
        )

        val endNode = WorkflowNode(
            type = NodeType.END,
            name = "结束",
            position = Position(700, 100)
        )

        return Workflow(
            name = "示例工作流",
            description = description,
            nodes = listOf(startNode, codeNode, agentNode, endNode),
            edges = listOf(
                WorkflowEdge(source = startNode.id, target = codeNode.id),
                WorkflowEdge(source = codeNode.id, target = agentNode.id),
                WorkflowEdge(source = agentNode.id, target = endNode.id)
            ),
            variables = mapOf(
                "raw_data" to Variable("raw_data", "list"),
                "processed_data" to Variable("processed_data", "list"),
                "analysis" to Variable("analysis", "string")
            )
        )
    }
}

data class WorkflowContext(
    val currentWorkflow: Workflow? = null,
    val selectedNodes: List<String> = emptyList(),
    val cursorPosition: Position? = null
)

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>
)
