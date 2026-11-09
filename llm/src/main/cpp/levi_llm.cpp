// Jembatan JNI antara aplikasi Levi (Kotlin) dan llama.cpp
// Semua teks dikirim sebagai byte UTF-8 supaya emoji & huruf non-latin aman.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <exception>
#include <string>
#include <vector>

#include "llama.h"
#include "chat.h"
#include "common.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LeviLLM", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LeviLLM", __VA_ARGS__)
#else
#define LOGI(...) do { fprintf(stderr, "[LeviLLM] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGE(...) do { fprintf(stderr, "[LeviLLM:E] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

namespace {

struct LeviEngine {
    llama_model             * model = nullptr;
    llama_context           * ctx   = nullptr;
    const llama_vocab       * vocab = nullptr;
    llama_batch_ext         * batch = nullptr;
    common_chat_templates_ptr tmpls;
    std::vector<llama_token>  cached;   // token yang sudah ada di KV cache
    std::atomic<bool>         abort{false};
    int                       n_ctx   = 0;
    int                       n_batch = 512;
};

void log_callback(enum ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN && text) {
#ifdef __ANDROID__
        __android_log_print(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "LeviLLM", "%s", text);
#else
        fputs(text, stderr);
#endif
    }
}

bool abort_callback(void * data) {
    return static_cast<LeviEngine *>(data)->abort.load();
}

void throw_java(JNIEnv * env, const std::string & msg) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, msg.c_str());
}

std::string jbytes_to_string(JNIEnv * env, jbyteArray arr) {
    if (!arr) return {};
    const jsize len = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(len), '\0');
    if (len > 0) env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

std::string jstring_to_string(JNIEnv * env, jstring js) {
    if (!js) return {};
    const char * c = env->GetStringUTFChars(js, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(js, c);
    return out;
}

// panjang awalan yang berisi karakter UTF-8 lengkap (tidak terpotong)
size_t utf8_complete_len(const std::string & s) {
    size_t i = 0, ok = 0;
    const size_t n = s.size();
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        size_t len = 1;
        if      ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        if (i + len > n) break;
        i += len;
        ok = i;
    }
    return ok;
}

// panjang akhiran `text` yang bisa jadi awal dari salah satu stop string
size_t partial_stop_len(const std::string & text, const std::vector<std::string> & stops) {
    size_t best = 0;
    for (const auto & s : stops) {
        const size_t max_k = std::min(s.size() - 1, text.size());
        for (size_t k = max_k; k > best; --k) {
            if (text.compare(text.size() - k, k, s, 0, k) == 0) {
                best = k;
                break;
            }
        }
    }
    return best;
}

std::string token_to_piece(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
    if (n >= 0) return std::string(buf, static_cast<size_t>(n));
    std::vector<char> big(static_cast<size_t>(-n));
    n = llama_token_to_piece(vocab, tok, big.data(), static_cast<int32_t>(big.size()), 0, false);
    return n > 0 ? std::string(big.data(), static_cast<size_t>(n)) : std::string();
}

struct FormattedPrompt {
    std::vector<llama_token> tokens;
    std::vector<std::string> stops;
};

FormattedPrompt format_and_tokenize(LeviEngine * eng,
                                    const std::vector<common_chat_msg> & msgs) {
    FormattedPrompt out;
    const bool add_bos = llama_vocab_get_add_bos(eng->vocab);

    common_chat_templates_inputs inputs;
    inputs.messages              = msgs;
    inputs.add_generation_prompt = true;
    inputs.enable_thinking       = false;   // jawab langsung, tanpa "berpikir" panjang (lebih cepat di HP)
    inputs.reasoning_format      = COMMON_REASONING_FORMAT_NONE;
    inputs.add_bos               = add_bos;

    common_chat_params params;
    bool ok = false;
    for (bool jinja : {true, false}) {
        inputs.use_jinja = jinja;
        try {
            params = common_chat_templates_apply(eng->tmpls.get(), inputs);
            ok = !params.prompt.parts.empty();
        } catch (const std::exception & e) {
            LOGE("template (jinja=%d) gagal: %s", jinja ? 1 : 0, e.what());
            ok = false;
        }
        if (ok) break;
    }

    if (ok) {
        bool first = true;
        for (const auto & part : params.prompt.parts) {
            if (part.text.empty()) continue;
            auto toks = common_tokenize(eng->vocab, part.text, first && add_bos, !part.is_input);
            out.tokens.insert(out.tokens.end(), toks.begin(), toks.end());
            first = false;
        }
        out.stops = params.additional_stops;
    } else {
        // cadangan terakhir: format polos
        std::string plain;
        for (const auto & m : msgs) plain += m.role + ": " + m.content + "\n";
        plain += "assistant: ";
        out.tokens = common_tokenize(eng->vocab, plain, add_bos, false);
        out.stops = {"\nuser:"};
    }
    return out;
}

