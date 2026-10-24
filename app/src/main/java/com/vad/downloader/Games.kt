package com.vad.downloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Четыре небольшие оригинальные мини-игры для "ворот" перед загрузкой.
 * Жанры классические (змейка, лабиринт, танки, бегалка), персонажи и графика - свои.
 */

private val DX = intArrayOf(0, 1, 0, -1)   // 0 вверх, 1 вправо, 2 вниз, 3 влево
private val DY = intArrayOf(-1, 0, 1, 0)

private val heartPath = Path().apply {
    moveTo(0f, 1f)
    cubicTo(-1.6f, -0.1f, -0.7f, -1.1f, 0f, -0.35f)
    cubicTo(0.7f, -1.1f, 1.6f, -0.1f, 0f, 1f)
    close()
}
private val starPath = Path().apply {
    moveTo(0f, -1f)
    quadTo(0.18f, -0.18f, 1f, 0f)
    quadTo(0.18f, 0.18f, 0f, 1f)
    quadTo(-0.18f, 0.18f, -1f, 0f)
    quadTo(-0.18f, -0.18f, 0f, -1f)
    close()
}

private fun drawShape(c: Canvas, path: Path, cx: Float, cy: Float, r: Float, p: Paint) {
    c.save()
    c.translate(cx, cy)
    c.scale(r, r)
    c.drawPath(path, p)
    c.restore()
}

/** Милая мордочка с ушками (игрок в лабиринте и в бегалке). */
private fun drawCatFace(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint, body: Int = 0xFFFF9ACB.toInt()) {
    p.style = Paint.Style.FILL
    p.color = body
    val ear = Path().apply {
        moveTo(cx - r * 0.95f, cy - r * 0.2f)
        lineTo(cx - r * 0.7f, cy - r * 1.35f)
        lineTo(cx - r * 0.1f, cy - r * 0.75f)
        close()
        moveTo(cx + r * 0.95f, cy - r * 0.2f)
        lineTo(cx + r * 0.7f, cy - r * 1.35f)
        lineTo(cx + r * 0.1f, cy - r * 0.75f)
        close()
    }
    c.drawPath(ear, p)
    c.drawCircle(cx, cy, r, p)
    p.color = 0xFF3E2670.toInt()
    c.drawCircle(cx - r * 0.38f, cy - r * 0.05f, r * 0.17f, p)
    c.drawCircle(cx + r * 0.38f, cy - r * 0.05f, r * 0.17f, p)
    p.color = 0xFFFFFFFF.toInt()
    c.drawCircle(cx - r * 0.43f, cy - r * 0.1f, r * 0.06f, p)
    c.drawCircle(cx + r * 0.33f, cy - r * 0.1f, r * 0.06f, p)
    p.color = 0xFFFF6F9F.toInt()
    c.drawCircle(cx, cy + r * 0.22f, r * 0.08f, p)
}

abstract class GameView(ctx: Context, private val onFinish: (Boolean) -> Unit) : View(ctx) {
    protected val rnd = Random()
    protected val density = resources.displayMetrics.density
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = 0L
    @Volatile var finished = false
        private set
    private var sx = 0f
    private var sy = 0f

    protected abstract fun update(dt: Float)
    protected abstract fun render(c: Canvas)
    protected open fun onSwipe(dir: Int) {}

    protected fun hud(c: Canvas, text: String, x: Float, y: Float, color: Int = 0xFF4B2E83.toInt()) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = 18f * density
        paint.typeface = Typeface.DEFAULT_BOLD
        c.drawText(text, x, y, paint)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        if (!finished) update(dt)
        render(canvas)
        if (!finished) postInvalidateOnAnimation()
    }

    protected fun finish(win: Boolean) {
        if (finished) return
        finished = true
        post { onFinish(win) }
    }

    /** По умолчанию - управление свайпами. */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x
                sy = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - sx
                val dy = e.y - sy
                if (max(abs(dx), abs(dy)) > 22f * density) {
                    val d = if (abs(dx) > abs(dy)) (if (dx > 0) 1 else 3) else (if (dy > 0) 2 else 0)
                    onSwipe(d)
                    sx = e.x
                    sy = e.y
                }
            }
        }
        return true
    }
}

