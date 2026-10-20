package com.androidharness.app.data.env

import android.content.Context
import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class AccessRequest(
    val projectId: String?, val sessionId: String?, val requireRoot: Boolean = false,
    val forceDevice: Boolean = false, val manual: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AccessRequest>
    val rootLease = RootLease()
    val receipts = mutableListOf<ExecutionReceipt>()
}

internal class VerifiedDeviceAccess(val backend: DeviceBackend, val uid: Int?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<VerifiedDeviceAccess>
}

class DeviceAccessManager(
    private val context: Context,
    val shizuku: ShizukuManager,
    val linuxEnv: LinuxEnvironmentManager,
    val root: RootManager = RootManager(context),
    private val projectForSession: suspend (String) -> String? = { null },
) {
    val store = DeviceAccessStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val preferences = store.preferences.stateIn(scope, SharingStarted.Eagerly, AccessPreferences())
    private val stopped = MutableStateFlow(false)
    val paused = stopped.asStateFlow()
    private val allowance = MutableStateFlow("Root use is disabled")
    val allowanceLabel = allowance.asStateFlow()
    private val last = MutableStateFlow("No command has run yet.")
    val lastExecution = last.asStateFlow()
    private val szUid = MutableStateFlow<Int?>(null)
    val shizukuUid = szUid.asStateFlow()
    private val temporaryRoot = TemporaryRootAllowance()
    private val active = MutableStateFlow(0)
    val activeCommands = active.asStateFlow()

    init {
        scope.launch {
            combine(shizuku.state, shizuku.serviceState) { _, _ -> shizuku.isServiceReady() }
                .collect { if (!it) szUid.value = null }
        }
    }

    suspend fun grantRoot(projectId: String?, sessionId: String?): Boolean {
        val prefs = store.preferences.first()
        if (prefs.rootUse == RootUse.CHAT && sessionId == null) {
            allowance.value = "Open a chat before enabling root for the current chat."
            return false
        }
        if (!root.requestAccess() && probeShizuku() != 0) return false
        if (projectId != null) store.allowProject(projectId, true)
        enableTemporaryUse(prefs.rootUse, sessionId)
        return resume() == null
    }
    fun enableTemporaryUse(use: RootUse, sessionId: String?) {
        temporaryRoot.grant(use, sessionId)
        allowance.value = when (use) {
            RootUse.SESSION -> "Allowed for this app session"
            RootUse.CHAT -> "Allowed for the selected chat"
            RootUse.NEXT_ACTION -> "Allowed for the next action"
        }
    }
    fun disableRootUse() { temporaryRoot.revoke(); allowance.value = "Root use is disabled" }
    suspend fun resume(): String? = try {
        root.resume(); shizuku.resumeCommands(); stopped.value = false; null
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { "Could not resume privileged access: ${e.message}" }

    private fun rootAllowed(request: AccessRequest, prefs: AccessPreferences): Boolean =
        (request.manual || request.projectId in prefs.rootProjects) && temporaryRoot.allows(request.sessionId, request.rootLease)
    private suspend fun claimRoot(request: AccessRequest): Boolean {
        if (stopped.value || !rootAllowed(request, store.preferences.first())) return false
        val claimed = temporaryRoot.claim(request.sessionId, request.rootLease)
        if (claimed && preferences.value.rootUse == RootUse.NEXT_ACTION) allowance.value = "Next-action allowance used"
        return claimed
    }
    private suspend fun probeShizuku(): Int? {
        if (!shizuku.isServiceReady()) { szUid.value = null; return null }
        val probe = shizuku.runPrivileged(arrayOf("/system/bin/sh", "-c", "id -u"), null, null, 2_000, 1024)
        return probe?.takeIf { it.exitCode == 0 }?.output?.trim()?.toIntOrNull()?.takeIf { it == 0 || it == 2000 }.also { szUid.value = it }
    }

    internal suspend fun <T> withRequest(
        projectId: String?, sessionId: String?, requireRoot: Boolean = false, forceDevice: Boolean = false,
        manual: Boolean = false, block: suspend () -> T,
    ): T = withContext(AccessRequest(projectId ?: sessionId?.let { projectForSession(it) }, sessionId, requireRoot, forceDevice, manual)) { block() }

    suspend fun traceTool(projectId: String?, sessionId: String?, requireRoot: Boolean = false, block: suspend () -> ToolResult): ToolResult =
        withRequest(projectId, sessionId, requireRoot) {
            val request = currentCoroutineContext()[AccessRequest]!!
            val result = block()
            result.copy(execution = result.execution + request.receipts.distinct())
        }

    internal suspend fun select(appWorkspace: Boolean = false, excluded: Set<DeviceBackend> = emptySet()): AccessDecision {
        val request = currentCoroutineContext()[AccessRequest] ?: AccessRequest(null, null)
        val prefs = store.preferences.first()
        if (appWorkspace && !request.forceDevice && !request.requireRoot) return AccessDecision(DeviceBackend.NORMAL)
        val allowed = rootAllowed(request, prefs)
        val rootReady = prefs.mode in setOf(AccessMode.AUTO, AccessMode.ROOT) && allowed &&
            DeviceBackend.ROOT !in excluded && root.service != null && root.verify()
        val uid = if (prefs.mode == AccessMode.NORMAL || DeviceBackend.SHIZUKU in excluded) null else probeShizuku()
        return DeviceAccessPolicy.choose(prefs.mode, prefs.autoFallback, rootReady, uid,
            allowed, stopped.value, appWorkspace && !request.forceDevice, request.requireRoot)
    }

    /** A null callback result means dispatch never began. Exceptions/results are never replayed. */
    internal suspend fun execute(
        appWorkspace: Boolean = false,
        normal: suspend () -> ShellRunResult,
        shizukuRun: suspend () -> ShellRunResult?,
        rootRun: suspend () -> ShellRunResult?,
    ): ShellRunResult {
        val request = currentCoroutineContext()[AccessRequest] ?: AccessRequest(null, null)
        val excluded = mutableSetOf<DeviceBackend>()
        while (true) {
            val decision = select(appWorkspace, excluded)
            val backend = decision.backend ?: return ShellRunResult(-1, false, "", decision.reason ?: "Selected access is unavailable.", ExecutionTier.TOYBOX, null)
            if (decision.uid == 0 && !claimRoot(request)) {
                excluded += backend
                continue
            }
            val receipt = ExecutionReceipt(backend, decision.uid, decision.reason)
            active.update { it + 1 }
            val result = try {
                withContext(VerifiedDeviceAccess(backend, decision.uid)) {
                    when (backend) { DeviceBackend.NORMAL -> normal(); DeviceBackend.SHIZUKU -> shizukuRun(); DeviceBackend.ROOT -> rootRun() }
                }
            } finally { active.update { (it - 1).coerceAtLeast(0) } }
            if (result == null) {
                excluded += backend
                continue
            }
            synchronized(request.receipts) { request.receipts += receipt }
            last.value = receipt.label + (decision.reason?.let { " · $it" } ?: "")
            return result.copy(note = listOfNotNull(result.note, decision.reason?.let { "[Access fallback: $it; using ${receipt.label}]" }).joinToString("\n").ifBlank { null })
        }
    }

    internal fun isAppWorkspace(cwd: File): Boolean = PathClassifier.regionOf(cwd.path, linuxEnv.internalDataRoot.path, context.getExternalFilesDir(null)?.path) == PathClassifier.Region.APP_DATA

    internal suspend fun liveTier(cwd: File): ExecutionTier = when (select(isAppWorkspace(cwd)).backend) {
        DeviceBackend.ROOT -> ExecutionTier.ROOT
        DeviceBackend.SHIZUKU -> ExecutionTier.PRIVILEGED
        else -> if (linuxEnv.isReady) ExecutionTier.APP_LINUX else ExecutionTier.TOYBOX
    }

    internal suspend fun runRoot(command: String, cwd: File, timeoutMs: Int, maxOutput: Int): ShellRunResult? {
        val builder = linuxEnv.shellProcessBuilder(command)
        val rootEnv = linuxEnv.processEnv() + mapOf("HARNESS_TMP_LIB" to "${linuxEnv.prefix}/lib", "HARNESS_TMP_BIN" to "${linuxEnv.prefix}/bin")
        val result = root.run(builder.command().toTypedArray(), rootEnv.map { "${it.key}=${it.value}" }.toTypedArray(), cwd.path, timeoutMs, maxOutput) ?: return null
        return ShellRunResult(result.exitCode, result.timedOut, result.output, result.stderr, ExecutionTier.ROOT, null)
    }

    suspend fun statusForAgent(workspace: com.androidharness.app.workspace.WorkspaceFs, sessionId: String?): String {
        val prefs = store.preferences.first()
        if (workspace is com.androidharness.app.workspace.SshFs) return "SSH commands run on the remote host. Local Android root/Shizuku grants do not grant root on that host."
        val project = (workspace as? com.androidharness.app.workspace.ChatWorkspaceFs)?.primaryId ?: sessionId?.let { projectForSession(it) }
        val allowed = rootAllowed(AccessRequest(project, sessionId), prefs)
        return (workspace as? com.androidharness.app.workspace.ChatWorkspaceFs)?.bindings?.joinToString("; ", postfix = ". ") {
            "${it.name}: root requests ${if (rootAllowed(AccessRequest(it.id, sessionId), prefs)) "allowed" else "not allowed"}"
        }.orEmpty() + "Device access: ${prefs.mode.label}; auto fallback ${if (prefs.mode == AccessMode.AUTO || prefs.autoFallback) "on" else "off"}; " +
            "direct root ${root.state.value.status.label}; Shizuku ${if (shizuku.isServiceReady()) "ready (${szUid.value?.let { "UID $it" } ?: "identity checked before execution"})" else "unavailable"}; " +
            "root ${if (allowed) "allowed" else "not allowed"} for this project/chat; ${allowance.value}; privileged access ${if (stopped.value) "paused" else "enabled"}. " +
            "App workspaces use the app user by default. Use shell access=\"root\" only when root is required; project restrictions still apply. " +
            "Do not invoke su or sudo directly; request root through the access parameter. " +
            "A dispatched command is never automatically replayed after a connection failure."
    }

    internal suspend fun <T> rootFiles(projectId: String?, sessionId: String?, action: suspend (RootFileClient) -> T): T =
        withRequest(projectId, sessionId, requireRoot = true, forceDevice = true, manual = true) {
            withContext(Dispatchers.IO) {
                val request = currentCoroutineContext()[AccessRequest]!!
                val decision = select()
                check(decision.uid == 0 && claimRoot(request)) { decision.reason ?: "Enable root use and select a root-capable access mode first." }
                val svc = if (decision.backend == DeviceBackend.ROOT) requireNotNull(root.service) { "Reconnect the root service first." } else null
                action(object : RootFileClient {
                    override suspend fun rootList(path: String, offset: Int): String = svc?.rootList(path, offset) ?: shizuku.rootFileOperation("rootList", listOf(path, offset.toString()))
                    override suspend fun rootRead(path: String): String = svc?.rootRead(path) ?: shizuku.rootFileOperation("rootRead", listOf(path))
                    override suspend fun rootWrite(path: String, expectedHash: String, text: String): String = svc?.rootWrite(path, expectedHash, text) ?: shizuku.rootFileOperation("rootWrite", listOf(path, expectedHash, text))
                })
            }
        }

    suspend fun checkConnections(): String {
        root.verify()
        probeShizuku()
        return report()
    }
    fun report(): String = buildString {
        appendLine("AndroidHarness device access")
        appendLine("Mode: ${preferences.value.mode.label}; auto fallback: ${preferences.value.mode == AccessMode.AUTO || preferences.value.autoFallback}")
        appendLine("Direct root: ${root.state.value.status.label}")
        appendLine(root.state.value.detail)
        appendLine("Root checked at: ${root.state.value.checkedAt.takeIf { it > 0 }?.let(java.time.Instant::ofEpochMilli) ?: "Not checked"}")
        appendLine("Shizuku ready: ${shizuku.isServiceReady()}; UID: ${szUid.value ?: "not verified"}")
        appendLine("Temporary use: ${allowance.value}; paused: ${stopped.value}")
        appendLine("Last execution: ${last.value}")
        append("Active tracked calls: ${active.value}")
    }
    var stopBackgroundTasks: suspend () -> String = { "No detached task registry connected." }

    suspend fun stopPrivileged(): String {
        stopped.value = true
        disableRootUse()
        return coroutineScope {
            val backgroundStop = async { stopBackgroundTasks() }
            val rootStop = async { root.stop() }
            val shizukuStop = async { shizuku.stopActiveCommands() }
            rootStop.await() + "\n" + shizukuStop.await() + "\n" + backgroundStop.await()
        }
    }
}
