package com.vad.downloader

/** Мост к whisper.cpp (libvadwhisper.so). Если библиотека не загрузилась (например, 32-битный телефон) — [available] = false. */
object WhisperLib {
    interface Callback {
        fun onSegment(t0: Long, t1: Long, text: ByteArray)
        fun onProgress(p: Int)
    }

    val available: Boolean = try {
        System.loadLibrary("vadwhisper")
        true
    } catch (_: Throwable) {
        false
    }

    private external fun nInit(path: String): Long
    private external fun nFree(h: Long)
    private external fun nAbort(v: Boolean)
    private external fun nRun(h: Long, raw: String, lang: String, threads: Int, cb: Callback): String

    fun init(path: String): Long = nInit(path)
    fun free(h: Long) = nFree(h)
    fun abort() = nAbort(true)

    /** Распознаёт сырой PCM (float32, 16 кГц, моно). Возвращает код языка; пустая строка — ошибка или остановка. Время сегментов в сотых долях секунды. */
    fun run(h: Long, raw: String, lang: String, threads: Int, cb: Callback): String = nRun(h, raw, lang, threads, cb)
}