// ============================================================ 1. Змейка

class SnakeGame(ctx: Context, onFinish: (Boolean) -> Unit) : GameView(ctx, onFinish) {
    private val n = 14
    private val goal = 8
    private var cell = 1f
    private var ox = 0f
    private var oy = 0f
    private val body = java.util.ArrayDeque<IntArray>()
    private var dir = 1
    private var nextDir = 1
    private var acc = 0f
    private val stepTime = 0.17f
    private var food = IntArray(2)
    private var eaten = 0

    init {
        body.add(intArrayOf(7, 7))
        body.add(intArrayOf(6, 7))
        body.add(intArrayOf(5, 7))
        spawnFood()
    }

    private fun spawnFood() {
        while (true) {
            val x = rnd.nextInt(n)
            val y = rnd.nextInt(n)
            if (body.none { it[0] == x && it[1] == y }) {
                food = intArrayOf(x, y)
                return
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cell = min(w, h) / n.toFloat()
        ox = (w - cell * n) / 2f
        oy = (h - cell * n) / 2f
    }

    override fun onSwipe(dir: Int) {
        if (dir != (this.dir + 2) % 4) nextDir = dir
    }

    override fun update(dt: Float) {
        acc += dt
        while (acc >= stepTime && !finished) {
            acc -= stepTime
            step()
        }
    }

    private fun step() {
        dir = nextDir
        val head = body.peekFirst()!!
        val nx = head[0] + DX[dir]
        val ny = head[1] + DY[dir]
        if (nx < 0 || ny < 0 || nx >= n || ny >= n) {
            finish(false)
            return
        }
        val eating = nx == food[0] && ny == food[1]
        val tail = body.peekLast()
        for (s in body) {
            if (s[0] == nx && s[1] == ny && !(s === tail && !eating)) {
                finish(false)
                return
            }
        }
        body.addFirst(intArrayOf(nx, ny))
        if (eating) {
            eaten++
            if (eaten >= goal) {
                finish(true)
                return
            }
            spawnFood()
        } else {
            body.removeLast()
        }
    }

    override fun render(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFF0F7.toInt()
        c.drawRoundRect(RectF(ox, oy, ox + cell * n, oy + cell * n), 18f, 18f, paint)
        paint.color = 0xFFFFE0EF.toInt()
        for (y in 0 until n) for (x in 0 until n) {
            if ((x + y) % 2 == 0) c.drawRect(ox + x * cell, oy + y * cell, ox + (x + 1) * cell, oy + (y + 1) * cell, paint)
        }
        paint.color = 0xFFFF4F7B.toInt()
        drawShape(c, heartPath, ox + (food[0] + 0.5f) * cell, oy + (food[1] + 0.5f) * cell, cell * 0.38f, paint)
        var i = 0
        for (s in body) {
            paint.color = if (i == 0) 0xFFFF4F98.toInt() else 0xFFFF8FC7.toInt()
            val r = RectF(ox + s[0] * cell + 2, oy + s[1] * cell + 2, ox + (s[0] + 1) * cell - 2, oy + (s[1] + 1) * cell - 2)
            c.drawRoundRect(r, cell * 0.35f, cell * 0.35f, paint)
            if (i == 0) {
                paint.color = 0xFF3E2670.toInt()
                val cx = ox + (s[0] + 0.5f) * cell
                val cy = oy + (s[1] + 0.5f) * cell
                c.drawCircle(cx - cell * 0.15f, cy - cell * 0.05f, cell * 0.08f, paint)
                c.drawCircle(cx + cell * 0.15f, cy - cell * 0.05f, cell * 0.08f, paint)
            }
            i++
        }
        hud(c, "♥ $eaten/$goal", ox + 12f, oy + 26f * density)
    }
}

// ============================================================ 2. Лабиринт

class MazeGame(ctx: Context, onFinish: (Boolean) -> Unit) : GameView(ctx, onFinish) {
    private val layout = arrayOf(
        "###########",
        "#....#....#",
        "#.##.#.##.#",
        "#.........#",
        "#.##.#.##.#",
        "#....#....#",
        "#.##.#.##.#",
        "#.........#",
        "#.##.#.##.#",
        "#....#....#",
        "###########"
    )
    private val n = 11
    private val wall = Array(n) { r -> BooleanArray(n) { c -> layout[r][c] == '#' } }
    private val star = Array(n) { r -> BooleanArray(n) { c -> layout[r][c] == '.' && (r + c) % 2 == 0 } }
    private var starsLeft = 0
    private var px = 5
    private var py = 7
    private var dir = -1
    private var want = -1
    private var pAcc = 0f
    private var eAcc = 0f
    private class Enemy(var x: Int, var y: Int, var last: Int)
    private val enemies = arrayListOf(Enemy(1, 1, 1), Enemy(9, 1, 3))
    private var cell = 1f
    private var ox = 0f
    private var oy = 0f
    private var anim = 0f

    init {
        for (r in 0 until n) for (c in 0 until n) if (star[r][c]) starsLeft++
        star[py][px] = false
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cell = min(w, h) / n.toFloat()
        ox = (w - cell * n) / 2f
        oy = (h - cell * n) / 2f
    }

    override fun onSwipe(dir: Int) {
        want = dir
    }

    private fun free(x: Int, y: Int) = x in 0 until n && y in 0 until n && !wall[y][x]

    override fun update(dt: Float) {
        anim += dt
        pAcc += dt
        eAcc += dt
        while (pAcc >= 0.17f && !finished) {
            pAcc -= 0.17f
            if (want >= 0 && free(px + DX[want], py + DY[want])) dir = want
            if (dir >= 0 && free(px + DX[dir], py + DY[dir])) {
                px += DX[dir]
                py += DY[dir]
                if (star[py][px]) {
                    star[py][px] = false
                    starsLeft--
                    if (starsLeft <= 0) {
                        finish(true)
                        return
                    }
                }
                if (enemies.any { it.x == px && it.y == py }) {
                    finish(false)
                    return
                }
            }
        }
        while (eAcc >= 0.30f && !finished) {
            eAcc -= 0.30f
            for (e in enemies) {
                val opts = (0 until 4).filter { free(e.x + DX[it], e.y + DY[it]) }
                val noBack = opts.filter { it != (e.last + 2) % 4 }
                val pool = if (noBack.isNotEmpty()) noBack else opts
                if (pool.isEmpty()) continue
                val pick = if (rnd.nextFloat() < 0.55f) {
                    pool.minByOrNull { abs(e.x + DX[it] - px) + abs(e.y + DY[it] - py) }!!
                } else {
                    pool[rnd.nextInt(pool.size)]
                }
                e.x += DX[pick]
                e.y += DY[pick]
                e.last = pick
                if (e.x == px && e.y == py) {
                    finish(false)
                    return
                }
            }
        }
    }

    override fun render(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFFF4ECFF.toInt()
        c.drawRoundRect(RectF(ox, oy, ox + cell * n, oy + cell * n), 18f, 18f, paint)
        for (r in 0 until n) for (col in 0 until n) {
            val x = ox + col * cell
            val y = oy + r * cell
            if (wall[r][col]) {
                paint.color = 0xFFB79BF0.toInt()
                c.drawRoundRect(RectF(x + 2, y + 2, x + cell - 2, y + cell - 2), cell * 0.25f, cell * 0.25f, paint)
            } else if (star[r][col]) {
                paint.color = 0xFFFFC93C.toInt()
                drawShape(c, starPath, x + cell / 2, y + cell / 2, cell * 0.28f, paint)
            }
        }
        for (e in enemies) {
            val cx = ox + (e.x + 0.5f) * cell
            val cy = oy + (e.y + 0.5f) * cell
            paint.color = 0xFF5CC8FF.toInt()
            c.drawRoundRect(RectF(cx - cell * 0.38f, cy - cell * 0.30f, cx + cell * 0.38f, cy + cell * 0.38f), cell * 0.3f, cell * 0.3f, paint)
            paint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(cx - cell * 0.14f, cy - cell * 0.05f, cell * 0.1f, paint)
            c.drawCircle(cx + cell * 0.14f, cy - cell * 0.05f, cell * 0.1f, paint)
            paint.color = 0xFF2B2B5E.toInt()
            c.drawCircle(cx - cell * 0.12f, cy - cell * 0.03f, cell * 0.05f, paint)
            c.drawCircle(cx + cell * 0.16f, cy - cell * 0.03f, cell * 0.05f, paint)
        }
        drawCatFace(c, ox + (px + 0.5f) * cell, oy + (py + 0.55f) * cell, cell * 0.36f, paint)
        hud(c, "★ $starsLeft", ox + 12f, oy + 26f * density)
    }
}

// ============================================================ 3. Танковая арена

class TankGame(ctx: Context, onFinish: (Boolean) -> Unit) : GameView(ctx, onFinish) {
    private val n = 13
    private val layout = arrayOf(
        ".............",
        ".B.B.B.B.B.B.",
        ".B.B.B.B.B.B.",
        ".B.B.S.S.B.B.",
        ".............",
        "BB.BB.B.BB.BB",
        ".............",
        ".S.B.B.B.B.S.",
        ".B.B.....B.B.",
        ".B.........B.",
        ".............",
        ".B.BB...BB.B.",
        "............."
    )
    private val tiles = Array(n) { r -> CharArray(n) { c -> layout[r].getOrElse(c) { '.' } } }
    private class Tank(var x: Float, var y: Float, var dir: Int, val player: Boolean) {
        var hp = 1
        var cd = 0f
        var turnT = 0f
    }
    private class Bullet(var x: Float, var y: Float, val dir: Int, val fromPlayer: Boolean)
    private val player = Tank(6.5f, 11.5f, 0, true).also { it.hp = 3 }
    private val enemies = ArrayList<Tank>()
    private val bullets = ArrayList<Bullet>()
    private var spawned = 0
    private var kills = 0
    private var spawnT = 0.4f
    private var heldDir = -1
    private var fireHeld = false
    private val goal = 5

    private var tile = 1f
    private var ox = 0f
    private var oy = 0f
    private var padX = 0f
    private var padY = 0f
    private var padR = 0f
    private var fireX = 0f
    private var fireY = 0f
    private var fireR = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        tile = min(w / n.toFloat(), h * 0.66f / n)
        ox = (w - tile * n) / 2f
        oy = 4f * density
        val areaBottom = oy + tile * n
        val ch = h - areaBottom
        padR = min(ch * 0.30f, w * 0.14f)
        padX = padR * 1.7f
        padY = areaBottom + ch / 2f
        fireR = padR * 0.95f
        fireX = w - fireR * 1.8f
        fireY = padY
    }

    private fun solidAt(tx: Int, ty: Int): Boolean {
        if (tx < 0 || ty < 0 || tx >= n || ty >= n) return true
        val t = tiles[ty][tx]
        return t == 'B' || t == 'S'
    }

    private fun canStand(x: Float, y: Float): Boolean {
        val h = 0.38f
        if (x - h < 0f || y - h < 0f || x + h > n || y + h > n) return false
        return !(solidAt(floor(x - h).toInt(), floor(y - h).toInt()) ||
            solidAt(floor(x + h).toInt(), floor(y - h).toInt()) ||
            solidAt(floor(x - h).toInt(), floor(y + h).toInt()) ||
            solidAt(floor(x + h).toInt(), floor(y + h).toInt()))
    }

    private fun overlapsTank(self: Tank, x: Float, y: Float): Boolean {
        if (self !== player && hypot(player.x - x, player.y - y) < 0.8f) return true
        for (e in enemies) if (e !== self && hypot(e.x - x, e.y - y) < 0.8f) return true
        return false
    }

    private fun turn(t: Tank, d: Int) {
        if (d == t.dir) return
        t.dir = d
        // выравнивание по клетке, чтобы удобно заезжать в узкие проходы
        if (d == 0 || d == 2) {
            val sx = floor(t.x).toFloat() + 0.5f
            if (canStand(sx, t.y) && !overlapsTank(t, sx, t.y)) t.x = sx
        } else {
            val sy = floor(t.y).toFloat() + 0.5f
            if (canStand(t.x, sy) && !overlapsTank(t, t.x, sy)) t.y = sy
        }
    }

    private fun move(t: Tank, dt: Float, speed: Float): Boolean {
        val nx = t.x + DX[t.dir] * speed * dt
        val ny = t.y + DY[t.dir] * speed * dt
        if (canStand(nx, ny) && !overlapsTank(t, nx, ny)) {
            t.x = nx
            t.y = ny
            return true
        }
        return false
    }

    private fun shoot(t: Tank) {
        bullets.add(Bullet(t.x + DX[t.dir] * 0.5f, t.y + DY[t.dir] * 0.5f, t.dir, t.player))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        var d = -1
        var f = false
        val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
        val up = if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        if (!cancel) {
            for (i in 0 until e.pointerCount) {
                if (i == up) continue
                val x = e.getX(i)
                val y = e.getY(i)
                if (hypot(x - fireX, y - fireY) < fireR * 1.3f) {
                    f = true
                } else {
                    val dx = x - padX
                    val dy = y - padY
                    if (hypot(dx, dy) < padR * 1.7f) {
                        d = if (abs(dx) > abs(dy)) (if (dx > 0) 1 else 3) else (if (dy > 0) 2 else 0)
                    }
                }
            }
        }
        heldDir = d
        fireHeld = f
        return true
    }

    override fun update(dt: Float) {
        // игрок
        if (heldDir >= 0) {
            turn(player, heldDir)
            move(player, dt, 3.4f)
        }
        player.cd -= dt
        if (fireHeld && player.cd <= 0f) {
            shoot(player)
            player.cd = 0.45f
        }
        // появление врагов
        spawnT -= dt
        if (spawned < goal && enemies.size < 3 && spawnT <= 0f) {
            val sp = floatArrayOf(0.5f, 6.5f, 12.5f)[rnd.nextInt(3)]
            if (!overlapsTank(player, sp, 0.5f) && enemies.none { hypot(it.x - sp, it.y - 0.5f) < 1f }) {
                enemies.add(Tank(sp, 0.5f, 2, false).also { it.cd = 0.8f })
                spawned++
                spawnT = 2.2f
            }
        }
        // враги
        for (en in enemies) {
            en.cd -= dt
            en.turnT -= dt
            val moved = move(en, dt, 1.9f)
            if (!moved || en.turnT <= 0f) {
                val r = rnd.nextFloat()
                val d = when {
                    r < 0.4f -> 2
                    r < 0.55f -> if (en.x < player.x) 1 else 3
                    else -> rnd.nextInt(4)
                }
                turn(en, d)
                en.turnT = 0.7f + rnd.nextFloat()
            }
            if (en.cd <= 0f && rnd.nextFloat() < dt * 1.6f) {
                shoot(en)
                en.cd = 1.3f
            }
        }
        // пули
        val it = bullets.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.x += DX[b.dir] * 8f * dt
            b.y += DY[b.dir] * 8f * dt
            val tx = floor(b.x).toInt()
            val ty = floor(b.y).toInt()
            if (tx < 0 || ty < 0 || tx >= n || ty >= n) {
                it.remove()
                continue
            }
            val t = tiles[ty][tx]
            if (t == 'B' || t == 'S') {
                if (t == 'B') tiles[ty][tx] = '.'
                it.remove()
                continue
            }
            if (b.fromPlayer) {
                val hit = enemies.firstOrNull { hypot(it.x - b.x, it.y - b.y) < 0.5f }
                if (hit != null) {
                    hit.hp--
                    it.remove()
                    if (hit.hp <= 0) {
                        enemies.remove(hit)
                        kills++
                        if (kills >= goal) {
                            finish(true)
                            return
                        }
                    }
                }
            } else if (hypot(player.x - b.x, player.y - b.y) < 0.5f) {
                it.remove()
                player.hp--
                if (player.hp <= 0) {
                    finish(false)
                    return
                }
            }
        }
    }

