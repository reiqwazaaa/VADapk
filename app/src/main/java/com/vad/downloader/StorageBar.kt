package com.vad.downloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Полоса памяти телефона: чужие данные, папка VAD (цветом акцента) и свободное место. */
class StorageBar(ctx: Context) : View(ctx) {
    var total = 0L
    var used = 0L
    var vad = 0L
    var accent = 0xFFFF5FA2.toInt()
    var othersColor = 0xFF8E8E9A.toInt()
    var freeColor = 0x33888888

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    fun set(total: Long, used: Long, vad: Long) {
        this.total = total; this.used = used; this.vad = vad.coerceAtMost(used)
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val dm = resources.displayMetrics
        setMeasuredDimension(MeasureSpec.getSize(w), (22 * dm.density).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        p.color = freeColor
        c.drawRect(rect, p)
        if (total > 0) {
            val others = (used - vad).coerceAtLeast(0)
            val wo = w * others / total
            // даже совсем маленькая папка VAD должна быть видна: минимум 4 dp
            val wv = if (vad > 0) maxOf(w * vad / total, 4 * resources.displayMetrics.density) else 0f
            p.color = othersColor
            c.drawRect(0f, 0f, wo, h, p)
            p.color = accent
            c.drawRect(wo, 0f, wo + wv, h, p)
        }
        c.restore()
    }
}
