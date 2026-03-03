package com.limz26.workflow.model

import java.util.UUID

/**
 * 工作流 DAG 定义
 */
data class Workflow(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val nodes: List<WorkflowNode> = emptyList(),
    val edges: List<WorkflowEdge> = emptyList(),
    val variables: Map<String, Variable> = emptyMap()
) {
    fun toJson(): String {
        return """
            {
              "id": "$id",
              "name": "$name",
              "description": "$description",
              "nodes": [${nodes.joinToString(",\n") { it.toJson() }}],
              "edges": [${edges.joinToString(",\n") { it.toJson() }}],
              "variables": {${variables.entries.joinToString(",\n") { "\"${it.key}\": ${it.value.toJson()}" }}}
            }
        """.trimIndent()
    }
}

/**
 * 工作流节点
 */
data class WorkflowNode(
    val id: String = UUID.randomUUID().toString(),
    val type: NodeType,
    val name: String,
    val position: Position = Position(0, 0),
    val config: NodeConfig = NodeConfig()
) {
    fun toJson(): String {
        return """
            {
              "id": "$id",
              "type": "${type.value}",
              "name": "$name",
              "position": ${position.toJson()},
              "config": ${config.toJson()}
            }
        """.trimIndent()
    }
}

/**
 * 节点类型
 */
enum class NodeType(val value: String) {
    START("start"),
    END("end"),
    CONDITION("condition"),
    CODE("code"),
    AGENT("agent"),
    HTTP("http"),
    VARIABLE("variable")
}

/**
 * 节点配置
 */
data class NodeConfig(
    val code: String? = null,                    // code 节点
    val codeFile: String? = null,                // code 节点脚本路径
    val prompt: String? = null,                  // agent 节点
    val promptTemplate: String? = null,          // agent 节点模板
    val systemPrompt: String? = null,            // agent 节点 system prompt
    val apiEndpoint: String? = null,             // agent 节点 endpoint
    val apiKey: String? = null,                  // agent 节点 key
    val model: String? = null,                   // agent 节点
    val condition: String? = null,               // condition 节点
    val method: String? = null,                  // http 节点
    val url: String? = null,                     // http 节点
    val headers: Map<String, String>? = null,     // http 节点
    val value: String? = null,                   // variable 节点
    val inputs: Map<String, String> = emptyMap(),
    val outputs: Map<String, String> = emptyMap()
) {
    fun toJson(): String {
        val fields = mutableListOf<String>()
        code?.let { fields.add("\"code\": \"${escapeJson(it)}\"") }
        codeFile?.let { fields.add("\"codeFile\": \"${escapeJson(it)}\"") }
        prompt?.let { fields.add("\"prompt\": \"${escapeJson(it)}\"") }
        promptTemplate?.let { fields.add("\"promptTemplate\": \"${escapeJson(it)}\"") }
        systemPrompt?.let { fields.add("\"systemPrompt\": \"${escapeJson(it)}\"") }
        apiEndpoint?.let { fields.add("\"apiEndpoint\": \"${escapeJson(it)}\"") }
        apiKey?.let { fields.add("\"apiKey\": \"${escapeJson(it)}\"") }
        model?.let { fields.add("\"model\": \"$it\"") }
        condition?.let { fields.add("\"condition\": \"${escapeJson(it)}\"") }
        method?.let { fields.add("\"method\": \"$it\"") }
        url?.let { fields.add("\"url\": \"$it\"") }
        headers?.let { fields.add("\"headers\": {${it.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}}") }
        value?.let { fields.add("\"value\": \"${escapeJson(it)}\"") }
        if (inputs.isNotEmpty()) fields.add("\"inputs\": {${inputs.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}}")
        if (outputs.isNotEmpty()) fields.add("\"outputs\": {${outputs.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}}")
        return "{${fields.joinToString(",")}}"
    }
}

/**
 * 工作流边（连接）
 */
data class WorkflowEdge(
    val id: String = UUID.randomUUID().toString(),
    val source: String,
    val target: String,
    val condition: String? = null  // 条件分支时的条件表达式
) {
    fun toJson(): String {
        val cond = condition?.let { ", \"condition\": \"${escapeJson(it)}\"" } ?: ""
        return """
            {
              "id": "$id",
              "source": "$source",
              "target": "$target"$cond
            }
        """.trimIndent()
    }
}

/**
 * 位置
 */
data class Position(val x: Int, val y: Int) {
    fun toJson(): String = "{\"x\": $x, \"y\": $y}"
}

/**
 * 变量定义
 */
data class Variable(
    val name: String,
    val type: String,
    val defaultValue: String? = null
) {
    fun toJson(): String {
        val def = defaultValue?.let { ", \"default\": \"$it\"" } ?: ""
        return "{\"type\": \"$type\"$def}"
    }
}

private fun escapeJson(s: String): String {
    return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
