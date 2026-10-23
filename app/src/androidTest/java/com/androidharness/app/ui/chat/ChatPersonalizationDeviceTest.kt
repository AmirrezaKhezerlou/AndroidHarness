package com.androidharness.app.ui.chat

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.core.ChatExperience
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.*
import com.androidharness.app.ui.chat.components.EmptyState
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatPersonalizationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun emptyChatChoicePersistsHidesAfterMessageAndNewChatDefaultsToOn(): Unit = runBlocking {
        val sid = c.sessions.createSession("Personalization fixture", conversation = ConversationConfig(ConversationKind.CHAT,
            instructions = "Keep this instruction", imageFrequency = ImageFrequency.MORE))
        val saved = c.settings.settings.first()
        val store = ViewModelStore()
        lateinit var vm: ChatViewModel
        fun display(scenario: ActivityScenario<ChatListTestActivity>, id: String?) {
            scenario.onActivity { host ->
                store.clear()
                vm = ChatViewModel(c, id, ConversationKind.CHAT)
                store.put("chat", vm)
                host.setContent { HarnessTheme { ChatScreen(viewModel = vm, onOpenDrawer = {}, onOpenFile = { _, _ -> }, onNewChat = {},
                        onOpenTerminal = {}, onOpenSubagent = {}) } }
            }
        }
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                display(scenario, sid)
                await("Personalization on")
                waitFor { vm.state.value.conversationReady }
                val control = Rect().also { await("Personalization on").getBoundsInScreen(it) }
                val headline = Rect().also { await("What is on your mind?").getBoundsInScreen(it) }
                assertTrue("Personalization must sit above the empty Chat headline", control.bottom < headline.top)
                assertTrue(checked("Personalization on"))
                tap("Personalization on")
                await("Personalization off")
                waitFor { !vm.state.value.conversationSaving }
                val config = ConversationConfig.decode(c.sessions.session(sid)!!.conversationJson)
                assertFalse(config.personalizationEnabled)
                assertEquals("Keep this instruction", config.instructions)
                assertEquals(ImageFrequency.MORE, config.imageFrequency)
                assertTrue(c.sessions.messages(sid).isEmpty())
                display(scenario, sid)
                await("Personalization off")
                assertFalse(checked("Personalization off"))
                screenshot("empty-chat-personalization-off")
                c.sessions.addMessage(sid, ChatMessage(Role.USER, "First message in this Chat"))
                await("First message in this Chat")
                waitFor { find("Personalization off") == null && find("Personalization on") == null }
                display(scenario, null)
                await("Personalization on")
                assertTrue(checked("Personalization on"))
                assertTrue(vm.state.value.conversation.personalizationEnabled)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun imageDefaultShowsAndTracksAppSettingWithoutCopyingItIntoChat(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val sid = c.sessions.createSession("Image inheritance fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        val store = ViewModelStore()
        lateinit var vm: ChatViewModel
        try {
            c.settings.setChatImageFrequency(ImageFrequency.ALWAYS)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    vm = ChatViewModel(c, sid); store.put("chat", vm)
                    host.setContent { HarnessTheme { ChatScreen(viewModel = vm, onOpenDrawer = {}, onOpenFile = { _, _ -> }, onNewChat = {},
                        onOpenTerminal = {}, onOpenSubagent = {}) } }
                }
                await("Personalization on")
                tap("More options"); tap("Chat settings")
                await("Use app setting (Always)")
                assertTrue(checked("Use app setting (Always)"))
                var config = ConversationConfig.decode(c.sessions.session(sid)!!.conversationJson)
                assertEquals(ImageFrequency.DEFAULT, config.imageFrequency)
                assertEquals(ImageFrequency.ALWAYS, ChatExperience.effectiveImageFrequency(config.imageFrequency,
                    c.settings.settings.first().chatImageFrequency))
                c.settings.setChatImageFrequency(ImageFrequency.NONE)
                await("Use app setting (No image)")
                assertTrue(checked("Use app setting (No image)"))
                tap("More often"); tap("Save")
                waitFor { !vm.state.value.conversationSaving && find("Images in this chat") == null }
                config = ConversationConfig.decode(c.sessions.session(sid)!!.conversationJson)
                assertEquals(ImageFrequency.MORE, config.imageFrequency)
                assertEquals(ImageFrequency.NONE, c.settings.settings.first().chatImageFrequency)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.settings.setChatImageFrequency(saved.chatImageFrequency)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun switchStaysReachableAtLargeTextAndNeverAppearsInCode() {
        val chat = mutableStateOf(true)
        val enabled = mutableStateOf(true)
        val desktop = mutableStateOf(false)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f),
                    com.androidharness.app.ui.LocalDesktopLayout provides desktop.value) {
                    HarnessTheme { Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
                            EmptyState(true, {}, {}, chatOnly = chat.value, personalizationEnabled = enabled.value,
                                onPersonalizationChange = { enabled.value = it })
                        }
                    } }
                }
            } }
            await("Personalization on")
            tap("Personalization on"); await("Personalization off")
            assertFalse(enabled.value)
            screenshot("empty-chat-large-text")
            scenario.onActivity { desktop.value = true }
            tap("Personalization off"); await("Personalization on")
            assertTrue(enabled.value)
            scenario.onActivity { chat.value = false }
            await("What do you want to build?")
            assertNull(find("Personalization on")); assertNull(find("Personalization off"))
        }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (!predicate() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
        assertTrue(predicate())
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser &&
        (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(text: String): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 15_000
        do { find(text)?.let { return it }; SystemClock.sleep(100) } while (SystemClock.uptimeMillis() < until)
        screenshot("failure")
        throw AssertionError("Missing $text: ${nodes().mapNotNull { it.text }}")
    }
    private fun checked(text: String): Boolean {
        var node = await(text)
        repeat(6) { if (node.isChecked || node.isSelected) return true; node = node.parent ?: return false }
        return false
    }
    private fun tap(text: String) {
        repeat(10) {
            if (find(text) == null) {
                nodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(100)
            }
        }
        var node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        val folder = File(c.appContext.getExternalFilesDir(null), "chat-personalization").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
