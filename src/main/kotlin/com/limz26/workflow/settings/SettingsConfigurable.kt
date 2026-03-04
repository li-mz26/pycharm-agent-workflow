package com.limz26.workflow.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.components.service
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JCheckBox
import java.awt.GridBagLayout
import java.awt.GridBagConstraints
import java.awt.Insets

class SettingsConfigurable : Configurable {
    private var panel: JPanel? = null
    private var apiKeyField: JBPasswordField? = null
    private var apiEndpointField: JBTextField? = null
    private var modelField: JBTextField? = null
    private var autoDetectCheckbox: JCheckBox? = null
    private var mcpEnabledCheckbox: JCheckBox? = null
    private var mcpPortField: JBTextField? = null
    private var pythonPathField: JBTextField? = null

    override fun getDisplayName(): String = "Agent Workflow (LLM 配置)"

    override fun createComponent(): JComponent {
        val settings = service<AppSettings>()
        
        panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(5, 5, 5, 5)
        }
        
        // LLM API 配置
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel?.add(JLabel("API Key:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        apiKeyField = JBPasswordField()
        apiKeyField?.text = settings.apiKey
        panel?.add(apiKeyField!!, gbc)
        
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel?.add(JLabel("API Endpoint:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        apiEndpointField = JBTextField()
        apiEndpointField?.text = settings.apiEndpoint
        panel?.add(apiEndpointField!!, gbc)
        
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel?.add(JLabel("Model:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        modelField = JBTextField()
        modelField?.text = settings.model
        panel?.add(modelField!!, gbc)
        
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2
        gbc.insets = Insets(5, 5, 5, 5)
        autoDetectCheckbox = JCheckBox("自动检测工作流文件夹")
        autoDetectCheckbox?.isSelected = settings.autoDetectWorkflows
        autoDetectCheckbox?.toolTipText = "自动在项目目录中查找工作流文件夹"
        panel?.add(autoDetectCheckbox!!, gbc)

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 1; gbc.weightx = 0.0
        panel?.add(JLabel("MCP 服务端口:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        mcpPortField = JBTextField()
        mcpPortField?.text = settings.mcpServerPort.toString()
        mcpPortField?.toolTipText = "内置 MCP 服务端口"
        panel?.add(mcpPortField!!, gbc)

        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2; gbc.weightx = 1.0
        mcpEnabledCheckbox = JCheckBox("启用内置 MCP 服务")
        mcpEnabledCheckbox?.isSelected = settings.mcpServerEnabled
        panel?.add(mcpEnabledCheckbox!!, gbc)

        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 1; gbc.weightx = 0.0
        panel?.add(JLabel("Python 路径:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        pythonPathField = JBTextField()
        pythonPathField?.text = settings.pythonPath
        pythonPathField?.toolTipText = "Python 解释器路径，例如 python3 或 /usr/bin/python3"
        panel?.add(pythonPathField!!, gbc)

        // 说明文本
        gbc.gridy = 7; gbc.gridwidth = 2; gbc.weighty = 1.0
        gbc.anchor = GridBagConstraints.NORTH
        val noteLabel = JLabel("""
            <html>
            <body style='width: 400px; color: gray;'>
            <b>说明：</b><br/>
            • API Endpoint 支持任何 OpenAI 兼容格式的服务<br/>
            • 工作流路径请在主界面「选择工作流目录」按钮中设置<br/>
            • 启用自动检测后，插件会查找包含 workflow 文件的目录
            </body>
            </html>
        """.trimIndent())
        panel?.add(noteLabel, gbc)
        
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = service<AppSettings>()
        return apiKeyField?.text != settings.apiKey ||
               apiEndpointField?.text != settings.apiEndpoint ||
               modelField?.text != settings.model ||
               autoDetectCheckbox?.isSelected != settings.autoDetectWorkflows ||
               mcpEnabledCheckbox?.isSelected != settings.mcpServerEnabled ||
               (mcpPortField?.text?.toIntOrNull() ?: settings.mcpServerPort) != settings.mcpServerPort ||
               (pythonPathField?.text ?: settings.pythonPath) != settings.pythonPath
    }

    override fun apply() {
        val settings = service<AppSettings>()
        settings.apiKey = apiKeyField?.text ?: ""
        settings.apiEndpoint = apiEndpointField?.text ?: settings.apiEndpoint
        settings.model = modelField?.text ?: settings.model
        settings.autoDetectWorkflows = autoDetectCheckbox?.isSelected ?: true
        settings.mcpServerEnabled = mcpEnabledCheckbox?.isSelected ?: true
        settings.mcpServerPort = (mcpPortField?.text?.toIntOrNull() ?: settings.mcpServerPort).coerceIn(1, 65535)
        settings.pythonPath = pythonPathField?.text?.takeIf { it.isNotBlank() } ?: "python3"
    }

    override fun reset() {
        val settings = service<AppSettings>()
        apiKeyField?.text = settings.apiKey
        apiEndpointField?.text = settings.apiEndpoint
        modelField?.text = settings.model
        autoDetectCheckbox?.isSelected = settings.autoDetectWorkflows
        mcpEnabledCheckbox?.isSelected = settings.mcpServerEnabled
        mcpPortField?.text = settings.mcpServerPort.toString()
        pythonPathField?.text = settings.pythonPath
    }
}
