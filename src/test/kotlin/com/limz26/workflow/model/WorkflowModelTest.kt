package com.limz26.workflow.model

import org.junit.Test
import org.junit.Assert.*

import com.limz26.workflow.agent.ValidationResult

class WorkflowModelTest {
    
    @Test
    fun `test workflow node creation`() {
        val node = WorkflowNode(
            type = NodeType.CODE,
            name = "Test Node",
            position = Position(100, 200),
            config = NodeConfig(code = "print('hello')")
        )
        
        assertEquals(NodeType.CODE, node.type)
        assertEquals("Test Node", node.name)
        assertEquals(100, node.position.x)
        assertEquals(200, node.position.y)
        assertEquals("print('hello')", node.config?.code)
    }
    
    @Test
    fun `test workflow edge creation`() {
        val edge = WorkflowEdge(source = "node_1", target = "node_2")
        
        assertEquals("node_1", edge.source)
        assertEquals("node_2", edge.target)
    }
    
    @Test
    fun `test workflow creation`() {
        val workflow = Workflow(
            name = "Test Workflow",
            description = "A test workflow",
            nodes = listOf(
                WorkflowNode(type = NodeType.START, name = "开始"),
                WorkflowNode(type = NodeType.END, name = "结束")
            ),
            edges = listOf(
                WorkflowEdge(source = "node_1", target = "node_2")
            ),
            variables = mapOf("var1" to Variable("var1", "string"))
        )
        
        assertEquals("Test Workflow", workflow.name)
        assertEquals("A test workflow", workflow.description)
        assertEquals(2, workflow.nodes.size)
        assertEquals(1, workflow.edges.size)
        assertEquals(1, workflow.variables.size)
    }
    
    @Test
    fun `test node type values`() {
        assertEquals(8, NodeType.values().size)
        assertNotNull(NodeType.START)
        assertNotNull(NodeType.END)
        assertNotNull(NodeType.CODE)
        assertNotNull(NodeType.AGENT)
        assertNotNull(NodeType.CONDITION)
        assertNotNull(NodeType.BRANCH)
        assertNotNull(NodeType.HTTP)
        assertNotNull(NodeType.VARIABLE)
    }
    
    @Test
    fun `test validation result`() {
        val validResult = ValidationResult(true, emptyList())
        val invalidResult = ValidationResult(false, listOf("Error 1", "Error 2"))
        
        assertTrue(validResult.isValid)
        assertFalse(invalidResult.isValid)
        assertEquals(2, invalidResult.errors.size)
    }
}
