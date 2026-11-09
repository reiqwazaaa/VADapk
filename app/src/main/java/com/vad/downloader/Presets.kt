package com.vad.downloader

import org.json.JSONObject

/** Готовые варианты скачивания: заменяют отдельные списки «качество / звук / субтитры». */
object DlPresets {
    class Preset(val key: String, val kind: String, val fmt: String, val afmt: String = "", val subs: String = "", val gif: Boolean = false)

    private fun v(h: Int) =
        if (h == 0) "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
        else "bestvideo[height<=$h][ext=mp4]+bestaudio[ext=m4a]/best[height<=$h][ext=mp4]/best"

    val all = listOf(
        Preset("pr_best", "video", v(0)),
        Preset("pr_fhd", "video", v(1080)),
        Preset("pr_hd", "video", v(720)),
        Preset("pr_small", "video", v(480)),
        Preset("pr_subs", "video", v(720), subs = "embed"),
        Preset("pr_mp3", "audio", "", "mp3:320K"),
        Preset("pr_mp3s", "audio", "", "mp3:128K"),
        Preset("pr_m4a", "audio", "", "m4a"),
        Preset("pr_flac", "audio", "", "flac"),
        Preset("pr_gif", "video", v(480), gif = true)
    )
}

/** Подбирает понятную подсказку по тексту ошибки yt-dlp. Возвращает ключ строки или null. */
object ErrorHints {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("members-only|members only|channel's members|Join this channel", RegexOption.IGNORE_CASE) to "err_members",
        Regex("private video|confirm your age|age-restricted|age restricted", RegexOption.IGNORE_CASE) to "err_private",
        Regex("not a bot", RegexOption.IGNORE_CASE) to "err_bot",
        Regex("sign in|log in|login required|Precondition|authentication", RegexOption.IGNORE_CASE) to "err_login",
        Regex("HTTP Error 429|Too Many Requests", RegexOption.IGNORE_CASE) to "err_429",
        Regex("not available in your country|blocked it in your country|geo.?restrict|georestrict", RegexOption.IGNORE_CASE) to "err_geo",
        Regex("HTTP Error 403|Forbidden", RegexOption.IGNORE_CASE) to "err_403",
        Regex("Unsupported URL", RegexOption.IGNORE_CASE) to "err_unsupported",
        Regex("Requested format is not available", RegexOption.IGNORE_CASE) to "err_format",
        Regex("live event will begin|Premieres in|is upcoming|will begin in", RegexOption.IGNORE_CASE) to "err_live",
        Regex("copyright", RegexOption.IGNORE_CASE) to "err_copyright",
        Regex("video unavailable|is unavailable|has been removed|no longer available|been deleted|HTTP Error 404|does not exist", RegexOption.IGNORE_CASE) to "err_gone",
        Regex("No space left|ENOSPC", RegexOption.IGNORE_CASE) to "err_space",
        Regex("ffmpeg|ffprobe", RegexOption.IGNORE_CASE) to "err_ffmpeg",
        Regex("download.sections|force.keyframes", RegexOption.IGNORE_CASE) to "err_trim",
        Regex("HTTP Error 5\\d\\d|Incomplete data|server error", RegexOption.IGNORE_CASE) to "err_server",
        Regex("Unable to download webpage|Temporary failure|Name or service not known|timed out|Connection reset|Network is unreachable|getaddrinfo|SSL|Unable to connect|Connection refused", RegexOption.IGNORE_CASE) to "err_net"
    )

    fun key(raw: String): String? = rules.firstOrNull { it.first.containsMatchIn(raw) }?.second
}

/** Подписка на канал/плейлист: докачивает только новые видео (архив yt-dlp помнит скачанное). */
class Sub(val id: String, val url: String, val name: String, val mode: String, val n: Int, val since: String, var first: Boolean, var last: Long, val spec: JSONObject) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("url", url).put("name", name).put("mode", mode).put("n", n)
        .put("since", since).put("first", first).put("last", last).put("spec", spec)

    companion object {
        fun from(o: JSONObject) = Sub(
            o.optString("id"), o.optString("url"), o.optString("name"), o.optString("mode", "new"), o.optInt("n", 3),
            o.optString("since"), o.optBoolean("first", true), o.optLong("last", 0L), o.optJSONObject("spec") ?: JSONObject()
        )
    }
}
