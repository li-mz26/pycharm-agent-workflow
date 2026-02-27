package com.limz26.workflow.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.limz26.workflow.model.LoadedWorkflow
import com.limz26.workflow.model.NodeDefinition
import com.limz26.workflow.model.NodeFiles
import com.limz26.workflow.model.WorkflowLoader
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.*
import javax.swing.border.MatteBorder

/**
 * 工作流预览面板 - 从文件夹加载并可视化，支持调试运行
 */
class WorkflowPreviewPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {
    
    private val loader = WorkflowLoader()
    private val workflowListModel = DefaultListModel<LoadedWorkflow>()
    private val workflowList = JBList(workflowListModel)
    private val canvas = WorkflowCanvas(project)
    
    // 控制台组件
    private val inputArea = JBTextArea(5, 40).apply {
        font = font.deriveFont(12f)
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(5)
    }
    
    private val outputArea = JTextArea().apply {
        isEditable = false
        font = font.deriveFont(12f)
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(5)
        background = UIUtil.getPanelBackground()
    }
    
    private val runButton = JButton("▶ 运行").apply {
        background = Color(76, 175, 80)
        foreground = Color.WHITE
        isOpaque = true
    }
    
    private val stopButton = JButton("⏹ 停止").apply {
        isEnabled = false
    }
    
    private var currentWorkflow: LoadedWorkflow? = null
    private var isRunning = false
    
