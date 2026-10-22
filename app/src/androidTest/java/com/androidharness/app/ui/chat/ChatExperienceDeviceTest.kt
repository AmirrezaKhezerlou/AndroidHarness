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
                chatOnlyTools = listOf(ReadChatTextTool(c.sessions::messages), OfferCodeHandoffTool()))
            val events = engine.run(chat, "chat-engine-turn", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT,
                "http://localhost", "fixture"), "", c.sessions.messages(chat), { PermissionMode.FULL_ACCESS }, mutableSetOf("read_file", "shell"),
                FileFs(root), RequestOptions(), 100_000, AgentMode.ACT, pinnedInstructions = "CODE_ONLY_PINNED",
                conversation = ConversationConfig(ConversationKind.CHAT)).toList()
            assertEquals(ChatExperience.tools, advertised.toSet())
            assertEquals(ChatExperience.SYSTEM_PROMPT, prompt)
            assertFalse(prompt.contains(root.path)); assertFalse(prompt.contains("CODE_ONLY_PINNED"))
            assertFalse(events.any { it is AgentEvent.EnvironmentNeeded || it is AgentEvent.ApprovalNeeded || it is AgentEvent.Error })
            val results = events.filterIsInstance<AgentEvent.ToolMessageCommitted>().map { it.message }
            assertTrue(results.single { it.toolName == "read_chat_text" }.text.contains("Brainstorm a small garden"))
            assertNotNull(results.single { it.toolName == "create_artifact" }.artifact)
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
                await("Thought for 3s"); await("Searched the web")
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

    @Test fun liveChatShowsReadableSearchActivityWithoutToolArguments(): Unit = runBlocking {
        val call = mutableStateOf("web_search")
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HarnessTheme {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
                    com.androidharness.app.ui.chat.components.ChatActivity(emptyList(), live = true,
                        runningCalls = if (call.value.isBlank()) emptyList() else listOf(ToolCallData("live", call.value, "RAW_ARGUMENTS")),
                        streamingThinking = "Compare the sources before answering.")
                }
            } } }
            await("Searching the web")
            assertNull(find("RAW_ARGUMENTS")); assertNull(find("Work summary"))
            screenshot("chat-search-shimmer")
            scenario.onActivity { call.value = "web_fetch" }
            await("Reading a web page")
            screenshot("chat-read-shimmer")
            scenario.onActivity { call.value = "" }
            await("Thinking")
            tap("Thinking"); await("Compare the sources before answering.")
            screenshot("chat-thinking-shimmer")
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
