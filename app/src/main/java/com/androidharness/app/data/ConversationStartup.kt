package com.androidharness.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class StartScreen {
    CODE, CHAT, LAST_SELECTED;

    fun kind(last: ConversationKind): ConversationKind = when (this) {
        CODE -> ConversationKind.CODE
        CHAT -> ConversationKind.CHAT
        LAST_SELECTED -> last
    }

    companion object {
        fun fromStored(value: String?): StartScreen = entries.firstOrNull { it.name == value } ?: CODE
    }
}

/** Startup mode and resume-chat preference are independent; never resume a different section. */
internal data class ConversationStartup(val kind: ConversationKind, val sessionId: String?) {
    companion object {
        fun resolve(settings: AppSettings, lastSessionKind: ConversationKind?): ConversationStartup {
            val kind = settings.startScreen.kind(settings.lastConversationKind)
            val session = settings.lastActiveSessionId?.takeIf {
                settings.resumeLastChat && lastSessionKind == kind && it.isNotBlank() && it !in settings.archivedSessions
            }
            return ConversationStartup(kind, session)
        }
    }
}
