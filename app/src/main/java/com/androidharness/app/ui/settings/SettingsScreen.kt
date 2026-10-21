package com.androidharness.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.filterNotNull
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ChatBackupException
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.components.FullAccessOrange
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.settings.ProviderManagerSheet
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.data.env.UserServiceState
import com.androidharness.app.ui.common.formatTokenCount
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.HarnessMark
import com.androidharness.app.ui.common.openOAuthBrowser
import com.androidharness.app.ui.common.AddWorkspaceDialog
import com.androidharness.app.ui.common.BiometricAuth
import com.androidharness.app.ui.common.findFragmentActivity
import com.androidharness.app.ui.common.SecureDialogEffect
import com.androidharness.app.ui.common.SecureScreenEffect
import com.androidharness.app.ui.common.SystemGrants
import com.androidharness.app.ui.common.rememberStorageAccess
import com.androidharness.app.ui.common.ThinLinearProgress
import com.androidharness.app.ui.theme.HarnessMono
import com.androidharness.app.ui.theme.LocalStatusColors
import com.androidharness.app.tools.mcp.McpConfigParser
import com.androidharness.app.tools.mcp.McpServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenStats: () -> Unit = {},
    onRunSetup: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val workspace by container.workspace.current.collectAsStateWithLifecycle(initialValue = null)
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentProject by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
    val envState by container.linuxEnv.state.collectAsStateWithLifecycle()
    val shizukuState by container.shizuku.state.collectAsStateWithLifecycle(initialValue = ShizukuState.NOT_INSTALLED)
    val serviceState by container.shizuku.serviceState.collectAsStateWithLifecycle(initialValue = UserServiceState.NOT_BOUND)
    val scope = rememberCoroutineScope()

    // Settings itself is screenshot-able; the surfaces that actually put a
    // key or token on screen raise the FLAG_SECURE policy themselves (the
    // web-search entry forms below, plus the MCP dialogs).

    var showAddWorkspace by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    val desktop = com.androidharness.app.ui.LocalDesktopLayout.current
    val page = pageName?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
        ?: if (desktop) SettingsPage.APPEARANCE else null
    // Label of the control a search result or deep link asked for, cleared once
    // the user has had a moment to see where they landed.
    var highlight by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(highlight) {
        if (highlight != null) {
            kotlinx.coroutines.delay(2600)
            highlight = null
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val homeScroll = rememberScrollState()
    fun goBack() {
        when {
            desktop -> onBack()
            page != null -> pageName = null
            query.isNotBlank() -> query = ""
            else -> onBack()
        }
    }
    BackHandler(enabled = page != null || query.isNotBlank()) { goBack() }
    LaunchedEffect(Unit) {
        container.pendingSettingsScroll.filterNotNull().collect { target ->
            settingsDeepLink(target)?.let { pageName = it.name }
            highlight = settingsDeepLinkAnchor(target)
            container.pendingSettingsScroll.value = null
        }
    }
    fun openPage(destination: SettingsPage, anchor: Int? = null) {
        highlight = anchor
        when (destination) {
            SettingsPage.SKILLS -> onOpenSkills()
            SettingsPage.USAGE -> onOpenStats()
            SettingsPage.SETUP -> onRunSetup()
            else -> pageName = destination.name
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) scope.launch { container.workspace.addPickedFolder(uri) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppHeader(
                title = if (desktop) stringResource(R.string.settings_page_title) else page?.let { stringResource(it.title) }
                    ?: stringResource(R.string.settings_page_title),
                onBack = { goBack() },
            )
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (desktop) {
                DesktopSettingsNavigation(page, query, { query = it }, ::openPage,
                    Modifier.width(240.dp).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        if (page == null) {
            SettingsHome(
                query = query,
                onQueryChange = { query = it },
                onOpen = ::openPage,
                modifier = Modifier.fillMaxSize().verticalScroll(homeScroll),
            )
        } else {
            key(page) {
                SettingsHighlightScope(highlight) {
                    Column(
                        modifier = Modifier.then(if (desktop) Modifier.widthIn(max = 880.dp) else Modifier)
                            .fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (page != SettingsPage.LOCAL_MODELS && page != SettingsPage.AGENT && page != SettingsPage.TELEGRAM && page != SettingsPage.STORAGE) SettingsPageIntro(page)
                    when (page) {
                        SettingsPage.CONNECTED_ACCOUNTS -> ConnectedAccountsSection(container)
                        SettingsPage.MODELS -> {
                            SettingsPanel(Modifier.fillMaxWidth()) {
                                SettingsAnchor(R.string.settings_anchor_manage_providers) {
                                    SettingRow(
                                        icon = Icons.Outlined.Key,
                                        title = stringResource(R.string.settings_anchor_manage_providers),
                                        subtitle = stringResource(R.string.settings_models_manage_subtitle),
                                        onClick = onOpenProviders,
                                        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                                    )
                                }
                            }
                            SettingsAnchor(R.string.settings_anchor_dual_planning) {
                                PlanningModelSection(container, settings, scope, onOpenProviders)
                            }
                            CurrentSetupCard(settings, providers)
                            FallbackSettingsSection(container, settings.fallbackPolicy)
                        }
                        SettingsPage.SUBAGENT -> {
                            SubagentModelSection(container, settings)
                            SubagentSettingsSection(
                                settings = settings,
                                onSubagentTools = { scope.launch { container.settings.setSubagentFullAccess(it) } },
                            )
                        }
                        SettingsPage.LOCAL_MODELS -> LocalModelsSection(container)
                        SettingsPage.AGENT -> AgentSection(container, settings, scope)
                        SettingsPage.CHAT -> {
                            ChatBehaviorSection(container, settings, scope)
                            SlashCommandsSection(container)
                        }
                        SettingsPage.CODE_INTELLIGENCE -> CodeIntelligenceSection(container, workspace, currentProject)
                        SettingsPage.CAVEMAN -> CavemanSection(container, settings)
                        SettingsPage.VOICE -> VoiceSpeechSection(container, settings, scope)
                        SettingsPage.APPEARANCE -> AppearanceSection(container, settings, scope)
                        SettingsPage.PRIVACY -> PrivacySection(container, settings, scope)
                        SettingsPage.GITHUB -> GitHubIntegrationSection(container)
                        SettingsPage.SEARCH -> WebSearchSection(container)
                        SettingsPage.MCP -> McpSection(container)
                        SettingsPage.TELEGRAM -> TelegramSection(container)
                        SettingsPage.WORKSPACE -> {
                            RememberedPermissionsSection(container)
                            WorkspaceSection(
                                container, projects, currentProject, workspace?.displayPath, shizukuState,
                                onAddWorkspace = { showAddWorkspace = true },
                                onDeleteWorkspace = { pendingDelete = it },
                                onSelectWorkspace = { id -> scope.launch { container.workspace.setActiveProject(id) } },
                            )
                        }
                        SettingsPage.STORAGE -> StorageSection(container)
                        SettingsPage.ENVIRONMENT -> TerminalSection(container, envState, shizukuState, serviceState)
                        SettingsPage.TROUBLESHOOTING -> TroubleshootingSection(container, ::openPage)
                        SettingsPage.BACKUP -> {
                            SettingsBackupSection(container)
                            ChatsBackupSection(container)
                        }
                        SettingsPage.UPDATES -> UpdatesCard(container)
                        SettingsPage.ABOUT -> AboutSection(container)
                        SettingsPage.SKILLS, SettingsPage.USAGE, SettingsPage.SETUP -> Unit
                    }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
            }
        }
    }

    if (showAddWorkspace) {
        AddWorkspaceDialog(
            container = container,
            onDismiss = { showAddWorkspace = false },
            onPickSaf = {
                showAddWorkspace = false
                folderPicker.launch(null)
            },
        )
    }

    pendingDelete?.let { project ->
        var deleting by remember(project.id) { mutableStateOf(false) }
        var deleteError by remember(project.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDelete = null },
            title = { Text(stringResource(R.string.settings_workspace_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_workspace_delete_message, project.name))
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    deleteError = null
                    scope.launch {
                        try {
                            container.workspace.deleteProject(project)
                            pendingDelete = null
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            deleteError = e.message?.takeIf { it.isNotBlank() } ?: "Could not remove workspace. Try again."
                        } finally {
                            deleting = false
                        }
                    }
                }) { Text(if (deleting) "Deleting…" else "Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { pendingDelete = null }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Workspace
// ---------------------------------------------------------------------------

@Composable
private fun WorkspaceSection(
    container: AppContainer,
    projects: List<ProjectEntity>,
    currentProject: ProjectEntity?,
    workspacePath: String?,
    shizukuState: ShizukuState,
    onAddWorkspace: () -> Unit,
    onDeleteWorkspace: (ProjectEntity) -> Unit,
    onSelectWorkspace: (String) -> Unit,
) {
    com.androidharness.app.ui.common.ShizukuWorkspaceWarningEffect(container.shizuku)
    SettingsHeader("Workspace")

    currentProject?.let { current ->
        val desc = container.workspace.describe(current)
        val scheme = MaterialTheme.colorScheme
        Surface(
            color = scheme.surface,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
            ) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = scheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_workspace_active), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Text(current.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        workspacePath ?: desc.kindLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.settings_workspace_active_badge),
                    tint = scheme.primary,
                )
            }
        }
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            projects.forEachIndexed { index, project ->
                val desc = container.workspace.describe(project)
                val isActive = project.id == currentProject?.id
                SettingRow(
                    icon = if (project.kind == "APP") Icons.Outlined.Folder else Icons.Outlined.FolderOpen,
                    title = project.name,
                    subtitle = desc.kindLabel + " · " + if (desc.shellCapable) "full shell" else "file tools only",
                    onClick = { onSelectWorkspace(project.id) }.takeIf { !isActive },
                    divider = index != projects.lastIndex,
                    trailing = {
                        if (isActive) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = stringResource(R.string.settings_workspace_active), tint = MaterialTheme.colorScheme.primary)
                        } else if (project.kind != "APP") {
                            IconButton(onClick = { onDeleteWorkspace(project) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.settings_workspace_delete_cd, project.name), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                        }
                    },
                )
            }
        }
        Button(
            onClick = onAddWorkspace,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_workspace_add))
        }
    }
}

// ---------------------------------------------------------------------------
// Terminal & environment
// ---------------------------------------------------------------------------

@Composable
private fun TerminalSection(
    container: AppContainer,
    envState: com.androidharness.app.data.env.EnvState,
    shizukuState: ShizukuState,
    serviceState: UserServiceState,
) {
    val storageAccess = rememberStorageAccess()
    val allFiles = storageAccess.granted
    val connectionFailure by container.shizuku.connectionFailure.collectAsStateWithLifecycle()

    SettingsHeader("Terminal & environment")

    LinuxEnvironmentCard(container = container, envState = envState)

    // What the shell can reach right now.
    val appShellOk = envState is com.androidharness.app.data.env.EnvState.Ready
    val storageText = if (allFiles) "Granted ✓" else "Needs grant"
    val systemText = when (shizukuState) {
        ShizukuState.GRANTED -> when (serviceState) {
            UserServiceState.BOUND_READY -> "Shizuku ✓"
            UserServiceState.COMPAT_READY -> "Compatibility ✓"
            UserServiceState.BINDING -> "Connecting…"
            UserServiceState.BIND_FAILED -> "Connection failed"
            UserServiceState.NOT_BOUND -> "Not connected"
        }
        ShizukuState.RUNNING_NO_PERMISSION -> "Needs grant"
        ShizukuState.NOT_RUNNING -> "Start Shizuku"
        ShizukuState.NOT_INSTALLED -> "Not installed"
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            SettingsAnchor(R.string.settings_anchor_app_shell) {
                SettingRow(
                    icon = Icons.Outlined.Terminal,
                    title = stringResource(R.string.settings_anchor_app_shell),
                    subtitle = stringResource(R.string.settings_env_app_shell_subtitle),
                    divider = true,
                    trailing = { StatusText(if (appShellOk) "Ready" else "toybox", ok = appShellOk) },
                )
            }
            SettingsAnchor(R.string.settings_anchor_shared_storage) {
                SettingRow(
                    icon = Icons.Outlined.SdStorage,
                    title = stringResource(R.string.settings_anchor_shared_storage),
                    subtitle = stringResource(R.string.settings_env_shared_storage_subtitle),
                    onClick = storageAccess.request,
                    divider = true,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusText(storageText, ok = allFiles)
                            if (!allFiles) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.settings_env_open_storage_settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
            }
            SettingsAnchor(R.string.settings_anchor_system_paths) {
                SettingRow(
                    icon = Icons.Outlined.Shield,
                    title = stringResource(R.string.settings_anchor_system_paths),
                    subtitle = stringResource(R.string.settings_env_shizuku_subtitle),
                    divider = false,
                    trailing = { StatusText(systemText, ok = shizukuState == ShizukuState.GRANTED && serviceState.isReady) },
                )
            }
        }
    }

    StorageAccessCard(allFiles = allFiles, onGrant = storageAccess.request)
    ShizukuCard(
        state = shizukuState,
        serviceState = serviceState,
        failure = connectionFailure,
        connectionReport = container.shizuku::connectionReport,
        startupReport = container.shizuku::startupReport,
        onRefresh = { container.shizuku.refresh() },
        onGrant = { container.shizuku.requestPermission() },
    )
    RootAccessSection(container)
    BatteryCard(container = container)
}

// ---------------------------------------------------------------------------
// Code intelligence
// ---------------------------------------------------------------------------

@Composable
private fun CodeIntelligenceSection(
    container: AppContainer,
    workspace: com.androidharness.app.workspace.WorkspaceFs?,
    currentProject: ProjectEntity?,
) {
    val codeGraphState by container.codeGraph.state.collectAsStateWithLifecycle()
    val workspaceRun by container.codeGraph.workspaceState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val rootPath = workspace?.shellRoot?.absolutePath
    var indexRevision by remember(rootPath) { mutableStateOf(0) }
    val indexed = remember(rootPath, indexRevision) { container.codeGraph.isIndexed(workspace) }

    LaunchedEffect(rootPath) {
        container.codeGraph.refresh()
        // Ask GitHub once per visit so the buttons can say what is actually
        // available instead of offering a blind "Update".
        container.codeGraph.checkForUpdates()
    }

    SettingsHeader("CodeGraph")
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_codegraph_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Local semantic code graph for symbols, callers, impact and affected tests",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        codeGraphState.version == null -> "Not installed"
                        codeGraphState.updateAvailable -> "v${codeGraphState.version} → v${codeGraphState.latest}"
                        codeGraphState.latest != null -> "v${codeGraphState.version} · latest"
                        else -> "v${codeGraphState.version}"
                    },
                    ok = codeGraphState.version != null,
                )
            }

            if (codeGraphState.busy) {
                ThinLinearProgress(modifier = Modifier.fillMaxWidth())
                Text(
                    codeGraphState.action ?: "Working…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            codeGraphState.message?.takeLast(1_200)?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (codeGraphState.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (codeGraphState.version == null) {
                    Button(
                        enabled = !codeGraphState.busy,
                        onClick = { scope.launch { container.codeGraph.installOrUpdate() } },
                    ) { Text(stringResource(R.string.settings_codegraph_install)) }
                } else {
                    // Only offer an update when a newer release actually exists;
                    // otherwise the tap is a check that answers "already latest".
                    if (codeGraphState.updateAvailable) {
                        Button(
                            enabled = !codeGraphState.busy,
                            onClick = { scope.launch { container.codeGraph.installOrUpdate() } },
                        ) { Text(stringResource(R.string.settings_codegraph_update_to, codeGraphState.latest.orEmpty())) }
                    } else {
                        OutlinedButton(
                            enabled = !codeGraphState.busy,
                            onClick = { scope.launch { container.codeGraph.checkForUpdates() } },
                        ) { Text(stringResource(R.string.settings_search_updates_title)) }
                    }
                    OutlinedButton(
                        enabled = !codeGraphState.busy,
                        onClick = { scope.launch { container.codeGraph.uninstall() } },
                    ) { Text(stringResource(R.string.settings_action_uninstall)) }
                }
            }

            Text(
                "Harness installs CodeGraph into its private Linux environment, about 170 MB with the Node runtime it needs. No Termux app, no Shizuku and no separate CodeGraph setup for your agents.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SettingsHeader("Active workspace")
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(currentProject?.name ?: "No workspace", style = MaterialTheme.typography.titleSmall)
                    Text(
                        rootPath ?: "This workspace does not expose a real filesystem path",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (rootPath != null) {
                    StatusText(if (indexed) "Indexed" else "Not indexed", ok = indexed)
                }
            }

            // Both an install and a sync make this card unusable for a moment,
            // so the row reports whichever is running.
            val workspaceBusy = workspaceRun.busy || codeGraphState.busy
            val workspaceAction = workspaceRun.action ?: codeGraphState.action

            if (workspaceBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        workspaceAction ?: "Working…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Live output of the run that is happening or has just finished,
            // shown here rather than in the install card above, which is not
            // what was tapped. During a long index these lines are the only
            // sign of life, so the tail scrolls with the work.
            workspaceRun.message?.takeLast(1_200)?.let { message ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        message.trim().lines().takeLast(9).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = HarnessMono),
                        color = if (workspaceRun.failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            when {
                rootPath == null -> Text(
                    "CodeGraph needs a shell-capable workspace with a real device path.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                codeGraphState.version == null -> Text(
                    "Install CodeGraph first, then enable it for this workspace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !indexed -> Button(
                    enabled = !workspaceBusy,
                    onClick = {
                        val current = workspace
                        scope.launch {
                            container.codeGraph.initWorkspace(current)
                            indexRevision++
                        }
                    },
                ) { Text(stringResource(R.string.settings_codegraph_enable)) }
                else -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        enabled = !workspaceBusy,
                        onClick = {
                            val current = workspace
                            scope.launch {
                                container.codeGraph.syncWorkspace(current)
                                indexRevision++
                            }
                        },
                    ) { Text(stringResource(R.string.settings_action_sync)) }
                    OutlinedButton(
                        enabled = !workspaceBusy,
                        onClick = {
                            val current = workspace
                            scope.launch {
                                container.codeGraph.reindexWorkspace(current)
                                indexRevision++
                            }
                        },
                    ) { Text(stringResource(R.string.settings_codegraph_reindex)) }
                    TextButton(
                        enabled = !workspaceBusy,
                        onClick = {
                            val current = workspace
                            scope.launch {
                                container.codeGraph.disableWorkspace(current)
                                indexRevision++
                            }
                        },
                    ) { Text(stringResource(R.string.settings_action_disable)) }
                }
            }
        }
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.settings_codegraph_agent_integration), style = MaterialTheme.typography.titleSmall)
            Text(
                "When this workspace is indexed, Harness exposes CodeGraph explore, node, impact, affected-tests and sync tools to the coding agent. Queries sync changed files before reading the graph.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Web search
// ---------------------------------------------------------------------------

@Composable
private fun WebSearchSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val provider = settings.webSearchProvider
    val providerName = when (provider) {
        "brave" -> "Brave Search API"
        "tavily" -> "Tavily API"
        else -> "Keyless"
    }
    var keyDraft by remember { mutableStateOf("") }
    var keyEpoch by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val hasKey = remember(provider, keyEpoch) { container.keys.searchApiKey(provider) != null }
    val keyUrl = if (provider == "brave") "https://brave.com/search/api/" else "https://app.tavily.com/home"

    // The pasted key is only on screen while the entry form is open.
    SecureScreenEffect(container, expanded)

    SettingsHeader("Web search")
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_web_backend_label), style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            provider == "keyless" -> "Public search engines, no setup"
                            hasKey -> "$providerName · key saved"
                            else -> "$providerName · no key yet (keyless fallback)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        provider == "keyless" -> "Keyless"
                        hasKey -> "On"
                        else -> "No key"
                    },
                    ok = provider == "keyless" || hasKey,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("keyless" to "Keyless", "brave" to "Brave", "tavily" to "Tavily")
                    .forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = provider == value,
                            onClick = {
                                if (provider != value) {
                                    expanded = false
                                    status = null
                                    keyDraft = ""
                                    scope.launch { container.settings.setWebSearchProvider(value) }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        ) { Text(label) }
                    }
            }

            when {
                provider == "keyless" -> Text(
                    "The agent searches public engines with zero setup; Brave or Tavily " +
                        "give cleaner, higher-quality results.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                expanded -> {
                    Text(
                        (if (provider == "brave") {
                            "Free key at brave.com/search/api (Free plan: 1 query/second). "
                        } else {
                            "Free key at app.tavily.com (1,000 requests/month). "
                        }) + "The key is checked with the provider before it is saved, and it " +
                            "lives only in the app's encrypted storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl)))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_provider_get_api_key))
                    }
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        label = { Text(if (hasKey) "Replace API key" else "Paste API key") },
                        placeholder = { Text(if (provider == "brave") "BSA…" else "tvly-…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = keyDraft.isNotBlank() && !checking,
                            onClick = {
                                checking = true
                                status = "Checking that key with $providerName…"
                                scope.launch {
                                    val key = keyDraft.trim()
                                    val error = withContext(Dispatchers.IO) { checkSearchKey(provider, key) }
                                    if (error == null) {
                                        withContext(Dispatchers.IO) { container.keys.putSearchApiKey(provider, key) }
                                        keyDraft = ""
                                        expanded = false
                                        status = "Key verified and saved"
                                        keyEpoch++
                                    } else {
                                        status = error
                                    }
                                    checking = false
                                }
                            },
                        ) { Text(if (checking) "Checking…" else "Save and check") }
                        TextButton(onClick = {
                            expanded = false
                            keyDraft = ""
                            status = null
                        }) { Text(stringResource(R.string.settings_action_cancel)) }
                    }
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                hasKey -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        expanded = true
                        keyDraft = ""
                        status = null
                    }) { Text(stringResource(R.string.settings_provider_replace_key)) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { container.keys.removeSearchApiKey(provider) }
                            keyEpoch++
                        }
                    }) { Text(stringResource(R.string.settings_action_remove), color = MaterialTheme.colorScheme.error) }
                }

                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl)))
                        }
                    }) { Text(stringResource(R.string.settings_provider_get_api_key)) }
                    OutlinedButton(onClick = {
                        expanded = true
                        keyDraft = ""
                        status = null
                    }) { Text(stringResource(R.string.settings_provider_have_key)) }
                }
            }
        }
    }
}

