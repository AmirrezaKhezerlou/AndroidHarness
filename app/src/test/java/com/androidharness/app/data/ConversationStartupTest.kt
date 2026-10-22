package com.androidharness.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ConversationStartupTest {
    @Test fun oldSettingsAndUnknownStoredChoicesStartInCode() {
        assertEquals(StartScreen.CODE, AppSettings().startScreen)
        assertEquals(StartScreen.CODE, StartScreen.fromStored(null))
        assertEquals(StartScreen.CODE, StartScreen.fromStored("future"))
        assertEquals(StartScreen.CODE, Json.decodeFromString<AppSettings>("{}").startScreen)
    }

    @Test fun eachPreferenceSelectsTheExpectedSectionIndependentlyOfResumeChat() {
        for (last in ConversationKind.entries) {
            assertEquals(ConversationKind.CODE, ConversationStartup.resolve(AppSettings(startScreen = StartScreen.CODE, lastConversationKind = last), null).kind)
            assertEquals(ConversationKind.CHAT, ConversationStartup.resolve(AppSettings(startScreen = StartScreen.CHAT, lastConversationKind = last), null).kind)
            assertEquals(last, ConversationStartup.resolve(AppSettings(startScreen = StartScreen.LAST_SELECTED, lastConversationKind = last, resumeLastChat = false), null).kind)
        }
    }

    @Test fun onlyExistingUnarchivedConversationsInTheSelectedSectionCanResume() {
        val settings = AppSettings(startScreen = StartScreen.CHAT, lastActiveSessionId = "previous")
        assertEquals("previous", ConversationStartup.resolve(settings, ConversationKind.CHAT).sessionId)
        assertNull(ConversationStartup.resolve(settings, ConversationKind.CODE).sessionId)
        assertNull(ConversationStartup.resolve(settings, null).sessionId)
        assertNull(ConversationStartup.resolve(settings.copy(resumeLastChat = false), ConversationKind.CHAT).sessionId)
        assertNull(ConversationStartup.resolve(settings.copy(archivedSessions = setOf("previous")), ConversationKind.CHAT).sessionId)
    }

    @Test fun backupsCarryThePreferenceButDoNotCarryDeviceLocalSelection() {
        val exported = SettingsBackupPolicy.exportSettings(AppSettings(startScreen = StartScreen.LAST_SELECTED,
            lastConversationKind = ConversationKind.CHAT, lastActiveSessionId = "device-chat"), emptySet())
        assertEquals(StartScreen.LAST_SELECTED, exported.startScreen)
        assertEquals(ConversationKind.CODE, exported.lastConversationKind)
        assertNull(exported.lastActiveSessionId)
        val decoded = Json.decodeFromString<AppSettings>(Json.encodeToString(AppSettings.serializer(), exported))
        assertEquals(StartScreen.LAST_SELECTED, decoded.startScreen)
    }
}
