package com.limz26.workflow.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.limz26.workflow.ui.WorkflowPreviewPanel
import com.limz26.workflow.ui.WorkflowPreviewToolWindowFactory
import java.io.File

/**
 * 预览工作流动作 - 右键工作流文件夹触发
 */
class PreviewWorkflowAction : AnAction("Preview Workflow") {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        
        val workflowDir = File(virtualFile.path)
        if (!isWorkflowDirectory(workflowDir)) {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                project,
                "选中的文件夹不是有效的工作流目录（缺少 workflow.json）",
                "无效的工作流"
            )
            return
        }
        
        // 打开预览工具窗口
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Workflow Preview")
        toolWindow?.show {
            val content = toolWindow.contentManager.getContent(0)
            val panel = content?.component as? WorkflowPreviewPanel
            panel?.loadWorkflow(workflowDir)
        }
    }
    
    override fun update(e: AnActionEvent) {
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = e.project
        
        e.presentation.isEnabledAndVisible = if (virtualFile != null && project != null) {
            virtualFile.isDirectory && isWorkflowDirectory(File(virtualFile.path))
        } else {
            false
        }
    }
    
    private fun isWorkflowDirectory(dir: File): Boolean {
        return dir.isDirectory && File(dir, "workflow.json").exists()
    }
}

/**
 * 刷新工作流列表动作
 */
class RefreshWorkflowsAction : AnAction("Refresh Workflows") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Workflow Preview")
        val content = toolWindow?.contentManager?.getContent(0)
        val panel = content?.component as? WorkflowPreviewPanel
        panel?.refresh()
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
