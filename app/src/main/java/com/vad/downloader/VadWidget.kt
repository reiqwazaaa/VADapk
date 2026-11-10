package com.vad.downloader

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.RemoteViews

/**
 * Виджет «Вставить и скачать». Кнопка берёт ссылку из буфера (через невидимое окно PasteActivity) и качает,
 * не открывая приложение. Во время загрузки на месте кнопки — название, проценты, скорость и полоса.
 * Цвета берутся из текущей темы приложения; Юта-тян показывает настроение. Сужается до 1×1 — остаётся одна кнопка.
 */
class VadWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        refresh(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) {
        refresh(ctx)
    }

    companion object {
        const val ACTION_PASTE = "com.vad.downloader.PASTE_DL"

        // состояние текущей загрузки (живёт, пока жив процесс)
        @Volatile private var dlActive = false
        @Volatile private var dlTitle = ""
        @Volatile private var dlInfo = ""
        @Volatile private var dlPct = 0
        @Volatile private var dlQueue = 0
        @Volatile private var lastPush = 0L

        fun downloadStart(ctx: Context, queued: Int) {
            dlActive = true; dlTitle = ""; dlInfo = ""; dlPct = 0; dlQueue = queued
            setMood(ctx, "wink")
        }

        fun downloadProgress(ctx: Context, title: String, pct: Int, info: String, queued: Int) {
            dlTitle = title; dlInfo = info; dlPct = pct.coerceIn(0, 100); dlQueue = queued
            val now = System.currentTimeMillis()
            if (now - lastPush < 1000) return
            lastPush = now
            refresh(ctx)
        }

        fun downloadEnd(ctx: Context, mood: String) {
            dlActive = false
            setMood(ctx, mood)
        }

        /** Сохранить настроение (happy/sad/wink/normal) и перерисовать виджеты. */
        fun setMood(ctx: Context, mood: String) {
            ctx.getSharedPreferences("vad", Context.MODE_PRIVATE).edit()
                .putString("w_mood", mood).putLong("w_mood_t", System.currentTimeMillis()).apply()
            refresh(ctx)
        }

        private fun outfit(p: SharedPreferences): Int {
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

        private val bgRes = intArrayOf(R.drawable.widget_bg_0, R.drawable.widget_bg_1, R.drawable.widget_bg_2, R.drawable.widget_bg_3, R.drawable.widget_bg_4, R.drawable.widget_bg_5, R.drawable.widget_bg_6, R.drawable.widget_bg_7)
        private val btnRes = intArrayOf(R.drawable.widget_btn_0, R.drawable.widget_btn_1, R.drawable.widget_btn_2, R.drawable.widget_btn_3, R.drawable.widget_btn_4, R.drawable.widget_btn_5, R.drawable.widget_btn_6, R.drawable.widget_btn_7)
        private val circleRes = intArrayOf(R.drawable.widget_circle_0, R.drawable.widget_circle_1, R.drawable.widget_circle_2, R.drawable.widget_circle_3, R.drawable.widget_circle_4, R.drawable.widget_circle_5, R.drawable.widget_circle_6, R.drawable.widget_circle_7)

        private fun toBitmap(d: Drawable, w: Int, h: Int): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            return b
        }

        private fun barBitmap(pct: Int, th: AppTheme.Colors, w: Int, h: Int): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = th.track
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, p)
            p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), 0f, th.btn[0], th.accent, android.graphics.Shader.TileMode.CLAMP)
            val fw = (w * pct / 100f).coerceAtLeast(if (pct > 0) h.toFloat() else 0f)
            if (fw > 0) c.drawRoundRect(RectF(0f, 0f, fw, h.toFloat()), h / 2f, h / 2f, p)
            return b
        }

        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, VadWidget::class.java))
                if (ids.isEmpty()) return
                Strings.init(ctx)
                val p = ctx.getSharedPreferences("vad", Context.MODE_PRIVATE)
                val th = AppTheme.of(p)
                val kawaii = p.getBoolean("kawaii_unlocked", false)
                val density = ctx.resources.displayMetrics.density

                var mood = p.getString("w_mood", "normal") ?: "normal"
                val age = System.currentTimeMillis() - p.getLong("w_mood_t", 0L)
                val ttl = when (mood) { "wink" -> 3 * 3600_000L; "happy", "sad" -> 30 * 60_000L; else -> 0L }
                if (mood != "normal" && age > ttl) mood = "normal"
                if (dlActive) mood = "wink" else if (mood == "wink") mood = "normal"

                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val paste = Intent(ctx, PasteActivity::class.java).setAction(ACTION_PASTE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                val pastePi = PendingIntent.getActivity(ctx, 1, paste, flags)
                val open = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val openPi = PendingIntent.getActivity(ctx, 2, open, flags)

                val girl: Bitmap? = if (kawaii) {
                    val o = outfit(p)
                    (Sprites.drawable(ctx, Sprites.girlSlot(o, mood)) ?: ctx.getDrawable(res(o, mood)))?.let { toBitmap(it, 240, 288) }
                } else {
                    ctx.getDrawable(R.drawable.ic_cat)?.let { toBitmap(it, 192, 192) }
                }

                for (id in ids) {
                    val opt = mgr.getAppWidgetOptions(id)
                    val wDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).let { if (it <= 0) 250 else it }
                    val hDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 70).let { if (it <= 0) 70 else it }
                    val small = wDp < 110
                    val rv = RemoteViews(ctx.packageName, if (small) R.layout.widget_vad_small else R.layout.widget_vad)
                    rv.setInt(R.id.w_root, "setBackgroundResource", bgRes[th.idx])
                    rv.setTextColor(R.id.w_btn, th.btnText)

                    if (small) {
                        rv.setInt(R.id.w_btn, "setBackgroundResource", circleRes[th.idx])
                        rv.setTextViewText(R.id.w_btn, if (dlActive) "$dlPct%" else "⬇")
                        rv.setOnClickPendingIntent(R.id.w_btn, if (dlActive) openPi else pastePi)
                        rv.setOnClickPendingIntent(R.id.w_root, openPi)
                    } else {
                        val tiny = hDp < 64
                        if (girl != null && !tiny) {
                            rv.setViewVisibility(R.id.w_img, View.VISIBLE)
                            rv.setImageViewBitmap(R.id.w_img, girl)
                        } else rv.setViewVisibility(R.id.w_img, View.GONE)
                        rv.setTextColor(R.id.w_status, th.text)
                        rv.setTextColor(R.id.w_info, th.text)
                        if (dlActive) {
                            val key = if (kawaii) "w_busy_k" else "w_busy"
                            var line = if (dlTitle.isNotBlank()) dlTitle else Strings.t(key)
                            if (dlQueue > 0) line += "  " + Strings.f("w_queue", dlQueue)
                            rv.setTextViewText(R.id.w_status, line)
                            rv.setTextViewText(R.id.w_info, if (dlInfo.isNotBlank()) dlInfo else "$dlPct%")
                            rv.setViewVisibility(R.id.w_info, if (tiny) View.GONE else View.VISIBLE)
                            rv.setViewVisibility(R.id.w_bar, View.VISIBLE)
                            val barH = (8 * density).toInt().coerceAtLeast(6)
                            val barW = ((wDp - 16 - 8 - (if (girl != null && !tiny) 56 else 0)) * density).toInt().coerceIn(60, 900)
                            rv.setImageViewBitmap(R.id.w_bar, barBitmap(dlPct, th, barW, barH))
                            rv.setViewVisibility(R.id.w_btn, View.GONE)
                        } else {
                            val key = when (mood) { "happy" -> "w_done"; "sad" -> "w_error"; else -> "w_idle" }
                            rv.setTextViewText(R.id.w_status, Strings.t(if (kawaii) key + "_k" else key))
                            rv.setViewVisibility(R.id.w_info, View.GONE)
                            rv.setViewVisibility(R.id.w_bar, View.GONE)
                            rv.setViewVisibility(R.id.w_btn, View.VISIBLE)
                            rv.setInt(R.id.w_btn, "setBackgroundResource", btnRes[th.idx])
                            rv.setTextViewText(R.id.w_btn, Strings.t("w_btn"))
                            rv.setOnClickPendingIntent(R.id.w_btn, pastePi)
                        }
                        rv.setOnClickPendingIntent(R.id.w_img, openPi)
                        rv.setOnClickPendingIntent(R.id.w_status, openPi)
                        rv.setOnClickPendingIntent(R.id.w_info, openPi)
                        rv.setOnClickPendingIntent(R.id.w_bar, openPi)
                    }
                    mgr.updateAppWidget(id, rv)
                }
            } catch (_: Throwable) {
            }
        }
    }
}
