package com.androidharness.app.ui.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
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
import androidx.compose.ui.platform.LocalResources
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
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    private var originalAccessibilityFlags = 0

    @Before fun inspectAllTestWindows() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        originalAccessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }

    private val originalResources = mutableListOf<Pair<Resources, Configuration>>()

    @Suppress("DEPRECATION")
    @After fun restoreActivityResources() {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = originalAccessibilityFlags }
        instrumentation.runOnMainSync {
            originalResources.asReversed().forEach { (resources, configuration) ->
                resources.updateConfiguration(configuration, resources.displayMetrics)
            }
        }
    }

    private fun context(locale: String): Context = target.createConfigurationContext(
        Configuration(target.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(locale)) },
    )

    @Suppress("DEPRECATION")
    private fun show(activity: ChatListTestActivity, scale: Float = 1f, locale: String = "zh-CN", content: @Composable () -> Unit) {
        // Native dialog windows use the activity context, outside our composition locals.
        val resources = activity.resources
        if (originalResources.none { it.first === resources })
            originalResources += resources to Configuration(resources.configuration)
        val configuration = Configuration(resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(locale))
            fontScale = scale
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        val localized = activity.createConfigurationContext(configuration)
        activity.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalResources provides localized.resources,
                LocalActivityResultRegistryOwner provides activity,
                LocalOnBackPressedDispatcherOwner provides activity,
                LocalConfiguration provides localized.resources.configuration,
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

    @Test fun russianResourcesUseRussianPluralRulesAndKeepDomainValues() {
        val ru = context("ru")
        assertEquals("Модели и провайдеры", ru.getString(R.string.settings_page_models_title))
        assertEquals(context("en").getString(R.string.app_name), ru.getString(R.string.app_name))
        assertEquals("Confirm everything", PermissionMode.CONFIRM_ALL.label)
        assertEquals("Подтверждать всё", ru.localizedOptionLabel(PermissionMode.CONFIRM_ALL.label))
        for ((count, word) in listOf(1 to "модель", 2 to "модели", 5 to "моделей", 11 to "моделей",
            21 to "модель", 22 to "модели", 25 to "моделей", 101 to "модель")) {
            assertEquals("$count $word", ru.resources.getQuantityString(R.plurals.ui_model_count, count, count))
            val prefix = when (word) { "модель" -> "Доступна"; "модели" -> "Доступны"; else -> "Доступно" }
            assertEquals("$prefix $count $word", ru.resources.getQuantityString(R.plurals.settings_accounts_models_available, count, count))
        }
        assertEquals("Из 5 файлов удалено 10 MB", ru.getString(R.string.ui_removed_from_files, "10 MB", "5"))
        assertEquals("Обновлено файлов: 2", ru.getString(R.string.ui_updated, "2", "files"))
        assertEquals("Использований: 3 · Файлов: 2", ru.getString(R.string.ui_in, "3", "references", "2", "files"))
    }

    @Test fun russianSearchAndPermissionControlsWorkWithLargeText() {
        val ru = context("ru")
        var selected: Pair<SettingsPage, Int?>? = null
        val state = mutableStateOf(AppSettings())
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> show(activity, locale = "ru") {
                SettingsHome(ru.getString(R.string.settings_search_max_context_title), {},
                    { page, anchor -> selected = page to anchor }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            } }
            click(ru.getString(R.string.settings_search_max_context_desc))
            assertEquals(SettingsPage.AGENT to R.string.settings_anchor_max_context, selected)
            screenshot("russian-settings-search.png")
            scenario.onActivity { activity -> show(activity, scale = 1.5f, locale = "ru") {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AgentBehaviorSection(state.value,
                        onPermissionMode = { state.value = state.value.copy(permissionMode = it) },
                        onContextLimit = { state.value = state.value.copy(maxContextTokens = it) },
                        onIterationLimit = { state.value = state.value.copy(maxIterations = it) },
                        onProjectInstructions = {}, onLiveUpdates = {})
                }
            } }
            click(ru.getString(R.string.settings_anchor_default_permission))
            click(ru.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            assertEquals(PermissionMode.FULL_AUTO, state.value.permissionMode)
            awaitText(ru.localizedOptionLabel(PermissionMode.FULL_AUTO.label))
            screenshot("russian-agent-150-percent.png")
        }
    }

    @Test fun russianPrimaryScreensAndModelPickerPreserveModelIds() {
        val container = (target.applicationContext as HarnessApp).container
        val ru = context("ru")
        val preference = "russian-picker-${System.nanoTime()}"
        val shortcuts = ModelShortcuts(target, preference)
        val provider = ProviderConfig("russian-provider", "Provider unchanged", ProviderType.OPENAI_COMPAT,
            "https://unused.invalid/v1", "first-model")
        var chosen: Pair<String, String?>? = null
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> show(activity, locale = "ru") { SetupScreen(container, onFinish = {}) } }
                awaitText(ru.getString(R.string.ui_welcome_to_androidharness)); screenshot("russian-setup.png")
                scenario.onActivity { activity -> show(activity, locale = "ru") { StatsScreen(container, onBack = {}) } }
                awaitText(ru.getString(R.string.ui_model_activity)); screenshot("russian-usage.png")
                scenario.onActivity { activity -> show(activity, locale = "ru") { AutomationScreen(container, onBack = {}, onOpenSession = {}) } }
                awaitText(ru.getString(R.string.ui_automation)); screenshot("russian-automation.png")
                scenario.onActivity { activity -> show(activity, locale = "ru") { FilesScreen(container, onBack = {}, onOpenFile = {}) } }
                awaitText(ru.getString(R.string.ui_files)); screenshot("russian-files.png")
                scenario.onActivity { activity -> show(activity, locale = "ru") {
                    ModelPickerSheet(listOf(provider), provider.id, provider.model,
                        mapOf(provider.id to listOf(ModelEntry("first-model", displayName = "First model"),
                            ModelEntry("second-model", displayName = "Second model"))),
                        onDismiss = {}, onSelect = { id, model -> chosen = id to model }, onRefreshCatalog = { null },
                        onManageProviders = {}, modelShortcuts = shortcuts)
                } }
                awaitText(ru.getString(R.string.ui_all_models))
                awaitText(ru.getString(R.string.ui_search_models_or_providers))
                awaitText("${provider.name} · ${ru.getString(R.string.ui_current)}")
                click("Second model")
                screenshot("russian-model-picker.png"); click(ru.getString(R.string.ui_use_model))
                assertEquals(provider.id to "second-model", chosen)
            }
        } finally { target.deleteSharedPreferences(preference) }
    }

    private fun node(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            // Older Android versions merge a button's title, value and description into one node.
            if (listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).any {
                it == text || it.lineSequence().any { line -> line == text }
            }) return node
            for (index in 0 until node.childCount) search(node.getChild(index))?.let { return it }
            return null
        }
        val automation = instrumentation.uiAutomation
        // Android 9 can report no active root immediately after a dialog is dismissed.
        return search(automation.rootInActiveWindow)
            ?: automation.windows.firstNotNullOfOrNull { search(it.root) }
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            node(text)?.let { return it }
            SystemClock.sleep(100)
        }
        screenshot("localization-failure.png")
        val visible = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            visible += "${node.text} | ${node.contentDescription}"
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(instrumentation.uiAutomation.rootInActiveWindow)
        error("Missing localized control: $text; visible: $visible")
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
