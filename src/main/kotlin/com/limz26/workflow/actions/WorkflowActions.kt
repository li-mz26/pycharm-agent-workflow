package com.limz26.workflow.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.limz26.workflow.ui.WorkflowToolWindowFactory

/**
 * 打开工作流面板动作
 */
class OpenWorkflowPanelAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openWorkflowToolWindow(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
    
    private fun openWorkflowToolWindow(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Agent Workflow")
        toolWindow?.show()
    }
}

/**
 * 从选中代码生成工作流
 */
class GenerateWorkflowFromSelectionAction : AnAction("Generate Workflow from Selection") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        
        val selectedText = editor.selectionModel.selectedText
        if (selectedText.isNullOrBlank()) {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                project,
                "请先选中一些代码",
                "未选择代码"
            )
            return
        }
        
        // 打开工作流面板并传入选中的代码作为上下文
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Agent Workflow")
        toolWindow?.show()
        
        // TODO: 将 selectedText 传递给面板
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor?.selectionModel?.hasSelection() == true
    }
}
