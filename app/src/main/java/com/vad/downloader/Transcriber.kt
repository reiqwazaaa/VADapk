package com.vad.downloader

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Задание на расшифровку и перевод. Живёт в процессе (не в окне), поэтому работает в фоне:
 * его запускает служба [TranscribeService], а окно [TranscribeActivity] только показывает состояние.
 */
object Transcriber {
    class Seg(val t0: Long, val t1: Long, val text: String, var tr: String = "")
    class Opts(val uri: Uri, val modelFile: String, val src: String, val dst: String, val baseName: String, val engine: Int = 0, val deeplKey: String = "")

    interface Listener {
        fun onState()
        fun onSegment(seg: Seg)
        fun onDone()
    }

    val segs = ArrayList<Seg>()
    @Volatile var running = false
    @Volatile var stopped = false
    @Volatile var status = ""
    @Volatile var progress = -1
    @Volatile var opts: Opts? = null
    @Volatile var listener: Listener? = null
    @Volatile private var pending: Opts? = null
    @Volatile private var ffProc: Process? = null
    private var lastNotify = 0L

    private fun t(k: String) = Strings.t(k)
    private fun f(k: String, vararg a: Any?) = Strings.f(k, *a)

    /** Запустить задание: сохраняем параметры и стартуем службу переднего плана. */
    fun start(ctx: Context, o: Opts) {
        if (running) return
        running = true
        stopped = false
        synchronized(segs) { segs.clear() }
        opts = o
        pending = o
        status = t("tr_prep")
        progress = -1
        TranscribeService.start(ctx.applicationContext)
        listener?.onState()
    }

    fun takePending(): Opts? = pending.also { pending = null }

    fun stop() {
        stopped = true
        WhisperLib.abort()
        try { ffProc?.destroy() } catch (_: Throwable) {}
    }

    fun snapshot(): List<Seg> = synchronized(segs) { ArrayList(segs) }

    private fun setStatus(ctx: Context, s: String, p: Int = -1) {
        status = s
        progress = p
        listener?.onState()
        val now = System.currentTimeMillis()
        if (now - lastNotify > 800) {
            lastNotify = now
            TranscribeService.update(ctx, s, p)
        }
    }

    private fun finish(ctx: Context, msg: String) {
        running = false
        status = msg
        progress = -1
        listener?.onDone()
        TranscribeService.done(ctx, msg, snapshot().isNotEmpty())
        TranscribeService.stop(ctx)
    }

