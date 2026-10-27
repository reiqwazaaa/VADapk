package com.vad.downloader

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

class EChoice(var label: String = "", var delta: Int = 0, var mood: String = "normal", var reply: String = "", var goto: Int? = null)
class ENode(var text: String = "…", var bg: String? = null, var mood: String = "normal", var end: String? = null, val choices: MutableList<EChoice> = ArrayList())

object SceneText {
    val moods = listOf("normal", "happy", "sad", "wink")
    val bgs = listOf("street", "park", "cafe", "aqua", "fun", "evening", "room_her", "room_home", "room_her_night", "room_home_night")
    val ends = listOf("", "win", "lose")

    fun parse(src: String): MutableList<ENode> = ScriptPlayer.parse(src).map { n ->
        ENode(n.text.ifBlank { "…" }, n.bg, n.mood, n.end,
            n.choices.map { EChoice(it.label, it.delta, it.mood, it.reply, it.goto) }.toMutableList())
    }.toMutableList()

    private fun clean(s: String) = s.replace("|", "/").replace("\n", " ").trim()

    fun serialize(nodes: List<ENode>): String = nodes.joinToString("\n---\n") { n ->
        buildString {
            n.bg?.let { append("@bg ").append(it).append('\n') }
            append("@mood ").append(n.mood).append('\n')
            n.end?.let { append("@end ").append(it).append('\n') }
            for (line in n.text.lines()) {
                val l = line.trim()
                if (l.isNotEmpty()) append(if (l[0] in "#@?-") "​$l" else l).append('\n')
            }
            for (c in n.choices) {
                append("? ").append(clean(c.label)).append(" | ").append(c.delta).append(" | ").append(c.mood)
                append(" | ").append(clean(c.reply))
                c.goto?.let { append(" | ").append(it) }
                append('\n')
            }
        }.trimEnd()
    } + "\n"
}

