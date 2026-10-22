package com.androidharness.app.ui.settings

import org.junit.Assert.*
import org.junit.Test
import com.androidharness.app.R

class SettingsNavigationTest {
    @Test fun `chat option follows language and its startup choice is searchable`() {
        assertEquals(SettingsPage.LANGUAGE.ordinal + 1, SettingsPage.CHAT_OPTIONS.ordinal)
        assertTrue(SettingsPage.CHAT_OPTIONS in matchingSettingsPages("last selected", settingsTestStrings()))
        assertTrue(matchingSettingsEntries("start screen", settingsTestStrings()).any { it.page == SettingsPage.CHAT_OPTIONS && it.anchor == R.string.settings_start_screen })
        assertTrue(matchingSettingsEntries("start chat", settingsTestStrings()).any { it.page == SettingsPage.CHAT_OPTIONS && it.anchor == R.string.settings_chat_start })
        assertTrue(SettingsPage.MEMORY in matchingSettingsPages("forget saved facts", settingsTestStrings()))
        assertTrue(matchingSettingsEntries("memory", settingsTestStrings()).any { it.page == SettingsPage.MEMORY })
    }

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
        val home = SettingsPage.entries.filter { it.onHome }
        val entries = matchingSettingsEntries("", settingsTestStrings())
        assertEquals(home.size, entries.size)
        assertTrue(entries.all { it.primary })
        assertEquals(home, matchingSettingsPages("", settingsTestStrings()))
    }

    @Test
    fun `automation search finds integrated GitHub presets and build shortcut stays removed`() {
        assertEquals(listOf(SettingsPage.GITHUB), matchingSettingsPages("automation", settingsTestStrings()))
        assertTrue(matchingSettingsPages("build test", settingsTestStrings()).isEmpty())
    }

    @Test
    fun `language is first and GitHub integration still supports a direct link`() {
        assertEquals(SettingsPage.LANGUAGE, matchingSettingsPages("", settingsTestStrings()).first())
        assertEquals(SettingsPage.CHAT_OPTIONS, matchingSettingsPages("", settingsTestStrings())[1])
        assertEquals(SettingsPage.MEMORY, matchingSettingsPages("", settingsTestStrings())[2])
        assertEquals(SettingsPage.CODING, matchingSettingsPages("", settingsTestStrings())[3])
        assertEquals(SettingsPage.GITHUB, matchingSettingsPages("", settingsTestStrings())[4])
        assertEquals(SettingsPage.LANGUAGE, settingsDeepLink("language"))
        assertEquals(SettingsPage.GITHUB, settingsDeepLink("github"))
        assertEquals(listOf(SettingsPage.GITHUB), matchingSettingsPages("commit push", settingsTestStrings()))
    }

    @Test
    fun `coding hub owns agent and device pages while memory stands alone`() {
        assertTrue(SettingsPage.CODING.onHome)
        for (page in listOf(SettingsPage.SUBAGENT, SettingsPage.AGENT, SettingsPage.CHAT,
            SettingsPage.SKILLS, SettingsPage.CODE_INTELLIGENCE, SettingsPage.CAVEMAN,
            SettingsPage.MCP, SettingsPage.TELEGRAM, SettingsPage.WORKSPACE,
            SettingsPage.ENVIRONMENT, SettingsPage.TROUBLESHOOTING)) {
            assertFalse(page.onHome)
            assertEquals(R.string.settings_group_coding, page.group)
            assertTrue(page in matchingSettingsPages(page.keywords.substringBefore(' '), settingsTestStrings()))
        }
        assertEquals(R.string.settings_group_memory, SettingsPage.MEMORY.group)
        assertEquals(SettingsPage.CODING, settingsDeepLink("coding"))
        assertTrue(SettingsPage.AGENT in matchingSettingsPages("max context", settingsTestStrings()))
        assertTrue(SettingsPage.CHAT in matchingSettingsPages("repo map", settingsTestStrings()))
    }

    @Test
    fun `translation search opens the contribution section in either language`() {
        val english = settingsTestStrings()
        val chinese = settingsTestStrings("values-zh-rCN")
        val anchor = R.string.settings_language_contribute_title
        for ((query, text) in listOf("poeditor" to english, chinese(anchor) to chinese)) {
            assertTrue(matchingSettingsEntries(query, text).any {
                it.page == SettingsPage.LANGUAGE && it.anchor == anchor
            })
        }
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
