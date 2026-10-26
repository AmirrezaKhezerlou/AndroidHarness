package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.ui.chat.components.FloatingSubagentDock
import com.androidharness.app.ui.subagent.SubagentScreen
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FloatingSubagentDeckDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun floatingDeckSwipesOpensRealChatAndKeepsSelectionAtNormalAndLargeText(): Unit = runBlocking {
        val service = instrumentation.uiAutomation.serviceInfo
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = service
        for ((scale, theme) in listOf(1f to ThemeMode.LIGHT, 1.5f to ThemeMode.DARK)) {
            val sid = c.sessions.createSession("Floating subagents fixture")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gate = CompletableDeferred<Unit>()
            var viewModel: ChatViewModel? = null
            val names = listOf("Build checker", "UI reviewer", "Test runner")
            val calls = names.mapIndexed { i, name -> ToolCallData("deck-$i-${System.nanoTime()}", "task",
                """{"name":"$name","prompt":"${listOf("Find why the APK build fails.", "Review the chat layout on a small screen.", "Run the focused regression tests.")[i]}"}""") }
            c.sessions.addMessage(sid, ChatMessage(Role.USER, "Check the build and review the UI."), "deck-turn")
            c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, text = "I’ll check the configuration while the subagents work.", toolCalls = calls), "deck-turn")
            val fixtureJobs = calls.mapIndexed { i, call ->
                val job = scope.launch {
                val run = SubagentRun(sid, "deck-turn", call, names[i],
                    listOf("Find why the APK build fails.", "Review the chat layout on a small screen.", "Run the focused regression tests.")[i],
                    "Fixture model", "fixture", false)
                c.runManager.subagents.foreground(run, { event ->
                    if (event is AgentEvent.SubagentMessageCommitted) c.sessions.addMessage(sid, event.message, "deck-turn")
                }) { events ->
                    events(AgentEvent.SubagentStep(call.id, if (i == 0) "Read app/build.gradle.kts" else "Read ChatScreen.kt"))
                    events(AgentEvent.SubagentStep(call.id, if (i == 0) "Check D8 and SDK paths" else "Inspect composer spacing"))
                    val update = if (i == 0) "Found the SDK configuration. Checking which build tools are available." else "Checking whether the controls leave enough room for messages."
                    events(AgentEvent.SubagentMessageCommitted(call.id, ChatMessage(Role.ASSISTANT, update, toolCallId = call.id)))
                    if (i == 0) gate.await() else if (i == 1) awaitCancellation()
                    ToolResult(true, if (i == 2) "12 focused tests passed." else "Build configuration checked.")
                }
                }
                withTimeout(10000) { c.runManager.subagents.forSession(sid).first { list -> list.any { it.id == call.id } } }
                job
            }
            try {
                withTimeout(15000) { c.runManager.subagents.forSession(sid).first { list -> list.size == 3 && list.first { it.id == calls[2].id }.status == SubagentStatus.COMPLETED } }
                instrumentation.runOnMainSync { viewModel = ChatViewModel(c, sid) }
                withTimeout(10000) { viewModel!!.state.first { it.messages.any { message -> message.toolCalls.size == 3 } } }
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                            HarnessTheme(theme, dynamicColor = false) {
                                var opened by remember { mutableStateOf<String?>(null) }
                                val holder = rememberSaveableStateHolder()
                                if (opened != null) SubagentScreen(c, sid, opened!!, onBack = { opened = null })
                                else holder.SaveableStateProvider("main-chat") {
                                    ChatScreen(viewModel!!, {}, { _, _ -> }, {}, {}, onOpenSubagent = { opened = it })
                                }
                            }
                        }
                    } }
                    await("LATEST ACTIVITY"); fits("Open chat"); screenshot("deck-$scale-expanded")
                    tap("Next subagent"); await("Review the chat layout on a small screen.")
                    gate.complete(Unit)
                    withTimeout(5000) { c.runManager.subagents.forSession(sid).first { list -> list.first { it.id == calls[0].id }.status == SubagentStatus.COMPLETED } }
                    await("Review the chat layout on a small screen.") // Completion must not switch pages.
                    tap("Open chat"); await("Results return to the main agent.")
                    await("Checking whether the controls leave enough room for messages.")
                    screenshot("deck-$scale-full-chat")
                    tap("Main chat"); await("LATEST ACTIVITY")
                    tap("Collapse subagent popup"); await("3 subagents")
                    screenshot("deck-$scale-collapsed")
                    tap("Expand subagent popup"); await("LATEST ACTIVITY")
                    screenshot("deck-$scale-restored")
                    val task = await("Review the chat layout on a small screen.")
                    val bounds = Rect().also(task::getBoundsInScreen)
                    swipe(bounds.centerY().toFloat())
                    await("Run the focused regression tests.")
                    fits("Open chat")
                    screenshot("deck-$scale-completed")
                }
            } finally {
                instrumentation.runOnMainSync { viewModel?.viewModelScope?.cancel() }
                c.runManager.subagents.stopSessionAndJoin(sid)
                fixtureJobs.forEach { it.cancel() }; fixtureJobs.joinAll(); scope.cancel()
                c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            }
        }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString()?.split('\n')?.contains(text) == true || node.contentDescription?.toString() == text)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::visit)
            ?: instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) { find(text)?.let { return it }; SystemClock.sleep(80) }
        error("Missing UI: $text")
    }
    private fun tap(text: String) {
        var node: AccessibilityNodeInfo? = await(text)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Could not tap $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync(); SystemClock.sleep(300)
    }
    private fun swipe(y: Float) {
        val width = c.appContext.resources.displayMetrics.widthPixels
        val start = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val event = MotionEvent.obtain(start, start + step * 18L,
                if (step == 0) MotionEvent.ACTION_DOWN else if (step == 12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                width * (.8f - .6f * step / 12), y, 0)
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(500)
    }
    private fun fits(text: String) {
        val bounds = Rect().also(await(text)::getBoundsInScreen)
        val display = c.appContext.resources.displayMetrics
        assertTrue("$text clipped: $bounds", bounds.left >= 0 && bounds.right <= display.widthPixels && bounds.top >= 0 && bounds.bottom <= display.heightPixels)
    }
    private fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(c.appContext.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