/** Визуальный редактор сцены: узлы, ответы, фоны, эмоции, переходы - без правки текста руками. */
class SceneEditor(
    private val act: Activity,
    private val t: (String) -> String,
    private val file: File,
    private val play: (String) -> Unit,
    private val openRaw: () -> Unit
) {
    private val nodes = SceneText.parse(file.readText())
    private lateinit var dlg: Dialog
    private lateinit var list: LinearLayout

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
    private fun save() { file.writeText(SceneText.serialize(nodes)) }

    private fun spin(items: List<String>, sel: Int) = Spinner(act).apply {
        adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel.coerceIn(0, items.size - 1))
    }

    private fun label(s: String) = TextView(act).apply { text = s; textSize = 12f; setPadding(0, dp(10), 0, 0) }

    private fun btn(text: String, click: () -> Unit) = Button(act).apply { this.text = text; textSize = 12f; setOnClickListener { click() } }

    fun show() {
        if (nodes.isEmpty()) nodes.add(ENode())
        dlg = Dialog(act, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(24), dp(10), dp(8)) }
        root.addView(TextView(act).apply { text = file.nameWithoutExtension + " · " + t("ed_nodes"); textSize = 17f; setTypeface(typeface, Typeface.BOLD) })
        list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(b: Button) = bar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(btn(t("save")) { save(); toast(t("ed_saved")) })
        add(btn(t("dbg_play")) { save(); play(file.readText()) })
        add(btn(t("ed_text")) { save(); dlg.dismiss(); openRaw() })
        add(btn(t("date_close")) { save(); dlg.dismiss() })
        root.addView(bar)
        dlg.setContentView(root)
        fill()
        dlg.show()
    }

    private fun fill() {
        list.removeAllViews()
        nodes.forEachIndexed { i, n ->
            list.addView(TextView(act).apply {
                val tail = when {
                    n.end != null -> "  ■ " + n.end
                    n.choices.isNotEmpty() -> "  ▸ ${n.choices.size}"
                    else -> ""
                }
                text = "#${i + 1}  [${n.bg ?: "·"} / ${n.mood}]$tail\n${n.text.replace("\n", " ").take(80)}"
                textSize = 14f
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0x11000000); cornerRadius = dp(8).toFloat() }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                setOnClickListener { editNode(i) }
            })
        }
        list.addView(btn("+ " + t("ed_add_node")) { nodes.add(ENode()); fill(); editNode(nodes.size - 1) })
    }

    /** Все ссылки "перейти к узлу №" проходят через f (старый номер -> новый или null). */
    private fun fixGotos(f: (Int) -> Int?) {
        for (n in nodes) for (c in n.choices) c.goto = c.goto?.let(f)
    }

    private fun editNode(i: Int) {
        val n = nodes[i]
        val d = Dialog(act, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(24), dp(12), dp(8)) }
        col.addView(TextView(act).apply { text = t("ed_node") + " #${i + 1}"; textSize = 17f; setTypeface(typeface, Typeface.BOLD) })
        col.addView(label(t("ed_text_f")))
        val text = EditText(act).apply {
            setText(n.text); minLines = 3; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        col.addView(text)
        col.addView(label(t("ed_bg")))
        val bgItems = listOf(t("ed_inherit")) + SceneText.bgs
        val bg = spin(bgItems, if (n.bg == null) 0 else bgItems.indexOf(n.bg).coerceAtLeast(0))
        col.addView(bg)
        col.addView(label(t("ed_mood")))
        val mood = spin(SceneText.moods, SceneText.moods.indexOf(n.mood))
        col.addView(mood)
        col.addView(label(t("ed_end")))
        val endItems = listOf(t("ed_none"), "win", "lose")
        val end = spin(endItems, SceneText.ends.indexOf(n.end ?: ""))
        col.addView(end)
        col.addView(label(t("ed_choices")))
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(box)

        fun commit() {
            n.text = text.text.toString().ifBlank { "…" }
            n.bg = if (bg.selectedItemPosition == 0) null else SceneText.bgs[bg.selectedItemPosition - 1]
            n.mood = SceneText.moods[mood.selectedItemPosition]
            n.end = SceneText.ends[end.selectedItemPosition].ifEmpty { null }
        }

        fun fillChoices() {
            box.removeAllViews()
            n.choices.forEachIndexed { k, c ->
                box.addView(TextView(act).apply {
                    val g = c.goto?.let { " → #$it" }.orEmpty()
                    this.text = "${k + 1}. ${c.label}  (${if (c.delta > 0) "+" else ""}${c.delta}, ${c.mood})$g"
                    textSize = 14f
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(0x11000000); cornerRadius = dp(8).toFloat() }
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
                    setOnClickListener { editChoice(n, k) { fillChoices() } }
                })
            }
            box.addView(btn("+ " + t("ed_add_choice")) {
                n.choices.add(EChoice(label = "…"))
                fillChoices()
                editChoice(n, n.choices.size - 1) { fillChoices() }
            })
        }
        fillChoices()

        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(b: Button) = bar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(btn("▲") {
            if (i > 0) {
                commit()
                nodes[i] = nodes[i - 1].also { nodes[i - 1] = nodes[i] }
                fixGotos { when (it) { i + 1 -> i; i -> i + 1; else -> it } }
                d.dismiss(); fill(); editNode(i - 1)
            }
        })
        add(btn("▼") {
            if (i < nodes.size - 1) {
                commit()
                nodes[i] = nodes[i + 1].also { nodes[i + 1] = nodes[i] }
                fixGotos { when (it) { i + 1 -> i + 2; i + 2 -> i + 1; else -> it } }
                d.dismiss(); fill(); editNode(i + 1)
            }
        })
        add(btn(t("ed_dup")) {
            commit()
            val copy = ENode(n.text, n.bg, n.mood, n.end, n.choices.map { EChoice(it.label, it.delta, it.mood, it.reply, it.goto) }.toMutableList())
            nodes.add(i + 1, copy)
            fixGotos { if (it > i + 1) it + 1 else it }
            // копия ссылается на те же узлы, что и оригинал (с поправкой на сдвиг)
            d.dismiss(); fill()
        })
        add(btn(t("dbg_delete")) {
            if (nodes.size > 1) {
                nodes.removeAt(i)
                fixGotos { if (it == i + 1) null else if (it > i + 1) it - 1 else it }
                d.dismiss(); fill()
            }
        })
        add(btn("OK") { commit(); d.dismiss(); fill() })
        col.addView(bar)
        d.setContentView(ScrollView(act).apply { addView(col) })
        d.show()
    }

    private fun editChoice(n: ENode, k: Int, done: () -> Unit) {
        val c = n.choices[k]
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val labelE = EditText(act).apply { setText(c.label); setSingleLine(true) }
        val delta = EditText(act).apply {
            setText(c.delta.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            filters = arrayOf(InputFilter.LengthFilter(3))
        }
        val mood = spin(SceneText.moods, SceneText.moods.indexOf(c.mood))
        val reply = EditText(act).apply { setText(c.reply); minLines = 2; gravity = Gravity.TOP }
        val goto = EditText(act).apply {
            setText(c.goto?.toString().orEmpty())
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(3))
        }
        col.addView(label(t("ed_label"))); col.addView(labelE)
        col.addView(label(t("ed_delta"))); col.addView(delta)
        col.addView(label(t("ed_mood"))); col.addView(mood)
        col.addView(label(t("ed_reply"))); col.addView(reply)
        col.addView(label(t("ed_goto"))); col.addView(goto)
        AlertDialog.Builder(act)
            .setView(ScrollView(act).apply { addView(col) })
            .setPositiveButton(t("save")) { _, _ ->
                c.label = labelE.text.toString().ifBlank { "…" }
                c.delta = (delta.text.toString().toIntOrNull() ?: 0).coerceIn(-9, 9)
                c.mood = SceneText.moods[mood.selectedItemPosition]
                c.reply = reply.text.toString()
                c.goto = goto.text.toString().toIntOrNull()?.takeIf { it in 1..nodes.size }
                done()
            }
            .setNeutralButton(t("dbg_delete")) { _, _ -> n.choices.removeAt(k); done() }
            .setNegativeButton(t("cancel")) { _, _ -> done() }
            .show()
    }
}
