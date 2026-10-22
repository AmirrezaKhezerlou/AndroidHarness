package com.androidharness.app.data

import com.androidharness.app.tools.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ConversationConfigTest {
    @Test fun legacySessionsKeepCodeAndMalformedPreferencesDoNotUnlockCodeTools() {
        assertEquals(ConversationKind.CODE, ConversationConfig.decode(null).kind)
        assertNull(ConversationConfig.decode(null).allowedTools)
        assertEquals(emptySet<String>(), ConversationConfig.decode("broken").allowedTools)
        assertEquals(emptySet<String>(), ConversationConfig.decode("{}").allowedTools)
        assertEquals(emptySet<String>(), ConversationConfig.decode("{\"kind\":\"FUTURE\"}").allowedTools)
    }

    @Test fun optionalToolsSurviveSerializationAndNeverIncludeWorkspaceTools() {
        val saved = ConversationConfig(ConversationKind.CHAT, toolsEnabled = true,
            selectedTools = setOf(ChatTool.WEB_SEARCH, ChatTool.ARTIFACTS), draftText = "An unsent idea")
        assertEquals(saved, ConversationConfig.decode(ConversationConfig.encode(saved)))
        assertEquals(setOf("web_search", "create_artifact", "read_artifact", "read_tool_output"), saved.allowedTools)
        assertEquals(emptySet<String>(), saved.copy(toolsEnabled = false).allowedTools)
        assertEquals(emptySet<String>(), saved.copy(selectedTools = emptySet()).allowedTools)
    }

    @Test fun restrictingRegistryBlocksBothAdvertisedAndExecutableToolsIncludingExtras() {
        fun tool(name: String) = object : Tool {
            override val name = name
            override val description = name
            override val parametersSchema = JsonObject(emptyMap())
            override val isReadOnly = true
            override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult(true, "done")
        }
        val base = ToolRegistry(listOf(tool("web_search"), tool("shell"), tool("task")))
        val merged = base.withExtra(listOf(tool("mcp_write")))
        val chat = merged.restrictedTo(setOf("web_search"))
        assertEquals(listOf("web_search"), chat.schemas().map { it.name })
        listOf("shell", "task", "mcp_write").forEach { assertNull(chat.get(it)) }
        assertTrue(merged.restrictedTo(emptySet()).schemas().isEmpty())
        assertSame(merged, merged.restrictedTo(null))
    }
}
