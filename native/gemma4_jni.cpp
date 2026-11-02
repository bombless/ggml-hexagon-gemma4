#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <ggml-backend.h>
#include <chat.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Gemma4JNI", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Gemma4JNI", __VA_ARGS__)
#ifndef GEMMA4_HEXAGON_BACKEND_NAME
#define GEMMA4_HEXAGON_BACKEND_NAME "cpu"
#endif

namespace {
struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * sampler = nullptr;
    common_chat_templates_ptr chatTemplates;
    std::atomic<bool> cancelled{false};
    std::mutex operation;
    std::string backend;
    int64_t modelLoadDurationMs = 0;
    ~Session() {
        if (sampler) llama_sampler_free(sampler);
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

void throwJava(JNIEnv * env, const char * message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}

std::string fromJava(JNIEnv * env, jstring value) {
    if (!value) return {};
    jclass charsetClass = env->FindClass("java/nio/charset/StandardCharsets");
    jfieldID utf8Field = env->GetStaticFieldID(charsetClass, "UTF_8", "Ljava/nio/charset/Charset;");
    jobject utf8 = env->GetStaticObjectField(charsetClass, utf8Field);
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID getBytes = env->GetMethodID(stringClass, "getBytes", "(Ljava/nio/charset/Charset;)[B");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, getBytes, utf8));
    const jsize length = bytes ? env->GetArrayLength(bytes) : 0;
    std::string result(static_cast<size_t>(length), '\0');
    if (length) env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(result.data()));
    if (bytes) env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(stringClass);
    env->DeleteLocalRef(utf8);
    env->DeleteLocalRef(charsetClass);
    return result;
}

jstring toJavaUtf8(JNIEnv * env, const char * bytes, size_t length) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(length));
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte *>(bytes));
    jclass charsetClass = env->FindClass("java/nio/charset/StandardCharsets");
    jfieldID utf8Field = env->GetStaticFieldID(charsetClass, "UTF_8", "Ljava/nio/charset/Charset;");
    jobject utf8 = env->GetStaticObjectField(charsetClass, utf8Field);
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/nio/charset/Charset;)V");
    jstring result = static_cast<jstring>(env->NewObject(stringClass, ctor, array, utf8));
    env->DeleteLocalRef(stringClass);
    env->DeleteLocalRef(utf8);
    env->DeleteLocalRef(charsetClass);
    env->DeleteLocalRef(array);
    return result;
}

