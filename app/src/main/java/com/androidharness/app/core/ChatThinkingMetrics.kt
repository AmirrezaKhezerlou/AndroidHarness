package com.androidharness.app.core

import com.androidharness.app.agent.ThinkingTiming
import com.androidharness.app.agent.thinkingTokenCount

internal data class ChatThinkingTotals(val milliseconds: Long, val tokens: Long, val estimated: Boolean)

/** Parent reasoning only; the live request is excluded by callers once its committed row is visible. */
internal fun chatThinkingTotals(messages: List<ChatMessage>, streamingThinking: String, timing: ThinkingTiming?,
    reasoningTokens: Int?, now: Long): ChatThinkingTotals {
    val parents = messages.filter { it.role == Role.ASSISTANT && it.toolCallId == null }
    val counts = parents.mapNotNull { thinkingTokenCount(it.thinking, it.generation.reasoningTokens) } +
        listOfNotNull(thinkingTokenCount(streamingThinking, reasoningTokens))
    return ChatThinkingTotals(
        parents.sumOf { it.thinkingMs.coerceAtLeast(0) } + (timing?.durationMs(now) ?: 0),
        counts.sumOf { it.count.toLong() },
        counts.isEmpty() || counts.any { it.estimated },
    )
}

/** One decimal where needed, promoting rounded values at the next unit boundary. */
internal fun compactThinkingTokenCount(tokens: Long): String {
    val units = listOf("", "k", "m", "b", "t")
    var value = tokens.coerceAtLeast(0).toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    var rounded = kotlin.math.floor(value * 10 + 0.5) / 10
    if (rounded >= 1000 && unit < units.lastIndex) { rounded /= 1000; unit++ }
    val number = if (rounded % 1.0 == 0.0) rounded.toLong().toString()
        else String.format(java.util.Locale.ROOT, "%.1f", rounded)
    return number + units[unit]
}
