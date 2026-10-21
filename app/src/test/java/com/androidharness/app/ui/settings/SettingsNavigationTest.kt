package com.androidharness.app.ui.settings

import org.junit.Assert.*
import org.junit.Test
import com.androidharness.app.R

class SettingsNavigationTest {
    @Test
    fun `storage is searchable and supports a direct link`() {
        assertEquals(listOf(SettingsPage.STORAGE), matchingSettingsPages("cleanup disk", settingsTestStrings()))
        assertEquals(SettingsPage.STORAGE, settingsDeepLink("storage"))
    }

    @Test
    fun `search finds controls by familiar names and hidden keywords`() {
        assertEquals(listOf(SettingsPage.APPEARANCE), matchingSettingsPages("  DARK  theme ", settingsTestStrings()))
        assertEquals(listOf(SettingsPage.ENVIRONMENT), matchingSettingsPages("battery", settingsTestStrings()))
        assertTrue(SettingsPage.MODELS in matchingSettingsPages("api\tkey", settingsTestStrings()))
        assertEquals(listOf(SettingsPage.CHAT), matchingSettingsPages("repo map", settingsTestStrings()))
        assertEquals(listOf(SettingsPage.PRIVACY), matchingSettingsPages("fingerprint", settingsTestStrings()))
        assertEquals(listOf(SettingsPage.CODE_INTELLIGENCE), matchingSettingsPages("codegraph affected", settingsTestStrings()))
    }

    @Test
    fun `search returns nested settings as primary style results`() {
        val context = matchingSettingsEntries("max context", settingsTestStrings()).first { it.title == R.string.settings_search_max_context_title }
        assertEquals(SettingsPage.AGENT, context.page)
        assertFalse(context.primary)

        val planning = matchingSettingsEntries("plan model", settingsTestStrings()).first { it.title == R.string.settings_search_plan_model_title }
        assertEquals(SettingsPage.MODELS, planning.page)

        val lock = matchingSettingsEntries("auto lock timeout", settingsTestStrings()).first { it.title == R.string.settings_search_autolock_title }
        assertEquals(SettingsPage.PRIVACY, lock.page)
        val subagent = matchingSettingsEntries("subagent model", settingsTestStrings()).first { it.title == R.string.settings_search_subagent_model_title }
        assertEquals(SettingsPage.SUBAGENT, subagent.page)
        assertEquals(R.string.settings_anchor_subagent_model, subagent.anchor)
    }

    @Test
    fun `empty search keeps the normal primary settings home`() {
        val entries = matchingSettingsEntries("", settingsTestStrings())
        assertEquals(SettingsPage.entries.size, entries.size)
        assertTrue(entries.all { it.primary })
        assertEquals(SettingsPage.entries.toList(), matchingSettingsPages("", settingsTestStrings()))
    }

    @Test
    fun `automation search finds integrated GitHub presets and build shortcut stays removed`() {
        assertEquals(listOf(SettingsPage.GITHUB), matchingSettingsPages("automation", settingsTestStrings()))
        assertTrue(matchingSettingsPages("build test", settingsTestStrings()).isEmpty())
    }

    @Test
    fun `GitHub integration is the first settings page and supports a direct link`() {
        assertEquals(SettingsPage.GITHUB, matchingSettingsPages("", settingsTestStrings()).first())
        assertEquals(SettingsPage.GITHUB, settingsDeepLink("github"))
        assertEquals(listOf(SettingsPage.GITHUB), matchingSettingsPages("commit push", settingsTestStrings()))
    }

    @Test
    fun `existing chat shortcuts land on their focused settings pages`() {
        assertEquals(SettingsPage.MODELS, settingsDeepLink("planning"))
        assertEquals(SettingsPage.VOICE, settingsDeepLink("voice"))
        assertNull(settingsDeepLink("unknown"))
    }
    @Test
    fun `Chinese labels and English keywords find the same control`() {
        val chinese = settingsTestStrings("values-zh-rCN")
        val english = settingsTestStrings()
        val anchor = R.string.settings_anchor_max_context
        val zhLabel = chinese(anchor)
        assertTrue(zhLabel != english(anchor))
        assertEquals(anchor, matchingSettingsEntries(zhLabel, chinese).first { it.anchor == anchor }.anchor)
        assertTrue(SettingsPage.AGENT in matchingSettingsPages("context tokens", chinese))
        assertTrue(matchingSettingsEntries("max context", chinese, english).any { it.anchor == anchor })
        assertEquals(settingsDeepLinkAnchor("planning"), R.string.settings_anchor_dual_planning)
        assertEquals(settingsDeepLinkAnchor("voice"), R.string.settings_anchor_stt)
    }
}
