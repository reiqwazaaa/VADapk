package com.vad.downloader

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Свои картинки (спрайты) вместо встроенных: хранятся в filesDir/sprites/<слот>.png */
object Sprites {
    @Volatile var version = 0
        private set
    private var known: Set<String>? = null
    private val cache = HashMap<String, Pair<Int, Bitmap>>()

    val rooms = listOf("room_her", "room_home")
    val backgrounds = listOf("bg_street", "bg_park", "bg_cafe", "bg_aqua", "bg_fun", "bg_evening")

    fun girlSlot(outfit: Int, mood: String): String =
        "girl_" + "htp"[outfit.coerceIn(0, 2)] + "_" + (if (mood == "happy" || mood == "sad" || mood == "wink") mood else "normal")

    val allSlots: List<String> = (0..2).flatMap { o -> listOf("normal", "happy", "sad", "wink").map { girlSlot(o, it) } } + rooms + backgrounds + listOf("cat_icon")

    private fun dir(ctx: Context) = File(ctx.filesDir, "sprites").apply { mkdirs() }

    private fun known(ctx: Context): Set<String> =
        known ?: (dir(ctx).listFiles()?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()).also { known = it }

    fun has(ctx: Context, slot: String) = slot in known(ctx)

    fun bitmap(ctx: Context, slot: String): Bitmap? {
        if (slot !in known(ctx)) return null
        val c = cache[slot]
        if (c != null && c.first == version) return c.second
        val b = try { BitmapFactory.decodeFile(File(dir(ctx), "$slot.png").absolutePath) } catch (_: Throwable) { null } ?: return null
        cache[slot] = version to b
        return b
    }

    fun drawable(ctx: Context, slot: String): Drawable? = bitmap(ctx, slot)?.let { BitmapDrawable(ctx.resources, it) }

    fun set(ctx: Context, slot: String, uri: Uri): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (bmp == null) false else {
            File(dir(ctx), "$slot.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            known = null
            version++
            true
        }
    } catch (_: Throwable) {
        false
    }

    fun fileOf(ctx: Context, slot: String) = File(dir(ctx), "$slot.png")

    fun setBytes(ctx: Context, slot: String, data: ByteArray): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 4096) false
        else {
            File(dir(ctx), "$slot.png").writeBytes(data)
            known = null
            version++
            true
        }
    } catch (_: Throwable) {
        false
    }

    fun reset(ctx: Context, slot: String) {
        File(dir(ctx), "$slot.png").delete()
        known = null
        version++
    }

    fun resetAll(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        known = null
        version++
    }
}

