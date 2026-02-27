package com.limz26.workflow.util

import com.intellij.openapi.project.Project
import com.limz26.workflow.model.LoadedWorkflow
import com.limz26.workflow.model.WorkflowLoader
import java.io.File

/**
 * 工作流文件夹检测器
 * 工作流定义：包含 workflow.json 的文件夹
 */
object WorkflowDetector {
    
    private val loader = WorkflowLoader()
    
    // 常见的工作流文件夹名称
    private val WORKFLOW_DIR_NAMES = listOf(
        "workflows",
        "workflow",
        "flows",
        "flow",
        "dag",
        "dags",
        "examples",
        "example"
    )
    
    /**
     * 检测项目中的所有工作流文件夹
     * @param project 当前项目
     * @return 工作流列表
     */
    fun detectWorkflowFolders(project: Project): List<LoadedWorkflow> {
        val basePath = project.basePath ?: return emptyList()
        return detectWorkflowFolders(File(basePath))
    }
    
    /**
     * 检测指定路径下的所有工作流文件夹
     * @param root 根目录
     * @return 工作流列表
     */
    fun detectWorkflowFolders(root: File): List<LoadedWorkflow> {
        val workflows = mutableListOf<LoadedWorkflow>()
        
        // 1. 直接查找命名匹配的工作流文件夹
        WORKFLOW_DIR_NAMES.forEach { dirName ->
            val dir = File(root, dirName)
            if (dir.exists() && dir.isDirectory) {
                loader.load(dir)?.let { workflows.add(it) }
            }
        }
        
        // 2. 遍历所有子目录，查找包含 workflow.json 的文件夹
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { dir ->
            // 排除常见非工作流目录
            if (dir.name !in listOf("src", "out", "build", ".git", ".idea", "gradle")) {
                loader.load(dir)?.let { 
                    if (!workflows.any { w -> w.baseDir.absolutePath == it.baseDir.absolutePath }) {
                        workflows.add(it)
                    }
                }
            }
        }
        
        return workflows.sortedBy { it.name }
    }
    
    /**
     * 扫描单个文件夹内的工作流
     */
    fun scanFolder(dir: File): LoadedWorkflow? {
        return loader.load(dir)
    }
    
    /**
     * 判断文件夹是否是工作流文件夹
     * 标准：包含 workflow.json 或任意 .json 文件
     */
    fun isWorkflowFolder(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        // 检查是否有任意 .json 文件
        return dir.listFiles { f -> f.isFile && f.extension == "json" }?.isNotEmpty() ?: false
    }
}
