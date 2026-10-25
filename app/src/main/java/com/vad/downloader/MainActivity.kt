package com.vad.downloader

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import android.util.TypedValue
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
import android.widget.FrameLayout
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

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

    // 0 - обычная, 1 - няшная (день), 2 - няшная (ночь)
    private var theme = 0
    private val kawaii: Boolean get() = theme > 0
    private val night: Boolean get() = theme == 2
    private var catTaps = 0
    // 0 - главная, 1 - лог, 2 - настройки
    private var tab = 0
    private var applyTabFn: (() -> Unit)? = null
    private val logLock = Any()
    private val logBuf = StringBuilder()
    private val tsFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var girlView: ImageView? = null
    private val moodHandler = Handler(Looper.getMainLooper())
    private val reqPickCookies = 43
    private lateinit var audioSpinner: Spinner
    private lateinit var historyList: LinearLayout
    private val audioLabels = listOf("MP3 (best)", "MP3 320k", "MP3 192k", "MP3 128k", "M4A (AAC)", "Opus", "FLAC", "WAV")
    private val audioCodes = listOf("mp3:0", "mp3:320K", "mp3:192K", "mp3:128K", "m4a", "opus", "flac", "wav")
    private class DlSpec(val kind: String, val fmt: String, val afmt: String)
    private class Saved(val name: String, val uri: Uri, val mime: String)

    private class Palette(val key: String, val need: Int, val bg: IntArray, val btn: IntArray, val accent: Int)
    private val palettes = listOf(
        Palette("pal_pink", 0, intArrayOf(0xFFFFC1E3.toInt(), 0xFFE0C3FF.toInt(), 0xFFBDE7FF.toInt()), intArrayOf(0xFFFF9ACB.toInt(), 0xFFFFC2E5.toInt()), 0xFFFF5FA2.toInt()),
        Palette("pal_mint", 1, intArrayOf(0xFFC9FFE5.toInt(), 0xFFBDEBFF.toInt(), 0xFFE8FFD0.toInt()), intArrayOf(0xFF5EDDB0.toInt(), 0xFFA8F5D6.toInt()), 0xFF22B58A.toInt()),
        Palette("pal_sunset", 3, intArrayOf(0xFFFFD3A8.toInt(), 0xFFFFB1C1.toInt(), 0xFFE7B6FF.toInt()), intArrayOf(0xFFFF9966.toInt(), 0xFFFFC48A.toInt()), 0xFFFF7043.toInt()),
        Palette("pal_lavender", 5, intArrayOf(0xFFE4D6FF.toInt(), 0xFFCDBDFF.toInt(), 0xFFF7D6FF.toInt()), intArrayOf(0xFFA987F0.toInt(), 0xFFCDB8FF.toInt()), 0xFF8A5CF0.toInt()),
        Palette("pal_lemon", 10, intArrayOf(0xFFFFF3A8.toInt(), 0xFFFFD6A5.toInt(), 0xFFFFB5D8.toInt()), intArrayOf(0xFFFFC93C.toInt(), 0xFFFFE59A.toInt()), 0xFFFF9F1C.toInt())
    )
    private fun wins() = prefs.getInt("wins", 0)
    private val palette: Palette get() {
        val p = palettes[prefs.getInt("palette", 0).coerceIn(0, palettes.size - 1)]
        return if (wins() >= p.need) p else palettes[0]
    }

    private val kawaiiText: Int get() = if (night) 0xFFEBDDFF.toInt() else 0xFF6A2C6B.toInt()
    private val accent: Int get() = if (night) 0xFFB388FF.toInt() else palette.accent
    private val logTextColor: Int get() = if (night) 0xFFD9C9F5.toInt() else 0xFF5A3A78.toInt()
    private val panelColor: Int get() = if (night) 0x33FFFFFF else 0x99FFFFFF.toInt()
    private val bgColors: IntArray
        get() = if (night) intArrayOf(0xFF2B1B4E.toInt(), 0xFF45307A.toInt(), 0xFF1E3A5F.toInt())
        else palette.bg
    private val btnColors: IntArray
        get() = if (night) intArrayOf(0xFF7A4DB8.toInt(), 0xFF9B6FD6.toInt())
        else palette.btn

    @Volatile private var engineReady = false
    @Volatile private var busy = false

    private val prefs by lazy { getSharedPreferences("vad", Context.MODE_PRIVATE) }

    private fun t(key: String) = Strings.t(key)
    private fun f(key: String, vararg a: Any?) = Strings.f(key, *a)

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Strings.init(this)
        try {
            if (logFile().isFile) logBuf.append(logFile().readText().takeLast(200_000))
        } catch (_: Exception) {
        }
        theme = if (prefs.getBoolean("kawaii_unlocked", false))
            prefs.getInt("theme", if (prefs.getBoolean("kawaii_on", false)) 1 else 0)
        else 0
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
            maxLines = 1
            // если текст не помещается - он сам уменьшается (но не меньше 9sp)
            setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP)
            setOnClickListener { onClick() }
            if (kawaii) styleKawaiiButton(this)
        }

    /** Ряд кнопок: кнопки по размеру текста; если ряд не влезает, делит ширину пропорционально. */
    private class FitRow(ctx: Context) : LinearLayout(ctx) {
        init {
            orientation = HORIZONTAL
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val naturals = IntArray(childCount)
            var total = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val lp = c.layoutParams as LayoutParams
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
                c.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                naturals[i] = c.measuredWidth + lp.leftMargin + lp.rightMargin
                total += naturals[i]
            }
            if (avail > 0 && total > avail) {
                for (i in 0 until childCount) {
                    val lp = getChildAt(i).layoutParams as LayoutParams
                    lp.width = 0
                    lp.weight = naturals[i].toFloat()
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** Лёгкое сжатие при нажатии и «пружинка» при отпускании. */
    private fun attachPressEffect(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    private fun circleBackground(): RippleDrawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (kawaii) {
                colors = btnColors
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            } else {
                setColor(0xFF4A4A4A.toInt())
            }
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        return RippleDrawable(ColorStateList.valueOf(0x99FFFFFF.toInt()), bg, mask)
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
        val shape = GradientDrawable(GradientDrawable.Orientation.TL_BR, btnColors).apply {
            cornerRadius = r
            setStroke(dp(2), 0xFFFFFFFF.toInt())
        }
        val mask = GradientDrawable().apply {
            cornerRadius = r
            setColor(0xFFFFFFFF.toInt())
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(0x99FFFFFF.toInt()), shape, mask)
        b.background = InsetDrawable(ripple, dp(4), dp(3), dp(4), dp(3))
        b.setPadding(dp(20), dp(10), dp(20), dp(10))
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = dp(44)
        b.setTextColor(kawaiiText)
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.stateListAnimator = null
        attachPressEffect(b)
    }

    /** Секретная кнопка-кошечка: 3 нажатия открывают няшный режим, дальше переключает темы. */
    private fun onCatTap(v: View) {
        if (prefs.getBoolean("kawaii_unlocked", false)) {
            theme = (theme + 1) % 3
            prefs.edit().putInt("theme", theme).apply()
            buildUi()
            return
        }
        catTaps++
        if (catTaps >= 3) {
            theme = 1
            prefs.edit().putBoolean("kawaii_unlocked", true).putInt("theme", 1).apply()
            Strings.set(this, "rk")
            buildUi()
            toast(t("kawaii_on"))
        } else {
            ObjectAnimator.ofFloat(v, "rotation", 0f, -18f, 18f, -10f, 0f).setDuration(300).start()
        }
    }

    /** Меняет выражение лица девочки; через delayMs возвращает обычное. */
    private fun setMood(mood: String, delayMs: Long = 0) {
        val girl = girlView ?: return
        moodHandler.removeCallbacksAndMessages(null)
        girl.setImageResource(
            when (mood) {
                "happy" -> R.drawable.kawaii_girl_happy
                "sad" -> R.drawable.kawaii_girl_sad
                "wink" -> R.drawable.kawaii_girl_wink
                else -> R.drawable.kawaii_girl
            }
        )
        if (mood != "normal" && delayMs > 0) {
            moodHandler.postDelayed({ girlView?.setImageResource(R.drawable.kawaii_girl) }, delayMs)
        }
    }

    /** Строит интерфейс заново (при первом запуске и при смене языка), сохраняя состояние. */
    private fun buildUi() {
        // --- запоминаем состояние старых вью ---
        val first = !::urlInput.isInitialized
        val oldUrl = if (first) "" else urlInput.text.toString()
        val oldPlaylist = if (first) false else playlistBox.isChecked
        val oldQuality = if (first) 0 else qualitySpinner.selectedItemPosition
        val oldProgress = if (first) 0 else progressBar.progress
        val oldStatus = if (first) null else statusText.text.toString()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            layoutDirection = if (Strings.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            if (kawaii) background = GradientDrawable(GradientDrawable.Orientation.TL_BR, bgColors)
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
        val catButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_cat)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            imageTintList = ColorStateList.valueOf(if (kawaii) kawaiiText else 0xFFFFFFFF.toInt())
            background = circleBackground()
            contentDescription = "cat"
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) }
            setOnClickListener { onCatTap(this) }
            if (kawaii) attachPressEffect(this)
        }
        langRow.addView(catButton)
        val settingsButton = TextView(this).apply {
            text = "\u2699"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(if (kawaii) kawaiiText else 0xFFFFFFFF.toInt())
            background = circleBackground()
            contentDescription = t("settings")
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) }
            setOnClickListener { tab = 2; applyTabFn?.invoke() }
            if (kawaii) attachPressEffect(this)
        }
        langRow.addView(settingsButton)
        headerLeft.addView(langRow)
        header.addView(headerLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (kawaii) {
            val girl = ImageView(this).apply {
                setImageResource(R.drawable.kawaii_girl)
                layoutParams = LinearLayout.LayoutParams(dp(110), dp(121))
                setOnClickListener {
                    toast(t("girl_tap"))
                    setMood("wink", 1500)
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
            girlView = girl
        } else {
            girlView = null
        }
        root.addView(header)

        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // --- ссылка ---
        urlInput = EditText(this).apply {
            hint = t("url_hint")
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            maxLines = 4
            textDirection = View.TEXT_DIRECTION_LTR
            setText(oldUrl)
            if (kawaii) {
                setTextColor(kawaiiText)
                setHintTextColor((0x99 shl 24) or (kawaiiText and 0xFFFFFF))
                backgroundTintList = ColorStateList.valueOf(accent)
            }
        }
        main.addView(urlInput)

        val urlRow = FitRow(this)
        urlRow.addView(button(t("paste")) { pasteFromClipboard() })
        urlRow.addView(button(t("clear")) { urlInput.setText("") })
        main.addView(urlRow)

        playlistBox = CheckBox(this).apply {
            text = t("playlist")
            isChecked = oldPlaylist
            tintText(this)
            if (kawaii) buttonTintList = ColorStateList.valueOf(accent)
        }
        main.addView(playlistBox)

        qualitySpinner = Spinner(this).apply {
            adapter = styledAdapter(listOf("FHD (1080p)", "HD (720p)", t("q_max")))
            setSelection(oldQuality)
        }
        main.addView(TextView(this).apply { text = t("quality_label"); tintText(this) })
        main.addView(qualitySpinner)
        main.addView(TextView(this).apply { text = t("audio_fmt_label"); tintText(this) })
        audioSpinner = Spinner(this).apply {
            adapter = styledAdapter(audioLabels)
            setSelection(prefs.getInt("audio_pos", 0).coerceIn(0, audioLabels.size - 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putInt("audio_pos", pos).apply()
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        main.addView(audioSpinner)

        // --- кнопки действий ---
        val bAuto = button(t("dl_auto")) { startAuto() }
        val bPick = button(t("dl_pick")) { startPickFormat() }
        val bMp3 = button(t("dl_audio")) { startMp3() }
        main.addView(bAuto)
        main.addView(bPick)
        main.addView(bMp3)

        cancelButton = button(t("cancel")) { cancelDownload() }
        main.addView(cancelButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = oldProgress
            if (kawaii) progressTintList = ColorStateList.valueOf(accent)
        }
        main.addView(progressBar)

        statusText = TextView(this).apply {
            text = when {
                oldStatus == null -> t("st_starting")
                busy -> oldStatus
                engineReady -> t("st_ready")
                else -> t("st_starting")
            }
            tintText(this)
        }
        main.addView(statusText)

        val toolsRow = FitRow(this)
        val bUpdate = button(t("update_btn")) { updateYtDlp() }
        val bProxy = button(t("proxy_btn")) { showProxyDialog() }
        val bCookies = button(t("cookies_btn")) { showCookiesDialog() }
        toolsRow.addView(bUpdate)
        toolsRow.addView(bProxy)
        toolsRow.addView(bCookies)
        main.addView(toolsRow)
        main.addView(button(t("history_btn")) { tab = 3; refreshHistory(); applyTabFn?.invoke() })


        actionButtons = listOf(bAuto, bPick, bMp3, bUpdate)
        actionButtons.forEach { it.isEnabled = engineReady && !busy }
        cancelButton.isEnabled = busy

        // --- вкладка «Лог» (текст всегда слева направо) ---
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            setTextIsSelectable(true)
            text = synchronized(logLock) { logBuf.toString() }
            if (kawaii) setTextColor(logTextColor)
        }
        logScroll = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            if (kawaii) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(panelColor)
                }
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        }
        val logButtons = FitRow(this)
        logButtons.addView(button(t("log_copy")) { copyLog() })
        logButtons.addView(button(t("log_share")) { shareLog() })
        logButtons.addView(button(t("log_save")) { saveLog() })
        logButtons.addView(button(t("log_clear")) { clearLog() })
        val logPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logPane.addView(button(t("back")) { tab = 2; applyTabFn?.invoke() })
        logPane.addView(logButtons)
        logPane.addView(logScroll)

        // --- настройки ---
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settings.addView(TextView(this).apply {
            text = t("settings")
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            tintText(this)
        })
        val langRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        langRow2.addView(TextView(this).apply {
            text = t("lang_label") + ": "
            setPadding(0, 0, dp(8), 0)
            tintText(this)
        })
        val langList = Strings.available(prefs.getBoolean("kawaii_unlocked", false))
        langRow2.addView(Spinner(this).apply {
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
        })
        settings.addView(langRow2)
        pathText = TextView(this).apply { tintText(this) }
        settings.addView(pathText)
        val pathRow = FitRow(this)
        pathRow.addView(button(t("pick_folder")) { pickFolder() })
        pathRow.addView(button(t("default_folder")) {
            prefs.edit().remove("tree_uri").apply()
            refreshPath()
        })
        settings.addView(pathRow)
        refreshPath()

        settings.addView(CheckBox(this).apply {
            text = t("set_game")
            isChecked = prefs.getBoolean("game_gate", false)
            tintText(this)
            if (kawaii) buttonTintList = ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("game_gate", on).apply() }
        })
        fun check(key: String, pref: String, def: Boolean) = settings.addView(CheckBox(this).apply {
            text = t(key)
            isChecked = prefs.getBoolean(pref, def)
            tintText(this)
            if (kawaii) buttonTintList = ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(pref, on).apply() }
        })
        check("set_preview", "preview", true)
        check("set_clip", "clip_watch", true)
        if (prefs.getBoolean("kawaii_unlocked", false)) {
            settings.addView(button(t("rewards_btn")) { showRewards() })
        }
        settings.addView(button(t("open_log")) { tab = 1; applyTabFn?.invoke() })
        settings.addView(button(t("back")) { tab = 0; applyTabFn?.invoke() })

        val mainScroll = ScrollView(this).apply { addView(main) }
        val settingsScroll = ScrollView(this).apply { addView(settings) }
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val historyPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyPane.addView(TextView(this).apply {
            text = t("history_btn")
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            tintText(this)
        })
        historyPane.addView(ScrollView(this).apply {
            addView(historyList)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        val histButtons = FitRow(this)
        histButtons.addView(button(t("back")) { tab = 0; applyTabFn?.invoke() })
        histButtons.addView(button(t("hist_clear")) { saveHistory(JSONArray()); refreshHistory() })
        historyPane.addView(histButtons)
        val content = FrameLayout(this)
        val matchBoth = ViewGroup.LayoutParams.MATCH_PARENT
        content.addView(mainScroll, FrameLayout.LayoutParams(matchBoth, matchBoth))
        content.addView(settingsScroll, FrameLayout.LayoutParams(matchBoth, matchBoth))
        content.addView(logPane, FrameLayout.LayoutParams(matchBoth, matchBoth))
        content.addView(historyPane, FrameLayout.LayoutParams(matchBoth, matchBoth))

        applyTabFn = {
            mainScroll.visibility = if (tab == 0) View.VISIBLE else View.GONE
            settingsScroll.visibility = if (tab == 2) View.VISIBLE else View.GONE
            logPane.visibility = if (tab == 1) View.VISIBLE else View.GONE
            historyPane.visibility = if (tab == 3) View.VISIBLE else View.GONE
            if (tab == 1) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        root.addView(content, LinearLayout.LayoutParams(matchBoth, 0, 1f))
        applyTabFn?.invoke()

        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (kawaii) {
            frame.addView(FallingHearts(this, night, dp(1).toFloat()), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(frame)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (tab) {
            1 -> { tab = 2; applyTabFn?.invoke() }
            2, 3 -> { tab = 0; applyTabFn?.invoke() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
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

    private fun logFile() = File(filesDir, "vad.log")

    private fun log(line: String) {
        val entry = tsFmt.format(Date()) + " " + line + "\n"
        synchronized(logLock) {
            logBuf.append(entry)
            if (logBuf.length > 400_000) logBuf.delete(0, logBuf.length - 300_000)
            try {
                logFile().appendText(entry)
                if (logFile().length() > 600_000) logFile().writeText(logBuf.toString())
            } catch (_: Exception) {
            }
        }
        runOnUiThread {
            logText.append(entry)
            if (logText.length() > 150_000) {
                logText.text = synchronized(logLock) { logBuf.takeLast(100_000).toString() }
            }
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Строка с данными об устройстве и настройках - чтобы лог было проще разбирать. */
    private fun logHeader(): String {
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val proxyOn = prefs.getString("proxy", "").orEmpty().isNotEmpty()
        return buildString {
            appendLine("VAD $ver | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()} | lang: ${Strings.code} | theme: $theme")
            appendLine("engineReady: $engineReady | proxy: ${if (proxyOn) "on" else "off"} | cookies: ${cookiesFile().isFile} | folder: ${targetLabel()}")
            appendLine("--------")
        }
    }

    private fun fullLogText(): String = logHeader() + synchronized(logLock) { logBuf.toString() }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VAD log", fullLogText()))
        toast(t("t_copied"))
    }

    private fun shareLog() {
        val text = fullLogText().takeLast(200_000)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "VAD log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, t("log_share")))
    }

    private fun saveLog() {
        try {
            val name = "vad-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/VAD")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert failed")
            contentResolver.openOutputStream(uri)?.use { it.write(fullLogText().toByteArray(Charsets.UTF_8)) }
            toast(t("t_log_saved"))
        } catch (e: Exception) {
            toast(e.message ?: "error")
        }
    }

    private fun clearLog() {
        synchronized(logLock) { logBuf.setLength(0) }
        try {
            logFile().delete()
        } catch (_: Exception) {
        }
        logText.text = ""
        toast(t("t_cleared"))
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
                runOnUiThread { offerResume() }
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

    // ---------------------------------------------------------------- Куки

    private fun cookiesFile() = File(filesDir, "cookies.txt")

    private fun showCookiesDialog() {
        AlertDialog.Builder(this)
            .setTitle(t("cookies_title"))
            .setMessage(t("cookies_msg"))
            .setPositiveButton(t("cookies_pick")) { _, _ ->
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(i, reqPickCookies)
            }
            .setNeutralButton(t("cookies_delete")) { _, _ ->
                cookiesFile().delete()
                toast(t("cookies_deleted"))
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
        if (requestCode == reqPickCookies && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    cookiesFile().outputStream().use { input.copyTo(it) }
                }
                toast(t("cookies_saved"))
            } catch (e: Exception) {
                log(f("log_cookies_fail", e.message))
            }
            return
        }
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

    /** Все ссылки из поля ввода (по одной в строке или через пробел). */
    private fun currentUrls(): List<String>? {
        val text = urlInput.text.toString().trim()
        val found = Regex("https?://\\S+").findAll(text).map { it.value }.toList()
        val list = if (found.isNotEmpty()) found else if (text.isNotEmpty()) listOf(text) else emptyList()
        if (list.isEmpty()) {
            toast(t("enter_url"))
            return null
        }
        return list
    }

    private fun currentUrl(): String? = currentUrls()?.first()

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
        if (cookiesFile().isFile && cookiesFile().length() > 0) {
            req.addOption("--cookies", cookiesFile().absolutePath)
        }
        return req
    }

    private fun startAuto() {
        val urls = currentUrls() ?: return
        val spec = DlSpec("video", qualityFormat(), "")
        previewThen(urls, playlistBox.isChecked) { runDownload(urls, playlistBox.isChecked, spec) }
    }

    private fun startMp3() {
        val urls = currentUrls() ?: return
        val spec = DlSpec("audio", "", audioCodes[audioSpinner.selectedItemPosition.coerceIn(0, audioCodes.size - 1)])
        previewThen(urls, playlistBox.isChecked) { runDownload(urls, playlistBox.isChecked, spec) }
    }

    private fun applySpec(spec: DlSpec, req: YoutubeDLRequest, embed: Boolean) {
        if (spec.kind == "video") {
            req.addOption("-f", spec.fmt)
            req.addOption("--merge-output-format", "mp4")
        } else {
            val codec = spec.afmt.substringBefore(":")
            val q = spec.afmt.substringAfter(":", "")
            req.addOption("-f", "bestaudio/best")
            req.addOption("-x")
            req.addOption("--audio-format", codec)
            if (q.isNotEmpty()) req.addOption("--audio-quality", q)
            if (embed && codec != "wav") {
                req.addOption("--embed-thumbnail")
                req.addOption("--convert-thumbnails", "jpg")
                req.addOption("--embed-metadata")
            }
        }
    }

    // ------------------------------------------------------------ предпросмотр

    private fun previewThen(urls: List<String>, playlist: Boolean, go: () -> Unit) {
        if (!prefs.getBoolean("preview", true) || urls.size != 1 || playlist || !engineReady || busy) {
            go()
            return
        }
        setBusy(true)
        setStatus(t("st_preview"))
        Thread {
            var info: JSONObject? = null
            var bmp: Bitmap? = null
            try {
                val req = baseRequest(urls[0], false)
                req.addOption("-J")
                req.addOption("--no-warnings")
                val parsed = JSONObject(YoutubeDL.getInstance().execute(req).out)
                info = parsed
                val th = parsed.optString("thumbnail", "")
                if (th.startsWith("http")) {
                    bmp = try {
                        val con = URL(th).openConnection() as HttpURLConnection
                        con.connectTimeout = 8000
                        con.readTimeout = 8000
                        con.inputStream.use { BitmapFactory.decodeStream(it) }
                    } catch (_: Throwable) {
                        null
                    }
                }
            } catch (e: Throwable) {
                log(f("log_error", e.message))
            }
            runOnUiThread {
                setBusy(false)
                setStatus(t("st_ready"))
                val i = info
                if (i == null) go() else showPreview(i, bmp, go)
            }
        }.start()
    }

    private fun fmtDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun showPreview(info: JSONObject, bmp: Bitmap?, go: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        if (bmp != null) {
            box.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                maxHeight = dp(200)
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
        }
        box.addView(TextView(this).apply {
            text = info.optString("title", "")
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
        })
        val by = info.optString("uploader", info.optString("channel", ""))
        val dur = info.optLong("duration", 0L)
        val sub = listOf(by, if (dur > 0) fmtDuration(dur) else "").filter { it.isNotEmpty() }.joinToString(" · ")
        if (sub.isNotEmpty()) box.addView(TextView(this).apply { text = sub })
        AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton(t("prev_go")) { _, _ -> go() }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    // ------------------------------------------------------------ буфер обмена

    private var clipDialog: AlertDialog? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !::urlInput.isInitialized) return
        if (!prefs.getBoolean("clip_watch", true) || busy || clipDialog?.isShowing == true) return
        if (urlInput.text.isNotBlank()) return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            val url = Regex("https?://\\S+").find(text)?.value ?: return
            if (url == prefs.getString("last_clip", "")) return
            prefs.edit().putString("last_clip", url).apply()
            clipDialog = AlertDialog.Builder(this)
                .setMessage(t("clip_found") + "\n\n" + url.take(200))
                .setPositiveButton(t("paste")) { _, _ -> urlInput.setText(url) }
                .setNegativeButton(t("cancel"), null)
                .show()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ продолжение прерванной загрузки

    private fun savePending(urls: List<String>, playlist: Boolean, spec: DlSpec) {
        val o = JSONObject()
        o.put("urls", JSONArray(urls))
        o.put("playlist", playlist)
        o.put("kind", spec.kind)
        o.put("fmt", spec.fmt)
        o.put("afmt", spec.afmt)
        prefs.edit().putString("pending", o.toString()).apply()
    }

    private fun offerResume() {
        val raw = prefs.getString("pending", null) ?: return
        if (busy) return
        try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("urls")
            val urls = (0 until arr.length()).map { arr.getString(it) }
            val spec = DlSpec(o.getString("kind"), o.optString("fmt"), o.optString("afmt"))
            val playlist = o.optBoolean("playlist")
            AlertDialog.Builder(this)
                .setMessage(t("resume_q") + "\n\n" + urls.first().take(120) + if (urls.size > 1) "\n(+${urls.size - 1})" else "")
                .setCancelable(false)
                .setPositiveButton(t("resume_yes")) { _, _ -> runDownloadNow(urls, playlist, spec, true) }
                .setNegativeButton(t("resume_no")) { _, _ ->
                    prefs.edit().remove("pending").apply()
                    File(cacheDir, "ytmp").deleteRecursively()
                }
                .show()
        } catch (_: Throwable) {
            prefs.edit().remove("pending").apply()
        }
    }

    // ------------------------------------------------------------ история

    private fun historyFile() = File(filesDir, "history.json")

    private fun loadHistory(): JSONArray = try {
        JSONArray(historyFile().readText())
    } catch (_: Throwable) {
        JSONArray()
    }

    private fun saveHistory(a: JSONArray) {
        try {
            historyFile().writeText(a.toString())
        } catch (_: Throwable) {
        }
    }

    private fun addHistory(items: List<Saved>) {
        val old = loadHistory()
        val out = JSONArray()
        for (it in items.reversed()) {
            out.put(JSONObject().put("name", it.name).put("uri", it.uri.toString()).put("mime", it.mime)
                .put("time", System.currentTimeMillis()))
        }
        for (i in 0 until minOf(old.length(), 199)) out.put(old.get(i))
        saveHistory(out)
    }

    private fun refreshHistory() {
        if (!::historyList.isInitialized) return
        historyList.removeAllViews()
        val a = loadHistory()
        if (a.length() == 0) {
            historyList.addView(TextView(this).apply { text = t("hist_empty"); setPadding(0, dp(12), 0, 0); tintText(this) })
            return
        }
        val df = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            historyList.addView(TextView(this).apply {
                text = o.optString("name") + "\n" + df.format(Date(o.optLong("time")))
                textSize = 14f
                setPadding(dp(8), dp(10), dp(8), dp(10))
                tintText(this)
                setOnClickListener { historyActions(i, o) }
            })
        }
    }

    private fun historyActions(index: Int, o: JSONObject) {
        val uri = Uri.parse(o.optString("uri"))
        val mime = o.optString("mime", "*/*")
        AlertDialog.Builder(this)
            .setTitle(o.optString("name"))
            .setItems(arrayOf(t("hist_open"), t("hist_share"), t("hist_delete"))) { _, which ->
                try {
                    when (which) {
                        0 -> startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        1 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                        else -> {
                            try {
                                if (DocumentsContract.isDocumentUri(this, uri)) DocumentsContract.deleteDocument(contentResolver, uri)
                                else contentResolver.delete(uri, null, null)
                            } catch (_: Throwable) {
                            }
                            val a = loadHistory()
                            val n = JSONArray()
                            for (k in 0 until a.length()) if (k != index) n.put(a.get(k))
                            saveHistory(n)
                            refreshHistory()
                        }
                    }
                } catch (_: Throwable) {
                    toast(t("hist_gone"))
                }
            }
            .show()
    }

    // ------------------------------------------------------------ награды

    private fun showRewards() {
        val w = wins()
        val items = palettes.map { p ->
            val name = t(p.key)
            if (w >= p.need) "\u2713 $name" else "\uD83D\uDD12 $name (${f("rw_locked", p.need)})"
        }
        AlertDialog.Builder(this)
            .setTitle(f("rw_wins", w))
            .setItems(items.toTypedArray()) { _, which ->
                if (w >= palettes[which].need) {
                    prefs.edit().putInt("palette", which).apply()
                    Handler(Looper.getMainLooper()).post { buildUi() }
                } else {
                    toast(f("rw_locked", palettes[which].need))
                }
            }
            .setNegativeButton(t("back"), null)
            .show()
    }

    private fun registerWin(kind: String) {
        val before = wins()
        prefs.edit().putInt("wins", before + 1).putInt("wins_$kind", prefs.getInt("wins_$kind", 0) + 1).apply()
        val fresh = palettes.firstOrNull { it.need in (before + 1)..(before + 1) }
        if (fresh != null && prefs.getBoolean("kawaii_unlocked", false)) toast(f("rw_new", t(fresh.key)))
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
        runDownload(listOf(url), playlist, DlSpec("video", fmt, ""))
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

    private fun runDownload(urls: List<String>, playlist: Boolean, spec: DlSpec) {
        if (!engineReady || busy) return
        if (prefs.getBoolean("game_gate", false)) {
            playGate { runDownloadNow(urls, playlist, spec, false) }
        } else {
            runDownloadNow(urls, playlist, spec, false)
        }
    }

    /** Случайная мини-игра: победа запускает загрузку. */
    private fun playGate(onWin: () -> Unit) {
        val dlg = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        dlg.setCancelable(false)
        val kind = listOf("snake", "maze", "tank", "run").random()
        val onFinish: (Boolean) -> Unit = { win ->
            dlg.dismiss()
            if (win) {
                toast(t("game_win"))
                registerWin(kind)
                onWin()
            } else {
                AlertDialog.Builder(this)
                    .setMessage(t("game_lose"))
                    .setCancelable(false)
                    .setPositiveButton(t("retry")) { _, _ -> playGate(onWin) }
                    .setNegativeButton(t("cancel"), null)
                    .show()
            }
        }
        val game: GameView = when (kind) {
            "snake" -> SnakeGame(this, onFinish)
            "maze" -> MazeGame(this, onFinish)
            "tank" -> TankGame(this, onFinish)
            else -> PlatformerGame(this, onFinish)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(0xFFFFE6F3.toInt())
            setPadding(dp(12), dp(24), dp(12), dp(12))
        }
        box.addView(TextView(this).apply {
            text = t("game_head")
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF4B2E83.toInt())
        })
        box.addView(TextView(this).apply {
            text = t("g_$kind")
            setTextColor(0xFF6A4A9A.toInt())
            setPadding(0, dp(4), 0, dp(8))
        })
        box.addView(game, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(button(t("cancel")) { dlg.dismiss() })
        dlg.setContentView(box)
        dlg.show()
    }

    private fun runDownloadNow(urls: List<String>, playlist: Boolean, spec: DlSpec, resume: Boolean) {
        if (!engineReady || busy) return
        setBusy(true)
        setStatus(t("st_downloading"))
        savePending(urls, playlist, spec)
        DownloadService.start(this)
        Thread {
            val tmp = File(cacheDir, "dl")
            if (!resume) File(cacheDir, "ytmp").deleteRecursively()
            var saved = 0
            var failed = 0
            var stopped = false
            var lastError = ""
            var friendly = ""
            try {
                for ((idx, url) in urls.withIndex()) {
                    tmp.deleteRecursively()
                    tmp.mkdirs()
                    val prefix = if (urls.size > 1) "[${idx + 1}/${urls.size}] " else ""
                    var embed = spec.kind == "audio"
                    while (true) {
                    try {
                        val req = baseRequest(url, playlist)
                        applySpec(spec, req, embed)
                        req.addOption("--newline")
                        val template = if (playlist)
                            "${tmp.absolutePath}/%(playlist_title|PlayList)s/%(playlist_index)s - %(title).120s [%(id)s].%(ext)s"
                        else
                            "${tmp.absolutePath}/%(title).120s [%(id)s].%(ext)s"
                        req.addOption("-o", template)

                        YoutubeDL.getInstance().execute(req, processId) { progress, eta, line ->
                            val info = progressText(eta, line)?.let { prefix + it }
                            runOnUiThread {
                                if (progress >= 0) progressBar.progress = progress.toInt()
                                statusText.text = info ?: (prefix + t("st_downloading") + " ${progress.toInt()}%")
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
                        val n = saveToDownloads(tmp)
                        if (n.isEmpty()) {
                            failed++
                            log(t("log_no_files"))
                        } else {
                            saved += n.size
                            addHistory(n)
                        }
                        break
                    } catch (e: Throwable) {
                        val m = e.message.orEmpty()
                        if (m.contains("interrupted", true) || m.contains("destroyed", true)) {
                            stopped = true
                            break
                        }
                        if (embed && (m.contains("thumbnail", true) || m.contains("mutagen", true) ||
                                m.contains("embed", true) || m.contains("postprocessing", true))
                        ) {
                            embed = false
                            log(t("log_embed_retry"))
                            tmp.deleteRecursively()
                            tmp.mkdirs()
                            continue
                        }
                        failed++
                        lastError = m
                        log(f("log_error", m))
                        val members = m.contains("members-only", true) || m.contains("members only", true) ||
                            m.contains("channel's members", true) || m.contains("Join this channel", true)
                        val priv = m.contains("private video", true) || m.contains("confirm your age", true) ||
                            m.contains("age-restricted", true)
                        if (members) {
                            friendly = t("err_members")
                            log(friendly)
                        } else if (priv) {
                            friendly = t("err_private")
                            log(friendly)
                        } else if (m.contains("sign in", true) || m.contains("Precondition", true)) {
                            log(t("log_hint_signin"))
                        }
                        break
                    }
                    }
                    if (stopped) break
                }
            } finally {
                tmp.deleteRecursively()
                prefs.edit().remove("pending").apply()
            }

            val doneTitle: String
            val doneText: String
            val mood: String
            when {
                stopped -> {
                    doneTitle = t("n_stopped")
                    doneText = t("n_cancelled")
                    mood = "normal"
                    setStatus(t("st_stopped"))
                    log(t("log_stopped"))
                }
                saved > 0 -> {
                    doneTitle = t("n_done_title")
                    doneText = f("n_done_text", saved, targetLabel())
                    mood = "happy"
                    setStatus(f("st_done", saved, targetLabel()))
                    log(f("log_done", targetLabel()))
                }
                else -> {
                    doneTitle = t("n_error")
                    doneText = if (friendly.isNotEmpty()) friendly
                    else lastError.lineSequence().firstOrNull { it.isNotBlank() }?.take(160)
                        ?: t("st_no_files")
                    mood = "sad"
                    setStatus(if (friendly.isNotEmpty()) friendly else t("st_error"))
                }
            }
            DownloadService.stop(this)
            DownloadService.finish(this, doneTitle, doneText)
            setBusy(false)
            runOnUiThread { setMood(mood, 5000) }
        }.start()
    }

    /** Переносит готовые файлы из кэша в выбранную папку или в общую «Download/VAD». */
    private fun saveToDownloads(dir: File): List<Saved> {
        val tree = treeUri()
        if (tree != null) {
            try {
                return saveToTree(dir, tree)
            } catch (e: Exception) {
                log(f("log_saf_fail", e.message))
            }
        }
        val result = ArrayList<Saved>()
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
                result.add(Saved(file.name, uri, mime))
            }
        }
        return result
    }

    /** Сохраняет в папку, выбранную пользователем (Storage Access Framework). */
    private fun saveToTree(dir: File, tree: Uri): List<Saved> {
        val resolver = contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val folders = HashMap<String, Uri>()
        val result = ArrayList<Saved>()
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
            result.add(Saved(file.name, doc, mime))
        }
        return result
    }
}


/** Лёгкая анимация: медленно падающие сердечки (день) или звёздочки (ночь). Касания пропускает. */
private class FallingHearts(ctx: Context, private val night: Boolean, private val density: Float) : View(ctx) {

    private class Particle(
        var x: Float, var y: Float, val size: Float, val speed: Float,
        val phase: Float, val sway: Float, val heart: Boolean, val color: Int
    )

    private val particles = ArrayList<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val heart = Path().apply {
        moveTo(0f, 1f)
        cubicTo(-1.6f, -0.1f, -0.7f, -1.1f, 0f, -0.35f)
        cubicTo(0.7f, -1.1f, 1.6f, -0.1f, 0f, 1f)
        close()
    }
    private val star = Path().apply {
        moveTo(0f, -1f)
        quadTo(0.15f, -0.15f, 1f, 0f)
        quadTo(0.15f, 0.15f, 0f, 1f)
        quadTo(-0.15f, 0.15f, -1f, 0f)
        quadTo(-0.15f, -0.15f, 0f, -1f)
        close()
    }
    private var last = 0L
    private var attached = false

    private fun rand(a: Float, b: Float) = a + Math.random().toFloat() * (b - a)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        particles.clear()
        val hearts = intArrayOf(0xFFFF8FC7.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFB3D9.toInt(), 0xFFFF7FB0.toInt())
        val stars = intArrayOf(0xFFFFF2A8.toInt(), 0xFFFFFFFF.toInt(), 0xFFD9C9FF.toInt())
        repeat(16) { i ->
            val isHeart = if (night) i % 4 == 0 else i % 3 != 0
            val palette = if (isHeart) hearts else stars
            particles.add(
                Particle(
                    rand(0f, w.toFloat()), rand(0f, h.toFloat()), rand(5f, 11f) * density,
                    rand(18f, 42f) * density, rand(0f, 6.28f), rand(10f, 24f) * density,
                    isHeart, palette[i % palette.size]
                )
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        last = 0L
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        for (p in particles) {
            p.y += p.speed * dt
            if (p.y - p.size > height) {
                p.y = -p.size
                p.x = rand(0f, width.toFloat())
            }
            val x = p.x + sin(p.phase + p.y / (40f * density)) * p.sway
            paint.color = p.color
            paint.alpha = if (night) 150 else 130
            canvas.save()
            canvas.translate(x, p.y)
            canvas.scale(p.size, p.size)
            canvas.drawPath(if (p.heart) heart else star, paint)
            canvas.restore()
        }
        if (attached) postInvalidateOnAnimation()
    }
}
