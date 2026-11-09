// Jembatan JNI antara aplikasi Levi (Kotlin) dan whisper.cpp (suara -> teks)

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LeviWhisper", __VA_ARGS__)
#else
#define LOGE(...) do { fprintf(stderr, "[LeviWhisper:E] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

namespace {

struct LeviWhisper {
    whisper_context * ctx = nullptr;
    std::atomic<bool> abort{false};
};

struct CallbackData {
    JNIEnv     * env = nullptr;
    jobject      callback = nullptr;
    jmethodID    on_segment = nullptr;
    jmethodID    on_progress = nullptr;
    LeviWhisper * w = nullptr;
    int64_t      offset_ms = 0;
    int          chunk_index = 0;
    int          n_chunks = 1;
    int          last_progress = -1;
};

void throw_java(JNIEnv * env, const std::string & msg) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, msg.c_str());
}

std::string jstring_to_string(JNIEnv * env, jstring js) {
    if (!js) return {};
    const char * c = env->GetStringUTFChars(js, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(js, c);
    return out;
}

void log_callback(enum ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_ERROR && text) LOGE("%s", text);
}

// ---- pembaca WAV PCM 16-bit sederhana ----
struct WavInfo {
    uint32_t sample_rate = 0;
    uint16_t channels = 0;
    uint16_t bits = 0;
    long     data_offset = 0;
    uint32_t data_size = 0;
};

bool read_u32(FILE * f, uint32_t & v) { unsigned char b[4]; if (fread(b, 1, 4, f) != 4) return false; v = b[0] | (b[1] << 8) | (b[2] << 16) | (static_cast<uint32_t>(b[3]) << 24); return true; }
bool read_u16(FILE * f, uint16_t & v) { unsigned char b[2]; if (fread(b, 1, 2, f) != 2) return false; v = static_cast<uint16_t>(b[0] | (b[1] << 8)); return true; }

bool parse_wav(FILE * f, WavInfo & info) {
    char id[4];
    uint32_t size;
    if (fread(id, 1, 4, f) != 4 || memcmp(id, "RIFF", 4) != 0) return false;
    if (!read_u32(f, size)) return false;
    if (fread(id, 1, 4, f) != 4 || memcmp(id, "WAVE", 4) != 0) return false;
    bool have_fmt = false;
    while (fread(id, 1, 4, f) == 4) {
        if (!read_u32(f, size)) return false;
        if (memcmp(id, "fmt ", 4) == 0) {
            uint16_t fmt;
            uint32_t byte_rate;
            uint16_t block_align;
            if (!read_u16(f, fmt) || !read_u16(f, info.channels) || !read_u32(f, info.sample_rate) ||
                !read_u32(f, byte_rate) || !read_u16(f, block_align) || !read_u16(f, info.bits)) return false;
            if (fmt != 1) return false; // hanya PCM
            if (size > 16) fseek(f, static_cast<long>(size - 16), SEEK_CUR);
            have_fmt = true;
        } else if (memcmp(id, "data", 4) == 0) {
            info.data_offset = ftell(f);
            info.data_size = size;
            // rekaman yang terhenti mendadak kadang ukuran data-nya 0/salah: hitung dari ukuran file
            fseek(f, 0, SEEK_END);
            const long file_end = ftell(f);
            const long real = file_end - info.data_offset;
            if (info.data_size == 0 || info.data_size == 0xFFFFFFFFu || static_cast<long>(info.data_size) > real) {
                info.data_size = static_cast<uint32_t>(std::max(0L, real));
            }
            fseek(f, info.data_offset, SEEK_SET);
            return have_fmt;
        } else {
            fseek(f, static_cast<long>(size + (size & 1)), SEEK_CUR);
        }
    }
    return false;
}

void on_new_segment(whisper_context * ctx, whisper_state *, int n_new, void * user_data) {
    auto * cb = static_cast<CallbackData *>(user_data);
    JNIEnv * env = cb->env;
    const int n = whisper_full_n_segments(ctx);
    for (int i = std::max(0, n - n_new); i < n; ++i) {
        const char * text = whisper_full_get_segment_text(ctx, i);
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10 + cb->offset_ms;
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10 + cb->offset_ms;
        const size_t len = text ? strlen(text) : 0;
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
        if (len > 0) env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(text));
        const jboolean keep = env->CallBooleanMethod(cb->callback, cb->on_segment, static_cast<jlong>(t0), static_cast<jlong>(t1), arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck() || keep != JNI_TRUE) cb->w->abort.store(true);
    }
}

void on_progress(whisper_context *, whisper_state *, int progress, void * user_data) {
    auto * cb = static_cast<CallbackData *>(user_data);
    const int overall = (cb->chunk_index * 100 + std::clamp(progress, 0, 100)) / std::max(1, cb->n_chunks);
    if (overall == cb->last_progress) return;
    cb->last_progress = overall;
    cb->env->CallVoidMethod(cb->callback, cb->on_progress, static_cast<jint>(overall));
    if (cb->env->ExceptionCheck()) cb->w->abort.store(true);
}

bool abort_cb(void * data) {
    return static_cast<LeviWhisper *>(data)->abort.load();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_pahlevi_levi_whisper_WhisperNative_load(JNIEnv * env, jclass, jstring jpath) {
    whisper_log_set(log_callback, nullptr);
    const std::string path = jstring_to_string(env, jpath);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cp);
    if (!ctx) {
        throw_java(env, "Gagal membuka model suara. File mungkin rusak atau belum selesai diunduh.");
        return 0;
    }
    auto * w = new LeviWhisper();
    w->ctx = ctx;
    return reinterpret_cast<jlong>(w);
}

