package com.androidharness.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.MemoryCategory
import com.androidharness.app.data.MemoryPrompt
import com.androidharness.app.data.MemoryScope
import com.androidharness.app.data.db.MemoryEntity
import com.androidharness.app.data.db.ProjectEntity
import kotlinx.coroutines.launch

@Composable
internal fun MemoryOptionsSection(
    container: AppContainer,
    settings: AppSettings,
    onEnabled: (Boolean) -> Unit,
    onOpen: () -> Unit,
    showManage: Boolean = true,
) {
    val memories by container.memory.memories.collectAsStateWithLifecycle(initialValue = emptyList())
    val count = memories.size
    SettingsAnchor(R.string.settings_memory) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.settings_memory), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(if (settings.memoryEnabled) R.string.settings_memory_on else R.string.settings_memory_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.memoryEnabled, onCheckedChange = onEnabled)
                }
                if (showManage) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onOpen)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.settings_memory_manage), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_memory_manage_desc, count),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryManagerSection(container: AppContainer) {
    val memories by container.memory.memories.collectAsStateWithLifecycle(initialValue = emptyList())
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var deleting by remember { mutableStateOf<MemoryEntity?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val projectNames = remember(projects) { projects.associate { it.id to it.name } }
    val active = memories.count { it.enabled }
    val visible = remember(memories, query) {
        val needle = query.trim()
        memories.filter {
            needle.isEmpty() || it.text.contains(needle, ignoreCase = true) ||
                it.category.contains(needle, ignoreCase = true)
        }.sortedWith(compareByDescending<MemoryEntity> { it.pinned }.thenByDescending { it.updatedAt })
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.settings_memory_manage), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_memory_count, active, memories.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.settings_memory_add)) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.settings_memory_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.settings_memory_budget, MemoryPrompt.approxTokens(MemoryPrompt.MAX_CHARS)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (visible.isEmpty()) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Text(
                stringResource(if (memories.isEmpty()) R.string.settings_memory_empty else R.string.settings_memory_empty_search),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    } else {
        SettingsPanel(Modifier.fillMaxWidth()) {
            visible.forEachIndexed { index, memory ->
                MemoryRow(
                    memory = memory,
                    projectName = memory.projectId?.let { projectNames[it] },
                    onEdit = { editing = memory },
                    onPinned = { pinned -> scope.launch { container.memory.setPinned(memory.id, pinned) } },
                    onEnabled = { enabled -> scope.launch { container.memory.setEnabled(memory.id, enabled) } },
                )
                if (index < visible.lastIndex) HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f),
                )
            }
        }
    }

    if (memories.isNotEmpty()) {
        TextButton(onClick = { clearing = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_memory_clear_all), color = MaterialTheme.colorScheme.error)
        }
    }

    if (adding || editing != null) {
        MemoryEditDialog(
            initial = editing,
            projects = projects,
            onDismiss = { adding = false; editing = null },
            onSave = { text, category, memoryScope, projectId ->
                val target = editing
                scope.launch {
                    if (target == null) container.memory.add(text, category, memoryScope, projectId)
                    else container.memory.update(target, text, category, memoryScope, projectId)
                }
                adding = false
                editing = null
            },
            onDelete = {
                editing?.let { deleting = it }
                adding = false
                editing = null
            },
        )
    }

    deleting?.let { memory ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.settings_memory_delete_confirm)) },
            text = { Text(stringResource(R.string.settings_memory_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.memory.remove(memory.id) }
                    deleting = null
                }) { Text(stringResource(R.string.settings_memory_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }

    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.settings_memory_clear_confirm)) },
            text = { Text(stringResource(R.string.settings_memory_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.memory.clearAll() }
                    clearing = false
                }) { Text(stringResource(R.string.settings_memory_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { clearing = false }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }
}

@Composable
private fun MemoryRow(
    memory: MemoryEntity,
    projectName: String?,
    onEdit: () -> Unit,
    onPinned: (Boolean) -> Unit,
    onEnabled: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                memory.text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                color = if (memory.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(
                    stringResource(categoryLabel(memory.category)),
                    if (memory.scope == MemoryScope.PROJECT.name) projectName ?: stringResource(R.string.settings_memory_project)
                    else stringResource(R.string.settings_memory_global),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onPinned(!memory.pinned) }) {
            Icon(
                if (memory.pinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = stringResource(R.string.settings_memory_pin),
                tint = if (memory.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = memory.enabled, onCheckedChange = onEnabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditDialog(
    initial: MemoryEntity?,
    projects: List<ProjectEntity>,
    onDismiss: () -> Unit,
    onSave: (String, MemoryCategory, MemoryScope, String?) -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember(initial?.id) { mutableStateOf(initial?.text.orEmpty()) }
    var category by remember(initial?.id) { mutableStateOf(MemoryCategory.fromStored(initial?.category)) }
    var scope by remember(initial?.id) { mutableStateOf(MemoryScope.fromStored(initial?.scope)) }
    var projectId by remember(initial?.id, projects) {
        mutableStateOf(initial?.projectId ?: projects.firstOrNull()?.id)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.settings_memory_add_title else R.string.settings_memory_edit_title))
        },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(600) },
                    label = { Text(stringResource(R.string.settings_memory_text_label)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.settings_memory_category_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoryCategory.entries.forEach { option ->
                        FilterChip(
                            selected = category == option,
                            onClick = { category = option },
                            label = { Text(stringResource(categoryLabel(option.name))) },
                        )
                    }
                }
                Text(stringResource(R.string.settings_memory_scope_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scope == MemoryScope.GLOBAL,
                        onClick = { scope = MemoryScope.GLOBAL },
                        label = { Text(stringResource(R.string.settings_memory_global)) },
                    )
                    projects.forEach { project ->
                        FilterChip(
                            selected = scope == MemoryScope.PROJECT && projectId == project.id,
                            onClick = { scope = MemoryScope.PROJECT; projectId = project.id },
                            label = { Text(project.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onSave(text, category, scope, if (scope == MemoryScope.PROJECT) projectId else null) },
            ) { Text(stringResource(R.string.settings_memory_save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.settings_memory_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) }
            }
        },
    )
}

private fun categoryLabel(category: String): Int = when (MemoryCategory.fromStored(category)) {
    MemoryCategory.PROFILE -> R.string.settings_memory_category_profile
    MemoryCategory.PREFERENCE -> R.string.settings_memory_category_preference
    MemoryCategory.PROJECT -> R.string.settings_memory_category_project
    MemoryCategory.DECISION -> R.string.settings_memory_category_decision
    MemoryCategory.FACT -> R.string.settings_memory_category_fact
}
