#include "AutoMixEngine.h"

#include <jni.h>
#include <cstdint>
#include <new>

namespace {

using lastwave::audio::AutoMixEngine;
using lastwave::audio::AutoMixState;

AutoMixEngine* fromHandle(jlong handle) noexcept {
    return reinterpret_cast<AutoMixEngine*>(static_cast<std::uintptr_t>(handle));
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeCreate(
    JNIEnv*,
    jclass) {
    const auto ptr = reinterpret_cast<std::uintptr_t>(new (std::nothrow) AutoMixEngine());
    return static_cast<jlong>(ptr);
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeDestroy(
    JNIEnv*,
    jclass,
    jlong handle) {
    delete fromHandle(handle);
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeConfigure(
    JNIEnv*,
    jclass,
    jlong handle,
    jdouble sampleRate) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->configure(sampleRate);
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeSetEnabled(
    JNIEnv*,
    jclass,
    jlong handle,
    jboolean enabled) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->setEnabled(enabled == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeSetBarMode(
    JNIEnv*,
    jclass,
    jlong handle,
    jint bars) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->setBarMode(bars);
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeTriggerFadeNow(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->triggerFadeNow();
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeReset(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->reset();
    }
}

JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeWriteDeckBPcm(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject directBuffer,
    jint offsetBytes,
    jint frameCount) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr || directBuffer == nullptr || frameCount <= 0 || offsetBytes < 0) {
        return 0;
    }

    auto* bufferAddress = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (bufferAddress == nullptr) {
        return 0;
    }

    const auto* pcm = reinterpret_cast<const float*>(bufferAddress + offsetBytes);
    return static_cast<jint>(engine->writeDeckBPcm(pcm, static_cast<std::size_t>(frameCount)));
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeProcess(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject directBuffer,
    jint offsetBytes,
    jint frameCount) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr || directBuffer == nullptr || frameCount <= 0 || offsetBytes < 0) {
        return;
    }

    auto* bufferAddress = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (bufferAddress == nullptr) {
        return;
    }

    auto* pcm = reinterpret_cast<float*>(bufferAddress + offsetBytes);
    engine->process(pcm, static_cast<std::size_t>(frameCount));
}

JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetState(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? static_cast<jint>(engine->getState()) : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetTransitionProgress(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? engine->getTransitionProgress() : 0.0F;
}

JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetDeckBLeadingTrimFrames(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? static_cast<jlong>(engine->getDeckBLeadingTrimFrames()) : 0L;
}

JNIEXPORT jfloat JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetDeckABpm(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? engine->getDeckABpm() : 120.0F;
}

JNIEXPORT jfloat JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetDeckBBpm(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? engine->getDeckBBpm() : 120.0F;
}

} // extern "C"
