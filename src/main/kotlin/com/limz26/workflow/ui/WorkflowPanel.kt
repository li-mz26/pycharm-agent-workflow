package com.limz26.workflow.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.limz26.workflow.agent.WorkflowAgent
import com.limz26.workflow.agent.WorkflowContext
import com.limz26.workflow.model.*
import com.limz26.workflow.settings.AppSettings
import com.limz26.workflow.util.WorkflowDetector
import com.limz26.workflow.util.WorkflowFileInfo
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

/**
 * 主工作流面板 - 对话 + 可视化
 */
class WorkflowPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {
    
    private val agent = WorkflowAgent()
    private val settings = service<AppSettings>()
    private var currentWorkflow: Workflow? = null
    private var workflowPath: String = ""
    private var workflowFiles: List<WorkflowFileInfo> = emptyList()
    
    // 使用 IDE 主题背景色
    private val chatArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(10)
        font = Font("Monospaced", Font.PLAIN, 13)
        background = UIUtil.getPanelBackground()
        foreground = UIUtil.getLabelForeground()
    }
    
    private val inputField = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        font = font.deriveFont(14f)
        background = UIUtil.getTextFieldBackground()
        foreground = UIUtil.getTextFieldForeground()
    }
    
    private val canvas = WorkflowCanvas()
    private val workflowListModel = DefaultListModel<String>()
    private val workflowList = JList(workflowListModel)
    
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
        // 直接使用项目根目录作为工作流路径
        workflowPath = project.basePath ?: "."
        
        // 如果用户配置了自定义路径，则使用自定义路径
        if (settings.workflowPath.isNotEmpty()) {
            val customDir = java.io.File(settings.workflowPath)
            if (customDir.exists() && customDir.isDirectory) {
                workflowPath = settings.workflowPath
            }
        }
        
        // 扫描工作流文件（包括项目根目录和检测到的子目录）
        workflowFiles = scanAllWorkflowFiles()
        updateWorkflowList()
    }
    
    private fun scanAllWorkflowFiles(): List<WorkflowFileInfo> {
        val allFiles = mutableListOf<WorkflowFileInfo>()
        
        // 1. 扫描项目根目录
        allFiles.addAll(WorkflowDetector.scanWorkflowFiles(workflowPath))
        
        // 2. 如果启用了自动检测，也扫描检测到的子目录
        if (settings.autoDetectWorkflows) {
            val detectedDirs = WorkflowDetector.detectWorkflowDirs(project)
            detectedDirs.forEach { dir ->
                if (dir != workflowPath) { // 避免重复扫描根目录
                    allFiles.addAll(WorkflowDetector.scanWorkflowFiles(dir))
                }
            }
        }
        
        return allFiles.sortedBy { it.name }
    }
    
    private fun updateWorkflowList() {
        workflowListModel.clear()
        workflowFiles.forEach { workflowListModel.addElement(it.name + "." + it.extension) }
        if (workflowFiles.isEmpty()) workflowListModel.addElement("(无工作流文件)")
    }
    
    private fun showWelcomeMessage() {
        val pathInfo = if (workflowPath == project.basePath) "项目根目录" else workflowPath
        val apiStatus = if (settings.apiKey.isBlank()) "未配置 API Key" else "已配置 (${settings.model})"
        
        chatArea.append("═══ Agent Workflow 助手 ═══\n\n")
        chatArea.append("用自然语言描述你的工作流，我来帮你生成\n\n")
        chatArea.append("📁 工作流路径: $pathInfo\n")
        chatArea.append("📊 文件数量: ${workflowFiles.size} 个\n")
        chatArea.append("🔑 API 状态: $apiStatus\n\n")
        chatArea.append("─── 示例 ───\n")
        chatArea.append("• 创建一个数据清洗工作流\n")
        chatArea.append("• 帮我做一个审批流程\n\n")
        chatArea.append("═══════════════════════\n\n")
    }
    
    private fun createLeftPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        
        // 工作流列表
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
        
        // 对话面板
        val chatPanel = createChatPanel()
        
        // 分割面板
        val splitter = JBSplitter(true, 0.3f)
        splitter.firstComponent = listPanel
        splitter.secondComponent = chatPanel
        
        panel.add(splitter, BorderLayout.CENTER)
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
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("对话")
        
        panel.add(JBScrollPane(chatArea), BorderLayout.CENTER)
        
        // 输入区域
        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(JBScrollPane(inputField), BorderLayout.CENTER)
        
        val buttonPanel = JPanel()
        buttonPanel.add(JButton("发送").apply { addActionListener { onSend() } })
        buttonPanel.add(JButton("验证").apply { addActionListener { onValidate() } })
        buttonPanel.add(JButton("导出").apply { addActionListener { onExport() } })
        inputPanel.add(buttonPanel, BorderLayout.SOUTH)
        
        panel.add(inputPanel, BorderLayout.SOUTH)
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
        chatArea.append("\n【你】\n$message\n")
        scrollToBottom()
    }
    
    private fun addAgentMessage(message: String) {
        chatArea.append("\n【Agent】\n$message\n")
        scrollToBottom()
    }
    
    private fun addSystemMessage(message: String) {
        chatArea.append("\n  [$message]\n")
        scrollToBottom()
    }
    
    private fun removeLastSystemMessage() {
        val text = chatArea.text
        val lastIndex = text.lastIndexOf("\n  [")
        if (lastIndex >= 0) {
            val endIndex = text.indexOf("]\n", lastIndex) + 2
            chatArea.text = text.substring(0, lastIndex) + text.substring(endIndex)
        }
    }
    
    private fun addErrorMessage(message: String) {
        chatArea.append("\n【错误】\n$message\n")
        scrollToBottom()
    }
    
    private fun scrollToBottom() {
        chatArea.caretPosition = chatArea.document.length
    }
}
