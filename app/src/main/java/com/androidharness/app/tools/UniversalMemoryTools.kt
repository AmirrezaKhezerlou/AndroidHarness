package com.androidharness.app.tools

import com.androidharness.app.data.MemoryCategory
import com.androidharness.app.data.MemoryRepository
import com.androidharness.app.data.MemoryScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Saves durable facts to the universal memory shared by Chat and Code. The
 * workspace memory_write tool stays separate for project notes on disk.
 */
class RememberTool(private val memory: MemoryRepository) : Tool {
    override val name = "remember"
    override val description =
        "Save a durable fact to memory so it is available in future Chat and Code conversations. " +
            "Use it for stable user preferences, ongoing work, decisions, and reusable project facts. " +
            "Keep each entry short and standalone. Do not save secrets, credentials, transient details, " +
            "or long code. Use recall first when you may be repeating or correcting an existing memory."
    override val parametersSchema = Schema.obj(
        mapOf(
            "text" to Schema.string("The fact to remember, written as a short standalone statement."),
            "scope" to Schema.string(
                "'global' applies to every conversation, 'project' only to the current project, " +
                    "'auto' (default) uses the project when this conversation has one and global otherwise.",
            ),
            "category" to Schema.string(
                "One of profile, preference, project, decision, or fact (default fact).",
            ),
        ),
        required = listOf("text"),
    )
    override val isReadOnly = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!memory.isEnabled()) throw ToolFailure("Memory is off in Settings > Chat Option.")
        val text = args["text"]?.jsonPrimitive?.content
            ?: throw ToolFailure("Missing required argument: text")
        val category = MemoryCategory.fromStored(args["category"]?.jsonPrimitive?.content)
        val requested = when (args["scope"]?.jsonPrimitive?.content?.trim()?.lowercase()) {
            "global" -> MemoryScope.GLOBAL
            "project" -> MemoryScope.PROJECT
            // Stable user traits belong everywhere even when the chat has a project.
            else -> if (category in setOf(MemoryCategory.PROFILE, MemoryCategory.PREFERENCE) ||
                memory.projectIdForSession(ctx.sessionId) == null) MemoryScope.GLOBAL
            else MemoryScope.PROJECT
        }
        val saved = memory.remember(text, category, requested, ctx.sessionId)
            ?: throw ToolFailure("The memory was empty.")
        val actual = if (saved.scope == MemoryScope.PROJECT.name) "project" else "global"
        val downgraded = requested == MemoryScope.PROJECT && actual == "global"
        return ToolResult(
            true,
            if (downgraded) "This conversation has no project, so the memory was saved globally."
            else "Saved to $actual memory.",
        )
    }
}

/** Searches the same universal memory from either section. */
class RecallTool(private val memory: MemoryRepository) : Tool {
    override val name = "recall"
    override val description =
        "Search saved universal memories shared by Chat and Code. Use it when the user refers to " +
            "something from an earlier conversation or when stored preferences or project facts would help. " +
            "With no query, returns the most recently saved memories."
    override val parametersSchema = Schema.obj(
        mapOf("query" to Schema.string("Optional text to find in saved memories.")),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!memory.isEnabled()) throw ToolFailure("Memory is off in Settings > Chat Option.")
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        val results = memory.search(query, ctx.sessionId)
        if (results.isEmpty()) {
            return ToolResult(true, "No saved memories matched" + if (query.isEmpty()) "." else " '$query'.")
        }
        return ToolResult(
            true,
            results.joinToString("\n") { memory ->
                val scope = if (memory.scope == MemoryScope.PROJECT.name) "project" else "global"
                "- [$scope/${memory.category.lowercase()}] ${memory.text}"
            },
        )
    }
}
