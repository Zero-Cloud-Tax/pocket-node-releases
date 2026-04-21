#include <jni.h>
#include <android/log.h>
#include <string>
#include <atomic>
#include <thread>

#include "llama.h"
#include <vector>
#include <unordered_map>

#define TAG "PocketNode"

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
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global stop flag per context (simplified — one active generation at a time)
static std::atomic<bool> g_stop_generation{false};

// Track n_past per context manually because llama_get_kv_cache_token_count is absent
static std::unordered_map<llama_context*, int> g_n_past;

extern "C" {

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
    model_params.n_gpu_layers = n_gpu_layers;

    llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }

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
        LOGE("Failed to create context");
        return 0;
    }
    
    g_n_past[ctx] = 0;
    
    LOGI("Context created (ctx_size=%d, threads=%d)", context_size, n_threads);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeFreeContext(
        JNIEnv * /* env */, jobject /* this */, jlong ctx_ptr) {
    if (ctx_ptr != 0) {
        llama_context* ctx = reinterpret_cast<llama_context *>(ctx_ptr);
        g_n_past.erase(ctx);
        llama_free(ctx);
        LOGI("Context freed");
    }
}

// =========================================================================
// Text generation (streaming via Kotlin callback)
// =========================================================================

JNIEXPORT void JNICALL
Java_com_pocketnode_app_inference_LlamaInference_nativeGenerate(
        JNIEnv *env, jobject /* this */,
        jlong ctx_ptr, jstring j_prompt,
        jint max_tokens, jfloat temperature, jfloat top_p,
        jint top_k, jfloat repeat_penalty,
        jobject callback) {

    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    const llama_model *model = llama_get_model(ctx);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    const char *prompt_cstr = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(j_prompt, prompt_cstr);

    // Get the callback method
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID invokeMethod = env->GetMethodID(callbackClass, "onToken",
                                               "(Ljava/lang/String;)V");
    if (!invokeMethod) {
        LOGE("Cannot find callback invoke method");
        return;
    }

    g_stop_generation.store(false);

    // Tokenize the prompt
    const int n_prompt_max = prompt.size() + 256;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                                   tokens.data(), n_prompt_max, true, true);
    if (n_tokens < 0) {
        // Fallback if buffer is too small
        n_tokens = -n_tokens;
        tokens.resize(n_tokens);
        n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                                   tokens.data(), n_tokens, true, true);
    } else {
        tokens.resize(n_tokens);
    }

    LOGI("Prompt tokens: %d, generating up to %d tokens", n_tokens, max_tokens);

    int n_past = g_n_past[ctx];
    
    if (n_past + n_tokens > llama_n_ctx(ctx)) {
        LOGE("Context limit exceeded. Please clear context beforehand.");
        return;
    }

    // Process prompt in a single batch
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        batch_add(batch, tokens[i], n_past + i, {0}, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(ctx, batch) != 0) {
        LOGE("Decode failed for prompt");
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
        // Check for end of generation
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        // Convert token to text
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string token_text(buf, n);
            jstring j_token = env->NewStringUTF(token_text.c_str());
            env->CallVoidMethod(callback, invokeMethod, j_token);
            env->DeleteLocalRef(j_token);

            // Check for exceptions from callback (e.g. cancellation)
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
        }

        // Prepare next batch
        batch_clear(batch);
        batch_add(batch, new_token_id, n_past + n_tokens + n_decode, {0}, true);

        if (llama_decode(ctx, batch) != 0) {
            LOGE("Decode failed at token %d", n_decode);
            break;
        }

        new_token_id = llama_sampler_sample(smpl, ctx, 0);
        n_decode++;
    }

    LOGI("Generated %d tokens", n_decode);

    g_n_past[ctx] = n_past + n_tokens + n_decode;

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
    g_n_past[ctx] = 0;
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

} // extern "C"
