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
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.*
import javax.swing.border.MatteBorder

/**
 * 主工作流面板 - 对话 + 可视化
 */
class WorkflowPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {
    
    private val agent = WorkflowAgent()
    private val settings = service<AppSettings>()
    private var currentWorkflow: Workflow? = null
    private var loadedWorkflows: List<LoadedWorkflow> = emptyList()
    private var selectedWorkflow: LoadedWorkflow? = null
    
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
    
    private val canvas = WorkflowCanvas(project)
    private val folderListModel = DefaultListModel<String>()
    private val folderList = JList(folderListModel)
    
    init {
        initWorkflowFolders()
        val leftPanel = createLeftPanel()
        val canvasPanel = createCanvasPanel()
        
        val splitter = JBSplitter(false, 0.35f)
        splitter.firstComponent = leftPanel
        splitter.secondComponent = canvasPanel
        
        setContent(splitter)
        showWelcomeMessage()
    }
    
    private fun initWorkflowFolders() {
        loadedWorkflows = WorkflowDetector.detectWorkflowFolders(project)
        updateFolderList()
    }
    
    private fun updateFolderList() {
        folderListModel.clear()
        loadedWorkflows.forEach { workflow ->
            folderListModel.addElement("✓ ${workflow.name}")
        }
        if (loadedWorkflows.isEmpty()) {
            folderListModel.addElement("(无工作流文件夹)")
        }
    }
    
    private fun showWelcomeMessage() {
        val folderCount = loadedWorkflows.size
        val apiStatus = if (settings.apiKey.isBlank()) "未配置" else "已配置"
        
        chatArea.append("═══ Agent Workflow 助手 ═══\n\n")
        chatArea.append("用自然语言描述你的工作流，我来帮你生成\n\n")
        chatArea.append("📁 工作流文件夹: $folderCount 个\n")
        chatArea.append("🔑 API 状态: $apiStatus\n\n")
        chatArea.append("─── 示例 ───\n")
        chatArea.append("• 创建一个数据清洗工作流\n")
        chatArea.append("• 帮我做一个审批流程\n\n")
        chatArea.append("═══════════════════════\n\n")
    }
    
    private fun createLeftPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        
        // 工作流文件夹列表
        val listPanel = JPanel(BorderLayout())
        listPanel.border = BorderFactory.createTitledBorder("工作流 (${loadedWorkflows.size})")
        
