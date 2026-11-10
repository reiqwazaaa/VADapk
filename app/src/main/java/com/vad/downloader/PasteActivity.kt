package com.vad.downloader

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Невидимое окно для кнопки виджета: Android разрешает читать буфер обмена только окну в фокусе,
 * поэтому на долю секунды открываем прозрачную активность, берём ссылку и сразу закрываемся.
 * Если приложение уже запущено — ссылка передаётся ему без показа интерфейса.
 */
class PasteActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Strings.init(applicationContext)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val text = try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString().orEmpty() else ""
        } catch (_: Throwable) {
            ""
        }
        val link = Regex("https?://\\S+").find(text)?.value
        if (link == null) {
            Toast.makeText(this, Strings.t("clip_empty"), Toast.LENGTH_SHORT).show()
        } else {
            val main = MainActivity.instance
            if (main != null) {
                main.runOnUiThread { main.pasteUrl(link, false) }
            } else {
                startActivity(
                    Intent(this, MainActivity::class.java).setAction(VadWidget.ACTION_PASTE)
                        .putExtra("url", link).putExtra("bg", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
