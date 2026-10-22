package com.androidharness.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
enum class ConversationKind { CODE, CHAT }

@Serializable
enum class ChatTool { WEB_SEARCH, WEB_PAGES, ARTIFACTS }

/** Independent of PLAN/ACT and of approvals. Old sessions remain Code. */
@Serializable
data class ConversationConfig(
    val kind: ConversationKind = ConversationKind.CODE,
    val toolsEnabled: Boolean = false,
    val selectedTools: Set<ChatTool> = setOf(ChatTool.WEB_SEARCH, ChatTool.WEB_PAGES),
    val sourceChatId: String? = null,
    val draftText: String = "",
) {
    val isChat: Boolean get() = kind == ConversationKind.CHAT

    /** Null leaves Code's registry unchanged; an empty set prevents every Chat tool. */
    val allowedTools: Set<String>? get() = if (!isChat) null else if (!toolsEnabled) emptySet() else buildSet {
        if (ChatTool.WEB_SEARCH in selectedTools) add("web_search")
        if (ChatTool.WEB_PAGES in selectedTools) add("web_fetch")
        if (ChatTool.ARTIFACTS in selectedTools) addAll(setOf("create_artifact", "read_artifact"))
        if (isNotEmpty()) add("read_tool_output")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(value: String?): ConversationConfig = if (value == null) ConversationConfig() else
            runCatching {
                val fields = json.parseToJsonElement(value).jsonObject
                require("kind" in fields)
                json.decodeFromJsonElement<ConversationConfig>(fields)
            }
                // Unknown or damaged configuration must never unlock coding tools.
                .getOrElse { ConversationConfig(kind = ConversationKind.CHAT) }
        fun encode(value: ConversationConfig): String = json.encodeToString(value)
    }
}