/**
 * Asks the provider to accept a search API key before it is saved, so an
 * invalid paste never becomes the stored credential.
 * Returns null when the key works, or a human-readable reason why not.
 */
private fun checkSearchKey(provider: String, key: String): String? {
    return runCatching {
        val req = when (provider) {
            "brave" -> okhttp3.Request.Builder()
                .url("https://api.search.brave.com/res/v1/web/search?q=android&count=1")
                .header("X-Subscription-Token", key)
                .header("Accept", "application/json")
                .build()
            else -> okhttp3.Request.Builder()
                .url("https://api.tavily.com/search")
                .post(
                    """{"api_key":"$key","query":"android","max_results":1}"""
                        .toRequestBody("application/json".toMediaTypeOrNull()),
                )
                .build()
        }
        val name = if (provider == "brave") "Brave" else "Tavily"
        okhttp3.OkHttpClient.Builder()
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            .newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val detail = Regex("\"(?:message|detail|error)\"\\s*:\\s*\"([^\"]+)\"")
                    .find(body)?.groupValues?.get(1)
                when {
                    resp.code == 401 || resp.code == 403 ->
                        "$name rejected that key" + (detail?.let { " ($it)" } ?: "") +
                            ". Make sure the whole key was copied, that it belongs to $name, " +
                            "and that it has not been deactivated."
                    !resp.isSuccessful ->
                        "$name returned HTTP ${resp.code}" + (detail?.let { ": $it" } ?: "") +
                            ". Try again shortly."
                    else -> null
                }
            }
    }.getOrElse { "Could not reach the provider. Check your connection and try again." }
}

