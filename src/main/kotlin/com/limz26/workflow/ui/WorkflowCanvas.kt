package com.limz26.workflow.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.UIUtil
import com.limz26.workflow.model.*
import java.awt.*
import java.awt.GraphicsEnvironment
import java.awt.event.*
import java.io.File
import java.util.UUID
import javax.swing.JPanel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

/**
 * 工作流可视化画布 - 渲染 DAG
 * 支持：滚轮缩放、中键拖动画布、左键移动节点、双击打开代码文件
 */
class WorkflowCanvas(private val project: Project? = null) : JPanel() {

    private var loadedWorkflow: LoadedWorkflow? = null
    private var workflow: Workflow? = null
    private var selectedNode: String? = null
    private var hoverNode: String? = null
    private var nodeSelectionListener: ((NodeDefinition?) -> Unit)? = null
    private var workflowDefinitionChangeListener: ((WorkflowDefinition) -> Unit)? = null

    // 视图状态
    private var scale = 1.0
    private var offsetX = 50.0
    private var offsetY = 50.0

    // 拖拽状态
    private var isPanning = false
    private var isDraggingNode = false
    private var dragNodeId: String? = null
    private var lastMouseX = 0
    private var lastMouseY = 0

    private var creatingEdgeSourceId: String? = null
    private var edgePreviewMouse: Point? = null
    private var popupCanvasPoint: Point? = null

    private val nodeColors = mapOf(
        "start" to Color(76, 175, 80),
        "end" to Color(244, 67, 54),
        "code" to Color(33, 150, 243),
        "agent" to Color(156, 39, 176),
        "condition" to Color(255, 152, 0),
        "branch" to Color(255, 152, 0),
        "http" to Color(0, 150, 136),
        "variable" to Color(121, 85, 72)
    )

    private val nodeSize = Dimension(140, 70)

    private val blockFontFamily: String = run {
        val preferred = listOf("Microsoft YaHei", "微软雅黑", "PingFang SC", "Noto Sans CJK SC", "Source Han Sans SC", "Dialog")
        val installed = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        preferred.firstOrNull { installed.contains(it) } ?: "Dialog"
    }

    private fun blockFont(style: Int, size: Int): Font = Font(blockFontFamily, style, size)

    init {
        background = UIUtil.getPanelBackground()
        preferredSize = Dimension(1200, 800)

        setupMouseListeners()
        setupMouseWheelListener()
    }

