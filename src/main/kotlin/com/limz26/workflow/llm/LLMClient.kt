package com.limz26.workflow.llm

import com.intellij.openapi.components.service
import com.limz26.workflow.settings.AppSettings
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
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
        
        val response = chat(listOf(
            Message("system", systemPrompt),
            Message("user", userPrompt)
        ))
        
        return extractJson(response)
    }
    
    private fun callOpenAI(messages: List<Message>): String {
        val url = URL("${settings.apiEndpoint}/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val body = buildJsonObject {
                put("model", settings.model)
                put("messages", JsonArray(messages.map { 
                    buildJsonObject {
                        put("role", it.role)
                        put("content", it.content)
                    }
                }))
                put("temperature", settings.temperature)
            }.toString()
            
            conn.outputStream.write(body.toByteArray())
            
            val response = conn.inputStream.bufferedReader().readText()
            parseOpenAIResponse(response)
        } finally {
            conn.disconnect()
        }
    }
    
    private fun callClaude(messages: List<Message>): String {
        // TODO: 实现 Claude API 调用
        return "Claude API not implemented yet"
    }
    
    private fun callKimi(messages: List<Message>): String {
        // TODO: 实现 Kimi API 调用
        return "Kimi API not implemented yet"
    }
    
    private fun callGeneric(messages: List<Message>): String {
        // 通用 OpenAI 兼容格式
        return callOpenAI(messages)
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
        // 从文本中提取 JSON 部分
        val jsonRegex = "```json\\s*(\\{[\\s\\S]*?\\})\\s*```|\\{[\\s\\S]*?\\}".toRegex()
        return jsonRegex.find(text)?.groupValues?.get(1) 
            ?: jsonRegex.find(text)?.value 
            ?: text
    }
}