// proses token prompt mulai dari n_past; true kalau sukses
int decode_prompt(LeviEngine * eng, const std::vector<llama_token> & tokens, size_t n_past) {
    for (size_t i = n_past; i < tokens.size(); i += static_cast<size_t>(eng->n_batch)) {
        const size_t end = std::min(tokens.size(), i + static_cast<size_t>(eng->n_batch));
        llama_batch_ext_clear(eng->batch);
        for (size_t j = i; j < end; ++j) {
            const int32_t idx = llama_batch_ext_add_token(eng->batch, 0, tokens[j]);
            if (idx < 0) return -100 + idx;
            const llama_pos pos = static_cast<llama_pos>(j);
            llama_batch_ext_set_pos(eng->batch, idx, &pos);
        }
        if (end == tokens.size()) {
            llama_batch_ext_set_output_logits(eng->batch, static_cast<int32_t>(end - i - 1), true);
        }
        const int ret = llama_process(eng->ctx, LLAMA_PROCESS_TYPE_DECODE, eng->batch);
        if (ret != 0) return ret;
        eng->cached.insert(eng->cached.end(), tokens.begin() + static_cast<long>(i), tokens.begin() + static_cast<long>(end));
    }
    return 0;
}

void reset_cache(LeviEngine * eng) {
    llama_memory_clear(llama_get_memory(eng->ctx), true);
    eng->cached.clear();
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_pahlevi_levi_llm_LlamaNative_init(JNIEnv *, jclass) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
}

JNIEXPORT jstring JNICALL
Java_com_pahlevi_levi_llm_LlamaNative_systemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_pahlevi_levi_llm_LlamaNative_load(JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    const std::string path = jstring_to_string(env, jpath);

    auto * eng = new LeviEngine();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    eng->model = llama_model_load_from_file(path.c_str(), mp);
    if (!eng->model) {
        delete eng;
        throw_java(env, "Gagal membuka model. File mungkin rusak atau belum selesai diunduh.");
        return 0;
    }
    eng->vocab = llama_model_get_vocab(eng->model);

    llama_context_params cp = llama_context_default_params();
    const int train_ctx = llama_model_n_ctx_train(eng->model);
    int want_ctx = n_ctx > 0 ? n_ctx : 4096;
    if (train_ctx > 0 && want_ctx > train_ctx) want_ctx = train_ctx;
    cp.n_ctx           = static_cast<uint32_t>(want_ctx);
    cp.n_batch         = static_cast<uint32_t>(eng->n_batch);
    cp.n_ubatch        = static_cast<uint32_t>(eng->n_batch);
    cp.n_threads       = n_threads > 0 ? n_threads : 4;
    cp.n_threads_batch = cp.n_threads;
    cp.abort_callback      = abort_callback;
    cp.abort_callback_data = eng;

    eng->ctx = llama_init_from_model(eng->model, cp);
    if (!eng->ctx) {
        llama_model_free(eng->model);
        delete eng;
        throw_java(env, "Memori HP tidak cukup untuk model ini. Coba model yang lebih kecil atau kurangi panjang konteks.");
        return 0;
    }
    eng->n_ctx = static_cast<int>(llama_n_ctx(eng->ctx));
    eng->batch = llama_batch_ext_init(eng->ctx);

    try {
        eng->tmpls = common_chat_templates_init(eng->model, "");
    } catch (const std::exception & e) {
        LOGE("chat template default dipakai: %s", e.what());
        eng->tmpls = common_chat_templates_init(eng->model, "chatml");
    }

    LOGI("model dimuat: ctx=%d threads=%d", eng->n_ctx, cp.n_threads);
    return reinterpret_cast<jlong>(eng);
}

JNIEXPORT void JNICALL
Java_com_pahlevi_levi_llm_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    auto * eng = reinterpret_cast<LeviEngine *>(handle);
    if (!eng) return;
    if (eng->batch) llama_batch_ext_free(eng->batch);
    eng->tmpls.reset();
    if (eng->ctx)   llama_free(eng->ctx);
    if (eng->model) llama_model_free(eng->model);
    delete eng;
}

