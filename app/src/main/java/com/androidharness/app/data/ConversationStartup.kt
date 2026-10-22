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

/** Chat has its own start preference; the older resume toggle keeps governing Code only. */
@Serializable
enum class ChatStartMode {
    NEW_CHAT, LAST_CHAT;

    companion object {
        fun fromStored(value: String?): ChatStartMode = entries.firstOrNull { it.name == value } ?: NEW_CHAT
    }
}

/** Startup mode and resume-chat preference are independent; never resume a different section. */
internal data class ConversationStartup(val kind: ConversationKind, val sessionId: String?) {
    companion object {
        fun resolve(settings: AppSettings, lastSessionKind: ConversationKind?): ConversationStartup {
            val kind = settings.startScreen.kind(settings.lastConversationKind)
            val resume = when (kind) {
                ConversationKind.CHAT -> settings.chatStart == ChatStartMode.LAST_CHAT
                ConversationKind.CODE -> settings.resumeLastChat
            }
            val session = settings.lastActiveSessionId?.takeIf {
                resume && lastSessionKind == kind && it.isNotBlank() && it !in settings.archivedSessions
            }
            return ConversationStartup(kind, session)
        }
    }
}
