package com.limz26.workflow.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.components.service
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.GridLayout

class SettingsConfigurable : Configurable {
    private var panel: JPanel? = null
    private var apiKeyField: JBPasswordField? = null
    private var apiEndpointField: JBTextField? = null
    private var modelField: JBTextField? = null

    override fun getDisplayName(): String = "Agent Workflow"

    override fun createComponent(): JComponent {
        val settings = service<AppSettings>()
        
        panel = JPanel(GridLayout(0, 2, 10, 10))
        
        panel?.add(JLabel("API Key:"))
        apiKeyField = JBPasswordField()
        apiKeyField?.text = settings.apiKey
        panel?.add(apiKeyField!!)
        
        panel?.add(JLabel("API Endpoint:"))
        apiEndpointField = JBTextField()
        apiEndpointField?.text = settings.apiEndpoint
        panel?.add(apiEndpointField!!)
        
        panel?.add(JLabel("Model:"))
        modelField = JBTextField()
        modelField?.text = settings.model
        panel?.add(modelField!!)
        
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = service<AppSettings>()
        return apiKeyField?.text != settings.apiKey ||
               apiEndpointField?.text != settings.apiEndpoint ||
               modelField?.text != settings.model
    }

    override fun apply() {
        val settings = service<AppSettings>()
        settings.apiKey = apiKeyField?.text ?: ""
        settings.apiEndpoint = apiEndpointField?.text ?: settings.apiEndpoint
        settings.model = modelField?.text ?: settings.model
    }

    override fun reset() {
        val settings = service<AppSettings>()
        apiKeyField?.text = settings.apiKey
        apiEndpointField?.text = settings.apiEndpoint
        modelField?.text = settings.model
    }
}
