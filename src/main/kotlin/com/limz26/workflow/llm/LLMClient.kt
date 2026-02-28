package com.limz26.workflow.llm

import com.intellij.openapi.components.service
import com.limz26.workflow.settings.AppSettings
import kotlinx.serialization.json.*
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * LLM 客户端 - 支持多 Provider
 */
class LLMClient {

    private val settings = service<AppSettings>()

    data class Message(
        val role: String,
        val content: String
    )

    /**
     * 发送对话请求
     */
    fun chat(messages: List<Message>): String {
        return when {
            settings.apiEndpoint.contains("openai") -> callOpenAI(messages)
            settings.apiEndpoint.contains("anthropic") -> callClaude(messages)
            settings.apiEndpoint.contains("moonshot") -> callKimi(messages)
            else -> callGeneric(messages)
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

    private fun callOpenAI(messages: List<Message>): String {
        val endpoint = buildChatCompletionsEndpoint(settings.apiEndpoint)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection)

        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = buildJsonObject {
                put("model", settings.model)
                put(
                    "messages",
                    JsonArray(messages.map {
                        buildJsonObject {
                            put("role", it.role)
                            put("content", it.content)
                        }
                    })
                )
                put("temperature", settings.temperature)
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
        } catch (e: Exception) {
            throw IllegalStateException("调用大模型服务失败。endpoint=$endpoint；原始错误: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun callClaude(_messages: List<Message>): String {
        // TODO: 实现 Claude API 调用
        return "Claude API not implemented yet"
    }

    private fun callKimi(_messages: List<Message>): String {
        // TODO: 实现 Kimi API 调用
        return "Kimi API not implemented yet"
    }

    private fun callGeneric(messages: List<Message>): String {
        // 通用 OpenAI 兼容格式
        return callOpenAI(messages)
    }


    private fun buildChatCompletionsEndpoint(rawEndpoint: String): String {
        val normalized = rawEndpoint.trim().removeSuffix("/")
        val withScheme = if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            normalized
        } else {
            "https://$normalized"
        }

        return if (withScheme.endsWith("/chat/completions")) {
            withScheme
        } else {
            "$withScheme/chat/completions"
        }
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