/** Проигрыватель собственных сцен, написанных в простом текстовом формате. */
class ScriptPlayer(
    private val ctx: Context,
    private val t: (String) -> String,
    private val button: (String, () -> Unit) -> Button,
    private val girl: (String) -> Drawable?,
    private val bg: IntArray,
    private val accent: Int,
    private val textColor: Int,
    script: String
) {
    class SChoice(val label: String, val delta: Int, val mood: String, val reply: String, val goto: Int?)
    class SNode(val text: String, val bg: String?, val mood: String, val end: String?, val choices: List<SChoice>)

    companion object {
        fun parse(src: String): List<SNode> {
            val nodes = ArrayList<SNode>()
            var text = StringBuilder()
            var bg: String? = null
            var mood = "normal"
            var end: String? = null
            var ch = ArrayList<SChoice>()
            fun flush() {
                if (text.isNotBlank() || ch.isNotEmpty() || end != null) nodes.add(SNode(text.toString(), bg, mood, end, ch))
                text = StringBuilder(); mood = "normal"; end = null; ch = ArrayList()
            }
            for (raw in src.lines()) {
                val l = raw.trim()
                when {
                    l == "---" -> flush()
                    l.startsWith("#") -> {}
                    l.startsWith("@bg ") -> bg = l.substring(4).trim()
                    l.startsWith("@mood ") -> mood = l.substring(6).trim()
                    l.startsWith("@end") -> end = l.removePrefix("@end").trim().ifEmpty { "win" }
                    l.startsWith("?") -> {
                        val p = l.drop(1).split("|").map { it.trim() }
                        ch.add(SChoice(
                            p[0], p.getOrNull(1)?.replace("+", "")?.toIntOrNull() ?: 0,
                            p.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: "normal",
                            p.getOrNull(3).orEmpty(), p.getOrNull(4)?.toIntOrNull()
                        ))
                    }
                    l.isNotEmpty() -> { if (text.isNotEmpty()) text.append('\n'); text.append(l) }
                }
            }
            flush()
            return nodes
        }
    }

    private val nodes = parse(script)
    private val max = 15
    private var doki = 5
    private var lastBg = "street"
    private lateinit var dlg: Dialog
    private lateinit var scene: SceneView
    private lateinit var girlView: ImageView
    private lateinit var bubble: TextView
    private lateinit var box: LinearLayout
    private lateinit var meter: ProgressBar

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show() {
        if (nodes.isEmpty()) {
            Toast.makeText(ctx, t("dev_empty"), Toast.LENGTH_SHORT).show()
            return
        }
        dlg = Dialog(ctx, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dlg.setCancelable(false)
        scene = SceneView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(16))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val frame = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, bg)
            addView(scene, FrameLayout.LayoutParams(-1, -1))
            addView(root, FrameLayout.LayoutParams(-1, -1))
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xB3FFFFFF.toInt()) }
        }
        top.addView(TextView(ctx).apply {
            text = "♥ " + t("doki_label")
            setTextColor(textColor)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, dp(10), 0)
        })
        meter = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            this.max = this@ScriptPlayer.max * 10
            progress = doki * 10
            progressTintList = ColorStateList.valueOf(accent)
        }
        top.addView(meter, LinearLayout.LayoutParams(0, dp(14), 1f))
        top.addView(TextView(ctx).apply {
            text = "  ✕"
            textSize = 20f
            setTextColor(textColor)
            setOnClickListener { dlg.dismiss() }
        })
        root.addView(top)
        girlView = ImageView(ctx)
        root.addView(girlView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))
        ObjectAnimator.ofFloat(girlView, "translationY", 0f, -dp(6).toFloat()).apply {
            duration = 1100; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE; start()
        }
        bubble = TextView(ctx).apply {
            textSize = 16f
            setTextColor(0xFF4B2E83.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minHeight = dp(120)
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xE6FFFFFF.toInt()) }
        }
        root.addView(bubble, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(ctx).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        dlg.setContentView(frame)
        dlg.show()
        showNode(0)
    }

    private fun mood(m: String) {
        girlView.setImageDrawable(girl(m))
    }

    private fun close() = box.addView(button(t("date_close")) { dlg.dismiss() })

    private fun next(i: Int) {
        if (i < 0 || i >= nodes.size) {
            mood("happy")
            bubble.text = t("dev_scene_end")
            box.removeAllViews()
            close()
        } else showNode(i)
    }

    private fun showNode(i: Int) {
        val n = nodes[i]
        n.bg?.let { lastBg = it }
        scene.scene = lastBg
        mood(n.mood)
        bubble.text = n.text
        box.removeAllViews()
        when {
            n.choices.isNotEmpty() -> for (c in n.choices) box.addView(button(c.label) { pick(c, i) })
            n.end != null -> close()
            else -> box.addView(button(t("date_next")) { next(i + 1) })
        }
    }

    private fun pick(c: SChoice, i: Int) {
        doki = (doki + c.delta).coerceIn(0, max)
        ObjectAnimator.ofInt(meter, "progress", doki * 10).setDuration(450).start()
        mood(c.mood)
        box.removeAllViews()
        if (doki <= 0) {
            bubble.text = c.reply + "\n\n" + t("dt_end_fail")
            mood("sad")
            close()
            return
        }
        if (c.reply.isBlank()) { next(c.goto?.minus(1) ?: (i + 1)); return }
        bubble.text = c.reply
        box.addView(button(t("date_next")) { next(c.goto?.minus(1) ?: (i + 1)) })
    }
}

