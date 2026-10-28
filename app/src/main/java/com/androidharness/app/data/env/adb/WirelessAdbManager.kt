package com.androidharness.app.data.env.adb

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.androidharness.app.data.env.ShizukuCompatClient
import com.androidharness.app.data.env.ShizukuCompatProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Optional built-in replacement for the Shizuku app. It pairs with Android's
 * own adbd over wireless debugging, starts the same compatibility runner the
 * Shizuku path uses, and keeps the resulting shell client available.
 */
@RequiresApi(Build.VERSION_CODES.R)
class WirelessAdbManager(private val context: Context) {

    sealed interface State {
        data object Unpaired : State
        data object Paired : State
        data object Connecting : State
        data class Connected(val uid: Int, val port: Int) : State
        data class Failed(val message: String) : State
    }

    private val preferences = context.getSharedPreferences("wireless_adb", Context.MODE_PRIVATE)
    private val keyStore = PreferenceAdbKeyStore(preferences)
    @Volatile private var keyCache: AdbKey? = null
    private val connectLock = Mutex()

    /** Created on demand so a forgotten pairing really starts with a new key. */
    @Synchronized
    private fun identity(): AdbKey = keyCache ?: AdbKey(keyStore, "harness@android").also { keyCache = it }

    private val _state = MutableStateFlow<State>(
        if (preferences.contains(KEY_STORAGE)) State.Paired else State.Unpaired,
    )
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var adbClient: AdbClient? = null
    @Volatile private var shell: AdbShell? = null
    @Volatile private var compat: ShizukuCompatClient? = null
    @Volatile private var autoConnectAfter = 0L

    val isPaired: Boolean get() = preferences.contains(KEY_STORAGE)
    val isConnected: Boolean get() = compat?.isAlive == true
    val uid: Int? get() = compat?.takeIf { it.isAlive }?.uid

    /** True on the builds that can pair: Android 11 and up with the native pairing library. */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && AdbPairingClient.available()

    /**
     * Pairs with the code shown by Android. Passing null discovers the pairing
     * port through mDNS; a manual port is the fallback for OEMs without it.
     */
    suspend fun pair(code: String, port: Int? = null): Int = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "Wireless pairing needs Android 11 or newer." }
        check(AdbPairingClient.available()) { "This build does not include the ADB pairing library." }
        val pairingPort = port?.takeIf { it in 1..65535 }
            ?: AdbMdns(context, AdbMdns.TLS_PAIRING).findPort(12_000)
            ?: throw AdbException("The pairing service was not found. Keep the pairing dialog open and try again.")
        AdbPairingClient("127.0.0.1", pairingPort, code, identity()).use { client ->
            if (!client.start()) throw AdbException("The pairing code was rejected. Start pairing again.")
        }
        _state.value = State.Paired
        pairingPort
    }

    /** Connects to the advertised wireless debugging port and starts the shell runner. */
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        autoConnectAfter = 0
        runCatching { connectInternal() }
    }

    /** Returns a live client, reconnecting on demand with a cooldown after failures. */
    internal suspend fun client(): ShizukuCompatClient? = withContext(Dispatchers.IO) {
        compat?.takeIf { it.isAlive } ?: run {
            if (System.currentTimeMillis() < autoConnectAfter) return@withContext null
            runCatching { connectInternal() }.onFailure {
                // A failed discovery can block a terminal command for seconds; do not retry every call.
                autoConnectAfter = System.currentTimeMillis() + AUTO_CONNECT_COOLDOWN_MS
            }
            compat?.takeIf { it.isAlive }
        }
    }

    suspend fun testShell(): String = withContext(Dispatchers.IO) {
        val active = client() ?: throw AdbException(
            if (isPaired) "Could not reach wireless debugging. Turn it on and try again."
            else "Pair with wireless debugging first.",
        )
        active.request(
            com.androidharness.app.data.env.CompatRequest(
                0, "exec", command = listOf("/system/bin/id", "-u"), timeoutMs = 5_000,
            ),
        )
    }

    /** Runs `id -u` through the runner and verifies the shell identity. */
    suspend fun testUid(): Int = withContext(Dispatchers.IO) {
        val raw = testShell()
        val uid = raw.lineSequence().drop(2).firstOrNull { it.isNotBlank() }?.trim()?.toIntOrNull()
        require(uid == 2000 || uid == 0) { "Unexpected shell UID: ${uid ?: "unknown"}." }
        uid
    }

    fun forget() {
        runCatching { compat?.close() }
        runCatching { shell?.close() }
        runCatching { adbClient?.close() }
        compat = null
        shell = null
        adbClient = null
        keyCache = null
        preferences.edit().clear().apply()
        _state.value = State.Unpaired
    }

    private suspend fun connectInternal() {
        connectLock.withLock {
            compat?.takeIf { it.isAlive }?.let { return }
            closeTransport()
            _state.value = State.Connecting
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                throw AdbException("Wireless debugging pairing needs Android 11 or newer.")
            }
            val port = AdbMdns(context, AdbMdns.TLS_CONNECT).findPort(10_000)
            if (port == null) {
                _state.value = State.Failed("Wireless debugging is off or no port was advertised.")
                throw AdbException("Turn on Developer options > Wireless debugging, then try again.")
            }
            val adb = AdbClient("127.0.0.1", port, identity())
            try {
                adb.connect()
                val opened = adb.openShell(runnerCommand())
                val started = ShizukuCompatClient.startWithAdb(opened) {
                    if (compat?.isAlive != true) {
                        _state.value = State.Failed("The shell runner disconnected. Reconnect to continue.")
                    }
                }
                adbClient = adb
                shell = opened
                compat = started
                autoConnectAfter = 0
                _state.value = State.Connected(started.uid, port)
            } catch (e: Exception) {
                runCatching { adb.close() }
                _state.value = State.Failed(e.message ?: "Could not connect to wireless debugging.")
                throw e
            }
        }
    }

    private fun closeTransport() {
        runCatching { compat?.close() }
        runCatching { shell?.close() }
        runCatching { adbClient?.close() }
        compat = null
        shell = null
        adbClient = null
    }

    /**
     * Starts the same runner the Shizuku compatibility path uses, but as the
     * ADB shell user. stderr is suppressed because the ADB shell stream merges
     * it into the JSON protocol; runner errors travel inside the protocol.
     */
    private fun runnerCommand(): String {
        val apk = context.applicationInfo.sourceDir
        val quoted = "'" + apk.replace("'", "'\\''") + "'"
        return "export CLASSPATH=$quoted; exec /system/bin/app_process /system/bin " +
            "${ShizukuCompatProtocol.MAIN_CLASS} 2>/dev/null"
    }

    private companion object {
        const val KEY_STORAGE = "adbkey"
        const val AUTO_CONNECT_COOLDOWN_MS = 30_000L
    }
}
