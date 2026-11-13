package com.vad.downloader

import android.content.Context
import java.io.File

/**
 * Перекодирование видео в GIF через ffmpeg из библиотеки youtubedl-android (тот же бинарник, что у конвертера).
 * Звук отбрасывается (-an): контейнер GIF его не принимает, а именно из-за него падало перекодирование средствами yt-dlp.
 * Файлы .gif никогда не трогаются.
 */
object GifTool {
    @Volatile private var current: Process? = null
    @Volatile var cancelled = false
        private set

    private val videoExt = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi", "flv", "3gp")

    /** Начало новой загрузки: сбрасывает флаг отмены. */
    fun reset() { cancelled = false }

    /** Отмена: останавливает текущий ffmpeg, а загрузки фото/гифок сами заметят флаг. */
    fun cancel() {
        cancelled = true
        try { current?.destroy() } catch (_: Throwable) {}
    }

    /** Бросает то же исключение, по которому окно загрузки понимает «остановлено пользователем». */
    fun checkCancelled() {
        if (cancelled) throw RuntimeException("interrupted")
    }

    /**
     * Превращает каждое видео в папке в GIF (рядом, с тем же именем) и удаляет исходник.
     * Если не вышло, исходное видео остаётся: пользователь получает то, что удалось скачать.
     * width/fps = 0 означают «как в оригинале».
     */
    fun convertAll(ctx: Context, dir: File, width: Int, fps: Int, onFail: (String, String) -> Unit): Int {
        var n = 0
        val sources = dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in videoExt }.toList()
        for (src in sources) {
            val dst = File(src.parentFile, src.nameWithoutExtension + ".gif")
            val err = convert(ctx, src, dst, width, fps)
            if (err == null) {
                src.delete()
                n++
            } else {
                checkCancelled()
                onFail(src.name, err)
            }
        }
        return n
    }

    /** Один файл. Возвращает null при успехе или текст ошибки. */
    fun convert(ctx: Context, src: File, dst: File, width: Int, fps: Int): String? {
        val bin = findBinary(ctx) ?: return "ffmpeg not found"
        val chain = ArrayList<String>()
        if (fps > 0) chain += "fps=$fps"
        if (width > 0) chain += "scale='min($width,iw)':-2:flags=lanczos"
        val pre = if (chain.isEmpty()) "" else chain.joinToString(",") + ","
        val vf = pre + "split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5"
        val args = listOf(
            "-y", "-hide_banner", "-nostdin", "-loglevel", "error",
            "-i", src.absolutePath, "-an", "-map", "0:v:0", "-vf", vf, "-loop", "0", dst.absolutePath
        )
        return try {
            val p = builder(ctx, bin, args).redirectErrorStream(true).start()
            current = p
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            current = null
            if (code == 0 && dst.length() > 0) null
            else {
                dst.delete()
                out.trim().takeLast(300).ifEmpty { "ffmpeg exit $code" }
            }
        } catch (e: Throwable) {
            current = null
            dst.delete()
            e.message ?: e.javaClass.simpleName
        }
    }

    // ---- поиск и запуск ffmpeg (как в конвертере)

    private fun findBinary(ctx: Context): File? {
        val lib = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (lib.isFile) return lib
        val base = File(ctx.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        return base.walkTopDown().firstOrNull { it.isFile && (it.name == "ffmpeg" || it.name == "libffmpeg.so") }
    }

    private fun builder(ctx: Context, bin: File, args: List<String>): ProcessBuilder {
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
