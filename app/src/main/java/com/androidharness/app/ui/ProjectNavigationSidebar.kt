package com.androidharness.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.RunManager
import com.androidharness.app.data.MessageHit
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.ui.common.HarnessMark
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.ui.common.formatRelativeTime

internal enum class SidebarChatAction { RENAME, PIN, ARCHIVE, DETAILS, DELETE }
internal enum class SidebarActivity { WORKING, NEEDS_YOU }

internal fun sidebarActivity(running: Boolean, pendingPlan: String?, live: RunManager.LiveRunState? = null): SidebarActivity? = when {
    !pendingPlan.isNullOrBlank() || live?.pendingApproval != null || live?.pendingQuestion != null ||
        live?.pendingEnvironment != null || !live?.pendingPlan.isNullOrBlank() -> SidebarActivity.NEEDS_YOU
    running || live?.running == true -> SidebarActivity.WORKING
    else -> null
}

/** Pinned and archived rows appear once; legacy chats keep a reachable home. */
internal data class SidebarChats(
    val pinned: List<SessionEntity>, val projects: Map<String, List<SessionEntity>>,
    val other: List<SessionEntity>, val archived: List<SessionEntity>,
) {
    companion object {
        fun of(projects: List<ProjectEntity>, sessions: List<SessionEntity>, pinned: Set<String>, archived: Set<String>): SidebarChats {
            val known = projects.mapTo(mutableSetOf()) { it.id }
            val active = sessions.filterNot { it.id in archived }
            val rest = active.filterNot { it.id in pinned }
            return SidebarChats(active.filter { it.id in pinned }, rest.filter { it.projectId in known }.groupBy { it.projectId!! },
                rest.filter { it.projectId !in known }, sessions.filter { it.id in archived })
        }
    }
}