JNIEXPORT jint JNICALL
Java_com_pahlevi_levi_llm_LlamaNative_contextSize(JNIEnv *, jclass, jlong handle) {
    auto * eng = reinterpret_cast<LeviEngine *>(handle);
    return eng ? eng->n_ctx : 0;
}

JNIEXPORT jstring JNICALL
Java_com_pahlevi_levi_llm_LlamaNative_modelDescription(JNIEnv * env, jclass, jlong handle) {
    auto * eng = reinterpret_cast<LeviEngine *>(handle);
    if (!eng) return env->NewStringUTF("");
    char buf[256] = {0};
    llama_model_desc(eng->model, buf, sizeof(buf));
    std::string desc(buf);
    // jaga-jaga: hanya ASCII supaya NewStringUTF aman
    for (auto & ch : desc) if (static_cast<unsigned char>(ch) > 127) ch = '?';
    return env->NewStringUTF(desc.c_str());
}

JNIEXPORT jint JNICALL
Java_com_pahlevi_levi_llm_LlamaNative_countTokens(JNIEnv * env, jclass, jlong handle, jbyteArray jtext) {
    auto * eng = reinterpret_cast<LeviEngine *>(handle);
    if (!eng) return 0;
    const std::string text = jbytes_to_string(env, jtext);
    return static_cast<jint>(common_tokenize(eng->vocab, text, false, false).size());
}

