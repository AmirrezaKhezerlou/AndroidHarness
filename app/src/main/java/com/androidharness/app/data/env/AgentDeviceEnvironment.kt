package com.androidharness.app.data.env

/** A read-only preview. Only dispatch verifies identity and consumes a root allowance. */
internal data class AgentDeviceEnvironment(
    val preferences: AccessPreferences,
    val rootReady: Boolean,
    val rootState: String,
    val rootAllowed: Boolean,
    val shizukuState: ShizukuState,
    val shizukuService: UserServiceState,
    val shizukuReady: Boolean,
    val shizukuUid: Int?,
    val paused: Boolean,
) {
    fun describe(appWorkspace: Boolean, linuxReady: Boolean): String = buildString {
        append("Access mode: ${preferences.mode.label}; fallback: ${preferences.mode == AccessMode.AUTO || preferences.autoFallback}; privileged commands: ${if (paused) "paused" else "enabled"}.\n")
        append("Root allowance policy: ${if (preferences.temporaryRoot) "temporary, ${preferences.rootUse.label}" else "unlimited while connected and unpaused"}.\n")
        append("Direct root: $rootState; connected UID 0 runner: $rootReady; root allowed for this project/chat: $rootAllowed.\n")
        append("Shizuku: ${shizukuState.name}; runner: ${shizukuService.name}; callable: $shizukuReady; last verified UID: ${shizukuUid ?: "not checked"}.\n")
        val decision = DeviceAccessPolicy.choose(preferences.mode, preferences.autoFallback,
            rootReady, shizukuUid.takeIf { shizukuReady }, rootAllowed, paused, appWorkspace)
        val awaitingIdentity = !appWorkspace && shizukuReady && shizukuUid == null && !paused &&
            preferences.mode != AccessMode.NORMAL && (preferences.mode == AccessMode.BOTH || preferences.mode == AccessMode.SHIZUKU || decision.backend != DeviceBackend.ROOT)
        append("Default command route: ")
        append(if (awaitingIdentity) "pending Shizuku UID verification at dispatch" else when (decision.backend) {
            DeviceBackend.NORMAL -> if (linuxReady) "app UID with the installed Linux userspace" else "app UID with Android toybox sh; Linux packages are not ready"
            DeviceBackend.ROOT -> "direct root, UID 0"
            DeviceBackend.DHIZUKU -> "Dhizuku Device Owner app"
            DeviceBackend.SHIZUKU -> "Shizuku, UID ${decision.uid}"
            null -> "unavailable: ${decision.reason}"
        })
        append(". This is a snapshot, not an execution receipt; identity and permission are rechecked before each action.\n")
        if (appWorkspace) append("The app-owned workspace uses the app UID for ordinary development even when root or Shizuku is connected.\n")
        append("Use shell access=\"shizuku\" to pin Android system commands to Shizuku; it never falls back to root or the app UID. Use access=\"root\" only for a task that needs UID 0 and an allowed root grant. Never invoke su/sudo to bypass access routing. A root-started Shizuku server can itself have UID 0; that does not grant direct-root permission.\n")
        if (!rootReady && !shizukuReady) append("Neither privileged runner is ready. Ordinary app-UID work remains available where Android storage permissions permit; privilege is not required for every coding task.\n")
    }
}
