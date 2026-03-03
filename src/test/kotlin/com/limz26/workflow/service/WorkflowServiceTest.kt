package com.limz26.workflow.service

import com.google.gson.GsonBuilder
import com.limz26.workflow.model.*
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkflowServiceTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun `run workflow executes code node in workflow service`() {
        val service = WorkflowService()
        val workflowDir = Files.createTempDirectory("workflow-service-run-test").toFile()
        val nodesDir = File(workflowDir, "nodes").apply { mkdirs() }

        val workflow = WorkflowDefinition(
            id = "wf-service-run",
            name = "service-run",
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
    return {"service_value": 7}
            """.trimIndent()
        )

        val result = service.runWorkflow(workflowDir.absolutePath)

        assertTrue(result.success)
        assertTrue(result.logs.any { it.contains("工作流运行完成（实际执行）") })
        assertTrue(result.logs.any { it.contains("service_value") })
    }
    @Test
    fun `run workflow accepts initial input map`() {
        val service = WorkflowService()
        val workflowDir = Files.createTempDirectory("workflow-service-input-test").toFile()
        val nodesDir = File(workflowDir, "nodes").apply { mkdirs() }

        val workflow = WorkflowDefinition(
            id = "wf-service-input",
            name = "service-input",
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
    return {"echo": inputs.get("user_input")}
            """.trimIndent()
        )

        val result = service.runWorkflow(workflowDir.absolutePath, mapOf("user_input" to "hello"))

        assertTrue(result.success)
        assertTrue(result.logs.any { it.contains("hello") })
    }

}
