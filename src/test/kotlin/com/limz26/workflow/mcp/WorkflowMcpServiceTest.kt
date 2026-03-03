package com.limz26.workflow.mcp

import com.google.gson.GsonBuilder
import com.limz26.workflow.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkflowMcpServiceTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun `validate tool detects cycle`() {
        val service = WorkflowMcpService()
        val workflow = WorkflowDefinition(
            id = "wf-1",
            name = "wf",
            description = "",
            nodes = listOf(
                NodeDefinition("n1", "start", "start", PositionDefinition(0, 0), NodeConfigDefinition()),
                NodeDefinition("n2", "code", "code", PositionDefinition(0, 0), NodeConfigDefinition()),
                NodeDefinition("n3", "end", "end", PositionDefinition(0, 0), NodeConfigDefinition())
            ),
            edges = listOf(
                EdgeDefinition("e1", "n1", "n2"),
                EdgeDefinition("e2", "n2", "n3"),
                EdgeDefinition("e3", "n3", "n1")
            ),
            variables = emptyMap()
        )

        val result = service.validateWorkflowJson(gson.toJson(workflow), null)

        assertTrue(result.validJson)
        assertFalse(result.isDag)
        assertTrue(result.errors.any { it.contains("环") })
    }

    @Test
    fun `layout tool repositions nodes by level`() {
        val service = WorkflowMcpService()
        val workflowDir = Files.createTempDirectory("workflow-layout-test").toFile()
        writeWorkflow(workflowDir)

        val result = service.layoutWorkflow(workflowDir.absolutePath, 300, 120)

        assertEquals(3, result.updatedNodeCount)

        val saved = gson.fromJson(File(workflowDir, "workflow.json").readText(), WorkflowDefinition::class.java)
        val positions = saved.nodes.associate { it.id to it.position }
        assertEquals(0, positions.getValue("n1").x)
        assertEquals(300, positions.getValue("n2").x)
        assertEquals(600, positions.getValue("n3").x)
    }

    private fun writeWorkflow(workflowDir: File) {
        val workflow = WorkflowDefinition(
            id = "wf-layout",
            name = "layout",
            description = "",
            nodes = listOf(
                NodeDefinition("n1", "start", "start", PositionDefinition(99, 99), NodeConfigDefinition()),
                NodeDefinition("n2", "code", "code", PositionDefinition(99, 99), NodeConfigDefinition()),
                NodeDefinition("n3", "end", "end", PositionDefinition(99, 99), NodeConfigDefinition())
            ),
            edges = listOf(
                EdgeDefinition("e1", "n1", "n2"),
                EdgeDefinition("e2", "n2", "n3")
            ),
            variables = emptyMap()
        )

        File(workflowDir, "workflow.json").writeText(gson.toJson(workflow))
    }
}
