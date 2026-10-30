package com.vad.downloader

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Конвертер видео и аудио. Запускает ffmpeg из библиотеки youtubedl-android напрямую
 * (бинарник libffmpeg.so лежит в папке нативных библиотек приложения).
 * Какие кодеки есть в этой сборке ffmpeg, определяется на лету по `ffmpeg -encoders`.
 */
class Converter(
    private val act: Activity,
    private val prefs: SharedPreferences,
    private val button: (String, () -> Unit) -> Button,
    private val adapter: (List<String>) -> ArrayAdapter<String>,
    private val tint: (TextView) -> Unit,
    private val dp: (Int) -> Int,
    private val isBusy: () -> Boolean,
    private val setBusy: (Boolean) -> Unit,
    private val saveDir: (File) -> Int,
    private val log: (String) -> Unit,
    private val back: () -> Unit,
    private val afterDone: () -> Unit,
    private val reqPick: Int
) {
    companion object {
        @Volatile private var current: Process? = null
        @Volatile private var cancelled = false
        private val lock = Any()

        /** Вызывается и из уведомления службы, и с экрана конвертера. */
        fun cancelActive() {
            cancelled = true
            synchronized(lock) { current?.destroy() }
        }
    }

    private class Fmt(val ext: String, val kind: Int, val label: String) // kind: 0 звук, 1 видео, 2 анимация
    private class Item(val uri: Uri, val name: String)
    private class Info(val durationUs: Long, val hasVideo: Boolean, val hasAudio: Boolean)
    private class ConvError(msg: String) : Exception(msg)

    private val fmts = listOf(
        Fmt("mp3", 0, "MP3"), Fmt("flac", 0, "FLAC"), Fmt("ogg", 0, "OGG"), Fmt("opus", 0, "OPUS"),
        Fmt("m4a", 0, "M4A (AAC)"), Fmt("aac", 0, "AAC"), Fmt("wav", 0, "WAV"), Fmt("wma", 0, "WMA"),
        Fmt("mp4", 1, "MP4"), Fmt("mkv", 1, "MKV"), Fmt("webm", 1, "WEBM"), Fmt("avi", 1, "AVI"), Fmt("mov", 1, "MOV"),
        Fmt("gif", 2, "GIF"), Fmt("webp", 2, "WEBP")
    )
    private val heights = intArrayOf(0, 1080, 720, 480, 360)
    private val bitrates = intArrayOf(128, 192, 256, 320)

    private val files = ArrayList<Item>()
    @Volatile private var running = false
    private var encCache: Set<String>? = null

    // вью текущей сборки интерфейса (при смене языка пересоздаются)
    private var filesView: TextView? = null
    private var statusView: TextView? = null
    private var progressView: ProgressBar? = null
    private var fmtSpinner: Spinner? = null
    private var qualSpinner: Spinner? = null
    private var resSpinner: Spinner? = null
    private var pickBtn: Button? = null
    private var startBtn: Button? = null
    private var cancelBtn: Button? = null
    private var lastStatus = ""
    private var lastProgress = 0

    private fun t(k: String) = Strings.t(k)
    private fun ui(block: () -> Unit) = act.runOnUiThread(block)

    // ------------------------------------------------------------------ интерфейс

    fun buildView(): View {
        val pane = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        pane.addView(TextView(act).apply {
            text = t("cv_title")
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            tint(this)
        })
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(act).apply { addView(body) }
        pane.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(TextView(act).apply { text = t("cv_hint"); tint(this) })
        pickBtn = button(t("cv_pick")) { pickFiles() }
        body.addView(pickBtn)
        filesView = TextView(act).apply { tint(this); setPadding(0, dp(6), 0, dp(6)) }
        body.addView(filesView)

        body.addView(TextView(act).apply { text = t("cv_format"); tint(this) })
        val fmtLabels = fmts.map { it.label + " (" + t(kindKey(it.kind)) + ")" }
        fmtSpinner = Spinner(act).apply {
            adapter = this@Converter.adapter(fmtLabels)
            setSelection(prefs.getInt("cv_fmt", 0).coerceIn(0, fmts.size - 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putInt("cv_fmt", pos).apply()
                    fillQuality(fmts[pos])
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        body.addView(fmtSpinner)

        body.addView(TextView(act).apply { text = t("cv_quality"); tint(this) })
        qualSpinner = Spinner(act).apply {
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putInt("cv_q", pos).apply()
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        body.addView(qualSpinner)

        body.addView(TextView(act).apply { text = t("cv_res"); tint(this) })
        val resLabels = heights.map { if (it == 0) t("cv_res_orig") else "${it}p" }
        resSpinner = Spinner(act).apply {
            adapter = this@Converter.adapter(resLabels)
            setSelection(prefs.getInt("cv_res", 0).coerceIn(0, heights.size - 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putInt("cv_res", pos).apply()
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        body.addView(resSpinner)

        progressView = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = lastProgress
        }
        body.addView(progressView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        statusView = TextView(act).apply { text = lastStatus; tint(this) }
        body.addView(statusView)

        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val w = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(button(t("back")) { back() }, w)
        startBtn = button(t("cv_start")) { start() }
        row.addView(startBtn, LinearLayout.LayoutParams(w))
        cancelBtn = button(t("cancel")) { cancelActive() }
        row.addView(cancelBtn, LinearLayout.LayoutParams(w))
        pane.addView(row)

        fillQuality(fmts[fmtSpinner!!.selectedItemPosition.coerceIn(0, fmts.size - 1)])
        refreshFiles()
        updateButtons()
        return pane
    }

    private fun kindKey(kind: Int) = when (kind) { 0 -> "cv_audio"; 1 -> "cv_video"; else -> "cv_anim" }

    /** Список «качества» зависит от формата: битрейт для звука, уровни для видео и анимации. */
    private fun fillQuality(f: Fmt) {
        val sp = qualSpinner ?: return
        val labels = if (f.kind == 0) bitrates.map { "$it kbps" }
        else listOf(t("cv_q_high"), t("cv_q_mid"), t("cv_q_low"), t("cv_q_min"))
        sp.adapter = adapter(labels)
        sp.setSelection(prefs.getInt("cv_q", 1).coerceIn(0, labels.size - 1))
        val lossless = f.ext == "flac" || f.ext == "wav"
        sp.isEnabled = !lossless
        resSpinner?.isEnabled = f.kind != 0
    }

    private fun refreshFiles() {
        val v = filesView ?: return
        v.text = if (files.isEmpty()) t("cv_none") else {
            val shown = files.take(8).joinToString("\n") { "• " + it.name }
            if (files.size > 8) shown + "\n… +" + (files.size - 8) else shown
        }
    }

    private fun updateButtons() {
        pickBtn?.isEnabled = !running
        startBtn?.isEnabled = !running
        cancelBtn?.isEnabled = running
    }

    private fun status(s: String) {
        lastStatus = s
        ui { statusView?.text = s }
    }

    private fun progress(p: Int) {
        lastProgress = p.coerceAtLeast(0)
        ui {
            progressView?.isIndeterminate = p < 0
            if (p >= 0) progressView?.progress = p
        }
    }

    // ------------------------------------------------------------------ выбор файлов

    private fun pickFiles() {
        if (running) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*", "image/gif", "image/webp"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        act.startActivityForResult(i, reqPick)
    }

    fun onPicked(data: Intent?) {
        if (data == null || running) return
        val uris = ArrayList<Uri>()
        val cd = data.clipData
        if (cd != null) {
            for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { uris.add(it) }
        } else data.data?.let { uris.add(it) }
        files.clear()
        uris.forEach { files.add(Item(it, displayName(it))) }
        refreshFiles()
    }

    private fun displayName(u: Uri): String {
        try {
            act.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Throwable) {
        }
        return u.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    // ------------------------------------------------------------------ запуск

    private fun start() {
        if (running) return
        if (files.isEmpty()) { toast(t("cv_nofiles")); return }
        if (isBusy()) { toast(t("cv_busy")); return }
        val fmt = fmts[(fmtSpinner?.selectedItemPosition ?: 0).coerceIn(0, fmts.size - 1)]
        val q = (qualSpinner?.selectedItemPosition ?: 1).coerceAtLeast(0)
        val res = (resSpinner?.selectedItemPosition ?: 0).coerceAtLeast(0)
        val items = ArrayList(files)
        running = true
        cancelled = false
        setBusy(true)
        updateButtons()
        progress(-1)
        DownloadService.start(act)
        Thread { runAll(fmt, q, res, items) }.start()
    }

    private fun runAll(fmt: Fmt, q: Int, res: Int, items: List<Item>) {
        val inDir = File(act.cacheDir, "conv_in")
        val outDir = File(act.cacheDir, "conv_out")
        var ok = 0
        var fail = 0
        var saved = 0
        var lastNotif = -1
        try {
            inDir.deleteRecursively(); inDir.mkdirs()
            outDir.deleteRecursively(); File(outDir, "Converted").mkdirs()
            val bin = findBinary()
            if (bin == null) {
                status(t("cv_err_noengine"))
                log("converter: ffmpeg binary not found")
                fail = items.size
            } else {
                val enc = encoders(bin)
                for ((idx, item) in items.withIndex()) {
                    if (cancelled) break
                    val label = "${idx + 1}/${items.size}"
                    val inF = File(inDir, "in_$idx." + item.name.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" })
                    try {
                        status(Strings.f("cv_copying", label, item.name))
                        progress(-1)
                        act.contentResolver.openInputStream(item.uri)!!.use { i -> inF.outputStream().use { i.copyTo(it) } }
                        val info = probe(bin, inF)
                        val outF = uniqueOut(File(outDir, "Converted"), item.name, fmt.ext)
                        val cut = fmt.kind == 2 && info.durationUs > 30_000_000L
                        val args = buildArgs(fmt, inF, outF, info, q, res, enc, cut)
                        val total = if (cut) 30_000_000L else info.durationUs
                        status(Strings.f("cv_working", label, item.name) + if (cut) "\n" + t("cv_anim_cut") else "")
                        val (code, err) = exec(bin, args, total) { p ->
                            val all = if (items.size > 1) (idx * 100 + p) / items.size else p
                            progress(all)
                            if (all != lastNotif) {
                                lastNotif = all
                                DownloadService.update(act, t("cv_title"), item.name, all)
                            }
                        }
                        if (cancelled) { outF.delete(); break }
                        if (code != 0 || !outF.isFile || outF.length() == 0L) {
                            val line = err.lineSequence().lastOrNull { it.isNotBlank() } ?: "exit $code"
                            throw ConvError(line.take(200))
                        }
                        ok++
                        log("converter: ${item.name} -> ${outF.name} (${outF.length() / 1024} KB)")
                    } catch (e: ConvError) {
                        fail++
                        log("converter: ${item.name}: ${e.message}")
                        status(item.name + ": " + e.message)
                    } catch (e: Throwable) {
                        fail++
                        log("converter: ${item.name}: ${e.message}")
                        status(item.name + ": " + (e.message ?: "error"))
                    } finally {
                        inF.delete()
                    }
                }
            }
            if (ok > 0) {
                status(Strings.f("cv_saving", ok))
                progress(-1)
                saved = try { saveDir(outDir) } catch (e: Throwable) { log("converter save: ${e.message}"); 0 }
            }
        } catch (e: Throwable) {
            log("converter: ${e.message}")
            status(e.message ?: "error")
        } finally {
            inDir.deleteRecursively()
            outDir.deleteRecursively()
            val wasCancelled = cancelled
            val text = when {
                wasCancelled -> t("cv_cancelled")
                ok > 0 && saved == 0 -> t("cv_save_fail")
                else -> Strings.f("cv_done", saved, ok + fail)
            }
            running = false
            DownloadService.stop(act)
            DownloadService.finish(act, t("cv_title"), text)
            ui {
                // сообщение об ошибке последнего файла остаётся, если ничего не получилось
                if (wasCancelled || ok > 0) statusView?.text = text.also { lastStatus = it }
                progressView?.isIndeterminate = false
                progressView?.progress = if (ok > 0 && !wasCancelled) 100 else 0
                lastProgress = progressView?.progress ?: 0
                updateButtons()
                setBusy(false)
                afterDone()
            }
        }
    }

    // ------------------------------------------------------------------ ffmpeg

    private fun findBinary(): File? {
        val lib = File(act.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (lib.isFile) return lib
        val base = File(act.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        return base.walkTopDown().firstOrNull { it.isFile && (it.name == "ffmpeg" || it.name == "libffmpeg.so") }
    }

    private fun processBuilder(bin: File, args: List<String>): ProcessBuilder {
        val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
        val base = File(act.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        val dirs = ArrayList<String>()
        for (sub in listOf("usr/lib", "lib", "usr/lib64")) {
            val d = File(base, sub)
            if (d.isDirectory) dirs.add(d.absolutePath)
        }
        dirs.add(act.applicationInfo.nativeLibraryDir)
        System.getenv("LD_LIBRARY_PATH")?.let { if (it.isNotEmpty()) dirs.add(it) }
        pb.environment()["LD_LIBRARY_PATH"] = dirs.joinToString(":")
        pb.environment()["TMPDIR"] = act.cacheDir.absolutePath
        return pb
    }

    /** Запускает ffmpeg, читает прогресс из stdout (-progress pipe:1), ошибки из stderr. */
    private fun exec(bin: File, args: List<String>, durationUs: Long, onProgress: (Int) -> Unit): Pair<Int, String> {
        val p = processBuilder(bin, args).start()
        synchronized(lock) { current = p }
        val err = StringBuilder()
        val errThread = Thread {
            try {
                p.errorStream.bufferedReader().forEachLine {
                    synchronized(err) {
                        err.append(it).append('\n')
                        if (err.length > 3000) err.delete(0, err.length - 3000)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        errThread.start()
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                    val us = line.substringAfter('=').trim().toLongOrNull()
                    if (us != null && us >= 0 && durationUs > 0) {
                        onProgress(((us * 100) / durationUs).toInt().coerceIn(0, 99))
                    }
                }
            }
        } catch (_: Throwable) {
        }
        val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
        errThread.join(2000)
        synchronized(lock) { current = null }
        return code to synchronized(err) { err.toString() }
    }

    /** Запускает ffmpeg и возвращает весь текст (stderr склеен со stdout). */
    private fun execCapture(bin: File, args: List<String>): String {
        val pb = processBuilder(bin, args).redirectErrorStream(true)
        val p = pb.start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return text
    }

    private fun encoders(bin: File): Set<String> {
        encCache?.let { return it }
        val set = HashSet<String>()
        try {
            val re = Regex("^\\s*[VAS][.A-Z]{5}\\s+(\\S+)")
            execCapture(bin, listOf("-hide_banner", "-encoders")).lineSequence().forEach { l ->
                re.find(l)?.let { set.add(it.groupValues[1]) }
            }
        } catch (e: Throwable) {
            log("converter: encoders query failed: ${e.message}")
        }
        val interesting = listOf(
            "libmp3lame", "flac", "libvorbis", "vorbis", "libopus", "aac", "libfdk_aac", "pcm_s16le", "wmav2",
            "libx264", "libx265", "mpeg4", "libvpx", "libvpx-vp9", "gif", "libwebp", "libwebp_anim"
        )
        log("converter: encoders " + interesting.joinToString(" ") { it + "=" + (if (it in set) "yes" else "no") })
        encCache = set
        return set
    }

    private fun probe(bin: File, f: File): Info {
        val out = execCapture(bin, listOf("-hide_banner", "-nostdin", "-i", f.absolutePath))
        val m = Regex("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(out)
        val dur = if (m != null) {
            ((m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toDouble()) * 1_000_000).toLong()
        } else 0L
        val streams = out.lineSequence().filter { it.contains("Stream #") }.toList()
        val hasVideo = streams.any { it.contains(": Video:") && !it.contains("attached pic") }
        val hasAudio = streams.any { it.contains(": Audio:") }
        if (streams.isEmpty()) throw ConvError(t("cv_err_unreadable"))
        return Info(dur, hasVideo, hasAudio)
    }

    private fun pick(enc: Set<String>, vararg c: String): String? = c.firstOrNull { enc.isEmpty() || it in enc }

    private fun need(enc: Set<String>, vararg c: String): String =
        pick(enc, *c) ?: throw ConvError(Strings.f("cv_err_noenc", c.first()))

    private fun scaleFor(res: Int): String? =
        if (res == 0) null else "scale=-2:'min(${heights[res]},ih)'"

    private fun buildArgs(
        fmt: Fmt, inF: File, outF: File, info: Info, q: Int, res: Int, enc: Set<String>, cut: Boolean
    ): List<String> {
        val a = ArrayList<String>()
        a += listOf("-y", "-hide_banner", "-nostdin", "-loglevel", "error", "-progress", "pipe:1", "-nostats")
        a += listOf("-i", inF.absolutePath)
        if (cut) a += listOf("-t", "30")
        val br = bitrates[q.coerceIn(0, 3)]
        val lvl = q.coerceIn(0, 3)
        when (fmt.kind) {
            0 -> {
                if (!info.hasAudio) throw ConvError(t("cv_err_noaudio"))
                a += listOf("-vn", "-map", "0:a:0", "-map_metadata", "0")
                when (fmt.ext) {
                    "mp3" -> a += listOf("-c:a", need(enc, "libmp3lame"), "-b:a", "${br}k")
                    "flac" -> a += listOf("-c:a", need(enc, "flac"))
                    "wav" -> a += listOf("-c:a", need(enc, "pcm_s16le"))
                    "ogg" -> a += listOf("-c:a", need(enc, "libvorbis", "vorbis", "libopus"), "-b:a", "${br}k", "-strict", "-2")
                    "opus" -> a += listOf("-c:a", need(enc, "libopus"), "-b:a", "${br}k")
                    "m4a" -> a += listOf("-c:a", need(enc, "aac", "libfdk_aac"), "-b:a", "${br}k")
                    "aac" -> a += listOf("-c:a", need(enc, "aac", "libfdk_aac"), "-b:a", "${br}k", "-f", "adts")
                    "wma" -> a += listOf("-c:a", need(enc, "wmav2"), "-b:a", "${br}k")
                }
            }
            1 -> {
                if (!info.hasVideo) throw ConvError(t("cv_err_novideo"))
                a += listOf("-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "0")
                scaleFor(res)?.let { a += listOf("-vf", it) }
                val crf = intArrayOf(20, 24, 28, 32)[lvl]
                val qv = intArrayOf(3, 5, 8, 12)[lvl]
                val audio: String?
                when (fmt.ext) {
                    "webm" -> {
                        val vc = need(enc, "libvpx-vp9", "libvpx")
                        if (vc == "libvpx-vp9") {
                            a += listOf("-c:v", vc, "-b:v", "0", "-crf", "${crf + 10}", "-row-mt", "1", "-deadline", "good", "-cpu-used", "5")
                        } else {
                            a += listOf("-c:v", vc, "-crf", "${crf}", "-b:v", "2M")
                        }
                        audio = pick(enc, "libopus", "libvorbis")
                    }
                    "avi" -> {
                        a += listOf("-c:v", need(enc, "mpeg4", "libx264"), "-q:v", "$qv")
                        audio = pick(enc, "libmp3lame", "aac")
                    }
                    else -> { // mp4, mkv, mov
                        val vc = need(enc, "libx264", "mpeg4")
                        if (vc == "libx264") a += listOf("-c:v", vc, "-preset", "veryfast", "-crf", "$crf", "-pix_fmt", "yuv420p")
                        else a += listOf("-c:v", vc, "-q:v", "$qv")
                        audio = pick(enc, "aac", "libfdk_aac", "libmp3lame")
                        if (fmt.ext != "mkv") a += listOf("-movflags", "+faststart")
                    }
                }
                if (audio != null) a += listOf("-c:a", audio, "-b:a", "160k")
                else if (info.hasAudio) throw ConvError(Strings.f("cv_err_noenc", "audio"))
                else a += "-an"
            }
            else -> {
                if (!info.hasVideo) throw ConvError(t("cv_err_novideo"))
                val fps = intArrayOf(15, 12, 10, 8)[lvl]
                val sc = if (res == 0) "scale='min(480,iw)':-2:flags=lanczos"
                else "scale=-2:'min(${heights[res]},ih)':flags=lanczos"
                a += listOf("-an", "-map", "0:v:0")
                if (fmt.ext == "gif") {
                    need(enc, "gif")
                    a += listOf(
                        "-vf", "fps=$fps,$sc,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5",
                        "-loop", "0"
                    )
                } else {
                    val c = need(enc, "libwebp_anim", "libwebp")
                    a += listOf(
                        "-vf", "fps=$fps,$sc", "-c:v", c, "-loop", "0",
                        "-q:v", "${intArrayOf(80, 65, 50, 35)[lvl]}", "-compression_level", "4"
                    )
                }
            }
        }
        a += outF.absolutePath
        return a
    }

    private fun uniqueOut(dir: File, name: String, ext: String): File {
        val base = name.substringBeforeLast('.', name)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "converted" }.take(100)
        var f = File(dir, "$base.$ext")
        var n = 1
        while (f.exists()) f = File(dir, "$base ($n).$ext").also { n++ }
        return f
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
}
