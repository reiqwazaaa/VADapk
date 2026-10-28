package com.vad.downloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Фон свидания: свои картинки (слоты bg_*), фото комнат, либо простая заливка-заглушка.
 * Нарисованные мной фоны удалены — вместо них ставятся фото через режим разработчика.
 */
class SceneView(ctx: Context) : View(ctx) {
    var scene: String = "street"
        set(v) {
            if (field != v) {
                field = v
                alpha = 0.35f
                animate().alpha(1f).setDuration(600).start()
                invalidate()
            }
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmps = HashMap<Int, Bitmap?>()
    private val builtIn = setOf("street", "park", "cafe", "aqua", "fun", "evening")

    private fun drawCrop(c: Canvas, w: Float, h: Float, bmp: Bitmap) {
        val scale = maxOf(w / bmp.width, h / bmp.height)
        val sw = (w / scale).toInt().coerceAtMost(bmp.width)
        val sh = (h / scale).toInt().coerceAtMost(bmp.height)
        val sx = (bmp.width - sw) / 2
        val sy = (bmp.height - sh) / 2
        c.drawBitmap(bmp, Rect(sx, sy, sx + sw, sy + sh), RectF(0f, 0f, w, h), null)
    }

    private fun plain(c: Canvas, night: Boolean) {
        p.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            if (night) 0xFF2B1B4E.toInt() else 0xFFFFE6F3.toInt(),
            if (night) 0xFF45307A.toInt() else 0xFFE9DCFF.toInt(), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        val custom = if (scene in builtIn) Sprites.bitmap(context, "bg_$scene") else null
        when {
            custom != null -> drawCrop(c, w, h, custom)
            scene.startsWith("room_home") -> room(c, w, h, R.drawable.room_home, scene.endsWith("night"))
            scene.startsWith("room_her") -> room(c, w, h, R.drawable.room_her, scene.endsWith("night"))
            else -> plain(c, scene == "evening")
        }
    }

    private fun room(c: Canvas, w: Float, h: Float, res: Int, night: Boolean) {
        val custom = Sprites.bitmap(context, if (res == R.drawable.room_her) "room_her" else "room_home")
        val bmp = custom ?: bmps.getOrPut(res) {
            try { BitmapFactory.decodeResource(resources, res) } catch (_: Throwable) { null }
        }
        if (bmp == null) { plain(c, night); return }
        drawCrop(c, w, h, bmp)
        p.color = if (night) 0x99120A33.toInt() else 0x33FFFFFF
        c.drawRect(0f, 0f, w, h, p)
    }
}
