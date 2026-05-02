#include <jni.h>
#include <android/log.h>
#include <string>
#include <atomic>
#include <mutex>
#include <thread>
#include <unistd.h>
#include <algorithm>

#include "llama.h"
#include "ggml-backend.h"
#include "clip.h"
#include "llava.h"
#include <vector>
#include <unordered_map>
#include <sstream>

#define TAG "PocketNode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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

static bool has_gpu_backend() {
    if (!llama_supports_gpu_offload()) {
        return false;
    }

    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev && ggml_backend_dev_type(dev) != GGML_BACKEND_DEVICE_TYPE_CPU) {
            return true;
        }
    }
    return false;
}

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

extern "C" {

// =========================================================================
// Initialization
// =========================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad called, initializing llama backend");
    llama_backend_init();
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
// Backend query
// =========================================================================

JNIEXPORT jstring JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGetBackendName(
        JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF(backend_names().c_str());
}

// =========================================================================
// Vision (Multimodal) Model loading / Unloading
// =========================================================================

JNIEXPORT jlong JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeLoadMmproj(
        JNIEnv *env, jobject /* this */,
        jstring mmproj_path) {
    const char *path = env->GetStringUTFChars(mmproj_path, nullptr);
    LOGI("Loading multimodal projector: %s", path);

    clip_ctx *ctx = clip_model_load(path, 1);
    env->ReleaseStringUTFChars(mmproj_path, path);

    if (!ctx) {
        g_last_error = "Failed to load mmproj model.";
        LOGE("%s", g_last_error.c_str());
        return 0;
    }
    g_last_error.clear();
    LOGI("Multimodal projector loaded");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeFreeMmproj(
        JNIEnv * /* env */, jobject /* this */, jlong ctx_ptr) {
    if (ctx_ptr != 0) {
        clip_free(reinterpret_cast<clip_ctx *>(ctx_ptr));
        LOGI("Multimodal projector freed");
    }
}

JNIEXPORT jlong JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeMakeImageEmbed(
        JNIEnv *env, jobject /* this */, jlong ctx_ptr, jbyteArray image_bytes) {
    if (ctx_ptr == 0 || image_bytes == nullptr) return 0;
    clip_ctx *ctx = reinterpret_cast<clip_ctx *>(ctx_ptr);
    
    jsize len = env->GetArrayLength(image_bytes);
    jbyte *bytes = env->GetByteArrayElements(image_bytes, nullptr);
    
    llava_image_embed *embed = llava_image_embed_make_with_bytes(
            ctx, 4, reinterpret_cast<const unsigned char*>(bytes), len);
            
    env->ReleaseByteArrayElements(image_bytes, bytes, JNI_ABORT);
    
    if (!embed) {
        g_last_error = "Failed to process image embedding.";
        LOGE("%s", g_last_error.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(embed);
}

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeFreeImageEmbed(
        JNIEnv * /* env */, jobject /* this */, jlong embed_ptr) {
    if (embed_ptr != 0) {
        llava_image_embed_free(reinterpret_cast<llava_image_embed *>(embed_ptr));
    }
}

// =========================================================================
// Model loading / unloading
// =========================================================================

JNIEXPORT jlong JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeLoadModel(
        JNIEnv *env, jobject /* this */,
        jstring model_path, jint n_gpu_layers) {

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model: %s (gpu_layers=%d)", path, n_gpu_layers);

    llama_model_params model_params = llama_model_default_params();
    const int effective_gpu_layers = has_gpu_backend() ? std::max(0, (int)n_gpu_layers) : 0;
    if (n_gpu_layers > 0 && effective_gpu_layers == 0) {
        LOGI("GPU layers requested, but no GPU backend is registered; loading on CPU");
    }
    model_params.n_gpu_layers = effective_gpu_layers;
    model_params.use_mmap = true;
    model_params.use_mlock = false;

    llama_model *model = llama_load_model_from_file(path, model_params);
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
        llama_free_model(reinterpret_cast<llama_model *>(model_ptr));
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

    llama_context *ctx = llama_new_context_with_model(model, ctx_params);
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
// Mutex ensures only one inference runs at a time — safe for concurrent
// calls from both the chat UI and the Edge API service.
// =========================================================================

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGenerate(
        JNIEnv *env, jobject /* this */,
        jlong ctx_ptr, jstring j_prompt, jlong image_embed_ptr,
        jint max_tokens, jfloat temperature, jfloat top_p,
        jint top_k, jfloat repeat_penalty,
        jobject callback) {

    std::lock_guard<std::mutex> lock(g_inference_mutex);

    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    const llama_model *model = llama_get_model(ctx);

    const char *prompt_cstr = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(j_prompt, prompt_cstr);

    // Get the callback method
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID invokeMethod = env->GetMethodID(callbackClass, "onToken",
                                               "(Ljava/lang/String;)V");
    if (!invokeMethod) {
        g_last_error = "Cannot find callback onToken method";
        LOGE("%s", g_last_error.c_str());
        return;
    }

    g_stop_generation.store(false);

    // Tokenize the prompt
    const int n_prompt_max = prompt.size() + 256;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(model, prompt.c_str(), prompt.size(),
                                   tokens.data(), n_prompt_max, true, true);
    if (n_tokens < 0) {
        n_tokens = -n_tokens;
        tokens.resize(n_tokens);
        n_tokens = llama_tokenize(model, prompt.c_str(), prompt.size(),
                                   tokens.data(), n_tokens, true, true);
    } else {
        tokens.resize(n_tokens);
    }

    LOGI("Prompt tokens: %d, generating up to %d tokens", n_tokens, max_tokens);

    // Kotlin sends a complete prompt, including recent chat history, on each
    // request. Start from a clean KV cache so prior requests are not replayed
    // underneath that full prompt.
    llama_kv_cache_clear(ctx);
    int n_past = 0;
    g_n_past[ctx] = 0;

    if (image_embed_ptr != 0) {
        const llava_image_embed *embed = reinterpret_cast<const llava_image_embed *>(image_embed_ptr);
        int n_batch = 2048; 
        if (!llava_eval_image_embed(ctx, embed, n_batch, &n_past)) {
            g_last_error = "Failed to evaluate image embedding";
            LOGE("%s", g_last_error.c_str());
            return;
        }
        g_n_past[ctx] = n_past;
        LOGI("Evaluated image embed, n_past is now %d", n_past);
    }

    if (n_past + n_tokens > llama_n_ctx(ctx)) {
        // Context full — clear KV cache and restart
        llama_kv_cache_clear(ctx);
        g_n_past[ctx] = 0;
        n_past = 0;
        LOGI("Context limit reached — KV cache cleared");
    }

    // Process prompt in a single batch
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        batch_add(batch, tokens[i], n_past + i, {0}, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(ctx, batch) != 0) {
        g_last_error = "Decode failed during prompt processing";
        LOGE("%s", g_last_error.c_str());
        llama_batch_free(batch);
        return;
    }

    // Set up sampler chain
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(0));

    int n_decode = 0;
    llama_token new_token_id = llama_sampler_sample(smpl, ctx, batch.n_tokens - 1);

    while (n_decode < max_tokens && !g_stop_generation.load()) {
        if (llama_token_is_eog(model, new_token_id)) {
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(model, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string token_text(buf, n);
            jstring j_token = env->NewStringUTF(token_text.c_str());
            env->CallVoidMethod(callback, invokeMethod, j_token);
            env->DeleteLocalRef(j_token);

            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
        }

        batch_clear(batch);
        batch_add(batch, new_token_id, n_past + n_tokens + n_decode, {0}, true);

        if (llama_decode(ctx, batch) != 0) {
            g_last_error = "Decode failed during generation";
            LOGE("Decode failed at token %d", n_decode);
            break;
        }

        new_token_id = llama_sampler_sample(smpl, ctx, 0);
        n_decode++;
    }

    LOGI("Generated %d tokens", n_decode);

    g_n_past[ctx] = n_past + n_tokens + n_decode;
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
    g_stop_generation.store(true);
    LOGI("Generation stop requested");
}

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeClearCache(
        JNIEnv * /* env */, jobject /* this */, jlong ctx_ptr) {
    if (ctx_ptr == 0) return;
    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    llama_kv_cache_clear(ctx);
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

    const char *text_cstr = env->GetStringUTFChars(j_text, nullptr);
    std::string text(text_cstr);
    env->ReleaseStringUTFChars(j_text, text_cstr);

    int n_prompt_max = text.size() + 256;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(model, text.c_str(), text.size(),
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
    return static_cast<jint>(llama_n_ctx_train(model));
}

JNIEXPORT jint JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGetEmbeddingSize(
        JNIEnv * /* env */, jobject /* this */, jlong model_ptr) {
    llama_model *model = reinterpret_cast<llama_model *>(model_ptr);
    return static_cast<jint>(llama_n_embd(model));
}

JNIEXPORT jint JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGetVocabSize(
        JNIEnv * /* env */, jobject /* this */, jlong model_ptr) {
    llama_model *model = reinterpret_cast<llama_model *>(model_ptr);
    return static_cast<jint>(llama_n_vocab(model));
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
