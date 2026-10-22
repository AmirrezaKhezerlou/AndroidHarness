package com.androidharness.app.core

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Derived only from this turn's parent assistant calls and their matching results. */
internal object ChatActivityPresentation {
    data class Source(val title: String, val url: String, val host: String, val read: Boolean)
    data class Step(val name: String, val finished: Boolean, val failed: Boolean)

    fun calls(messages: List<ChatMessage>): List<ToolCallData> = messages
        .filter { it.role == Role.ASSISTANT && it.toolCallId == null }
        .flatMap { it.toolCalls }.distinctBy { it.id }

    fun steps(messages: List<ChatMessage>): List<Step> {
        val results = messages.filter { it.role == Role.TOOL }.associateBy { it.toolCallId }
        return calls(messages).filter { it.name != "offer_code_handoff" }.map {
            Step(it.name, results[it.id] != null, results[it.id]?.isError == true)
        }
    }

    /** True only when a remember call in this turn received a successful result. */
    fun savedMemory(messages: List<ChatMessage>): Boolean {
        val rememberIds = calls(messages).filter { it.name == "remember" }.map { it.id }.toSet()
        if (rememberIds.isEmpty()) return false
        return messages.any { it.role == Role.TOOL && it.toolCallId in rememberIds && !it.isError }
    }

    fun savedMemoryIds(messages: List<ChatMessage>): Set<String> {
        val ids = calls(messages).filter { it.name == "remember" }.map { it.id }.toSet()
        return messages.filter { it.role == Role.TOOL && !it.isError && it.toolCallId in ids }
            .mapNotNull { Regex("\\[memory_id:([a-zA-Z0-9-]+)]").find(it.text)?.groupValues?.get(1) }.toSet()
    }

    /** Once a turn calls any tool, streamed prose is working commentary, not the answer. */
    fun hasToolWork(messages: List<ChatMessage>): Boolean = calls(messages).isNotEmpty()

    fun activeCall(messages: List<ChatMessage>, running: List<ToolCallData>): ToolCallData? {
        val finished = messages.filter { it.role == Role.TOOL }.mapNotNull { it.toolCallId }.toSet()
        return running.lastOrNull { it.id !in finished } ?: calls(messages).lastOrNull { it.id !in finished }
    }

    sealed interface Entry {
        val key: String
        data class Thought(override val key: String, val text: String, val milliseconds: Long) : Entry
        data class Commentary(override val key: String, val text: String) : Entry
        data class Tool(override val key: String, val call: ToolCallData, val result: ChatMessage?) : Entry
    }

    /** Preserve model request boundaries: thought, commentary, calls, then the next request. */
    fun timeline(messages: List<ChatMessage>, running: List<ToolCallData> = emptyList(), streamingThinking: String = "", streamId: String = "live", streamingCommentary: String = ""): List<Entry> {
        val results = messages.filter { it.role == Role.TOOL }.associateBy { it.toolCallId }
        val parents = messages.withIndex().filter { it.value.role == Role.ASSISTANT && it.value.toolCallId == null }
        val answerIndex = parents.lastOrNull()?.takeIf { it.value.toolCalls.isEmpty() }?.index
        val seenCalls = hashSetOf<String>()
        return buildList {
            for ((index, message) in parents) {
                val key = message.id ?: "message-$index"
                if (message.thinking.isNotBlank() || message.thinkingMs > 0 || (message.generation.reasoningTokens ?: 0) > 0)
                    add(Entry.Thought("$key-thinking", message.thinking, message.thinkingMs))
                if (message.text.isNotBlank() && index != answerIndex) add(Entry.Commentary("$key-commentary", message.text))
                message.toolCalls.filter { it.name != "offer_code_handoff" }.forEach { call ->
                    if (seenCalls.add(call.id)) add(Entry.Tool("tool-${call.id}", call, results[call.id]))
                }
            }
            if (streamingThinking.isNotBlank()) add(Entry.Thought("$streamId-thinking", streamingThinking, 0))
            if (streamingCommentary.isNotBlank()) add(Entry.Commentary("$streamId-commentary", streamingCommentary))
            running.filter { it.name != "offer_code_handoff" && seenCalls.add(it.id) }.forEach {
                add(Entry.Tool("tool-${it.id}", it, results[it.id]))
            }
        }
    }

    fun sources(messages: List<ChatMessage>): List<Source> {
        val results = messages.filter { it.role == Role.TOOL && !it.isError }.associateBy { it.toolCallId }
        val sources = linkedMapOf<String, Source>()
        fun add(title: String, rawUrl: String, read: Boolean) {
            val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return
            if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return
            val url = uri.toASCIIString()
            val key = url.substringBefore('#').trimEnd('/')
            val existing = sources[key]
            if (existing != null) sources[key] = existing.copy(read = existing.read || read)
            else if (sources.size < 40) sources[key] = Source(title.trim().ifBlank { uri.host }.take(240), url, uri.host.removePrefix("www."), read)
        }
        for (call in calls(messages)) {
            val result = results[call.id] ?: continue
            when (call.name) {
                "web_search" -> {
                    // WebSearchTool's numbered title + standalone URL format. Do not scrape
                    // arbitrary links from snippets or error/fallback diagnostic messages.
                    val lines = result.text.lineSequence().take(300).toList()
                    for (index in 0 until lines.lastIndex) {
                        val title = Regex("^\\d+\\.\\s+(.+)$").matchEntire(lines[index].trim())?.groupValues?.get(1) ?: continue
                        add(title, lines[index + 1].trim(), false)
                    }
                }
                "web_fetch" -> if (result.text.isNotBlank() && !result.text.startsWith("[binary content:")) {
                    val url = runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject["url"]?.jsonPrimitive?.content }.getOrNull()
                    if (url != null) add("", url, true)
                }
            }
        }
        return sources.values.toList()
    }
}
