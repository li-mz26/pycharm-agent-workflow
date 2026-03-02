package com.limz26.workflow.mcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.limz26.workflow.agent.WorkflowAgent
import com.limz26.workflow.model.*
import com.limz26.workflow.settings.AppSettings
import java.io.File
import java.util.UUID

/**
 * 内置 MCP 风格服务（供外部调用/二次集成）
 * 提供：工作流列表、工作流 JSON 读取、节点代码读取、编辑、运行（模拟日志）
 */
@Service
class WorkflowMcpService {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    data class WorkflowSummary(
        val name: String,
        val path: String,
        val nodeCount: Int
    )

    data class WorkflowEditRequest(
        val addNodes: List<NodeDefinition> = emptyList(),
        val updateNodes: List<NodeDefinition> = emptyList(),
        val removeNodeIds: List<String> = emptyList(),
        val addEdges: List<EdgeDefinition> = emptyList(),
        val removeEdgeIds: List<String> = emptyList(),
        val nodeCodeUpdates: Map<String, String> = emptyMap(),
        val nodePromptUpdates: Map<String, String> = emptyMap()
    )

    data class WorkflowRunResult(
        val success: Boolean,
        val logs: List<String>,
        val validationErrors: List<String> = emptyList()
    )

    data class McpServerConfig(
        val enabled: Boolean,
        val port: Int
    )

    fun getServerConfig(): McpServerConfig {
        val settings = service<AppSettings>()
        return McpServerConfig(settings.mcpServerEnabled, settings.mcpServerPort)
    }

    fun listWorkflows(projectBasePath: String): List<WorkflowSummary> {
        val loader = WorkflowLoader()
        return loader.scanWorkflows(File(projectBasePath)).map {
            WorkflowSummary(it.name, it.baseDir.absolutePath, it.definition.nodes.size)
        }
    }

    fun readWorkflowJson(workflowDirPath: String): String {
        val workflowJson = File(workflowDirPath, "workflow.json")
        require(workflowJson.exists()) { "workflow.json 不存在: $workflowDirPath" }
        return workflowJson.readText()
    }

    fun readNodeCodeFile(workflowDirPath: String, nodeId: String): String {
        val py = File(workflowDirPath, "nodes/$nodeId.py")
        require(py.exists()) { "节点代码文件不存在: ${py.absolutePath}" }
        return py.readText()
    }

    fun editWorkflow(workflowDirPath: String, request: WorkflowEditRequest): String {
        val workflowJson = File(workflowDirPath, "workflow.json")
        require(workflowJson.exists()) { "workflow.json 不存在: $workflowDirPath" }

        val def = gson.fromJson(workflowJson.readText(), WorkflowDefinition::class.java)

        val remainingNodes = def.nodes.filterNot { request.removeNodeIds.contains(it.id) }.toMutableList()
        val updatedById = request.updateNodes.associateBy { it.id }
        for (i in remainingNodes.indices) {
            val replacement = updatedById[remainingNodes[i].id]
            if (replacement != null) remainingNodes[i] = replacement
        }
        remainingNodes.addAll(request.addNodes)

        val remainingEdges = def.edges.filterNot { edge ->
            request.removeEdgeIds.contains(edge.id) ||
                request.removeNodeIds.contains(edge.source) ||
                request.removeNodeIds.contains(edge.target)
        }.toMutableList()
        remainingEdges.addAll(request.addEdges.map {
            if (it.id.isBlank()) it.copy(id = UUID.randomUUID().toString()) else it
        })

        val newDef = def.copy(nodes = remainingNodes, edges = remainingEdges)
        workflowJson.writeText(gson.toJson(newDef))

        val nodesDir = File(workflowDirPath, "nodes").apply { mkdirs() }
        request.nodeCodeUpdates.forEach { (nodeId, code) ->
            File(nodesDir, "$nodeId.py").writeText(code)
        }
        request.nodePromptUpdates.forEach { (nodeId, prompt) ->
            File(nodesDir, "${nodeId}_prompt.md").writeText(prompt)
        }

        // 删除节点关联文件
        request.removeNodeIds.forEach { nodeId ->
            File(nodesDir, "$nodeId.py").takeIf { it.exists() }?.delete()
            File(nodesDir, "${nodeId}_prompt.md").takeIf { it.exists() }?.delete()
            File(nodesDir, "${nodeId}_config.json").takeIf { it.exists() }?.delete()
        }

        return gson.toJson(newDef)
    }

    fun runWorkflow(workflowDirPath: String): WorkflowRunResult {
        val loader = WorkflowLoader()
        val loaded = loader.load(File(workflowDirPath)) ?: return WorkflowRunResult(
            success = false,
            logs = listOf("加载失败：无法读取工作流目录"),
            validationErrors = listOf("workflow 目录格式无效")
        )

        val workflow = toWorkflow(loaded.definition)
        val validation = WorkflowAgent().validateWorkflow(workflow)
        val logs = mutableListOf<String>()
        logs += "开始运行工作流: ${workflow.name}"
        logs += "节点数=${workflow.nodes.size}, 边数=${workflow.edges.size}"

        if (!validation.isValid) {
            logs += "校验失败: ${validation.errors.joinToString("; ")}"
            return WorkflowRunResult(false, logs, validation.errors)
        }

        workflow.nodes.forEachIndexed { idx, node ->
            logs += "[${idx + 1}/${workflow.nodes.size}] 执行节点: ${node.name} (${node.type.value})"
            if (node.type == NodeType.CODE) logs += "  - 模拟执行 code 节点"
            if (node.type == NodeType.AGENT) logs += "  - 模拟执行 agent 节点"
        }
        logs += "工作流运行完成（模拟）"
        return WorkflowRunResult(true, logs)
    }

    private fun toWorkflow(def: WorkflowDefinition): Workflow {
        return Workflow(
            id = def.id,
            name = def.name,
            description = def.description,
            nodes = def.nodes.map {
                WorkflowNode(
                    id = it.id,
                    type = NodeType.valueOf(it.type.uppercase()),
                    name = it.name,
                    position = Position(it.position.x, it.position.y),
                    config = NodeConfig(
                        code = it.config.code,
                        prompt = it.config.prompt,
                        model = it.config.model,
                        condition = it.config.condition,
                        method = it.config.method,
                        url = it.config.url,
                        headers = it.config.headers,
                        value = it.config.value,
                        inputs = it.config.inputs,
                        outputs = it.config.outputs
                    )
                )
            },
            edges = def.edges.map { WorkflowEdge(it.id, it.source, it.target, it.condition) },
            variables = def.variables.mapValues { Variable(it.key, it.value.type, it.value.default) }
        )
    }
}