    private fun drawTank(c: Canvas, t: Tank, body: Int, top: Int) {
        val cx = ox + t.x * tile
        val cy = oy + t.y * tile
        c.save()
        c.translate(cx, cy)
        c.rotate(t.dir * 90f)
        paint.style = Paint.Style.FILL
        paint.color = body
        c.drawRoundRect(RectF(-tile * 0.38f, -tile * 0.38f, tile * 0.38f, tile * 0.38f), tile * 0.15f, tile * 0.15f, paint)
        paint.color = 0xFF5A4A7A.toInt()
        c.drawRect(-tile * 0.42f, -tile * 0.36f, -tile * 0.30f, tile * 0.36f, paint)
        c.drawRect(tile * 0.30f, -tile * 0.36f, tile * 0.42f, tile * 0.36f, paint)
        paint.color = top
        c.drawRect(-tile * 0.07f, -tile * 0.55f, tile * 0.07f, 0f, paint)
        c.drawCircle(0f, 0f, tile * 0.2f, paint)
        c.restore()
    }

    override fun render(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF2E2352.toInt()
        c.drawRoundRect(RectF(ox, oy, ox + tile * n, oy + tile * n), 14f, 14f, paint)
        for (r in 0 until n) for (col in 0 until n) {
            val x = ox + col * tile
            val y = oy + r * tile
            when (tiles[r][col]) {
                'B' -> {
                    paint.color = 0xFFFF9B8A.toInt()
                    c.drawRoundRect(RectF(x + 1, y + 1, x + tile - 1, y + tile - 1), 4f, 4f, paint)
                    paint.color = 0xFFE5776A.toInt()
                    c.drawRect(x + 1, y + tile / 2 - 1, x + tile - 1, y + tile / 2 + 1, paint)
                    c.drawRect(x + tile / 2 - 1, y + 1, x + tile / 2 + 1, y + tile / 2, paint)
                }
                'S' -> {
                    paint.color = 0xFFB9C3D9.toInt()
                    c.drawRoundRect(RectF(x + 1, y + 1, x + tile - 1, y + tile - 1), 4f, 4f, paint)
                    paint.color = 0xFF8D98B5.toInt()
                    c.drawRect(x + tile * 0.3f, y + tile * 0.3f, x + tile * 0.7f, y + tile * 0.7f, paint)
                }
            }
        }
        for (e in enemies) drawTank(c, e, 0xFF63D0C3.toInt(), 0xFF2FA394.toInt())
        drawTank(c, player, 0xFFFF8FC7.toInt(), 0xFFFF5FA2.toInt())
        paint.color = 0xFFFFF2A8.toInt()
        for (b in bullets) c.drawCircle(ox + b.x * tile, oy + b.y * tile, tile * 0.1f, paint)

        hud(c, "♥ ${player.hp}   ✖ $kills/$goal", ox + 10f, oy + 24f * density, 0xFFFFE6F2.toInt())
        // кнопки управления
        paint.color = 0x55FFFFFF
        c.drawCircle(padX, padY, padR * 1.5f, paint)
        paint.color = 0xAAFFFFFF.toInt()
        val a = padR * 0.95f
        for (d in 0 until 4) {
            val tx = padX + DX[d] * a
            val ty = padY + DY[d] * a
            drawShape(c, starPath, tx, ty, padR * 0.3f, paint)
        }
        paint.color = if (fireHeld) 0xFFFF4F98.toInt() else 0xCCFF8FC7.toInt()
        c.drawCircle(fireX, fireY, fireR, paint)
        paint.color = 0xFFFFFFFF.toInt()
        drawShape(c, starPath, fireX, fireY, fireR * 0.55f, paint)
    }
}

