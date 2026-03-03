package com.limz26.workflow.mcp

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import java.net.InetSocketAddress
import java.net.Socket
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson

/**
 * Pure Kotlin MCP runtime that can be validated without IntelliJ startup.
 */
class WorkflowMcpRuntime(
    private val serverProvider: () -> Server
) {
    @Volatile
    private var engine: EmbeddedServer<*, *>? = null

    @Volatile
    private var runningPort: Int? = null

    fun isRunning(): Boolean = engine != null

    fun getRunningPort(): Int? = runningPort

    @Synchronized
    fun start(port: Int) {
        require(port in 1..65535) { "端口号无效: $port" }
        if (engine != null && runningPort == port) return
        stop()

        val appEngine = try {
            embeddedServer(CIO, host = "0.0.0.0", port = port) {
                install(ContentNegotiation) {
                    json(McpJson)
                }
                install(CORS) {
                    anyHost()
                    allowMethod(HttpMethod.Get)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Options)
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader(HttpHeaders.Authorization)
                    allowHeader(HttpHeaders.Accept)
                    allowHeader("mcp-session-id")
                    allowHeader("mcp-protocol-version")
                    allowHeader("last-event-id")
                    exposeHeader("mcp-session-id")
                }
                mcpStreamableHttp {
                    serverProvider()
                }
            }
        } catch (t: Throwable) {
            throw IllegalStateException("MCP 运行时依赖缺失或不兼容，请检查插件打包产物（${t::class.simpleName}: ${t.message}）", t)
        }

        try {
            appEngine.start(wait = false)
            waitForPortReady(port)
        } catch (t: Throwable) {
            throw IllegalStateException("MCP 服务启动失败（${t::class.simpleName}: ${t.message}）", t)
        }

        engine = appEngine
        runningPort = port
    }

    @Synchronized
    fun stop() {
        engine?.stop(500, 1000)
        engine = null
        runningPort = null
    }

    private fun waitForPortReady(port: Int, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                }
                return
            } catch (t: Throwable) {
                lastError = t
                Thread.sleep(50)
            }
        }
        throw IllegalStateException("MCP 服务端口未就绪: $port", lastError)
    }
}
