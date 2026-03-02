package com.limz26.workflow.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LLMClientExtractJsonTest {

    @Test
    fun `extract json from fenced block`() {
        val text = """
            Here is the workflow:
            ```json
            {"name":"demo","nodes":[{"id":"1"}]}
            ```
        """.trimIndent()

        val result = LLMClient.extractJsonFromText(text)

        assertEquals("{" + "\"name\":\"demo\",\"nodes\":[{\"id\":\"1\"}]}" , result)
    }

    @Test
    fun `extract first complete json object with nested braces`() {
        val text = "prefix {" +
            "\"name\":\"demo\"," +
            "\"variables\":{\"k\":{\"type\":\"string\"}}," +
            "\"nodes\":[]}" +
            " suffix"

        val result = LLMClient.extractJsonFromText(text)

        assertEquals(
            "{" +
                "\"name\":\"demo\"," +
                "\"variables\":{\"k\":{\"type\":\"string\"}}," +
                "\"nodes\":[]}",
            result
        )
    }
}
