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
import android.graphics.drawable.GradientDrawable
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

    private class Th(val bg: IntArray, val btn: IntArray, val text: Int, val btnText: Int, val accent: Int, val track: Int)

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

        // Палитры — копия из MainActivity: (нужно побед, фон, кнопка, акцент, флаг награды)
        private class Pal(val need: Int, val bg: IntArray, val btn: IntArray, val accent: Int, val flag: String = "")
        private val pals = listOf(
            Pal(0, intArrayOf(0xFFFFC1E3.toInt(), 0xFFE0C3FF.toInt(), 0xFFBDE7FF.toInt()), intArrayOf(0xFFFF9ACB.toInt(), 0xFFFFC2E5.toInt()), 0xFFFF5FA2.toInt()),
            Pal(1, intArrayOf(0xFFC9FFE5.toInt(), 0xFFBDEBFF.toInt(), 0xFFE8FFD0.toInt()), intArrayOf(0xFF5EDDB0.toInt(), 0xFFA8F5D6.toInt()), 0xFF22B58A.toInt()),
            Pal(3, intArrayOf(0xFFFFD3A8.toInt(), 0xFFFFB1C1.toInt(), 0xFFE7B6FF.toInt()), intArrayOf(0xFFFF9966.toInt(), 0xFFFFC48A.toInt()), 0xFFFF7043.toInt()),
            Pal(5, intArrayOf(0xFFE4D6FF.toInt(), 0xFFCDBDFF.toInt(), 0xFFF7D6FF.toInt()), intArrayOf(0xFFA987F0.toInt(), 0xFFCDB8FF.toInt()), 0xFF8A5CF0.toInt()),
            Pal(10, intArrayOf(0xFFFFF3A8.toInt(), 0xFFFFD6A5.toInt(), 0xFFFFB5D8.toInt()), intArrayOf(0xFFFFC93C.toInt(), 0xFFFFE59A.toInt()), 0xFFFF9F1C.toInt()),
            Pal(0, intArrayOf(0xFFF4F4F4.toInt(), 0xFFD6D6D6.toInt(), 0xFFFAFAFA.toInt()), intArrayOf(0xFF9E9E9E.toInt(), 0xFFD0D0D0.toInt()), 0xFF212121.toInt(), "mono_theme")
        )

        private fun theme(p: SharedPreferences): Th {
            val th = p.getInt("theme", 0)
            if (th == 0 || !p.getBoolean("kawaii_unlocked", false)) {
                return Th(intArrayOf(0xFF3A3A3A.toInt(), 0xFF262626.toInt()), intArrayOf(0xFF4A90D9.toInt(), 0xFF3B78B8.toInt()),
                    0xFFEEEEEE.toInt(), 0xFFFFFFFF.toInt(), 0xFF4A90D9.toInt(), 0x33FFFFFF)
            }
            if (th == 2) {
                return Th(intArrayOf(0xFF07050F.toInt(), 0xFF110B22.toInt(), 0xFF060D1A.toInt()), intArrayOf(0xFF3A2468.toInt(), 0xFF4D3188.toInt()),
                    0xFFEBDDFF.toInt(), 0xFFEBDDFF.toInt(), 0xFFB388FF.toInt(), 0x33FFFFFF)
            }
            var pl = pals[p.getInt("palette", 0).coerceIn(0, pals.size - 1)]
            val open = if (pl.flag.isNotEmpty()) p.getBoolean(pl.flag, false) else p.getInt("wins", 0) >= pl.need
            if (!open) pl = pals[0]
            val txt = if (pl.flag == "mono_theme") 0xFF222222.toInt() else 0xFF6A2C6B.toInt()
            return Th(pl.bg, pl.btn, txt, txt, pl.accent, 0x44FFFFFF)
        }

        private fun toBitmap(d: Drawable, w: Int, h: Int): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            return b
        }

        private fun gradient(colors: IntArray, w: Int, h: Int, radiusPx: Float): Bitmap {
            val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (colors.size >= 2) colors else intArrayOf(colors[0], colors[0]))
            g.cornerRadius = radiusPx
            return toBitmap(g, w, h)
        }

        private fun barBitmap(pct: Int, th: Th): Bitmap {
            val w = 400; val h = 16
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = th.track
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, p)
            p.color = th.accent
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
                val th = theme(p)
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
                    val wPx = (wDp * density).toInt().coerceIn(40, 700)
                    val hPx = (hDp * density).toInt().coerceIn(40, 700)
                    val small = wDp < 110
                    val rv = RemoteViews(ctx.packageName, if (small) R.layout.widget_vad_small else R.layout.widget_vad)
                    rv.setImageViewBitmap(R.id.w_bg, gradient(th.bg, wPx, hPx, 20 * density))
                    rv.setImageViewBitmap(R.id.w_btn_bg, gradient(th.btn, 320, 96, 48f))
                    rv.setTextColor(R.id.w_btn, th.btnText)

                    if (small) {
                        rv.setTextViewText(R.id.w_btn, if (dlActive) "$dlPct%" else "⬇")
                        rv.setOnClickPendingIntent(R.id.w_btn_wrap, if (dlActive) openPi else pastePi)
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
                            rv.setImageViewBitmap(R.id.w_bar, barBitmap(dlPct, th))
                            rv.setViewVisibility(R.id.w_btn_wrap, View.GONE)
                        } else {
                            val key = when (mood) { "happy" -> "w_done"; "sad" -> "w_error"; else -> "w_idle" }
                            rv.setTextViewText(R.id.w_status, Strings.t(if (kawaii) key + "_k" else key))
                            rv.setViewVisibility(R.id.w_info, View.GONE)
                            rv.setViewVisibility(R.id.w_bar, View.GONE)
                            rv.setViewVisibility(R.id.w_btn_wrap, View.VISIBLE)
                            rv.setTextViewText(R.id.w_btn, Strings.t("w_btn"))
                            rv.setOnClickPendingIntent(R.id.w_btn_wrap, pastePi)
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