/** Shared phone drawer / desktop sidebar, matching the Projects first concept. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProjectNavigationSidebar(
    projects: List<ProjectEntity>, sessions: List<SessionEntity>, selectedSession: String?, selectedProject: String?,
    pinned: Set<String>, archived: Set<String>, query: String, onQueryChange: (String) -> Unit,
    messageHits: List<MessageHit>, fuzzy: Boolean, onFuzzyChange: () -> Unit,
    activity: @Composable (SessionEntity) -> SidebarActivity?,
    onNewChat: () -> Unit, onChat: (String) -> Unit, onMessage: (MessageHit) -> Unit,
    onProjectChat: (ProjectEntity) -> Unit, onProjects: () -> Unit,
    onChatActions: (SessionEntity) -> Unit, onAction: (SessionEntity, SidebarChatAction) -> Unit,
    onBuild: () -> Unit, onTerminal: () -> Unit, onAutomation: () -> Unit, onProviders: () -> Unit, onSettings: () -> Unit,
    workspaceLabel: String, workspaceSummary: String, onWorkspaces: () -> Unit,
    onCollapse: () -> Unit, currentRoute: String?, projectCreationEnabled: Boolean = true,
    changesSummary: String? = null, onChanges: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scheme = MaterialTheme.colorScheme
    var expandedProjects by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var collapsedGroups by rememberSaveable { mutableStateOf(listOf("archived")) }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    var footerHeight by remember { mutableStateOf(0.dp) }
    val currentProject = sessions.firstOrNull { it.id == selectedSession }?.projectId ?: selectedProject
    val expanded = expandedProjects ?: listOfNotNull(currentProject ?: projects.firstOrNull()?.id)
    LaunchedEffect(selectedSession, currentProject) {
        if (currentProject != null) expandedProjects = (expanded + currentProject).distinct()
    }
    val grouped = remember(projects, sessions, pinned, archived) { SidebarChats.of(projects, sessions, pinned, archived) }
    val matching = remember(sessions, projects, query) {
        val names = projects.associate { it.id to it.name }
        sessions.filter { it.title.contains(query.trim(), true) || names[it.projectId]?.contains(query.trim(), true) == true }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    @Composable fun Chat(chat: SessionEntity, indented: Boolean = false, showProject: Boolean = false) {
        SidebarChatRow(chat, chat.id == selectedSession, chat.id in pinned, chat.id in archived, activity(chat),
            projectName = if (showProject) projects.firstOrNull { it.id == chat.projectId }?.name else null,
            indented = indented, onClick = { keyboard?.hide(); onChat(chat.id) }, onActions = { onChatActions(chat) },
            onAction = { onAction(chat, it) })
    }
    @Composable fun Section(label: String, key: String) = SidebarSectionHeader(label, key !in collapsedGroups) {
        collapsedGroups = if (key in collapsedGroups) collapsedGroups - key else collapsedGroups + key
    }
    @Composable fun Footer() {
        Column(Modifier.fillMaxWidth().onSizeChanged { footerHeight = with(density) { it.height.toDp() } }
            .padding(horizontal = 10.dp, vertical = 8.dp)) {
            SidebarNavRow("Tools", Icons.Outlined.Widgets, expanded = toolsOpen, onClick = { toolsOpen = !toolsOpen })
            if (toolsOpen) Column(Modifier.padding(start = 16.dp)) {
                SidebarNavRow("Terminal", Icons.Outlined.Terminal, currentRoute?.startsWith("terminal") == true, onClick = onTerminal)
                SidebarNavRow("Build & test", Icons.Outlined.Build, currentRoute?.startsWith("build-test") == true, onClick = onBuild)
                SidebarNavRow("Scheduled tasks", Icons.Outlined.Schedule, currentRoute == "automation", onClick = onAutomation)
                SidebarNavRow(workspaceLabel, Icons.Outlined.FolderOpen, subtitle = workspaceSummary, onClick = onWorkspaces)
                changesSummary?.let { SidebarNavRow(it, Icons.Outlined.Difference, onClick = onChanges) }
            }
            SidebarNavRow("Providers", Icons.Outlined.Hub, currentRoute == "providers", onClick = onProviders)
            SidebarNavRow("Settings", Icons.Outlined.Settings, currentRoute == "settings", onClick = onSettings)
        }
    }
    Surface(color = scheme.surface, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Project and chat sidebar" }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val short = maxHeight < 440.dp
            val narrow = maxWidth < 280.dp
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    HarnessMark(26.dp, Modifier.semantics { contentDescription = "AndroidHarness icon" })
                    Text("AndroidHarness", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onCollapse) { Icon(Icons.AutoMirrored.Outlined.MenuOpen, "Collapse sidebar", Modifier.size(19.dp)) }
                }
                Button(onClick = onNewChat, enabled = projectCreationEnabled, shape = RoundedCornerShape(11.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).heightIn(min = 44.dp)) {
                    Icon(Icons.Outlined.EditNote, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp))
                    Text("New chat", style = MaterialTheme.typography.labelLarge)
                }
                BasicTextField(query, onQueryChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Search chats and messages" },
                    decorationBox = { field ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(scheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                            .padding(start = 12.dp, end = if (query.isBlank()) 12.dp else 0.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) Text(if (narrow) "Search chats" else "Search chats and messages",
                                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
                                field()
                            }
                            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Outlined.Close, "Clear search", Modifier.size(17.dp))
                            }
                        }
                    })
                if (query.isNotBlank()) TextButton(onClick = onFuzzyChange,
                    modifier = Modifier.padding(horizontal = 14.dp).semantics { stateDescription = if (fuzzy) "Contains text" else "Match words" }) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (fuzzy) "Contains text" else "Match words", style = MaterialTheme.typography.labelSmall)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 2.dp, bottom = 10.dp)) {
                    if (query.isNotBlank()) {
                        if (matching.isNotEmpty()) {
                            item { SidebarSectionHeader("Chats", true, null) }
                            items(matching, key = { "search:${it.id}" }) { Chat(it, showProject = true) }
                        }
                        if (messageHits.isNotEmpty()) {
                            item { SidebarSectionHeader("Messages", true, null) }
                            items(messageHits, key = { "message:${it.messageId}" }) { hit ->
                                MessageHitRow(hit, query, fuzzy) { keyboard?.hide(); onMessage(hit) }
                            }
                        }
                        if (matching.isEmpty() && messageHits.isEmpty()) item {
                            Text("No chats found. Try another title or message.", Modifier.padding(20.dp),
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    } else {
                        if (grouped.pinned.isNotEmpty()) {
                            item { Section("Pinned", "pinned") }
                            if ("pinned" !in collapsedGroups) items(grouped.pinned, key = { "pinned:${it.id}" }) { Chat(it, showProject = true) }
                        }
                        item {
                            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp).heightIn(min = 44.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = onProjects, modifier = Modifier.weight(1f)) {
                                    Box(Modifier.fillMaxWidth()) { Text("Projects", style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant) }
                                }
                                IconButton(onClick = onProjects) { Icon(Icons.Outlined.FolderCopy, "Manage projects", Modifier.size(18.dp)) }
                            }
                        }
                        projects.forEach { project ->
                            val chats = grouped.projects[project.id].orEmpty()
                            item(key = "project:${project.id}") {
                                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(onClick = { expandedProjects = if (project.id in expanded) expanded - project.id else expanded + project.id },
                                        color = scheme.surface, modifier = Modifier.weight(1f)
                                            .semantics { contentDescription = "${if (project.id in expanded) "Collapse" else "Expand"} ${project.name}" }) {
                                        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                            Icon(if (project.id in expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                                null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                                            Icon(Icons.Outlined.Folder, null, Modifier.size(17.dp), tint = scheme.onSurfaceVariant)
                                            Text(project.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(chats.size.toString(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(onClick = { onProjectChat(project) }, enabled = projectCreationEnabled) {
                                        Icon(Icons.Outlined.Add, "New chat in ${project.name}", Modifier.size(18.dp))
                                    }
                                }
                            }
                            if (project.id in expanded) {
                                items(chats, key = { "project-chat:${it.id}" }) { Chat(it, indented = true) }
                                if (chats.isEmpty()) item(key = "empty:${project.id}") {
                                    Text("No other chats", Modifier.padding(start = 47.dp, top = 4.dp, bottom = 12.dp),
                                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (projects.isEmpty()) item {
                            TextButton(onClick = onProjects, Modifier.padding(horizontal = 18.dp)) { Text("Add a project") }
                        }
                        if (grouped.other.isNotEmpty()) {
                            item { Section("Other chats", "other") }
                            if ("other" !in collapsedGroups) items(grouped.other, key = { "other:${it.id}" }) { Chat(it) }
                        }
                        if (grouped.archived.isNotEmpty()) {
                            item { Section("Archived", "archived") }
                            if ("archived" !in collapsedGroups) items(grouped.archived, key = { "archive:${it.id}" }) { Chat(it, showProject = true) }
                        }
                    }
                    if (short) item { HorizontalDivider(color = scheme.outlineVariant); Footer() }
                }
                if (!short) { HorizontalDivider(color = scheme.outlineVariant); Footer() }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (short) 8.dp else footerHeight + 8.dp))
        }
    }
}

@Composable
private fun SidebarSectionHeader(label: String, expanded: Boolean, onClick: (() -> Unit)?) {
    val contents: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = if (onClick == null) 32.dp else 44.dp).padding(start = 20.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (label == "Pinned") {
                Icon(Icons.Outlined.PushPin, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onClick != null) Icon(if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (onClick == null) contents() else Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.semantics { contentDescription = "$label chats"; stateDescription = if (expanded) "Expanded" else "Collapsed" }) { contents() }
}

@Composable
private fun SidebarNavRow(label: String, icon: ImageVector, selected: Boolean = false, subtitle: String? = null,
    expanded: Boolean? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, color = if (selected) scheme.surfaceContainerHigh else scheme.surface,
        shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxWidth().semantics {
            this.selected = selected; expanded?.let { stateDescription = if (it) "Expanded" else "Collapsed" }
        }) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall)
                subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (expanded != null) Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                null, Modifier.size(15.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarChatRow(chat: SessionEntity, selected: Boolean, pinned: Boolean, archived: Boolean,
    activity: SidebarActivity?, projectName: String?, indented: Boolean, onClick: () -> Unit,
    onActions: () -> Unit, onAction: (SidebarChatAction) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current
    var menu by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = if (indented) 30.dp else 10.dp, end = 10.dp, top = 2.dp, bottom = 2.dp)) {
        if (indented) Box(Modifier.width(1.dp).height(56.dp).background(scheme.outlineVariant))
        Row(Modifier.weight(1f).padding(start = if (indented) 5.dp else 0.dp)
            .background(if (selected) scheme.primary.copy(alpha = .07f) else if (focused) scheme.surfaceContainerHigh else scheme.surface, RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).secondaryClick(onActions).pointerHoverIcon(PointerIcon.Hand)
                .onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
                    if (event.key == Key.Menu || event.key == Key.F10 && event.isShiftPressed) {
                        if (event.type == KeyEventType.KeyUp) onActions(); true
                    } else false
                }.combinedClickable(onClick = onClick, onLongClick = onActions, onLongClickLabel = "Chat actions")
                .semantics { this.selected = selected; contentDescription = "Open ${chat.title}" }
                .heightIn(min = 56.dp).padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(chat.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        maxLines = if (LocalDensity.current.fontScale > 1.3f) 2 else 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val color = when (activity) { SidebarActivity.WORKING -> statusColors.success; SidebarActivity.NEEDS_YOU -> statusColors.warning; null -> scheme.onSurfaceVariant }
                        if (activity == SidebarActivity.WORKING) Box(Modifier.size(6.dp).background(color, CircleShape))
                        if (activity == SidebarActivity.NEEDS_YOU) Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, Modifier.size(13.dp), tint = color)
                        Text(listOfNotNull(when (activity) { SidebarActivity.WORKING -> "Working"; SidebarActivity.NEEDS_YOU -> "Needs you"; null -> projectName },
                            if (archived) "Archived" else null,
                            if (activity == null) formatRelativeTime(chat.updatedAt) else null).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pinned) Icon(Icons.Outlined.PushPin, "Pinned", Modifier.size(13.dp), tint = scheme.onSurfaceVariant)
                if (selected) Icon(Icons.Outlined.Check, "Current chat", Modifier.size(15.dp), tint = scheme.primary)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Icon(Icons.Outlined.MoreHoriz, "Actions for ${chat.title}", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
                DropdownMenu(menu, { menu = false }) {
                    listOf(Triple(SidebarChatAction.RENAME, "Rename", Icons.Outlined.Edit),
                        Triple(SidebarChatAction.PIN, if (pinned) "Unpin" else "Pin", Icons.Outlined.PushPin),
                        Triple(SidebarChatAction.DETAILS, "Chat details", Icons.Outlined.Info),
                        Triple(SidebarChatAction.ARCHIVE, if (archived) "Unarchive" else "Archive", Icons.Outlined.Archive),
                        Triple(SidebarChatAction.DELETE, "Delete", Icons.Outlined.DeleteOutline)).forEach { (action, label, icon) ->
                        DropdownMenuItem(text = { Text(label, color = if (action == SidebarChatAction.DELETE) scheme.error else scheme.onSurface) },
                            leadingIcon = { Icon(icon, null, tint = if (action == SidebarChatAction.DELETE) scheme.error else scheme.onSurfaceVariant) },
                            onClick = { menu = false; onAction(action) })
                    }
                }
            }
        }
    }
}
