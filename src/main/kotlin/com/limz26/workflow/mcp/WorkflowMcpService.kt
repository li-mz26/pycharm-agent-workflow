package com.limz26.workflow.mcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.limz26.workflow.agent.WorkflowAgent
import com.limz26.workflow.model.*
import com.limz26.workflow.settings.AppSettings
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * 内置 MCP 服务（官方 kotlin-sdk streamable HTTP）
 */
@Service
class WorkflowMcpService {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var engine: EmbeddedServer<*, *>? = null

    @Volatile
    private var runningPort: Int? = null

    private val mcpServer: Server by lazy { createMcpServer() }

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
        val port: Int,
        val protocol: String = "streamable_http"
    )

    fun getServerConfig(): McpServerConfig {
        val settings = service<AppSettings>()
        return McpServerConfig(settings.mcpServerEnabled, settings.mcpServerPort)
    }

    fun isRunning(): Boolean = engine != null

    fun getRunningPort(): Int? = runningPort

    @Synchronized
    fun startServer(port: Int) {
        require(port in 1..65535) { "端口号无效: $port" }
        if (engine != null && runningPort == port) return
        stopServer()

        val appEngine = embeddedServer(CIO, host = "0.0.0.0", port = port) {
            install(SSE)
            routing {
                mcp("/mcp") {
                    mcpServer
                }
            }
        }
        appEngine.start(wait = false)
        engine = appEngine
        runningPort = port
    }

    @Synchronized
    fun stopServer() {
        engine?.stop(500, 1000)
        engine = null
        runningPort = null
    }

    private fun createMcpServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "workflow-mcp", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        )

        server.addTool(
            name = "workflows_list",
            description = "查询项目下工作流列表",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("projectBasePath", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                required = listOf("projectBasePath")
            )
        ) { request ->
            val basePath = request.requireStringArg("projectBasePath")
            asToolResult(listWorkflows(basePath))
        }

        server.addTool(
            name = "workflow_read_json",
            description = "读取指定工作流 json",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                required = listOf("workflowDirPath")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            asToolResult(mapOf("workflowJson" to readWorkflowJson(dir)))
        }

        server.addTool(
            name = "workflow_read_node_code",
            description = "读取指定工作流节点代码",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("nodeId", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                required = listOf("workflowDirPath", "nodeId")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            val nodeId = request.requireStringArg("nodeId")
            asToolResult(mapOf("code" to readNodeCodeFile(dir, nodeId)))
        }

        server.addTool(
            name = "workflow_edit",
            description = "编辑工作流（节点/边/代码/prompt）",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("request", buildJsonObject { put("type", JsonPrimitive("object")) })
                },
                required = listOf("workflowDirPath", "request")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            val requestObj = request.requireObjectArg("request")
            val editRequest = gson.fromJson(requestObj.toString(), WorkflowEditRequest::class.java)
            asToolResult(mapOf("workflowJson" to editWorkflow(dir, editRequest)))
        }

        server.addTool(
            name = "workflow_run",
            description = "运行工作流并返回日志",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                required = listOf("workflowDirPath")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            asToolResult(runWorkflow(dir))
        }

        return server
    }

    private fun asToolResult(payload: Any): CallToolResult {
        return CallToolResult(
            content = listOf(TextContent(gson.toJson(payload))),
            isError = false
        )
    }

    private fun CallToolRequest.requireStringArg(name: String): String {
        val args = arguments ?: throw IllegalArgumentException("Missing arguments")
        val value = args[name] ?: throw IllegalArgumentException("Missing argument: $name")
        return value.jsonPrimitive.content
    }

    private fun CallToolRequest.requireObjectArg(name: String): JsonObject {
        val args = arguments ?: throw IllegalArgumentException("Missing arguments")
        val value = args[name] ?: throw IllegalArgumentException("Missing argument: $name")
        return value as? JsonObject ?: throw IllegalArgumentException("$name must be object")
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
