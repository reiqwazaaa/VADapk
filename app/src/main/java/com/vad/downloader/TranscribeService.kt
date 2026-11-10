package com.vad.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import android.os.Process

/** Служба переднего плана для расшифровки и перевода: процесс не засыпает и не убивается, пока идёт работа. */
class TranscribeService : Service() {
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Transcriber.stop()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, build(this, Strings.t("tr_prep"), -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wl == null) wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vad:transcribe").apply { acquire(8L * 3600_000L) }
        } catch (_: Throwable) {
        }
        val o = Transcriber.takePending()
        if (o == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val app = applicationContext
        Thread {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND) } catch (_: Throwable) {}
            Transcriber.work(app, o)
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wl?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.vad.downloader.TR_STOP"
        private const val CHANNEL_ID = "vad_transcribe"
        private const val NOTIF_ID = 1011
        private const val DONE_ID = 1012

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, Strings.t("tr_notif_title"), NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 11,
            Intent(ctx, TranscribeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun build(ctx: Context, text: String, percent: Int): Notification {
            val stop = PendingIntent.getService(
                ctx, 12, Intent(ctx, TranscribeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(Strings.t("tr_notif_title"))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, percent.coerceIn(0, 100), percent < 0)
                .setContentIntent(openIntent(ctx))
                .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel), Strings.t("tr_stop"), stop).build())
                .build()
        }

        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, TranscribeService::class.java)) } catch (_: Throwable) {}
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, TranscribeService::class.java)) } catch (_: Throwable) {}
        }

        fun update(ctx: Context, text: String, percent: Int) {
            try {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(ctx, text, percent))
            } catch (_: Throwable) {
            }
        }

        fun done(ctx: Context, text: String, ok: Boolean) {
            try {
                ensureChannel(ctx)
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_ID)
                nm.notify(DONE_ID, Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                    .setContentTitle(Strings.t("tr_notif_title"))
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(openIntent(ctx))
                    .build())
            } catch (_: Throwable) {
            }
        }
    }
}
