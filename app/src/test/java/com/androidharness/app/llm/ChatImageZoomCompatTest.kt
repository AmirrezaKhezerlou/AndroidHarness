package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageData
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ChatImageZoomCompatTest {
    @Test fun closeUpFollowsAllToolResultsAsAnImageUserContentPart() {
        val provider = OpenAiCompatProvider(okhttp3.OkHttpClient(), ProviderFactory.json)
        val config = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "gpt-4o")
        val body = provider.buildRequestBody(config, "system", listOf(
            ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("one", "zoom_chat_image", "{}"), ToolCallData("two", "read_chat_text", "{}"))),
            ChatMessage(Role.TOOL, text = "close-up", toolCallId = "one", toolName = "zoom_chat_image", imageData = listOf(ImageData("image/png", "detail"))),
            ChatMessage(Role.TOOL, text = "text", toolCallId = "two", toolName = "read_chat_text"),
        ), emptyList(), RequestOptions())
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "assistant", "tool", "tool", "user"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals("close-up", messages[2].getValue("content").jsonPrimitive.content)
        val parts = messages.last().getValue("content").jsonArray.map { it.jsonObject }
        assertEquals("image_url", parts[1].getValue("type").jsonPrimitive.content)
        assertEquals("data:image/png;base64,detail", parts[1].getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
    }
}
