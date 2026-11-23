// Thin JNI over risk_core for app.family.igguard.RiskNative.
// Error convention: nativeLoad throws IllegalStateException on failure (there is no handle yet).
// nativeScore returns null on failure; the caller then reads nativeLastCode(handle)
// (2 = context too long, 1 = other failure) and nativeLastError(handle).
#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <new>
#include <string>
#include <vector>

#include "risk_core.h"

namespace {
struct Handle {
    risk_model* model;
    int last_code = 0;
    char last_err[256] = {0};
};

void throw_state(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, msg);
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_app_family_igguard_RiskNative_nativeLoad(JNIEnv* env, jobject, jstring path,
                                                                     jint threads) {
    if (path == nullptr) {
        throw_state(env, "no model path");
        return 0;
    }
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (utf == nullptr) return 0;
    // No C++ exception may cross the JNI boundary (that aborts the process): turn it into IllegalStateException.
    try {
        std::string p(utf);
        env->ReleaseStringUTFChars(path, utf);
        utf = nullptr;
        char err[256] = {0};
        risk_model* m = risk_load(p.c_str(), (int)threads, err, sizeof(err));
        if (m == nullptr) {
            throw_state(env, err[0] ? err : "model load failed");
            return 0;
        }
        Handle* h = new Handle();
        h->model = m;
        return (jlong)(intptr_t)h;
    } catch (const std::bad_alloc&) {
        if (utf != nullptr) env->ReleaseStringUTFChars(path, utf);
        throw_state(env, "out of memory while loading the model");
    } catch (...) {
        if (utf != nullptr) env->ReleaseStringUTFChars(path, utf);
        throw_state(env, "model load failed with a native exception");
    }
    return 0;
}

JNIEXPORT jfloatArray JNICALL Java_app_family_igguard_RiskNative_nativeScore(JNIEnv* env, jobject, jlong handle,
                                                                            jbyteArray prompt) {
    Handle* h = (Handle*)(intptr_t)handle;
    if (h == nullptr || prompt == nullptr) return nullptr;
    float logits[RISK_N_ANSWERS];
    h->last_err[0] = 0;
    // No C++ exception may cross the JNI boundary: report it as an ordinary failure (code 1), so the thread is
    // stored as "needs review" and the run goes on. An abort() inside llama.cpp cannot be caught here; the Kotlin
    // side marks the thread in flight for that case.
    try {
        jsize n = env->GetArrayLength(prompt);
        std::vector<char> text((size_t)n + 1, 0);
        env->GetByteArrayRegion(prompt, 0, n, reinterpret_cast<jbyte*>(text.data()));
        h->last_code = risk_score_n(h->model, text.data(), (int)n, logits, nullptr, 0, nullptr, h->last_err, sizeof(h->last_err));
    } catch (const std::bad_alloc&) {
        h->last_code = RISK_ERR_FAILED;
        std::snprintf(h->last_err, sizeof(h->last_err), "%s", "out of memory while scoring");
    } catch (...) {
        h->last_code = RISK_ERR_FAILED;
        std::snprintf(h->last_err, sizeof(h->last_err), "%s", "native exception while scoring");
    }
    if (h->last_code != RISK_OK) return nullptr;
    jfloatArray out = env->NewFloatArray(RISK_N_ANSWERS);
    if (out == nullptr) return nullptr;
    env->SetFloatArrayRegion(out, 0, RISK_N_ANSWERS, logits);
    return out;
}

JNIEXPORT jint JNICALL Java_app_family_igguard_RiskNative_nativeLastCode(JNIEnv*, jobject, jlong handle) {
    Handle* h = (Handle*)(intptr_t)handle;
    return h ? h->last_code : RISK_ERR_FAILED;
}

JNIEXPORT jstring JNICALL Java_app_family_igguard_RiskNative_nativeLastError(JNIEnv* env, jobject, jlong handle) {
    Handle* h = (Handle*)(intptr_t)handle;
    return env->NewStringUTF(h ? h->last_err : "no handle");
}

JNIEXPORT void JNICALL Java_app_family_igguard_RiskNative_nativeFree(JNIEnv*, jobject, jlong handle) {
    Handle* h = (Handle*)(intptr_t)handle;
    if (h == nullptr) return;
    risk_free(h->model);
    delete h;
}

}  // extern "C"
