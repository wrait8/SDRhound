package com.example.rtlsdr

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service behind the "RUN IN BACKGROUND" option. It does no DSP itself (the engine lives in the app
 * process); it exists so Android treats the app as actively playing audio: the process isn't killed or frozen,
 * and a partial wake lock keeps the CPU running with the screen off so IQ keeps flowing and the audio keeps playing.
 */
class RadioService : Service() {
    companion object {
        private const val CHANNEL = "radio_background"
        private const val NOTIF_ID = 4711
        private const val ACTION_STOP = "com.example.rtlsdr.STOP_RADIO"
        private const val EXTRA_TEXT = "text"

        /** Set by the activity: called when the notification's STOP button is pressed. */
        @Volatile var stopHandler: (() -> Unit)? = null

        fun start(ctx: Context, text: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RadioService::class.java).putExtra(EXTRA_TEXT, text))
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, RadioService::class.java)) }
    }

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopHandler?.invoke()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "receiving"
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rtlsdr:radio").apply {
                setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) // released in onDestroy; 12 h is only a safety cap
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Radio running in background", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, RadioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("RTL-SDR receiving")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "STOP", stop)
            .build()
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }
}
