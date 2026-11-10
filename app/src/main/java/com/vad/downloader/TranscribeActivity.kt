package com.vad.downloader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Расшифровка речи из аудио/видео (whisper.cpp, офлайн) и перевод текста (ML Kit, на устройстве).
 * Файл → ffmpeg (16 кГц моно float) → whisper → сегменты с временем → перевод по сегментам → .txt / .srt.
 */
class TranscribeActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val reqPick = 71
    private var uri: Uri? = null
    private var baseName = "transcript"

    private lateinit var fileLabel: TextView
    private lateinit var modelSp: Spinner
    private lateinit var srcSp: Spinner
    private lateinit var dstSp: Spinner
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var status: TextView
    private lateinit var bar: SmoothBar
    private lateinit var result: TextView
    private lateinit var resultBox: ScrollView
    private lateinit var saveRow: LinearLayout

    private val running get() = Transcriber.running

    private val models = listOf(
        Triple("ggml-base-q5_1.bin", "tr_model_fast", 57L),
        Triple("ggml-small-q5_1.bin", "tr_model_good", 181L)
    )
    private val srcCodes = listOf("ja", "auto", "en", "ru", "zh", "ko")
    private val srcNames = listOf("日本語", "", "English", "Русский", "中文", "한국어")
    private val dstCodes = listOf("", "ru", "en", "uk", "es", "de", "fr", "zh", "ko", "pt")
    private val dstNames = listOf("", "Русский", "English", "Українська", "Español", "Deutsch", "Français", "中文", "한국어", "Português")

    private fun t(k: String) = Strings.t(k)
    private fun f(k: String, vararg a: Any?) = Strings.f(k, *a)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val th by lazy { AppTheme.of(getSharedPreferences("vad", Context.MODE_PRIVATE)) }
    private val ink get() = th.text
    private val panel get() = if (th.dark) 0x1FFFFFFF else 0x99FFFFFF.toInt()

    private fun btn(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(th.btnText)
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, th.btn).apply { cornerRadius = dp(22).toFloat() }
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ink)
        textSize = 13f
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        setPopupBackgroundDrawable(GradientDrawable().apply { setColor(if (th.dark) th.bg[0] else 0xFFFFFFFF.toInt()); cornerRadius = dp(12).toFloat() })
        background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(14).toFloat() }
        setPadding(dp(8), dp(6), dp(8), dp(6))
        adapter = object : ArrayAdapter<String>(this@TranscribeActivity, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).also { it.setTextColor(ink) }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (super.getDropDownView(position, convertView, parent) as TextView).also { it.setTextColor(ink); it.setBackgroundColor(0) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Strings.init(applicationContext)
        window.decorView.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (th.bg.size >= 2) th.bg else intArrayOf(th.bg[0], th.bg[0]))

        val col = LinearLayout(this).apply {
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        col.addView(TextView(this).apply {
            text = t("tr_title")
            setTextColor(ink)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = t("tr_keep")
            setTextColor(ink)
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(8))
        })

        fileLabel = TextView(this).apply { text = t("tr_nofile"); setTextColor(ink); textSize = 13f }
        col.addView(btn(t("tr_pick")) { pick() })
        col.addView(fileLabel)

        col.addView(label(t("tr_model")))
        modelSp = spinner(models.map { (_, k, mb) -> t(k) + " ~" + mb + " MB" })
        col.addView(modelSp)
        col.addView(label(t("tr_src")))
        srcSp = spinner(srcNames.mapIndexed { i, n -> if (srcCodes[i] == "auto") t("tr_auto") else n })
        col.addView(srcSp)
        col.addView(label(t("tr_dst")))
        dstSp = spinner(dstNames.mapIndexed { i, n -> if (dstCodes[i].isEmpty()) t("tr_none") else n })
        dstSp.setSelection(if (java.util.Locale.getDefault().language == "ru" || Strings.code == "ru" || Strings.code == "rk") 1 else 2)
        col.addView(dstSp)

        startBtn = btn(t("tr_start")) { start() }
        stopBtn = btn(t("tr_stop")) { stop() }.apply { visibility = View.GONE }
        col.addView(startBtn)
        col.addView(stopBtn)

        bar = SmoothBar(this).apply {
            visibility = View.GONE
            setColors(if (th.dark) 0x33FFFFFF else 0x55FFFFFF, th.btn[0], th.accent)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(12) }
        }
        col.addView(bar)
        status = TextView(this).apply { setTextColor(ink); textSize = 13f; setPadding(0, dp(6), 0, dp(6)); minLines = 3 }
        col.addView(status)

        saveRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        saveRow.addView(btn(t("tr_copy")) { copy() })
        saveRow.addView(btn(t("tr_save_txt")) { save("txt") })
        saveRow.addView(btn(t("tr_save_srt")) { save("srt") })
        col.addView(saveRow)

        result = TextView(this).apply {
            setTextColor(ink)
            textSize = 14f
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        // Окно результата фиксированной высоты со своей прокруткой: от новых строк страница не «ползёт»
        resultBox = ScrollView(this).apply {
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(14).toFloat() }
            isVerticalScrollBarEnabled = true
            addView(result)
            setOnTouchListener { v, e ->
                v.parent.requestDisallowInterceptTouchEvent(e.action != android.view.MotionEvent.ACTION_UP && e.action != android.view.MotionEvent.ACTION_CANCEL)
                false
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280)).apply { topMargin = dp(8) }
        }
        col.addView(resultBox)

        setContentView(ScrollView(this).apply { addView(col); isFillViewport = true })
        if (!WhisperLib.available) {
            status.text = t("tr_unsupported")
            startBtn.isEnabled = false
        }
    }

    private val listener = object : Transcriber.Listener {
        override fun onState() { ui.post { showState() } }
        override fun onSegment(seg: Transcriber.Seg) {
            ui.post {
                val atEnd = resultBox.scrollY + resultBox.height >= result.height - dp(40)
                result.append(stamp(seg.t0) + " " + seg.text + "\n")
                if (atEnd) resultBox.post { resultBox.fullScroll(View.FOCUS_DOWN) }
            }
        }
        override fun onDone() {
            ui.post {
                result.text = render(false)
                showState()
            }
        }
    }

    /** Показывает текущее состояние задания (оно живёт в службе, окно можно закрывать и открывать). */
    private fun showState() {
        val r = Transcriber.running
        status.text = Transcriber.status
        if (r) {
            bar.visibility = View.VISIBLE
            if (Transcriber.progress >= 0) { bar.indeterminate = false; bar.progress = Transcriber.progress } else bar.indeterminate = true
        } else bar.visibility = View.GONE
        startBtn.visibility = if (r) View.GONE else View.VISIBLE
        stopBtn.visibility = if (r) View.VISIBLE else View.GONE
        saveRow.visibility = if (!r && Transcriber.snapshot().isNotEmpty()) View.VISIBLE else View.GONE
        result.setTextIsSelectable(!r)
    }

    override fun onStart() {
        super.onStart()
        Transcriber.listener = listener
        Transcriber.opts?.let { baseName = it.baseName; uri = it.uri; fileLabel.text = it.baseName }
        result.text = render(false)
        showState()
    }

    override fun onStop() {
        super.onStop()
        if (Transcriber.listener === listener) Transcriber.listener = null
    }

    // ------------------------------------------------------------ выбор файла

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
        }
        startActivityForResult(i, reqPick)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqPick || resultCode != RESULT_OK) return
        val u = data?.data ?: return
        uri = u
        var name = "audio"
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ix >= 0) name = c.getString(ix) ?: name
                }
            }
        } catch (_: Throwable) {
        }
        baseName = name.substringBeforeLast('.').ifBlank { "transcript" }
        fileLabel.text = name
    }

    // ------------------------------------------------------------ запуск

    private fun start() {
        val u = uri
        if (u == null) {
            Toast.makeText(this, t("tr_nofile"), Toast.LENGTH_SHORT).show()
            return
        }
        if (running) return
        askBatteryThen { launch(u) }
    }

    private fun launch(u: Uri) {
        val model = models[modelSp.selectedItemPosition]
        val src = srcCodes[srcSp.selectedItemPosition]
        val dst = dstCodes[dstSp.selectedItemPosition]
        result.text = ""
        Transcriber.start(this, Transcriber.Opts(u, model.first, src, dst, baseName))
        showState()
    }

    /** Чтобы работа шла при выключенном экране, просим отключить экономию энергии (не больше трёх раз). */
    private fun askBatteryThen(go: () -> Unit) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val prefs = getSharedPreferences("vad", Context.MODE_PRIVATE)
            val asked = prefs.getInt("tr_batt_asked", 0)
            if (pm.isIgnoringBatteryOptimizations(packageName) || asked >= 3) { go(); return }
            prefs.edit().putInt("tr_batt_asked", asked + 1).apply()
            android.app.AlertDialog.Builder(this)
                .setTitle(t("tr_batt_title"))
                .setMessage(t("tr_batt_msg"))
                .setPositiveButton(t("tr_batt_ok")) { _, _ ->
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    } catch (_: Throwable) {
                    }
                    go()
                }
                .setNegativeButton(t("tr_batt_later")) { _, _ -> go() }
                .setOnCancelListener { }
                .show()
        } catch (_: Throwable) {
            go()
        }
    }

    private fun stop() {
        Transcriber.stop()
    }

    // ------------------------------------------------------------ вывод

    private fun stamp(cs: Long): String {
        val s = cs / 100
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("[%d:%02d:%02d]", h, m, sec) else String.format("[%02d:%02d]", m, sec)
    }

    private fun srtTime(cs: Long): String {
        val ms = cs * 10
        return String.format("%02d:%02d:%02d,%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000)
    }

    private fun render(withTimes: Boolean): String {
        val sb = StringBuilder()
        for (s in Transcriber.snapshot()) {
            sb.append(stamp(s.t0)).append(' ').append(s.text).append('\n')
            if (s.tr.isNotEmpty()) sb.append("    ").append(s.tr).append('\n')
        }
        return sb.toString()
    }

    private fun srt(): String {
        val sb = StringBuilder()
        for ((i, s) in Transcriber.snapshot().withIndex()) {
            sb.append(i + 1).append('\n')
            sb.append(srtTime(s.t0)).append(" --> ").append(srtTime(maxOf(s.t1, s.t0 + 50))).append('\n')
            sb.append(s.text).append('\n')
            if (s.tr.isNotEmpty()) sb.append(s.tr).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("transcript", render(false)))
        Toast.makeText(this, t("tr_copied"), Toast.LENGTH_SHORT).show()
    }

    private fun save(ext: String) {
        try {
            val name = "$baseName.$ext"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (ext == "srt") "application/x-subrip" else "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VAD")
            }
            val out = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw RuntimeException("insert failed")
            contentResolver.openOutputStream(out)!!.use { it.write((if (ext == "srt") srt() else render(false)).toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, f("tr_saved", name), Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Toast.makeText(this, f("tr_err", e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }
}
