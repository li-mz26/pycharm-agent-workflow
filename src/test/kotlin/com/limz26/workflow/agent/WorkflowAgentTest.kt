package com.limz26.workflow.agent

import com.limz26.workflow.model.*
import org.junit.Test
import org.junit.Assert.*

class WorkflowAgentTest {

    @Test
    fun `test validate workflow with valid DAG`() {
        val agent = WorkflowAgent()
        
        val workflow = Workflow(
            name = "Test Workflow",
            nodes = listOf(
                WorkflowNode(id = "1", type = NodeType.START, name = "开始"),
                WorkflowNode(id = "2", type = NodeType.CODE, name = "处理"),
                WorkflowNode(id = "3", type = NodeType.END, name = "结束")
            ),
            edges = listOf(
                WorkflowEdge(source = "1", target = "2"),
                WorkflowEdge(source = "2", target = "3")
            )
        )
        
        val result = agent.validateWorkflow(workflow)
        
        assertTrue("Valid workflow should pass validation", result.isValid)
        assertTrue("No errors expected", result.errors.isEmpty())
    }
    
    @Test
    fun `test validate workflow missing start node`() {
        val agent = WorkflowAgent()
        
        val workflow = Workflow(
            name = "Test Workflow",
            nodes = listOf(
                WorkflowNode(id = "1", type = NodeType.CODE, name = "处理"),
                WorkflowNode(id = "2", type = NodeType.END, name = "结束")
            ),
            edges = listOf()
        )
        
        val result = agent.validateWorkflow(workflow)
        
        assertFalse("Should fail validation", result.isValid)
        assertTrue("Should report missing start node", result.errors.any { it.contains("开始") })
    }
    
    @Test
    fun `test validate workflow missing end node`() {
        val agent = WorkflowAgent()
        
        val workflow = Workflow(
            name = "Test Workflow",
            nodes = listOf(
                WorkflowNode(id = "1", type = NodeType.START, name = "开始"),
                WorkflowNode(id = "2", type = NodeType.CODE, name = "处理")
            ),
            edges = listOf()
        )
        
        val result = agent.validateWorkflow(workflow)
        
        assertFalse("Should fail validation", result.isValid)
        assertTrue("Should report missing end node", result.errors.any { it.contains("结束") })
    }
    
    @Test
    fun `test validate workflow with cycle`() {
        val agent = WorkflowAgent()
        
        val workflow = Workflow(
            name = "Test Workflow",
            nodes = listOf(
                WorkflowNode(id = "1", type = NodeType.START, name = "开始"),
                WorkflowNode(id = "2", type = NodeType.CODE, name = "A"),
                WorkflowNode(id = "3", type = NodeType.CODE, name = "B"),
                WorkflowNode(id = "4", type = NodeType.END, name = "结束")
            ),
            edges = listOf(
                WorkflowEdge(source = "1", target = "2"),
                WorkflowEdge(source = "2", target = "3"),
                WorkflowEdge(source = "3", target = "2"),
                WorkflowEdge(source = "3", target = "4")
            )
        )
        
        val result = agent.validateWorkflow(workflow)
        
        assertFalse("Should fail validation due to cycle", result.isValid)
        assertTrue("Should report cycle", result.errors.any { it.contains("循环") })
    }
    
    @Test
    fun `test validate workflow with isolated nodes`() {
        val agent = WorkflowAgent()
        
        val workflow = Workflow(
            name = "Test Workflow",
            nodes = listOf(
                WorkflowNode(id = "1", type = NodeType.START, name = "开始"),
                WorkflowNode(id = "2", type = NodeType.CODE, name = "处理"),
                WorkflowNode(id = "3", type = NodeType.CODE, name = "孤立节点"),
                WorkflowNode(id = "4", type = NodeType.END, name = "结束")
            ),
            edges = listOf(
                WorkflowEdge(source = "1", target = "2"),
                WorkflowEdge(source = "2", target = "4")
            )
        )
        
        val result = agent.validateWorkflow(workflow)
        
        assertFalse("Should fail validation", result.isValid)
        assertTrue("Should report isolated node", result.errors.any { it.contains("孤立") })
    }
    
    @Test
    fun `test generate workflow without API key`() {
        val agent = WorkflowAgent()
        
        val result = agent.generateWorkflow("创建一个简单的工作流")
        
        assertEquals("Should return error workflow", "错误", result.name)
        assertTrue("Should mention API Key", result.description.contains("API Key"))
    }
}