    init {
        workflowList.cellRenderer = WorkflowListCellRenderer()
        workflowList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        workflowList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                val selected = workflowList.selectedValue
                if (selected != null) {
                    loadWorkflow(selected)
                }
            }
        }
        
        // 设置画布双击回调
        setupCanvasDoubleClick()
        
        // 创建主分割面板（左侧列表 + 右侧画布和控制台）
        val splitter = JBSplitter(false, 0.25f)
        splitter.firstComponent = createListPanel()
        splitter.secondComponent = createRightPanel()
        
        setContent(splitter)
        
        // 自动扫描项目中的工作流
        scanProjectWorkflows()
        
        // 设置运行按钮事件
        runButton.addActionListener { runWorkflow() }
        stopButton.addActionListener { stopWorkflow() }
    }
    
    /**
     * 设置画布双击打开文件
     */
    private fun setupCanvasDoubleClick() {
        // 画布的双击功能已在 WorkflowCanvas 中实现
        // 这里只需要确保 WorkflowCanvas 接收到 project 参数
    }
    
    /**
     * 加载指定工作流
     */
    fun loadWorkflow(workflow: LoadedWorkflow) {
        currentWorkflow = workflow
        canvas.setWorkflow(workflow)
        
        // 清空输出
        outputArea.text = ""
        appendOutput("已加载工作流: ${workflow.name}\n")
        appendOutput("节点数: ${workflow.definition.nodes.size}\n")
        appendOutput("边数: ${workflow.definition.edges.size}\n")
        appendOutput("-".repeat(50) + "\n")
    }
    
    /**
     * 刷新工作流列表
     */
    fun refresh() {
        workflowListModel.clear()
        scanProjectWorkflows()
    }
    
    private fun createListPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("工作流列表")
        
        val scrollPane = JBScrollPane(workflowList)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        val buttonPanel = JPanel()
        val refreshButton = JButton("🔄 刷新").apply {
            addActionListener { refresh() }
        }
        val openButton = JButton("📁 打开文件夹...").apply {
            addActionListener { showOpenDialog() }
        }
        buttonPanel.add(refreshButton)
        buttonPanel.add(openButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)
        
        return panel
    }
    
    private fun createRightPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        
        // 上半部分：画布（占 60%）
        val canvasPanel = JPanel(BorderLayout())
        canvasPanel.border = BorderFactory.createTitledBorder("工作流可视化 (双击节点打开文件)")
        canvasPanel.add(canvas, BorderLayout.CENTER)
        
        // 画布工具栏
        val canvasToolbar = JPanel()
        canvasToolbar.add(runButton)
        canvasToolbar.add(stopButton)
        canvasToolbar.add(JButton("🔍 重置视图").apply {
            addActionListener { canvas.resetView() }
        })
        canvasPanel.add(canvasToolbar, BorderLayout.NORTH)
        
        // 下半部分：控制台（占 40%）
        val consolePanel = createConsolePanel()
        
        // 垂直分割
        val splitter = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        splitter.topComponent = canvasPanel
        splitter.bottomComponent = consolePanel
        splitter.resizeWeight = 0.6
        splitter.dividerSize = 3
        
        panel.add(splitter, BorderLayout.CENTER)
        return panel
    }
    
    private fun createConsolePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("调试控制台")
        
        // 输入区域
        val inputPanel = JPanel(BorderLayout())
        inputPanel.border = BorderFactory.createTitledBorder("测试输入 (JSON)")
        
        // 示例输入按钮
        val exampleButton = JButton("加载示例输入").apply {
            addActionListener { loadExampleInput() }
        }
        val inputToolbar = JPanel()
        inputToolbar.add(exampleButton)
        inputToolbar.add(JButton("清空").apply {
            addActionListener { inputArea.text = "" }
        })
        inputPanel.add(inputToolbar, BorderLayout.NORTH)
        inputPanel.add(JBScrollPane(inputArea), BorderLayout.CENTER)
        
        // 输出区域
        val outputPanel = JPanel(BorderLayout())
        outputPanel.border = BorderFactory.createTitledBorder("测试输出")
        
        val outputToolbar = JPanel()
        outputToolbar.add(JButton("清空输出").apply {
            addActionListener { outputArea.text = "" }
        })
        outputToolbar.add(JButton("复制输出").apply {
            addActionListener { 
                val selection = StringSelection(outputArea.text)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            }
        })
        outputPanel.add(outputToolbar, BorderLayout.NORTH)
        outputPanel.add(JBScrollPane(outputArea), BorderLayout.CENTER)
        
        // 水平分割输入和输出
        val ioSplitter = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        ioSplitter.leftComponent = inputPanel
        ioSplitter.rightComponent = outputPanel
        ioSplitter.resizeWeight = 0.4
        ioSplitter.dividerSize = 3
        
        panel.add(ioSplitter, BorderLayout.CENTER)
        return panel
    }
    
    private fun loadExampleInput() {
        val example = """{
  "raw_data": [1, 2, 3, null, 4, 5],
  "threshold": 3
}"""
        inputArea.text = example
    }
    
    private fun runWorkflow() {
        val workflow = currentWorkflow ?: run {
            appendOutput("错误: 请先选择一个工作流\n", isError = true)
            return
        }
        
        if (isRunning) {
            appendOutput("错误: 工作流正在运行中\n", isError = true)
            return
        }
        
        isRunning = true
        runButton.isEnabled = false
        stopButton.isEnabled = true
        
        appendOutput("\n" + "=".repeat(50) + "\n")
        appendOutput("开始运行工作流: ${workflow.name}\n")
        appendOutput("时间: ${java.util.Date()}\n")
        appendOutput("-".repeat(50) + "\n")
        
        // 解析输入
        val inputJson = inputArea.text.trim()
        val inputs = try {
            if (inputJson.isNotEmpty()) {
                com.google.gson.JsonParser.parseString(inputJson).asJsonObject
            } else {
                null
            }
        } catch (e: Exception) {
            appendOutput("输入解析错误: ${e.message}\n", isError = true)
            isRunning = false
            runButton.isEnabled = true
            stopButton.isEnabled = false
            return
        }
        
        appendOutput("输入数据: $inputs\n")
        appendOutput("-".repeat(50) + "\n")
        
        // 模拟执行
        SwingUtilities.invokeLater {
            simulateExecution(workflow, inputs)
        }
    }
    
    private fun simulateExecution(workflow: LoadedWorkflow, inputs: com.google.gson.JsonObject?) {
        try {
            // 按拓扑顺序执行节点
            val executedNodes = mutableSetOf<String>()
            val nodeOutputs = mutableMapOf<String, MutableMap<String, Any?>>()
            
            // 添加输入到第一个节点的输入
            inputs?.entrySet()?.forEach { (key, value) ->
                nodeOutputs["start_001"] = mutableMapOf(key to value.toString())
            }
            
            workflow.definition.nodes.forEach { node ->
                if (!isRunning) {
                    appendOutput("\n[已停止]\n")
                    return
                }
                
                appendOutput("执行节点: ${node.name} (${node.type})\n")
                
                when (node.type) {
                    "code" -> {
                        // 显示代码文件内容
                        val nodeFiles = workflow.nodeFiles[node.id]
                        if (nodeFiles?.codeContent != null) {
                            appendOutput("  代码文件: ${nodeFiles.codeFilePath ?: "nodes/${node.id}.py"}\n")
                            appendOutput("  代码行数: ${nodeFiles.codeContent.lines().size}\n")
                        } else {
                            appendOutput("  代码: ${node.config.code?.lines()?.firstOrNull()?.take(50) ?: "内联代码"}\n")
                        }
                        
                        // 模拟执行结果
                        val outputs = mutableMapOf<String, Any?>()
                        node.config.outputs?.keys?.forEach { key ->
                            outputs[key] = "[模拟输出] $key"
                        }
                        nodeOutputs[node.id] = outputs
                        appendOutput("  输出: $outputs\n")
                    }
                    "agent" -> {
                        appendOutput("  模型: ${node.config.model ?: "gpt-4"}\n")
                        val promptFile = workflow.nodeFiles[node.id]?.promptFilePath
                        appendOutput("  提示词文件: ${promptFile ?: "内联提示词"}\n")
                        
                        val outputs = mutableMapOf<String, Any?>()
                        node.config.outputs?.keys?.forEach { key ->
                            outputs[key] = "[AI生成结果] $key"
                        }
                        nodeOutputs[node.id] = outputs
                        appendOutput("  输出: $outputs\n")
                    }
                    "condition" -> {
                        appendOutput("  条件: ${node.config.condition}\n")
                        appendOutput("  结果: ${if (Math.random() > 0.5) "true (走'有数据'分支)" else "false (走'无数据'分支)"}\n")
                    }
                    "start" -> {
                        appendOutput("  输入参数初始化\n")
                    }
                    "end" -> {
                        appendOutput("  工作流执行完成\n")
                    }
                }
                
                executedNodes.add(node.id)
                appendOutput("\n")
                
                // 模拟延迟
                Thread.sleep(300)
            }
            
            appendOutput("=".repeat(50) + "\n")
            appendOutput("执行完成! 共执行 ${executedNodes.size} 个节点\n")
            appendOutput("最终输出:\n")
            
            // 显示最终输出
            nodeOutputs.forEach { (nodeId, outputs) ->
                if (outputs.isNotEmpty()) {
                    appendOutput("  $nodeId: $outputs\n")
                }
            }
            
        } catch (e: Exception) {
            appendOutput("执行错误: ${e.message}\n", isError = true)
            e.printStackTrace()
        } finally {
            isRunning = false
            runButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }
    
    private fun stopWorkflow() {
        isRunning = false
        appendOutput("\n[停止信号已发送]\n")
    }
    
    private fun appendOutput(text: String, isError: Boolean = false) {
        SwingUtilities.invokeLater {
            val color = if (isError) "[错误] " else ""
            outputArea.append(color + text)
            outputArea.caretPosition = outputArea.document.length
        }
    }
    
    private fun scanProjectWorkflows() {
        val basePath = project.basePath ?: return
        val workflows = loader.scanWorkflows(File(basePath))
        
        // 也扫描项目根目录
        File(basePath).listFiles { f -> f.isDirectory }?.forEach { dir ->
            loader.load(dir)?.let { 
                if (!workflows.any { w -> w.baseDir.absolutePath == it.baseDir.absolutePath }) {
                    // 手动添加到列表
                }
            }
        }
        
        workflows.forEach { workflowListModel.addElement(it) }
        
        if (workflows.isEmpty()) {
            // 尝试加载 examples 目录
            val examplesDir = File(basePath, "examples")
            if (examplesDir.exists()) {
                loader.load(examplesDir)?.let { workflowListModel.addElement(it) }
            }
        }
    }
    
    private fun showOpenDialog() {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "选择工作流文件夹"
        }
        
        val result = chooser.showOpenDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            loader.load(chooser.selectedFile)?.let { loaded ->
                // 检查是否已存在
                val existingIndex = (0 until workflowListModel.size()).find { 
                    workflowListModel.getElementAt(it).baseDir == loaded.baseDir 
                }
                if (existingIndex != null) {
                    workflowListModel.setElementAt(loaded, existingIndex)
                    workflowList.selectedIndex = existingIndex
                } else {
                    workflowListModel.addElement(loaded)
                    workflowList.selectedIndex = workflowListModel.size() - 1
                }
            }
        }
    }
    
    /**
     * 列表项渲染器
     */
    private inner class WorkflowListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            
            if (value is LoadedWorkflow) {
                text = "✓ ${value.name} (${value.definition.nodes.size} 节点)"
                toolTipText = value.description
            }
            
            return component
        }
    }
}
