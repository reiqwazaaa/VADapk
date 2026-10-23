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
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Служба переднего плана: держит процесс живым, пока идёт скачивание,
 * и показывает в уведомлении проценты, мегабайты и скорость.
 */
class DownloadService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vad:download").apply {
            acquire(6L * 60 * 60 * 1000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            try {
                YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
            } catch (_: Throwable) {
            }
            return START_NOT_STICKY
        }
        val n = buildNotification(this, "Скачивание", "Подготовка…", -1)
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val PROCESS_ID = "vad_download"
        const val ACTION_CANCEL = "com.vad.downloader.CANCEL"
        private const val CHANNEL_ID = "vad_download"
        private const val NOTIF_ID = 1001
        private const val DONE_ID = 1002

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Загрузки", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        private fun openAppIntent(ctx: Context): PendingIntent =
            PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        fun buildNotification(ctx: Context, title: String, text: String, percent: Int): Notification {
            val cancel = PendingIntent.getService(
                ctx, 1,
                Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val action = Notification.Action.Builder(
                Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel),
                "Отмена", cancel
            ).build()
            return Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, percent.coerceIn(0, 100), percent < 0)
                .setContentIntent(openAppIntent(ctx))
                .addAction(action)
                .build()
        }

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
            } catch (_: Throwable) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DownloadService::class.java))
        }

        fun update(ctx: Context, title: String, text: String, percent: Int) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(ctx, title, text, percent))
        }

        /** Итоговое уведомление (без кнопок, можно смахнуть). */
        fun finish(ctx: Context, title: String, text: String) {
            ensureChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(ctx))
                .build()
            nm.notify(DONE_ID, n)
        }
    }
}
