package com.vad.downloader

import android.content.SharedPreferences

/** Цвета текущей темы приложения (по настройкам в prefs) — для виджета и экрана расшифровки. */
object AppTheme {
    class Colors(
        val bg: IntArray, val btn: IntArray, val text: Int, val btnText: Int, val accent: Int,
        val track: Int, val dark: Boolean,
        /** 0 обычная, 1 ночная, 2.. палитры — номер набора рисунков виджета */
        val idx: Int
    )

    private class Pal(val need: Int, val bg: IntArray, val btn: IntArray, val accent: Int, val flag: String = "")

    // Копия палитр из MainActivity: (нужно побед, фон, кнопка, акцент, флаг награды)
    private val pals = listOf(
        Pal(0, intArrayOf(0xFFFFC1E3.toInt(), 0xFFE0C3FF.toInt(), 0xFFBDE7FF.toInt()), intArrayOf(0xFFFF9ACB.toInt(), 0xFFFFC2E5.toInt()), 0xFFFF5FA2.toInt()),
        Pal(1, intArrayOf(0xFFC9FFE5.toInt(), 0xFFBDEBFF.toInt(), 0xFFE8FFD0.toInt()), intArrayOf(0xFF5EDDB0.toInt(), 0xFFA8F5D6.toInt()), 0xFF22B58A.toInt()),
        Pal(3, intArrayOf(0xFFFFD3A8.toInt(), 0xFFFFB1C1.toInt(), 0xFFE7B6FF.toInt()), intArrayOf(0xFFFF9966.toInt(), 0xFFFFC48A.toInt()), 0xFFFF7043.toInt()),
        Pal(5, intArrayOf(0xFFE4D6FF.toInt(), 0xFFCDBDFF.toInt(), 0xFFF7D6FF.toInt()), intArrayOf(0xFFA987F0.toInt(), 0xFFCDB8FF.toInt()), 0xFF8A5CF0.toInt()),
        Pal(10, intArrayOf(0xFFFFF3A8.toInt(), 0xFFFFD6A5.toInt(), 0xFFFFB5D8.toInt()), intArrayOf(0xFFFFC93C.toInt(), 0xFFFFE59A.toInt()), 0xFFFF9F1C.toInt()),
        Pal(0, intArrayOf(0xFFF4F4F4.toInt(), 0xFFD6D6D6.toInt(), 0xFFFAFAFA.toInt()), intArrayOf(0xFF9E9E9E.toInt(), 0xFFD0D0D0.toInt()), 0xFF212121.toInt(), "mono_theme")
    )

    fun of(p: SharedPreferences): Colors {
        val th = p.getInt("theme", 0)
        if (th == 0 || !p.getBoolean("kawaii_unlocked", false)) {
            return Colors(intArrayOf(0xFF3A3A3A.toInt(), 0xFF262626.toInt()), intArrayOf(0xFF4A90D9.toInt(), 0xFF3B78B8.toInt()),
                0xFFEEEEEE.toInt(), 0xFFFFFFFF.toInt(), 0xFF4A90D9.toInt(), 0x33FFFFFF, true, 0)
        }
        if (th == 2) {
            return Colors(intArrayOf(0xFF07050F.toInt(), 0xFF110B22.toInt(), 0xFF060D1A.toInt()), intArrayOf(0xFF3A2468.toInt(), 0xFF4D3188.toInt()),
                0xFFEBDDFF.toInt(), 0xFFEBDDFF.toInt(), 0xFFB388FF.toInt(), 0x33FFFFFF, true, 1)
        }
        var i = p.getInt("palette", 0).coerceIn(0, pals.size - 1)
        var pl = pals[i]
        val open = if (pl.flag.isNotEmpty()) p.getBoolean(pl.flag, false) else p.getInt("wins", 0) >= pl.need
        if (!open) { i = 0; pl = pals[0] }
        val txt = if (pl.flag == "mono_theme") 0xFF222222.toInt() else 0xFF6A2C6B.toInt()
        return Colors(pl.bg, pl.btn, txt, txt, pl.accent, 0x44FFFFFF, false, 2 + i)
    }
}
