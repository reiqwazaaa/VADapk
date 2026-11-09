package com.vad.downloader

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.widget.RemoteViews

/** Виджет «Вставить и скачать»: кнопка открывает приложение, оно берёт ссылку из буфера и качает. Юта-тян показывает настроение. */
class VadWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        refresh(ctx)
    }

    companion object {
        const val ACTION_PASTE = "com.vad.downloader.PASTE_DL"

        /** Сохранить настроение (happy/sad/wink/normal) и перерисовать виджеты. */
        fun setMood(ctx: Context, mood: String) {
            ctx.getSharedPreferences("vad", Context.MODE_PRIVATE).edit()
                .putString("w_mood", mood).putLong("w_mood_t", System.currentTimeMillis()).apply()
            refresh(ctx)
        }

        private fun outfit(p: android.content.SharedPreferences): Int {
            val cur = if (p.contains("outfit_cur")) p.getInt("outfit_cur", 0) else if (p.getBoolean("outfit_on", false)) 1 else 0
            return when {
                cur == 1 && p.getBoolean("outfit_tank", false) -> 1
                cur == 2 && p.getBoolean("outfit_pj", false) -> 2
                cur == 3 && p.getBoolean("outfit_queen", false) -> 3
                else -> 0
            }
        }

        private fun res(o: Int, m: String): Int = when (o) {
            1 -> when (m) { "happy" -> R.drawable.kawaii_girl_happy_tank; "sad" -> R.drawable.kawaii_girl_sad_tank; "wink" -> R.drawable.kawaii_girl_wink_tank; else -> R.drawable.kawaii_girl_tank }
            2 -> when (m) { "happy" -> R.drawable.kawaii_girl_happy_pj; "sad" -> R.drawable.kawaii_girl_sad_pj; "wink" -> R.drawable.kawaii_girl_wink_pj; else -> R.drawable.kawaii_girl_pj }
            3 -> when (m) { "happy" -> R.drawable.kawaii_girl_happy_queen; "sad" -> R.drawable.kawaii_girl_sad_queen; "wink" -> R.drawable.kawaii_girl_wink_queen; else -> R.drawable.kawaii_girl_queen }
            else -> when (m) { "happy" -> R.drawable.kawaii_girl_happy; "sad" -> R.drawable.kawaii_girl_sad; "wink" -> R.drawable.kawaii_girl_wink; else -> R.drawable.kawaii_girl }
        }

        private fun toBitmap(d: Drawable, w: Int, h: Int): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            return b
        }

        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, VadWidget::class.java))
                if (ids.isEmpty()) return
                Strings.init(ctx)
                val p = ctx.getSharedPreferences("vad", Context.MODE_PRIVATE)
                val kawaii = p.getBoolean("kawaii_unlocked", false)
                // Настроение живёт ограниченное время, потом снова обычное
                var mood = p.getString("w_mood", "normal") ?: "normal"
                val age = System.currentTimeMillis() - p.getLong("w_mood_t", 0L)
                val ttl = when (mood) { "wink" -> 3 * 3600_000L; "happy", "sad" -> 30 * 60_000L; else -> 0L }
                if (mood != "normal" && age > ttl) mood = "normal"

                val rv = RemoteViews(ctx.packageName, R.layout.widget_vad)
                if (kawaii) {
                    val o = outfit(p)
                    val d = Sprites.drawable(ctx, Sprites.girlSlot(o, mood)) ?: ctx.getDrawable(res(o, mood))
                    if (d != null) rv.setImageViewBitmap(R.id.w_img, toBitmap(d, 240, 288))
                } else {
                    ctx.getDrawable(R.drawable.ic_cat)?.let { rv.setImageViewBitmap(R.id.w_img, toBitmap(it, 192, 192)) }
                }
                val key = when (mood) { "wink" -> "w_busy"; "happy" -> "w_done"; "sad" -> "w_error"; else -> "w_idle" }
                rv.setTextViewText(R.id.w_status, Strings.t(if (kawaii) key + "_k" else key))
                rv.setTextViewText(R.id.w_btn, Strings.t("w_btn"))

                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val paste = Intent(ctx, MainActivity::class.java).setAction(ACTION_PASTE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                rv.setOnClickPendingIntent(R.id.w_btn, PendingIntent.getActivity(ctx, 1, paste, flags))
                val open = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val openPi = PendingIntent.getActivity(ctx, 2, open, flags)
                rv.setOnClickPendingIntent(R.id.w_img, openPi)
                rv.setOnClickPendingIntent(R.id.w_status, openPi)
                mgr.updateAppWidget(ids, rv)
            } catch (_: Throwable) {
            }
        }
    }
}
