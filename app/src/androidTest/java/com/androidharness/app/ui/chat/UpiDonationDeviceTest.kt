package com.androidharness.app.ui.chat

import android.content.ClipboardManager
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.ui.chat.components.EmptyState
import com.androidharness.app.ui.chat.components.UpiDonation
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpiDonationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)

    @Test fun dismissingFundingMessagePersistsAcrossRecreationAndBothSections(): Unit = runBlocking {
        val settings = (context.applicationContext as HarnessApp).container.settings
        val saved = settings.donationNoticeDismissed.first()
        val chat = mutableStateOf(true)
        fun display(host: ChatListTestActivity) = host.setContent {
            HarnessTheme(ThemeMode.LIGHT, dynamicColor = false) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    EmptyState(true, {}, {}, chatOnly = chat.value, modifier = Modifier.fillMaxSize())
                }
            }
        }
        try {
            settings.setDonationNoticeDismissed(false)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity(::display)
                reveal(label(R.string.donate_funding_message))
                screenshot("donation-funding-message")
                tap(label(R.string.donate_dismiss_message))
                awaitCondition { find(label(R.string.donate_funding_message)) == null }
                assertTrue(com.androidharness.app.data.SettingsRepository(context).donationNoticeDismissed.first())
                reveal(label(R.string.ui_donate))
                reveal(label(R.string.donate_upi_title))
                screenshot("donation-message-dismissed")
                scenario.recreate()
                scenario.onActivity(::display)
                reveal(label(R.string.donate_upi_title))
                assertNull(find(label(R.string.donate_funding_message)))
                scenario.onActivity { chat.value = false }
                reveal(label(R.string.donate_upi_title))
                assertNull(find(label(R.string.donate_funding_message)))
                tap(label(R.string.donate_upi_title))
                await(UpiDonation.UPI_ID)
                tap(label(R.string.settings_action_close))
            }
        } finally { settings.setDonationNoticeDismissed(saved) }
    }

    @Test fun chatAndCodeOfferUpiBelowCoffeeAndDialogWorksWithLargeText() {
        val chat = mutableStateOf(true)
        val dark = mutableStateOf(false)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    HarnessTheme(if (dark.value) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.width(320.dp).fillMaxHeight().safeDrawingPadding()) {
                                EmptyState(true, {}, {}, chatOnly = chat.value, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            } }
            for (isChat in listOf(true, false)) for (isDark in listOf(false, true)) {
                scenario.onActivity { chat.value = isChat; dark.value = isDark }
                reveal(label(R.string.donate_upi_title))
                // The funding notice can still be expanding after its saved dismissal loads.
                awaitCondition {
                    val tree = nodes()
                    val upi = tree.firstOrNull { matches(it, label(R.string.donate_upi_title)) }
                    val coffee = tree.firstOrNull { matches(it, label(R.string.ui_donate)) }
                    upi != null && coffee != null && bounds(upi).top >= bounds(coffee).bottom
                }
                assertNull("UPI ID stays hidden before the donation dialog opens", find(UpiDonation.UPI_ID))
                screenshot("upi-${if (isChat) "chat" else "code"}-${if (isDark) "dark" else "light"}-row")
                tap(label(R.string.donate_upi_title))
                await(UpiDonation.UPI_ID)
                assertNull("QR stays hidden until requested", find(label(R.string.donate_upi_qr_description)))
                tap(label(R.string.donate_upi_copy_id))
                instrumentation.runOnMainSync {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    assertEquals("sanuj69@ybl", clipboard.primaryClip!!.getItemAt(0).text.toString())
                }
                tap(label(R.string.donate_upi_show_qr))
                await(label(R.string.donate_upi_qr_description))
                val save = reveal(label(R.string.donate_upi_save_qr))
                assertTrue("Save stays on screen at large fonts", bounds(save).bottom <= context.resources.displayMetrics.heightPixels)
                assertNotNull(find(label(R.string.settings_action_close)))
                screenshot("upi-${if (isChat) "chat" else "code"}-${if (isDark) "dark" else "light"}-qr")
                tap(label(R.string.settings_action_close))
            }
        }
    }

    @Test fun bundledQrCanBePublishedAndReadThroughMediaStore(): Unit = runBlocking {
        val uri = UpiDonation.saveQrToGallery(context)
        try {
            assertEquals("Gallery publication returns an indexed content URI", "content", uri.scheme)
            val saved = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val original = context.resources.openRawResource(R.drawable.donate_upi_qr).use { it.readBytes() }
            assertArrayEquals(original, saved)
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun saveButtonPublishesTheOriginalQrInTheGallery() {
        val before = galleryIds()
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent {
                    HarnessTheme(ThemeMode.LIGHT, dynamicColor = false) {
                        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                            EmptyState(true, {}, {}, chatOnly = true, modifier = Modifier.fillMaxSize())
                        }
                    }
                } }
                tap(label(R.string.donate_upi_title))
                tap(label(R.string.donate_upi_show_qr))
                tap(label(R.string.donate_upi_save_qr))
                if (Build.VERSION.SDK_INT < 29) {
                    val until = SystemClock.uptimeMillis() + 5000
                    while (galleryIds() == before && SystemClock.uptimeMillis() < until) {
                        find("Allow")?.let { tap("Allow") }
                        SystemClock.sleep(100)
                    }
                }
                try {
                    awaitCondition(35_000) { galleryIds().any { it !in before } }
                } catch (failure: AssertionError) {
                    screenshot("upi-gallery-failure")
                    throw AssertionError("Gallery did not update. Visible UI: ${nodes().mapNotNull { it.text ?: it.contentDescription }}", failure)
                }
                val created = galleryIds() - before
                assertEquals("One tap creates one gallery item", 1, created.size)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, created.single())
                val saved = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val bundled = context.resources.openRawResource(R.drawable.donate_upi_qr).use { it.readBytes() }
                assertArrayEquals("QR bytes must not be recompressed or recolored", bundled, saved)
                screenshot("upi-gallery-save")
                tap(label(R.string.settings_action_close))
            }
        } finally {
            (galleryIds() - before).forEach { id ->
                context.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), null, null)
            }
        }
    }

    private fun galleryIds(): Set<Long> = buildSet {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("AndroidHarness-UPI-%"), null)?.use { cursor ->
            while (cursor.moveToNext()) add(cursor.getLong(0))
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun matches(node: AccessibilityNodeInfo, text: String) = node.isVisibleToUser &&
        (node.text?.toString()?.lineSequence()?.any { line -> line.equals(text, true) } == true || node.contentDescription?.toString() == text)
    private fun find(text: String) = nodes().firstOrNull { matches(it, text) }
    private fun await(text: String): AccessibilityNodeInfo {
        awaitCondition { find(text) != null }
        return requireNotNull(find(text))
    }
    private fun awaitCondition(timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!predicate() && SystemClock.uptimeMillis() < until) SystemClock.sleep(60)
        assertTrue("Expected UI or gallery state was not reached", predicate())
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun reveal(text: String): AccessibilityNodeInfo {
        repeat(12) {
            find(text)?.let { return it }
            nodes().lastOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        return await(text)
    }
    private fun tap(text: String) {
        reveal(text)
        var previous = Rect()
        var stableFrames = 0
        var target = Rect()
        awaitCondition {
            var node = find(text) ?: return@awaitCondition false
            while (!node.isClickable && node.parent != null) node = node.parent
            target = bounds(node)
            if (target == previous && node.isEnabled) stableFrames++ else stableFrames = 0
            previous = Rect(target)
            stableFrames >= 4 && !target.isEmpty
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(80)
        }
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(400)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
