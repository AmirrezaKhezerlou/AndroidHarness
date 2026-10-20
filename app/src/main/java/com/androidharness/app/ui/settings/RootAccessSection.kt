package com.androidharness.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.env.*
import kotlinx.coroutines.launch

@Composable
internal fun RootAccessSection(container: AppContainer) {
    val access = container.deviceAccess
    val prefs by access.preferences.collectAsStateWithLifecycle()
    val root by access.root.state.collectAsStateWithLifecycle()
    val paused by access.paused.collectAsStateWithLifecycle()
    val allowance by access.allowanceLabel.collectAsStateWithLifecycle()
    val last by access.lastExecution.collectAsStateWithLifecycle()
    val shizukuUid by access.shizukuUid.collectAsStateWithLifecycle()
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val project by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = com.androidharness.app.data.AppSettings())
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var projectPicker by remember { mutableStateOf(false) }
    var browser by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var useMenu by remember { mutableStateOf(false) }
    var confirmUnlimited by remember { mutableStateOf(false) }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Root access", style = MaterialTheme.typography.titleMedium)
                    Text(root.status.label, style = MaterialTheme.typography.bodySmall,
                        color = if (root.status == RootStatus.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy || root.status == RootStatus.REQUESTING) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Text(root.detail, style = MaterialTheme.typography.bodySmall,
                color = if (root.status == RootStatus.DENIED || (root.status == RootStatus.UNAVAILABLE && root.checkedAt > 0))
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            root.provider?.let { provider ->
                Text("Root manager: ${provider.label}", style = MaterialTheme.typography.labelMedium,
                    color = if (root.status == RootStatus.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (shizukuUid == 0) Text("Shizuku · Root available", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            val rootEnabled = (root.status == RootStatus.READY || shizukuUid == 0) &&
                access.rootUseActive(settings.lastActiveSessionId, prefs)
            AccessActionPair(first = { buttonModifier ->
                if (rootEnabled) Surface(
                    modifier = buttonModifier.heightIn(min = 48.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
                        Text("Root enabled", style = MaterialTheme.typography.titleSmall)
                    }
                } else Button(onClick = { scope.launch {
                    busy = true
                    try { access.grantRoot(project?.id, settings.lastActiveSessionId) } finally { busy = false }
                } }, enabled = !busy && root.status != RootStatus.REQUESTING, modifier = buttonModifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(if (root.status == RootStatus.READY) "Enable root use" else "Grant root access")
                }
            }, second = { buttonModifier ->
                OutlinedButton(onClick = { scope.launch {
                    busy = true
                    try { report = access.checkConnections() } finally { busy = false }
                } }, enabled = !busy, modifier = buttonModifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) { Text("Check access") }
            })
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Auto fallback", style = MaterialTheme.typography.titleSmall)
                    Text(if (prefs.mode == AccessMode.AUTO) "Always on · Root → Shizuku → Normal" else "Root → Shizuku → Normal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = prefs.mode == AccessMode.AUTO || prefs.autoFallback, enabled = prefs.mode != AccessMode.AUTO, onCheckedChange = { scope.launch { access.store.setFallback(it) } })
            }
            Text("Access mode", style = MaterialTheme.typography.titleSmall)
            AccessModeChoices(prefs.mode) { scope.launch { access.store.setMode(it) } }
            Text(when {
                prefs.mode == AccessMode.AUTO -> "Auto chooses available access: Root, then Shizuku, then Normal. Root still requires permission for this project unless temporary root mode is off."
                prefs.mode == AccessMode.BOTH -> "Both stay connected: Shizuku runs ordinary commands and direct root handles root requests. Root still requires permission unless temporary root mode is off."
                !prefs.autoFallback && prefs.mode != AccessMode.NORMAL -> "Commands fail if the selected access is unavailable."
                else -> "Fallback applies before a command starts. Interrupted commands are never automatically repeated."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Temporary root mode", style = MaterialTheme.typography.titleSmall)
                    Text(if (prefs.temporaryRoot) "Root is limited to an app session, one chat or one action, and to allowed projects."
                        else "Root is allowed in every project and chat while the connection stays active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (prefs.temporaryRoot) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
                Switch(checked = prefs.temporaryRoot, onCheckedChange = { enabled ->
                    if (enabled) scope.launch { access.store.setTemporaryRoot(true) } else confirmUnlimited = true
                })
            }
            if (prefs.temporaryRoot) {
                Text("Temporary root use", style = MaterialTheme.typography.titleSmall)
                Box {
                    OutlinedButton(onClick = { useMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(prefs.rootUse.label, Modifier.weight(1f)); Icon(Icons.Outlined.ExpandMore, null)
                    }
                    DropdownMenu(expanded = useMenu, onDismissRequest = { useMenu = false }) {
                        RootUse.entries.forEach { use -> DropdownMenuItem(text = { Text(use.label) },
                            enabled = use != RootUse.CHAT || settings.lastActiveSessionId != null,
                            onClick = { useMenu = false; access.disableRootUse(); scope.launch { access.store.setRootUse(use) } }) }
                    }
                }
                Text(allowance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("Grant root access once after each app start to reconnect. Turning this back on restores the per-use limits.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { projectPicker = true }, enabled = prefs.temporaryRoot, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("Project root permissions", Modifier.weight(1f))
                Text("${projects.count { it.id in prefs.rootProjects }}", style = MaterialTheme.typography.labelMedium)
            }
            if (!prefs.temporaryRoot) Text("Project permissions are unused while temporary root mode is off.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AccessActionPair(first = { buttonModifier ->
                FilledTonalButton(onClick = { browser = true }, enabled = root.status == RootStatus.READY || shizukuUid == 0,
                    modifier = buttonModifier) { Text("Root browser") }
            }, second = { buttonModifier ->
                OutlinedButton(onClick = { report = access.report() }, modifier = buttonModifier) { Text("Diagnostics") }
            })
            Text("Last used: $last", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (paused) {
                FilledTonalButton(onClick = { scope.launch { report = access.resume() } }, modifier = Modifier.fillMaxWidth()) { Text("Resume privileged access") }
            } else {
                OutlinedButton(onClick = { scope.launch {
                    busy = true
                    try { report = access.stopPrivileged() } finally { busy = false }
                } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Stop privileged tasks")
                }
            }
        }
    }
    if (projectPicker) AlertDialog(onDismissRequest = { projectPicker = false }, title = { Text("Project root permissions") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Allow the agent to request root in selected projects. Full access mode does not override this choice.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(projects, key = { it.id }) { item ->
                    val checked = item.id in prefs.rootProjects
                    Row(Modifier.fillMaxWidth().clickable { scope.launch { access.store.allowProject(item.id, !checked) } }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = { value -> scope.launch { access.store.allowProject(item.id, value) } })
                        Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { projectPicker = false }) { Text("Done") } })
    if (confirmUnlimited) AlertDialog(
        onDismissRequest = { confirmUnlimited = false },
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Use root everywhere?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Temporary root mode limits root to one app session, one chat or one action, and to projects you allow.")
            Text("Turning it off lets the agent use root in every project and chat for as long as the root connection is active. Project permissions and per-use limits no longer apply.")
            Text("Only turn this off on a device you fully control. You still grant root explicitly once after each app start.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(onClick = {
            confirmUnlimited = false
            scope.launch { access.store.setTemporaryRoot(false) }
        }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Use root everywhere") } },
        dismissButton = { TextButton(onClick = { confirmUnlimited = false }) { Text("Keep temporary mode") } },
    )
    report?.let { text -> AlertDialog(onDismissRequest = { report = null }, title = { Text("Device access diagnostics") },
        text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton(onClick = { report = null }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy report") } }) }
    if (browser) RootBrowserDialog(access, project?.id, settings.lastActiveSessionId) { browser = false }
}

@Composable
internal fun AccessModeChoices(selected: AccessMode, onSelect: (AccessMode) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 280.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f) 1 else 2
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccessMode.entries.chunked(columns).forEach { modes ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    modes.forEach { mode ->
                        OutlinedCard(modifier = Modifier.weight(1f).selectable(selected == mode, role = Role.RadioButton, onClick = { onSelect(mode) }),
                            colors = CardDefaults.outlinedCardColors(containerColor = if (selected == mode) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected == mode, onClick = null, modifier = Modifier.size(20.dp))
                                Text(mode.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessActionPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 280.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.weight(1f)); second(Modifier.weight(1f))
            }
        }
    }
}
