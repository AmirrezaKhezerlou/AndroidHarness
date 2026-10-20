package com.androidharness.app.ui

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.layout.statusBarsPadding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.androidharness.app.ui.common.formatTokens
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.content.Context
import com.androidharness.app.AppContainer
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.MessageHit
import com.androidharness.app.data.db.ChatSearch
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.projects.ProjectsScreen
import com.androidharness.app.ui.projects.ProjectScreen
import com.androidharness.app.ui.projects.ProjectMemoryScreen
import com.androidharness.app.ui.buildtest.BuildTestScreen
import com.androidharness.app.ui.common.formatRelativeTime
import com.androidharness.app.ui.files.ChangesScreen
import com.androidharness.app.ui.files.CodeEditorScreen
import com.androidharness.app.ui.files.FilesScreen
import com.androidharness.app.ui.settings.ProvidersScreen
import com.androidharness.app.ui.settings.SettingsScreen
import com.androidharness.app.ui.settings.SkillsScreen
import com.androidharness.app.ui.setup.SetupScreen
import com.androidharness.app.ui.terminal.TerminalScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppNav(container: AppContainer) {
    com.androidharness.app.ui.settings.LocalModelWarningDialog(container.localModels)
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    // Nullable gate: the synthetic AppSettings() default (onboardingDone=false)
    // used to pass for one frame as a real emission and flash the setup screen
    // for fully-onboarded users. Null = DataStore hasn't spoken yet.
    val settingsState by container.settings.settings
        .map { it as AppSettings? }
        .collectAsStateWithLifecycle(initialValue = null)
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val allWorkspaces by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val keyboard = LocalSoftwareKeyboardController.current

    // Setup fires exactly once, decided from DataStore's REAL first emission.
    val settings = settingsState
    if (settings == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    // Stay on setup until Skip or Start harness. Connecting a provider used
    // to flip this and remount NavHost onto chat mid-flow.
    val needsSetup = !settings.onboardingDone
    val lastSession = settings.lastActiveSessionId?.takeIf { settings.resumeLastChat }
    val startDestination = remember {
        when {
            needsSetup -> "setup"
            !lastSession.isNullOrBlank() -> "chat/$lastSession"
            else -> "chat"
        }
    }

    val desktopNavigation = com.androidharness.app.data.useWorkspacePanes(
        settings.workspaceLayout, androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat(),
        androidx.compose.ui.platform.LocalDensity.current.fontScale)
    var sidebarVisible by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    val desktopGuard = remember { com.androidharness.app.ui.files.EditorNavigationGuard() }
    val desktopFocus = remember { FocusRequester() }
    val desktopKeyboard = remember { DesktopKeyboardRouter() }
    androidx.compose.runtime.LaunchedEffect(desktopNavigation) {
        if (desktopNavigation) { drawerState.close(); androidx.compose.runtime.withFrameNanos { }; desktopFocus.requestFocus() }
    }

    var searchQuery by remember { mutableStateOf("") }
    var fuzzySearch by remember { mutableStateOf(false) }
    var messageHits by remember { mutableStateOf<List<MessageHit>>(emptyList()) }

    // Message hits lag the title filter by a short debounce so typing stays
    // fluid: word mode asks the FTS index, fuzzy mode falls back to LIKE.
    androidx.compose.runtime.LaunchedEffect(searchQuery, fuzzySearch) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            messageHits = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        messageHits = runCatching { container.sessions.searchMessages(q, fuzzySearch) }
            .getOrDefault(emptyList())
    }

    // End text entry as the drawer starts moving. Its initial focus belongs
    // to the sidebar container; later taps on search must keep their focus.
    androidx.compose.runtime.LaunchedEffect(drawerState.targetValue) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        if (drawerState.targetValue == DrawerValue.Closed) {
            // Drawer closed: collapse search so it starts fresh next open.
            searchQuery = ""
        }
    }

    // Which session the current back stack shows, for drawer highlighting.
    val currentEntry by nav.currentBackStackEntryFlow.collectAsStateWithLifecycle(initialValue = null)
    var draftSessionId by remember { mutableStateOf<String?>(null) }
    var workspaceChatViewModel by remember { mutableStateOf<ChatViewModel?>(null) }
    val currentSessionId = when (currentEntry?.destination?.route) {
        "chat/{sessionId}?messageId={messageId}" -> currentEntry?.arguments?.getString("sessionId")
        "chat" -> draftSessionId
        else -> null
    }
    val workspaceSessionId = currentSessionId
        ?: currentEntry?.arguments?.getString("sessionId")
        ?: currentEntry?.arguments?.getString("session")?.takeIf { it.isNotBlank() }
    val workspaceProjectId = currentEntry?.arguments?.getString("projectId")
        ?: currentEntry?.arguments?.getString("project")?.takeIf { it.isNotBlank() }
    val currentWorkspace by remember(workspaceSessionId, workspaceProjectId) { container.workspace.projectForTools(workspaceSessionId, workspaceProjectId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val chatWorkspaceSelection by remember(workspaceSessionId) { container.workspace.selectionForChat(workspaceSessionId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val chatWorkspaceContext = workspaceSessionId != null || workspaceChatViewModel != null
    val workspaceSummary = if (chatWorkspaceContext) {
        val count = chatWorkspaceSelection?.grants?.size ?: 1
        "$count workspace${if (count == 1) "" else "s"}" +
            currentWorkspace?.name?.let { " · Primary: $it" }.orEmpty()
    } else currentWorkspace?.name ?: "Choose workspace"
    val activeFs by remember(workspaceSessionId, workspaceProjectId) { container.workspace.forTools(workspaceSessionId, workspaceProjectId) }
        .collectAsStateWithLifecycle(initialValue = null)
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var creatingProjectChat by remember { mutableStateOf(false) }
    fun selectWorkspace(id: String) {
        val sid = workspaceSessionId
        scope.launch {
            try {
                if (sid == null && workspaceProjectId != null) nav.navigate("project/${encode(id)}")
                else if (sid == null) container.workspace.setActiveProject(id)
                else container.runManager.assignWorkspace(sid, id)
            } catch (e: Exception) { workspaceError = e.message }
        }
    }

    // Track active session in DataStore whenever navigating to a chat session
    androidx.compose.runtime.LaunchedEffect(currentSessionId) {
        if (!currentSessionId.isNullOrBlank()) {
            container.settings.setLastActiveSessionId(currentSessionId)
        }
    }
    // The code editor fights the drawer's edge-swipe for every gesture:
    // horizontal scrolling inside the editor opens the sidebar mid-edit.
    // The drawer keeps its menu button; only the swipe gesture is disabled
    // while an editor is on screen.
    val currentRoute = currentEntry?.destination?.route
    val drawerGesturesEnabled = currentRoute?.startsWith("viewer/") != true &&
        currentRoute?.endsWith("/memory") != true
    val runningSessionIds by container.runManager.runningSessionIds.collectAsStateWithLifecycle()

    var actionsSession by remember { mutableStateOf<SessionEntity?>(null) }
    var renamingSession by remember { mutableStateOf<SessionEntity?>(null) }
    var renamingProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var deletingProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var projectName by remember { mutableStateOf("") }
    var detailsSession by remember { mutableStateOf<SessionEntity?>(null) }
    var deletingSession by remember { mutableStateOf<SessionEntity?>(null) }
    val sidebarSnackbar = remember { androidx.compose.material3.SnackbarHostState() }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showAddWorkspace by remember { mutableStateOf(false) }

    // One-time notice for installs whose Linux environment predates a package
    // addition (gh): shown once, then never again, whichever button closes it.
    val latePackages = remember { container.linuxEnv.latePackagesPending() }
    var showLatePackagesNotice by remember {
        val prefs = container.appContext.getSharedPreferences("notices", Context.MODE_PRIVATE)
        mutableStateOf(latePackages.isNotEmpty() && !prefs.getBoolean("late_packages_v1", false))
    }
    fun dismissLatePackagesNotice() {
        container.appContext.getSharedPreferences("notices", Context.MODE_PRIVATE)
            .edit().putBoolean("late_packages_v1", true).apply()
        showLatePackagesNotice = false
    }

    // System folder picker for adding a SAF workspace from the drawer.
    val safWorkspacePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { scope.launch {
        try { selectWorkspace(container.workspace.addPickedFolder(it).id) }
        catch (e: Exception) { workspaceError = e.message }
    } } }

    // Run-result notifications deep-link into the session's chat.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.pendingSessionId.collect { sid -> nav.navigate("chat/$sid") }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.pendingSettingsScroll.collect { target ->
            if (target != null && nav.currentDestination?.route != "settings") nav.navigate("settings") { launchSingleTop = true }
        }
    }

    fun openChat(sessionId: String?, messageId: String? = null) {
        scope.launch { drawerState.close() }
        if (sessionId == null) {
            draftSessionId = null
            scope.launch { container.settings.setLastActiveSessionId(null) }
            nav.navigate("chat") { popUpTo("chat") { inclusive = true } }
        } else {
            val target = messageId?.let { "?messageId=${encode(it)}" }.orEmpty()
            nav.navigate("chat/$sessionId$target")
        }
    }

    fun navigateFromSidebar(action: () -> Unit) {
        val navigate = { scope.launch { drawerState.close() }; action() }
        if (desktopNavigation) desktopGuard.navigate(navigate) else navigate()
    }
    fun sidebarAction(session: SessionEntity, action: SidebarChatAction) {
        when (action) {
            SidebarChatAction.RENAME -> renamingSession = session
            SidebarChatAction.DETAILS -> detailsSession = session
            SidebarChatAction.DELETE -> deletingSession = session
            SidebarChatAction.PIN -> scope.launch { container.settings.setPinned(session.id, session.id !in settings.pinnedSessions) }
            SidebarChatAction.ARCHIVE -> scope.launch {
                val wasArchived = session.id in settings.archivedSessions
                container.settings.setArchived(session.id, !wasArchived)
                if (sidebarSnackbar.showSnackbar(if (wasArchived) "Chat restored" else "Chat archived", actionLabel = "Undo") ==
                    androidx.compose.material3.SnackbarResult.ActionPerformed) container.settings.setArchived(session.id, wasArchived)
            }
        }
    }
    @Composable fun Sidebar(onCollapse: () -> Unit) {
        val changes by remember(currentSessionId) {
            currentSessionId?.let(container.sessions::fileChangesFor) ?: kotlinx.coroutines.flow.flowOf(emptyList())
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        ProjectNavigationSidebar(
            projects = allWorkspaces, sessions = sessions, selectedSession = currentSessionId, selectedProject = currentWorkspace?.id,
            pinned = settings.pinnedSessions.toSet(), archived = settings.archivedSessions.toSet(), query = searchQuery,
            onQueryChange = { searchQuery = it }, messageHits = messageHits, fuzzy = fuzzySearch, onFuzzyChange = { fuzzySearch = !fuzzySearch },
            activity = { session ->
                if (session.id in runningSessionIds) {
                    val live by remember(session.id) { container.runManager.live(session.id) }.collectAsStateWithLifecycle()
                    sidebarActivity(true, session.pendingPlan, live)
                } else sidebarActivity(false, session.pendingPlan)
            },
            onNewChat = { navigateFromSidebar {
                scope.launch { currentWorkspace?.id?.let { container.workspace.setActiveProject(it) }; openChat(null) }
            } }, onChat = { sid -> navigateFromSidebar { openChat(sid) } },
            onMessage = { hit -> navigateFromSidebar { openChat(hit.sessionId, hit.messageId) } },
            onProjectChat = { project -> if (!creatingProjectChat) {
                creatingProjectChat = true
                val create = { scope.launch {
                    try { openChat(container.sessions.createSession("New chat", project.id)) }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; workspaceError = e.message ?: "Could not create chat." }
                    finally { creatingProjectChat = false }
                }; Unit }
                if (desktopNavigation) {
                    // A cancelled editor guard must not leave every new-chat button disabled.
                    creatingProjectChat = false
                    desktopGuard.navigate { creatingProjectChat = true; create() }
                } else create()
            } },
            onProjects = { navigateFromSidebar { nav.navigate("projects") { launchSingleTop = true } } },
            onChatActions = { actionsSession = it }, onAction = ::sidebarAction,
            onBuild = { navigateFromSidebar { nav.navigate("build-test?session=${encode(workspaceSessionId.orEmpty())}&project=${encode(workspaceProjectId.orEmpty())}") } },
            onTerminal = { navigateFromSidebar { nav.navigate("terminal?session=${encode(workspaceSessionId.orEmpty())}&project=${encode(workspaceProjectId.orEmpty())}") } },
            onAutomation = { navigateFromSidebar { nav.navigate("automation") { launchSingleTop = true } } },
            onProviders = { navigateFromSidebar { nav.navigate("providers") { launchSingleTop = true } } },
            onSettings = { navigateFromSidebar { nav.navigate("settings") { launchSingleTop = true } } },
            workspaceLabel = if (chatWorkspaceContext) "Workspaces for this chat" else "Workspace", workspaceSummary = workspaceSummary,
            onWorkspaces = { scope.launch { drawerState.close() }; showWorkspaceSheet = true }, onCollapse = onCollapse,
            currentRoute = currentRoute, projectCreationEnabled = !creatingProjectChat,
            changesSummary = changes.takeIf { it.isNotEmpty() }?.let { "${it.size} files changed" },
            onChanges = { currentSessionId?.let { sid -> navigateFromSidebar { nav.navigate("changes/${encode(sid)}") } } },
            snackbar = sidebarSnackbar,
            projectsView = settings.sidebarProjects,
            onProjectsViewChange = { scope.launch { container.settings.setSidebarProjects(it) } },
            projectPins = settings.pinnedProjects, projectArchives = settings.archivedProjects,
            onProjectAction = { project, action -> when (action) {
                SidebarProjectAction.OPEN -> navigateFromSidebar { nav.navigate("project/${encode(project.id)}") { launchSingleTop = true } }
                SidebarProjectAction.RENAME -> { projectName = project.name; renamingProject = project }
                SidebarProjectAction.DELETE -> {
                    val show = { deletingProject = project; Unit }
                    if (desktopNavigation) desktopGuard.navigate(show) else show()
                }
                SidebarProjectAction.PIN -> scope.launch { container.settings.setProjectPinned(project.id, project.id !in settings.pinnedProjects) }
                SidebarProjectAction.ARCHIVE -> scope.launch {
                    val wasArchived = project.id in settings.archivedProjects
                    container.settings.setProjectArchived(project.id, !wasArchived)
                    if (sidebarSnackbar.showSnackbar(if (wasArchived) "Project restored" else "Project archived", actionLabel = "Undo") ==
                        androidx.compose.material3.SnackbarResult.ActionPerformed) container.settings.setProjectArchived(project.id, wasArchived)
                }
            } },
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalDesktopLayout provides desktopNavigation,
        LocalDesktopInput provides (settings.workspaceLayout == com.androidharness.app.data.WorkspaceLayoutMode.DESKTOP),
        LocalDesktopNavigationGuard provides desktopGuard,
        LocalDesktopKeyboardRouter provides if (desktopNavigation) desktopKeyboard else null,
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !desktopNavigation && drawerGesturesEnabled,
        drawerContent = {
            if (!desktopNavigation) ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
                Sidebar { scope.launch { drawerState.close() } }
            }
        },
    ) {
        Row(Modifier.fillMaxSize().focusRequester(desktopFocus).onPreviewKeyEvent { event ->
            if (desktopNavigation && desktopKeyboard.handler?.invoke(event) == true) true
            else if (!desktopNavigation || !event.isCtrlPressed) false
            else when (event.key) {
                Key.N -> { if (event.type == KeyEventType.KeyUp) desktopGuard.navigate { openChat(null) }; true }
                Key.Comma -> { if (event.type == KeyEventType.KeyUp) desktopGuard.navigate {
                    nav.navigate("settings") { launchSingleTop = true }
                }; true }
                Key.B -> if (event.isShiftPressed) { if (event.type == KeyEventType.KeyUp) sidebarVisible = !sidebarVisible; true } else false
                else -> false
            }
        }.then(if (desktopNavigation) Modifier.focusable() else Modifier).then(if (desktopNavigation) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)) {
            RetainedWorkspaceContent(desktopNavigation && sidebarVisible,
                Modifier.width(if (desktopNavigation && sidebarVisible) 232.dp else 0.dp)) {
                Sidebar { sidebarVisible = false }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
        if (currentRoute?.startsWith("project") != true) {
            com.androidharness.app.ui.common.SshWorkspaceBar(container, activeFs as? com.androidharness.app.workspace.SshFs)
        }
        NavHost(
            modifier = Modifier.weight(1f),
            navController = nav,
            startDestination = startDestination,
            // Quiet transitions: a short fade with a small rise. No shared-element
            // theatrics, screens should feel instant.
            enterTransition = {
                fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 28 }
            },
            exitTransition = { fadeOut(tween(180, easing = FastOutSlowInEasing)) },
            popEnterTransition = { fadeIn(tween(220, easing = FastOutSlowInEasing)) },
            popExitTransition = {
                fadeOut(tween(180, easing = FastOutSlowInEasing)) +
                    slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it / 28 }
            },
        ) {
            composable("chat") {
                val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(container, null))
                androidx.compose.runtime.DisposableEffect(vm) {
                    workspaceChatViewModel = vm
                    onDispose { if (workspaceChatViewModel === vm) workspaceChatViewModel = null }
                }
                val draftState by vm.state.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(draftState.sessionId) { draftSessionId = draftState.sessionId }
                ChatWorkspaceScreen(
                    viewModel = vm,
                    onOpenDrawer = {
                        focusManager.clearFocus(force = true)
                        if (desktopNavigation) sidebarVisible = !sidebarVisible else scope.launch { drawerState.open() }
                    },
                    onOpenFile = { path, line ->
                        nav.navigate("viewer/${encode(path)}?line=${line ?: 0}&session=${encode(vm.state.value.sessionId.orEmpty())}")
                    },
                    onNewChat = {
                        draftSessionId = null
                        scope.launch { container.settings.setLastActiveSessionId(null) }
                        nav.navigate("chat") { popUpTo("chat") { inclusive = true } }
                    },
                    onOpenTerminal = { nav.navigate("terminal?session=${encode(vm.state.value.sessionId.orEmpty())}") },
                    onOpenFiles = { nav.navigate("files?session=${encode(vm.state.value.sessionId.orEmpty())}") },
                    onOpenSubagent = { callId ->
                        vm.state.value.sessionId?.let { sid ->
                            nav.navigate("subagent/${encode(sid)}/${encode(callId)}")
                        }
                    },
                    onOpenSettings = { nav.navigate("settings") },
                    onNavigateToSession = { sid ->
                        nav.navigate("chat/$sid")
                    },
                )
            }
            composable(
                "chat/{sessionId}?messageId={messageId}",
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("messageId") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val sessionId = entry.arguments?.getString("sessionId")
                val searchMessageId = entry.arguments?.getString("messageId")
                val vm: ChatViewModel =
                    viewModel(factory = ChatViewModel.factory(container, sessionId))
                androidx.compose.runtime.DisposableEffect(vm) {
                    workspaceChatViewModel = vm
                    onDispose { if (workspaceChatViewModel === vm) workspaceChatViewModel = null }
                }
                ChatWorkspaceScreen(
                    viewModel = vm,
                    searchMessageId = searchMessageId,
                    onOpenDrawer = {
                        focusManager.clearFocus(force = true)
                        if (desktopNavigation) sidebarVisible = !sidebarVisible else scope.launch { drawerState.open() }
                    },
                    onOpenFile = { path, line ->
                        val sid = vm.state.value.sessionId ?: sessionId
                        nav.navigate("viewer/${encode(path)}?line=${line ?: 0}&session=${encode(sid.orEmpty())}")
                    },
                    onNewChat = {
                        draftSessionId = null
                        scope.launch { container.settings.setLastActiveSessionId(null) }
                        nav.navigate("chat") { popUpTo("chat") { inclusive = true } }
                    },
                    onOpenTerminal = { nav.navigate("terminal?session=${encode(vm.state.value.sessionId.orEmpty())}") },
                    onOpenFiles = { nav.navigate("files?session=${encode(vm.state.value.sessionId.orEmpty())}") },
                    onOpenSubagent = { callId ->
                        vm.state.value.sessionId?.let { sid ->
                            nav.navigate("subagent/${encode(sid)}/${encode(callId)}")
                        }
                    },
                    onOpenSettings = { nav.navigate("settings") },
                    onNavigateToSession = { sid ->
                        nav.navigate("chat/$sid")
                    },
                )
            }
            composable(
                "subagent/{sessionId}/{toolCallId}",
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("toolCallId") { type = NavType.StringType },
                ),
            ) { entry ->
                com.androidharness.app.ui.subagent.SubagentScreen(
                    container = container,
                    sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                    toolCallId = entry.arguments?.getString("toolCallId").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("terminal?session={session}&project={project}", arguments = listOf(
                navArgument("session") { defaultValue = "" }, navArgument("project") { defaultValue = "" })) { entry ->
                TerminalScreen(container = container, sessionId = entry.arguments?.getString("session")?.takeIf { it.isNotBlank() },
                    projectId = entry.arguments?.getString("project")?.takeIf { it.isNotBlank() }, onBack = { nav.popBackStack() })
            }
            composable("files?session={session}&project={project}", arguments = listOf(
                navArgument("session") { defaultValue = "" }, navArgument("project") { defaultValue = "" })) { entry ->
                val filesSessionId = entry.arguments?.getString("session")?.takeIf { it.isNotBlank() }
                val filesProjectId = entry.arguments?.getString("project")?.takeIf { it.isNotBlank() }
                FilesScreen(
                    container = container,
                    sessionId = filesSessionId,
                    projectId = filesProjectId,
                    onBack = { nav.popBackStack() },
                    onOpenFile = { path ->
                        nav.navigate("viewer/${encode(path)}?line=0&session=${encode(filesSessionId.orEmpty())}&project=${encode(filesProjectId.orEmpty())}")
                    },
                    onOpenChanges = filesSessionId?.let { sid ->
                        { nav.navigate("changes/${encode(sid)}") }
                    },
                )
            }
            composable("changes/{sessionId}") { entry ->
                val sid = entry.arguments?.getString("sessionId").orEmpty()
                ChangesScreen(
                    container = container,
                    sessionId = sid,
                    onBack = { nav.popBackStack() },
                    onAskAgent = { prompt ->
                        container.pendingAgentPrompt.value = prompt
                        nav.navigate("chat/${encode(sid)}") { popUpTo("changes/{sessionId}") { inclusive = true } }
                    },
                )
            }
            composable(
                "viewer/{path}?line={line}&session={session}&project={project}",
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("line") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("session") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("project") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val path = entry.arguments?.getString("path").orEmpty()
                val line = entry.arguments?.getInt("line")?.takeIf { it > 0 }
                val sessionArg = entry.arguments?.getString("session").orEmpty()
                CodeEditorScreen(
                    container = container,
                    path = path,
                    initialLine = line,
                    sessionId = sessionArg.takeIf { it.isNotBlank() },
                    projectId = entry.arguments?.getString("project")?.takeIf { it.isNotBlank() },
                    onBack = { nav.popBackStack() },
                    onAskAgent = { sourceId, prompt ->
                        scope.launch {
                            try {
                                val sid = sessionArg.takeIf { it.isNotBlank() }
                                    ?: sourceId?.let { container.sessions.createProjectSession(it) }
                                    ?: error("Choose a workspace before asking about code.")
                                container.pendingCodeDraft.value = com.androidharness.app.ui.files.CodeChatDraft(sid, prompt)
                                val route = if (draftSessionId == sid) "chat" else "chat/$sid"
                                if (!nav.popBackStack(route, inclusive = false)) openChat(sid)
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { workspaceError = e.message }
                        }
                    },
                )
            }
            composable("automation") {
                com.androidharness.app.ui.automation.AutomationScreen(container,
                    onBack = { nav.popBackStack() },
                    onOpenSession = { nav.navigate("chat/$it") })
            }
            composable("projects") {
                ProjectsScreen(container, onBack = { nav.popBackStack() },
                    onOpenProject = { nav.navigate("project/${encode(it)}") },
                    onProjectDeleted = {
                        draftSessionId = null
                        nav.navigate("projects") { popUpTo(nav.graph.id); launchSingleTop = true }
                    })
            }
            composable("project/{projectId}", arguments = listOf(navArgument("projectId") { type = NavType.StringType })) { entry ->
                val id = requireNotNull(entry.arguments?.getString("projectId"))
                fun openProjectChat(projectId: String, prompt: String? = null) {
                    if (creatingProjectChat) return
                    creatingProjectChat = true
                    scope.launch {
                        try {
                            val sid = container.sessions.createProjectSession(projectId)
                            if (prompt != null) container.pendingAgentPrompt.value = prompt
                            nav.navigate("chat/${encode(sid)}")
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { workspaceError = e.message }
                        finally { creatingProjectChat = false }
                    }
                }
                ProjectScreen(container, id, onBack = { nav.popBackStack() },
                    onOpenChat = { nav.navigate("chat/${encode(it)}") },
                    onNewChat = { openProjectChat(it) }, creatingChat = creatingProjectChat,
                    onMemory = { nav.navigate("project/${encode(id)}/memory") },
                    onAutomation = { taskId, create ->
                        nav.navigate("project/${encode(id)}/automation?taskId=${encode(taskId.orEmpty())}&create=$create")
                    }, onOpenSettings = { nav.navigate("settings") },
                    onOpenFiles = { nav.navigate("files?project=${encode(id)}") },
                    onOpenTerminal = { nav.navigate("terminal?project=${encode(id)}") },
                    onOpenBuildTest = { nav.navigate("build-test?project=${encode(id)}") },
                    onSendPrompt = { openProjectChat(id, it) },
                    onProjectDeleted = {
                        draftSessionId = null
                        nav.navigate("projects") { popUpTo(nav.graph.id); launchSingleTop = true }
                    })
            }
            composable("project/{projectId}/memory", arguments = listOf(navArgument("projectId") { type = NavType.StringType })) { entry ->
                ProjectMemoryScreen(container, requireNotNull(entry.arguments?.getString("projectId")), onBack = { nav.popBackStack() })
            }
            composable("project/{projectId}/automation?taskId={taskId}&create={create}", arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("taskId") { type = NavType.StringType; defaultValue = "" },
                navArgument("create") { type = NavType.BoolType; defaultValue = false },
            )) { entry ->
                com.androidharness.app.ui.automation.AutomationScreen(container,
                    onBack = { nav.popBackStack() }, onOpenSession = { nav.navigate("chat/${encode(it)}") },
                    projectId = entry.arguments?.getString("projectId"),
                    initialTaskId = entry.arguments?.getString("taskId")?.takeIf { it.isNotBlank() },
                    createOnOpen = entry.arguments?.getBoolean("create") == true)
            }
            composable("build-test?session={session}&project={project}", arguments = listOf(
                navArgument("session") { defaultValue = "" }, navArgument("project") { defaultValue = "" })) { entry ->
                val buildSessionId = entry.arguments?.getString("session")?.takeIf { it.isNotBlank() }
                val buildProjectId = entry.arguments?.getString("project")?.takeIf { it.isNotBlank() }
                BuildTestScreen(
                    container = container,
                    sessionId = buildSessionId,
                    projectId = buildProjectId,
                    onBack = { nav.popBackStack() },
                    onOpenFile = { path, line ->
                        nav.navigate("viewer/${encode(path)}?line=$line&session=${encode(buildSessionId.orEmpty())}&project=${encode(buildProjectId.orEmpty())}")
                    },
                    onOpenTerminal = { nav.navigate("terminal?session=${encode(buildSessionId.orEmpty())}&project=${encode(buildProjectId.orEmpty())}") },
                    onFixWithAgent = { prompt ->
                        if (!creatingProjectChat) scope.launch {
                            creatingProjectChat = true
                            try {
                                val sid = buildSessionId ?: buildProjectId?.let { container.sessions.createProjectSession(it) }
                                container.pendingAgentPrompt.value = prompt
                                if (sid != null) nav.navigate("chat/${encode(sid)}")
                                else { draftSessionId = null; nav.navigate("chat") }
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { workspaceError = e.message }
                            finally { creatingProjectChat = false }
                        }
                    },
                )
            }
            composable("settings") {
                SettingsScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                    onOpenStats = { nav.navigate("stats") },
                    onRunSetup = { nav.navigate("setup") },
                    onOpenSkills = { nav.navigate("skills") },
                    onOpenProviders = { nav.navigate("providers") },
                )
            }
            composable("skills") {
                SkillsScreen(container = container, onBack = { nav.popBackStack() })
            }
            composable("stats") {
                com.androidharness.app.ui.stats.StatsScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("providers") {
                ProvidersScreen(container = container, onBack = { nav.popBackStack() })
            }
            composable("setup") {
                com.androidharness.app.ui.setup.SetupScreen(
                    container = container,
                    onFinish = {
                        nav.navigate("chat") { popUpTo("setup") { inclusive = true } }
                    },
                )
            }
        }
    }

    }

    }
    }

    com.androidharness.app.ui.common.ShizukuDisabledWarning(container.shizuku.workspaceWarning)

    // The sidebar opens the same per-chat access editor, including unsent chats.
    if (showWorkspaceSheet) {
        com.androidharness.app.ui.common.ShizukuWorkspaceWarningEffect(container.shizuku)
        if (chatWorkspaceContext) com.androidharness.app.ui.chat.components.ChatWorkspaceManagerSheet(
            container, workspaceSessionId, onDismiss = { showWorkspaceSheet = false },
            onSave = workspaceChatViewModel?.let { vm -> vm::saveWorkspaces })
        else com.androidharness.app.ui.chat.components.WorkspaceSwitcherSheet(
            projects = allWorkspaces,
            currentProjectId = currentWorkspace?.id,
            chatWorkspace = workspaceSessionId != null,
            describe = { container.workspace.describe(it) },
            onSelect = ::selectWorkspace,
            onAdd = { showAddWorkspace = true },
            onDismiss = { showWorkspaceSheet = false },
            onDelete = { project ->
                scope.launch {
                    try { container.workspace.deleteProject(project) }
                    catch (e: Exception) { workspaceError = e.message }
                }
            },
        )
    }
    workspaceError?.let { message ->
        AlertDialog(onDismissRequest = { workspaceError = null }, title = { Text("Workspace") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { workspaceError = null }) { Text("OK") } })
    }
    if (showAddWorkspace) {
        com.androidharness.app.ui.common.AddWorkspaceDialog(
            container = container,
            onSelected = ::selectWorkspace,
            onDismiss = { showAddWorkspace = false },
            onPickSaf = {
                showAddWorkspace = false
                safWorkspacePicker.launch(null)
            },
        )
    }

    // One-time "the toolchain gained new packages" notice for installs that
    // predate the addition. "Fetch now" lands on Settings and starts the
    // update so the progress card is visible; every path marks it as seen.
    if (showLatePackagesNotice) {
        AlertDialog(
            onDismissRequest = { dismissLatePackagesNotice() },
            title = { Text("Terminal & environment updated") },
            text = {
                Text(
                    "The Linux toolchain gained new packages: ${latePackages.joinToString(", ")}. " +
                        "Fetch them to add the new tools to your installed environment; " +
                        "until then they show up as missing.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dismissLatePackagesNotice()
                    nav.navigate("settings")
                    scope.launch { container.linuxEnv.updateEnvironment() }
                }) { Text("Fetch now") }
            },
            dismissButton = {
                TextButton(onClick = { dismissLatePackagesNotice() }) { Text("Later") }
            },
        )
    }

    // Long-press session actions.
    actionsSession?.let { session ->
        val pinned = session.id in settings.pinnedSessions
        val archived = session.id in settings.archivedSessions
        AlertDialog(
            onDismissRequest = { actionsSession = null },
            title = { Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SessionAction("Rename") {
                        renamingSession = session
                        actionsSession = null
                    }
                    SessionAction(if (pinned) "Unpin" else "Pin") {
                        sidebarAction(session, SidebarChatAction.PIN)
                        actionsSession = null
                    }
                    SessionAction(if (archived) "Unarchive" else "Archive") {
                        sidebarAction(session, SidebarChatAction.ARCHIVE)
                        actionsSession = null
                    }
                    SessionAction("Chat details") { detailsSession = session; actionsSession = null }
                    SessionAction("Delete", destructive = true) {
                        deletingSession = session
                        actionsSession = null
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionsSession = null }) { Text("Close") }
            },
        )
    }

    deletingSession?.let { session ->
        AlertDialog(onDismissRequest = { deletingSession = null }, title = { Text("Delete chat?") },
            text = { Text("Delete \"${session.title}\" and its conversation history?") },
            confirmButton = { TextButton(onClick = {
                val remove = { scope.launch {
                    container.runManager.stopAndJoin(session.id)
                    container.sessions.deleteSession(session)
                    if (session.id == currentSessionId) openChat(null)
                    else if (session.id == settings.lastActiveSessionId) container.settings.setLastActiveSessionId(null)
                }; Unit }
                deletingSession = null
                if (desktopNavigation && session.id == currentSessionId) desktopGuard.navigate(remove) else remove()
            }) { Text("Delete chat", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deletingSession = null }) { Text("Keep chat") } })
    }
    detailsSession?.let { session ->
        val choice = if (settings.sameModelAcrossChats) com.androidharness.app.data.ChatModelSelection.from(settings, providers).active
            else runCatching { com.androidharness.app.data.ChatModelSelection.decode(session.modelSelectionJson)?.active
                ?: com.androidharness.app.data.ChatModelSelection.from(settings, providers).active }.getOrNull()
        AlertDialog(onDismissRequest = { detailsSession = null }, title = { Text("Chat details") },
            text = { Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(session.title, style = MaterialTheme.typography.titleSmall)
                Text("Workspace: ${allWorkspaces.firstOrNull { it.id == session.projectId }?.name ?: "Other chats"}")
                Text("Model: ${choice?.model ?: "Unavailable"}")
                Text("Tokens used: ${formatTokens(session.totalInputTokens + session.totalOutputTokens)}")
                Text("Updated: ${formatRelativeTime(session.updatedAt)}")
            } }, confirmButton = { TextButton(onClick = { detailsSession = null }) { Text("Close") } })
    }

    deletingProject?.let { project ->
        com.androidharness.app.ui.projects.ProjectDeletionDialog(container, project,
            onDismiss = { deletingProject = null }, onDeleted = { deletedChats ->
                deletingProject = null
                scope.launch { drawerState.close() }
                if (draftSessionId in deletedChats) draftSessionId = null
                nav.navigate("projects") { popUpTo(nav.graph.id); launchSingleTop = true }
            })
    }

    renamingProject?.let { project ->
        AlertDialog(onDismissRequest = { renamingProject = null }, title = { Text("Rename project") },
            text = { OutlinedTextField(projectName, { projectName = it }, singleLine = true, label = { Text("Project name") }) },
            confirmButton = { TextButton(enabled = projectName.isNotBlank(), onClick = { scope.launch {
                try { container.workspace.renameProject(project.id, projectName); renamingProject = null }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; workspaceError = e.message }
            } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renamingProject = null }) { Text("Cancel") } })
    }

    renamingSession?.let { session ->
        var title by remember(session) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renamingSession = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) {
                        scope.launch { container.sessions.renameSession(session.id, title.trim()) }
                    }
                    renamingSession = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamingSession = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
internal fun MessageHitRow(
    hit: MessageHit,
    query: String,
    fuzzy: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val annotated = run {
        val snippet = ChatSearch.snippet(hit.text, ChatSearch.highlightNeedles(query, fuzzy))
        buildAnnotatedString {
            append(snippet.text)
            for (r in snippet.ranges) {
                addStyle(
                    SpanStyle(color = scheme.primary, fontWeight = FontWeight.SemiBold),
                    r.first,
                    r.last + 1,
                )
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hit.sessionTitle.ifBlank { "Chat" },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatRelativeTime(hit.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Text(
            annotated,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun SessionAction(
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = if (destructive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
