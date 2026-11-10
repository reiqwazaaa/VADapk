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
    class Opts(val uri: Uri, val modelFile: String, val src: String, val dst: String, val baseName: String)

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

            // 4. перевод
            var trNote = ""
            if (o.dst.isNotEmpty()) {
                val from = if (o.src == "auto") detected else o.src
                val fromTag = TranslateLanguage.fromLanguageTag(from)
                val toTag = TranslateLanguage.fromLanguageTag(o.dst)
                if (fromTag == null || toTag == null) {
                    trNote = f("tr_tr_unsupported", from)
                } else if (fromTag != toTag) {
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
                    } catch (e: Throwable) {
                        trNote = f("tr_tr_fail", e.message ?: e.javaClass.simpleName)
                    } finally {
                        try { translator?.close() } catch (_: Throwable) {}
                    }
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