    private fun setupMouseListeners() {
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                lastMouseX = e.x
                lastMouseY = e.y

                when {
                    // 中键：开始拖动画布
                    SwingUtilities.isMiddleMouseButton(e) -> {
                        isPanning = true
                        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    }
                    // 左键：检查是否点击节点
                    SwingUtilities.isLeftMouseButton(e) -> {
                        val edgeSource = findEdgeHandleAt(e.x, e.y)
                        if (edgeSource != null) {
                            creatingEdgeSourceId = edgeSource.id
                            edgePreviewMouse = screenToCanvas(e.x, e.y)
                            selectedNode = edgeSource.id
                            nodeSelectionListener?.invoke(edgeSource)
                        } else {
                            val node = findNodeAt(e.x, e.y)
                            if (node != null) {
                                isDraggingNode = true
                                dragNodeId = node.id
                                selectedNode = node.id
                                nodeSelectionListener?.invoke(node)
                            } else {
                                selectedNode = null
                                nodeSelectionListener?.invoke(null)
                            }
                        }
                        repaint()
                    }
                    SwingUtilities.isRightMouseButton(e) -> {
                        popupCanvasPoint = screenToCanvas(e.x, e.y)
                        showContextMenu(e)
                    }
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (creatingEdgeSourceId != null) {
                    finishEdgeCreation(e.x, e.y)
                }
                if (e.isPopupTrigger) {
                    popupCanvasPoint = screenToCanvas(e.x, e.y)
                    showContextMenu(e)
                }
                isPanning = false
                isDraggingNode = false
                dragNodeId = null
                cursor = Cursor.getDefaultCursor()
            }

            override fun mouseClicked(e: MouseEvent) {
                // 双击 code 节点打开 Python 文件
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    val node = findNodeAt(e.x, e.y)
                    if (node != null && node.type == "code") {
                        openNodeFileInEditor(node)
                    }
                }
            }
        })

        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val dx = e.x - lastMouseX
                val dy = e.y - lastMouseY
                lastMouseX = e.x
                lastMouseY = e.y

                when {
                    // 中键拖拽：移动画布
                    isPanning -> {
                        offsetX += dx
                        offsetY += dy
                        repaint()
                    }
                    // 左键拖拽：移动节点
                    isDraggingNode && dragNodeId != null -> {
                        moveNode(dragNodeId!!, dx / scale, dy / scale)
                        repaint()
                    }
                    creatingEdgeSourceId != null -> {
                        edgePreviewMouse = screenToCanvas(e.x, e.y)
                        repaint()
                    }
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                val node = findNodeAt(e.x, e.y)
                val prevHover = hoverNode
                hoverNode = node?.id

                if (prevHover != hoverNode) {
                    cursor = if (hoverNode != null) {
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else {
                        Cursor.getDefaultCursor()
                    }
                    repaint()
                }
            }
        })
    }

    private fun setupMouseWheelListener() {
        addMouseWheelListener { e ->
            val zoomFactor = if (e.wheelRotation < 0) 1.1 else 0.9
            val newScale = (scale * zoomFactor).coerceIn(0.3, 3.0)

            // 以鼠标位置为中心缩放
            val mouseX = e.x.toDouble()
            val mouseY = e.y.toDouble()

            offsetX = mouseX - (mouseX - offsetX) * (newScale / scale)
            offsetY = mouseY - (mouseY - offsetY) * (newScale / scale)

            scale = newScale
            repaint()
        }
    }

    private fun moveNode(nodeId: String, dx: Double, dy: Double) {
        val wf = loadedWorkflow?.definition ?: workflow?.let { convertToDefinition(it) } ?: return
        val node = wf.nodes.find { it.id == nodeId } ?: return

        // 更新节点位置
        val newX = (node.position.x + dx.toInt()).coerceAtLeast(0)
        val newY = (node.position.y + dy.toInt()).coerceAtLeast(0)

        // 创建新节点定义
        val updatedNode = node.copy(
            position = PositionDefinition(newX, newY)
        )

        // 更新工作流
        val updatedNodes = wf.nodes.map {
            if (it.id == nodeId) updatedNode else it
        }

        val newDefinition = wf.copy(nodes = updatedNodes)

        // 更新内部状态
        if (loadedWorkflow != null) {
            loadedWorkflow = loadedWorkflow?.copy(definition = newDefinition)
        }
        if (workflow != null) {
            workflow = workflow?.copy(
                nodes = workflow!!.nodes.map {
                    if (it.id == nodeId) {
                        it.copy(position = Position(newX, newY))
                    } else it
                }
            )
        }
        workflowDefinitionChangeListener?.invoke(newDefinition)
    }

    fun setWorkflow(loadedWorkflow: LoadedWorkflow, autoLayout: Boolean = true) {
        this.loadedWorkflow = loadedWorkflow
        this.workflow = null
        if (autoLayout) autoLayoutNodes(loadedWorkflow.definition)
        repaint()
    }

    fun setWorkflow(workflow: Workflow, autoLayout: Boolean = true) {
        this.workflow = workflow
        this.loadedWorkflow = null
        if (autoLayout) autoLayoutNodes(convertToDefinition(workflow))
        repaint()
    }

    /**
     * 自动布局节点 - 垂直方向排列
     */
    private fun autoLayoutNodes(definition: WorkflowDefinition) {
        val nodes = definition.nodes.toMutableList()
        if (nodes.isEmpty()) return

        // 找到开始节点
        val startNode = nodes.find { it.type == "start" }
        val endNode = nodes.find { it.type == "end" }
        val otherNodes = nodes.filter { it.type != "start" && it.type != "end" }

        val layoutNodes = mutableListOf<NodeDefinition>()
        startNode?.let { layoutNodes.add(it) }
        layoutNodes.addAll(otherNodes)
        endNode?.let { layoutNodes.add(it) }

        // 垂直排列
        val centerX = 300
        val startY = 50
        val spacingY = 120

        layoutNodes.forEachIndexed { index, node ->
            val newX = centerX - nodeSize.width / 2
            val newY = startY + index * spacingY

            // 更新节点位置
            val updatedNode = node.copy(position = PositionDefinition(newX, newY))

            // 更新列表
            val nodeIndex = nodes.indexOfFirst { it.id == node.id }
            if (nodeIndex >= 0) {
                nodes[nodeIndex] = updatedNode
            }
        }

        // 更新工作流定义
        val newDefinition = definition.copy(nodes = nodes)

        if (loadedWorkflow != null) {
            loadedWorkflow = loadedWorkflow?.copy(definition = newDefinition)
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        // 保存原始变换
        val originalTransform = g2d.transform

        // 应用缩放和平移
        g2d.translate(offsetX, offsetY)
        g2d.scale(scale, scale)

        val wf = loadedWorkflow?.definition ?: workflow?.let { convertToDefinition(it) } ?: return

        // 绘制边
        drawEdges(g2d, wf)

        // 绘制节点
        wf.nodes.forEach { node ->
            drawNode(g2d, node, node.id == selectedNode, node.id == hoverNode)
        }

        drawEdgePreview(g2d, wf)

        // 恢复原始变换
        g2d.transform = originalTransform

        // 绘制缩放信息
        drawZoomInfo(g2d)
    }

    private fun drawZoomInfo(g: Graphics2D) {
        g.color = if (UIUtil.isUnderDarcula()) Color(200, 200, 200) else Color(100, 100, 100)
        g.font = Font("Dialog", Font.PLAIN, 11)
        val zoomText = "${(scale * 100).toInt()}%"
        g.drawString(zoomText, width - 50, height - 20)

        // 绘制操作提示
        val hintText = "滚轮缩放 | 中键拖动 | 左键移动节点"
        g.drawString(hintText, 10, height - 20)
    }

    private fun drawEdges(g: Graphics2D, wf: WorkflowDefinition) {
        g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        wf.edges.forEach { edge ->
            val source = wf.nodes.find { it.id == edge.source }
            val target = wf.nodes.find { it.id == edge.target }
            if (source != null && target != null) {
                drawEdge(g, source, target, edge.condition)
            }
        }
    }

    private fun drawEdge(g: Graphics2D, source: NodeDefinition, target: NodeDefinition, condition: String?) {
        val start = getConnectionPoint(source, true)
        val end = getConnectionPoint(target, false)

        // 贝塞尔曲线（垂直方向）
        val ctrl1 = Point(start.x, start.y + (end.y - start.y) / 2)
        val ctrl2 = Point(end.x, start.y + (end.y - start.y) / 2)

        // 边的颜色根据条件
        g.color = if (condition != null) {
            Color(255, 152, 0)  // 条件分支用橙色
        } else {
            Color(100, 100, 100)
        }

        val path = java.awt.geom.Path2D.Double()
        path.moveTo(start.x.toDouble(), start.y.toDouble())
        path.curveTo(
            ctrl1.x.toDouble(), ctrl1.y.toDouble(),
            ctrl2.x.toDouble(), ctrl2.y.toDouble(),
            end.x.toDouble(), end.y.toDouble()
        )
        g.draw(path)

        // 箭头
        drawArrow(g, start, end)

        // 条件标签
        condition?.let {
            val midX = (start.x + end.x) / 2
            val midY = (start.y + end.y) / 2

            // 标签背景
            val fm = g.fontMetrics
            val textWidth = fm.stringWidth(it)
            val textHeight = fm.height

            g.color = Color(255, 248, 225)
            g.fillRoundRect(midX - textWidth/2 - 5, midY - textHeight/2 - 2, textWidth + 10, textHeight + 4, 8, 8)

            g.color = Color(230, 81, 0)
            g.font = blockFont(Font.BOLD, 12)
            g.drawString(it, midX - textWidth/2, midY + textHeight/4)
        }
    }

    private fun drawArrow(g: Graphics2D, start: Point, end: Point) {
        val angle = Math.atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
        val arrowLength = 12
        val arrowAngle = Math.PI / 6

        val x1 = (end.x - arrowLength * Math.cos(angle - arrowAngle)).toInt()
        val y1 = (end.y - arrowLength * Math.sin(angle - arrowAngle)).toInt()
        val x2 = (end.x - arrowLength * Math.cos(angle + arrowAngle)).toInt()
        val y2 = (end.y - arrowLength * Math.sin(angle + arrowAngle)).toInt()

        val path = java.awt.geom.Path2D.Double()
        path.moveTo(end.x.toDouble(), end.y.toDouble())
        path.lineTo(x1.toDouble(), y1.toDouble())
        path.moveTo(end.x.toDouble(), end.y.toDouble())
        path.lineTo(x2.toDouble(), y2.toDouble())
        g.draw(path)
    }

    private fun drawNode(g: Graphics2D, node: NodeDefinition, isSelected: Boolean, isHover: Boolean) {
        val x = node.position.x
        val y = node.position.y
        val w = nodeSize.width
        val h = nodeSize.height

        val color = nodeColors[node.type] ?: Color.GRAY

        // 阴影
        if (isSelected || isHover) {
            g.color = Color(0, 0, 0, if (isSelected) 60 else 40)
            val offset = if (isSelected) 4 else 2
            g.fillRoundRect(x + offset, y + offset, w, h, 12, 12)
        }

        // 节点主体渐变
        val gradient = GradientPaint(
            x.toFloat(), y.toFloat(), color.brighter(),
            x.toFloat(), (y + h).toFloat(), color
        )
        g.paint = gradient
        g.fillRoundRect(x, y, w, h, 12, 12)

        // 边框
        if (isSelected) {
            g.color = Color(255, 193, 7)
            g.stroke = BasicStroke(3f)
            g.drawRoundRect(x, y, w, h, 12, 12)
        } else {
            g.color = color.darker()
            g.stroke = BasicStroke(1.5f)
            g.drawRoundRect(x, y, w, h, 12, 12)
        }

        // 图标区域（左侧）
        g.color = color.darker()
        g.fillRoundRect(x, y, 30, h, 12, 0)
        g.fillRect(x + 20, y, 10, h)

        // 类型图标
        g.color = Color.WHITE
        g.font = blockFont(Font.BOLD, 15)
        val icon = getNodeIcon(node.type)
        val fm = g.fontMetrics
        g.drawString(icon, x + 15 - fm.stringWidth(icon)/2, y + h/2 + 5)

        // 节点名称
        g.color = Color.WHITE
        g.font = blockFont(Font.BOLD, 14)
        val nameText = if (node.name.length > 10) node.name.take(10) + "..." else node.name
        g.drawString(nameText, x + 38, y + 28)

        // 类型标签
        g.color = Color(220, 220, 220)
        g.font = blockFont(Font.BOLD, 12)
        g.drawString(node.type.uppercase(), x + 38, y + 50)

        // 底部连接锚点（用于拖拽创建边）
        g.color = Color.WHITE
        g.fillOval(x + w / 2 - 5, y + h - 5, 10, 10)
        g.color = color.darker()
        g.drawOval(x + w / 2 - 5, y + h - 5, 10, 10)

        // 选中时显示额外信息
        if (isSelected) {
            drawNodeDetails(g, node, x, y + h + 5)
        }
    }

    private fun drawNodeDetails(g: Graphics2D, node: NodeDefinition, x: Int, y: Int) {
        val details = mutableListOf<String>()

        when (node.type) {
            "code" -> node.config.code?.let {
                details.add("代码: ${it.lines().firstOrNull()?.take(30) ?: ""}...")
            }
            "agent" -> {
                node.config.model?.let { details.add("模型: $it") }
                node.config.prompt?.let {
                    details.add("提示词: ${it.take(30)}...")
                }
            }
            "condition" -> node.config.condition?.let {
                details.add("条件: $it")
            }
            "branch" -> {
                node.config.branchField?.let { details.add("字段: $it") }
                if (node.config.branchCases.isNotEmpty()) {
                    details.add("case: ${node.config.branchCases.keys.joinToString(",")}")
                }
            }
        }

        if (details.isNotEmpty()) {
            g.color = Color(50, 50, 50, 230)
            val lineHeight = 16
            val padding = 8
            val maxWidth = details.maxOf { g.fontMetrics.stringWidth(it) } + padding * 2
            val height = details.size * lineHeight + padding * 2

            g.fillRoundRect(x, y, maxWidth.coerceAtLeast(140), height, 8, 8)

            g.color = Color.WHITE
            g.font = blockFont(Font.BOLD, 12)
            details.forEachIndexed { index, text ->
                g.drawString(text, x + padding, y + padding + (index + 1) * lineHeight - 4)
            }
        }
    }

    private fun getConnectionPoint(node: NodeDefinition, isOutput: Boolean): Point {
        val x = node.position.x + nodeSize.width / 2
        val y = node.position.y + if (isOutput) nodeSize.height else 0
        return Point(x, y)
    }

    private fun getNodeIcon(type: String): String {
        return when (type) {
            "start" -> "▶"
            "end" -> "■"
            "code" -> "{ }"
            "agent" -> "🤖"
            "condition" -> "?"
            "branch" -> "⎇"
            "http" -> "🌐"
            "variable" -> "$"
            else -> "●"
        }
    }

    /**
     * 将屏幕坐标转换为画布坐标
     */
    private fun screenToCanvas(screenX: Int, screenY: Int): Point {
        val canvasX = ((screenX - offsetX) / scale).toInt()
        val canvasY = ((screenY - offsetY) / scale).toInt()
        return Point(canvasX, canvasY)
    }

    private fun findNodeAt(screenX: Int, screenY: Int): NodeDefinition? {
        val canvasPoint = screenToCanvas(screenX, screenY)
        val wf = loadedWorkflow?.definition ?: workflow?.let { convertToDefinition(it) } ?: return null

        return wf.nodes.find { node ->
            val x = node.position.x
            val y = node.position.y
            canvasPoint.x >= x && canvasPoint.x <= x + nodeSize.width &&
            canvasPoint.y >= y && canvasPoint.y <= y + nodeSize.height
        }
    }

    /**
     * 在 IDE 编辑器中打开节点的代码文件
     */
    private fun openNodeFileInEditor(node: NodeDefinition) {
        if (project == null) return

        val baseDir = loadedWorkflow?.baseDir
        if (baseDir == null) {
            println("Cannot open file: no base directory")
            return
        }

        // 仅 code 节点支持双击打开 Python 文件
        if (node.type != "code") {
            return
        }

        // 获取代码文件路径
        val codeFilePath = node.config.codeFile ?: "nodes/${node.id}.py"

        // 构建完整文件路径
        val file = File(baseDir, codeFilePath)
        if (!file.exists()) {
            println("File does not exist: ${file.absolutePath}")
            return
        }

        // 在 IDE 中打开文件
        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)
        if (virtualFile != null) {
            SwingUtilities.invokeLater {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        } else {
            println("Cannot find virtual file for: ${file.absolutePath}")
        }
    }


    private fun drawEdgePreview(g: Graphics2D, wf: WorkflowDefinition) {
        val sourceId = creatingEdgeSourceId ?: return
        val mouse = edgePreviewMouse ?: return
        val source = wf.nodes.find { it.id == sourceId } ?: return
        val start = getConnectionPoint(source, true)
        g.color = Color(255, 193, 7)
        g.stroke = BasicStroke(2f)
        g.drawLine(start.x, start.y, mouse.x, mouse.y)
    }

    private fun findEdgeHandleAt(screenX: Int, screenY: Int): NodeDefinition? {
        val point = screenToCanvas(screenX, screenY)
        val wf = loadedWorkflow?.definition ?: workflow?.let { convertToDefinition(it) } ?: return null
        return wf.nodes.find { node ->
            val handleX = node.position.x + nodeSize.width / 2
            val handleY = node.position.y + nodeSize.height
            val dx = point.x - handleX
            val dy = point.y - handleY
            dx * dx + dy * dy <= 100
        }
    }

    private fun finishEdgeCreation(screenX: Int, screenY: Int) {
        val sourceId = creatingEdgeSourceId
        creatingEdgeSourceId = null
        edgePreviewMouse = null
        if (sourceId == null) return

        val target = findNodeAt(screenX, screenY) ?: return
        if (target.id == sourceId) return

        val def = loadedWorkflow?.definition ?: workflow?.let { convertToDefinition(it) } ?: return
        if (def.edges.any { it.source == sourceId && it.target == target.id }) return

        val newEdge = EdgeDefinition(UUID.randomUUID().toString(), sourceId, target.id)
        val newDef = def.copy(edges = def.edges + newEdge)
        applyDefinition(newDef)
    }

    private fun showContextMenu(e: MouseEvent) {
        val popup = JPopupMenu()
        val edge = findEdgeAt(e.x, e.y)
        if (edge != null) {
            popup.add(JMenuItem("删除边").apply {
                addActionListener { deleteEdge(edge.id) }
            })
        } else {
            val addNodeMenu = JMenu("添加节点")
            listOf("code" to "代码节点", "agent" to "Agent节点", "branch" to "分支节点", "http" to "HTTP节点", "variable" to "变量节点").forEach { (type, label) ->
                addNodeMenu.add(JMenuItem(label).apply {
                    addActionListener { addNodeAtPopup(type, label) }
                })
            }
            popup.add(addNodeMenu)
        }
        popup.show(this, e.x, e.y)
    }

    private fun addNodeAtPopup(type: String, displayName: String) {
        val canvasPoint = popupCanvasPoint ?: Point(120, 120)
        val def = loadedWorkflow?.definition ?: workflow?.let { convertToDefinition(it) } ?: return
        val node = NodeDefinition(
            id = "node_${UUID.randomUUID().toString().take(8)}",
            type = type,
            name = displayName,
            position = PositionDefinition(canvasPoint.x, canvasPoint.y),
            config = NodeConfigDefinition()
        )
        applyDefinition(def.copy(nodes = def.nodes + node))
    }

    private fun deleteEdge(edgeId: String) {
        val def = loadedWorkflow?.definition ?: workflow?.let { convertToDefinition(it) } ?: return
        applyDefinition(def.copy(edges = def.edges.filterNot { it.id == edgeId }))
    }

    private fun findEdgeAt(screenX: Int, screenY: Int): EdgeDefinition? {
        val point = screenToCanvas(screenX, screenY)
        val wf = loadedWorkflow?.definition ?: workflow?.let { convertToDefinition(it) } ?: return null

        fun distanceToCurve(source: NodeDefinition, target: NodeDefinition): Double {
            val start = getConnectionPoint(source, true)
            val end = getConnectionPoint(target, false)
            val ctrl1 = Point(start.x, start.y + (end.y - start.y) / 2)
            val ctrl2 = Point(end.x, start.y + (end.y - start.y) / 2)
            var min = Double.MAX_VALUE
            var prev = start
            for (i in 1..20) {
                val t = i / 20.0
                val x = cubic(start.x.toDouble(), ctrl1.x.toDouble(), ctrl2.x.toDouble(), end.x.toDouble(), t)
                val y = cubic(start.y.toDouble(), ctrl1.y.toDouble(), ctrl2.y.toDouble(), end.y.toDouble(), t)
                val cur = Point(x.toInt(), y.toInt())
                min = minOf(min, pointToSegmentDistance(point, prev, cur))
                prev = cur
            }
            return min
        }

        return wf.edges.firstOrNull { edge ->
            val source = wf.nodes.find { it.id == edge.source } ?: return@firstOrNull false
            val target = wf.nodes.find { it.id == edge.target } ?: return@firstOrNull false
            distanceToCurve(source, target) <= 8.0
        }
    }

    private fun cubic(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val u = 1 - t
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3
    }

    private fun pointToSegmentDistance(p: Point, a: Point, b: Point): Double {
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        if (dx == 0.0 && dy == 0.0) {
            return p.distance(a)
        }
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return Point(projX.toInt(), projY.toInt()).distance(p)
    }

    private fun applyDefinition(newDefinition: WorkflowDefinition) {
        if (loadedWorkflow != null) {
            loadedWorkflow = loadedWorkflow?.copy(definition = newDefinition)
        }
        if (workflow != null) {
            workflow = convertFromDefinition(newDefinition)
        }
        workflowDefinitionChangeListener?.invoke(newDefinition)
        repaint()
    }

    private fun convertToDefinition(workflow: Workflow): WorkflowDefinition {
        return WorkflowDefinition(
            id = workflow.id,
            name = workflow.name,
            description = workflow.description,
            nodes = workflow.nodes.map { node ->
                NodeDefinition(
                    id = node.id,
                    type = node.type.value,
                    name = node.name,
                    position = PositionDefinition(node.position.x, node.position.y),
                    config = NodeConfigDefinition(
                        code = node.config.code,
                        codeFile = node.config.codeFile,
                        prompt = node.config.prompt,
                        agentConfigFile = node.config.agentConfigFile,
                        promptTemplate = node.config.promptTemplate,
                        systemPrompt = node.config.systemPrompt,
                        apiEndpoint = node.config.apiEndpoint,
                        apiKey = node.config.apiKey,
                        model = node.config.model,
                        branchField = node.config.branchField,
                        branchCases = node.config.branchCases,
                        defaultTarget = node.config.defaultTarget,
                        condition = node.config.condition,
                        method = node.config.method,
                        url = node.config.url,
                        headers = node.config.headers,
                        value = node.config.value,
                        inputs = node.config.inputs,
                        outputs = node.config.outputs
                    )
                )
            },
            edges = workflow.edges.map { edge ->
                EdgeDefinition(
                    id = edge.id,
                    source = edge.source,
                    target = edge.target,
                    condition = edge.condition
                )
            },
            variables = workflow.variables.mapValues { (_, v) ->
                VariableDefinition(type = v.type, default = v.defaultValue)
            }
        )
    }

    fun setOnNodeSelected(listener: (NodeDefinition?) -> Unit) {
        nodeSelectionListener = listener
    }

    fun setOnWorkflowDefinitionChanged(listener: (WorkflowDefinition) -> Unit) {
        workflowDefinitionChangeListener = listener
    }


    private fun convertFromDefinition(def: WorkflowDefinition): Workflow {
        return Workflow(
            id = def.id,
            name = def.name,
            description = def.description,
            nodes = def.nodes.map { node ->
                WorkflowNode(
                    id = node.id,
                    type = NodeType.valueOf(node.type.uppercase()),
                    name = node.name,
                    position = Position(node.position.x, node.position.y),
                    config = NodeConfig(
                        code = node.config.code,
                        codeFile = node.config.codeFile,
                        prompt = node.config.prompt,
                        agentConfigFile = node.config.agentConfigFile,
                        promptTemplate = node.config.promptTemplate,
                        systemPrompt = node.config.systemPrompt,
                        apiEndpoint = node.config.apiEndpoint,
                        apiKey = node.config.apiKey,
                        model = node.config.model,
                        branchField = node.config.branchField,
                        branchCases = node.config.branchCases,
                        defaultTarget = node.config.defaultTarget,
                        condition = node.config.condition,
                        method = node.config.method,
                        url = node.config.url,
                        headers = node.config.headers,
                        value = node.config.value,
                        inputs = node.config.inputs,
                        outputs = node.config.outputs
                    )
                )
            },
            edges = def.edges.map { WorkflowEdge(it.id, it.source, it.target, it.condition) },
            variables = def.variables.mapValues { Variable(it.key, it.value.type, it.value.default) }
        )
    }

    /**
     * 重置视图
     */
    fun resetView() {
        scale = 1.0
        offsetX = 50.0
        offsetY = 50.0
        repaint()
    }
}
