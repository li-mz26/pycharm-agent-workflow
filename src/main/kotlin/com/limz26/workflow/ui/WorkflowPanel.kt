package com.limz26.workflow.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.limz26.workflow.agent.WorkflowAgent
import com.limz26.workflow.agent.WorkflowContext
import com.limz26.workflow.model.*
import com.limz26.workflow.settings.AppSettings
import com.limz26.workflow.util.WorkflowDetector
import com.limz26.workflow.util.WorkflowFileInfo
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridBagLayout
import java.awt.GridBagConstraints
import java.awt.Insets
import javax.swing.*
import javax.swing.text.html.HTMLEditorKit

/**
 * 主工作流面板 - 对话 + 可视化
 * 使用 IntelliJ 主题颜色，自适应布局
 */
class WorkflowPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {
    
    private val agent = WorkflowAgent()
    private val settings = service<AppSettings>()
    private var currentWorkflow: Workflow? = null
    private var workflowPath: String = ""
    private var workflowFiles: List<WorkflowFileInfo> = emptyList()
    
    // 颜色定义 - 适配明暗主题
    private val userBubbleColor = Color(0x66, 0x7E, 0xEA)
    private val agentBubbleColor = Color.WHITE
    private val errorBubbleColor = Color(0xFF, 0xEB, 0xEE)
    private val systemTextColor = Color(0x66, 0x66, 0x66)
    private val borderColor = Color(0xE0, 0xE0, 0xE0)
    private val bgColor = Color(0xF5, 0xF5, 0xF5)
    
