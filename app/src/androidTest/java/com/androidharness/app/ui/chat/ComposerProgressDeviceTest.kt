package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.ui.LocalDesktopInput
import com.androidharness.app.ui.LocalDesktopLayout
import com.androidharness.app.ui.chat.components.MessageComposer
import com.androidharness.app.ui.chat.components.ToolCallCard
import com.androidharness.app.ui.common.PackageInstallProgress
import com.androidharness.app.ui.theme.HarnessTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ComposerProgressDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun composerKeepsItsHeightWhenTypingAndHandlesMultilineSelectionAtLargeFonts() {
        for (scale in listOf(1f, 1.5f)) {
            val value = mutableStateOf(TextFieldValue())
            val cursorColor = java.util.concurrent.atomic.AtomicInteger()
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale),
                        LocalDesktopLayout provides false, LocalDesktopInput provides false) {
                        HarnessTheme {
                            cursorColor.set(MaterialTheme.colorScheme.primary.toArgb())
                            Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                                    MessageComposer(false, value.value, null, { value.value = it }, {}, {}, {}, {}, {})
                                }
                            }
                        }
                    }
                } }
                assertTrue(awaitInput().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                SystemClock.sleep(500)
                val empty = bounds(awaitInput())
                screenshot("composer-${scale}-empty")
                setText("Agyp")
                await { value.value.text == "Agyp" }
                screenshot("composer-${scale}-single")
                val typed = bounds(awaitInput())
                assertEquals("Typing must preserve field height at font scale $scale", empty.height(), typed.height())
                assertEquals(empty.width(), typed.width())
                // The keyboard may move the entire viewport while its insets settle.
                assertRenderedCursorCentered(typed, cursorColor.get())
                setText("First line\nSecond line\nThird line")
                await { value.value.text.contains("Third line") }
                assertTrue(bounds(awaitInput()).height() > empty.height())
                instrumentation.runOnMainSync { value.value = value.value.copy(selection = TextRange(0, 5)) }
                await { value.value.selection == TextRange(0, 5) }
                val long = (1..15).joinToString("\n") { "Line $it café 😀" }
                setText(long)
                await { value.value.text == long }
                val longBounds = bounds(awaitInput())
                val density = context.resources.displayMetrics.density
                assertTrue("Composer must remain bounded", longBounds.height() <= (142 * density).toInt())
                instrumentation.runOnMainSync {
                    value.value = value.value.copy(selection = TextRange(long.length), composition = TextRange(long.length - 2, long.length))
                }
                await { value.value.composition != null }
                screenshot("composer-${scale}-multiline")
                setText("Agyp")
                await { value.value.text == "Agyp" }
                assertEquals(empty.height(), bounds(awaitInput()).height())
                assertEquals(empty.width(), bounds(awaitInput()).width())
            }
        }
    }

    @Test fun packageProgressUpdatesAndCompletedToolsStopAnimating() {
        val progress = mutableStateOf<EnvState>(EnvState.Preparing)
        val result = mutableStateOf<ChatMessage?>(null)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Linux packages", style = MaterialTheme.typography.titleMedium)
                        PackageInstallProgress(progress.value)
                        ToolCallCard(ToolCallData("ui-progress", "shell", "{\"command\":\"python3 check.py\"}"),
                            result.value, running = result.value == null, onOpenFile = { _, _ -> }, showLiveCode = false)
                    }
                }
            } } }
            awaitText("Preparing installation")
            instrumentation.runOnMainSync { progress.value = EnvState.Downloading(1, 4, "python", 2048, 8192) }
            awaitText("25%")
            awaitText("Downloading python")
            assertNull(find { it.text?.toString() == "Installed" })
            instrumentation.runOnMainSync { progress.value = EnvState.Downloading(1, 4, "python", 6144, 8192) }
            awaitText("75%")
            screenshot("package-download-wave-a")
            SystemClock.sleep(350)
            screenshot("package-download-wave-b")
            instrumentation.runOnMainSync { progress.value = EnvState.Installing(2, 4, "python") }
            awaitText("Installing python")
            awaitText("2 of 4 packages installed")
            awaitText("50%")
            screenshot("package-installing")
            instrumentation.runOnMainSync {
                progress.value = EnvState.Ready
                result.value = ChatMessage(Role.TOOL, "Completed", toolCallId = "ui-progress", toolName = "shell")
            }
            await { find { it.text?.toString()?.startsWith("Installing python") == true } == null }
            await { progressNodes() == 0 }
        }
    }

    private fun setText(text: String) {
        assertTrue(awaitInput().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        await { find { it.isEditable && it.text?.toString() == text } != null }
        SystemClock.sleep(100)
    }
    private fun awaitInput(): AccessibilityNodeInfo {
        var input: AccessibilityNodeInfo? = null
        await { input = find { it.isEditable }; input != null }
        return requireNotNull(input)
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun assertRenderedCursorCentered(bounds: Rect, color: Int) {
        // Measure pixels: accessibility character boxes omit the decorator's padding.
        val deadline = SystemClock.uptimeMillis() + 4000
        while (SystemClock.uptimeMillis() < deadline) {
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            var left = image.width; var top = image.height; var right = -1; var bottom = -1
            for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(image.height)) {
                for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(image.width)) {
                    if (image.getPixel(x, y) and 0xffffff == color and 0xffffff) {
                        left = minOf(left, x); right = maxOf(right, x)
                        top = minOf(top, y); bottom = maxOf(bottom, y)
                    }
                }
            }
            image.recycle()
            if (bottom > top && bottom - top > (right - left) * 4) {
                assertEquals("The visible cursor must be vertically centered in the single-line field", bounds.exactCenterY(),
                    (top + bottom + 1) / 2f, 2f * context.resources.displayMetrics.density)
                return
            }
            SystemClock.sleep(60) // Cursor may be in its blink-off interval.
        }
        fail("No visible caret found in the input viewport")
    }
    private fun awaitText(text: String) = await { find { it.text?.toString() == text } != null }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            if (condition()) return
            SystemClock.sleep(30)
        }
        fail("UI did not reach the expected state")
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun progressNodes(): Int {
        var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.rangeInfo != null) count++
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return count
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(80)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
