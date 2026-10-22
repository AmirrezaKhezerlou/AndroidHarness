package com.androidharness.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData

/** Chat shows readable activity, never the Code tool inspector or a work receipt. */
@Composable
internal fun ChatActivity(
    messages: List<ChatMessage>,
    live: Boolean = false,
    runningCalls: List<ToolCallData> = emptyList(),
    streamingThinking: String = "",
    writing: Boolean = false,
) {
    val thinking = remember(messages, streamingThinking) {
        (messages.filter { it.role == Role.ASSISTANT && it.toolCallId == null }.map { it.thinking } + streamingThinking)
            .filter(String::isNotBlank).distinct().joinToString("\n\n")
    }
    val calls = remember(messages) { messages.filter { it.role == Role.ASSISTANT && it.toolCallId == null }.flatMap { it.toolCalls }.distinctBy { it.id } }
    val seconds = messages.sumOf { it.thinkingMs }.coerceAtLeast(0) / 1000
    var expanded by rememberSaveable { mutableStateOf(false) }
    val active = runningCalls.lastOrNull()?.name
    val title = if (live) when {
        active != null -> activityLabel(active, true)
        writing -> stringResource(R.string.chat_writing_reply)
        else -> stringResource(R.string.chat_thinking)
    } else if (thinking.isNotBlank() || seconds > 0 || messages.any { (it.generation.reasoningTokens ?: 0) > 0 }) {
        if (seconds > 0) stringResource(R.string.chat_thought_seconds, seconds) else stringResource(R.string.chat_thought)
    } else null
    if (title == null && calls.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) {
            val header: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active in setOf("web_search", "web_fetch")) Icon(Icons.Outlined.Search, null, Modifier.size(17.dp), tint = scheme.onSurfaceVariant)
                    else Icon(painterResource(R.drawable.ic_chat_sparkles), null, Modifier.size(17.dp), tint = scheme.onSurfaceVariant)
                    ChatShimmerText(title, live)
                    if (thinking.isNotBlank()) Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                }
            }
            if (thinking.isNotBlank()) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(vertical = 4.dp, horizontal = 0.dp)) { header() }
            else Box(Modifier.heightIn(min = 32.dp), contentAlignment = Alignment.CenterStart) { header() }
        }
        AnimatedVisibility(expanded && thinking.isNotBlank()) {
            Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(start = 25.dp, bottom = 8.dp)) {
                Text(thinking, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        if (!live && calls.isNotEmpty()) {
            val failed = messages.filter { it.role == Role.TOOL && it.isError }.mapNotNull { it.toolCallId }.toSet()
            val labels = calls.filter { it.name != "offer_code_handoff" }.distinctBy { it.name }.map { call ->
                if (call.id in failed) stringResource(R.string.chat_activity_unavailable, activityLabel(call.name, false)) else activityLabel(call.name, false)
            }
            if (labels.isNotEmpty()) Text(labels.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun activityLabel(name: String, running: Boolean): String = stringResource(when (name) {
    "web_search" -> if (running) R.string.chat_searching else R.string.chat_searched
    "web_fetch" -> if (running) R.string.chat_reading_page else R.string.chat_read_page
    "create_artifact" -> if (running) R.string.chat_creating_artifact else R.string.chat_created_artifact
    "read_artifact" -> if (running) R.string.chat_reading_artifact else R.string.chat_read_artifact
    "read_chat_text" -> if (running) R.string.chat_reading_text else R.string.chat_read_text
    "offer_code_handoff" -> R.string.chat_preparing_code_option
    else -> if (running) R.string.chat_reviewing_sources else R.string.chat_reviewed_sources
})

@Composable
private fun ChatShimmerText(text: String, running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    // Compose's duration scale and the system animation preference both stop motion.
    val animationsEnabled = android.provider.Settings.Global.getFloat(
        androidx.compose.ui.platform.LocalContext.current.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    if (!running || !animationsEnabled) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        return
    }
    val transition = rememberInfiniteTransition(label = "Chat activity")
    val x by transition.animateFloat(-180f, 650f,
        infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "Chat text shimmer")
    Text(text, style = MaterialTheme.typography.labelLarge.copy(brush = Brush.linearGradient(
        listOf(scheme.onSurfaceVariant, scheme.onSurface, scheme.onSurfaceVariant),
        start = Offset(x, 0f), end = Offset(x + 160f, 0f))))
}
