package com.limz26.workflow.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * 工作流加载器 - 从文件夹加载工作流
 */
class WorkflowLoader {
    
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    
    /**
     * 从工作流文件夹加载完整工作流
     * 优先查找 workflow.json，如果没有则查找任意 .json 文件
     */
    fun load(workflowDir: File): LoadedWorkflow? {
        if (!workflowDir.exists() || !workflowDir.isDirectory) {
            return null
        }

        // 1. 优先查找 workflow.json
        var workflowJson = File(workflowDir, "workflow.json")

        // 2. 如果没有 workflow.json，查找任意 .json 文件
        if (!workflowJson.exists()) {
            val jsonFiles = workflowDir.listFiles { f -> f.isFile && f.extension == "json" }
            if (jsonFiles.isNullOrEmpty()) {
                return null
            }
            workflowJson = jsonFiles.first()
        }

        return try {
            val workflow = gson.fromJson(workflowJson.readText(), WorkflowDefinition::class.java)
            val nodesDir = File(workflowDir, "nodes")

            // 加载节点文件内容
            val nodeFiles = mutableMapOf<String, NodeFiles>()
            workflow.nodes.forEach { node ->
                nodeFiles[node.id] = loadNodeFiles(node, nodesDir)
            }

            LoadedWorkflow(
                definition = workflow,
                baseDir = workflowDir,
                nodeFiles = nodeFiles
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 扫描目录下的所有工作流
     */
    fun scanWorkflows(baseDir: File): List<LoadedWorkflow> {
        val workflowsDir = File(baseDir, "workflows")
        if (!workflowsDir.exists()) return emptyList()
        
        return workflowsDir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { load(it) }
            ?: emptyList()
    }
    
    private fun loadNodeFiles(node: NodeDefinition, nodesDir: File): NodeFiles {
        return when (node.type) {
            "code" -> {
                val codeFile = File(nodesDir, "${node.id}.py")
                NodeFiles(
                    codeContent = if (codeFile.exists()) codeFile.readText() else null,
                    promptContent = null,
                    configContent = null
                )
            }
            "agent" -> {
                val promptFile = File(nodesDir, "${node.id}_prompt.md")
                val configFile = File(nodesDir, "${node.id}_config.json")
                NodeFiles(
                    codeContent = null,
                    promptContent = if (promptFile.exists()) promptFile.readText() else null,
                    configContent = if (configFile.exists()) configFile.readText() else null
                )
            }
            else -> NodeFiles(null, null, null)
        }
    }
}

/**
 * 加载后的工作流（包含文件内容）
 */
data class LoadedWorkflow(
    val definition: WorkflowDefinition,
    val baseDir: File,
    val nodeFiles: Map<String, NodeFiles>
) {
    val name: String get() = definition.name
    val description: String get() = definition.description
}

/**
 * 节点的文件内容
 */
data class NodeFiles(
    val codeContent: String?,
    val promptContent: String?,
    val configContent: String?
)

/**
 * 工作流定义（JSON 结构）
 */
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val description: String,
    val nodes: List<NodeDefinition>,
    val edges: List<EdgeDefinition>,
    val variables: Map<String, VariableDefinition>
)

data class NodeDefinition(
    val id: String,
    val type: String,
    val name: String,
    val position: PositionDefinition,
    val config: NodeConfigDefinition
)

data class PositionDefinition(
    val x: Int,
    val y: Int
)

data class NodeConfigDefinition(
    val code: String? = null,
    val prompt: String? = null,
    val model: String? = null,
    val condition: String? = null,
    val method: String? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val value: String? = null,
    val inputs: Map<String, String> = emptyMap(),
    val outputs: Map<String, String> = emptyMap()
)

data class EdgeDefinition(
    val id: String,
    val source: String,
    val target: String,
    val condition: String? = null
)

data class VariableDefinition(
    val type: String,
    val default: String? = null
)
