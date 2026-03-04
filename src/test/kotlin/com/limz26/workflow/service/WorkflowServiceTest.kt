package com.limz26.workflow.service

import com.google.gson.GsonBuilder
import com.limz26.workflow.model.*
import org.junit.Assert.assertFalse
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

    @Test
    fun `run workflow branch node routes by switch case`() {
        val service = WorkflowService()
        val workflowDir = Files.createTempDirectory("workflow-service-condition-test").toFile()
        val nodesDir = File(workflowDir, "nodes").apply { mkdirs() }

        val workflow = WorkflowDefinition(
            id = "wf-service-condition",
            name = "service-condition",
            description = "",
            nodes = listOf(
                NodeDefinition("start", "start", "start", PositionDefinition(0, 0), NodeConfigDefinition()),
                NodeDefinition("code_prepare", "code", "prepare", PositionDefinition(0, 0), NodeConfigDefinition(codeFile = "nodes/prepare.py")),
                NodeDefinition(
                    "branch_001",
                    "branch",
                    "branch",
                    PositionDefinition(0, 0),
                    NodeConfigDefinition(branchField = "status", branchCases = mapOf("has_data" to "agent_001", "empty" to "code_002"))
                ),
                NodeDefinition("agent_001", "code", "agent_001", PositionDefinition(0, 0), NodeConfigDefinition(codeFile = "nodes/agent.py")),
                NodeDefinition("code_002", "code", "code_002", PositionDefinition(0, 0), NodeConfigDefinition(codeFile = "nodes/code2.py")),
                NodeDefinition("end", "end", "end", PositionDefinition(0, 0), NodeConfigDefinition())
            ),
            edges = listOf(
                EdgeDefinition("e1", "start", "code_prepare"),
                EdgeDefinition("e2", "code_prepare", "branch_001"),
                EdgeDefinition("e3", "branch_001", "agent_001", "has_data"),
                EdgeDefinition("e4", "branch_001", "code_002", "empty"),
                EdgeDefinition("e5", "agent_001", "end"),
                EdgeDefinition("e6", "code_002", "end")
            ),
            variables = emptyMap()
        )

        File(workflowDir, "workflow.json").writeText(gson.toJson(workflow))
        File(nodesDir, "prepare.py").writeText(
            """
def main(inputs):
    return {"status": "empty", "cleaned_data": []}
            """.trimIndent()
        )
        File(nodesDir, "agent.py").writeText(
            """
def main(inputs):
    return {"branch": "agent"}
            """.trimIndent()
        )
        File(nodesDir, "code2.py").writeText(
            """
def main(inputs):
    return {"branch": "fallback"}
            """.trimIndent()
        )

        val result = service.runWorkflow(workflowDir.absolutePath)

        assertTrue(result.success)
        assertTrue(result.logs.any { it.contains("分支命中") })
        assertTrue(result.logs.any { it.contains("执行节点: code_002") })
        assertFalse(result.logs.any { it.contains("执行节点: agent_001") })
    }

    @Test
    fun `run workflow fails when agent config file is missing`() {
        val service = WorkflowService()
        val workflowDir = Files.createTempDirectory("workflow-service-agent-missing-config-test").toFile()

        val workflow = WorkflowDefinition(
            id = "wf-service-agent-missing-config",
            name = "service-agent-missing-config",
            description = "",
            nodes = listOf(
                NodeDefinition("start", "start", "start", PositionDefinition(0, 0), NodeConfigDefinition()),
                NodeDefinition("agent_001", "agent", "agent", PositionDefinition(0, 0), NodeConfigDefinition(agentConfigFile = "nodes/agent_001_config.json")),
                NodeDefinition("end", "end", "end", PositionDefinition(0, 0), NodeConfigDefinition())
            ),
            edges = listOf(
                EdgeDefinition("e1", "start", "agent_001"),
                EdgeDefinition("e2", "agent_001", "end")
            ),
            variables = emptyMap()
        )

        File(workflowDir, "workflow.json").writeText(gson.toJson(workflow))

        val result = service.runWorkflow(workflowDir.absolutePath)

        assertFalse(result.success)
        assertTrue(result.validationErrors.any { it.contains("agent 节点配置文件不存在") })
    }

}