// ---------------------------------------------------------------------------
// Voice & speech (Groq Whisper / Inbuilt)
// ---------------------------------------------------------------------------

@Composable
private fun VoiceSpeechSection(
    container: AppContainer,
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    val voiceEngine = settings.voiceEngine
    val groqModel = settings.groqWhisperModel
    val isGroq = voiceEngine == AppSettings.VOICE_ENGINE_GROQ

    var keyDraft by remember { mutableStateOf("") }
    var keyEpoch by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val hasGroqKey = remember(keyEpoch) { container.keys.groqApiKey() != null }
    val groqKeyUrl = "https://console.groq.com/keys"

    // The pasted key is only on screen while the entry form is open.
    SecureScreenEffect(container, expanded)

    SettingsHeader("Voice & speech")
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    SettingsAnchor(R.string.settings_anchor_stt) {
                        Text(stringResource(R.string.settings_anchor_stt), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        when {
                            !isGroq -> "Inbuilt Android recognizer"
                            hasGroqKey -> "Groq Whisper ($groqModel) · key saved"
                            else -> "Groq Whisper · no key yet"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        !isGroq -> "Inbuilt"
                        hasGroqKey -> "On"
                        else -> "No key"
                    },
                    ok = !isGroq || hasGroqKey,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(
                    AppSettings.VOICE_ENGINE_INBUILT to "Inbuilt",
                    AppSettings.VOICE_ENGINE_GROQ to "Groq",
                ).forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = voiceEngine == value,
                        onClick = {
                            if (voiceEngine != value) {
                                expanded = false
                                status = null
                                keyDraft = ""
                                scope.launch { container.settings.setVoiceEngine(value) }
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    ) { Text(label) }
                }
            }

            if (!isGroq) {
                Text(
                    "Uses the on-device or system speech recognizer without sending audio to external APIs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SettingsAnchor(R.string.settings_anchor_whisper) {
                    Text(
                        stringResource(R.string.settings_anchor_whisper),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(
                        AppSettings.GROQ_MODEL_WHISPER_V3 to "Whisper 3",
                        AppSettings.GROQ_MODEL_WHISPER_TURBO to "Turbo",
                    ).forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = groqModel == value,
                            onClick = {
                                if (groqModel != value) {
                                    scope.launch { container.settings.setGroqWhisperModel(value) }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        ) { Text(label) }
                    }
                }

                when {
                    expanded -> {
                        Text(
                            "Get a free API key at console.groq.com/keys. The key is checked with " +
                                "Groq before it is saved, and it lives only in the app's encrypted storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(groqKeyUrl)))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_voice_get_groq_key))
                        }
                        OutlinedTextField(
                            value = keyDraft,
                            onValueChange = { keyDraft = it },
                            label = { Text(if (hasGroqKey) "Replace Groq API key" else "Paste Groq API key") },
                            placeholder = { Text("gsk_…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = keyDraft.isNotBlank() && !checking,
                                onClick = {
                                    checking = true
                                    status = "Checking that key with Groq…"
                                    scope.launch {
                                        val key = keyDraft.trim()
                                        val res = com.androidharness.app.data.audio.GroqWhisperClient.validateApiKey(key)
                                        if (res.success) {
                                            withContext(Dispatchers.IO) { container.keys.putGroqApiKey(key) }
                                            keyDraft = ""
                                            expanded = false
                                            status = "Key verified and saved"
                                            keyEpoch++
                                        } else {
                                            status = res.error ?: "Verification failed"
                                        }
                                        checking = false
                                    }
                                },
                            ) { Text(if (checking) "Checking…" else "Save and check") }
                            TextButton(onClick = {
                                expanded = false
                                keyDraft = ""
                                status = null
                            }) { Text(stringResource(R.string.settings_action_cancel)) }
                        }
                        status?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    hasGroqKey -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            expanded = true
                            keyDraft = ""
                            status = null
                        }) { Text(stringResource(R.string.settings_provider_replace_key)) }
                        OutlinedButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { container.keys.removeGroqApiKey() }
                                keyEpoch++
                            }
                        }) { Text(stringResource(R.string.settings_action_remove), color = MaterialTheme.colorScheme.error) }
                    }

                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(groqKeyUrl)))
                            }
                        }) { Text(stringResource(R.string.settings_provider_get_api_key)) }
                        OutlinedButton(onClick = {
                            expanded = true
                            keyDraft = ""
                            status = null
                        }) { Text(stringResource(R.string.settings_provider_have_key)) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// MCP servers
// ---------------------------------------------------------------------------

@Composable
private fun McpSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val servers by container.mcp.servers.collectAsStateWithLifecycle(initialValue = emptyList())
    val statuses by container.mcp.statuses.collectAsStateWithLifecycle(initialValue = emptyMap())
    val configTampered by container.mcp.configTampered.collectAsStateWithLifecycle(initialValue = false)
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    val anyConnected = servers.any { statuses[it.name]?.state == "connected" }

    SettingsHeader("MCP servers")
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (configTampered) {
                Text(
                    "The MCP server list was modified outside the app and failed its integrity " +
                        "check; its contents were ignored. Re-add your servers below to rebuild it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_mcp_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Extra tools the agent can use in any chat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusText(
                    when {
                        servers.isEmpty() -> "Off"
                        anyConnected -> "On"
                        else -> "Idle"
                    },
                    ok = anyConnected,
                )
            }

            servers.forEach { server ->
                val status = statuses[server.name]
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dotColor = when {
                            !server.enabled -> MaterialTheme.colorScheme.outlineVariant
                            status?.state == "connected" -> LocalStatusColors.current.success
                            status?.state == "auth" -> LocalStatusColors.current.warning
                            status?.state == "failed" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                        Box(Modifier.size(9.dp).background(dotColor, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${server.name} · ${server.type}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = server.enabled,
                            onCheckedChange = { checked ->
                                scope.launch { container.mcp.setServerEnabled(server.name, checked) }
                            },
                        )
                    }
                    Text(
                        when {
                            !server.enabled -> "Off (enable to use its tools)"
                            status == null -> "Not connected yet; tap Test, or it connects before a run"
                            status.state == "connected" -> "Connected · ${status.toolCount} tools"
                            status.state == "auth" -> "Sign in to use this server"
                            status.state == "connecting" -> "Connecting…"
                            else -> "Failed: ${status.error?.take(110) ?: "unknown error"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status?.needsAuth == true && server.enabled) {
                        Button(
                            onClick = {
                                authError = null
                                scope.launch {
                                    container.mcp.startAuthentication(server.name).fold(
                                        onSuccess = { url ->
                                            runCatching {
                                                openOAuthBrowser(context, Uri.parse(url))
                                            }
                                        },
                                        onFailure = { e ->
                                            authError = e.message?.take(160)
                                        },
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.settings_action_authenticate)) }
                        authError?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            scope.launch { withContext(Dispatchers.IO) { container.mcp.testConnection(server) } }
                        }) { Text(stringResource(R.string.settings_action_test)) }
                        TextButton(onClick = { editing = server }) { Text(stringResource(R.string.settings_action_edit)) }
                        TextButton(onClick = {
                            scope.launch { container.mcp.removeServer(server.name) }
                        }) { Text(stringResource(R.string.settings_action_remove)) }
                    }
                }
            }

            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.settings_action_add_server))
            }
        }
    }

    if (showAdd) {
        McpAddDialog(container, onDismiss = { showAdd = false }, onManual = {
            showAdd = false
            editing = McpServerConfig(name = "", type = "stdio")
        })
    }
    editing?.let { config ->
        McpServerDialog(container, initial = config, onDismiss = { editing = null })
    }
}

