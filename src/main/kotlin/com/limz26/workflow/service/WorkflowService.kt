package com.limz26.workflow.service

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.components.Service
import com.limz26.workflow.agent.WorkflowAgent
import com.limz26.workflow.llm.LLMClient
import com.limz26.workflow.model.*
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

@Service
class WorkflowService {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    data class WorkflowExecutionResult(
        val success: Boolean,
        val logs: List<String>,
        val validationErrors: List<String> = emptyList()
    )

    fun runWorkflow(workflowDirPath: String, initialInput: Map<String, Any?> = emptyMap()): WorkflowExecutionResult {
        val loader = WorkflowLoader()
        val loaded = loader.load(File(workflowDirPath)) ?: return WorkflowExecutionResult(
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
            return WorkflowExecutionResult(false, logs, validation.errors)
        }

        val executionOrder = topologicalOrder(loaded.definition)
        if (executionOrder.size != loaded.definition.nodes.size) {
            logs += "执行失败: 工作流存在环，无法拓扑执行"
            return WorkflowExecutionResult(false, logs, listOf("workflow 不是 DAG"))
        }

        val nodeById = loaded.definition.nodes.associateBy { it.id }
        val incomingEdges = loaded.definition.edges.groupBy { it.target }
        val outgoingEdges = loaded.definition.edges.groupBy { it.source }
        val outputs = mutableMapOf<String, Map<String, Any?>>()
        val activeEdgeIds = mutableSetOf<String>()

        for ((index, nodeId) in executionOrder.withIndex()) {
            val node = nodeById.getValue(nodeId)
            val nodeType = runCatching { NodeType.valueOf(node.type.uppercase()) }.getOrElse { NodeType.CODE }
            val incomingForNode = incomingEdges[node.id].orEmpty()
            val activeIncoming = incomingForNode.filter { it.id in activeEdgeIds }

            if (incomingForNode.isNotEmpty() && activeIncoming.isEmpty()) {
                logs += "[${index + 1}/${executionOrder.size}] 跳过节点: ${node.name} (${node.type})，未命中条件分支"
                continue
            }

            val upstreamNodeIds = activeIncoming.map { it.source }
            val mergedInputs = mergeUpstreamInputs(upstreamNodeIds, outputs)
            logs += "[${index + 1}/${executionOrder.size}] 执行节点: ${node.name} (${node.type})"

            try {
                val result = when (nodeType) {
                    NodeType.START -> if (initialInput.isEmpty()) mapOf("started" to true) else initialInput
                    NodeType.END -> mergedInputs
                    NodeType.CODE -> executeCodeNode(loaded, node, mergedInputs)
                    NodeType.AGENT -> executeAgentNode(node, mergedInputs)
                    else -> mergedInputs
                }
                outputs[node.id] = result
                logs += "  - 输出: ${gson.toJson(result).take(300)}"

                val outgoingForNode = outgoingEdges[node.id].orEmpty()
                if (nodeType == NodeType.CONDITION) {
                    val conditionResult = evaluateConditionResult(node, mergedInputs, result)
                    val selected = selectConditionEdges(outgoingForNode, conditionResult)
                    activeEdgeIds.addAll(selected.map { it.id })
                    logs += "  - 条件结果: $conditionResult, 命中分支: ${selected.joinToString { it.target }}"
                } else {
                    activeEdgeIds.addAll(outgoingForNode.map { it.id })
                }
            } catch (e: Exception) {
                logs += "  - 节点执行失败: ${e.message}"
                return WorkflowExecutionResult(false, logs, listOf("节点 ${node.id} 执行失败: ${e.message}"))
            }
        }

        logs += "工作流运行完成（实际执行）"
        return WorkflowExecutionResult(true, logs)
    }