JNIEXPORT jint JNICALL
Java_com_pahlevi_levi_llm_LlamaNative_promptTokens(JNIEnv * env, jclass, jlong handle,
                                                   jobjectArray jroles, jobjectArray jcontents) {
    auto * eng = reinterpret_cast<LeviEngine *>(handle);
    if (!eng) return 0;
    std::vector<common_chat_msg> msgs;
    const jsize n = env->GetArrayLength(jroles);
    for (jsize i = 0; i < n; ++i) {
        common_chat_msg m;
        auto jr = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
        auto jc = static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
        m.role    = jstring_to_string(env, jr);
        m.content = jbytes_to_string(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        msgs.push_back(std::move(m));
    }
    return static_cast<jint>(format_and_tokenize(eng, msgs).tokens.size());
}

JNIEXPORT void JNICALL
Java_com_pahlevi_levi_llm_LlamaNative_stop(JNIEnv *, jclass, jlong handle) {
    auto * eng = reinterpret_cast<LeviEngine *>(handle);
    if (eng) eng->abort.store(true);
}

JNIEXPORT void JNICALL
Java_com_pahlevi_levi_llm_LlamaNative_resetCache(JNIEnv *, jclass, jlong handle) {
    auto * eng = reinterpret_cast<LeviEngine *>(handle);
    if (eng) reset_cache(eng);
}

JNIEXPORT jint JNICALL
Java_com_pahlevi_levi_llm_LlamaNative_generate(JNIEnv * env, jclass, jlong handle,
                                               jobjectArray jroles, jobjectArray jcontents,
                                               jint max_tokens, jfloat temp, jfloat top_p, jint top_k,
                                               jfloat repeat_penalty, jobject callback) {
    auto * eng = reinterpret_cast<LeviEngine *>(handle);
    if (!eng) {
        throw_java(env, "Model belum dimuat");
        return 0;
    }
    eng->abort.store(false);

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_cls, "onToken", "([B)Z");
    if (!on_token) {
        throw_java(env, "Callback tidak valid");
        return 0;
    }

    // ---- susun pesan ----
    std::vector<common_chat_msg> msgs;
    const jsize n_msgs = env->GetArrayLength(jroles);
    for (jsize i = 0; i < n_msgs; ++i) {
        common_chat_msg m;
        auto jr = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
        auto jc = static_cast<jbyteArray>(env->GetObjectArrayElement(jcontents, i));
        m.role    = jstring_to_string(env, jr);
        m.content = jbytes_to_string(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        msgs.push_back(std::move(m));
    }

    FormattedPrompt fp;
    try {
        fp = format_and_tokenize(eng, msgs);
    } catch (const std::exception & e) {
        throw_java(env, std::string("Gagal menyusun prompt: ") + e.what());
        return 0;
    }
    const auto & tokens = fp.tokens;
    if (tokens.empty()) {
        throw_java(env, "Prompt kosong");
        return 0;
    }
    if (static_cast<int>(tokens.size()) + 16 >= eng->n_ctx) {
        throw_java(env, "CONTEXT_FULL:" + std::to_string(tokens.size()) + "/" + std::to_string(eng->n_ctx));
        return 0;
    }

    // ---- pakai ulang KV cache untuk awalan yang sama ----
    size_t n_past = 0;
    while (n_past < eng->cached.size() && n_past < tokens.size() && eng->cached[n_past] == tokens[n_past]) {
        ++n_past;
    }
    if (n_past == tokens.size()) --n_past; // minimal 1 token diproses ulang untuk dapat logits
    if (n_past < eng->cached.size()) {
        if (!llama_memory_seq_rm(llama_get_memory(eng->ctx), 0, static_cast<llama_pos>(n_past), -1)) {
            reset_cache(eng);
            n_past = 0;
        } else {
            eng->cached.resize(n_past);
        }
    }

    int ret = decode_prompt(eng, tokens, n_past);
    if (ret != 0) {
        reset_cache(eng);
        if (eng->abort.load()) return 0;
        // coba sekali lagi dari awal (misal cache tidak konsisten)
        ret = decode_prompt(eng, tokens, 0);
        if (ret != 0) {
            reset_cache(eng);
            if (eng->abort.load()) return 0;
            throw_java(env, "Gagal memproses prompt (kode " + std::to_string(ret) + ")");
            return 0;
        }
    }

    // ---- sampler ----
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (repeat_penalty > 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(eng->vocab), 64, repeat_penalty, 0.0f, 0.0f));
    }
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p > 0.0f ? top_p : 0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // ---- generate ----
    std::string full;     // semua teks yang dihasilkan
    size_t emitted = 0;   // berapa byte dari `full` yang sudah dikirim ke Kotlin
    int n_gen = 0;
    bool stopped_by_string = false;
    llama_pos pos = static_cast<llama_pos>(tokens.size());

    auto emit = [&](size_t upto) -> bool {
        if (upto <= emitted) return true;
        const size_t len = upto - emitted;
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(full.data() + emitted));
        const jboolean keep = env->CallBooleanMethod(callback, on_token, arr);
        env->DeleteLocalRef(arr);
        emitted = upto;
        if (env->ExceptionCheck()) return false;
        return keep == JNI_TRUE;
    };

    const int limit = max_tokens > 0 ? max_tokens : 1024;
    while (n_gen < limit && !eng->abort.load()) {
        if (pos + 1 >= eng->n_ctx) break;

        const llama_token tok = llama_sampler_sample(smpl, eng->ctx, -1);
        if (llama_vocab_is_eog(eng->vocab, tok)) break;

        full += token_to_piece(eng->vocab, tok);
        ++n_gen;

        // cek stop string tambahan dari template
        for (const auto & s : fp.stops) {
            if (s.empty()) continue;
            const size_t found = full.find(s, emitted > s.size() ? emitted - s.size() : 0);
            if (found != std::string::npos) {
                full.resize(found);
                stopped_by_string = true;
                break;
            }
        }
        if (stopped_by_string) break;

        // tahan teks yang mungkin awal dari stop string, dan karakter UTF-8 yang belum lengkap
        const size_t limit_bytes = full.size() - partial_stop_len(full, fp.stops);
        size_t safe = emitted;
        if (limit_bytes > emitted) {
            safe = emitted + utf8_complete_len(full.substr(emitted, limit_bytes - emitted));
        }
        if (!emit(safe)) {
            eng->abort.store(true);
            break;
        }

        // masukkan token yang baru ke model
        llama_batch_ext_clear(eng->batch);
        const int32_t idx = llama_batch_ext_add_token(eng->batch, 0, tok);
        llama_batch_ext_set_pos(eng->batch, idx, &pos);
        llama_batch_ext_set_output_logits(eng->batch, idx, true);
        ret = llama_process(eng->ctx, LLAMA_PROCESS_TYPE_DECODE, eng->batch);
        if (ret != 0) {
            // posisi cache tidak lagi pasti: mulai bersih di permintaan berikutnya
            reset_cache(eng);
            break;
        }
        eng->cached.push_back(tok);
        ++pos;
    }

    llama_sampler_free(smpl);

    if (!env->ExceptionCheck()) {
        emit(full.size());
    }
    if (eng->abort.load()) {
        // KV cache bisa setengah jadi kalau dihentikan di tengah proses
        if (static_cast<llama_pos>(eng->cached.size()) != pos) reset_cache(eng);
    }
    return n_gen;
}

} // extern "C"
