#include "pitch_render.h"
#include <jni.h>
#include <new>
#include <vector>

namespace {
void throwJava(JNIEnv* env, const char* type, const char* message) {
    if (auto c = env->FindClass(type)) env->ThrowNew(c, message);
}
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_pocketgroove_PitchRender_nRenderPitch(JNIEnv* env, jclass, jfloatArray stereo,
                                              jint sampleRate, jdouble semitones,
                                              jboolean preserveFormants) {
    if (!stereo) {
        throwJava(env, "java/lang/IllegalArgumentException", "PCM is missing");
        return nullptr;
    }
    const jsize length = env->GetArrayLength(stereo);
    if (length <= 0 || length % 2 != 0) {
        throwJava(env, "java/lang/IllegalArgumentException", "PCM must be non-empty interleaved stereo");
        return nullptr;
    }
    std::vector<float> input(static_cast<size_t>(length));
    env->GetFloatArrayRegion(stereo, 0, length, input.data());
    if (env->ExceptionCheck()) return nullptr;

    try {
        auto output = groove::renderPitchExact(input, sampleRate, semitones, preserveFormants != 0);
        auto result = env->NewFloatArray(static_cast<jsize>(output.size()));
        if (!result) return nullptr;
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
        return result;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "피치를 렌더링할 메모리가 부족합니다.");
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalArgumentException", e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "피치를 렌더링하지 못했습니다.");
    }
    return nullptr;
}
