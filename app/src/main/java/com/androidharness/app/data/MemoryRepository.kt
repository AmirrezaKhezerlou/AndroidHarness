package com.androidharness.app.data

import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

enum class MemoryScope {
    GLOBAL, PROJECT;

    companion object {
        fun fromStored(value: String?): MemoryScope = entries.firstOrNull { it.name == value } ?: GLOBAL
    }
}

enum class MemoryCategory {
    PROFILE, PREFERENCE, PROJECT, DECISION, FACT;

    companion object {
        fun fromStored(value: String?): MemoryCategory =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: FACT
    }
}

internal object MemoryText {
    const val MAX_ENTRY_CHARS = 600

    fun normalize(raw: String): String? {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        return text.take(MAX_ENTRY_CHARS)
    }
}

/**
 * Builds the small memory block injected into a request. Individual entries are
 * kept for accuracy; only the highest-value ones enter the prompt, so a long
 * memory list cannot quietly turn into a second system prompt.
 */
internal object MemoryPrompt {
    const val MAX_CHARS = 5_000

    data class Block(val text: String, val usedIds: List<String>, val omitted: Int)

    fun build(memories: List<MemoryEntity>, maxChars: Int = MAX_CHARS): Block {
        val sorted = memories.filter { it.enabled }.sortedWith(
            compareByDescending<MemoryEntity> { it.pinned }
                .thenByDescending { it.scope == MemoryScope.PROJECT.name }
                .thenByDescending { maxOf(it.lastUsedAt, it.updatedAt) }
                .thenByDescending { it.useCount }
                .thenByDescending { it.updatedAt },
        )
        val text = StringBuilder()
        val used = mutableListOf<String>()
        var omitted = 0
        for (memory in sorted) {
            val line = "- [${memory.category.lowercase()}] ${memory.text}\n"
            if (text.length + line.length > maxChars) {
                omitted++
                continue
            }
            text.append(line)
            used += memory.id
        }
        if (omitted > 0) {
            val note = "- $omitted more saved memories are available with recall(query).\n"
            if (text.length + note.length <= maxChars) text.append(note)
        }
        return Block(text.toString().trim(), used, omitted)
    }

    fun approxTokens(chars: Int): Int = (chars / 4).coerceAtLeast(1)
}

/**
 * App-owned memory shared by Chat and Code. It is independent of providers and
 * of the workspace files used for project notes.
 */
class MemoryRepository(private val db: AppDatabase, private val settings: SettingsRepository) {
    private val dao get() = db.dao()

    val memories: Flow<List<MemoryEntity>> get() = dao.memoriesFlow()

    suspend fun isEnabled(): Boolean = settings.settings.first().memoryEnabled

    suspend fun projectIdForSession(sessionId: String?): String? = sessionId
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { dao.projectIdForSession(it) }.getOrNull() }

    /** Null when memory is off or nothing applies to this conversation. */
    suspend fun promptBlock(sessionId: String?): String? {
        if (!isEnabled()) return null
        val projectId = projectIdForSession(sessionId)
        val block = MemoryPrompt.build(dao.activeMemories(projectId))
        if (block.text.isBlank()) return null
        if (block.usedIds.isNotEmpty()) dao.markMemoriesUsed(block.usedIds, System.currentTimeMillis())
        return block.text
    }

    suspend fun remember(
        text: String,
        category: MemoryCategory,
        requestedScope: MemoryScope,
        sessionId: String?,
    ): MemoryEntity? = save(text, category, requestedScope, projectIdForSession(sessionId), sessionId)

    suspend fun add(
        text: String,
        category: MemoryCategory,
        requestedScope: MemoryScope,
        projectId: String?,
    ): MemoryEntity? = save(text, category, requestedScope, projectId, null)

    private suspend fun save(
        text: String,
        category: MemoryCategory,
        requestedScope: MemoryScope,
        projectId: String?,
        sessionId: String?,
    ): MemoryEntity? {
        val clean = MemoryText.normalize(text) ?: return null
        val scope = if (requestedScope == MemoryScope.PROJECT && projectId != null) MemoryScope.PROJECT else MemoryScope.GLOBAL
        val now = System.currentTimeMillis()
        val existing = dao.findMemory(clean, scope.name, projectId)
        val memory = if (existing != null) {
            existing.copy(text = clean, category = category.name, updatedAt = now, enabled = true)
        } else {
            MemoryEntity(
                id = UUID.randomUUID().toString(),
                text = clean,
                category = category.name,
                scope = scope.name,
                projectId = projectId,
                sourceSessionId = sessionId,
                createdAt = now,
                updatedAt = now,
            )
        }
        dao.upsertMemory(memory)
        trim()
        return memory
    }

    suspend fun update(memory: MemoryEntity, text: String, category: MemoryCategory, scope: MemoryScope, projectId: String?) {
        val clean = MemoryText.normalize(text) ?: return
        val actualProject = if (scope == MemoryScope.PROJECT) projectId else null
        val actualScope = if (actualProject != null) MemoryScope.PROJECT else MemoryScope.GLOBAL
        dao.updateMemory(memory.id, clean, category.name, actualScope.name, actualProject, System.currentTimeMillis())
    }

    suspend fun remove(id: String) = dao.deleteMemory(id)

    suspend fun clearAll() = dao.deleteAllMemories()

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setMemoryEnabled(id, enabled, System.currentTimeMillis())
        if (enabled) trim()
    }

    suspend fun setPinned(id: String, pinned: Boolean) = dao.setMemoryPinned(id, pinned, System.currentTimeMillis())

    /** Active memories only. Blank query returns the most recent entries. */
    suspend fun search(query: String, sessionId: String?): List<MemoryEntity> {
        if (!isEnabled()) return emptyList()
        val projectId = projectIdForSession(sessionId)
        val active = dao.activeMemories(projectId)
        val needle = query.trim()
        return if (needle.isEmpty()) active.take(20)
        else active.filter { it.text.contains(needle, ignoreCase = true) || it.category.contains(needle, ignoreCase = true) }.take(40)
    }

    /** Keep the store bounded without deleting anything: oldest unpinned entries are disabled. */
    private suspend fun trim() {
        val enabled = dao.enabledMemories()
        if (enabled.size <= MAX_ENABLED) return
        val now = System.currentTimeMillis()
        enabled.filterNot { it.pinned }
            .sortedWith(compareBy<MemoryEntity> { it.lastUsedAt }.thenBy { it.updatedAt })
            .take(enabled.size - MAX_ENABLED)
            .forEach { dao.setMemoryEnabled(it.id, false, now) }
    }

    companion object {
        const val MAX_ENABLED = 400
        val toolNames = setOf("remember", "recall")
    }
}
