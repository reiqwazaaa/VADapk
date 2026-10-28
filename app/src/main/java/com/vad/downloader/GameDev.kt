package com.vad.downloader

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Режим разработчика: меню мини-игр, редактор кода, редактор графики, экспорт/импорт. */
class GameDev(
    private val act: Activity,
    private val t: (String) -> String,
    private val button: (String, () -> Unit) -> Button,
    private val dev: DevTools
) {
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
    private var pendingGame: String? = null
    private var pendingSprite: GamePacks.Sprite? = null
    private var onSpriteChanged: (() -> Unit)? = null

    fun menu() {
        val ids = GamePacks.ids(act)
        val items = (listOf("+ " + t("gm_create"), "⇩ " + t("gm_import")) + ids.map { GamePacks.name(act, it) + if (GamePacks.isBuiltin(it)) "" else " *" } + listOf("? " + t("gm_help"))).toTypedArray()
        val d = AlertDialog.Builder(act)
            .setTitle(t("gm_title"))
            .setItems(items) { _, w ->
                when {
                    w == 0 -> create()
                    w == 1 -> import()
                    w == items.size - 1 -> AlertDialog.Builder(act).setTitle(t("gm_help")).setMessage(t("gm_help_text")).setPositiveButton("OK", null).show()
                    else -> test(ids[w - 2])
                }
            }
            .setNegativeButton(t("back"), null)
            .create()
        d.show()
        d.listView.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos in 2 until items.size - 1) {
                d.dismiss()
                gameMenu(ids[pos - 2])
                true
            } else false
        }
    }

    private fun create() {
        val name = EditText(act).apply { setSingleLine(true); setText(t("gm_new_name")); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        AlertDialog.Builder(act).setTitle(t("gm_create")).setView(name)
            .setPositiveButton("OK") { _, _ ->
                val id = GamePacks.create(act, name.text.toString().trim())
                gameMenu(id)
            }
            .setNegativeButton(t("cancel"), null).show()
    }

    private fun gameMenu(id: String) {
        val builtin = GamePacks.isBuiltin(id)
        val items = mutableListOf(t("gm_play"), t("gm_code"), t("gm_graphics"), t("gm_export"))
        items += if (builtin) t("gm_reset") else t("gm_delete")
        if (!builtin) items += t("gm_rename")
        AlertDialog.Builder(act)
            .setTitle(GamePacks.name(act, id))
            .setItems(items.toTypedArray()) { _, w ->
                when (w) {
                    0 -> test(id)
                    1 -> codeEditor(id)
                    2 -> graphics(id)
                    3 -> dev.createDocPublic(id + ".vadgame.zip", "application/zip") { GamePacks.export(act, id) }
                    4 -> confirm(if (builtin) t("gm_reset_q") else t("gm_delete_q")) {
                        if (builtin) GamePacks.reset(act, id) else GamePacks.delete(act, id)
                        toast(t("dbg_done")); menu()
                    }
                    else -> rename(id)
                }
            }
            .setNegativeButton(t("back"), null)
            .show()
    }

    private fun rename(id: String) {
        val name = EditText(act).apply { setSingleLine(true); setText(GamePacks.name(act, id)); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        AlertDialog.Builder(act).setTitle(t("gm_rename")).setView(name)
            .setPositiveButton("OK") { _, _ -> GamePacks.setTitle(act, id, name.text.toString().trim().ifEmpty { id }); menu() }
            .setNegativeButton(t("cancel"), null).show()
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(act).setMessage(msg).setPositiveButton("OK") { _, _ -> ok() }.setNegativeButton(t("cancel"), null).show()
    }

    private fun import() {
        dev.openDocPublic { uri, data ->
            var name = "imported"
            act.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0).orEmpty()
            }
            val id = try { GamePacks.importGame(act, data, name) } catch (_: Throwable) { null }
            if (id == null) toast(t("ed_bad")) else toast(String.format(t("ed_imported"), GamePacks.name(act, id)))
        }
    }

    // ------------------------------------------------------------ тестовый запуск

    fun test(id: String, codeOverride: String? = null) {
        val dlg = Dialog(act, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        val err = TextView(act).apply { setTextColor(0xFFC62828.toInt()); textSize = 13f; setPadding(0, dp(4), 0, dp(4)) }
        lateinit var host: GameHost
        host = GameHost(act, GamePacks.html(act, id, codeOverride), { win ->
            toast(if (win) t("game_win") else t("game_lose"))
        }, { msg -> err.text = "JS: $msg" })
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFE6F3.toInt())
            setPadding(dp(12), dp(24), dp(12), dp(12))
        }
        box.addView(TextView(act).apply { text = GamePacks.name(act, id); textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF4B2E83.toInt()) })
        box.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(err)
        box.addView(button(t("back")) { dlg.dismiss() })
        dlg.setContentView(box)
        dlg.setOnDismissListener { host.destroy() }
        dlg.show()
    }

    // ------------------------------------------------------------ редактор кода

    private fun codeEditor(id: String) {
        val dlg = Dialog(act, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        val edit = EditText(act).apply {
            setText(GamePacks.readCode(act, id))
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setHorizontallyScrolling(true)
            setTextColor(0xFF000000.toInt())
            setHintTextColor(0xFF666666.toInt())
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val scroll = android.widget.ScrollView(act).apply { addView(edit) }
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, click: () -> Unit) = bar.addView(button(label, click), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(t("ed_save")) { GamePacks.writeCode(act, id, edit.text.toString()); toast(t("dbg_done")) }
        add("▶ " + t("gm_play")) { GamePacks.writeCode(act, id, edit.text.toString()); test(id) }
        add(t("back")) {
            if (edit.text.toString() == GamePacks.readCode(act, id)) dlg.dismiss()
            else AlertDialog.Builder(act).setMessage(t("ed_discard"))
                .setPositiveButton(t("ed_save")) { _, _ -> GamePacks.writeCode(act, id, edit.text.toString()); dlg.dismiss() }
                .setNegativeButton(t("ed_drop")) { _, _ -> dlg.dismiss() }
                .setNeutralButton(t("cancel"), null).show()
        }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFFFE6F3.toInt()); setPadding(dp(8), dp(24), dp(8), dp(8)) }
        box.addView(TextView(act).apply { text = GamePacks.name(act, id) + " — game.js"; setTextColor(0xFF4B2E83.toInt()) })
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(bar)
        dlg.setContentView(box)
        dlg.setCancelable(false)
        dlg.show()
    }

    // ------------------------------------------------------------ редактор графики

    private fun graphics(id: String) {
        val list = GamePacks.sprites(act, id)
        if (list.isEmpty()) { toast(t("gm_no_sprites")); return }
        val items = list.map { (if (GamePacks.hasSprite(act, id, it.name)) "✓ " else "") + it.name + "  (${it.size}px)" }.toTypedArray()
        AlertDialog.Builder(act).setTitle(t("gm_graphics"))
            .setItems(items) { _, w -> spriteMenu(id, list[w]) }
            .setNegativeButton(t("back"), null).show()
    }

    private fun spriteMenu(id: String, s: GamePacks.Sprite) {
        AlertDialog.Builder(act).setTitle(s.name)
            .setItems(arrayOf(t("gm_draw"), t("dbg_replace"), t("dbg_default"))) { _, w ->
                when (w) {
                    0 -> pixelEditor(id, s)
                    1 -> {
                        pendingGame = id; pendingSprite = s
                        act.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, 47)
                    }
                    else -> { GamePacks.resetSprite(act, id, s.name); toast(t("dbg_done")); graphics(id) }
                }
            }
            .setNegativeButton(t("back"), null).show()
    }

    fun onImagePicked(uri: Uri) {
        val id = pendingGame ?: return
        val s = pendingSprite ?: return
        pendingGame = null; pendingSprite = null
        try {
            val bmp = act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bmp != null && GamePacks.setSprite(act, id, s.name, bmp)) toast(t("dbg_done")) else toast(t("dbg_fail"))
        } catch (_: Throwable) {
            toast(t("dbg_fail"))
        }
    }

    private val palette = intArrayOf(
        0x00000000, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF9E9E9E.toInt(), 0xFFFF4F98.toInt(), 0xFFFF9ACB.toInt(), 0xFFE53935.toInt(), 0xFFFF9800.toInt(),
        0xFFFFC93C.toInt(), 0xFF8BC34A.toInt(), 0xFF2E7D32.toInt(), 0xFF5CC8FF.toInt(), 0xFF1E5BC6.toInt(), 0xFF7E57C2.toInt(), 0xFF4B2E83.toInt(), 0xFF795548.toInt()
    )

    private fun pixelEditor(id: String, s: GamePacks.Sprite) {
        val n = 24
        val grid = IntArray(n * n)
        val file = GamePacks.spriteFile(act, id, s.name)
        if (file.isFile) {
            BitmapFactory.decodeFile(file.path)?.let { b ->
                val sm = Bitmap.createScaledBitmap(b, n, n, true)
                for (y in 0 until n) for (x in 0 until n) grid[y * n + x] = sm.getPixel(x, y)
            }
        }
        var color = palette[4]
        var tool = 0 // 0 кисть, 1 заливка
        val view = object : View(act) {
            private val p = Paint()
            override fun onMeasure(w: Int, h: Int) {
                val sz = MeasureSpec.getSize(w)
                setMeasuredDimension(sz, sz)
            }

            override fun onDraw(c: Canvas) {
                val cs = width.toFloat() / n
                for (y in 0 until n) for (x in 0 until n) {
                    p.color = if ((x + y) % 2 == 0) 0xFFE8E8E8.toInt() else 0xFFFFFFFF.toInt()
                    c.drawRect(x * cs, y * cs, (x + 1) * cs, (y + 1) * cs, p)
                    val v = grid[y * n + x]
                    if (Color.alpha(v) > 0) { p.color = v; c.drawRect(x * cs, y * cs, (x + 1) * cs, (y + 1) * cs, p) }
                }
            }

            private fun fill(x: Int, y: Int, to: Int) {
                val from = grid[y * n + x]
                if (from == to) return
                val st = ArrayDeque<Int>()
                st.add(y * n + x)
                while (st.isNotEmpty()) {
                    val i = st.removeLast()
                    if (grid[i] != from) continue
                    grid[i] = to
                    val cx = i % n; val cy = i / n
                    if (cx > 0) st.add(i - 1); if (cx < n - 1) st.add(i + 1)
                    if (cy > 0) st.add(i - n); if (cy < n - 1) st.add(i + n)
                }
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                val x = (e.x / width * n).toInt()
                val y = (e.y / height * n).toInt()
                if (x !in 0 until n || y !in 0 until n) return true
                parent.requestDisallowInterceptTouchEvent(true)
                if (tool == 1) { if (e.action == MotionEvent.ACTION_DOWN) fill(x, y, color) } else grid[y * n + x] = color
                invalidate()
                return true
            }
        }
        val dlg = Dialog(act, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFFFE6F3.toInt()); setPadding(dp(12), dp(24), dp(12), dp(12)) }
        box.addView(TextView(act).apply { text = s.name; setTextColor(0xFF4B2E83.toInt()) })
        box.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val pal = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until 2) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 8) {
                val col = palette[r * 8 + k]
                row.addView(View(act).apply {
                    setBackgroundColor(if (col == 0) 0xFFDDDDDD.toInt() else col)
                    setOnClickListener { color = col }
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            pal.addView(row)
        }
        box.addView(pal)
        val tools = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, click: () -> Unit) = tools.addView(button(label, click), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add("✎") { tool = 0 }
        add("▣") { tool = 1 }
        add("⌫") { color = 0 }
        add("✕") { grid.fill(0); view.invalidate() }
        box.addView(tools)
        val bottom = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(button(t("ed_save")) {
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            for (y in 0 until n) for (x in 0 until n) bmp.setPixel(x, y, grid[y * n + x])
            val out = Bitmap.createScaledBitmap(bmp, s.size, s.size, false)
            toast(if (GamePacks.setSprite(act, id, s.name, out)) t("dbg_done") else t("dbg_fail"))
            dlg.dismiss()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(button(t("back")) { dlg.dismiss() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(bottom)
        dlg.setContentView(box)
        dlg.show()
    }
}
