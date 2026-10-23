package com.androidharness.app.ui.chat.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.androidharness.app.R
import com.androidharness.app.agent.ThinkingTiming
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.chatThinkingTotals
import com.androidharness.app.core.compactThinkingTokenCount
import kotlinx.coroutines.delay

/** Only this small header label ticks; the reasoning transcript does not recompose with the clock. */
@Composable
internal fun ChatThinkingMetrics(messages: List<ChatMessage>, streamingThinking: String, timing: ThinkingTiming?,
    reasoningTokens: Int?, live: Boolean) {
    var now by remember(timing) { mutableLongStateOf(ThinkingTiming.now()) }
    LaunchedEffect(live, timing) {
        if (live && timing?.startedAt != null) while (true) {
            now = ThinkingTiming.now()
            delay(200)
        }
    }
    val totals = remember(messages, streamingThinking, reasoningTokens) {
        chatThinkingTotals(messages, streamingThinking, null, reasoningTokens, 0)
    }
    val duration = stringResource(R.string.ui_thinking_seconds, (totals.milliseconds + (timing?.durationMs(now) ?: 0)) / 1000)
    val count = compactThinkingTokenCount(totals.tokens)
    val tokens = stringResource(R.string.chat_thinking_tokens_compact, if (totals.estimated) "~$count" else count)
    val description = duration + " · " + stringResource(
        if (totals.estimated) R.string.ui_thinking_estimated_tokens else R.string.ui_thinking_tokens, totals.tokens)
    Text("$duration · $tokens", modifier = Modifier.semantics { contentDescription = description },
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
