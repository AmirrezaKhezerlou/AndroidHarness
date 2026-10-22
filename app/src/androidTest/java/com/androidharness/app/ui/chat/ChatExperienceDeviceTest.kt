package com.androidharness.app.ui.chat

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.agent.*
import com.androidharness.app.llm.*
import com.androidharness.app.tools.*
import com.androidharness.app.workspace.FileFs
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatExperienceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun chatEngineKeepsToolsAutomaticAndRejectsProjectToolsWithoutEnvironmentSetup(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "chat-engine-${System.nanoTime()}").apply { mkdirs() }
        val chat = c.sessions.createSession("New chat", conversation = ConversationConfig(ConversationKind.CHAT))
        var round = 0
        var advertised = emptyList<String>()
        var prompt = ""
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                advertised = tools.map { it.name }; prompt = systemPrompt
                if (round++ == 0) emit(StreamEvent.ToolCallBatch(listOf(
                    ToolCallData("text", "read_chat_text", "{}"),
                    ToolCallData("artifact", "create_artifact", "{\"title\":\"Idea\",\"html\":\"<html><body>Idea preview</body></html>\"}"),
                    ToolCallData("blocked-file", "read_file", "{\"path\":\"/private\"}"),
                    ToolCallData("blocked-shell", "shell", "{\"command\":\"id\"}"))))
                else emit(StreamEvent.TextDelta("Here is the idea and its visual."))
                emit(StreamEvent.Done(if (round == 1) "tool_calls" else "stop"))
            }
        }
        try {
            c.sessions.addMessage(chat, ChatMessage(Role.USER, "Brainstorm a small garden"))
            val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills,
                cavemanSettings = { AppSettings(cavemanInstalled = true) },
                universalMemory = c.memory,
                chatOnlyTools = listOf(ReadChatTextTool(c.sessions::messages), OfferCodeHandoffTool()))
            val events = engine.run(chat, "chat-engine-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT,
                "http://localhost", "fixture"), "", c.sessions.messages(chat), { PermissionMode.FULL_ACCESS }, mutableSetOf("read_file", "shell"),
                FileFs(root), RequestOptions(), 100_000, AgentMode.ACT, pinnedInstructions = "CODE_ONLY_PINNED",
                conversation = ConversationConfig(ConversationKind.CHAT)).toList()
            assertEquals(ChatExperience.tools, advertised.toSet())
            assertTrue(prompt.startsWith(ChatExperience.SYSTEM_PROMPT))
            assertTrue(prompt.contains("Current local date: ${java.time.LocalDate.now()}"))
            assertFalse(prompt.contains(root.path)); assertFalse(prompt.contains("CODE_ONLY_PINNED"))
            assertFalse(events.any { it is AgentEvent.EnvironmentNeeded || it is AgentEvent.ApprovalNeeded || it is AgentEvent.Error })
            val results = events.filterIsInstance<AgentEvent.ToolMessageCommitted>().map { it.message }
            assertTrue(results.single { it.toolName == "read_chat_text" }.text.contains("Brainstorm a small garden"))
            assertTrue(results.single { it.toolName == "create_artifact" }.isError)
            assertTrue(results.none { it.artifact != null })
            assertFalse(advertised.any { it in setOf("create_artifact", "read_artifact") })
            assertTrue(results.single { it.toolName == "read_file" }.isError)
            assertTrue(results.single { it.toolName == "shell" }.isError)
        } finally { c.sessions.session(chat)?.let { c.sessions.deleteSession(it) }; root.deleteRecursively() }
    }

    @Test fun chatHasQuietThoughtsNoRawResultsAndOnlyConditionalCodeHandoff(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val project = c.workspace.currentProjectOnce()
        val chat = c.sessions.createSession("New chat", project.id, ConversationConfig(ConversationKind.CHAT))
        val code = c.sessions.createSession("Code comparison", project.id)
        val stores = mutableListOf<ViewModelStore>()
        val selected = mutableStateOf(ConversationKind.CHAT)
        val scale = mutableStateOf(1f)
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val turn = "chat-experience-fixture"
        for (sid in listOf(chat, code)) {
            c.sessions.addMessage(sid, ChatMessage(Role.USER, "Help me brainstorm an idea"), turn)
            c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, thinking = "Consider the audience and keep the first experiment small.", thinkingMs = 3000,
                toolCalls = listOf(ToolCallData("search", "web_search", "{\"query\":\"brainstorming\"}"))), turn)
            c.sessions.addMessage(sid, ChatMessage(Role.TOOL, "RAW_PRIVATE_TOOL_OUTPUT", toolCallId = "search", toolName = "web_search"), turn)
            c.sessions.addMessage(sid, ChatMessage(Role.ASSISTANT, "Start with one small idea you can test this week."), turn)
        }
        try {
            assertEquals("Help me brainstorm an idea", c.sessions.session(chat)?.title)
            assertFalse(c.sessions.searchMessages("RAW_PRIVATE_TOOL_OUTPUT", true).any { it.sessionId == chat })
            assertTrue(c.sessions.searchMessages("RAW_PRIVATE_TOOL_OUTPUT", true).any { it.sessionId == code })
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val chatVm = ChatViewModel(c, chat); val codeVm = ChatViewModel(c, code)
                    stores += ViewModelStore().apply { put("chat", chatVm); put("code", codeVm) }
                    activity.setContent { HarnessTheme(themeMode = theme.value, dynamicColor = false) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                            key(selected.value) { ChatScreen(if (selected.value == ConversationKind.CHAT) chatVm else codeVm,
                                {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) }
                        }
                    } }
                }
                await("Start with one small idea you can test this week.")
                await("Thought for 3s")
                assertNull(find("Searched the web"))
                assertNull(find("Work summary")); assertNull(find("RAW_PRIVATE_TOOL_OUTPUT"))
                assertNull(find("Chat tools off")); assertNull(find("Continue in Code"))
                screenshot("chat-reply")
                tap("Thought for 3s"); await("Consider the audience and keep the first experiment small.")
                screenshot("chat-thoughts-expanded")
                scenario.onActivity { scale.value = 1.5f; theme.value = ThemeMode.DARK }
                await("Consider the audience and keep the first experiment small.")
                screenshot("chat-thoughts-large-dark")
                scenario.onActivity { scale.value = 1f; theme.value = ThemeMode.LIGHT }
                tap("Thought for 3s")
                val count = c.sessions.sessions.first().size
                val offerTurn = "chat-offer-fixture"
                c.sessions.addMessage(chat, ChatMessage(Role.USER, "Apply the chosen idea to my project"), offerTurn)
                c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT,
                    toolCalls = listOf(ToolCallData("offer", "offer_code_handoff", "{\"reason\":\"Project edits need Code\"}"))), offerTurn)
                c.sessions.addMessage(chat, ChatMessage(Role.TOOL, "Offered", toolCallId = "offer", toolName = "offer_code_handoff"), offerTurn)
                c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT, "You can continue in Code to apply the changes."), offerTurn)
                await("Continue in Code")
                assertEquals("Offering does not create a Code conversation", count, c.sessions.sessions.first().size)
                tap("Continue in Code"); await("Create Code conversation")
                assertEquals(count, c.sessions.sessions.first().size)
                tap("Cancel")
                scenario.onActivity { selected.value = ConversationKind.CODE }
                await("Work summary")
                screenshot("code-summary-preserved")
            }
        } finally {
            instrumentation.runOnMainSync { stores.forEach { it.clear() } }
            for (id in listOf(chat, code)) c.sessions.session(id)?.let { c.sessions.deleteSession(it) }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun firstMessageNamesChatAndRepairsLegacyPlaceholdersWithoutChangingCodeOrCustomTitles(): Unit = runBlocking {
        val project = c.workspace.currentProjectOnce()
        val chat = c.sessions.createSession("New chat", project.id, ConversationConfig(ConversationKind.CHAT))
        val custom = c.sessions.createSession("My chosen title", project.id, ConversationConfig(ConversationKind.CHAT))
        val code = c.sessions.createSession("New chat", project.id)
        try {
            for (id in listOf(chat, custom, code)) c.sessions.addMessage(id, ChatMessage(Role.USER, "Plan a garden"))
            assertEquals("Plan a garden", c.sessions.session(chat)?.title)
            assertEquals("My chosen title", c.sessions.session(custom)?.title)
            assertEquals("New chat", c.sessions.session(code)?.title)
            c.sessions.renameSession(chat, "New chat")
            c.sessions.repairChatTitles()
            assertEquals("Plan a garden", c.sessions.session(chat)?.title)
            c.sessions.addMessage(chat, ChatMessage(Role.USER, "Second message"))
            assertEquals("Plan a garden", c.sessions.session(chat)?.title)
        } finally { for (id in listOf(chat, custom, code)) c.sessions.session(id)?.let { c.sessions.deleteSession(it) } }
    }

    @Test fun expandedThinkingFollowsNewActivityAndReopensAtTheLatestEntry(): Unit = runBlocking {
        val messages = mutableStateOf(emptyList<ChatMessage>())
        val thought = mutableStateOf("Initial thought")
        val live = mutableStateOf(true)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
                    com.androidharness.app.ui.chat.components.ChatActivity(messages.value, live = live.value, streamingThinking = thought.value)
                }
            } } }
            await("Thinking"); assertNull(find("Initial thought"))
            tap("Thinking"); await("Initial thought")
            for (index in 1..12) {
                scenario.onActivity {
                    val call = ToolCallData("search-$index", if (index % 2 == 0) "web_fetch" else "web_search", "RAW_ARGUMENTS")
                    messages.value = messages.value + ChatMessage(Role.ASSISTANT, "Progress update $index", thinking = "Thinking round $index", toolCalls = listOf(call)) +
                        ChatMessage(Role.TOOL, "RAW_RESULTS", toolCallId = call.id)
                    thought.value = "Latest thought $index"
                }
                await("Latest thought $index")
            }
            await("Progress update 12")
            assertNull(find("Initial thought")); assertNull(find("RAW_ARGUMENTS")); assertNull(find("RAW_RESULTS"))
            val latest = android.graphics.Rect().also { await("Latest thought 12").getBoundsInScreen(it) }
            assertTrue("The latest thought is within the visible inner viewport", latest.top > 0 && latest.bottom < instrumentation.targetContext.resources.displayMetrics.heightPixels)
            screenshot("chat-nested-activity-auto-follow")
            tap("Thinking"); awaitGone("Latest thought 12")
            tap("Thinking"); await("Latest thought 12")
            scenario.onActivity { live.value = false }
            await("Thoughts"); await("Latest thought 12")
            screenshot("chat-nested-completed-latest")
        }
    }

    @Test fun commentarySurvivesEveryToolRoundAndSourcesOpenTheReturnedPage(): Unit = runBlocking {
        val saved = c.settings.settings.first()
        val project = c.workspace.currentProjectOnce()
        val chat = c.sessions.createSession("New chat", project.id, ConversationConfig(ConversationKind.CHAT))
        val store = ViewModelStore()
        val turn = "news-commentary-turn"
        val intro = "Let me look at the news today."
        val followup = "I found the report. I will read it before summarizing."
        val search = ToolCallData("news-search", "web_search", "{\"query\":\"news today\"}")
        val fetch = ToolCallData("news-read", "web_fetch", "{\"url\":\"https://example.com/report\"}")
        try {
            c.sessions.addMessage(chat, ChatMessage(Role.USER, "What is the news today?"), turn)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = ChatViewModel(c, chat); store.put("chat", vm)
                    activity.setContent { HarnessTheme(dynamicColor = false) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                await("What is the news today?")
                c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT, intro, thinking = "Check the date and use reliable sources.", thinkingMs = 1000, toolCalls = listOf(search)), turn)
                await("Thought for 1s")
                assertNull(find(intro))
                tap("Thought for 1s"); await(intro)
                c.sessions.addMessage(chat, ChatMessage(Role.TOOL, "1. Example report\n   https://example.com/report\n   RAW_SEARCH_SNIPPET", toolCallId = search.id, toolName = search.name), turn)
                await("Searched the web"); await(intro)
                c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT, followup, thinking = "Use the original report for detail.", thinkingMs = 2000, toolCalls = listOf(fetch)), turn)
                await("Thought for 3s"); await(followup)
                c.sessions.addMessage(chat, ChatMessage(Role.TOOL, "RAW_PAGE_CONTENT", toolCallId = fetch.id, toolName = fetch.name), turn)
                c.sessions.addMessage(chat, ChatMessage(Role.ASSISTANT, "The report describes a new discovery.\n{\"type\":\"callout\",\"tone\":\"info\",\"title\":\"Today’s lead story\",\"text\":\"A new discovery was announced.\"}\n[Example report](https://example.com/report)"), turn)
                await("Read web pages"); await(followup); await("Today’s lead story")
                assertNull(find("{\"type\":\"callout\""))
                screenshot("chat-nested-chronological-rich-reply")
                tap("Thought for 3s")
                awaitGone(intro); awaitGone(followup); awaitGone("Read web pages")
                await("Today’s lead story")
                var sourceNode = await("Sources · 1")
                while (!sourceNode.isClickable && sourceNode.parent != null) sourceNode = sourceNode.parent
                val sourceBounds = android.graphics.Rect().also { sourceNode.getBoundsInScreen(it) }
                val answerBounds = android.graphics.Rect().also { await("The report describes a new discovery.").getBoundsInScreen(it) }
                assertTrue("Sources align with the answer", sourceBounds.left <= answerBounds.left + 4)
                assertEquals(1, nodes().count { it.isVisibleToUser && it.text?.toString() == "Sources · 1" })
                assertNull(find("RAW_SEARCH_SNIPPET")); assertNull(find("RAW_PAGE_CONTENT")); assertNull(find("Work summary"))
                screenshot("chat-nested-collapsed-reply")
                tap("Sources · 1"); await("Example report"); await("Read page")
                screenshot("chat-news-sources")
                // Recreate the screen: commentary and source provenance survive persisted history.
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                scenario.recreate()
                scenario.onActivity { activity ->
                    val vm = ChatViewModel(c, chat); store.put("chat-recreated", vm)
                    activity.setContent { HarnessTheme(dynamicColor = false) { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                await("Thought for 3s"); await("Sources · 1")
                assertNull(find(followup))
                tap("Thought for 3s"); await(followup); await("Read web pages")
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.sessions.session(chat)?.let { c.sessions.deleteSession(it) }
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
        }
    }

    @Test fun individualToolRowsFinishWithoutMovingAndSourcesAreCompact(): Unit = runBlocking {
        val result = mutableStateOf<ChatMessage?>(null)
        val search = ToolCallData("phase-search", "web_search", "RAW_ARGUMENTS")
        var opened = ""
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme(dynamicColor = false) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
                    com.androidharness.app.ui.chat.components.ChatToolActivity(search, result.value, result.value == null) { opened = it }
                    if (result.value != null) com.androidharness.app.ui.chat.components.ChatSources(listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(search)), result.value!!)) { opened = it }
                }
            } } }
            await("Searching the web")
            scenario.onActivity { result.value = ChatMessage(Role.TOOL, "1. Example report\n   https://example.com/report\n   RAW_SNIPPET", toolCallId = search.id) }
            await("Searched the web"); await("Sources · 1")
            assertNull(find("RAW_ARGUMENTS")); assertNull(find("RAW_SNIPPET")); assertNull(find("example.com"))
            tap("Sources · 1"); await("Example report"); await("Found in search")
            tap("Example report"); assertEquals("https://example.com/report", opened)
            tap("Searched the web"); await("Example report")
            screenshot("chat-compact-sources-and-tool-result")
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private fun await(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do { find(text)?.let { return it }; SystemClock.sleep(100) } while (SystemClock.uptimeMillis() < deadline)
        screenshot("failure"); throw AssertionError("Expected: $text; visible: ${nodes().mapNotNull { it.text?.toString() }}")
    }
    private fun awaitGone(text: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do { if (find(text) == null) return; SystemClock.sleep(50) } while (SystemClock.uptimeMillis() < deadline)
        screenshot("failure"); throw AssertionError("Still visible after collapse: $text")
    }
    private fun tap(text: String) {
        var node = await(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        val folder = File(c.appContext.getExternalFilesDir(null), "chat-experience").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()!!.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
