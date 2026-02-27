package com.limz26.workflow.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.BorderLayout

class WorkflowPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {
    init {
        val mainPanel = JPanel(BorderLayout())
        
        // Input area
        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(JLabel("Describe your workflow:"), BorderLayout.NORTH)
        val inputArea = JBTextArea(5, 40)
        inputPanel.add(inputArea, BorderLayout.CENTER)
        
        // Generate button
        val generateButton = JButton("Generate Workflow")
        generateButton.addActionListener {
            val prompt = inputArea.text
            if (prompt.isNotBlank()) {
                // TODO: Call LLM agent to generate workflow
                println("Generating workflow for: $prompt")
            }
        }
        inputPanel.add(generateButton, BorderLayout.SOUTH)
        
        mainPanel.add(inputPanel, BorderLayout.NORTH)
        setContent(mainPanel)
    }
}