/**
 * Paste-first add dialog: accepts a Claude-style `{"mcpServers": {…}}` JSON, a
 * single-server JSON object, or a `claude mcp add …` command line, showing a
 * live preview of what will be added. A manual editor is one tap away.
 */
@Composable
private fun McpAddDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onManual: () -> Unit,
) {
    // Pasted config routinely carries auth headers; the dialog has its own
    // window, so it raises FLAG_SECURE itself.
    SecureDialogEffect()
    val scope = rememberCoroutineScope()
    var paste by remember { mutableStateOf("") }
    val parsed = remember(paste) { McpConfigParser.parsePaste(paste) }
    val existing = container.mcp.servers.value.map { it.name.lowercase() }
    val conflicts = parsed.count { it.name.lowercase() in existing }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_mcp_add_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = paste,
                    onValueChange = { paste = it },
                    label = { Text(stringResource(R.string.settings_mcp_paste_config)) },
                    placeholder = {
                        Text(
                            "{\"mcpServers\": {\"supabase\": {\"type\": \"http\", \"url\": \"…\"}}}\n" +
                                "or: claude mcp add --transport http supabase \"https://…\"",
                        )
                    },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    paste.isBlank() -> Text(
                        "Works with any of: a full mcpServers JSON (Claude Desktop / Cursor format), " +
                            "a single server JSON object, or a `claude mcp add` command.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    parsed.isEmpty() -> Text(
                        "Could not parse that. Use the manual editor below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Column {
                        Text(
                            "Will add ${parsed.size} server(s):",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        parsed.forEach { c ->
                            Text(
                                "• ${c.name} · ${c.type} · " +
                                    (c.url ?: c.command + if (c.args.isEmpty()) "" else " ${c.args.joinToString(" ")}"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (conflicts > 0) {
                            Text(
                                "$conflicts name(s) already exist and will be replaced.",
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalStatusColors.current.warning,
                            )
                        }
                    }
                }
                Text(
                    "Tools appear in chats as mcp__server__tool and follow the normal " +
                        "permission rules. If a server needs sign-in, an Authenticate " +
                        "button appears after Test.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed.isNotEmpty(),
                onClick = {
                    scope.launch {
                        parsed.forEach { container.mcp.addServer(it) }
                        onDismiss()
                    }
                },
            ) { Text(if (parsed.size > 1) "Add all" else "Add") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onManual) { Text(stringResource(R.string.settings_mcp_manual_editor)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) }
            }
        },
    )
}

