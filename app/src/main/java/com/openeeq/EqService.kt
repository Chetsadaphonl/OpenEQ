package com.openeeq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Foreground service that keeps EqEngine alive while the app is in background.
 * MainActivity binds to it via LocalBinder to get the shared EqEngine instance.
 */
class EqService : Service() {

    companion object {
        const val ACTION_START = "com.openeeq.START"
        const val ACTION_STOP  = "com.openeeq.STOP"
        private const val CHANNEL_ID   = "OpenEQ_channel"
        private const val NOTIF_ID     = 1
    }

    inner class LocalBinder : Binder() {
        val service get() = this@EqService
    }

    val engine = EqEngine(audioSession = 0)
    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                engine.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification())
                if (!engine.isEnabled) {
                    try { engine.start() } catch (_: Exception) {}
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OpenEQ", NotificationManager.IMPORTANCE_LOW)
                    .also { it.description = "EQ active" }
            )
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, EqService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenEQ")
            .setContentText("EQ กำลังทำงาน")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null, "ปิด EQ",
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }
}