    fun work(ctx: Context, o: Opts) {
        var handle = 0L
        val tmpIn = File(ctx.cacheDir, "tr_in.bin")
        val raw = File(ctx.cacheDir, "tr.raw")
        try {
            // 1. модель
            val model = File(File(ctx.filesDir, "whisper").apply { mkdirs() }, o.modelFile)
            if (!model.isFile || model.length() < 1_000_000) downloadModel(ctx, o.modelFile, model)
            if (stopped) return finish(ctx, t("tr_stopped"))

            // 2. звук → 16 кГц float
            setStatus(ctx, t("tr_prep"))
            ctx.contentResolver.openInputStream(o.uri)!!.use { ins -> tmpIn.outputStream().use { ins.copyTo(it) } }
            if (stopped) return finish(ctx, t("tr_stopped"))
            val ff = findFfmpeg(ctx) ?: throw RuntimeException("ffmpeg not found")
            val p = ffmpegBuilder(ctx, ff, listOf("-y", "-hide_banner", "-loglevel", "error", "-i", tmpIn.absolutePath,
                "-vn", "-ac", "1", "-ar", "16000", "-f", "f32le", raw.absolutePath)).redirectErrorStream(true).start()
            ffProc = p
            val ffOut = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            ffProc = null
            tmpIn.delete()
            if (stopped) return finish(ctx, t("tr_stopped"))
            if (code != 0 || !raw.isFile || raw.length() < 4000) throw RuntimeException(ffOut.trim().takeLast(200).ifBlank { "ffmpeg failed" })

            // 3. распознавание
            setStatus(ctx, t("tr_loading"))
            handle = WhisperLib.init(model.absolutePath)
            if (handle == 0L) throw RuntimeException(t("tr_model_bad"))
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            setStatus(ctx, f("tr_run", 0), 0)
            val detected = WhisperLib.run(handle, raw.absolutePath, o.src, threads, object : WhisperLib.Callback {
                override fun onSegment(t0: Long, t1: Long, text: ByteArray) {
                    val s = String(text, Charsets.UTF_8).trim()
                    if (s.isEmpty()) return
                    val seg = Seg(t0, t1, s)
                    synchronized(segs) { segs.add(seg) }
                    listener?.onSegment(seg)
                }
                override fun onProgress(p: Int) { setStatus(ctx, f("tr_run", p), p) }
            })
            WhisperLib.free(handle)
            handle = 0L
            raw.delete()
            if (stopped) return finish(ctx, t("tr_stopped"))
            if (synchronized(segs) { segs.isEmpty() }) return finish(ctx, if (detected.isEmpty()) t("tr_err_run") else t("tr_empty"))

            // 4. перевод (0 — на телефоне, 1 — Google онлайн, 2 — DeepL)
            var trNote = ""
            if (o.dst.isNotEmpty()) {
                val from = if (o.src == "auto") detected else o.src
                try {
                    trNote = when (o.engine) {
                        1 -> translateOnline(ctx, from, o.dst, null)
                        2 -> translateOnline(ctx, from, o.dst, o.deeplKey)
                        else -> translateLocal(ctx, from, o.dst)
                    }
                } catch (e: Throwable) {
                    trNote = f("tr_tr_fail", e.message ?: e.javaClass.simpleName)
                }
            }
            finish(ctx, if (stopped) t("tr_stopped") else f("tr_done", snapshot().size) + (if (trNote.isNotEmpty()) "\n$trNote" else ""))
        } catch (e: Throwable) {
            if (handle != 0L) try { WhisperLib.free(handle) } catch (_: Throwable) {}
            finish(ctx, if (stopped) t("tr_stopped") else f("tr_err", e.message ?: e.javaClass.simpleName))
        } finally {
            tmpIn.delete()
            raw.delete()
        }
    }

    // ------------------------------------------------------------ перевод