@Composable
private fun McpServerDialog(
    container: AppContainer,
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
) {
    // Header and env values are secrets; this dialog owns its own window.
    SecureDialogEffect()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "stdio") }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var argsText by remember { mutableStateOf(initial?.args?.joinToString("\n") ?: "") }
    var envText by remember {
        mutableStateOf(initial?.env?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "")
    }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var headersText by remember {
        mutableStateOf(initial?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "")
    }
    val duplicate = (initial == null || initial.name.isBlank()) &&
        container.mcp.servers.value.any { it.name.equals(name.trim(), ignoreCase = true) }
    val valid = when (type) {
        "http", "sse" -> name.isNotBlank() && url.isNotBlank() && !duplicate
        else -> name.isNotBlank() && command.isNotBlank() && !duplicate
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial?.name.isNullOrBlank()) "Add MCP server" else "Edit MCP server") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_mcp_name)) },
                    placeholder = { Text(stringResource(R.string.settings_mcp_name_hint)) },
                    singleLine = true,
                    supportingText = { if (duplicate) Text(stringResource(R.string.settings_mcp_name_taken)) },
                    isError = duplicate,
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("stdio" to "Local", "http" to "HTTP", "sse" to "SSE")
                        .forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = type == value,
                                onClick = { type = value },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                            ) { Text(label, maxLines = 1) }
                        }
                }
                if (type == "stdio") {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text(stringResource(R.string.settings_mcp_command)) },
                        placeholder = { Text(stringResource(R.string.settings_mcp_command_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text(stringResource(R.string.settings_mcp_arguments)) },
                        placeholder = { Text("-y\n@modelcontextprotocol/server-filesystem /path") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = envText,
                        onValueChange = { envText = it },
                        label = { Text(stringResource(R.string.settings_mcp_env_vars)) },
                        minLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Local process (stdio transport) running as the app user with the " +
                            "Linux toolchain's PATH; it starts lazily before a run and dies with " +
                            "the app. Needs node/python from the Linux environment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.settings_mcp_server_url)) },
                        placeholder = { Text("https://mcp.example.com/mcp") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = { Text(stringResource(R.string.settings_mcp_headers)) },
                        placeholder = { Text("Authorization: Bearer …") },
                        minLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            if (type == "http") R.string.settings_mcp_transport_http
                            else R.string.settings_mcp_transport_sse,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    fun kvLines(text: String, sep: Char) = text.lines().mapNotNull { line ->
                        val parts = line.split(sep, limit = 2)
                        if (parts.size == 2 && parts[0].trim().isNotEmpty()) {
                            parts[0].trim() to parts[1].trim()
                        } else null
                    }.toMap()
                    val config = McpServerConfig(
                        name = name.trim(),
                        type = type,
                        command = if (type == "stdio") command.trim() else "",
                        args = if (type == "stdio") argsText.lines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList(),
                        env = if (type == "stdio") kvLines(envText, '=') else emptyMap(),
                        url = if (type == "stdio") null else url.trim(),
                        headers = if (type == "stdio") emptyMap() else kvLines(headersText, ':'),
                        enabled = initial?.enabled ?: true,
                    )
                    scope.launch {
                        container.mcp.addServer(config)
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.settings_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) }
        },
    )
}

@Composable
private fun StorageAccessCard(allFiles: Boolean, onGrant: () -> Unit) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SdStorage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_env_storage_access), style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (allFiles) stringResource(R.string.settings_env_storage_granted)
                        else stringResource(R.string.settings_env_storage_optional),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!allFiles) {
                    Button(onClick = onGrant) { Text(stringResource(R.string.settings_action_grant)) }
                }
            }
        }
    }
}

@Composable
internal fun ShizukuCard(
    state: ShizukuState,
    serviceState: UserServiceState,
    failure: com.androidharness.app.data.env.ShizukuConnectionFailure?,
    connectionReport: () -> String,
    startupReport: suspend () -> com.androidharness.app.data.env.ShizukuStartupReport,
    onRefresh: () -> Unit,
    onGrant: () -> Unit,
) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_shizuku_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (state) {
                            ShizukuState.NOT_INSTALLED -> "Not installed: optional."
                            ShizukuState.NOT_RUNNING -> "Installed, not running."
                            ShizukuState.RUNNING_NO_PERMISSION -> "Running: grant AndroidHarness access."
                            ShizukuState.GRANTED -> when (serviceState) {
                                UserServiceState.BOUND_READY -> "Connected: shell privileges ready."
                                UserServiceState.COMPAT_READY -> "Connected in compatibility mode: shell privileges ready."
                                UserServiceState.BINDING -> "Access granted. Connecting to the shell runner…"
                                UserServiceState.BIND_FAILED -> "Access granted, but the shell runner could not connect. Retry the connection."
                                UserServiceState.NOT_BOUND -> "Access granted. Shell runner not connected."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ShizukuFailureDetails(failure, connectionReport, startupReport,
                recovered = serviceState == UserServiceState.COMPAT_READY)
            when (state) {
                ShizukuState.GRANTED -> {
                    if (serviceState.isReady) {
                        Text(
                            if (serviceState == UserServiceState.COMPAT_READY) "Compatibility runner ready ✓" else "Privileged runner ready ✓",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = onRefresh, enabled = serviceState != UserServiceState.BINDING) {
                        Text(if (serviceState.isReady) "Re-check status" else "Retry connection")
                    }
                }
                ShizukuState.RUNNING_NO_PERMISSION -> Button(onClick = onGrant) { Text(stringResource(R.string.settings_env_grant_shizuku)) }
                ShizukuState.NOT_RUNNING -> OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.settings_env_refresh_status)) }
                ShizukuState.NOT_INSTALLED -> OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.settings_env_check_again)) }
            }
        }
    }
}

