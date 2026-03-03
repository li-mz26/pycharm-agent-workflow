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


    @Test
    fun `list workflows accepts workflows directory path directly`() {
        val service = WorkflowMcpService()
        val projectDir = Files.createTempDirectory("workflow-list-test").toFile()
        val workflowsDir = File(projectDir, "workflows").apply { mkdirs() }
        val workflowDir = File(workflowsDir, "wf1").apply { mkdirs() }

        val workflow = WorkflowDefinition(
            id = "wf-list",
            name = "wf-list",
            description = "",
            nodes = listOf(
                NodeDefinition("n1", "start", "start", PositionDefinition(0, 0), NodeConfigDefinition()),
                NodeDefinition("n2", "end", "end", PositionDefinition(0, 0), NodeConfigDefinition())
            ),
            edges = listOf(EdgeDefinition("e1", "n1", "n2")),
            variables = emptyMap()
        )
        File(workflowDir, "workflow.json").writeText(gson.toJson(workflow))

        val fromProjectPath = service.listWorkflows(projectDir.absolutePath)
        val fromWorkflowsPath = service.listWorkflows(workflowsDir.absolutePath)

        assertEquals(1, fromProjectPath.size)
        assertEquals(1, fromWorkflowsPath.size)
        assertEquals(fromProjectPath.first().path, fromWorkflowsPath.first().path)
    }

    @Test
    fun `run workflow executes python code node`() {
        val service = WorkflowMcpService()
        val workflowDir = Files.createTempDirectory("workflow-run-test").toFile()
        val nodesDir = File(workflowDir, "nodes").apply { mkdirs() }

        val workflow = WorkflowDefinition(
            id = "wf-run",
            name = "run",
            description = "",
            nodes = listOf(
                NodeDefinition("n1", "start", "start", PositionDefinition(0, 0), NodeConfigDefinition()),
                NodeDefinition("n2", "code", "code", PositionDefinition(0, 0), NodeConfigDefinition(codeFile = "nodes/n2.py")),
                NodeDefinition("n3", "end", "end", PositionDefinition(0, 0), NodeConfigDefinition())
            ),
            edges = listOf(
                EdgeDefinition("e1", "n1", "n2"),
                EdgeDefinition("e2", "n2", "n3")
            ),
            variables = emptyMap()
        )

        File(workflowDir, "workflow.json").writeText(gson.toJson(workflow))
        File(nodesDir, "n2.py").writeText(
            """
def main(inputs):
    return {"value": 42}
            """.trimIndent()
        )

        val result = service.runWorkflow(workflowDir.absolutePath)

        assertTrue(result.success)
        assertTrue(result.logs.any { it.contains("工作流运行完成（实际执行）") })
        assertTrue(result.logs.any { it.contains("\"value\": 42") || it.contains("\"value\":42") })
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
