package com.limz26.workflow.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 工作流预览工具窗口工厂
 */
class WorkflowPreviewToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = WorkflowPreviewPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.displayName = "预览"
        toolWindow.contentManager.addContent(content)
    }
}
