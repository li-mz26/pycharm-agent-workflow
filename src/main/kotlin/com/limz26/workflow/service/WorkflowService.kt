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

    fun runWorkflow(workflowDirPath: String): WorkflowExecutionResult {
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
        val outputs = mutableMapOf<String, Map<String, Any?>>()

        for ((index, nodeId) in executionOrder.withIndex()) {
            val node = nodeById.getValue(nodeId)
            val nodeType = runCatching { NodeType.valueOf(node.type.uppercase()) }.getOrElse { NodeType.CODE }
            val upstreamNodeIds = incomingEdges[node.id].orEmpty().map { it.source }
            val mergedInputs = mergeUpstreamInputs(upstreamNodeIds, outputs)
            logs += "[${index + 1}/${executionOrder.size}] 执行节点: ${node.name} (${node.type})"

            try {
                val result = when (nodeType) {
                    NodeType.START -> mapOf("started" to true)
                    NodeType.END -> mergedInputs
                    NodeType.CODE -> executeCodeNode(loaded, node, mergedInputs)
                    NodeType.AGENT -> executeAgentNode(node, mergedInputs)
                    else -> mergedInputs
                }
                outputs[node.id] = result
                logs += "  - 输出: ${gson.toJson(result).take(300)}"
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
