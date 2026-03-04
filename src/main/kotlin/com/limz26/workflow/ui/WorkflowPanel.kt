package com.limz26.workflow.ui

import com.google.gson.GsonBuilder
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
import com.limz26.workflow.service.WorkflowService
import com.limz26.workflow.util.WorkflowDetector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
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
    private val workflowService = service<WorkflowService>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var currentWorkflow: Workflow? = null
    private var loadedWorkflows: List<LoadedWorkflow> = emptyList()
    private var selectedWorkflow: LoadedWorkflow? = null
    private val mainSplitter = JBSplitter(false, 0.35f)
    private var isChatCollapsed = true
    private val mcpToggleButton = JButton("启用MCP")
    private val chatToggleButton = JButton("展开对话")

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
    private var nodeConfigSplitter: JBSplitter? = null

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

        mainSplitter.firstComponent = if (isChatCollapsed) JPanel() else leftPanel
        mainSplitter.secondComponent = canvasPanel
        if (isChatCollapsed) {
            mainSplitter.proportion = 0.0f
            mainSplitter.dividerWidth = 1
        }

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
        val customPath = settings.workflowPath.trim()
        loadedWorkflows = if (customPath.isNotEmpty()) {
            WorkflowDetector.detectWorkflowFolders(java.io.File(customPath))
        } else {
            WorkflowDetector.detectWorkflowFolders(project)
        }
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
        return convertToWorkflow(loaded.definition)
    }

    private fun convertToWorkflow(def: WorkflowDefinition): Workflow {
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
                        codeFile = nodeDef.config.codeFile,
                        prompt = nodeDef.config.prompt,
                        agentConfigFile = nodeDef.config.agentConfigFile,
                        promptTemplate = nodeDef.config.promptTemplate,
                        systemPrompt = nodeDef.config.systemPrompt,
                        apiEndpoint = nodeDef.config.apiEndpoint,
                        apiKey = nodeDef.config.apiKey,
                        model = nodeDef.config.model,
                        inputs = nodeDef.config.inputs,
                        outputs = nodeDef.config.outputs
                    )
                )
            },
            edges = def.edges.map { WorkflowEdge(id = it.id, source = it.source, target = it.target, condition = it.condition) },
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

        val selectWorkflowRootBtn = JButton("选择工作流目录")
        selectWorkflowRootBtn.toolTipText = "选择工作流文件夹所在路径"
        selectWorkflowRootBtn.addActionListener { onSelectWorkflowRootPath() }

        val comboPanel = JPanel(BorderLayout(6, 0))
        comboPanel.add(selectWorkflowRootBtn, BorderLayout.WEST)
        workflowCombo.renderer = DefaultListCellRenderer()
        workflowCombo.addActionListener {
            val idx = workflowCombo.selectedIndex
            if (idx >= 0 && idx < loadedWorkflows.size) {
                loadWorkflowFolder(loadedWorkflows[idx])
            }
        }
        comboPanel.add(workflowCombo, BorderLayout.CENTER)
        selectorPanel.add(comboPanel, BorderLayout.CENTER)
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

        val rightEditor = createNodeConfigPanel()
        canvas.setOnNodeSelected { node ->
            updateNodeConfigPanel(rightEditor, node)
        }
        canvas.setOnWorkflowDefinitionChanged { def ->
            currentWorkflow = convertToWorkflow(def)
            selectedWorkflow = selectedWorkflow?.copy(definition = def)
        }

        val canvasWithEditor = JBSplitter(false, 0.72f)
        nodeConfigSplitter = canvasWithEditor
        canvasWithEditor.firstComponent = canvas
        canvasWithEditor.secondComponent = rightEditor
        setNodeConfigPanelVisible(false)

        val splitter = JBSplitter(true, 0.68f)
        splitter.firstComponent = canvasWithEditor
        splitter.secondComponent = consolePanel

        panel.add(topBar, BorderLayout.NORTH)
        panel.add(splitter, BorderLayout.CENTER)
        return panel
    }

    private fun createNodeConfigPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("节点配置")

        val header = JPanel(BorderLayout())
        val titleLabel = JLabel("未选择节点")
        header.add(titleLabel, BorderLayout.WEST)
        panel.add(header, BorderLayout.NORTH)

        val cardLayout = CardLayout()
        val cardPanel = JPanel(cardLayout)
        panel.putClientProperty("titleLabel", titleLabel)
        panel.putClientProperty("cardLayout", cardLayout)
        panel.putClientProperty("cardPanel", cardPanel)

        val emptyPanel = JPanel(BorderLayout()).apply {
            add(JLabel("请选择一个节点进行配置", SwingConstants.CENTER), BorderLayout.CENTER)
        }
        cardPanel.add(emptyPanel, "empty")

        val codeForm = JPanel(BorderLayout())
        val codePathField = JTextField()
        val codeSaveBtn = JButton("保存脚本路径")
        val codeInner = JPanel(BorderLayout(0, 6))
        codeInner.border = JBUI.Borders.empty(8)
        codeInner.add(JLabel("Python脚本路径"), BorderLayout.NORTH)
        codeInner.add(codePathField, BorderLayout.CENTER)
        codeInner.add(codeSaveBtn, BorderLayout.SOUTH)
        codeForm.add(codeInner, BorderLayout.NORTH)
        panel.putClientProperty("codePathField", codePathField)
        panel.putClientProperty("codeSaveBtn", codeSaveBtn)
        cardPanel.add(codeForm, "code")

        val agentForm = JPanel()
        agentForm.layout = BoxLayout(agentForm, BoxLayout.Y_AXIS)
        agentForm.border = JBUI.Borders.empty(8)
        fun addField(label: String, field: JComponent) {
            agentForm.add(JLabel(label)); agentForm.add(field); agentForm.add(Box.createVerticalStrut(6))
        }
        val configPathField = JTextField()
        val apiField = JTextField()
        val keyField = JTextField()
        val modelField = JTextField()
        val systemArea = JTextArea(3, 20).apply { lineWrap = true; wrapStyleWord = true }
        val templateArea = JTextArea(4, 20).apply { lineWrap = true; wrapStyleWord = true }
        addField("配置文件路径", configPathField)
        addField("API Endpoint", apiField)
        addField("API Key", keyField)
        addField("Model", modelField)
        addField("系统提示词", JBScrollPane(systemArea))
        addField("提示词模板", JBScrollPane(templateArea))
        val agentSaveBtn = JButton("保存Agent配置")
        agentForm.add(agentSaveBtn)
        panel.putClientProperty("agentConfigPathField", configPathField)
        panel.putClientProperty("agentApiField", apiField)
        panel.putClientProperty("agentKeyField", keyField)
        panel.putClientProperty("agentModelField", modelField)
        panel.putClientProperty("agentSystemArea", systemArea)
        panel.putClientProperty("agentTemplateArea", templateArea)
        panel.putClientProperty("agentSaveBtn", agentSaveBtn)
        cardPanel.add(JBScrollPane(agentForm), "agent")

        val condForm = JPanel(BorderLayout(0, 8))
        condForm.border = JBUI.Borders.empty(8)
        val rowsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val addRowBtn = JButton("添加条件")
        val condSaveBtn = JButton("保存条件流向")
        val btnPanel = JPanel(GridLayout(1, 2, 6, 0)).apply {
            add(addRowBtn)
            add(condSaveBtn)
        }
        condForm.add(JLabel("条件分支配置"), BorderLayout.NORTH)
        condForm.add(JBScrollPane(rowsPanel), BorderLayout.CENTER)
        condForm.add(btnPanel, BorderLayout.SOUTH)
        panel.putClientProperty("condRowsPanel", rowsPanel)
        panel.putClientProperty("condAddBtn", addRowBtn)
        panel.putClientProperty("condSaveBtn", condSaveBtn)
        cardPanel.add(condForm, "condition")

        panel.add(cardPanel, BorderLayout.CENTER)
        cardLayout.show(cardPanel, "empty")
        return panel
    }

    private fun setNodeConfigPanelVisible(visible: Boolean) {
        val splitter = nodeConfigSplitter ?: return
        splitter.proportion = if (visible) 0.72f else 1.0f
    }


    private fun updateNodeConfigPanel(panel: JPanel, node: NodeDefinition?) {
        val cardLayout = panel.getClientProperty("cardLayout") as? CardLayout ?: return
        val cardPanel = panel.getClientProperty("cardPanel") as? JPanel ?: return
        val titleLabel = panel.getClientProperty("titleLabel") as? JLabel

        if (node == null) {
            titleLabel?.text = "未选择节点"
            cardLayout.show(cardPanel, "empty")
            setNodeConfigPanelVisible(false)
            return
        }

        setNodeConfigPanelVisible(true)
        titleLabel?.text = "${node.name} (${node.type})"
        when (node.type) {
            "code" -> bindCodeForm(panel, node, cardLayout, cardPanel)
            "agent" -> bindAgentForm(panel, node, cardLayout, cardPanel)
            "condition" -> bindConditionForm(panel, node, cardLayout, cardPanel)
            else -> cardLayout.show(cardPanel, "empty")
        }
    }

    private fun bindCodeForm(panel: JPanel, node: NodeDefinition, cardLayout: CardLayout, cardPanel: JPanel) {
        val pathField = panel.getClientProperty("codePathField") as? JTextField ?: return
        val saveBtn = panel.getClientProperty("codeSaveBtn") as? JButton ?: return
        pathField.text = node.config.codeFile ?: "nodes/${node.id}.py"
        saveBtn.actionListeners.forEach { saveBtn.removeActionListener(it) }
        saveBtn.addActionListener {
            updateNodeConfig(node.id) { cfg -> cfg.copy(codeFile = pathField.text.trim()) }
        }
        cardLayout.show(cardPanel, "code")
    }

    private fun bindAgentForm(panel: JPanel, node: NodeDefinition, cardLayout: CardLayout, cardPanel: JPanel) {
        val configPathField = panel.getClientProperty("agentConfigPathField") as? JTextField ?: return
        val apiField = panel.getClientProperty("agentApiField") as? JTextField ?: return
        val keyField = panel.getClientProperty("agentKeyField") as? JTextField ?: return
        val modelField = panel.getClientProperty("agentModelField") as? JTextField ?: return
        val systemArea = panel.getClientProperty("agentSystemArea") as? JTextArea ?: return
        val templateArea = panel.getClientProperty("agentTemplateArea") as? JTextArea ?: return
        val saveBtn = panel.getClientProperty("agentSaveBtn") as? JButton ?: return

        val configPath = node.config.agentConfigFile ?: "nodes/${node.id}_config.json"
        configPathField.text = configPath

        val configFromFile = selectedWorkflow?.baseDir?.let { baseDir ->
            runCatching {
                val file = java.io.File(baseDir, configPath)
                if (!file.exists()) null else gson.fromJson(file.readText(), AgentNodeFileConfig::class.java)
            }.getOrNull()
        }

        apiField.text = configFromFile?.apiEndpoint.orEmpty()
        keyField.text = configFromFile?.apiKey.orEmpty()
        modelField.text = configFromFile?.model.orEmpty()
        systemArea.text = configFromFile?.systemPrompt.orEmpty()
        templateArea.text = configFromFile?.promptTemplate.orEmpty()

        saveBtn.actionListeners.forEach { saveBtn.removeActionListener(it) }
        saveBtn.addActionListener {
            val path = configPathField.text.trim().ifEmpty { "nodes/${node.id}_config.json" }
            val payload = AgentNodeFileConfig(
                apiEndpoint = apiField.text.trim(),
                apiKey = keyField.text.trim(),
                model = modelField.text.trim(),
                systemPrompt = systemArea.text,
                promptTemplate = templateArea.text
            )

            selectedWorkflow?.baseDir?.let { baseDir ->
                val file = java.io.File(baseDir, path)
                file.parentFile?.mkdirs()
                file.writeText(gson.toJson(payload))
            }

            updateNodeConfig(node.id) { cfg ->
                cfg.copy(
                    agentConfigFile = path,
                    prompt = null,
                    promptTemplate = null,
                    systemPrompt = null,
                    apiEndpoint = null,
                    apiKey = null,
                    model = null
                )
            }
        }
        cardLayout.show(cardPanel, "agent")
    }

    private data class AgentNodeFileConfig(
        val apiEndpoint: String? = null,
        val apiKey: String? = null,
        val model: String? = null,
        val systemPrompt: String? = null,
        val promptTemplate: String? = null
    )

    private fun bindConditionForm(panel: JPanel, node: NodeDefinition, cardLayout: CardLayout, cardPanel: JPanel) {
        val rowsPanel = panel.getClientProperty("condRowsPanel") as? JPanel ?: return
        val addBtn = panel.getClientProperty("condAddBtn") as? JButton ?: return
        val saveBtn = panel.getClientProperty("condSaveBtn") as? JButton ?: return

        rowsPanel.removeAll()
        val edges = currentWorkflow?.edges?.filter { it.source == node.id }.orEmpty()
        if (edges.isEmpty()) {
            addConditionRow(rowsPanel, "", "")
        } else {
            edges.forEach { edge ->
                addConditionRow(rowsPanel, edge.condition.orEmpty(), edge.target)
            }
        }
        rowsPanel.revalidate()
        rowsPanel.repaint()

        addBtn.actionListeners.forEach { addBtn.removeActionListener(it) }
        addBtn.addActionListener {
            addConditionRow(rowsPanel, "", "")
            rowsPanel.revalidate()
            rowsPanel.repaint()
        }

        saveBtn.actionListeners.forEach { saveBtn.removeActionListener(it) }
        saveBtn.addActionListener {
            val parsed = rowsPanel.components.mapNotNull { comp ->
                val row = comp as? JPanel ?: return@mapNotNull null
                val condField = row.getClientProperty("condField") as? JTextField ?: return@mapNotNull null
                val targetField = row.getClientProperty("targetField") as? JTextField ?: return@mapNotNull null
                val target = targetField.text.trim()
                if (target.isBlank()) return@mapNotNull null
                condField.text.trim() to target
            }
            val wf = currentWorkflow ?: return@addActionListener
            val keep = wf.edges.filterNot { it.source == node.id }
            val newEdges = parsed.map { (cond, target) ->
                WorkflowEdge(source = node.id, target = target, condition = cond.ifBlank { null })
            }
            currentWorkflow = wf.copy(edges = keep + newEdges)
            canvas.setWorkflow(currentWorkflow!!, autoLayout = false)
        }
        cardLayout.show(cardPanel, "condition")
    }

    private fun addConditionRow(rowsPanel: JPanel, condition: String, target: String) {
        val row = JPanel(GridLayout(1, 3, 6, 0))
        val rowHeight = 34
        row.preferredSize = Dimension(0, rowHeight)
        row.minimumSize = Dimension(0, rowHeight)
        row.maximumSize = Dimension(Int.MAX_VALUE, rowHeight)
        row.border = JBUI.Borders.empty(0, 0, 6, 0)
        val conditionField = JTextField(condition)
        val targetField = JTextField(target)
        val removeBtn = JButton("删除")
        removeBtn.preferredSize = Dimension(72, rowHeight)
        row.putClientProperty("condField", conditionField)
        row.putClientProperty("targetField", targetField)
        removeBtn.addActionListener {
            rowsPanel.remove(row)
            rowsPanel.revalidate()
            rowsPanel.repaint()
        }
        row.add(conditionField)
        row.add(targetField)
        row.add(removeBtn)
        rowsPanel.add(row)
    }

    private fun updateNodeConfig(nodeId: String, updater: (NodeConfig) -> NodeConfig) {
        val wf = currentWorkflow ?: return
        currentWorkflow = wf.copy(nodes = wf.nodes.map { n -> if (n.id == nodeId) n.copy(config = updater(n.config)) else n })
        canvas.setWorkflow(currentWorkflow!!, autoLayout = false)
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
        try {
            Json.parseToJsonElement(rawInput).jsonObject
        } catch (e: Exception) {
            testOutputArea.text = "测试输入 JSON 格式错误：${e.message}"
            appendWorkflowLog("测试运行失败：输入 JSON 格式错误")
            return
        }

        val workflowDirPath = selectedWorkflow?.baseDir?.absolutePath ?: run {
            val projectBase = project.basePath ?: "."
            WorkflowExporter(projectBase).export(workflow)
        }

        val result = workflowService.runWorkflow(workflowDirPath)
        testOutputArea.text = result.logs.joinToString("\n")
        if (result.success) {
            appendWorkflowLog("测试运行完成：${workflow.name}")
        } else {
            appendWorkflowLog("测试运行失败：${result.validationErrors.joinToString("; ")}")
        }
    }

    private fun onSelectWorkflowRootPath() {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择工作流文件夹所在目录"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            selectedFile = when {
                settings.workflowPath.isNotBlank() -> java.io.File(settings.workflowPath)
                !project.basePath.isNullOrBlank() -> java.io.File(project.basePath!!)
                else -> null
            }
        }

        val result = chooser.showOpenDialog(this)
        if (result != JFileChooser.APPROVE_OPTION) return
        val dir = chooser.selectedFile ?: return
        if (!dir.exists() || !dir.isDirectory) {
            Messages.showErrorDialog(project, "请选择有效目录", "工作流目录选择失败")
            return
        }

        settings.workflowPath = dir.absolutePath
        appendWorkflowLog("工作流根目录已切换: ${dir.absolutePath}")
        initWorkflowFolders()
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
