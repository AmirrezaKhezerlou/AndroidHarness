package com.androidharness.app.core

/** Pure turn lookups shared by the chat UI and its JVM tests. */
object ChatTurnActions {
    /** The question that produced [turnId], used when regenerating its answer. */
    fun userMessageFor(messages: List<ChatMessage>, turnId: String?): ChatMessage? =
        turnId?.let { id -> messages.lastOrNull { it.role == Role.USER && it.turnId == id } }
}
