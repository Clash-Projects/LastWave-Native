#include "AutoDJProcessor.h"

#include <jni.h>
#include <cstdint>
#include <new>

namespace {

using lastwave::AutoDJProcessor;
using lastwave::TrackLifecycleStage;
using lastwave::AutoDJDecision;

AutoDJProcessor* fromHandle(jlong handle) noexcept {
    return reinterpret_cast<AutoDJProcessor*>(static_cast<std::uintptr_t>(handle));
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAutoDJEngine_nativeCreate(
    JNIEnv*,
    jclass,
    jint maxRetries,
    jint refillThreshold) {
    const auto ptr = reinterpret_cast<std::uintptr_t>(new (std::nothrow) AutoDJProcessor(maxRetries, refillThreshold));
    return static_cast<jlong>(ptr);
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoDJEngine_nativeDestroy(
    JNIEnv*,
    jclass,
    jlong handle) {
    delete fromHandle(handle);
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoDJEngine_nativeRecordLatency(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong latencyMs,
    jboolean wasSuccess) {
    auto* processor = fromHandle(handle);
    if (processor != nullptr) {
        processor->recordResolutionLatency(static_cast<int64_t>(latencyMs), wasSuccess == JNI_TRUE);
    }
}

JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_NativeAutoDJEngine_nativeEvaluate(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong currentPositionMs,
    jlong durationMs,
    jboolean isPlaying,
    jlong transitionDurationMs,
    jint nextStage,
    jint retryCount,
    jint unplayedCount) {
    auto* processor = fromHandle(handle);
    if (processor == nullptr) {
        return static_cast<jint>(AutoDJDecision::DO_NOTHING);
    }
    const auto decision = processor->evaluate(
        static_cast<int64_t>(currentPositionMs),
        static_cast<int64_t>(durationMs),
        isPlaying == JNI_TRUE,
        static_cast<int64_t>(transitionDurationMs),
        static_cast<TrackLifecycleStage>(nextStage),
        static_cast<int32_t>(retryCount),
        static_cast<int32_t>(unplayedCount)
    );
    return static_cast<jint>(decision);
}

JNIEXPORT jfloatArray JNICALL
Java_com_lastwave_app_playback_NativeAutoDJEngine_nativeGetLatencyStats(
    JNIEnv* env,
    jclass,
    jlong handle,
    jlong transitionDurationMs) {
    auto* processor = fromHandle(handle);
    jfloat stats[3] = {2500.0f, 1000.0f, 15000.0f};
    if (processor != nullptr) {
        processor->getLatencyStats(stats[0], stats[1], stats[2], static_cast<int64_t>(transitionDurationMs));
    }
    jfloatArray result = env->NewFloatArray(3);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, 3, stats);
    }
    return result;
}

} // extern "C"