@Composable
private fun LinuxEnvironmentCard(
    container: AppContainer,
    envState: com.androidharness.app.data.env.EnvState,
) {
    val scope = rememberCoroutineScope()
    var checkResult by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var showPackagesSheet by remember { mutableStateOf(false) }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_linux_title), style = MaterialTheme.typography.titleSmall)
                    Text("bash, git, gh, python, pip, node, npm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusText(
                    when (envState) {
                        is com.androidharness.app.data.env.EnvState.Ready -> "Installed"
                        is com.androidharness.app.data.env.EnvState.Downloading -> "Downloading…"
                        is com.androidharness.app.data.env.EnvState.Installing -> "Installing…"
                        is com.androidharness.app.data.env.EnvState.Preparing -> "Resolving…"
                        is com.androidharness.app.data.env.EnvState.Failed -> "Failed"
                        else -> "Not installed"
                    },
                    ok = envState is com.androidharness.app.data.env.EnvState.Ready,
                )
            }

            when (envState) {
                is com.androidharness.app.data.env.EnvState.Ready -> {
                    val installed = container.linuxEnv.installedPackages()
                    if (installed.isNotEmpty()) {
                        Text(
                            "Packages (${installed.size}): ${installed.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    checkResult?.let { report ->
                        Text(
                            report,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (report.startsWith("All present")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
                    // Step 1: check. Step 2 (only when something is actually
                    // missing/broken): confirm and update. Nothing to do → no
                    // dead button.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilledTonalButton(onClick = { showPackagesSheet = true }) {
                            Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_env_packages))
                        }
                        OutlinedButton(
                            enabled = !checking,
                            onClick = {
                                checking = true
                                scope.launch {
                                    checkResult = container.linuxEnv.checkMissing()
                                    checking = false
                                }
                            },
                        ) {
                            Text(if (checking) "Checking…" else "Check missing")
                        }
                        if (checkResult != null && !checkResult!!.startsWith("All present")) {
                            Button(onClick = {
                                checkResult = null
                                scope.launch { container.linuxEnv.updateEnvironment() }
                            }) {
                                Text(stringResource(R.string.settings_action_update))
                            }
                        }
                        OutlinedButton(onClick = { confirmUninstall = true }) {
                            Text(stringResource(R.string.settings_action_uninstall))
                        }
                    }
                }
                is com.androidharness.app.data.env.EnvState.NotInstalled,
                is com.androidharness.app.data.env.EnvState.Failed -> {
                    if (envState is com.androidharness.app.data.env.EnvState.Failed) {
                        Text(
                            "Install failed: ${envState.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            "Installs bash, git, gh, python 3, pip, node.js and npm into private storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { scope.launch { container.linuxEnv.install(container.linuxEnv.fullPackages) } }) {
                        Text(if (envState is com.androidharness.app.data.env.EnvState.Failed) "Retry install" else "Install full environment")
                    }
                }
                is com.androidharness.app.data.env.EnvState.Downloading,
                is com.androidharness.app.data.env.EnvState.Installing,
                is com.androidharness.app.data.env.EnvState.Preparing -> {
                    com.androidharness.app.ui.common.PackageInstallProgress(envState)
                }
            }
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(stringResource(R.string.settings_env_uninstall_title)) },
            text = { Text(stringResource(R.string.settings_env_uninstall_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    checkResult = null
                    scope.launch {
                        try { container.storage.uninstallToolchain() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { checkResult = e.message }
                    }
                }) { Text(stringResource(R.string.settings_action_uninstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }

    if (showPackagesSheet) {
        PackageManagerSheet(
            container = container,
            onDismiss = { showPackagesSheet = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Agent / Appearance / slash commands
// ---------------------------------------------------------------------------

/**
 * Read-only snapshot of what's actually driving requests right now. Model and
 * thinking changed from the chat header (picker + overflow), so they show
 * here as status, not settings, keeps this screen single-purpose.
 */
@Composable
private fun CurrentSetupCard(
    settings: AppSettings,
    providers: List<com.androidharness.app.llm.ProviderConfig>,
) {
    val active = providers.firstOrNull { it.id == settings.activeProviderId }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.settings_models_current_config), style = MaterialTheme.typography.titleSmallEmphasized)
            SetupLine(
                "Model",
                if (active == null) "No provider connected"
                else "${active.name} · ${settings.activeModel?.takeIf { it.isNotBlank() } ?: active.model}",
            )
            SetupLine("Thinking", settings.thinkingLevel.label)
            SetupLine("Context window", formatTokenCount(settings.maxContextTokens.toLong()))
            SetupLine("Default permission", settings.permissionMode.label)
            Text(
                "Model and thinking change from the chat header.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SetupLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f).padding(end = 12.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.62f),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}


@Composable
private fun AgentSection(
    container: AppContainer,
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var showAgentsDialog by remember { mutableStateOf(false) }
    AgentBehaviorSection(
        settings = settings,
        onPermissionMode = { scope.launch { container.settings.setPermissionMode(it) } },
        onContextLimit = { scope.launch { container.settings.setMaxContextTokens(it) } },
        onIterationLimit = { scope.launch { container.settings.setMaxIterations(it) } },
        onProjectInstructions = { showAgentsDialog = true },
    )
    if (showAgentsDialog) {
        AgentsInstructionsDialog(container, onDismiss = { showAgentsDialog = false })
    }
}

/** Maximum size injected into the system prompt (AgentEngine.readWorkspaceDoc). */
private const val AGENTS_MD_CAP = 16_000

private val AGENTS_MD_TEMPLATE = """# AGENTS.md

## Project
(What this project is, in one or two sentences.)

## Build & run
(Commands to build, run, and test; plain names, e.g. `gradlew assembleDebug`.)

## Conventions
(Code style, naming, file organization the agent should follow.)

## Constraints
(Things the agent must never do in this workspace.)
"""

@Composable
private fun AgentsInstructionsDialog(container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) } // null = loading
    var existed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load(): Pair<Boolean, String> {
        val fs = container.workspace.currentOnce()
        val node = runCatching { fs.resolve("AGENTS.md") }.getOrNull()
        return if (node != null && node.exists && node.isFile) true to node.readText() else false to ""
    }

    LaunchedEffect(Unit) {
        val (found, content) = runCatching { load() }.getOrElse { false to "" }
        existed = found
        text = if (found) content else AGENTS_MD_TEMPLATE
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_anchor_project_instructions)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!existed) {
                    Text(
                        "No AGENTS.md found in this workspace. Start from the template below, " +
                            "or type /init in chat to have the agent analyze the workspace and write one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = text ?: "",
                    onValueChange = {
                        text = it
                        if (it.length > AGENTS_MD_CAP) {
                            error = "Only the first $AGENTS_MD_CAP characters are injected into the prompt."
                        } else if (error != null) {
                            error = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 420.dp),
                    label = { Text(stringResource(R.string.settings_agent_instructions_content)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = HarnessMono,
                    ),
                )
                if (error != null) {
                    Text(
                        error!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val content = text ?: return@TextButton
                    scope.launch {
                        runCatching {
                            val fs = container.workspace.currentOnce()
                            fs.resolve("AGENTS.md").writeText(content)
                        }.onSuccess {
                            existed = true
                            onDismiss()
                        }.onFailure { e -> error = "Could not save: ${e.message}" }
                    }
                },
                enabled = text != null,
            ) { Text(if (existed) "Save" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}

@Composable
private fun AppearanceSection(container: AppContainer, settings: AppSettings, scope: kotlinx.coroutines.CoroutineScope) {
    SettingsHeader("Appearance")
    WorkspaceLayoutSection(settings.workspaceLayout) { scope.launch { container.settings.setWorkspaceLayout(it) } }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsAnchor(R.string.settings_anchor_theme) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_anchor_theme), style = MaterialTheme.typography.titleSmall)
                ThemeMode.entries.chunked(2).forEach { modes ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        modes.forEach { mode ->
                            ThemeChoice(
                                mode = mode,
                                selected = settings.themeMode == mode,
                                modifier = Modifier.weight(1f),
                                onClick = { scope.launch { container.settings.setThemeMode(mode) } },
                            )
                        }
                    }
                }
            }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingsAnchor(R.string.settings_anchor_dynamic_color) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_anchor_dynamic_color), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Match your wallpaper (Android 12+)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.dynamicColor,
                        onCheckedChange = { scope.launch { container.settings.setDynamicColor(it) } },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingsAnchor(R.string.settings_anchor_progress_bar_style) {
                ProgressBarStyleSection(settings.progressBarStyle) {
                    scope.launch { container.settings.setProgressBarStyle(it) }
                }
            }
        }
    }
}

@Composable
private fun ChatBehaviorSection(container: AppContainer, settings: AppSettings, scope: kotlinx.coroutines.CoroutineScope) {
    SettingsHeader("Chat behavior")
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsAnchor(R.string.settings_anchor_show_live_code) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                    .clickable { scope.launch { container.settings.setShowLiveCode(!settings.showLiveCode) } }) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_anchor_show_live_code), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.settings_chat_show_live_code_subtitle),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.showLiveCode,
                        onCheckedChange = { scope.launch { container.settings.setShowLiveCode(it) } })
                }
            }
            HorizontalDivider()
            SettingsAnchor(R.string.settings_anchor_resume_last_chat) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_anchor_resume_last_chat), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (settings.resumeLastChat) {
                                "On: opens your most recent chat when launching the app."
                            } else {
                                "Off: starts a new empty chat each time the app opens."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.resumeLastChat,
                        onCheckedChange = { scope.launch { container.settings.setResumeLastChat(it) } },
                    )
                }
            }
            HorizontalDivider()
            SettingsAnchor(R.string.settings_anchor_repo_map) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_anchor_repo_map), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (settings.repoMapEnabled) {
                                "On: injects a compact project symbol map (classes, functions, types) into context so the agent understands codebase structure without extra searches."
                            } else {
                                "Off: agent explores files only using tool commands."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.repoMapEnabled,
                        onCheckedChange = { scope.launch { container.settings.setRepoMapEnabled(it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacySection(container: AppContainer, settings: AppSettings, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    SettingsHeader("Privacy")
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_app_lock_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.biometricLockEnabled) {
                            "On: requires fingerprint, face or device PIN to open the app."
                        } else {
                            "Off: the app opens immediately without authentication."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val biometricTitle = if (settings.biometricLockEnabled)
                    stringResource(R.string.settings_privacy_lock_disable_title)
                else stringResource(R.string.settings_privacy_lock_enable_title)
                val biometricPrompt = stringResource(R.string.settings_privacy_biometric_prompt)
                Switch(
                    checked = settings.biometricLockEnabled,
                    onCheckedChange = { targetState ->
                        val activity = context.findFragmentActivity()
                        if (activity != null) {
                            BiometricAuth.prompt(
                                activity = activity,
                                title = biometricTitle,
                                subtitle = biometricPrompt,
                                onSuccess = {
                                    scope.launch { container.settings.setBiometricLockEnabled(targetState) }
                                },
                            )
                        } else {
                            scope.launch { container.settings.setBiometricLockEnabled(targetState) }
                        }
                    },
                )
            }
            if (settings.biometricLockEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.settings_search_autolock_title), style = MaterialTheme.typography.labelLarge)
                    val timeouts = listOf(0 to "Instant", 1 to "1m", 5 to "5m", 15 to "15m")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        timeouts.forEachIndexed { index, (mins, label) ->
                            SegmentedButton(
                                selected = settings.biometricLockTimeoutMinutes == mins,
                                onClick = { scope.launch { container.settings.setBiometricLockTimeout(mins) } },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = timeouts.size),
                            ) {
                                Text(label, maxLines = 1)
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_screenshots_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.allowScreenshots) {
                            "On: screenshots work everywhere except while a key or token is on screen."
                        } else {
                            "Off: screenshots and the recents preview are blocked app wide. " +
                                "Key screens stay blocked even when this is on."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.allowScreenshots,
                    onCheckedChange = { scope.launch { container.settings.setAllowScreenshots(it) } },
                )
            }
        }
    }
}

