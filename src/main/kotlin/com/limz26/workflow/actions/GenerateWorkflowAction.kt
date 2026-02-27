package com.limz26.workflow.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class GenerateWorkflowAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        Messages.showMessageDialog(
            project,
            "Agent Workflow Generator\n\nSelect code and describe what workflow you want to create.",
            "Generate Workflow",
            Messages.getInformationIcon()
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
