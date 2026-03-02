package com.limz26.workflow.mcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.limz26.workflow.agent.WorkflowAgent
import com.limz26.workflow.model.*
import com.limz26.workflow.settings.AppSettings
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 内置 MCP 服务（streamable HTTP / JSON-RPC 2.0）
 */
@Service
class WorkflowMcpService {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var httpServer: HttpServer? = null

    @Volatile
    private var runningPort: Int? = null

    private val sessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    data class RpcError(val code: Int, val message: String)

    fun getServerConfig(): McpServerConfig {
        val settings = service<AppSettings>()
        return McpServerConfig(settings.mcpServerEnabled, settings.mcpServerPort)
    }

    fun isRunning(): Boolean = httpServer != null

    fun getRunningPort(): Int? = runningPort

    @Synchronized
    fun startServer(port: Int) {
        require(port in 1..65535) { "端口号无效: $port" }
        if (httpServer != null && runningPort == port) return
        stopServer()

        val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
        server.createContext("/mcp") { exchange ->
            handleMcpRequest(exchange)
        }
        server.executor = null
        server.start()

        httpServer = server
        runningPort = port
    }

    @Synchronized
    fun stopServer() {
        httpServer?.stop(0)
        httpServer = null
        runningPort = null
        sessions.clear()
    }

    private fun handleMcpRequest(exchange: HttpExchange) {
        try {
            val method = exchange.requestMethod.uppercase()
            val path = exchange.requestURI.path
            val query = parseQuery(exchange.requestURI.rawQuery)

            when {
                method == "OPTIONS" -> {
                    sendEmpty(exchange, 204)
                    return
                }

                method == "POST" && path == "/mcp" -> {
                    handleRpcCall(exchange)
                    return
                }

                method == "GET" && path == "/mcp" -> {
                    sendJson(exchange, 200, mapOf(
                        "name" to "workflow-mcp",
                        "protocol" to "streamable_http",
                        "jsonrpc" to "2.0",
                        "runningPort" to (runningPort ?: -1),
                        "sessions" to sessions.size,
                        "endpoints" to listOf(
                            "POST /mcp (JSON-RPC)",
                            "GET /mcp/workflows?projectBasePath=...",
                            "GET /mcp/workflow/json?workflowDirPath=...",
                            "GET /mcp/workflow/node-code?workflowDirPath=...&nodeId=...",
                            "POST /mcp/workflow/edit",
                            "POST /mcp/workflow/run"
                        )
                    ))
                    return
                }

                method == "GET" && path == "/mcp/workflows" -> {
                    val base = query["projectBasePath"] ?: ""
                    sendJson(exchange, 200, listWorkflows(base))
                    return
                }

                method == "GET" && path == "/mcp/workflow/json" -> {
                    val dir = query["workflowDirPath"] ?: error("缺少 workflowDirPath")
                    sendJson(exchange, 200, mapOf("workflowJson" to readWorkflowJson(dir)))
                    return
                }

                method == "GET" && path == "/mcp/workflow/node-code" -> {
                    val dir = query["workflowDirPath"] ?: error("缺少 workflowDirPath")
                    val nodeId = query["nodeId"] ?: error("缺少 nodeId")
                    sendJson(exchange, 200, mapOf("code" to readNodeCodeFile(dir, nodeId)))
                    return
                }

                method == "POST" && path == "/mcp/workflow/edit" -> {
                    val payload = gson.fromJson(readBody(exchange), Map::class.java)
                    val workflowDirPath = payload["workflowDirPath"]?.toString() ?: error("缺少 workflowDirPath")
                    val requestJson = gson.toJson(payload["request"])
                    val request = gson.fromJson(requestJson, WorkflowEditRequest::class.java)
                    sendJson(exchange, 200, mapOf("workflowJson" to editWorkflow(workflowDirPath, request)))
                    return
                }

                method == "POST" && path == "/mcp/workflow/run" -> {
                    val payload = gson.fromJson(readBody(exchange), Map::class.java)
                    val workflowDirPath = payload["workflowDirPath"]?.toString() ?: error("缺少 workflowDirPath")
                    sendJson(exchange, 200, runWorkflow(workflowDirPath))
                    return
                }

                else -> {
                    sendJson(exchange, 404, mapOf("error" to "Unsupported endpoint: $method $path"))
                    return
                }
            }
        } catch (e: Exception) {
            sendJson(exchange, 500, mapOf("error" to (e.message ?: "unknown error")))
        }
    }

