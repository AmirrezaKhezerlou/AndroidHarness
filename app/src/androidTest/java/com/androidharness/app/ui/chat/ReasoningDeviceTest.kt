package com.androidharness.app.ui.chat

import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.components.*
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReasoningDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun migrationReopenBackupAndForkKeepReasoningWithoutLosingOldChats() = runBlocking {
        val name = "reasoning-${System.nanoTime()}.db"
        val importedName = "reasoning-import-${System.nanoTime()}.db"
        val backup = File(context.cacheDir, "$name.json")
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var imported: AppDatabase? = null
        try {
            var sessions = SessionRepository(db)
            val sid = sessions.createSession("Keep existing history")
            sessions.addMessage(sid, ChatMessage(Role.USER, "Existing question"), "turn")
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("ALTER TABLE messages DROP COLUMN generationJson")
                it.version = 17
            }
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_17_18).build()
            sessions = SessionRepository(db)
            assertEquals("Existing question", sessions.messages(sid).single().text)
            assertNull(sessions.messages(sid).single().generation.state)
            val c = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6.1-sol")
            val assistant = ChatMessage(Role.ASSISTANT, "Answer", thinking = "Visible summary", turnId = "turn")
            val state = ProviderMessageState.of(c, "responses",
                listOf(Json.parseToJsonElement("""{"type":"reasoning","id":"r1","encrypted_content":"fixture-opaque"}""").jsonObject)).boundTo(assistant)
            sessions.addMessage(sid, assistant.copy(generation = GenerationMetadata(state, 42, "Thinking: Max")), "turn")
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            sessions = SessionRepository(db)
            val restored = sessions.messages(sid).last()
            assertEquals("Answer", restored.text)
            assertEquals(42, restored.generation.reasoningTokens)
            assertTrue(restored.generation.state!!.matches(c, "responses", restored))
            val fork = sessions.forkSession(sid, restored.id!!)
            assertEquals(restored.generation, sessions.messages(fork).last().generation)
            ChatBackupManager(db, context).exportTo(Uri.fromFile(backup))
            imported = Room.databaseBuilder(context, AppDatabase::class.java, importedName).build()
            ChatBackupManager(imported, context).importFrom(Uri.fromFile(backup))
            assertEquals(restored.generation, SessionRepository(imported).messages(sid).last().generation)
            assertEquals(2, SessionRepository(imported).messages(sid).size)
        } finally {
            db.close(); imported?.close()
            context.deleteDatabase(name); context.deleteDatabase(importedName); backup.delete()
        }
    }

    @Test fun pickerShowsNativeLevelsAndAutomaticAndSeparatesReasoningFromTextTokens() {
        val original = ModelsDev.providers().associate { it.id to ModelsDev.modelsFor(it.id) }
        val originalProviders = ModelsDev.providers()
        ModelsDev.replaceForTesting(emptyMap())
        val selected = mutableStateOf(ThinkingLevel.MAX)
        val provider = mutableStateOf(ProviderConfig("fixture", "DeepSeek", ProviderType.OPENAI_COMPAT,
            "https://api.deepseek.com/v1", "deepseek-v4-flash"))
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { HarnessTheme {
                    Surface(Modifier.fillMaxSize()) { Column {
                        val resolved = ThinkingSpecs.resolve(provider.value, selected.value)
                        androidx.compose.runtime.CompositionLocalProvider(com.androidharness.app.ui.LocalDesktopLayout provides
                            (InstrumentationRegistry.getArguments().getString("desktop") == "true")) {
                        MainHeader("Reasoning controls", true, provider.value.model, AgentMode.ACT,
                            thinkingLevel = resolved.level, thinkingLevels = resolved.spec.levels,
                            permissionMode = PermissionMode.CONFIRM_ALL, canUndo = false,
                            onOpenDrawer = {}, onPickModel = {}, onOpenTerminal = {}, onSetThinking = { selected.value = it },
                            onSetPermission = {}, onSetMode = {}, onOpenContext = {}, onOpenUndo = {},
                            onOpenFiles = {}, onOpenGitHub = {}, thinkingResolved = resolved)
                        }
                        ThinkingBlock("Short summary", reportedTokens = 5_000, setting = "Thinking: Max")
                    } }
                } } }
                await("5K reasoning tokens", contains = true)
                tap("Thinking level")
                await("Low"); await("High"); await("Max")
                await("Applies to your next message")
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Ultra"))
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Medium"))
                tap("High")
                instrumentation.waitForIdleSync()
                assertEquals(ThinkingLevel.HIGH, selected.value)
                val dismissedBy = SystemClock.uptimeMillis() + 5_000
                while (find(instrumentation.uiAutomation.rootInActiveWindow, "Switch model…") != null && SystemClock.uptimeMillis() < dismissedBy)
                    SystemClock.sleep(50)
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Switch model…"))
                await("High")
                screenshot("native-levels")
                scenario.onActivity { provider.value = provider.value.copy(baseUrl = "https://custom.example/v1", model = "custom-model") }
                await("Thinking")
                tap("Thinking level")
                await("Reasoning support is unverified", contains = true)
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Low"))
                screenshot("automatic")
            }
        } finally { ModelsDev.replaceForTesting(original, originalProviders) }
    }

    @Test fun customEditorSavesNormalizedLevelsAndEvaluatesCelOnAndroid() = runBlocking {
        val prefs = SettingsRepository(context)
        val original = prefs.settings.first()
        val config = ProviderConfig("custom-reasoning-fixture", "DeepSeek", ProviderType.OPENAI_COMPAT,
            "https://api.deepseek.com/v1", "deepseek-v4-flash")
        val key = CustomReasoning.key(config)
        val custom = mutableStateOf<CustomReasoningConfig?>(CustomReasoningConfig(listOf("low", "HIGH", "max"),
            """{"thinking": {"type": "enabled"}, "reasoning_effort": reasoningLevel}"""))
        val selected = mutableStateOf("max")
        val saved = mutableStateOf(false)
        val editing = mutableStateOf(false)
        val githubOpens = mutableStateOf(0)
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { HarnessTheme {
                    Surface(Modifier.fillMaxSize()) { Column {
                        val options = RequestOptions(thinking = ThinkingLevel.HIGH,
                            customReasoning = custom.value?.let { mapOf(key to it) }.orEmpty(),
                            customThinkingLevels = mapOf(key to selected.value))
                        val resolved = options.resolveThinking(config)
                        androidx.compose.runtime.CompositionLocalProvider(com.androidharness.app.ui.LocalDesktopLayout provides
                            (InstrumentationRegistry.getArguments().getString("desktop") == "true")) {
                        MainHeader("Custom reasoning", false, "DeepSeek", AgentMode.ACT,
                            thinkingLevel = resolved.level, thinkingLevels = resolved.spec.levels,
                            permissionMode = PermissionMode.CONFIRM_ALL, canUndo = false,
                            onOpenDrawer = {}, onPickModel = {}, onOpenTerminal = {}, onSetThinking = {},
                            onSetPermission = {}, onSetMode = {}, onOpenContext = {}, onOpenUndo = {},
                            onOpenFiles = {}, onOpenGitHub = { githubOpens.value++ }, thinkingResolved = resolved,
                            onCustomizeThinking = { editing.value = true }, onSetCustomThinking = { selected.value = CustomReasoning.wire(it) })
                        }
                        if (editing.value) CustomReasoningSheet(config, custom.value, ThinkingSpecs.forProvider(config),
                            onDismiss = { editing.value = false }, onSave = { custom.value = it; saved.value = true; editing.value = false })
                    } }
                } } }
                await("Max")
                val actionLabel = if (InstrumentationRegistry.getArguments().getString("desktop") == "true") "Conversation actions" else "More options"
                val actionBounds = Rect().also { await("Open GitHub").getBoundsInScreen(it) }
                val menuBounds = Rect().also { await(actionLabel).getBoundsInScreen(it) }
                val thinkingBounds = Rect().also { await("Thinking level").getBoundsInScreen(it) }
                assertEquals(thinkingBounds.centerY().toDouble(), actionBounds.centerY().toDouble(), 12.0)
                assertTrue(actionBounds.left > thinkingBounds.right)
                if (InstrumentationRegistry.getArguments().getString("desktop") != "true") {
                    val filesBounds = Rect().also { await("Workspace files").getBoundsInScreen(it) }
                    assertEquals(filesBounds.centerY().toDouble(), menuBounds.centerY().toDouble(), 12.0)
                    assertTrue(menuBounds.bottom <= actionBounds.top)
                }
                screenshot("custom-header")
                tap("Open GitHub")
                instrumentation.waitForIdleSync()
                assertEquals(1, githubOpens.value)
                tap(actionLabel); tap("GitHub")
                instrumentation.waitForIdleSync()
                assertEquals(2, githubOpens.value)
                tap("Thinking level"); tap("Customize thinking")
                await("Reasoning levels (low to high)")
                await(CustomReasoning.LEVEL_HELP)
                screenshot("custom-editor")
                tap("Save")
                val until = SystemClock.uptimeMillis() + 15_000
                while (!saved.value && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
                assertTrue("CEL must compile and evaluate on Android before saving", saved.value)
                assertEquals(listOf("Low", "High", "Max"), custom.value!!.levels)
                prefs.setReasoningConfig(key, custom.value)
                assertEquals(custom.value, prefs.settings.first().reasoningConfigs[key])
                tap("Thinking level"); tap("High")
                val chosen = RequestOptions(customReasoning = mapOf(key to custom.value!!), customThinkingLevels = mapOf(key to selected.value))
                val request = OpenAiCompatProvider(okhttp3.OkHttpClient(), ProviderFactory.json)
                    .buildRequestBody(config, "fixture", emptyList(), emptyList(), chosen)
                assertEquals("high", request["reasoning_effort"]!!.jsonPrimitive.content)
                tap("Thinking level"); tap("Customize thinking"); tap("Use defaults")
                assertNull(custom.value)
            }
        } finally { prefs.restorePortable(original) }
    }

    @Test fun customLevelSelectionKeepsEachChatAndSharedScopeIndependent() = runBlocking {
        val prefs = SettingsRepository(context)
        val original = prefs.settings.first()
        val name = "custom-levels-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val key = CustomReasoning.key(ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://fixture.invalid/v1", "model"))
        try {
            val sessions = SessionRepository(db)
            val providers = (context.applicationContext as com.androidharness.app.HarnessApp).container.providers
            val chats = ChatModelRepository(prefs, providers, sessions)
            val a = sessions.createSession("Reasoning A")
            val b = sessions.createSession("Reasoning B")
            prefs.setModelSelectionScope(false)
            chats.initialize(a); chats.initialize(b)
            chats.setCustomThinking(a, key, "max")
            chats.setCustomThinking(b, key, "low")
            assertEquals("max", chats.observe(a).first().selection.customThinkingLevels[key])
            assertEquals("low", chats.observe(b).first().selection.customThinkingLevels[key])
            chats.setShared(a, true)
            assertEquals("max", prefs.settings.first().customThinkingLevels[key])
            chats.setCustomThinking(null, key, "high")
            assertEquals("high", chats.observe(b).first().selection.customThinkingLevels[key])
            chats.setShared(a, false)
            assertEquals("max", chats.observe(a).first().selection.customThinkingLevels[key])
            assertEquals("low", chats.observe(b).first().selection.customThinkingLevels[key])
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals("max", ChatModelSelection.decode(SessionRepository(db).session(a)!!.modelSelectionJson)!!.customThinkingLevels[key])
        } finally { db.close(); context.deleteDatabase(name); prefs.restorePortable(original) }
    }

    private fun find(node: AccessibilityNodeInfo?, text: String, contains: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        val values = listOf(node.text?.toString(), node.contentDescription?.toString())
        if (values.any { it != null && if (contains) it.contains(text, ignoreCase = true) else it == text }) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text, contains)?.let { return it }
        return null
    }
    private fun await(text: String, contains: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(instrumentation.uiAutomation.rootInActiveWindow, text, contains)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing UI: $text")
    }
    private fun tap(text: String) {
        var node: AccessibilityNodeInfo? = await(text)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        val bounds = Rect().also { await(text).getBoundsInScreen(it) }
        instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").close()
    }
    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "qa/reasoning").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