        folderList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        folderList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                val idx = folderList.selectedIndex
                if (idx >= 0 && idx < loadedWorkflows.size) {
                    loadWorkflowFolder(loadedWorkflows[idx])
                }
            }
        }
        
        listPanel.add(JBScrollPane(folderList), BorderLayout.CENTER)
        listPanel.add(JButton("刷新").apply {
            addActionListener { initWorkflowFolders() }
        }, BorderLayout.SOUTH)
        
        // 对话面板 - 带分隔线
        val chatPanel = createChatPanel()
        
        // 分割面板
        val splitter = JBSplitter(true, 0.3f)
        splitter.firstComponent = listPanel
        splitter.secondComponent = chatPanel
        
        panel.add(splitter, BorderLayout.CENTER)
        return panel
    }
    
    private fun loadWorkflowFolder(workflow: LoadedWorkflow) {
        selectedWorkflow = workflow
        addSystemMessage("加载工作流: ${workflow.name}")
        
        try {
            // 转换为 Workflow 对象并显示
            currentWorkflow = convertToWorkflow(workflow)
            canvas.setWorkflow(currentWorkflow!!)
            addAgentMessage("已加载: ${workflow.name}\n节点数: ${workflow.definition.nodes.size}")
        } catch (e: Exception) {
            addErrorMessage("加载失败: ${e.message}")
        }
    }
    
    private fun convertToWorkflow(loaded: LoadedWorkflow): Workflow {
        val def = loaded.definition
        
        return Workflow(
            name = def.name,
            description = def.description,
            nodes = def.nodes.map { nodeDef ->
                WorkflowNode(
                    id = nodeDef.id,
                    type = NodeType.valueOf(nodeDef.type.uppercase()),
                    name = nodeDef.name,
                    position = Position(
                        x = nodeDef.position.x,
                        y = nodeDef.position.y
                    ),
                    config = NodeConfig(
                        code = nodeDef.config.code,
                        prompt = nodeDef.config.prompt,
                        model = nodeDef.config.model,
                        inputs = nodeDef.config.inputs,
                        outputs = nodeDef.config.outputs
                    )
                )
            },
            edges = def.edges.map { edgeDef ->
                WorkflowEdge(
                    source = edgeDef.source,
                    target = edgeDef.target
                )
            },
            variables = def.variables.mapValues { Variable(it.key, it.value.type) }
        )
    }
    
    private fun createChatPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("对话")
        
        // 聊天区域
        panel.add(JBScrollPane(chatArea), BorderLayout.CENTER)
        
        // 分隔线
        val separator = JSeparator(JSeparator.HORIZONTAL)
        separator.foreground = UIUtil.getLabelDisabledForeground()
        separator.preferredSize = Dimension(separator.preferredSize.width, 2)
        panel.add(separator, BorderLayout.SOUTH)
        
        // 输入区域面板
        val inputWrapper = JPanel(BorderLayout())
        inputWrapper.border = JBUI.Borders.empty(5)
        
        // 输入框
        inputWrapper.add(JBScrollPane(inputField), BorderLayout.CENTER)
        
        // 按钮面板
        val buttonPanel = JPanel()
        buttonPanel.add(JButton("发送").apply { addActionListener { onSend() } })
        buttonPanel.add(JButton("验证").apply { addActionListener { onValidate() } })
        buttonPanel.add(JButton("导出").apply { addActionListener { onExport() } })
        inputWrapper.add(buttonPanel, BorderLayout.SOUTH)
        
        // 将输入区域放在分隔线下方
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(inputWrapper, BorderLayout.CENTER)
        
        // 使用分割面板实现上下布局
        val fullPanel = JPanel(BorderLayout())
        fullPanel.add(panel, BorderLayout.CENTER)
        fullPanel.add(bottomPanel, BorderLayout.SOUTH)
        
        // 重新组织：聊天记录在上，分隔线，输入在下
        val mainPanel = JPanel(BorderLayout())
        
        // 上部：聊天记录（带标题边框）
        val chatAreaPanel = JPanel(BorderLayout())
        chatAreaPanel.border = BorderFactory.createTitledBorder("对话历史")
        chatAreaPanel.add(JBScrollPane(chatArea), BorderLayout.CENTER)
        
        // 下部：输入区域
        val inputPanel = JPanel(BorderLayout())
        inputPanel.border = JBUI.Borders.empty(5)
        
        // 添加分隔线到输入面板顶部
        val topBorder = MatteBorder(2, 0, 0, 0, UIUtil.getLabelDisabledForeground())
        inputPanel.border = BorderFactory.createCompoundBorder(
            topBorder,
            JBUI.Borders.empty(10)
        )
        
        inputPanel.add(JBScrollPane(inputField), BorderLayout.CENTER)
        
        val btnPanel = JPanel()
        btnPanel.add(JButton("发送").apply { addActionListener { onSend() } })
        btnPanel.add(JButton("验证").apply { addActionListener { onValidate() } })
        btnPanel.add(JButton("导出").apply { addActionListener { onExport() } })
        inputPanel.add(btnPanel, BorderLayout.SOUTH)
        
        // 分割面板
        val splitter = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        splitter.topComponent = chatAreaPanel
        splitter.bottomComponent = inputPanel
        splitter.resizeWeight = 0.75  // 聊天记录占 75%
        splitter.dividerSize = 2
        
        mainPanel.add(splitter, BorderLayout.CENTER)
        
        return mainPanel
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
        val path = selectedWorkflow?.baseDir?.absolutePath ?: project.basePath ?: "."
        val exporter = WorkflowExporter(path)
        val exportedPath = exporter.export(workflow)
        addAgentMessage("已导出到: $exportedPath")
        initWorkflowFolders()
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
