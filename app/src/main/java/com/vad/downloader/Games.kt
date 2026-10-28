package com.vad.downloader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Мини-игры — это папки filesDir/games/<id>/ : meta.json, game.js, sprites/<имя>.png.
 * Встроенные игры копируются из assets при первом запуске; свои можно создавать, править, экспортировать и импортировать.
 */
object GamePacks {
    class Sprite(val name: String, val size: Int)

    private val builtinIds = listOf("snake", "maze", "tank", "platformer")
    private val idRe = Regex("[A-Za-z0-9_\\-]{1,40}")
    private val spriteRe = Regex("G\\.sprite\\(\\s*['\"]([A-Za-z0-9_]{1,30})['\"]\\s*,\\s*(\\d{1,3})")

    fun root(ctx: Context) = File(ctx.filesDir, "games").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id)

    private fun readAsset(ctx: Context, path: String): ByteArray? = try {
        ctx.assets.open(path).use { it.readBytes() }
    } catch (_: Throwable) {
        null
    }

    private fun seed(ctx: Context, id: String, force: Boolean) {
        val d = dir(ctx, id)
        if (!force && File(d, "game.js").exists()) return
        d.mkdirs()
        readAsset(ctx, "games/$id/game.js")?.let { File(d, "game.js").writeBytes(it) }
        readAsset(ctx, "games/$id/meta.json")?.let { File(d, "meta.json").writeBytes(it) }
        if (force) File(d, "sprites").deleteRecursively()
    }

    fun ids(ctx: Context): List<String> {
        for (id in builtinIds) seed(ctx, id, false)
        return root(ctx).listFiles()?.filter { it.isDirectory && File(it, "game.js").isFile && idRe.matches(it.name) }
            ?.map { it.name }?.sortedWith(compareBy({ it !in builtinIds }, { builtinIds.indexOf(it) }, { it })) ?: emptyList()
    }

    fun isBuiltin(id: String) = id in builtinIds

    private fun meta(ctx: Context, id: String): JSONObject = try {
        JSONObject(File(dir(ctx, id), "meta.json").readText())
    } catch (_: Throwable) {
        JSONObject()
    }

    fun name(ctx: Context, id: String): String {
        val m = meta(ctx, id)
        return m.optString("name_" + Strings.code).ifEmpty { m.optString("name_en") }.ifEmpty { m.optString("name") }.ifEmpty { id }
    }

    fun hint(ctx: Context, id: String): String {
        val m = meta(ctx, id)
        return m.optString("hint_" + Strings.code).ifEmpty { m.optString("hint_en") }.ifEmpty { m.optString("hint") }
    }

    fun readCode(ctx: Context, id: String): String = try {
        File(dir(ctx, id), "game.js").readText()
    } catch (_: Throwable) {
        ""
    }

    fun writeCode(ctx: Context, id: String, code: String) {
        dir(ctx, id).mkdirs()
        File(dir(ctx, id), "game.js").writeText(code)
    }

    /** Спрайты игры берутся из вызовов G.sprite('имя', размер, ...) в коде. */
    fun sprites(ctx: Context, id: String): List<Sprite> {
        val out = LinkedHashMap<String, Sprite>()
        for (m in spriteRe.findAll(readCode(ctx, id))) {
            val size = m.groupValues[2].toIntOrNull()?.coerceIn(8, 256) ?: 64
            out.putIfAbsent(m.groupValues[1], Sprite(m.groupValues[1], size))
        }
        return out.values.toList()
    }

    fun spriteFile(ctx: Context, id: String, name: String) = File(File(dir(ctx, id), "sprites").apply { mkdirs() }, "$name.png")
    fun hasSprite(ctx: Context, id: String, name: String) = spriteFile(ctx, id, name).isFile

    fun setSprite(ctx: Context, id: String, name: String, bmp: Bitmap): Boolean = try {
        val b = if (bmp.width > 256 || bmp.height > 256) {
            val k = 256f / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        } else bmp
        spriteFile(ctx, id, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (_: Throwable) {
        false
    }

    fun resetSprite(ctx: Context, id: String, name: String) {
        spriteFile(ctx, id, name).delete()
    }

    fun reset(ctx: Context, id: String) {
        if (isBuiltin(id)) seed(ctx, id, true)
    }

    fun delete(ctx: Context, id: String) {
        if (!isBuiltin(id)) dir(ctx, id).deleteRecursively()
    }

    private fun uniqueId(ctx: Context, base: String): String {
        val b = base.replace(Regex("[^A-Za-z0-9_\\-]"), "_").trim('_').take(30).ifEmpty { "game" }
        var id = b
        var i = 2
        while (dir(ctx, id).exists()) id = "${b}_${i++}"
        return id
    }

    fun create(ctx: Context, title: String): String {
        val id = uniqueId(ctx, "g_" + title.lowercase().filter { it.code < 128 && it.isLetterOrDigit() }.ifEmpty { "game" })
        val d = dir(ctx, id)
        d.mkdirs()
        val m = JSONObject()
        m.put("name", title.ifBlank { "Game" })
        m.put("hint", "")
        File(d, "meta.json").writeText(m.toString())
        File(d, "game.js").writeBytes(readAsset(ctx, "games/template/game.js") ?: "function draw(c){c.fillText('hi',20,20)}".toByteArray())
        return id
    }

    fun setTitle(ctx: Context, id: String, title: String) {
        val m = meta(ctx, id)
        m.put("name", title)
        File(dir(ctx, id), "meta.json").writeText(m.toString())
    }

    // ------------------------------------------------------------ экспорт / импорт

    fun export(ctx: Context, id: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(n: String, f: File) {
                if (!f.isFile) return
                z.putNextEntry(ZipEntry(n)); z.write(f.readBytes()); z.closeEntry()
            }
            val d = dir(ctx, id)
            put("meta.json", File(d, "meta.json"))
            put("game.js", File(d, "game.js"))
            File(d, "sprites").listFiles()?.filter { it.extension == "png" }?.forEach { put("sprites/${it.name}", it) }
        }
        return bos.toByteArray()
    }

    private fun readLimited(z: java.io.InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = z.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    /** Принимает .zip (meta.json + game.js + sprites/NAME.png) или просто текст JavaScript. Возвращает id или null. */
    fun importGame(ctx: Context, data: ByteArray, fileName: String): String? {
        val base = fileName.substringBeforeLast('.').substringBeforeLast(".vadgame")
        if (data.size < 4 || !(data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte())) {
            val code = String(data, Charsets.UTF_8)
            if (code.length > 500_000 || code.isBlank() || '\u0000' in code) return null
            val id = create(ctx, base.ifBlank { "Imported" })
            writeCode(ctx, id, code)
            return id
        }
        var code: ByteArray? = null
        var metaB: ByteArray? = null
        val sprites = HashMap<String, ByteArray>()
        var n = 0
        ZipInputStream(ByteArrayInputStream(data)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (++n > 80) break
                val nm = e.name
                when {
                    nm == "game.js" -> code = readLimited(z, 500_000)
                    nm == "meta.json" -> metaB = readLimited(z, 20_000)
                    Regex("sprites/[A-Za-z0-9_]{1,30}\\.png").matches(nm) -> readLimited(z, 2_000_000)?.let { sprites[nm.substring(8, nm.length - 4)] = it }
                }
            }
        }
        val c = code ?: return null
        var title = base
        var meta: JSONObject? = null
        try {
            meta = JSONObject(String(metaB ?: ByteArray(0), Charsets.UTF_8))
            title = meta.optString("name_en").ifEmpty { meta.optString("name") }.ifEmpty { base }
        } catch (_: Throwable) {
        }
        val id = uniqueId(ctx, "g_" + title.lowercase().filter { it.code < 128 && it.isLetterOrDigit() }.ifEmpty { "game" })
        val d = dir(ctx, id)
        d.mkdirs()
        File(d, "game.js").writeBytes(c)
        val m = meta ?: JSONObject()
        m.remove("builtin")
        if (m.optString("name").isEmpty() && m.optString("name_en").isEmpty()) m.put("name", title.ifBlank { "Game" })
        File(d, "meta.json").writeText(m.toString())
        for ((k, v) in sprites) {
            if (BitmapFactory.decodeByteArray(v, 0, v.size) != null) spriteFile(ctx, id, k).writeBytes(v)
        }
        return id
    }

    // ------------------------------------------------------------ страница для WebView

    fun html(ctx: Context, id: String, codeOverride: String? = null): String {
        val spr = JSONObject()
        for (s in sprites(ctx, id)) {
            val f = spriteFile(ctx, id, s.name)
            if (f.isFile) spr.put(s.name, "data:image/png;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        val host = String(readAsset(ctx, "host.html") ?: ByteArray(0), Charsets.UTF_8)
        val code = codeOverride ?: readCode(ctx, id)
        val js = JSONObject.quote(code).replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return host.replace("__SPR__", spr.toString().replace("</", "<\\/")).replace("__CODE__") { js }
    }
}

private fun String.replace(key: String, value: () -> String): String {
    val i = indexOf(key)
    return if (i < 0) this else substring(0, i) + value() + substring(i + key.length)
}

/** WebView, в котором работает игра. Сети нет (CSP + blockNetworkLoads), файлов нет. */
@SuppressLint("SetJavaScriptEnabled")
class GameHost(ctx: Context, html: String, private val onFinish: (Boolean) -> Unit, private val onError: (String) -> Unit) : FrameLayout(ctx) {
    private val web = WebView(ctx)
    private val ui = Handler(Looper.getMainLooper())
    private var finished = false

    init {
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.blockNetworkLoads = true
        web.settings.domStorageEnabled = false
        web.setBackgroundColor(0xFFFFE6F3.toInt())
        web.isHorizontalScrollBarEnabled = false
        web.isVerticalScrollBarEnabled = false
        web.overScrollMode = OVER_SCROLL_NEVER
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                return if (u.startsWith("data:") || u.startsWith("about:")) null
                else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun finish(win: Boolean) {
                ui.post { if (!finished) { finished = true; onFinish(win) } }
            }

            @JavascriptInterface
            fun error(msg: String) {
                ui.post { onError(msg.take(300)) }
            }
        }, "VadBridge")
        addView(web, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    fun destroy() {
        try {
            web.stopLoading()
            web.removeJavascriptInterface("VadBridge")
            removeAllViews()
            web.destroy()
        } catch (_: Throwable) {
        }
    }
}
