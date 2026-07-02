#include <jni.h>
#include <android/log.h>
#include <string>
#include <atomic>
#include <mutex>
#include <thread>
#include <chrono>
#include <unistd.h>
#include <algorithm>
#include <sys/system_properties.h>
#include <dlfcn.h>

#include "llama.h"
#include "ggml-backend.h"
// clip.h / llava.h removed in llama.cpp b9045 (moved to tools/mtmd); multimodal stubbed
#include <vector>
#include <unordered_map>
#include <sstream>

#define TAG "PocketNode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Returns the length of the longest prefix of `buf` that consists entirely of
// complete, valid UTF-8 sequences. Any trailing bytes that are either an
// incomplete multi-byte sequence (needs more bytes from the next token) or
// an outright invalid lead/continuation byte are left out of the prefix.
//
// P28 Phase 2: llama_token_to_piece() can return a piece whose bytes split a
// multi-byte UTF-8 character across two tokens. Passing such a partial
// sequence straight to NewStringUTF() aborts the JVM ("JNI DETECTED ERROR IN
// APPLICATION: input is not valid Modified UTF-8"). Buffering token bytes and
// only emitting this valid prefix — see nativeGenerate's emit_token — avoids
// that abort without changing the text ultimately delivered to callers.
static size_t utf8_valid_prefix_len(const std::string &buf) {
    const unsigned char *data = reinterpret_cast<const unsigned char *>(buf.data());
    size_t len = buf.size();
    size_t i = 0, last_complete = 0;
    while (i < len) {
        unsigned char c = data[i];
        size_t seq_len;
        if ((c & 0x80) == 0x00)      seq_len = 1;
        else if ((c & 0xE0) == 0xC0) seq_len = 2;
        else if ((c & 0xF0) == 0xE0) seq_len = 3;
        else if ((c & 0xF8) == 0xF0) seq_len = 4;
        else break; // invalid lead byte — stop; caller decides how to resync
        if (i + seq_len > len) break; // incomplete sequence at end of buffer
        bool valid = true;
        for (size_t k = 1; k < seq_len; k++) {
            if ((data[i + k] & 0xC0) != 0x80) { valid = false; break; }
        }
        if (!valid) break;
        i += seq_len;
        last_complete = i;
    }
    return last_complete;
}

// Inline helpers replacing common_batch_add / common_batch_clear
static void batch_add(llama_batch &batch, llama_token id, llama_pos pos,
                      const std::vector<llama_seq_id> &seq_ids, bool logits) {
    batch.token   [batch.n_tokens] = id;
    batch.pos     [batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = (int32_t)seq_ids.size();
    for (size_t i = 0; i < seq_ids.size(); i++) {
        batch.seq_id[batch.n_tokens][i] = seq_ids[i];
    }
    batch.logits[batch.n_tokens] = logits ? 1 : 0;
    batch.n_tokens++;
}

static void batch_clear(llama_batch &batch) {
    batch.n_tokens = 0;
}

// Global stop flag per context (one active generation at a time)
static std::atomic<bool> g_stop_generation{false};

// Mutex protecting inference state — prevents concurrent nativeGenerate calls
// from the UI and the Edge API service.
static std::mutex g_inference_mutex;

// Thread-local last error string, readable via nativeGetLastError()
static thread_local std::string g_last_error;

// Track n_past per context
static std::unordered_map<llama_context*, int> g_n_past;

// llama_backend_init() is deferred to nativeLoadModel (background thread) to
// avoid blocking the main thread while the OpenCL backend compiles kernels.
static std::once_flag g_backend_init_flag;

static std::string backend_names() {
    std::vector<std::string> names;
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        if (!reg) continue;

        const char *name = ggml_backend_reg_name(reg);
        if (name && std::string(name) != "CPU") {
            names.emplace_back(name);
        }
    }

    if (names.empty()) {
        return "CPU";
    }

    std::ostringstream out;
    for (size_t i = 0; i < names.size(); ++i) {
        if (i > 0) out << ",";
        out << names[i];
    }
    return out.str();
}

