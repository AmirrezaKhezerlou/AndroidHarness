package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.ui.LocalDesktopLayout
import com.androidharness.app.ui.LocalDesktopInput
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProviderManagementDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container
    private val desktop get() = InstrumentationRegistry.getArguments().getString("desktop") == "true"
    private val dark get() = InstrumentationRegistry.getArguments().getString("dark") == "true"

    @Test fun inlineHeadersEditPersistDisableAndRemoveThroughProviderSettings() = runBlocking {
        val settings = c.settings.settings.first()
        val server = java.net.ServerSocket(0)
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val serverThread = kotlin.concurrent.thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    received += buildString {
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; appendLine(line) }
                    }
                    val body = """{"data":[{"id":"header-model"}]}"""
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                }
            } catch (_: java.net.SocketException) { }
        }
        val provider = c.providers.add("Headers fixture", ProviderType.OPENAI_COMPAT,
            "http://127.0.0.1:${server.localPort}", "header-model", "header-fixture-api-key",
            listOf(ProviderHeader("X-Client-Type", "original-fixture-header")))
        val saved = AtomicReference<ProviderConfig?>()
        val observed = AtomicReference<ProviderConfig?>()
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            c.providers.providers.collect { observed.set(it.firstOrNull { item -> item.id == provider.id }) }
        }
        try {
            c.settings.setAllowScreenshots(true)
            await { observed.get() != null }
            fun open(existing: ProviderConfig): ActivityScenario<ChatListTestActivity> =
                ActivityScenario.launch(ChatListTestActivity::class.java).also { scenario ->
                    scenario.onActivity { activity -> activity.setContent { theme {
                        val scope = rememberCoroutineScope()
                        ProviderSheetContent(existing, "header-fixture-api-key", {},
                            onSave = { name, type, url, model, key, headers -> scope.launch {
                                val updated = existing.copy(name = name, type = type, baseUrl = url, model = model, headers = headers)
                                c.providers.update(updated, key)
                                saved.set(updated)
                            } })
                    } } }
                }
            open(provider).use {
                await { find("Connect Custom endpoint") != null }
                scrollTo("Add header"); tap("Add header")
                editHeader("Header 2 name", "X-Disabled")
                editHeader("Header 2 value", "disabled-fixture-value")
                hideKeyboard()
                tap("Enable header 2")
                tap("Add header"); hideKeyboard(); tap("Remove header 3")
                editHeader("Header 1 value", "edited-fixture-header")
                hideKeyboard()
                scrollTo("Custom headers")
                screenshot("provider-inline-headers")
                assertVisibleWithinWindow("Continue")
                tap("Continue")
                await { find("Choose model") != null }
                tap("Save")
                await { saved.get() != null && observed.get()?.headers?.firstOrNull()?.value == "edited-fixture-header" }
            }
            val loaded = c.providers.providers.first().single { it.id == provider.id }
            assertEquals(listOf(ProviderHeader("X-Client-Type", "edited-fixture-header"),
                ProviderHeader("X-Disabled", "disabled-fixture-value", false)), loaded.headers)
            assertEquals(loaded.headers, KeyStoreManager(c.appContext).providerHeaders(provider.id))
            assertEquals(loaded.headers, c.providers.resolveHarnessConfig(loaded.copy(headers = emptyList())).headers)
            assertThrows(IllegalStateException::class.java) { runBlocking {
                c.providers.resolveHarnessConfig(loaded.copy(baseUrl = "https://other.example/v1", headers = emptyList()))
            } }
            assertTrue(received.last().contains("X-Client-Type: edited-fixture-header"))
            assertFalse(received.last().contains("X-Disabled", ignoreCase = true))
            val metadata = File(c.appContext.filesDir, "datastore/providers.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(metadata.contains("edited-fixture-header"))
            saved.set(null)
            open(loaded).use {
                await { find("Connect Custom endpoint") != null }
                scrollTo("Show header 1 value"); tap("Show header 1 value")
                await { find("edited-fixture-header") != null }
                tap("Remove header 2"); tap("Remove header 1")
                tap("Continue"); await { find("Choose model") != null }; tap("Save")
                await { saved.get() != null && observed.get()?.headers?.isEmpty() == true }
            }
            assertTrue(KeyStoreManager(c.appContext).providerHeaders(provider.id).isEmpty())
            assertFalse(received.last().contains("X-Client-Type", ignoreCase = true))
        } finally {
            collector.cancel()
            c.providers.delete(provider.id)
            c.settings.restorePortable(settings)
            server.close(); serverThread.join(1000)
        }
    }

    private fun editHeader(label: String, value: String) {
        scrollTo(label)
        // Compose exposes the description as a child of the native EditText.
        val input = checkNotNull(generateSequence(requireNotNull(find(label))) { it.parent }
            .firstOrNull { node -> node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } })
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.waitForIdleSync()
    }

    private fun hideKeyboard() {
        if (instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun providerScreenReordersHidesSearchesAndPersistsWithoutChangingExistingSelection() = withProviders { first, second ->
        runBlocking { c.settings.setActiveSelection(first.id, "shared-model") }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { theme { ProvidersScreen(c, {}) } } }
            await { find("Search providers or models") != null }
            search("Fixture")
            scrollTo("Select ${first.name}")
            tap("Actions for ${first.name}"); tap("Move down")
            await { runBlocking { c.providers.providers.first().filter { it.id in setOf(first.id, second.id) }.first().id == second.id } }
            scrollTo("Select ${second.name}")
            if (find("Select ${first.name}") != null) {
                assertTrue(bounds(requireNotNull(find("Select ${second.name}"))).top < bounds(requireNotNull(find("Select ${first.name}"))).top)
            }
            screenshot("providers-reordered")
            tap("Enable ${first.name}")
            await { runBlocking { c.providers.providers.first().first { it.id == first.id }.enabled == false } }
            search("")
            await { find("Select ${first.name}") == null }
            assertEquals(first.id, runBlocking { c.settings.settings.first().activeProviderId })
            assertEquals("shared-model", runBlocking { c.settings.settings.first().activeModel })
            val reloaded = runBlocking { SettingsRepository(c.appContext).settings.first() }
            assertTrue(first.id in reloaded.disabledProviderIds)
            assertTrue(reloaded.providerOrder.indexOf(second.id) < reloaded.providerOrder.indexOf(first.id))
            search("Fixture Primary")
            scrollTo("Enable & use")
            assertVisibleWithinWindow("Enable & use")
            screenshot("providers-disabled-search")
            tap("Enable & use")
            await { runBlocking { first.id !in c.settings.settings.first().disabledProviderIds } }
            search("no-provider-or-model-matches")
            await { find("No matching providers") != null }
            screenshot("providers-empty-search")
        }
    }

    @Test fun managerSheetReordersAndRestoresDisabledProviders() = withProviders { first, second ->
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { theme {
                val providers by c.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
                val scope = rememberCoroutineScope()
                ProviderManagerSheet(providers, first.id, c.providers::apiKey, {}, {},
                    onDelete = {},
                    onEnabledChange = { id, enabled -> scope.launch { c.providers.setEnabled(id, enabled) } },
                    onMove = { id, direction, visible -> scope.launch { c.providers.move(id, direction, visible) } },
                    onSave = { _, _, _, _, _, _, _ -> })
            } } }
            await { find("Search providers") != null }
            search("Fixture")
            scrollTo("Actions for ${second.name}")
            tap("Actions for ${second.name}"); tap("Move up")
            await { runBlocking { c.providers.providers.first().filter { it.id in setOf(first.id, second.id) }.first().id == second.id } }
            tap("Enable ${second.name}")
            await { runBlocking { second.id in c.settings.settings.first().disabledProviderIds } }
            search("")
            await { find("Select ${second.name}") == null }
            search("Fixture Secondary")
            scrollTo("Enable & use")
            screenshot("providers-manager-disabled")
            tap("Enable & use")
            await { runBlocking { second.id !in c.settings.settings.first().disabledProviderIds } }
        }
    }

    @Test fun unifiedSearchKeepsProviderIdentityAndEnablesTheChosenHiddenProvider() {
        val first = ProviderConfig("fixture:one", "Fixture Primary with a long connection name", ProviderType.OPENAI_COMPAT,
            "https://example.test", "shared-model")
        val second = first.copy(id = "fixture:two", name = "Fixture Backup with an exceptionally long connection name", enabled = false)
        val providers = mutableStateOf(listOf(first, second))
        val selected = AtomicReference<ModelShortcut?>()
        val enabled = AtomicReference<String?>()
        val catalog = mapOf(first.id to listOf(ModelEntry("shared-model", displayName = "Shared primary", reasoning = true)),
            second.id to listOf(ModelEntry("shared-model", displayName = "Shared backup", reasoning = false)))
        val shortcuts = ModelShortcuts(c.appContext, "provider-test-${System.nanoTime()}")
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { theme {
                var open by remember { mutableStateOf(true) }
                if (open) ModelPickerSheet(providers.value, first.id, first.model, catalog,
                    onDismiss = { open = false }, onSelect = { id, model -> selected.set(ModelShortcut(id, requireNotNull(model))) },
                    onRefreshCatalog = { "Offline fixture: saved catalog retained" }, onManageProviders = {},
                    modelShortcuts = shortcuts, searchAllProvidersInitially = true,
                    onEnableProvider = { id -> enabled.set(id); providers.value = providers.value.map { if (it.id == id) it.copy(enabled = true) else it } })
            } } }
            await { find("Show all") != null }
            scrollTo("Search models or providers")
            if (desktop) {
                // Tab brings the complete field into view, including in short desktop windows.
                for (step in 0 until 16) {
                    if (nodes().any { it.isEditable && it.isFocused }) break
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
                    SystemClock.sleep(100)
                }
                await { nodes().any { it.isEditable && it.isFocused } }
                instrumentation.sendStringSync("shared")
                await { nodes().any { it.isEditable && it.text?.toString() == "shared" } }
            } else search("shared")
            if (instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                SystemClock.sleep(400)
            }
            scrollTo("Refresh models"); screenshot("models-before-refresh"); tap("Refresh models")
            await { nodes().any { it.text?.contains("Offline fixture") == true } }
            scrollTo("Shared backup")
            scrollTo("Disabled · Enable & use")
            tap("Favourite Shared backup")
            scrollTo("Favourites"); tap("Favourites")
            scrollTo("Shared backup")
            await { find("Shared backup") != null }
            assertNull(find("Favourite Shared primary"))
            screenshot("models-unified-favourites")
            tap("Disabled · Enable & use")
            await { find("Enable and use") != null }
            tap("Enable and use")
            await { selected.get() != null }
            assertEquals(ModelShortcut(second.id, "shared-model"), selected.get())
            assertEquals(second.id, enabled.get())
            assertTrue(providers.value.last().enabled)
        }
    }

    @Test fun providerPopupSwitchesCatalogWithoutApplyingUntilModelConfirmed() {
        val first = ProviderConfig("popup:first", "Primary provider", ProviderType.OPENAI_COMPAT,
            "https://example.test", "alpha-model")
        val second = first.copy(id = "popup:second", name = "Secondary provider", model = "deepseek-r1")
        val catalog = mapOf(first.id to listOf(ModelEntry(first.model, reasoning = true)),
            second.id to listOf(ModelEntry(second.model)))
        val selected = AtomicReference<ModelShortcut?>()
        val storeName = "popup-test-${System.nanoTime()}"
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { theme {
                    var open by remember { mutableStateOf(true) }
                    if (open) ModelPickerSheet(listOf(first, second), first.id, first.model, catalog,
                        onDismiss = { open = false }, onSelect = { id, model -> selected.set(ModelShortcut(id, requireNotNull(model))) },
                        onRefreshCatalog = { null }, onManageProviders = {}, modelShortcuts = ModelShortcuts(c.appContext, storeName))
                } } }
                await { find("Switch provider") != null }
                assertTrue(bounds(requireNotNull(find("Switch provider"))).top < bounds(requireNotNull(find("Choose model"))).top)
                assertNotNull(find("Reasoning reported by provider"))
                screenshot("models-brain-provider-control")
                tap("Switch provider")
                await { find("Search providers") != null }
                search("Secondary"); hideKeyboard()
                assertNull(find("Browse models from ${first.name}"))
                screenshot("models-provider-popup")
                tap("Browse models from ${second.name}")
                await { find("Choose ${second.model} from ${second.name}") != null }
                assertNull(selected.get())
                assertNull(find("Choose ${first.model} from ${first.name}"))
                tap("Reasoning inferred from model name")
                await { find("This model family may support reasoning") != null }
                tap("Got it")
                screenshot("models-inferred-brain")
                tapModelRow(second.model)
                assertNull(selected.get())
                tap("Use ${second.model}")
                await { selected.get() != null }
                assertEquals(ModelShortcut(second.id, second.model), selected.get())
            }
        } finally {
            c.appContext.getSharedPreferences(storeName, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun providerFirstPickerSavesFilteredChecksAndKeepsRefreshesAndCancelledEditsSafe() {
        val provider = ProviderConfig("visibility:fixture", "OpenRouter fixture", ProviderType.OPENAI_COMPAT,
            "https://example.test", "qwen-one")
        val other = provider.copy(id = "visibility:disabled", name = "NVIDIA fixture", model = "other-model", enabled = false)
        val catalog = mutableStateOf(mapOf(provider.id to listOf(ModelEntry("qwen-one", true), ModelEntry("qwen-two"), ModelEntry("llama")),
            other.id to listOf(ModelEntry("other-model"))))
        val storeName = "visibility-test-${System.nanoTime()}"
        val visibility = ModelVisibility(c.appContext, storeName)
        visibility.save(provider.id, setOf("llama"))
        val selected = AtomicReference<ModelShortcut?>()
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { theme {
                    var open by remember { mutableStateOf(true) }
                    if (open) ModelPickerSheet(listOf(provider, other), provider.id, provider.model, catalog.value,
                        onDismiss = { open = false }, onSelect = { id, model -> selected.set(ModelShortcut(id, requireNotNull(model))) },
                        onRefreshCatalog = { null }, onManageProviders = {}, modelVisibility = visibility,
                        modelShortcuts = ModelShortcuts(c.appContext, "visibility-shortcuts-test"))
                } } }
                await { find("Choose model") != null }
                tap("Reasoning indicators")
                await { find("Reasoning support does not guarantee visible thinking text.") != null }
                assertVisibleWithinWindow("Got it")
                tap("Got it")
                tap("Manage models")
                await { find("Search this provider") != null }
                search("qwen"); hideKeyboard()
                tap("Select results (2)")
                search("llama"); hideKeyboard(); tapModelRow("llama", checkable = true)
                // Draft edits do not change any saved quick list before Save.
                assertEquals(setOf("llama"), visibility.state.value.selected[provider.id])
                search("qwen"); hideKeyboard()
                tap("Reasoning reported by provider")
                assertVisibleWithinWindow("Got it")
                tap("Got it")
                screenshot("models-manage-filtered-selection")
                tap("Save selection")
                await { find("Choose model") != null }
                assertEquals(setOf("qwen-one", "qwen-two"), ModelVisibility(c.appContext, storeName).state.value.selected[provider.id])
                scenario.onActivity { catalog.value = catalog.value + (provider.id to (catalog.value.getValue(provider.id) + ModelEntry("newly-discovered"))) }
                search("newly-discovered"); hideKeyboard()
                assertNull(find("Hidden"))
                assertFalse(visibility.state.value.includes(provider.id, "newly-discovered"))
                tap("Show all")
                scrollTo("newly-discovered")
                await { find("Hidden") != null }
                screenshot("models-show-all-hidden")
                search(""); hideKeyboard()
                tap("Manage models")
                search("qwen-one"); hideKeyboard(); tapModelRow("qwen-one", checkable = true)
                tap("Close model management")
                await { find("Choose model") != null }
                assertTrue(visibility.state.value.includes(provider.id, "qwen-one"))
                search("qwen-two"); hideKeyboard(); tapModelRow("qwen-two")
                assertNull(selected.get())
                assertVisibleWithinWindow("Use qwen-two")
                screenshot("models-provider-first-confirm")
                tap("Use qwen-two")
                await { selected.get() != null }
                assertEquals(ModelShortcut(provider.id, "qwen-two"), selected.get())
            }
        } finally {
            c.appContext.getSharedPreferences(storeName, android.content.Context.MODE_PRIVATE).edit().clear().commit()
            c.appContext.getSharedPreferences("visibility-shortcuts-test", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun longPressBulkActionsReviewEveryDeletionAndEditKeepsSelection() = withProviders { first, second ->
        runBlocking { c.settings.setActiveSelection(first.id, "shared-model") }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { theme { ProvidersScreen(c, {}) } } }
            await { find("Search providers or models") != null }
            search("Fixture"); hideKeyboard()
            scrollTo("Select ${first.name}")
            tap("Select ${first.name}", holdMillis = 700)
            await { find("1 selected") != null }
            tap("Select provider ${second.name}")
            await { find("2 selected") != null }
            tap("Disable")
            await { runBlocking { c.providers.providers.first().filter { it.id in setOf(first.id, second.id) }.all { !it.enabled } } }
            assertNotNull(find("2 selected"))
            tap("Enable")
            await { runBlocking { c.providers.providers.first().filter { it.id in setOf(first.id, second.id) }.all { it.enabled } } }
            tap("Delete")
            await { find("Delete 2 providers?") != null }
            tap("Continue")
            await { find("Delete all 2 providers?") != null }
            assertNotNull(find(first.name)); assertNotNull(find(second.name))
            assertTrue(runBlocking { c.providers.providers.first().any { it.id == first.id } })
            screenshot("providers-bulk-delete-review")
            tap("Edit selection")
            await { find("2 selected") != null }
            assertTrue(nodes().count { it.isCheckable && it.isChecked } >= 2)
            screenshot("providers-bulk-edit-retains-selection")
            tap("Delete"); tap("Continue"); tap("Delete all")
            await { runBlocking { c.providers.providers.first().none { it.id == first.id || it.id == second.id } } }
            // Keep the missing connection explicit; deleting must never reroute a chat.
            assertEquals(first.id, runBlocking { c.settings.settings.first().activeProviderId })
        }
    }

    private fun withProviders(block: (ProviderConfig, ProviderConfig) -> Unit) = runBlocking {
        val saved = c.settings.settings.first()
        val first = c.providers.add("Fixture Primary with a long connection name", ProviderType.OPENAI_COMPAT,
            "https://example.test", "shared-model", "")
        val second = c.providers.add("Fixture Secondary", ProviderType.OPENAI_COMPAT, "https://example.test", "shared-model", "")
        try {
            listOf(first, second).forEach { c.providers.saveCatalog(it.id, listOf(ModelEntry("shared-model"))) }
            val ids = c.providers.providers.first().map { it.id }
            c.settings.restorePortable(saved.copy(providerOrder = listOf(first.id, second.id) + ids.filterNot { it in setOf(first.id, second.id) }))
            block(first, second)
        } finally {
            c.providers.delete(first.id); c.providers.delete(second.id)
            c.settings.restorePortable(saved)
        }
    }

    @Composable private fun theme(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDesktopLayout provides desktop, LocalDesktopInput provides desktop) {
            HarnessTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        val visible = nodes().filter { it.isVisibleToUser }
        return visible.firstOrNull { it.contentDescription?.toString() == text || it.text?.toString() == text }
            ?: visible.firstOrNull { it.text?.toString()?.contains(text) == true }
    }
    private fun tapModelRow(text: String, checkable: Boolean = false) {
        val label = if (checkable) "Show $text in quick selection" else nodes().first {
            it.isVisibleToUser && it.contentDescription?.toString()?.startsWith("Choose $text from ") == true
        }.contentDescription.toString()
        tap(label)
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun search(value: String) {
        repeat(15) {
            if (nodes().any { it.isEditable && it.isVisibleToUser }) return@repeat
            scrollingList()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(150)
        }
        val input = nodes().first { it.isEditable && it.isVisibleToUser }
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
    }
    private fun tap(text: String, holdMillis: Long = 0) {
        if (find(text) == null) scrollTo(text)
        await { find(text) != null }
        val rect = bounds(requireNotNull(find(text)))
        val source = if (desktop) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = if (desktop) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = rect.exactCenterX(); y = rect.exactCenterY(); pressure = 1f; size = 1f })
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, 1, properties, coords,
                0, if (desktop && action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0,
                1f, 1f, 0, 0, source, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN && holdMillis > 0) SystemClock.sleep(holdMillis)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
    }
    private fun scrollingList() = nodes().firstOrNull {
        it.isScrollable && it.contentDescription?.toString() !in listOf("Drag handle", "Close sheet")
    }
    private fun scrollTo(text: String) {
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            for (step in 0 until 12) {
                if (find(text) != null) return
                val list = scrollingList() ?: break
                if (list.actionList.none { it.id == action } || !list.performAction(action)) break
                instrumentation.waitForIdleSync()
                SystemClock.sleep(400)
            }
        }
        screenshot("scroll-failure")
        error("Could not scroll to $text; nodes: ${nodes().map { Triple(it.text, it.contentDescription, it.isScrollable) }}")
    }
    private fun assertVisibleWithinWindow(text: String) {
        val rect = bounds(requireNotNull(find(text)))
        val screen = instrumentation.targetContext.resources.displayMetrics
        assertTrue("$text outside window: $rect", rect.left >= 0 && rect.right <= screen.widthPixels && rect.top >= 0 && rect.bottom <= screen.heightPixels)
    }
    private fun screenshot(name: String) {
        val tag = InstrumentationRegistry.getArguments().getString("variant") ?: "default"
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(c.appContext.getExternalFilesDir(null), "$tag-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Provider UI condition timed out; nodes: ${nodes().map { it.text ?: it.contentDescription }.filterNotNull()}")
    }
}
