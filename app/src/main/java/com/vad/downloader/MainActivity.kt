package com.vad.downloader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * VAD - загрузчик видео и аудио по ссылке (порт Video_Audio_Downloader v5.1).
 * Движок: yt-dlp + ffmpeg (библиотека youtubedl-android).
 */
class MainActivity : Activity() {

    private val processId = DownloadService.PROCESS_ID
    private var lastNotifyAt = 0L
    private val reqPickFolder = 42
    private val qualityNames = listOf("FHD (1080p)", "HD (720p)", "Максимум")

    private lateinit var urlInput: EditText
    private lateinit var playlistBox: CheckBox
    private lateinit var qualitySpinner: Spinner
    private lateinit var pathText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var actionButtons: List<Button>
    private lateinit var cancelButton: Button

    @Volatile private var engineReady = false
    @Volatile private var busy = false

    private val prefs by lazy { getSharedPreferences("vad", Context.MODE_PRIVATE) }

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        handleShareIntent(intent)
        initEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Загрузчик Аудио и Видео"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })

        urlInput = EditText(this).apply {
            hint = "Ссылка на видео"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            maxLines = 3
        }
        root.addView(urlInput)

        val urlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urlRow.addView(button("Вставить из буфера") { pasteFromClipboard() })
        urlRow.addView(button("Очистить") { urlInput.setText("") })
        root.addView(urlRow)

        playlistBox = CheckBox(this).apply { text = "Скачать весь плейлист" }
        root.addView(playlistBox)

        qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, qualityNames
            )
        }
        root.addView(TextView(this).apply { text = "Качество (для авто-режима)" })
        root.addView(qualitySpinner)

        pathText = TextView(this)
        root.addView(pathText)
        val pathRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pathRow.addView(button("Выбрать папку…") { pickFolder() })
        pathRow.addView(button("По умолчанию") {
            prefs.edit().remove("tree_uri").apply()
            refreshPath()
        })
        root.addView(pathRow)
        refreshPath()

        val bAuto = button("Скачать видео (авто)") { startAuto() }
        val bPick = button("Скачать, выбрав формат…") { startPickFormat() }
        val bMp3 = button("Скачать MP3") { startMp3() }
        root.addView(bAuto)
        root.addView(bPick)
        root.addView(bMp3)

        cancelButton = button("Отмена") { cancelDownload() }.apply { isEnabled = false }
        root.addView(cancelButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(progressBar)

        statusText = TextView(this).apply { text = "Запуск движка…" }
        root.addView(statusText)

        val toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bUpdate = button("Обновить yt-dlp") { updateYtDlp() }
        val bProxy = button("Прокси…") { showProxyDialog() }
        toolsRow.addView(bUpdate)
        toolsRow.addView(bProxy)
        root.addView(toolsRow)

        actionButtons = listOf(bAuto, bPick, bMp3, bUpdate)
        actionButtons.forEach { it.isEnabled = false }

        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.START
        }
        logScroll = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(logScroll)

        setContentView(root)
    }

    private fun handleShareIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type == "text/plain") {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val link = Regex("https?://\\S+").find(text)?.value ?: text
            urlInput.setText(link)
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) toast("Буфер обмена пуст") else urlInput.setText(text)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun log(line: String) = runOnUiThread {
        logText.append(line + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setBusy(b: Boolean) = runOnUiThread {
        busy = b
        actionButtons.forEach { it.isEnabled = engineReady && !b }
        cancelButton.isEnabled = b
        if (!b) progressBar.progress = 0
    }

    private fun setStatus(s: String) = runOnUiThread { statusText.text = s }

    // -------------------------------------------------------------- Движок

    private fun initEngine() {
        Thread {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
                engineReady = true
                setBusy(true)
                val last = prefs.getLong("last_update", 0L)
                if (System.currentTimeMillis() - last > 12L * 3600 * 1000) {
                    setStatus("Обновление yt-dlp…")
                    try {
                        YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel.NIGHTLY)
                        prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
                        log("yt-dlp обновлён автоматически.")
                    } catch (e: Throwable) {
                        log("Авто-обновление yt-dlp не удалось: ${e.message}")
                    }
                }
                setStatus("Готово. Вставьте ссылку.")
                setBusy(false)
            } catch (e: Throwable) {
                setStatus("Ошибка запуска движка")
                log("Ошибка инициализации: ${e.message}")
            }
        }.start()
    }

    private fun updateYtDlp() {
        if (busy) return
        setBusy(true)
        setStatus("Обновление yt-dlp…")
        Thread {
            try {
                val res = YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel.NIGHTLY)
                prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
                log("yt-dlp: обновление завершено (${res ?: "без изменений"})")
                setStatus("yt-dlp обновлён")
            } catch (e: Throwable) {
                log("Ошибка обновления: ${e.message}")
                setStatus("Не удалось обновить yt-dlp")
            } finally {
                setBusy(false)
            }
        }.start()
    }

    private fun showProxyDialog() {
        val input = EditText(this).apply {
            hint = "например socks5://127.0.0.1:1080"
            setText(prefs.getString("proxy", ""))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Прокси (пусто - выключен)")
            .setMessage("Для обхода блокировок можно использовать приложение ByeDPI/VPN со SOCKS5.")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit().putString("proxy", input.text.toString().trim()).apply()
                toast("Сохранено")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ------------------------------------------------------ Папка загрузки

    private fun treeUri(): Uri? = prefs.getString("tree_uri", null)?.let { Uri.parse(it) }

    private fun targetLabel(): String {
        val t = treeUri() ?: return "Загрузки/VAD"
        return try {
            DocumentsContract.getTreeDocumentId(t).replace(":", "/")
        } catch (e: Exception) {
            t.toString()
        }
    }

    private fun refreshPath() {
        pathText.text = "Папка загрузки: ${targetLabel()}"
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(i, reqPickFolder)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqPickFolder && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                log("Не удалось запомнить доступ к папке: ${e.message}")
            }
            prefs.edit().putString("tree_uri", uri.toString()).apply()
            refreshPath()
            toast("Папка выбрана")
        }
    }

    // ---------------------------------------------------------- Скачивание

    private fun currentUrl(): String? {
        val u = urlInput.text.toString().trim()
        if (u.isEmpty()) {
            toast("Введите ссылку на видео")
            return null
        }
        return u
    }

    private fun qualityFormat(): String = when (qualitySpinner.selectedItemPosition) {
        0 -> "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best"
        1 -> "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best"
        else -> "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
    }

    private fun baseRequest(url: String, playlist: Boolean): YoutubeDLRequest {
        val req = YoutubeDLRequest(url)
        req.addOption(if (playlist) "--yes-playlist" else "--no-playlist")
        req.addOption("--windows-filenames")
        // На Android нет записываемого /tmp: временные файлы и кэш держим в папке приложения
        val tmpDir = File(cacheDir, "ytmp").apply { mkdirs() }
        val cacheDirYt = File(cacheDir, "ytcache").apply { mkdirs() }
        req.addOption("--paths", "temp:" + tmpDir.absolutePath)
        req.addOption("--cache-dir", cacheDirYt.absolutePath)
        val proxy = prefs.getString("proxy", "").orEmpty()
        if (proxy.isNotEmpty()) req.addOption("--proxy", proxy)
        return req
    }

    private fun startAuto() {
        val url = currentUrl() ?: return
        runDownload(url, playlistBox.isChecked) { req ->
            req.addOption("-f", qualityFormat())
            req.addOption("--merge-output-format", "mp4")
        }
    }

    private fun startMp3() {
        val url = currentUrl() ?: return
        runDownload(url, playlistBox.isChecked) { req ->
            req.addOption("-f", "bestaudio/best")
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "0")
        }
    }

    private class VFormat(val label: String, val id: String, val hasAudio: Boolean)
    private class AFormat(val label: String, val id: String)

    private fun startPickFormat() {
        val url = currentUrl() ?: return
        val playlist = playlistBox.isChecked
        setBusy(true)
        setStatus("Получение форматов…")
        Thread {
            try {
                val req = baseRequest(url, playlist)
                req.addOption("-J")
                req.addOption("--no-warnings")
                if (playlist) req.addOption("--playlist-items", "1")
                val out = YoutubeDL.getInstance().execute(req).out
                var info = JSONObject(out)
                val entries = info.optJSONArray("entries")
                if (entries != null && entries.length() > 0) info = entries.getJSONObject(0)

                val video = ArrayList<Pair<Int, VFormat>>()
                val audio = ArrayList<Pair<Double, AFormat>>()
                val formats = info.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.getJSONObject(i)
                        val vc = f.optString("vcodec", "none")
                        val ac = f.optString("acodec", "none")
                        val fid = f.optString("format_id", "?")
                        val ext = f.optString("ext", "")
                        if (vc != "none") {
                            val h = f.optInt("height", 0)
                            val w = f.optInt("width", 0)
                            val res = if (w > 0 && h > 0) "${w}x$h" else f.optString("resolution", "?")
                            val tag = if (ac != "none") "(+звук)" else "(без звука)"
                            video.add(h to VFormat("$fid | $res | ${vc.substringBefore('.')} | $ext $tag", fid, ac != "none"))
                        } else if (ac != "none") {
                            val abr = f.optDouble("abr", 0.0)
                            audio.add(abr to AFormat("$fid | ${ac.substringBefore('.')} | ${abr.toInt()}k | $ext", fid))
                        }
                    }
                }
                val v = video.sortedByDescending { it.first }.map { it.second }
                val a = audio.sortedByDescending { it.first }.map { it.second }
                val title = info.optString("title", url)
                log("Форматы для: $title")
                runOnUiThread {
                    setBusy(false)
                    setStatus("Выберите формат")
                    if (v.isEmpty()) {
                        log("Видеоформатов не найдено - используйте авто-режим или MP3.")
                    } else {
                        chooseVideo(url, playlist, v, a)
                    }
                }
            } catch (e: Throwable) {
                log("Ошибка: ${e.message}")
                setStatus("Не удалось получить форматы")
                setBusy(false)
            }
        }.start()
    }

    private fun chooseVideo(url: String, playlist: Boolean, v: List<VFormat>, a: List<AFormat>) {
        AlertDialog.Builder(this)
            .setTitle("Видео")
            .setItems(v.map { it.label }.toTypedArray()) { _, which ->
                val chosen = v[which]
                if (chosen.hasAudio || a.isEmpty()) {
                    downloadFormat(url, playlist, if (chosen.hasAudio) chosen.id else "${chosen.id}+bestaudio")
                } else {
                    chooseAudio(url, playlist, chosen, a)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun chooseAudio(url: String, playlist: Boolean, v: VFormat, a: List<AFormat>) {
        val labels = listOf("Лучшее аудио") + a.map { it.label }
        AlertDialog.Builder(this)
            .setTitle("Аудио")
            .setItems(labels.toTypedArray()) { _, which ->
                val aid = if (which == 0) "bestaudio" else a[which - 1].id
                downloadFormat(url, playlist, "${v.id}+$aid")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun downloadFormat(url: String, playlist: Boolean, fmt: String) {
        runDownload(url, playlist) { req ->
            req.addOption("-f", fmt)
            req.addOption("--merge-output-format", "mp4")
        }
    }

    private fun cancelDownload() {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
            log("Остановка процесса…")
        } catch (e: Throwable) {
            log("Не удалось остановить: ${e.message}")
        }
    }

    private val progressRe = Regex(
        "(\\d+(?:\\.\\d+)?)%\\s+of\\s+~?\\s*(\\d+(?:\\.\\d+)?)\\s*([KMGT]?i?B)(?:.*?\\bat\\s+(\\d+(?:\\.\\d+)?)\\s*([KMGT]?i?B)/s)?"
    )

    private fun toMb(v: Double, unit: String): Double = when (unit.uppercase().replace("I", "")) {
        "B" -> v / (1024 * 1024)
        "KB" -> v / 1024
        "MB" -> v
        "GB" -> v * 1024
        "TB" -> v * 1024 * 1024
        else -> v
    }

    private fun fmtEta(sec: Long): String =
        if (sec < 0) "?" else String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)

    /** -> строка вида "45% · 22.6 / 50.0 МБ · 2.5 МБ/с · осталось 0:12" либо null */
    private fun progressText(pct: Float, eta: Long, line: String): String? {
        val m = progressRe.find(line) ?: return null
        val p = m.groupValues[1].toDouble()
        val total = toMb(m.groupValues[2].toDouble(), m.groupValues[3])
        val done = total * p / 100.0
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "%.1f%% · %.1f / %.1f МБ", p, done, total))
        if (m.groupValues[4].isNotEmpty()) {
            val sp = toMb(m.groupValues[4].toDouble(), m.groupValues[5])
            sb.append(String.format(Locale.US, " · %.2f МБ/с", sp))
        }
        sb.append(" · осталось ").append(fmtEta(eta))
        return sb.toString()
    }

    private fun runDownload(url: String, playlist: Boolean, configure: (YoutubeDLRequest) -> Unit) {
        if (!engineReady || busy) return
        setBusy(true)
        setStatus("Скачивание…")
        DownloadService.start(this)
        Thread {
            var doneTitle = "Скачивание"
            var doneText = ""
            val tmp = File(cacheDir, "dl").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                val req = baseRequest(url, playlist)
                configure(req)
                req.addOption("--newline")
                val template = if (playlist)
                    "${tmp.absolutePath}/%(playlist_title|PlayList)s/%(playlist_index)s - %(title).120s [%(id)s].%(ext)s"
                else
                    "${tmp.absolutePath}/%(title).120s [%(id)s].%(ext)s"
                req.addOption("-o", template)

                YoutubeDL.getInstance().execute(req, processId) { progress, eta, line ->
                    val info = progressText(progress, eta, line)
                    runOnUiThread {
                        if (progress >= 0) progressBar.progress = progress.toInt()
                        statusText.text = info ?: "Скачивание… ${progress.toInt()}%"
                    }
                    if (info != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyAt > 700) {
                            lastNotifyAt = now
                            DownloadService.update(this, "Скачивание", info, progress.toInt())
                        }
                    }
                    if (!line.startsWith("[download]") || !line.contains("%")) log(line)
                }

                setStatus("Сохранение в ${targetLabel()}…")
                val saved = saveToDownloads(tmp)
                if (saved == 0) {
                    doneTitle = "Ошибка"
                    doneText = "Файлы не найдены"
                    setStatus("Файлы не найдены")
                    log("Скачивание завершилось, но файлов нет.")
                } else {
                    doneTitle = "Скачивание завершено"
                    doneText = "Сохранено файлов: $saved (${targetLabel()})"
                    setStatus("Готово! Сохранено файлов: $saved (${targetLabel()})")
                    log("Готово. Файлы в папке ${targetLabel()}.")
                }
            } catch (e: Throwable) {
                val m = e.message.orEmpty()
                if (m.contains("interrupted", true) || m.contains("destroyed", true)) {
                    doneTitle = "Остановлено"
                    doneText = "Скачивание отменено"
                    setStatus("Остановлено")
                    log("Процесс остановлен.")
                } else {
                    doneTitle = "Ошибка скачивания"
                    doneText = m.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "Неизвестная ошибка"
                    setStatus("Ошибка скачивания")
                    log("Ошибка: $m")
                    if (m.contains("sign in", true) || m.contains("Precondition", true)) {
                        log("Подсказка: нажмите «Обновить yt-dlp» и повторите; если не помогло - включите прокси/VPN.")
                    }
                }
            } finally {
                tmp.deleteRecursively()
                DownloadService.stop(this)
                DownloadService.finish(this, doneTitle, doneText)
                setBusy(false)
            }
        }.start()
    }

    /** Переносит готовые файлы из кэша в общую папку «Загрузки/VAD» через MediaStore. */
    private fun saveToDownloads(dir: File): Int {
        val tree = treeUri()
        if (tree != null) {
            try {
                return saveToTree(dir, tree)
            } catch (e: Exception) {
                log("Не удалось сохранить в выбранную папку (${e.message}). Сохраняю в Загрузки/VAD.")
            }
        }
        var count = 0
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val ext = f.extension.lowercase()
            if (ext == "part" || ext == "ytdl" || ext == "temp") return@forEach
            val rel = f.parentFile!!.relativeTo(dir).path.let { if (it.isEmpty()) "" else "/$it" }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/VAD$rel")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                }
                log("Сохранено: ${f.name}")
                count++
            }
        }
        return count
    }

    /** Сохраняет в папку, выбранную пользователем (Storage Access Framework). */
    private fun saveToTree(dir: File, tree: Uri): Int {
        val resolver = contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val folders = HashMap<String, Uri>()
        var count = 0
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val ext = f.extension.lowercase()
            if (ext == "part" || ext == "ytdl" || ext == "temp") return@forEach
            val rel = f.parentFile!!.relativeTo(dir).path
            var parent = root
            if (rel.isNotEmpty()) {
                var acc = ""
                for (part in rel.split("/")) {
                    acc = if (acc.isEmpty()) part else "$acc/$part"
                    val existing = folders[acc]
                    parent = if (existing != null) existing else {
                        val created = DocumentsContract.createDocument(
                            resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, part
                        ) ?: throw IllegalStateException("не удалось создать папку $part")
                        folders[acc] = created
                        created
                    }
                }
            }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val doc = DocumentsContract.createDocument(resolver, parent, mime, f.name)
                ?: throw IllegalStateException("не удалось создать файл ${f.name}")
            resolver.openOutputStream(doc)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
            log("Сохранено: ${f.name}")
            count++
        }
        return count
    }
}
