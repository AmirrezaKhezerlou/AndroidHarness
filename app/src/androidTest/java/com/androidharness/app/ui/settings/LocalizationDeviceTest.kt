package com.androidharness.app.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.ModelShortcuts
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.ui.LocalDesktopLayout
import com.androidharness.app.ui.automation.AutomationScreen
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.common.localizedOptionLabel
import com.androidharness.app.ui.files.FilesScreen
import com.androidharness.app.ui.setup.SetupScreen
import com.androidharness.app.ui.stats.StatsScreen
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    private fun context(locale: String): Context = target.createConfigurationContext(
        Configuration(target.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(locale)) },
    )

    private fun show(activity: ChatListTestActivity, scale: Float = 1f, content: @Composable () -> Unit) {
        val chinese = context("zh-CN")
        activity.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides chinese,
                LocalActivityResultRegistryOwner provides activity,
                LocalOnBackPressedDispatcherOwner provides activity,
                LocalConfiguration provides chinese.resources.configuration,
                LocalDensity provides Density(density, scale),
                LocalDesktopLayout provides false,
            ) { HarnessTheme(dynamicColor = false, content = content) }
        }
    }

    @Test fun englishFallbackAndChineseResourcesKeepFormatsAndDomainLabels() {
        val en = context("en")
        val zh = context("zh-CN")
        val fallback = context("fr")
        assertEquals("Models & providers", en.getString(R.string.settings_page_models_title))
        assertEquals(en.getString(R.string.settings_page_models_title), fallback.getString(R.string.settings_page_models_title))
        assertNotEquals(en.getString(R.string.settings_page_models_title), zh.getString(R.string.settings_page_models_title))
        assertEquals("Confirm everything", PermissionMode.CONFIRM_ALL.label)
        assertNotEquals(PermissionMode.CONFIRM_ALL.label, zh.localizedOptionLabel(PermissionMode.CONFIRM_ALL.label))
        assertEquals("1 available model", en.resources.getQuantityString(R.plurals.settings_accounts_models_available, 1, 1))
        assertEquals("2 available models", en.resources.getQuantityString(R.plurals.settings_accounts_models_available, 2, 2))
        assertEquals("已从 5 个文件中移除 10 MB", zh.getString(R.string.ui_removed_from_files, "10 MB", "5"))
        assertEquals(" · full shell", en.getString(R.string.ui_full_shell))
    }

    @Test fun chineseSearchOpensTheSameAnchorAndPermissionChoicesWorkAtLargeFont() {
        val zh = context("zh-CN")
        val query = mutableStateOf(zh.getString(R.string.settings_search_max_context_title))
        var selected: Pair<SettingsPage, Int?>? = null
        val state = mutableStateOf(AppSettings(maxContextTokens = 200_000, maxIterations = 75))
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> show(activity) {
                SettingsHome(query.value, { query.value = it }, { page, anchor -> selected = page to anchor },
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            } }
            // The query field contains the same title; click the result's description.
            click(zh.getString(R.string.settings_search_max_context_desc))
            assertEquals(SettingsPage.AGENT to R.string.settings_anchor_max_context, selected)
            screenshot("chinese-settings-search.png")
            scenario.onActivity { activity -> show(activity, 1.5f) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AgentBehaviorSection(state.value,
                        onPermissionMode = { state.value = state.value.copy(permissionMode = it) },
                        onContextLimit = { state.value = state.value.copy(maxContextTokens = it) },
                        onIterationLimit = { state.value = state.value.copy(maxIterations = it) },
                        onProjectInstructions = {},
                        onLiveUpdates = { state.value = state.value.copy(liveUpdatesEnabled = it) })
                }
            } }
            click(zh.getString(R.string.settings_anchor_default_permission))
            click(zh.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            assertEquals(PermissionMode.FULL_AUTO, state.value.permissionMode)
            awaitText(zh.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            screenshot("chinese-agent-150-percent.png")
        }
    }

    @Test fun primaryScreensRenderChineseWithoutChangingStoredPreferences() {
        val container = (target.applicationContext as HarnessApp).container
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> show(activity) { SetupScreen(container, onFinish = {}) } }
            awaitText(context("zh-CN").getString(R.string.ui_welcome_to_androidharness))
            screenshot("chinese-setup.png")
            scenario.onActivity { activity -> show(activity) { StatsScreen(container, onBack = {}) } }
            awaitText(context("zh-CN").getString(R.string.ui_model_activity))
            screenshot("chinese-usage.png")
            scenario.onActivity { activity -> show(activity) { AutomationScreen(container, onBack = {}, onOpenSession = {}) } }
            awaitText(context("zh-CN").getString(R.string.ui_automation))
            screenshot("chinese-automation.png")
            scenario.onActivity { activity -> show(activity) { FilesScreen(container, onBack = {}, onOpenFile = {}) } }
            awaitText(context("zh-CN").getString(R.string.ui_files))
            screenshot("chinese-files.png")
        }
    }

    @Test fun chineseModelPickerPreservesProviderAndModelIdentifiers() {
        val preference = "localization-picker-${System.nanoTime()}"
        val shortcuts = ModelShortcuts(target, preference)
        val provider = ProviderConfig("localization-test-provider", "Provider unchanged",
            ProviderType.OPENAI_COMPAT, "https://unused.invalid/v1", "first-model")
        var selected: Pair<String, String?>? = null
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> show(activity) {
                    ModelPickerSheet(listOf(provider), provider.id, provider.model,
                        mapOf(provider.id to listOf(ModelEntry("first-model", displayName = "First model"), ModelEntry("second-model", displayName = "Second model"))),
                        onDismiss = {}, onSelect = { id, model -> selected = id to model },
                        onRefreshCatalog = { null }, onManageProviders = {}, modelShortcuts = shortcuts)
                } }
                awaitText(context("zh-CN").getString(R.string.ui_all_models))
                click("Second model")
                screenshot("chinese-model-picker.png")
                click(context("zh-CN").getString(R.string.ui_use_model))
                assertEquals(provider.id to "second-model", selected)
            }
        } finally { target.deleteSharedPreferences(preference) }
    }

    private fun node(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) search(node.getChild(index))?.let { return it }
            return null
        }
        return search(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            node(text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing localized control: $text")
    }

    private fun click(text: String) {
        var current: AccessibilityNodeInfo? = awaitText(text)
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(150)
                return
            }
            current = current.parent
        }
        error("Localized control is not clickable: $text")
    }

    private fun screenshot(name: String) {
        SystemClock.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(target.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
