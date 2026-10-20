package com.androidharness.app.ui.projects

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.workspace.ProjectDeletionPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProjectDeletionDialog(
    container: AppContainer,
    project: ProjectEntity,
    onDismiss: () -> Unit,
    onDeleted: (Set<String>) -> Unit,
) {
    var preview by remember(project.id) { mutableStateOf<ProjectDeletionPreview?>(null) }
    var error by remember(project.id) { mutableStateOf<String?>(null) }
    var deleting by remember(project.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(project.id) {
        try { preview = container.projectDeletion.preview(project.id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not review this project." }
    }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("Delete project?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall)
                val review = preview
                if (review == null && error == null) Text("Checking project…")
                if (review != null) {
                    val chats = "${review.chatIds.size} saved ${if (review.chatIds.size == 1) "chat" else "chats"}"
                    val automations = "${review.automationIds.size} ${if (review.automationIds.size == 1) "automation" else "automations"}"
                    Text("Removes this project, $chats and $automations from the app. Conversation history and undo history for these chats will be deleted. This cannot be undone.")
                    Text("Your folder and files stay on disk.", style = MaterialTheme.typography.bodyMedium)
                    review.blocker?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !deleting && error == null && preview?.blocker == null && preview != null,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    val review = preview ?: return@TextButton
                    deleting = true
                    scope.launch {
                        try { container.projectDeletion.delete(review); onDeleted(review.chatIds) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Could not delete this project." }
                        finally { deleting = false }
                    }
                },
            ) { Text(if (deleting) "Deleting…" else "Delete project") }
        },
        dismissButton = { TextButton(enabled = !deleting, onClick = onDismiss) { Text("Keep project") } },
    )
}
