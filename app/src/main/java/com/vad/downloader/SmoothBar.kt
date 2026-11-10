package com.vad.downloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View

/** Плавная шкала: скруглённая, с градиентом, значение «подъезжает» к цели, по заливке бежит блик. */
class SmoothBar(ctx: Context) : View(ctx) {
    var max = 100
    var indeterminate = false
        set(v) { field = v; invalidate() }
    var progress: Int
        get() = target.toInt()
        set(v) = setProgress(v, true)

    private var target = 0f
    private var shown = 0f
    private var track = 0x33000000
    private var c1 = 0xFFFF9ACB.toInt()
    private var c2 = 0xFFFF5FA2.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val density = ctx.resources.displayMetrics.density

    fun setColors(track: Int, from: Int, to: Int) {
        this.track = track; c1 = from; c2 = to
        invalidate()
    }

    fun setProgress(p: Int, animate: Boolean) {
        target = p.coerceIn(0, max) * 100f / max
        if (!animate) shown = target
        invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val h = if (MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(hSpec) else (10 * density).toInt()
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, wSpec), h)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        val now = SystemClock.uptimeMillis()
        paint.shader = null
        paint.color = track
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, paint)

        var animating = false
        if (indeterminate) {
            val seg = w * 0.35f
            val x = -seg + (now % 1400L) / 1400f * (w + seg)
            clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            paint.shader = LinearGradient(x, 0f, x + seg, 0f, c1, c2, Shader.TileMode.CLAMP)
            rect.set(x, 0f, x + seg, h)
            c.drawRoundRect(rect, r, r, paint)
            c.restore()
            animating = true
        } else {
            if (shown != target) {
                shown += (target - shown) * 0.14f
                if (Math.abs(target - shown) < 0.05f) shown = target
                animating = true
            }
            if (shown > 0f) {
                val fw = maxOf(h, w * shown / 100f)
                paint.shader = LinearGradient(0f, 0f, fw, 0f, c1, c2, Shader.TileMode.CLAMP)
                rect.set(0f, 0f, fw, h)
                c.drawRoundRect(rect, r, r, paint)
                if (shown < 100f) {
                    // блик
                    val bw = h * 4f
                    val bx = -bw + (now % 1600L) / 1600f * (fw + bw)
                    clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
                    c.save(); c.clipPath(clip)
                    paint.shader = LinearGradient(bx, 0f, bx + bw, 0f, intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                    rect.set(bx, 0f, bx + bw, h)
                    c.drawRect(rect, paint)
                    c.restore()
                    animating = true
                }
            }
        }
        paint.shader = null
        if (animating) postInvalidateOnAnimation()
    }
}
