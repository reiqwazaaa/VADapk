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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.webkit.MimeTypeMap
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
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
    private val reqPickConvert = 48
    private val reqPickReader = 49
    private val reqPickReaderTree = 50

    private lateinit var urlInput: EditText
    private lateinit var playlistBox: Switch
    private lateinit var gifBox: Switch
    private lateinit var qualitySpinner: Spinner
    private lateinit var pathText: TextView
    private lateinit var progressBar: SmoothBar
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var actionButtons: List<Button>
    private lateinit var cancelButton: Button

    // 0 - обычная, 1 - няшная (день), 2 - няшная (ночь)
    private var theme = 0
    private val kawaii: Boolean get() = theme > 0
    private val night: Boolean get() = theme == 2
    companion object {
        @Volatile private var inst: MainActivity? = null
        val instance: MainActivity? get() = inst
    }
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
    private class DlSpec(
        val kind: String, val fmt: String, val afmt: String, val subs: String = "",
        val gif: Boolean = false, val gifW: Int = 480, val gifFps: Int = 15,
        val trimFrom: String = "", val trimTo: String = "", val extra: String = ""
    )

    private fun specToJson(s: DlSpec): JSONObject = JSONObject().put("kind", s.kind).put("fmt", s.fmt).put("afmt", s.afmt).put("subs", s.subs)
        .put("gif", s.gif).put("gifW", s.gifW).put("gifFps", s.gifFps).put("trimFrom", s.trimFrom).put("trimTo", s.trimTo).put("extra", s.extra)

    private fun specFromJson(o: JSONObject) = DlSpec(
        o.optString("kind", "video"), o.optString("fmt"), o.optString("afmt"), o.optString("subs"),
        o.optBoolean("gif"), o.optInt("gifW", 480), o.optInt("gifFps", 15), o.optString("trimFrom"), o.optString("trimTo"), o.optString("extra")
    )
    private val subLabelKeys = listOf("sub_none", "sub_embed", "sub_file")
    private val subCodes = listOf("", "embed", "file")
    private lateinit var subsSpinner: Spinner
    private lateinit var lockedButtons: List<Button>

    // очередь загрузок: 0 ждёт, 1 качается, 2 готово, 3 ошибка, 4 отменено
    private class QItem(val id: Int, val urls: List<String>, val playlist: Boolean, val spec: DlSpec) {
        var state = 0
        var title = ""
        var info = ""
        var progress = 0
        var saved = 0
    }
    private val queue = ArrayList<QItem>()
    private var queueSeq = 0
    private var queuePaused = false
    private lateinit var queueList: LinearLayout
    private var queueBtn: Button? = null
    private var lastQueueUi = 0L
    private class Saved(val name: String, val uri: Uri, val mime: String)

    private class Palette(val key: String, val need: Int, val bg: IntArray, val btn: IntArray, val accent: Int, val flag: String = "")
    private val palettes = listOf(
        Palette("pal_pink", 0, intArrayOf(0xFFFFC1E3.toInt(), 0xFFE0C3FF.toInt(), 0xFFBDE7FF.toInt()), intArrayOf(0xFFFF9ACB.toInt(), 0xFFFFC2E5.toInt()), 0xFFFF5FA2.toInt()),
        Palette("pal_mint", 1, intArrayOf(0xFFC9FFE5.toInt(), 0xFFBDEBFF.toInt(), 0xFFE8FFD0.toInt()), intArrayOf(0xFF5EDDB0.toInt(), 0xFFA8F5D6.toInt()), 0xFF22B58A.toInt()),
        Palette("pal_sunset", 3, intArrayOf(0xFFFFD3A8.toInt(), 0xFFFFB1C1.toInt(), 0xFFE7B6FF.toInt()), intArrayOf(0xFFFF9966.toInt(), 0xFFFFC48A.toInt()), 0xFFFF7043.toInt()),
        Palette("pal_lavender", 5, intArrayOf(0xFFE4D6FF.toInt(), 0xFFCDBDFF.toInt(), 0xFFF7D6FF.toInt()), intArrayOf(0xFFA987F0.toInt(), 0xFFCDB8FF.toInt()), 0xFF8A5CF0.toInt()),
        Palette("pal_lemon", 10, intArrayOf(0xFFFFF3A8.toInt(), 0xFFFFD6A5.toInt(), 0xFFFFB5D8.toInt()), intArrayOf(0xFFFFC93C.toInt(), 0xFFFFE59A.toInt()), 0xFFFF9F1C.toInt()),
        // монохромная тема: награда за победу над Ютой-тян в шахматах на сложном уровне
        Palette("pal_mono", 0, intArrayOf(0xFFF4F4F4.toInt(), 0xFFD6D6D6.toInt(), 0xFFFAFAFA.toInt()), intArrayOf(0xFF9E9E9E.toInt(), 0xFFD0D0D0.toInt()), 0xFF212121.toInt(), "mono_theme")
    )
    private fun palUnlocked(p: Palette) = if (p.flag.isNotEmpty()) prefs.getBoolean(p.flag, false) else wins() >= p.need
    private fun wins() = prefs.getInt("wins", 0)
    private val palette: Palette get() {
        val p = palettes[prefs.getInt("palette", 0).coerceIn(0, palettes.size - 1)]
        return if (palUnlocked(p)) p else palettes[0]
    }

    private val kawaiiText: Int get() = if (night) 0xFFEBDDFF.toInt() else if (palette.flag == "mono_theme") 0xFF222222.toInt() else 0xFF6A2C6B.toInt()
    private val accent: Int get() = if (night) 0xFFB388FF.toInt() else palette.accent
    private val logTextColor: Int get() = if (night) 0xFFD9C9F5.toInt() else 0xFF5A3A78.toInt()
    private val panelColor: Int get() = if (night) 0x1FFFFFFF else 0x99FFFFFF.toInt()
    private val bgColors: IntArray
        get() = if (night) intArrayOf(0xFF07050F.toInt(), 0xFF110B22.toInt(), 0xFF060D1A.toInt())
        else palette.bg
    private val btnColors: IntArray
        get() = if (night) intArrayOf(0xFF3A2468.toInt(), 0xFF4D3188.toInt())
        else palette.btn

    @Volatile private var engineReady = false
    @Volatile private var busy = false

    private val prefs by lazy { getSharedPreferences("vad", Context.MODE_PRIVATE) }
    private val reader by lazy {
        Reader(
            this, prefs,
            { text, click -> button(text, click) },
            { tintText(it) },
            { dp(it) },
            { tab = 0; applyTabFn?.invoke() },
            { applyTabFn?.invoke() },
            reqPickReader, reqPickReaderTree
        )
    }
    private val conv by lazy {
        Converter(
            this, prefs,
            { text, click -> button(text, click) },
            { items -> styledAdapter(items) },
            { tintText(it) },
            { dp(it) },
            { busy },
            { setBusy(it) },
            { saveToDownloads(it).size },
            { log(it) },
            { tab = 0; applyTabFn?.invoke() },
            { startNextInQueue() },
            reqPickConvert
        )
    }

    private fun t(key: String) = Strings.t(key)
    private fun f(key: String, vararg a: Any?) = Strings.f(key, *a)

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        inst = this
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

    /** Тумблер вместо флажка. */
    private fun toggle(text: String, checked: Boolean, onChange: ((Boolean) -> Unit)? = null): Switch =
        Switch(this).apply {
            this.text = text
            isChecked = checked
            textSize = 16f
            setPadding(dp(4), dp(10), dp(4), dp(10))
            tintText(this)
            if (kawaii) {
                thumbTintList = ColorStateList.valueOf(accent)
                trackTintList = ColorStateList.valueOf((0x66 shl 24) or (accent and 0xFFFFFF))
            }
            if (onChange != null) setOnCheckedChangeListener { _, on -> onChange(on) }
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
        girl.setImageDrawable(girlDrawable(mood))
        if (mood != "normal" && delayMs > 0) {
            moodHandler.postDelayed({ girlView?.setImageDrawable(girlDrawable("normal")) }, delayMs)
        }
    }

    /** Текущий наряд: 0 худи, 1 майка, 2 пижама (если открыт). */
    private fun outfit(): Int {
        val cur = if (prefs.contains("outfit_cur")) prefs.getInt("outfit_cur", 0)
        else if (prefs.getBoolean("outfit_on", false)) 1 else 0
        return when {
            cur == 1 && prefs.getBoolean("outfit_tank", false) -> 1
            cur == 2 && prefs.getBoolean("outfit_pj", false) -> 2
            cur == 3 && prefs.getBoolean("outfit_queen", false) -> 3
            else -> 0
        }
    }

    /** Картинка девочки с учётом выбранного наряда. */
    private fun girlRes(mood: String): Int = when (outfit()) {
        1 -> when (mood) {
            "happy" -> R.drawable.kawaii_girl_happy_tank
            "sad" -> R.drawable.kawaii_girl_sad_tank
            "wink" -> R.drawable.kawaii_girl_wink_tank
            else -> R.drawable.kawaii_girl_tank
        }
        2 -> when (mood) {
            "happy" -> R.drawable.kawaii_girl_happy_pj
            "sad" -> R.drawable.kawaii_girl_sad_pj
            "wink" -> R.drawable.kawaii_girl_wink_pj
            else -> R.drawable.kawaii_girl_pj
        }
        3 -> when (mood) {
            "happy" -> R.drawable.kawaii_girl_happy_queen
            "sad" -> R.drawable.kawaii_girl_sad_queen
            "wink" -> R.drawable.kawaii_girl_wink_queen
            else -> R.drawable.kawaii_girl_queen
        }
        else -> when (mood) {
            "happy" -> R.drawable.kawaii_girl_happy
            "sad" -> R.drawable.kawaii_girl_sad
            "wink" -> R.drawable.kawaii_girl_wink
            else -> R.drawable.kawaii_girl
        }
    }

    private fun girlDrawable(mood: String): android.graphics.drawable.Drawable? =
        Sprites.drawable(this, Sprites.girlSlot(outfit(), mood)) ?: getDrawable(girlRes(mood))

    private val dev by lazy {
        DevTools(
            this, prefs, { t(it) }, { text, click -> button(text, click) }, { girlDrawable(it) },
            { Triple(bgColors, accent, kawaiiText) },
            { Handler(Looper.getMainLooper()).post { buildUi() } }
        )
    }

    /** Долгое нажатие: следующий открытый наряд по кругу. */
    private fun nextOutfit() {
        val open = listOf(0) + (if (prefs.getBoolean("outfit_tank", false)) listOf(1) else emptyList()) +
            (if (prefs.getBoolean("outfit_pj", false)) listOf(2) else emptyList()) +
            (if (prefs.getBoolean("outfit_queen", false)) listOf(3) else emptyList())
        if (open.size == 1) {
            toast(t("outfit_locked"))
            return
        }
        val next = open[(open.indexOf(outfit()) + 1) % open.size]
        prefs.edit().putInt("outfit_cur", next).apply()
        setMood("normal")
        widgetMood("normal")
        toast(t(when (next) { 1 -> "outfit_on"; 2 -> "outfit_pj"; 3 -> "outfit_queen"; else -> "outfit_off" }))
    }

    /** Меню «⋯»: игры, читалка, конвертер и (если открыт няшный режим) Юта-тян. */
    private fun showMoreMenu() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        var dlg: AlertDialog? = null
        fun item(label: String, action: () -> Unit) {
            box.addView(button(label) { dlg?.dismiss(); action() })
        }
        item(t("more_games")) { showGames() }
        item(t("subs_btn")) { showSubs() }
        item(t("rd_btn")) { tab = 6; applyTabFn?.invoke() }
        item(t("cv_btn")) { tab = 5; applyTabFn?.invoke() }
        item(t("tr_btn")) { startActivity(Intent(this, TranscribeActivity::class.java)) }
        if (prefs.getBoolean("kawaii_unlocked", false)) item(t("date_btn")) { showDate() }
        dlg = AlertDialog.Builder(this)
            .setTitle(t("more_title"))
            .setView(box)
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    // ------------------------------------------------------------ меню «Игры»

    private val freeGames = listOf(
        "chess_full" to "free_chess",
        "snake_inf" to "free_snake",
        "tetris_inf" to "free_tetris",
        "tanks" to "free_tanks"
    )

    private fun showGames() {
        val list = freeGames.filter { GamePacks.freeExists(this, it.first) }
        if (list.isEmpty()) { toast(t("games_none")); return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        var dlg: AlertDialog? = null
        for ((id, key) in list) box.addView(button(t(key)) { dlg?.dismiss(); playFree(id, t(key)) })
        dlg = AlertDialog.Builder(this)
            .setTitle(t("games_title"))
            .setView(box)
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    /** Картинки Юты-тян для игр (шахматы): текущий наряд и все четыре настроения. */
    private fun yutaSprites(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (m in listOf("normal", "happy", "sad", "wink")) {
            try {
                val d = girlDrawable(m) ?: continue
                val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 256
                val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 256
                val k = 256f / maxOf(w, h)
                val bw = (w * k).toInt().coerceAtLeast(1)
                val bh = (h * k).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, bw, bh)
                d.draw(Canvas(bmp))
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                out["yuta_$m"] = "data:image/png;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            } catch (_: Throwable) {
            }
        }
        return out
    }

    /** Полноценная игра из меню «Игры» (не связана с загрузкой): закрывается кнопкой. */
    private fun playFree(id: String, title: String) {
        val dlg = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        var host: GameHost? = null
        dlg.setOnDismissListener { host?.destroy() }
        val errView = TextView(this).apply { setTextColor(0xFFC62828.toInt()); textSize = 12f }
        var failures = 0
        host = GameHost(this, GamePacks.freeHtml(this, id, yutaSprites()), { dlg.dismiss() }) { msg ->
            if (++failures == 1) errView.text = "JS: $msg"
        }
        host?.store = prefs
        host?.storeId = "free_$id"
        if (id == "chess_full") host?.onSave = { key, value ->
            // победа над Ютой-тян: средний уровень открывает костюм, сложный ещё и монохромную тему
            val lv = if (key == "chess_win") value.toIntOrNull() ?: -1 else -1
            if (lv >= 1 && !prefs.getBoolean("outfit_queen", false)) {
                prefs.edit().putBoolean("outfit_queen", true).apply(); toast(t("rw_queen"))
            }
            if (lv >= 2 && !prefs.getBoolean("mono_theme", false)) {
                prefs.edit().putBoolean("mono_theme", true).apply(); toast(t("rw_mono"))
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(0xFFFFE6F3.toInt())
            setPadding(dp(8), dp(16), dp(8), dp(8))
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF4B2E83.toInt())
            setPadding(dp(4), 0, 0, dp(4))
        })
        box.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(errView)
        box.addView(button(t("games_close")) { dlg.dismiss() })
        dlg.setContentView(box)
        dlg.show()
    }

    private fun showDate() {
        DateSim(
            this, { t(it) }, { text, click -> button(text, click) }, { girlDrawable(it) },
            bgColors, accent, kawaiiText,
            prefs.getBoolean("outfit_tank", false),
            { prefs.edit().putBoolean("outfit_tank", true).apply() },
            { prefs.edit().putBoolean("outfit_pj", true).apply() }
        ).show()
    }

    /** Строит интерфейс заново (при первом запуске и при смене языка), сохраняя состояние. */
    private fun buildUi() {
        // --- запоминаем состояние старых вью ---
        val first = !::urlInput.isInitialized
        val oldUrl = if (first) "" else urlInput.text.toString()
        val oldPlaylist = if (first) false else playlistBox.isChecked
        val oldTrimA = if (first) "" else trimFromEt.text.toString()
        val oldTrimB = if (first) "" else trimToEt.text.toString()
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
            val customCat = Sprites.drawable(this@MainActivity, "cat_icon")
            if (customCat != null) setImageDrawable(customCat) else setImageResource(R.drawable.ic_cat)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            imageTintList = if (customCat != null) null
            else ColorStateList.valueOf(if (kawaii) kawaiiText else 0xFFFFFFFF.toInt())
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
            setOnClickListener { tab = if (tab == 2) 0 else 2; applyTabFn?.invoke() }
            if (kawaii) attachPressEffect(this)
        }
        langRow.addView(settingsButton)
        val moreButton = TextView(this).apply {
            text = "\u2022\u2022\u2022"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (kawaii) kawaiiText else 0xFFFFFFFF.toInt())
            background = circleBackground()
            contentDescription = t("more_title")
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) }
            setOnClickListener { showMoreMenu() }
            if (kawaii) attachPressEffect(this)
        }
        langRow.addView(moreButton)
        headerLeft.addView(langRow)
        header.addView(headerLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (kawaii) {
            val girl = ImageView(this).apply {
                setImageDrawable(girlDrawable("normal"))
                layoutParams = LinearLayout.LayoutParams(dp(110), dp(121))
                setOnLongClickListener {
                    nextOutfit()
                    true
                }
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

        playlistBox = toggle(t("playlist"), oldPlaylist)
        main.addView(playlistBox)
        gifBox = toggle(t("gif_video"), prefs.getBoolean("gif_video", false)) {
            prefs.edit().putBoolean("gif_video", it).apply()
        }
        qualitySpinner = Spinner(this).apply {
            adapter = styledAdapter(listOf("FHD (1080p)", "HD (720p)", t("q_max")))
            setSelection(oldQuality)
        }
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
        subsSpinner = Spinner(this).apply {
            adapter = styledAdapter(subLabelKeys.map { t(it) })
            setSelection(prefs.getInt("subs_pos", 0).coerceIn(0, subCodes.size - 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putInt("subs_pos", pos).apply()
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        presetSpinner = Spinner(this).apply {
            adapter = styledAdapter(DlPresets.all.map { t(it.key) })
            setSelection(prefs.getInt("preset_pos", 0).coerceIn(0, DlPresets.all.size - 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putInt("preset_pos", pos).apply()
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        // Качество, формат звука, субтитры и обрезка спрятаны в раскрывающийся блок «Параметры загрузки»
        val params = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val paramsOpen = prefs.getBoolean("dl_params_open", false)
        params.visibility = if (paramsOpen) View.VISIBLE else View.GONE
        lateinit var paramsHdr: Button
        fun paramsLabel(open: Boolean) = t("dl_params") + if (open) "  \u25B4" else "  \u25BE"
        paramsHdr = button(paramsLabel(paramsOpen)) {
            val open = params.visibility != View.VISIBLE
            params.visibility = if (open) View.VISIBLE else View.GONE
            paramsHdr.text = paramsLabel(open)
            prefs.edit().putBoolean("dl_params_open", open).apply()
        }
        main.addView(paramsHdr)
        main.addView(params)
        if (classicUi) {
            params.addView(gifBox)
            params.addView(TextView(this).apply { text = t("quality_label"); tintText(this) })
            params.addView(qualitySpinner)
            params.addView(TextView(this).apply { text = t("audio_fmt_label"); tintText(this) })
            params.addView(audioSpinner)
            params.addView(TextView(this).apply { text = t("sub_label"); tintText(this) })
            params.addView(subsSpinner)
        } else {
            params.addView(TextView(this).apply { text = t("pr_label"); tintText(this) })
            params.addView(presetSpinner)
        }
        // обрезка: по желанию, для видео и звука
        params.addView(TextView(this).apply { text = t("trim_label"); tintText(this) })
        trimFromEt = EditText(this).apply {
            hint = t("trim_from"); setText(oldTrimA); setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT
            if (kawaii) { setTextColor(kawaiiText); setHintTextColor(kawaiiText and 0x00FFFFFF or 0x88000000.toInt()) }
        }
        trimToEt = EditText(this).apply {
            hint = t("trim_to"); setText(oldTrimB); setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT
            if (kawaii) { setTextColor(kawaiiText); setHintTextColor(kawaiiText and 0x00FFFFFF or 0x88000000.toInt()) }
        }
        val trimRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        trimRow.addView(trimFromEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trimRow.addView(trimToEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        params.addView(trimRow)

        // --- кнопки действий ---
        val bAuto = button(if (classicUi) t("dl_auto") else t("dl_go")) { startAuto() }
        val bPick = button(t("dl_pick")) { startPickFormat() }
        val bMp3 = button(t("dl_audio")) { startMp3() }
        main.addView(bAuto)
        main.addView(bPick)
        if (classicUi) main.addView(bMp3)

        cancelButton = button(t("cancel")) { cancelDownload() }
        main.addView(cancelButton)

        progressBar = SmoothBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(10); bottomMargin = dp(4) }
            if (kawaii) setColors(if (night) 0x33FFFFFF else 0x55FFFFFF, btnColors[0], accent)
            else setColors(0x33FFFFFF, 0xFF6FB1F2.toInt(), 0xFF4A90D9.toInt())
            setProgress(oldProgress, false)
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

        // эти три кнопки живут в настройках (создаём здесь, чтобы «Обновить» участвовал в блокировке на время загрузки)
        val bUpdate = button(t("update_btn")) { updateYtDlp() }
        val bProxy = button(t("proxy_btn")) { showProxyDialog() }
        val bCookies = button(t("cookies_btn")) { showCookiesDialog() }
        val histRow = FitRow(this)
        histRow.addView(button(t("history_btn")) { tab = 3; refreshHistory(); applyTabFn?.invoke() })
        queueBtn = button(t("q_btn")) { tab = 4; refreshQueue(); applyTabFn?.invoke() }
        histRow.addView(queueBtn)
        main.addView(histRow)
        updateQueueButton()


        actionButtons = listOf(bAuto, bMp3)          // во время загрузки они ставят в очередь
        lockedButtons = listOf(bPick, bUpdate)       // эти работают только когда ничего не качается
        actionButtons.forEach { it.isEnabled = engineReady }
        lockedButtons.forEach { it.isEnabled = engineReady && !busy }
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
        val langList = Strings.available(prefs.getBoolean("kawaii_unlocked", false))
        val curLang = langList.firstOrNull { it.code == Strings.code }?.name ?: "English"
        settings.addView(button("\uD83C\uDF10  " + t("lang_label") + ": " + curLang + "  \u25BE") {
            AlertDialog.Builder(this)
                .setTitle(t("lang_label"))
                .setItems(langList.map { it.name }.toTypedArray()) { _, pos ->
                    val code = langList[pos].code
                    if (code != Strings.code) {
                        Strings.set(this@MainActivity, code)
                        Handler(Looper.getMainLooper()).post { buildUi() }
                    }
                }
                .setNegativeButton(t("cancel"), null)
                .show()
        })
        pathText = TextView(this).apply { tintText(this); setPadding(dp(4), dp(8), dp(4), dp(2)) }
        settings.addView(pathText)
        val pathRow = FitRow(this)
        pathRow.addView(button("\uD83D\uDCC1  " + t("pick_folder")) { pickFolder() })
        pathRow.addView(button(t("default_folder")) {
            prefs.edit().remove("tree_uri").apply()
            refreshPath()
        })
        settings.addView(pathRow)
        refreshPath()
        settings.addView(buildMemoryCard())

        settings.addView(toggle(t("set_game"), prefs.getBoolean("game_gate", false)) {
            prefs.edit().putBoolean("game_gate", it).apply()
        })
        fun check(key: String, pref: String, def: Boolean) = settings.addView(
            toggle(t(key), prefs.getBoolean(pref, def)) { prefs.edit().putBoolean(pref, it).apply() }
        )
        check("set_clip", "clip_watch", true)
        check("sub_auto", "subs_auto", true)
        settings.addView(toggle(t("classic_ui"), prefs.getBoolean("classic_ui", false)) {
            prefs.edit().putBoolean("classic_ui", it).apply()
            Handler(Looper.getMainLooper()).post { buildUi() }
        })
        settings.addView(TextView(this).apply { text = t("gif_size"); tintText(this); setPadding(dp(4), dp(8), dp(4), 0) })
        val gifRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gifRow.addView(Spinner(this).apply {
            adapter = styledAdapter(gifWidths.map { if (it == 0) t("gif_orig") else "$it px" })
            setSelection(prefs.getInt("gif_w_pos", 2).coerceIn(0, gifWidths.size - 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { prefs.edit().putInt("gif_w_pos", pos).apply() }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        gifRow.addView(Spinner(this).apply {
            adapter = styledAdapter(gifFpsList.map { "$it ${t("gif_fps")}" })
            setSelection(prefs.getInt("gif_fps_pos", 1).coerceIn(0, gifFpsList.size - 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { prefs.edit().putInt("gif_fps_pos", pos).apply() }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        settings.addView(gifRow)
        val toolsRow = FitRow(this)
        toolsRow.addView(bUpdate)
        toolsRow.addView(bProxy)
        toolsRow.addView(bCookies)
        settings.addView(toolsRow)
        if (prefs.getBoolean("kawaii_unlocked", false)) {
            settings.addView(button(t("rewards_btn")) { showRewards() })
        }
        settings.addView(button(t("open_log")) { tab = 1; applyTabFn?.invoke() })
        settings.addView(button(t("dbg_btn")) { dev.open() })
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
        queueList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val queuePane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        queuePane.addView(TextView(this).apply {
            text = t("q_title")
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            tintText(this)
        })
        queuePane.addView(ScrollView(this).apply {
            addView(queueList)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        val queueButtons = FitRow(this)
        queueButtons.addView(button(t("back")) { tab = 0; applyTabFn?.invoke() })
        queueButtons.addView(button(t("q_clear_done")) {
            queue.removeAll { it.state >= 2 }
            refreshQueue(); updateQueueButton()
        })
        queueButtons.addView(button(t("q_resume")) { queuePaused = false; startNextInQueue(); refreshQueue() })
        queuePane.addView(queueButtons)
        val convPane = conv.buildView()
        val readerPane = reader.buildView()
        val content = FrameLayout(this)
        val matchBoth = ViewGroup.LayoutParams.MATCH_PARENT
        content.addView(mainScroll, FrameLayout.LayoutParams(matchBoth, matchBoth))
        content.addView(settingsScroll, FrameLayout.LayoutParams(matchBoth, matchBoth))
        content.addView(logPane, FrameLayout.LayoutParams(matchBoth, matchBoth))
        content.addView(historyPane, FrameLayout.LayoutParams(matchBoth, matchBoth))
        content.addView(queuePane, FrameLayout.LayoutParams(matchBoth, matchBoth))
        content.addView(convPane, FrameLayout.LayoutParams(matchBoth, matchBoth))
        content.addView(readerPane, FrameLayout.LayoutParams(matchBoth, matchBoth))

        applyTabFn = {
            mainScroll.visibility = if (tab == 0) View.VISIBLE else View.GONE
            settingsScroll.visibility = if (tab == 2) View.VISIBLE else View.GONE
            logPane.visibility = if (tab == 1) View.VISIBLE else View.GONE
            historyPane.visibility = if (tab == 3) View.VISIBLE else View.GONE
            queuePane.visibility = if (tab == 4) View.VISIBLE else View.GONE
            convPane.visibility = if (tab == 5) View.VISIBLE else View.GONE
            readerPane.visibility = if (tab == 6) View.VISIBLE else View.GONE
            // во время чтения прячем шапку приложения и поля: читалка занимает весь экран
            val full = tab == 6 && reader.reading
            header.visibility = if (full) View.GONE else View.VISIBLE
            if (full) root.setPadding(0, 0, 0, 0) else root.setPadding(dp(16), dp(24), dp(16), dp(16))
            if (tab == 1) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            if (tab == 2) refreshMemory()
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
            2, 3, 5 -> { tab = 0; applyTabFn?.invoke() }
            6 -> if (!reader.handleBack()) { tab = 0; applyTabFn?.invoke() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (tab == 6 && reader.onKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    /** «Открыть с помощью VAD»: аудио/видео уходят в конвертер, остальное в читалку; гифки и webp спрашиваем. */
    private fun handleViewIntent(i: Intent) {
        val u = i.data ?: return
        val name = try {
            contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Throwable) { null } ?: (u.lastPathSegment ?: "")
        val ext = name.substringAfterLast('.', "").lowercase()
        val type = (i.type ?: "").lowercase()
        val media = type.startsWith("audio/") || type.startsWith("video/") || type == "application/ogg" ||
            ext in setOf("mp3", "flac", "ogg", "opus", "m4a", "aac", "wma", "wav", "mp4", "mkv", "webm", "avi", "mov")
        val ambiguous = ext == "gif" || ext == "webp" || type == "image/gif" || type == "image/webp"
        val toConverter = { tab = 5; applyTabFn?.invoke(); conv.onPicked(i) }
        val toReader = { tab = 6; applyTabFn?.invoke(); reader.onFilePicked(i) }
        when {
            ambiguous -> AlertDialog.Builder(this)
                .setTitle(t("open_choose"))
                .setItems(arrayOf(t("rd_btn"), t("cv_btn"))) { _, w -> if (w == 0) toReader() else toConverter() }
                .show()
            media -> toConverter()
            else -> toReader()
        }
    }

    private fun handleShareIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW && i.data != null && i.data?.scheme != "http" && i.data?.scheme != "https") {
            handleViewIntent(i)
            return
        }
        if (i?.action == VadWidget.ACTION_PASTE) {
            i.action = Intent.ACTION_MAIN
            tab = 0
            applyTabFn?.invoke()
            val u = i.getStringExtra("url")
            if (u != null) pasteUrl(u, i.getBooleanExtra("bg", false)) else pasteAndDownload(5)
            return
        }
        if (i?.action == Intent.ACTION_SEND && i.type == "text/plain") {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val link = Regex("https?://\\S+").find(text)?.value ?: text
            urlInput.setText(link)
        }
    }

    private fun pasteFromClipboard(tries: Int = 0) {
        val text = readClip().first.trim()
        if (text.isNotEmpty()) { urlInput.setText(text); return }
        if (tries < 2) urlInput.postDelayed({ pasteFromClipboard(tries + 1) }, 200) else toast(t("clip_empty"))
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
        actionButtons.forEach { it.isEnabled = engineReady }
        lockedButtons.forEach { it.isEnabled = engineReady && !b }
        cancelButton.isEnabled = b
        if (!b) progressBar.setProgress(0, false)
    }

    @Volatile private var pendingPasteDl = false

    /** Из виджета: берём ссылку из буфера (читается, когда окно в фокусе — поэтому с повторами) и качаем. */
    private fun pasteAndDownload(tries: Int) {
        val link = Regex("https?://\\S+").find(readClip().first)?.value
        if (link == null) {
            if (tries > 0) Handler(Looper.getMainLooper()).postDelayed({ pasteAndDownload(tries - 1) }, 250)
            else toast(t("clip_empty"))
            return
        }
        urlInput.setText(link)
        if (engineReady) startAuto() else pendingPasteDl = true
    }

    /** Ссылка из виджета: подставить и скачать. bg — после старта убрать приложение обратно на задний план. */
    fun pasteUrl(link: String, bg: Boolean) {
        urlInput.setText(link)
        if (engineReady) {
            startAuto()
            if (bg) Handler(Looper.getMainLooper()).postDelayed({ moveTaskToBack(true) }, 600)
        } else {
            pendingPasteDl = true
            pendingPasteBg = bg
        }
    }
    @Volatile private var pendingPasteBg = false

    private fun widgetMood(m: String) {
        try { VadWidget.setMood(this, m) } catch (_: Throwable) {}
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
                runOnUiThread {
                    offerResume(); autoCheckSubs()
                    if (pendingPasteDl) {
                        pendingPasteDl = false; startAuto()
                        if (pendingPasteBg) { pendingPasteBg = false; Handler(Looper.getMainLooper()).postDelayed({ moveTaskToBack(true) }, 600) }
                    }
                }
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

    // ------------------------------------------------------ Подписки

    private fun loadSubs(): MutableList<Sub> = try {
        val a = JSONArray(prefs.getString("subs", "[]"))
        (0 until a.length()).map { Sub.from(a.getJSONObject(it)) }.toMutableList()
    } catch (_: Throwable) { mutableListOf() }

    private fun saveSubs(l: List<Sub>) {
        val a = JSONArray(); l.forEach { a.put(it.toJson()) }
        prefs.edit().putString("subs", a.toString()).apply()
    }

    private fun subDir() = File(filesDir, "subs").apply { mkdirs() }

    /** Ставит в очередь проверку подписки: yt-dlp сам пропускает уже скачанное (архив) и останавливается на первом знакомом видео. */
    private fun checkSub(sub: Sub, all: MutableList<Sub>) {
        val base = specFromJson(sub.spec)
        val ex = ArrayList<String>()
        ex += listOf("--download-archive", File(subDir(), sub.id + ".txt").absolutePath)
        if (sub.mode == "new") {
            ex += listOf("--dateafter", sub.since, "--break-on-reject", "--break-on-existing", "--playlist-end", "50")
        } else if (sub.first) {
            ex += listOf("--playlist-end", sub.n.toString())
        } else {
            ex += listOf("--break-on-existing", "--playlist-end", "30")
        }
        val spec = DlSpec(base.kind, base.fmt, base.afmt, base.subs, base.gif, base.gifW, base.gifFps, "", "", ex.joinToString("\n"))
        enqueue(listOf(sub.url), true, spec)
        sub.first = false
        sub.last = System.currentTimeMillis()
        saveSubs(all)
    }

    private fun checkAllSubs(auto: Boolean) {
        val all = loadSubs()
        if (all.isEmpty()) return
        if (!engineReady) { toast(t("st_starting")); return }
        toast(f("sub_checking", all.size))
        all.forEach { checkSub(it, all) }
        if (auto) prefs.edit().putLong("subs_last_auto", System.currentTimeMillis()).apply()
    }

    private fun autoCheckSubs() {
        if (!prefs.getBoolean("subs_auto", true) || prefs.getString("pending", null) != null) return
        if (System.currentTimeMillis() - prefs.getLong("subs_last_auto", 0L) < 6L * 3600 * 1000) return
        checkAllSubs(true)
    }

    private fun addSubFromField() {
        val url = currentUrl() ?: run { toast(t("sub_need_url")); return }
        val spec = currentSpec(false) ?: return
        val modes = arrayOf(t("sub_mode_new"), f("sub_mode_last", 3), f("sub_mode_last", 10))
        AlertDialog.Builder(this)
            .setTitle(t("sub_mode_title"))
            .setItems(modes) { _, w ->
                val all = loadSubs()
                val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                val name = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").take(70)
                all.add(Sub(System.currentTimeMillis().toString(), url, name, if (w == 0) "new" else "last", if (w == 2) 10 else 3, today, true, 0L, specToJson(spec)))
                saveSubs(all)
                toast(t("sub_added"))
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    private fun showSubs() {
        val all = loadSubs()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        if (all.isEmpty()) box.addView(TextView(this).apply { text = t("subs_empty") })
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        lateinit var dlg: AlertDialog
        for (sub in all) {
            box.addView(TextView(this).apply {
                text = sub.name + "\n" + (if (sub.last == 0L) t("sub_never") else f("sub_last", fmt.format(java.util.Date(sub.last))))
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setBackgroundColor(0x22888888)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(sub.name)
                        .setItems(arrayOf(t("sub_check_now"), t("sub_delete"))) { _, w ->
                            if (w == 0) { if (engineReady) { checkSub(sub, all); toast(f("sub_checking", 1)) } else toast(t("st_starting")) }
                            else { all.remove(sub); saveSubs(all); File(subDir(), sub.id + ".txt").delete(); toast(t("sub_removed")) }
                            dlg.dismiss()
                        }
                        .setNegativeButton(t("cancel"), null)
                        .show()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        dlg = AlertDialog.Builder(this)
            .setTitle(t("subs_btn"))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(t("subs_check_all")) { _, _ -> checkAllSubs(false) }
            .setNeutralButton(t("subs_add")) { _, _ -> addSubFromField() }
            .setNegativeButton(t("subs_close"), null)
            .create()
        dlg.show()
    }

    // ------------------------------------------------------ Память телефона

    private var memBar: StorageBar? = null
    private var memText: TextView? = null

    private fun buildMemoryCard(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(6))
        }
        box.addView(TextView(this).apply {
            text = t("mem_title"); setTypeface(typeface, Typeface.BOLD); tintText(this)
        })
        val bar = StorageBar(this).apply { accent = this@MainActivity.accent }
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        })
        val txt = TextView(this).apply { textSize = 12f; tintText(this); text = t("mem_loading") }
        box.addView(txt)
        memBar = bar
        memText = txt
        return box
    }

    /** Считает память в фоне: всего/занято по StatFs, папка VAD по MediaStore и (если выбрана) по выбранной папке. */
    private fun refreshMemory() {
        val bar = memBar ?: return
        val txt = memText ?: return
        Thread {
            try {
                val st = android.os.StatFs(Environment.getExternalStorageDirectory().path)
                val total = st.totalBytes
                val free = st.availableBytes
                val used = (total - free).coerceAtLeast(0)
                val vad = vadFolderSize()
                runOnUiThread {
                    bar.accent = accent
                    bar.set(total, used, vad)
                    fun sz(b: Long) = android.text.format.Formatter.formatFileSize(this, b)
                    val sb = android.text.SpannableStringBuilder()
                    fun dot(color: Int, label: String, value: String) {
                        val start = sb.length
                        sb.append("\u25CF ")
                        sb.setSpan(android.text.style.ForegroundColorSpan(color), start, sb.length, 0)
                        sb.append("$label: $value\n")
                    }
                    dot(bar.othersColor, t("mem_others"), sz((used - vad).coerceAtLeast(0)))
                    dot(accent, t("mem_vad"), sz(vad))
                    dot(0x99888888.toInt(), t("mem_free"), sz(free) + " / " + sz(total))
                    txt.text = sb.trimEnd()
                }
            } catch (e: Throwable) {
                runOnUiThread { txt.text = t("mem_err") }
            }
        }.start()
    }

    private fun vadFolderSize(): Long {
        var sum = 0L
        try {
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads.SIZE),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("Download/VAD%"), null
            )?.use { c -> while (c.moveToNext()) sum += c.getLong(0) }
        } catch (_: Throwable) {
        }
        try {
            treeUri()?.let { tu ->
                sum += treeSize(tu, DocumentsContract.getTreeDocumentId(tu), 0)
            }
        } catch (_: Throwable) {
        }
        return sum
    }

    private fun treeSize(tree: Uri, docId: String, depth: Int): Long {
        if (depth > 8) return 0
        var sum = 0L
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        contentResolver.query(
            kids,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) sum += treeSize(tree, c.getString(0), depth + 1)
                else sum += c.getLong(2)
            }
        }
        return sum
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
        if (requestCode == reqPickReader && resultCode == RESULT_OK) {
            reader.onFilePicked(data)
            return
        }
        if (requestCode == reqPickReaderTree && resultCode == RESULT_OK) {
            reader.onFolderPicked(data)
            return
        }
        if (requestCode == reqPickConvert && resultCode == RESULT_OK) {
            conv.onPicked(data)
            return
        }
        if (requestCode == 44 && resultCode == RESULT_OK) {
            data?.data?.let { dev.onSpritePicked(it) }
            return
        }
        if (requestCode == 47 && resultCode == RESULT_OK) {
            data?.data?.let { dev.games.onImagePicked(it) }
            return
        }
        if (requestCode == 45 && resultCode == RESULT_OK) {
            data?.data?.let { dev.onCreated(it) }
            return
        }
        if (requestCode == 46 && resultCode == RESULT_OK) {
            data?.data?.let { dev.onOpened(it) }
            return
        }
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

    private val classicUi: Boolean get() = prefs.getBoolean("classic_ui", false)
    private val gifWidths = intArrayOf(240, 320, 480, 640, 0)
    private val gifFpsList = intArrayOf(10, 15, 20)
    private fun gifW() = gifWidths[prefs.getInt("gif_w_pos", 2).coerceIn(0, gifWidths.size - 1)]
    private fun gifFps() = gifFpsList[prefs.getInt("gif_fps_pos", 1).coerceIn(0, gifFpsList.size - 1)]
    private val timeRe = Regex("^\\d+(:\\d{1,2}){0,2}(\\.\\d+)?$")
    private lateinit var trimFromEt: EditText
    private lateinit var trimToEt: EditText
    private lateinit var presetSpinner: Spinner

    private fun toSec(s: String): Double = s.split(":").fold(0.0) { acc, p -> acc * 60 + p.toDouble() }

    /** Читает поля обрезки; при ошибке показывает подсказку и возвращает null. */
    private fun readTrim(): Pair<String, String>? {
        val a = trimFromEt.text.toString().trim()
        val b = trimToEt.text.toString().trim()
        if ((a.isNotEmpty() && !timeRe.matches(a)) || (b.isNotEmpty() && !timeRe.matches(b))) { toast(t("trim_bad")); return null }
        if (a.isNotEmpty() && b.isNotEmpty() && toSec(b) <= toSec(a)) { toast(t("trim_order")); return null }
        return a to b
    }

    /** Что сейчас выбрано на главном экране: пресет или классические списки. */
    private fun currentSpec(withTrim: Boolean = true): DlSpec? {
        val (a, b) = if (withTrim) (readTrim() ?: return null) else ("" to "")
        if (!classicUi) {
            val p = DlPresets.all[presetSpinner.selectedItemPosition.coerceIn(0, DlPresets.all.size - 1)]
            return DlSpec(p.kind, p.fmt, p.afmt, p.subs, p.gif, gifW(), gifFps(), a, b)
        }
        return DlSpec("video", qualityFormat(), "", subCode(), gifBox.isChecked, gifW(), gifFps(), a, b)
    }

    private fun startAuto() {
        val urls = currentUrls() ?: return
        val spec = currentSpec() ?: return
        runDownload(urls, playlistBox.isChecked, spec)
    }

    private fun startMp3() {
        val urls = currentUrls() ?: return
        val (a, b) = readTrim() ?: return
        val spec = DlSpec("audio", "", audioCodes[audioSpinner.selectedItemPosition.coerceIn(0, audioCodes.size - 1)], trimFrom = a, trimTo = b)
        runDownload(urls, playlistBox.isChecked, spec)
    }

    private fun subCode(): String = subCodes[subsSpinner.selectedItemPosition.coerceIn(0, subCodes.size - 1)]

    /** Язык субтитров = язык приложения (для «няшного русского» — русский). */
    private fun subLang(): String = when (val c = Strings.code) {
        "rk" -> "ru"
        "in" -> "id"
        else -> c.substringBefore('-')
    }

    private fun applySpec(spec: DlSpec, req: YoutubeDLRequest, embed: Boolean, url: String) {
        // обрезка: скачиваем только нужный кусок (точные границы по ключевым кадрам)
        if (spec.trimFrom.isNotEmpty() || spec.trimTo.isNotEmpty()) {
            req.addOption("--download-sections", "*" + spec.trimFrom.ifEmpty { "0" } + "-" + spec.trimTo.ifEmpty { "inf" })
            req.addOption("--force-keyframes-at-cuts")
        }
        // дополнительные ключи (подписки: архив и ограничения)
        if (spec.extra.isNotEmpty()) {
            val tk = spec.extra.split("\n").filter { it.isNotEmpty() }
            var i = 0
            while (i < tk.size) {
                if (i + 1 < tk.size && !tk[i + 1].startsWith("--")) { req.addOption(tk[i], tk[i + 1]); i += 2 } else { req.addOption(tk[i]); i++ }
            }
        }
        if (spec.kind == "video") {
            if (spec.gif) {
                // «Скачать как GIF»: берём настоящий gif, а если его нет, качаем лучшее, что есть.
                // В GIF видео превращает уже наш ffmpeg после загрузки (runDownloadNow), потому что
                // --recode-video у yt-dlp тащит в GIF ещё и звук, и ffmpeg на этом падает.
                req.addOption("-f", "best[ext=gif]/" + spec.fmt)
            } else {
                req.addOption("-f", spec.fmt)
            }
            req.addOption("--merge-output-format", "mp4")
            if (embed && spec.subs.isNotEmpty()) {
                req.addOption("--write-subs")
                req.addOption("--write-auto-subs")
                req.addOption("--sub-langs", subLang() + ".*")
                if (spec.subs == "embed") req.addOption("--embed-subs") else req.addOption("--convert-subs", "srt")
            }
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

    // ------------------------------------------------------------ буфер обмена

    private var clipDialog: AlertDialog? = null

    private fun readClip(): Pair<String, Long> {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString().orEmpty() else ""
            val ts = try { cm.primaryClipDescription?.timestamp ?: 0L } catch (_: Throwable) { 0L }
            Pair(text, ts)
        } catch (_: Throwable) {
            Pair("", 0L)
        }
    }

    /** Предложение вставить ссылку: на возврат в приложение и на получение фокуса, с повторами (буфер читается только когда окно в фокусе). */
    private fun checkClip(tries: Int = 0) {
        if (!::urlInput.isInitialized || isFinishing) return
        if (!prefs.getBoolean("clip_watch", true) || busy || clipDialog?.isShowing == true || gateOpen) return
        val (text, ts) = readClip()
        val url = Regex("https?://\\S+").find(text)?.value
        if (url == null) {
            if (tries < 3 && hasWindowFocus()) urlInput.postDelayed({ checkClip(tries + 1) }, 300)
            return
        }
        val key = if (ts != 0L) "ts$ts" else url
        if (key == prefs.getString("last_clip", "")) return
        if (urlInput.text.toString().trim() == url) return
        prefs.edit().putString("last_clip", key).apply()
        clipDialog = AlertDialog.Builder(this)
            .setMessage(t("clip_found") + "\n\n" + url.take(200))
            .setPositiveButton(t("paste")) { _, _ -> urlInput.setText(url) }
            .setNeutralButton(t("paste_dl")) { _, _ ->
                urlInput.setText(url)
                if (engineReady) startAuto() else toast(t("st_starting"))
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::urlInput.isInitialized) urlInput.postDelayed({ checkClip() }, 250)
    }

    override fun onPause() {
        super.onPause()
        try { VadWidget.refresh(this) } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        if (inst === this) inst = null
    }

    override fun onResume() {
        super.onResume()
        if (::urlInput.isInitialized) urlInput.postDelayed({ checkClip() }, 400)
    }

    // ------------------------------------------------------------ продолжение прерванной загрузки

    private fun savePending(urls: List<String>, playlist: Boolean, spec: DlSpec) {
        val o = JSONObject()
        o.put("urls", JSONArray(urls))
        o.put("playlist", playlist)
        o.put("spec", specToJson(spec))
        prefs.edit().putString("pending", o.toString()).apply()
    }

    private fun offerResume() {
        val raw = prefs.getString("pending", null) ?: return
        if (busy) return
        try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("urls")
            val urls = (0 until arr.length()).map { arr.getString(it) }
            val spec = specFromJson(o.optJSONObject("spec") ?: o)
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
            if (palUnlocked(p)) "\u2713 $name" else "\uD83D\uDD12 $name (${if (p.flag.isNotEmpty()) t("rw_locked_mono") else f("rw_locked", p.need)})"
        }
        AlertDialog.Builder(this)
            .setTitle(f("rw_wins", w))
            .setItems(items.toTypedArray()) { _, which ->
                if (palUnlocked(palettes[which])) {
                    prefs.edit().putInt("palette", which).apply()
                    Handler(Looper.getMainLooper()).post { buildUi() }
                } else {
                    toast(if (palettes[which].flag.isNotEmpty()) t("rw_locked_mono") else f("rw_locked", palettes[which].need))
                }
            }
            .setNegativeButton(t("back"), null)
            .show()
    }

    private fun registerWin(kind: String) {
        val before = wins()
        prefs.edit().putInt("wins", before + 1).putInt("wins_$kind", prefs.getInt("wins_$kind", 0) + 1).apply()
        val fresh = palettes.firstOrNull { it.flag.isEmpty() && it.need in (before + 1)..(before + 1) }
        if (fresh != null && prefs.getBoolean("kawaii_unlocked", false)) toast(f("rw_new", t(fresh.key)))
    }

    private class VFormat(val label: String, val id: String, val hasAudio: Boolean)
    private class AFormat(val label: String, val id: String)

    private fun startPickFormat() {
        if (busy) { toast(t("q_pick_busy")); return }
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
                setStatus(ErrorHints.key(e.message.orEmpty())?.let { t(it) } ?: t("st_formats_fail"))
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
        val (ta, tb) = readTrim() ?: return
        runDownload(listOf(url), playlist, DlSpec("video", fmt, "", subCode(), trimFrom = ta, trimTo = tb))
    }

    private fun cancelDownload() {
        try {
            GifTool.cancel()
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

    // Сглаживание: yt-dlp для фрагментных потоков даёт "оценочный" размер (~), который скачет.
    private var smTotal = 0.0
    private var smP = 0.0
    private var smDone = 0.0
    @Volatile private var smoothPercent = 0

    private fun resetProgress() {
        smTotal = 0.0
        smP = 0.0
        smDone = 0.0
    }

    /** -> строка вида "45.3% · 22.6 / 50.0 MB · 2.50 MB/s · 0:12 left" либо null */
    private fun progressText(eta: Long, line: String): String? {
        val m = progressRe.find(line) ?: return null
        val rawP = m.groupValues[1].toDouble()
        val rawTotal = toMb(m.groupValues[2].toDouble(), m.groupValues[3])
        val approx = line.contains("~")
        if (rawP + 30.0 < smP) resetProgress()          // начался новый поток
        smTotal = when {
            smTotal == 0.0 -> rawTotal
            approx -> smTotal * 0.92 + rawTotal * 0.08
            else -> rawTotal
        }
        smP = maxOf(smP, rawP)
        smDone = maxOf(smDone, smTotal * smP / 100.0)
        if (smDone > smTotal) smTotal = smDone
        val p = smP
        val total = smTotal
        val done = smDone
        smoothPercent = p.toInt()
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
        if (!engineReady) return
        if (prefs.getBoolean("game_gate", false)) {
            playGate { enqueue(urls, playlist, spec) }
        } else {
            enqueue(urls, playlist, spec)
        }
    }

    // ------------------------------------------------------------ очередь загрузок

    private fun enqueue(urls: List<String>, playlist: Boolean, spec: DlSpec) {
        val item = QItem(++queueSeq, urls, playlist, spec)
        queue.add(item)
        val waiting = queue.count { it.state == 0 }
        if (busy || queue.any { it.state == 1 }) toast(f("q_added", waiting))
        queuePaused = false
        updateQueueButton(); refreshQueue()
        startNextInQueue()
    }

    private fun startNextInQueue() {
        if (busy || queuePaused || !engineReady) return
        val next = queue.firstOrNull { it.state == 0 } ?: return
        runDownloadNow(next.urls, next.playlist, next.spec, false, next)
    }

    private fun updateQueueButton() {
        val n = queue.count { it.state <= 1 }
        queueBtn?.text = if (n > 0) t("q_btn") + " ($n)" else t("q_btn")
    }

    private fun stateGlyph(st: Int) = when (st) { 0 -> "\u23F3"; 1 -> "\u25B6"; 2 -> "\u2705"; 3 -> "\u274C"; else -> "\u23F8" }
    private fun stateName(st: Int) = t(when (st) { 0 -> "q_st_wait"; 1 -> "q_st_active"; 2 -> "q_st_done"; 3 -> "q_st_err"; else -> "q_st_cancel" })

    private fun specLabel(spec: DlSpec): String {
        val base = if (spec.kind == "audio") t("q_audio") + " " + audioLabels.getOrElse(audioCodes.indexOf(spec.afmt).coerceAtLeast(0)) { "" }
        else t("q_video") + when {
            spec.fmt.contains("1080") -> " 1080p"
            spec.fmt.contains("720") -> " 720p"
            else -> ""
        }
        return base + when (spec.subs) { "embed" -> " \u00B7 CC" ; "file" -> " \u00B7 SRT"; else -> "" }
    }

    /** Экран очереди: карточки с состоянием, прогрессом и кнопками. */
    private fun refreshQueue() {
        if (!::queueList.isInitialized) return
        runOnUiThread {
            queueList.removeAllViews()
            updateQueueButton()
            if (queue.isEmpty()) {
                queueList.addView(TextView(this).apply { text = t("q_empty"); setPadding(0, dp(12), 0, 0); tintText(this) })
                return@runOnUiThread
            }
            if (queuePaused && queue.any { it.state == 0 }) {
                queueList.addView(TextView(this).apply {
                    text = "\u23F8 " + t("q_paused"); setPadding(dp(4), dp(8), dp(4), dp(8)); setTypeface(typeface, Typeface.BOLD); tintText(this)
                })
            }
            for (item in queue) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(if (kawaii) panelColor else 0x14000000)
                        if (item.state == 1) setStroke(dp(2), if (kawaii) accent else 0xFF4A90D9.toInt())
                    }
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(5), 0, dp(5)) }
                }
                val name = item.title.ifEmpty { item.urls.first().removePrefix("https://").removePrefix("http://").removePrefix("www.") }
                card.addView(TextView(this).apply {
                    text = stateGlyph(item.state) + "  " + name.take(90) + if (item.urls.size > 1) "  (+${item.urls.size - 1})" else ""
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 15f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textDirection = View.TEXT_DIRECTION_LTR
                    tintText(this)
                })
                card.addView(TextView(this).apply {
                    text = specLabel(item.spec) + "  \u00B7  " + stateName(item.state)
                    textSize = 12.5f
                    tintText(this)
                    alpha = 0.85f
                })
                if (item.state == 1) {
                    card.addView(SmoothBar(this).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6); bottomMargin = dp(4) }
                        if (kawaii) setColors(if (night) 0x33FFFFFF else 0x55FFFFFF, btnColors[0], accent)
                        else setColors(0x33FFFFFF, 0xFF6FB1F2.toInt(), 0xFF4A90D9.toInt())
                        setProgress(item.progress, false)
                    })
                    if (item.info.isNotEmpty()) card.addView(TextView(this).apply { text = item.info; textSize = 12f; tintText(this); textDirection = View.TEXT_DIRECTION_LTR })
                }
                if (item.state == 3 && item.info.isNotEmpty()) card.addView(TextView(this).apply { text = item.info; textSize = 12f; tintText(this) })
                if (item.state == 0 || item.state == 1) {
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
                    row.addView(button(if (item.state == 1) t("q_cancel") else t("q_remove")) {
                        if (item.state == 1) cancelDownload() else { queue.remove(item); refreshQueue() }
                    })
                    card.addView(row)
                }
                queueList.addView(card)
            }
        }
    }

    private var gateOpen = false

    /** Случайная мини-игра (все игры равновероятны): победа запускает загрузку. */
    private fun playGate(onWin: () -> Unit) {
        val ids = GamePacks.ids(this)
        if (ids.isEmpty()) { onWin(); return }
        val kind = ids.random()
        val dlg = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        dlg.setCancelable(false)
        gateOpen = true
        var host: GameHost? = null
        dlg.setOnDismissListener { gateOpen = false; host?.destroy() }
        val errView = TextView(this).apply { setTextColor(0xFFC62828.toInt()); textSize = 12f }
        var failures = 0
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
        host = GameHost(this, GamePacks.html(this, kind), onFinish) { msg ->
            if (++failures == 1) errView.text = "JS: $msg"
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
            text = GamePacks.hint(this@MainActivity, kind)
            setTextColor(0xFF6A4A9A.toInt())
            setPadding(0, dp(4), 0, dp(8))
        })
        box.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(errView)
        box.addView(button(t("cancel")) { dlg.dismiss() })
        dlg.setContentView(box)
        dlg.show()
    }

    private fun runDownloadNow(urls: List<String>, playlist: Boolean, spec: DlSpec, resume: Boolean, item: QItem? = null) {
        if (!engineReady || busy) return
        val qi = item ?: QItem(++queueSeq, urls, playlist, spec).also { queue.add(it) }
        qi.state = 1
        refreshQueue()
        setBusy(true)
        setStatus(t("st_downloading"))
        try { VadWidget.downloadStart(this, queue.count { it.state == 0 }) } catch (_: Throwable) {}
        savePending(urls, playlist, spec)
        DownloadService.start(this)
        Thread {
            GifTool.reset()
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
                    resetProgress()
                    val prefix = if (urls.size > 1) "[${idx + 1}/${urls.size}] " else ""
                    var embed = spec.kind == "audio" || spec.subs.isNotEmpty()
                    while (true) {
                    try {
                        // Пост в Твиттере: фото (оригинал) и гифки (настоящий GIF) забираем сами, видео остаётся за yt-dlp
                        var skipYtdlp = false
                        if (spec.kind == "video") {
                            val proxy = prefs.getString("proxy", "").orEmpty()
                            val post = TweetMedia.fetch(url, proxy)
                            if (post != null && post.items.isNotEmpty()) {
                                setStatus(prefix + t("st_tweet"))
                                log(f("log_tweet_media", post.items.count { it.type == "photo" }, post.items.count { it.type == "gif" }, post.items.count { it.type == "video" }))
                                if (qi.title.isEmpty()) qi.title = "@" + post.user + " \u00B7 " + post.id
                                TweetMedia.saveMedia(this, post, tmp, proxy) { log(it) }
                                skipYtdlp = post.items.none { it.type == "video" }
                            }
                        }
                        if (!skipYtdlp) {
                            val req = baseRequest(url, playlist)
                            applySpec(spec, req, embed, url)
                            req.addOption("--newline")
                            val template = if (playlist)
                                "${tmp.absolutePath}/%(playlist_title|PlayList)s/%(playlist_index)s - %(title).120s [%(id)s].%(ext)s"
                            else
                                "${tmp.absolutePath}/%(title).120s [%(id)s].%(ext)s"
                            req.addOption("-o", template)

                            YoutubeDL.getInstance().execute(req, processId) { progress, eta, line ->
                                if (line.contains("Destination:")) {
                                    resetProgress()
                                    if (qi.title.isEmpty()) {
                                        qi.title = line.substringAfter("Destination:").trim().substringAfterLast('/')
                                            .substringBeforeLast('.').replace(Regex("\\s*\\[[^\\]]{6,}\\]$"), "")
                                    }
                                }
                                val info = progressText(eta, line)?.let { prefix + it }
                                runOnUiThread {
                                    if (info != null) { qi.info = info; qi.progress = smoothPercent }
                                    if (tab == 4 && System.currentTimeMillis() - lastQueueUi > 500) { lastQueueUi = System.currentTimeMillis(); refreshQueue() }
                                    if (info != null) progressBar.progress = smoothPercent
                                    else if (progress >= 0 && smoothPercent == 0) progressBar.progress = progress.toInt()
                                    statusText.text = info ?: (prefix + t("st_downloading") + " ${progress.toInt()}%")
                                }
                                if (info != null) {
                                    try { VadWidget.downloadProgress(this, qi.title, smoothPercent, info, queue.count { it.state == 0 }) } catch (_: Throwable) {}
                                    val now = System.currentTimeMillis()
                                    if (now - lastNotifyAt > 700) {
                                        lastNotifyAt = now
                                        DownloadService.update(this, t("n_title"), info, progress.toInt())
                                    }
                                }
                                if (!line.startsWith("[download]") || !line.contains("%")) log(line)
                            }
                        }
                        // «Скачать как GIF»: всё, что скачалось видео, переделываем в GIF; готовые .gif не трогаем никогда
                        if (spec.kind == "video" && spec.gif) {
                            setStatus(prefix + t("st_gif"))
                            GifTool.convertAll(this, tmp, spec.gifW, spec.gifFps) { name, err -> log(f("log_gif_fail", name, err)) }
                        }

                        setStatus(f("st_saving", targetLabel()))
                        val n = saveToDownloads(tmp)
                        if (n.isEmpty()) {
                            failed++
                            log(t("log_no_files"))
                        } else {
                            saved += n.size
                            val media = n.filter { it.name.substringAfterLast('.').lowercase() !in listOf("srt", "vtt", "ass", "lrc") }
                            if (media.isNotEmpty()) addHistory(media)
                        }
                        break
                    } catch (e: Throwable) {
                        val m = e.message.orEmpty()
                        if (m.contains("interrupted", true) || m.contains("destroyed", true)) {
                            stopped = true
                            break
                        }
                        if (embed && (m.contains("thumbnail", true) || m.contains("mutagen", true) ||
                                m.contains("embed", true) || m.contains("postprocessing", true) || m.contains("subtitle", true))
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
                        val hk = ErrorHints.key(m)
                        if (hk != null) {
                            friendly = t(hk)
                            log(friendly)
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
            qi.saved = saved
            qi.state = when { stopped -> 4; saved > 0 -> 2; else -> 3 }
            qi.progress = if (saved > 0) 100 else qi.progress
            qi.info = if (qi.state == 3) doneText else ""
            DownloadService.stop(this)
            DownloadService.finish(this, doneTitle, doneText)
            setBusy(false)
            try { VadWidget.downloadEnd(this, if (stopped) "normal" else mood) } catch (_: Throwable) {}
            runOnUiThread {
                setMood(mood, 5000)
                if (stopped && queue.any { it.state == 0 }) queuePaused = true
                refreshQueue()
                if (!stopped) startNextInQueue()
            }
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