@Composable
private fun PlanningModelSection(
    container: AppContainer,
    settings: AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenProviders: () -> Unit,
) {
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    var pickingRole by remember { mutableStateOf<String?>(null) }
    var managingRoleProvider by remember { mutableStateOf<String?>(null) }

    // The sheet opens on the role's provider when one is set, else the
    // active one, so the list is never empty for a first pick.
    val fallbackProviderId = settings.activeProviderId ?: providers.firstOrNull()?.id

    SettingsHeader("Dual planning models")
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_models_dual_subtitle), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Configure models for dual planning. When activated from chat, " +
                        "planning runs with the plan model and execution runs with the execute model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ModelRoleRow(
                label = stringResource(R.string.settings_search_plan_model_title),
                model = settings.planningModel
                    ?: providers.firstOrNull { it.id == (settings.planningProviderId ?: fallbackProviderId) }?.model,
            ) { pickingRole = "plan" }
            ModelRoleRow(
                label = stringResource(R.string.settings_search_execute_model_title),
                model = settings.executionModel
                    ?: providers.firstOrNull { it.id == (settings.executionProviderId ?: fallbackProviderId) }?.model,
            ) { pickingRole = "exec" }
        }
    }

    pickingRole?.let { role ->
        val isPlan = role == "plan"
        ModelPickerSheet(
            providers = providers,
            activeProviderId = (if (isPlan) settings.planningProviderId else settings.executionProviderId)
                ?: fallbackProviderId,
            activeModel = if (isPlan) settings.planningModel else settings.executionModel,
            catalogs = catalogs,
            onDismiss = { pickingRole = null },
            onSelect = { providerId, model ->
                scope.launch {
                    if (isPlan) container.settings.setPlanningModel(providerId, model)
                    else container.settings.setExecutionModel(providerId, model)
                }
                pickingRole = null
            },
            onRefreshCatalog = { providerId ->
                val provider = providers.firstOrNull { it.id == providerId }
                val key = container.providers.apiKey(providerId)
                when {
                    provider == null -> "Unknown provider"
                    else -> {
                        val usable = provider.effectiveApiKey(key)
                        if (usable.isBlank()) "No API key for this provider"
                        else when (val result = com.androidharness.app.llm.ModelCatalog.listModels(provider, usable)) {
                            is com.androidharness.app.llm.ModelCatalog.Result.Models -> {
                                container.providers.saveCatalog(providerId, result.models)
                                null
                            }
                            is com.androidharness.app.llm.ModelCatalog.Result.Failed -> result.message
                        }
                    }
                }
            },
            onAddCustomModel = { providerId, model, reasoning ->
                scope.launch { container.providers.addCustomModel(providerId, model, reasoning) }
            },
            onDeleteCustomModel = { providerId, model ->
                scope.launch { container.providers.removeCustomModel(providerId, model) }
            },
            onEnableProvider = { id -> scope.launch { container.providers.setEnabled(id, true) } },
            onManageProviders = {
                managingRoleProvider = role
                pickingRole = null
            },
        )
    }

    managingRoleProvider?.let { role ->
        val isPlan = role == "plan"
        ProviderManagerSheet(
            providers = providers,
            activeProviderId = (if (isPlan) settings.planningProviderId else settings.executionProviderId)
                ?: fallbackProviderId,
            apiKey = container.providers::apiKey,
            onDismiss = { managingRoleProvider = null },
            onSetActive = { providerId ->
                scope.launch {
                    if (isPlan) container.settings.setPlanningModel(providerId, null)
                    else container.settings.setExecutionModel(providerId, null)
                }
                managingRoleProvider = null
            },
            catalogs = catalogs, onRefreshCatalog = container.providers::refreshCatalog,
            onDelete = { providerId ->
                container.providers.delete(providerId)
            },
            onEnabledChange = { id, enabled -> container.providers.setEnabled(id, enabled) },
            onMove = { id, direction, visible -> scope.launch { container.providers.move(id, direction, visible) } },
            onSave = { existing, name, type, baseUrl, model, apiKey, headers ->
                scope.launch {
                    val created = if (existing == null) {
                        container.providers.add(name, type, baseUrl, model, apiKey, headers)
                    } else {
                        val updated = existing.copy(name = name, type = type, baseUrl = baseUrl, model = model, headers = headers)
                        container.providers.update(updated, apiKey)
                        updated
                    }
                    if (isPlan) container.settings.setPlanningModel(created.id, null)
                    else container.settings.setExecutionModel(created.id, null)
                    managingRoleProvider = null
                }
            },
        )
    }
}