static std::string system_property(const char *name) {
    char value[PROP_VALUE_MAX] = {0};
    __system_property_get(name, value);
    return std::string(value);
}

extern "C" {

// =========================================================================
// Initialization
// =========================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad called, initializing llama backend");

    // Attempt to pre-load libOpenCL.so from common locations to ensure it's available for the process.
    // This is often necessary on Android where libOpenCL.so is not in the default search path for apps.
    // RTLD_GLOBAL makes the symbols available to subsequently loaded libraries (like ggml-opencl).
    void* handle = dlopen("libOpenCL.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) handle = dlopen("/vendor/lib64/libOpenCL.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) handle = dlopen("/system/vendor/lib64/libOpenCL.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) handle = dlopen("/system/lib64/libOpenCL.so", RTLD_NOW | RTLD_GLOBAL);

    if (handle) {
        LOGI("OpenCL library pre-loaded");
    } else {
        LOGI("OpenCL library not found; GPU acceleration may fail");
    }
    // llama_backend_init() deferred to nativeLoadModel — OpenCL kernel compilation
    // can take 30-60s on first run and would ANR if called here on the main thread.
    return JNI_VERSION_1_6;
}

// =========================================================================
// Error reporting
// =========================================================================

JNIEXPORT jstring JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGetLastError(
        JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF(g_last_error.c_str());
}

// =========================================================================
// Backend query and Metadata
// =========================================================================

JNIEXPORT jstring JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGetBackendName(
        JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF(backend_names().c_str());
}

JNIEXPORT jobjectArray JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGetModelMetadata(
        JNIEnv *env, jobject /* this */, jlong ctx_ptr) {
    if (ctx_ptr == 0) return nullptr;
    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    const llama_model *model = llama_get_model(ctx);

    auto get_meta = [&](const char *key) -> std::string {
        int32_t len = llama_model_meta_val_str(model, key, nullptr, 0);
        if (len <= 0) return "";
        std::vector<char> buf(len + 1);
        llama_model_meta_val_str(model, key, buf.data(), buf.size());
        return std::string(buf.data(), len);
    };

    std::string arch = get_meta("general.architecture");
    std::string name = get_meta("general.name");
    std::string tokenizer = get_meta("tokenizer.ggml.model");
    std::string chat_template = get_meta("tokenizer.chat_template");
    int vocab_size = llama_vocab_n_tokens(llama_model_get_vocab(model));
    std::string vocab_size_str = std::to_string(vocab_size);
    LOGI("ModelMetadata: arch=%s name=%s tokenizer=%s vocab=%d",
         arch.c_str(), name.c_str(), tokenizer.c_str(), vocab_size);

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(5, stringClass, env->NewStringUTF(""));
    env->SetObjectArrayElement(result, 0, env->NewStringUTF(arch.c_str()));
    env->SetObjectArrayElement(result, 1, env->NewStringUTF(name.c_str()));
    env->SetObjectArrayElement(result, 2, env->NewStringUTF(tokenizer.c_str()));
    env->SetObjectArrayElement(result, 3, env->NewStringUTF(vocab_size_str.c_str()));
    env->SetObjectArrayElement(result, 4, env->NewStringUTF(chat_template.c_str()));

    return result;
}

// =========================================================================
// Vision (Multimodal) Model loading / Unloading
// =========================================================================

JNIEXPORT jlong JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeLoadMmproj(
        JNIEnv *env, jobject /* this */, jstring mmproj_path) {
    (void)env; (void)mmproj_path;
    g_last_error = "Multimodal not supported in this build (llama.cpp b9045 removed old llava API)";
    LOGE("%s", g_last_error.c_str());
    return 0;
}

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeFreeMmproj(
        JNIEnv * /* env */, jobject /* this */, jlong ctx_ptr) {
    (void)ctx_ptr;
}

JNIEXPORT jlong JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeMakeImageEmbed(
        JNIEnv *env, jobject /* this */, jlong ctx_ptr, jbyteArray image_bytes) {
    (void)env; (void)ctx_ptr; (void)image_bytes;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeFreeImageEmbed(
        JNIEnv * /* env */, jobject /* this */, jlong embed_ptr) {
    (void)embed_ptr;
}

// =========================================================================
// Draft model (speculative decoding) — load / context / free
// =========================================================================

JNIEXPORT jlong JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeLoadDraftModel(
        JNIEnv *env, jobject /* this */,
        jstring model_path, jint n_gpu_layers) {

    // Backend must already be initialized by the main model load
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading draft model: %s (gpu_layers=%d)", path, n_gpu_layers);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = (int)n_gpu_layers;
    model_params.use_mmap  = true;
    model_params.use_mlock = false;

    llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!model) {
        g_last_error = "Failed to load draft model. Check path and GGUF validity.";
        LOGE("%s", g_last_error.c_str());
        return 0;
    }

    g_last_error.clear();
    LOGI("Draft model loaded successfully");
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeFreeDraftModel(
        JNIEnv * /* env */, jobject /* this */, jlong model_ptr) {
    if (model_ptr != 0) {
        llama_model_free(reinterpret_cast<llama_model *>(model_ptr));
        LOGI("Draft model freed");
    }
}

JNIEXPORT jlong JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeCreateDraftContext(
        JNIEnv * /* env */, jobject /* this */,
        jlong model_ptr, jint context_size, jint n_threads) {

    llama_model *model = reinterpret_cast<llama_model *>(model_ptr);
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = context_size;
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        g_last_error = "Failed to create draft context.";
        LOGE("%s", g_last_error.c_str());
        return 0;
    }

    {
        std::lock_guard<std::mutex> lock(g_inference_mutex);
        g_n_past[ctx] = 0;
    }

    g_last_error.clear();
    LOGI("Draft context created (ctx_size=%d, threads=%d)", context_size, n_threads);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeFreeDraftContext(
        JNIEnv * /* env */, jobject /* this */, jlong ctx_ptr) {
    if (ctx_ptr != 0) {
        llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
        {
            std::lock_guard<std::mutex> lock(g_inference_mutex);
            g_n_past.erase(ctx);
        }
        llama_free(ctx);
        LOGI("Draft context freed");
    }
}

// =========================================================================
// Model loading / unloading
// =========================================================================

JNIEXPORT jlong JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeLoadModel(
        JNIEnv *env, jobject /* this */,
        jstring model_path, jint n_gpu_layers) {

    std::call_once(g_backend_init_flag, []() {
        LOGI("Initializing llama backend (first model load)");
        llama_backend_init();
        LOGI("llama backend initialized");
        size_t n_backends = ggml_backend_reg_count();
        LOGI("Registered backends: %zu", n_backends);
        for (size_t i = 0; i < n_backends; ++i) {
            ggml_backend_reg_t reg = ggml_backend_reg_get(i);
            if (reg) LOGI("  backend[%zu]: %s", i, ggml_backend_reg_name(reg));
        }
    });

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model: %s (gpu_layers=%d)", path, n_gpu_layers);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = (int)n_gpu_layers;
    model_params.use_mmap = true;
    model_params.use_mlock = false;

    llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!model) {
        g_last_error = "Failed to load model from file. Check that the path exists and the file is a valid GGUF.";
        LOGE("%s", g_last_error.c_str());
        return 0;
    }

    g_last_error.clear();
    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeFreeModel(
        JNIEnv * /* env */, jobject /* this */, jlong model_ptr) {
    if (model_ptr != 0) {
        llama_model_free(reinterpret_cast<llama_model *>(model_ptr));
        LOGI("Model freed");
    }
}

// =========================================================================
// Context creation / destruction
// =========================================================================

JNIEXPORT jlong JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeCreateContext(
        JNIEnv * /* env */, jobject /* this */,
        jlong model_ptr, jint context_size, jint n_threads) {

    llama_model *model = reinterpret_cast<llama_model *>(model_ptr);
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx    = context_size;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        g_last_error = "Failed to create inference context. The model may require more memory than available.";
        LOGE("%s", g_last_error.c_str());
        return 0;
    }

    {
        std::lock_guard<std::mutex> lock(g_inference_mutex);
        g_n_past[ctx] = 0;
    }

    g_last_error.clear();
    LOGI("Context created (ctx_size=%d, threads=%d)", context_size, n_threads);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeFreeContext(
        JNIEnv * /* env */, jobject /* this */, jlong ctx_ptr) {
    if (ctx_ptr != 0) {
        llama_context* ctx = reinterpret_cast<llama_context *>(ctx_ptr);
        {
            std::lock_guard<std::mutex> lock(g_inference_mutex);
            g_n_past.erase(ctx);
        }
        llama_free(ctx);
        LOGI("Context freed");
    }
}

// =========================================================================
// Text generation (streaming via Kotlin callback)
// Supports both standard and speculative decoding (draft_ctx_ptr != 0).
// Mutex ensures only one inference runs at a time — safe for concurrent
// calls from both the chat UI and the Edge API service.
// =========================================================================

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGenerate(
        JNIEnv *env, jobject /* this */,
        jlong ctx_ptr, jstring j_prompt, jlong image_embed_ptr,
        jint max_tokens, jfloat temperature, jfloat top_p,
        jint top_k, jfloat repeat_penalty,
        jlong draft_ctx_ptr, jint n_draft, jint batch_size, jint ubatch_size,
        jobject callback) {

    std::lock_guard<std::mutex> lock(g_inference_mutex);

    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    const llama_model *model = llama_get_model(ctx);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    const char *prompt_cstr = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(j_prompt, prompt_cstr);

    // Resolve callback methods
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onStatsMethod = env->GetMethodID(callbackClass, "onStats", "(FJFIFLjava/lang/String;IIII)V");
    if (!onTokenMethod) {
        g_last_error = "Cannot find callback onToken method";
        LOGE("%s", g_last_error.c_str());
        return;
    }

    g_stop_generation.store(false, std::memory_order_release);
    (void)image_embed_ptr; // multimodal stubbed pending mtmd API migration

    // Tokenize prompt
    const int n_prompt_max = (int)prompt.size() + 256;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                  tokens.data(), n_prompt_max, true, true);
    if (n_tokens < 0) {
        n_tokens = -n_tokens;
        tokens.resize(n_tokens);
        n_tokens = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                  tokens.data(), n_tokens, true, true);
    } else {
        tokens.resize(n_tokens);
    }

    bool use_speculative = (draft_ctx_ptr != 0) && (n_draft > 0);
    LOGI("Prompt tokens=%d max_new=%d spec=%s n_draft=%d batch=%d ubatch=%d",
         n_tokens, (int)max_tokens, use_speculative ? "ON" : "OFF",
         (int)n_draft, (int)batch_size, (int)ubatch_size);

    auto t_start = std::chrono::steady_clock::now();

    // Clear target KV and process prompt (positions start at 0 after each call)
    llama_memory_clear(llama_get_memory(ctx), true);
    g_n_past[ctx] = 0;

    // Allocate batch large enough for prompt processing (chunked) and speculative verify pass
    int batch_cap = std::max((int)batch_size, (int)n_draft + 2);
    llama_batch batch = llama_batch_init(batch_cap, 0, 1);

    // Prefill: check stop flag before and between every chunk so Stop is responsive
    // even during the long prompt-ingestion phase (before any tokens are emitted).
    if (g_stop_generation.load(std::memory_order_acquire)) {
        LOGI("native: stop observed before prefill — aborting cleanly");
        llama_batch_free(batch);
        return;
    }
    for (int i = 0; i < n_tokens; i += batch_size) {
        if (g_stop_generation.load(std::memory_order_acquire)) {
            LOGI("native: stop observed during prefill chunk %d — aborting cleanly",
                 i / (int)batch_size);
            llama_batch_free(batch);
            return;
        }
        int n_eval = std::min((int)batch_size, n_tokens - i);
        batch_clear(batch);
        for (int j = 0; j < n_eval; j++) {
            bool is_last = (i + j == n_tokens - 1);
            batch_add(batch, tokens[i + j], i + j, {0}, is_last);
        }
        if (llama_decode(ctx, batch) != 0) {
            g_last_error = "Decode failed during prompt processing";
            LOGE("%s", g_last_error.c_str());
            llama_batch_free(batch);
            return;
        }
    }

    auto t_after_prompt = std::chrono::steady_clock::now();
    double prompt_secs = std::chrono::duration<double>(t_after_prompt - t_start).count();
    float prompt_tps = (prompt_secs > 0.0) ? (float)(n_tokens / prompt_secs) : 0.0f;

    // Target sampler chain
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, repeat_penalty, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(0));

    static const char* STOP_STRINGS[] = {
        "<|eot_id|>", "<|end_of_text|>", "<|im_end|>", "</s>", "<|end|>", "[/INST]", nullptr
    };

    int  n_decode         = 0;
    int  n_accepted_total = 0;
    int  n_drafted_total  = 0;
    long long ttft_ms     = -1LL;
    std::string accumulated;
    // Per-generation buffer of token bytes not yet confirmed as complete,
    // valid UTF-8 — see utf8_valid_prefix_len() / P28 Phase 2. Cleared at the
    // start (fresh std::string per nativeGenerate call) and flushed below
    // once the decode loop finishes.
    std::string pending_utf8;

    // Returns false when generation should stop (EOG or stop string matched)
    auto emit_token = [&](llama_token tok) -> bool {
        if (llama_vocab_is_eog(vocab, tok)) return false;
        if (ttft_ms < 0) {
            auto now = std::chrono::steady_clock::now();
            ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                          now - t_start).count();
        }
        char buf[256];
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (n <= 0) return true;
        std::string piece(buf, n);
        accumulated += piece;
        for (int si = 0; STOP_STRINGS[si]; ++si) {
            if (accumulated.find(STOP_STRINGS[si]) != std::string::npos) return false;
        }
        if (accumulated.size() > 64) accumulated = accumulated.substr(accumulated.size() - 32);

        // Only ever hand NewStringUTF a complete, valid UTF-8 prefix. A split
        // multi-byte character (e.g. Cyrillic/CJK/emoji token boundary) stays
        // in pending_utf8 until the next token completes it.
        pending_utf8 += piece;
        size_t valid_len = utf8_valid_prefix_len(pending_utf8);
        if (valid_len == 0 && pending_utf8.size() > 4) {
            // Bigger than any valid UTF-8 sequence and still unresolved —
            // this is a genuinely invalid lead byte, not just a truncation.
            // Drop it so the buffer can't stall forever waiting for bytes
            // that will never arrive.
            LOGW("Dropping invalid UTF-8 byte 0x%02x from token stream",
                 (unsigned char)pending_utf8[0]);
            pending_utf8.erase(0, 1);
            valid_len = utf8_valid_prefix_len(pending_utf8);
        }
        if (valid_len > 0) {
            std::string emitted = pending_utf8.substr(0, valid_len);
            pending_utf8.erase(0, valid_len);
            jstring j_tok = env->NewStringUTF(emitted.c_str());
            env->CallVoidMethod(callback, onTokenMethod, j_tok);
            env->DeleteLocalRef(j_tok);
            if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        }
        return true;
    };

    // Process prompt on draft context if speculative decoding is requested
    if (use_speculative) {
        llama_context *draft_ctx = reinterpret_cast<llama_context *>(draft_ctx_ptr);
        llama_memory_clear(llama_get_memory(draft_ctx), true);
        llama_batch dp = llama_batch_init(batch_size, 0, 1);
        bool draft_ok = true;
        for (int i = 0; i < n_tokens; i += batch_size) {
            if (g_stop_generation.load(std::memory_order_acquire)) {
                LOGI("native: stop observed during draft prefill chunk %d — aborting cleanly",
                     i / (int)batch_size);
                llama_batch_free(dp);
                llama_sampler_free(smpl);
                llama_batch_free(batch);
                return;
            }
            int n_eval = std::min((int)batch_size, n_tokens - i);
            batch_clear(dp);
            for (int j = 0; j < n_eval; j++) {
                bool is_last = (i + j == n_tokens - 1);
                batch_add(dp, tokens[i + j], i + j, {0}, is_last);
            }
            if (llama_decode(draft_ctx, dp) != 0) {
                LOGE("Draft prompt decode failed - falling back to standard decoding");
                draft_ok = false;
                break;
            }
        }
        llama_batch_free(dp);
        if (!draft_ok) use_speculative = false;
    }

    // Sample first token from target (after prompt logits at last batch position)
    llama_token current_token = llama_sampler_sample(smpl, ctx, batch.n_tokens - 1);

    // Check stop between prefill completion and decode start
    if (g_stop_generation.load(std::memory_order_acquire)) {
        LOGI("native: stop observed after prefill, before decode — aborting cleanly");
        llama_sampler_free(smpl);
        llama_batch_free(batch);
        return;
    }

    if (!use_speculative) {
        // ── Standard autoregressive loop ─────────────────────────────────────
        // Positions: prompt occupied [0, n_tokens-1]; decode starts at n_tokens
        int n_pos = n_tokens;

        while (n_decode < max_tokens && !g_stop_generation.load(std::memory_order_acquire)) {
            if (!emit_token(current_token)) break;
            n_decode++;

            batch_clear(batch);
            batch_add(batch, current_token, n_pos++, {0}, true);
            if (llama_decode(ctx, batch) != 0) {
                LOGE("Decode failed at token %d", n_decode);
                break;
            }
            current_token = llama_sampler_sample(smpl, ctx, 0);
        }

        g_n_past[ctx] = n_tokens + n_decode;

    } else {
        // ── Speculative decoding loop ─────────────────────────────────────────
        // Both target and draft KV caches use the same 0-based position space.
        // Prompt occupies [0, n_tokens-1]; decode tokens start at n_tokens.
        // kv_head = next write position in both KV caches (always in sync).

        llama_context *draft_ctx = reinterpret_cast<llama_context *>(draft_ctx_ptr);
        const llama_vocab *draft_vocab =
            llama_model_get_vocab(llama_get_model(draft_ctx));

        // Greedy draft sampler — speed over quality
        llama_sampler *draft_smpl = llama_sampler_chain_init(
            llama_sampler_chain_default_params());
        llama_sampler_chain_add(draft_smpl, llama_sampler_init_greedy());

        int kv_head = n_tokens;
        llama_batch db = llama_batch_init(1, 0, 1);

        while (n_decode < max_tokens && !g_stop_generation.load(std::memory_order_acquire)) {

            // ── 1. Draft phase ────────────────────────────────────────────
            // Feed current_token to draft at kv_head, then auto-regressively
            // sample up to n_draft proposal tokens.
            std::vector<llama_token> draft_tokens;
            draft_tokens.reserve(n_draft);

            {
                batch_clear(db);
                batch_add(db, current_token, kv_head, {0}, true);
                if (llama_decode(draft_ctx, db) != 0) { LOGE("Draft decode (seed) failed"); break; }
            }

            for (int i = 0; i < (int)n_draft; i++) {
                llama_token d = llama_sampler_sample(draft_smpl, draft_ctx, 0);
                if (llama_vocab_is_eog(draft_vocab, d)) break;
                draft_tokens.push_back(d);
                if (i < (int)n_draft - 1) {
                    batch_clear(db);
                    batch_add(db, d, kv_head + 1 + i, {0}, true);
                    if (llama_decode(draft_ctx, db) != 0) break;
                }
            }

            int nd = (int)draft_tokens.size();
            n_drafted_total += nd;

            // ── 2. Verification phase ─────────────────────────────────────
            // Submit [current_token, draft[0], …, draft[nd-1]] to target.
            // Request logits at every position so we can compare/sample.
            batch_clear(batch);
            batch_add(batch, current_token, kv_head, {0}, true);
            for (int i = 0; i < nd; i++) {
                batch_add(batch, draft_tokens[i], kv_head + 1 + i, {0}, true);
            }
            if (llama_decode(ctx, batch) != 0) {
                LOGE("Target verify decode failed");
                break;
            }

            // ── 3. Emit current_token (always accepted from target) ───────
            if (!emit_token(current_token)) {
                // Trim back to kv_head (nothing accepted this round)
                llama_memory_seq_rm(llama_get_memory(ctx),      0, kv_head, -1);
                llama_memory_seq_rm(llama_get_memory(draft_ctx), 0, kv_head, -1);
                break;
            }
            n_decode++;

            // ── 4. Accept / reject draft tokens ──────────────────────────
            // logit at batch index i predicts the token AFTER batch[i],
            // so sample(smpl, ctx, i) == expected draft_tokens[i].
            int  n_acc     = 0;
            bool rejected  = false;
            bool stop_now  = false;
            llama_token next_token = 0;

            for (int i = 0; i < nd && n_decode < max_tokens
                                   && !g_stop_generation.load(std::memory_order_acquire); i++) {
                llama_token target_pred = llama_sampler_sample(smpl, ctx, i);

                if (target_pred == draft_tokens[i]) {
                    // Accept
                    n_acc++;
                    if (!emit_token(draft_tokens[i])) {
                        // Stop signal inside an accepted draft token
                        stop_now = true;
                        break;
                    }
                    n_decode++;
                } else {
                    // Reject — target's correction becomes next current_token
                    next_token = target_pred;
                    rejected   = true;
                    break;
                }
            }

            if (!stop_now && !rejected) {
                // All nd draft tokens accepted — sample bonus token from
                // target logit at batch index nd (follows last draft token).
                next_token = llama_sampler_sample(smpl, ctx, nd);
                n_acc      = nd;
            }

            n_accepted_total += n_acc;

            // Trim both KV caches: discard rejected positions [kv_head+n_acc+1, ∞)
            // p1 = -1 means [p0, ∞) per the llama_memory_seq_rm contract.
            llama_memory_seq_rm(llama_get_memory(ctx),       0, kv_head + n_acc + 1, -1);
            llama_memory_seq_rm(llama_get_memory(draft_ctx), 0, kv_head + n_acc + 1, -1);
            kv_head += n_acc + 1;

            if (stop_now) break;
            current_token = next_token;
        }

        g_n_past[ctx] = kv_head;
        llama_sampler_free(draft_smpl);
        llama_batch_free(db);
    }

    // Flush any bytes still held back for UTF-8 completion. Reaching here
    // means the model won't emit more tokens (EOG/stop/max_tokens/decode
    // failure), so an incomplete sequence at this point is truncation, not a
    // transient boundary — emit the Unicode replacement character for it
    // rather than dropping it silently or ever handing partial bytes to
    // NewStringUTF.
    if (!pending_utf8.empty()) {
        size_t valid_len = utf8_valid_prefix_len(pending_utf8);
        std::string flush_str = (valid_len == pending_utf8.size())
            ? pending_utf8
            : std::string("\xEF\xBF\xBD"); // U+FFFD
        if (valid_len != pending_utf8.size()) {
            LOGW("Flushing %zu incomplete/invalid trailing UTF-8 byte(s) at end of generation",
                 pending_utf8.size() - valid_len);
        }
        jstring j_tok = env->NewStringUTF(flush_str.c_str());
        env->CallVoidMethod(callback, onTokenMethod, j_tok);
        env->DeleteLocalRef(j_tok);
        if (env->ExceptionCheck()) { env->ExceptionClear(); }
        pending_utf8.clear();
    }

    // ── Stats reporting ───────────────────────────────────────────────────────
    auto t_end = std::chrono::steady_clock::now();
    double decode_secs = std::chrono::duration<double>(t_end - t_after_prompt).count();
    float  tps         = (decode_secs > 0.0 && n_decode > 0)
                         ? (float)(n_decode / decode_secs) : 0.0f;
    if (ttft_ms < 0) ttft_ms = (long long)(prompt_secs * 1000.0);
    float accept_rate = (n_drafted_total > 0)
                        ? (float)n_accepted_total / (float)n_drafted_total : 0.0f;
    std::string backend = backend_names();

    LOGI("Generated %d tokens in %.2fs (%.1f TPS) | "
         "prompt_tps=%.1f ttft=%lldms | "
         "draft_accept=%.2f drafted=%d accepted=%d | backend=%s",
         n_decode, decode_secs, tps, prompt_tps, ttft_ms,
         accept_rate, n_drafted_total, n_accepted_total, backend.c_str());

    if (onStatsMethod) {
        jstring j_backend = env->NewStringUTF(backend.c_str());
        env->CallVoidMethod(callback, onStatsMethod,
                            (jfloat)tps,
                            (jlong)ttft_ms,
                            (jfloat)accept_rate,
                            (jint)n_decode,
                            (jfloat)prompt_tps,
                            j_backend,
                            (jint)n_drafted_total,
                            (jint)n_accepted_total,
                            (jint)llama_n_ctx(ctx),
                            (jint)g_n_past[ctx]);
        env->DeleteLocalRef(j_backend);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    g_last_error.clear();
    llama_sampler_free(smpl);
    llama_batch_free(batch);
}

