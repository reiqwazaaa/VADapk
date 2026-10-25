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
    private val girlRes: (String) -> Int,
    private val bg: IntArray,
    private val accent: Int,
    private val textColor: Int,
    private val onWin: () -> Unit
) {
    private class Choice(val label: String, val delta: Int, val reply: String, val mood: String)
    private class Node(val text: String, val mood: String, val choices: List<Choice>)

    private val max = 9
    private var doki = 3
    private var step = 0
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
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, bg)
            setPadding(dp(16), dp(28), dp(16), dp(16))
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        // шкала doki-doki
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
        girl = ImageView(ctx).apply { setImageResource(girlRes("normal")) }
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

        dlg.setContentView(root)
        dlg.show()
        showNode(nodeFor(0))
    }

    private fun setMood(m: String) {
        girl.setImageResource(girlRes(m))
        girl.animate().scaleX(1.08f).scaleY(1.08f).setDuration(110).withEndAction {
            girl.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }.start()
    }

    private fun showNode(n: Node) {
        setMood(n.mood)
        bubble.text = n.text
        choicesBox.removeAllViews()
        for (c in n.choices.shuffled()) {
            choicesBox.addView(button(c.label) { choose(c) })
        }
    }

    private fun choose(c: Choice) {
        doki = (doki + c.delta).coerceIn(0, max)
        ObjectAnimator.ofInt(meter, "progress", doki * 10).setDuration(450).start()
        floatDelta(c.delta)
        setMood(c.mood)
        bubble.text = c.reply
        choicesBox.removeAllViews()
        when {
            doki <= 0 -> {
                bubble.text = c.reply + "\n\n" + t("dt_end_fail")
                setMood("sad")
                choicesBox.addView(button(t("date_close")) { dlg.dismiss() })
            }
            step >= 4 -> {
                choicesBox.addView(button(t("date_next")) { finishDate() })
            }
            else -> {
                choicesBox.addView(button(t("date_next")) {
                    step++
                    showNode(nodeFor(step))
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

    private fun finishDate() {
        choicesBox.removeAllViews()
        if (doki >= max) {
            setMood("happy")
            bubble.text = t("dt_end_win") + "\n\n" + t("date_reward")
            onWin()
        } else {
            setMood("wink")
            bubble.text = t("dt_end_ok")
        }
        choicesBox.addView(button(t("date_close")) { dlg.dismiss() })
    }

    // ------------------------------------------------------------------ сценарий

    private fun nodeFor(i: Int): Node = when (i) {
        0 -> Node(
            t("dt_hi") + "\n\n" + t("dt_hint_$hint"), "happy",
            listOf(
                Choice(t("dt_hi_1"), 2, t("dt_hi_r1"), "happy"),
                Choice(t("dt_hi_2"), 1, t("dt_hi_r2"), "wink"),
                Choice(t("dt_hi_3"), -2, t("dt_hi_r3"), "sad")
            )
        )
        1 -> Node(
            t("dt_where"), "normal",
            places.map { p -> Choice(t("dt_pl_$p"), if (p == hint) 2 else 1, t("dt_plr_$p"), "happy") }
        ).also { }
        2 -> Node(t("dt_act_$place"), "normal", trio("dt_act_$place"))
        3 -> Node(t("dt_talk"), "normal", trio("dt_talk"))
        else -> giftNode()
    }

    /** Три варианта: хороший (+2), нейтральный (+1), плохой (-2). */
    private fun trio(prefix: String) = listOf(
        Choice(t("${prefix}_1"), 2, t("${prefix}_r1"), "happy"),
        Choice(t("${prefix}_2"), 1, t("${prefix}_r2"), "normal"),
        Choice(t("${prefix}_3"), -2, t("${prefix}_r3"), "sad")
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