// ============================================================ 4. Бегалка

class RunnerGame(ctx: Context, onFinish: (Boolean) -> Unit) : GameView(ctx, onFinish) {
    private class Gap(val x0: Float, val x1: Float)
    private class Spike(val x: Float)
    private class Pickup(val x: Float, val y: Float) {
        var taken = false
    }

    private val finishX = 200f
    private var u = 1f
    private var groundY = 0f
    private var w = 0f
    private var h = 0f
    private var worldX = 0f
    private var py = 0f
    private var vy = 0f
    private var onGround = true
    private var falling = false
    private var speed = 6f
    private var lives = 3
    private var invul = 0f
    private var hearts = 0
    private var time = 0f
    private val gaps = ArrayList<Gap>()
    private val spikes = ArrayList<Spike>()
    private val pickups = ArrayList<Pickup>()
    private var sky: Shader? = null

    init {
        var x = 16f
        while (x < finishX - 12f) {
            val r = rnd.nextFloat()
            when {
                r < 0.45f -> {
                    spikes.add(Spike(x))
                    pickups.add(Pickup(x - 1.1f, 1.2f)); pickups.add(Pickup(x, 2.0f)); pickups.add(Pickup(x + 1.1f, 1.2f))
                }
                r < 0.70f -> {
                    spikes.add(Spike(x)); spikes.add(Spike(x + 1.0f))
                    pickups.add(Pickup(x - 0.6f, 1.5f)); pickups.add(Pickup(x + 0.5f, 2.2f)); pickups.add(Pickup(x + 1.6f, 1.5f))
                }
                r < 0.90f -> {
                    gaps.add(Gap(x, x + 2.4f))
                    pickups.add(Pickup(x + 0.4f, 1.4f)); pickups.add(Pickup(x + 1.2f, 2.0f)); pickups.add(Pickup(x + 2.0f, 1.4f))
                }
                else -> {
                    for (i in 0..3) pickups.add(Pickup(x + i * 0.9f, 0.6f))
                }
            }
            x += 8f + rnd.nextFloat() * 4f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        this.w = w.toFloat()
        this.h = h.toFloat()
        u = h / 10f
        groundY = h * 0.72f
        sky = LinearGradient(0f, 0f, 0f, groundY, 0xFFBDE7FF.toInt(), 0xFFFFD6EC.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (onGround && !falling) {
                vy = 14f
                onGround = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (vy > 6f) vy *= 0.5f
        }
        return true
    }

    private fun gapAt(x: Float): Gap? = gaps.firstOrNull { x > it.x0 + 0.15f && x < it.x1 - 0.15f }

    override fun update(dt: Float) {
        time += dt
        invul = max(0f, invul - dt)
        speed = min(9.5f, 6f + worldX / 60f)
        worldX += speed * dt
        py += vy * dt
        vy -= 38f * dt
        if (!falling && py <= 0f) {
            if (gapAt(worldX) != null) {
                falling = true
                onGround = false
            } else {
                py = 0f
                vy = 0f
                onGround = true
            }
        }
        if (falling && py < -5f) {
            val g = gapAt(worldX)
            lives--
            if (lives <= 0) {
                finish(false)
                return
            }
            worldX = (g?.x1 ?: worldX) + 1f
            py = 0f
            vy = 0f
            falling = false
            onGround = true
            invul = 1.3f
        }
        if (invul <= 0f && py < 0.7f && !falling) {
            if (spikes.any { abs(it.x - worldX) < 0.65f }) {
                lives--
                invul = 1.3f
                if (lives <= 0) {
                    finish(false)
                    return
                }
            }
        }
        for (p in pickups) {
            if (!p.taken && abs(p.x - worldX) < 0.7f && abs(p.y - py) < 0.9f) {
                p.taken = true
                hearts++
            }
        }
        if (worldX >= finishX) finish(true)
    }

    private fun sxOf(wx: Float) = (wx - worldX) * u + w * 0.25f

    override fun render(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = sky
        c.drawRect(0f, 0f, w, groundY, paint)
        paint.shader = null
        // дальние и ближние холмы (параллакс)
        for (layer in 0..1) {
            paint.color = if (layer == 0) 0xFFE3D3FF.toInt() else 0xFFFFC2E0.toInt()
            val factor = if (layer == 0) 0.15f else 0.35f
            val amp = if (layer == 0) 2.2f * u else 1.4f * u
            val base = groundY - (if (layer == 0) 1.0f else 0.2f) * u
            val path = Path()
            path.moveTo(0f, groundY)
            var x = 0f
            while (x <= w + 12f) {
                val y = base - amp * (0.5f + 0.5f * sin((x + worldX * u * factor) / (u * (if (layer == 0) 4.5f else 3f))))
                path.lineTo(x, y)
                x += 12f
            }
            path.lineTo(w, groundY)
            path.close()
            c.drawPath(path, paint)
        }
        // земля
        paint.color = 0xFFFF9ACB.toInt()
        c.drawRect(0f, groundY, w, h, paint)
        paint.color = 0xFFFFC2E5.toInt()
        c.drawRect(0f, groundY, w, groundY + 0.35f * u, paint)
        paint.color = 0xFFF283B8.toInt()
        var sx = -((worldX * u) % (2f * u))
        while (sx < w) {
            c.drawRect(sx, groundY + 0.8f * u, sx + u, h, paint)
            sx += 2f * u
        }
        // ямы
        paint.color = 0xFF3A2466.toInt()
        for (g in gaps) {
            val a = sxOf(g.x0)
            val b = sxOf(g.x1)
            if (b > 0 && a < w) c.drawRect(a, groundY, b, h, paint)
        }
        // шипы
        paint.color = 0xFF8D6BD6.toInt()
        for (s in spikes) {
            val x = sxOf(s.x)
            if (x < -u || x > w + u) continue
            val p = Path()
            p.moveTo(x - 0.45f * u, groundY)
            p.lineTo(x, groundY - 0.85f * u)
            p.lineTo(x + 0.45f * u, groundY)
            p.close()
            c.drawPath(p, paint)
        }
        // сердечки
        paint.color = 0xFFFF4F7B.toInt()
        for (p in pickups) {
            if (p.taken) continue
            val x = sxOf(p.x)
            if (x < -u || x > w + u) continue
            drawShape(c, heartPath, x, groundY - p.y * u - 0.3f * u, 0.28f * u, paint)
        }
        // финиш
        val fx = sxOf(finishX)
        if (fx < w + u) {
            paint.color = 0xFFFFFFFF.toInt()
            c.drawRect(fx, groundY - 3.5f * u, fx + 0.12f * u, groundY, paint)
            paint.color = 0xFFFF4F98.toInt()
            val flag = Path()
            flag.moveTo(fx + 0.12f * u, groundY - 3.5f * u)
            flag.lineTo(fx + 1.8f * u, groundY - 3.0f * u)
            flag.lineTo(fx + 0.12f * u, groundY - 2.5f * u)
            flag.close()
            c.drawPath(flag, paint)
        }
        // игрок
        if (invul <= 0f || ((time * 14f).toInt() % 2 == 0)) {
            val bob = if (onGround && !falling) abs(sin(time * 14f)) * 0.08f * u else 0f
            drawCatFace(c, w * 0.25f, groundY - py * u - 0.55f * u - bob, 0.42f * u, paint)
        }
        // HUD
        hud(c, "♥".repeat(max(0, lives)) + "   ★ $hearts", 12f, 26f * density)
        paint.color = 0x55FFFFFF
        c.drawRoundRect(RectF(w * 0.35f, 14f * density, w - 12f, 22f * density), 8f, 8f, paint)
        paint.color = 0xFFFF4F98.toInt()
        c.drawRoundRect(RectF(w * 0.35f, 14f * density, w * 0.35f + (w * 0.65f - 12f) * (worldX / finishX).coerceIn(0f, 1f), 22f * density), 8f, 8f, paint)
    }
}
