package com.limz26.workflow.ui

import com.limz26.workflow.model.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * 工作流可视化画布 - 渲染 DAG
 */
class WorkflowCanvas : JPanel() {
    
    private var workflow: LoadedWorkflow? = null
    private var selectedNode: String? = null
    private var hoverNode: String? = null
    
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
        background = Color(250, 250, 250)
        preferredSize = Dimension(1200, 800)
        
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val node = findNodeAt(e.point)
                selectedNode = node?.id
                repaint()
            }
        })
        
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val node = findNodeAt(e.point)
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
    
    fun setWorkflow(loadedWorkflow: LoadedWorkflow) {
        this.workflow = loadedWorkflow
        repaint()
    }
    
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        
        val wf = workflow?.definition ?: return
        
        // 绘制边
        drawEdges(g2d, wf)
        
        // 绘制节点
        wf.nodes.forEach { node ->
            drawNode(g2d, node, node.id == selectedNode, node.id == hoverNode)
        }
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
        
        // 贝塞尔曲线
        val ctrl1 = Point(start.x + (end.x - start.x) / 2, start.y)
        val ctrl2 = Point(start.x + (end.x - start.x) / 2, end.y)
        
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
        val gradient = Paint(
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
        g.color = Color(255, 255, 255, 200)
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
            "variable" -> "$")
            else -> "●"
        }
    }
    
    private fun findNodeAt(point: Point): NodeDefinition? {
        return workflow?.definition?.nodes?.find { node ->
            val x = node.position.x
            val y = node.position.y
            point.x >= x && point.x <= x + nodeSize.width &&
            point.y >= y && point.y <= y + nodeSize.height
        }
    }
}
