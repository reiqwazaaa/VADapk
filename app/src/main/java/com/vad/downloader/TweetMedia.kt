package com.vad.downloader

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL

/**
 * Посты Твиттера (X): список медиа поста берём из публичного API fxtwitter (фото, гифки, видео).
 * Фото качаем сами в оригинальном размере, гифки (в Твиттере это mp4) забираем и сохраняем настоящим GIF.
 * Видео остаются за yt-dlp. Любая ошибка API не фатальна: тогда всё идёт старым путём, через yt-dlp.
 */
object TweetMedia {
    class Item(val type: String, val url: String) // type: photo, gif, video
    class Post(val user: String, val id: String, val items: List<Item>)

    private val postRe = Regex(
        "^https?://(?:www\\.|mobile\\.|m\\.)?(?:twitter\\.com|x\\.com|fxtwitter\\.com|vxtwitter\\.com|fixupx\\.com|fixvx\\.com)" +
            "/([^/?#]+)/status(?:es)?/(\\d+)",
        RegexOption.IGNORE_CASE
    )
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** (автор, id поста) или null, если это не ссылка на пост. */
    fun parse(url: String): Pair<String, String>? {
        val m = postRe.find(url.trim()) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /** Список медиа поста. null при любой ошибке сети или разбора. */
    fun fetch(url: String, proxy: String): Post? {
        val (user, id) = parse(url) ?: return null
        return try {
            val c = open("https://api.fxtwitter.com/$user/status/$id", proxy)
            val body = try {
                if (c.responseCode !in 200..299) return null
                c.inputStream.bufferedReader().readText()
            } finally {
                c.disconnect()
            }
            val media = JSONObject(body).optJSONObject("tweet")?.optJSONObject("media") ?: return Post(user, id, emptyList())
            val items = ArrayList<Item>()
            val all = media.optJSONArray("all")
            if (all != null) {
                for (i in 0 until all.length()) {
                    val o = all.optJSONObject(i) ?: continue
                    val type = o.optString("type")
                    val u = o.optString("url")
                    if (u.isNotEmpty() && (type == "photo" || type == "gif" || type == "video")) items.add(Item(type, u))
                }
            } else {
                // запасной разбор, если общего списка нет
                for ((key, type) in listOf("photos" to "photo", "videos" to "video")) {
                    val arr = media.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val u = o.optString("url")
                        if (u.isNotEmpty()) items.add(Item(if (type == "video" && o.optString("type") == "gif") "gif" else type, u))
                    }
                }
            }
            Post(user, id, items)
        } catch (e: Throwable) {
            GifTool.checkCancelled()
            null
        }
    }

    /**
     * Сохраняет в dir фото (оригинал) и гифки (mp4 → GIF). Видео не трогает.
     * Возвращает число полученных файлов. Ошибка одного файла не мешает остальным.
     */
    fun saveMedia(ctx: Context, post: Post, dir: File, proxy: String, log: (String) -> Unit): Int {
        var got = 0
        var photoN = 0
        var gifN = 0
        for (item in post.items) {
            GifTool.checkCancelled()
            try {
                when (item.type) {
                    "photo" -> {
                        photoN++
                        val f = File(dir, "${post.user} - ${post.id} ($photoN).${photoExt(item.url)}")
                        download(origUrl(item.url), f, proxy)
                        got++
                    }
                    "gif" -> {
                        gifN++
                        val stem = "${post.user} - ${post.id} gif$gifN"
                        val mp4 = File(dir, "$stem.mp4")
                        download(item.url, mp4, proxy)
                        // гифка из Твиттера: без масштабирования и без смены частоты кадров, как в оригинале
                        val err = GifTool.convert(ctx, mp4, File(dir, "$stem.gif"), 0, 0)
                        if (err == null) mp4.delete() else {
                            GifTool.checkCancelled()
                            log("GIF: ${mp4.name}: $err")
                        }
                        got++
                    }
                }
            } catch (e: Throwable) {
                if (GifTool.cancelled) throw e
                log("${item.type}: ${e.message}")
            }
        }
        return got
    }

    // ---- сеть

    private fun photoExt(url: String): String {
        val e = url.substringBefore('?').substringAfterLast('.', "jpg").lowercase()
        return if (e.length in 2..4 && e.all { it.isLetterOrDigit() }) e else "jpg"
    }

    /** Оригинальный размер: pbs.twimg.com отдаёт его по ?name=orig. */
    private fun origUrl(url: String): String {
        val base = url.substringBefore('?')
        return if (base.contains("pbs.twimg.com")) "$base?name=orig" else url
    }

    private fun proxyOf(p: String): Proxy? {
        if (p.isBlank()) return null
        return try {
            val u = URI(if ("://" in p) p.trim() else "http://${p.trim()}")
            val type = if (u.scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
            Proxy(type, InetSocketAddress.createUnresolved(u.host, u.port))
        } catch (_: Throwable) {
            null
        }
    }

    private fun open(url: String, proxy: String): HttpURLConnection {
        val px = proxyOf(proxy)
        val c = (if (px != null) URL(url).openConnection(px) else URL(url).openConnection()) as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "*/*")
        return c
    }

    private fun download(url: String, dest: File, proxy: String) {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        val c = open(url, proxy)
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        GifTool.checkCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            if (part.length() == 0L) throw RuntimeException("empty file")
            if (!part.renameTo(dest)) throw RuntimeException("rename failed")
        } finally {
            c.disconnect()
            part.delete()
        }
    }
}
