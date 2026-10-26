package com.androidharness.app.data.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updater backed by GitHub Releases.
 *
 * Flow: check latest release -> if its version is NEWER than the running one,
 * surface a dialog (with clickable changelog links parsed from the release
 * body) -> download the .apk asset with progress -> install:
 *
 *  - Already-authorized Root or ready Shizuku, respecting device access: `pm install -r` runs silently
 *    as the shell uid (the APK is staged under the app's external files dir,
 *    which the shell uid demonstrably reads, the toolchain deploy uses the
 *    exact same path family).
 *  - No available privileged route: hand the downloaded file to the system package installer
 *    (the standard "express" sideload flow with the platform's own UI).
 */
class UpdateManager(
    private val context: Context,
    private val access: com.androidharness.app.data.env.DeviceAccessManager,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
) {

    enum class Phase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, DONE, ERROR }

    data class LatestRelease(
        val tag: String,
        val name: String,
        /** Release body/description, may contain plain links or a linked changelog.md. */
        val body: String,
        val htmlUrl: String,
        val apkUrl: String?,
        val apkName: String?,
        val apkBytes: Long,
        val apkSha256: String? = null,
    )

    sealed interface Step {
        data object Idle : Step
        data object Checking : Step
        data class UpToDate(val current: String) : Step
        data class Available(val release: LatestRelease) : Step
        data class Downloading(val release: LatestRelease, val percent: Int, val mb: Float, val totalMb: Float) : Step
        data class Installing(val release: LatestRelease, val viaShizuku: Boolean, val viaRoot: Boolean = false) : Step
        data class Done(val viaShizuku: Boolean, val viaRoot: Boolean = false) : Step
        data class Error(val message: String, val release: LatestRelease? = null) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val operation = kotlinx.coroutines.sync.Mutex()
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var activeCall: okhttp3.Call? = null
    private val preferences = context.getSharedPreferences("update-installation", Context.MODE_PRIVATE)
    private val mutableMethod = MutableStateFlow(runCatching { UpdateInstallMethod.valueOf(preferences.getString("method", "AUTO")!!) }.getOrDefault(UpdateInstallMethod.AUTO))
    val installMethod = mutableMethod.asStateFlow()
    fun setInstallMethod(method: UpdateInstallMethod) {
        if (_step.value is Step.Downloading || _step.value is Step.Installing) return
        preferences.edit().putString("method", method.name).apply(); mutableMethod.value = method
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Where downloads stage: shared external files dir (shell-uid readable). */
    private val updateDir: File
        get() = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "update",
        ).apply { mkdirs() }

    /** Manual flag so a Settings-triggered check always surfaces its result. */
    @Volatile private var manualCheck = false

    suspend fun check(manual: Boolean = false): Unit = withContext(Dispatchers.IO) {
        manualCheck = manual || manualCheck
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        }.getOrDefault("")
        val alreadyRunning = when (val s = _step.value) {
            is Step.Checking, is Step.Downloading, is Step.Installing -> true
            else ->
                // Auto checks shouldn't nag again over an existing dialog.
                !manual && (s is Step.Available || s is Step.Error)
        }
        if (alreadyRunning) return@withContext
        try {
            _step.value = Step.Checking
            val release = fetchLatest()
            val newer = release != null && isNewer(release.name.ifBlank { release.tag }, current)
            when {
                release == null -> _step.value =
                    if (manualCheck) Step.Error("No published release found yet.") else Step.Idle
                newer -> _step.value = Step.Available(release)
                manualCheck -> _step.value = Step.UpToDate(current)
                else -> _step.value = Step.Idle
            }
        } catch (e: Exception) {
            if (manualCheck) {
                _step.value = Step.Error(e.message ?: "Update check failed")
            } else {
                _step.value = Step.Idle
            }
        } finally {
            manualCheck = false
        }
    }

    fun dismiss() {
        if (_step.value is Step.Installing) return
        cancelled.set(true); activeCall?.cancel(); _step.value = Step.Idle
    }

    private fun fetchLatest(): LatestRelease? {
        // NOTE: /releases/latest EXCLUDES prereleases, every release in this
        // repo ships as pre-release, so query the list instead. It arrives
        // newest-first (by created date); drafts are skipped.
        val req = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("GitHub API HTTP ${resp.code}")
            val arr = json.parseToJsonElement(resp.body!!.string()).jsonArray
            for (el in arr) {
                val root = el.jsonObject
                if (root["draft"]?.jsonPrimitive?.content == "true") continue
                return parseRelease(root) ?: continue
            }
            return null
        }
    }

    private fun parseRelease(root: kotlinx.serialization.json.JsonObject): LatestRelease? {
        val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty().removePrefix("v")
        val name = root["name"]?.jsonPrimitive?.content.orEmpty()
        val body = root["body"]?.jsonPrimitive?.content.orEmpty()
        val url = root["html_url"]?.jsonPrimitive?.content.orEmpty()
        var apkUrl: String? = null
        var apkName: String? = null
        var apkBytes = 0L
        var apkSha256: String? = null
        root["assets"]?.jsonArray?.forEach { el ->
            val a = el.jsonObject
            val assetName = a["name"]?.jsonPrimitive?.content.orEmpty()
            val lower = assetName.lowercase()
            val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            val assetAbi = knownAbis.firstOrNull { lower.contains(it) }
            val supported = assetAbi == null || assetAbi in android.os.Build.SUPPORTED_ABIS
            if (assetName.endsWith(".apk", ignoreCase = true) && supported && apkUrl == null) {
                apkUrl = a["browser_download_url"]?.jsonPrimitive?.content
                apkName = assetName
                apkBytes = a["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                apkSha256 = a["digest"]?.jsonPrimitive?.content?.removePrefix("sha256:")?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            }
        }
        if (tag.isBlank()) return null
        return LatestRelease(tag, name.ifBlank { tag }, body, url, apkUrl, apkName, apkBytes, apkSha256)
    }

    /** Stage under a fixed name, never a filename supplied by a release asset. */
    private suspend fun download(release: LatestRelease): File = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
        require(release.apkUrl?.let(UpdateInstallPolicy::trustedAsset) == true) { "This release has no supported GitHub APK asset." }
        val out = File(updateDir, "pending-update.apk")
        val partial = File(updateDir, "pending-update.apk.part")
        val request = Request.Builder().url(release.apkUrl!!).build()
        val call = client.newCall(request); activeCall = call
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = requireNotNull(response.body) { "Empty download" }
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkBytes
                require(total <= UpdateInstallPolicy.MAX_APK_BYTES) { "Update APK is too large." }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var read = 0L
                body.byteStream().use { input -> java.io.FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get() || Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException()
                        val n = input.read(buffer); if (n < 0) break
                        read += n; require(read <= UpdateInstallPolicy.MAX_APK_BYTES) { "Update APK is too large." }
                        output.write(buffer, 0, n); digest.update(buffer, 0, n)
                        if (!cancelled.get()) _step.value = Step.Downloading(release, if (total > 0) (read * 100 / total).toInt().coerceIn(0, 100) else 0,
                            read / 1_048_576f, total.coerceAtLeast(0) / 1_048_576f)
                    }
                    output.fd.sync()
                } }
                check(read > 0 && (total <= 0 || read == total) && (release.apkBytes <= 0 || read == release.apkBytes)) { "Update download was incomplete." }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(release.apkSha256 == null || release.apkSha256.equals(hash, ignoreCase = true)) { "Update checksum did not match. Download again." }
                check(partial.renameTo(out)) { "Could not stage the update." }
            }
            out
        } finally { activeCall = null; partial.delete() }
    }

    internal fun validateApk(apk: File) {
        val pm = context.packageManager
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val current = pm.getPackageInfo(context.packageName, flags)
        val candidate = requireNotNull(pm.getPackageArchiveInfo(apk.absolutePath, flags)) { "The downloaded file is not a valid APK." }
        fun version(info: android.content.pm.PackageInfo) = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun digest(signatures: Array<android.content.pm.Signature>?) = signatures.orEmpty().map {
            java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }.toSet()
        val installedSigners = if (android.os.Build.VERSION.SDK_INT >= 28) digest(current.signingInfo?.apkContentsSigners) else digest(current.signatures)
        val newSigners = if (android.os.Build.VERSION.SDK_INT >= 28) digest(candidate.signingInfo?.apkContentsSigners) else digest(candidate.signatures)
        val history = if (android.os.Build.VERSION.SDK_INT >= 28) digest(candidate.signingInfo?.signingCertificateHistory).ifEmpty { newSigners } else newSigners
        require(UpdateInstallPolicy.compatible(current.packageName, candidate.packageName, version(current), version(candidate), installedSigners, newSigners, history)) {
            "This APK is incompatible with the installed app, has a different signing certificate, or is an older build."
        }
        require((candidate.applicationInfo?.minSdkVersion ?: 1) <= android.os.Build.VERSION.SDK_INT) { "This update needs a newer Android version." }
        java.util.zip.ZipFile(apk).use { zip ->
            val abis = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet()
            require(abis.isEmpty() || android.os.Build.SUPPORTED_ABIS.any { it in abis }) { "This APK does not support this device architecture." }
        }
    }

    suspend fun startUpdate(release: LatestRelease, onOpenSystemInstaller: (File) -> Unit, onOpenUnknownSourcesSettings: () -> Unit): Unit = withContext(Dispatchers.IO) {
        if (!operation.tryLock()) return@withContext
        cancelled.set(false)
        try {
            _step.value = Step.Downloading(release, 0, 0f, release.apkBytes / 1_048_576f)
            val apk = download(release)
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) return@withContext
            validateApk(apk)
            val method = installMethod.value
            if (method != UpdateInstallMethod.SYSTEM) {
                val backend = when (method) { UpdateInstallMethod.ROOT -> com.androidharness.app.data.env.DeviceBackend.ROOT; UpdateInstallMethod.SHIZUKU -> com.androidharness.app.data.env.DeviceBackend.SHIZUKU; else -> null }
                val attempt = access.installUpdate(apk, backend) { selected ->
                    _step.value = Step.Installing(release, selected == com.androidharness.app.data.env.DeviceBackend.SHIZUKU, selected == com.androidharness.app.data.env.DeviceBackend.ROOT)
                }
                if (attempt != null) {
                    val (selected, result) = attempt
                    if (UpdateInstallPolicy.succeeded(result.exitCode, result.timedOut, result.output)) {
                        _step.value = Step.Done(selected == com.androidharness.app.data.env.DeviceBackend.SHIZUKU, selected == com.androidharness.app.data.env.DeviceBackend.ROOT)
                        apk.delete()
                    } else _step.value = Step.Error("Installation was not confirmed. Check the app version before retrying; the install was not replayed.", release)
                    return@withContext
                }
                check(method == UpdateInstallMethod.AUTO) { "The selected install method is unavailable. Connect it in device access settings, or choose Android installer." }
            }
            _step.value = Step.Installing(release, false)
            if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                _step.value = Step.Error(NEED_INSTALL_PERMISSION, release)
                withContext(Dispatchers.Main) { onOpenUnknownSourcesSettings() }
            } else withContext(Dispatchers.Main) { onOpenSystemInstaller(apk) }
        } catch (e: kotlinx.coroutines.CancellationException) { _step.value = Step.Idle; throw e }
        catch (e: Exception) { if (!cancelled.get()) _step.value = Step.Error(e.message ?: "Update failed", release) }
        finally { operation.unlock() }
    }

    suspend fun continueSystemInstall(release: LatestRelease, apk: File, onOpenSystemInstaller: (File) -> Unit) {
        withContext(Dispatchers.IO) { validateApk(apk) }
        _step.value = Step.Installing(release, false)
        withContext(Dispatchers.Main) { onOpenSystemInstaller(apk) }
    }

    companion object {
        const val RELEASES_API = "https://api.github.com/repos/Sanuu7/AndroidHarness/releases"
        const val NEED_INSTALL_PERMISSION =
            "Allow installs from this source to continue. Grant it, then tap Update again."

        /**
         * Compares the numeric core of [remote] against [current]: "0.3-alpha" ->
         * 0.3, "Alpha-v0.3" -> 0.3, "v1.2.3" -> 1.2.3. Anything unparsable never
         * prompts (no nagging on odd tags); equal versions never prompt.
         */
        internal fun isNewer(remote: String, current: String): Boolean {
            val r = parseParts(remote) ?: return false
            val c = parseParts(current) ?: return false
            val n = maxOf(r.size, c.size)
            for (i in 0 until n) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }
            return false
        }

        /**
         * Pulls the version core out of arbitrary release names/tags by scanning
         * digit runs anywhere in the string and taking the last: "Alpha-v0.3"
         * (the repo's tag shape), "0.3-alpha", "v1.2.3", "Alpha v0.31".
         */
        private fun parseParts(version: String): List<Int>? =
            VERSION_CORE_RE.findAll(version).lastOrNull()
                ?.value?.split('.')?.map { it.toIntOrNull() ?: 0 }

        private val VERSION_CORE_RE = Regex("""\d+(?:\.\d+)*""")
    }
}
