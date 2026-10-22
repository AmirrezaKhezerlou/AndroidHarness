package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.SessionRepository

@Composable
internal fun ChatAnswerVersions(sessionId: String, messages: List<ChatMessage>, repository: SessionRepository,
    enabled: Boolean, onSelect: (String, String) -> Unit) {
    val flow = remember(sessionId, repository) { repository.latestAnswerVersions(sessionId) }
    val archived by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val question = messages.lastOrNull { it.role == Role.USER } ?: return
    val userId = question.id ?: return
    val versions = (archived.map { it.id to it.createdAt } +
        (userId to question.createdAt)).distinctBy { it.first }.sortedWith(compareBy({ it.second }, { it.first }))
    if (versions.size < 2) return
    val position = versions.indexOfFirst { it.first == userId }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = enabled && position > 0, onClick = { onSelect(userId, versions[position - 1].first) }) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.chat_previous_answer), Modifier.size(18.dp))
        }
        Text(stringResource(R.string.chat_answer_version, position + 1, versions.size), style = MaterialTheme.typography.labelMedium)
        IconButton(enabled = enabled && position < versions.lastIndex, onClick = { onSelect(userId, versions[position + 1].first) }) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.chat_next_answer), Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ChatRecovery(reason: String?, onContinue: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.chat_response_interrupted), style = MaterialTheme.typography.titleSmall)
        Text(reason?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_recovery_explanation), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onContinue) { Text(stringResource(R.string.chat_continue_reply)) }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.chat_retry_reply)) }
        }
    }
}
