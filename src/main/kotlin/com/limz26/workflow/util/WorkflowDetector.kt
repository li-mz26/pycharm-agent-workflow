package com.limz26.workflow.util

import com.intellij.openapi.project.Project
import java.io.File

/**
 * 工作流文件夹检测器
 */
object WorkflowDetector {
    
    // 常见的工作流文件夹名称
    private val WORKFLOW_DIR_NAMES = listOf(
        "workflows",
        "workflow",
        "flows",
        "flow",
        "dag",
        "dags",
        "pipelines",
        "pipeline",
        "jobs",
        "job"
    )
    
    // 工作流文件扩展名
    private val WORKFLOW_EXTENSIONS = listOf(
        ".json",
        ".yaml",
        ".yml",
        ".py",
        ".kts",
        ".groovy"
    )
    
    /**
     * 检测项目中的工作流文件夹
     * @param project 当前项目
     * @return 工作流文件夹路径列表
     */
    fun detectWorkflowDirs(project: Project): List<String> {
        val basePath = project.basePath ?: return emptyList()
        return detectWorkflowDirs(File(basePath))
    }
    
    /**
     * 检测指定路径下的工作流文件夹
     * @param root 根目录
     * @return 工作流文件夹路径列表
     */
    fun detectWorkflowDirs(root: File): List<String> {
        val workflowDirs = mutableListOf<String>()
        
        // 1. 直接查找命名匹配的工作流文件夹
        WORKFLOW_DIR_NAMES.forEach { dirName ->
            val dir = File(root, dirName)
            if (dir.exists() && dir.isDirectory) {
                workflowDirs.add(dir.absolutePath)
            }
        }
        
        // 2. 查找包含工作流文件的文件夹
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            if (containsWorkflowFiles(dir)) {
                if (!workflowDirs.contains(dir.absolutePath)) {
                    workflowDirs.add(dir.absolutePath)
                }
            }
        }
        
        return workflowDirs.sorted()
    }
    
    /**
     * 检查目录是否包含工作流文件
     */
    private fun containsWorkflowFiles(dir: File): Boolean {
        val files = dir.listFiles() ?: return false
        
        return files.any { file ->
            if (file.isFile) {
                // 检查文件扩展名
                WORKFLOW_EXTENSIONS.any { ext ->
                    file.name.endsWith(ext, ignoreCase = true)
                } || 
                // 检查文件名是否包含 workflow 相关关键词
                file.name.contains("workflow", ignoreCase = true) ||
                file.name.contains("dag", ignoreCase = true) ||
                file.name.contains("flow", ignoreCase = true) ||
                file.name.contains("pipeline", ignoreCase = true)
            } else {
                false
            }
        }
    }
    
    /**
     * 获取默认工作流路径
     * @param project 当前项目
     * @param customPath 自定义路径
     * @param autoDetect 是否自动检测
     * @return 实际使用的工作流路径
     */
    fun getWorkflowPath(project: Project, customPath: String, autoDetect: Boolean): String {
        // 如果指定了自定义路径，优先使用
        if (customPath.isNotEmpty()) {
            val customDir = File(customPath)
            if (customDir.exists() && customDir.isDirectory) {
                return customPath
            }
        }
        
        // 自动检测工作流文件夹
        if (autoDetect) {
            val detectedDirs = detectWorkflowDirs(project)
            if (detectedDirs.isNotEmpty()) {
                return detectedDirs.first()  // 返回第一个检测到的文件夹
            }
        }
        
        // 默认使用项目根目录
        return project.basePath ?: "."
    }
    
    /**
     * 扫描工作流文件
     * @param workflowPath 工作流文件夹路径
     * @return 工作流文件列表
     */
    fun scanWorkflowFiles(workflowPath: String): List<WorkflowFileInfo> {
        val dir = File(workflowPath)
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }
        
        val files = dir.listFiles() ?: return emptyList()
        
        return files.filter { it.isFile }
            .map { file ->
                WorkflowFileInfo(
                    name = file.nameWithoutExtension,
                    path = file.absolutePath,
                    extension = file.extension.lowercase(),
                    size = file.length(),
                    lastModified = file.lastModified()
                )
            }
            .sortedBy { it.name }
    }
    
    /**
     * 判断文件是否是有效的工作流文件
     */
    fun isValidWorkflowFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        
        return WORKFLOW_EXTENSIONS.any { ext ->
            file.name.endsWith(ext, ignoreCase = true)
        } || file.name.contains("workflow", ignoreCase = true)
    }
}

/**
 * 工作流文件信息
 */
data class WorkflowFileInfo(
    val name: String,
    val path: String,
    val extension: String,
    val size: Long,
    val lastModified: Long
)
