package com.wein.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a navigation session alive while the app is backgrounded or the screen is locked.
 *
 * The activity still owns the GPS listener and all routing state — this service exists so the
 * process stays foreground-privileged (type = location), which is what lets Android keep
 * delivering location fixes while we're not visible, and so the user sees an ongoing
 * turn-by-turn notification with a one-tap way back in (and an End action).
 */
class NavService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Navigating"
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        startForegroundCompat(buildNotification(title, text))
        // Navigation state lives in the activity; don't let the OS resurrect a bare service.
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val end = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_END_NAV).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "End", end)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val ACTION_END_NAV = "com.wein.app.END_NAV"

        private const val CHANNEL_ID = "wein_nav"
        private const val NOTIF_ID = 42
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Ongoing turn-by-turn while navigating"
                    setShowBadge(false)
                }
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            }
        }

        /** Start the ongoing navigation notification, or update it if already running. */
        fun start(ctx: Context, title: String, text: String) {
            ensureChannel(ctx)
            val i = Intent(ctx, NavService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NavService::class.java))
        }
    }
}
