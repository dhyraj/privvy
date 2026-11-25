package com.privvy.app.security

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.privvy.app.R
import com.privvy.app.ui.MainActivity

class SessionGuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastInteraction = SystemClock.elapsedRealtime()
    private var backgrounded = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) lockSession()
        }
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (shouldLock()) {
                lockSession()
                return
            }
            updatePill()
            handler.postDelayed(this, MONITOR_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        handler.postDelayed(monitor, MONITOR_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (SessionLockManager.isLocked(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                lockSession()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOUCH -> lastInteraction = SystemClock.elapsedRealtime()
            ACTION_BACKGROUND -> backgrounded = true
            ACTION_FOREGROUND -> backgrounded = false
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        lockSession()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        com.privvy.app.ui.ReturnPill.hide(this)
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    private fun updatePill() {
        if (!backgrounded) {
            com.privvy.app.ui.ReturnPill.hide(this)
            return
        }
        val manager = getSystemService(ActivityManager::class.java)
        val guestVisible = manager?.runningAppProcesses.orEmpty().any { process ->
            process.processName.startsWith("$packageName:v") &&
                (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
        }
        if (guestVisible) com.privvy.app.ui.ReturnPill.show(this)
        else com.privvy.app.ui.ReturnPill.hide(this)
    }

    private fun shouldLock(): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return true
        if (SystemClock.elapsedRealtime() - lastInteraction >= INACTIVITY_TIMEOUT_MS) return true
        val manager = getSystemService(ActivityManager::class.java) ?: return false
        val ownVisible = manager.runningAppProcesses.orEmpty().any { process ->
            process.pkgList.any { it == packageName } &&
                (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
        }
        return backgrounded && !ownVisible
    }

    private fun lockSession() {
        SessionLockManager.lockSession(this)
        com.privvy.app.ui.ReturnPill.hide(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Active session", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while Privvy is protecting an active session"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val closeIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionGuardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_privvy)
            .setContentTitle("Privvy is active")
            .setContentText("Session running — tap to open, or close to stop.")
            .setContentIntent(openIntent)
            .addAction(0, "Close", closeIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.privvy.app.action.STOP_SESSION"
        const val ACTION_TOUCH = "com.privvy.app.action.TOUCH_SESSION"
        const val ACTION_BACKGROUND = "com.privvy.app.action.BACKGROUND_SESSION"
        const val ACTION_FOREGROUND = "com.privvy.app.action.FOREGROUND_SESSION"
        private const val CHANNEL_ID = "privvy_session"
        private const val NOTIFICATION_ID = 1001
        private const val MONITOR_INTERVAL_MS = 2_000L
        private const val INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