// =========================================================================
// Stop generation
// =========================================================================

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeStopGeneration(
        JNIEnv * /* env */, jobject /* this */, jlong /* ctx_ptr */) {
    g_stop_generation.store(true, std::memory_order_release);
    LOGI("nativeStopGeneration: stop flag set (release)");
}

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeClearCache(
        JNIEnv * /* env */, jobject /* this */, jlong ctx_ptr) {
    if (ctx_ptr == 0) return;
    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    llama_memory_clear(llama_get_memory(ctx), true);
    {
        std::lock_guard<std::mutex> lock(g_inference_mutex);
        g_n_past[ctx] = 0;
    }
    LOGI("KV cache cleared");
}

JNIEXPORT jint JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGetTokenCount(
        JNIEnv *env, jobject /* this */, jlong model_ptr, jstring j_text) {

    llama_model *model = reinterpret_cast<llama_model *>(model_ptr);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    const char *text_cstr = env->GetStringUTFChars(j_text, nullptr);
    std::string text(text_cstr);
    env->ReleaseStringUTFChars(j_text, text_cstr);

    int n_prompt_max = text.size() + 256;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(vocab, text.c_str(), text.size(),
                                   tokens.data(), n_prompt_max, true, true);

    if (n_tokens < 0) {
        return -n_tokens;
    }
    return n_tokens;
}

// =========================================================================
// Model info queries
// =========================================================================

JNIEXPORT jint JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGetContextLength(
        JNIEnv * /* env */, jobject /* this */, jlong model_ptr) {
    llama_model *model = reinterpret_cast<llama_model *>(model_ptr);
    return static_cast<jint>(llama_model_n_ctx_train(model));
}

JNIEXPORT jint JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGetEmbeddingSize(
        JNIEnv * /* env */, jobject /* this */, jlong model_ptr) {
    llama_model *model = reinterpret_cast<llama_model *>(model_ptr);
    return static_cast<jint>(llama_model_n_embd(model));
}

JNIEXPORT jint JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGetVocabSize(
        JNIEnv * /* env */, jobject /* this */, jlong model_ptr) {
    llama_model *model = reinterpret_cast<llama_model *>(model_ptr);
    return static_cast<jint>(llama_vocab_n_tokens(llama_model_get_vocab(model)));
}

// =========================================================================
// File descriptor cleanup (for content:// URI models opened via /proc/self/fd)
// =========================================================================

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeCloseFd(
        JNIEnv * /* env */, jobject /* this */, jint fd) {
    if (fd >= 0) ::close(fd);
}

} // extern "C"
