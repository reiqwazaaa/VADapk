package com.vad.downloader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Читалка. Документы (pdf не входит сюда) превращаются в HTML и показываются в WebView без JavaScript и без сети:
 * html, txt, md, docx, pptx, fb2, epub. Всё, что состоит из картинок (cbz, cbr, cb7, cbt, zip, rar, 7z,
 * папки, отдельные картинки и PDF), показывается постранично с зумом и режимом манги (справа налево).
 */
class Reader(
    private val act: Activity,
    private val prefs: SharedPreferences,
    private val button: (String, () -> Unit) -> Button,
    private val tint: (TextView) -> Unit,
    private val dp: (Int) -> Int,
    private val back: () -> Unit,
    private val onModeChange: () -> Unit,
    private val reqFile: Int,
    private val reqTree: Int
) {
    @Volatile var reading = false
        private set

    private class ReadErr(msg: String) : Exception(msg)
    private class Loaded(val pages: Pages? = null, val body: String? = null, val raw: Boolean = false, val epub: EpubBook? = null)
    private class Theme(val bg: Int, val fg: Int, val link: String)

    private val themes = listOf(
        Theme(0xFFFFFFFF.toInt(), 0xFF111111.toInt(), "#0b57d0"),
        Theme(0xFFF4ECD8.toInt(), 0xFF3B2F1E.toInt(), "#8a4b08"),
        Theme(0xFF121212.toInt(), 0xFFD8D8D8.toInt(), "#8ab4f8")
    )

    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()     // страницы
    private val loader = Executors.newSingleThreadExecutor()   // открытие файлов и чистка кэша
    private val cache = object : LruCache<Int, Bitmap>(
        minOf(96L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4).toInt()
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    // состояние открытого документа
    @Volatile private var epub: EpubBook? = null
    private var pages: Pages? = null
    private var index = 0
    private var epubIdx = 0
    private var docBody = ""
    private var docRaw = false
    private var pendingFrac = 0f
    private var curFrac = 0f
    private var curUri = ""
    private var curName = ""
    private var curTree = false
    private var loading = false
    private var bars = true
    private var gen = 0
    private var kindPages = false

    // вью
    private lateinit var listPane: LinearLayout
    private lateinit var viewer: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var recentBox: LinearLayout
    private lateinit var web: WebView
    private lateinit var comic: ComicView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var pageRow: LinearLayout
    private lateinit var epubRow: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var chapLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var bMinus: Button
    private lateinit var bPlus: Button
    private lateinit var bTheme: Button
    private lateinit var bManga: Button
    private lateinit var bFit: Button

    private val saveRun = Runnable { saveProgress(if (epub != null) epubIdx else 0, curFrac) }

    private fun t(k: String) = Strings.t(k)
    private fun f(k: String, vararg a: Any?) = Strings.f(k, *a)
    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
    private val theme: Int get() = prefs.getInt("rd_theme", 0).coerceIn(0, themes.size - 1)
    private val rtl: Boolean get() = prefs.getBoolean("rd_rtl", false)
    private val fitW: Boolean get() = prefs.getBoolean("rd_fitw", false)

    // ------------------------------------------------------------------ интерфейс

    fun buildView(): View {
        if (::web.isInitialized) {
            closeDoc()
            try { web.destroy() } catch (_: Throwable) {}
            if (reading) {
                reading = false
                fullscreen(false)
            }
        }
        val root = FrameLayout(act)
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(buildList(), FrameLayout.LayoutParams(mp, mp))
        root.addView(buildViewer(), FrameLayout.LayoutParams(mp, mp))
        viewer.visibility = View.GONE
        refreshRecent()
        return root
    }

    private fun buildList(): View {
        listPane = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        listPane.addView(TextView(act).apply {
            text = t("rd_title")
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            tint(this)
        })
        listPane.addView(TextView(act).apply { text = t("rd_hint"); tint(this) })
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val w = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        row.addView(button(t("rd_open")) { pickFile() }, w())
        row.addView(button(t("rd_open_folder")) { pickFolder() }, w())
        listPane.addView(row)
        statusText = TextView(act).apply { tint(this); setPadding(0, dp(6), 0, dp(6)) }
        listPane.addView(statusText)
        listPane.addView(TextView(act).apply {
            text = t("rd_recent")
            setTypeface(typeface, Typeface.BOLD)
            tint(this)
        })
        recentBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        listPane.addView(ScrollView(act).apply {
            addView(recentBox)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        listPane.addView(button(t("back")) { back() })
        return listPane
    }

    private fun buildViewer(): View {
        viewer = FrameLayout(act).apply {
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
        }
        val mp = ViewGroup.LayoutParams.MATCH_PARENT

        web = WebView(act).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            settings.textZoom = prefs.getInt("rd_zoom", 100)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url
                    if (u.scheme == "data") return null
                    val e = epub
                    if (e != null && u.host == "epub.local") {
                        val path = (u.path ?: "").removePrefix("/")
                        val entry = e.zip.getEntry(path)
                        if (entry != null) return try { serve(e, path) } catch (_: Throwable) { blocked() }
                    }
                    return blocked()
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val e = epub
                    val u = request.url
                    if (e != null && u.host == "epub.local") {
                        val idx = e.chapters.indexOf((u.path ?: "").removePrefix("/"))
                        if (idx == epubIdx) return false          // якорь внутри главы
                        if (idx >= 0) loadChapter(idx, 0f)
                    }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    val fr = pendingFrac
                    if (fr > 0f) {
                        pendingFrac = 0f
                        view.postDelayed({ scrollToFrac(fr) }, 350)
                    }
                }
            }
            setOnScrollChangeListener { _, _, y, _, oldY ->
                val range = scrollRange()
                curFrac = if (range > 0) (y / range.toFloat()).coerceIn(0f, 1f) else 0f
                if (bars && Math.abs(y - oldY) > 8) { bars = false; applyBars() }
                ui.removeCallbacks(saveRun)
                ui.postDelayed(saveRun, 700)
            }
            val tap = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (e.y > height * 0.25f && e.y < height * 0.75f) toggleBars()
                    return false
                }
            })
            setOnTouchListener { _, ev -> tap.onTouchEvent(ev); false }
        }
        comic = ComicView(act).apply {
            onTap = { z ->
                when (z) {
                    0 -> toggleBars()
                    -1 -> turn(rtl)
                    else -> turn(!rtl)
                }
            }
            onTurn = { fwd -> turn(fwd) }
        }
        viewer.addView(web, FrameLayout.LayoutParams(mp, mp))
        viewer.addView(comic, FrameLayout.LayoutParams(mp, mp))

        // верхняя панель
        topBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xDD000000.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val w = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        topBar.addView(button(t("back")) { closeViewer() }, w())
        bMinus = button("A−") { changeZoom(-10) }
        bPlus = button("A+") { changeZoom(10) }
        bTheme = button(t("rd_theme")) { changeTheme() }
        bManga = button("") { toggleManga() }
        bFit = button("") { toggleFit() }
        listOf(bMinus, bPlus, bTheme, bManga, bFit).forEach { topBar.addView(it, w()) }
        viewer.addView(topBar, FrameLayout.LayoutParams(mp, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // нижняя панель
        bottomBar = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD000000.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        pageLabel = TextView(act).apply { setTextColor(Color.WHITE); setPadding(0, 0, dp(10), 0) }
        seek = SeekBar(act).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) pageLabel.text = f("rd_page", p + 1, (pages?.count ?: 0))
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    showPage(s.progress, false)
                }
            })
        }
        pageRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pageLabel)
            addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        chapLabel = TextView(act).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        epubRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button("◀") { openChapterRel(-1) }, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(chapLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("▶") { openChapterRel(1) }, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        bottomBar.addView(pageRow)
        bottomBar.addView(epubRow)
        viewer.addView(bottomBar, FrameLayout.LayoutParams(mp, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return viewer
    }

    private fun scrollRange(): Int = (web.contentHeight * act.resources.displayMetrics.density).toInt() - web.height

    private fun scrollToFrac(fr: Float) {
        val r = scrollRange()
        if (r > 0) web.scrollTo(0, (r * fr).toInt())
    }

    private fun toggleBars() {
        bars = !bars
        applyBars()
    }

    private fun applyBars() {
        topBar.visibility = if (bars) View.VISIBLE else View.GONE
        bottomBar.visibility = if (bars && (kindPages || epub != null)) View.VISIBLE else View.GONE
    }

    private fun updateToolLabels() {
        bManga.text = f("rd_manga", if (rtl) t("rd_on") else t("rd_off"))
        bFit.text = if (fitW) t("rd_fit_width") else t("rd_fit_page")
    }

    @Suppress("DEPRECATION")
    private fun fullscreen(on: Boolean) {
        act.window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun showViewer() {
        reading = true
        listPane.visibility = View.GONE
        viewer.visibility = View.VISIBLE
        web.visibility = if (kindPages) View.GONE else View.VISIBLE
        comic.visibility = if (kindPages) View.VISIBLE else View.GONE
        listOf(bMinus, bPlus, bTheme).forEach { it.visibility = if (kindPages) View.GONE else View.VISIBLE }
        listOf(bManga, bFit).forEach { it.visibility = if (kindPages) View.VISIBLE else View.GONE }
        pageRow.visibility = if (kindPages) View.VISIBLE else View.GONE
        epubRow.visibility = if (epub != null) View.VISIBLE else View.GONE
        updateToolLabels()
        bars = true
        applyBars()
        fullscreen(true)
        onModeChange()
    }

    private fun closeViewer() {
        ui.removeCallbacks(saveRun)
        if (!kindPages && curUri.isNotEmpty()) saveProgress(if (epub != null) epubIdx else 0, curFrac)
        reading = false
        viewer.visibility = View.GONE
        listPane.visibility = View.VISIBLE
        fullscreen(false)
        closeDoc()
        refreshRecent()
        onModeChange()
    }

    /** Кнопка «назад» телефона: из просмотра возвращает к списку. */
    fun handleBack(): Boolean {
        if (!reading) return false
        closeViewer()
        return true
    }

    /** Кнопки громкости листают страницы. */
    fun onKey(code: Int): Boolean {
        if (!reading) return false
        if (code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP) return false
        val down = code == KeyEvent.KEYCODE_VOLUME_DOWN
        if (kindPages) turn(down) else if (down) web.pageDown(false) else web.pageUp(false)
        return true
    }

    // ------------------------------------------------------------------ настройки чтения

    private fun changeZoom(d: Int) {
        val z = (prefs.getInt("rd_zoom", 100) + d).coerceIn(50, 300)
        prefs.edit().putInt("rd_zoom", z).apply()
        web.settings.textZoom = z
    }

    private fun changeTheme() {
        prefs.edit().putInt("rd_theme", (theme + 1) % themes.size).apply()
        val fr = curFrac
        if (epub != null) loadChapter(epubIdx, fr) else { pendingFrac = fr; renderDoc() }
    }

    private fun toggleManga() {
        prefs.edit().putBoolean("rd_rtl", !rtl).apply()
        comic.rtl = rtl
        seek.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        updateToolLabels()
    }

    private fun toggleFit() {
        prefs.edit().putBoolean("rd_fitw", !fitW).apply()
        comic.fitWidth = fitW
        comic.refit()
        updateToolLabels()
    }

    // ------------------------------------------------------------------ тема и оформление HTML

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    private fun css(): String {
        val th = themes[theme]
        return "html,body{background:${hex(th.bg)} !important;color:${hex(th.fg)} !important}" +
            "body{margin:0;padding:64px 16px 90px;line-height:1.6;font-family:serif;overflow-wrap:anywhere}" +
            "img,svg,video{max-width:100%;height:auto}" +
            "table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #8888;padding:4px 6px}" +
            "pre,code{white-space:pre-wrap;font-family:monospace}" +
            "pre{background:#8882;padding:8px;border-radius:6px}" +
            "pre.txt{background:none;padding:0;font-family:inherit}" +
            "blockquote{margin:.8em 0;padding-left:12px;border-left:3px solid #8885}" +
            "a,.lnk{color:${th.link} !important}" +
            "h1,h2,h3{line-height:1.25}" +
            ".slide{border:1px solid #8886;border-radius:8px;padding:6px 14px;margin:14px 0}" +
            ".sn{opacity:.6;font-size:.8em}.ta{text-align:right;font-style:italic}.poem{margin:1em 2em}"
    }

    private fun injectCss(html: String, xml: Boolean): String {
        val tag = (if (xml) "" else "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">") +
            "<style type=\"text/css\">" + css() + "</style>"
        val i = html.indexOf("</head>", 0, true)
        return if (i >= 0) html.substring(0, i) + tag + html.substring(i) else if (xml) html else tag + html
    }

    private fun wrap(body: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>" + css() + "</style></head><body>" + body + "</body></html>"

    private fun renderDoc() {
        web.setBackgroundColor(themes[theme].bg)
        val html = if (docRaw) injectCss(docBody, false) else wrap(docBody)
        web.loadDataWithBaseURL("https://doc.local/", html, "text/html", "utf-8", null)
    }

    private fun blocked() = WebResourceResponse(
        "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    private fun serve(e: EpubBook, path: String): WebResourceResponse {
        val lower = path.lowercase()
        val mime = when {
            lower.endsWith(".xhtml") || lower.endsWith(".xml") -> "application/xhtml+xml"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".svg") -> "image/svg+xml"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(lower.substringAfterLast('.', "")) ?: "application/octet-stream"
        }
        val entry = e.zip.getEntry(path)
        if (mime == "application/xhtml+xml" || mime == "text/html") {
            val html = ReaderFormats.decodeHtml(e.zip.getInputStream(entry).use { it.readBytes() })
            val out = injectCss(html, mime != "text/html")
            return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(out.toByteArray(Charsets.UTF_8)))
        }
        return WebResourceResponse(mime, null, e.zip.getInputStream(entry))
    }

    // ------------------------------------------------------------------ EPUB

    private fun loadChapter(i: Int, frac: Float) {
        val e = epub ?: return
        epubIdx = i.coerceIn(0, e.chapters.size - 1)
        pendingFrac = frac
        curFrac = frac
        web.setBackgroundColor(themes[theme].bg)
        web.loadUrl("https://epub.local/" + Uri.encode(e.chapters[epubIdx], "/"))
        chapLabel.text = f("rd_chapter", epubIdx + 1, e.chapters.size)
        saveProgress(epubIdx, frac)
    }

    private fun openChapterRel(d: Int) {
        val e = epub ?: return
        val n = epubIdx + d
        if (n < 0 || n >= e.chapters.size) return
        loadChapter(n, 0f)
    }

    // ------------------------------------------------------------------ страницы

    private fun turn(fwd: Boolean) {
        val p = pages ?: return
        val n = index + if (fwd) 1 else -1
        if (n < 0) { toast(t("rd_first_page")); return }
        if (n >= p.count) { toast(t("rd_last_page")); return }
        if (bars) { bars = false; applyBars() }
        showPage(n, !fwd)
    }

    private fun targetWidth(): Int = act.resources.displayMetrics.widthPixels * 3 / 2

    private fun showPage(i: Int, fromEnd: Boolean) {
        val p = pages ?: return
        if (p.count == 0) return
        val idx = i.coerceIn(0, p.count - 1)
        index = idx
        seek.max = (p.count - 1).coerceAtLeast(0)
        seek.progress = idx
        pageLabel.text = f("rd_page", idx + 1, p.count)
        saveProgress(idx, 0f)
        val hit = cache.get(idx)
        if (hit != null) {
            comic.setPage(hit, fromEnd)
            prefetch(p, idx)
            return
        }
        val my = ++gen
        comic.setLoading()
        val tw = targetWidth()
        exec.execute {
            val bm = try { p.bitmap(idx, tw) } catch (_: Throwable) { null }
            if (bm != null) cache.put(idx, bm)
            act.runOnUiThread {
                if (my == gen && pages === p) {
                    if (bm != null) {
                        comic.setPage(bm, fromEnd)
                        prefetch(p, idx)
                    } else {
                        comic.setPage(null, false)
                        toast(t("rd_err_page"))
                    }
                }
            }
        }
    }

    private fun prefetch(p: Pages, idx: Int) {
        val tw = targetWidth()
        for (n in listOf(idx + 1, idx - 1)) {
            if (n < 0 || n >= p.count || cache.get(n) != null) continue
            exec.execute {
                if (pages === p && cache.get(n) == null) {
                    try { p.bitmap(n, tw)?.let { cache.put(n, it) } } catch (_: Throwable) {}
                }
            }
        }
    }

    // ------------------------------------------------------------------ выбор и открытие

    private fun pickFile() {
        if (loading) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        act.startActivityForResult(i, reqFile)
    }

    private fun pickFolder() {
        if (loading) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        act.startActivityForResult(i, reqTree)
    }

    private fun persist(u: Uri) {
        try {
            act.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
    }

    fun onFilePicked(data: Intent?) {
        if (data == null) return
        val uris = ArrayList<Uri>()
        val cd = data.clipData
        if (cd != null) {
            for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { uris.add(it) }
        } else data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        uris.forEach { persist(it) }
        if (uris.size == 1) {
            open(uris, displayName(uris[0]), false)
        } else {
            // несколько файлов сразу: считаем их страницами (картинки по порядку имён)
            val named = uris.map { it to displayName(it) }
            val imgs = named.filter { ReaderFormats.isImage(it.second) }
                .sortedWith { a, b -> ReaderFormats.natural(a.second, b.second) }
            if (imgs.isEmpty()) open(listOf(uris[0]), named[0].second, false)
            else open(imgs.map { it.first }, imgs[0].second, false, multi = true)
        }
    }

    fun onFolderPicked(data: Intent?) {
        val u = data?.data ?: return
        persist(u)
        val name = try {
            DocumentsContract.getTreeDocumentId(u).substringAfterLast(':').substringAfterLast('/')
        } catch (_: Throwable) { "folder" }
        open(listOf(u), name.ifEmpty { "folder" }, true)
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

    private fun status(s: String) = act.runOnUiThread { statusText.text = s }

    private fun open(uris: List<Uri>, name: String, tree: Boolean, multi: Boolean = false) {
        if (loading) return
        loading = true
        status(t("rd_loading"))
        val key = if (multi) "" else uris[0].toString()
        loader.execute {
            try {
                val l = load(uris, name, tree, multi)
                act.runOnUiThread { applyLoaded(l, key, name, tree) }
            } catch (e: Throwable) {
                val msg = when {
                    e is ReadErr -> e.message ?: "error"
                    e is SecurityException && name.lowercase().endsWith(".pdf") -> t("rd_err_pdf_pw")
                    e is SecurityException -> t("rd_err_access")
                    e.javaClass.simpleName.contains("V5") -> t("rd_err_rar5")
                    else -> f("rd_err_open", e.message ?: e.javaClass.simpleName)
                }
                status(msg)
                act.runOnUiThread { loading = false }
            }
        }
    }

    private fun load(uris: List<Uri>, name: String, tree: Boolean, multi: Boolean): Loaded {
        val dir = File(act.cacheDir, "reader")
        dir.deleteRecursively()
        dir.mkdirs()
        val resolver = act.contentResolver
        if (tree) {
            val list = listTree(uris[0])
            if (list.isEmpty()) throw ReadErr(t("rd_err_empty"))
            return Loaded(pages = UriPages(resolver, list))
        }
        if (multi) return Loaded(pages = UriPages(resolver, uris))

        val lower = name.lowercase()
        val ext = if (lower.endsWith(".fb2.zip")) "zip" else lower.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        val file = File(dir, "cur.$ext")
        val input = resolver.openInputStream(uris[0]) ?: throw ReadErr(f("rd_err_open", name))
        input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }

        return when (val kind = ReaderFormats.detect(file, name)) {
            "pdf" -> Loaded(pages = PdfPages(file))
            "zip" -> pagesOrEmpty(ZipPages(file))
            "rar" -> pagesOrEmpty(RarPages(file))
            "7z" -> pagesOrEmpty(SevenPages(file))
            "tar" -> {
                val files = ReaderFormats.extractTar(file, dir)
                if (files.isEmpty()) throw ReadErr(t("rd_err_empty"))
                Loaded(pages = FilePages(files))
            }
            "img" -> Loaded(pages = FilePages(listOf(file)))
            "epub" -> Loaded(epub = ReaderFormats.openEpub(file))
            "fb2" -> Loaded(body = file.inputStream().use { ReaderFormats.fb2ToHtml(it) })
            "fb2zip" -> Loaded(body = ReaderFormats.fb2FromZip(file))
            "docx" -> Loaded(body = ReaderFormats.docxToHtml(file))
            "pptx" -> Loaded(body = ReaderFormats.pptxToHtml(file))
            "html" -> Loaded(body = ReaderFormats.decodeHtml(file.readBytes()), raw = true)
            "txt" -> Loaded(body = ReaderFormats.txtToHtml(ReaderFormats.decodeText(file.readBytes())))
            "md" -> Loaded(body = ReaderFormats.mdToHtml(ReaderFormats.decodeText(file.readBytes())))
            else -> throw ReadErr(f("rd_err_unsupported", if (kind == "unsupported") lower.substringAfterLast('.', "?") else kind))
        }
    }

    private fun pagesOrEmpty(p: Pages): Loaded {
        if (p.count == 0) {
            p.close()
            throw ReadErr(t("rd_err_empty"))
        }
        return Loaded(pages = p)
    }

    private fun listTree(tree: Uri): List<Uri> {
        val res = ArrayList<Pair<String, Uri>>()
        fun walk(docId: String, prefix: String, depth: Int) {
            val ch = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            act.contentResolver.query(ch, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val nm = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 3) walk(id, "$prefix$nm/", depth + 1)
                    } else if (ReaderFormats.isImage(nm)) {
                        res.add((prefix + nm) to DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return res.sortedWith { a, b -> ReaderFormats.natural(a.first, b.first) }.map { it.second }
    }

    private fun applyLoaded(l: Loaded, key: String, name: String, tree: Boolean) {
        loading = false
        statusText.text = ""
        closeDocViews()
        curUri = key
        curName = name
        curTree = tree
        pages = l.pages
        epub = l.epub
        docBody = l.body ?: ""
        docRaw = l.raw
        kindPages = l.pages != null
        val saved = savedFor(key)
        val si = saved?.optInt("i", 0) ?: 0
        val sf = saved?.optDouble("f", 0.0)?.toFloat() ?: 0f
        index = 0
        showViewer()
        when {
            l.pages != null -> {
                comic.rtl = rtl
                comic.fitWidth = fitW
                seek.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                showPage(si.coerceIn(0, (l.pages.count - 1).coerceAtLeast(0)), false)
            }
            l.epub != null -> {
                web.settings.textZoom = prefs.getInt("rd_zoom", 100)
                loadChapter(si, sf)
            }
            else -> {
                web.settings.textZoom = prefs.getInt("rd_zoom", 100)
                pendingFrac = sf
                curFrac = sf
                renderDoc()
                saveProgress(0, sf)
            }
        }
    }

    private fun closeDocViews() {
        pages?.close()
        pages = null
        try { epub?.zip?.close() } catch (_: Throwable) {}
        epub = null
        cache.evictAll()
        gen++
    }

    private fun closeDoc() {
        ui.removeCallbacks(saveRun)
        closeDocViews()
        if (::comic.isInitialized) comic.setPage(null, false)
        if (::web.isInitialized) web.loadUrl("about:blank")
        val dir = File(act.cacheDir, "reader")
        loader.execute { dir.deleteRecursively() }
    }

    // ------------------------------------------------------------------ недавние

    private fun recents(): JSONArray = try {
        JSONArray(prefs.getString("rd_recent", "[]"))
    } catch (_: Throwable) {
        JSONArray()
    }

    private fun savedFor(key: String): JSONObject? {
        if (key.isEmpty()) return null
        val a = recents()
        for (k in 0 until a.length()) {
            val o = a.optJSONObject(k) ?: continue
            if (o.optString("u") == key) return o
        }
        return null
    }

    private fun saveProgress(i: Int, fr: Float) {
        if (curUri.isEmpty()) return
        val total = pages?.count ?: epub?.chapters?.size ?: 0
        val e = JSONObject()
            .put("u", curUri).put("n", curName).put("t", curTree)
            .put("i", i).put("f", fr.toDouble()).put("c", total)
            .put("k", if (kindPages) "p" else if (epub != null) "e" else "d")
        val out = JSONArray().put(e)
        val old = recents()
        for (k in 0 until old.length()) {
            val o = old.optJSONObject(k) ?: continue
            if (o.optString("u") != curUri && out.length() < 20) out.put(o)
        }
        prefs.edit().putString("rd_recent", out.toString()).apply()
    }

    private fun removeRecent(key: String) {
        val old = recents()
        val out = JSONArray()
        for (k in 0 until old.length()) {
            val o = old.optJSONObject(k) ?: continue
            if (o.optString("u") != key) out.put(o)
        }
        prefs.edit().putString("rd_recent", out.toString()).apply()
    }

    private fun refreshRecent() {
        recentBox.removeAllViews()
        val a = recents()
        if (a.length() == 0) {
            recentBox.addView(TextView(act).apply { text = t("rd_recent_empty"); tint(this) })
            return
        }
        recentBox.addView(TextView(act).apply { text = t("rd_recent_hint"); textSize = 12f; alpha = 0.7f; tint(this) })
        for (k in 0 until a.length()) {
            val o = a.optJSONObject(k) ?: continue
            val u = o.optString("u")
            val n = o.optString("n")
            val tree = o.optBoolean("t")
            val info = when (o.optString("k")) {
                "p" -> f("rd_page", o.optInt("i") + 1, o.optInt("c"))
                "e" -> f("rd_chapter", o.optInt("i") + 1, o.optInt("c"))
                else -> (o.optDouble("f") * 100).toInt().toString() + "%"
            }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setBackgroundColor(0x22888888)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                setOnClickListener { open(listOf(Uri.parse(u)), n, tree) }
                setOnLongClickListener {
                    removeRecent(u)
                    refreshRecent()
                    toast(t("rd_removed"))
                    true
                }
            }
            row.addView(TextView(act).apply { text = n; setTypeface(typeface, Typeface.BOLD); tint(this) })
            row.addView(TextView(act).apply { text = info; textSize = 12f; alpha = 0.7f; tint(this) })
            recentBox.addView(row)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Просмотр страницы: зум щипком, двойной тап, перетаскивание, листание тапами по краям и свайпом
// ---------------------------------------------------------------------------------------------

private class ComicView(ctx: Context) : View(ctx) {
    var bmp: Bitmap? = null
        private set
    var fitWidth = false
    var rtl = false
    var onTap: ((Int) -> Unit)? = null      // -1 левый край, 0 центр, 1 правый край
    var onTurn: ((Boolean) -> Unit)? = null // true - вперёд
    private var loading = false
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 64f
        textAlign = Paint.Align.CENTER
    }
    private val mat = Matrix()

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val det = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDet.isInProgress) return false
            tx -= dx
            ty -= dy
            clamp()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val b = bmp ?: return false
            val s = scale()
            val iw = b.width * s
            val ih = b.height * s
            val atLeft = iw <= width + 1 || tx >= -1f
            val atRight = iw <= width + 1 || tx <= width - iw + 1f
            val atTop = ih <= height + 1 || ty >= -1f
            val atBottom = ih <= height + 1 || ty <= height - ih + 1f
            if (Math.abs(vx) > Math.abs(vy)) {
                if (Math.abs(vx) < 1200f) return false
                if (vx < 0 && atRight) { onTurn?.invoke(!rtl); return true }
                if (vx > 0 && atLeft) { onTurn?.invoke(rtl); return true }
            } else if (ih > height + 1 && Math.abs(vy) > 1500f) {
                if (vy < 0 && atBottom) { onTurn?.invoke(true); return true }
                if (vy > 0 && atTop) { onTurn?.invoke(false); return true }
            }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val z = if (e.x < width * 0.3f) -1 else if (e.x > width * 0.7f) 1 else 0
            onTap?.invoke(z)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) zoomBy(1f / zoom, e.x, e.y) else zoomBy(2.5f, e.x, e.y)
            return true
        }
    })

    private fun base(): Float {
        val b = bmp ?: return 1f
        if (width == 0 || height == 0) return 1f
        val fw = width.toFloat() / b.width
        return if (fitWidth) fw else minOf(fw, height.toFloat() / b.height)
    }

    private fun scale() = base() * zoom

    private fun clamp() {
        val b = bmp ?: return
        val s = scale()
        val iw = b.width * s
        val ih = b.height * s
        tx = if (iw <= width) (width - iw) / 2f else tx.coerceIn(width - iw, 0f)
        ty = if (ih <= height) (height - ih) / 2f else ty.coerceIn(height - ih, 0f)
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        if (bmp == null) return
        val nz = (zoom * factor).coerceIn(1f, 6f)
        val k = nz / zoom
        tx = fx - (fx - tx) * k
        ty = fy - (fy - ty) * k
        zoom = nz
        clamp()
        invalidate()
    }

    fun setLoading() {
        loading = true
        invalidate()
    }

    fun setPage(b: Bitmap?, fromEnd: Boolean) {
        bmp = b
        loading = false
        zoom = 1f
        tx = 0f
        ty = 0f
        if (b != null) {
            val ih = b.height * scale()
            ty = if (fromEnd && ih > height) height - ih else 0f
            clamp()
        }
        invalidate()
    }

    /** После смены режима «по странице / по ширине». */
    fun refit() {
        setPage(bmp, false)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        clamp()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val b = bmp
        if (b == null) {
            if (loading) canvas.drawText("…", width / 2f, height / 2f, dots)
            return
        }
        val s = scale()
        mat.setScale(s, s)
        mat.postTranslate(tx, ty)
        canvas.drawBitmap(b, mat, paint)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDet.onTouchEvent(ev)
        if (!scaleDet.isInProgress) det.onTouchEvent(ev)
        return true
    }
}
