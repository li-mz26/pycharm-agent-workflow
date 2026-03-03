package com.limz26.workflow.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class WorkflowMcpClientConnectivityTest {

    @Test
    fun `streamable http client can connect and list tools`() = runBlocking {
        val server = Server(
            serverInfo = Implementation(name = "test-server", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        )
        server.addTool(
            name = "echo",
            description = "echo",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("text", buildJsonObject { put("type", "string") })
                }
            )
        ) { _ ->
            CallToolResult(content = listOf(TextContent("ok")))
        }

        val port = freePort()
        val runtime = WorkflowMcpRuntime { server }
        runtime.start(port)

        try {
            val httpClient = HttpClient(CIO) { install(SSE) }
            val client = Client(clientInfo = Implementation(name = "test-client", version = "1.0.0"))
            try {
                val transport = StreamableHttpClientTransport(
                    client = httpClient,
                    url = "http://127.0.0.1:$port/mcp"
                )
                client.connect(transport)
                val tools = client.listTools(ListToolsRequest()).tools
                assertTrue(tools.any { it.name == "echo" })
            } finally {
                client.close()
                httpClient.close()
            }
        } finally {
            runtime.stop()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