    private fun handleRpcCall(exchange: HttpExchange) {
        val requestElement = try {
            JsonParser.parseString(readBody(exchange))
        } catch (e: Exception) {
            sendJson(exchange, 200, rpcErrorResponse(null, -32700, "Parse error: ${e.message}"))
            return
        }

        if (!requestElement.isJsonObject) {
            sendJson(exchange, 200, rpcErrorResponse(null, -32600, "Invalid Request"))
            return
        }

        val request = requestElement.asJsonObject
        val idElement = request.get("id")
        val method = request.get("method")?.asString

        if (method.isNullOrBlank()) {
            sendJson(exchange, 200, rpcErrorResponse(idElement, -32600, "Missing method"))
            return
        }

        val sessionHeader = exchange.requestHeaders.getFirst("Mcp-Session-Id")
        val params = request.getAsJsonObject("params") ?: JsonObject()

        if (method == "initialize") {
            val sessionId = UUID.randomUUID().toString()
            sessions.add(sessionId)
            val protocolVersion = params.get("protocolVersion")?.asString ?: "2024-11-05"
            val result = mapOf(
                "protocolVersion" to protocolVersion,
                "capabilities" to mapOf(
                    "tools" to mapOf("listChanged" to false),
                    "logging" to mapOf<String, Any>()
                ),
                "serverInfo" to mapOf("name" to "workflow-mcp", "version" to "1.0.0")
            )
            sendRpcResult(exchange, idElement, result, sessionId)
            return
        }

        if (sessionHeader.isNullOrBlank() || !sessions.contains(sessionHeader)) {
            sendJson(exchange, 200, rpcErrorResponse(idElement, -32001, "Missing or invalid Mcp-Session-Id"))
            return
        }

        try {
            when (method) {
                "initialized" -> {
                    // notification, no response body needed
                    sendEmpty(exchange, 202)
                }

                "ping" -> sendRpcResult(exchange, idElement, mapOf("ok" to true), sessionHeader)

                "tools/list" -> {
                    val result = mapOf("tools" to buildToolDescriptors())
                    sendRpcResult(exchange, idElement, result, sessionHeader)
                }

                "tools/call" -> {
                    val toolName = params.get("name")?.asString ?: throw IllegalArgumentException("Missing tool name")
                    val arguments = params.getAsJsonObject("arguments") ?: JsonObject()
                    val toolResult = callTool(toolName, arguments)
                    sendRpcResult(exchange, idElement, toolResult, sessionHeader)
                }

                else -> sendJson(exchange, 200, rpcErrorResponse(idElement, -32601, "Method not found: $method"))
            }
        } catch (e: Exception) {
            sendJson(exchange, 200, rpcErrorResponse(idElement, -32603, e.message ?: "Internal error"))
        }
    }

    private fun sendRpcResult(exchange: HttpExchange, idElement: JsonElement?, result: Any, sessionId: String) {
        val response = linkedMapOf<String, Any>(
            "jsonrpc" to "2.0",
            "result" to result
        )
        if (idElement != null && !idElement.isJsonNull) {
            response["id"] = gson.fromJson(idElement, Any::class.java)
        }
        sendJson(exchange, 200, response, sessionId)
    }