    private fun topologicalOrder(def: WorkflowDefinition): List<String> {
        val inDegree = def.nodes.associate { it.id to 0 }.toMutableMap()
        val adjacency = def.nodes.associate { it.id to mutableListOf<String>() }.toMutableMap()
        def.edges.forEach { edge ->
            adjacency.getValue(edge.source).add(edge.target)
            inDegree[edge.target] = inDegree.getValue(edge.target) + 1
        }

        val queue: ArrayDeque<String> = ArrayDeque(inDegree.filterValues { it == 0 }.keys.sorted())
        val order = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            order += current
            adjacency.getValue(current).sorted().forEach { target ->
                inDegree[target] = inDegree.getValue(target) - 1
                if (inDegree.getValue(target) == 0) queue.add(target)
            }
        }
        return order
    }

    private fun mergeUpstreamInputs(upstreamNodeIds: List<String>, outputs: Map<String, Map<String, Any?>>): Map<String, Any?> {
        val merged = linkedMapOf<String, Any?>()
        upstreamNodeIds.forEach { sourceId ->
            outputs[sourceId]?.forEach { (key, value) ->
                merged[key] = value
            }
            merged[sourceId] = outputs[sourceId].orEmpty()
        }
        return merged
    }

    private fun executeCodeNode(loaded: LoadedWorkflow, node: NodeDefinition, mergedInputs: Map<String, Any?>): Map<String, Any?> {
        val code = loaded.nodeFiles[node.id]?.codeContent
            ?: node.config.code
            ?: throw IllegalArgumentException("code 节点缺少可执行代码")
        val inputJson = gson.toJson(mergedInputs)

        val codeFile = Files.createTempFile("workflow-node-${node.id}-", ".py").toFile()
        val inputFile = Files.createTempFile("workflow-node-input-${node.id}-", ".json").toFile()
        val runnerFile = Files.createTempFile("workflow-node-runner-", ".py").toFile()

        try {
            codeFile.writeText(code)
            inputFile.writeText(inputJson)
            runnerFile.writeText(
                """
import json
import sys

code_path = sys.argv[1]
input_path = sys.argv[2]

namespace = {}
with open(code_path, "r", encoding="utf-8") as f:
    source = f.read()

exec(source, namespace)
if "main" not in namespace:
    raise RuntimeError("Python 节点必须定义 main(inputs) 函数")

with open(input_path, "r", encoding="utf-8") as f:
    inputs = json.load(f)

result = namespace["main"](inputs)
if result is None:
    result = {}
print(json.dumps(result, ensure_ascii=False))
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
    }

    private fun executeAgentNode(node: NodeDefinition, mergedInputs: Map<String, Any?>): Map<String, Any?> {
        val config = node.config
        val endpoint = config.apiEndpoint ?: throw IllegalArgumentException("agent 节点缺少 apiEndpoint")
        val apiKey = config.apiKey ?: throw IllegalArgumentException("agent 节点缺少 apiKey")
        val model = config.model ?: "gpt-4o-mini"
        val systemPrompt = config.systemPrompt ?: "你是一个工作流执行助手。"
        val template = config.promptTemplate ?: config.prompt ?: "请根据输入给出结果：{{input_json}}"

        val renderedPrompt = renderTemplate(template, mergedInputs)
        val client = LLMClient()
        val answer = client.chat(
            messages = listOf(
                LLMClient.Message("system", systemPrompt),
                LLMClient.Message("user", renderedPrompt)
            ),
            config = LLMClient.ChatConfig(
                apiEndpoint = endpoint,
                apiKey = apiKey,
                model = model,
                temperature = 0.2
            )
        )

        return mapOf(
            "prompt" to renderedPrompt,
            "response" to answer
        )
    }

    private fun renderTemplate(template: String, mergedInputs: Map<String, Any?>): String {
        val placeholderRegex = Regex("\\{\\{\\s*([a-zA-Z0-9_\\.]+)\\s*}}")
        return placeholderRegex.replace(template) { match ->
            val key = match.groupValues[1]
            if (key == "input_json") {
                gson.toJson(mergedInputs)
            } else {
                resolvePath(mergedInputs, key)?.toString() ?: ""
            }
        }
    }

    private fun resolvePath(data: Any?, path: String): Any? {
        var current: Any? = data
        for (part in path.split('.')) {
            current = when (current) {
                is Map<*, *> -> current[part]
                else -> return null
            }
        }
        return current
    }

    private fun parseJsonMapFromOutput(stdout: String): Map<*, *> {
        val jsonLine = stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.lastOrNull() ?: "{}"
        val parsed = gson.fromJson(jsonLine, Map::class.java)
        return parsed as? Map<*, *> ?: emptyMap<String, Any?>()
    }

    private fun evaluateConditionResult(
        node: NodeDefinition,
        mergedInputs: Map<String, Any?>,
        executionResult: Map<String, Any?>
    ): Boolean {
        val configuredExpression = node.config.condition?.trim().orEmpty()
        if (configuredExpression.isBlank()) {
            return when (val raw = executionResult["condition"] ?: mergedInputs["condition"] ?: mergedInputs["result"]) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                is String -> raw.equals("true", ignoreCase = true) || raw == "1" || raw == "是" || raw == "有"
                else -> false
            }
        }

        val lengthPattern = Regex("""len\(([^)]+)\)\s*(==|!=|>=|<=|>|<)\s*(\d+)""")
        val simplePattern = Regex("""([a-zA-Z0-9_\.]+)\s*(==|!=|>=|<=|>|<)\s*(.+)""")

        lengthPattern.matchEntire(configuredExpression)?.let { match ->
            val key = match.groupValues[1].trim()
            val op = match.groupValues[2]
            val right = match.groupValues[3].toIntOrNull() ?: return false
            val value = resolvePath(mergedInputs, key)
            val left = when (value) {
                is Collection<*> -> value.size
                is Map<*, *> -> value.size
                is String -> value.length
                is Array<*> -> value.size
                else -> 0
            }
            return compareNumber(left.toDouble(), op, right.toDouble())
        }

        simplePattern.matchEntire(configuredExpression)?.let { match ->
            val key = match.groupValues[1].trim()
            val op = match.groupValues[2]
            val rightRaw = match.groupValues[3].trim().trim('"', '\'')
            val left = resolvePath(mergedInputs, key)
            val rightNumber = rightRaw.toDoubleOrNull()
            val leftNumber = (left as? Number)?.toDouble()
            if (leftNumber != null && rightNumber != null) {
                return compareNumber(leftNumber, op, rightNumber)
            }
            val leftText = left?.toString().orEmpty()
            return compareText(leftText, op, rightRaw)
        }

        return false
    }

    private fun selectConditionEdges(outgoing: List<EdgeDefinition>, conditionResult: Boolean): List<EdgeDefinition> {
        if (outgoing.isEmpty()) return emptyList()

        val matched = outgoing.filter { edgeMatchesConditionResult(it.condition, conditionResult) }
        if (matched.isNotEmpty()) return matched

        if (outgoing.size == 1) return outgoing
        return if (conditionResult) listOf(outgoing.first()) else listOf(outgoing.last())
    }

    private fun edgeMatchesConditionResult(label: String?, conditionResult: Boolean): Boolean {
        val normalized = label?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false

        val trueLabels = setOf("true", "yes", "y", "1", "有", "是", "命中", "通过", "success")
        val falseLabels = setOf("false", "no", "n", "0", "无", "否", "不通过", "失败", "else", "default")

        return if (conditionResult) {
            trueLabels.any { normalized.contains(it) }
        } else {
            falseLabels.any { normalized.contains(it) }
        }
    }

    private fun compareNumber(left: Double, op: String, right: Double): Boolean {
        return when (op) {
            "==" -> left == right
            "!=" -> left != right
            ">" -> left > right
            "<" -> left < right
            ">=" -> left >= right
            "<=" -> left <= right
            else -> false
        }
    }

    private fun compareText(left: String, op: String, right: String): Boolean {
        return when (op) {
            "==" -> left == right
            "!=" -> left != right
            else -> false
        }
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
                        codeFile = it.config.codeFile,
                        prompt = it.config.prompt,
                        promptTemplate = it.config.promptTemplate,
                        systemPrompt = it.config.systemPrompt,
                        apiEndpoint = it.config.apiEndpoint,
                        apiKey = it.config.apiKey,
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
