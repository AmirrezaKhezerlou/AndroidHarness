package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageData
import com.androidharness.app.core.Role

/** Attach recent pictures actually shown in assistant messages to the next Chat request. */
internal object ChatImageContext {
    private val markdownImage = Regex("!\\[([^\\]]*)]\\((https?://[^)\\s]+)\\)")

    suspend fun attach(history: List<ChatMessage>, load: suspend (String) -> ImageData?): List<ChatMessage> {
        val userIndex = history.indexOfLast { it.role == Role.USER }
        if (userIndex < 0) return history
        val available = (ContextHygiene.MAX_CONTEXT_IMAGES - history[userIndex].imageData.size).coerceAtLeast(0)
        if (available == 0) return history
        val urls = history.take(userIndex).asReversed().asSequence()
            .filter { it.role == Role.ASSISTANT && it.toolCallId == null }
            .flatMap { message -> markdownImage.findAll(message.text).map { it.groupValues[2] }.toList().asReversed().asSequence() }
            .distinct().take(available).toList()
        if (urls.isEmpty()) return history
        val found = urls.mapNotNull { url -> load(url)?.let { url to it } }
        val note = if (found.isEmpty()) "[Pictures shown earlier could not be loaded for visual inspection.]"
            else "[Images attached for context are pictures shown earlier in this chat, in this order: ${found.joinToString { it.first }}. They are not new user uploads.]"
        return history.toMutableList().also { result ->
            val user = result[userIndex]
            result[userIndex] = user.copy(text = user.text + "\n\n" + note, imageData = user.imageData + found.map { it.second })
        }
    }
}
