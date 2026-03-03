package com.limz26.workflow.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class WorkflowMcpRuntimeTest {

    @Test
    fun `runtime can start and expose streamable http endpoint without IntelliJ`() {
        val mcpServer = Server(
            serverInfo = Implementation(name = "test-server", version = "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities())
        )
        val runtime = WorkflowMcpRuntime(serverProvider = { mcpServer })
        val port = freePort()

        runtime.start(port)
        try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()

            val getReq = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:$port/mcp"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
            val getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString())

            // Streamable HTTP endpoint should be mounted; it may reject plain GET (405)
            // but should not be a missing route (404).
            assertTrue("GET /mcp should not be 404", getResp.statusCode() != 404)

            val postReq = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:$port/mcp"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()
            val postResp = client.send(postReq, HttpResponse.BodyHandlers.ofString())
            assertTrue("POST /mcp should not be 404", postResp.statusCode() != 404)
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `runtime responds to CORS preflight for inspector`() {
        val mcpServer = Server(
            serverInfo = Implementation(name = "test-server", version = "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities())
        )
        val runtime = WorkflowMcpRuntime(serverProvider = { mcpServer })
        val port = freePort()

        runtime.start(port)
        try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()

            val optionsReq = HttpRequest.newBuilder()
                .uri(URI("http://127.0.0.1:$port/mcp"))
                .timeout(Duration.ofSeconds(5))
                .header("Origin", "http://localhost:6274")
                .header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build()
            val optionsResp = client.send(optionsReq, HttpResponse.BodyHandlers.ofString())

            assertEquals("OPTIONS /mcp should pass CORS preflight", 200, optionsResp.statusCode())
            assertTrue(
                "CORS preflight should return Access-Control-Allow-Origin",
                optionsResp.headers().firstValue("Access-Control-Allow-Origin").isPresent
            )
        } finally {
            runtime.stop()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
