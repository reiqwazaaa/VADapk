package com.vad.downloader

import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Маленький симулятор свидания с Ютой-чан: пять выборов, шкала doki-doki (старт 1/3),
 * при провале она уходит по делам, при полной шкале в конце открывается наряд.
 */
class DateSim(
    private val ctx: Context,
    private val t: (String) -> String,
    private val button: (String, () -> Unit) -> Button,
    private val girlRes: (String) -> android.graphics.drawable.Drawable?,
    private val bg: IntArray,
    private val accent: Int,
    private val textColor: Int,
    private val hadTank: Boolean,
    private val onWin: () -> Unit,
    private val onPajama: () -> Unit
) {
    private class Choice(val label: String, val delta: Int, val reply: String, val mood: String, val place: String? = null)
    private class Node(val text: String, val mood: String, val choices: List<Choice>)

    private val max = 15
    private var doki = 5
    private var step = 0
    private val evs = (1..8).shuffled().take(2)
    private val tks = (1..5).shuffled().take(2)
    private val steps = listOf("s0", "where", "a1", "a2", "ev1", "tk1", "a3", "ev2", "tk2", "eve", "gift", "bye")
    private lateinit var scene: SceneView
    private var inRoom = false
    private var roomScene: String? = null
    private var roomNodes: List<Node> = emptyList()
    private var roomStep = 0
    private var roomEnd = ""
    private val places = listOf("park", "cafe", "aqua", "fun")
    private val hint = places.random()
    private var place = "park"
    private val giftMood = listOf("tired", "sad", "cheerful").random()

    private lateinit var dlg: Dialog
    private lateinit var girl: ImageView
    private lateinit var bubble: TextView
    private lateinit var choicesBox: LinearLayout
    private lateinit var meter: ProgressBar
    private lateinit var delta: TextView

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show() {
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
            addView(scene, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // шкала doki-doki
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xB3FFFFFF.toInt())
            }
        }
        top.addView(TextView(ctx).apply {
            text = "♥ " + t("doki_label")
            setTextColor(textColor)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, dp(10), 0)
        })
        meter = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            this.max = this@DateSim.max * 10
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

        // девочка и пузырь с плавающим "+♥"
        val stage = FrameLayout(ctx)
        girl = ImageView(ctx).apply { setImageDrawable(girlRes("normal")) }
        stage.addView(girl, FrameLayout.LayoutParams(dp(170), dp(204), Gravity.CENTER))
        delta = TextView(ctx).apply {
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            alpha = 0f
        }
        stage.addView(delta, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(214)))
        ObjectAnimator.ofFloat(girl, "translationY", 0f, -dp(6).toFloat()).apply {
            duration = 1100
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }

        bubble = TextView(ctx).apply {
            textSize = 16f
            setTextColor(0xFF4B2E83.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minHeight = dp(120)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xE6FFFFFF.toInt())
            }
        }
        root.addView(bubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        choicesBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(ctx).apply { addView(choicesBox) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        dlg.setContentView(frame)
        dlg.show()
        showNode(nodeFor(steps[0]))
    }

    private fun setMood(m: String) {
        girl.setImageDrawable(girlRes(m))
        girl.animate().scaleX(1.08f).scaleY(1.08f).setDuration(110).withEndAction {
            girl.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }.start()
    }

    private fun showNode(n: Node) {
        scene.scene = roomScene ?: sceneFor(steps[step])
        setMood(n.mood)
        bubble.text = n.text
        choicesBox.removeAllViews()
        for (c in n.choices.shuffled()) {
            choicesBox.addView(button(c.label) { choose(c) })
        }
    }

    private fun choose(c: Choice) {
        c.place?.let { place = it }
        doki = (doki + c.delta).coerceIn(0, max)
        ObjectAnimator.ofInt(meter, "progress", doki * 10).setDuration(450).start()
        floatDelta(maxOf(c.delta, -3))
        setMood(c.mood)
        bubble.text = c.reply
        choicesBox.removeAllViews()
        when {
            doki <= 0 -> {
                bubble.text = c.reply + "\n\n" + t(if (inRoom) "dt_end_fail2" else "dt_end_fail")
                setMood("sad")
                choicesBox.addView(button(t("date_close")) { dlg.dismiss() })
            }
            inRoom -> {
                choicesBox.addView(button(t("date_next")) {
                    roomStep++
                    if (roomStep < roomNodes.size) showNode(roomNodes[roomStep]) else winRoom()
                })
            }
            step >= steps.lastIndex -> {
                choicesBox.addView(button(t("date_next")) { finishDate() })
            }
            else -> {
                choicesBox.addView(button(t("date_next")) {
                    step++
                    showNode(nodeFor(steps[step]))
                })
            }
        }
    }

    private fun floatDelta(d: Int) {
        delta.text = if (d > 0) "+".repeat(1) + "♥".repeat(d) else "−" + "♥".repeat(-d)
        delta.setTextColor(if (d > 0) 0xFFFF4F98.toInt() else 0xFF6C63B5.toInt())
        delta.translationY = dp(30).toFloat()
        delta.alpha = 1f
        delta.animate().translationY(-dp(10).toFloat()).alpha(0f).setDuration(1100).start()
    }

    /** Макс. шкала: продолжение у неё дома или у игрока, фон - фото комнаты. */
    private fun goHome(room: String, reply: String, end: String) {
        inRoom = true
        roomScene = room
        roomEnd = end
        roomStep = 0
        roomNodes = if (room == "room_her") listOf(
            Node(t("dt_w1"), "happy", quad4("dt_w1", intArrayOf(3, -99, -99, -99))),
            Node(t("dt_w2"), "normal", quad4("dt_w2", intArrayOf(2, 2, -99, -99)))
        ) else listOf(
            Node(t("dt_h1"), "normal", quad4("dt_h1", intArrayOf(2, 1, 1, 0))),
            Node(t("dt_h2"), "normal", quad4("dt_h2", intArrayOf(2, 1, 0, -99))),
            Node(t("dt_h3"), "normal", quad4("dt_h3", intArrayOf(2, 2, 1, -99)))
        )
        scene.scene = room
        setMood("wink")
        bubble.text = t(reply)
        choicesBox.removeAllViews()
        choicesBox.addView(button(t("date_next")) { showNode(roomNodes[0]) })
    }

    private fun quad4(prefix: String, d: IntArray): List<Choice> = (1..4).map { i ->
        Choice(t("${prefix}_$i"), d[i - 1], t("${prefix}_r$i"), if (d[i - 1] < 0) "sad" else if (d[i - 1] >= 2) "happy" else "wink")
    }

    private fun winRoom() {
        setMood("happy")
        bubble.text = t(roomEnd)
        choicesBox.removeAllViews()
        choicesBox.addView(button(t("date_next")) { sleepover() })
    }

    // ------------------------------------------------------------------ ночёвка: разговор по душам

    private val epLines = intArrayOf(5, 4, 5, 4, 5)
    private val lineMoods = listOf("normal", "wink", "happy", "normal", "happy")

    private fun sleepover() {
        val night = (roomScene ?: "room_her") + "_night"
        scene.scene = night
        roomScene = night
        setMood("wink")
        bubble.text = t(if (roomEnd == "dt_home_end1") "dt_so_intro_her" else "dt_so_intro_his")
        choicesBox.removeAllViews()
        choicesBox.addView(button(t("date_next")) { pickTopic() })
    }

    private fun pickTopic() {
        setMood("normal")
        bubble.text = t("dt_so_pick")
        choicesBox.removeAllViews()
        for (i in 1..5) choicesBox.addView(button(t("dt_ep${i}_t")) { playLine(i, 1) })
    }

    private fun playLine(ep: Int, n: Int) {
        setMood(lineMoods[(n - 1) % lineMoods.size])
        bubble.text = t("dt_ep${ep}_l$n")
        choicesBox.removeAllViews()
        choicesBox.addView(button(t("date_next")) {
            if (n < epLines[ep - 1]) playLine(ep, n + 1) else askQuestion(ep)
        })
    }

    private fun askQuestion(ep: Int) {
        setMood("wink")
        bubble.text = t("dt_ep${ep}_q")
        choicesBox.removeAllViews()
        for (i in 1..5) choicesBox.addView(button(t("dt_ep${ep}_a$i")) {
            setMood("happy")
            bubble.text = t("dt_ep${ep}_ar$i")
            choicesBox.removeAllViews()
            choicesBox.addView(button(t("date_next")) { goodNight() })
        })
    }

    private fun goodNight() {
        setMood("normal")
        bubble.text = t("dt_so_gn")
        choicesBox.removeAllViews()
        choicesBox.addView(button(t("dt_so_you")) {
            setMood("happy")
            bubble.text = t("dt_so_end") + "\n\n" + t("date_reward_pj")
            onPajama()
            choicesBox.removeAllViews()
            choicesBox.addView(button(t("date_close")) { dlg.dismiss() })
        })
    }

    private fun finishDate() {
        choicesBox.removeAllViews()
        if (doki >= max) {
            setMood("happy")
            if (!hadTank) {
                bubble.text = t("dt_end_win") + "\n\n" + t("date_reward")
                onWin()
            } else {
                scene.scene = "evening"
                bubble.text = t("dt_home")
                choicesBox.addView(button(t("dt_home_1")) { goHome("room_her", "dt_home_r1", "dt_home_end1") })
                choicesBox.addView(button(t("dt_home_2")) { goHome("room_home", "dt_home_r2", "dt_home_end2") })
                choicesBox.addView(button(t("dt_home_3")) {
                    bubble.text = t("dt_end_win")
                    choicesBox.removeAllViews()
                    choicesBox.addView(button(t("date_close")) { dlg.dismiss() })
                })
                return
            }
        } else {
            setMood("wink")
            bubble.text = t("dt_end_ok")
        }
        choicesBox.addView(button(t("date_close")) { dlg.dismiss() })
    }

    // ------------------------------------------------------------------ сценарий

    private fun sceneFor(k: String): String = when (k) {
        "s0", "where" -> "street"
        "a1", "a2", "a3", "ev1", "ev2", "tk1", "tk2" -> place
        else -> "evening"
    }

    private fun nodeFor(k: String): Node = when (k) {
        "s0" -> Node(t("dt_hi") + "\n\n" + t("dt_hint_$hint"), "happy", quad("dt_s0"))
        "where" -> Node(
            t("dt_where"), "normal",
            places.map { p -> Choice(t("dt_pl_$p"), if (p == hint) 2 else 1, t("dt_plr_$p"), "happy", p) }
        )
        "a1" -> Node(t("dt_a_${place}1"), "normal", quad("dt_a_${place}1"))
        "a2" -> Node(t("dt_a_${place}2"), "normal", quad("dt_a_${place}2"))
        "a3" -> Node(t("dt_a_${place}3"), "normal", quad("dt_a_${place}3"))
        "ev1", "ev2" -> {
            val n = evs[if (k == "ev1") 0 else 1]
            Node(t("dt_ev$n"), "normal", quad("dt_ev$n"))
        }
        "tk1", "tk2" -> {
            val n = tks[if (k == "tk1") 0 else 1]
            Node(t("dt_tk$n"), "normal", quad("dt_tk$n"))
        }
        "eve" -> Node(t("dt_eve"), "normal", quad("dt_eve"))
        "bye" -> Node(t("dt_bye"), "wink", quad("dt_bye"))
        else -> giftNode()
    }

    /** Четыре варианта: отлично (+2), хорошо (+1), нейтрально (0), плохо (-2). */
    private fun quad(prefix: String): List<Choice> = listOf(
        Choice(t("${prefix}_1"), 2, t("${prefix}_r1"), "happy"),
        Choice(t("${prefix}_2"), 1, t("${prefix}_r2"), "wink"),
        Choice(t("${prefix}_3"), 0, t("${prefix}_r3"), "normal"),
        Choice(t("${prefix}_4"), -2, t("${prefix}_r4"), "sad")
    )

    private fun giftDelta(g: String): Int = when (g) {
        "face" -> 2
        "plush" -> if (giftMood == "sad") 2 else 1
        "tea" -> if (giftMood == "tired") 2 else 1
        "camera" -> if (giftMood == "cheerful") 2 else 1
        "candy" -> 1
        else -> -2
    }

    private fun giftNode(): Node {
        val best = when (giftMood) { "tired" -> "tea"; "sad" -> "plush"; else -> "camera" }
        val sport = listOf("dumb", "rope").random()
        val others = listOf("face", "plush", "tea", "camera", "candy").filter { it != best }.shuffled().take(2)
        val items = listOf(sport, best) + others
        val mood = when (giftMood) { "tired" -> "normal"; "sad" -> "sad"; else -> "happy" }
        return Node(t("dt_gift_$giftMood"), mood, items.map { g ->
            val d = giftDelta(g)
            val reply = if (g == "dumb" || g == "rope") t("dt_gr_sport") else t("dt_gr_$g")
            val m = when {
                d < 0 -> "sad"
                g == "face" -> "wink"
                d >= 2 -> "happy"
                else -> "normal"
            }
            Choice(t("dt_g_$g"), d, reply, m)
        })
    }
}
