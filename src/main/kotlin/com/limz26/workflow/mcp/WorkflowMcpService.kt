package com.limz26.workflow.mcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.limz26.workflow.agent.WorkflowAgent
import com.limz26.workflow.model.*
import com.limz26.workflow.settings.AppSettings
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 内置 MCP 服务（官方 kotlin-sdk streamable HTTP）
 */
@Service
class WorkflowMcpService {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val mcpServer: Server by lazy { createMcpServer() }
    private val runtime: WorkflowMcpRuntime by lazy { WorkflowMcpRuntime { mcpServer } }

    data class WorkflowSummary(
        val name: String,
        val path: String,
        val nodeCount: Int
    )

    data class WorkflowValidationResult(
        val validJson: Boolean,
        val isDag: Boolean,
        val errors: List<String> = emptyList(),
        val nodeCount: Int = 0,
        val edgeCount: Int = 0
    )

    data class WorkflowLayoutResult(
        val workflowJson: String,
        val updatedNodeCount: Int,
        val levels: Int
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

    fun isRunning(): Boolean = runtime.isRunning()

    fun getRunningPort(): Int? = runtime.getRunningPort()

    fun startServer(port: Int) {
        runtime.start(port)
    }

    fun stopServer() {
        runtime.stop()
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
            description = "列出项目下可用工作流。projectBasePath 可选；为空时自动使用 IDE 当前打开项目路径。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("projectBasePath", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("项目根目录绝对路径（可选；为空时默认 IDE 当前打开项目路径）"))
                    })
                },
                required = emptyList()
            )
        ) { request ->
            val basePath = request.optionalStringArg("projectBasePath")
            asToolResult(listWorkflows(basePath))
        }

        server.addTool(
            name = "workflow_read_json",
            description = "读取指定工作流目录下的 workflow.json 原文。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("工作流目录绝对路径"))
                    })
                },
                required = listOf("workflowDirPath")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            asToolResult(mapOf("workflowJson" to readWorkflowJson(dir)))
        }

        server.addTool(
            name = "workflow_read_node_code",
            description = "读取 code 节点对应的 Python 文件内容。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("工作流目录绝对路径"))
                    })
                    put("nodeId", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("目标节点 ID"))
                    })
                },
                required = listOf("workflowDirPath", "nodeId")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            val nodeId = request.requireStringArg("nodeId")
            asToolResult(mapOf("code" to readNodeCodeFile(dir, nodeId)))
        }

        server.addTool(
            name = "workflow_add_node",
            description = "向工作流新增一个节点。仅传递关键字段，底层 JSON 合并由服务端处理。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", schemaString("工作流目录绝对路径"))
                    put("id", schemaString("节点 ID（为空时服务端自动生成）"))
                    put("type", schemaString("节点类型：start/end/condition/code/agent/http/variable"))
                    put("name", schemaString("节点显示名称"))
                    put("x", schemaNumber("节点 x 坐标"))
                    put("y", schemaNumber("节点 y 坐标"))
                    put("config", schemaObject("节点配置对象（可选）"))
                },
                required = listOf("workflowDirPath", "type", "name")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            asToolResult(mapOf("workflowJson" to addNode(dir, request.toNodeDefinition())))
        }

        server.addTool(
            name = "workflow_delete_node",
            description = "删除指定节点，并自动删除关联边及节点附属文件。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", schemaString("工作流目录绝对路径"))
                    put("nodeId", schemaString("待删除节点 ID"))
                },
                required = listOf("workflowDirPath", "nodeId")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            val nodeId = request.requireStringArg("nodeId")
            asToolResult(mapOf("workflowJson" to deleteNode(dir, nodeId)))
        }

        server.addTool(
            name = "workflow_edit_node",
            description = "编辑指定节点。仅覆盖传入字段，未传字段保持不变。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", schemaString("工作流目录绝对路径"))
                    put("nodeId", schemaString("待编辑节点 ID"))
                    put("name", schemaString("新节点名（可选）"))
                    put("x", schemaNumber("新 x 坐标（可选）"))
                    put("y", schemaNumber("新 y 坐标（可选）"))
                    put("config", schemaObject("新配置对象（可选，整体替换）"))
                },
                required = listOf("workflowDirPath", "nodeId")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            asToolResult(mapOf("workflowJson" to editNode(dir, request)))
        }

        server.addTool(
            name = "workflow_add_edge",
            description = "新增一条边连接两个节点。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", schemaString("工作流目录绝对路径"))
                    put("id", schemaString("边 ID（为空时服务端自动生成）"))
                    put("source", schemaString("起点节点 ID"))
                    put("target", schemaString("终点节点 ID"))
                    put("condition", schemaString("边条件（可选）"))
                },
                required = listOf("workflowDirPath", "source", "target")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            asToolResult(mapOf("workflowJson" to addEdge(dir, request.toEdgeDefinition())))
        }

        server.addTool(
            name = "workflow_delete_edge",
            description = "删除指定边。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", schemaString("工作流目录绝对路径"))
                    put("edgeId", schemaString("待删除边 ID"))
                },
                required = listOf("workflowDirPath", "edgeId")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            val edgeId = request.requireStringArg("edgeId")
            asToolResult(mapOf("workflowJson" to deleteEdge(dir, edgeId)))
        }

        server.addTool(
            name = "workflow_edit_edge",
            description = "编辑指定边，支持修改 source/target/condition。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", schemaString("工作流目录绝对路径"))
                    put("edgeId", schemaString("待编辑边 ID"))
                    put("source", schemaString("新起点节点 ID（可选）"))
                    put("target", schemaString("新终点节点 ID（可选）"))
                    put("condition", schemaString("新边条件（可选）"))
                },
                required = listOf("workflowDirPath", "edgeId")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            asToolResult(mapOf("workflowJson" to editEdge(dir, request)))
        }

        server.addTool(
            name = "workflow_validate",
            description = "校验工作流 JSON：检查可解析性、结构合法性、并验证是否为有向无环图。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", schemaString("工作流目录绝对路径（与 workflowJson 二选一）"))
                    put("workflowJson", schemaString("待校验 workflow JSON 原文（与 workflowDirPath 二选一）"))
                },
                required = emptyList()
            )
        ) { request ->
            val raw = request.optionalStringArg("workflowJson")
            val dir = request.optionalStringArg("workflowDirPath")
            asToolResult(validateWorkflowJson(raw, dir))
        }

        server.addTool(
            name = "workflow_layout",
            description = "自动排布节点位置，减少重叠并提升可读性，结果写回 workflow.json。",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("workflowDirPath", schemaString("工作流目录绝对路径"))
                    put("horizontalSpacing", schemaNumber("层之间横向间距，默认 280"))
                    put("verticalSpacing", schemaNumber("同层节点纵向间距，默认 140"))
                },
                required = listOf("workflowDirPath")
            )
        ) { request ->
            val dir = request.requireStringArg("workflowDirPath")
            val horizontal = request.optionalIntArg("horizontalSpacing") ?: 280
            val vertical = request.optionalIntArg("verticalSpacing") ?: 140
            asToolResult(layoutWorkflow(dir, horizontal, vertical))
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

    private fun CallToolRequest.optionalStringArg(name: String): String? {
        val args = arguments ?: return null
        return args[name]?.jsonPrimitive?.content
    }

    private fun CallToolRequest.optionalIntArg(name: String): Int? {
        val args = arguments ?: return null
        val primitive = args[name]?.jsonPrimitive ?: return null
        return primitive.doubleOrNull?.toInt()
    }

    private fun schemaString(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
    }

    private fun schemaNumber(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("number"))
        put("description", JsonPrimitive(description))
    }

    private fun schemaObject(description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("description", JsonPrimitive(description))
    }

    fun listWorkflows(projectBasePath: String?): List<WorkflowSummary> {
        val loader = WorkflowLoader()
        val basePath = resolveProjectBasePath(projectBasePath)
        val baseDir = File(basePath)

        val loadedWorkflows = when {
            File(baseDir, "workflows").isDirectory -> loader.scanWorkflows(baseDir)
            baseDir.name == "workflows" && baseDir.isDirectory -> {
                baseDir.listFiles { f -> f.isDirectory }?.mapNotNull { loader.load(it) } ?: emptyList()
            }
            File(baseDir, "workflow.json").isFile -> listOfNotNull(loader.load(baseDir))
            else -> emptyList()
        }

        return loadedWorkflows.map {
            WorkflowSummary(it.name, it.baseDir.absolutePath, it.definition.nodes.size)
        }
    }


    private fun resolveProjectBasePath(projectBasePath: String?): String {
        val provided = projectBasePath?.trim()
        if (!provided.isNullOrEmpty()) return provided

        val openProjectPath = ProjectManager.getInstance().openProjects
            .firstOrNull { !it.basePath.isNullOrBlank() }
            ?.basePath
        if (!openProjectPath.isNullOrBlank()) return openProjectPath

        val configuredPath = service<AppSettings>().workflowPath.trim()
        if (configuredPath.isNotEmpty()) return configuredPath

        throw IllegalArgumentException("projectBasePath 为空且无法获取 IDE 当前项目路径，请显式传入 projectBasePath")
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

    fun addNode(workflowDirPath: String, node: NodeDefinition): String {
        val def = loadWorkflowDefinition(workflowDirPath)
        require(def.nodes.none { it.id == node.id }) { "节点已存在: ${node.id}" }
        val newNode = if (node.id.isBlank()) node.copy(id = UUID.randomUUID().toString()) else node
        return saveWorkflowDefinition(workflowDirPath, def.copy(nodes = def.nodes + newNode))
    }

    fun deleteNode(workflowDirPath: String, nodeId: String): String {
        val def = loadWorkflowDefinition(workflowDirPath)
        require(def.nodes.any { it.id == nodeId }) { "节点不存在: $nodeId" }
        val newDef = def.copy(
            nodes = def.nodes.filterNot { it.id == nodeId },
            edges = def.edges.filterNot { it.source == nodeId || it.target == nodeId }
        )
        val nodesDir = File(workflowDirPath, "nodes")
        File(nodesDir, "$nodeId.py").takeIf { it.exists() }?.delete()
        File(nodesDir, "${nodeId}_prompt.md").takeIf { it.exists() }?.delete()
        File(nodesDir, "${nodeId}_config.json").takeIf { it.exists() }?.delete()
        return saveWorkflowDefinition(workflowDirPath, newDef)
    }

    fun editNode(workflowDirPath: String, request: CallToolRequest): String {
        val def = loadWorkflowDefinition(workflowDirPath)
        val nodeId = request.requireStringArg("nodeId")
        require(def.nodes.any { it.id == nodeId }) { "节点不存在: $nodeId" }

        val config = request.arguments?.get("config")?.let { gson.fromJson(it.toString(), NodeConfigDefinition::class.java) }
        val x = request.optionalIntArg("x")
        val y = request.optionalIntArg("y")
        val name = request.optionalStringArg("name")

        val updated = def.nodes.map { node ->
            if (node.id != nodeId) {
                node
            } else {
                node.copy(
                    name = name ?: node.name,
                    position = if (x != null || y != null) PositionDefinition(x ?: node.position.x, y ?: node.position.y) else node.position,
                    config = config ?: node.config
                )
            }
        }
        return saveWorkflowDefinition(workflowDirPath, def.copy(nodes = updated))
    }

    fun addEdge(workflowDirPath: String, edge: EdgeDefinition): String {
        val def = loadWorkflowDefinition(workflowDirPath)
        val nodeIds = def.nodes.map { it.id }.toSet()
        require(edge.source in nodeIds && edge.target in nodeIds) { "边引用了不存在的节点: ${edge.source} -> ${edge.target}" }
        val newEdge = if (edge.id.isBlank()) edge.copy(id = UUID.randomUUID().toString()) else edge
        require(def.edges.none { it.id == newEdge.id }) { "边已存在: ${newEdge.id}" }
        return saveWorkflowDefinition(workflowDirPath, def.copy(edges = def.edges + newEdge))
    }

    fun deleteEdge(workflowDirPath: String, edgeId: String): String {
        val def = loadWorkflowDefinition(workflowDirPath)
        require(def.edges.any { it.id == edgeId }) { "边不存在: $edgeId" }
        return saveWorkflowDefinition(workflowDirPath, def.copy(edges = def.edges.filterNot { it.id == edgeId }))
    }

    fun editEdge(workflowDirPath: String, request: CallToolRequest): String {
        val def = loadWorkflowDefinition(workflowDirPath)
        val edgeId = request.requireStringArg("edgeId")
        val source = request.optionalStringArg("source")
        val target = request.optionalStringArg("target")
        val condition = request.optionalStringArg("condition")
        val nodeIds = def.nodes.map { it.id }.toSet()

        val updated = def.edges.map { edge ->
            if (edge.id != edgeId) {
                edge
            } else {
                val newSource = source ?: edge.source
                val newTarget = target ?: edge.target
                require(newSource in nodeIds && newTarget in nodeIds) { "边引用了不存在的节点: $newSource -> $newTarget" }
                edge.copy(source = newSource, target = newTarget, condition = condition ?: edge.condition)
            }
        }
        require(updated.any { it.id == edgeId }) { "边不存在: $edgeId" }
        return saveWorkflowDefinition(workflowDirPath, def.copy(edges = updated))
    }

    fun validateWorkflowJson(rawWorkflowJson: String?, workflowDirPath: String?): WorkflowValidationResult {
        val raw = rawWorkflowJson ?: workflowDirPath?.let { readWorkflowJson(it) }
            ?: return WorkflowValidationResult(false, false, listOf("workflowJson 与 workflowDirPath 不能同时为空"))

        val def = try {
            gson.fromJson(raw, WorkflowDefinition::class.java)
        } catch (e: Exception) {
            return WorkflowValidationResult(false, false, listOf("JSON 解析失败: ${e.message}"))
        }

        val errors = mutableListOf<String>()
        val nodeIds = def.nodes.map { it.id }
        if (nodeIds.size != nodeIds.toSet().size) {
            errors += "节点 ID 重复"
        }
        val edgeIds = def.edges.map { it.id }
        if (edgeIds.size != edgeIds.toSet().size) {
            errors += "边 ID 重复"
        }
        val nodeSet = nodeIds.toSet()
        def.edges.forEach { edge ->
            if (edge.source !in nodeSet || edge.target !in nodeSet) {
                errors += "边 ${edge.id} 引用了不存在的节点"
            }
        }

        val isDag = errors.isEmpty() && isDag(def)
        if (!isDag && errors.isEmpty()) {
            errors += "图中存在环"
        }
        return WorkflowValidationResult(
            validJson = true,
            isDag = isDag,
            errors = errors,
            nodeCount = def.nodes.size,
            edgeCount = def.edges.size
        )
    }

    fun layoutWorkflow(workflowDirPath: String, horizontalSpacing: Int, verticalSpacing: Int): WorkflowLayoutResult {
        val def = loadWorkflowDefinition(workflowDirPath)
        require(horizontalSpacing > 0 && verticalSpacing > 0) { "间距必须为正数" }
        val levels = topologicalLevels(def)
        val nodesById = def.nodes.associateBy { it.id }
        val updatedNodes = mutableListOf<NodeDefinition>()

        levels.forEachIndexed { level, ids ->
            ids.sorted().forEachIndexed { row, id ->
                val node = nodesById[id] ?: return@forEachIndexed
                updatedNodes += node.copy(position = PositionDefinition(level * horizontalSpacing, row * verticalSpacing))
            }
        }

        val untouchedNodes = def.nodes.filterNot { node -> updatedNodes.any { it.id == node.id } }
        val newDef = def.copy(nodes = updatedNodes + untouchedNodes)
        val workflowJson = saveWorkflowDefinition(workflowDirPath, newDef)
        return WorkflowLayoutResult(workflowJson = workflowJson, updatedNodeCount = updatedNodes.size, levels = levels.size)
    }

    private fun loadWorkflowDefinition(workflowDirPath: String): WorkflowDefinition {
        val workflowJson = File(workflowDirPath, "workflow.json")
        require(workflowJson.exists()) { "workflow.json 不存在: $workflowDirPath" }
        return gson.fromJson(workflowJson.readText(), WorkflowDefinition::class.java)
    }

    private fun saveWorkflowDefinition(workflowDirPath: String, def: WorkflowDefinition): String {
        val workflowJson = File(workflowDirPath, "workflow.json")
        require(workflowJson.exists()) { "workflow.json 不存在: $workflowDirPath" }
        val content = gson.toJson(def)
        workflowJson.writeText(content)
        return content
    }

    private fun CallToolRequest.toNodeDefinition(): NodeDefinition {
        val type = requireStringArg("type")
        val name = requireStringArg("name")
        val id = optionalStringArg("id") ?: UUID.randomUUID().toString()
        val x = optionalIntArg("x") ?: 0
        val y = optionalIntArg("y") ?: 0
        val config = arguments?.get("config")?.let { gson.fromJson(it.toString(), NodeConfigDefinition::class.java) } ?: NodeConfigDefinition()
        return NodeDefinition(id = id, type = type, name = name, position = PositionDefinition(x, y), config = config)
    }

    private fun CallToolRequest.toEdgeDefinition(): EdgeDefinition {
        val id = optionalStringArg("id") ?: UUID.randomUUID().toString()
        return EdgeDefinition(
            id = id,
            source = requireStringArg("source"),
            target = requireStringArg("target"),
            condition = optionalStringArg("condition")
        )
    }

    private fun isDag(def: WorkflowDefinition): Boolean {
        val inDegree = def.nodes.associate { it.id to 0 }.toMutableMap()
        val adjacency = def.nodes.associate { it.id to mutableListOf<String>() }.toMutableMap()
        def.edges.forEach { edge ->
            adjacency.getValue(edge.source).add(edge.target)
            inDegree[edge.target] = inDegree.getValue(edge.target) + 1
        }

        val queue: ArrayDeque<String> = ArrayDeque(inDegree.filterValues { it == 0 }.keys)
        var visited = 0
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            visited += 1
            adjacency.getValue(current).forEach { next ->
                val degree = inDegree.getValue(next) - 1
                inDegree[next] = degree
                if (degree == 0) queue.add(next)
            }
        }
        return visited == def.nodes.size
    }

    private fun topologicalLevels(def: WorkflowDefinition): List<List<String>> {
        val inDegree = def.nodes.associate { it.id to 0 }.toMutableMap()
        val adjacency = def.nodes.associate { it.id to mutableListOf<String>() }.toMutableMap()
        def.edges.forEach { edge ->
            adjacency.getValue(edge.source).add(edge.target)
            inDegree[edge.target] = inDegree.getValue(edge.target) + 1
        }

        val levels = mutableListOf<List<String>>()
        var current = inDegree.filterValues { it == 0 }.keys.sorted()
        val remaining = inDegree.toMutableMap()

        while (current.isNotEmpty()) {
            levels += current
            val nextLevel = mutableListOf<String>()
            current.forEach { nodeId ->
                adjacency.getValue(nodeId).forEach { target ->
                    remaining[target] = remaining.getValue(target) - 1
                    if (remaining.getValue(target) == 0) {
                        nextLevel += target
                    }
                }
            }
            current = nextLevel.distinct().sorted()
        }

        if (levels.flatten().size != def.nodes.size) {
            return listOf(def.nodes.map { it.id })
        }
        return levels
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
        val inputFile = Files.createTempFile("workflow-node-input-${node.id}-", ".json").toFile()

        try {
            codeFile.writeText(code)
            inputFile.writeText(inputJson)
            runnerFile.writeText(
                """
input_path = sys.argv[2]
with open(input_path, "r", encoding="utf-8") as f:
    inputs = json.load(f)

                """.trimIndent()
            )
            val process = ProcessBuilder("python3", runnerFile.absolutePath, codeFile.absolutePath, inputFile.absolutePath)
                .redirectErrorStream(false)
                .start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException("python 执行超时(>60s)")
            }
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                throw IllegalStateException("python 执行失败(exit=$exitCode): ${stderr.ifBlank { stdout }}")
            }
            val parsed = parseJsonMapFromOutput(stdout)
            return parsed.entries.associate { it.key.toString() to it.value }
        } finally {
            codeFile.delete()
            inputFile.delete()
            runnerFile.delete()
        }
        return placeholderRegex.replace(template) { match ->
            if (key == "input_json") {
                gson.toJson(mergedInputs)
            } else {
                resolvePath(mergedInputs, key)?.toString() ?: ""
            }
    private fun parseJsonMapFromOutput(stdout: String): Map<*, *> {
        val jsonLine = stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.lastOrNull() ?: "{}"
        val parsed = gson.fromJson(jsonLine, Map::class.java)
        return parsed as? Map<*, *> ?: emptyMap<String, Any?>()
    }

