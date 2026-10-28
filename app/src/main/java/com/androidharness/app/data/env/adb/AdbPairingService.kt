package com.androidharness.app.data.env.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Discovers the wireless debugging pairing port and lets the user type the
 * six-digit code from the notification shade. The notification is deliberate:
 * Android keeps the system pairing dialog on screen, so the app itself cannot
 * be used to type the code on a phone.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var search: Job? = null
    private var mdns: AdbMdns? = null

    private val manager: WirelessAdbManager
        get() = (application as HarnessApp).container.wirelessAdb

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        if (notifications.getNotificationChannel(CHANNEL) == null) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.wireless_adb_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    setShowBadge(false)
                    setAllowBubbles(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginSearch()
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_CODE)?.toString().orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                startForeground(NOTIFICATION_ID, working())
                finishPairing(code, port)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
        return START_REDELIVER_INTENT
    }

    private fun beginSearch() {
        startForeground(NOTIFICATION_ID, searching())
        if (search?.isActive == true) return
        search = scope.launch {
            val discovery = AdbMdns(this@AdbPairingService, AdbMdns.TLS_PAIRING).also { mdns = it }
            while (isActive) {
                val port = discovery.findPort(20_000)
                if (port != null) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, input(port))
                    return@launch
                }
            }
        }
    }

    private fun finishPairing(code: String, port: Int) {
        scope.launch {
            val result = runCatching {
                if (port in 1..65535) manager.pair(code, port) else manager.pair(code)
                manager.connect().getOrThrow()
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, resultNotification(result))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun resultNotification(result: Result<Any?>): Notification {
        val success = result.isSuccess
        val text = result.exceptionOrNull()?.message
        return base(success)
            .setContentTitle(getString(if (success) R.string.wireless_adb_paired else R.string.wireless_adb_pairing_failed))
            .apply { if (!success && !text.isNullOrBlank()) setContentText(text) }
            .setAutoCancel(true)
            .build()
    }

    private fun searching(): Notification = base(false)
        .setContentTitle(getString(R.string.wireless_adb_searching))
        .setContentText(getString(R.string.wireless_adb_searching_hint))
        .addAction(Notification.Action.Builder(null, getString(R.string.wireless_adb_stop_search), serviceIntent(ACTION_STOP, 3)).build())
        .build()

    private fun input(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(EXTRA_CODE)
            .setLabel(getString(R.string.wireless_adb_code_label))
            .build()
        val reply = PendingIntent.getForegroundService(
            this, 2,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_PORT, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return base(false)
            .setContentTitle(getString(R.string.wireless_adb_pairing_found))
            .setContentText(getString(R.string.wireless_adb_pairing_found_hint))
            .addAction(
                Notification.Action.Builder(null, getString(R.string.wireless_adb_enter_code), reply)
                    .addRemoteInput(remoteInput)
                    .build(),
            )
            .build()
    }

    private fun working(): Notification = base(false)
        .setContentTitle(getString(R.string.wireless_adb_pairing))
        .build()

    private fun base(ongoing: Boolean): Notification.Builder {
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, AdbPairingService::class.java).setAction(action),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        search?.cancel()
        mdns?.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "wireless_adb_pairing"
        private const val NOTIFICATION_ID = 9102
        private const val ACTION_START = "com.androidharness.app.WIRELESS_ADB_PAIR"
        private const val ACTION_REPLY = "com.androidharness.app.WIRELESS_ADB_REPLY"
        private const val ACTION_STOP = "com.androidharness.app.WIRELESS_ADB_STOP"
        private const val EXTRA_CODE = "pairing_code"
        private const val EXTRA_PORT = "pairing_port"

        @RequiresApi(Build.VERSION_CODES.R)
        fun start(context: Context) {
            context.startForegroundService(Intent(context, AdbPairingService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AdbPairingService::class.java).setAction(ACTION_STOP))
        }
    }
}
