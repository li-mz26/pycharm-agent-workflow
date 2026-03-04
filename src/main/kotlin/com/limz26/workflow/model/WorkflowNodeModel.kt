package com.limz26.workflow.model

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

        fun withConfigFile(path: String): AgentNodeModel {
            val merged = definition.config.copy(
                agentConfigFile = path,
                prompt = null,
                promptFile = null,
                promptTemplate = null,
                systemPrompt = null,
                apiEndpoint = null,
                apiKey = null,
                model = null
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

    class BranchNodeModel(override val definition: NodeDefinition) : WorkflowNodeModel(definition) {
        init {
            require(definition.type == "branch") { "BranchNodeModel 仅支持 branch 节点" }
        }

        fun withSwitchConfig(field: String?, cases: Map<String, String>, defaultTarget: String?): BranchNodeModel {
            val updated = definition.config.copy(
                branchField = field,
                branchCases = cases,
                defaultTarget = defaultTarget
            )
            return BranchNodeModel(definition.copy(config = updated))
        }

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
                "branch" -> BranchNodeModel(definition)
                else -> GenericNodeModel(definition)
            }
        }
    }
}
