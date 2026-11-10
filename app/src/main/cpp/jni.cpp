#include <jni.h>
#include <atomic>
#include <string>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include "whisper.h"

// Тонкая обёртка над whisper.cpp для WhisperLib.kt.

static std::atomic<bool> g_abort{false};

struct CbCtx {
    JNIEnv *env;
    jobject cb;
    jmethodID onSeg;
    jmethodID onProg;
};

static void on_new_segment(struct whisper_context *ctx, struct whisper_state *, int n_new, void *ud) {
    CbCtx *c = static_cast<CbCtx *>(ud);
    int n = whisper_full_n_segments(ctx);
    for (int i = n - n_new; i < n; i++) {
        if (i < 0) continue;
        const char *txt = whisper_full_get_segment_text(ctx, i);
        if (!txt) txt = "";
        // Передаём байты, а не строку: в тексте может оказаться обрезанный UTF-8 (NewStringUTF на нём падает)
        jsize len = (jsize) std::char_traits<char>::length(txt);
        jbyteArray arr = c->env->NewByteArray(len);
        c->env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(txt));
        c->env->CallVoidMethod(c->cb, c->onSeg,
                               (jlong) whisper_full_get_segment_t0(ctx, i),
                               (jlong) whisper_full_get_segment_t1(ctx, i), arr);
        c->env->DeleteLocalRef(arr);
    }
}

static void on_progress(struct whisper_context *, struct whisper_state *, int progress, void *ud) {
    CbCtx *c = static_cast<CbCtx *>(ud);
    c->env->CallVoidMethod(c->cb, c->onProg, (jint) progress);
}

static bool on_abort(void *) { return g_abort.load(); }

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_vad_downloader_WhisperLib_nInit(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *c = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return (jlong) c;
}

JNIEXPORT void JNICALL
Java_com_vad_downloader_WhisperLib_nFree(JNIEnv *, jclass, jlong h) {
    if (h) whisper_free((whisper_context *) h);
}

JNIEXPORT void JNICALL
Java_com_vad_downloader_WhisperLib_nAbort(JNIEnv *, jclass, jboolean v) {
    g_abort.store(v);
}

// Возвращает код определённого языка ("ja") или пустую строку при ошибке.
JNIEXPORT jstring JNICALL
Java_com_vad_downloader_WhisperLib_nRun(JNIEnv *env, jclass, jlong h, jstring rawPath, jstring lang,
                                       jint threads, jobject cb) {
    whisper_context *ctx = (whisper_context *) h;
    if (!ctx) return env->NewStringUTF("");
    g_abort.store(false);

    const char *rp = env->GetStringUTFChars(rawPath, nullptr);
    int fd = open(rp, O_RDONLY);
    env->ReleaseStringUTFChars(rawPath, rp);
    if (fd < 0) return env->NewStringUTF("");
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < (off_t) sizeof(float)) { close(fd); return env->NewStringUTF(""); }
    // mmap: часовая запись в float — сотни МБ, в память целиком не читаем
    void *map = mmap(nullptr, (size_t) st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (map == MAP_FAILED) { close(fd); return env->NewStringUTF(""); }
    int n_samples = (int) (st.st_size / sizeof(float));

    jclass k = env->GetObjectClass(cb);
    CbCtx cc{env, cb, env->GetMethodID(k, "onSegment", "(JJ[B)V"), env->GetMethodID(k, "onProgress", "(I)V")};

    const char *lg = env->GetStringUTFChars(lang, nullptr);
    std::string lgs(lg);
    env->ReleaseStringUTFChars(lang, lg);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads;
    wp.language = lgs.c_str();     // "auto" — определить по звуку
    wp.translate = false;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.greedy.best_of = 1;
    wp.new_segment_callback = on_new_segment;
    wp.new_segment_callback_user_data = &cc;
    wp.progress_callback = on_progress;
    wp.progress_callback_user_data = &cc;
    wp.abort_callback = on_abort;
    wp.abort_callback_user_data = nullptr;

    int rc = whisper_full(ctx, wp, (const float *) map, n_samples);
    munmap(map, (size_t) st.st_size);
    close(fd);
    if (rc != 0) return env->NewStringUTF("");
    int lid = whisper_full_lang_id(ctx);
    const char *ls = lid >= 0 ? whisper_lang_str(lid) : "";
    return env->NewStringUTF(ls ? ls : "");
}

}
