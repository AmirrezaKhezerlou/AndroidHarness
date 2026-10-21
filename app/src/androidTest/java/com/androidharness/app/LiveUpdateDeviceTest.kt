package com.androidharness.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The persistent foreground service must stay a plain notification while idle;
 * only an active run or terminal may request Live Update promotion.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36)
class LiveUpdateDeviceTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = target.getSystemService(NotificationManager::class.java)

    @Before
    fun grantNotifications() {
        // Each connectedAndroidTest run installs a fresh package, so the runtime
        // notification permission starts denied.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(target.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun returnToIdle() {
        RuntimeNotifier.setSessionPrompts("live-test", emptyList())
        RuntimeNotifier.setActive(false)
        AgentService.startPersistent(target)
    }

    @Test
    fun idleServiceIsNotPromoted() {
        RuntimeNotifier.setActive(false)
        AgentService.startPersistent(target)
        assertTrue(!awaitNotification(promoted = false).hasPromotionRequest())
    }

    @Test
    fun activeRunRequestsPromotion() {
        // Space posts out: the previous test leaves several updates in flight,
        // and NotificationManager rate-limits bursts.
        Thread.sleep(1_500)
        RuntimeNotifier.setActive(true)
        AgentService.startPersistent(target)
        assertTrue(awaitNotification(promoted = true).hasPromotionRequest())
    }

    @Test
    fun pendingApprovalExposesAllowAndDenyActions() {
        RuntimeNotifier.setActive(true)
        RuntimeNotifier.setSessionPrompts(
            "live-test",
            listOf(PendingPrompt("live-test", PendingPrompt.Kind.APPROVAL, "Chat", "Run a command")),
        )
        AgentService.startPersistent(target)
        val titles = awaitNotification(promoted = true).actions.orEmpty().map { it.title.toString() }
        assertTrue(titles.contains(target.getString(R.string.ui_approve)))
        assertTrue(titles.contains(target.getString(R.string.ui_deny)))
    }

    private fun Notification.hasPromotionRequest(): Boolean =
        flags and Notification.FLAG_PROMOTED_ONGOING != 0

    private fun awaitNotification(promoted: Boolean): Notification {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val notification = manager.activeNotifications
                .firstOrNull { it.id == AgentService.NOTIFICATION_ID }
                ?.notification
            if (notification != null && notification.hasPromotionRequest() == promoted) return notification
            Thread.sleep(100)
        }
        error("Agent notification did not reach promoted=$promoted")
    }
}
