package com.limz26.workflow.llm

import com.intellij.openapi.components.service
import com.limz26.workflow.settings.AppSettings
import kotlinx.serialization.json.*
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import javax.net.ssl.SSLException

/**
 * LLM 客户端 - 支持多 Provider
 */
class LLMClient {

    private val settings = service<AppSettings>()

    data class Message(
        val role: String,
        val content: String
    )

    data class ChatConfig(
        val apiEndpoint: String,
        val apiKey: String,
        val model: String,
        val temperature: Double
    )

    /**
     * 发送对话请求
     */
    fun chat(messages: List<Message>): String {
        return chat(messages, ChatConfig(settings.apiEndpoint, settings.apiKey, settings.model, settings.temperature))
    }

    fun chat(messages: List<Message>, config: ChatConfig): String {
        return when {
            config.apiEndpoint.contains("openai") -> callOpenAI(messages, config)
            config.apiEndpoint.contains("anthropic") -> callClaude(messages, config)
            config.apiEndpoint.contains("moonshot") -> callKimi(messages, config)
            else -> callGeneric(messages, config)
        }
    }

    /**
     * 生成工作流 DSL
     */
    fun generateWorkflowDSL(userPrompt: String): String {
        val systemPrompt = """
            你是一个工作流生成助手。请将用户的需求转换为 JSON 格式的工作流定义。

            支持的节点类型：
            - start: 开始节点
            - end: 结束节点
            - code: Python 代码执行节点
            - agent: LLM Agent 节点
            - condition: 条件分支节点
            - http: HTTP 请求节点
            - variable: 变量节点

            输出格式必须是合法的 JSON，包含：
            {
              "name": "工作流名称",
              "description": "描述",
              "nodes": [...],
              "edges": [...],
              "variables": {...}
            }

            用户输入：$userPrompt
        """.trimIndent()

        val response = chat(
            listOf(
                Message("system", systemPrompt),
                Message("user", userPrompt)
            )
        )

        return extractJson(response)
    }

    private fun callOpenAI(messages: List<Message>, config: ChatConfig): String {
        val endpoint = buildChatCompletionsEndpoint(config.apiEndpoint)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection)

        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = buildJsonObject {
                put("model", config.model)
                put(
                    "messages",
                    JsonArray(messages.map {
                        buildJsonObject {
                            put("role", it.role)
                            put("content", it.content)
                        }
                    })
                )
                put("temperature", config.temperature)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }

            val statusCode = conn.responseCode
            val responseBody = if (statusCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            if (statusCode !in 200..299) {
                throw IllegalStateException("OpenAI API request failed: HTTP $statusCode, body=$responseBody")
            }

            parseOpenAIResponse(responseBody)
        } catch (e: ConnectException) {
            throw IllegalStateException(
                "无法连接到大模型服务。endpoint=$endpoint；请确认 API Endpoint 可访问、端口未被拦截、代理/VPN 配置正确。原始错误: ${e.message}",
                e
            )
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException(
                "请求大模型服务超时。endpoint=$endpoint；请检查网络延迟或服务端负载。原始错误: ${e.message}",
                e
            )
        } catch (e: SSLException) {
            throw IllegalStateException(
                "TLS/SSL 握手失败。endpoint=$endpoint；如果你连接的是本地 OpenAI 兼容服务（如 localhost/127.0.0.1），请把 API Endpoint 改为 http://... 而不是 https://...。原始错误: ${e.message}",
                e
            )
        } catch (e: Exception) {
            throw IllegalStateException("调用大模型服务失败。endpoint=$endpoint；原始错误: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun callClaude(_messages: List<Message>, _config: ChatConfig): String {
        return "Claude API not implemented yet"
    }

    private fun callKimi(_messages: List<Message>, _config: ChatConfig): String {
        return "Kimi API not implemented yet"
    }

    private fun callGeneric(messages: List<Message>, config: ChatConfig): String {
        return callOpenAI(messages, config)
    }


    private fun buildChatCompletionsEndpoint(rawEndpoint: String): String {
        val cleaned = rawEndpoint.trim().removeSuffix("/")
        require(cleaned.isNotEmpty()) { "API Endpoint 不能为空" }

        val withScheme = if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            cleaned
        } else {
            val host = cleaned.substringBefore('/').lowercase()
            val useHttp = host == "localhost" || host.startsWith("127.") ||
                host.startsWith("10.") || host.startsWith("192.168.") ||
                host.matches(Regex("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))
            if (useHttp) "http://$cleaned" else "https://$cleaned"
        }

        if (withScheme.endsWith("/chat/completions")) return withScheme

        val uri = URI(withScheme)
        val path = (uri.path ?: "").trimEnd('/')

        val finalPath = when {
            path.endsWith("/chat/completions") -> path
            path.isEmpty() -> "/v1/chat/completions"
            path.endsWith("/v1") -> "$path/chat/completions"
            else -> "$path/chat/completions"
        }

        val queryPart = if (uri.query.isNullOrBlank()) "" else "?${uri.query}"
        val portPart = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme}://${uri.host}$portPart$finalPath$queryPart"
    }

    private fun parseOpenAIResponse(json: String): String {
        return try {
            val element = Json.parseToJsonElement(json)
            element.jsonObject["choices"]
                ?.jsonArray?.get(0)
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            "Error parsing response: ${e.message}"
        }
    }

    private fun extractJson(text: String): String {
        return extractJsonFromText(text)
    }

    companion object {
        internal fun extractJsonFromText(text: String): String {
            val fenced = Regex("```json\\s*(\\{[\\s\\S]*})\\s*```", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
            if (!fenced.isNullOrEmpty()) {
                return fenced
            }

            val start = text.indexOf('{')
            if (start == -1) return text

            var depth = 0
            var inString = false
            var escaping = false

            for (i in start until text.length) {
                val c = text[i]

                if (inString) {
                    if (escaping) {
                        escaping = false
                    } else if (c == '\\') {
                        escaping = true
                    } else if (c == '"') {
                        inString = false
                    }
                    continue
                }

                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return text.substring(start, i + 1)
                        }
                    }
                }
            }

            return text
        }
    }
}
