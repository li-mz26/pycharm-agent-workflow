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
                nodeFiles[node.id] = loadNodeFiles(node, nodesDir, workflowDir)
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
    
    private fun loadNodeFiles(node: NodeDefinition, nodesDir: File, workflowDir: File): NodeFiles {
        return when (node.type) {
            "code" -> {
                // 优先从外部文件加载代码
                val codeFromFile = node.config.codeFile?.let { codeFilePath ->
                    val codeFile = File(workflowDir, codeFilePath)
                    if (codeFile.exists()) codeFile.readText() else null
                }
                
                // 如果没有外部文件，尝试从 nodes 目录加载
                val codeFromNodesDir = if (codeFromFile == null) {
                    val codeFile = File(nodesDir, "${node.id}.py")
                    if (codeFile.exists()) codeFile.readText() else null
                } else null
                
                // 最后使用内联代码
                val finalCode = codeFromFile ?: codeFromNodesDir ?: node.config.code
                
                NodeFiles(
                    codeContent = finalCode,
                    codeFilePath = node.config.codeFile ?: "nodes/${node.id}.py",
                    promptContent = null,
                    configContent = null
                )
            }
            "agent" -> {
                // 优先从外部文件加载提示词
                val promptFromFile = node.config.promptFile?.let { promptFilePath ->
                    val promptFile = File(workflowDir, promptFilePath)
                    if (promptFile.exists()) promptFile.readText() else null
                }
                
                val finalPrompt = promptFromFile ?: node.config.prompt
                
                val configFile = File(nodesDir, "${node.id}_config.json")
                NodeFiles(
                    codeContent = null,
                    codeFilePath = null,
                    promptContent = finalPrompt,
                    promptFilePath = node.config.promptFile ?: "nodes/${node.id}_prompt.md",
                    configContent = if (configFile.exists()) configFile.readText() else null
                )
            }
            else -> NodeFiles(null, null, null, null, null)
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
    val codeFilePath: String? = null,      // 代码文件路径
    val promptContent: String?,
    val promptFilePath: String? = null,    // 提示词文件路径
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
    val code: String? = null,                    // 内联代码（向后兼容）
    val codeFile: String? = null,                // 外部代码文件路径，如 "nodes/process_data.py"
    val prompt: String? = null,
    val promptFile: String? = null,              // 外部提示词文件路径
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
