package com.limz26.workflow.mcp

import com.google.gson.Gson
import com.limz26.workflow.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class WorkflowMcpServiceTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var service: WorkflowMcpService
    private val gson = Gson()

    @Before
    fun setUp() {
        service = WorkflowMcpService()
    }

    @After
    fun tearDown() {
        if (service.isRunning()) service.stopServer()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Creates a minimal valid workflow directory under [dir]. */
    private fun createWorkflowDir(dir: File, name: String = "Test Workflow"): File {
        dir.mkdirs()
        val def = WorkflowDefinition(
            id = "test-id",
            name = name,
            description = "Test",
            nodes = listOf(
                NodeDefinition("n1", "start", "开始", PositionDefinition(0, 0), NodeConfigDefinition()),
                NodeDefinition("n2", "code", "处理", PositionDefinition(0, 120), NodeConfigDefinition(code = "print('hi')")),
                NodeDefinition("n3", "end", "结束", PositionDefinition(0, 240), NodeConfigDefinition())
            ),
            edges = listOf(
                EdgeDefinition("e1", "n1", "n2"),
                EdgeDefinition("e2", "n2", "n3")
            ),
            variables = emptyMap()
        )
        File(dir, "workflow.json").writeText(gson.toJson(def))
        return dir
    }

    // ── listWorkflows ──────────────────────────────────────────────────────────

    @Test
    fun `listWorkflows returns empty when project has no workflows dir`() {
        val project = tempDir.newFolder("project")
        val result = service.listWorkflows(project.absolutePath)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `listWorkflows returns summary for each valid workflow dir`() {
        val project = tempDir.newFolder("project")
        val workflowsDir = File(project, "workflows").apply { mkdirs() }
        createWorkflowDir(File(workflowsDir, "wf1"), "Alpha")
        createWorkflowDir(File(workflowsDir, "wf2"), "Beta")

        val result = service.listWorkflows(project.absolutePath)

        assertEquals(2, result.size)
        val names = result.map { it.name }.toSet()
        assertTrue("Alpha" in names)
        assertTrue("Beta" in names)
        result.forEach { assertEquals(3, it.nodeCount) }
    }

    @Test
    fun `listWorkflows ignores dirs without workflow json`() {
        val project = tempDir.newFolder("project")
        val workflowsDir = File(project, "workflows").apply { mkdirs() }
        createWorkflowDir(File(workflowsDir, "valid"))
        File(workflowsDir, "empty_dir").mkdirs() // no JSON

        val result = service.listWorkflows(project.absolutePath)
        assertEquals(1, result.size)
    }

    // ── readWorkflowJson ───────────────────────────────────────────────────────

    @Test
    fun `readWorkflowJson returns raw file content`() {
        val wfDir = tempDir.newFolder("wf")
        createWorkflowDir(wfDir)

        val json = service.readWorkflowJson(wfDir.absolutePath)

        assertTrue(json.contains("Test Workflow"))
        assertTrue(json.contains("\"n1\""))
    }

    @Test
    fun `readWorkflowJson throws when workflow json is missing`() {
        val emptyDir = tempDir.newFolder("empty")
        assertThrows(IllegalArgumentException::class.java) {
            service.readWorkflowJson(emptyDir.absolutePath)
        }
    }

    // ── readNodeCodeFile ───────────────────────────────────────────────────────

    @Test
    fun `readNodeCodeFile returns code content for existing py file`() {
        val wfDir = tempDir.newFolder("wf")
        val nodesDir = File(wfDir, "nodes").apply { mkdirs() }
        File(nodesDir, "node_1.py").writeText("def main(inputs): return {}")

        val code = service.readNodeCodeFile(wfDir.absolutePath, "node_1")

        assertEquals("def main(inputs): return {}", code)
    }

    @Test
    fun `readNodeCodeFile throws when py file is absent`() {
        val wfDir = tempDir.newFolder("wf")
        assertThrows(IllegalArgumentException::class.java) {
            service.readNodeCodeFile(wfDir.absolutePath, "missing_node")
        }
    }

    // ── editWorkflow ───────────────────────────────────────────────────────────

    @Test
    fun `editWorkflow adds a new node and persists to disk`() {
        val wfDir = tempDir.newFolder("wf")
        createWorkflowDir(wfDir)

        val newNode = NodeDefinition(
            id = "n4", type = "variable", name = "新变量",
            position = PositionDefinition(0, 360), config = NodeConfigDefinition()
        )
        val request = WorkflowMcpService.WorkflowEditRequest(addNodes = listOf(newNode))

        val resultJson = service.editWorkflow(wfDir.absolutePath, request)
        val resultDef = gson.fromJson(resultJson, WorkflowDefinition::class.java)

        assertEquals(4, resultDef.nodes.size)
        assertTrue(resultDef.nodes.any { it.id == "n4" })
        // Verify persisted to disk
        val persisted = gson.fromJson(File(wfDir, "workflow.json").readText(), WorkflowDefinition::class.java)
        assertEquals(4, persisted.nodes.size)
    }

    @Test
    fun `editWorkflow removes specified node and its connected edges`() {
        val wfDir = tempDir.newFolder("wf")
        createWorkflowDir(wfDir)

        val request = WorkflowMcpService.WorkflowEditRequest(removeNodeIds = listOf("n2"))
        val resultJson = service.editWorkflow(wfDir.absolutePath, request)
        val resultDef = gson.fromJson(resultJson, WorkflowDefinition::class.java)

        assertEquals(2, resultDef.nodes.size)
        assertTrue(resultDef.nodes.none { it.id == "n2" })
        // Both e1 (n1→n2) and e2 (n2→n3) should be gone
        assertTrue(resultDef.edges.none { it.source == "n2" || it.target == "n2" })
    }

    @Test
    fun `editWorkflow updates existing node`() {
        val wfDir = tempDir.newFolder("wf")
        createWorkflowDir(wfDir)

        val updated = NodeDefinition(
            id = "n2", type = "code", name = "Updated Name",
            position = PositionDefinition(0, 120), config = NodeConfigDefinition(code = "# updated")
        )
        val request = WorkflowMcpService.WorkflowEditRequest(updateNodes = listOf(updated))
        val resultJson = service.editWorkflow(wfDir.absolutePath, request)
        val resultDef = gson.fromJson(resultJson, WorkflowDefinition::class.java)

        val node = resultDef.nodes.first { it.id == "n2" }
        assertEquals("Updated Name", node.name)
        assertEquals("# updated", node.config.code)
    }

    @Test
    fun `editWorkflow writes node code to py file`() {
        val wfDir = tempDir.newFolder("wf")
        createWorkflowDir(wfDir)

        val code = "def main(inputs):\n    return {'out': inputs['x'] * 2}"
        val request = WorkflowMcpService.WorkflowEditRequest(nodeCodeUpdates = mapOf("n2" to code))
        service.editWorkflow(wfDir.absolutePath, request)

        val pyFile = File(wfDir, "nodes/n2.py")
        assertTrue(pyFile.exists())
        assertEquals(code, pyFile.readText())
    }

    @Test
    fun `editWorkflow writes node prompt to md file`() {
        val wfDir = tempDir.newFolder("wf")
        createWorkflowDir(wfDir)

        val prompt = "# System Prompt\nYou are a helpful assistant."
        val request = WorkflowMcpService.WorkflowEditRequest(nodePromptUpdates = mapOf("n2" to prompt))
        service.editWorkflow(wfDir.absolutePath, request)

        val mdFile = File(wfDir, "nodes/n2_prompt.md")
        assertTrue(mdFile.exists())
        assertEquals(prompt, mdFile.readText())
    }

    @Test
    fun `editWorkflow deletes node files when node is removed`() {
        val wfDir = tempDir.newFolder("wf")
        createWorkflowDir(wfDir)
        val nodesDir = File(wfDir, "nodes").apply { mkdirs() }
        File(nodesDir, "n2.py").writeText("def main(inputs): return {}")
        File(nodesDir, "n2_config.json").writeText("{}")

        service.editWorkflow(wfDir.absolutePath, WorkflowMcpService.WorkflowEditRequest(removeNodeIds = listOf("n2")))

        assertFalse(File(nodesDir, "n2.py").exists())
        assertFalse(File(nodesDir, "n2_config.json").exists())
    }

    @Test
    fun `editWorkflow auto-generates id for edges without one`() {
        val wfDir = tempDir.newFolder("wf")
        createWorkflowDir(wfDir)

        val edgeWithBlankId = EdgeDefinition(id = "", source = "n1", target = "n3")
        val request = WorkflowMcpService.WorkflowEditRequest(addEdges = listOf(edgeWithBlankId))
        val resultJson = service.editWorkflow(wfDir.absolutePath, request)
        val resultDef = gson.fromJson(resultJson, WorkflowDefinition::class.java)

        val newEdge = resultDef.edges.first { it.source == "n1" && it.target == "n3" }
        assertTrue("Auto-generated id should be non-blank", newEdge.id.isNotBlank())
    }

    // ── runWorkflow ────────────────────────────────────────────────────────────

    @Test
    fun `runWorkflow returns success with logs for valid workflow`() {
        val wfDir = tempDir.newFolder("wf")
        createWorkflowDir(wfDir)

        val result = service.runWorkflow(wfDir.absolutePath)

        assertTrue("Should succeed for a valid workflow", result.success)
        assertTrue("Should have no validation errors", result.validationErrors.isEmpty())
        assertTrue("Logs should mention completion", result.logs.any { it.contains("完成") })
    }

    @Test
    fun `runWorkflow returns failure when directory does not exist`() {
        val result = service.runWorkflow("/nonexistent/path/does/not/exist")
        assertFalse(result.success)
        assertTrue(result.logs.any { it.contains("加载失败") })
    }

    @Test
    fun `runWorkflow returns validation errors for workflow without start node`() {
        val wfDir = tempDir.newFolder("wf")
        val def = WorkflowDefinition(
            id = "x", name = "Bad", description = "",
            nodes = listOf(
                NodeDefinition("n1", "code", "处理", PositionDefinition(0, 0), NodeConfigDefinition()),
                NodeDefinition("n2", "end", "结束", PositionDefinition(0, 120), NodeConfigDefinition())
            ),
            edges = listOf(EdgeDefinition("e1", "n1", "n2")),
            variables = emptyMap()
        )
        File(wfDir, "workflow.json").writeText(gson.toJson(def))

        val result = service.runWorkflow(wfDir.absolutePath)

        assertFalse(result.success)
        assertTrue(result.validationErrors.any { it.contains("开始") })
    }

    // ── Server lifecycle ───────────────────────────────────────────────────────

    @Test
    fun `isRunning reflects server state`() {
        assertFalse(service.isRunning())
        assertNull(service.getRunningPort())

        service.startServer(18765)
        assertTrue(service.isRunning())
        assertEquals(18765, service.getRunningPort())

        service.stopServer()
        assertFalse(service.isRunning())
        assertNull(service.getRunningPort())
    }

    @Test
    fun `startServer is idempotent for same port`() {
        service.startServer(18766)
        service.startServer(18766) // must not throw or start a second engine
        assertTrue(service.isRunning())
        assertEquals(18766, service.getRunningPort())
    }

    @Test
    fun `startServer rejects invalid ports`() {
        assertThrows(IllegalArgumentException::class.java) { service.startServer(0) }
        assertThrows(IllegalArgumentException::class.java) { service.startServer(65536) }
    }

    @Test
    fun `stopServer is safe when server is not running`() {
        service.stopServer() // must not throw
        assertFalse(service.isRunning())
    }

    // ── HTTP connectivity (requires both fixes: correct path + fresh Server per connection) ────

    @Test
    fun `MCP SSE endpoint responds after server starts`() {
        val port = 18767
        service.startServer(port)
        // Ktor CIO binds the socket synchronously inside start(), so a short wait is sufficient
        Thread.sleep(300)

        try {
            // The MCP SDK registers the SSE endpoint at root (/), not at /mcp.
            // GET / opens an SSE stream; server returns 200 immediately with the response headers.
            val conn = URL("http://localhost:$port/").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            val status = conn.responseCode
            assertEquals("SSE endpoint at root (/) must return 200", 200, status)
        } finally {
            service.stopServer()
        }
    }

    @Test
    fun `MCP server accepts multiple SSE connections after singleton Server fix`() {
        val port = 18768
        service.startServer(port)
        Thread.sleep(300)

        // Each SSE connection should get a fresh Server instance (regression: singleton Server
        // had terminal state after first connection, blocking all subsequent connects).
        fun sseConnect(): Int {
            val conn = URL("http://localhost:$port/").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            return conn.responseCode
        }

        try {
            val first = sseConnect()
            assertEquals("First SSE connection must return 200", 200, first)

            val second = sseConnect()
            assertEquals("Second SSE connection must also return 200 (regression: singleton Server bug)", 200, second)
        } finally {
            service.stopServer()
        }
    }
}
