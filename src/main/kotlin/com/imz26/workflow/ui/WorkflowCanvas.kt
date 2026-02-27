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
    
    private var workflow: Workflow? = null
    private var selectedNode: String? = null
    private var dragOffset = Point(0, 0)
    private var isDragging = false
    
    private val nodeColors = mapOf(
        NodeType.START to Color(76, 175, 80),
        NodeType.END to Color(244, 67, 54),
        NodeType.CODE to Color(33, 150, 243),
        NodeType.AGENT to Color(156, 39, 176),
        NodeType.CONDITION to Color(255, 152, 0),
        NodeType.HTTP to Color(0, 150, 136),
        NodeType.VARIABLE to Color(121, 85, 72)
    )
    
    init {
        background = Color(250, 250, 250)
        preferredSize = Dimension(800, 600)
        
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val node = findNodeAt(e.point)
                selectedNode = node?.id
                if (node != null) {
                    isDragging = true
                    dragOffset.x = e.x - node.position.x
                    dragOffset.y = e.y - node.position.y
                }
                repaint()
            }
            
            override fun mouseReleased(e: MouseEvent) {
                isDragging = false
            }
        })
        
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (isDragging && selectedNode != null) {
                    // TODO: 更新节点位置
                    repaint()
                }
            }
        })
    }
    
    fun setWorkflow(workflow: Workflow) {
        this.workflow = workflow
        repaint()
    }
    
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        
        val wf = workflow ?: return
        
        // 绘制边
        g2d.stroke = BasicStroke(2f)
        g2d.color = Color(150, 150, 150)
        wf.edges.forEach { edge ->
            val source = wf.nodes.find { it.id == edge.source }
            val target = wf.nodes.find { it.id == edge.target }
            if (source != null && target != null) {
                drawEdge(g2d, source, target, edge.condition)
            }
        }
        
        // 绘制节点
        wf.nodes.forEach { node ->
            drawNode(g2d, node, node.id == selectedNode)
        }
    }
    
    private fun drawNode(g: Graphics2D, node: WorkflowNode, isSelected: Boolean) {
        val x = node.position.x
        val y = node.position.y
        val w = 120
        val h = 60
        
        val color = nodeColors[node.type] ?: Color.GRAY
        
        // 阴影
        g.color = Color(0, 0, 0, 30)
        g.fillRoundRect(x + 3, y + 3, w, h, 10, 10)
        
        // 节点主体
        g.color = color
        g.fillRoundRect(x, y, w, h, 10, 10)
        
        // 选中边框
        if (isSelected) {
            g.color = Color(255, 193, 7)
            g.stroke = BasicStroke(3f)
            g.drawRoundRect(x, y, w, h, 10, 10)
        } else {
            g.color = color.darker()
            g.stroke = BasicStroke(1f)
            g.drawRoundRect(x, y, w, h, 10, 10)
        }
        
        // 文字
        g.color = Color.WHITE
        g.font = Font("Dialog", Font.BOLD, 12)
        val fm = g.fontMetrics
        val typeText = node.type.value.uppercase()
        val nameText = if (node.name.length > 8) node.name.take(8) + "..." else node.name
        
        val typeWidth = fm.stringWidth(typeText)
        val nameWidth = fm.stringWidth(nameText)
        
        g.drawString(typeText, x + (w - typeWidth) / 2, y + 22)
        g.font = Font("Dialog", Font.PLAIN, 10)
        g.drawString(nameText, x + (w - nameWidth) / 2, y + 42)
    }
    
    private fun drawEdge(g: Graphics2D, source: WorkflowNode, target: WorkflowNode, condition: String?) {
        val x1 = source.position.x + 60
        val y1 = source.position.y + 60
        val x2 = target.position.x + 60
        val y2 = target.position.y
        
        // 绘制连线
        g.drawLine(x1, y1, x2, y2)
        
        // 绘制箭头
        drawArrow(g, x1, y1, x2, y2)
        
        // 绘制条件标签
        condition?.let {
            g.color = Color(100, 100, 100)
            g.font = Font("Dialog", Font.ITALIC, 10)
            val midX = (x1 + x2) / 2
            val midY = (y1 + y2) / 2
            g.drawString(it, midX + 5, midY - 5)
        }
    }
    
    private fun drawArrow(g: Graphics2D, x1: Int, y1: Int, x2: Int, y2: Int) {
        val angle = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val arrowLength = 10
        val arrowAngle = Math.PI / 6
        
        val x3 = (x2 - arrowLength * Math.cos(angle - arrowAngle)).toInt()
        val y3 = (y2 - arrowLength * Math.sin(angle - arrowAngle)).toInt()
        val x4 = (x2 - arrowLength * Math.cos(angle + arrowAngle)).toInt()
        val y4 = (y2 - arrowLength * Math.sin(angle + arrowAngle)).toInt()
        
        g.drawLine(x2, y2, x3, y3)
        g.drawLine(x2, y2, x4, y4)
    }
    
    private fun findNodeAt(point: Point): WorkflowNode? {
        return workflow?.nodes?.find { node ->
            val x = node.position.x
            val y = node.position.y
            point.x >= x && point.x <= x + 120 &&
            point.y >= y && point.y <= y + 60
        }
    }
}