void callback(JNIEnv * env, jobject listener, const char * method, const char * text) {
    jclass cls = env->GetObjectClass(listener);
    jmethodID id = env->GetMethodID(cls, method, "(Ljava/lang/String;)V");
    if (id) {
        jstring value = toJavaUtf8(env, text, strlen(text));
        env->CallVoidMethod(listener, id, value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(cls);
}

uint64_t tiledCacheSourceSignature(const std::string & path, uint64_t size) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return 0;
    uint64_t hash = 1469598103934665603ULL;
    auto feed = [&](const void * bytes, size_t count) {
        const auto * p = static_cast<const uint8_t *>(bytes);
        for (size_t i = 0; i < count; ++i) { hash ^= p[i]; hash *= 1099511628211ULL; }
    };
    feed(&size, sizeof(size));
    constexpr size_t SAMPLE = 64 * 1024;
    const size_t count = static_cast<size_t>(std::min<uint64_t>(size, SAMPLE));
    std::vector<char> sample(count);
    if (count && !file.read(sample.data(), static_cast<std::streamsize>(count))) return 0;
    if (count) feed(sample.data(), count);
    if (size > count) {
        file.clear();
        file.seekg(static_cast<std::streamoff>(size - count));
        if (!file.read(sample.data(), static_cast<std::streamsize>(count))) return 0;
        feed(sample.data(), count);
    }
    return hash;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_gemmaapp_engine_NativeLlamaSession_nativeCreate(
        JNIEnv * env, jobject, jstring modelPath, jint contextSize, jint threads, jstring backend) {
    const std::string path = fromJava(env, modelPath);
    const std::string requestedBackend = fromJava(env, backend);
    const std::string compiledBackend = GEMMA4_HEXAGON_BACKEND_NAME;
    if (requestedBackend != compiledBackend) {
        throwJava(env, ("Requested backend '" + requestedBackend + "' does not match this APK's compiled backend '" + compiledBackend + "'. Rebuild and install the matching AP/DSP artifact variant.").c_str());
        return 0;
    }
    if (path.empty() || contextSize < 256 || threads < 1) {
        throwJava(env, "Invalid model path, context size, or thread count");
        return 0;
    }
    try {
        using SteadyClock = std::chrono::steady_clock;
        const auto createStart = SteadyClock::now();
        auto logStage = [&](const char * stage, SteadyClock::time_point stageStart) {
            const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(SteadyClock::now() - stageStart).count();
            const auto total = std::chrono::duration_cast<std::chrono::milliseconds>(SteadyClock::now() - createStart).count();
            LOGI("LOAD_TIMING stage=%s stage_ms=%lld total_ms=%lld backend=%s", stage,
                 static_cast<long long>(elapsed), static_cast<long long>(total), compiledBackend.c_str());
        };
        LOGI("LOAD_TIMING stage=nativeCreate_begin model=%s bytes=%lld context=%d threads=%d backend=%s",
             path.c_str(), static_cast<long long>(std::filesystem::file_size(path)), contextSize, threads, compiledBackend.c_str());
        if (compiledBackend == "fastrpc-mempool") {
            const uint64_t sourceSize = std::filesystem::file_size(path);
            const uint64_t sourceSignature = tiledCacheSourceSignature(path, sourceSize);
            if (sourceSignature != 0) {
                const std::string cachePath = path + ".htpbin";
                const std::string sizeText = std::to_string(sourceSize);
                const std::string signatureText = std::to_string(sourceSignature);
                setenv("GGML_HEXAGON_TILED_CACHE", cachePath.c_str(), 1);
                setenv("GGML_HEXAGON_TILED_CACHE_SOURCE_SIZE", sizeText.c_str(), 1);
                setenv("GGML_HEXAGON_TILED_CACHE_SOURCE_SIGNATURE", signatureText.c_str(), 1);
                LOGI("[TILED_CACHE] candidate=%s source_signature=%llu present=%d", cachePath.c_str(),
                     static_cast<unsigned long long>(sourceSignature), std::filesystem::exists(cachePath));
            }
        }
        auto stageStart = SteadyClock::now();
        static std::once_flag backendInit;
        std::call_once(backendInit, [] { llama_backend_init(); });
        logStage("backend_init", stageStart);
        auto session = std::make_unique<Session>();
        session->backend = requestedBackend;
        auto modelParams = llama_model_default_params();
        std::vector<ggml_backend_dev_t> selectedDevices;
        if (compiledBackend == "cpu") {
            modelParams.n_gpu_layers = 0;
        } else {
            // llama_backend_init() populates ggml's static backend registry.
            // The pinned Hexagon backend registers itself there under "HTP".
            ggml_backend_reg_t htp = ggml_backend_reg_by_name("HTP");
            if (!htp) throw std::runtime_error("Hexagon backend was not registered in ggml; verify GGML_HEXAGON is enabled and the matching AP library is linked");
            const size_t deviceCount = ggml_backend_reg_dev_count(htp);
            if (deviceCount == 0) throw std::runtime_error("Hexagon backend registered but exposed no HTP devices; check DSP runtime, permissions, and matching v75 skeleton deployment");
            ggml_backend_dev_t device = ggml_backend_reg_dev_get(htp, 0);
            if (!device) throw std::runtime_error("Hexagon registry returned an invalid HTP device");
            LOGE("Selecting ggml backend %s device %s (%s)", ggml_backend_reg_name(htp),
                 ggml_backend_dev_name(device), compiledBackend.c_str());
            selectedDevices = { device, nullptr };
            modelParams.devices = selectedDevices.data();
            modelParams.n_gpu_layers = -1; // Request full layer offload; unsupported ops may still use CPU.
        }
        stageStart = SteadyClock::now();
        LOGI("LOAD_TIMING stage=model_load_begin");
        session->model = llama_model_load_from_file(path.c_str(), modelParams);
        session->modelLoadDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                SteadyClock::now() - stageStart).count();
        logStage("model_load", stageStart);
        if (!session->model) throw std::runtime_error("llama_model_load_from_file failed");
        if (!llama_model_chat_template(session->model, nullptr)) {
            throw std::runtime_error("GGUF does not contain tokenizer.chat_template; a model-specific template is required");
        }
        // Use llama-common's Jinja implementation. It also selects the
        // dedicated Gemma 4 renderer when the GGUF embeds the official template.
        stageStart = SteadyClock::now();
        session->chatTemplates = common_chat_templates_init(session->model, "");
        logStage("chat_template_init", stageStart);
        if (!session->chatTemplates) throw std::runtime_error("Failed to initialize GGUF chat templates");
        auto contextParams = llama_context_default_params();
        contextParams.n_ctx = static_cast<uint32_t>(contextSize);
        contextParams.n_batch = std::min<uint32_t>(contextSize, 512);
        contextParams.n_threads = threads;
        contextParams.n_threads_batch = threads;
        stageStart = SteadyClock::now();
        LOGI("LOAD_TIMING stage=context_init_begin n_ctx=%u n_batch=%u threads=%d", contextParams.n_ctx, contextParams.n_batch, threads);
        session->ctx = llama_init_from_model(session->model, contextParams);
        logStage("context_init", stageStart);
        if (!session->ctx) throw std::runtime_error("llama_init_from_model failed");
        auto samplerParams = llama_sampler_chain_default_params();
        session->sampler = llama_sampler_chain_init(samplerParams);
        if (!session->sampler) throw std::runtime_error("sampler initialization failed");
        llama_sampler_chain_add(session->sampler, llama_sampler_init_greedy());
        logStage("nativeCreate_total", createStart);
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception & e) {
        LOGE("create failed: %s", e.what());
        throwJava(env, e.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_gemmaapp_engine_NativeLlamaSession_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jstring promptValue, jint maxTokens, jobject listener) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (!s || !listener || maxTokens < 1 || maxTokens > 4096) {
        throwJava(env, "Invalid session, listener, or token limit"); return;
    }
    std::lock_guard<std::mutex> lock(s->operation);
    s->cancelled.store(false);
    const std::string userPrompt = fromJava(env, promptValue);
    // Render the GGUF's actual Jinja template instead of the limited legacy
    // llama_chat_apply_template() heuristic formatter.
    std::string prompt;
    try {
        common_chat_templates_inputs inputs;
        common_chat_msg message;
        message.role = "user";
        message.content = userPrompt;
        inputs.messages.push_back(std::move(message));
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        prompt = common_chat_templates_apply(s->chatTemplates.get(), inputs).prompt.str();
    } catch (const std::exception & e) {
        LOGE("chat template application failed: %s", e.what());
        callback(env, listener, "onError", e.what());
        return;
    }
    if (prompt.empty()) {
        callback(env, listener, "onError", "GGUF chat template produced an empty prompt");
        return;
    }
    llama_memory_clear(llama_get_memory(s->ctx), true);
    const auto * vocab = llama_model_get_vocab(s->model);
    std::vector<llama_token> tokens(std::max<size_t>(prompt.size() + 8, 32));
    int32_t count = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                   tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (count < 0) {
        tokens.resize(static_cast<size_t>(-count));
        count = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                               tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    }
    if (count <= 0) { callback(env, listener, "onError", "Prompt tokenization failed"); return; }
    tokens.resize(static_cast<size_t>(count));
    const uint32_t nCtx = llama_n_ctx(s->ctx);
    const uint32_t nBatch = llama_n_batch(s->ctx);
    LOGE("Prompt prepared: chars=%zu tokens=%d n_ctx=%u n_batch=%u backend=%s",
         prompt.size(), count, nCtx, nBatch, s->backend.c_str());
    if (static_cast<uint32_t>(count) > nCtx) {
        const std::string error = "Prompt is too long: " + std::to_string(count) +
                " tokens exceeds context size " + std::to_string(nCtx);
        callback(env, listener, "onError", error.c_str());
        return;
    }

    // llama_decode() accepts at most n_batch tokens per call. A full prompt
    // can be longer than that, so feed it in contiguous chunks while keeping
    // absolute KV positions and requesting logits only for the final token.
    int result = 0;
    for (int32_t offset = 0; offset < count; ) {
        const int32_t chunkSize = std::min<int32_t>(count - offset,
                static_cast<int32_t>(nBatch));
        llama_batch batch = llama_batch_init(chunkSize, 0, 1);
        if (!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) {
            llama_batch_free(batch);
            callback(env, listener, "onError", "Failed to allocate prompt decode batch");
            return;
        }
        for (int32_t i = 0; i < chunkSize; ++i) {
            const int32_t tokenIndex = offset + i;
            batch.token[i] = tokens[static_cast<size_t>(tokenIndex)];
            batch.pos[i] = tokenIndex;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (tokenIndex == count - 1);
        }
        batch.n_tokens = chunkSize;
        result = llama_decode(s->ctx, batch);
        llama_batch_free(batch);
        if (result != 0) {
            LOGE("Prompt decode failed: rc=%d offset=%d chunk=%d total=%d n_ctx=%u n_batch=%u backend=%s",
                 result, offset, chunkSize, count, nCtx, nBatch, s->backend.c_str());
            const std::string error = "Prompt decode failed (llama_decode rc=" +
                    std::to_string(result) + ", tokens=" + std::to_string(count) +
                    ", n_batch=" + std::to_string(nBatch) + ")";
            callback(env, listener, "onError", error.c_str());
            return;
        }
        offset += chunkSize;
    }

    std::vector<char> piece(4096);
    const int32_t generationLimit = std::min<int32_t>(maxTokens,
            static_cast<int32_t>(nCtx) - count);
    for (int i = 0; i < generationLimit && !s->cancelled.load(); ++i) {
        const llama_token token = llama_sampler_sample(s->sampler, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        int n = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
        if (n > 0) {
            std::string chunk(piece.data(), static_cast<size_t>(n));
            jclass cls = env->GetObjectClass(listener);
            jmethodID onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
            if (onToken) {
                jstring value = toJavaUtf8(env, chunk.data(), chunk.size());
                env->CallVoidMethod(listener, onToken, value);
                env->DeleteLocalRef(value);
            }
            env->DeleteLocalRef(cls);
            if (env->ExceptionCheck()) return;
        }
        llama_batch next = llama_batch_init(1, 0, 1);
        next.token[0] = token;
        next.pos[0] = count + i;
        next.n_seq_id[0] = 1;
        next.seq_id[0][0] = 0;
        next.logits[0] = 1;
        // llama_batch_init() allocates the arrays but leaves n_tokens at 0.
        // The prompt path sets this explicitly; the single-token generation
        // path must do the same or llama.cpp rejects the empty batch (rc=-1).
        next.n_tokens = 1;
        LOGI("Decode token batch: step=%d token=%d position=%d n_tokens=%d "
             "n_seq_id=%d seq_id=%d logits=%d n_ctx=%u n_batch=%u backend=%s",
             i, static_cast<int>(token), next.pos[0], next.n_tokens,
             next.n_seq_id[0], next.seq_id[0][0], next.logits[0],
             nCtx, nBatch, s->backend.c_str());
        result = llama_decode(s->ctx, next);
        llama_batch_free(next);
        if (result != 0) {
            LOGE("Token decode failed: rc=%d step=%d token=%d position=%d "
                 "n_tokens=1 n_ctx=%u n_batch=%u backend=%s",
                 result, i, static_cast<int>(token), count + i,
                 nCtx, nBatch, s->backend.c_str());
            const std::string error = "Token decode failed (llama_decode rc=" +
                    std::to_string(result) + ", position=" + std::to_string(count + i) + ")";
            callback(env, listener, "onError", error.c_str());
            return;
        }
    }
    callback(env, listener, "onComplete", s->cancelled.load() ? "cancelled" : "complete");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_gemmaapp_engine_NativeLlamaSession_nativeModelLoadDurationMs(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    return s ? static_cast<jlong>(s->modelLoadDurationMs) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_gemmaapp_engine_NativeLlamaSession_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s) s->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_gemmaapp_engine_NativeLlamaSession_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s) {
        std::lock_guard<std::mutex> lock(s->operation);
        delete s;
    }
}