    private fun translateLocal(ctx: Context, from: String, dst: String): String {
        val fromTag = TranslateLanguage.fromLanguageTag(from)
        val toTag = TranslateLanguage.fromLanguageTag(dst)
        if (fromTag == null || toTag == null) return f("tr_tr_unsupported", from)
        if (fromTag == toTag) return ""
        var translator: com.google.mlkit.nl.translate.Translator? = null
        try {
            translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(fromTag).setTargetLanguage(toTag).build())
            setStatus(ctx, t("tr_trdl"))
            Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            val all = snapshot()
            for ((i, s) in all.withIndex()) {
                if (stopped) break
                s.tr = try { Tasks.await(translator.translate(s.text)) } catch (_: Throwable) { "" }
                if (i % 5 == 0) setStatus(ctx, f("tr_trans", i + 1, all.size), (i + 1) * 100 / all.size)
            }
        } finally {
            try { translator?.close() } catch (_: Throwable) {}
        }
        return ""
    }

    /** Онлайн-перевод пачками: key == null — Google (без ключа), иначе DeepL с этим ключом. */
    private fun translateOnline(ctx: Context, from: String, dst: String, key: String?): String {
        val all = snapshot()
        var i = 0
        while (i < all.size && !stopped) {
            val batch = ArrayList<Transcriber.Seg>()
            var chars = 0
            val maxN = if (key == null) 8 else 40
            while (i < all.size && batch.size < maxN && chars + all[i].text.length < 1500) {
                batch.add(all[i]); chars += all[i].text.length; i++
            }
            if (batch.isEmpty()) { batch.add(all[i]); i++ }
            val texts = batch.map { it.text }
            val out = if (key == null) googleBatch(from, dst, texts) else deeplBatch(key, from, dst, texts)
            for ((k, seg) in batch.withIndex()) seg.tr = out.getOrNull(k) ?: ""
            setStatus(ctx, f("tr_trans", i, all.size), i * 100 / all.size)
        }
        return ""
    }

    private fun http(url: String, method: String, headers: Map<String, String>, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20000
            c.readTimeout = 30000
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw RuntimeException("HTTP $code" + (if (code == 429) " (too many requests)" else if (code == 403 || code == 401) " (key/access)" else ""))
            return txt
        } finally {
            c.disconnect()
        }
    }

    private fun gcode(c: String) = if (c == "zh") "zh-CN" else c

    private fun googleOne(from: String, to: String, text: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + gcode(from) + "&tl=" + gcode(to) +
            "&dt=t&q=" + java.net.URLEncoder.encode(text, "UTF-8")
        val arr = org.json.JSONArray(http(url, "GET", mapOf("User-Agent" to "Mozilla/5.0"), null)).getJSONArray(0)
        val sb = StringBuilder()
        for (k in 0 until arr.length()) sb.append(arr.getJSONArray(k).optString(0, ""))
        return sb.toString()
    }

    private fun googleBatch(from: String, to: String, texts: List<String>): List<String> {
        if (texts.size == 1) return listOf(googleOne(from, to, texts[0]))
        val joined = googleOne(from, to, texts.joinToString("\n"))
        val parts = joined.split("\n")
        if (parts.size == texts.size) return parts.map { it.trim() }
        // строки слиплись или разъехались — переводим по одной
        return texts.map { googleOne(from, to, it) }
    }

    private fun deeplTarget(c: String) = when (c) { "en" -> "EN-US"; "pt" -> "PT-BR"; else -> c.uppercase() }

    private fun deeplBatch(key: String, from: String, to: String, texts: List<String>): List<String> {
        val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val body = org.json.JSONObject().apply {
            put("text", org.json.JSONArray(texts))
            put("target_lang", deeplTarget(to))
            if (from != "auto" && from.isNotEmpty()) put("source_lang", from.uppercase())
        }.toString()
        val resp = http("https://$host/v2/translate", "POST",
            mapOf("Authorization" to "DeepL-Auth-Key $key", "Content-Type" to "application/json"), body)
        val arr = org.json.JSONObject(resp).getJSONArray("translations")
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("text", "") }
    }

    // ------------------------------------------------------------ модель

    private fun downloadModel(ctx: Context, name: String, dest: File) {
        val part = File(dest.parentFile, "$name.part")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$name").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP " + conn.responseCode)
            val total = conn.contentLengthLong
            var done = 0L
            var lastP = -1
            conn.inputStream.use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (stopped) return
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pr = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pr != lastP) { lastP = pr; setStatus(ctx, f("tr_dl_model", pr), pr) }
                    }
                }
            }
            if (total > 0 && done != total) throw RuntimeException(t("tr_model_bad"))
            if (!part.renameTo(dest)) throw RuntimeException("rename failed")
        } finally {
            conn?.disconnect()
            if (!dest.isFile) part.delete()
        }
    }

    // ------------------------------------------------------------ ffmpeg (как в конвертере)

    private fun findFfmpeg(ctx: Context): File? {
        val lib = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (lib.isFile) return lib
        val base = File(ctx.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        return base.walkTopDown().firstOrNull { it.isFile && (it.name == "ffmpeg" || it.name == "libffmpeg.so") }
    }

    private fun ffmpegBuilder(ctx: Context, bin: File, args: List<String>): ProcessBuilder {
        val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
        val base = File(ctx.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        val dirs = ArrayList<String>()
        for (sub in listOf("usr/lib", "lib", "usr/lib64")) {
            val d = File(base, sub)
            if (d.isDirectory) dirs.add(d.absolutePath)
        }
        dirs.add(ctx.applicationInfo.nativeLibraryDir)
        System.getenv("LD_LIBRARY_PATH")?.let { if (it.isNotEmpty()) dirs.add(it) }
        pb.environment()["LD_LIBRARY_PATH"] = dirs.joinToString(":")
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
        return pb
    }
}
