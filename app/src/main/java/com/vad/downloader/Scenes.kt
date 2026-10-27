package com.vad.downloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Нарисованные анимированные фоны для свидания (без внешних картинок). */
class SceneView(ctx: Context) : View(ctx) {
    var scene: String = "street"
        set(v) {
            if (field != v) {
                field = v
                alpha = 0.35f
                animate().alpha(1f).setDuration(600).start()
            }
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var time = 0f
    private var last = 0L
    private val rnd = Random(11)
    // x, y (0..1), размер, скорость
    private val parts = Array(30) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.4f + rnd.nextFloat() * 0.9f, 0.03f + rnd.nextFloat() * 0.07f) }
    private val bh = FloatArray(9) { 0.18f + rnd.nextFloat() * 0.22f }
    private val shaders = HashMap<String, Shader>()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shaders.clear()
    }

    private fun grad(key: String, top: Int, bottom: Int): Shader =
        shaders.getOrPut(key) { LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP) }

    private fun fillGrad(c: Canvas, key: String, top: Int, bottom: Int) {
        p.style = Paint.Style.FILL
        p.shader = grad(key, top, bottom)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        if (last != 0L) time += ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        val customBg = if (scene in builtIn) Sprites.bitmap(context, "bg_$scene") else null
        if (customBg != null) {
            drawCrop(c, w, h, customBg)
            postInvalidateOnAnimation()
            return
        }
        when (scene) {
            "park" -> park(c, w, h)
            "cafe" -> cafe(c, w, h)
            "aqua" -> aqua(c, w, h)
            "fun" -> funfair(c, w, h)
            "evening" -> city(c, w, h, true)
            "room_home" -> room(c, w, h, R.drawable.room_home)
            "room_her" -> room(c, w, h, R.drawable.room_her)
            "room_home_night" -> room(c, w, h, R.drawable.room_home, true)
            "room_her_night" -> room(c, w, h, R.drawable.room_her, true)
            else -> city(c, w, h, false)
        }
        postInvalidateOnAnimation()
    }

    private val bmps = HashMap<Int, Bitmap?>()

    /** Фото комнаты, обрезанное по центру под размер экрана, с лёгким осветлением для читаемости. */
    private val builtIn = setOf("street", "park", "cafe", "aqua", "fun", "evening")

    private fun drawCrop(c: Canvas, w: Float, h: Float, bmp: Bitmap) {
        val scale = maxOf(w / bmp.width, h / bmp.height)
        val sw = (w / scale).toInt().coerceAtMost(bmp.width)
        val sh = (h / scale).toInt().coerceAtMost(bmp.height)
        val sx = (bmp.width - sw) / 2
        val sy = (bmp.height - sh) / 2
        c.drawBitmap(bmp, Rect(sx, sy, sx + sw, sy + sh), RectF(0f, 0f, w, h), null)
    }

    private fun room(c: Canvas, w: Float, h: Float, res: Int, night: Boolean = false) {
        val custom = Sprites.bitmap(context, if (res == R.drawable.room_her) "room_her" else "room_home")
        val bmp = custom ?: bmps.getOrPut(res) {
            try { BitmapFactory.decodeResource(resources, res) } catch (_: Throwable) { null }
        }
        if (bmp == null) {
            fillGrad(c, "roomfb", 0xFFFFE9D6.toInt(), 0xFFFFC9B0.toInt())
            return
        }
        val scale = maxOf(w / bmp.width, h / bmp.height)
        val sw = (w / scale).toInt().coerceAtMost(bmp.width)
        val sh = (h / scale).toInt().coerceAtMost(bmp.height)
        val sx = (bmp.width - sw) / 2
        val sy = (bmp.height - sh) / 2
        c.drawBitmap(bmp, Rect(sx, sy, sx + sw, sy + sh), RectF(0f, 0f, w, h), null)
        p.color = if (night) 0x99120A33.toInt() else 0x33FFFFFF
        c.drawRect(0f, 0f, w, h, p)
    }

    private fun city(c: Canvas, w: Float, h: Float, eve: Boolean) {
        if (eve) fillGrad(c, "eve", 0xFF7B5BC8.toInt(), 0xFFFFA77A.toInt())
        else fillGrad(c, "day", 0xFFBDE7FF.toInt(), 0xFFFFE1F0.toInt())
        if (eve) {
            p.color = 0x55FFE9A8
            c.drawCircle(w * 0.5f, h * 0.62f, h * 0.2f, p)
            p.color = 0xFFFFE9A8.toInt()
            c.drawCircle(w * 0.5f, h * 0.62f, h * 0.12f, p)
            p.color = 0xFF3E2670.toInt()
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            for (i in 0..3) {
                val x = ((i * 0.27f + time * 0.03f) % 1.2f - 0.1f) * w
                val y = h * (0.2f + 0.05f * i) + sin(time * 2 + i) * 6f
                val path = Path().apply { moveTo(x - 14f, y - 6f); quadTo(x - 7f, y + 2f, x, y); quadTo(x + 7f, y + 2f, x + 14f, y - 6f) }
                c.drawPath(path, p)
            }
            p.style = Paint.Style.FILL
        } else {
            p.color = 0xCCFFFFFF.toInt()
            for (i in 0..2) {
                val x = ((i * 0.37f + time * (0.01f + 0.004f * i)) % 1.4f - 0.2f) * w
                val y = h * (0.12f + 0.09f * i)
                c.drawOval(RectF(x, y, x + w * 0.28f, y + h * 0.06f), p)
                c.drawOval(RectF(x + w * 0.06f, y - h * 0.03f, x + w * 0.2f, y + h * 0.05f), p)
            }
        }
        val bw = w / 8f
        for (i in 0..8) {
            val x = i * bw - bw * 0.2f
            val top = h * (1f - bh[i] - 0.18f)
            p.color = if (eve) 0xFF3E2670.toInt() else if (i % 2 == 0) 0xFFE9D0FF.toInt() else 0xFFFFC9E3.toInt()
            c.drawRect(x, top, x + bw * 0.95f, h, p)
            p.color = if (eve) (if ((i * 7) % 3 == 0) 0xFFFFD84D.toInt() else 0x33FFD84D) else 0x99FFFFFF.toInt()
            var yy = top + 14f
            while (yy < h * 0.9f) {
                c.drawRect(x + bw * 0.2f, yy, x + bw * 0.38f, yy + 14f, p)
                c.drawRect(x + bw * 0.55f, yy, x + bw * 0.73f, yy + 14f, p)
                yy += 40f
            }
        }
        p.color = if (eve) 0xFF2B1B4E.toInt() else 0xFFFF9ACB.toInt()
        c.drawRect(0f, h * 0.9f, w, h, p)
        // фонарь
        p.color = if (eve) 0xFF1F1238.toInt() else 0xFF8D6BD6.toInt()
        c.drawRect(w * 0.88f, h * 0.55f, w * 0.89f + 6f, h * 0.9f, p)
        p.color = 0xFFFFE08A.toInt()
        c.drawCircle(w * 0.88f + 3f, h * 0.55f, 16f, p)
    }

    private fun park(c: Canvas, w: Float, h: Float) {
        fillGrad(c, "park", 0xFFA8E0FF.toInt(), 0xFFFFEBF3.toInt())
        p.color = 0x66FFF2A8
        c.drawCircle(w * 0.82f, h * 0.14f, h * 0.1f, p)
        p.color = 0xFFFFE680.toInt()
        c.drawCircle(w * 0.82f, h * 0.14f, h * 0.06f, p)
        for (layer in 0..1) {
            p.color = if (layer == 0) 0xFFB8F0C8.toInt() else 0xFF8FE0AE.toInt()
            val base = h * (0.62f + 0.08f * layer)
            val path = Path()
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w + 10f) {
                path.lineTo(x, base - h * 0.05f * sin(x / w * (3f + layer * 2f) * PI.toFloat() + layer))
                x += 10f
            }
            path.lineTo(w, h)
            path.close()
            c.drawPath(path, p)
        }
        val xs = floatArrayOf(0.1f, 0.36f, 0.68f, 0.92f)
        for ((i, fx) in xs.withIndex()) {
            val x = fx * w
            val base = h * (0.7f + 0.03f * (i % 2))
            p.color = 0xFF9C6B4F.toInt()
            c.drawRect(x - 8f, base - h * 0.18f, x + 8f, base, p)
            val r = h * (0.1f + 0.015f * (i % 3))
            p.color = 0xFFFFB3D1.toInt()
            c.drawCircle(x, base - h * 0.22f, r, p)
            p.color = 0xFFFF8FBC.toInt()
            c.drawCircle(x - r * 0.6f, base - h * 0.18f, r * 0.7f, p)
            c.drawCircle(x + r * 0.6f, base - h * 0.19f, r * 0.7f, p)
        }
        p.color = 0xFF7ED6A0.toInt()
        c.drawRect(0f, h * 0.88f, w, h, p)
        p.color = 0xFFFFC2DD.toInt()
        for (q in parts) {
            val x = ((q[0] + sin(time * q[3] * 8f + q[1] * 6f) * 0.03f) % 1f) * w
            val y = ((q[1] + time * q[3]) % 1f) * h
            c.save()
            c.rotate(time * 60f * q[3] * 10f, x, y)
            c.drawOval(RectF(x - 7f * q[2], y - 4f * q[2], x + 7f * q[2], y + 4f * q[2]), p)
            c.restore()
        }
    }

    private fun cafe(c: Canvas, w: Float, h: Float) {
        fillGrad(c, "cafe", 0xFFFFE9D6.toInt(), 0xFFFFC9B0.toInt())
        // окно
        p.color = 0xFF9C6B4F.toInt()
        c.drawRoundRect(RectF(w * 0.08f, h * 0.18f, w * 0.52f, h * 0.55f), 14f, 14f, p)
        p.color = 0xFFBDE7FF.toInt()
        c.drawRoundRect(RectF(w * 0.1f, h * 0.2f, w * 0.5f, h * 0.53f), 10f, 10f, p)
        p.color = 0x88FFFFFF.toInt()
        c.drawRect(w * 0.29f, h * 0.2f, w * 0.31f, h * 0.53f, p)
        c.drawRect(w * 0.1f, h * 0.365f, w * 0.5f, h * 0.38f, p)
        // полка с чашками
        p.color = 0xFF9C6B4F.toInt()
        c.drawRect(w * 0.6f, h * 0.36f, w * 0.95f, h * 0.375f, p)
        val cups = intArrayOf(0xFFFF9ACB.toInt(), 0xFF8EC9FF.toInt(), 0xFFFFD84D.toInt(), 0xFFB79BF0.toInt())
        for (i in 0..3) {
            p.color = cups[i]
            val cx = w * (0.64f + 0.08f * i)
            c.drawRoundRect(RectF(cx - 14f, h * 0.31f, cx + 14f, h * 0.36f), 6f, 6f, p)
        }
        // лампы
        for (i in 0..2) {
            val x = w * (0.2f + 0.3f * i)
            p.color = 0xFF8D6BD6.toInt()
            c.drawRect(x - 1.5f, 0f, x + 1.5f, h * 0.1f, p)
            p.color = 0x33FFE08A
            c.drawCircle(x, h * 0.12f, 46f + sin(time * 2f + i) * 3f, p)
            p.color = 0xFFFFE08A.toInt()
            c.drawCircle(x, h * 0.12f, 18f, p)
        }
        // стол и пол
        p.color = 0xFFC8946A.toInt()
        c.drawRect(0f, h * 0.82f, w, h, p)
        p.color = 0xFFE5B38B.toInt()
        c.drawRect(0f, h * 0.8f, w, h * 0.83f, p)
        // чашка с паром
        val cx = w * 0.78f
        val cy = h * 0.78f
        p.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(RectF(cx - 30f, cy - 24f, cx + 30f, cy + 4f), 10f, 10f, p)
        for (k in 0..4) {
            val t = (time * 0.4f + k * 0.2f) % 1f
            p.color = ((0.55f * (1f - t) * 255).toInt() shl 24) or 0xFFFFFF
            c.drawCircle(cx + sin(t * 9f + k) * 10f, cy - 30f - t * h * 0.12f, 8f + t * 10f, p)
        }
    }

    private fun aqua(c: Canvas, w: Float, h: Float) {
        fillGrad(c, "aqua", 0xFF4FC3F7.toInt(), 0xFF0B2A5B.toInt())
        p.color = 0x14FFFFFF
        for (i in 0..3) {
            val x = w * (0.1f + 0.25f * i) + sin(time * 0.4f + i) * 20f
            val path = Path().apply { moveTo(x, 0f); lineTo(x + w * 0.12f, 0f); lineTo(x + w * 0.3f, h); lineTo(x + w * 0.1f, h); close() }
            c.drawPath(path, p)
        }
        // водоросли и песок
        p.style = Paint.Style.STROKE
        p.strokeWidth = 10f
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0xFF2BB673.toInt()
        for (i in 0..8) {
            val x = w * (0.05f + 0.11f * i)
            val path = Path().apply {
                moveTo(x, h)
                quadTo(x + sin(time + i) * 18f, h * 0.9f, x + sin(time * 0.8f + i) * 12f, h * 0.8f - (i % 3) * 20f)
            }
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
        p.color = 0xFFE8D3A8.toInt()
        c.drawRect(0f, h * 0.95f, w, h, p)
        // рыбки
        val fc = intArrayOf(0xFFFF9966.toInt(), 0xFFFF8FC7.toInt(), 0xFFFFD84D.toInt(), 0xFFB79BF0.toInt(), 0xFF7FFFD4.toInt())
        for (i in 0..4) {
            val dir = if (i % 2 == 0) 1f else -1f
            var fx = (i * 0.23f + time * (0.04f + 0.01f * i)) % 1.3f - 0.15f
            if (dir < 0) fx = 1f - fx
            val x = fx * w
            val y = h * (0.25f + 0.11f * i) + sin(time * 2f + i) * 8f
            p.color = fc[i]
            c.drawOval(RectF(x - 28f, y - 14f, x + 28f, y + 14f), p)
            val tail = Path().apply { moveTo(x - dir * 22f, y); lineTo(x - dir * 44f, y - 14f); lineTo(x - dir * 44f, y + 14f); close() }
            c.drawPath(tail, p)
            p.color = 0xFF2B2B5E.toInt()
            c.drawCircle(x + dir * 14f, y - 3f, 3.5f, p)
        }
        // медузы
        for (i in 0..1) {
            val x = w * (0.25f + 0.5f * i) + sin(time * 0.5f + i * 2f) * 20f
            val y = h * (0.35f + 0.12f * i) + sin(time * 1.2f + i) * 14f
            p.color = 0xAAFFB3E6.toInt()
            c.drawArc(RectF(x - 34f, y - 30f, x + 34f, y + 30f), 180f, 180f, true, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            p.color = 0x99FFB3E6.toInt()
            for (k in -2..2) {
                val path = Path().apply { moveTo(x + k * 12f, y); quadTo(x + k * 12f + sin(time * 2f + k) * 8f, y + 28f, x + k * 12f, y + 52f) }
                c.drawPath(path, p)
            }
            p.style = Paint.Style.FILL
        }
        // пузырьки
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f
        p.color = 0xAAFFFFFF.toInt()
        for (q in parts) {
            val x = q[0] * w + sin(time + q[1] * 9f) * 8f
            val y = h - ((q[1] + time * q[3] * 1.5f) % 1f) * h
            c.drawCircle(x, y, 5f + 6f * q[2], p)
        }
        p.style = Paint.Style.FILL
    }

    private fun funfair(c: Canvas, w: Float, h: Float) {
        fillGrad(c, "fun", 0xFF3B2A7A.toInt(), 0xFFFF8FBF.toInt())
        p.color = 0xCCFFFFFF.toInt()
        for (i in 0..11) c.drawCircle(w * ((i * 0.37f) % 1f), h * 0.3f * ((i * 0.53f) % 1f), 2f + (i % 3), p)
        // колесо обозрения
        val cx = w * 0.68f
        val cy = h * 0.46f
        val r = h * 0.26f
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5f
        p.color = 0xFFFFFFFF.toInt()
        c.drawLine(cx, cy, cx - r * 0.5f, h * 0.88f, p)
        c.drawLine(cx, cy, cx + r * 0.5f, h * 0.88f, p)
        c.drawCircle(cx, cy, r, p)
        c.drawCircle(cx, cy, r * 0.5f, p)
        val ang = time * 0.35f
        val gc = intArrayOf(0xFFFF4F98.toInt(), 0xFFFFD84D.toInt(), 0xFF7FD3FF.toInt(), 0xFF7FFFD4.toInt())
        for (i in 0 until 10) {
            val a = ang + i * (2f * PI.toFloat() / 10f)
            val x = cx + cos(a) * r
            val y = cy + sin(a) * r
            p.strokeWidth = 3f
            p.color = 0xFFFFFFFF.toInt()
            c.drawLine(cx, cy, x, y, p)
            p.style = Paint.Style.FILL
            p.color = gc[i % 4]
            c.drawRoundRect(RectF(x - 16f, y, x + 16f, y + 26f), 8f, 8f, p)
            p.style = Paint.Style.STROKE
        }
        p.style = Paint.Style.FILL
        // палатка
        p.color = 0xFFFF9ACB.toInt()
        c.drawRect(w * 0.04f, h * 0.7f, w * 0.34f, h * 0.9f, p)
        for (i in 0..5) {
            p.color = if (i % 2 == 0) 0xFFFF4F98.toInt() else 0xFFFFFFFF.toInt()
            val path = Path().apply {
                moveTo(w * 0.04f + i * w * 0.05f, h * 0.7f)
                lineTo(w * 0.04f + (i + 1) * w * 0.05f, h * 0.7f)
                lineTo(w * 0.19f, h * 0.58f)
                close()
            }
            c.drawPath(path, p)
        }
        // гирлянда
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = 0x99FFFFFF.toInt()
        val g = Path().apply { moveTo(0f, h * 0.05f); quadTo(w * 0.5f, h * 0.2f, w, h * 0.05f) }
        c.drawPath(g, p)
        p.style = Paint.Style.FILL
        for (i in 0..10) {
            val t = i / 10f
            val x = w * t
            val y = h * 0.05f + 2f * (h * 0.15f) * t * (1f - t) * 1.0f
            val on = ((time * 2f + i) % 2f) < 1f
            p.color = if (on) gc[i % 4] else 0x55FFFFFF
            c.drawCircle(x, y + 4f, 7f, p)
        }
        p.color = 0xFF2B1B4E.toInt()
        c.drawRect(0f, h * 0.9f, w, h, p)
        // шарики
        for (i in 0..5) {
            val q = parts[i]
            val x = q[0] * w
            val y = h - ((q[1] + time * q[3] * 0.6f) % 1f) * h * 1.1f
            p.color = gc[i % 4]
            c.drawOval(RectF(x - 14f, y - 18f, x + 14f, y + 18f), p)
        }
    }
}
