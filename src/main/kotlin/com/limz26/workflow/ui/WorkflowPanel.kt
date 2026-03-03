package com.limz26.workflow.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.limz26.workflow.agent.WorkflowAgent
import com.limz26.workflow.agent.WorkflowContext
import com.limz26.workflow.mcp.WorkflowMcpService
import com.limz26.workflow.model.*
import com.limz26.workflow.settings.AppSettings
import com.limz26.workflow.util.WorkflowDetector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.time.LocalTime
import javax.swing.*
import javax.swing.border.MatteBorder

/**
 * 主工作流面板 - 对话 + 可视化
 */
class WorkflowPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {

    private val agent = WorkflowAgent()
    private val settings = service<AppSettings>()
    private val mcpService = service<WorkflowMcpService>()
    private var currentWorkflow: Workflow? = null
    private var loadedWorkflows: List<LoadedWorkflow> = emptyList()
    private var selectedWorkflow: LoadedWorkflow? = null
    private val mainSplitter = JBSplitter(false, 0.35f)
    private var isChatCollapsed = false
    private val mcpToggleButton = JButton("启用MCP")
    private val chatToggleButton = JButton("隐藏对话")

    private val chatArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(10)
        font = Font("Microsoft YaHei", Font.BOLD, 14)
        background = UIUtil.getPanelBackground()
        foreground = UIUtil.getLabelForeground()
    }

    private val inputField = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        font = Font("Microsoft YaHei", Font.BOLD, 14)
        background = UIUtil.getTextFieldBackground()
        foreground = UIUtil.getTextFieldForeground()
    }

    private val canvas = WorkflowCanvas(project)
    private val workflowComboModel = DefaultComboBoxModel<String>()
    private val workflowCombo = JComboBox(workflowComboModel)
    private val consoleWorkflowLabel = JLabel("workflow")

    // 可视化底部 console 面板
    private val testInputArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        font = Font("Monospaced", Font.PLAIN, 12)
    }

    private val testOutputArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        font = Font("Monospaced", Font.PLAIN, 12)
        background = UIUtil.getPanelBackground()
        foreground = UIUtil.getLabelForeground()
    }

    private val workflowLogArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        font = Font("Monospaced", Font.PLAIN, 12)
        background = UIUtil.getPanelBackground()
        foreground = UIUtil.getLabelForeground()
    }

    init {
        initWorkflowFolders()
        val leftPanel = createLeftPanel()
        val canvasPanel = createCanvasPanel()

        mainSplitter.firstComponent = leftPanel
        mainSplitter.secondComponent = canvasPanel

        setContent(mainSplitter)
        showWelcomeMessage()
        syncMcpButtonText()
        if (settings.mcpServerEnabled) {
            try {
                mcpService.startServer(settings.mcpServerPort)
                appendWorkflowLog("MCP 服务已启动: http://127.0.0.1:${settings.mcpServerPort}/mcp (transport=streamable_http)")
            } catch (e: Throwable) {
                settings.mcpServerEnabled = false
                appendWorkflowLog("MCP 服务启动失败: ${e.message}")
                syncMcpButtonText()
            }
        }
    }

    private fun initWorkflowFolders() {
        loadedWorkflows = WorkflowDetector.detectWorkflowFolders(project)
        updateWorkflowCombo()
    }

    private fun updateWorkflowCombo() {
        workflowComboModel.removeAllElements()
        loadedWorkflows.forEach { wf -> workflowComboModel.addElement(wf.name) }

        if (loadedWorkflows.isEmpty()) {
            workflowComboModel.addElement("(无工作流)")
            workflowCombo.selectedIndex = 0
            workflowCombo.isEnabled = false
        } else {
            workflowCombo.isEnabled = true
            val currentName = selectedWorkflow?.name
            val index = loadedWorkflows.indexOfFirst { it.name == currentName }
            workflowCombo.selectedIndex = if (index >= 0) index else 0
            if (selectedWorkflow == null && loadedWorkflows.isNotEmpty()) {
                loadWorkflowFolder(loadedWorkflows.first())
            }
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
        return createChatPanel()
    }

    private fun loadWorkflowFolder(workflow: LoadedWorkflow) {
        selectedWorkflow = workflow
        addSystemMessage("加载工作流: ${workflow.name}")
        appendWorkflowLog("加载工作流: ${workflow.name}")

        try {
            currentWorkflow = convertToWorkflow(workflow)
            updateConsoleWorkflowTitle(currentWorkflow?.name)
            canvas.setWorkflow(workflow)
            addAgentMessage("已加载: ${workflow.name}\n节点数: ${workflow.definition.nodes.size}")
            appendWorkflowLog("加载完成: ${workflow.name}, 节点数=${workflow.definition.nodes.size}")
            testInputArea.text = buildWorkflowTestInput(currentWorkflow!!)
            testOutputArea.text = "已加载工作流，可在此查看执行输出或验证结果。"
        } catch (e: Exception) {
            addErrorMessage("加载失败: ${e.message}")
            appendWorkflowLog("加载失败: ${e.message}")
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
                    position = Position(nodeDef.position.x, nodeDef.position.y),
                    config = NodeConfig(
                        code = nodeDef.config.code,
                        prompt = nodeDef.config.prompt,
                        model = nodeDef.config.model,
                        inputs = nodeDef.config.inputs,
                        outputs = nodeDef.config.outputs
                    )
                )
            },
            edges = def.edges.map { WorkflowEdge(source = it.source, target = it.target) },
            variables = def.variables.mapValues { Variable(it.key, it.value.type) }
        )
    }

    private fun createChatPanel(): JPanel {
        val mainPanel = JPanel(BorderLayout())

        val chatAreaPanel = JPanel(BorderLayout())
        chatAreaPanel.border = BorderFactory.createTitledBorder("对话历史")
        chatAreaPanel.add(JBScrollPane(chatArea), BorderLayout.CENTER)

        val inputPanel = JPanel(BorderLayout())
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

        val splitter = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        splitter.topComponent = chatAreaPanel
        splitter.bottomComponent = inputPanel
        splitter.resizeWeight = 0.75
        splitter.dividerSize = 2

        mainPanel.add(splitter, BorderLayout.CENTER)
        return mainPanel
    }

    private fun createCanvasPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("工作流可视化")

        val topBar = JPanel(BorderLayout())
        topBar.border = JBUI.Borders.empty(4)
        val selectorPanel = JPanel(BorderLayout(6, 0))
        selectorPanel.add(JLabel("工作流"), BorderLayout.WEST)
        workflowCombo.renderer = DefaultListCellRenderer()
        workflowCombo.addActionListener {
            val idx = workflowCombo.selectedIndex
            if (idx >= 0 && idx < loadedWorkflows.size) {
                loadWorkflowFolder(loadedWorkflows[idx])
            }
        }
        selectorPanel.add(workflowCombo, BorderLayout.CENTER)
        topBar.add(selectorPanel, BorderLayout.CENTER)

        val rightActions = JPanel(BorderLayout(6, 0))
        rightActions.add(JButton("刷新").apply { addActionListener { initWorkflowFolders() } }, BorderLayout.WEST)

        val togglePanel = JPanel(BorderLayout(6, 0))
        mcpToggleButton.addActionListener { onToggleMcpServer() }
        chatToggleButton.addActionListener { onToggleChatPanel() }
        togglePanel.add(mcpToggleButton, BorderLayout.WEST)
        togglePanel.add(chatToggleButton, BorderLayout.EAST)

        rightActions.add(togglePanel, BorderLayout.EAST)
        topBar.add(rightActions, BorderLayout.EAST)

        val tabs = JTabbedPane()
        tabs.addTab("输入", JBScrollPane(testInputArea))
        tabs.addTab("输出", JBScrollPane(testOutputArea))
        tabs.addTab("日志", JBScrollPane(workflowLogArea))

        val consolePanel = JPanel(BorderLayout())
        consolePanel.preferredSize = Dimension(200, 220)

        val runHeader = JPanel(BorderLayout())
        runHeader.border = JBUI.Borders.empty(4, 8)
        val runButton = JButton("▶ 运行").apply { addActionListener { onRunWorkflowTest() } }
        runHeader.add(runButton, BorderLayout.WEST)
        consoleWorkflowLabel.font = Font("Dialog", Font.BOLD, 13)
        runHeader.add(consoleWorkflowLabel, BorderLayout.CENTER)

        consolePanel.add(runHeader, BorderLayout.NORTH)
        consolePanel.add(tabs, BorderLayout.CENTER)

        val splitter = JBSplitter(true, 0.68f)
        splitter.firstComponent = canvas
        splitter.secondComponent = consolePanel

        panel.add(topBar, BorderLayout.NORTH)
        panel.add(splitter, BorderLayout.CENTER)
        return panel
    }

    private fun onToggleMcpServer() {
        if (mcpService.isRunning()) {
            mcpService.stopServer()
            settings.mcpServerEnabled = false
            appendWorkflowLog("MCP 服务已停止")
            syncMcpButtonText()
            return
        }

        val input = Messages.showInputDialog(
            project,
            "请输入 MCP 服务端口（1-65535）",
            "启用 MCP 服务",
            Messages.getQuestionIcon(),
            settings.mcpServerPort.toString(),
            null
        ) ?: return

        val port = input.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Messages.showErrorDialog(project, "端口号无效：$input", "MCP 启动失败")
            return
        }

        try {
            settings.mcpServerPort = port
            settings.mcpServerEnabled = true
            mcpService.startServer(port)
            appendWorkflowLog("MCP 服务已启动: http://127.0.0.1:$port/mcp (transport=streamable_http)")
            syncMcpButtonText()
        } catch (e: Throwable) {
            Messages.showErrorDialog(project, "启动失败: ${e.message}", "MCP 启动失败")
            appendWorkflowLog("MCP 服务启动失败: ${e.message}")
            settings.mcpServerEnabled = false
            syncMcpButtonText()
        }
    }

    private fun syncMcpButtonText() {
        mcpToggleButton.text = if (mcpService.isRunning()) "关闭MCP" else "启用MCP"
    }

    private fun onToggleChatPanel() {
        isChatCollapsed = !isChatCollapsed
        if (isChatCollapsed) {
            mainSplitter.firstComponent = JPanel()
            mainSplitter.proportion = 0.0f
            mainSplitter.dividerWidth = 1
            chatToggleButton.text = "展开对话"
        } else {
            mainSplitter.firstComponent = createLeftPanel()
            mainSplitter.proportion = 0.35f
            mainSplitter.dividerWidth = 7
            chatToggleButton.text = "隐藏对话"
        }
        mainSplitter.revalidate()
        mainSplitter.repaint()
    }

    private fun onSend() {
        val text = inputField.text.trim()
        if (text.isEmpty()) return

        addUserMessage(text)
        inputField.text = ""
        addSystemMessage("思考中...")
        appendWorkflowLog("收到对话请求: ${text.take(80)}")

        object : SwingWorker<WorkflowAgent.AgentResponse, Void>() {
            override fun doInBackground(): WorkflowAgent.AgentResponse {
                return agent.talk(text, WorkflowContext(currentWorkflow))
            }

            override fun done() {
                try {
                    val response = get()
                    removeLastSystemMessage()
                    addAgentMessage(response.reply)

                    response.workflow?.let { workflow ->
                        currentWorkflow = workflow
                        updateConsoleWorkflowTitle(workflow.name)
                        canvas.setWorkflow(workflow)
                        appendWorkflowLog("工作流更新: ${workflow.name}, 节点数=${workflow.nodes.size}")
                        testInputArea.text = buildWorkflowTestInput(workflow)
                        testOutputArea.text = "工作流已更新，等待执行测试。"
                    }
                } catch (e: Exception) {
                    removeLastSystemMessage()
                    addErrorMessage("错误: ${e.message}")
                    appendWorkflowLog("对话异常: ${e.message}")
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
            appendWorkflowLog("验证通过: ${workflow.name}")
            testOutputArea.text = "验证通过：${workflow.name}"
        } else {
            val errorText = result.errors.joinToString(", ")
            addErrorMessage("问题: $errorText")
            appendWorkflowLog("验证失败: $errorText")
            testOutputArea.text = "验证失败：$errorText"
        }
    }

    private fun onExport() {
        val workflow = currentWorkflow ?: return addAgentMessage("没有可导出的工作流")
        val path = selectedWorkflow?.baseDir?.absolutePath ?: project.basePath ?: "."
        val exporter = WorkflowExporter(path)
        val exportedPath = exporter.export(workflow)
        addAgentMessage("已导出到: $exportedPath")
        appendWorkflowLog("导出工作流: ${workflow.name} -> $exportedPath")
        testOutputArea.text = "导出成功：$exportedPath"
        initWorkflowFolders()
    }

    private fun onRunWorkflowTest() {
        val workflow = currentWorkflow
        if (workflow == null) {
            addAgentMessage("请先加载或生成一个工作流")
            return
        }

        val rawInput = testInputArea.text.trim().ifEmpty { "{}" }
        val parsedInput = try {
            Json.parseToJsonElement(rawInput).jsonObject
        } catch (e: Exception) {
            testOutputArea.text = "测试输入 JSON 格式错误：${e.message}"
            appendWorkflowLog("测试运行失败：输入 JSON 格式错误")
            return
        }

        val missingVars = workflow.variables.keys.filterNot { parsedInput.containsKey(it) }
        val lines = mutableListOf<String>()
        lines += "工作流：${workflow.name}"
        lines += "节点数：${workflow.nodes.size}，连线数：${workflow.edges.size}"
        lines += "输入字段：${parsedInput.keys.joinToString(", ").ifBlank { "(空)" }}"

        if (missingVars.isNotEmpty()) {
            lines += "缺失变量：${missingVars.joinToString(", ")}"
        } else {
            lines += "变量检查：通过"
        }

        val orderedNodeNames = workflow.nodes.joinToString(" -> ") { it.name }
        lines += "执行预览：$orderedNodeNames"
        lines += "说明：当前为本地模拟测试，未真正执行 code/agent 节点。"

        val output = lines.joinToString("\n")
        testOutputArea.text = output
        appendWorkflowLog("测试运行完成：${workflow.name}")
    }

    private fun buildWorkflowTestInput(workflow: Workflow): String {
        if (workflow.variables.isEmpty()) {
            return "{}"
        }

        val content = workflow.variables.entries.joinToString(",\n") { (name, variable) ->
            val sampleValue = when (variable.type.lowercase()) {
                "string" -> "\"sample_${name}\""
                "int", "integer", "number" -> "0"
                "bool", "boolean" -> "false"
                "list", "array" -> "[]"
                "object", "map" -> "{}"
                else -> "null"
            }
            "  \"${name}\": ${sampleValue}"
        }

        return "{\n$content\n}"
    }

    private fun updateConsoleWorkflowTitle(name: String?) {
        consoleWorkflowLabel.text = name ?: "workflow"
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
            if (endIndex >= 2) {
                chatArea.text = text.substring(0, lastIndex) + text.substring(endIndex)
            }
        }
    }

    private fun addErrorMessage(message: String) {
        chatArea.append("\n【错误】\n$message\n")
        scrollToBottom()
    }

    private fun scrollToBottom() {
        chatArea.caretPosition = chatArea.document.length
    }

    private fun appendWorkflowLog(message: String) {
        workflowLogArea.append("[${LocalTime.now().withNano(0)}] $message\n")
        workflowLogArea.caretPosition = workflowLogArea.document.length
    }
}
