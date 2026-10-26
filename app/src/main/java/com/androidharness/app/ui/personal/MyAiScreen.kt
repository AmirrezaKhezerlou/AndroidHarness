package com.androidharness.app.ui.personal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.*
import com.androidharness.app.data.db.MemoryEntity
import com.androidharness.app.ui.common.ExpressiveIconButton
import com.androidharness.app.ui.settings.MemoryEditDialog
import com.androidharness.app.ui.settings.sectionLabel
import com.androidharness.app.ui.theme.expressiveEffectsSpec
import com.androidharness.app.ui.theme.expressiveSpatialSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class PersonalTab(val label: Int, val category: MemoryCategory?) {
    ALL(R.string.my_ai_all, null),
    ABOUT(R.string.my_ai_about, MemoryCategory.PROFILE),
    PREFERENCES(R.string.my_ai_preferences, MemoryCategory.PREFERENCE),
    GOALS(R.string.my_ai_goals, MemoryCategory.GOAL),
}

/** A local, editable view of the same memory used by Chat and Code. No model call on opening. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MyAiScreen(container: AppContainer, onBack: () -> Unit, onChat: () -> Unit, onSettings: () -> Unit) {
    val memories by container.memory.memories.collectAsStateWithLifecycle(initialValue = emptyList())
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by remember(container) { container.settings.settings.map { it as AppSettings? } }
        .collectAsStateWithLifecycle(initialValue = null)
    val work = rememberCoroutineScope()
    var tabName by rememberSaveable { mutableStateOf(PersonalTab.ALL.name) }
    val tab = PersonalTab.valueOf(tabName)
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val entries = memories.filter { it.consolidatedInto == null }
    val visible = entries.filter { tab.category == null || it.category == tab.category.name }
        .sortedWith(compareByDescending<MemoryEntity> { it.enabled }.thenByDescending { it.pinned }.thenByDescending { it.updatedAt })
    val editing = entries.firstOrNull { it.id == editingId }
    val deleting = entries.firstOrNull { it.id == deletingId }
    fun mutate(action: suspend () -> Unit, done: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        work.launch {
            try { action(); done() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }

    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.my_ai_title)) }, navigationIcon = {
                    ExpressiveIconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back)) }
                }, actions = {
                    ExpressiveIconButton(onSettings) { Icon(Icons.Outlined.Tune, stringResource(R.string.settings_page_title)) }
                })
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(onClick = { adding = (tab.category ?: MemoryCategory.PROFILE).name; error = null },
                    icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(stringResource(R.string.my_ai_add)) })
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "intro") {
                    AnimatedVisibility(entered, enter = fadeIn(expressiveEffectsSpec()) +
                        slideInVertically(expressiveSpatialSpec()) { it / 8 }) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary) {
                                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.padding(14.dp).size(28.dp),
                                            tint = MaterialTheme.colorScheme.onPrimary)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.my_ai_headline), style = MaterialTheme.typography.headlineSmall)
                                        Text(stringResource(R.string.my_ai_subtitle), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                Text(stringResource(R.string.my_ai_intro), style = MaterialTheme.typography.bodyMedium)
                                FilledTonalButton(onChat) {
                                    Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.my_ai_chat))
                                }
                            }
                        }
                    }
                }
                item(key = "memory-status") {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Memory, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(if (settings?.memoryEnabled == true) R.string.my_ai_memory_on else R.string.my_ai_memory_off),
                                    style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.my_ai_memory_desc), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = settings?.memoryEnabled == true, enabled = settings != null && !busy,
                                onCheckedChange = { value -> mutate({ container.settings.setMemoryEnabled(value) }) },
                                modifier = Modifier.semantics { this.role = Role.Switch },
                                thumbContent = { Icon(if (settings?.memoryEnabled == true) Icons.Outlined.Check else Icons.Outlined.Pause,
                                    stringResource(R.string.settings_memory), Modifier.size(16.dp)) })
                        }
                    }
                }
                item(key = "budget") {
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.my_ai_budget, MemoryPrompt.approxTokens(MemoryPrompt.MAX_CHARS)),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.my_ai_budget_desc), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = "tabs") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PersonalTab.entries.forEach { option ->
                            val selected = option == tab
                            val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                expressiveEffectsSpec(), label = "personal tab color")
                            val corner by animateDpAsState(if (selected) 22.dp else 12.dp, expressiveSpatialSpec(), label = "personal tab shape")
                            Surface(onClick = { tabName = option.name }, color = color, shape = RoundedCornerShape(corner),
                                modifier = Modifier.semantics { role = Role.Tab; this.selected = selected }) {
                                Text(stringResource(option.label), Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                item(key = "count") {
                    Text(stringResource(R.string.my_ai_count, visible.count { it.enabled }, visible.size),
                        Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (error != null && adding == null && editing == null) item(key = "error") {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
                if (visible.isEmpty()) item(key = "empty:${tab.name}") {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(24.dp).animateContentSize(expressiveSpatialSpec()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(categoryIcon(tab.category?.name), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.my_ai_empty_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(when (tab) {
                                PersonalTab.GOALS -> R.string.my_ai_empty_goals
                                PersonalTab.PREFERENCES -> R.string.my_ai_empty_preferences
                                else -> R.string.my_ai_empty_about
                            }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { adding = (tab.category ?: MemoryCategory.PROFILE).name }) {
                                Text(stringResource(R.string.my_ai_add_first))
                            }
                        }
                    }
                }
                items(visible, key = { "memory:${it.id}" }) { memory ->
                    val color by animateColorAsState(if (memory.enabled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        expressiveEffectsSpec(), label = "memory state")
                    Surface(onClick = { if (!busy) { editingId = memory.id; error = null } }, color = color,
                        shape = RoundedCornerShape(24.dp), modifier = Modifier.animateItem()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(categoryIcon(memory.category), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text(stringResource(categoryTitle(memory.category)), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                Switch(checked = memory.enabled, enabled = !busy,
                                    onCheckedChange = { value -> mutate({ container.memory.setEnabled(memory.id, value) }) },
                                    modifier = Modifier.semantics { this.role = Role.Switch },
                                    thumbContent = { Icon(if (memory.enabled) Icons.Outlined.Check else Icons.Outlined.Pause,
                                        stringResource(if (memory.enabled) R.string.my_ai_pause else R.string.my_ai_resume), Modifier.size(16.dp)) })
                            }
                            Text(memory.text, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis,
                                color = if (memory.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            val owner = stringResource(if (memory.section == MemorySection.SHARED.name) R.string.my_ai_both else sectionLabel(MemorySection.fromStored(memory.section)))
                            val project = memory.projectId?.let { id -> projects.firstOrNull { it.id == id }?.name ?: stringResource(R.string.settings_memory_project) }
                            Text(listOfNotNull(owner, project, if (!memory.enabled) stringResource(R.string.my_ai_paused) else null).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (adding != null || editing != null) MemoryEditDialog(
            initial = editing, projects = projects, enabled = !busy, error = error,
            initialSection = MemorySection.CHAT,
            initialCategory = adding?.let(MemoryCategory::fromStored) ?: MemoryCategory.FACT,
            onDismiss = { if (!busy) { adding = null; editingId = null; error = null } },
            onSave = { text, category, scope, projectId, section ->
                mutate({
                    if (editing != null) container.memory.update(editing, text, category, scope, projectId, section)
                    else container.memory.add(text, category, scope, projectId, section)
                }) { adding = null; editingId = null }
            },
            onDelete = { deletingId = editingId },
        )
        deleting?.let { memory ->
            AlertDialog(onDismissRequest = { if (!busy) deletingId = null },
                title = { Text(stringResource(R.string.settings_memory_delete)) },
                text = { Text(stringResource(R.string.my_ai_forget_confirm)) },
                confirmButton = { TextButton(enabled = !busy, onClick = {
                    mutate({ container.memory.remove(memory.id) }) { deletingId = null; editingId = null }
                }) { Text(stringResource(R.string.settings_memory_delete)) } },
                dismissButton = { TextButton(enabled = !busy, onClick = { deletingId = null }) { Text(stringResource(R.string.settings_action_cancel)) } })
        }
    }
}

private fun categoryIcon(category: String?): ImageVector = when (category) {
    "PROFILE" -> Icons.Outlined.PersonOutline
    "PREFERENCE" -> Icons.Outlined.Tune
    "GOAL" -> Icons.Outlined.Flag
    "PROJECT" -> Icons.Outlined.FolderOpen
    "DECISION" -> Icons.Outlined.CheckCircleOutline
    else -> Icons.Outlined.AutoAwesome
}

private fun categoryTitle(category: String): Int = when (MemoryCategory.fromStored(category)) {
    MemoryCategory.PROFILE -> R.string.my_ai_about
    MemoryCategory.PREFERENCE -> R.string.my_ai_preferences
    MemoryCategory.GOAL -> R.string.my_ai_goals
    MemoryCategory.PROJECT -> R.string.settings_memory_category_project
    MemoryCategory.DECISION -> R.string.settings_memory_category_decision
    MemoryCategory.FACT -> R.string.settings_memory_category_fact
}
