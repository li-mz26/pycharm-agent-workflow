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
import java.awt.Dimension
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
    
    private val chatArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(10)
    }
    
    private val inputField = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(5)
    }
    
    private val canvas = WorkflowCanvas()
    private val workflowListModel = DefaultListModel<String>()
    private val workflowList = JList(workflowListModel)
    
    init {
        // 初始化工作流路径
        initWorkflowPath()
        
        // 左侧面板：对话 + 工作流列表
        val leftPanel = createLeftPanel()
        
        // 右侧面板：可视化画布
        val canvasPanel = createCanvasPanel()
        
        // 分割面板
        val splitter = JBSplitter(false, 0.35f)
        splitter.firstComponent = leftPanel
        splitter.secondComponent = canvasPanel
        
        setContent(splitter)
        
        // 显示欢迎消息
        showWelcomeMessage()
    }
    
    private fun initWorkflowPath() {
        workflowPath = WorkflowDetector.getWorkflowPath(
            project,
            settings.workflowPath,
            settings.autoDetectWorkflows
        )
        
        // 扫描工作流文件
        workflowFiles = WorkflowDetector.scanWorkflowFiles(workflowPath)
        
        // 更新工作流列表
        updateWorkflowList()
    }
    
    private fun updateWorkflowList() {
        workflowListModel.clear()
        workflowFiles.forEach { file ->
            workflowListModel.addElement("📄 ${file.name}.${file.extension}")
        }
        
        if (workflowFiles.isEmpty()) {
            workflowListModel.addElement("(无工作流文件)")
        }
    }
    
    private fun showWelcomeMessage() {
        val pathInfo = if (workflowPath == project.basePath) {
            "项目根目录"
        } else {
            workflowPath
        }
        
        appendMessage("Agent", """
            你好！我是工作流助手。
            
            📁 当前工作流路径: $pathInfo
            📊 发现 ${workflowFiles.size} 个工作流文件
            
            请描述你想要创建的工作流，我会帮你生成 DAG 结构。
            
            例如：
            • "创建一个数据清洗工作流，先读取 CSV，然后过滤空值，最后保存"
            • "帮我做一个带条件分支的审批流程"
            • "生成一个定时备份数据库的任务"
        """.trimIndent())
    }
    
    private fun createLeftPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        
        // 工作流列表面板
        val listPanel = JPanel(BorderLayout())
        listPanel.border = BorderFactory.createTitledBorder("工作流文件 (${workflowFiles.size})")
        
        workflowList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        workflowList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                val selectedIndex = workflowList.selectedIndex
                if (selectedIndex >= 0 && selectedIndex < workflowFiles.size) {
                    loadWorkflowFile(workflowFiles[selectedIndex])
                }
            }
        }
        
        val listScrollPane = JBScrollPane(workflowList)
        listScrollPane.preferredSize = Dimension(200, 150)
        listPanel.add(listScrollPane, BorderLayout.CENTER)
        
        // 刷新按钮
        val refreshButton = JButton("🔄 刷新")
        refreshButton.addActionListener {
            initWorkflowPath()
            listPanel.border = BorderFactory.createTitledBorder("工作流文件 (${workflowFiles.size})")
        }
        listPanel.add(refreshButton, BorderLayout.SOUTH)
        
        // 对话面板
        val chatPanel = createChatPanel()
        
        // 分割左侧面板
        val splitter = JBSplitter(true, 0.3f)
        splitter.firstComponent = listPanel
        splitter.secondComponent = chatPanel
        
        panel.add(splitter, BorderLayout.CENTER)
        return panel
    }
    
    private fun loadWorkflowFile(fileInfo: WorkflowFileInfo) {
        appendMessage("System", "正在加载工作流文件: ${fileInfo.name}")
        
        try {
            val content = java.io.File(fileInfo.path).readText()
            // TODO: 解析工作流文件内容
            appendMessage("Agent", "已加载工作流: ${fileInfo.name}.${fileInfo.extension}")
        } catch (e: Exception) {
            appendMessage("Error", "加载失败: ${e.message}")
        }
    }
    
    private fun createChatPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("对话")
        
        // 聊天记录
        val scrollPane = JBScrollPane(chatArea)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // 输入区域
        val inputPanel = JPanel(BorderLayout())
        inputPanel.border = JBUI.Borders.empty(5)
        
        val scrollInput = JBScrollPane(inputField)
        scrollInput.preferredSize = Dimension(200, 80)
        inputPanel.add(scrollInput, BorderLayout.CENTER)
        
        // 按钮
        val buttonPanel = JPanel()
        val sendButton = JButton("发送").apply {
            addActionListener { onSend() }
        }
        val exportButton = JButton("导出").apply {
            addActionListener { onExport() }
        }
        val validateButton = JButton("验证").apply {
            addActionListener { onValidate() }
        }
        
        buttonPanel.add(sendButton)
        buttonPanel.add(validateButton)
        buttonPanel.add(exportButton)
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
        
        appendMessage("你", text)
        inputField.text = ""
        
        // 生成工作流
        val workflow = agent.generateWorkflow(text, WorkflowContext(currentWorkflow))
        currentWorkflow = workflow
        
        // 更新画布
        canvas.setWorkflow(workflow)
        
        // 回复
        val nodeSummary = workflow.nodes.groupBy { it.type.value }
            .map { "${it.key}: ${it.value.size}个" }
            .joinToString(", ")
        appendMessage("Agent", "已生成工作流 \"${workflow.name}\"\n节点: $nodeSummary\n\n你可以在右侧查看 DAG 结构，或继续对话修改。")
    }
    
    private fun onValidate() {
        val workflow = currentWorkflow
        if (workflow == null) {
            appendMessage("Agent", "还没有工作流，请先描述你的需求。")
            return
        }
        
        val result = agent.validateWorkflow(workflow)
        if (result.isValid) {
            appendMessage("Agent", "✅ 工作流验证通过！")
        } else {
            appendMessage("Agent", "❌ 发现问题:\n${result.errors.joinToString("\n") { "- $it" }}")
        }
    }
    
    private fun onExport() {
        val workflow = currentWorkflow
        if (workflow == null) {
            appendMessage("Agent", "还没有工作流可导出。")
            return
        }
        
        val exporter = WorkflowExporter(workflowPath)
        val path = exporter.export(workflow)
        appendMessage("Agent", "✅ 工作流已导出到:\n$path")
        
        // 刷新工作流列表
        initWorkflowPath()
    }
    
    private fun appendMessage(sender: String, message: String) {
        chatArea.append("[$sender]\n$message\n\n")
        chatArea.caretPosition = chatArea.document.length
    }
}
