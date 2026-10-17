package com.androidharness.app.ui.chat.rich

import androidx.activity.compose.setContent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.artifacts.ChatArtifact
import com.androidharness.app.core.*
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.ChatScreen
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.chat.components.ChatAppearance
import com.androidharness.app.ui.theme.HarnessTheme
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RichResponseDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val c get() = (context.applicationContext as HarnessApp).container

    @Test fun providerRichReplyPersistsInRealChatAndActionsOnlyPrepareComposerWhileArtifactsRemain() = runBlocking {
        val root = File(context.cacheDir, "rich-response-${System.nanoTime()}").apply { mkdirs() }
        val sid = c.sessions.createSession("Rich conversation", c.workspace.currentProjectOnce().id)
        val store = ViewModelStore()
        var requests = 0
        val answer = "Here are two approaches.\n\n" + ui(buildJsonObject {
            put("type", "choices"); put("title", "Choose your approach")
            putJsonArray("options") {
                add(buildJsonObject { put("label", "Local tools"); put("description", "Work offline with files on your phone."); put("prompt", "Help me use local tools.") })
                add(buildJsonObject { put("label", "Remote tools"); put("description", "Use a connected development machine.") })
            }
        }) + "\n\nBoth approaches stay in this conversation."
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                requests++
                assertTrue(systemPrompt.contains("RICH CONVERSATION RESPONSES"))
                assertTrue(tools.any { it.name == "create_artifact" })
                answer.chunked(37).forEach { emit(StreamEvent.TextDelta(it)) }
                emit(StreamEvent.Done("stop"))
            }
        }
        try {
            val oldArtifact = ChatArtifact.create("Existing artifact", "<p>Your saved artifact is still here.</p>")
            c.sessions.addMessage(sid, ChatMessage(Role.TOOL, "Saved concept", toolName = "create_artifact", artifact = oldArtifact), "old")
            val user = ChatMessage(Role.USER, "Compare local and remote approaches right in the reply.")
            c.sessions.addMessage(sid, user, "rich-turn")
            val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
            engine.run(sid, "rich-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "http://localhost", "fixture"), "", listOf(user),
                permissionMode = { PermissionMode.CONFIRM_RISKY }, sessionAllowedTools = mutableSetOf(), workspace = FileFs(root),
                options = RequestOptions(), maxContextTokens = 100_000, mode = AgentMode.ACT, repoMapEnabled = false).collect { event ->
                if (event is AgentEvent.AssistantCommitted) c.sessions.addMessage(sid, event.message, "rich-turn")
            }
            assertEquals(answer, c.sessions.messages(sid).last { it.role == Role.ASSISTANT }.text)
            assertEquals(listOf(oldArtifact), c.sessions.artifacts(sid))
            assertTrue(root.listFiles().orEmpty().isEmpty())
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    val vm = ChatViewModel(c, sid); store.put("chat", vm)
                    host.setContent { HarnessTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                tap("Remote tools"); tap("Discuss selection")
                await("Response · Choose your approach"); await("Tell me more about Remote tools.", contains = true)
                assertEquals("Actions do not send another provider request", 1, requests)
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "harness-ui", true))
                screenshot("rich-real-chat")
            }
        } finally { instrumentation.runOnMainSync { store.clear() }; c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }; root.deleteRecursively() }
    }

    @Test fun nativeContentAndCustomPreviewWorkInlineWithSelectionsThemeAndExpandedState() {
        val theme = mutableStateOf(ThemeMode.LIGHT)
        var followUp = RichResponse.FollowUp("", "", "")
        val text = showcase()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent { HarnessTheme(themeMode = theme.value, dynamicColor = false) { ChatAppearance {
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AssistantText(text, onFollowUp = { followUp = it })
                }
            } } } }
            await("A little clarity, right here")
            screenshot("rich-native-light")
            tap("Show data"); await("Research")
            tap("Why this plan?"); await("Short sessions make the next action easier to start.")
            tap("Add 5 minutes"); await("10 minutes")
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "PARENT EXPOSED", true))
            tap("Ask about this")
            assertTrue(followUp.context.contains("Break length: 10 minutes"))
            scenario.onActivity { host ->
                fun preview(view: android.view.View): com.androidharness.app.artifacts.ArtifactWebView? {
                    if (view is com.androidharness.app.artifacts.ArtifactWebView) return view
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) preview(view.getChildAt(i))?.let { return it }
                    return null
                }
                val view = requireNotNull(preview(host.window.decorView))
                assertTrue("Preview should size to its content", view.height > 0 && view.height / context.resources.displayMetrics.density < 600)
            }
            tap("Expand response preview"); await("10 minutes")
            screenshot("rich-preview-expanded")
            tap("Close response preview"); await("10 minutes")
            scenario.onActivity { theme.value = ThemeMode.DARK }
            await("5 minutes")
            screenshot("rich-preview-dark")
        }
    }

    @Test fun streamingKeepsIncompleteMarkupHiddenAndInvalidContentHasARecoveryAction() {
        val text = mutableStateOf("Before the visual.\n\n```harness-ui\n{\"type\":\"preview\",\"html\":\"<script>")
        val streaming = mutableStateOf(true)
        var followUp = RichResponse.FollowUp("", "", "")
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent { HarnessTheme { ChatAppearance {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) { AssistantText(text.value, streaming = streaming.value, onFollowUp = { followUp = it }) }
            } } } }
            await("Preparing visual…")
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Interactive response preview"))
            scenario.onActivity { streaming.value = false }
            await("This content couldn’t be displayed")
            tap("Ask for a text version"); assertTrue(followUp.prompt.contains("text explanation"))
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "<script>", true))
        }
    }

    @Test fun nativeActionsCheckboxStateAndLinkCardsWorkWithoutExecutingAgentActions() {
        var followUp = RichResponse.FollowUp("", "", "")
        var openedUrl = ""
        val text = listOf(
            buildJsonObject { put("type", "card"); put("title", "A practical starting point"); put("text", "Start with **one small task**."); putJsonArray("actions") {
                add(buildJsonObject { put("label", "Explore locally"); put("prompt", "Help me start locally.") })
            } },
            buildJsonObject { put("type", "steps"); put("title", "Your next steps"); putJsonArray("items") {
                add(buildJsonObject { put("title", "Pick a task"); put("text", "Keep the scope small.") })
                add(buildJsonObject { put("title", "Review the result"); put("text", "Check it before continuing.") })
            } },
            buildJsonObject { put("type", "link"); put("title", "Android reference"); put("text", "Read the platform documentation."); put("url", "https://developer.android.com/") },
        ).joinToString("\n\n", transform = ::ui)
        fun show(host: ChatListTestActivity) { host.setContent { HarnessTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) { ChatAppearance {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                AssistantText(text, onFollowUp = { followUp = it }, onOpenUrl = { openedUrl = it })
            }
        } } } }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity(::show)
            tap("Explore locally"); assertTrue(followUp.prompt.startsWith("Help me start locally."))
            tap("Pick a task"); tap("Continue from here")
            assertTrue(followUp.context.contains("Done: Pick a task")); assertTrue(followUp.context.contains("Remaining: Review the result"))
            scenario.recreate(); scenario.onActivity(::show)
            tap("Continue from here"); assertTrue("Checkbox state survives recreation", followUp.context.contains("Done: Pick a task"))
            assertEquals("", openedUrl)
            tap("Android reference"); assertEquals("https://developer.android.com/", openedUrl)
            screenshot("rich-actions-and-checklist")
        }
    }

    private fun showcase(): String = "A little clarity, right here\n\n" + listOf(
        buildJsonObject { put("type", "callout"); put("title", "Make room for focused work"); put("text", "One clear next step is easier to act on than a full dashboard.") },
        buildJsonObject { put("type", "metrics"); putJsonArray("items") {
            add(buildJsonObject { put("label", "Focus sessions"); put("value", "4"); put("detail", "Illustrative plan") })
            add(buildJsonObject { put("label", "Break length"); put("value", "5 min"); put("detail", "Adjust it below") })
        } },
        buildJsonObject { put("type", "chart"); put("title", "A balanced afternoon"); put("unit", "min"); put("caption", "Illustrative schedule"); putJsonArray("points") {
            add(buildJsonObject { put("label", "Research"); put("value", 20) }); add(buildJsonObject { put("label", "Create"); put("value", 40) }); add(buildJsonObject { put("label", "Review"); put("value", 15) })
        } },
        buildJsonObject { put("type", "details"); put("title", "Why this plan?"); put("text", "Short sessions make the next action easier to start.") },
        buildJsonObject { put("type", "preview"); put("title", "Try your break length"); put("html", """<h3 style="margin-top:0;font-weight:500">A moment to reset.</h3><p>Adjust the break to fit your day.</p><p id="value" role="status" style="font-size:28px">5 minutes</p><button onclick="let v=document.getElementById('value');let n=parseInt(v.textContent)+5;v.textContent=n+' minutes';parent.postMessage({type:'harness:state',summary:'Break length: '+n+' minutes'},'*')">Add 5 minutes</button><script>try{parent.document.body;document.body.append('PARENT EXPOSED')}catch(e){}</script>""") }
    ).joinToString("\n\n", transform = ::ui) + "\n\nTell me what you’d like to adjust."
    private fun ui(body: JsonObject) = "```harness-ui\n$body\n```"
    private fun find(node: AccessibilityNodeInfo?, text: String, contains: Boolean = false): AccessibilityNodeInfo? {
        node ?: return null; node.refresh()
        if (node.isVisibleToUser && listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).any { if (contains) it.contains(text) else it == text }) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text, contains)?.let { return it }
        return null
    }
    private fun await(text: String, contains: Boolean = false): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 12_000
        var attempts = 0
        fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) scrollable(node.getChild(i))?.let { return it }
            return null
        }
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            find(root, text, contains)?.let { return it }
            if (++attempts > 4) scrollable(root)?.performAction(if ((attempts / 7) % 2 == 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(160)
        } while (SystemClock.uptimeMillis() < end)
        screenshot("rich-failure"); throw AssertionError("Missing: $text")
    }
    private fun tap(text: String) {
        await(text).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(180)
        var node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Tap $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(180)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(400)
        val dir = File(context.getExternalFilesDir(null), "rich-response-qa").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