JNIEXPORT void JNICALL
Java_com_pahlevi_levi_whisper_WhisperNative_free(JNIEnv *, jclass, jlong handle) {
    auto * w = reinterpret_cast<LeviWhisper *>(handle);
    if (!w) return;
    if (w->ctx) whisper_free(w->ctx);
    delete w;
}

JNIEXPORT void JNICALL
Java_com_pahlevi_levi_whisper_WhisperNative_stop(JNIEnv *, jclass, jlong handle) {
    auto * w = reinterpret_cast<LeviWhisper *>(handle);
    if (w) w->abort.store(true);
}

JNIEXPORT jstring JNICALL
Java_com_pahlevi_levi_whisper_WhisperNative_systemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

// Transkrip file WAV 16 kHz PCM 16-bit. Diproses per potongan supaya rekaman berjam-jam tetap muat di memori.
JNIEXPORT jint JNICALL
Java_com_pahlevi_levi_whisper_WhisperNative_transcribeWav(JNIEnv * env, jclass, jlong handle, jstring jwav,
                                                          jstring jlang, jint n_threads, jint start_ms,
                                                          jobject callback) {
    auto * w = reinterpret_cast<LeviWhisper *>(handle);
    if (!w) {
        throw_java(env, "Model suara belum dimuat");
        return 0;
    }
    w->abort.store(false);

    const std::string wav_path = jstring_to_string(env, jwav);
    const std::string lang = jstring_to_string(env, jlang);

    FILE * f = fopen(wav_path.c_str(), "rb");
    if (!f) {
        throw_java(env, "File rekaman tidak bisa dibuka");
        return 0;
    }
    WavInfo info;
    if (!parse_wav(f, info) || info.bits != 16 || info.channels < 1) {
        fclose(f);
        throw_java(env, "Format audio tidak didukung (harus WAV PCM 16-bit)");
        return 0;
    }
    if (info.sample_rate != WHISPER_SAMPLE_RATE) {
        fclose(f);
        throw_java(env, "Audio harus 16000 Hz, file ini " + std::to_string(info.sample_rate) + " Hz");
        return 0;
    }

    CallbackData cb;
    cb.env = env;
    cb.callback = callback;
    cb.w = w;
    jclass cls = env->GetObjectClass(callback);
    cb.on_segment = env->GetMethodID(cls, "onSegment", "(JJ[B)Z");
    cb.on_progress = env->GetMethodID(cls, "onProgress", "(I)V");
    if (!cb.on_segment || !cb.on_progress) {
        fclose(f);
        throw_java(env, "Callback tidak valid");
        return 0;
    }

    const uint32_t frame_bytes = 2u * info.channels;
    const uint64_t total_frames = info.data_size / frame_bytes;
    const uint64_t start_frame = std::min<uint64_t>(total_frames, static_cast<uint64_t>(std::max(0, static_cast<int>(start_ms))) * WHISPER_SAMPLE_RATE / 1000);
    const uint64_t chunk_frames = static_cast<uint64_t>(WHISPER_SAMPLE_RATE) * 60 * 5; // 5 menit per potongan
    const uint64_t remaining = total_frames - start_frame;
    cb.n_chunks = static_cast<int>(std::max<uint64_t>(1, (remaining + chunk_frames - 1) / chunk_frames));

    fseek(f, info.data_offset + static_cast<long>(start_frame * frame_bytes), SEEK_SET);

    std::vector<int16_t> raw;
    std::vector<float> pcm;
    int total_segments = 0;

    for (uint64_t done = 0; done < remaining && !w->abort.load(); done += chunk_frames) {
        const uint64_t frames = std::min(chunk_frames, remaining - done);
        raw.resize(static_cast<size_t>(frames * info.channels));
        const size_t got = fread(raw.data(), frame_bytes, static_cast<size_t>(frames), f);
        if (got == 0) break;
        pcm.resize(got);
        for (size_t i = 0; i < got; ++i) {
            int32_t acc = 0;
            for (uint16_t c = 0; c < info.channels; ++c) acc += raw[i * info.channels + c];
            pcm[i] = static_cast<float>(acc) / (32768.0f * static_cast<float>(info.channels));
        }
        // potongan < 1 detik di akhir dilewati (biasanya hanya hening)
        if (got < WHISPER_SAMPLE_RATE && done > 0) break;

        cb.offset_ms = static_cast<int64_t>((start_frame + done) * 1000 / WHISPER_SAMPLE_RATE);

        whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        wp.n_threads        = n_threads > 0 ? n_threads : 4;
        wp.print_progress   = false;
        wp.print_realtime   = false;
        wp.print_special    = false;
        wp.print_timestamps = false;
        wp.translate        = false;
        wp.no_context       = false;
        wp.single_segment   = false;
        wp.suppress_nst     = true;
        wp.language         = lang.empty() ? "auto" : lang.c_str();
        wp.detect_language  = false;
        wp.new_segment_callback           = on_new_segment;
        wp.new_segment_callback_user_data = &cb;
        wp.progress_callback              = on_progress;
        wp.progress_callback_user_data    = &cb;
        wp.abort_callback                 = abort_cb;
        wp.abort_callback_user_data       = w;

        const int ret = whisper_full(w->ctx, wp, pcm.data(), static_cast<int>(pcm.size()));
        if (env->ExceptionCheck()) break;
        if (ret != 0 && !w->abort.load()) {
            fclose(f);
            throw_java(env, "Transkripsi gagal (kode " + std::to_string(ret) + ")");
            return total_segments;
        }
        total_segments += whisper_full_n_segments(w->ctx);
        cb.chunk_index++;
    }
    fclose(f);
    return total_segments;
}

} // extern "C"