    private val chatPane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        editorKit = HTMLEditorKit()
        background = bgColor
    }
    
    private val inputField = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        font = font.deriveFont(14f)
    }
    
    private val canvas = WorkflowCanvas()
    private val workflowListModel = DefaultListModel<String>()
    private val workflowList = JList(workflowListModel)
    
    private val chatHistory = StringBuilder()
    
    init {
        initWorkflowPath()
        val leftPanel = createLeftPanel()
        val canvasPanel = createCanvasPanel()
        
        val splitter = JBSplitter(false, 0.35f)
        splitter.firstComponent = leftPanel
        splitter.secondComponent = canvasPanel
        
        setContent(splitter)
        showWelcomeMessage()
    }
    
    private fun initWorkflowPath() {
        workflowPath = WorkflowDetector.getWorkflowPath(
            project, settings.workflowPath, settings.autoDetectWorkflows
        )
        workflowFiles = WorkflowDetector.scanWorkflowFiles(workflowPath)
        updateWorkflowList()
    }
    
    private fun updateWorkflowList() {
        workflowListModel.clear()
        workflowFiles.forEach { workflowListModel.addElement(it.name + "." + it.extension) }
        if (workflowFiles.isEmpty()) workflowListModel.addElement("(无工作流文件)")
    }
    
    private fun showWelcomeMessage() {
        val pathInfo = if (workflowPath == project.basePath) "项目根目录" else workflowPath
        val apiStatus = if (settings.apiKey.isBlank()) {
            "<span style='color:#e74c3c'>未配置 API Key</span>"
        } else {
            "<span style='color:#27ae60'>已配置 (${settings.model})</span>"
        }
        
        val themeBg = String.format("#%06X", 0xFFFFFF and bgColor.rgb)
        val textColor = "#333333"
        val borderHex = String.format("#%06X", 0xFFFFFF and borderColor.rgb)
        
        chatHistory.append("""
            <div style='background:${themeBg};color:${textColor};padding:10px;border-radius:8px;margin-bottom:10px;border:1px solid ${borderHex};'>
                <b>Agent Workflow 助手</b><br/>
                用自然语言描述你的工作流，我来帮你生成<br/><br/>
                <b>工作流路径:</b> $pathInfo<br/>
                <b>文件数量:</b> ${workflowFiles.size} 个<br/>
                <b>API 状态:</b> $apiStatus
            </div>
            <div style='background:#F0F0F0;padding:8px;border-radius:5px;font-size:12px;'>
                <b>示例:</b><br/>
                "创建一个数据清洗工作流" <br/>
                "帮我做一个审批流程"
            </div>
        """)
        updateChatDisplay()
    }
    
    private fun createLeftPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        
        // 工作流列表 - 固定高度，自适应宽度
        val listPanel = JPanel(BorderLayout())
        listPanel.border = BorderFactory.createTitledBorder("工作流 (${workflowFiles.size})")
        
        workflowList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        workflowList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                val idx = workflowList.selectedIndex
                if (idx >= 0 && idx < workflowFiles.size) loadWorkflowFile(workflowFiles[idx])
            }
        }
        
        listPanel.add(JBScrollPane(workflowList), BorderLayout.CENTER)
        listPanel.add(JButton("刷新").apply {
            addActionListener { initWorkflowPath() }
        }, BorderLayout.SOUTH)
        
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 1.0
        gbc.weighty = 0.3
        gbc.fill = GridBagConstraints.BOTH
        gbc.insets = Insets(5, 5, 5, 5)
        panel.add(listPanel, gbc)
        
        // 对话面板 - 自适应高度
        val chatPanel = createChatPanel()
        gbc.gridy = 1
        gbc.weighty = 0.7
        panel.add(chatPanel, gbc)
        
        return panel
    }
    
    private fun loadWorkflowFile(fileInfo: WorkflowFileInfo) {
        addSystemMessage("加载中: ${fileInfo.name}...")
        try {
            java.io.File(fileInfo.path).readText()
            addAgentMessage("已加载: ${fileInfo.name}.${fileInfo.extension}")
        } catch (e: Exception) {
            addErrorMessage("加载失败: ${e.message}")
        }
    }
    
    private fun createChatPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("对话")
        
        val gbc = GridBagConstraints()
        
        // 聊天区域 - 自适应填充
        chatPane.text = "<html><body></body></html>"
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 1.0
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        gbc.insets = Insets(5, 5, 5, 5)
        panel.add(JBScrollPane(chatPane), gbc)
        
        // 输入区域
        val inputPanel = JPanel(GridBagLayout())
        val inputGbc = GridBagConstraints()
        
        inputGbc.gridx = 0
        inputGbc.gridy = 0
        inputGbc.weightx = 1.0
        inputGbc.weighty = 1.0
        inputGbc.fill = GridBagConstraints.BOTH
        inputPanel.add(JBScrollPane(inputField), inputGbc)
        
        // 按钮面板
        val buttonPanel = JPanel()
        buttonPanel.add(JButton("发送").apply { addActionListener { onSend() } })
        buttonPanel.add(JButton("验证").apply { addActionListener { onValidate() } })
        buttonPanel.add(JButton("导出").apply { addActionListener { onExport() } })
        
        inputGbc.gridx = 0
        inputGbc.gridy = 1
        inputGbc.weighty = 0.0
        inputGbc.fill = GridBagConstraints.HORIZONTAL
        inputPanel.add(buttonPanel, inputGbc)
        
        gbc.gridy = 1
        gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(inputPanel, gbc)
        
        return panel
    }
    
    private fun createCanvasPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("工作流可视化")
        panel.add(canvas, BorderLayout.CENTER)
        return panel
    }
    
    private fun onSend() {
        val text = inputField.text.trim()
        if (text.isEmpty()) return
        
        addUserMessage(text)
        inputField.text = ""
        addSystemMessage("生成中...")
        
        object : SwingWorker<Workflow, Void>() {
            override fun doInBackground(): Workflow {
                return agent.generateWorkflow(text, WorkflowContext(currentWorkflow))
            }
            
            override fun done() {
                try {
                    val workflow = get()
                    currentWorkflow = workflow
                    removeLastSystemMessage()
                    
                    if (workflow.name == "错误") {
                        addErrorMessage(workflow.description)
                    } else {
                        addAgentMessage("已生成工作流: ${workflow.name}")
                        canvas.setWorkflow(workflow)
                    }
                } catch (e: Exception) {
                    removeLastSystemMessage()
                    addErrorMessage("错误: ${e.message}")
                }
            }
        }.execute()
    }
    
    private fun onValidate() {
        val workflow = currentWorkflow
        if (workflow == null) {
            addAgentMessage("还没有工作流")
            return
        }
        
        val result = agent.validateWorkflow(workflow)
        if (result.isValid) {
            addAgentMessage("验证通过!")
        } else {
            addErrorMessage("问题: ${result.errors.joinToString(", ")}")
        }
    }
    
    private fun onExport() {
        val workflow = currentWorkflow ?: return addAgentMessage("没有可导出的工作流")
        val path = WorkflowExporter(workflowPath).export(workflow)
        addAgentMessage("已导出到: $path")
        initWorkflowPath()
    }
    
    private fun addUserMessage(message: String) {
        val html = escapeHtml(message).replace("\n", "<br/>")
        val bg = String.format("#%06X", 0xFFFFFF and userBubbleColor.rgb)
        chatHistory.append("""
            <table width='100%' cellpadding='5'><tr><td align='right'>
            <div style='background:${bg};color:white;padding:10px 15px;border-radius:18px 18px 4px 18px;display:inline-block;max-width:85%;text-align:left;box-shadow:0 1px 3px rgba(0,0,0,0.2);'>
            <b>你:</b><br/>$html</div>
            </td></tr></table>
        """)
        updateChatDisplay()
    }
    
    private fun addAgentMessage(message: String) {
        val bg = String.format("#%06X", 0xFFFFFF and agentBubbleColor.rgb)
        val textColor = "#333333"
        chatHistory.append("""
            <table width='100%' cellpadding='5'><tr><td align='left'>
            <div style='background:${bg};color:${textColor};padding:10px 15px;border-radius:18px 18px 18px 4px;display:inline-block;max-width:85%;border:1px solid ${String.format("#%06X", 0xFFFFFF and borderColor.rgb)};box-shadow:0 1px 3px rgba(0,0,0,0.1);'>
            <b>Agent:</b><br/>$message</div>
            </td></tr></table>
        """)
        updateChatDisplay()
    }
    
    private fun addSystemMessage(message: String) {
        val textColor = "#666666"
        val bg = "#F0F0F0"
        chatHistory.append("""
            <div style='text-align:center;margin:8px 0;'>
            <span style='background:${bg};color:${textColor};padding:4px 12px;border-radius:12px;font-size:11px;'>$message</span>
            </div>
        """)
        updateChatDisplay()
    }
    
    private fun removeLastSystemMessage() {
        val lastDiv = chatHistory.lastIndexOf("<div style='text-align:center")
        if (lastDiv >= 0) {
            val endDiv = chatHistory.indexOf("</div>", lastDiv) + 6
            chatHistory.delete(lastDiv, endDiv)
            updateChatDisplay()
        }
    }
    
    private fun addErrorMessage(message: String) {
        val bg = String.format("#%06X", 0xFFFFFF and errorBubbleColor.rgb)
        val border = "#FFCCCC"
        val textColor = "#CC3333"
        chatHistory.append("""
            <table width='100%' cellpadding='5'><tr><td align='left'>
            <div style='background:${bg};color:${textColor};padding:10px 15px;border-radius:12px;display:inline-block;max-width:85%;border:1px solid ${border};'>
            <b>错误:</b><br/>$message</div>
            </td></tr></table>
        """)
        updateChatDisplay()
    }
    
    private fun updateChatDisplay() {
        val bg = String.format("#%06X", 0xFFFFFF and bgColor.rgb)
        val textColor = "#333333"
        chatPane.text = "<html><body bgcolor='$bg' style='font-family:Arial,sans-serif;color:$textColor;'>$chatHistory</body></html>"
        scrollToBottom()
    }
    
    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            chatPane.caretPosition = chatPane.document.length
        }
    }
    
    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
