package com.limz26.workflow.ui

import com.intellij.util.ui.UIUtil
import com.limz26.workflow.model.*
import java.awt.*
import java.awt.event.*
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 工作流可视化画布 - 渲染 DAG
 * 支持：滚轮缩放、中键拖动画布、左键移动节点
 */
class WorkflowCanvas : JPanel() {

    private var loadedWorkflow: LoadedWorkflow? = null
    private var workflow: Workflow? = null
    private var selectedNode: String? = null
    private var hoverNode: String? = null

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

    private val nodeColors = mapOf(
        "start" to Color(76, 175, 80),
        "end" to Color(244, 67, 54),
        "code" to Color(33, 150, 243),
        "agent" to Color(156, 39, 176),
        "condition" to Color(255, 152, 0),
        "http" to Color(0, 150, 136),
        "variable" to Color(121, 85, 72)
    )

    private val nodeSize = Dimension(140, 70)

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
                        val node = findNodeAt(e.x, e.y)
                        if (node != null) {
                            isDraggingNode = true
                            dragNodeId = node.id
                            selectedNode = node.id
                        } else {
                            selectedNode = null
                        }
                        repaint()
                    }
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                isPanning = false
                isDraggingNode = false
                dragNodeId = null
                cursor = Cursor.getDefaultCursor()
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
    }

    fun setWorkflow(loadedWorkflow: LoadedWorkflow) {
        this.loadedWorkflow = loadedWorkflow
        this.workflow = null
        // 自动布局节点（垂直方向）
        autoLayoutNodes(loadedWorkflow.definition)
        repaint()
    }

    fun setWorkflow(workflow: Workflow) {
        this.workflow = workflow
        this.loadedWorkflow = null
        // 自动布局节点（垂直方向）
        autoLayoutNodes(convertToDefinition(workflow))
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
        drawArrow(g, end, target.position)

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
            g.font = Font("Dialog", Font.BOLD, 11)
            g.drawString(it, midX - textWidth/2, midY + textHeight/4)
        }
    }

    private fun drawArrow(g: Graphics2D, end: Point, targetPos: PositionDefinition) {
        val angle = Math.atan2((targetPos.y + nodeSize.height/2 - end.y).toDouble(),
                              (targetPos.x + nodeSize.width/2 - end.x).toDouble())
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
        g.font = Font("Dialog", Font.BOLD, 14)
        val icon = getNodeIcon(node.type)
        val fm = g.fontMetrics
        g.drawString(icon, x + 15 - fm.stringWidth(icon)/2, y + h/2 + 5)

        // 节点名称
        g.color = Color.WHITE
        g.font = Font("Dialog", Font.BOLD, 12)
        val nameText = if (node.name.length > 10) node.name.take(10) + "..." else node.name
        g.drawString(nameText, x + 38, y + 28)

        // 类型标签
        g.color = Color(220, 220, 220)
        g.font = Font("Dialog", Font.PLAIN, 10)
        g.drawString(node.type.uppercase(), x + 38, y + 50)

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
        }

        if (details.isNotEmpty()) {
            g.color = Color(50, 50, 50, 230)
            val lineHeight = 16
            val padding = 8
            val maxWidth = details.maxOf { g.fontMetrics.stringWidth(it) } + padding * 2
            val height = details.size * lineHeight + padding * 2

            g.fillRoundRect(x, y, maxWidth.coerceAtLeast(140), height, 8, 8)

            g.color = Color.WHITE
            g.font = Font("Dialog", Font.PLAIN, 11)
            details.forEachIndexed { index, text ->
                g.drawString(text, x + padding, y + padding + (index + 1) * lineHeight - 4)
            }
        }
    }

    private fun getConnectionPoint(node: NodeDefinition, isOutput: Boolean): Point {
        val x = node.position.x + if (isOutput) nodeSize.width else 0
        val y = node.position.y + nodeSize.height / 2
        return Point(x, y)
    }

    private fun getNodeIcon(type: String): String {
        return when (type) {
            "start" -> "▶"
            "end" -> "■"
            "code" -> "{ }"
            "agent" -> "🤖"
            "condition" -> "?"
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
                        prompt = node.config.prompt,
                        model = node.config.model,
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
