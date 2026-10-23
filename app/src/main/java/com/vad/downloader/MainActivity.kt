package com.vad.downloader

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.webkit.MimeTypeMap
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
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

    private var kawaii = false
    private var catTaps = 0
    private val kawaiiText = 0xFF6A2C6B.toInt()

    @Volatile private var engineReady = false
    @Volatile private var busy = false

    private val prefs by lazy { getSharedPreferences("vad", Context.MODE_PRIVATE) }

    private fun t(key: String) = Strings.t(key)
    private fun f(key: String, vararg a: Any?) = Strings.f(key, *a)

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Strings.init(this)
        kawaii = prefs.getBoolean("kawaii_unlocked", false) && prefs.getBoolean("kawaii_on", false)
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
            if (kawaii) styleKawaiiButton(this)
        }

    private fun tintText(tv: TextView) {
        if (kawaii) tv.setTextColor(kawaiiText)
    }

    private fun styledAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, items) {
            init {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                if (kawaii) (v as? TextView)?.setTextColor(kawaiiText)
                return v
            }
        }

    /** Няшные кнопки: градиент, скругления, ripple и «пружинка» при нажатии. */
    private fun styleKawaiiButton(b: Button) {
        val r = dp(24).toFloat()
        val shape = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFFF9ACB.toInt(), 0xFFFFC2E5.toInt())
        ).apply {
            cornerRadius = r
            setStroke(dp(2), 0xFFFFFFFF.toInt())
        }
        val mask = GradientDrawable().apply {
            cornerRadius = r
            setColor(0xFFFFFFFF.toInt())
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(0x99FFFFFF.toInt()), shape, mask)
        b.background = InsetDrawable(ripple, dp(4), dp(3), dp(4), dp(3))
        b.setTextColor(kawaiiText)
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.stateListAnimator = null
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    /** Секретная кнопка-кошечка: 3 нажатия открывают няшный режим, дальше - переключатель. */
    private fun onCatTap(v: View) {
        if (prefs.getBoolean("kawaii_unlocked", false)) {
            kawaii = !kawaii
            prefs.edit().putBoolean("kawaii_on", kawaii).apply()
            buildUi()
            return
        }
        catTaps++
        if (catTaps >= 3) {
            prefs.edit().putBoolean("kawaii_unlocked", true).putBoolean("kawaii_on", true).apply()
            kawaii = true
            Strings.set(this, "rk")
            buildUi()
            toast(t("kawaii_on"))
        } else {
            ObjectAnimator.ofFloat(v, "rotation", 0f, -18f, 18f, -10f, 0f).setDuration(300).start()
        }
    }

    /** Строит интерфейс заново (при первом запуске и при смене языка), сохраняя состояние. */
    private fun buildUi() {
        // --- запоминаем состояние старых вью ---
        val first = !::urlInput.isInitialized
        val oldUrl = if (first) "" else urlInput.text.toString()
        val oldPlaylist = if (first) false else playlistBox.isChecked
        val oldQuality = if (first) 0 else qualitySpinner.selectedItemPosition
        val oldLog = if (first) "" else logText.text.toString()
        val oldProgress = if (first) 0 else progressBar.progress
        val oldStatus = if (first) null else statusText.text.toString()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            layoutDirection = if (Strings.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            if (kawaii) {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFFFFC1E3.toInt(), 0xFFE0C3FF.toInt(), 0xFFBDE7FF.toInt())
                )
            }
        }

        // --- заголовок, выбор языка, секретная кнопка ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerLeft.addView(TextView(this).apply {
            text = t("title")
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            tintText(this)
        })

        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        langRow.addView(TextView(this).apply {
            text = t("lang_label") + ": "
            setPadding(0, 0, dp(8), 0)
            tintText(this)
        })
        val langList = Strings.available(prefs.getBoolean("kawaii_unlocked", false))
        val langSpinner = Spinner(this).apply {
            adapter = styledAdapter(langList.map { it.name })
            setSelection(langList.indexOfFirst { it.code == Strings.code }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val code = langList[pos].code
                    if (code != Strings.code) {
                        Strings.set(this@MainActivity, code)
                        Handler(Looper.getMainLooper()).post { buildUi() }
                    }
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        langRow.addView(langSpinner)
        val catButton = Button(this).apply {
            text = "\uD83D\uDC31"
            isAllCaps = false
            textSize = 18f
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onCatTap(this) }
            if (kawaii) styleKawaiiButton(this)
        }
        langRow.addView(catButton)
        headerLeft.addView(langRow)
        header.addView(headerLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (kawaii) {
            val girl = ImageView(this).apply {
                setImageResource(R.drawable.kawaii_girl)
                layoutParams = LinearLayout.LayoutParams(dp(110), dp(121))
                setOnClickListener {
                    toast(t("girl_tap"))
                    animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                    }.start()
                }
            }
            val bob = ObjectAnimator.ofFloat(girl, "translationY", 0f, -dp(6).toFloat()).apply {
                duration = 1100
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
            }
            girl.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    bob.cancel()
                }
            })
            bob.start()
            header.addView(girl)
        }
        root.addView(header)

        // --- ссылка ---
        urlInput = EditText(this).apply {
            hint = t("url_hint")
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            maxLines = 3
            textDirection = View.TEXT_DIRECTION_LTR
            setText(oldUrl)
            if (kawaii) {
                setTextColor(kawaiiText)
                setHintTextColor(0x996A2C6B.toInt())
                backgroundTintList = ColorStateList.valueOf(0xFFFF8FC7.toInt())
            }
        }
        root.addView(urlInput)

        val urlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urlRow.addView(button(t("paste")) { pasteFromClipboard() })
        urlRow.addView(button(t("clear")) { urlInput.setText("") })
        root.addView(urlRow)

        playlistBox = CheckBox(this).apply {
            text = t("playlist")
            isChecked = oldPlaylist
            tintText(this)
            if (kawaii) buttonTintList = ColorStateList.valueOf(0xFFFF5FA2.toInt())
        }
        root.addView(playlistBox)

        qualitySpinner = Spinner(this).apply {
            adapter = styledAdapter(listOf("FHD (1080p)", "HD (720p)", t("q_max")))
            setSelection(oldQuality)
        }
        root.addView(TextView(this).apply { text = t("quality_label"); tintText(this) })
        root.addView(qualitySpinner)

        // --- папка загрузки ---
        pathText = TextView(this).apply { tintText(this) }
        root.addView(pathText)
        val pathRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pathRow.addView(button(t("pick_folder")) { pickFolder() })
        pathRow.addView(button(t("default_folder")) {
            prefs.edit().remove("tree_uri").apply()
            refreshPath()
        })
        root.addView(pathRow)
        refreshPath()

        // --- кнопки действий ---
        val bAuto = button(t("dl_auto")) { startAuto() }
        val bPick = button(t("dl_pick")) { startPickFormat() }
        val bMp3 = button(t("dl_mp3")) { startMp3() }
        root.addView(bAuto)
        root.addView(bPick)
        root.addView(bMp3)

        cancelButton = button(t("cancel")) { cancelDownload() }
        root.addView(cancelButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = oldProgress
            if (kawaii) progressTintList = ColorStateList.valueOf(0xFFFF5FA2.toInt())
        }
        root.addView(progressBar)

        statusText = TextView(this).apply {
            text = when {
                oldStatus == null -> t("st_starting")
                busy -> oldStatus
                engineReady -> t("st_ready")
                else -> t("st_starting")
            }
            tintText(this)
        }
        root.addView(statusText)

        val toolsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bUpdate = button(t("update_btn")) { updateYtDlp() }
        val bProxy = button(t("proxy_btn")) { showProxyDialog() }
        toolsRow.addView(bUpdate)
        toolsRow.addView(bProxy)
        root.addView(toolsRow)

        actionButtons = listOf(bAuto, bPick, bMp3, bUpdate)
        actionButtons.forEach { it.isEnabled = engineReady && !busy }
        cancelButton.isEnabled = busy

        // --- лог (всегда слева направо) ---
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            text = oldLog
            if (kawaii) setTextColor(0xFF5A3A78.toInt())
        }
        logScroll = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            if (kawaii) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(0x99FFFFFF.toInt())
                }
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        }
        root.addView(logScroll)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

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
        if (text.isEmpty()) toast(t("clip_empty")) else urlInput.setText(text)
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
                    setStatus(t("st_updating"))
                    try {
                        YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel.NIGHTLY)
                        prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
                        log(t("log_autoupdated"))
                    } catch (e: Throwable) {
                        log(f("log_autoupdate_fail", e.message))
                    }
                }
                setStatus(t("st_ready"))
                setBusy(false)
            } catch (e: Throwable) {
                setStatus(t("st_init_fail"))
                log(f("log_init_fail", e.message))
            }
        }.start()
    }

    private fun updateYtDlp() {
        if (busy) return
        setBusy(true)
        setStatus(t("st_updating"))
        Thread {
            try {
                val res = YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel.NIGHTLY)
                prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
                log(f("log_update_done", res ?: "-"))
                setStatus(t("st_update_done"))
            } catch (e: Throwable) {
                log(f("log_update_fail", e.message))
                setStatus(t("st_update_fail"))
            } finally {
                setBusy(false)
            }
        }.start()
    }

    private fun showProxyDialog() {
        val input = EditText(this).apply {
            hint = t("proxy_hint")
            setText(prefs.getString("proxy", ""))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            textDirection = View.TEXT_DIRECTION_LTR
        }
        AlertDialog.Builder(this)
            .setTitle(t("proxy_title"))
            .setMessage(t("proxy_msg"))
            .setView(input)
            .setPositiveButton(t("save")) { _, _ ->
                prefs.edit().putString("proxy", input.text.toString().trim()).apply()
                toast(t("saved"))
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    // ------------------------------------------------------ Папка загрузки

    private fun treeUri(): Uri? = prefs.getString("tree_uri", null)?.let { Uri.parse(it) }

    private fun targetLabel(): String {
        val tu = treeUri() ?: return "Download/VAD"
        return try {
            DocumentsContract.getTreeDocumentId(tu).replace(":", "/")
        } catch (e: Exception) {
            tu.toString()
        }
    }

    private fun refreshPath() {
        pathText.text = f("folder", targetLabel())
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
                log(f("log_perm_fail", e.message))
            }
            prefs.edit().putString("tree_uri", uri.toString()).apply()
            refreshPath()
            toast(t("folder_chosen"))
        }
    }

    // ---------------------------------------------------------- Скачивание

    private fun currentUrl(): String? {
        val u = urlInput.text.toString().trim()
        if (u.isEmpty()) {
            toast(t("enter_url"))
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
        setStatus(t("st_formats"))
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
                        val fm = formats.getJSONObject(i)
                        val vc = fm.optString("vcodec", "none")
                        val ac = fm.optString("acodec", "none")
                        val fid = fm.optString("format_id", "?")
                        val ext = fm.optString("ext", "")
                        if (vc != "none") {
                            val h = fm.optInt("height", 0)
                            val w = fm.optInt("width", 0)
                            val res = if (w > 0 && h > 0) "${w}x$h" else fm.optString("resolution", "?")
                            val tag = if (ac != "none") t("with_sound") else t("no_sound")
                            video.add(h to VFormat("$fid | $res | ${vc.substringBefore('.')} | $ext $tag", fid, ac != "none"))
                        } else if (ac != "none") {
                            val abr = fm.optDouble("abr", 0.0)
                            audio.add(abr to AFormat("$fid | ${ac.substringBefore('.')} | ${abr.toInt()}k | $ext", fid))
                        }
                    }
                }
                val v = video.sortedByDescending { it.first }.map { it.second }
                val a = audio.sortedByDescending { it.first }.map { it.second }
                val title = info.optString("title", url)
                log(f("log_formats_for", title))
                runOnUiThread {
                    setBusy(false)
                    setStatus(t("st_choose"))
                    if (v.isEmpty()) {
                        log(t("log_no_video"))
                    } else {
                        chooseVideo(url, playlist, v, a)
                    }
                }
            } catch (e: Throwable) {
                log(f("log_error", e.message))
                setStatus(t("st_formats_fail"))
                setBusy(false)
            }
        }.start()
    }

    private fun chooseVideo(url: String, playlist: Boolean, v: List<VFormat>, a: List<AFormat>) {
        AlertDialog.Builder(this)
            .setTitle(t("video"))
            .setItems(v.map { it.label }.toTypedArray()) { _, which ->
                val chosen = v[which]
                if (chosen.hasAudio || a.isEmpty()) {
                    downloadFormat(url, playlist, if (chosen.hasAudio) chosen.id else "${chosen.id}+bestaudio")
                } else {
                    chooseAudio(url, playlist, chosen, a)
                }
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    private fun chooseAudio(url: String, playlist: Boolean, v: VFormat, a: List<AFormat>) {
        val labels = listOf(t("best_audio")) + a.map { it.label }
        AlertDialog.Builder(this)
            .setTitle(t("audio"))
            .setItems(labels.toTypedArray()) { _, which ->
                val aid = if (which == 0) "bestaudio" else a[which - 1].id
                downloadFormat(url, playlist, "${v.id}+$aid")
            }
            .setNegativeButton(t("cancel"), null)
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
            log(t("log_stopping"))
        } catch (e: Throwable) {
            log(f("log_stop_fail", e.message))
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

    /** -> строка вида "45.3% · 22.6 / 50.0 MB · 2.50 MB/s · 0:12 left" либо null */
    private fun progressText(eta: Long, line: String): String? {
        val m = progressRe.find(line) ?: return null
        val p = m.groupValues[1].toDouble()
        val total = toMb(m.groupValues[2].toDouble(), m.groupValues[3])
        val done = total * p / 100.0
        val mb = t("mb")
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "%.1f%% · %.1f / %.1f %s", p, done, total, mb))
        if (m.groupValues[4].isNotEmpty()) {
            val sp = toMb(m.groupValues[4].toDouble(), m.groupValues[5])
            sb.append(String.format(Locale.US, " · %.2f %s", sp, t("mbs")))
        }
        sb.append(" · ").append(f("left", fmtEta(eta)))
        return sb.toString()
    }

    private fun runDownload(url: String, playlist: Boolean, configure: (YoutubeDLRequest) -> Unit) {
        if (!engineReady || busy) return
        setBusy(true)
        setStatus(t("st_downloading"))
        DownloadService.start(this)
        Thread {
            var doneTitle = t("n_title")
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
                    val info = progressText(eta, line)
                    runOnUiThread {
                        if (progress >= 0) progressBar.progress = progress.toInt()
                        statusText.text = info ?: (t("st_downloading") + " ${progress.toInt()}%")
                    }
                    if (info != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyAt > 700) {
                            lastNotifyAt = now
                            DownloadService.update(this, t("n_title"), info, progress.toInt())
                        }
                    }
                    if (!line.startsWith("[download]") || !line.contains("%")) log(line)
                }

                setStatus(f("st_saving", targetLabel()))
                val saved = saveToDownloads(tmp)
                if (saved == 0) {
                    doneTitle = t("n_error")
                    doneText = t("st_no_files")
                    setStatus(t("st_no_files"))
                    log(t("log_no_files"))
                } else {
                    doneTitle = t("n_done_title")
                    doneText = f("n_done_text", saved, targetLabel())
                    setStatus(f("st_done", saved, targetLabel()))
                    log(f("log_done", targetLabel()))
                }
            } catch (e: Throwable) {
                val m = e.message.orEmpty()
                if (m.contains("interrupted", true) || m.contains("destroyed", true)) {
                    doneTitle = t("n_stopped")
                    doneText = t("n_cancelled")
                    setStatus(t("st_stopped"))
                    log(t("log_stopped"))
                } else {
                    doneTitle = t("n_error")
                    doneText = m.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: t("n_unknown")
                    setStatus(t("st_error"))
                    log(f("log_error", m))
                    if (m.contains("sign in", true) || m.contains("Precondition", true)) {
                        log(t("log_hint_signin"))
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

    /** Переносит готовые файлы из кэша в выбранную папку или в общую «Download/VAD». */
    private fun saveToDownloads(dir: File): Int {
        val tree = treeUri()
        if (tree != null) {
            try {
                return saveToTree(dir, tree)
            } catch (e: Exception) {
                log(f("log_saf_fail", e.message))
            }
        }
        var count = 0
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val ext = file.extension.lowercase()
            if (ext == "part" || ext == "ytdl" || ext == "temp") return@forEach
            val rel = file.parentFile!!.relativeTo(dir).path.let { if (it.isEmpty()) "" else "/$it" }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/VAD$rel")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                log(f("log_saved_file", file.name))
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
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val ext = file.extension.lowercase()
            if (ext == "part" || ext == "ytdl" || ext == "temp") return@forEach
            val rel = file.parentFile!!.relativeTo(dir).path
            var parent = root
            if (rel.isNotEmpty()) {
                var acc = ""
                for (part in rel.split("/")) {
                    acc = if (acc.isEmpty()) part else "$acc/$part"
                    val existing = folders[acc]
                    parent = if (existing != null) existing else {
                        val created = DocumentsContract.createDocument(
                            resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, part
                        ) ?: throw IllegalStateException("mkdir $part")
                        folders[acc] = created
                        created
                    }
                }
            }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val doc = DocumentsContract.createDocument(resolver, parent, mime, file.name)
                ?: throw IllegalStateException("create ${file.name}")
            resolver.openOutputStream(doc)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            log(f("log_saved_file", file.name))
            count++
        }
        return count
    }
}
