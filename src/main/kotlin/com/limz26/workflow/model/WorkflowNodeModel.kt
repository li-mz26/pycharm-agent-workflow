package com.limz26.workflow.model

import com.google.gson.JsonObject

/**
 * 面向对象节点模型：不同节点类型通过子类承载各自行为。
 */
sealed class WorkflowNodeModel(open val definition: NodeDefinition) {
    val id: String get() = definition.id
    val type: String get() = definition.type
    val name: String get() = definition.name

    abstract fun toDefinition(): NodeDefinition

    class AgentNodeModel(override val definition: NodeDefinition) : WorkflowNodeModel(definition) {
        init {
            require(definition.type == "agent") { "AgentNodeModel 仅支持 agent 节点" }
        }

        fun mergeConfigPatch(configObj: JsonObject): AgentNodeModel {
            val merged = definition.config.copy(
                prompt = configObj.stringValue("prompt", definition.config.prompt),
                promptFile = configObj.stringValue("promptFile", definition.config.promptFile),
                promptTemplate = configObj.stringValue("promptTemplate", definition.config.promptTemplate),
                systemPrompt = configObj.stringValue("systemPrompt", definition.config.systemPrompt),
                apiEndpoint = configObj.stringValue("apiEndpoint", definition.config.apiEndpoint),
                apiKey = configObj.stringValue("apiKey", definition.config.apiKey),
                model = configObj.stringValue("model", definition.config.model),
                inputs = configObj.mapValue("inputs", definition.config.inputs),
                outputs = configObj.mapValue("outputs", definition.config.outputs)
            )
            return AgentNodeModel(definition.copy(config = merged))
        }

        override fun toDefinition(): NodeDefinition = definition
    }

    class CodeNodeModel(override val definition: NodeDefinition) : WorkflowNodeModel(definition) {
        init {
            require(definition.type == "code") { "CodeNodeModel 仅支持 code 节点" }
        }

        fun codeFilePathOrDefault(): String = definition.config.codeFile ?: "nodes/${definition.id}.py"

        override fun toDefinition(): NodeDefinition = definition
    }

    class GenericNodeModel(override val definition: NodeDefinition) : WorkflowNodeModel(definition) {
        override fun toDefinition(): NodeDefinition = definition
    }

    companion object {
        fun fromDefinition(definition: NodeDefinition): WorkflowNodeModel {
            return when (definition.type) {
                "agent" -> AgentNodeModel(definition)
                "code" -> CodeNodeModel(definition)
                else -> GenericNodeModel(definition)
            }
        }
    }
}

private fun JsonObject.stringValue(key: String, fallback: String?): String? {
    if (!has(key)) return fallback
    val value = get(key)
    return if (value.isJsonNull) null else value.asString
}

private fun JsonObject.mapValue(key: String, fallback: Map<String, String>): Map<String, String> {
    if (!has(key)) return fallback
    val value = get(key)
    if (value.isJsonNull) return emptyMap()
    if (!value.isJsonObject) return fallback
    return value.asJsonObject.entrySet().associate { (k, v) -> k to (if (v.isJsonNull) "" else v.asString) }
}