/** Меню дебаг-режима: пароль, «открыть всё», редактор текстов, свои сцены, замена спрайтов. */
class DevTools(
    private val act: Activity,
    private val prefs: SharedPreferences,
    private val t: (String) -> String,
    private val button: (String, () -> Unit) -> Button,
    private val girl: (String) -> Drawable?,
    private val colors: () -> Triple<IntArray, Int, Int>,
    private val rebuild: () -> Unit
) {
    private var pendingSlot: String? = null
    val games by lazy { GameDev(act, t, button, this) }
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    fun open() {
        if (prefs.getBoolean("debug", false)) menu() else password()
    }

    private fun password() {
        val input = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            hint = "0000"
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(act)
            .setTitle(t("dbg_pass_title"))
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString().toIntOrNull() == 4) {
                    prefs.edit().putBoolean("debug", true).apply()
                    menu()
                } else toast(t("dbg_wrong"))
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    private fun menu() {
        val items = arrayOf(t("dbg_unlock"), t("dbg_reset"), t("dbg_texts"), t("dbg_texts_reset"), t("dbg_scenes"), t("dbg_sprites"), t("gm_title"), t("ed_pack_export"), t("ed_pack_import"), t("dbg_exit"))
        AlertDialog.Builder(act)
            .setTitle(t("dbg_btn"))
            .setItems(items) { _, w ->
                when (w) {
                    0 -> {
                        prefs.edit().putBoolean("kawaii_unlocked", true).putBoolean("outfit_tank", true)
                            .putBoolean("outfit_pj", true).putInt("wins", maxOf(prefs.getInt("wins", 0), 10)).apply()
                        toast(t("dbg_done")); rebuild()
                    }
                    1 -> {
                        val e = prefs.edit()
                        for (k in listOf("outfit_tank", "outfit_pj", "wins", "wins_snake", "wins_maze", "wins_tank", "wins_run", "palette", "outfit_cur", "outfit_on")) e.remove(k)
                        e.apply(); toast(t("dbg_done")); rebuild()
                    }
                    2 -> textEditor()
                    3 -> { Strings.clearOverrides(act); toast(t("dbg_done")) }
                    4 -> scenes()
                    5 -> sprites()
                    6 -> games.menu()
                    7 -> packExportDialog()
                    8 -> packImport()
                    else -> { prefs.edit().putBoolean("debug", false).apply(); toast(t("dbg_done")) }
                }
            }
            .setNegativeButton(t("back"), null)
            .show()
    }

    // ---------------------------------------------------------------- редактор текстов

    private fun textEditor() {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val search = EditText(act).apply { setText("dt_"); setSingleLine(true); hint = t("dbg_search") }
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val cats = listOf("ed_c1" to "dt_s0", "ed_c2" to "dt_a_", "ed_c3" to "dt_ev", "ed_c4" to "dt_tk", "ed_c5" to "dt_eve",
            "ed_c6" to "dt_g", "ed_c7" to "dt_h", "ed_c8" to "dt_ep", "ed_c9" to "dt_so", "ed_c10" to "dt_end")
        root.addView(android.widget.HorizontalScrollView(act).apply {
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((label, prefix) in cats) addView(Button(act).apply {
                    text = t(label); textSize = 11f; isAllCaps = false
                    setOnClickListener { search.setText(prefix) }
                })
            })
        })
        root.addView(search)
        root.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(340)))
        fun fill() {
            list.removeAllViews()
            val q = search.text.toString().trim()
            val keys = Strings.keys().filter { q.isEmpty() || it.contains(q, true) || Strings.defaultOf(it).contains(q, true) }.take(80)
            for (k in keys) {
                list.addView(TextView(act).apply {
                    val v = Strings.defaultOf(k)
                    text = (if (Strings.hasOverride(k)) "✎ " else "") + k + "\n" + v.take(70)
                    textSize = 13f
                    setPadding(0, dp(8), 0, dp(8))
                    setOnClickListener { editKey(k) { fill() } }
                })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = fill()
        })
        fill()
        AlertDialog.Builder(act).setTitle(t("dbg_texts")).setView(root).setPositiveButton(t("back"), null).show()
    }

    private fun editKey(key: String, done: () -> Unit) {
        val e = EditText(act).apply {
            setText(Strings.defaultOf(key))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            minLines = 3
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(act)
            .setTitle(key)
            .setView(e)
            .setPositiveButton(t("save")) { _, _ -> Strings.setOverride(act, key, e.text.toString()); toast(t("dbg_done")); done() }
            .setNeutralButton(t("dbg_default")) { _, _ -> Strings.setOverride(act, key, null); done() }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    // ---------------------------------------------------------------- свои сцены

    private fun scenesDir() = File(act.filesDir, "scenes").apply { mkdirs() }

    private val template = """# My scene
@bg park
@mood happy
Hi! This is Yuta-chan's first line.
? Hello! | 2 | happy | Oh, how sweet!
? Um... | 0 | normal | Hm... okay.
? Ugh. | -2 | sad | (；´Д｀) | 3
---
@bg cafe
@mood wink
Second node. Where next?
? Onwards | 1 | wink | Yay!
---
@end win
@mood happy
The end. Thank you!
"""

    private fun scenes() {
        val files = scenesDir().listFiles()?.filter { it.extension == "txt" }?.sortedBy { it.name } ?: emptyList()
        val items = (listOf("+ " + t("dbg_new"), "⇩ " + t("ed_import_scene")) + files.map { it.nameWithoutExtension } + listOf("? " + t("dbg_help"))).toTypedArray()
        AlertDialog.Builder(act)
            .setTitle(t("dbg_scenes"))
            .setItems(items) { _, w ->
                when {
                    w == 0 -> newScene()
                    w == 1 -> importScene()
                    w == items.size - 1 -> help()
                    else -> sceneHub(files[w - 2])
                }
            }
            .setNegativeButton(t("back"), null)
            .show()
    }

    private fun newScene() {
        val name = EditText(act).apply { setSingleLine(true); setText("scene1"); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        AlertDialog.Builder(act).setTitle(t("dbg_new")).setView(name)
            .setPositiveButton("OK") { _, _ ->
                val n = name.text.toString().trim().replace(Regex("[^A-Za-z0-9_\\-\\u0400-\\u04FF]"), "_").ifEmpty { "scene" }
                val f = File(scenesDir(), "$n.txt")
                if (!f.exists()) f.writeText(template)
                sceneHub(f)
            }
            .setNegativeButton(t("cancel"), null).show()
    }

    private fun help() {
        AlertDialog.Builder(act).setTitle(t("dbg_help")).setMessage(t("dbg_help_text")).setPositiveButton("OK", null).show()
    }

    private fun play(src: String) {
        val (bg, accent, text) = colors()
        ScriptPlayer(act, t, button, girl, bg, accent, text, src).show()
    }

    private fun editScene(f: File) {
        val dlg = Dialog(act, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val edit = EditText(act).apply {
            setText(f.readText())
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP
            setHorizontallyScrolling(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, click: () -> Unit) = bar.addView(
            Button(act).apply { text = label; textSize = 12f; setOnClickListener { click() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        add(t("save")) { f.writeText(edit.text.toString()); toast(t("dbg_done")) }
        add(t("dbg_play")) { f.writeText(edit.text.toString()); play(edit.text.toString()) }
        add(t("ed_visual")) { f.writeText(edit.text.toString()); dlg.dismiss(); visual(f) }
        add("?") { help() }
        add(t("dbg_delete")) { f.delete(); dlg.dismiss() }
        add(t("date_close")) { f.writeText(edit.text.toString()); dlg.dismiss() }
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(24), dp(8), dp(8))
            addView(TextView(act).apply { text = f.nameWithoutExtension; textSize = 16f })
            addView(edit, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bar)
        }
        dlg.setContentView(root)
        dlg.show()
    }

    private fun visual(f: File) {
        SceneEditor(act, t, f, { play(it) }, { editScene(f) }).show()
    }

    private fun sceneHub(f: File) {
        AlertDialog.Builder(act)
            .setTitle(f.nameWithoutExtension)
            .setItems(arrayOf(t("ed_visual"), t("ed_text"), t("dbg_play"), t("ed_export"), t("dbg_delete"))) { _, w ->
                when (w) {
                    0 -> visual(f)
                    1 -> editScene(f)
                    2 -> play(f.readText())
                    3 -> exportScene(f)
                    else -> { f.delete(); toast(t("dbg_done")) }
                }
            }
            .setNegativeButton(t("back"), null)
            .show()
    }

    // ---------------------------------------------------------------- экспорт и импорт

    private var pendingExport: (() -> ByteArray)? = null
    private var pendingImport: ((Uri, ByteArray) -> Unit)? = null

    fun createDocPublic(name: String, mime: String, gen: () -> ByteArray) = createDoc(name, mime, gen)
    fun openDocPublic(handler: (Uri, ByteArray) -> Unit) = openDoc(handler)

    private fun createDoc(name: String, mime: String, gen: () -> ByteArray) {
        pendingExport = gen
        act.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }, 45)
    }

    private fun openDoc(handler: (Uri, ByteArray) -> Unit) {
        pendingImport = handler
        act.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, 46)
    }

    fun onCreated(uri: Uri) {
        val gen = pendingExport ?: return
        pendingExport = null
        try {
            act.contentResolver.openOutputStream(uri)?.use { it.write(gen()) }
            toast(t("ed_exported"))
        } catch (_: Throwable) {
            toast(t("dbg_fail"))
        }
    }

    fun onOpened(uri: Uri) {
        val h = pendingImport ?: return
        pendingImport = null
        try {
            val data = act.contentResolver.openInputStream(uri)?.use { readLimited(it, 40 * 1024 * 1024) }
            if (data == null) toast(t("ed_bad")) else h(uri, data)
        } catch (_: Throwable) {
            toast(t("ed_bad"))
        }
        rebuild()
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    private fun exportScene(f: File) = createDoc(f.name, "text/plain") { f.readBytes() }

    private fun uniqueScene(base: String): File {
        var f = File(scenesDir(), "$base.txt")
        var i = 2
        while (f.exists()) f = File(scenesDir(), "${base}_${i++}.txt")
        return f
    }

    private fun importScene() = openDoc { uri, data ->
        var name = "imported"
        act.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0).orEmpty().substringBeforeLast('.')
        }
        name = name.replace(Regex("[^\\p{L}\\p{N}_\\-]"), "_").take(40).ifEmpty { "imported" }
        val text = String(data, Charsets.UTF_8)
        if (ScriptPlayer.parse(text).isEmpty()) toast(t("ed_bad")) else {
            uniqueScene(name).writeText(text)
            toast(String.format(t("ed_imported"), name))
        }
    }

    private fun packExportDialog() {
        val names = arrayOf(t("ed_part_skins"), t("ed_part_scenes"), t("ed_part_texts"))
        val on = booleanArrayOf(true, true, true)
        AlertDialog.Builder(act)
            .setTitle(t("ed_pack_export"))
            .setMultiChoiceItems(names, on) { _, i, c -> on[i] = c }
            .setPositiveButton("OK") { _, _ ->
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                createDoc("vad-pack-$stamp.zip", "application/zip") { buildPack(on[0], on[1], on[2]) }
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    private fun buildPack(skins: Boolean, scenes: Boolean, texts: Boolean): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            fun put(name: String, data: ByteArray) {
                z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(data); z.closeEntry()
            }
            put("manifest.json", "{\"app\":\"vad\",\"version\":1}".toByteArray())
            if (skins) for (slot in Sprites.allSlots) if (Sprites.has(act, slot)) put("sprites/$slot.png", Sprites.fileOf(act, slot).readBytes())
            if (scenes) scenesDir().listFiles()?.filter { it.extension == "txt" }?.forEach { put("scenes/${it.name}", it.readBytes()) }
            if (texts) {
                val o = org.json.JSONObject()
                for ((k, v) in Strings.overridesMap()) o.put(k, v)
                put("texts.json", o.toString().toByteArray())
            }
        }
        return bos.toByteArray()
    }

    private fun packImport() = openDoc { _, data ->
        var sp = 0
        var sc = 0
        var tx = 0
        var count = 0
        val sceneRe = Regex("scenes/([\\p{L}\\p{N}_\\-]{1,40})\\.txt")
        val spriteRe = Regex("sprites/([A-Za-z0-9_]+)\\.png")
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(data)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (++count > 300) break
                val name = e.name
                val body = readLimited(z, 20 * 1024 * 1024) ?: continue
                val sm = spriteRe.matchEntire(name)
                val cm = sceneRe.matchEntire(name)
                when {
                    sm != null && sm.groupValues[1] in Sprites.allSlots -> if (Sprites.setBytes(act, sm.groupValues[1], body)) sp++
                    cm != null -> {
                        val text = String(body, Charsets.UTF_8)
                        if (text.length < 500_000 && ScriptPlayer.parse(text).isNotEmpty()) {
                            uniqueScene(cm.groupValues[1]).writeText(text); sc++
                        }
                    }
                    name == "texts.json" -> try {
                        val o = org.json.JSONObject(String(body, Charsets.UTF_8))
                        val m = HashMap<String, String>()
                        for (k in o.keys()) if (k.length < 80) m[k] = o.getString(k).take(2000)
                        Strings.mergeOverrides(act, m); tx = m.size
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        toast(String.format(t("ed_imported"), "$sp / $sc / $tx"))
    }

    // ---------------------------------------------------------------- спрайты

    private fun sprites() {
        val slots = Sprites.allSlots
        val items = slots.map { (if (Sprites.has(act, it)) "✓ " else "") + it } + listOf("↺ " + t("dbg_reset_all_sprites"))
        AlertDialog.Builder(act)
            .setTitle(t("dbg_sprites"))
            .setItems(items.toTypedArray()) { _, w ->
                if (w == slots.size) {
                    Sprites.resetAll(act); toast(t("dbg_done")); rebuild()
                } else slotMenu(slots[w])
            }
            .setNegativeButton(t("back"), null)
            .show()
    }

    private fun slotMenu(slot: String) {
        AlertDialog.Builder(act)
            .setTitle(slot)
            .setItems(arrayOf(t("dbg_replace"), t("dbg_default"))) { _, w ->
                if (w == 0) {
                    pendingSlot = slot
                    act.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }, 44)
                } else {
                    Sprites.reset(act, slot); toast(t("dbg_done")); rebuild()
                }
            }
            .setNegativeButton(t("cancel"), null)
            .show()
    }

    fun onSpritePicked(uri: Uri) {
        val s = pendingSlot ?: return
        pendingSlot = null
        if (Sprites.set(act, s, uri)) { toast(t("dbg_done")); rebuild() } else toast(t("dbg_fail"))
    }
}
