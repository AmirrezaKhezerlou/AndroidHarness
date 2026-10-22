package com.androidharness.app.ui.chat.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.ChatActivityPresentation
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData

/** One chronological disclosure for a Chat turn; the final answer stays outside. */
@Composable
internal fun ChatActivity(
    messages: List<ChatMessage>,
    live: Boolean = false,
    streamingThinking: String = "",
    writing: Boolean = false,
    runningCalls: List<ToolCallData> = emptyList(),
    streamId: String = "live",
    streamingCommentary: String = "",
    onOpenUrl: (String) -> Unit = {},
) {
    val entries = remember(messages, runningCalls, streamingThinking, streamId, streamingCommentary) {
        ChatActivityPresentation.timeline(messages, runningCalls, streamingThinking, streamId, streamingCommentary)
    }
    if (!live && entries.isEmpty()) return
    val active = remember(messages, runningCalls) { ChatActivityPresentation.activeCall(messages, runningCalls) }
    val seconds = messages.sumOf { it.thinkingMs }.coerceAtLeast(0) / 1000
    val title = when {
        live && active != null -> activityLabel(active.name, true)
        live && writing -> stringResource(R.string.chat_writing_reply)
        live -> stringResource(R.string.chat_thinking)
        seconds > 0 -> stringResource(R.string.chat_thought_seconds, seconds)
        else -> stringResource(R.string.chat_thought)
    }
    ChatActivityRow(title, if (live) active?.name else null, live, entries.isNotEmpty(), followLatest = true) {
        entries.forEach { entry -> key(entry.key) {
            when (entry) {
                is ChatActivityPresentation.Entry.Thought -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (entry.milliseconds >= 1000) stringResource(R.string.chat_thought_seconds, entry.milliseconds / 1000)
                        else stringResource(R.string.chat_thought), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.text.isNotBlank()) Text(entry.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ChatActivityPresentation.Entry.Commentary -> Text(com.androidharness.app.core.response.RichResponse.plainText(
                    com.androidharness.app.core.response.ChatRichResponse.normalize(entry.text)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ChatActivityPresentation.Entry.Tool -> ChatToolActivity(entry.call, entry.result,
                    running = live && entry.result == null, onOpenUrl)
            }
        } }
    }
}

/** A tool owns its own result disclosure, rather than being appended to a thinking block. */
@Composable
internal fun ChatToolActivity(call: ToolCallData, result: ChatMessage?, running: Boolean, onOpenUrl: (String) -> Unit) {
    if (call.name == "offer_code_handoff") return
    val title = when {
        running -> activityLabel(call.name, true)
        result?.isError == true -> stringResource(when (call.name) {
            "web_search" -> R.string.chat_search_failed
            "web_fetch" -> R.string.chat_page_failed
            else -> R.string.chat_tool_failed
        })
        result != null -> activityLabel(call.name, false)
        else -> stringResource(R.string.chat_activity_interrupted)
    }
    val sources = remember(call, result) { ChatActivityPresentation.sources(listOfNotNull(ChatMessage(Role.ASSISTANT, toolCalls = listOf(call)), result)) }
    ChatActivityRow(title, call.name, running, result != null || sources.isNotEmpty()) {
        if (sources.isNotEmpty()) {
            sources.take(4).forEach { source ->
                TextButton(onClick = { onOpenUrl(source.url) }, contentPadding = PaddingValues(0.dp)) {
                    Text(source.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        } else Text(stringResource(when {
            result?.isError == true -> R.string.chat_tool_result_failed
            result != null -> R.string.chat_tool_result_empty
            else -> R.string.chat_activity_interrupted
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatActivityRow(title: String, tool: String?, live: Boolean, expandable: Boolean, followLatest: Boolean = false, details: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var following by remember { mutableStateOf(true) }
    // Follow measured content growth, including wrapped streamed text and newly completed tools.
    // Reopening starts at the newest entry; dragging back pauses following until the bottom is reached.
    LaunchedEffect(expanded, followLatest) {
        if (expanded && followLatest) {
            following = true
            snapshotFlow { scroll.maxValue }.collect { end ->
                if (following) scroll.scrollTo(end)
            }
        }
    }
    LaunchedEffect(scroll, followLatest, expanded) {
        if (followLatest && expanded) snapshotFlow { !scroll.isScrollInProgress && !scroll.canScrollForward }.collect { atEnd ->
            if (atEnd) following = true
        }
    }
    LaunchedEffect(scroll, followLatest) {
        if (followLatest) scroll.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> following = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> following = !scroll.canScrollForward
            }
        }
    }
    val scheme = MaterialTheme.colorScheme
    val motion = android.provider.Settings.Global.getFloat(androidx.compose.ui.platform.LocalContext.current.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    Column(Modifier.fillMaxWidth().animateContentSize(tween(if (motion) 180 else 0))) {
        val header: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatActivityGlyph(tool, live, motion)
                AnimatedContent(title, transitionSpec = {
                    (fadeIn(tween(if (motion) 180 else 0)) + slideInVertically { if (motion) it / 3 else 0 }) togetherWith
                        fadeOut(tween(if (motion) 100 else 0))
                }, label = "Chat activity phase", modifier = Modifier.weight(1f, fill = false)) { label -> ChatShimmerText(label, live && motion) }
                if (expandable) Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
            }
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            if (expandable) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { header() }
            else Box(Modifier.heightIn(min = 36.dp), contentAlignment = Alignment.CenterStart) { header() }
        }
        AnimatedVisibility(expanded && expandable) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(scroll).padding(start = 36.dp, top = 4.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = details)
        }
    }
}

/** Compact, left-aligned attribution below the reply, with domains inside the sources sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSources(messages: List<ChatMessage>, onOpenUrl: (String) -> Unit) {
    val sources = remember(messages) { ChatActivityPresentation.sources(messages) }
    if (sources.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
            Icon(Icons.Outlined.Public, null, Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.chat_source_count, sources.size), style = MaterialTheme.typography.labelMedium)
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.chat_sources), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.chat_sources_explanation), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            sources.forEach { source ->
                TextButton(onClick = { open = false; onOpenUrl(source.url) }, contentPadding = PaddingValues(vertical = 10.dp, horizontal = 0.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(source.title, style = MaterialTheme.typography.titleSmall)
                        Text(source.host, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        Text(stringResource(if (source.read) R.string.chat_source_read else R.string.chat_source_found), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.Public, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ChatActivityGlyph(name: String?, live: Boolean, motion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    var angle = 0f
    if (live && motion) {
        val transition = rememberInfiniteTransition(label = "Chat activity orbit")
        val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "Chat orbit rotation")
        angle = rotation
    }
    Box(Modifier.size(28.dp).background(scheme.surfaceContainerHigh.copy(alpha = .65f), CircleShape), contentAlignment = Alignment.Center) {
        if (live) Canvas(Modifier.fillMaxSize()) {
            drawArc(scheme.onSurfaceVariant.copy(alpha = .35f), angle - 90f, 95f, false,
                topLeft = Offset(1.dp.toPx(), 1.dp.toPx()), size = size.copy(width = size.width - 2.dp.toPx(), height = size.height - 2.dp.toPx()),
                style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round))
        }
        Crossfade(name.orEmpty(), animationSpec = tween(if (motion) 180 else 0), label = "Chat activity icon") { phase ->
            when (phase) {
                "web_search" -> Icon(Icons.Outlined.Search, null, Modifier.size(15.dp), tint = scheme.onSurfaceVariant)
                "web_fetch" -> Icon(Icons.Outlined.Public, null, Modifier.size(15.dp), tint = scheme.onSurfaceVariant)
                else -> Icon(painterResource(R.drawable.ic_chat_sparkles), null, Modifier.size(15.dp), tint = scheme.onSurfaceVariant)
            }
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
    if (!running) {
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
