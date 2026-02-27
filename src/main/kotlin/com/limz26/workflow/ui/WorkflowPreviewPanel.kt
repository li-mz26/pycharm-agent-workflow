package com.limz26.workflow.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.limz26.workflow.model.LoadedWorkflow
import com.limz26.workflow.model.WorkflowLoader
import java.awt.BorderLayout
import java.io.File
import javax.swing.*

/**
 * 工作流预览面板 - 从文件夹加载并可视化
 */
class WorkflowPreviewPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {
    
    private val loader = WorkflowLoader()
    private val workflowListModel = DefaultListModel<LoadedWorkflow>()
    private val workflowList = JBList(workflowListModel)
    private val canvas = WorkflowCanvas()
    
    init {
        workflowList.cellRenderer = WorkflowListCellRenderer()
        workflowList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        workflowList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                val selected = workflowList.selectedValue
                if (selected != null) {
                    canvas.setWorkflow(selected)
                }
            }
        }
        
        val splitter = JBSplitter(false, 0.3f)
        splitter.firstComponent = createListPanel()
        splitter.secondComponent = createCanvasPanel()
        
        setContent(splitter)
        
        // 自动扫描项目中的工作流
        scanProjectWorkflows()
    }
    
    /**
     * 加载指定工作流文件夹
     */
    fun loadWorkflow(workflowDir: File) {
        val workflow = loader.load(workflowDir)
        if (workflow != null) {
            // 检查是否已存在
            val existingIndex = (0 until workflowListModel.size()).find { 
                workflowListModel.getElementAt(it).baseDir == workflowDir 
            }
            if (existingIndex != null) {
                workflowListModel.setElementAt(workflow, existingIndex)
            } else {
                workflowListModel.addElement(workflow)
            }
            workflowList.selectedIndex = workflowListModel.size() - 1
        }
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
        val refreshButton = JButton("刷新").apply {
            addActionListener { refresh() }
        }
        val openButton = JButton("打开文件夹...").apply {
            addActionListener { showOpenDialog() }
        }
        buttonPanel.add(refreshButton)
        buttonPanel.add(openButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)
        
        return panel
    }
    
    private fun createCanvasPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("工作流预览")
        panel.add(canvas, BorderLayout.CENTER)
        return panel
    }
    
    private fun scanProjectWorkflows() {
        val basePath = project.basePath ?: return
        val workflows = loader.scanWorkflows(File(basePath))
        workflows.forEach { workflowListModel.addElement(it) }
    }
    
    private fun showOpenDialog() {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "选择工作流文件夹"
        }
        
        val result = chooser.showOpenDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            loadWorkflow(chooser.selectedFile)
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
                text = "${value.name} (${value.definition.nodes.size} 个节点)"
                toolTipText = value.description
            }
            
            return component
        }
    }
}
