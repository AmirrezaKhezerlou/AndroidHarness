package com.androidharness.app.data.env

import android.content.*
import android.os.IBinder
import com.androidharness.app.IHarnessService
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class RootStatus(val label: String) {
    NOT_CHECKED("Not checked"), REQUESTING("Requesting access…"), READY("Ready · UID 0"), DENIED("Access denied"), UNAVAILABLE("Unavailable")
}
internal data class RootState(
    val status: RootStatus = RootStatus.NOT_CHECKED,
    val detail: String = "Enable root to request access.",
    val checkedAt: Long = 0,
    val provider: RootProvider? = null,
    val providerEvidence: String? = null,
)

class RootManager(private val context: Context) {
    private val installedPackages: Set<String> by lazy {
        runCatching {
            context.packageManager.getInstalledPackages(0).mapTo(mutableSetOf()) { it.packageName }
        }.getOrDefault(emptySet())
    }
    private val kernelVersion: String by lazy {
        runCatching { java.io.File("/proc/version").readText() }.getOrDefault("")
    }
    private val mutableState = MutableStateFlow(RootState())
    internal val state = mutableState.asStateFlow()
    @Volatile internal var service: IHarnessService? = null
        private set

    /** Package-only detection, so the settings card can name the manager before any prompt. */
    internal suspend fun refreshManagerDetection() = withContext(Dispatchers.IO) {
        if (mutableState.value.status == RootStatus.READY || mutableState.value.status == RootStatus.REQUESTING) return@withContext
        val detection = RootProviderDetector.classify(installedPackages, kernelVersion, emptySet())
        mutableState.value = mutableState.value.copy(
            detail = detection.provider?.let { "${it.label} detected. Grant root access to verify." }
                ?: "Enable root to request access.",
            provider = detection.provider,
            providerEvidence = detection.evidence.takeIf { detection.provider != null },
        )
    }
    private var connection: ServiceConnection? = null
    private var waiting: CompletableDeferred<IHarnessService>? = null
    private val requestLock = kotlinx.coroutines.sync.Mutex()

    internal suspend fun requestAccess(): Boolean {
        requestLock.lock()
        try {
            mutableState.value = mutableState.value.copy(
                status = RootStatus.REQUESTING, detail = "Waiting for your root manager.", checkedAt = 0,
            )
            return try {
                val existing = service
                if (existing != null && verify()) return true
                disconnect()
                withContext(Dispatchers.IO) {
                    Shell.getCachedShell()?.takeIf { !it.isRoot }?.close()
                    Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(15))
                    check(Shell.getShell().isRoot) { "Root was denied or no working su provider is available." }
                }
                val deferred = CompletableDeferred<IHarnessService>()
                waiting = deferred
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) { deferred.complete(IHarnessService.Stub.asInterface(binder)) }
                    override fun onServiceDisconnected(name: ComponentName) { unavailable("The root service disconnected.") }
                    override fun onBindingDied(name: ComponentName) { unavailable("The root service stopped.") }
                    override fun onNullBinding(name: ComponentName) { deferred.completeExceptionally(IllegalStateException("Root service could not start.")) }
                }
                connection = conn
                withContext(Dispatchers.Main) { RootService.bind(Intent(context, RootHarnessService::class.java), conn) }
                service = withTimeout(20_000) { deferred.await() }
                check(verify()) { "The connected runner did not verify root access." }
                true
            } catch (e: CancellationException) {
                withContext(NonCancellable) { disconnect() }
                mutableState.value = mutableState.value.copy(
                    status = RootStatus.UNAVAILABLE,
                    detail = "Root request ended before verification.",
                    checkedAt = System.currentTimeMillis(),
                )
                throw e
            }
            catch (e: Exception) {
                disconnect()
                mutableState.value = mutableState.value.copy(
                    status = RootStatus.DENIED,
                    detail = e.message ?: "Root access is unavailable.",
                    checkedAt = System.currentTimeMillis(),
                )
                false
            } finally { waiting = null }
        } finally { requestLock.unlock() }
    }

    /** Never invokes su or prompts: verify only the already connected service. */
    internal suspend fun verify(): Boolean = withContext(Dispatchers.IO) {
        val svc = service
        if (svc == null) {
            unavailable("No active root connection. Tap Grant root access to connect.")
            return@withContext false
        }
        val probe = runCatching {
            // Identity and manager markers share one root call, so detection
            // never adds a second prompt or a visible shell invocation.
            val script = "id -u\n" + RootProviderDetector.markerScript
            parsePrivilegedResult(svc.exec(arrayOf("/system/bin/sh", "-c", script), null, null, 4096, 5_000))
        }.getOrNull()
        val lines = probe?.output?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()
        val ready = probe?.exitCode == 0 && lines.firstOrNull() == "0"
        val detection = RootProviderDetector.classify(installedPackages, kernelVersion, lines.drop(1).map { it.uppercase() }.toSet())
        if (ready && svc.asBinder().pingBinder() && svc.uid == 0) {
            mutableState.value = RootState(
                RootStatus.READY,
                "${detection.provider?.label ?: "Root"} · identity verified by the running command service.",
                System.currentTimeMillis(),
                detection.provider,
                detection.evidence,
            )
        } else unavailable("Root access was lost or could not be verified.")
        ready
    }

    internal suspend fun run(cmd: Array<String>, env: Array<String>?, dir: String?, timeoutMs: Int, maxBytes: Int): PrivilegedResult? = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext null
        // Null means dispatch never happened. A binder failure after dispatch is an uncertain result.
        try { parsePrivilegedResult(svc.exec(cmd, env, dir, maxBytes, timeoutMs)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { unavailable("Root connection lost during execution."); PrivilegedResult(-1, false, "", "Root connection lost after dispatch. The command may have run; it was not retried.") }
    }

    internal suspend fun stop(): String = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext "Root: no connected runner; termination cannot be verified."
        runCatching {
            val count = svc.cancelActive()
            repeat(30) { if (svc.activeCommandCount() == 0) return@withContext "Root: stopped $count active commands."; delay(100) }
            "Root: stop requested for $count commands; termination is not confirmed."
        }.getOrElse { "Root: termination could not be verified (${it.message})." }
    }
    internal suspend fun resume() = withContext(Dispatchers.IO) { service?.resumeCommands(); Unit }
    internal suspend fun disconnect() {
        service = null
        val conn = connection; connection = null
        if (conn != null) withContext(Dispatchers.Main) { runCatching { RootService.unbind(conn) } }
    }
    private fun unavailable(detail: String) {
        service = null
        mutableState.value = mutableState.value.copy(
            status = RootStatus.UNAVAILABLE, detail = detail, checkedAt = System.currentTimeMillis(),
        )
    }
}

internal fun parsePrivilegedResult(raw: String): PrivilegedResult {
    val parts = raw.split('\n', limit = 3)
    val body = parts.getOrElse(2) { "" }
    val split = body.split(HarnessUserService.STDERR_SEPARATOR, limit = 2)
    return PrivilegedResult(parts[0].removePrefix("exit=").toIntOrNull() ?: -1,
        parts.getOrNull(1)?.removePrefix("timeout=") == "1", split[0].trimEnd('\n'), split.getOrElse(1) { "" }.trimStart('\n'))
}
