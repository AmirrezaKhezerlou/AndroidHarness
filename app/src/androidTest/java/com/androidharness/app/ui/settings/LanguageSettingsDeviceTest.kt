package com.androidharness.app.ui.settings

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.R
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageSettingsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val container get() = (target.applicationContext as HarnessApp).container

    private var previousOnboarding = false

    @Before fun openAnAlreadyConfiguredApp() = runBlocking {
        // First-launch notification prompts must not cover the language picker on Android 13+.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            target.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        previousOnboarding = container.settings.settings.first().onboardingDone
        container.settings.setOnboardingDone(true)
    }

    @After fun restoreSetupState() = runBlocking {
        container.settings.setOnboardingDone(previousOnboarding)
    }

    private fun label(id: Int, tags: String): String = target.createConfigurationContext(
        Configuration(target.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tags)) },
    ).getString(id)

    @Test fun pickerSwitchesTheRealActivityPersistsAndClearsTheOverride() {
        var original = LocaleListCompat.getEmptyLocaleList()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                original = AppCompatDelegate.getApplicationLocales()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
            }
            try {
                openLanguage(scenario)
                scrollTo(label(R.string.settings_language_contribute_title, "en"))
                click("简体中文")
                scrollTo(label(R.string.settings_language_contribute_title, "zh-CN"))
                scenario.onActivity {
                    assertEquals("zh", it.resources.configuration.locales[0].language)
                    assertEquals("zh-CN", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                }
                screenshot("language-chinese-phone.png")
                scenario.recreate()
                scrollTo(label(R.string.settings_language_contribute_title, "zh-CN"))
                scenario.onActivity { assertEquals("zh", it.resources.configuration.locales[0].language) }

                click("Русский")
                scrollTo(label(R.string.settings_language_contribute_title, "ru"))
                scenario.onActivity {
                    assertEquals("ru", it.resources.configuration.locales[0].language)
                    assertEquals("ru", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                }
                screenshot("language-russian-phone.png")
                scenario.recreate()
                scrollTo(label(R.string.settings_language_contribute_title, "ru"))
                scenario.onActivity { assertEquals("ru", it.resources.configuration.locales[0].language) }

                click("العربية")
                scrollTo(label(R.string.settings_language_contribute_title, "ar"))
                scenario.onActivity {
                    assertEquals("ar", it.resources.configuration.locales[0].language)
                    assertEquals("ar", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, it.resources.configuration.layoutDirection)
                    assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, it.window.decorView.layoutDirection)
                }
                screenshot("language-arabic.png")
                scenario.recreate()
                scrollTo(label(R.string.settings_language_contribute_title, "ar"))
                scenario.onActivity { assertEquals("ar", it.resources.configuration.locales[0].language) }

                // Indonesian must restore LTR layout after Arabic, including on legacy Android.
                click("Bahasa Indonesia")
                scrollTo(label(R.string.settings_language_contribute_title, "id"))
                scenario.onActivity {
                    assertEquals("id", it.resources.configuration.locales[0].toLanguageTag().substringBefore('-'))
                    assertEquals("id", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.resources.configuration.layoutDirection)
                    assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, it.window.decorView.layoutDirection)
                }
                screenshot("language-indonesian.png")
                scenario.recreate()
                scrollTo(label(R.string.settings_language_contribute_title, "id"))
                scenario.onActivity {
                    assertEquals("id", it.resources.configuration.locales[0].toLanguageTag().substringBefore('-'))
                    // Older Android uses the legacy language code internally.
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("in"))
                }
                scrollTo(label(R.string.settings_language_contribute_title, "id"))
                var chosen = find("Bahasa Indonesia")
                while (chosen != null && !chosen.isCheckable) chosen = chosen.parent
                assertTrue("Indonesian remains selected with the legacy locale code", chosen?.isChecked == true)

                // Explicit English must work even after Chinese, Russian, Arabic and Indonesian overrides.
                click("English")
                scrollTo(label(R.string.settings_language_contribute_title, "en"))
                scenario.onActivity { assertEquals("en", it.resources.configuration.locales[0].language) }
                click("Bahasa Indonesia")
                scrollTo(label(R.string.settings_language_system, "id"))
                click(label(R.string.settings_language_system, "id"))
                val systemTags = if (android.os.Build.VERSION.SDK_INT >= 33)
                    target.getSystemService(android.app.LocaleManager::class.java).systemLocales.toLanguageTags()
                else Resources.getSystem().configuration.locales.toLanguageTags()
                awaitText(label(R.string.settings_language_contribute_title, systemTags))
                scenario.onActivity {
                    assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
                    assertEquals(label(R.string.settings_page_language_title, systemTags),
                        it.getString(R.string.settings_page_language_title))
                }
                screenshot("language-system-default-phone.png")
            } finally {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(original) }
            }
        }
    }

    @Test fun contributionButtonOpensThePublicProjectAtLargeFont() {
        var opened: String? = null
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(
                        LocalDensity provides Density(density, 1.5f),
                        LocalUriHandler provides object : UriHandler {
                            override fun openUri(uri: String) { opened = uri }
                        },
                    ) {
                        HarnessTheme(dynamicColor = false) {
                            Surface {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    LanguageSection()
                                }
                            }
                        }
                    }
                }
            }
            val action = target.getString(R.string.settings_language_contribute_action)
            scrollTo(action)
            screenshot("language-contribute-150-percent.png")
            click(action)
            assertEquals("https://poeditor.com/join/project/ZPbHNa9WWc", opened)
        }
    }

    private fun openLanguage(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { container.pendingSettingsScroll.value = "language" }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            find(text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing language control: $text")
    }

    private fun scrollTo(text: String) {
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            repeat(8) {
                if (find(text) != null) return
                fun scroll(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.isScrollable && node.performAction(direction)) return true
                    for (index in 0 until node.childCount) if (scroll(node.getChild(index))) return true
                    return false
                }
                scroll(instrumentation.uiAutomation.rootInActiveWindow)
                SystemClock.sleep(200)
            }
        }
        awaitText(text)
    }

    private fun click(text: String) {
        scrollTo(text)
        var node: AccessibilityNodeInfo? = awaitText(text)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                return
            }
            node = node.parent
        }
        error("Language control is not clickable: $text")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(target.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
