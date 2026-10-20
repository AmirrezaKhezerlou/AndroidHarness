package com.androidharness.app.data.env

import kotlinx.serialization.Serializable

@Serializable
enum class AccessMode(val label: String) { AUTO("Auto"), NORMAL("Normal"), SHIZUKU("Shizuku"), ROOT("Root") }
@Serializable
enum class RootUse(val label: String) { SESSION("App session"), CHAT("Current chat"), NEXT_ACTION("Next action") }
@Serializable
enum class DeviceBackend { NORMAL, SHIZUKU, ROOT }

@Serializable
data class ExecutionReceipt(
    val backend: DeviceBackend,
    val uid: Int? = null,
    val fallbackReason: String? = null,
) {
    val label: String get() = when (backend) {
        DeviceBackend.NORMAL -> "Normal"
        DeviceBackend.ROOT -> "Root"
        DeviceBackend.SHIZUKU -> if (uid == 0) "Shizuku · Root" else "Shizuku · ADB"
    }
}

data class AccessPreferences(
    val mode: AccessMode = AccessMode.AUTO,
    val autoFallback: Boolean = true,
    val rootUse: RootUse = RootUse.SESSION,
    val rootProjects: Set<String> = emptySet(),
)

data class AccessDecision(val backend: DeviceBackend?, val reason: String? = null, val uid: Int? = null)

/** Pure selection rules; readiness is verified by the executor immediately before dispatch. */
object DeviceAccessPolicy {
    fun choose(
        mode: AccessMode, fallback: Boolean, rootReady: Boolean, shizukuUid: Int?,
        rootAllowed: Boolean, paused: Boolean = false, appWorkspace: Boolean = false,
        requireRoot: Boolean = false,
    ): AccessDecision {
        if (appWorkspace && !requireRoot) return AccessDecision(DeviceBackend.NORMAL)
        val order = when (mode) {
            AccessMode.NORMAL -> listOf(DeviceBackend.NORMAL)
            AccessMode.SHIZUKU -> if (fallback) listOf(DeviceBackend.SHIZUKU, DeviceBackend.NORMAL) else listOf(DeviceBackend.SHIZUKU)
            AccessMode.ROOT -> if (fallback) listOf(DeviceBackend.ROOT, DeviceBackend.SHIZUKU, DeviceBackend.NORMAL) else listOf(DeviceBackend.ROOT)
            AccessMode.AUTO -> buildList {
                if (rootAllowed || requireRoot) add(DeviceBackend.ROOT)
                add(DeviceBackend.SHIZUKU)
                add(DeviceBackend.NORMAL)
            }
        }
        val unavailable = mutableListOf<String>()
        for (backend in order) {
            val uid = when (backend) { DeviceBackend.NORMAL -> null; DeviceBackend.ROOT -> if (rootReady) 0 else null; DeviceBackend.SHIZUKU -> shizukuUid }
            val issue = when {
                backend != DeviceBackend.NORMAL && paused -> "Privileged access is paused"
                backend == DeviceBackend.ROOT && !rootAllowed -> "Root is not allowed for this project or its temporary allowance has ended"
                backend == DeviceBackend.ROOT && !rootReady -> "Root is unavailable"
                backend == DeviceBackend.SHIZUKU && shizukuUid == null -> "Shizuku is unavailable"
                uid == 0 && !rootAllowed -> "Root is not allowed for this project or its temporary allowance has ended"
                requireRoot && uid != 0 -> "This action requires root"
                else -> null
            }
            if (issue == null) return AccessDecision(backend, unavailable.distinct().joinToString("; ").ifBlank { null }, uid)
            unavailable += issue
        }
        return AccessDecision(null, unavailable.distinct().joinToString("; "))
    }
}

/** A lease belongs to one tool action, including its internal shell calls. */
internal class RootLease { internal var generation = -1L }

/** Temporary grants are deliberately memory-only and invalidated on revoke/regrant. */
internal class TemporaryRootAllowance {
    private var generation = 0L
    private var use: RootUse? = null
    private var chat: String? = null
    private var next = false

    @Synchronized fun grant(scope: RootUse, sessionId: String?) {
        require(scope != RootUse.CHAT || sessionId != null)
        generation++; use = scope; chat = sessionId; next = true
    }
    @Synchronized fun revoke() { generation++; use = null; next = false }
    @Synchronized fun allows(sessionId: String?, lease: RootLease): Boolean =
        when (use) {
            RootUse.SESSION -> true
            RootUse.CHAT -> chat != null && chat == sessionId
            RootUse.NEXT_ACTION -> next || lease.generation == generation
            null -> false
        }
    @Synchronized fun claim(sessionId: String?, lease: RootLease): Boolean {
        if (!allows(sessionId, lease)) return false
        if (use == RootUse.NEXT_ACTION) next = false
        lease.generation = generation
        return true
    }
}
