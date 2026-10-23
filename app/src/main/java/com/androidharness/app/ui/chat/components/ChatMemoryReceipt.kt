package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.core.ChatActivityPresentation
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.data.db.MemoryEntity
import com.androidharness.app.ui.settings.MemoryEditDialog
import kotlinx.coroutines.launch

@Composable
internal fun ChatMemoryReceipt(messages: List<ChatMessage>, container: AppContainer, busy: Boolean,
    supplied: List<com.androidharness.app.core.SuppliedMemory> = emptyList()) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var deleting by remember { mutableStateOf<MemoryEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (supplied.isEmpty()) ChatMemorySaved { open = true }
    else TextButton(onClick = { open = true }) { Text(stringResource(R.string.chat_memories_used, supplied.size)) }
    if (!open) return
    val memories by container.memory.memories.collectAsStateWithLifecycle(initialValue = emptyList())
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val ids = remember(messages, supplied) { ChatActivityPresentation.savedMemoryIds(messages) + supplied.map { it.id } }
    val saved = memories.filter { it.id in ids }
    if (editing == null && deleting == null) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(if (supplied.isEmpty()) R.string.chat_memory_saved else R.string.chat_memory_used_title)) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (supplied.isNotEmpty()) Text(stringResource(R.string.chat_memory_used_explanation))
            if (saved.isEmpty() && supplied.isEmpty()) Text(stringResource(R.string.chat_memory_unavailable))
            if (supplied.isEmpty()) saved.forEach { memory ->
                TextButton(enabled = !busy && !saving, onClick = { editing = memory }) { Text(memory.text) }
            } else supplied.forEach { snapshot ->
                val current = saved.firstOrNull { it.id == snapshot.id }
                TextButton(enabled = current != null && !busy && !saving, onClick = { editing = current }) {
                    Text(snapshot.text + if (current == null) " · " + stringResource(R.string.chat_memory_deleted) else "")
                }
            }
            if (busy) Text(stringResource(R.string.chat_memory_wait))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.settings_action_close)) } })
    editing?.let { memory ->
        // Remove a receipt that was deleted from another surface while this dialog was open.
        if (saved.none { it.id == memory.id }) { LaunchedEffect(memory.id) { editing = null }; return@let }
        MemoryEditDialog(memory, projects, enabled = !busy && !saving, onDismiss = { if (!saving) editing = null },
            onSave = { text, category, memoryScope, project ->
                if (!saving && !busy) { saving = true; scope.launch {
                    try { container.memory.update(memory, text, category, memoryScope, project); editing = null }
                    catch (e: Exception) { error = e.message; editing = null }
                    finally { saving = false }
                } }
            }, onDelete = {
                if (!saving && !busy) { deleting = memory; editing = null }
            })
    }
    deleting?.let { memory ->
        AlertDialog(
            onDismissRequest = { if (!saving) deleting = null },
            title = { Text(stringResource(R.string.settings_memory_delete_confirm)) },
            text = { Text(stringResource(R.string.settings_memory_delete_body)) },
            confirmButton = {
                TextButton(enabled = !saving && !busy, onClick = {
                    if (!saving && !busy) {
                        saving = true
                        scope.launch {
                            try { container.memory.remove(memory.id) }
                            catch (e: Exception) { error = e.message }
                            finally { saving = false; deleting = null }
                        }
                    }
                }) { Text(stringResource(R.string.settings_memory_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { deleting = null }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }
}