    private fun rpcErrorResponse(idElement: JsonElement?, code: Int, message: String): Map<String, Any> {
        val response = linkedMapOf<String, Any>(
            "jsonrpc" to "2.0",
            "error" to RpcError(code, message)
        )
        if (idElement != null && !idElement.isJsonNull) {
            response["id"] = gson.fromJson(idElement, Any::class.java)
        }
        return response
    }

    private fun buildToolDescriptors(): List<Map<String, Any>> = listOf(
        toolDescriptor("workflows_list", "查询项目下工作流列表", mapOf(
            "type" to "object",
            "properties" to mapOf("projectBasePath" to mapOf("type" to "string")),
            "required" to listOf("projectBasePath")
        )),
        toolDescriptor("workflow_read_json", "读取指定工作流 json", mapOf(
            "type" to "object",
            "properties" to mapOf("workflowDirPath" to mapOf("type" to "string")),
            "required" to listOf("workflowDirPath")
        )),
        toolDescriptor("workflow_read_node_code", "读取指定节点代码文件", mapOf(
            "type" to "object",
            "properties" to mapOf(
                "workflowDirPath" to mapOf("type" to "string"),
                "nodeId" to mapOf("type" to "string")
            ),
            "required" to listOf("workflowDirPath", "nodeId")
        )),
        toolDescriptor("workflow_edit", "编辑工作流（节点/边/代码/prompt）", mapOf(
            "type" to "object",
            "properties" to mapOf(
                "workflowDirPath" to mapOf("type" to "string"),
                "request" to mapOf("type" to "object")
            ),
            "required" to listOf("workflowDirPath", "request")
        )),
        toolDescriptor("workflow_run", "运行工作流并返回日志", mapOf(
            "type" to "object",
            "properties" to mapOf("workflowDirPath" to mapOf("type" to "string")),
            "required" to listOf("workflowDirPath")
        ))
    )

    private fun toolDescriptor(name: String, description: String, inputSchema: Map<String, Any>) = mapOf(
        "name" to name,
        "description" to description,
        "inputSchema" to inputSchema
    )

    private fun callTool(name: String, arguments: JsonObject): Map<String, Any> {
        val payload: Any = when (name) {
            "workflows_list" -> listWorkflows(arguments.requireString("projectBasePath"))
            "workflow_read_json" -> mapOf("workflowJson" to readWorkflowJson(arguments.requireString("workflowDirPath")))
            "workflow_read_node_code" -> mapOf(
                "code" to readNodeCodeFile(
                    arguments.requireString("workflowDirPath"),
                    arguments.requireString("nodeId")
                )
            )

            "workflow_edit" -> {
                val dir = arguments.requireString("workflowDirPath")
                val requestObj = arguments.getAsJsonObject("request") ?: throw IllegalArgumentException("Missing request")
                val request = gson.fromJson(requestObj, WorkflowEditRequest::class.java)
                mapOf("workflowJson" to editWorkflow(dir, request))
            }

            "workflow_run" -> runWorkflow(arguments.requireString("workflowDirPath"))
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }

        return mapOf(
            "content" to listOf(
                mapOf("type" to "text", "text" to gson.toJson(payload))
            ),
            "isError" to false
        )
    }

    private fun JsonObject.requireString(key: String): String {
        val value: JsonElement = this.get(key) ?: throw IllegalArgumentException("Missing $key")
        if (!value.isJsonPrimitive) throw IllegalArgumentException("$key must be string")
        return value.asString
    }

    private fun sendJson(exchange: HttpExchange, status: Int, payload: Any, sessionId: String? = null) {
        val bytes = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        addCorsHeaders(exchange)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        if (!sessionId.isNullOrBlank()) {
            exchange.responseHeaders.set("Mcp-Session-Id", sessionId)
        }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendEmpty(exchange: HttpExchange, status: Int) {
        addCorsHeaders(exchange)
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun addCorsHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Authorization, Mcp-Session-Id")
    }

    private fun readBody(exchange: HttpExchange): String {
        return exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8)
            val value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
            key to value
        }.toMap()
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