@Composable
private fun ModelRoleRow(label: String, model: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                model ?: "Provider default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.settings_models_choose_model, label),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SlashCommandsSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    SettingsAnchor(R.string.settings_anchor_slash_commands) {
        SettingsHeader("Slash commands")
    }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var snippetName by remember { mutableStateOf("") }
            var snippetBody by remember { mutableStateOf("") }
            var showAdd by remember { mutableStateOf(false) }
            TextButton(onClick = { showAdd = !showAdd }) {
                Text(if (showAdd) "Cancel" else "Add custom command")
            }
            AnimatedVisibility(showAdd) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = snippetName, onValueChange = { snippetName = it },
                        label = { Text(stringResource(R.string.settings_mcp_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = snippetBody, onValueChange = { snippetBody = it },
                        label = { Text(stringResource(R.string.settings_slash_prompt)) }, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        val n = snippetName.trim(); val b = snippetBody.trim()
                        if (n.isNotBlank() && b.isNotBlank()) {
                            scope.launch { container.snippets.add(n, b) }
                            snippetName = ""; snippetBody = ""; showAdd = false
                        }
                    }) { Text(stringResource(R.string.settings_action_save)) }
                }
            }
            val snippets by container.snippets.snippets.collectAsStateWithLifecycle(initialValue = emptyList())
            snippets.forEach { snippet ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "/${snippet.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = HarnessMono,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { scope.launch { container.snippets.delete(snippet) } }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.settings_action_delete), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryCard(container: AppContainer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_env_keep_alive), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Hold the CPU while a run or terminal is active so the terminal will not die when the app is minimized.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.keepAlive,
                    onCheckedChange = { scope.launch { container.settings.setKeepAlive(it) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_search_battery_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (exempt) "Excluded: Android will not throttle background runs."
                        else "On: Samsung and other OEMs may kill background work. Exclude the app for reliable long runs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!exempt) {
                    Button(onClick = {
                        requestBatteryExemption(context)
                        exempt = isIgnoringBatteryOptimizations(context)
                    }) { Text(stringResource(R.string.settings_action_fix)) }
                }
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    com.androidharness.app.ui.common.SystemGrants.isIgnoringBatteryOptimizations(context)

private fun requestBatteryExemption(context: android.content.Context) =
    com.androidharness.app.ui.common.SystemGrants.requestBatteryExemption(context)

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

@Composable
private fun SettingsHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    divider: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) = SettingsRow(
    leading = {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    },
    title = title,
    subtitle = subtitle,
    onClick = onClick,
    divider = divider,
    trailing = trailing,
)

/** Same row, for the places that need something other than a themed icon up front. */
@Composable
private fun SettingsRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    divider: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
    if (divider) {
        HorizontalDivider(
            Modifier.padding(start = 50.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun StatusText(text: String, ok: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (ok) LocalStatusColors.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DropdownSetting(
    label: String,
    current: String,
    entries: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    divider: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 12.dp),
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.settings_models_change_model, label),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Box {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                entries.forEach { (value, entryLabel) ->
                    val isFullAccess = value == PermissionMode.FULL_ACCESS.name
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entryLabel,
                                    Modifier.weight(1f),
                                    color = if (isFullAccess) FullAccessOrange else Color.Unspecified,
                                )
                                if (entryLabel == current) {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = if (isFullAccess) FullAccessOrange else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdatesCard(container: AppContainer) {
    val step by container.updates.step.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val version = remember(container) { appVersionLabel(container.appContext) }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.settings_page_updates_title), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Current version $version. Checks GitHub Releases for newer builds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { scope.launch { container.updates.check(manual = true) } },
                    enabled = step !is com.androidharness.app.data.update.UpdateManager.Step.Checking &&
                        step !is com.androidharness.app.data.update.UpdateManager.Step.Downloading,
                ) {
                    if (step is com.androidharness.app.data.update.UpdateManager.Step.Checking) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (step is com.androidharness.app.data.update.UpdateManager.Step.Checking) "Checking…" else "Check for updates")
                }
                when (val s = step) {
                    is com.androidharness.app.data.update.UpdateManager.Step.UpToDate -> {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_updates_uptodate), style = MaterialTheme.typography.labelMedium)
                    }
                    is com.androidharness.app.data.update.UpdateManager.Step.Available -> {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${s.release.tag} available, see the dialog",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------

private const val AUTHOR_URL = "https://github.com/Sanuu7"
private const val REPO_URL = "https://github.com/Sanuu7/AndroidHarness"
private const val LICENSE_URL = "https://github.com/Sanuu7/AndroidHarness/blob/main/LICENSE"

/** Installed version, shared by the About page and the update card. */
private fun appVersionLabel(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull().orEmpty().ifBlank { "?" }

/** A project AndroidHarness leans on, listed on the About page. */
private data class Credit(val name: String, val role: String, val url: String)

private val BUILT_WITH = listOf(
    Credit("Termux", "Linux packages and shell tooling", "https://github.com/termux"),
    Credit("Shizuku", "Elevated shell access without root", "https://github.com/RikkaApps/Shizuku"),
    Credit("CodeGraph", "Semantic code index behind the graph tools", "https://github.com/colbymchenry/codegraph"),
    Credit("Caveman", "Terse reply modes, adapted from Julius Brussee's skills", "https://github.com/JuliusBrussee/caveman"),
    Credit("sora-editor", "The in-app code editor", "https://github.com/Rosemoe/sora-editor"),
    Credit("Eruda", "Devtools inside the web preview", "https://github.com/liriliri/eruda"),
)

private val INSPIRED_BY = listOf(
    Credit("Hermes Agent", "Agent loop and scheduled runs", "https://github.com/NousResearch/hermes-agent"),
    Credit("Aider", "Repo map and edit workflow", "https://github.com/Aider-AI/aider"),
    Credit("pi (ohmypi)", "Terminal agent design", "https://github.com/earendil-works/pi"),
    Credit("OpenCode", "Provider and relay handling", "https://github.com/anomalyco/opencode"),
    Credit("Claude Code", "Tool cards and permission model", "https://github.com/anthropics"),
    Credit("Roo Code", "Approval flow and modes", "https://github.com/RooCodeInc/Roo-Code"),
    Credit("Cline", "Approval flow and modes", "https://github.com/cline/cline"),
    Credit("browser-use", "Agent-driven browsing", "https://github.com/browser-use/browser-use"),
    Credit("llama.cpp", "Local server compatibility", "https://github.com/ggerganov/llama.cpp"),
)

@Composable
private fun AboutSection(container: AppContainer) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val version = remember(container) { appVersionLabel(container.appContext) }
    val open: (String) -> Unit = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HarnessMark(size = 58.dp)
            Text("AndroidHarness", style = MaterialTheme.typography.titleLarge)
            Text(
                "A coding agent that lives on your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Surface(color = scheme.surfaceContainerHigh, shape = CircleShape) {
                Text(
                    "Version $version",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
    }

    SettingsHeader("Made by")
    SettingsPanel(Modifier.fillMaxWidth()) {
        SettingsRow(
            leading = { GitHubMark() },
            title = "Sanuu",
            subtitle = "github.com/Sanuu7",
            onClick = { open(AUTHOR_URL) },
            divider = true,
            trailing = { ExternalLinkIcon() },
        )
        SettingsRow(
            leading = {
                Icon(Icons.Outlined.Code, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            },
            title = stringResource(R.string.settings_about_source),
            subtitle = "github.com/Sanuu7/AndroidHarness",
            onClick = { open(REPO_URL) },
            trailing = { ExternalLinkIcon() },
        )
    }

    SettingsHeader("License")
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.settings_about_license), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Surface(color = scheme.primaryContainer, shape = CircleShape) {
                    Text(
                        "Open source",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                "Use it, change it, ship it, even inside a commercial app. The one condition is that the " +
                    "copyright notice and the license text travel with the code. It comes with no warranty.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { open(LICENSE_URL) }.padding(vertical = 4.dp),
            ) {
                Text(stringResource(R.string.settings_about_read_license), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
            }
        }
    }

    SettingsHeader("Credits")
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "AndroidHarness is built on open source work and borrows ideas from the agents that came before it.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        CreditsGroup("Built with", BUILT_WITH, open)
        CreditsGroup("Inspired by", INSPIRED_BY, open)
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "Every project keeps its own license. AndroidHarness is not affiliated with them.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** One labelled block of the credits list, separated from the block above it. */
@Composable
private fun CreditsGroup(label: String, credits: List<Credit>, onOpen: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
    Text(
        label,
        style = MaterialTheme.typography.labelMediumEmphasized,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
    )
    credits.forEachIndexed { index, credit ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(credit.url) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(credit.name, style = MaterialTheme.typography.bodyMedium)
                Text(credit.role, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        if (index != credits.lastIndex) {
            HorizontalDivider(
                Modifier.padding(start = 16.dp, end = 16.dp),
                color = scheme.outlineVariant.copy(alpha = 0.3f),
            )
        }
    }
}

/** GitHub's mark, tinted with the theme like every other icon in settings. */
@Composable
private fun GitHubMark() {
    Icon(
        painterResource(R.drawable.ic_github),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun ExternalLinkIcon() {
    Icon(
        Icons.AutoMirrored.Outlined.OpenInNew,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun ChatsBackupSection(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var restorePreview by remember { mutableStateOf<com.androidharness.app.data.ChatImportPreview?>(null) }

    val suggestedName = remember {
        "androidharness-chats-" +
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) +
            ".json"
    }

    fun report(text: String, error: Boolean = false) {
        statusText = text
        statusIsError = error
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            statusText = null
            try {
                val r = container.chatBackup.exportTo(uri)
                report("Exported ${r.sessions} chats (${r.messages} messages).")
            } catch (e: ChatBackupException) {
                report(e.message ?: "Export failed.", error = true)
            } catch (e: Exception) {
                report("Export failed: ${e.message ?: "unknown error"}", error = true)
            } finally {
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            statusText = null
            try {
                val preview = container.chatBackup.prepareImport(uri)
                if (preview.missingWorkspaces.isNotEmpty()) {
                    restorePreview = preview
                    return@launch
                }
                val r = container.chatBackup.importPrepared(preview, emptyMap())
                report(
                    "Imported ${r.sessions} chats (${r.messages} messages)" +
                        if (r.skipped > 0) ", ${r.skipped} already present." else ".",
                )
            } catch (e: ChatBackupException) {
                report(e.message ?: "Import failed.", error = true)
            } catch (e: Exception) {
                report("Import failed: ${e.message ?: "unknown error"}", error = true)
            } finally {
                busy = false
            }
        }
    }

    restorePreview?.let { preview -> ChatRestoreDialog(container, preview,
        onDismiss = { restorePreview = null }, onImported = { result ->
            restorePreview = null
            report("Imported ${result.sessions} chats (${result.messages} messages). ${result.skipped} already present.")
        }) }
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Archive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_backup_chat_backup), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Save all chats to a file, or restore them from one. " +
                            "Chats and messages only: no API keys, providers, or settings are in the file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch(suggestedName) }, enabled = !busy) {
                    Text(stringResource(R.string.settings_action_export))
                }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.settings_action_import))
                }
            }
            if (busy) ThinLinearProgress(Modifier.fillMaxWidth())
            statusText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
